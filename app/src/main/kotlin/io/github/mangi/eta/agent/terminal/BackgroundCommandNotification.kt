package io.github.mangi.eta.agent.terminal

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.MainActivity

internal object BackgroundCommandNotification {
    private const val CHANNEL = "eta_background_commands"
    private const val NOTIFICATION_ID = 1124

    fun show(context: Context, task: DetachedTask) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "后台命令结果", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(context, 0,
            Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_OPEN_TERMINAL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val title = when (task.commandStatus) {
            "completed" -> "后台命令已完成"
            "timed_out" -> "后台命令已超时"
            "stopped" -> "后台命令已停止"
            "interrupted" -> "后台命令已中断"
            else -> "后台命令执行失败"
        }
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(task.description.ifBlank { "在终端的后台任务中查看状态和日志" })
            .setSubText(buildString {
                task.exitCode?.let { append("退出码 $it") }
                task.completedAt?.let { completed ->
                    if (isNotEmpty()) append(" · ")
                    append("耗时 ${(completed - task.startedAt).coerceAtLeast(0) / 1000}s")
                }
            })
            .setStyle(
                Notification.BigTextStyle().bigText(buildString {
                    append(task.description.ifBlank { task.command.take(300) })
                    val tail = logTail(task.logPath)
                    if (tail.isNotBlank()) {
                        append("\n\n日志末尾：\n")
                        append(tail)
                    }
                }),
            )
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        if (manager.areNotificationsEnabled()) manager.notify(task.id, NOTIFICATION_ID, notification)
    }

    /** 只读截取日志最后 1200 字符，失败时不影响通知发送。 */
    private fun logTail(path: String): String = runCatching {
        val file = java.io.File(path)
        if (!file.isFile || file.length() == 0L || file.length() > 2 * 1024 * 1024) return@runCatching ""
        val bytes = file.readBytes()
        String(bytes, Charsets.UTF_8).takeLast(1200).trim()
    }.getOrDefault("")
}
