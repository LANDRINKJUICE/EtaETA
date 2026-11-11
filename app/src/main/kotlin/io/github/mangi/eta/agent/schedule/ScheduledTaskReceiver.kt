package io.github.mangi.eta.agent.schedule

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import java.util.concurrent.Executors

/**
 * 自动任务的系统广播入口。
 *
 * 两种职责：
 * 1. 闹钟到点（[ACTION_RUN_TASK]）：立刻把执行转交给前台服务，onReceive 本身必须快速返回；
 * 2. 系统状态变化（开机、改时间、改时区、精确闹钟授权变化）：整表重建闹钟，补跑窗口内错过的一次性任务。
 *
 * 广播接收器不直接持有任何长任务，避免 ANR 与"onReceive 返回后进程被回收"。
 */
internal class ScheduledTaskReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val appContext = context.applicationContext
        when (intent?.action) {
            ACTION_RUN_TASK -> {
                val taskId = intent.getStringExtra(EXTRA_TASK_ID).orEmpty()
                if (taskId.isBlank()) return
                // 先排期、再派发（审查 B1）：闹钟已经消耗掉了，必须在这里就把下一轮写回并挂好闹钟，
                // 否则一旦后面的执行被并发闸门跳过或前台服务没起来，任务就永久停摆。
                val outcome = runCatching { ScheduledTaskRunner.advanceSchedule(appContext, taskId) }
                    .getOrElse { throwable ->
                        AndroidAgentLogger.warn("Scheduled task advance failed: type=${throwable.safeLogType()}")
                        // 排期失败仍然尝试执行一次：下一次开机重建会重新修好排期。
                        ScheduledTaskRunner.AdvanceOutcome(shouldRun = true)
                    }
                if (!outcome.shouldRun) return
                ScheduledTaskRunner.dispatch(appContext, taskId, outcome.title)
            }
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
            -> restore(appContext)
            else -> Unit
        }
    }

    /**
     * 整表重建。
     *
     * 用 [goAsync] 让系统继续托管这次广播直到重建完成：开机后进程随时可能被回收，
     * 如果 onReceive 直接返回，剩下的任务就再也不会被排期。
     */
    private fun restore(context: Context) {
        val pending = runCatching { goAsync() }.getOrNull()
        runCatching {
            RESTORE_EXECUTOR.execute {
                try {
                    val plan = ScheduledTaskScheduler.restoreAll(context)
                    plan.immediate.forEach { taskId -> ScheduledTaskRunner.dispatch(context, taskId) }
                    // 补跑也走 dispatch：排期与认领已在 restoreAll 里完成。
                    AndroidAgentLogger.debug {
                        "Scheduled tasks restored: scheduled=${plan.scheduled} " +
                            "immediate=${plan.immediate.size} retired=${plan.retired.size}"
                    }
                } catch (throwable: Throwable) {
                    AndroidAgentLogger.warn("Scheduled task restore failed: type=${throwable.safeLogType()}")
                } finally {
                    runCatching { pending?.finish() }
                }
            }
        }.onFailure { throwable ->
            AndroidAgentLogger.warn("Scheduled task restore dispatch failed: type=${throwable.safeLogType()}")
            runCatching { pending?.finish() }
        }
    }

    companion object {
        const val ACTION_RUN_TASK = "io.github.mangi.eta.agent.schedule.ALARM"
        const val EXTRA_TASK_ID = "task_id"

        private val RESTORE_EXECUTOR = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "scheduled-task-restore").apply { isDaemon = true }
        }

        fun runIntent(context: Context, taskId: String): Intent =
            Intent(context, ScheduledTaskReceiver::class.java)
                .setAction(ACTION_RUN_TASK)
                .putExtra(EXTRA_TASK_ID, taskId)
    }
}
