package io.github.mangi.eta.agent.tool

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.mangi.eta.R
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.ui.MainActivity

/**
 * 录制中的常驻通知：这是用户唯一随时可见的"正在录制"状态，也是停止入口。
 *
 * 选通知而不是悬浮窗：成本最低、不引入额外悬浮权限、也不遮挡用户正在操作的目标应用。
 */
internal object MacroRecordingNotifications {
    const val CHANNEL_ID = "macro_recording"

    /**
     * 通知 id 分段（审查 m4）：自动任务占用 9000..9290，宏录制固定用 9600（录制中）与
     * 9601（已停止结果），两者永不互相覆盖。
     */
    const val NOTIFICATION_ID = 9_600
    private const val STOPPED_NOTIFICATION_ID = 9_601

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        runCatching {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.macro_recording_channel),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = context.getString(R.string.macro_recording_channel_description)
                    setShowBadge(false)
                },
            )
        }
    }

    fun showRecording(context: Context, suggestedName: String, steps: Int, elapsedMillis: Long = 0L) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        ensureChannel(context)
        val stopIntent = PendingIntent.getBroadcast(
            context,
            NOTIFICATION_ID,
            Intent(context, MacroRecordingReceiver::class.java)
                .setAction(MacroRecordingReceiver.ACTION_STOP)
                .putExtra(MacroRecordingReceiver.EXTRA_NAME, suggestedName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            Notification.Builder(context, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }
        val notification = builder
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.macro_recording_title))
            // 实时状态：已录步数 + 已用时（由调用方按 ≥1s 节流刷新），而不是一条不变的"正在录制"。
            .setContentText(
                context.getString(R.string.macro_recording_text, steps, suggestedName) +
                    " · " + context.getString(R.string.notify_elapsed_format, (elapsedMillis / 1_000L).coerceAtLeast(0L)),
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, true)
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    0,
                    Intent(context, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .addAction(
                Notification.Action.Builder(
                    null,
                    context.getString(R.string.macro_recording_stop_action),
                    stopIntent,
                ).build(),
            )
            .build()
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    fun cancel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        runCatching { manager.cancel(NOTIFICATION_ID) }
    }

    /** 停止录制后的结果提示（非阻塞，允许用户稍后在宏列表里看到）。 */
    fun showStopped(context: Context, summary: String) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        ensureChannel(context)
        val builder = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            Notification.Builder(context, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }
        val notification = builder
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.macro_recording_stopped_title))
            .setContentText(summary)
            .setStyle(Notification.BigTextStyle().bigText(summary))
            .setAutoCancel(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    1,
                    Intent(context, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .addAction(
                Notification.Action.Builder(
                    null,
                    context.getString(R.string.notify_action_open_task_center),
                    io.github.mangi.eta.agent.notify.AgentNotifications
                        .taskCenterIntent(context, STOPPED_NOTIFICATION_ID + 1, "MACROS"),
                ).build(),
            )
            .build()
        runCatching { manager.notify(STOPPED_NOTIFICATION_ID, notification) }
    }
}

/**
 * 通知上的"停止"按钮。
 *
 * 用户在通知上停止时通常不会回到聊天流，所以这里直接按建议名保存成宏，并把动作数写进结果通知；
 * Agent 侧调用 `macro_record(action=stop)` 时则只返回动作序列，由模型决定是否 `macro_save`。
 */
internal class MacroRecordingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_STOP) return
        val appContext = context.applicationContext
        val failed = runCatching {
            MacroRecordingStopHandler.stopAndSave(appContext, intent.getStringExtra(EXTRA_NAME))
        }.getOrElse { throwable ->
            AndroidAgentLogger.warn("Macro recording stop from notification failed: type=${throwable.safeLogType()}")
            null
        }
        if (failed == null) {
            MacroRecordingNotifications.showStopped(
                appContext,
                appContext.getString(R.string.macro_recording_no_actions),
            )
        }
    }

    companion object {
        const val ACTION_STOP = "io.github.mangi.eta.agent.tool.MACRO_RECORD_STOP"
        const val EXTRA_NAME = "name"
    }
}

/**
 * "停止录制并保存"的唯一实现：通知按钮、到达上限自动收尾都走这里，保证两条路径行为一致。
 */
internal object MacroRecordingStopHandler {
    /** 停止当前录制并保存；没有正在进行的录制时返回 null。 */
    fun stopAndSave(context: Context, requestedName: String?): String? {
        val result = MacroRecorder.stop(context) ?: return null
        return finish(context, result, requestedName)
    }

    /**
     * 把已经停止的录制结果落盘并发通知。
     *
     * @return 实际保存的宏名；没有可回放动作时返回 null（仍会发一条"没录到内容"的通知）。
     */
    fun finish(context: Context, result: MacroRecorder.StopResult, requestedName: String?): String? {
        val appContext = context.applicationContext
        val saved = runCatching {
            if (result.actions.isEmpty()) return@runCatching null
            val name = MacroRecorder.normalizeName(requestedName ?: result.suggestedName)
                ?: MacroRecorder.defaultName()
            GuiMacroStore.save(
                name = name,
                description = appContext.getString(R.string.macro_recording_saved_description),
                actions = org.json.JSONArray().also { array -> result.actions.forEach { array.put(it.toJson()) } },
                overwrite = true,
                source = GuiMacroStore.SOURCE_RECORDING,
            )
            name
        }.getOrElse { throwable ->
            AndroidAgentLogger.warn("Macro recording save failed: type=${throwable.safeLogType()}")
            null
        }
        val summary = when {
            saved == null -> appContext.getString(R.string.macro_recording_no_actions)
            result.stoppedByLimit -> appContext.getString(
                R.string.macro_recording_saved_summary_limit,
                saved,
                result.actions.size,
                result.stats.sensitiveSkipped,
            )
            else -> appContext.getString(
                R.string.macro_recording_saved_summary,
                saved,
                result.actions.size,
                result.stats.sensitiveSkipped,
            )
        }
        MacroRecordingNotifications.showStopped(appContext, summary)
        return saved
    }
}
