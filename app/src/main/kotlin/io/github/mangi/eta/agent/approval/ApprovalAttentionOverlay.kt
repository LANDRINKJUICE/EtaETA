package io.github.mangi.eta.agent.approval

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
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
 *   仅当用户点开自由输入 composer 时，临时去掉该 flag 并改用 SOFT_INPUT_ADJUST_PAN 让
 *   `BasicTextField` 拿到焦点、IME 弹出（见 `setFocusable`）；composer 收起 / 决策 / 载体消失
 *   立刻还原。纯逻辑判定见 [ApprovalOverlayFocusPolicy]，窗口切换仿
 *   `AgentRuntimeService` 的 capsule 输入模式。
 * - 布局：y 用状态栏∪挖孔 inset（与既有浮窗一致），内容加 verticalScroll + 最大高度，长命令也能滚到底部按钮。
 *
 * @return 是否成功 addView。false 表示建窗失败，调用方必须据此降级到通知兜底
 *         （对应 [AgentApprovalAttentionPolicy] 规则 3：addView 实际结果再降级一次）。
 */
internal object ApprovalAttentionOverlay {

    /** 复用主线程 Handler（与 ApprovalAttentionPresenter 同口径），避免每次切焦点都 new。主线程访问。 */
    private val mainHandler = Handler(Looper.getMainLooper())

    private class Attached(
        val windowManager: WindowManager,
        val view: ComposeView,
        val params: WindowManager.LayoutParams,
        val lifecycle: LifecycleRegistry,
        val requestId: String,
        val requestState: MutableState<AgentApprovalRequest>,
    ) {
        /** 加窗时的初始 flags 基准（含 FLAG_NOT_FOCUSABLE）；每次切焦点从它派生，避免失败后基准被改脏。 */
        val baseFlags: Int = params.flags

        /** 当前窗口是否处于「可聚焦」态（composer 可见、IME 可用）。主线程访问。 */
        var focusable: Boolean = ApprovalOverlayFocusPolicy.INITIAL_FOCUSABLE

        /** 窗口是否曾进入过可聚焦态；用于 detach 时判断是否需要主动收键盘 / 清焦点。主线程访问。 */
        var everFocusable: Boolean = false
    }

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
                        // 浮窗专属：composer 展开时临时去掉 FLAG_NOT_FOCUSABLE 让 BasicTextField
                        // 拿到焦点、IME 能弹出；收起/消失时还原，不吞下层 App 的返回手势。
                        overlayComposerHost = { active -> setFocusable(active) },
                        onDecide = { optionId, freeText ->
                            // 决策落地：先把浮窗还原成不可聚焦（收键盘 / 还焦点），再 resolve。
                            // 否则窗口在收起动画期间仍持有输入焦点，会遮挡下层 App。
                            setFocusable(composerActive = false, decisionConsumed = true)
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
                attached = Attached(wm, composeView, params, owner.lifecycleRegistry, request.id, requestState)
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

    /**
     * 切换窗口焦点模式：有明确输入意图时 focusable=true，去掉 FLAG_NOT_FOCUSABLE 并改用
     * SOFT_INPUT_ADJUST_PAN，让卡片里的 `BasicTextField` 拿到焦点、IME 能弹出；意图消失/决策/
     * 消失时还原成不可聚焦 + 主动收键盘，把输入焦点还给下层 App、不吞返回手势。
     *
     * 与 `AgentRuntimeService.setCapsuleInputMode` 同一套 updateViewLayout 模式。从 Compose
     * 回调（主线程）调用，非主线程时 post 到主线程执行。幂等：状态未变时不重复改布局。
     */
    private fun setFocusable(composerActive: Boolean, decisionConsumed: Boolean = false) {
        val focusable = ApprovalOverlayFocusPolicy.shouldBeFocusable(composerActive, decisionConsumed)
        val applyFocusMode = {
            val current = attached
            if (current != null && current.focusable != focusable) {
                val lp = current.params
                // 从加窗时的基准 flags 派生，绝不就地拿 lp.flags 当基准：
                // updateViewLayout 失败时 lp 不会被写脏，窗口状态与基准始终一致（G4）。
                val nextFlags = if (focusable) {
                    current.baseFlags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
                } else {
                    current.baseFlags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                }
                val nextSoftInputMode = if (focusable) {
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
                } else {
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
                }
                val snapshotFlags = lp.flags
                val snapshotSoftInputMode = lp.softInputMode
                lp.flags = nextFlags
                lp.softInputMode = nextSoftInputMode
                val ok = runCatching {
                    current.windowManager.updateViewLayout(current.view, lp)
                }.isSuccess
                if (ok) {
                    current.focusable = focusable
                    if (focusable) current.everFocusable = true
                    // true -> false：主动清焦点 + 收键盘。放在这里而不是只依赖 detachLocked，
                    // 因为决策路径会先把 focusable 改成 false 再 dismiss（G2）。
                    if (!focusable && current.everFocusable) hideKeyboard(current)
                } else {
                    // 失败：回滚 lp 到快照，focusable 不变，下次 composer 变化会重试。
                    lp.flags = snapshotFlags
                    lp.softInputMode = snapshotSoftInputMode
                }
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runCatching { applyFocusMode() }
        } else {
            mainHandler.post { runCatching { applyFocusMode() } }
        }
    }

    /** 清掉附着在该浮窗上的输入焦点与 IME；可重复调用。 */
    private fun hideKeyboard(current: Attached) {
        runCatching { current.view.clearFocus() }
        runCatching {
            val imm = current.view.context
                .getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
            imm?.hideSoftInputFromWindow(current.view.windowToken, 0)
        }
    }

    private fun detachLocked() {
        val current = attached ?: return
        attached = null
        // 先收键盘再拆窗：曾可聚焦的窗口上 IME 可能附着，直接 removeView 会留残影/焦点。
        // 用 everFocusable 而非 focusable 作判据——决策路径可能已把 focusable 复位（G2）。
        if (current.everFocusable) hideKeyboard(current)
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
        overlayComposerHost: (Boolean) -> Unit,
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
                        overlayComposerHost = overlayComposerHost,
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
