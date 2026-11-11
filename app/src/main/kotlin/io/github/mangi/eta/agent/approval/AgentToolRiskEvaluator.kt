package io.github.mangi.eta.agent.approval

import io.github.mangi.eta.agent.self.AgentSelfPrefsRules
import io.github.mangi.eta.agent.self.AgentSelfPrefsSpec
import java.util.Locale
import org.json.JSONObject

/**
 * 工具调用的静态风险判定入口。
 *
 * 纯函数：不读取运行期状态、不执行任何 IO，便于单元测试与复用。执行侧只在拿到
 * [ToolRiskVerdict.NeedsConfirm] 时挂起等待用户确认。
 */
internal object AgentToolRiskEvaluator {
    /** 需要解析 command 字段的终端类工具。 */
    private val COMMAND_TOOLS = setOf("terminal", "terminal_job", "terminal_daemon")

    /** 会把 path 当作写入目标的文件工具。 */
    private val FILE_WRITE_TOOLS = setOf("write_file", "edit_file")

    /**
     * Agent 改写 Eta 自身运行配置的自管理工具（走 [io.github.mangi.eta.agent.self.AgentSelfTools]
     * 分发，但闸门在 [io.github.mangi.eta.agent.tool.AgentLocalTools] 分发之前统一生效）。
     *
     * 这些工具不属于"系统操作"，却能把请求改发到别的端点、换上别人的凭据、关掉工具组，
     * 所以在同一张确认卡上过一遍；只读的 eta_get_config / *_list / eta_mcp_refresh 不在这里。
     */
    private val SELF_CONFIG_TOOLS = setOf(
        "eta_set_config",
        "eta_set_tool_enabled",
        "eta_provider_upsert",
        "eta_provider_delete",
        "eta_model_upsert",
        "eta_model_delete",
        "eta_model_select",
        "eta_mcp_upsert",
        "eta_mcp_delete",
        "eta_skill_delete",
    )

    private val SYSTEM_PACKAGE_PREFIXES = listOf(
        "android",
        "com.android.",
        "com.oplus.",
        "com.coloros.",
        "com.oppo.",
        "com.heytap.",
        "com.google.android.",
        "com.qualcomm.",
        "com.mediatek.",
        "com.miui.",
        "com.xiaomi.",
    )

    /**
     * @param defaultCwd 工具未显式给 cwd 时使用的默认工作目录（本次运行的默认工作区）；
     *   为 null 时相对路径一律按"无法静态确认"处理。
     */
    fun evaluate(
        toolName: String,
        args: JSONObject,
        defaultCwd: String? = null,
    ): ToolRiskVerdict {
        val name = toolName.trim()
        if (name.isEmpty()) return ToolRiskVerdict.Safe
        val explicitCwd = args.optString("cwd").takeIf { it.isNotBlank() }
        val findings = when (name) {
            in COMMAND_TOOLS -> analyzeCommand(
                command = args.optString("command"),
                // 持久终端会话里的 cwd 与变量会跨调用累计，无法静态确认的判定需要升级。
                sessionScoped = args.optString("session_id").isNotBlank() ||
                    args.optString("action").equals("open", ignoreCase = true),
                initialCwd = explicitCwd ?: defaultCwd,
            )
            in FILE_WRITE_TOOLS -> analyzeFileWrite(
                path = args.optString("path"),
                cwd = explicitCwd ?: defaultCwd,
            )
            "set_setting" -> analyzeSetting(args)
            "app_state_control" -> analyzeAppState(args)
            "schedule_task" -> analyzeScheduleTask(args)
            "eta_prefs_set" -> analyzePrefsSet(args)
            in SELF_CONFIG_TOOLS -> analyzeSelfConfig(name, args)
            else -> emptyList()
        }
        if (findings.isEmpty()) return ToolRiskVerdict.Safe
        return ToolRiskVerdict.NeedsConfirm(
            toolName = name,
            subject = subjectFor(name, args, findings),
            findings = findings,
        )
    }

    private fun analyzeCommand(
        command: String,
        sessionScoped: Boolean,
        initialCwd: String?,
    ): List<RiskFinding> {
        if (command.isBlank()) return emptyList()
        return ShellCommandAnalyzer.analyze(
            command,
            ShellCommandAnalyzer.Options(sessionScoped = sessionScoped, initialCwd = initialCwd),
        )
    }

