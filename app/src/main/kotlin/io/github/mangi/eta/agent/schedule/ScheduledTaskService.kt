package io.github.mangi.eta.agent.schedule

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
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.agent.notify.NotificationPolicy
import io.github.mangi.eta.core.safeLogType
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 自动任务执行期间的前台服务。
 *
 * 闹钟广播的 onReceive 只有几秒预算，而一次 agent run 可能持续数分钟；必须尽快把执行转交给
 * 前台服务，进程才不会在任务中途被系统回收。通知同时是"任务正在跑"的用户可见状态。
 *
 * 两条实现约束：
 * - **onStartCommand 不读文件**（审查 m6）：标题由 Receiver 通过 Intent extra 带过来，
 *   主线程只做建通知与进前台，磁盘 IO 全部留在工作线程；
 * - 收尾用 [stopSelfResult]（审查 m7），避免"新的 startId 已到、旧的收尾把服务停掉"的竞态。
 */
internal class ScheduledTaskService : Service() {
    private val pendingWork = AtomicInteger(0)
    /** 单线程串行：同一时刻只跑一条自动任务，避免互相抢 GUI 与模型配额。 */
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "scheduled-task").apply { isDaemon = true }
    }
    /** "执行中"通知的实时耗时刷新：主线程 1s 一次，独立于执行线程。 */
    private val progressHandler = Handler(Looper.getMainLooper())
    private val progressThrottle = NotificationPolicy.Throttle()
    @Volatile private var runningTitle: String = ""
    @Volatile private var runStartedAtMillis: Long = 0L
    private val progressTick = object : Runnable {
        override fun run() {
            if (runStartedAtMillis <= 0L) return
            val elapsed = SystemClock.elapsedRealtime() - runStartedAtMillis
            val stage = getString(R.string.notify_stage_running)
            // Throttle 以"阶段+秒"为 key，同一秒内不重复 notify；≥1s 由 Throttle 保证。
            // key 用 5s 粒度：同一窗口内不重复 notify（心跳周期与粒度一致，省电且不闪烁）。
            val bucket = elapsed / NotificationPolicy.SLOW_REFRESH_INTERVAL_MILLIS
            val key = "$stage|$bucket"
            if (progressThrottle.shouldEmit(key, SystemClock.elapsedRealtime())) {
                ScheduledTaskNotifications.updateRunning(this@ScheduledTaskService, runningTitle, stage, elapsed)
            }
            progressHandler.postDelayed(this, NotificationPolicy.SLOW_REFRESH_INTERVAL_MILLIS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        ScheduledTaskNotifications.ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_RUN_TASK) {
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        val taskId = intent.getStringExtra(EXTRA_TASK_ID).orEmpty()
        if (taskId.isBlank()) {
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        val title = intent.getStringExtra(EXTRA_TASK_TITLE).orEmpty()
            .ifBlank { getString(R.string.schedule_notification_running_title) }
        // 先进入前台：Android 12+ 要求 startForegroundService 后在极短时间内 startForeground。
        val entered = runCatching {
            val notification = ScheduledTaskNotifications.running(this, title)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    ScheduledTaskNotifications.runningId(),
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(ScheduledTaskNotifications.runningId(), notification)
            }
            true
        }.getOrElse { throwable ->
            AndroidAgentLogger.warn(
                "Scheduled task foreground failed: type=${throwable.safeLogType()}",
            )
            false
        }
        if (!entered) {
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        // 只有从"空闲"进入"有任务"时才重置心跳基线；并发叠进来的第二次 start 不该把
        // 已经在跑的这轮计时清零（否则通知里的耗时会突然倒退）。
        if (pendingWork.getAndIncrement() == 0) {
            startProgressTicker(title)
        }
        // 收尾必须用**本次** startId：期间若来了新任务（新 startId），stopSelfResult 会拒绝停止，
        // 避免旧的收尾把新任务的运行状态一起掐断。
        val runStartId = startId
        worker.execute {
            try {
                ScheduledTaskRunner.execute(this, taskId)
            } catch (throwable: Throwable) {
                AndroidAgentLogger.warn("Scheduled task execution failed: type=${throwable.safeLogType()}")
            } finally {
                if (pendingWork.decrementAndGet() <= 0) {
                    stopProgressTicker()
                    stopSelfResult(runStartId)
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopProgressTicker()
        // 不 shutdownNow：中断正在跑的 run 会留下"结果未知"的执行记录；
        // 进程真正被杀时线程随之结束，任务定义与排期都已经落盘。
        worker.shutdown()
        super.onDestroy()
    }

    /** 开始刷新"执行中"通知的耗时；重复调用会重置基线。 */
    private fun startProgressTicker(title: String) {
        runningTitle = title
        runStartedAtMillis = SystemClock.elapsedRealtime()
        progressThrottle.reset()
        progressHandler.removeCallbacks(progressTick)
        progressHandler.post(progressTick)
    }

    private fun stopProgressTicker() {
        runStartedAtMillis = 0L
        progressHandler.removeCallbacks(progressTick)
    }

    companion object {
        const val ACTION_RUN_TASK = "io.github.mangi.eta.agent.schedule.RUN_TASK"
        const val EXTRA_TASK_ID = "task_id"
        const val EXTRA_TASK_TITLE = "task_title"

        /** 前台服务起不来时的降级路径：普通线程跑一次，进程可能被回收但至少尝试执行。 */
        fun startFallbackWorker(context: Context, taskId: String) {
            Thread({
                runCatching { ScheduledTaskRunner.execute(context, taskId) }
            }, "scheduled-task-fallback").apply { isDaemon = true }.start()
        }
    }
}
