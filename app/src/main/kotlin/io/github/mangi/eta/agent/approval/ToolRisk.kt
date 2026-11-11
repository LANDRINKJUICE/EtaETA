package io.github.mangi.eta.agent.approval

/**
 * 工具调用的静态风险判定结果。
 *
 * 判定是纯函数：输入工具名与参数，输出 [ToolRiskVerdict]。执行侧只根据判定结果决定
 * "直接执行"还是"挂起等用户确认"，不在这里做任何副作用。
 */
internal sealed interface ToolRiskVerdict {
    /** 没有命中任何危险规则，可以直接执行。 */
    data object Safe : ToolRiskVerdict

    /**
     * 命中危险规则，必须由用户在聊天流内确认后才能执行。
     *
     * @param toolName 触发判定的工具名，用于展示与"本次会话内允许同类"的授权粒度。
     * @param subject 展示给用户的操作原文（命令、路径或关键参数），已截断。
     * @param findings 命中的全部规则；同一类别可能因为多个路径重复出现。
     */
    data class NeedsConfirm(
        val toolName: String,
        val subject: String,
        val findings: List<RiskFinding>,
    ) : ToolRiskVerdict {
        val level: ToolRiskLevel
            get() = findings.maxOfOrNull { it.level } ?: ToolRiskLevel.MEDIUM

        /** 命中的风险类别（去重、保持首次出现顺序）；会话授权按此粒度记忆。 */
        val categories: List<ToolRiskCategory>
            get() = findings.map { it.category }.distinct()
    }
}

/** 风险等级；数值越高越接近不可逆的系统级变更。 */
internal enum class ToolRiskLevel {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL,
}

/**
 * 风险类别。
 *
 * 类别有两层用途：给用户解释"为什么被拦下"，以及作为"本次会话内允许同类操作"的授权键
 * （授权键 = 工具名 + 类别）。
 */
internal enum class ToolRiskCategory(val wireValue: String) {
    /** 写入 /system /vendor /product 等系统分区。 */
    SYSTEM_PARTITION_WRITE("system_partition_write"),

    /** 写入启动链相关分区（boot、init_boot、recovery、dtbo、super、/dev/block 等）。 */
    BOOT_CHAIN_WRITE("boot_chain_write"),

    /** 以读写方式重新挂载分区。 */
    REMOUNT_READ_WRITE("remount_read_write"),

    /** 大范围递归删除或通配符删除。 */
    DESTRUCTIVE_DELETE("destructive_delete"),

    /** 格式化、分区表或裸块设备写入。 */
    DISK_FORMAT("disk_format"),

    /** 写入系统私有数据区（/data/system、/data/adb、/data/misc 等）。 */
    SYSTEM_DATA_WRITE("system_data_write"),

    /** 写入其他应用的私有目录。 */
    FOREIGN_APP_DATA_WRITE("foreign_app_data_write"),

    /** 卸载、停用、冻结或清除应用（尤其是系统应用）。 */
    SYSTEM_APP_MUTATION("system_app_mutation"),

    /** 修改 global/secure/system 中的敏感设置键。 */
    SENSITIVE_SETTING_WRITE("sensitive_setting_write"),

    /** 其他特权系统命令（magisk、reboot 到 bootloader、setprop 持久属性等）。 */
    PRIVILEGED_SYSTEM_COMMAND("privileged_system_command"),

    /** 间接执行：脚本文件、管道送进 shell、xargs/find -exec、解释器代码等无法静态确认的形态。 */
    INDIRECT_EXECUTION("indirect_execution"),

    /** 写入目标无法静态确认：未解析变量、家目录、未知 cwd 下的相对路径。 */
    UNRESOLVED_TARGET("unresolved_target"),

    /**
     * 创建"到点自动执行"的 agent 任务（审查 M3）。
     *
     * 这类调用把一段指令持久化进系统，之后无人值守地执行；用户必须看到它到底被要求做什么，
     * 因此单独成一类风险，确认卡的文案也单独说明。
     */
    AGENT_SCHEDULED_TASK_CREATE("agent_scheduled_task_create"),

    /**
     * Agent 写入 Eta 自身的敏感设置（服务地址、凭据、Linux 执行环境）。
     *
     * 这些键不会碰系统分区，但会改变"之后的请求往哪发、用谁的凭据、命令在哪个环境里跑"，
     * 属于用户必须当面点头的一类；判定规则见 [io.github.mangi.eta.agent.self.AgentSelfPrefsRules.confirmationReason]。
     */
    AGENT_SETTINGS_WRITE("agent_settings_write"),

    /**
     * Agent 改写 Eta 自身的运行配置：模型提供商、模型、MCP 服务器、工具组开关。
     *
     * 这类写入不碰系统分区，但决定"之后的请求发往哪个端点、带谁的凭据、模型具备哪些能力、
     * 哪些工具还能被调用"，属于用户必须当面点头的一类；与 [AGENT_SETTINGS_WRITE] 分开是因为
     * 确认卡的解释不同（那边说的是单个设置键，这边说的是整套模型\/MCP 配置）。
     */
    AGENT_SELF_CONFIG_WRITE("agent_self_config_write"),

    /**
     * Agent 删除 Eta 自身的配置项或已安装内容（提供商、模型、MCP 服务器、技能）。
     *
     * 删除不可撤销，重建要用户自己重新填地址、凭据或重新安装技能包，因此单列一类 HIGH。
     */
    AGENT_SELF_DELETE("agent_self_delete"),
}

/** 单条命中记录：类别 + 等级 + 具体对象（路径、属性键、命令片段）。 */
internal data class RiskFinding(
    val category: ToolRiskCategory,
    val level: ToolRiskLevel,
    val detail: String,
)

/** 风险判定器统一使用的展示截断长度，避免把整段命令塞进卡片。 */
internal const val RISK_SUBJECT_MAX_CHARS = 600

internal fun String.boundedRiskSubject(): String {
    val trimmed = trim()
    if (trimmed.length <= RISK_SUBJECT_MAX_CHARS) return trimmed
    return trimmed.take(RISK_SUBJECT_MAX_CHARS) + "…"
}