    private fun analyzeFileWrite(path: String, cwd: String?): List<RiskFinding> {
        val target = path.trim()
        if (target.isEmpty()) return emptyList()
        val resolved = fileWriteTarget(target, cwd)
        if (resolved == null) {
            return listOf(
                RiskFinding(
                    ToolRiskCategory.UNRESOLVED_TARGET,
                    ToolRiskLevel.MEDIUM,
                    "写入目标无法静态确认：$target（未提供 cwd，且不是绝对路径）",
                ),
            )
        }
        return listOfNotNull(fileWriteFinding(resolved))
    }

    /** 相对路径按 cwd 拼接；cwd 未知或目标含未解析变量时返回 null。 */
    private fun fileWriteTarget(path: String, cwd: String?): String? {
        if (path.contains('$') || path.contains('`')) return null
        if (path.startsWith("/")) return ProtectedPaths.normalize(path)
        if (path.startsWith("~")) return null
        val base = cwd?.takeIf { it.isNotBlank() } ?: return null
        return ProtectedPaths.normalize("$base/$path")
    }

    private fun fileWriteFinding(path: String): RiskFinding? {
        if (!path.startsWith("/")) return null
        ProtectedPaths.foreignPackageOf(path)?.let { packageName ->
            return RiskFinding(
                ToolRiskCategory.FOREIGN_APP_DATA_WRITE,
                ToolRiskLevel.HIGH,
                "写入 $path（应用 $packageName 的私有目录）",
            )
        }
        return when {
            ProtectedPaths.isBootChain(path) -> RiskFinding(
                ToolRiskCategory.BOOT_CHAIN_WRITE,
                ToolRiskLevel.CRITICAL,
                "写入 $path",
            )
            ProtectedPaths.isSystemPartition(path) -> RiskFinding(
                ToolRiskCategory.SYSTEM_PARTITION_WRITE,
                ToolRiskLevel.CRITICAL,
                "写入 $path",
            )
            ProtectedPaths.isSystemData(path) -> RiskFinding(
                ToolRiskCategory.SYSTEM_DATA_WRITE,
                ToolRiskLevel.HIGH,
                "写入 $path",
            )
            else -> null
        }
    }

    /**
     * 创建自动任务需要用户确认（审查 M3）；查看、启停、删除不改写"以后要执行什么"，
     * 不需要额外确认。
     */
    private fun analyzeScheduleTask(args: JSONObject): List<RiskFinding> {
        val action = args.optString("action").trim().lowercase(Locale.ROOT)
        if (action.isNotEmpty() && action != "create") return emptyList()
        val prompt = args.optString("prompt").ifBlank { args.optString("instruction") }.trim()
        if (prompt.isEmpty()) return emptyList()
        val title = args.optString("title").trim().ifEmpty { prompt.lineSequence().first() }
        return listOf(
            RiskFinding(
                ToolRiskCategory.AGENT_SCHEDULED_TASK_CREATE,
                ToolRiskLevel.MEDIUM,
                "创建自动任务「${title.boundedRiskSubject()}」：$prompt",
            ),
        )
    }

    /**
     * Agent 写入 Eta 自身设置（`eta_prefs_set`）。
     *
     * 只有"改了以后会发生什么"这件事需要用户点头的键才拦：服务地址（请求与凭据会发往新地址）、
     * 凭据（写/清）、Linux 执行环境（所有终端命令换环境）。普通布尔/枚举（自动朗读、语音识别开关、
     * 音色等）放行，否则每改一个小开关都要弹卡，用户会习惯性点同意。
     *
     * 判定规则与执行侧共用 [io.github.mangi.eta.agent.self.AgentSelfPrefsRules.confirmationReason]，
     * 不存在"两处各写一套"的漂移；未登记的键交给执行侧按白名单拒绝，这里不制造额外噪音。
     */
    private fun analyzePrefsSet(args: JSONObject): List<RiskFinding> {
        val key = args.optString("key").trim()
        if (key.isEmpty()) return emptyList()
        val spec = AgentSelfPrefsSpec.find(key) ?: return emptyList()
        val reason = AgentSelfPrefsRules.confirmationReason(spec) ?: return emptyList()
        val value = args.opt("value")?.let { if (it == JSONObject.NULL) "" else it.toString() }.orEmpty()
        val shown = if (spec.secret) "（凭据，已隐藏）" else value.boundedRiskSubject()
        return listOf(
            RiskFinding(
                ToolRiskCategory.AGENT_SETTINGS_WRITE,
                ToolRiskLevel.MEDIUM,
                "修改 Eta 设置 $key = $shown：$reason",
            ),
        )
    }

