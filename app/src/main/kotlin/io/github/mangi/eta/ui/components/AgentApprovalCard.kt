package io.github.mangi.eta.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.GppMaybe
import androidx.compose.material.icons.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Send
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.itos.coui.effect.couiButtonFeedback
import com.itos.coui.theme.CouiTheme
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.approval.AgentApprovalKind
import io.github.mangi.eta.agent.approval.AgentApprovalOption
import io.github.mangi.eta.agent.approval.AgentApprovalRequest
import io.github.mangi.eta.agent.approval.AgentApprovalOptionKind
import io.github.mangi.eta.agent.approval.ResolvedApproval
import io.github.mangi.eta.agent.approval.ToolRiskCategory
import io.github.mangi.eta.agent.approval.ToolRiskLevel
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.ui.app.LocalUiStyle
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 聊天流内的用户确认卡。
 *
 * 两种形态共用一套视觉：危险操作拦截卡（红橙色强调、展示命令原文与命中的风险规则）
 * 和 Agent 主动询问卡（问题 + 选项 + 可选自由输入）。卡片直接消费
 * [io.github.mangi.eta.agent.approval.AgentApprovalCoordinator] 的等待列表，
 * 选项点击后立刻回填决策，工具执行线程随即恢复。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AgentApprovalRequestCard(
    request: AgentApprovalRequest,
    onDecide: (optionId: String, freeText: String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MiuixTheme.colorScheme
    val isCoui = LocalUiStyle.current == AppearanceUiStyle.COUI
    val danger = request.kind == AgentApprovalKind.ToolRisk
    val accent = if (danger) riskAccent(request.level, colors.error) else colors.primary
    var freeText by remember(request.id) { mutableStateOf("") }
    var composerOpen by remember(request.id) { mutableStateOf(false) }
    var remainingSeconds by remember(request.id) {
        mutableStateOf(secondsUntilExpiry(request.expiresAtMillis))
    }
    LaunchedEffect(request.id, request.expiresAtMillis) {
        // 倒计时到 0 后停掉 tick，避免过期卡片继续每秒重组。
        while (true) {
            val remaining = secondsUntilExpiry(request.expiresAtMillis)
            remainingSeconds = remaining
            if (remaining <= 0) break
            delay(1_000)
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .squircleSurface(color = colors.surface, cornerRadius = 16.dp)
            .border(0.6.dp, accent.copy(alpha = 0.55f), RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .background(accent.copy(alpha = 0.14f), RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (danger) Icons.Rounded.GppMaybe else Icons.Rounded.HelpOutline,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                    tint = accent,
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (danger) {
                    stringResource(R.string.approval_danger_title)
                } else {
                    stringResource(R.string.approval_ask_title)
                },
                style = MiuixTheme.textStyles.headline1,
                color = colors.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (danger) {
                Text(
                    text = stringResource(riskLevelLabel(request.level)),
                    style = MiuixTheme.textStyles.footnote2,
                    color = accent,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .background(accent.copy(alpha = 0.12f), RoundedCornerShape(percent = 50))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }

        if (danger) {
            ApprovalSectionLabel(stringResource(R.string.approval_danger_operation))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.surfaceContainerHigh, RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                Text(
                    text = request.subject.ifBlank { request.toolName },
                    style = MiuixTheme.textStyles.footnote1.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                    ),
                    color = colors.onSurface,
                    maxLines = 8,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (request.options.none { it.id == AgentApprovalOption.ALLOW_SESSION }) {
                Text(
                    text = stringResource(R.string.approval_only_once_hint),
                    style = MiuixTheme.textStyles.footnote2,
                    color = accent,
                )
            }
            ApprovalSectionLabel(stringResource(R.string.approval_danger_why))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                request.findings.forEach { finding ->
                    Row {
                        Text(
                            text = "·",
                            style = MiuixTheme.textStyles.footnote1,
                            color = accent,
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(riskCategoryReason(finding.category)),
                            style = MiuixTheme.textStyles.footnote1,
                            color = colors.onSurfaceVariantSummary,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                request.findings.firstOrNull()?.detail?.takeIf { it.isNotBlank() }?.let { detail ->
                    Text(
                        text = detail,
                        style = MiuixTheme.textStyles.footnote2.copy(fontFamily = FontFamily.Monospace),
                        color = colors.onSurfaceVariantSummary,
                    )
                }
            }
        } else {
            Text(
                text = request.question,
                style = MiuixTheme.textStyles.body1,
                color = colors.onSurface,
            )
            if (request.explanation.isNotBlank()) {
                Text(
                    text = request.explanation,
                    style = MiuixTheme.textStyles.footnote1,
                    color = colors.onSurfaceVariantSummary,
                )
            }
        }

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            request.options.forEach { option ->
                val destructive = option.kind == AgentApprovalOptionKind.Deny
                val allow = option.kind == AgentApprovalOptionKind.AllowOnce ||
                    option.kind == AgentApprovalOptionKind.AllowSession
                ApprovalOptionButton(
                    label = if (danger) {
                        stringResource(dangerOptionLabel(option.id))
                    } else {
                        option.label
                    },
                    primary = allow,
                    destructive = destructive,
                    enabled = remainingSeconds > 0,
                    isCoui = isCoui,
                    onClick = {
                        when {
                            option.id == AgentApprovalOption.DENY_WITH_FEEDBACK && danger -> {
                                composerOpen = !composerOpen
                            }
                            else -> onDecide(option.id, freeText.takeIf { composerOpen || !danger })
                        }
                    },
                )
            }
        }
        if (!danger && request.options.any { it.description.isNotBlank() }) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                request.options.filter { it.description.isNotBlank() }.forEach { option ->
                    Text(
                        text = "${option.label}：${option.description}",
                        style = MiuixTheme.textStyles.footnote2,
                        color = colors.onSurfaceVariantSummary,
                    )
                }
            }
        }

        val showComposer = request.allowFreeText && (composerOpen || !danger)
        if (showComposer) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 36.dp)
                        .background(colors.surfaceContainerHigh, RoundedCornerShape(10.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (freeText.isEmpty()) {
                        Text(
                            text = stringResource(
                                if (danger) {
                                    R.string.approval_free_text_hint_danger
                                } else {
                                    R.string.approval_free_text_hint_ask
                                },
                            ),
                            style = MiuixTheme.textStyles.footnote1,
                            color = colors.onSurfaceVariantSummary.copy(alpha = 0.7f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    BasicTextField(
                        value = freeText,
                        onValueChange = { freeText = it.take(1_000) },
                        textStyle = MiuixTheme.textStyles.footnote1.copy(color = colors.onSurface),
                        cursorBrush = SolidColor(colors.primary),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                ApprovalOptionButton(
                    label = stringResource(R.string.approval_send),
                    primary = true,
                    destructive = false,
                    enabled = freeText.isNotBlank() && remainingSeconds > 0,
                    isCoui = isCoui,
                    icon = true,
                    onClick = {
                        onDecide(
                            if (danger) AgentApprovalOption.DENY_WITH_FEEDBACK else AgentApprovalOption.FREE_TEXT_ONLY,
                            freeText,
                        )
                    },
                )
            }
        }

        Text(
            text = when {
                remainingSeconds <= 0 -> stringResource(R.string.approval_expired)
                danger -> stringResource(R.string.approval_waiting_danger, remainingSeconds)
                else -> stringResource(R.string.approval_waiting_ask, remainingSeconds)
            },
            style = MiuixTheme.textStyles.footnote2,
            color = if (remainingSeconds <= 0) colors.error else colors.onSurfaceVariantSummary,
        )
    }
}

/** 决策后短暂回显，让"点了之后确实生效"这件事在聊天流里可见。 */
@Composable
internal fun AgentApprovalResolvedChip(
    resolved: ResolvedApproval,
    modifier: Modifier = Modifier,
) {
    val colors = MiuixTheme.colorScheme
    val allowed = resolved.resolution.kind == AgentApprovalOptionKind.AllowOnce ||
        resolved.resolution.kind == AgentApprovalOptionKind.AllowSession
    val label = when {
        resolved.resolution.optionId == AgentApprovalOption.FREE_TEXT_ONLY ||
            resolved.request.kind == AgentApprovalKind.AskUser ->
            stringResource(R.string.approval_resolved_answered)
        resolved.resolution.kind == AgentApprovalOptionKind.AllowOnce ->
            stringResource(R.string.approval_resolved_allowed_once)
        resolved.resolution.kind == AgentApprovalOptionKind.AllowSession ->
            stringResource(R.string.approval_resolved_allowed_session)
        else -> stringResource(R.string.approval_resolved_denied)
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .background(colors.surface, RoundedCornerShape(percent = 50))
                .border(
                    0.5.dp,
                    colors.outline.copy(alpha = 0.5f),
                    RoundedCornerShape(percent = 50),
                )
                .padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (allowed) Icons.Rounded.Check else Icons.Rounded.Block,
                contentDescription = null,
                modifier = Modifier.size(12.dp),
                tint = if (allowed) colors.primary else colors.onSurfaceVariantSummary,
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                style = MiuixTheme.textStyles.footnote2,
                color = colors.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ApprovalSectionLabel(text: String) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.footnote2,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        fontWeight = FontWeight.Medium,
    )
}

@Composable
private fun ApprovalOptionButton(
    label: String,
    primary: Boolean,
    destructive: Boolean,
    enabled: Boolean,
    isCoui: Boolean,
    icon: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    val accent = when {
        destructive -> colors.error
        primary -> colors.primary
        else -> colors.onSurfaceVariantSummary
    }
    val background = when {
        !enabled -> colors.surfaceContainerHigh.copy(alpha = 0.6f)
        primary -> colors.primary.copy(alpha = 0.14f)
        destructive -> colors.error.copy(alpha = 0.12f)
        else -> colors.surfaceContainerHigh
    }
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .background(background, RoundedCornerShape(10.dp))
            .then(
                if (isCoui) {
                    Modifier.couiButtonFeedback(
                        CouiTheme.isDark,
                        interactionSource,
                        enabled = enabled,
                    )
                } else {
                    Modifier
                },
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon) {
            Icon(
                imageVector = Icons.Rounded.Send,
                contentDescription = null,
                modifier = Modifier.size(13.dp),
                tint = if (enabled) accent else colors.disabledOnSurface,
            )
            Spacer(modifier = Modifier.width(6.dp))
        }
        Text(
            text = label,
            style = MiuixTheme.textStyles.footnote1,
            color = if (enabled) accent else colors.disabledOnSurface,
            fontWeight = if (primary) FontWeight.Medium else FontWeight.Normal,
        )
    }
}

/** 决策完成后在聊天流里保留回显的时长。 */
internal const val APPROVAL_FEEDBACK_VISIBLE_MILLIS = 6_000L

private fun secondsUntilExpiry(expiresAtMillis: Long): Int {
    val now = System.nanoTime() / 1_000_000
    return ((expiresAtMillis - now) / 1_000).coerceAtLeast(0L).toInt()
}

private fun riskLevelLabel(level: ToolRiskLevel): Int = when (level) {
    ToolRiskLevel.LOW -> R.string.approval_level_low
    ToolRiskLevel.MEDIUM -> R.string.approval_level_medium
    ToolRiskLevel.HIGH -> R.string.approval_level_high
    ToolRiskLevel.CRITICAL -> R.string.approval_level_critical
}

private fun riskCategoryReason(category: ToolRiskCategory): Int = when (category) {
    ToolRiskCategory.SYSTEM_PARTITION_WRITE -> R.string.approval_reason_system_partition
    ToolRiskCategory.BOOT_CHAIN_WRITE -> R.string.approval_reason_boot_chain
    ToolRiskCategory.REMOUNT_READ_WRITE -> R.string.approval_reason_remount
    ToolRiskCategory.DESTRUCTIVE_DELETE -> R.string.approval_reason_delete
    ToolRiskCategory.DISK_FORMAT -> R.string.approval_reason_format
    ToolRiskCategory.SYSTEM_DATA_WRITE -> R.string.approval_reason_system_data
    ToolRiskCategory.FOREIGN_APP_DATA_WRITE -> R.string.approval_reason_app_data
    ToolRiskCategory.SYSTEM_APP_MUTATION -> R.string.approval_reason_app_mutation
    ToolRiskCategory.SENSITIVE_SETTING_WRITE -> R.string.approval_reason_setting
    ToolRiskCategory.PRIVILEGED_SYSTEM_COMMAND -> R.string.approval_reason_privileged
    ToolRiskCategory.INDIRECT_EXECUTION -> R.string.approval_reason_indirect_execution
    ToolRiskCategory.UNRESOLVED_TARGET -> R.string.approval_reason_unresolved_target
    ToolRiskCategory.AGENT_SCHEDULED_TASK_CREATE -> R.string.approval_reason_scheduled_task_create
    ToolRiskCategory.AGENT_SETTINGS_WRITE -> R.string.approval_reason_agent_settings
}

private fun dangerOptionLabel(optionId: String): Int = when (optionId) {
    AgentApprovalOption.ALLOW_ONCE -> R.string.approval_option_allow_once
    AgentApprovalOption.ALLOW_SESSION -> R.string.approval_option_allow_session
    AgentApprovalOption.DENY -> R.string.approval_option_deny
    AgentApprovalOption.DENY_WITH_FEEDBACK -> R.string.approval_option_deny_feedback
    else -> R.string.approval_option_deny
}

private fun riskAccent(level: ToolRiskLevel, error: Color): Color = when (level) {
    ToolRiskLevel.LOW, ToolRiskLevel.MEDIUM -> error.copy(alpha = 0.85f)
    ToolRiskLevel.HIGH, ToolRiskLevel.CRITICAL -> error
}
