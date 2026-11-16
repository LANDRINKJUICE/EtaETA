package io.github.mangi.eta.ui.screens.filemanager

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.BookmarkRemove
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.CheckBoxOutlineBlank
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.NoteAdd
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.itos.coui.menu.CouiMenuItem
import com.itos.coui.menu.Menu
import com.itos.coui.menu.couiMenuAnchor
import com.itos.coui.menu.rememberCouiMenuAnchor
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.components.coui.CouiIconDivider
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.LocalDismissState
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowListPopup

// ------------------------------------------------------------------
// 窗格状态（MT 双窗范式：两窗各自持有路径、排序、多选与搜索态）
// ------------------------------------------------------------------

internal class PaneUiState(initialPath: String) {
    var path by mutableStateOf(initialPath)
    var entries by mutableStateOf<List<FileManagerRepository.FsEntry>?>(null)
    var error by mutableStateOf<String?>(null)
    var showHidden by mutableStateOf(false)
    var sortMode by mutableStateOf(FileManagerRepository.SortMode.NAME)
    var sortAsc by mutableStateOf(true)
    var foldersFirst by mutableStateOf(true)
    var selection by mutableStateOf<Set<String>>(emptySet())
    var history by mutableStateOf(listOf<String>())
    var searchQuery by mutableStateOf<String?>(null)
    var searchResults by mutableStateOf<List<FileManagerRepository.FsEntry>>(emptyList())
    var searching by mutableStateOf(false)
}

internal data class FmClipboard(
    val paths: List<String>,
    val move: Boolean,
    val homePane: Int,
)

internal sealed interface FmDialogState {
    data class Jump(val pane: Int) : FmDialogState
    data class Create(val pane: Int, val isDir: Boolean) : FmDialogState
    data class Rename(val pane: Int, val entry: FileManagerRepository.FsEntry) : FmDialogState
    data class Delete(val paths: List<String>) : FmDialogState
    data class Zip(val paths: List<String>, val dir: String) : FmDialogState
    data class Unzip(val zipPath: String, val dir: String) : FmDialogState
    data class Sort(val pane: Int) : FmDialogState
    data class Search(val pane: Int) : FmDialogState
    data class Properties(val path: String) : FmDialogState
    data class Permissions(val path: String) : FmDialogState
}

/** 窗格回调集合：索引闭包化，两窗共用同一份实现。 */
internal data class FmPaneCallbacks(
    val onOpen: (FileManagerRepository.FsEntry) -> Unit,
    val onLongPress: (FileManagerRepository.FsEntry) -> Unit,
    val onToggleSelect: (FileManagerRepository.FsEntry) -> Unit,
    val onPaneBack: () -> Unit,
    val onSelectAll: () -> Unit,
    val onClearSelection: () -> Unit,
    val onJump: () -> Unit,
    val onSearch: () -> Unit,
    val onClearSearch: () -> Unit,
    val onPaste: () -> Unit,
    val onCreate: (Boolean) -> Unit,
    val onSort: () -> Unit,
    val onToggleShowHidden: () -> Unit,
    val onToggleBookmark: () -> Unit,
    val onJumpBookmark: (String) -> Unit,
)

private val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic")

private const val BOOKMARK_PREFS = "eta_file_manager_prefs"
private const val BOOKMARK_KEY = "bookmarks"
private const val BOOKMARK_MAX = 12

private fun loadBookmarks(context: Context): List<String> =
    context.getSharedPreferences(BOOKMARK_PREFS, Context.MODE_PRIVATE)
        .getString(BOOKMARK_KEY, "")?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()

private fun saveBookmarks(context: Context, bookmarks: List<String>) {
    context.getSharedPreferences(BOOKMARK_PREFS, Context.MODE_PRIVATE)
        .edit().putString(BOOKMARK_KEY, bookmarks.joinToString("\n")).apply()
}

