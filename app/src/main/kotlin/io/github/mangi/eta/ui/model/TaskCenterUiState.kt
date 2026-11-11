package io.github.mangi.eta.ui.model

import androidx.compose.runtime.Immutable
import io.github.mangi.eta.agent.approval.AgentApprovalRequest
import io.github.mangi.eta.agent.runs.AgentRunSource
import io.github.mangi.eta.agent.usage.UsageSummary

/** 一条正在执行的 Agent run（子代理单独占一条）。 */
@Immutable
internal data class TaskCenterRunUi(
    val runId: String,
    val source: AgentRunSource,
    /** 单行展示标题（用户消息或子代理描述），可能为空。 */
    val title: String,
    val isChild: Boolean,
    val startedAtMillis: Long,
    /** 这条 run 正在等用户确认（与待确认卡片的 runId 对应）。 */
    val awaitingApproval: Boolean,
) {
    /**
     * 能否从任务中心单独停止。
     *
     * 只有顶层 run 有独立的 Runtime 会话：子代理挂在父 run 的取消链上，
     * 直接对子代理 id 发 cancel 没有任何效果，因此不给无效按钮。
     */
    val isStoppable: Boolean get() = isStoppableRun(source = source, isChild = isChild)
}

internal enum class TaskCenterTerminalKind {
    /** terminal_job：一次后台命令。 */
    JOB,

    /** terminal_daemon：跨会话长驻服务。 */
    DAEMON,
}

/** 一条后台命令或守护服务。 */
@Immutable
internal data class TaskCenterTerminalUi(
    val id: String,
    val kind: TaskCenterTerminalKind,
    val label: String,
    val detail: String,
    val running: Boolean,
    val startedAtMillis: Long,
)

/** 一条已保存的 GUI 宏。 */
@Immutable
internal data class TaskCenterMacroUi(
    val name: String,
    val description: String,
    val actionCount: Int,
    /** true 表示用户录制生成，false 表示模型手动保存。 */
    val recorded: Boolean,
)

/** 任务中心整页状态：所有分区共用一份快照，全部由 [kotlinx.coroutines.flow.StateFlow] 推送。 */
@Immutable
internal data class TaskCenterUiState(
    val tab: TaskCenterTab = TaskCenterTab.APPROVALS,
    val isLoading: Boolean = true,
    val approvals: List<AgentApprovalRequest> = emptyList(),
    val tasks: List<ScheduledTaskUi> = emptyList(),
    val exactAlarmsAllowed: Boolean = true,
    val busyScheduledTaskId: String? = null,
    val runs: List<TaskCenterRunUi> = emptyList(),
    val terminalTasks: List<TaskCenterTerminalUi> = emptyList(),
    val terminalLoading: Boolean = false,
    val busyTerminalTaskId: String? = null,
    val macros: List<TaskCenterMacroUi> = emptyList(),
    val usage: UsageSummary = UsageSummary(),
    val usageEnabled: Boolean = true,
    val notice: String? = null,
) {
    /** 标签栏角标：只对待确认项显示数量，其余分区用"有内容就点亮"的方式表达。 */
    fun badge(tab: TaskCenterTab): Int = when (tab) {
        TaskCenterTab.APPROVALS -> approvals.size
        TaskCenterTab.SCHEDULED -> tasks.count { it.enabled }
        TaskCenterTab.RUNNING -> runs.size + terminalTasks.count { it.running }
        TaskCenterTab.MACROS -> macros.size
        TaskCenterTab.USAGE -> 0
    }

    fun isEmpty(tab: TaskCenterTab): Boolean = when (tab) {
        TaskCenterTab.APPROVALS -> approvals.isEmpty()
        TaskCenterTab.SCHEDULED -> tasks.isEmpty()
        TaskCenterTab.RUNNING -> runs.isEmpty() && terminalTasks.isEmpty()
        TaskCenterTab.MACROS -> macros.isEmpty()
        TaskCenterTab.USAGE -> usage.isEmpty
    }
}

/**
 * 页面动作集合。
 *
 * 分区多、动作多，逐个平铺到 Screen 参数里会变成十几项近乎同型的 lambda，容易错位；
 * 这里集中成一个不可变动作包，Screen 只按分区取用需要的几个，也便于预览与测试时只覆盖部分动作。
 */
internal data class TaskCenterActions(
    val onSelectTab: (TaskCenterTab) -> Unit = {},
    /** (requestId, optionId, freeText) */
    val onDecideApproval: (String, String, String?) -> Unit = { _, _, _ -> },
    val onStopRun: (String) -> Unit = {},
    val onStopTerminalTask: (String) -> Unit = {},
    val onToggleScheduled: (String, Boolean) -> Unit = { _, _ -> },
    val onDeleteScheduled: (String) -> Unit = {},
    val onRunScheduledNow: (String) -> Unit = {},
    val onDeleteMacro: (String) -> Unit = {},
    val onToggleUsageStats: (Boolean) -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onDismissNotice: () -> Unit = {},
)
