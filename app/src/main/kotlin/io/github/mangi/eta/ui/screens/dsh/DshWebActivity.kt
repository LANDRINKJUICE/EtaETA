package io.github.mangi.eta.ui.screens.dsh

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import io.github.mangi.eta.R
import io.github.mangi.eta.data.repository.AppearanceSettingsRepository
import io.github.mangi.eta.ui.app.AgentAppTheme
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * DSH 网页的内嵌宿主。
 *
 * 引擎的凭据是 `HttpOnly + SameSite=Strict` 的 Cookie，只有「同源页面自己发起请求」才会带上，
 * 所以这里必须由 WebView 直接加载带 token 的根路径，让 303 的 Set-Cookie 落进 WebView 自己的
 * CookieManager；任何「外部浏览器跳转」或「本地壳页面套 iframe」都会丢掉 Cookie 或 Origin。
 */
class DshWebActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(EXTRA_URL)?.takeIf { it.isNotBlank() }
        if (url == null) {
            finish()
            return
        }
        enableEdgeToEdge()
        lifecycleScope.launch {
            val initialAppearance = AppearanceSettingsRepository.settings()
            setContent {
                val appearance by AppearanceSettingsRepository.settingsFlow().collectAsState(initialAppearance)
                AgentAppTheme(
                    appearance = appearance,
                    applyInterfaceScale = true,
                    provideGlassHost = true,
                    onResolvedDarkModeChange = { isDark ->
                        val style = if (isDark) {
                            SystemBarStyle.dark(Color.TRANSPARENT)
                        } else {
                            SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                        }
                        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                    },
                ) {
                    DshWebScreen(initialUrl = url, onBack = ::finish)
                }
            }
        }
    }

    companion object {
        private const val EXTRA_URL = "io.github.mangi.eta.extra.DSH_WEB_URL"

        fun createIntent(context: Context, url: String): Intent =
            Intent(context, DshWebActivity::class.java)
                .putExtra(EXTRA_URL, url)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

@SuppressLint("SetJavaScriptEnabled")
@androidx.compose.runtime.Composable
private fun DshWebScreen(initialUrl: String, onBack: () -> Unit) {
    var failed by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { context ->
                FrameLayout(context).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    )
                    setBackgroundColor(Color.TRANSPARENT)
                    val webView = WebView(context)
                    webView.layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    )
                    webView.settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        // DSH 页面与引擎同源（127.0.0.1:3080），不加载本地文件。
                        allowFileAccess = false
                        allowContentAccess = false
                        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        mediaPlaybackRequiresUserGesture = false
                        setSupportZoom(false)
                    }
                    // 引擎 Cookie 是 SameSite=Strict 的同站凭据，WebView 必须接受。
                    CookieManager.getInstance().apply {
                        setAcceptCookie(true)
                        setAcceptThirdPartyCookies(webView, true)
                    }
                    webView.webViewClient = object : WebViewClient() {
                        override fun onReceivedError(
                            view: WebView,
                            request: WebResourceRequest,
                            error: WebResourceError,
                        ) {
                            // 只对主框架失败提示，子资源失败不影响页面可用性。
                            if (request.isForMainFrame) failed = true
                        }

                        override fun onPageFinished(view: WebView, url: String) {
                            // Cookie 可能延迟落盘，显式刷新一次，避免进程重启后丢登录态。
                            CookieManager.getInstance().flush()
                        }
                    }
                    webView.loadUrl(initialUrl)
                    addView(webView)
                }
            },
            onRelease = { container ->
                (container.getChildAt(0) as? WebView)?.let { webView ->
                    container.removeView(webView)
                    webView.stopLoading()
                    webView.destroy()
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        if (failed) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.dsh_web_load_failed),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                TextButton(
                    text = stringResource(R.string.action_close),
                    onClick = onBack,
                )
            }
        }
    }
}
