package io.github.mangi.eta.agent.schedule

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.notify.AgentNotifications
import io.github.mangi.eta.agent.notify.NotificationPolicy
import io.github.mangi.eta.agent.notify.AttentionIntent
import io.github.mangi.eta.agent.notify.NotificationKey
import io.github.mangi.eta.agent.notify.AttentionPublisher
import io.github.mangi.eta.agent.notify.NotificationIdRegistry

/**
 * 自动任务的通知：执行中（同时是前台服务通知）与执行结果各一条。
 *
 * 通知是无人值守任务唯一对用户可见的出口，所以：
 * - **执行中**：显示实时阶段与耗时（[running] / [updateRunning]），由 [ScheduledTaskService]
 *   按 ≥1s 节流刷新，而不是一条永远不变的"执行中"；
 * - **结果**：固定带上"成功/失败"与摘要，并提供"查看结果 / 打开任务中心"两个可用动作。
 *
 * 通知点击直达任务中心的"自动任务"分区，而不是回首页让用户自己找。
 */
internal object ScheduledTaskNotifications {

    /**
     * 通知 id 分段（审查 m4）：
     * - 9000：自动任务前台服务"执行中"；
     * - 9001..9290：各任务的结果通知（按任务 id 散列，互不覆盖）；
     * - 宏录制用 9600/9601（见 MacroRecordingNotifications）；
     * - 本次新增的主动沟通用 9300..9349、待确认 9400（见 NotificationPolicy）。
     */
    private const val RUNNING_NOTIFICATION_ID = 9_000

    fun ensureChannel(context: Context) = AttentionPublisher.ensureAllChannels(context)

    /**
     * "执行中"通知的初始版本：带上阶段与耗时基线。
     *
     * @param stage 当前阶段（已本地化，如"运行中"）；刷新时传入最新阶段即可原地更新。
     * @param elapsedMillis 已运行时长（单调时钟差值）。
     */
    fun running(
        context: Context,
        title: String,
        conversationId: String,
        stage: String = "",
        elapsedMillis: Long = 0L,
    ): Notification {
        val builder = baseBuilder(context, title)
            .setContentTitle(context.getString(R.string.schedule_notification_running_title))
            .setContentText(runningText(context, stage, elapsedMillis))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, true)
        // 按会话深链直达：点进去就是那个正在跑、可实时观测 / 可对话的自动任务会话。
        if (conversationId.isNotBlank()) {
            builder.setContentIntent(
                AgentNotifications.conversationByIdIntent(
                    context, AgentNotifications.RequestCodes.SCHEDULED_CONTENT, conversationId,
                    "eta://eta.scheduled/running/$conversationId",
                ),
            )
        }
        builder.addAction(
            Notification.Action.Builder(
                null,
                context.getString(R.string.notify_action_open_task_center),
                AgentNotifications.taskCenterIntent(context, RUNNING_NOTIFICATION_ID, "SCHEDULED"),
            ).build(),
        )
        return builder.build()
    }

    /** 原地更新"执行中"通知（同一 ID）。调用方负责 ≥1s 节流。 */
    fun updateRunning(
        context: Context,
        title: String,
        conversationId: String,
        stage: String,
        elapsedMillis: Long,
    ) {
        AttentionPublisher.publish(context,
            AttentionIntent(
                key = NotificationKey(NotificationIdRegistry.idOf("scheduled_task_running")),
                channel = NotificationIdRegistry.channelOf("scheduled_task_running"),
                subject = conversationId,
            ), running(context, title, conversationId, stage, elapsedMillis))
    }

    fun runningId(): Int = RUNNING_NOTIFICATION_ID

    fun showResult(
        context: Context,
        task: ScheduledTask,
        succeeded: Boolean,
        summary: String,
    ) {
        val titleRes = if (succeeded) {
            R.string.schedule_notification_done
        } else {
            R.string.schedule_notification_failed
        }
        val body = summary.ifBlank { context.getString(R.string.schedule_notification_no_summary) }
        val resultId = resultNotificationId(task.id)
        val notification = baseBuilder(context, task.title)
            .setContentTitle(context.getString(titleRes, task.title))
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            // 结果通知：主体进"自动任务"分区，另给"打开任务中心"按钮便于看全部。
            .setContentIntent(AgentNotifications.taskCenterIntent(context, resultId, "SCHEDULED"))
            .addAction(
                Notification.Action.Builder(
                    null,
                    context.getString(R.string.notify_action_open_task_center),
                    AgentNotifications.taskCenterIntent(context, resultId + 1, "SCHEDULED"),
                ).build(),
            )
            .build()
        AttentionPublisher.publish(context,
            AttentionIntent(
                key = NotificationKey(resultNotificationId(task.id)),
                channel = NotificationIdRegistry.channelOf("scheduled_task_result"),
                subject = task.id,
            ), notification)
    }

    fun cancel(context: Context, taskId: String) {
        AttentionPublisher.cancel(context, resultNotificationId(taskId))
    }

    /** 执行中通知的正文：阶段 · 已用时；阶段为空时只显示已用时，避免出现" · 12s"这种残句。 */
    private fun runningText(context: Context, stage: String, elapsedMillis: Long): String {
        val elapsedFmt = context.getString(R.string.notify_elapsed_format)
        val stageText = stage.ifBlank { context.getString(R.string.notify_stage_running) }
        return NotificationPolicy.ongoingText(stageText, null, elapsedMillis, elapsedFmt)
    }

    private fun baseBuilder(context: Context, contentText: String): Notification.Builder =
        AgentNotifications.builder(context, NotificationIdRegistry.channelOf("scheduled_task_running"))
            .setContentText(contentText)
            .setContentIntent(
                AgentNotifications.taskCenterIntent(context, AgentNotifications.RequestCodes.SCHEDULED_CONTENT, "SCHEDULED"),
            )

    // 与 showResult 发布、cancel 撤销共用同一派生源，杜绝「两套散列撤错号」。
    private fun resultNotificationId(taskId: String): Int =
        NotificationIdRegistry.idOf("scheduled_task_result", taskId)
}
