package io.github.mangi.eta.agent.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.approval.AgentApprovalCoordinator
import io.github.mangi.eta.agent.overlay.AgentOverlayStatus
import io.github.mangi.eta.agent.overlay.localizedText
import io.github.mangi.eta.agent.overlay.toolDisplayName
import io.github.mangi.eta.agent.notify.AgentNotifications
import io.github.mangi.eta.agent.notify.NotificationPolicy
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.ui.MainActivity
import java.util.concurrent.atomic.AtomicLong

/** 只在用户任务存活期间持有前台执行生命周期；进程被系统停止后不重放任务。 */
internal class AgentExecutionService : Service() {
    private val stopQueue = ExecutionStopQueue { failure ->
        AndroidAgentLogger.warn("Execution task stop failed: type=${failure.safeLogType()}")
    }
    private val owner = ownerSequence.incrementAndGet()
    private var foregroundActive = false
    @Volatile private var startRejected = false
    /** 等待用户确认的卡片变化时刷新常驻通知，让浮层/后台场景也能感知"有操作在等确认"。 */
    private var approvalListener: (() -> Unit)? = null
    /**
     * 常驻通知的实时耗时心跳：每秒刷新一次，让"运行中"显示真实的已用时，
     * 而不是一条直到状态变化才更新的静态文案。节流由 [NotificationPolicy.Throttle] 保证 ≥1s。
     */
    private val elapsedHandler = Handler(Looper.getMainLooper())
    private val elapsedThrottle = NotificationPolicy.Throttle()
    private val elapsedTick = object : Runnable {
        override fun run() {
            if (!foregroundActive || runStatus == null) return
            val now = SystemClock.elapsedRealtime()
            val enabled = runStartedAtMillis > 0L
            val seconds = if (enabled) (now - runStartedAtMillis).coerceAtLeast(0L) / 1_000L else 0L
            // key 带上秒数：同一秒内不重复 notify，跨秒才刷新。
            // key 用 5s 粒度：同一窗口内不重复 notify，跨窗口才刷新（配合 5s 心跳省电）。
            val bucket = seconds / (NotificationPolicy.SLOW_REFRESH_INTERVAL_MILLIS / 1_000L)
            if (elapsedThrottle.shouldEmit("$runStatus|$runRound|$bucket", now)) {
                runCatching {
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
                }
            }
            elapsedHandler.postDelayed(this, NotificationPolicy.SLOW_REFRESH_INTERVAL_MILLIS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        leases.attachOwner(owner)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.execution_channel), NotificationManager.IMPORTANCE_LOW),
        )
        approvalListener = AgentApprovalCoordinator.shared.addPendingListener {
            mainHandler.post { if (instance === this && foregroundActive) refreshNotification() }
        }
        ensureForeground()
    }

    /** 仅在"前台服务活着 + 有 Agent run 在跑"时开启耗时心跳；其余情况立刻停掉。 */
    private fun syncElapsedTicker() {
        elapsedHandler.removeCallbacks(elapsedTick)
        if (foregroundActive && runStatus != null) {
            elapsedThrottle.reset()
            // 用 postDelayed 起步：不抢在 startForeground 那一条之后立刻重复 notify。
            elapsedHandler.postDelayed(elapsedTick, NotificationPolicy.SLOW_REFRESH_INTERVAL_MILLIS)
        }
    }

    private fun ensureForeground() {
        if (foregroundActive || startRejected) return
        leases.attachOwner(owner)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification())
            }
            foregroundActive = true
            syncElapsedTicker()
        } catch (failure: RuntimeException) {
            startRejected = true
            AndroidAgentLogger.warn("Execution service foreground failed: type=${failure.safeLogType()}")
            stopTasks(startFailed = true)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopTasks()
        } else {
            ensureForeground()
            refreshNotification()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        elapsedHandler.removeCallbacks(elapsedTick)
        approvalListener?.invoke()
        approvalListener = null
        if (instance === this) instance = null
        // 销毁时同样收回本服务拥有的任务。回收在独立有界工作线程上完成，不阻塞 Main。
        stopQueue.close(leases.drainOwner(owner))
        super.onDestroy()
    }

    private fun stopTasks(startFailed: Boolean = false) {
        val callbacks = leases.drain(startFailed)
        stopQueue.submit(callbacks) {
            mainHandler.post { if (instance === this) refreshNotification() }
        }
    }

    private fun refreshNotification() {
        if (leases.closeOwnerIfIdle(owner)) {
            foregroundActive = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } else {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
        }
    }

    private fun notification(): Notification {
        val status = runStatus
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, AgentExecutionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val taskCount = if (activeRunCount > 0) activeRunCount else leases.count()
        val actionText = status?.localizedText(resources) ?: getString(R.string.execution_title)
        val detail = runDetail.ifBlank { runThought }.take(240)
        // 待确认数量由专用的"待确认"常驻通知负责展示（可直达待确认区、可滑掉），
        // 执行通知这里始终显示当前动作/细节，不再被数量文案顶掉。
        val contentText = detail.ifBlank { executionText(this, status, taskCount) }
        val builder = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(actionText)
            .setContentText(contentText)
            .setSubText(ongoingSubText(this, taskCount))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.execution_stop), stop).build())
        if (status == null && detail.isNotBlank()) {
            builder.setStyle(Notification.BigTextStyle().bigText(detail))
        }
        // 只有 Agent run 在跑时才申请提升为 Live Update；终端会话、Linux 启动等长驻任务不属于"正在进行、需要持续关注"的活动。
        if (status != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            builder
                .setStyle(Notification.ProgressStyle().setProgressIndeterminate(true))
                .setShortCriticalText(status.shortText())
            // 请求方法从 API 36.1 才公开；标准 extra 也可供支持该协议的 API 36 系统读取。
            builder.extras.putBoolean("android.requestPromotedOngoing", true)
        }
        return builder.build()
    }

    /** 状态栏胶囊约 7 个字符可完整显示；更长时只取动作，工具名留在展开态。 */
    private fun AgentOverlayStatus.shortText(): String = when (this) {
        is AgentOverlayStatus.RunningTool -> toolDisplayName(resources, name).take(7).trim()
        is AgentOverlayStatus.HostedToolRunning -> getString(R.string.execution_chip_running)
        AgentOverlayStatus.Paused -> getString(R.string.overlay_paused)
        AgentOverlayStatus.Stopping -> getString(R.string.overlay_stopping)
        AgentOverlayStatus.ResultReady -> getString(R.string.overlay_result_ready)
        AgentOverlayStatus.RunFailed -> getString(R.string.overlay_run_failed)
        AgentOverlayStatus.Stopped -> getString(R.string.overlay_stopped)
        AgentOverlayStatus.GeneratingAnswer -> getString(R.string.execution_chip_answering)
        else -> getString(R.string.overlay_reasoning)
    }

    companion object {
        private const val CHANNEL = "eta_execution"
        private const val NOTIFICATION_ID = 1107
        private const val RESULT_CHANNEL = "eta_run_results"
        private const val RESULT_NOTIFICATION_ID = 1108
        private const val ACTION_STOP = "io.github.mangi.eta.action.STOP_USER_EXECUTION"
        private val leases = ExecutionLeaseRegistry()
        private val ownerSequence = AtomicLong()
        private val mainHandler = Handler(Looper.getMainLooper())
        @Volatile private var instance: AgentExecutionService? = null
        private var runStatus: AgentOverlayStatus? = null
        private var activeRunCount = 0
        private var publishedStatusText: String? = null
        private var runRound = 0
        /** 本段 Agent run 的实际开始时间（elapsedRealtime）；0 表示当前没有 run。 */
        private var runStartedAtMillis = 0L
        private var runDetail = ""
        private var runThought = ""
        private var resultPublished = false

        /**
         * Agent run 的当前状态，供通知与状态栏胶囊展示；null 表示没有 Agent run 在跑。
         * 只在显示文字变化时刷新通知，避免流式事件把通知更新频率推到系统限流。
         */
        fun updateRunStatus(
            context: Context,
            status: AgentOverlayStatus?,
            activeRunCount: Int,
            round: Int = 0,
            detailText: String = "",
            thought: String = "",
            finished: Boolean = false,
            failed: Boolean = false,
            conversationId: String? = null,
            handoffSource: String? = null,
        ) {
            mainHandler.post {
                // 记录/清理本段运行的实际开始时间：用于常驻通知里的实时耗时。
                if (status != null && runStatus == null) {
                    runStartedAtMillis = SystemClock.elapsedRealtime()
                } else if (status == null) {
                    runStartedAtMillis = 0L
                }
                runStatus = status
                this.activeRunCount = activeRunCount
                runRound = round
                runDetail = detailText
                runThought = thought
                if (status != null && !finished && !failed) resultPublished = false
                val count = if (activeRunCount > 0) activeRunCount else leases.count()
                val text = listOf(
                    executionText(context, status, count),
                    status?.localizedText(context.resources).orEmpty(),
                    detailText.take(48),
                    (detailText.length / 96).toString(),
                    (thought.length / 32).toString(),
                    round.toString(),
                ).joinToString("|")
                if (text != publishedStatusText) {
                    publishedStatusText = text
                    instance?.takeIf { it.foregroundActive }?.refreshNotification()
                }
                instance?.syncElapsedTicker()
                if ((finished || failed) && !resultPublished && conversationId != null) {
                    // 前台 UI 聊天一轮说完（成功或失败）：结果/错误就在屏幕上，不再弹通知刷屏、占通知栏。
                    val foregroundUiChat = handoffSource == AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE &&
                        MainActivity.isInForeground
                    if (!foregroundUiChat) {
                        // 后台/无 UI 的 run 用 archive-/run:/assistant- 前缀会话 id：它们不在可回复的
                        // UI 会话表里，挂回复按钮会「点了必然失败」，所以这类通知不挂回复、查看结果
                        // 退回任务中心。UI 会话（App 不在前台时跑完）才挂通知栏回复 + 按会话深链。
                        val uiConversation = AgentConversationIdentity.isUiConversation(conversationId)
                        resultPublished = true
                        showRunResult(context, failed, detailText, conversationId, uiConversation)
                    }
                }
            }
        }

        /** 后台完成时给出可消散的结果卡；成功安静、失败保留，均不替代常驻执行通知。 */
        private fun showRunResult(
            context: Context,
            failed: Boolean,
            detail: String,
            conversationId: String,
            uiConversation: Boolean,
        ) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            manager.createNotificationChannel(
                NotificationChannel(
                    RESULT_CHANNEL,
                    context.getString(R.string.execution_result_channel),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
            )
            // UI 会话：点主体 /「查看结果」按会话 id 直达；后台归档会话：退回任务中心
            // （归档会话另有 ACTION_OPEN_ARCHIVED_CONVERSATION 入口，不拿普通会话 id 硬开空白页）。
            val open = if (uiConversation) {
                AgentNotifications.conversationByIdIntent(
                    context,
                    20,
                    conversationId,
                    "eta://eta.runresult/open/$conversationId",
                )
            } else {
                AgentNotifications.taskCenterIntent(context, 20, "RUNNING")
            }
            val text = detail.ifBlank {
                context.getString(
                    if (failed) R.string.execution_result_failed_hint else R.string.execution_result_done_hint,
                )
            }
            val notification = Notification.Builder(context, RESULT_CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(
                    context.getString(
                        if (failed) R.string.execution_result_failed_title else R.string.execution_result_done_title,
                    ),
                )
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_MESSAGE)
                .apply {
                    // 仅 UI 会话挂「回复」：后台归档会话挂了会点了必失败、丢用户打的字。
                    if (uiConversation) {
                        addAction(AgentNotifications.replyAction(context, RESULT_NOTIFICATION_ID, conversationId))
                    }
                }
                .addAction(
                    Notification.Action.Builder(
                        null,
                        context.getString(R.string.notify_action_view_result),
                        open,
                    ).build(),
                )
                .addAction(
                    Notification.Action.Builder(
                        null,
                        context.getString(R.string.notify_action_open_task_center),
                        AgentNotifications.taskCenterIntent(context, RESULT_NOTIFICATION_ID + 1, "RUNNING"),
                    ).build(),
                )
                .build()
            if (manager.areNotificationsEnabled()) manager.notify(RESULT_NOTIFICATION_ID, notification)
        }

        private fun ongoingSubText(context: Context, taskCount: Int): String? {
            // companion 里没有 Context：所有本地化都必须走调用方传进来的 context
            // （之前直接调 getString 是编译错误，也是 CI 报 unresolved reference 的原因）。
            val parts = buildList {
                if (runRound > 0) add(context.getString(R.string.execution_subtext_round, runRound))
                if (taskCount > 0) add(context.getString(R.string.execution_subtext_tasks, taskCount))
                if (runStartedAtMillis > 0L) {
                    val elapsed = (SystemClock.elapsedRealtime() - runStartedAtMillis).coerceAtLeast(0L)
                    add(String.format(context.getString(R.string.notify_elapsed_format), elapsed / 1_000L))
                }
            }
            // 三段都为空时返回 null：不要给通知塞一个空 subText（会多出一行空白）。
            return parts.joinToString(" · ").ifBlank { null }
        }

        private fun executionText(context: Context, status: AgentOverlayStatus?, taskCount: Int): String {
            val summary = context.resources.getQuantityString(R.plurals.execution_summary, taskCount, taskCount)
            return when {
                status == null -> summary
                taskCount <= 1 -> status.localizedText(context.resources)
                else -> "$summary · ${status.localizedText(context.resources)}"
            }
        }

        /** 必须从有效的用户入口取得引用，再创建会话或子进程；失败时调用方不启动任务。 */
        fun acquire(
            context: Context,
            id: String,
            allowBoundFallback: Boolean = false,
            onStop: () -> Unit,
        ): Boolean {
            if (instance?.startRejected == true) return false
            if (!leases.acquire(id, allowBoundFallback, onStop)) return true
            return try {
                context.applicationContext.startForegroundService(Intent(context, AgentExecutionService::class.java))
                true
            } catch (failure: RuntimeException) {
                leases.release(id)
                AndroidAgentLogger.warn("Execution service start rejected: type=${failure.safeLogType()}")
                false
            }
        }

        fun release(id: String) {
            leases.release(id)
            mainHandler.post { instance?.refreshNotification() }
        }
    }
}
