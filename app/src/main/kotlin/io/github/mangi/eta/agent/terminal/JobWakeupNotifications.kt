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
 * - **运行中常驻 + 阶段可见**：showRunning 起一条 setOngoing(true) 的通知，run 期间由
 *   [updateRunning] 把 AgentEvent 映射成的阶段文案同 ID 就地变形（"正在调用 xx 工具"…），
 *   用户不点开也能看见这一轮在做什么；终态 [showFinished] 重建为可滑掉的通知收尾。
 * - channel 复用"后台命令结果"，不新增需要用户单独静音的频道；若该 channel 被用户关掉，
 *   退到已注册的主动沟通频道兜底，避免"正在自动处理"彻底消失。
 *
 * **收尾纪律**：只要发出过"正在自动处理"，这一轮结束时（成功 / 失败 / 超时 / 未启动）
 * 必须用同 ID 更新成终态并 setAutoCancel(true)——不能留一条永远显示"正在处理"的通知。
 */
internal object JobWakeupNotifications {

    /**
     * App 启动时清掉上一次进程遗留的 9602..9651 常驻通知。
     *
     * showRunning 是 setOngoing(true) 的，用户滑不掉；若进程在 run 期间被系统回收，
     * 没人调 cancel，通知栏会留一条永远"正在自动处理"的僵尸。号段取自
     * [io.github.mangi.eta.agent.notify.NotificationIdRegistry]（唯一登记表），不硬编码漂移。
     */
    fun cancelStale(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val segments = io.github.mangi.eta.agent.notify.NotificationIdRegistry.allIdSegments
            .filter { it.owner == io.github.mangi.eta.agent.notify.NotificationIdRegistry.OWNER_JOB_WAKEUP }
        for (segment in segments) {
            for (id in segment.range) {
                runCatching { manager.cancel(id) }
            }
        }
        lastStageAt.clear()
    }

    /** 正在自动处理（终态未到达前常驻、随阶段更新）。 */
    fun showRunning(context: Context, task: DetachedTask, watched: Boolean) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        ensureChannel(context, manager)
        val titleId = if (watched) R.string.job_wakeup_title_watch else R.string.job_wakeup_title
        val bodySuffix = context.getString(
            if (watched) R.string.job_wakeup_body_suffix_watch else R.string.job_wakeup_body_suffix,
        )
        val status = statusText(context, task)
        val body = if (status.isBlank()) bodySuffix else "$status · $bodySuffix"
        val notification = base(context, task, resolveChannel(manager))
            .setContentTitle(context.getString(titleId, subjectOf(context, task)))
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            // 运行期间常驻、不可被误滑掉；终态 showFinished 会重建为可滑掉的通知。
            .setOngoing(true)
            .build()
        notifyIfAllowed(manager, task, notification)
    }

    /**
     * 运行期间把「正在自动处理」原地更新成当前阶段（同 ID 变形，不新增通知）。
     *
     * 由 BackgroundJobWakeup 把 Runtime 的 AgentEvent 映射成短文案传进来，
     * 用户不点开也能看见「正在调用 xx 工具 / 正在整理回答 / 第 N 轮」。
     * ≥1s 节流（每条任务独立计时），避免高频事件把通知行反复重绑、收起 RemoteInput。
     */
    fun updateRunning(context: Context, task: DetachedTask, stage: String, watched: Boolean = false) {
        if (stage.isBlank()) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val now = android.os.SystemClock.elapsedRealtime()
        val last = lastStageAt[task.id] ?: 0L
        if (now - last < STAGE_THROTTLE_MILLIS) return
        lastStageAt[task.id] = now
        ensureChannel(context, manager)
        val status = statusText(context, task)
        val body = buildList {
            if (status.isNotBlank()) add(status)
            add(stage)
        }.joinToString(" · ")
        val titleId = if (watched) R.string.job_wakeup_title_watch else R.string.job_wakeup_title
        val title = context.getString(titleId, subjectOf(context, task))
        val notification = base(context, task, resolveChannel(manager))
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setOngoing(true)
            .build()
        notifyIfAllowed(manager, task, notification)
    }

    /** 阶段更新节流：每条任务独立计时。 */
    private val lastStageAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private const val STAGE_THROTTLE_MILLIS = 1_000L

    /**
     * 用同一 ID 把"正在自动处理"更新成终态（可滑掉、不再 ongoing）。
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
            JobWakeupPolicy.Finish.STOPPED -> R.string.job_wakeup_stopped_title
        }
        val bodyId = when (finish) {
            JobWakeupPolicy.Finish.DONE -> R.string.job_wakeup_done_body
            JobWakeupPolicy.Finish.FAILED -> R.string.job_wakeup_failed_body
            JobWakeupPolicy.Finish.TIMED_OUT -> R.string.job_wakeup_timeout_body
            JobWakeupPolicy.Finish.NOT_STARTED -> R.string.job_wakeup_skipped_body
            JobWakeupPolicy.Finish.STOPPED -> R.string.job_wakeup_stopped_body
        }
        val body = context.getString(bodyId)
        val notification = base(context, task, resolveChannel(manager))
            .setContentTitle(context.getString(titleId, subjectOf(context, task)))
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            // 运行中的同 ID 通知是 onlyAlertOnce，终态显式放开，让「跑完了」有一次可见提醒。
            .setOnlyAlertOnce(false)
            .build()
        lastStageAt.remove(task.id)
        notifyIfAllowed(manager, task, notification)
    }

    /** 上一条无人值守 run 还没结束：只告知"这次没接手"，不伪装成失败/未启动终态。 */
    fun showBusy(context: Context, task: DetachedTask) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        ensureChannel(context, manager)
        val body = context.getString(R.string.job_wakeup_busy_body)
        val notification = base(context, task, resolveChannel(manager))
            .setContentTitle(context.getString(R.string.job_wakeup_busy_title, subjectOf(context, task)))
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .build()
        lastStageAt.remove(task.id)
        notifyIfAllowed(manager, task, notification)
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
        val notification = base(context, task, resolveChannel(manager))
            .setContentTitle(context.getString(R.string.job_wakeup_chain_title, subjectOf(context, task)))
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .build()
        lastStageAt.remove(task.id)
        notifyIfAllowed(manager, task, notification)
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

    private fun base(
        context: Context,
        task: DetachedTask,
        channelId: String,
    ): Notification.Builder {
        val conversationKey = JobWakeupPolicy.conversationKey(task.id)
        return AgentNotifications.builder(context, channelId)
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

    /**
     * 本 channel 被用户关掉/降级时，退到已注册的主动沟通频道，避免通知彻底消失。
     * 这是「经常不显示」的常见原因之一：以前只查全局开关，channel 被关就静默无效。
     */
    private fun resolveChannel(manager: NotificationManager): String {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) {
            return BackgroundCommandNotification.CHANNEL
        }
        val channel = runCatching {
            manager.getNotificationChannel(BackgroundCommandNotification.CHANNEL)
        }.getOrNull()
        return if (channel != null && channel.importance == NotificationManager.IMPORTANCE_NONE) {
            NotificationPolicy.CHANNEL_PROACTIVE
        } else {
            BackgroundCommandNotification.CHANNEL
        }
    }

    /** 全局开关开才发；channel 降级已由 [resolveChannel] 在 build 前决定。 */
    private fun notifyIfAllowed(manager: NotificationManager, task: DetachedTask, notification: Notification) {
        if (!manager.areNotificationsEnabled()) return
        runCatching { manager.notify(NotificationPolicy.jobWakeupId(task.id), notification) }
    }

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
