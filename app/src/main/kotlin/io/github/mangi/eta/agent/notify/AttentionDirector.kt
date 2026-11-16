package io.github.mangi.eta.agent.notify

/**
 * 通知架构的**唯一裁决中枢**（纯逻辑，零 android.* / R.*，进纯 JVM 基线 + 完整单测）。
 *
 * 2026-11-15 从根重构：裁决只有 [decide] 一处（删掉与它语义分叉、且无生产调用方的
 * `shouldDisturb`）。[DisturbContext] 只放**客观事实**（系统开关 / channel 开关 / App 是否前台），
 * 不再放「由意图抄过来的伪事实」，也不再放恒真的 rateLimit/budget 假闸门——限流是
 * `notify_user` 工具的策略，由唯一出口 [AttentionPublisher] 在落地时专门处理，不是所有通知的通用裁决。
 *
 * 三道结构强制（机器挡住散乱，不靠注释）：
 * 1. 通知 ID 必须在 [NotificationIdRegistry.allIdSegments] 登记，否则 [RejectReason.UNREGISTERED_ID]；
 * 2. 该 ID 的 owner 与实际落地 channel 必须在 [NotificationIdRegistry.allowedChannelsFor] 允许集内，
 *    否则 [RejectReason.CHANNEL_MISMATCH]——杜绝「系统按错 channel 静默丢弃」这类漂移；
 * 3. 「该不该打扰」只有这里一处判定。
 */
internal object AttentionDirector {

    enum class RejectReason {
        UNREGISTERED_ID,
        CHANNEL_MISMATCH,
        NOTIFICATIONS_DISABLED,
        CHANNEL_DISABLED,
        RATE_LIMITED,
        FOREGROUND_SUPPRESSED,
        PUBLISH_FAILED,
    }

    /**
     * 一次裁决结果。
     * @param publish 是否真正落地这条通知。
     * @param cancelOngoing 无论 publish 与否都要撤掉的进行中通知句柄（避免「正在处理」僵尸）。
     * @param reject 非 null 表示被抑制/拒绝的原因（供工具如实回、日志与单测断言）。
     */
    data class AttentionDecision(
        val publish: Boolean,
        val cancelOngoing: List<NotificationKey>,
        val reject: RejectReason? = null,
    )

    /**
     * 统一裁决入口。
     * @param intent 一条通知的发布声明。
     * @param ctx 打扰判定所需的**客观事实**（由 [AttentionPublisher.defaultContext] 从唯一源取好传入）。
     */
    fun decide(intent: AttentionIntent, ctx: DisturbContext): AttentionDecision {
        val cancel = intent.replaceOngoing
        // (1) 号段合法性：硬约束，未登记 ID 一律拒绝（结构强制，不靠注释）。
        val owner = NotificationIdRegistry.ownerOfNotificationId(intent.key.id)
            ?: return AttentionDecision(false, cancel, RejectReason.UNREGISTERED_ID)
        // (2) ID 与 channel 匹配：不匹配会让系统按错 channel 静默丢弃，直接挡在这里。
        if (intent.channel !in NotificationIdRegistry.allowedChannelsFor(owner)) {
            return AttentionDecision(false, cancel, RejectReason.CHANNEL_MISMATCH)
        }
        // (3) 系统开关 / 单 channel 开关。
        if (!ctx.notificationsEnabled) {
            return AttentionDecision(false, cancel, RejectReason.NOTIFICATIONS_DISABLED)
        }
        if (!ctx.channelEnabled) {
            return AttentionDecision(false, cancel, RejectReason.CHANNEL_DISABLED)
        }
        // (4) 前台静默（既定策略）：App 在前台且本会话非无人值守时，结果就在屏幕上，不弹通知刷屏。
        //     无人值守会话（唤醒轮 / 自动任务）即便 App 在前台也照发，否则前台跑完完全静默。
        //     unattended 由 subject 经 AttentionSubject 一处派生，不再由发起点手填。
        if (intent.suppressWhenForeground && ctx.appForeground && !AttentionSubject.isUnattended(intent.subject)) {
            return AttentionDecision(false, cancel, RejectReason.FOREGROUND_SUPPRESSED)
        }
        return AttentionDecision(publish = true, cancelOngoing = cancel)
    }
}

/**
 * 「要不要打扰」的统一裁决输入：只放客观事实，全部由 [AttentionPublisher.defaultContext]
 * 从唯一源采集，Director 不引用任何 Android 类、不接收任何「从意图抄来的伪事实」。
 *
 * @param appForeground App 是否在前台（[AppForeground] 单一源，去抖后稳定值）。
 */
internal data class DisturbContext(
    val notificationsEnabled: Boolean,
    val channelEnabled: Boolean,
    val appForeground: Boolean,
)
