package io.github.mangi.eta.ui.app

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.schedule.ScheduledTask
import io.github.mangi.eta.agent.schedule.ScheduledTaskRunner
import io.github.mangi.eta.agent.schedule.ScheduledTaskScheduler
import io.github.mangi.eta.agent.schedule.ScheduledTaskStore
import io.github.mangi.eta.agent.schedule.ScheduledTaskTool
import io.github.mangi.eta.agent.schedule.TaskScheduleCalculator
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.ui.model.ScheduledTaskUi
import io.github.mangi.eta.ui.model.ScheduledTasksUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 自动任务页的状态与动作。
 *
 * 与 Agent run 完全解耦：这里只读写任务文件、操作闹钟、必要时派发一次后台执行，
 * 不触碰聊天会话，因此页面在不在前台都不影响任务本身。
 */
internal class ScheduledTasksStore(
    context: Context,
    private val scope: CoroutineScope,
) {
    private val appContext = context.applicationContext

    var state by mutableStateOf(ScheduledTasksUiState())
        private set

    fun refresh() {
        scope.launch(Dispatchers.IO) {
            val loaded = runCatching {
                val store = ScheduledTaskStore(appContext)
                val tasks = store.load().sortedWith(
                    compareByDescending<ScheduledTask> { it.enabled }
                        .thenBy { if (it.nextTriggerAtMillis > 0) it.nextTriggerAtMillis else Long.MAX_VALUE },
                )
                val scheduler = ScheduledTaskScheduler(appContext)
                tasks.map { it.toUi() } to scheduler.canScheduleExactAlarms()
            }.getOrElse { throwable ->
                AndroidAgentLogger.warnThrottled("scheduled_tasks_ui_load_failed") {
                    "Scheduled tasks page load failed: type=${throwable.safeLogType()}"
                }
                emptyList<ScheduledTaskUi>() to true
            }
            withContext(Dispatchers.Main) {
                state = state.copy(
                    isLoading = false,
                    tasks = loaded.first,
                    exactAlarmsAllowed = loaded.second,
                )
            }
        }
    }

    fun setEnabled(taskId: String, enabled: Boolean) {
        if (state.busyTaskId != null) return
        state = state.copy(busyTaskId = taskId)
        scope.launch(Dispatchers.IO) {
            val failure = runCatching {
                val store = ScheduledTaskStore(appContext)
                val scheduler = ScheduledTaskScheduler(appContext)
                val task = store.find(taskId) ?: error("任务不存在")
                if (!enabled) {
                    store.setEnabled(taskId, false)
                    store.updateNextTrigger(taskId, 0L)
                    scheduler.cancel(taskId)
                } else {
                    val next = TaskScheduleCalculator.nextAfter(task.schedule, System.currentTimeMillis())
                        ?: error(appContext.getString(R.string.schedule_no_future_trigger))
                    store.setEnabled(taskId, true)
                    store.updateNextTrigger(taskId, next)
                    scheduler.schedule(taskId, next)
                }
            }.exceptionOrNull()
            withContext(Dispatchers.Main) {
                state = state.copy(
                    busyTaskId = null,
                    notice = failure?.let { appContext.getString(R.string.schedule_update_failed, it.message.orEmpty()) },
                )
            }
            refresh()
        }
    }

    fun delete(taskId: String) {
        if (state.busyTaskId != null) return
        state = state.copy(busyTaskId = taskId)
        scope.launch(Dispatchers.IO) {
            val failure = runCatching {
                ScheduledTaskScheduler(appContext).cancel(taskId)
                ScheduledTaskStore(appContext).delete(taskId)
            }.exceptionOrNull()
            withContext(Dispatchers.Main) {
                state = state.copy(
                    busyTaskId = null,
                    notice = failure?.let { appContext.getString(R.string.schedule_update_failed, it.message.orEmpty()) },
                )
            }
            refresh()
        }
    }

    /** 立刻跑一次；结果进通知与列表，不在页面里阻塞等待。 */
    fun runNow(taskId: String) {
        ScheduledTaskRunner.dispatch(appContext, taskId)
        state = state.copy(notice = appContext.getString(R.string.schedule_run_now_started))
    }

    fun dismissNotice() {
        state = state.copy(notice = null)
    }

    private fun ScheduledTask.toUi(): ScheduledTaskUi = ScheduledTaskUi(
        id = id,
        title = title,
        prompt = prompt,
        enabled = enabled,
        scheduleText = ScheduledTaskTool.describeSchedule(appContext, schedule),
        nextTriggerText = nextTriggerAtMillis
            .takeIf { enabled && it > 0L }
            ?.let { TaskScheduleCalculator.format(it) },
        lastRunText = lastRunAtMillis
            .takeIf { it > 0L }
            ?.let { TaskScheduleCalculator.format(it) },
        outcome = lastOutcome,
        lastResult = lastResult,
        runCount = runCount,
    )
}
