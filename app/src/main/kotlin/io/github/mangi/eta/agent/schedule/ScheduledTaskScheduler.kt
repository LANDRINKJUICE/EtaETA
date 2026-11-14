package io.github.mangi.eta.agent.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType

/**
 * 整表重建的结果。
 *
 * @param scheduled 成功挂上闹钟的任务条数。
 * @param immediate 需要在本次重建里立刻派发执行的任务 id（错过但仍在补跑窗口内的一次性任务）。
 * @param retired 因为错过太久而被停用的任务 id。
 */
internal data class RestorePlan(
    val scheduled: Int,
    val immediate: List<String>,
    val retired: List<String>,
)

/**
 * 用 AlarmManager 把任务挂到系统闹钟上。
 *
 * 精确定时依赖 [AlarmManager.canScheduleExactAlarms]：用户授予"闹钟和提醒"后走
 * setExactAndAllowWhileIdle；没有授权时降级为 setAndAllowWhileIdle（允许系统推迟到维护窗口），
 * 只把结果告诉调用方，不静默失败、也不假装精确。
 *
 * 每个任务一个 requestCode（下一节的 [requestCodeFor]），PendingIntent 用 FLAG_UPDATE_CURRENT
 * 覆盖同一条任务旧的闹钟，用 FLAG_IMMUTABLE 防止外部改写。
 */
internal class ScheduledTaskScheduler(private val context: Context) {
    private val appContext = context.applicationContext

    enum class Exactness {
        /** 系统保证大致到点触发。 */
        EXACT,

        /** 没有精确闹钟授权，只能允许系统推迟。 */
        INEXACT,

        /** 连闹钟都没能设置（权限被撤销或系统拒绝）。 */
        FAILED,
    }

    fun alarmManager(): AlarmManager? =
        runCatching { appContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager }.getOrNull()

    fun canScheduleExactAlarms(): Boolean {
        val manager = alarmManager() ?: return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return runCatching { manager.canScheduleExactAlarms() }.getOrDefault(false)
    }

    /** 排在 [triggerAtMillis] 之后（含）；已有未来时间不早于它的闹钟时不重复设置。 */
    fun schedule(taskId: String, triggerAtMillis: Long): Exactness {
        if (triggerAtMillis <= 0L) return Exactness.FAILED
        val manager = alarmManager() ?: return Exactness.FAILED
        val pending = pendingIntent(taskId)
            ?: return Exactness.FAILED
        return if (canScheduleExactAlarms()) {
            runCatching {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pending)
            }.fold(
                onSuccess = { Exactness.EXACT },
                onFailure = { throwable ->
                    AndroidAgentLogger.warnThrottled("scheduled_task_exact_alarm_failed") {
                        "Exact alarm rejected, falling back to inexact: type=${throwable.safeLogType()}"
                    }
                    scheduleInexact(manager, triggerAtMillis, pending)
                },
            )
        } else {
            scheduleInexact(manager, triggerAtMillis, pending)
        }
    }

    private fun scheduleInexact(
        manager: AlarmManager,
        triggerAtMillis: Long,
        pending: PendingIntent,
    ): Exactness = runCatching {
        manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pending)
    }.fold(
        onSuccess = { Exactness.INEXACT },
        onFailure = { throwable ->
            AndroidAgentLogger.warn("Scheduled task alarm could not be set: type=${throwable.safeLogType()}")
            Exactness.FAILED
        },
    )

    fun cancel(taskId: String) {
        val manager = alarmManager() ?: return
        val pending = pendingIntent(taskId) ?: return
        runCatching { manager.cancel(pending) }
    }

    private fun pendingIntent(taskId: String): PendingIntent? = runCatching {
        PendingIntent.getBroadcast(
            appContext,
            requestCodeFor(taskId),
            ScheduledTaskReceiver.runIntent(appContext, taskId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }.getOrNull()

    companion object {
        /**
         * 任务 id 是随机 UUID，把它映射成 31 位 requestCode：同一 id 永远得到同一个值，
         * 不同 id 的碰撞只影响"覆盖旧闹钟"的精细度，不影响触发（触发时按 id 查真实任务）。
         */
        fun requestCodeFor(taskId: String): Int {
            val hash = taskId.fold(0) { acc, char -> 31 * acc + char.code }
            return hash and 0x7FFF_FFFF
        }

        /**
         * 开机、改时间、改时区、精确闹钟授权变化之后的整表重建。
         *
         * 重新排期不能只看文件里的 old next_trigger：设备关机期间错过的轮次要么补一次、要么
         * 明确记为错过，不能让它一直挂着"下次触发时间已经过去"的假状态。
         *
         * @return [RestorePlan]，其中 [RestorePlan.immediate] 需要调用方立刻派发执行。
         */
        fun restoreAll(context: Context, nowMillis: Long = System.currentTimeMillis()): RestorePlan {
            val scheduler = ScheduledTaskScheduler(context)
            val store = ScheduledTaskStore(context)
            var scheduled = 0
            val immediate = mutableListOf<String>()
            val retired = mutableListOf<String>()
            store.load().forEach { task ->
                if (!task.enabled) {
                    scheduler.cancel(task.id)
                    return@forEach
                }
                when (task.schedule.kind) {
                    ScheduleKind.ONCE -> {
                        val at = task.schedule.atMillis
                        when {
                            at > nowMillis -> {
                                if (scheduler.schedule(task.id, at) != Exactness.FAILED) scheduled++
                                store.updateNextTrigger(task.id, at)
                            }
                            nowMillis - at <= TaskScheduleCalculator.ONCE_CATCH_UP_WINDOW_MILLIS -> {
                                // 关机或重启错过了这次一次性任务，窗口内补跑一次。
                                // 先认领（next_trigger 清零 + claim 时间戳）再派发：开机广播与闹钟
                                // 广播成对到达时，第二次会看到已认领而不会重复执行（审查 M2）。
                                scheduler.cancel(task.id)
                                store.markClaimed(task.id, nextTriggerAtMillis = 0L, claimedAtMillis = nowMillis)
                                immediate += task.id
                            }
                            else -> {
                                scheduler.cancel(task.id)
                                store.recordRun(
                                    id = task.id,
                                    atMillis = nowMillis,
                                    outcome = TaskRunOutcome.SKIPPED,
                                    summary = context.getString(
                                        io.github.mangi.eta.R.string.schedule_missed_summary,
                                    ),
                                    durationMillis = 0L,
                                    nextTriggerAtMillis = 0L,
                                )
                                retired += task.id
                            }
                        }
                    }
                    ScheduleKind.DAILY, ScheduleKind.WEEKLY, ScheduleKind.INTERVAL -> {
                        val next = TaskScheduleCalculator.nextRun(
                            schedule = task.schedule,
                            previousTriggerMillis = task.nextTriggerAtMillis,
                            nowMillis = nowMillis,
                        )
                        if (next == null) {
                            scheduler.cancel(task.id)
                            store.updateNextTrigger(task.id, 0L)
                        } else {
                            if (scheduler.schedule(task.id, next) != Exactness.FAILED) scheduled++
                            store.updateNextTrigger(task.id, next)
                        }
                    }
                }
            }
            return RestorePlan(scheduled = scheduled, immediate = immediate, retired = retired)
        }
    }
}
