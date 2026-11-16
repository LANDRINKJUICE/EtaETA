package io.github.mangi.eta.ui.screens.taskcenter

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.itos.coui.layout.Divider
import com.itos.coui.layout.Group
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.runs.AgentRunSource
import io.github.mangi.eta.agent.usage.UsageWindow
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.components.AgentApprovalRequestCard
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.MiuixScaffold
import io.github.mangi.eta.ui.components.coui.CouiButton
import io.github.mangi.eta.ui.components.coui.CouiButtonRole
import io.github.mangi.eta.ui.components.coui.CouiConfirmDialog
import io.github.mangi.eta.ui.components.coui.CouiNoticeDialog
import io.github.mangi.eta.ui.components.coui.CouiPreference
import io.github.mangi.eta.ui.components.coui.CouiScaffold
import io.github.mangi.eta.ui.components.coui.CouiSwitchPreference
import io.github.mangi.eta.ui.components.coui.CouiTopAppBarIconButton
import io.github.mangi.eta.ui.layout.horizontalCutoutPadding
import io.github.mangi.eta.ui.model.ScheduledTasksUiState
import io.github.mangi.eta.ui.model.TaskCenterActions
import io.github.mangi.eta.ui.model.TaskCenterMacroUi
import io.github.mangi.eta.ui.model.TaskCenterTab
import io.github.mangi.eta.ui.model.TaskCenterTerminalKind
import io.github.mangi.eta.ui.model.TaskCenterTerminalUi
import io.github.mangi.eta.ui.model.TaskCenterUiState
import io.github.mangi.eta.ui.model.formatElapsed
import io.github.mangi.eta.ui.model.formatTokens
import io.github.mangi.eta.ui.screens.schedule.ScheduledTasksSection
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 任务中心：一页看清「Eta 此刻在忙什么」，并且能就地处理。
 *
 * 五个分区对应五个真实存在的状态源（待确认卡片 / 自动任务 / 运行中与后台 / 宏 / 用量），
 * 全部由 [io.github.mangi.eta.ui.app.TaskCenterStore] 的单一 StateFlow 推送。
 * 页面只做渲染与动作转发：不排期、不探测进程、不写文件。
 */
