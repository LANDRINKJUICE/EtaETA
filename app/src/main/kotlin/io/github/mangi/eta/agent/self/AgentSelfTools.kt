package io.github.mangi.eta.agent.self

import io.github.mangi.eta.BuildConfig
import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.agent.mcp.McpServerManager
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.data.model.McpAuthorizationType
import io.github.mangi.eta.data.model.McpProtocolMode
import io.github.mangi.eta.data.model.McpServerSetting
import io.github.mangi.eta.data.repository.McpServerRepository
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/**
 * Agent 对 Eta 自身的认知与控制。
 *
 * - [promptBlock] 在每次 run 组装系统提示时实时读取宿主状态，让模型知道自己运行在
 *   Eta 内、各工具组开关与 MCP 服务器的实际情况，而不是靠猜。
 * - eta_get_config / eta_set_config / eta_set_tool_enabled / eta_mcp_* 让模型直接
 *   读写自身配置，不再要求用户到设置页手动操作，也不走 GUI 点击。
 * - eta_prefs_list / eta_prefs_get / eta_prefs_set 覆盖工具组开关以外的设置域
 *   （网页搜索与读取、语音与朗读、Linux 工具环境），键表与取值规则见 [AgentSelfPrefsSpec]，
 *   执行侧在 [AgentSelfPrefsTools]，写入落在设置页同一份存储上。
 *
 * 写入范围有白名单：布尔开关仅限 [Prefs.Keys.LOCAL_AGENT_KEYS]（Eta Runtime 本地
 * 裁决、不依赖 LSPosed 的键）；单工具开关走 [Prefs.setToolEnabled] 既有实现；
 * MCP 走 [McpServerRepository] 既有实现，bearer token 仍由 SecretStore 保管。
 */
internal object AgentSelfTools {

    val TOOL_NAMES = setOf(
        "eta_get_config",
        "eta_set_config",
        "eta_set_tool_enabled",
        "eta_mcp_list",
        "eta_mcp_upsert",
        "eta_mcp_delete",
        "eta_mcp_refresh",
    ) + AgentSelfPrefsTools.TOOL_NAMES + AgentModelTools.TOOL_NAMES + AgentSkillAdminTools.TOOL_NAMES

    private val SWITCH_LABELS = linkedMapOf(
        Prefs.Keys.AGENT_TERMINAL_TOOLS to "terminal/file",
        Prefs.Keys.AGENT_BROWSER_TOOLS to "browser/web",
        Prefs.Keys.AGENT_DEVICE_DIRECT_TOOLS to "device_direct",
        Prefs.Keys.AGENT_DEVICE_SENSITIVE_READ_TOOLS to "sensitive_read",
        Prefs.Keys.AGENT_DEVICE_SENSITIVE_ACTION_TOOLS to "sensitive_action",
        Prefs.Keys.AGENT_SUBAGENT_ENABLED to "subagent",
        Prefs.Keys.AGENT_VIRTUAL_DISPLAY_ENABLED to "virtual_display",
        Prefs.Keys.AGENT_THINKING_ENABLED to "thinking",
        Prefs.Keys.AGENT_AUTO_COMPACTION_ENABLED to "auto_compaction",
    )

    fun execute(name: String, args: JSONObject, context: android.content.Context): String = when {
        name in AgentModelTools.TOOL_NAMES -> AgentModelTools.execute(name, args)
        name in AgentSkillAdminTools.TOOL_NAMES -> AgentSkillAdminTools.execute(name, args, context)
        name in AgentSelfPrefsTools.TOOL_NAMES -> AgentSelfPrefsTools.execute(name, args, context)
        else -> executeSelf(name, args)
    }

