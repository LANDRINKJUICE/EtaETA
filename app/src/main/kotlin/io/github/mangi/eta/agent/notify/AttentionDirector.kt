package io.github.mangi.eta.agent.notify

/**
 * 通知架构的**唯一裁决中枢**（纯逻辑，零 android.* / R.*，进纯 JVM 基线 + 完整单测）。
 *
 * 2026-11-15 一次统一：所有发起点把「我要发什么」填成 [AttentionIntent] 提交到这里，
 * 由它一处回答「合不合法 / 该不该打扰 / 前台要不要抑制 / 同 ID 变形还是新建 / 收尾撤哪些
 * ongoing」。裁决结果 [AttentionDecision] 交唯一出口 [AttentionPublisher] 落地。
 *
 * 三道结构强制（不是文档提醒，是机器挡住散乱）：
 * 1. 通知 ID **必须**在 [NotificationIdRegistry.allIdSegments] 登记，否则 [RejectReason.UNREGISTERED_ID]
 *    —— 新功能漏登记就发不出去，且护栏单测会把"未登记 ID"变红灯；
 * 2. channel **必须**是 [NotificationIdRegistry.NotificationChannel] 之一（本类型系统已保证）；
 * 3. 「该不该打扰」只有 [shouldDisturb] 一处判定，不再 6 个地方各判各的。
 *
 * 冷却 / 每-run 额度有「占用副作用」，由调用方在真正发布前用 [NotificationPolicy.admission]
 * 提交，这里只消费其结论（[DisturbContext.rateLimitAllows] / [budgetAllows]），保持纯函数。
 */
internal object AttentionDirector {

    enum class RejectReason { UNREGISTERED_ID, NOTIFICATIONS_DISABLED, RATE_LIMITED, FOREGROUND_SUPPRESSED }

    /**
     * 一次裁决结果。
     * @param publish 是否真正落地这条通知。
     * @param cancelOngoingIds 无论 publish 与否都要撤掉的进行中通知（避免「正在处理」僵尸）。
     * @param reject 非 null 表示被抑制/拒绝的原因（供日志与单测断言）。
     */
    data class AttentionDecision(
        val publish: Boolean,
        val cancelOngoingIds: List<Int>,
        val reject: RejectReason? = null,
    )

    /**
     * 统一裁决入口。
     *
     * @param intent 一条通知的完整声明。
     * @param ctx 打扰判定所需的全部事实（由调用方从唯一源取好传入，Director 不碰 Android）。
     */
    fun decide(intent: AttentionIntent, ctx: DisturbContext): AttentionDecision {
        // (1) 号段合法性：硬约束，未登记 ID 一律拒绝（结构强制，不靠注释）。
        if (NotificationIdRegistry.ownerOfNotificationId(intent.notificationId) == null) {
            return AttentionDecision(
                publish = false,
                cancelOngoingIds = intent.replaceOngoingIds,
                reject = RejectReason.UNREGISTERED_ID,
            )
        }
        // (2) 收尾：无论最终发不发，进行中通知都要撤（不留僵尸）。
        val cancel = intent.replaceOngoingIds
        // (3) 打扰裁决（唯一一处）。
        if (!ctx.notificationsEnabled || !ctx.channelEnabled) {
            return AttentionDecision(false, cancel, RejectReason.NOTIFICATIONS_DISABLED)
        }
        if (!ctx.rateLimitAllows || !ctx.budgetAllows) {
            return AttentionDecision(false, cancel, RejectReason.RATE_LIMITED)
        }
        // 前台用户正盯着的有值守 UI 聊天：结果就在屏上，别弹通知刷屏（但 ongoing 仍收尾）。
        // 无人值守会话即便 App 在前台也照发，否则前台跑完完全静默。
        if (intent.suppressIfForegroundChat && ctx.foregroundUiChat && ctx.foreground && !intent.unattended) {
            return AttentionDecision(false, cancel, RejectReason.FOREGROUND_SUPPRESSED)
        }
        return AttentionDecision(publish = true, cancelOngoingIds = cancel)
    }

    /**
     * 统一打扰裁决（[decide] 的核心谓词，单独保留便于单测与复用）。
     */
    fun shouldDisturb(ctx: DisturbContext): Boolean {
        if (!ctx.notificationsEnabled || !ctx.channelEnabled) return false
        if (!ctx.rateLimitAllows || !ctx.budgetAllows) return false
        if (ctx.foregroundUiChat && ctx.foreground && !ctx.unattended) return false
        return true
    }

    /**
     * 终态发布者归属（唯一判定，避免 showRunResult / publishFailureResult 两处各写 startsWith 漂移）：
     * 唤醒轮会话（conv-wakeup-）终态由 9602 就地变形承担，run_result 段一律不发。
     */
    fun isWakeupConversation(conversationId: String): Boolean =
        conversationId.startsWith("conv-wakeup-")
}

/**
 * 「要不要打扰」的统一裁决输入。所有发起点把这几个事实交上来，由一处回答。
 * 前台真相由调用方从唯一源 [AppForeground]（Android 侧）取好传入，Director 不引用 Android 类。
 */
internal data class DisturbContext(
    val notificationsEnabled: Boolean,
    val channelEnabled: Boolean,
    val foreground: Boolean,
    val foregroundUiChat: Boolean,
    val unattended: Boolean,
    val rateLimitAllows: Boolean,
    val budgetAllows: Boolean,
)
