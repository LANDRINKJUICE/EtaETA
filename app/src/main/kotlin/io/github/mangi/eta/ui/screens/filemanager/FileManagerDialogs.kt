package io.github.mangi.eta.ui.screens.filemanager

import android.text.format.Formatter
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Save
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.coui.CouiConfirmDialog
import io.github.mangi.eta.ui.components.coui.CouiInputDialog
import io.github.mangi.eta.ui.components.coui.CouiNoticeDialog
import com.itos.coui.checkbox.Checkbox as CouiCheckbox
import com.itos.coui.layout.CouiPageDefaults
import com.itos.coui.theme.CouiTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.layout.DialogDefaults
import top.yukonga.miuix.kmp.window.WindowDialog

// ------------------------------------------------------------------
// 通用展示助手
// ------------------------------------------------------------------

internal fun fmFormatSize(context: android.content.Context, bytes: Long): String =
    Formatter.formatShortFileSize(context, bytes)

internal fun fmFormatDate(epochSeconds: Long): String =
    if (epochSeconds <= 0L) "-" else SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(epochSeconds * 1000L))

/** 文件类型 → 列表图标与主题色；与 MT 的分类图标范式对齐。 */
internal fun fmEntryVisual(entry: FileManagerRepository.FsEntry): Pair<ImageVector, Color> {
    if (entry.isDir) return Icons.Rounded.Folder to Color(0xFFFBC02D)
    val ext = FileManagerRepository.extensionOf(entry.name).lowercase()
    return when (ext) {
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "svg" -> Icons.Rounded.Image to Color(0xFFAB47BC)
        "mp4", "mkv", "avi", "mov", "webm", "3gp", "flv" -> Icons.Rounded.Movie to Color(0xFF42A5F5)
        "mp3", "flac", "wav", "ogg", "m4a", "aac" -> Icons.Rounded.MusicNote to Color(0xFFEF5350)
        "zip", "7z", "rar", "tar", "gz", "xz", "bz2" -> Icons.Rounded.FolderZip to Color(0xFFFF7043)
        "apk" -> Icons.Rounded.Android to Color(0xFF66BB6A)
        "txt", "md", "json", "xml", "html", "css", "js", "ts", "kt", "java", "py", "sh",
        "c", "cpp", "h", "hpp", "go", "rs", "rb", "php", "yml", "yaml", "toml", "ini",
        "log", "conf", "gradle", "csv", "sql", "bat",
        -> Icons.Rounded.Description to Color(0xFF78909C)
        else -> Icons.Rounded.InsertDriveFile to Color(0xFF90A4AE)
    }
}

// ------------------------------------------------------------------
// 复选框（双皮肤分发）
// ------------------------------------------------------------------

@Composable
internal fun FmCheckbox(
    checked: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> {
            val dark = CouiTheme.isDark
            val colors = CouiPageDefaults.themeColors(dark)
            CouiCheckbox(
                modifier = modifier.size(22.dp),
                checked = checked,
                colors = com.itos.coui.checkbox.CouiCheckboxDefaults.colors(colors.accent, dark),
                shape = com.itos.coui.checkbox.CouiCheckboxDefaults.shape,
                borderShape = com.itos.coui.checkbox.CouiCheckboxDefaults.borderShape,
            )
        }
        AppearanceUiStyle.MIUIX -> {
            Checkbox(
                state = if (checked) ToggleableState.On else ToggleableState.Off,
                onClick = onToggle,
                modifier = modifier,
            )
        }
    }
}

@Composable
private fun FmLabeledChoice(
    label: String,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = 2.dp),
    ) {
        FmCheckbox(checked = checked, onToggle = onToggle)
        Spacer(Modifier.width(10.dp))
        Text(label, fontSize = 15.sp)
    }
}

// ------------------------------------------------------------------
// 输入 / 确认 / 提示 / 进度对话框（COUI 与 MIUIX 双皮肤分发）
// ------------------------------------------------------------------