// ------------------------------------------------------------------
// 屏幕入口
// ------------------------------------------------------------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun FileManagerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()

    val panes = remember {
        listOf(
            PaneUiState(FileManagerRepository.DEFAULT_LEFT_DIR),
            PaneUiState(FileManagerRepository.DEFAULT_RIGHT_DIR),
        )
    }
    var refreshTick by remember { mutableIntStateOf(0) }
    var clipboard by remember { mutableStateOf<FmClipboard?>(null) }
    var lastActionPane by remember { mutableIntStateOf(0) }
    var dialog by remember { mutableStateOf<FmDialogState?>(null) }
    var opJob by remember { mutableStateOf<Job?>(null) }
    var opProgress by remember { mutableStateOf<FmProgressState?>(null) }
    var editorState by remember { mutableStateOf<FmEditorState?>(null) }
    var editorContent by remember { mutableStateOf("") }
    var editorSaving by remember { mutableStateOf(false) }
    var imagePath by remember { mutableStateOf<String?>(null) }
    var imageBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var imageLoading by remember { mutableStateOf(false) }
    var imageFailed by remember { mutableStateOf(false) }
    var propertiesDetails by remember { mutableStateOf<FileManagerRepository.FileDetails?>(null) }
    var bookmarks by remember { mutableStateOf(loadBookmarks(context)) }

    fun toast(message: String) = Toast.makeText(context, message, Toast.LENGTH_SHORT).show()

    fun refreshAll() {
        refreshTick++
    }

    fun toggleBookmark(pane: PaneUiState) {
        val normalized = FileManagerRepository.normalizePath(pane.path) ?: return
        bookmarks = if (normalized in bookmarks) {
            bookmarks - normalized
        } else {
            (bookmarks + normalized).takeLast(BOOKMARK_MAX)
        }
        saveBookmarks(context, bookmarks)
    }

    fun runOp(
        titleRes: Int,
        labelRes: Int,
        cancellable: Boolean = false,
        block: ((Int, Int, String) -> Unit) -> FileManagerRepository.OpResult,
    ) {
        opJob?.cancel()
        opJob = scope.launch {
            opProgress = FmProgressState(
                title = resources.getString(titleRes),
                label = resources.getString(labelRes),
                done = 0,
                total = 0,
                currentName = "",
                cancellable = cancellable,
            )
            try {
                val result = withContext(Dispatchers.IO) {
                    block { done, total, current ->
                        opProgress = opProgress?.copy(done = done, total = total, currentName = current)
                    }
                }
                opProgress = null
                toast(
                    when (result) {
                        is FileManagerRepository.OpResult.Success -> resources.getString(R.string.fm_done)
                        is FileManagerRepository.OpResult.Failed ->
                            resources.getString(R.string.fm_failed, result.code)
                    },
                )
            } catch (cancelled: CancellationException) {
                opProgress = null
                throw cancelled
            } finally {
                refreshTick++
            }
        }
    }

    fun clearSelections() {
        panes.forEach { it.selection = emptySet() }
    }

    fun enterSelection(index: Int, path: String) {
        lastActionPane = index
        panes[index].selection = setOf(path)
    }

    fun toggleSelection(index: Int, path: String) {
        lastActionPane = index
        val pane = panes[index]
        pane.selection = if (path in pane.selection) pane.selection - path else pane.selection + path
    }

    fun paneBack(index: Int) {
        val pane = panes[index]
        pane.history.lastOrNull()?.let { previous ->
            pane.history = pane.history.dropLast(1)
            pane.path = previous
            pane.selection = emptySet()
            pane.searchQuery = null
        }
    }

    fun onPaste(index: Int) {
        val clip = clipboard ?: return
        val targetDir = panes[index].path
        clipboard = null
        runOp(
            titleRes = R.string.route_file_manager,
            labelRes = if (clip.move) R.string.fm_op_moving else R.string.fm_op_copying,
            cancellable = true,
        ) { onProgress ->
            FileManagerRepository.copyOrMove(clip.paths, targetDir, clip.move, onProgress)
        }
    }

    fun openEntry(index: Int, entry: FileManagerRepository.FsEntry) {
        val pane = panes[index]
        if (entry.isDir) {
            pane.history = pane.history + pane.path
            pane.path = FileManagerRepository.normalizePath(entry.path) ?: entry.path
            pane.selection = emptySet()
            pane.searchQuery = null
            pane.error = null
            return
        }
        val ext = FileManagerRepository.extensionOf(entry.name).lowercase()
        when {
            ext in IMAGE_EXTS -> {
                imagePath = entry.path
                imageBitmap = null
                imageFailed = false
            }
            ext == "zip" -> dialog = FmDialogState.Unzip(zipPath = entry.path, dir = pane.path)
            else -> scope.launch {
                val read = withContext(Dispatchers.IO) { FileManagerRepository.readText(entry.path) }
                when (read) {
                    is FileManagerRepository.TextReadResult.Text -> {
                        editorState = FmEditorState(entry.path, read.content, read.truncated)
                        editorContent = read.content
                    }
                    is FileManagerRepository.TextReadResult.Binary ->
                        dialog = FmDialogState.Properties(entry.path)
                    is FileManagerRepository.TextReadResult.Failed ->
                        toast(resources.getString(R.string.fm_failed, read.code))
                }
            }
        }
    }

    fun callbacksFor(index: Int): FmPaneCallbacks = FmPaneCallbacks(
        onOpen = { openEntry(index, it) },
        onLongPress = { enterSelection(index, it.path) },
        onToggleSelect = { toggleSelection(index, it.path) },
        onPaneBack = { paneBack(index) },
        onSelectAll = {
            lastActionPane = index
            panes[index].selection = panes[index].entries.orEmpty().map { it.path }.toSet()
        },
        onClearSelection = { panes[index].selection = emptySet() },
        onJump = { dialog = FmDialogState.Jump(index) },
        onSearch = { dialog = FmDialogState.Search(index) },
        onClearSearch = {
            panes[index].searchQuery = null
            panes[index].searchResults = emptyList()
        },
        onPaste = { onPaste(index) },
        onCreate = { isDir -> dialog = FmDialogState.Create(index, isDir) },
        onSort = { dialog = FmDialogState.Sort(index) },
        onToggleShowHidden = { panes[index].showHidden = !panes[index].showHidden },
        onToggleBookmark = { toggleBookmark(panes[index]) },
        onJumpBookmark = { path ->
            val pane = panes[index]
            pane.history = pane.history + pane.path
            pane.path = path
            pane.searchQuery = null
        },
    )

    // 目录加载：路径或 refreshTick 变化即重载（增删改后统一 ++）。
    panes.forEach { pane ->
        LaunchedEffect(pane.path, refreshTick) {
            pane.error = null
            val result = withContext(Dispatchers.IO) { FileManagerRepository.list(pane.path) }
            if (pane.searchQuery == null) {
                when (result) {
                    is FileManagerRepository.ListResult.Success -> {
                        pane.entries = result.entries
                        pane.error = null
                    }
                    is FileManagerRepository.ListResult.NotDirectory -> {
                        pane.entries = emptyList()
                        pane.error = "NOT_DIRECTORY"
                    }
                    is FileManagerRepository.ListResult.Unreadable -> {
                        pane.entries = emptyList()
                        pane.error = "UNREADABLE"
                    }
                    is FileManagerRepository.ListResult.Failed -> {
                        pane.entries = emptyList()
                        pane.error = result.code
                    }
                }
            }
        }
    }

    // 属性 / 权限对话框打开时异步取详情。
    LaunchedEffect(dialog) {
        val target = when (val d = dialog) {
            is FmDialogState.Properties -> d.path
            is FmDialogState.Permissions -> d.path
            else -> null
        } ?: return@LaunchedEffect
        propertiesDetails = null
        propertiesDetails = withContext(Dispatchers.IO) { FileManagerRepository.fileDetails(target) }
    }

    // 图片查看器加载。
    LaunchedEffect(imagePath) {
        val path = imagePath ?: return@LaunchedEffect
        imageLoading = true
        imageFailed = false
        imageBitmap = null
        val read = withContext(Dispatchers.IO) {
            FileManagerRepository.readFileBytes(path, 20L * 1024 * 1024)
        }
        imageLoading = false
        if (read == null) {
            imageFailed = true
        } else {
            imageBitmap = BitmapFactory.decodeByteArray(read.first, 0, read.first.size)
            imageFailed = imageBitmap == null
        }
    }

    // 返回键：先清多选，再关覆盖层，最后才交给导航出栈。
    val anySelection = panes.any { it.selection.isNotEmpty() }
    BackHandler(enabled = anySelection || editorState != null || imagePath != null) {
        when {
            anySelection -> clearSelections()
            editorState != null -> editorState = null
            imagePath != null -> imagePath = null
        }
    }

    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    var splitFraction by rememberSaveable { mutableFloatStateOf(0.5f) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }

    fun applyDrag(deltaPx: Float) {
        val span = if (isLandscape) containerSize.width else containerSize.height
        if (span > 0) {
            splitFraction = (splitFraction + deltaPx / span).coerceIn(0.2f, 0.8f)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { containerSize = it },
    ) {
        if (isLandscape) {
            Row(modifier = Modifier.fillMaxSize()) {
                Box(Modifier.weight(splitFraction)) {
                    FmPane(0, panes[0], clipboard, bookmarks, if (anySelection) 76.dp else 0.dp, callbacksFor(0))
                }
                FmDivider(horizontal = false, onDrag = ::applyDrag)
                Box(Modifier.weight(1f - splitFraction)) {
                    FmPane(1, panes[1], clipboard, bookmarks, if (anySelection) 76.dp else 0.dp, callbacksFor(1))
                }
            }
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                Box(Modifier.weight(splitFraction)) {
                    FmPane(0, panes[0], clipboard, bookmarks, if (anySelection) 76.dp else 0.dp, callbacksFor(0))
                }
                FmDivider(horizontal = true, onDrag = ::applyDrag)
                Box(Modifier.weight(1f - splitFraction)) {
                    FmPane(1, panes[1], clipboard, bookmarks, if (anySelection) 76.dp else 0.dp, callbacksFor(1))
                }
            }
        }

        // ---- 底部多选操作栏（MT 底栏范式） ----
        if (anySelection) {
            val selected = panes[lastActionPane].selection.toList()
            FmActionBar(
                onSelectAll = callbacksFor(lastActionPane).onSelectAll,
                onCopy = {
                    if (selected.isNotEmpty()) {
                        clipboard = FmClipboard(selected, move = false, homePane = lastActionPane)
                        clearSelections()
                    }
                },
                onCut = {
                    if (selected.isNotEmpty()) {
                        clipboard = FmClipboard(selected, move = true, homePane = lastActionPane)
                        clearSelections()
                    }
                },
                onDelete = { dialog = FmDialogState.Delete(selected.toList()) },
                onRename = {
                    if (selected.size == 1) {
                        panes[lastActionPane].entries.orEmpty()
                            .firstOrNull { it.path == selected[0] }
                            ?.let { dialog = FmDialogState.Rename(lastActionPane, it) }
                    }
                },
                onZip = {
                    if (selected.isNotEmpty()) {
                        dialog = FmDialogState.Zip(selected.toList(), panes[lastActionPane].path)
                    }
                },
                onProperties = {
                    if (selected.size == 1) dialog = FmDialogState.Properties(selected[0])
                },
                onPermissions = {
                    if (selected.size == 1) dialog = FmDialogState.Permissions(selected[0])
                },
                onCancel = ::clearSelections,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(),
            )
        }

        // ---- 对话框宿主 ----
        when (val d = dialog) {
            is FmDialogState.Jump -> {
                var input by remember(d) { mutableStateOf(panes[d.pane].path) }
                FmInputDialog(
                    visible = true,
                    title = stringResource(R.string.fm_jump_path),
                    value = input,
                    onValueChange = { input = it },
                    placeholder = stringResource(R.string.fm_jump_hint),
                    confirmText = stringResource(R.string.fm_confirm),
                    confirmEnabled = FileManagerRepository.normalizePath(input) != null,
                    onConfirm = {
                        val target = FileManagerRepository.normalizePath(input) ?: return@FmInputDialog
                        val pane = panes[d.pane]
                        pane.history = pane.history + pane.path
                        pane.path = target
                        pane.searchQuery = null
                        dialog = null
                    },
                    onDismiss = { dialog = null },
                )
            }
            is FmDialogState.Create -> {
                var input by remember(d) { mutableStateOf("") }
                val cleanName = input.trim()
                FmInputDialog(
                    visible = true,
                    title = stringResource(if (d.isDir) R.string.fm_new_folder_title else R.string.fm_new_file_title),
                    value = input,
                    onValueChange = { input = it },
                    placeholder = stringResource(R.string.fm_rename_hint),
                    confirmText = stringResource(R.string.fm_confirm),
                    confirmEnabled = cleanName.isNotEmpty() && '/' !in cleanName &&
                        cleanName != "." && cleanName != "..",
                    onConfirm = {
                        val target = panes[d.pane].path.trimEnd('/') + "/" + cleanName
                        dialog = null
                        runOp(titleRes = R.string.route_file_manager, labelRes = R.string.fm_loading) {
                            if (d.isDir) FileManagerRepository.makeDir(target) else FileManagerRepository.createFile(target)
                        }
                    },
                    onDismiss = { dialog = null },
                )
            }
            is FmDialogState.Rename -> {
                var input by remember(d) { mutableStateOf(d.entry.name) }
                val cleanName = input.trim()
                FmInputDialog(
                    visible = true,
                    title = stringResource(R.string.fm_rename_title),
                    value = input,
                    onValueChange = { input = it },
                    placeholder = stringResource(R.string.fm_rename_hint),
                    confirmText = stringResource(R.string.fm_confirm),
                    confirmEnabled = cleanName.isNotEmpty() && '/' !in cleanName &&
                        cleanName != d.entry.name && cleanName != "." && cleanName != "..",
                    onConfirm = {
                        dialog = null
                        runOp(titleRes = R.string.route_file_manager, labelRes = R.string.fm_loading) {
                            FileManagerRepository.rename(d.entry.path, cleanName)
                        }
                    },
                    onDismiss = { dialog = null },
                )
            }
            is FmDialogState.Delete -> {
                FmConfirmDialog(
                    visible = true,
                    title = stringResource(R.string.fm_confirm_delete_title),
                    message = stringResource(R.string.fm_confirm_delete_message, d.paths.size),
                    confirmText = stringResource(R.string.fm_confirm),
                    destructive = true,
                    onConfirm = {
                        dialog = null
                        runOp(
                            titleRes = R.string.fm_confirm_delete_title,
                            labelRes = R.string.fm_op_deleting,
                            cancellable = true,
                        ) { onProgress ->
                            FileManagerRepository.delete(d.paths, onProgress)
                        }
                    },
                    onDismiss = { dialog = null },
                )
            }
            is FmDialogState.Zip -> {
                var input by remember(d) {
                    mutableStateOf(
                        (d.paths.firstOrNull()?.substringAfterLast('/') ?: "archive").let { name ->
                            if (name.endsWith(".zip")) name else name + ".zip"
                        },
                    )
                }
                val cleanName = input.trim()
                FmInputDialog(
                    visible = true,
                    title = stringResource(R.string.fm_compress_title),
                    value = input,
                    onValueChange = { input = it },
                    placeholder = stringResource(R.string.fm_compress_hint),
                    confirmText = stringResource(R.string.fm_confirm),
                    confirmEnabled = cleanName.endsWith(".zip") && cleanName.length > 4 && '/' !in cleanName,
                    onConfirm = {
                        dialog = null
                        runOp(
                            titleRes = R.string.fm_compress_title,
                            labelRes = R.string.fm_op_zipping,
                            cancellable = true,
                        ) { onProgress ->
                            FileManagerRepository.zip(
                                sources = d.paths,
                                targetZipPath = d.dir.trimEnd('/') + "/" + cleanName,
                                stagingDir = context.cacheDir,
                                onProgress = onProgress,
                            )
                        }
                    },
                    onDismiss = { dialog = null },
                )
            }
            is FmDialogState.Unzip -> {
                FmConfirmDialog(
                    visible = true,
                    title = stringResource(R.string.fm_extract_title),
                    message = stringResource(R.string.fm_extract_message, d.dir),
                    confirmText = stringResource(R.string.fm_extract),
                    onConfirm = {
                        dialog = null
                        runOp(
                            titleRes = R.string.fm_extract_title,
                            labelRes = R.string.fm_op_unzipping,
                            cancellable = true,
                        ) {
                            FileManagerRepository.unzip(d.zipPath, d.dir, context.cacheDir)
                        }
                    },
                    onDismiss = { dialog = null },
                )
            }
            is FmDialogState.Sort -> {
                val pane = panes[d.pane]
                FmSortDialog(
                    visible = true,
                    mode = pane.sortMode,
                    ascending = pane.sortAsc,
                    foldersFirst = pane.foldersFirst,
                    onApply = { mode, asc, foldersFirst ->
                        pane.sortMode = mode
                        pane.sortAsc = asc
                        pane.foldersFirst = foldersFirst
                        dialog = null
                    },
                    onDismiss = { dialog = null },
                )
            }
            is FmDialogState.Search -> {
                var input by remember(d) { mutableStateOf(panes[d.pane].searchQuery ?: "") }
                FmInputDialog(
                    visible = true,
                    title = stringResource(R.string.fm_search),
                    value = input,
                    onValueChange = { input = it },
                    placeholder = stringResource(R.string.fm_search_hint),
                    confirmText = stringResource(R.string.fm_confirm),
                    confirmEnabled = input.trim().isNotEmpty(),
                    onConfirm = {
                        dialog = null
                        val pane = panes[d.pane]
                        val query = input.trim()
                        scope.launch {
                            pane.searching = true
                            val results = withContext(Dispatchers.IO) {
                                FileManagerRepository.search(pane.path, query)
                            }
                            pane.searching = false
                            pane.searchQuery = query
                            pane.searchResults = results
                        }
                    },
                    onDismiss = { dialog = null },
                )
            }
            is FmDialogState.Properties -> {
                FmPropertiesDialog(
                    visible = true,
                    details = propertiesDetails,
                    onDismiss = { dialog = null },
                )
            }
            is FmDialogState.Permissions -> {
                FmPermissionsDialog(
                    visible = true,
                    targetPath = d.path,
                    modeText = propertiesDetails?.modeText,
                    onApply = { modeBits ->
                        dialog = null
                        runOp(titleRes = R.string.fm_permissions, labelRes = R.string.fm_loading) {
                            FileManagerRepository.chmod(d.path, modeBits)
                        }
                    },
                    onDismiss = { dialog = null },
                )
            }
            null -> Unit
        }

        // ---- 进度 / 编辑器 / 图片覆盖层 ----
        FmProgressDialog(
            state = opProgress,
            onCancel = if (opProgress?.cancellable == true) {
                { opJob?.cancel() }
            } else null,
        )
        editorState?.let { state ->
            FmEditorOverlay(
                state = state,
                content = editorContent,
                onContentChange = { editorContent = it },
                saving = editorSaving,
                onSave = {
                    val target = state.path
                    editorSaving = true
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            FileManagerRepository.writeText(target, editorContent, context.cacheDir)
                        }
                        editorSaving = false
                        when (result) {
                            is FileManagerRepository.OpResult.Success -> {
                                editorState = state.copy(initialContent = editorContent)
                                toast(resources.getString(R.string.fm_saved))
                                refreshTick++
                            }
                            is FileManagerRepository.OpResult.Failed ->
                                toast(resources.getString(R.string.fm_failed, result.code))
                        }
                    }
                },
                onClose = { editorState = null },
            )
        }
        imagePath?.let { path ->
            FmImageOverlay(
                path = path,
                bitmap = imageBitmap,
                loading = imageLoading,
                failed = imageFailed,
                onClose = { imagePath = null },
            )
        }
    }
}

