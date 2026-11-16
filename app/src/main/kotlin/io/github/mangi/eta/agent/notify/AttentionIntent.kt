package io.github.mangi.eta.agent.notify

/**
 * 一条通知的**发布声明**（纯数据、零 android.* / R.*，可进 JVM 单测）。
 *
 * 2026-11-15 从根重构。设计原则：**调用方只声明「这是哪条通知 + 策略开关」，不手填事实**。
 *  - 主键（ID + tag）与 channel 全部由 [NotificationIdRegistry] 号段唯一源派生/校验，
 *    发起点不再各挑硬编码 ID、不再各自填 channel 字符串（[AttentionDirector.decide] 会校验
 *    「该 ID 的 owner 允许的 channel」是否匹配，不匹配直接 [AttentionDirector.RejectReason.CHANNEL_MISMATCH]）；
 *  - 「是不是无人值守 / 是不是唤醒轮」由 [AttentionPublisher]→[AttentionDirector] 从
 *    [subject]（会话 id）经 [AttentionSubject] 一处派生，不再由发起点手填 `unattended`；
 *  - 「App 是否在前台」是客观事实，由 [AttentionPublisher.defaultContext] 从
 *    [AppForeground] 单一源取，不再把意图自己的开关回填进上下文（旧实现两个同源布尔退化）；
 *  - 渲染细节（深链 / RemoteInput / 决策按钮 / 大图）仍由各模块以已构造好的 Notification
 *    提供——逻辑收敛 ≠ 代码合并，故本意图不重复声明渲染字段。
 *
 * @param key 通知唯一句柄（[NotificationIdRegistry] 派生的 ID + 可选 tag）。notify / cancel /
 *            [replaceOngoing] 共用同一类型，带 tag 的通知不会再被裸 `cancel(id)` 静默撤不掉。
 * @param channel 落地使用的 channel，必须是该 ID owner 允许集之一（[NotificationIdRegistry.allowedChannelsFor]）。
 * @param subject 会话 id / 稳定对象键：用于 unattended 派生、终态按会话幂等、诊断；编排器真正读取它。
 * @param admission 仅主动沟通（notify_user）传入的准入声明；由唯一出口在落地成功后才记账。
 * @param suppressWhenForeground 策略：App 在前台且本会话非无人值守时抑制本条（既定前台静默策略）。
 * @param replaceOngoing 发布前需撤掉的进行中通知句柄（收尾，避免 setOngoing 僵尸）。
 */
internal data class AttentionIntent(
    val key: NotificationKey,
    val channel: NotificationIdRegistry.NotificationChannel,
    val subject: String? = null,
    val admission: AdmissionClaim? = null,
    val suppressWhenForeground: Boolean = false,
    val replaceOngoing: List<NotificationKey> = emptyList(),
) {
    /** 主动沟通的准入声明：priority + runId，编排器据此 gate（不占额）→ 落地成功才 commit。 */
    data class AdmissionClaim(val important: Boolean, val runId: String)
}
