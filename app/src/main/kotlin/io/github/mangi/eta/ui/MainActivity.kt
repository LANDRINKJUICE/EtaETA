package io.github.mangi.eta.ui

import android.app.UiModeManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import io.github.mangi.eta.agent.runtime.AgentRuntimeService
import io.github.mangi.eta.agent.voice.EtaAssistantOverlayService
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.data.model.AppearanceThemeMode
import io.github.mangi.eta.data.repository.AppearanceSettingsRepository
import io.github.mangi.eta.ui.app.AgentAppRoot
import io.github.mangi.eta.ui.navigation.TaskCenterDeepLink
import io.github.mangi.eta.ui.app.AgentAppTheme
import io.github.mangi.eta.ui.app.PredictiveBackController
import io.github.mangi.eta.ui.app.installStartupSplash
import java.util.Collections
import java.util.IdentityHashMap
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var launchRequests by mutableStateOf(MainActivityLaunchRequests())
    private var appliedPredictiveBackEnabled = true
    /** 后台唤醒轮启动计数：每次 +1 触发 AgentAppRoot 实时对账（见 wakeupRefreshSignal）。 */
    private var wakeupRefreshSignal by mutableIntStateOf(0)
    private var wakeupReceiverRegistered = false
    private val wakeupRunReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == io.github.mangi.eta.agent.terminal.ACTION_WAKEUP_RUN_STARTED) {
                // App 在前台时立刻认领这个 active run，让它在会话列表里可见、可对话。
                wakeupRefreshSignal += 1
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AndroidAgentLogger.info("Agent UI created: task=$taskId restored=${savedInstanceState != null}")
        var contentReady = false
        installStartupSplash { contentReady }
        enableEdgeToEdge()
        launchRequests = MainActivityLaunchRequests.restore(savedInstanceState, intent, applicationContext)
        lifecycleScope.launch {
            val initialAppearance = AppearanceSettingsRepository.settings()
            appliedPredictiveBackEnabled = initialAppearance.predictiveBackEnabled
            setContent {
                val appearance by AppearanceSettingsRepository.settingsFlow()
                    .collectAsState(initial = initialAppearance)

                LaunchedEffect(appearance.themeMode) {
                    updateApplicationNightMode(appearance.themeMode)
                }

                LaunchedEffect(appearance.predictiveBackEnabled) {
                    val enabled = appearance.predictiveBackEnabled
                    if (enabled != appliedPredictiveBackEnabled &&
                        PredictiveBackController.apply(applicationInfo, enabled)
                    ) {
                        appliedPredictiveBackEnabled = enabled
                        recreateWithoutTransition()
                    }
                }

                AgentAppTheme(
                    appearance = appearance,
                    applyInterfaceScale = true,
                    onResolvedDarkModeChange = ::updateSystemBars,
                ) {
                    AgentAppRoot(
                        assistantConversationKey = launchRequests.assistantConversationKey,
                        requestedConversationId = launchRequests.conversationId,
                        onRequestedConversationOpened = {
                            launchRequests = launchRequests.copy(conversationId = null)
                            intent?.action = null
                        },
                        requestedArchivedConversation = launchRequests.archivedConversation,
                        onArchivedConversationOpened = {
                            launchRequests = launchRequests.copy(archivedConversation = null)
                            intent?.action = null
                        },
                        conversationReply = launchRequests.conversationReply,
                        onConversationReplyConsumed = {
                            launchRequests = launchRequests.copy(conversationReply = null)
                            intent?.action = null
                        },
                        sharedContent = launchRequests.sharedContent,
                        onSharedContentAccepted = {
                            launchRequests = launchRequests.copy(sharedContent = null)
                            intent?.action = null
                        },
                        openSpeechSettings = launchRequests.speechSettings,
                        openTerminal = launchRequests.terminal,
                        openTaskCenterTab = launchRequests.taskCenterTab,
                        onTaskCenterOpened = {
                            launchRequests = launchRequests.copy(taskCenterTab = null)
                            intent?.action = null
                        },
                        onTerminalOpened = {
                            launchRequests = launchRequests.copy(terminal = false)
                            intent?.action = null
                        },
                        onSpeechSettingsOpened = {
                            launchRequests = launchRequests.copy(speechSettings = false)
                            intent?.action = null
                        },
                        onAssistantConversationOpened = { opened ->
                            launchRequests = launchRequests.copy(assistantConversationKey = null)
                            intent?.action = null
                            if (opened) {
                                EtaAssistantOverlayService.notifyHandoffReady(this@MainActivity)
                            }
                        },
                        wakeupRefreshSignal = wakeupRefreshSignal,
                    )
                }
            }
            contentReady = true
        }
    }

    override fun onStart() {
        super.onStart()
        startedInstances.add(this)
        isInForeground = true
        AgentRuntimeService.onEtaUiVisibilityChanged()
        if (!wakeupReceiverRegistered) {
            val filter = IntentFilter(
                io.github.mangi.eta.agent.terminal.ACTION_WAKEUP_RUN_STARTED,
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(wakeupRunReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(wakeupRunReceiver, filter)
            }
            wakeupReceiverRegistered = true
        }
    }

    override fun onStop() {
        startedInstances.remove(this)
        isInForeground = startedInstances.isNotEmpty() || isChangingConfigurations
        AgentRuntimeService.onEtaUiVisibilityChanged()
        if (wakeupReceiverRegistered) {
            runCatching { unregisterReceiver(wakeupRunReceiver) }
            wakeupReceiverRegistered = false
        }
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val fromLauncher = intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_LAUNCHER)
        AndroidAgentLogger.info("Agent UI new intent: task=$taskId launcher=$fromLauncher")
        setIntent(intent)
        launchRequests = launchRequests.accept(intent, applicationContext)
    }

    override fun onDestroy() {
        startedInstances.remove(this)
        isInForeground = startedInstances.isNotEmpty() || isChangingConfigurations
        AndroidAgentLogger.info(
            "Agent UI destroyed: task=$taskId changing_configuration=$isChangingConfigurations finishing=$isFinishing",
        )
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        launchRequests.saveTo(outState)
        super.onSaveInstanceState(outState)
    }

    private fun updateApplicationNightMode(themeMode: AppearanceThemeMode) {
        val mode = when (themeMode) {
            AppearanceThemeMode.LIGHT -> UiModeManager.MODE_NIGHT_NO
            AppearanceThemeMode.DARK -> UiModeManager.MODE_NIGHT_YES
            // 应用级 AUTO 清除夜间模式覆盖，恢复跟随系统。
            AppearanceThemeMode.SYSTEM -> UiModeManager.MODE_NIGHT_AUTO
        }
        getSystemService(UiModeManager::class.java).setApplicationNightMode(mode)
    }

    private fun updateSystemBars(isDark: Boolean) {
        val style = if (isDark) {
            SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        } else {
            SystemBarStyle.light(
                scrim = android.graphics.Color.TRANSPARENT,
                darkScrim = android.graphics.Color.TRANSPARENT,
            )
        }
        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !isDark
            isAppearanceLightNavigationBars = !isDark
        }
        window.decorView.post {
            WindowInsetsControllerCompat(window, window.decorView).apply {
                isAppearanceLightStatusBars = !isDark
                isAppearanceLightNavigationBars = !isDark
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun recreateWithoutTransition() {
        overridePendingTransition(0, 0)
        recreate()
        overridePendingTransition(0, 0)
    }

    companion object {
        private val startedInstances = Collections.newSetFromMap(IdentityHashMap<MainActivity, Boolean>())

        @Volatile
        internal var isInForeground: Boolean = false
            private set

        const val ACTION_OPEN_TERMINAL = "io.github.mangi.eta.action.OPEN_TERMINAL"
        /** 主动沟通 / 待确认通知点击直达任务中心指定分区；字符串唯一来源见 [TaskCenterDeepLink]。 */
        const val ACTION_OPEN_TASK_CENTER = TaskCenterDeepLink.ACTION_OPEN_TASK_CENTER
        const val EXTRA_TASK_CENTER_TAB = TaskCenterDeepLink.EXTRA_TASK_CENTER_TAB
        /** 运行浮层的结果卡片回到本体：App 发起的 run 带会话 ID，直接打开该会话。 */
        const val ACTION_OPEN_CONVERSATION_ID = "io.github.mangi.eta.action.OPEN_CONVERSATION_ID"
        const val EXTRA_CONVERSATION_ID = "io.github.mangi.eta.extra.CONVERSATION_ID"
        /** notify_user 通知栏直接回复：RemoteInput 的结果写进 [REPLY_RESULT_KEY] 对应的 Intent extra。 */
        const val ACTION_REPLY_TO_CONVERSATION = "io.github.mangi.eta.action.REPLY_TO_CONVERSATION"
        const val REPLY_RESULT_KEY = "eta_reply_text"
        /** 回复 Intent 的本装机令牌；校验不过一律不接受（防外部 App 伪造注入）。见 ReplyToken。 */
        const val EXTRA_REPLY_TOKEN = "io.github.mangi.eta.extra.REPLY_TOKEN"
        /** 一次性 nonce：消费过即丢弃，防进程被杀后用旧 Intent 重建时重复发同一条回复。见 ReplyToken。 */
        const val EXTRA_REPLY_NONCE = "io.github.mangi.eta.extra.REPLY_NONCE"
        /** 发出这条通知的 notificationId，回复成功后用于取消那条通知。 */
        const val EXTRA_NOTIFICATION_ID = "io.github.mangi.eta.extra.NOTIFICATION_ID"
        /** 系统助手等外部入口发起的 run 按来源与会话键归档，回到本体时打开对应的归档会话。 */
        const val ACTION_OPEN_ARCHIVED_CONVERSATION = "io.github.mangi.eta.action.OPEN_ARCHIVED_CONVERSATION"
        const val EXTRA_HANDOFF_SOURCE = "io.github.mangi.eta.extra.HANDOFF_SOURCE"
        const val EXTRA_CONVERSATION_KEY = "io.github.mangi.eta.extra.CONVERSATION_KEY"
    }
}
