package io.github.mangi.eta.ui.app

import android.content.Context
import androidx.compose.runtime.snapshotFlow
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.approval.AgentApprovalCoordinator
import io.github.mangi.eta.agent.runs.LiveRunRegistry
import io.github.mangi.eta.agent.runs.agentLiveRuns
import io.github.mangi.eta.agent.runtime.AgentRuntimeClient
import io.github.mangi.eta.agent.terminal.DetachedTaskSupervisor
import io.github.mangi.eta.agent.terminal.LinuxEnvironmentPaths
import io.github.mangi.eta.agent.terminal.SharedFolderMounts
import io.github.mangi.eta.agent.tool.GuiMacroStore
import io.github.mangi.eta.agent.usage.AgentUsageRepository
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.ui.model.TaskCenterMacroUi
import io.github.mangi.eta.ui.model.TaskCenterRunUi
import io.github.mangi.eta.ui.model.TaskCenterTab
import io.github.mangi.eta.ui.model.TaskCenterTerminalKind
import io.github.mangi.eta.ui.model.TaskCenterTerminalUi
import io.github.mangi.eta.ui.model.TaskCenterUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray

/**
 * 任务中心的状态所有者。
 *
 * 数据来源全部是既有设施，这里只做"投影 + 转发"，不新增第二份事实：
 * - 待确认：直接订阅 [AgentApprovalCoordinator.pending]（StateFlow），就地决策回填；
 * - 自动任务：复用 [ScheduledTasksStore]（含排期/开关/立即执行/删除），用 snapshotFlow 把它的
 *   Compose 状态镜像进本页的单一 StateFlow；
 * - 运行中：订阅 [agentLiveRuns] 的 StateFlow；
 * - 后台任务与服务：读 [DetachedTaskSupervisor.list]，它与 terminal_job / terminal_daemon 共用同一份记录文件；
 * - 宏：读 [GuiMacroStore]；
 * - 用量：读 [AgentUsageRepository] 并按 30 日窗口聚合。
 *
 * 刷新策略：轻量的（审批、运行中）是实时流；重探测的（后台任务要起 shell 探活、宏与用量要读盘）
 * 只在进入页面、切换分区、手动刷新和执行动作后拉一次，避免页面开着就持续 fork shell。
 */