@Composable
internal fun TaskCenterScreen(
    state: TaskCenterUiState,
    actions: TaskCenterActions,
    onBack: () -> Unit,
) {
    val title = stringResource(R.string.task_center_title)
    var pendingDeleteMacro by remember { mutableStateOf<String?>(null) }
    val refreshLabel = stringResource(R.string.task_center_refresh)

    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> {
            CouiScaffold(
                title = title,
                onBack = onBack,
                actions = {
                    CouiTopAppBarIconButton(
                        icon = Icons.Rounded.Refresh,
                        contentDescription = refreshLabel,
                        onClick = actions.onRefresh,
                    )
                },
            ) { contentPadding, _, nestedScrollConnection, sidePadding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .horizontalCutoutPadding()
                        .padding(top = contentPadding.calculateTopPadding())
                        .padding(horizontal = sidePadding)
                        .then(
                            if (nestedScrollConnection != null) {
                                Modifier.nestedScroll(nestedScrollConnection)
                            } else {
                                Modifier
                            },
                        )
                        .verticalScroll(rememberScrollState())
                        .navigationBarsPadding(),
                ) {
                    TaskCenterTabStrip(state = state, onSelect = actions.onSelectTab)
                    TaskCenterTabContent(
                        state = state,
                        actions = actions,
                        onRequestDeleteMacro = { name -> pendingDeleteMacro = name },
                    )
                    Spacer(Modifier.height(24.dp))
                }
            }
        }

        AppearanceUiStyle.MIUIX -> {
            MiuixScaffold(
                title = title,
                onBack = onBack,
                actions = {
                    IconButton(onClick = actions.onRefresh) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = refreshLabel,
                        )
                    }
                },
            ) { paddingValues, scrollBehavior, sidePadding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .horizontalCutoutPadding()
                        .padding(top = paddingValues.calculateTopPadding())
                        .padding(horizontal = sidePadding)
                        .nestedScroll(scrollBehavior.nestedScrollConnection)
                        .verticalScroll(rememberScrollState())
                        .navigationBarsPadding(),
                ) {
                    TaskCenterTabStrip(state = state, onSelect = actions.onSelectTab)
                    TaskCenterTabContent(
                        state = state,
                        actions = actions,
                        onRequestDeleteMacro = { name -> pendingDeleteMacro = name },
                    )
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }

    pendingDeleteMacro?.let { name ->
        val onConfirm: () -> Unit = {
            pendingDeleteMacro = null
            actions.onDeleteMacro(name)
        }
        val onDismiss: () -> Unit = { pendingDeleteMacro = null }
        when (LocalUiStyle.current) {
            AppearanceUiStyle.COUI -> CouiConfirmDialog(
                visible = true,
                title = stringResource(R.string.task_center_macro_delete_title),
                message = stringResource(R.string.task_center_macro_delete_message, name),
                confirmText = stringResource(R.string.task_center_delete),
                destructive = true,
                onConfirm = onConfirm,
                onDismissRequest = onDismiss,
            )
            AppearanceUiStyle.MIUIX -> WindowDialog(
                show = true,
                title = stringResource(R.string.task_center_macro_delete_title),
                summary = stringResource(R.string.task_center_macro_delete_message, name),
                onDismissRequest = onDismiss,
            ) {
                MiuixDialogActions(
                    confirmText = stringResource(R.string.task_center_delete),
                    destructive = true,
                    onCancel = onDismiss,
                    onConfirm = onConfirm,
                )
            }
        }
    }

    state.notice?.let { notice ->
        when (LocalUiStyle.current) {
            AppearanceUiStyle.COUI -> CouiNoticeDialog(
                visible = true,
                title = title,
                message = notice,
                confirmText = stringResource(R.string.ui_knew_cb63c6),
                onDismissRequest = actions.onDismissNotice,
            )
            AppearanceUiStyle.MIUIX -> WindowDialog(
                show = true,
                title = title,
                summary = notice,
                onDismissRequest = actions.onDismissNotice,
            ) {
                TextButton(
                    text = stringResource(R.string.ui_knew_cb63c6),
                    onClick = actions.onDismissNotice,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** 分区标签：可横向滚动，选中态用主色填充；数量角标只在有意义时出现。 */
@Composable
private fun TaskCenterTabStrip(
    state: TaskCenterUiState,
    onSelect: (TaskCenterTab) -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TaskCenterTab.entries.forEach { tab ->
            val selected = state.tab == tab
            val badge = state.badge(tab)
            val label = tabLabel(tab)
            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(percent = 50))
                    .background(if (selected) colors.primary else colors.surfaceContainer)
                    .selectable(selected = selected, role = Role.Tab, onClick = { onSelect(tab) })
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text(
                    text = if (badge > 0) "$label $badge" else label,
                    style = MiuixTheme.textStyles.footnote1,
                    color = if (selected) colors.onPrimary else colors.onSurface,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun TaskCenterTabContent(
    state: TaskCenterUiState,
    actions: TaskCenterActions,
    onRequestDeleteMacro: (String) -> Unit,
) {
    when (state.tab) {
        TaskCenterTab.APPROVALS -> ApprovalsTab(state = state, actions = actions)
        TaskCenterTab.SCHEDULED -> ScheduledTasksSection(
            state = ScheduledTasksUiState(
                isLoading = state.isLoading,
                tasks = state.tasks,
                exactAlarmsAllowed = state.exactAlarmsAllowed,
                notice = null,
                busyTaskId = state.busyScheduledTaskId,
            ),
            onToggle = actions.onToggleScheduled,
            onDelete = actions.onDeleteScheduled,
            onRunNow = actions.onRunScheduledNow,
        )
        TaskCenterTab.RUNNING -> RunningTab(state = state, actions = actions)
        TaskCenterTab.MACROS -> MacrosTab(state = state, onRequestDelete = onRequestDeleteMacro)
        TaskCenterTab.USAGE -> UsageTab(state = state, onToggleEnabled = actions.onToggleUsageStats)
    }
}

@Composable
private fun ApprovalsTab(state: TaskCenterUiState, actions: TaskCenterActions) {
    if (state.approvals.isEmpty()) {
        EmptyHint(
            title = stringResource(R.string.task_center_empty_approvals),
            summary = stringResource(R.string.task_center_empty_approvals_hint),
        )
        return
    }
    state.approvals.forEach { request ->
        AgentApprovalRequestCard(
            request = request,
            onDecide = { optionId, freeText ->
                actions.onDecideApproval(request.id, optionId, freeText)
            },
        )
    }
}

@Composable
private fun RunningTab(state: TaskCenterUiState, actions: TaskCenterActions) {
    val nowMillis = rememberTickingNow()
    SectionTitle(stringResource(R.string.task_center_section_runs))
    if (state.runs.isEmpty()) {
        EmptyHint(
            title = stringResource(R.string.task_center_empty_runs),
            summary = stringResource(R.string.task_center_empty_runs_hint),
        )
    } else {
        StyleGroup {
            state.runs.forEachIndexed { index, run ->
                if (index > 0) GroupDivider()
                TaskCenterRow(
                    title = run.title.ifBlank { stringResource(R.string.task_center_run_untitled) },
                    summary = runSummary(run.source, run.isChild, run.awaitingApproval) +
                        " · " + stringResource(
                        R.string.task_center_elapsed,
                        formatElapsed(nowMillis - run.startedAtMillis),
                    ),
                    // 子代理不能单独停止（它挂在父 run 的取消链上），因此不给一个无效的按钮；
                    // 停父 run 会连带取消子代理。
                    actionLabel = if (run.isStoppable) stringResource(R.string.task_center_stop) else null,
                    destructive = true,
                    onAction = if (run.isStoppable) ({ actions.onStopRun(run.runId) }) else null,
                )
            }
        }
    }

    Spacer(Modifier.height(16.dp))
    SectionTitle(stringResource(R.string.task_center_section_terminal))
    when {
        state.terminalLoading && state.terminalTasks.isEmpty() -> EmptyHint(
            title = stringResource(R.string.task_center_terminal_probing),
            summary = null,
        )
        state.terminalTasks.isEmpty() -> EmptyHint(
            title = stringResource(R.string.task_center_empty_terminal),
            summary = stringResource(R.string.task_center_empty_terminal_hint),
        )
        else -> StyleGroup {
            state.terminalTasks.forEachIndexed { index, task ->
                if (index > 0) GroupDivider()
                TaskCenterRow(
                    title = task.label.ifBlank { task.id },
                    summary = terminalSummary(task, nowMillis),
                    actionLabel = stringResource(R.string.task_center_stop),
                    destructive = true,
                    actionEnabled = state.busyTerminalTaskId == null,
                    onAction = { actions.onStopTerminalTask(task.id) },
                )
            }
        }
    }
}

@Composable
private fun MacrosTab(state: TaskCenterUiState, onRequestDelete: (String) -> Unit) {
    if (state.macros.isEmpty()) {
        EmptyHint(
            title = stringResource(R.string.task_center_empty_macros),
            summary = stringResource(R.string.task_center_empty_macros_hint),
        )
        return
    }
    StyleGroup {
        state.macros.forEachIndexed { index, macro ->
            if (index > 0) GroupDivider()
            TaskCenterRow(
                title = macro.name,
                summary = macroSummary(macro),
                actionLabel = stringResource(R.string.task_center_delete),
                destructive = true,
                onAction = { onRequestDelete(macro.name) },
            )
        }
    }
    Spacer(Modifier.height(12.dp))
    Footnote(stringResource(R.string.task_center_macro_footnote))
}

@Composable
private fun UsageTab(state: TaskCenterUiState, onToggleEnabled: (Boolean) -> Unit) {
    if (!state.usageEnabled) {
        // 关闭态只渲染说明 + 开关本身，然后直接返回：
        // 原来少了 return，会让"已关闭"的页面在提示下面继续画出一整页全 0 的数据区。
        EmptyHint(
            title = stringResource(R.string.task_center_usage_disabled),
            summary = stringResource(R.string.task_center_usage_disabled_hint),
        )
        Spacer(Modifier.height(16.dp))
        StyleGroup {
            TaskCenterSwitchRow(
                title = stringResource(R.string.task_center_usage_switch),
                summary = stringResource(R.string.task_center_usage_switch_summary),
                checked = false,
                onCheckedChange = onToggleEnabled,
            )
        }
        Spacer(Modifier.height(8.dp))
        Footnote(stringResource(R.string.task_center_usage_footnote))
        return
    }
    SectionTitle(stringResource(R.string.task_center_usage_windows))
    StyleGroup {
        UsageRow(stringResource(R.string.task_center_usage_today), state.usage.today)
        GroupDivider()
        UsageRow(stringResource(R.string.task_center_usage_week), state.usage.week)
        GroupDivider()
        UsageRow(stringResource(R.string.task_center_usage_month), state.usage.month)
    }

    Spacer(Modifier.height(16.dp))
    SectionTitle(stringResource(R.string.task_center_usage_by_source))
    if (state.usage.bySource.isEmpty()) {
        EmptyHint(title = stringResource(R.string.task_center_usage_empty), summary = null)
    } else {
        StyleGroup {
            state.usage.bySource.forEachIndexed { index, group ->
                if (index > 0) GroupDivider()
                val source = AgentRunSource.fromWireName(group.key)
                TaskCenterRow(
                    title = sourceLabel(source),
                    summary = windowSummary(group.window.requests, group.window.estimatedRequests),
                    trailingValue = formatTokens(group.window.totalTokens),
                )
            }
        }
    }

    Spacer(Modifier.height(16.dp))
    SectionTitle(stringResource(R.string.task_center_usage_by_model))
    if (state.usage.byModel.isEmpty()) {
        EmptyHint(title = stringResource(R.string.task_center_usage_empty), summary = null)
    } else {
        StyleGroup {
            state.usage.byModel.forEachIndexed { index, group ->
                if (index > 0) GroupDivider()
                TaskCenterRow(
                    title = group.key,
                    summary = windowSummary(group.window.requests, group.window.estimatedRequests),
                    trailingValue = formatTokens(group.window.totalTokens),
                )
            }
        }
    }

    Spacer(Modifier.height(16.dp))
    StyleGroup {
        TaskCenterSwitchRow(
            title = stringResource(R.string.task_center_usage_switch),
            summary = stringResource(R.string.task_center_usage_switch_summary),
            checked = state.usageEnabled,
            onCheckedChange = onToggleEnabled,
        )
    }
    Spacer(Modifier.height(8.dp))
    Footnote(stringResource(R.string.task_center_usage_footnote))
}

@Composable
private fun UsageRow(label: String, window: UsageWindow) {
    TaskCenterRow(
        title = label,
        summary = windowSummary(window.requests, window.estimatedRequests),
        trailingValue = formatTokens(window.totalTokens),
    )
}

// ---- 通用小组件（两种 UI 风格各一套实现，调用方不需要关心风格） ----

/** 分区小标题。 */
@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.footnote1,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp),
    )
}

/** 空态：一行主文案 + 可选说明，避免空白页让人以为加载失败。 */
@Composable
private fun EmptyHint(title: String, summary: String?) {
    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> StyleGroup {
            CouiPreference(title = title, summary = summary, showChevron = false)
        }
        AppearanceUiStyle.MIUIX -> Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(text = title, style = MiuixTheme.textStyles.body2)
                if (!summary.isNullOrBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = summary,
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }
    }
}

@Composable
private fun Footnote(text: String) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.footnote1,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
}

/** 一组行：COUI 用 Group，MIUIX 用一张 Card 包住 Column。 */
@Composable
private fun StyleGroup(content: @Composable ColumnScope.() -> Unit) {
    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> Group { content() }
        AppearanceUiStyle.MIUIX -> Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(vertical = 4.dp)) { content() }
        }
    }
}

