package io.github.mangi.eta.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.mcp.McpApprovalGate
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.components.coui.CouiButton
import io.github.mangi.eta.ui.components.coui.CouiButtonRole
import io.github.mangi.eta.ui.components.coui.CouiNoticeDialog
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.glass.GlassDialog
import top.yukonga.miuix.kmp.glass.GlassDialogButton
import top.yukonga.miuix.kmp.glass.GlassDialogButtonRole
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 审批卡：拦截 DSH 经 MCP 调用 Eta 工具时的一次性授权。
 *
 * 三个动作：允许一次 / 本会话总是允许 / 拒绝。只读工具不会走到这里。
 */
@Composable
internal fun AgentApprovalDialog(
    approval: McpApprovalGate.PendingApproval?,
    onAllow: () -> Unit,
    onAllowAlways: () -> Unit,
    onDeny: () -> Unit,
) {
    val current = approval ?: return
    // 弹窗关闭动画期间保留上一张卡的内容，避免文字瞬间变空。
    var retained by remember { mutableStateOf(current) }
    if (current.id != retained.id) retained = current
    val shown = retained
    val title = stringResource(R.string.approval_title)
    val riskLabel = stringResource(
        when (shown.risk) {
            McpApprovalGate.ApprovalRisk.Mutating -> R.string.approval_risk_mutating
            McpApprovalGate.ApprovalRisk.Root -> R.string.approval_risk_root
        },
    )
    val sourceLabel = stringResource(
        when (shown.source) {
            McpApprovalGate.ApprovalSource.Mcp -> R.string.approval_source_mcp
        },
    )
    val allowText = stringResource(R.string.approval_allow_once)
    val allowAlwaysText = stringResource(R.string.approval_allow_always)
    val denyText = stringResource(R.string.approval_deny)

    val body: @Composable () -> Unit = {
        Column(
            modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Badge(text = sourceLabel)
                Badge(text = riskLabel)
            }
            Text(
                text = shown.toolName,
                style = MiuixTheme.textStyles.body1.copy(fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
                color = MiuixTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = shown.summary,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            )
        }
    }

    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> {
            CouiNoticeDialog(
                visible = true,
                title = title,
                message = null,
                confirmText = allowText,
                onDismissRequest = onDeny,
                content = {
                    body()
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        CouiButton(
                            text = allowAlwaysText,
                            onClick = onAllowAlways,
                            role = CouiButtonRole.Secondary,
                            modifier = Modifier.weight(1f),
                        )
                        CouiButton(
                            text = denyText,
                            onClick = onDeny,
                            role = CouiButtonRole.Destructive,
                            modifier = Modifier.weight(1f),
                        )
                    }
                },
            )
        }
        AppearanceUiStyle.MIUIX -> {
            GlassDialog(
                show = true,
                title = title,
                backdrop = currentGlassOverlayBackdrop(),
                onDismissRequest = onDeny,
            ) {
                Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    body()
                    GlassDialogButton(
                        onClick = onAllow,
                        role = GlassDialogButtonRole.Primary,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(text = allowText, style = MiuixTheme.textStyles.button, maxLines = 1)
                    }
                    GlassDialogButton(
                        onClick = onAllowAlways,
                        role = GlassDialogButtonRole.Normal,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(text = allowAlwaysText, style = MiuixTheme.textStyles.button, maxLines = 1)
                    }
                    GlassDialogButton(
                        onClick = onDeny,
                        role = GlassDialogButtonRole.Normal,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(text = denyText, style = MiuixTheme.textStyles.button, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun Badge(text: String) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.footnote1.copy(fontSize = 11.sp, fontWeight = FontWeight.Medium),
        color = MiuixTheme.colorScheme.primary,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.12f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
