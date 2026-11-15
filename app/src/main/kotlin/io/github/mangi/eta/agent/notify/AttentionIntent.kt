package io.github.mangi.eta.agent.notify

/**
 * 一条通知的**完整声明**（纯数据、零 android.* / R.*，可进 JVM 单测）。
 *
 * 2026-11-15 一次统一：所有发起点不再各自 `Notification.Builder` + `manager.notify` +
 * 各自挑号段 / 判前台，而是把「我要表达什么」填成一条 [AttentionIntent] 交给唯一编排器
 * [AttentionDirector] 裁决、由唯一出口 [AttentionPublisher] 落地。渲染细节（RemoteInput、
 * 决策按钮、深链）仍由各模块以 [actions] / [deepLink] 形式声明，逻辑收敛 ≠ 代码合并。
 *
 * @param notificationId 通知 ID，**必须**来自 [NotificationIdRegistry] 已登记段（编排器会校验）。
 * @param channel 通知 channel，**必须**是 [NotificationIdRegistry.NotificationChannel] 之一。
 * @param objectKey 注意力对象稳定键：同一对象状态变化是「同 ID 就地变形」而非新增通知。
 * @param urgency 紧急度：决定 heads-up / 提示音 / 冷却档位（默认 NORMAL，默认不打扰）。
 * @param ongoing 是否常驻进行中（前台服务载体 / 运行阶段刷新）。
 * @param isForegroundService 是否为系统要求的前台服务载体（startForeground 用，逻辑上仍受统一裁决）。
 * @param replaceOngoingIds 本次发布后需撤掉的其它进行中通知 ID（一条 run 一条终态，收尾用）。
 */
internal data class AttentionIntent(
    val notificationId: Int,
    val channel: NotificationIdRegistry.NotificationChannel,
    val objectKey: String,
    val tag: String? = null,
    val title: String,
    val body: String,
    val urgency: Urgency = Urgency.NORMAL,
    val ongoing: Boolean = false,
    val autoCancel: Boolean = true,
    val onlyAlertOnce: Boolean = true,
    val isForegroundService: Boolean = false,
    val suppressIfForegroundChat: Boolean = false,
    val unattended: Boolean = false,
    val replaceOngoingIds: List<Int> = emptyList(),
    /** 由唯一出口构造 Notification 时用；各模块把自己的渲染逻辑封装进来，保持细节不丢。 */
    val render: RenderSpec = RenderSpec(),
) {
    enum class Urgency { NORMAL, IMPORTANT, SILENT }

    /**
     * 渲染规格（纯数据描述）：Builder 的细节由 [AttentionPublisher] 统一施加。
     * 深链 / 动作仍由调用方给 PendingIntent/Notification.Action 的**构造参数**（在 Android 侧
     * 以 lambda 形式注入到 publish，这里只保留是否需要回复位 / 类别等声明）。
     */
    data class RenderSpec(
        val category: String = "",              // Notification.CATEGORY_* 字符串
        val bigText: Boolean = false,
        val subText: String = "",
        val colorized: Boolean = false,
        val visibilityPrivate: Boolean = false,
        val progressIndeterminate: Boolean = false,
        val shortCriticalText: String = "",     // 流体云短文本
        val requestedPromotedOngoing: Boolean = false,
        val withReplyAction: Boolean = false,   // 是否挂 Direct Reply
        val withSound: Boolean = false,
    )
}
