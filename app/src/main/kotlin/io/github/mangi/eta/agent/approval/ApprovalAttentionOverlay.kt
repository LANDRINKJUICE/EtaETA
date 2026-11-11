package io.github.mangi.eta.agent.approval

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.itos.coui.effect.LocalCouiContourGlowEnabled
import io.github.mangi.eta.agent.accessibility.AgentAccessibilityService
import io.github.mangi.eta.data.model.AppearanceSettings
import io.github.mangi.eta.data.repository.AppearanceSettingsRepository
import io.github.mangi.eta.ui.app.AgentAppTheme
import io.github.mangi.eta.ui.components.AgentApprovalRequestCard
import top.yukonga.miuix.kmp.squircle.LocalSquircleEnabled

/**
 * 后台审批卡的悬浮层载体。
 *
 * 关键约束（用户明确要求）：不自造样式，**复用聊天流同一个 [AgentApprovalRequestCard] Composable**，
 * 并包在 [AgentAppTheme] 里，因此 Eta / COUI / MIUIX 三套视觉与聊天流里的卡片完全一致。
 *
 * 决策出口只有一个：[AgentApprovalCoordinator.shared.resolve]（与聊天流卡片、通知按钮同一个函数）。
 *
 * 审查后加固的几点：
 * - 生命周期：每次 [show] 新建**私有** LifecycleOwner / SavedStateRegistryOwner 并挂到该 ComposeView，
 *   [detachLocked] 时派发 ON_DESTROY 后丢弃。绝不复用进程级单例 owner——否则在
 *   DisposeOnViewTreeLifecycleDestroyed 策略下 composition 永不销毁，每次弹卡泄漏一整套 View +
 *   Recomposer + settingsFlow 收集器（对照 AgentRuntimeService/EtaAssistantOverlayService 都在
 *   onDestroy 派发 ON_DESTROY）。
 * - 不重复建窗：同一 requestId 再次 show 只更新 MutableState 内容，不拆窗重建，
 *   避免丢掉用户正在输入的内容、IME 目标与倒计时状态。
 * - 异常边界：建 View / setContent / addView 全程 runCatching，失败返回 false 让 Presenter 降级到通知；
 *   AgentAppTheme 会碰 platformDynamicColors/COUI 主题，个别 ROM 抛错不能把主线程带崩。
 * - 焦点：默认 FLAG_NOT_FOCUSABLE，不抢用户当前 App 的输入焦点、不吞返回手势；
 *   因此悬浮层定位为「快速点选决策」，需要填自由文本/纠正说明时由通知主体或任务中心的聊天流卡片承接。
 * - 布局：y 用状态栏∪挖孔 inset（与既有浮窗一致），内容加 verticalScroll + 最大高度，长命令也能滚到底部按钮。
 *
 * @return 是否成功 addView。false 表示建窗失败，调用方必须据此降级到通知兜底
 *         （对应 [AgentApprovalAttentionPolicy] 规则 3：addView 实际结果再降级一次）。
 */
internal object ApprovalAttentionOverlay {

    private class Attached(
        val windowManager: WindowManager,
        val view: ComposeView,
        val lifecycle: LifecycleRegistry,
        val requestId: String,
        val requestState: MutableState<AgentApprovalRequest>,
    )

    @Volatile
    private var attached: Attached? = null