    /**
     * Agent 改写 Eta 自身运行配置（[SELF_CONFIG_TOOLS]）。
     *
     * 与 [analyzePrefsSet] 的分工：那边管"单个设置键"的白名单语义，这里管"整套运行时对象"——
     * 提供商（含 baseUrl / apiKey）、模型、MCP 服务器（含 bearer token）、工具组开关、技能卸载。
     * 文案要让人一眼看出改的是哪个对象（提供商名 / 模型 id / 服务器名），凭据一律不回显。
     *
     * 缺少必填参数时不制造噪音：执行侧本来就会拒绝，判定器不重复弹卡。
     */
    private fun analyzeSelfConfig(toolName: String, args: JSONObject): List<RiskFinding> {
        val write = when (toolName) {
            "eta_set_config" -> SelfWrite(
                ToolRiskCategory.AGENT_SELF_CONFIG_WRITE,
                ToolRiskLevel.MEDIUM,
                groupSwitchDetail(args),
            )
            "eta_set_tool_enabled" -> SelfWrite(
                ToolRiskCategory.AGENT_SELF_CONFIG_WRITE,
                ToolRiskLevel.MEDIUM,
                toolSwitchDetail(args),
            )
            "eta_provider_upsert" -> SelfWrite(
                ToolRiskCategory.AGENT_SELF_CONFIG_WRITE,
                ToolRiskLevel.MEDIUM,
                providerUpsertDetail(args),
            )
            "eta_provider_delete" -> SelfWrite(
                ToolRiskCategory.AGENT_SELF_DELETE,
                ToolRiskLevel.HIGH,
                providerDeleteDetail(args),
            )
            "eta_model_upsert" -> SelfWrite(
                ToolRiskCategory.AGENT_SELF_CONFIG_WRITE,
                ToolRiskLevel.MEDIUM,
                modelUpsertDetail(args),
            )
            "eta_model_delete" -> SelfWrite(
                ToolRiskCategory.AGENT_SELF_DELETE,
                ToolRiskLevel.HIGH,
                modelDeleteDetail(args),
            )
            "eta_model_select" -> SelfWrite(
                ToolRiskCategory.AGENT_SELF_CONFIG_WRITE,
                ToolRiskLevel.MEDIUM,
                modelSelectDetail(args),
            )
            "eta_mcp_upsert" -> SelfWrite(
                ToolRiskCategory.AGENT_SELF_CONFIG_WRITE,
                ToolRiskLevel.MEDIUM,
                mcpUpsertDetail(args),
            )
            "eta_mcp_delete" -> SelfWrite(
                ToolRiskCategory.AGENT_SELF_DELETE,
                ToolRiskLevel.HIGH,
                mcpDeleteDetail(args),
            )
            "eta_skill_delete" -> SelfWrite(
                ToolRiskCategory.AGENT_SELF_DELETE,
                ToolRiskLevel.HIGH,
                skillDeleteDetail(args),
            )
            else -> return emptyList()
        }
        val text = write.detail?.takeIf { it.isNotBlank() } ?: return emptyList()
        return listOf(RiskFinding(write.category, write.level, text.boundedRiskSubject()))
    }

    /** 一条自管理写入判定的三个静态属性；[detail] 为 null 表示参数不足、交给执行侧拒绝。 */
    private data class SelfWrite(
        val category: ToolRiskCategory,
        val level: ToolRiskLevel,
        val detail: String?,
    )

    private fun groupSwitchDetail(args: JSONObject): String? {
        val key = args.optString("key").trim()
        if (key.isEmpty()) return null
        val value = argText(args, "value")
        val verb = when {
            value.equals("true", ignoreCase = true) -> "打开"
            value.equals("false", ignoreCase = true) -> "关闭"
            else -> "修改"
        }
        val effect = if (verb == "关闭") "该组工具立刻不可用" else "从下一轮运行起生效"
        return "${verb}工具组开关 $key = $value（$effect）"
    }

