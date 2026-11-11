package io.github.mangi.eta.agent.terminal

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.notify.AgentNotifications
import io.github.mangi.eta.agent.notify.NotificationPolicy
import io.github.mangi.eta.agent.runtime.AgentRuntimeWire

/**
 * 后台任务自动唤醒的通知出口。
 *
 * 无人值守的唤醒 run 不在用户的会话列表里，通知是唯一"看得见有人在替你收拾"的地方，
 * 因此：
 * - 点击通知直接进那一轮处理的归档会话（[AgentNotifications.archivedConversationIntent]），
 *   而不是把用户丢回首页；
 * - 另给一个"打开任务中心"按钮看全部后台任务；
 * - 通知 id 走 [NotificationPolicy.jobWakeupId]（9602..9651），同一任务反复唤醒原地更新；
 * - PendingIntent 的 requestCode 与 data 都按任务派生：固定值 + FLAG_UPDATE_CURRENT 会让
 *   多条通知的会话参数互相覆盖（extras 不参与 PendingIntent 判等）；
 * - channel 复用"后台命令结果"，不新增一条需要用户单独静音的 channel。
 *
 * **收尾纪律**：只要发出过"正在自动处理"，这一轮结束时（成功 / 失败 / 超时 / 未启动）
 * 必须用同 ID 更新成终态并 setAutoCancel(true)——不能留一条永远显示"正在处理"的通知。
 */
internal object JobWakeupNotifications {

    /** 正在自动处理（终态未到达前一直可点、可滑动取消）。 */
    fun showRunning(context: Context, task: DetachedTask, watched: Boolean) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        ensureChannel(context, manager)
        val titleId = if (watched) R.string.job_wakeup_title_watch else R.string.job_wakeup_title
        val bodySuffix = context.getString(
            if (watched) R.string.job_wakeup_body_suffix_watch else R.string.job_wakeup_body_suffix,
        )
        val status = statusText(context, task)
        val body = if (status.isBlank()) bodySuffix else "$status · $bodySuffix"
        val notification = base(context, task)
            .setContentTitle(context.getString(titleId, subjectOf(context, task)))
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .build()
        if (manager.areNotificationsEnabled()) manager.notify(NotificationPolicy.jobWakeupId(task.id), notification)
    }

    /**
     * 用同一 ID 把"正在自动处理"更新成终态。
     *
     * 终态取值只有一处定义（[JobWakeupPolicy.Finish]）：通知文案与执行侧判定必须对齐，
     * 各自再定义一份枚举只会漂移。
     */
    fun showFinished(context: Context, task: DetachedTask, finish: JobWakeupPolicy.Finish) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        ensureChannel(context, manager)
        val titleId = when (finish) {
            JobWakeupPolicy.Finish.DONE -> R.string.job_wakeup_done_title
            JobWakeupPolicy.Finish.FAILED -> R.string.job_wakeup_failed_title
            JobWakeupPolicy.Finish.TIMED_OUT -> R.string.job_wakeup_timeout_title
            JobWakeupPolicy.Finish.NOT_STARTED -> R.string.job_wakeup_skipped_title
        }
        val bodyId = when (finish) {
            JobWakeupPolicy.Finish.DONE -> R.string.job_wakeup_done_body
            JobWakeupPolicy.Finish.FAILED -> R.string.job_wakeup_failed_body
            JobWakeupPolicy.Finish.TIMED_OUT -> R.string.job_wakeup_timeout_body
            JobWakeupPolicy.Finish.NOT_STARTED -> R.string.job_wakeup_skipped_body
        }
        val body = context.getString(bodyId)
        val notification = base(context, task)
            .setContentTitle(context.getString(titleId, subjectOf(context, task)))
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .build()
        if (manager.areNotificationsEnabled()) manager.notify(NotificationPolicy.jobWakeupId(task.id), notification)
    }

    /** 超过连续唤醒上限 / 全局配额用尽：只告知，不再唤醒。 */
    fun showChainLimit(context: Context, task: DetachedTask, globalLimit: Boolean = false) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        ensureChannel(context, manager)
        val body = if (globalLimit) {
            context.getString(R.string.job_wakeup_reason_global_limit)
        } else {
            context.getString(R.string.job_wakeup_chain_body, JobWakeupPolicy.MAX_CHAIN_WAKEUPS)
        }
        val notification = base(context, task)
            .setContentTitle(context.getString(R.string.job_wakeup_chain_title, subjectOf(context, task)))
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .build()
        if (manager.areNotificationsEnabled()) manager.notify(NotificationPolicy.jobWakeupId(task.id), notification)
    }

    /**
     * 通知与归档标题共用的"这一条任务是谁"。
     *
     * 描述为空时退到固定文案 + job_id，**绝不拿命令首行**：失败命令里常带 token / 凭据，
     * 而通知标题会出现在锁屏与通知历史里。
     */
    internal fun subjectOf(context: Context, task: DetachedTask): String = JobWakeupPolicy.subject(
        description = task.description,
        fallback = context.getString(R.string.job_wakeup_unknown_subject, task.id),
    )

    private fun base(context: Context, task: DetachedTask): Notification.Builder {
        val conversationKey = JobWakeupPolicy.conversationKey(task.id)
        return AgentNotifications.builder(context, BackgroundCommandNotification.CHANNEL)
            .setSubText(context.getString(R.string.job_wakeup_subtext))
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_STATUS)
            // 主体：进这一轮处理的归档会话，跑完之后用户能看到 Agent 到底做了什么。
            .setContentIntent(
                AgentNotifications.archivedConversationIntent(
                    context = context,
                    requestCode = NotificationPolicy.jobWakeupRequestCode(task.id),
                    source = AgentRuntimeWire.ETA_JOB_WAKEUP_HANDOFF_SOURCE,
                    conversationKey = conversationKey,
                    distinctData = "$JOB_WAKEUP_DATA_PREFIX${task.id}",
                ),
            )
            .addAction(
                Notification.Action.Builder(
                    null,
                    context.getString(R.string.notify_action_open_task_center),
                    AgentNotifications.taskCenterIntent(
                        context,
                        NotificationPolicy.jobWakeupActionRequestCode(task.id),
                        "RUNNING",
                    ),
                ).build(),
            )
    }

    /** 退出码 / 结束原因 / 耗时；三者都可能缺失，缺失就不显示（不编造 0）。 */
    private fun statusText(context: Context, task: DetachedTask): String = buildList {
        task.exitCode?.let { add(context.getString(R.string.job_wakeup_status_exit, it)) }
        task.endReason?.let { add(context.getString(R.string.job_wakeup_status_reason, it)) }
        task.completedAt?.let { completed ->
            val seconds = (completed - task.startedAt).coerceAtLeast(0L) / 1_000L
            add(String.format(context.getString(R.string.notify_elapsed_format), seconds))
        }
    }.joinToString(" · ")

    private fun ensureChannel(context: Context, manager: NotificationManager) {
        manager.createNotificationChannel(
            NotificationChannel(
                BackgroundCommandNotification.CHANNEL,
                context.getString(R.string.background_command_channel),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.background_command_channel_description)
            },
        )
    }

    private const val JOB_WAKEUP_DATA_PREFIX = "eta://job-wakeup/"
}
