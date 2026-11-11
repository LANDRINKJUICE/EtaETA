package io.github.mangi.eta.agent.approval

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
            else -> findings.firstOrNull()?.detail?.boundedRiskSubject()
                ?: "${toolName} ${args.toString().boundedRiskSubject()}"
        }
}