    private fun toolSwitchDetail(args: JSONObject): String? {
        val tool = args.optString("tool").trim()
        if (tool.isEmpty() || !args.has("enabled")) return null
        return if (args.optBoolean("enabled")) {
            "重新启用工具 $tool"
        } else {
            "在工具列表中禁用工具 $tool（之后它无法被调用）"
        }
    }

    private fun providerUpsertDetail(args: JSONObject): String? {
        val id = args.optString("id").trim()
        val name = args.optString("name").trim()
        val baseUrl = args.optString("base_url").trim()
        if (id.isEmpty() && name.isEmpty() && baseUrl.isEmpty() && !args.has("api_key")) return null
        val action = if (id.isEmpty()) "新增模型提供商" else "修改模型提供商"
        val parts = buildList {
            if (baseUrl.isNotEmpty()) add("base_url=$baseUrl")
            add(secretDisclosure(args, "api_key"))
            if (args.has("enabled")) add("enabled=${args.optBoolean("enabled")}")
        }
        return "$action${objectLabel(name, id)}：${parts.joinToString("，")}；" +
            "之后对外的请求与凭据都会走这个地址"
    }

    private fun providerDeleteDetail(args: JSONObject): String? {
        val id = args.optString("id").trim()
        if (id.isEmpty()) return null
        return "删除模型提供商${objectLabel(args.optString("name"), id)}：" +
            "其下模型与保存的凭据一并移除，无法撤销"
    }

    private fun modelUpsertDetail(args: JSONObject): String? {
        val providerId = args.optString("provider_id").trim()
        val id = args.optString("id").trim()
        val modelId = args.optString("model_id").trim()
        val displayName = args.optString("display_name").trim()
        if (providerId.isEmpty() && id.isEmpty() && modelId.isEmpty()) return null
        val action = if (id.isEmpty()) "新增模型" else "修改模型"
        val provider = if (providerId.isEmpty()) "提供商（未指定）" else "提供商（id=$providerId）"
        val parts = buildList {
            if (modelId.isNotEmpty()) add("model_id=$modelId")
            if (id.isNotEmpty()) add("id=$id")
            if (displayName.isNotEmpty()) add("显示名=$displayName")
            if (args.has("enabled")) add("enabled=${args.optBoolean("enabled")}")
        }
        return "${provider}下$action（${parts.joinToString("，")}）：可选的模型清单随之变化"
    }

    private fun modelDeleteDetail(args: JSONObject): String? {
        val providerId = args.optString("provider_id").trim()
        val id = args.optString("id").trim()
        val modelId = args.optString("model_id").trim()
        if (id.isEmpty() && modelId.isEmpty()) return null
        val label = when {
            modelId.isNotEmpty() && id.isNotEmpty() -> "模型 $modelId（id=$id）"
            modelId.isNotEmpty() -> "模型 $modelId"
            else -> "模型（id=$id）"
        }
        val provider = if (providerId.isEmpty()) "提供商（未指定）" else "提供商（id=$providerId）"
        return "删除${provider}下的$label：无法撤销，需要重新添加才能用回来"
    }

    /**
     * 切换默认模型 / 提供商同样落在确认卡上：它决定之后每一轮请求用什么模型、发往哪个端点，
     * 用户当场就能看出"刚才那个模型被换掉了"，不必事后去设置页核对。
     */
    private fun modelSelectDetail(args: JSONObject): String? {
        val id = args.optString("id").trim()
        val providerId = args.optString("provider_id").trim()
        val target = when {
            id.isNotEmpty() -> "切换默认模型为 $id"
            providerId.isNotEmpty() -> "切换默认提供商为（id=$providerId）"
            else -> return null
        }
        return "$target：之后的对话改由它处理"
    }

    private fun mcpUpsertDetail(args: JSONObject): String? {
        val id = args.optString("id").trim()
        val name = args.optString("name").trim()
        val url = args.optString("url").trim()
        if (id.isEmpty() && name.isEmpty() && url.isEmpty() && !args.has("bearer_token")) return null
        val action = if (id.isEmpty()) "新增 MCP 服务器" else "修改 MCP 服务器"
        val parts = buildList {
            if (url.isNotEmpty()) add("url=$url")
            add(secretDisclosure(args, "bearer_token"))
            if (args.has("enabled")) add("enabled=${args.optBoolean("enabled")}")
        }
        return "$action${objectLabel(name, id)}：${parts.joinToString("，")}；" +
            "它提供的工具会进入工具列表"
    }

