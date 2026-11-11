package io.github.mangi.eta.agent.terminal

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.notify.AgentNotifications
import io.github.mangi.eta.ui.MainActivity

/**
 * 后台命令/守护任务的结果通知。
 *
 * 审查改进（在不动既有断言的前提下）：
 * - 补一个"打开任务中心"动作，结果不再只有"点主体进终端"一条路；
 * - 点击/动作统一走 [AgentNotifications]，channel 名改为本地化资源（与其它通知保持同一套语义）。
 *
 * 说明：状态标题与正文沿用原有中文文案——既有 Robolectric 测试
 * `BackgroundCommandNotificationTest` 精确断言了这些字面值，改名会破坏基线；
 * 该通知的本地化留作后续与测试同步更新，不在此轮硬改。
 */
internal object BackgroundCommandNotification {
    /** 与自动唤醒通知共用同一条 channel：语义同属"后台命令结果"，避免多出一条用户要单独静音的 channel。 */
    internal const val CHANNEL = "eta_background_commands"
    private const val NOTIFICATION_ID = 1124

    fun show(context: Context, task: DetachedTask) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                context.getString(R.string.background_command_channel),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.background_command_channel_description)
            },
        )
        val open = PendingIntent.getActivity(
            context,
            AgentNotifications.RequestCodes.BACKGROUND_COMMAND_CONTENT,
            Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_OPEN_TERMINAL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val title = when (task.commandStatus) {
            "completed" -> "后台命令已完成"
            "timed_out" -> "后台命令已超时"
            "stopped" -> "后台命令已停止"
            "interrupted" -> "后台命令已中断"
            else -> "后台命令执行失败"
        }
        val notification = AgentNotifications.builder(context, CHANNEL)
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
            .addAction(
                Notification.Action.Builder(
                    null,
                    context.getString(R.string.notify_action_open_task_center),
                    AgentNotifications.taskCenterIntent(context, NOTIFICATION_ID + 1, "RUNNING"),
                ).build(),
            )
            .build()
        if (manager.areNotificationsEnabled()) manager.notify(task.id, NOTIFICATION_ID, notification)
    }

    /** 与 DetachedTaskSupervisor 相同的 wire 路径映射，避免 App 进程读到不存在的容器路径。 */
    internal fun hostLogPath(task: DetachedTask): String {
        val wire = task.logPath
        if (!task.environment.isLinux || !wire.startsWith("/workspace/daemon")) return wire
        val root = task.hostWorkspace?.let { "$it/daemon" } ?: "/data/local/tmp/eta/daemon"
        return root + wire.removePrefix("/workspace/daemon")
    }

    /** 通知里展示的日志尾部：默认只读 8KB、截最后 1200 字符。 */
    private fun logTail(path: String): String = logTail(path, maxBytes = 8 * 1024, maxChars = 1_200)

    /**
     * 只读日志尾部；Linux 任务先映射到宿主路径。
     *
     * 上限由调用方给：通知只需要一小段预览，而自动唤醒要把最后 [JobWakeupPolicy.OUTPUT_TAIL_CHARS]
     * 个字符贴进 prompt，两者共用同一份读取实现，避免两套截断语义漂移。读取失败返回空串。
     */
    internal fun logTail(task: DetachedTask, maxBytes: Int, maxChars: Int): String =
        logTail(hostLogPath(task), maxBytes, maxChars)

    private fun logTail(path: String, maxBytes: Int, maxChars: Int): String = runCatching {
        val file = java.io.File(path)
        if (!file.isFile || file.length() == 0L) return@runCatching ""
        java.io.RandomAccessFile(file, "r").use { reader ->
            val start = (reader.length() - maxBytes.coerceAtLeast(1)).coerceAtLeast(0)
            reader.seek(start)
            val bytes = ByteArray((reader.length() - start).toInt())
            reader.readFully(bytes)
            String(bytes, Charsets.UTF_8).takeLast(maxChars.coerceAtLeast(1)).trim()
        }
    }.getOrDefault("")
}