internal class TaskCenterStore(
    context: Context,
    private val scope: CoroutineScope,
    initialTab: TaskCenterTab = TaskCenterTab.APPROVALS,
) {
    private val appContext = context.applicationContext
    private val approvals = AgentApprovalCoordinator.shared
    private val scheduled = ScheduledTasksStore(appContext, scope)

    private val _state = MutableStateFlow(
        TaskCenterUiState(tab = initialTab, isLoading = true, usageEnabled = AgentUsageRepository.isEnabled()),
    )
    val state: StateFlow<TaskCenterUiState> = _state.asStateFlow()

    /** 只在需要探活时才构造；与 AI 侧 / 其它 UI 实例共用同一份记录文件与全局锁。 */
    private val supervisor by lazy {
        DetachedTaskSupervisor(
            logger = AndroidAgentLogger,
            recordsFile = DetachedTaskSupervisor.defaultRecordsFile(appContext),
            linuxRootfsPathProvider = { environment ->
                environment.linuxDistribution?.let { distribution ->
                    LinuxEnvironmentPaths.rootfsDir(appContext, distribution).absolutePath
                }
            },
            linuxSharedMountsProvider = { SharedFolderMounts.current() },
        )
    }

    init {
        observeApprovals()
        observeRuns()
        observeScheduledTasks()
    }

    // ---- 实时流 ----

    private fun observeApprovals() {
        scope.launch {
            approvals.pending.collect { pending ->
                val approvalRunIds = pending.map { it.runId }.toSet()
                _state.update { current ->
                    current.copy(
                        approvals = pending,
                        // 审批卡片出现/消失时同步刷新"运行中"的等待标记。
                        runs = current.runs.map { run -> run.copy(awaitingApproval = run.runId in approvalRunIds) },
                    )
                }
            }
        }
    }

    private fun observeRuns() {
        scope.launch {
            agentLiveRuns.active.collect { entries -> applyRuns(entries) }
        }
    }

    private fun applyRuns(entries: List<LiveRunRegistry.Entry>) {
        val approvalRunIds = _state.value.approvals.map { it.runId }.toSet()
        val runs = entries
            .sortedByDescending { it.sequence }
            .map { entry ->
                TaskCenterRunUi(
                    runId = entry.runId,
                    source = entry.source,
                    title = entry.title,
                    isChild = entry.isChild,
                    startedAtMillis = entry.startedAtMillis,
                    awaitingApproval = entry.runId in approvalRunIds,
                )
            }
        _state.update { it.copy(runs = runs) }
    }

    private fun observeScheduledTasks() {
        // ScheduledTasksStore 使用 Compose 可变状态；用 snapshotFlow 桥接到本页的 StateFlow，
        // 保证页面只有一条数据流，同时不复制一份排期逻辑。
        scope.launch {
            snapshotFlow { scheduled.state }.collect { snapshot ->
                _state.update {
                    it.copy(
                        // 页面级的加载态就以自动任务列表为准：它是唯一需要读盘的分区。
                        isLoading = snapshot.isLoading,
                        tasks = snapshot.tasks,
                        exactAlarmsAllowed = snapshot.exactAlarmsAllowed,
                        busyScheduledTaskId = snapshot.busyTaskId,
                        // 自动任务侧的提示（排期失败、立即执行已开始等）并入本页统一的提示出口。
                        notice = snapshot.notice ?: it.notice,
                    )
                }
            }
        }
    }

    // ---- 生命周期 ----

    fun selectTab(tab: TaskCenterTab) {
        _state.update { it.copy(tab = tab) }
        when (tab) {
            TaskCenterTab.SCHEDULED -> scheduled.refresh()
            TaskCenterTab.RUNNING -> refreshTerminalTasks()
            TaskCenterTab.MACROS -> refreshMacros()
            TaskCenterTab.USAGE -> refreshUsage()
            TaskCenterTab.APPROVALS -> Unit
        }
    }

    fun refresh() {
        scheduled.refresh()
        refreshTerminalTasks()
        refreshMacros()
        refreshUsage()
        _state.update { it.copy(usageEnabled = AgentUsageRepository.isEnabled()) }
    }

    fun dismissNotice() {
        _state.update { it.copy(notice = null) }
        scheduled.dismissNotice()
    }

    // ---- 待确认 ----

    /** 就地决策；卡片已失效（超时/运行结束）时提示而不是假装成功。 */
    fun decide(requestId: String, optionId: String, freeText: String?) {
        val applied = runCatching { approvals.resolve(requestId, optionId, freeText) }.getOrDefault(false)
        if (!applied) {
            _state.update { it.copy(notice = appContext.getString(R.string.task_center_approval_expired)) }
        }
    }

    // ---- 运行中 ----

    fun stopRun(runId: String) {
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    AgentRuntimeClient(appContext, AndroidAgentLogger).cancelRun(runId)
                    true
                }.getOrElse { throwable ->
                    AndroidAgentLogger.warn("Task center stop run failed: type=${throwable.safeLogType()}")
                    false
                }
            }
            if (!ok) {
                _state.update { it.copy(notice = appContext.getString(R.string.task_center_stop_failed)) }
            }
        }
    }

    // ---- 后台任务与服务 ----

    fun refreshTerminalTasks() {
        if (_state.value.terminalLoading) return
        _state.update { it.copy(terminalLoading = true) }
        scope.launch(Dispatchers.IO) {
            try {
                val rows = runCatching {
                    supervisor.list()
                        .filter { status -> status.task.completedAt == null && status.running }
                        .sortedByDescending { status -> status.task.startedAt }
                        .map { status ->
                            val task = status.task
                            val label = task.description.ifBlank { firstLine(task.command) }
                            TaskCenterTerminalUi(
                                id = task.id,
                                kind = if (task.commandTask) TaskCenterTerminalKind.JOB else TaskCenterTerminalKind.DAEMON,
                                label = label,
                                detail = terminalDetail(task.environment.wireName, task.identity, task.cwd),
                                running = status.running,
                                startedAtMillis = task.startedAt,
                            )
                        }
                }.getOrElse { throwable ->
                    AndroidAgentLogger.warn("Task center daemon probe failed: type=${throwable.safeLogType()}")
                    emptyList()
                }
                withContext(Dispatchers.Main) {
                    _state.update { it.copy(terminalTasks = rows, terminalLoading = false) }
                }
            } finally {
                // 异常/取消也要复位 loading，否则这一页会永远停在"加载中"且再也刷不动。
                withContext(NonCancellable) {
                    withContext(Dispatchers.Main) {
                        _state.update { if (it.terminalLoading) it.copy(terminalLoading = false) else it }
                    }
                }
            }
        }
    }

    fun stopTerminalTask(id: String) {
        if (_state.value.busyTerminalTaskId != null) return
        _state.update { it.copy(busyTerminalTaskId = id) }
        scope.launch(Dispatchers.IO) {
            val stopped = runCatching { supervisor.stop(id) }.getOrElse { throwable ->
                AndroidAgentLogger.warn("Task center stop task failed: type=${throwable.safeLogType()}")
                false
            }
            withContext(Dispatchers.Main) {
                _state.update {
                    it.copy(
                        busyTerminalTaskId = null,
                        terminalTasks = if (stopped) {
                            it.terminalTasks.filterNot { row -> row.id == id }
                        } else {
                            it.terminalTasks
                        },
                        notice = if (stopped) null else appContext.getString(R.string.task_center_stop_failed),
                    )
                }
            }
            refreshTerminalTasks()
        }
    }

    // ---- 自动任务 ----

    fun toggleScheduled(taskId: String, enabled: Boolean) {
        scheduled.setEnabled(taskId, enabled)
    }

    fun deleteScheduled(taskId: String) {
        scheduled.delete(taskId)
    }

    fun runScheduledNow(taskId: String) {
        scheduled.runNow(taskId)
        _state.update { it.copy(notice = appContext.getString(R.string.schedule_run_now_started)) }
    }

    // ---- 宏 ----

    fun refreshMacros() {
        scope.launch(Dispatchers.IO) {
            val macros = runCatching {
                val array: JSONArray = GuiMacroStore.list()
                (0 until array.length()).mapNotNull { index ->
                    val item = array.optJSONObject(index) ?: return@mapNotNull null
                    val name = item.optString("name")
                    if (name.isBlank()) return@mapNotNull null
                    TaskCenterMacroUi(
                        name = name,
                        description = item.optString("description"),
                        actionCount = item.optInt("actions", 0),
                        recorded = item.optString("source") == GuiMacroStore.SOURCE_RECORDING,
                    )
                }
            }.getOrElse { throwable ->
                AndroidAgentLogger.warn("Task center macro load failed: type=${throwable.safeLogType()}")
                emptyList()
            }
            withContext(Dispatchers.Main) {
                _state.update { it.copy(macros = macros) }
            }
        }
    }

    fun deleteMacro(name: String) {
        scope.launch(Dispatchers.IO) {
            val deleted = runCatching { GuiMacroStore.delete(name) }.getOrDefault(false)
            withContext(Dispatchers.Main) {
                _state.update { current ->
                    current.copy(
                        macros = if (deleted) current.macros.filterNot { it.name == name } else current.macros,
                        notice = if (deleted) null else appContext.getString(R.string.task_center_macro_delete_failed),
                    )
                }
            }
            refreshMacros()
        }
    }

    // ---- 用量 ----

    fun refreshUsage() {
        // 关闭态：不 flush、不整段读盘。埋点此时也不会写入，读出来只可能是旧历史；
        // 页面本身在关闭态只显示开关与说明，没必要为此付一次文件 IO。
        if (!AgentUsageRepository.isEnabled()) {
            _state.update { it.copy(usageEnabled = false) }
            return
        }
        scope.launch(Dispatchers.IO) {
            AgentUsageRepository.flush()
            val summary = AgentUsageRepository.summarize(appContext)
            withContext(Dispatchers.Main) {
                _state.update {
                    it.copy(usage = summary, usageEnabled = AgentUsageRepository.isEnabled())
                }
            }
        }
    }

    fun setUsageStatsEnabled(enabled: Boolean) {
        val applied = AgentUsageRepository.setEnabled(enabled)
        _state.update { current ->
            current.copy(
                usageEnabled = if (applied) enabled else current.usageEnabled,
                notice = if (applied) null else appContext.getString(R.string.settings_write_failed),
            )
        }
        if (applied) refreshUsage()
    }

    private fun firstLine(command: String): String =
        command.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty().take(80)

    private fun terminalDetail(environment: String, identity: String, cwd: String): String =
        listOf(environment, identity, cwd).filter { it.isNotBlank() }.joinToString(" · ")
}
