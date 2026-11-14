package io.github.mangi.eta.ui.screens.schedule

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.itos.coui.layout.Divider
import com.itos.coui.layout.Group
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.schedule.TaskRunOutcome
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.coui.CouiConfirmDialog
import io.github.mangi.eta.ui.components.coui.CouiPreference
import io.github.mangi.eta.ui.components.coui.CouiSwitchPreference
import io.github.mangi.eta.ui.model.ScheduledTaskUi
import io.github.mangi.eta.ui.model.ScheduledTasksUiState
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 自动任务分区：名称、调度描述、下次触发、启用开关、立即执行、删除与最近结果。
 *
 * 这里只渲染内容，不带自己的 Scaffold，也不自己弹提示：它被任务中心的「自动任务」页签复用，
 * 因此不允许再出现第二份自动任务列表实现；页面骨架、滚动与提示统一由宿主提供。
 */
@Composable
internal fun ScheduledTasksSection(
    state: ScheduledTasksUiState,
    onToggle: (String, Boolean) -> Unit,
    onDelete: (String) -> Unit,
    onRunNow: (String) -> Unit,
) {
    val context = LocalContext.current
    var pendingDelete by remember { mutableStateOf<ScheduledTaskUi?>(null) }

    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> {
            Column(modifier = Modifier.fillMaxWidth()) {
                ExactAlarmNotice(state = state, context = context)
                Group {
                    if (state.tasks.isEmpty() && state.isLoading) {
                        // 首次加载中：不显示"还没有任务"，避免把加载态说成空态。
                        CouiPreference(title = stringResource(R.string.schedule_loading), showChevron = false)
                    } else if (state.tasks.isEmpty()) {
                        CouiPreference(
                            title = stringResource(R.string.schedule_empty),
                            summary = stringResource(R.string.schedule_create_hint),
                        )
                    } else {
                        state.tasks.forEachIndexed { index, task ->
                            if (index > 0) Divider()
                            CouiSwitchPreference(
                                title = task.title,
                                summary = summaryText(task),
                                checked = task.enabled,
                                enabled = state.busyTaskId == null,
                                onCheckedChange = { onToggle(task.id, it) },
                            )
                            Divider()
                            CouiPreference(
                                title = stringResource(R.string.schedule_run_now),
                                summary = lastResultText(task),
                                enabled = task.enabled && state.busyTaskId == null,
                                onClick = { onRunNow(task.id) },
                                showChevron = false,
                            )
                            Divider()
                            CouiPreference(
                                title = stringResource(R.string.schedule_delete),
                                enabled = state.busyTaskId == null,
                                onClick = { pendingDelete = task },
                                showChevron = false,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Group {
                    CouiPreference(
                        title = stringResource(R.string.schedule_unattended_notice),
                        showChevron = false,
                    )
                }
            }
        }

        AppearanceUiStyle.MIUIX -> {
            Column(modifier = Modifier.fillMaxWidth()) {
                ExactAlarmNotice(state = state, context = context)
                if (state.tasks.isEmpty() && state.isLoading) {
                    // 首次加载中：不显示"还没有任务"，避免把加载态说成空态。
                    Text(
                        text = stringResource(R.string.schedule_loading),
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                } else if (state.tasks.isEmpty()) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = stringResource(R.string.schedule_empty),
                                style = MiuixTheme.textStyles.body1,
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = stringResource(R.string.schedule_create_hint),
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                } else {
                    state.tasks.forEach { task ->
                        Spacer(Modifier.height(12.dp))
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(vertical = 6.dp)) {
                                SwitchPreference(
                                    title = task.title,
                                    summary = summaryText(task),
                                    checked = task.enabled,
                                    enabled = state.busyTaskId == null,
                                    onCheckedChange = { onToggle(task.id, it) },
                                )
                                Text(
                                    text = lastResultText(task),
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                )
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 8.dp),
                                    horizontalArrangement = Arrangement.End,
                                ) {
                                    TextButton(
                                        text = stringResource(R.string.schedule_run_now),
                                        onClick = { onRunNow(task.id) },
                                        enabled = task.enabled && state.busyTaskId == null,
                                    )
                                    TextButton(
                                        text = stringResource(R.string.schedule_delete),
                                        onClick = { pendingDelete = task },
                                        enabled = state.busyTaskId == null,
                                        colors = ButtonDefaults.textButtonColorsPrimary(
                                            color = MiuixTheme.colorScheme.error,
                                            textColor = MiuixTheme.colorScheme.onError,
                                        ),
                                    )
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.schedule_unattended_notice),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
    }

    pendingDelete?.let { task ->
        val onConfirm: () -> Unit = {
            pendingDelete = null
            onDelete(task.id)
        }
        val onDismiss: () -> Unit = { pendingDelete = null }
        when (LocalUiStyle.current) {
            AppearanceUiStyle.COUI -> CouiConfirmDialog(
                visible = true,
                title = stringResource(R.string.schedule_delete_title),
                message = stringResource(R.string.schedule_delete_message, task.title),
                confirmText = stringResource(R.string.schedule_delete),
                destructive = true,
                onConfirm = onConfirm,
                onDismissRequest = onDismiss,
            )
            AppearanceUiStyle.MIUIX -> WindowDialog(
                show = true,
                title = stringResource(R.string.schedule_delete_title),
                summary = stringResource(R.string.schedule_delete_message, task.title),
                onDismissRequest = onDismiss,
            ) {
                MiuixDialogActions(
                    confirmText = stringResource(R.string.schedule_delete),
                    destructive = true,
                    onCancel = onDismiss,
                    onConfirm = onConfirm,
                )
            }
        }
    }
}

@Composable
private fun ExactAlarmNotice(state: ScheduledTasksUiState, context: Context) {
    if (state.exactAlarmsAllowed) return
    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> {
            Group {
                CouiPreference(
                    title = stringResource(R.string.schedule_exact_alarm_missing),
                    icon = { Icon(Icons.Rounded.Alarm, contentDescription = null) },
                    onClick = { openExactAlarmSettings(context) },
                    showChevron = true,
                )
            }
            Spacer(Modifier.height(8.dp))
        }
        AppearanceUiStyle.MIUIX -> {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.schedule_exact_alarm_missing),
                        style = MiuixTheme.textStyles.body2,
                    )
                    Spacer(Modifier.height(6.dp))
                    TextButton(
                        text = stringResource(R.string.schedule_exact_alarm_action),
                        onClick = { openExactAlarmSettings(context) },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

private fun openExactAlarmSettings(context: Context) {
    val intent = Intent(
        Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
        Uri.parse("package:${context.packageName}"),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
        .recoverCatching {
            context.startActivity(
                Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
}

@Composable
private fun summaryText(task: ScheduledTaskUi): String {
    val schedule = task.scheduleText
    val next = task.nextTriggerText
    return if (next != null) {
        schedule + " · " + stringResource(R.string.schedule_next_trigger, next)
    } else {
        schedule + " · " + stringResource(R.string.schedule_no_next_trigger)
    }
}

@Composable
private fun lastResultText(task: ScheduledTaskUi): String {
    val outcome = when (task.outcome) {
        TaskRunOutcome.OK -> stringResource(R.string.schedule_outcome_ok)
        TaskRunOutcome.FAILED -> stringResource(R.string.schedule_outcome_failed)
        TaskRunOutcome.SKIPPED -> stringResource(R.string.schedule_outcome_skipped)
        TaskRunOutcome.NEVER -> stringResource(R.string.schedule_outcome_never)
    }
    val runAt = task.lastRunText ?: stringResource(R.string.schedule_never_run)
    val detail = task.lastResult.ifBlank { outcome }
    return "$outcome · $runAt · $detail"
}