@Composable
private fun GroupDivider() {
    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> Divider()
        AppearanceUiStyle.MIUIX -> Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .height(0.5.dp)
                .background(MiuixTheme.colorScheme.dividerLine),
        )
    }
}

/**
 * 一条可操作行。
 *
 * @param trailingValue 右侧数值（用量计数）；与 [actionLabel] 同时存在时只用于纯展示行，避免挤压。
 */
@Composable
private fun TaskCenterRow(
    title: String,
    summary: String? = null,
    trailingValue: String? = null,
    actionLabel: String? = null,
    actionEnabled: Boolean = true,
    destructive: Boolean = false,
    onAction: (() -> Unit)? = null,
) {
    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> {
            val trailing: (@Composable () -> Unit)? = if (actionLabel != null) {
                {
                    CouiButton(
                        text = actionLabel,
                        onClick = { onAction?.invoke() },
                        enabled = actionEnabled,
                        role = if (destructive) CouiButtonRole.Destructive else CouiButtonRole.Secondary,
                        minHeight = 30.dp,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
            } else {
                null
            }
            CouiPreference(
                title = title,
                summary = summary,
                value = trailingValue,
                enabled = actionEnabled,
                showChevron = false,
                onClick = if (onAction != null && actionEnabled) onAction else null,
                trailing = trailing,
            )
        }

        AppearanceUiStyle.MIUIX -> Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MiuixTheme.textStyles.body2,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!summary.isNullOrBlank()) {
                    Text(
                        text = summary,
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (!trailingValue.isNullOrBlank()) {
                Text(
                    text = trailingValue,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            if (actionLabel != null) {
                TextButton(
                    text = actionLabel,
                    onClick = { onAction?.invoke() },
                    enabled = actionEnabled,
                    colors = if (destructive) {
                        ButtonDefaults.textButtonColorsPrimary(
                            color = MiuixTheme.colorScheme.error,
                            textColor = MiuixTheme.colorScheme.onError,
                        )
                    } else {
                        ButtonDefaults.textButtonColorsPrimary()
                    },
                )
            }
        }
    }
}

@Composable
private fun TaskCenterSwitchRow(
    title: String,
    summary: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> CouiSwitchPreference(
            title = title,
            summary = summary,
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
        AppearanceUiStyle.MIUIX -> SwitchPreference(
            title = title,
            summary = summary,
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}

// ---- 文案与格式化 ----

@Composable
private fun tabLabel(tab: TaskCenterTab): String = when (tab) {
    TaskCenterTab.APPROVALS -> stringResource(R.string.task_center_tab_approvals)
    TaskCenterTab.SCHEDULED -> stringResource(R.string.task_center_tab_scheduled)
    TaskCenterTab.RUNNING -> stringResource(R.string.task_center_tab_running)
    TaskCenterTab.MACROS -> stringResource(R.string.task_center_tab_macros)
    TaskCenterTab.USAGE -> stringResource(R.string.task_center_tab_usage)
}

@Composable
private fun sourceLabel(source: AgentRunSource): String = when (source) {
    AgentRunSource.CHAT -> stringResource(R.string.task_center_source_chat)
    AgentRunSource.VOICE -> stringResource(R.string.task_center_source_voice)
    AgentRunSource.SCHEDULED -> stringResource(R.string.task_center_source_scheduled)
    AgentRunSource.JOB_WAKEUP -> stringResource(R.string.task_center_source_job_wakeup)
    AgentRunSource.SUBAGENT -> stringResource(R.string.task_center_source_subagent)
    AgentRunSource.DISTILL -> stringResource(R.string.task_center_source_distill)
    AgentRunSource.EXTERNAL -> stringResource(R.string.task_center_source_external)
    AgentRunSource.UNKNOWN -> stringResource(R.string.task_center_source_unknown)
}

@Composable
private fun runSummary(source: AgentRunSource, isChild: Boolean, awaitingApproval: Boolean): String {
    val parts = mutableListOf(sourceLabel(source))
    // 子代理的 sourceLabel 本身已经是"子代理"，不要重复标注。
    if (isChild && source != AgentRunSource.SUBAGENT) parts += stringResource(R.string.task_center_run_child)
    if (awaitingApproval) parts += stringResource(R.string.task_center_run_waiting)
    return parts.joinToString(" · ")
}

@Composable
private fun terminalSummary(task: TaskCenterTerminalUi, nowMillis: Long): String {
    val kind = when (task.kind) {
        TaskCenterTerminalKind.JOB -> stringResource(R.string.task_center_kind_job)
        TaskCenterTerminalKind.DAEMON -> stringResource(R.string.task_center_kind_daemon)
    }
    val parts = mutableListOf(kind)
    if (task.detail.isNotBlank()) parts += task.detail
    parts += stringResource(
        R.string.task_center_elapsed,
        formatElapsed(nowMillis - task.startedAtMillis),
    )
    return parts.joinToString(" · ")
}

@Composable
private fun macroSummary(macro: TaskCenterMacroUi): String {
    val source = if (macro.recorded) {
        stringResource(R.string.task_center_macro_recorded)
    } else {
        stringResource(R.string.task_center_macro_manual)
    }
    val actions = stringResource(R.string.task_center_macro_actions, macro.actionCount)
    val description = macro.description.takeIf { it.isNotBlank() }
    return listOfNotNull(description, actions, source).joinToString(" · ")
}

@Composable
private fun windowSummary(requests: Int, estimatedRequests: Int): String {
    val base = stringResource(R.string.task_center_usage_requests, requests)
    return if (estimatedRequests > 0) {
        base + " · " + stringResource(R.string.task_center_usage_estimated, estimatedRequests)
    } else {
        base
    }
}

/** 每秒推进一次的"现在"，只用于展示运行时长；离开页面即停止。 */
@Composable
private fun rememberTickingNow(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000L)
        }
    }
    return now
}