    private fun executeSelf(name: String, args: JSONObject): String = try {
        when (name) {
            "eta_get_config" -> getConfig()
            "eta_set_config" -> setConfig(args)
            "eta_set_tool_enabled" -> setToolEnabled(args)
            "eta_mcp_list" -> mcpList()
            "eta_mcp_upsert" -> mcpUpsert(args)
            "eta_mcp_delete" -> mcpDelete(args)
            "eta_mcp_refresh" -> mcpRefresh(args)
            else -> error("未知自身配置工具：$name")
        }.toString()
    } catch (failure: Exception) {
        if (failure is InterruptedException || failure is kotlinx.coroutines.CancellationException) throw failure
        JSONObject()
            .put("ok", false)
            .put("code", "SELF_TOOL_ERROR")
            .put("message", failure.message ?: failure.javaClass.simpleName)
            .toString()
    }

    /** 系统提示注入用；任何读取失败都降级为简短说明，不影响 run 启动。 */
    fun promptBlock(): String = buildString {
        append("你正运行在 Eta（Android 上的 Agent 宿主应用）内，以下自身状态在每次运行开始时实时读取，不是历史记忆：")
        appendLine()
        appendLine("- Eta 版本：${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        val switches = SWITCH_LABELS.entries.joinToString(", ") { (key, label) ->
            "$label=${if (switchState(key)) "on" else "off"}"
        }
        appendLine("- 工具组开关：$switches")
        val disabled = runCatching { Prefs.disabledTools() }.getOrDefault(emptySet())
        appendLine("- 被单独禁用的工具：${if (disabled.isEmpty()) "无" else disabled.sorted().joinToString(", ")}")
        val servers = runCatching { runBlocking { McpServerRepository.servers() } }
            .getOrDefault(emptyList())
        appendLine(
            "- MCP 服务器：" + if (servers.isEmpty()) {
                "无"
            } else {
                servers.joinToString("; ") { server ->
                    "${server.name}(${if (server.enabled) "on" else "off"}, ${server.tools.size} tools)"
                }
            },
        )
        append(
            "你可以用 eta_get_config / eta_set_config / eta_set_tool_enabled 直接读取和修改上述配置，" +
                "用 eta_mcp_list / eta_mcp_upsert / eta_mcp_delete / eta_mcp_refresh 管理 MCP 服务器；" +
                "网页搜索与读取、语音与朗读、Linux 工具环境这三类设置用 eta_prefs_list 查看可管理键，" +
                "eta_prefs_get 读取单个键，eta_prefs_set 写入（白名单制、类型校验、写后读回验证，凭据读取脱敏）；" +
                "若这些工具不在你的可用工具列表中，说明当前会话（如子智能体或角色会话）无权使用，不要尝试调用。" +
                "关闭开关立即生效，开启从下一轮运行生效；不需要用户去设置页手动操作，也不要用 GUI 点击设置页代替。" +
                "开关为 off 的工具组即使声明也无法执行；需要开启时经用户同意后用 eta_set_config 打开。",
        )
    }

    private fun switchState(key: String): Boolean = runCatching {
        if (Prefs.isGroupSwitchKey(key)) Prefs.isAgentGroupEnabled(key) else Prefs.isEnabled(key)
    }.getOrDefault(true)

    private fun getConfig(): JSONObject {
        val switches = JSONObject()
        SWITCH_LABELS.keys.forEach { key ->
            switches.put(key, switchState(key))
        }
        return JSONObject()
            .put("ok", true)
            .put("version_name", BuildConfig.VERSION_NAME)
            .put("version_code", BuildConfig.VERSION_CODE)
            .put("switches", switches)
            .put("writable_switches", JSONArray(Prefs.Keys.LOCAL_AGENT_KEYS.toList()))
            .put("disabled_tools", JSONArray(Prefs.disabledTools().sorted()))
            .put("mcp_servers", mcpServersJson())
    }