@Composable
internal fun FmInputDialog(
    visible: Boolean,
    title: String,
    message: String? = null,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String? = null,
    confirmText: String,
    confirmEnabled: Boolean = true,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> {
            CouiInputDialog(
                visible = visible,
                title = title,
                message = message,
                value = value,
                onValueChange = onValueChange,
                placeholder = placeholder,
                confirmText = confirmText,
                confirmEnabled = confirmEnabled,
                onConfirm = onConfirm,
                onDismissRequest = onDismiss,
            )
        }
        AppearanceUiStyle.MIUIX -> {
            WindowDialog(
                show = visible,
                cornerRadius = DialogDefaults.CornerRadius,
                title = title,
                summary = message,
                onDismissRequest = onDismiss,
            ) {
                Column {
                    TextField(
                        value = value,
                        onValueChange = onValueChange,
                        label = placeholder ?: "",
                        useLabelAsPlaceholder = true,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    MiuixDialogActions(
                        confirmText = confirmText,
                        confirmEnabled = confirmEnabled,
                        onCancel = onDismiss,
                        onConfirm = onConfirm,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                }
            }
        }
    }
}

@Composable
internal fun FmConfirmDialog(
    visible: Boolean,
    title: String,
    message: String,
    confirmText: String,
    destructive: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> {
            CouiConfirmDialog(
                visible = visible,
                title = title,
                message = message,
                confirmText = confirmText,
                destructive = destructive,
                onConfirm = onConfirm,
                onDismissRequest = onDismiss,
            )
        }
        AppearanceUiStyle.MIUIX -> {
            WindowDialog(
                show = visible,
                cornerRadius = DialogDefaults.CornerRadius,
                title = title,
                summary = message,
                onDismissRequest = onDismiss,
            ) {
                MiuixDialogActions(
                    confirmText = confirmText,
                    confirmEnabled = true,
                    destructive = destructive,
                    onCancel = onDismiss,
                    onConfirm = onConfirm,
                )
            }
        }
    }
}

@Composable
internal fun FmNoticeDialog(
    visible: Boolean,
    title: String,
    message: String,
    onDismiss: () -> Unit,
) {
    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> {
            CouiNoticeDialog(
                visible = visible,
                title = title,
                message = message,
                onDismissRequest = onDismiss,
            )
        }
        AppearanceUiStyle.MIUIX -> {
            WindowDialog(
                show = visible,
                cornerRadius = DialogDefaults.CornerRadius,
                title = title,
                summary = message,
                onDismissRequest = onDismiss,
            ) {
                MiuixDialogActions(
                    confirmText = stringResource(R.string.fm_close),
                    confirmEnabled = true,
                    onCancel = onDismiss,
                    onConfirm = onDismiss,
                )
            }
        }
    }
}

internal data class FmProgressState(
    val title: String,
    val label: String,
    val done: Int,
    val total: Int,
    val currentName: String,
    val cancellable: Boolean = false,
)

