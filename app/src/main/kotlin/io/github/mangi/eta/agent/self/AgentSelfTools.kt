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
    )

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

    fun execute(name: String, args: JSONObject): String = try {
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
    } catch (failure: Throwable) {
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
            "$label=${if (runCatching { Prefs.isEnabled(key) }.getOrDefault(true)) "on" else "off"}"
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
                    "${server.name}(${if (server.enabled) "on" else "off"}, ${server.activeTools.size} tools)"
                }
            },
        )
        append(
            "你可以用 eta_get_config / eta_set_config / eta_set_tool_enabled 直接读取和修改上述配置，" +
                "用 eta_mcp_list / eta_mcp_upsert / eta_mcp_delete / eta_mcp_refresh 管理 MCP 服务器；" +
                "修改即时生效，不需要用户去设置页手动操作，也不要用 GUI 点击设置页代替。" +
                "开关为 off 的工具组即使声明也无法执行；需要开启时经用户同意后用 eta_set_config 打开。",
        )
    }

    private fun getConfig(): JSONObject {
        val switches = JSONObject()
        SWITCH_LABELS.keys.forEach { key ->
            switches.put(key, runCatching { Prefs.isEnabled(key) }.getOrDefault(true))
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
        val preferences = Prefs.localAgentPreferences()
            ?: return JSONObject()
                .put("ok", false)
                .put("code", "SELF_CONFIG_UNAVAILABLE")
                .put("message", "本地 Agent 配置尚未初始化")
        val committed = runCatching {
            preferences.edit().putBoolean(key, value).commit()
        }.getOrDefault(false)
        val verified = committed && Prefs.isEnabled(key) == value
        return JSONObject()
            .put("ok", verified)
            .put("key", key)
            .put("value", value)
            .put("verified", verified)
    }

    private fun setToolEnabled(args: JSONObject): JSONObject {
        val tool = args.getString("tool").trim()
        val enabled = args.getBoolean("enabled")
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
        val bearerToken = args.optString("bearer_token")
        var server = (existing ?: McpServerSetting(id = "", name = "", url = url)).copy(
            name = args.optString("name").trim().ifEmpty { existing?.name ?: url },
            url = url,
            enabled = if (args.has("enabled")) args.getBoolean("enabled") else existing?.enabled ?: true,
            protocolMode = protocolMode,
            authorizationType = when {
                bearerToken.isNotEmpty() -> McpAuthorizationType.BEARER
                else -> existing?.authorizationType ?: McpAuthorizationType.NONE
            },
        )
        var discoveryError: String? = null
        if (args.optBoolean("discover", true)) {
            server = try {
                val discovered = McpServerManager.discover(
                    server,
                    bearerToken.ifEmpty { existing?.let { McpServerRepository.bearerToken(it.id) } },
                )
                // 新服务器默认启用发现的全部工具；已有服务器保留原有选择。
                if (existing == null) {
                    discovered.copy(enabledToolNames = discovered.tools.mapTo(linkedSetOf()) { it.name })
                } else {
                    discovered
                }
            } catch (failure: Throwable) {
                discoveryError = failure.message ?: failure.javaClass.simpleName
                server
            }
        }
        val saved = runBlocking {
            if (existing == null) {
                McpServerRepository.add(server, bearerToken)
            } else {
                McpServerRepository.update(server, bearerToken.ifEmpty { null })
                McpServerRepository.serverById(existing.id)
            }
        }
        return JSONObject()
            .put("ok", true)
            .put("id", saved?.id ?: server.id)
            .put("name", server.name)
            .put("enabled", server.enabled)
            .put("tool_count", server.tools.size)
            .put("enabled_tool_names", JSONArray(server.enabledToolNames.sorted()))
            .put("discovery_error", discoveryError ?: JSONObject.NULL)
    }

    private fun mcpDelete(args: JSONObject): JSONObject {
        val id = args.getString("id")
        runBlocking { McpServerRepository.delete(id) }
        return JSONObject().put("ok", true).put("deleted", id)
    }

    private fun mcpRefresh(args: JSONObject): JSONObject {
        val refreshed = runBlocking { McpServerManager.refresh(args.getString("id")) }
        return JSONObject()
            .put("ok", true)
            .put("id", refreshed.id)
            .put("name", refreshed.name)
            .put("tool_count", refreshed.tools.size)
            .put("enabled_tool_names", JSONArray(refreshed.enabledToolNames.sorted()))
    }
}