    /**
     * 显示（或原地更新）悬浮审批卡。幂等：
     * - 同一 [request.id] 且窗口仍在 → 只更新内容 state，不重建；
     * - 不同 id 或窗口不在 → 先销毁旧窗口再建新的。
     */
    fun show(
        context: Context,
        request: AgentApprovalRequest,
        onDecided: () -> Unit,
    ): Boolean = runCatching {
        synchronized(this) {
            val existing = attached
            if (existing != null && existing.requestId == request.id && existing.view.isAttachedToWindow) {
                // 同一张卡：只更新内容，避免拆窗丢输入。
                existing.requestState.value = request
                return@synchronized true
            }
            detachLocked()

            val appContext = context.applicationContext
            val service = AgentAccessibilityService.current()
            val overlayContext: Context = service ?: appContext
            val type = if (service != null) {
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            }
            val wm = overlayContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                ?: return@synchronized false

            // 每次新建私有 owner；detach 时派发 ON_DESTROY 销毁 composition。
            val owner = OverlayViewOwner()
            owner.onCreate()

            val requestState = mutableStateOf(request)
            val composeView = ComposeView(overlayContext).apply {
                setViewTreeLifecycleOwner(owner)
                setViewTreeSavedStateRegistryOwner(owner)
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                setContent {
                    ApprovalCardContent(
                        requestState = requestState,
                        onDecide = { optionId, freeText ->
                            val resolved = AgentApprovalCoordinator.shared.resolve(request.id, optionId, freeText)
                            // resolve 内部把 pending 清空，Presenter 的监听会收起载体；这里只做一次
                            // 兜底刷新，避免极端时序下窗口残留。决策失败（卡片已失效）也不重试。
                            onDecided()
                        },
                    )
                }
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = statusBarHeightPx(overlayContext, wm)
                title = "EtaApprovalAttention"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
                softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
            }

            val ok = runCatching { wm.addView(composeView, params) }.isSuccess
            if (ok) {
                attached = Attached(wm, composeView, owner.lifecycleRegistry, request.id, requestState)
            } else {
                runCatching { wm.removeViewImmediate(composeView) }
                owner.onDestroy()
            }
            ok
        }
    }.getOrElse { false }

    /** 收起悬浮层（pending 归零或切回前台时）。幂等。 */
    fun dismiss() {
        synchronized(this) { detachLocked() }
    }

    fun isShowing(): Boolean = synchronized(this) { attached != null }

    private fun detachLocked() {
        val current = attached ?: return
        attached = null
        runCatching { current.windowManager.removeViewImmediate(current.view) }
        // 派发 ON_DESTROY 让 DisposeOnViewTreeLifecycleDestroyed 真正销毁 composition + 收集器。
        runCatching { current.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY) }
    }

    private fun statusBarHeightPx(context: Context, wm: WindowManager): Int {
        val density = context.resources.displayMetrics.density
        val fallback = (28 * density).toInt()
        val inset = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                wm.currentWindowMetrics.windowInsets
                    .getInsetsIgnoringVisibility(
                        android.view.WindowInsets.Type.statusBars() or
                            android.view.WindowInsets.Type.displayCutout(),
                    ).top
            }.getOrDefault(0)
        } else {
            0
        }
        return inset.takeIf { it > 0 } ?: fallback
    }

    @Composable
    private fun ApprovalCardContent(
        requestState: MutableState<AgentApprovalRequest>,
        onDecide: (String, String?) -> Unit,
    ) {
        val appearance by AppearanceSettingsRepository.settingsFlow()
            .collectAsState(initial = AppearanceSettings())
        AgentAppTheme(
            appearance = appearance,
            applyInterfaceScale = false,
        ) {
            // 与 AgentRuntimeService / EtaAssistantOverlayService 的浮窗一致：部分 ROM 会给
            // TYPE_ACCESSIBILITY_OVERLAY 分配软件 Canvas，RuntimeShader 无法绘制，统一走圆角回退。
            CompositionLocalProvider(
                LocalSquircleEnabled provides false,
                LocalCouiContourGlowEnabled provides false,
            ) {
                val request by requestState
                androidx.compose.foundation.layout.Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 520.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    AgentApprovalRequestCard(
                        request = request,
                        onDecide = onDecide,
                    )
                }
            }
        }
    }

    /** 每个悬浮窗口私有的 Lifecycle / SavedState owner，随窗口创建与销毁。 */
    private class OverlayViewOwner : LifecycleOwner, SavedStateRegistryOwner {
        val lifecycleRegistry = LifecycleRegistry(this)
        private val savedStateRegistryController = SavedStateRegistryController.create(this)

        override val lifecycle: Lifecycle get() = lifecycleRegistry
        override val savedStateRegistry: SavedStateRegistry
            get() = savedStateRegistryController.savedStateRegistry

        fun onCreate() {
            savedStateRegistryController.performRestore(null)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }

        fun onDestroy() {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        }
    }
}