@Composable
internal fun FmProgressDialog(
    state: FmProgressState?,
    onCancel: (() -> Unit)? = null,
) {
    if (state == null) return
    val message = buildString {
        append(state.label)
        if (state.currentName.isNotBlank()) {
            append('\n')
            append(state.currentName)
        }
        if (state.total > 0) {
            append("  ")
            append(state.done)
            append('/')
            append(state.total)
        }
    }
    val actionText = if (onCancel != null) stringResource(R.string.fm_cancel) else stringResource(R.string.fm_close)
    val onAction: () -> Unit = { onCancel?.invoke() }
    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> {
            CouiNoticeDialog(
                visible = true,
                title = state.title,
                message = message,
                confirmText = actionText,
                onDismissRequest = onAction,
                content = {
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        InfiniteProgressIndicator(size = 28.dp)
                    }
                },
            )
        }
        AppearanceUiStyle.MIUIX -> {
            WindowDialog(
                show = true,
                cornerRadius = DialogDefaults.CornerRadius,
                title = state.title,
                summary = message,
                onDismissRequest = onAction,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    InfiniteProgressIndicator(size = 28.dp)
                    MiuixDialogActions(
                        confirmText = actionText,
                        confirmEnabled = onCancel != null,
                        onCancel = onAction,
                        onConfirm = onAction,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------------
// 排序对话框
// ------------------------------------------------------------------

@Composable
internal fun FmSortDialog(
    visible: Boolean,
    mode: FileManagerRepository.SortMode,
    ascending: Boolean,
    foldersFirst: Boolean,
    onApply: (FileManagerRepository.SortMode, Boolean, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var localMode by remember(visible) { mutableStateOf(mode) }
    var localAsc by remember(visible) { mutableStateOf(ascending) }
    var localFoldersFirst by remember(visible) { mutableStateOf(foldersFirst) }
    val modeLabels = listOf(
        stringResource(R.string.fm_sort_name),
        stringResource(R.string.fm_sort_size),
        stringResource(R.string.fm_sort_date),
        stringResource(R.string.fm_sort_type),
    )
    val modes = FileManagerRepository.SortMode.entries
    val title = stringResource(R.string.fm_sort)
    val cancel = stringResource(R.string.fm_cancel)
    val confirm = stringResource(R.string.fm_confirm)
    val body: @Composable ColumnScope.() -> Unit = {
        modes.forEachIndexed { index, candidate ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { localMode = candidate },
            ) {
                FmCheckbox(checked = localMode == candidate, onToggle = { localMode = candidate })
                Spacer(Modifier.width(10.dp))
                Text(modeLabels[index], fontSize = 15.sp)
            }
        }
        FmLabeledChoice(
            label = stringResource(R.string.fm_sort_folders_first),
            checked = localFoldersFirst,
            onToggle = { localFoldersFirst = !localFoldersFirst },
        )
        FmLabeledChoice(
            label = if (localAsc) stringResource(R.string.fm_sort_asc) else stringResource(R.string.fm_sort_desc),
            checked = localAsc,
            onToggle = { localAsc = !localAsc },
        )
    }
    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> {
            CouiConfirmDialog(
                visible = visible,
                title = title,
                message = null,
                confirmText = confirm,
                onConfirm = { onApply(localMode, localAsc, localFoldersFirst) },
                onDismissRequest = onDismiss,
                cancelText = cancel,
                content = body,
            )
        }
        AppearanceUiStyle.MIUIX -> {
            WindowDialog(
                show = visible,
                cornerRadius = DialogDefaults.CornerRadius,
                title = title,
                onDismissRequest = onDismiss,
            ) {
                Column {
                    body()
                    MiuixDialogActions(
                        confirmText = confirm,
                        confirmEnabled = true,
                        onCancel = onDismiss,
                        onConfirm = { onApply(localMode, localAsc, localFoldersFirst) },
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------------
// 属性对话框
// ------------------------------------------------------------------

@Composable
internal fun FmPropertiesDialog(
    visible: Boolean,
    details: FileManagerRepository.FileDetails?,
    onDismiss: () -> Unit,
) {
    if (!visible) return
    val context = LocalContext.current
    val title = stringResource(R.string.fm_properties)
    val dismiss = stringResource(R.string.fm_close)
    val body: @Composable ColumnScope.() -> Unit = {
        if (details == null) {
            Text(stringResource(R.string.fm_loading), fontSize = 14.sp)
        } else {
            FmPropertyRow(stringResource(R.string.fm_name), details.path.substringAfterLast('/'))
            FmPropertyRow(
                stringResource(R.string.fm_type),
                if (details.isDir) context.getString(R.string.fm_folder) else context.getString(R.string.fm_file),
            )
            if (!details.isDir) {
                FmPropertyRow(stringResource(R.string.fm_size), fmFormatSize(context, details.sizeBytes) + " (${details.sizeBytes} B)")
            } else if (details.childCount != null) {
                FmPropertyRow(stringResource(R.string.fm_contains), context.getString(R.string.fm_dir_item_count, details.childCount))
            }
            FmPropertyRow(stringResource(R.string.fm_location), details.path.substringBeforeLast('/'))
            FmPropertyRow(stringResource(R.string.fm_date), fmFormatDate(details.mtimeEpochSeconds))
            details.modeText?.let { FmPropertyRow(stringResource(R.string.fm_mode), it) }
            details.owner?.let { FmPropertyRow(stringResource(R.string.fm_owner), it) }
            details.group?.let { FmPropertyRow(stringResource(R.string.fm_group), it) }
        }
    }
    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> {
            CouiNoticeDialog(
                visible = true,
                title = title,
                message = null,
                confirmText = dismiss,
                onDismissRequest = onDismiss,
                content = body,
            )
        }
        AppearanceUiStyle.MIUIX -> {
            WindowDialog(
                show = true,
                cornerRadius = DialogDefaults.CornerRadius,
                title = title,
                onDismissRequest = onDismiss,
            ) {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    body()
                    MiuixDialogActions(
                        confirmText = dismiss,
                        confirmEnabled = true,
                        onCancel = onDismiss,
                        onConfirm = onDismiss,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun FmPropertyRow(label: String, value: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Text(label, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        Text(value, fontSize = 14.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

// ------------------------------------------------------------------
// 权限对话框（rwx × 属主/属组/其他）
// ------------------------------------------------------------------

internal data class FmPermissionBits(
    val owner: BooleanArray,
    val group: BooleanArray,
    val others: BooleanArray,
) {
    fun toModeBits(): Int {
        fun groupBits(bits: BooleanArray): Int {
            var v = 0
            if (bits[0]) v = v or 4
            if (bits[1]) v = v or 2
            if (bits[2]) v = v or 1
            return v
        }
        return (groupBits(owner) shl 6) or (groupBits(group) shl 3) or groupBits(others)
    }

    // BooleanArray 内容相等按位比较，避免默认 equals 用引用比较。
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FmPermissionBits) return false
        return owner.contentEquals(other.owner) &&
            group.contentEquals(other.group) &&
            others.contentEquals(other.others)
    }

    override fun hashCode(): Int = owner.contentHashCode() * 31 + group.contentHashCode() * 17 + others.contentHashCode()

    companion object {
        fun fromModeText(text: String): FmPermissionBits? {
            if (text.length != 9) return null
            fun parse(segment: String) = BooleanArray(3) { i -> segment[i] != '-' }
            return FmPermissionBits(
                owner = parse(text.substring(0, 3)),
                group = parse(text.substring(3, 6)),
                others = parse(text.substring(6, 9)),
            )
        }
    }
}

@Composable
internal fun FmPermissionsDialog(
    visible: Boolean,
    targetPath: String,
    modeText: String?,
    onApply: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    if (!visible) return
    var bits by remember(visible, modeText) {
        mutableStateOf(
            modeText?.let { FmPermissionBits.fromModeText(it) }
                ?: FmPermissionBits(
                    owner = booleanArrayOf(true, true, false),
                    group = booleanArrayOf(true, false, false),
                    others = booleanArrayOf(true, false, false),
                ),
        )
    }
    val rowLabels = listOf(
        stringResource(R.string.fm_perm_owner),
        stringResource(R.string.fm_perm_group),
        stringResource(R.string.fm_perm_others),
    )
    val columnLabels = listOf(
        stringResource(R.string.fm_perm_read),
        stringResource(R.string.fm_perm_write),
        stringResource(R.string.fm_perm_exec),
    )
    val title = stringResource(R.string.fm_permissions)
    val cancel = stringResource(R.string.fm_cancel)
    val confirm = stringResource(R.string.fm_confirm)
    val body: @Composable ColumnScope.() -> Unit = {
        Text(targetPath.substringAfterLast('/'), fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(8.dp))
        Row {
            Spacer(Modifier.width(76.dp))
            columnLabels.forEach { label ->
                Box(Modifier.width(64.dp), contentAlignment = Alignment.Center) {
                    Text(label, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                }
            }
        }
        val groups = listOf(bits.owner, bits.group, bits.others)
        rowLabels.forEachIndexed { rowIndex, label ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Box(Modifier.width(76.dp)) {
                    Text(label, fontSize = 14.sp)
                }
                groups[rowIndex].forEachIndexed { colIndex, checked ->
                    Box(Modifier.width(64.dp), contentAlignment = Alignment.Center) {
                        FmCheckbox(
                            checked = checked,
                            onToggle = {
                                bits = when (rowIndex) {
                                    0 -> bits.copy(owner = bits.owner.copyOf().also { it[colIndex] = !checked })
                                    1 -> bits.copy(group = bits.group.copyOf().also { it[colIndex] = !checked })
                                    else -> bits.copy(others = bits.others.copyOf().also { it[colIndex] = !checked })
                                }
                            },
                        )
                    }
                }
            }
        }
    }
    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> {
            CouiConfirmDialog(
                visible = true,
                title = title,
                message = null,
                confirmText = confirm,
                onConfirm = { onApply(bits.toModeBits()) },
                onDismissRequest = onDismiss,
                cancelText = cancel,
                content = body,
            )
        }
        AppearanceUiStyle.MIUIX -> {
            WindowDialog(
                show = true,
                cornerRadius = DialogDefaults.CornerRadius,
                title = title,
                onDismissRequest = onDismiss,
            ) {
                Column {
                    body()
                    MiuixDialogActions(
                        confirmText = confirm,
                        confirmEnabled = true,
                        onCancel = onDismiss,
                        onConfirm = { onApply(bits.toModeBits()) },
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------------
// 文本编辑器 / 图片查看（全屏覆盖层，MT 编辑器范式）
// ------------------------------------------------------------------

internal data class FmEditorState(
    val path: String,
    val initialContent: String,
    val readOnly: Boolean,
)

@Composable
internal fun FmEditorOverlay(
    state: FmEditorState,
    content: String,
    onContentChange: (String) -> Unit,
    saving: Boolean,
    onSave: () -> Unit,
    onClose: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MiuixTheme.colorScheme.surface)
            .imePadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = stringResource(R.string.action_back),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    state.path.substringAfterLast('/'),
                    fontSize = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (state.readOnly) stringResource(R.string.fm_editor_readonly) else state.path,
                    fontSize = 11.sp,
                    color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (saving) {
                InfiniteProgressIndicator(size = 22.dp)
            } else if (!state.readOnly) {
                IconButton(onClick = onSave, enabled = content != state.initialContent) {
                    Icon(
                        imageVector = Icons.Rounded.Save,
                        contentDescription = stringResource(R.string.fm_editor_save),
                    )
                }
            }
        }
        BasicTextField(
            value = content,
            onValueChange = onContentChange,
            readOnly = state.readOnly,
            textStyle = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                color = MiuixTheme.colorScheme.onSurface,
            ),
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
        )
    }
}

@Composable
internal fun FmImageOverlay(
    path: String,
    bitmap: android.graphics.Bitmap?,
    loading: Boolean,
    failed: Boolean,
    onClose: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = stringResource(R.string.action_back),
                    tint = Color.White,
                )
            }
            Text(
                path.substringAfterLast('/'),
                fontSize = 15.sp,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            when {
                loading -> InfiniteProgressIndicator(size = 28.dp)
                failed -> Text(stringResource(R.string.fm_open_unsupported), color = Color.White, fontSize = 14.sp)
                bitmap != null -> Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = path.substringAfterLast('/'),
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            }
        }
    }
}
