package io.github.mangi.eta.ui.screens.browser

import android.content.Intent
import android.net.Uri
import android.webkit.WebChromeClient
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.core.net.toUri
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.browser.AgentBrowserSession
import io.github.mangi.eta.agent.browser.BrowserSessionRegistry
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.currentGlassOverlayBackdrop
import io.github.mangi.eta.ui.components.coui.CouiConfirmDialog
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.glass.GlassDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 挂载会话原有的 WebView，页面自身持有地址栏及系统栏 Insets。 */
@Composable
internal fun AgentBrowserScreen(
    session: AgentBrowserSession,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** 非空时在页面挂载后自动导航一次（如从首页打开 DSH 网页）。 */
    initialUrl: String? = null,
    /**
     * 「用外部应用打开」应交给浏览器的地址；为空时用当前页地址。
     *
     * DSH 页面必须传中转地址：它的会话 cookie 是 `SameSite=Strict`，
     * 外部应用跳转过来时浏览器不会带上，直接开 3080 会停在 401。
     * 见 [io.github.mangi.eta.agent.dsh.DshExternalLoginServer]。
     */
    externalOpenUrl: String? = null,
) {
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val noExternalAppMessage = stringResource(R.string.browser_no_external_app)
    val linkCopiedMessage = stringResource(R.string.browser_link_copied)
    val snapshot by session.snapshots.collectAsState()
    // 交给外部浏览器的地址：首页打开 DSH 时带 token（303 之后地址栏里已经没有它），
    // 只有页面仍停留在该站点时才使用，跳转走后回落到当前地址，避免拿旧 token 误导。
    val externalUrl = remember(initialUrl, snapshot.url) {
        externalBrowserUrl(initialUrl, snapshot.url)
    }
    val onCopyLink = {
        if (externalUrl.isNotBlank()) {
            clipboard.setText(AnnotatedString(externalUrl))
            Toast.makeText(context, linkCopiedMessage, Toast.LENGTH_SHORT).show()
        }
    }
    // 网页里的 <input type="file">（DSH 的「文件/图片」按钮）必须由宿主接管，
    // 否则 WebView 默认实现返回 false，点下去毫无反应。这里接到系统文件选择器。
    val pendingFileChooser = remember(session) { AtomicReference<((Array<Uri>?) -> Unit)?>(null) }
    val fileChooserLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        pendingFileChooser.getAndSet(null)?.invoke(
            WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data),
        )
    }
    DisposableEffect(session) {
        session.setFileChooser { intent, onResult ->
            pendingFileChooser.set(onResult)
            runCatching { fileChooserLauncher.launch(intent) }.onFailure {
                pendingFileChooser.set(null)
                onResult(null)
            }
            true
        }
        onDispose {
            session.setFileChooser(null)
            // 页面离开时把未决选择作废，否则回来还停在"等待选择"。
            pendingFileChooser.getAndSet(null)?.invoke(null)
            session.cancelPendingFileChooser()
        }
    }
    var address by remember(session) { mutableStateOf(TextFieldValue("")) }
    var addressFocused by remember(session) { mutableStateOf(false) }
    var actionPending by remember(session) { mutableStateOf(false) }
    var showClearDataDialog by remember { mutableStateOf(false) }

    // 编辑期间不覆盖用户输入；非编辑态仅显示域名，进入编辑时还原完整地址。
    LaunchedEffect(addressFocused) {
        if (addressFocused) {
            address = TextFieldValue(snapshot.displayUrl, TextRange(0, snapshot.displayUrl.length))
        }
    }
    LaunchedEffect(snapshot.displayUrl, snapshot.host, addressFocused) {
        if (!addressFocused) address = TextFieldValue(snapshot.host.ifBlank { snapshot.displayUrl })
    }
    LaunchedEffect(snapshot.error) {
        snapshot.error?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    }

    fun launchBrowserAction(action: () -> Unit) {
        if (actionPending) return
        actionPending = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) { action() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                AndroidAgentLogger.warn("Browser user action failed: type=${failure.safeLogType()}")
                Toast.makeText(context, R.string.browser_user_action_failed, Toast.LENGTH_SHORT).show()
            } finally {
                actionPending = false
            }
        }
    }

    fun navigate() {
        val input = address.text.trim()
        val target = if (input.isNotBlank() && input == snapshot.displayUrl) {
            snapshot.url
        } else {
            resolveBrowserAddress(input) ?: return
        }
        if (target.isBlank() || actionPending) return
        focusManager.clearFocus()
        keyboard?.hide()
        launchBrowserAction { session.navigateFromUser(context.applicationContext, target) }
    }

    // 从首页打开 DSH 等场景：页面挂载后自动导航一次。
    // 导航是阻塞调用（最长 25s），必须放到 IO 线程，否则会卡住首帧。
    LaunchedEffect(session, initialUrl) {
        val target = initialUrl ?: return@LaunchedEffect
        withContext(Dispatchers.IO) {
            runCatching { session.navigateFromUser(context.applicationContext, target) }
        }
    }

    val pageContent: @Composable (Modifier) -> Unit = { bodyModifier ->
        Column(
            modifier = bodyModifier
                .fillMaxSize()
                .background(MiuixTheme.colorScheme.surface)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .imePadding()
                .navigationBarsPadding(),
        ) {
            BrowserAddressBar(
                snapshot = snapshot,
                address = address,
                onAddressChange = { address = it },
                focused = addressFocused,
                onFocusChange = { addressFocused = it },
                actionPending = actionPending,
                onExit = onBack,
                onGo = ::navigate,
                onBack = { launchBrowserAction { session.goBackFromUser() } },
                onForward = { launchBrowserAction { session.goForwardFromUser() } },
                onRefreshOrStop = {
                    if (snapshot.isLoading) {
                        session.stopFromUser()
                    } else {
                        launchBrowserAction { session.reloadFromUser() }
                    }
                },
                onOpenExternal = {
                    // DSH 页面优先走中转地址：它用 ETA 自己发的 Set-Cookie 种下会话
                    // （不带 SameSite=Strict），浏览器随后跳 3080 时就能带上。
                    // 其余网站行为完全不变。
                    val target = externalOpenUrl?.takeIf { it.isNotBlank() } ?: externalUrl
                    if (target.startsWith("http://") || target.startsWith("https://")) {
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, target.toUri()))
                        } catch (_: android.content.ActivityNotFoundException) {
                            Toast.makeText(context, noExternalAppMessage, Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                onCopyLink = onCopyLink,
                onClosePage = {
                    focusManager.clearFocus()
                    keyboard?.hide()
                    launchBrowserAction { session.resetFromUser() }
                },
                onClearData = { showClearDataDialog = true },
            )
            BrowserWebViewHost(session = session, modifier = Modifier.weight(1f))
        }
    }

    val onConfirmClear: () -> Unit = {
        showClearDataDialog = false
        launchBrowserAction { BrowserSessionRegistry.clearSharedBrowsingData(context.applicationContext) }
    }

    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> {
            pageContent(modifier)
            if (showClearDataDialog) {
                CouiConfirmDialog(
                    visible = true,
                    title = stringResource(R.string.browser_clear_shared_data),
                    message = stringResource(R.string.browser_clear_shared_data_message),
                    confirmText = stringResource(R.string.browser_clear_shared_data),
                    confirmEnabled = !actionPending,
                    destructive = true,
                    onConfirm = onConfirmClear,
                    onDismissRequest = { showClearDataDialog = false },
                )
            }
        }
        AppearanceUiStyle.MIUIX -> {
            pageContent(modifier)
            GlassDialog(
                show = showClearDataDialog,
                backdrop = currentGlassOverlayBackdrop(),
                title = stringResource(R.string.browser_clear_shared_data),
                summary = stringResource(R.string.browser_clear_shared_data_message),
                onDismissRequest = { showClearDataDialog = false },
            ) {
                MiuixDialogActions(
                    confirmText = stringResource(R.string.browser_clear_shared_data),
                    confirmEnabled = !actionPending,
                    destructive = true,
                    onCancel = { showClearDataDialog = false },
                    onConfirm = onConfirmClear,
                )
            }
        }
    }
}

/** 入口地址携带的认证参数；只有这类地址才需要原样交给外部浏览器。 */
private val TOKEN_QUERY = Regex("[?&]token=")

/**
 * 交给外部浏览器的地址：入口地址带 token、且页面仍停在同一站点时原样返回，
 * 否则返回页面当前地址（303 之后 token 已不在地址里，直接复制过去会被 401 拒绝）。
 */
internal fun externalBrowserUrl(initialUrl: String?, currentUrl: String): String {
    val tokenized = initialUrl?.takeIf { TOKEN_QUERY.containsMatchIn(it) }
        ?: return currentUrl
    return if (sameOrigin(tokenized, currentUrl)) tokenized else currentUrl
}

/** 两个地址是否同协议、同主机端口；空地址视为不同源。 */
private fun sameOrigin(left: String, right: String): Boolean {
    if (right.isBlank()) return false
    val leftUri = Uri.parse(left)
    val rightUri = Uri.parse(right)
    return leftUri.scheme == rightUri.scheme && leftUri.authority == rightUri.authority
}
