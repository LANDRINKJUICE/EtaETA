package io.github.mangi.eta.agent.approval

import io.github.mangi.eta.agent.tool.ToolExecutionDecision
import org.json.JSONObject

/**
 * 工具执行前的危险操作闸门。
 *
 * 位置在 [io.github.mangi.eta.agent.tool.AgentLocalTools] 真正分发工具之前，与"无障碍是否可用"
 * 这类执行前检查串联：危险判定命中且没有被本次会话授权时，阻塞等待用户在聊天流里点选。
 *
 * 返回值沿用 [ToolExecutionDecision]，让调用方用同一套拒绝语义回报给模型。
 * 任何"没等到用户明确允许"的分支都只会返回拒绝，绝不默认放行。
 */
internal class AgentToolApprovalGate(
    private val coordinator: AgentApprovalCoordinator = AgentApprovalCoordinator.shared,
    private val grants: AgentSessionApprovalGrants = AgentSessionApprovalGrants.shared,
    /** 开关由调用方注入：默认启用，设置里关掉时直接放行。 */
    private val enabled: () -> Boolean = { true },
    private val evaluator: (String, JSONObject, String?) -> ToolRiskVerdict = { toolName, arguments, cwd ->
        AgentToolRiskEvaluator.evaluate(toolName, arguments, cwd)
    },
    /** 确认卡等待上限；超时按"用户未确认"处理。 */
    private val approvalTimeoutMillis: Long = AGENT_APPROVAL_TIMEOUT_MILLIS,
) {
    fun check(
        toolName: String,
        args: JSONObject,
        runId: String,
        conversationId: String?,
        isRunActive: () -> Boolean = { true },
        defaultCwd: String? = null,
    ): ToolExecutionDecision {
        if (!enabled()) return ToolExecutionDecision.Allow
        val verdict = evaluator(toolName, args, defaultCwd)
        if (verdict !is ToolRiskVerdict.NeedsConfirm) return ToolExecutionDecision.Allow
        if (grants.isGranted(conversationId, toolName, verdict.findings)) {
            return ToolExecutionDecision.Allow
        }
        // 模型调工具时可带一个可选意图字段：规整成单行、按码点安全截断后带进确认卡，
        // 让用户不只看到命令原文与风险理由，还知道 Agent 为什么想做它。没填 / 超长 / 含换行
        // 都安全降级（见 ApprovalIntentPolicy，防止模型用换行伪造额外风险理由、切坏 emoji）。
        val intent = ApprovalIntentPolicy.normalize(
            args.optString("intent"),
            ApprovalIntentPolicy.CARD_MAX_CHARS,
        )
        val request = coordinator.dangerRequest(
            verdict = verdict,
            runId = runId,
            conversationId = conversationId,
            timeoutMillis = approvalTimeoutMillis,
            intent = intent,
        )
        return when (val outcome = coordinator.await(request, isAborted = { !isRunActive() })) {
            is AgentApprovalOutcome.Decided -> {
                // 用户点了允许，但运行在这期间被停止：仍然不能执行。
                if (!isRunActive()) {
                    return ToolExecutionDecision.Reject(
                        code = CODE_USER_NOT_CONFIRMED,
                        message = notConfirmedMessage(verdict.subject),
                    )
                }
                when (outcome.resolution.kind) {
                    AgentApprovalOptionKind.AllowOnce -> ToolExecutionDecision.Allow
                    AgentApprovalOptionKind.AllowSession -> {
                        // 无会话 id 或 CRITICAL 级风险不落会话授权，和卡片隐藏该选项的行为保持一致。
                        if (!conversationId.isNullOrBlank() && verdict.level != ToolRiskLevel.CRITICAL) {
                            grants.grant(conversationId, toolName, verdict.findings)
                        }
                        ToolExecutionDecision.Allow
                    }
                    AgentApprovalOptionKind.Deny, AgentApprovalOptionKind.Choice ->
                        ToolExecutionDecision.Reject(
                            code = CODE_USER_DENIED,
                            message = denialMessage(verdict.subject, outcome.resolution.freeText),
                        )
                }
            }
            AgentApprovalOutcome.TimedOut,
            AgentApprovalOutcome.Cancelled,
            -> ToolExecutionDecision.Reject(
                code = CODE_USER_NOT_CONFIRMED,
                message = notConfirmedMessage(verdict.subject),
            )
        }
    }

    private fun notConfirmedMessage(subject: String): String =
        "用户未确认（等待超时或会话已结束），操作未执行：" +
            "$subject。不要假设它已经执行，也不要立刻用等价命令重试；" +
            "可以说明情况、改用只读方式验证，或调用 ask_user 询问用户。"

    private fun denialMessage(subject: String, feedback: String?): String = buildString {
        append("用户拒绝执行这次操作，本次未执行：")
        append(subject)
        append("。不要换个写法重试同一操作。")
        if (feedback != null) {
            append("用户的纠正建议：")
            append(feedback)
            append("。请按这条建议调整方案。")
        }
    }

    companion object {
        const val CODE_USER_DENIED = "USER_DENIED"
        const val CODE_USER_NOT_CONFIRMED = "USER_NOT_CONFIRMED"
    }
}