    private fun setConfig(args: JSONObject): JSONObject {
        val key = args.getString("key")
        if (key !in Prefs.Keys.LOCAL_AGENT_KEYS) {
            return JSONObject()
                .put("ok", false)
                .put("code", "SELF_CONFIG_KEY_NOT_WRITABLE")
                .put("message", "该键不在可写白名单内；可写键见 eta_get_config 的 writable_switches")
        }
        val value = args.getBoolean("value")
        // 组开关必须走禁用工具集这一权威模型，legacy 布尔直接写会被后续操作刷回。
        if (Prefs.isGroupSwitchKey(key)) {
            val committed = runCatching {
                Prefs.setAgentGroupEnabled(key, value, EtaApp.serviceInstance)
            }.getOrDefault(false)
            return JSONObject()
                .put("ok", committed)
                .put("key", key)
                .put("value", value)
                .put("verified", committed && Prefs.isAgentGroupEnabled(key) == value)
                .put("note", "组内全部工具已同步；关闭立即生效，开启从下一轮运行生效")
        }
        val preferences = Prefs.localAgentPreferences()
            ?: return JSONObject()
                .put("ok", false)
                .put("code", "SELF_CONFIG_UNAVAILABLE")
                .put("message", "本地 Agent 配置尚未初始化")
        val committed = runCatching {
            preferences.edit().putBoolean(key, value).commit()
        }.getOrDefault(false)
        if (committed) {
            runCatching { Prefs.reconcileAgentPreferences(EtaApp.serviceInstance) }
        }
        val verified = committed && Prefs.isEnabled(key) == value
        return JSONObject()
            .put("ok", verified)
            .put("key", key)
            .put("value", value)
            .put("verified", verified)
            .put("note", "关闭立即生效，开启从下一轮运行生效")
    }

    private fun setToolEnabled(args: JSONObject): JSONObject {
        val tool = args.getString("tool").trim()
        val enabled = args.getBoolean("enabled")
        if (io.github.mangi.eta.agent.tool.AgentToolRequirements.find(tool) == null) {
            return JSONObject()
                .put("ok", false)
                .put("code", "UNKNOWN_TOOL")
                .put("message", "未登记的工具名：$tool")
        }
        val committed = runCatching {
            Prefs.setToolEnabled(tool, enabled, EtaApp.serviceInstance)
        }.getOrDefault(false)
        val verified = committed && Prefs.isToolEnabled(tool) == enabled
        return JSONObject()
            .put("ok", verified)
            .put("tool", tool)
            .put("enabled", enabled)
            .put("verified", verified)
    }

    private fun mcpServersJson(): JSONArray {
        val servers = runCatching { runBlocking { McpServerRepository.servers() } }
            .getOrDefault(emptyList())
        return JSONArray().apply {
            servers.forEach { server ->
                put(
                    JSONObject()
                        .put("id", server.id)
                        .put("name", server.name)
                        .put("url", server.url)
                        .put("enabled", server.enabled)
                        .put("protocol_mode", server.protocolMode)
                        .put("authorization_type", server.authorizationType)
                        .put("tool_count", server.tools.size)
                        .put("enabled_tool_names", JSONArray(server.enabledToolNames.sorted()))
                        .put("last_refreshed_at", server.lastRefreshedAt ?: JSONObject.NULL),
                )
            }
        }
    }

    private fun mcpList(): JSONObject = JSONObject()
        .put("ok", true)
        .put("servers", mcpServersJson())

