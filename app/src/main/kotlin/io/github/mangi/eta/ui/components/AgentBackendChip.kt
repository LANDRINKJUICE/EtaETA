package io.github.mangi.eta.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.itos.coui.effect.couiButtonFeedback
import io.github.mangi.eta.R
import io.github.mangi.eta.data.db.ConversationEntity
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.ui.app.LocalUiStyle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.glass.glassPress
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 会话后端胶囊：空会话上单击在 Eta / DSH 之间切换。
 *
 * 只在没有消息的空会话里出现，因此不存在"聊到一半换后端"的非法状态；
 * 已有消息的会话由会话列表角标与顶栏标记表达后端，不再提供切换入口。
 */
@Composable
internal fun AgentBackendChip(
    backend: String,
    onBackendChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isDsh = backend == ConversationEntity.BACKEND_DSH
    val target = if (isDsh) ConversationEntity.BACKEND_ETA else ConversationEntity.BACKEND_DSH
    val colors = MiuixTheme.colorScheme
    val isDark = colors.surface.luminance() < 0.5f
    val isCoui = LocalUiStyle.current == AppearanceUiStyle.COUI
    val ink = colors.onSurface
    val haptic = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }

    val accent = if (isDsh) colors.primary else ink.copy(alpha = if (isDark) 0.58f else 0.54f)
    val contentColor by animateColorAsState(
        targetValue = accent,
        animationSpec = tween(durationMillis = 160),
        label = "backend_content",
    )
    val bgColor by animateColorAsState(
        targetValue = if (isDsh) {
            colors.primary.copy(alpha = if (isDark) 0.18f else 0.10f)
        } else {
            ink.copy(alpha = if (isDark) 0.06f else 0.038f)
        },
        animationSpec = tween(durationMillis = 160),
        label = "backend_bg",
    )
    val borderColor by animateColorAsState(
        targetValue = if (isDsh) {
            colors.primary.copy(alpha = if (isDark) 0.30f else 0.22f)
        } else {
            ink.copy(alpha = if (isDark) 0.10f else 0.065f)
        },
        animationSpec = tween(durationMillis = 160),
        label = "backend_border",
    )

    val label = stringResource(
        if (isDsh) R.string.chat_backend_dsh else R.string.chat_backend_eta,
    )
    val accessibility = stringResource(R.string.chat_backend_switch, label)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .heightIn(min = 30.dp)
            .clip(CircleShape)
            .background(bgColor, CircleShape)
            .border(0.5.dp, borderColor, CircleShape)
            .then(
                if (isCoui) {
                    Modifier.couiButtonFeedback(isDark, interactionSource, enabled = true)
                } else {
                    Modifier.glassPress(interactionSource, enabled = true)
                },
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onBackendChange(target)
                },
            )
            .semantics { contentDescription = accessibility }
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(
            text = label,
            style = MiuixTheme.textStyles.footnote1.copy(
                fontSize = 12.5.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight.Medium,
            ),
            color = contentColor,
            maxLines = 1,
        )
    }
}
