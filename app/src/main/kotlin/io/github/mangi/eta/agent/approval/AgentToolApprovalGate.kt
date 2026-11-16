package io.github.mangi.eta.agent.approval

import io.github.mangi.eta.agent.tool.ToolExecutionDecision
import org.json.JSONObject

/**
 * 工具执行前的危险操作闸门：判定与处置对所有来源统一（在线 / 后台唤醒 / 自动任务 / 子代理）。
 *
 * 位置在 [io.github.mangi.eta.agent.tool.AgentLocalTools] 真正分发工具之前，与"无障碍是否可用"
 * 这类执行前检查串联。判定收敛（用户 2026-11-12 定稿）：evaluator 负责如实识别，闸门只认
 * [PROTECTED_BOUNDARY_CATEGORIES] 里的「确凿关键部位越界」——命中且没被本次会话授权时，
 * **一律照样弹卡**并阻塞等待；其余类别（看不清脚本、路径没解析、Eta 自管理等）直接放行。
 *
 * 不预判"这轮有没有人在线"：有没有人是超时之后才知道的事实。统一等 [approvalTimeoutMillis]
 * （全局 2 分钟），有人点按 Allow/Deny 走；没人点则回「未确认 / 未执行 / 可能无人值守 /
 * 其余任务照常做完 / 别重试别谎报」交模型自行判定。弹卡因此始终有意义，判定与处置不再分叉。
 *
 * 返回值沿用 [ToolExecutionDecision]：没等到明确允许只会拒绝、绝不默认放行、绝不偷偷执行关键
 * 部位；CRITICAL 永不被范围授权覆盖。非关键部位命中直接放行。
 */
internal class AgentToolApprovalGate(
    private val coordinator: AgentApprovalCoordinator = AgentApprovalCoordinator.shared,
    private val grants: AgentSessionApprovalGrants = AgentSessionApprovalGrants.shared,
    /** 开关由调用方注入：默认启用，设置里关掉时直接放行。 */
    private val enabled: () -> Boolean = { true },
    private val evaluator: (String, JSONObject, String?) -> ToolRiskVerdict = { toolName, arguments, cwd ->
        AgentToolRiskEvaluator.evaluate(toolName, arguments, cwd)
    },
    /** 确认卡等待上限；全局统一 2 分钟，超时按"用户未确认"处理。 */
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
        // 判定收敛（用户 2026-11-12 定稿）：识别与裁决分离。evaluator / ShellCommandAnalyzer
        // 仍如实产出全部 findings，但闸门只认「确凿关键部位越界」——系统分区 / 启动链 /
        // 安卓侧手机数据 / 大范围删除 / remount / format / 装卸冻结系统应用 / 敏感系统设置。
        // 其余（INDIRECT_EXECUTION 看不清脚本、UNRESOLVED_TARGET 路径没解析、
        // PRIVILEGED_SYSTEM_COMMAND 静态兜底、AGENT_* 自管理动作）一律放行、不弹卡。
        val boundary = verdict.findings.filter { it.category in PROTECTED_BOUNDARY_CATEGORIES }
        if (boundary.isEmpty()) return ToolExecutionDecision.Allow
        // 后续一律用过滤后的 findings：level / categories / 会话授权粒度都按真命中算。
        val effective = verdict.copy(findings = boundary)
        if (grants.isGranted(conversationId, toolName, effective.findings)) {
            return ToolExecutionDecision.Allow
        }
        // 命中关键部位：所有来源一律照样弹卡确认（弹卡唯一的存在理由）。
        // 模型调工具时可带一个可选意图字段：规整成单行、按码点安全截断后带进确认卡，
        // 让用户不只看到命令原文与风险理由，还知道 Agent 为什么想做它。没填 / 超长 / 含换行
        // 都安全降级（见 ApprovalIntentPolicy，防止模型用换行伪造额外风险理由、切坏 emoji）。
        val intent = ApprovalIntentPolicy.normalize(
            args.optString("intent"),
            ApprovalIntentPolicy.CARD_MAX_CHARS,
        )
        val request = coordinator.dangerRequest(
            verdict = effective,
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
                        message = notConfirmedMessage(effective.subject),
                    )
                }
                when (outcome.resolution.kind) {
                    AgentApprovalOptionKind.AllowOnce -> ToolExecutionDecision.Allow
                    AgentApprovalOptionKind.AllowSession -> {
                        // 无会话 id 或 CRITICAL 级风险不落会话授权，和卡片隐藏该选项的行为保持一致。
                        if (!conversationId.isNullOrBlank() && effective.level != ToolRiskLevel.CRITICAL) {
                            grants.grant(conversationId, toolName, effective.findings)
                        }
                        ToolExecutionDecision.Allow
                    }
                    AgentApprovalOptionKind.Deny, AgentApprovalOptionKind.Choice ->
                        ToolExecutionDecision.Reject(
                            code = CODE_USER_DENIED,
                            message = denialMessage(effective.subject, outcome.resolution.freeText),
                        )
                }
            }
            AgentApprovalOutcome.TimedOut,
            AgentApprovalOutcome.Cancelled,
            -> ToolExecutionDecision.Reject(
                code = CODE_USER_NOT_CONFIRMED,
                message = notConfirmedMessage(effective.subject),
            )
        }
    }

    private fun notConfirmedMessage(subject: String): String =
        "两分钟内没有人确认这张卡，操作未执行：$subject。这轮很可能无人值守（用户不在线），" +
            "没人为你点选——这是事实、不是故障，也**不代表用户已同意**。不要假设它已经执行，" +
            "不要立刻用等价命令重试，更不要因此替用户放行这条关键部位操作；如实把「未执行」写进结论即可。" +
            "其余不需要这一步的任务照常做到完成、产出结果（重跑构建、取包、交付、写交接等），苦活累活继续续上。"

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