    private fun mcpUpsert(args: JSONObject): JSONObject {
        val id = args.optString("id").trim()
        val existing = if (id.isNotEmpty()) {
            runBlocking { McpServerRepository.serverById(id) }
                ?: error("MCP 服务器不存在：$id")
        } else {
            null
        }
        val url = args.optString("url").trim().ifEmpty { existing?.url.orEmpty() }
        require(url.startsWith("https://") || url.startsWith("http://")) {
            "MCP 服务器 url 必须是 http(s) 地址"
        }
        val protocolMode = args.optString("protocol_mode").trim()
            .ifEmpty { existing?.protocolMode ?: McpProtocolMode.AUTO }
        require(
            protocolMode in setOf(McpProtocolMode.AUTO, McpProtocolMode.LATEST, McpProtocolMode.LEGACY),
        ) { "protocol_mode 仅支持 auto / ${McpProtocolMode.LATEST} / ${McpProtocolMode.LEGACY}" }
        // bearer_token 三态语义（避免「顺手传空串」摧毁已有鉴权）：
        //   - 非空字符串                → 设为 BEARER 并写入新凭据
        //   - 空串 / null / 未提供       → 不修改鉴权，沿用 existing / 现有凭据
        //   - clear_bearer_token=true   → 显式清空（唯一能清掉鉴权的路径）
        // org.json 对 JSONObject.NULL 的 optString 会返回字面量 "null"，用 isNull 挡掉，
        // 绝不能把它当成有效 token 存进 SecretStore。
        val clearBearer = args.optBoolean("clear_bearer_token", false)
        val hasBearerField = args.has("bearer_token") && !args.isNull("bearer_token")
        val newToken = if (hasBearerField) args.optString("bearer_token", "").trim() else ""
        val setNewBearer = hasBearerField && newToken.isNotEmpty()
        require(!(clearBearer && hasBearerField)) {
            "clear_bearer_token 与 bearer_token 不能同时给出"
        }
        val authorizationType = when {
            clearBearer -> McpAuthorizationType.NONE
            setNewBearer -> McpAuthorizationType.BEARER
            else -> existing?.authorizationType ?: McpAuthorizationType.NONE
        }
        var server = (existing ?: McpServerSetting(id = "", name = "", url = url)).copy(
            name = args.optString("name").trim().ifEmpty { existing?.name ?: url },
            url = url,
            enabled = if (args.has("enabled")) args.getBoolean("enabled") else existing?.enabled ?: true,
            protocolMode = protocolMode,
            authorizationType = authorizationType,
        )
        var discoveryError: String? = null
        if (args.optBoolean("discover", true)) {
            server = try {
                // 只有「显式设新 token」用新值；显式清空不带凭据；其余回退现有凭据。
                // 首次成功发现的默认全开在 McpServerManager.discover 内统一处理。
                val discoveryToken: String? = when {
                    setNewBearer -> newToken
                    clearBearer -> null
                    else -> existing?.let { McpServerRepository.bearerToken(it.id) }
                }
                McpServerManager.discover(server, discoveryToken)
            } catch (failure: Throwable) {
                discoveryError = failure.message ?: failure.javaClass.simpleName
                server
            }
        }
        val saved = runBlocking {
            if (existing == null) {
                // 新增：只有设了新 token 才写凭据，否则不带 secret。
                McpServerRepository.add(server, if (setNewBearer) newToken else "")
            } else {
                // 更新：传 null = 完全不碰 secret（保留现有凭据）；
                // 只有设新 token / 显式清空才动 secret。
                val tokenArg: String? = when {
                    setNewBearer -> newToken
                    clearBearer -> ""
                    else -> null
                }
                McpServerRepository.update(server, tokenArg)
                McpServerRepository.serverById(existing.id)
            }
        }
        return JSONObject()
            .put("ok", true)
            .put("id", saved?.id ?: server.id)
            .put("name", server.name)
            .put("enabled", server.enabled)
            .put("tool_count", server.tools.size)
            .put("authorization_type", server.authorizationType)
            .put("enabled_tool_names", JSONArray(server.enabledToolNames.sorted()))
            .put("discovery_error", discoveryError ?: JSONObject.NULL)
    }

    private fun mcpDelete(args: JSONObject): JSONObject {
        val id = args.getString("id")
        runBlocking { McpServerRepository.delete(id) }
        return JSONObject().put("ok", true).put("deleted", id)
    }

    private fun mcpRefresh(args: JSONObject): JSONObject {
        // 首次发现的默认全开在 McpServerManager.discover 内统一处理；这里不覆盖用户手动全关的选择。
        val refreshed = runBlocking { McpServerManager.refresh(args.getString("id")) }
        return JSONObject()
            .put("ok", true)
            .put("id", refreshed.id)
            .put("name", refreshed.name)
            .put("tool_count", refreshed.tools.size)
            .put("enabled_tool_names", JSONArray(refreshed.enabledToolNames.sorted()))
    }
}