// ------------------------------------------------------------------
// 窗格
// ------------------------------------------------------------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FmPane(
    index: Int,
    pane: PaneUiState,
    clipboard: FmClipboard?,
    bookmarks: List<String>,
    bottomInset: Dp,
    callbacks: FmPaneCallbacks,
) {
    val context = LocalContext.current
    val selectionMode = pane.selection.isNotEmpty()
    val displayed = remember(
        pane.entries,
        pane.showHidden,
        pane.sortMode,
        pane.sortAsc,
        pane.foldersFirst,
    ) {
        pane.entries.orEmpty()
            .filter { pane.showHidden || !it.name.startsWith(".") }
            .sortedWith { a, b ->
                FileManagerRepository.compareEntries(a, b, pane.sortMode, pane.sortAsc, pane.foldersFirst)
            }
    }
    val searchDisplayed = remember(pane.searchResults, pane.sortMode, pane.sortAsc, pane.foldersFirst) {
        pane.searchResults.sortedWith { a, b ->
            FileManagerRepository.compareEntries(a, b, pane.sortMode, pane.sortAsc, pane.foldersFirst)
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface)) {
        FmPaneTopBar(index, pane, clipboard, bookmarks, selectionMode, callbacks)
        if (pane.searchQuery != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            ) {
                Text(
                    stringResource(R.string.fm_search_results, searchDisplayed.size),
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                IconButton(onClick = callbacks.onClearSearch) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.fm_clear_search),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when {
                pane.searching -> FmPaneCenterHint { InfiniteProgressIndicator(size = 24.dp) }
                pane.entries == null && pane.searchQuery == null ->
                    FmPaneCenterHint { InfiniteProgressIndicator(size = 24.dp) }
                pane.error == "UNREADABLE" ->
                    FmPaneCenterHint { Text(stringResource(R.string.fm_root_required), fontSize = 13.sp, textAlign = TextAlign.Center) }
                pane.error != null ->
                    FmPaneCenterHint {
                        Text(
                            stringResource(R.string.fm_failed, pane.error ?: ""),
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center,
                        )
                    }
                pane.searchQuery != null && searchDisplayed.isEmpty() ->
                    FmPaneCenterHint { Text(stringResource(R.string.fm_empty_dir), fontSize = 13.sp) }
                pane.searchQuery != null -> {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(searchDisplayed, key = { it.path }) { entry ->
                            FmItemRow(
                                context = context,
                                entry = entry,
                                selected = entry.path in pane.selection,
                                selectionMode = selectionMode,
                                onOpen = { callbacks.onOpen(entry) },
                                onLongPress = { callbacks.onLongPress(entry) },
                                onToggle = { callbacks.onToggleSelect(entry) },
                            )
                        }
                    }
                }
                displayed.isEmpty() ->
                    FmPaneCenterHint { Text(stringResource(R.string.fm_empty_dir), fontSize = 13.sp) }
                else -> {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(displayed, key = { it.path }) { entry ->
                            FmItemRow(
                                context = context,
                                entry = entry,
                                selected = entry.path in pane.selection,
                                selectionMode = selectionMode,
                                onOpen = { callbacks.onOpen(entry) },
                                onLongPress = { callbacks.onLongPress(entry) },
                                onToggle = { callbacks.onToggleSelect(entry) },
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(bottomInset))
    }
}

@Composable
private fun FmPaneCenterHint(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) { content() }
    }
}

@Composable
private fun FmPaneTopBar(
    index: Int,
    pane: PaneUiState,
    clipboard: FmClipboard?,
    bookmarks: List<String>,
    selectionMode: Boolean,
    callbacks: FmPaneCallbacks,
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(44.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { if (selectionMode) callbacks.onClearSelection() else callbacks.onPaneBack() }) {
            Icon(
                imageVector = if (selectionMode) Icons.Rounded.Close else Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
        }
        Text(
            text = if (selectionMode) {
                stringResource(R.string.fm_select_count, pane.selection.size)
            } else {
                pane.path
            },
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .then(if (selectionMode) Modifier else Modifier.clickable(onClick = callbacks.onJump)),
        )
        if (selectionMode) {
            IconButton(onClick = callbacks.onSelectAll) {
                Icon(imageVector = Icons.Rounded.SelectAll, contentDescription = null, modifier = Modifier.size(20.dp))
            }
        } else {
            if (clipboard != null && clipboard.homePane != index) {
                IconButton(onClick = callbacks.onPaste) {
                    Icon(
                        imageVector = Icons.Rounded.ContentPaste,
                        contentDescription = stringResource(R.string.fm_paste),
                        modifier = Modifier.size(20.dp),
                        tint = MiuixTheme.colorScheme.primary,
                    )
                }
            }
            IconButton(onClick = callbacks.onSearch) {
                Icon(imageVector = Icons.Rounded.Search, contentDescription = stringResource(R.string.fm_search), modifier = Modifier.size(20.dp))
            }
        }
        FmPaneMenu(pane, bookmarks, selectionMode, callbacks)
    }
}

private data class FmMenuItem(
    val label: String,
    val checked: Boolean,
    val icon: ImageVector?,
    val action: () -> Unit,
)

@Composable
private fun FmPaneMenu(
    pane: PaneUiState,
    bookmarks: List<String>,
    selectionMode: Boolean,
    callbacks: FmPaneCallbacks,
) {
    Box {
        val anchor = rememberCouiMenuAnchor()
        var showMenu by remember { mutableStateOf(false) }
        IconButton(
            onClick = { anchor.open { showMenu = true } },
            modifier = Modifier.couiMenuAnchor(anchor),
        ) {
            Icon(imageVector = Icons.Rounded.MoreVert, contentDescription = null, modifier = Modifier.size(20.dp))
        }
        val menuItems: List<FmMenuItem> = buildList {
            if (!selectionMode) {
                add(FmMenuItem(stringResource(R.string.fm_refresh), false, Icons.Rounded.Refresh) { callbacks.onPaneBack() })
                add(FmMenuItem(stringResource(R.string.fm_new_folder), false, Icons.Rounded.CreateNewFolder) { callbacks.onCreate(true) })
                add(FmMenuItem(stringResource(R.string.fm_new_file), false, Icons.Rounded.NoteAdd) { callbacks.onCreate(false) })
                add(FmMenuItem(stringResource(R.string.fm_show_hidden), pane.showHidden, null) { callbacks.onToggleShowHidden() })
                add(FmMenuItem(stringResource(R.string.fm_sort), false, Icons.Rounded.Sort) { callbacks.onSort() })
                add(FmMenuItem(stringResource(R.string.fm_jump_path), false, null) { callbacks.onJump() })
                add(FmMenuItem(stringResource(R.string.fm_search), false, Icons.Rounded.Search) { callbacks.onSearch() })
                val bookmarked = FileManagerRepository.normalizePath(pane.path)?.let { it in bookmarks } == true
                add(
                    FmMenuItem(
                        if (bookmarked) stringResource(R.string.fm_bookmark_remove) else stringResource(R.string.fm_bookmark_add),
                        bookmarked,
                        if (bookmarked) Icons.Rounded.BookmarkRemove else Icons.Rounded.BookmarkAdd,
                    ) { callbacks.onToggleBookmark() },
                )
                bookmarks.forEach { path ->
                    add(FmMenuItem(path.substringAfterLast('/').ifEmpty { path }, false, null) { callbacks.onJumpBookmark(path) })
                }
            }
        }
        when (LocalUiStyle.current) {
            AppearanceUiStyle.COUI -> {
                val couiItems = menuItems.mapIndexed { idx, item ->
                    CouiMenuItem(
                        value = idx,
                        text = if (item.checked) "✓ " + item.label else item.label,
                    )
                }
                Menu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                    items = couiItems,
                    onItemClick = { item ->
                        showMenu = false
                        menuItems.getOrNull(item.value)?.action?.invoke()
                    },
                    anchor = anchor,
                )
            }
            AppearanceUiStyle.MIUIX -> {
                WindowListPopup(
                    show = showMenu,
                    alignment = PopupPositionProvider.Align.TopEnd,
                    onDismissRequest = { showMenu = false },
                ) {
                    val dismiss = LocalDismissState.current
                    ListPopupColumn {
                        menuItems.forEachIndexed { idx, item ->
                            DropdownImpl(
                                text = if (item.checked) "✓ " + item.label else item.label,
                                optionSize = menuItems.size,
                                isSelected = item.checked,
                                index = idx,
                                onSelectedIndexChange = {
                                    dismiss?.invoke()
                                    showMenu = false
                                    item.action()
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FmItemRow(
    context: Context,
    entry: FileManagerRepository.FsEntry,
    selected: Boolean,
    selectionMode: Boolean,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
    onToggle: () -> Unit,
) {
    val (icon, tint) = fmEntryVisual(entry)
    val secondary = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.55f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .combinedClickable(
                onClick = { if (selectionMode) onToggle() else onOpen() },
                onLongClick = onLongPress,
            )
            .background(if (selected) MiuixTheme.colorScheme.primary.copy(alpha = 0.14f) else Color.Transparent)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(34.dp), contentAlignment = Alignment.Center) {
            if (selectionMode) {
                Icon(
                    imageVector = if (selected) Icons.Rounded.CheckBox else Icons.Rounded.CheckBoxOutlineBlank,
                    contentDescription = null,
                    tint = MiuixTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
            } else {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(26.dp),
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(
            entry.name,
            fontSize = 15.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            if (!entry.isDir) {
                Text(fmFormatSize(context, entry.sizeBytes), fontSize = 11.sp, color = secondary)
            }
            Text(fmFormatDate(entry.mtimeEpochSeconds), fontSize = 11.sp, color = secondary)
        }
    }
}

// ------------------------------------------------------------------
// 底部多选操作栏
// ------------------------------------------------------------------

@Composable
private fun FmActionBar(
    onSelectAll: () -> Unit,
    onCopy: () -> Unit,
    onCut: () -> Unit,
    onDelete: () -> Unit,
    onRename: () -> Unit,
    onZip: () -> Unit,
    onProperties: () -> Unit,
    onPermissions: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .background(MiuixTheme.colorScheme.surface)
            .navigationBarsPadding(),
    ) {
        CouiIconDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FmActionItem(Icons.Rounded.SelectAll, stringResource(R.string.fm_select_all), onSelectAll)
            FmActionItem(Icons.Rounded.ContentCopy, stringResource(R.string.fm_copy), onCopy)
            FmActionItem(Icons.Rounded.ContentCut, stringResource(R.string.fm_cut), onCut)
            FmActionItem(Icons.Rounded.Delete, stringResource(R.string.fm_delete), onDelete, tint = Color(0xFFE53935))
            FmActionItem(Icons.Rounded.DriveFileRenameOutline, stringResource(R.string.fm_rename), onRename)
            FmActionItem(Icons.Rounded.FolderZip, stringResource(R.string.fm_compress), onZip)
            FmActionItem(Icons.Rounded.Info, stringResource(R.string.fm_properties), onProperties)
            FmActionItem(Icons.Rounded.Lock, stringResource(R.string.fm_permissions), onPermissions)
            FmActionItem(Icons.Rounded.Close, stringResource(R.string.fm_cancel), onCancel)
        }
    }
}

@Composable
private fun FmActionItem(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    tint: Color = MiuixTheme.colorScheme.onSurface,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 2.dp),
    ) {
        Icon(imageVector = icon, contentDescription = label, tint = tint, modifier = Modifier.size(22.dp))
        Text(label, fontSize = 10.sp, color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.7f))
    }
}

// ------------------------------------------------------------------
// 分隔条（可拖动调窗格比例，MT 同款）
// ------------------------------------------------------------------

@Composable
private fun FmDivider(horizontal: Boolean, onDrag: (Float) -> Unit) {
    Box(
        modifier = Modifier
            .then(
                if (horizontal) {
                    Modifier.fillMaxWidth().height(8.dp)
                } else {
                    Modifier.fillMaxHeight().width(8.dp)
                },
            )
            .background(MiuixTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            .pointerInput(horizontal) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    if (horizontal) onDrag(dragAmount.y) else onDrag(dragAmount.x)
                }
            },
    )
}
