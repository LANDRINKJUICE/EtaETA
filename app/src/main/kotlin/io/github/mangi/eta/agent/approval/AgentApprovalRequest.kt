package io.github.mangi.eta.agent.approval

/**
 * 一张等待用户确认的卡片。
 *
 * 卡片是"聊天流内的用户确认"这一交互的唯一数据源：
 * 危险操作拦截卡（[AgentApprovalKind.ToolRisk]）与 Agent 主动提问卡（[AgentApprovalKind.AskUser]）
 * 共用同一份结构，UI 只按 [kind] 选择展示重点。
 */
internal data class AgentApprovalRequest(
    val id: String,
    val kind: AgentApprovalKind,
    val toolName: String,
    /** 会话 id；null 表示无法归属到具体会话（例如手动入口）。 */
    val conversationId: String?,
    val runId: String,
    /** Agent 想做什么：命令、路径或操作原文。 */
    val subject: String,
    /** 危险卡的风险说明；询问卡为空。 */
    val findings: List<RiskFinding> = emptyList(),
    /** 询问卡的问题。 */
    val question: String = "",
    /** 询问卡的补充说明（背景、为什么需要用户决定）。 */
    val explanation: String = "",
    val options: List<AgentApprovalOption> = emptyList(),
    val allowFreeText: Boolean = false,
    val createdAtMillis: Long = 0L,
    val expiresAtMillis: Long = 0L,
) {
    val level: ToolRiskLevel
        get() = findings.maxOfOrNull { it.level } ?: ToolRiskLevel.MEDIUM

    val categories: List<ToolRiskCategory>
        get() = findings.map { it.category }.distinct()
}

internal enum class AgentApprovalKind {
    /** 危险操作拦截卡：工具执行前强制确认。 */
    ToolRisk,

    /** Agent 主动询问卡：拿不准用户意图时由模型发起。 */
    AskUser,
}

/** 选项种类决定点选后的授权范围与工具结果。 */
internal enum class AgentApprovalOptionKind {
    AllowOnce,
    AllowSession,
    Deny,
    /** 询问卡的自定义选项。 */
    Choice,
}

internal data class AgentApprovalOption(
    val id: String,
    val kind: AgentApprovalOptionKind,
    /** 询问卡的自定义文案；危险卡的内置选项为空，由 UI 按 id 解析字符串资源。 */
    val label: String = "",
    val description: String = "",
) {
    companion object {
        const val ALLOW_ONCE = "allow_once"
        const val ALLOW_SESSION = "allow_session"
        const val DENY = "deny"
        const val DENY_WITH_FEEDBACK = "deny_with_feedback"

        /**
         * 询问卡里"用户只输入了自由文本、没有点任何选项"的伪选项 id。
         * 它不是卡片上的按钮，只用于把自由输入回填给等待中的工具调用。
         */
        const val FREE_TEXT_ONLY = "free_text_only"

        /**
         * 危险卡的选择项：默认四种（允许一次 / 本次会话允许同类 / 拒绝 / 拒绝并给出纠正建议）。
         *
         * [allowSession] 为 false 时不提供"本次会话允许同类"：
         * - CRITICAL 级风险只能一次一放（避免一次授权覆盖更危险的同类操作）；
         * - 无法归属到具体会话时也没有"会话内"的概念。
         */
        fun dangerOptions(allowSession: Boolean = true): List<AgentApprovalOption> = buildList {
            add(AgentApprovalOption(ALLOW_ONCE, AgentApprovalOptionKind.AllowOnce))
            if (allowSession) {
                add(AgentApprovalOption(ALLOW_SESSION, AgentApprovalOptionKind.AllowSession))
            }
            add(AgentApprovalOption(DENY, AgentApprovalOptionKind.Deny))
            add(AgentApprovalOption(DENY_WITH_FEEDBACK, AgentApprovalOptionKind.Deny))
        }
    }
}

/** 用户的最终选择。 */
internal data class AgentApprovalResolution(
    val requestId: String,
    val optionId: String,
    val kind: AgentApprovalOptionKind,
    val freeText: String? = null,
    val atMillis: Long = 0L,
)

/** 卡片与决策的配对，供 UI 在决策后短暂回显"已允许/已拒绝"。 */
internal data class ResolvedApproval(
    val request: AgentApprovalRequest,
    val resolution: AgentApprovalResolution,
)

/** [AgentApprovalCoordinator.await] 的结果。 */
internal sealed interface AgentApprovalOutcome {
    data class Decided(val resolution: AgentApprovalResolution) : AgentApprovalOutcome

    /** 等待超时。 */
    data object TimedOut : AgentApprovalOutcome

    /** 会话/运行已结束或被用户停止。 */
    data object Cancelled : AgentApprovalOutcome
}

/** 默认等待上限：超时一律按"用户未确认"处理，绝不默认放行。 */
internal const val AGENT_APPROVAL_TIMEOUT_MILLIS = 300_000L
