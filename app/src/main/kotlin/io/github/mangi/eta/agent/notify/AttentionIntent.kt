package io.github.mangi.eta.agent.notify

/**
 * 一条通知的**唯一裁决输入**（纯数据、零 android.* / R.*，可进 JVM 单测）。
 *
 * 2026-11-15 一次统一：12 个发起点把「这条通知是什么」填成 [AttentionIntent]，交
 * [AttentionDirector.decide] 一处裁决「号段合法性 / 打扰 / 前台抑制 / 撤哪些 ongoing」，
 * 由 [AttentionPublisher.publish] 唯一物理出口落地。**Builder 渲染细节（深链、RemoteInput、
 * 决策按钮、大图）仍由各模块以已构造好的 Notification 提供**——逻辑收敛 ≠ 代码合并，
 * 故本意图只承载裁决所需事实，不重复声明渲染字段（避免声明了却不生效的半成品 API）。
 *
 * @param notificationId 通知 ID，必须来自 [NotificationIdRegistry] 已登记段（编排器校验）。
 * @param channel 通知 channel，必须是 [NotificationIdRegistry.NotificationChannel] 之一。
 * @param objectKey 注意力对象稳定键：同对象状态变化 = 同 ID 就地变形，不新增。
 * @param unattended 是否无人值守会话（唤醒轮/自动任务）：即便 App 在前台也照发结果。
 * @param suppressIfForegroundChat 前台「用户正盯着的有值守 UI 聊天」时抑制本条通知。
 * @param replaceOngoingIds 发布前需撤掉的进行中通知 ID（收尾，避免 setOngoing 僵尸）。
 */
internal data class AttentionIntent(
    val notificationId: Int,
    val channel: NotificationIdRegistry.NotificationChannel,
    val objectKey: String,
    val tag: String? = null,
    val unattended: Boolean = false,
    val suppressIfForegroundChat: Boolean = false,
    val replaceOngoingIds: List<Int> = emptyList(),
)
