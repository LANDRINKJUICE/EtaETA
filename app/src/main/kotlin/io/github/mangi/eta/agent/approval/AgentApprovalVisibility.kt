package io.github.mangi.eta.agent.approval

/**
 * 确认卡的可见性投影（纯函数，不依赖 Compose/Android，便于在 JVM 上钉住回归）。
 *
 * 聊天流渲染的是 [AgentApprovalCoordinator.pending] 的**投影**，而不是投影本身：
 * 决策一旦落地，[AgentApprovalCoordinator.pending] 里就再也没有这张卡，
 * 因此下面两个函数的唯一职责是"过滤归属会话 + 判断回显窗口"，
 * 不能引入任何可以留在列表里的中间状态。
 *
 * 归属规则：
 * - 卡片有会话 id 时只在该会话显示，避免在别的会话里看不到／误点；
 * - 卡片无法归属到会话（`conversationId == null`，例如子代理、
 *   后台任务唤醒的 run 发起的危险操作）时在任何会话都显示——
 *   否则用户永远看不到后台 run 在等确认，工具只能等到超时。
 */
internal fun List<AgentApprovalRequest>.awaitingConfirmationIn(
    conversationId: String?,
): List<AgentApprovalRequest> = filter { request -> request.belongsToConversation(conversationId) }

/** 决策后 [windowMillis] 内的回显条；窗口用传入的 now 判断，不依赖任何后台计时器。 */
internal fun List<ResolvedApproval>.resolvedFeedbackIn(
    conversationId: String?,
    nowMillis: Long,
    windowMillis: Long,
): List<ResolvedApproval> = filter { resolved ->
    resolved.request.belongsToConversation(conversationId) &&
        nowMillis - resolved.resolution.atMillis in 0L..windowMillis
}

/**
 * 回显心跳还需要睡多久；返回 null 表示"不需要再刷新了"。
 *
 * 聊天流里原来的写法是 `while (approvalResolved.isNotEmpty()) { …; delay(1_000) }`，
 * 但 [AgentApprovalCoordinator.resolved] 是"最近完成的决策"历史，永不自动清空，
 * 于是这个循环在第一次决策之后就**永远不会退出**：整个聊天体被无意义地每秒重组一次，
 * 已决策卡片所在列表槽位的退出动画也会被持续扰动。用窗口判断收口。
 */
internal fun approvalFeedbackRefreshDelayMillis(
    resolved: List<ResolvedApproval>,
    nowMillis: Long,
    windowMillis: Long,
): Long? {
    if (resolved.isEmpty()) return null
    val newest = resolved.maxOf { it.resolution.atMillis }
    val expiresAt = newest + windowMillis
    if (nowMillis >= expiresAt) return null
    return (expiresAt - nowMillis).coerceAtMost(APPROVAL_FEEDBACK_TICK_MILLIS)
}

/** 回显窗口内的心跳粒度：够让"还剩几秒"看起来走字，又不至于每帧都重组。 */
internal const val APPROVAL_FEEDBACK_TICK_MILLIS = 1_000L

private fun AgentApprovalRequest.belongsToConversation(conversationId: String?): Boolean =
    conversationId == null || this.conversationId == null || this.conversationId == conversationId
