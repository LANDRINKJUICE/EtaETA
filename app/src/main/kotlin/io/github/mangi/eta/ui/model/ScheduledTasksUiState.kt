package io.github.mangi.eta.ui.model

import androidx.compose.runtime.Immutable
import io.github.mangi.eta.agent.schedule.TaskRunOutcome

/** 自动任务页的一条任务（已本地化，直接交给 Compose 渲染）。 */
@Immutable
data class ScheduledTaskUi(
    val id: String,
    val title: String,
    val prompt: String,
    val enabled: Boolean,
    val scheduleText: String,
    /** 已格式化的下次触发时间；没有未来触发点时为 null。 */
    val nextTriggerText: String?,
    /** 已格式化的最近执行时间。 */
    val lastRunText: String?,
    val outcome: TaskRunOutcome,
    val lastResult: String,
    val runCount: Int,
)

@Immutable
data class ScheduledTasksUiState(
    val isLoading: Boolean = true,
    val tasks: List<ScheduledTaskUi> = emptyList(),
    /** 精确闹钟授权状态；false 时页面顶部提示任务可能被推迟。 */
    val exactAlarmsAllowed: Boolean = true,
    val notice: String? = null,
    /** 正在删除/切换的任务 id，用于禁用重复点击。 */
    val busyTaskId: String? = null,
)
