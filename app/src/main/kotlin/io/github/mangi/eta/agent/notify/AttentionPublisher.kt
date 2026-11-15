package io.github.mangi.eta.agent.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import io.github.mangi.eta.R

/**
 * 通知的**唯一物理出口**（2026-11-15 一次统一）。
 *
 * 全仓 `manager.notify(` 只此一处；channel 注册也只此一处（[ensureAllChannels] 按
 * [NotificationIdRegistry.NotificationChannel] 表一次性建全）。所有发起点构造
 * [AttentionIntent] 交给 [AttentionDirector.decide] 裁决，再由这里落地：
 *  - 普通通知：[publish] 裁决 + 构造 + notify；
 *  - 前台服务载体：[build] 返回构造好的 Notification 供 service.startForeground 用——
 *    FGS type / 停止按钮 / 流体云 ProgressStyle 等系统语义由调用方声明进 render，
 *    但 ID / channel / 内容 / 号段全部来自统一意图，不再各自硬编码。
 *
 * 结构护栏（[ATTENTION_PUBLISHER 唯一性]）：`scripts/check-unfixed.sh` 会断言全仓
 * `.notify(` 调用只出现在本文件，新增发起点若绕过这里直接 notify → 闸红。
 */
internal object AttentionPublisher {

    /** channel 表进程内只注册一次；系统本身幂等，这里省掉每次 publish 的 9 次 binder。 */
    @Volatile private var channelsEnsured = false

    /** 所有 channel 的唯一注册入口（幂等）。完整保留改造前各 channel 的 sound/vibration/badge 语义。 */
    fun ensureAllChannels(context: Context) {
        if (channelsEnsured) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        var allOk = true
        NotificationIdRegistry.NotificationChannel.entries.forEach { ch ->
            val importance = when (ch.id) {
                NotificationIdRegistry.NotificationChannel.ATTENTION.id -> NotificationManager.IMPORTANCE_HIGH
                NotificationIdRegistry.NotificationChannel.EXECUTION.id,
                NotificationIdRegistry.NotificationChannel.MACRO_RECORDING.id,
                NotificationIdRegistry.NotificationChannel.ASSISTANT_SPEECH.id,
                NotificationIdRegistry.NotificationChannel.READ_ALOUD.id -> NotificationManager.IMPORTANCE_LOW
                else -> NotificationManager.IMPORTANCE_DEFAULT
            }
            val label = channelLabel(context, ch)
            val ok = runCatching {
                manager.createNotificationChannel(NotificationChannel(ch.id, label, importance).apply {
                    when (ch) {
                        // 改造前 AgentNotifications.ensureChannels 的 PROACTIVE：普通主动沟通明确静音、不振、有角标。
                        NotificationIdRegistry.NotificationChannel.PROACTIVE -> {
                            description = context.getString(R.string.notify_channel_proactive_desc)
                            setShowBadge(true)
                            enableVibration(false)
                            setSound(null, null)
                        }
                        // ATTENTION：真正紧急，heads-up + 提示音 + 振动 + 角标。丢了 setSound 会让新装用户 important 通知不响。
                        NotificationIdRegistry.NotificationChannel.ATTENTION -> {
                            description = context.getString(R.string.notify_channel_attention_desc)
                            setShowBadge(true)
                            enableVibration(true)
                            setSound(
                                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                                AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                    .build(),
                            )
                        }
                        NotificationIdRegistry.NotificationChannel.MACRO_RECORDING -> setShowBadge(false)
                        NotificationIdRegistry.NotificationChannel.SCHEDULED_TASKS -> setShowBadge(true)
                        else -> Unit
                    }
                })
            }.isSuccess
            if (!ok) allOk = false
        }
        // 全部成功才置位；某次 channel 创建失败时下次 publish 会重试，不留半注册状态。
        if (allOk) channelsEnsured = true
    }

    private fun channelLabel(context: Context, ch: NotificationIdRegistry.NotificationChannel): String =
        when (ch) {
            NotificationIdRegistry.NotificationChannel.EXECUTION -> context.getString(R.string.execution_channel)
            NotificationIdRegistry.NotificationChannel.RUN_RESULTS -> context.getString(R.string.execution_result_channel)
            NotificationIdRegistry.NotificationChannel.PROACTIVE -> context.getString(R.string.notify_channel_proactive)
            NotificationIdRegistry.NotificationChannel.ATTENTION -> context.getString(R.string.notify_channel_attention)
            NotificationIdRegistry.NotificationChannel.SCHEDULED_TASKS -> context.getString(R.string.schedule_notification_channel)
            NotificationIdRegistry.NotificationChannel.BACKGROUND_COMMANDS -> context.getString(R.string.background_command_channel)
            NotificationIdRegistry.NotificationChannel.MACRO_RECORDING -> context.getString(R.string.macro_recording_channel)
            NotificationIdRegistry.NotificationChannel.ASSISTANT_SPEECH -> context.getString(R.string.assistant_speech_channel)
            NotificationIdRegistry.NotificationChannel.READ_ALOUD -> context.getString(R.string.read_aloud_channel)
        }

