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
                    val tail = logTail(hostLogPath(task))
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

    /** 与 DetachedTaskSupervisor 相同的 wire 路径映射，避免 App 进程读到不存在的容器路径。 */
    private fun hostLogPath(task: DetachedTask): String {
        val wire = task.logPath
        if (!task.environment.isLinux || !wire.startsWith("/workspace/daemon")) return wire
        val root = task.hostWorkspace?.let { "$it/daemon" } ?: "/data/local/tmp/eta/daemon"
        return root + wire.removePrefix("/workspace/daemon")
    }

    /** 只读日志尾部 8KB，Linux 任务先映射到宿主路径；失败不影响通知。 */
    private fun logTail(path: String): String = runCatching {
        val file = java.io.File(path)
        if (!file.isFile || file.length() == 0L) return@runCatching ""
        java.io.RandomAccessFile(file, "r").use { reader ->
            val start = (reader.length() - 8 * 1024).coerceAtLeast(0)
            reader.seek(start)
            val bytes = ByteArray((reader.length() - start).toInt())
            reader.readFully(bytes)
            String(bytes, Charsets.UTF_8).takeLast(1200).trim()
        }
    }.getOrDefault("")
}
