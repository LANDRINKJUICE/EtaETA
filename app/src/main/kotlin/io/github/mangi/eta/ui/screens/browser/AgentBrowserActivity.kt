package io.github.mangi.eta.ui.screens.browser

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import io.github.mangi.eta.agent.browser.BrowserSessionRegistry
import io.github.mangi.eta.data.repository.AppearanceSettingsRepository
import io.github.mangi.eta.ui.app.AgentAppTheme
import kotlinx.coroutines.launch

/** WebView 保持在固定窗口内，进出动画由系统合成窗口，避免整页绘制裁剪介入网页渲染。 */
class AgentBrowserActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sessionKey = intent.getStringExtra(EXTRA_SESSION_KEY)?.takeIf { it.isNotBlank() }
        if (sessionKey == null) {
            finish()
            return
        }
        val initialUrl = intent.getStringExtra(EXTRA_INITIAL_URL)?.takeIf { it.isNotBlank() }
        val externalOpenUrl = intent.getStringExtra(EXTRA_EXTERNAL_OPEN_URL)?.takeIf { it.isNotBlank() }
        enableEdgeToEdge()
        val session = BrowserSessionRegistry.get(sessionKey)
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
                            SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                        } else {
                            SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                        }
                        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                    },
                ) {
                    AgentBrowserScreen(
                        session = session,
                        initialUrl = initialUrl,
                        onBack = ::finish,
                        externalOpenUrl = externalOpenUrl,
                    )
                }
            }
        }
    }

    companion object {
        private const val EXTRA_SESSION_KEY = "io.github.mangi.eta.extra.BROWSER_SESSION_KEY"
        private const val EXTRA_INITIAL_URL = "io.github.mangi.eta.extra.BROWSER_INITIAL_URL"
        private const val EXTRA_EXTERNAL_OPEN_URL = "io.github.mangi.eta.extra.BROWSER_EXTERNAL_OPEN_URL"

        /**
         * @param initialUrl 非空时，页面挂载后自动导航到该地址。
         * @param externalOpenUrl 「用外部应用打开」应交给浏览器的地址；DSH 页面
         *   需传中转地址，否则外部浏览器会因 `SameSite=Strict` 停在 401。
         */
        fun createIntent(
            context: Context,
            sessionKey: String,
            initialUrl: String? = null,
            externalOpenUrl: String? = null,
        ): Intent {
            require(sessionKey.isNotBlank())
            return Intent(context, AgentBrowserActivity::class.java)
                .putExtra(EXTRA_SESSION_KEY, sessionKey)
                .putExtra(EXTRA_INITIAL_URL, initialUrl)
                .putExtra(EXTRA_EXTERNAL_OPEN_URL, externalOpenUrl)
        }
    }
}