    private fun mcpDeleteDetail(args: JSONObject): String? {
        val id = args.optString("id").trim()
        if (id.isEmpty()) return null
        return "删除 MCP 服务器${objectLabel(args.optString("name"), id)}：" +
            "其工具与保存的 token 一并移除，无法撤销"
    }

    private fun skillDeleteDetail(args: JSONObject): String? {
        val id = args.optString("id").trim()
        if (id.isEmpty()) return null
        return "卸载已安装技能 $id：技能文件被删除，需要重新安装才能用回来"
    }

    /**
     * 凭据在确认卡上只说"替换 / 清除 / 沿用"，一个字符都不回显：卡片会留在聊天记录里，
     * 用户此刻也不需要重读自己的 key 才能决定是否放行。
     */
    private fun secretDisclosure(args: JSONObject, key: String): String = when {
        !args.has(key) -> "凭据沿用原值"
        args.opt(key) == null || args.opt(key) == JSONObject.NULL -> "凭据将被清除"
        args.optString(key).isBlank() -> "凭据将被清除"
        else -> "凭据将替换为新值（已隐藏）"
    }

    /** 对象标签：有名字显示名字，同时带上内部 id 便于对上列表。 */
    private fun objectLabel(name: String, id: String): String {
        val trimmedName = name.trim()
        val trimmedId = id.trim()
        return when {
            trimmedName.isNotEmpty() && trimmedId.isNotEmpty() -> "「$trimmedName」（id=$trimmedId）"
            trimmedName.isNotEmpty() -> "「$trimmedName」"
            trimmedId.isNotEmpty() -> "（id=$trimmedId）"
            else -> "（未命名）"
        }
    }

    private fun argText(args: JSONObject, key: String): String = when (val value = args.opt(key)) {
        null, JSONObject.NULL -> "未提供"
        else -> value.toString()
    }

    private fun analyzeSetting(args: JSONObject): List<RiskFinding> {
        val namespace = args.optString("namespace").trim().lowercase(Locale.ROOT)
        val key = args.optString("key").trim()
        if (namespace.isEmpty() || key.isEmpty()) return emptyList()
        if (!ShellCommandAnalyzer.isSensitiveSettingKey(namespace, key)) return emptyList()
        return listOf(
            RiskFinding(
                ToolRiskCategory.SENSITIVE_SETTING_WRITE,
                ToolRiskLevel.MEDIUM,
                "写入系统设置 $namespace/$key",
            ),
        )
    }

    private fun analyzeAppState(args: JSONObject): List<RiskFinding> {
        val action = args.optString("action").trim().lowercase(Locale.ROOT)
        val packageName = args.optString("package_name").trim()
        if (packageName.isEmpty()) return emptyList()
        val mutating = action in setOf("freeze", "unfreeze")
        val systemPackage = SYSTEM_PACKAGE_PREFIXES.any { prefix ->
            packageName == prefix || packageName.startsWith(prefix)
        }
        val dangerous = action == "force_stop" && systemPackage
        if (!mutating && !dangerous) return emptyList()
        return listOf(
            RiskFinding(
                ToolRiskCategory.SYSTEM_APP_MUTATION,
                if (systemPackage) ToolRiskLevel.HIGH else ToolRiskLevel.MEDIUM,
                "${if (mutating) "冻结/解冻" else "强制停止"}应用 $packageName",
            ),
        )
    }

    private fun subjectFor(toolName: String, args: JSONObject, findings: List<RiskFinding>): String =
        when (toolName) {
            in COMMAND_TOOLS -> args.optString("command").boundedRiskSubject()
            in FILE_WRITE_TOOLS -> args.optString("path").boundedRiskSubject()
            "schedule_task" -> (args.optString("title").ifBlank { args.optString("prompt") })
                .boundedRiskSubject()
            // 自管理工具：卡片标题必须写出工具名与关键参数，但绝不能把原始 args 整个打出来
            // （里面可能带 api_key / bearer_token）。
            in SELF_CONFIG_TOOLS -> findings.firstOrNull()?.detail
                ?.let { detail -> "$toolName · $detail" }
                ?.boundedRiskSubject()
                ?: toolName
            else -> findings.firstOrNull()?.detail?.boundedRiskSubject()
                ?: "${toolName} ${args.toString().boundedRiskSubject()}"
        }
}