    /** 唯一 channel 开关查询（供 DisturbContext.channelEnabled）。 */
    fun channelEnabled(context: Context, ch: NotificationIdRegistry.NotificationChannel): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        val channel = runCatching { manager.getNotificationChannel(ch.id) }.getOrNull() ?: return true
        return channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    /** 组装 DisturbContext 的唯一入口：通知开关 / channel / 前台真相全部从统一源取。 */
    fun defaultContext(
        context: Context,
        intent: AttentionIntent,
        rateLimitAllows: Boolean = true,
        budgetAllows: Boolean = true,
    ): DisturbContext {
        val manager = context.getSystemService(NotificationManager::class.java)
        return DisturbContext(
            notificationsEnabled = manager?.areNotificationsEnabled() ?: false,
            channelEnabled = channelEnabled(context, intent.channel),
            foreground = AppForeground.isForeground(),
            // 从意图读「是否用户正盯着的有值守 UI 聊天」，供 AttentionDirector 一处裁决前台抑制；
            // 不再恒 false（恒 false 会让 showRunResult 的前台静默语义失效）。
            foregroundUiChat = intent.suppressIfForegroundChat,
            unattended = intent.unattended,
            rateLimitAllows = rateLimitAllows,
            budgetAllows = budgetAllows,
        )
    }

    /**
     * 唯一发布入口（**兼容重载**：发起点已用各自 Builder 构造好 Notification 时用这个）。
     * 12 个既有发起点收编就是一行替换：`manager.notify(id, n)` → 本方法。
     * 公共裁决（号段合法性 / 打扰 / 前台抑制 / 撤 ongoing）与新出口完全一致，
     * Builder 细节（深链、RemoteInput、决策按钮）留在各模块，零重构风险——逻辑收敛、代码不硬并。
     */
    fun publish(
        context: Context,
        intent: AttentionIntent,
        notification: Notification,
        ctx: DisturbContext = defaultContext(context, intent),
    ): AttentionDirector.AttentionDecision {
        val decision = AttentionDirector.decide(intent, ctx)
        decision.cancelOngoingIds.forEach { cancel(context, it) }
        if (!decision.publish) return decision
        val manager = context.getSystemService(NotificationManager::class.java) ?: return decision
        ensureAllChannels(context)
        runCatching {
            if (intent.tag == null) manager.notify(intent.notificationId, notification)
            else manager.notify(intent.tag, intent.notificationId, notification)
        }
        return decision
    }

    /** 准备阶段失败（没进 Runtime）结果通知：与正常失败共用 run_result 段、同 ID 变形、不双发。 */
    fun publishFailureResult(
        context: Context,
        conversationId: String,
        build: (notificationId: Int) -> Notification,
    ): AttentionDirector.AttentionDecision {
        // 唤醒轮会话的终态唯一由 9602（showFinished）承担；这里早退，避免 9502 + 9602 双发。
        // 判定收归 AttentionDirector 一处，别在两处各写前缀比较。
        if (AttentionDirector.isWakeupConversation(conversationId)) {
            return AttentionDirector.AttentionDecision(publish = false, cancelOngoingIds = emptyList())
        }
        val id = NotificationIdRegistry.idOf("run_result", conversationId)
        val intent = AttentionIntent(
            notificationId = id,
            channel = NotificationIdRegistry.NotificationChannel.RUN_RESULTS,
            objectKey = conversationId,
            tag = conversationId,
            title = "", body = "",
            unattended = true,
        )
        return publish(context, intent, build(id))
    }

    /** 结果 channel 单独 ensure（兼容既有调用；完整统一后统一走 ensureAllChannels）。 */
    fun ensureResultChannel(context: Context) = ensureAllChannels(context)

    /** 撤掉一条进行中通知（终态收尾，避免「正在处理」僵尸）。 */
    fun cancel(context: Context, id: Int, tag: String? = null) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        runCatching {
            if (tag == null) manager.cancel(id) else manager.cancel(tag, id)
        }
    }
}
