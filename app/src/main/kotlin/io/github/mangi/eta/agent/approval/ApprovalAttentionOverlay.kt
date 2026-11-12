package io.github.mangi.eta.agent.approval

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
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
import androidx.compose.ui.layout.onSizeChanged
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
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.data.model.AppearanceSettings
import io.github.mangi.eta.data.repository.AppearanceSettingsRepository
import io.github.mangi.eta.ui.app.AgentAppTheme
import io.github.mangi.eta.ui.components.AgentApprovalRequestCard
import top.yukonga.miuix.kmp.squircle.LocalSquircleEnabled
import kotlin.math.roundToInt

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

    /**
     * 浮窗卡片可见高度上限（dp）：[ApprovalCardContent] 里 Column 的 `heightIn(max = ...)` 与
     * 窗口高度 clamp 共用同一个常量，保证「窗口 height === 可绘制内容盒高度」精确相等——
     * 不裁切卡片底边、也不在卡片下方留一条会吞触摸的透明死区。长命令超过它时卡片内部
     * verticalScroll 滚动，窗口高度保持这个值。
     *
     * 注意：该处的 `verticalScroll` 会给子节点无限高约束，onSizeChanged 回报的是「自然内容
     * 高度」（可能超过本上限），所以窗口层必须 clamp 到本上限，不能照单写回。
     */
    private const val CARD_MAX_HEIGHT_DP = 520

    /** 弹 IME 重试：窗口焦点切换是异步的，短间隔多试几次覆盖这段窗口；总数约 8×70ms。 */
    private const val IME_SHOW_RETRY_INTERVAL_MS = 70L
    private const val IME_SHOW_RETRY_MAX = 8

    private class Attached(
        val windowManager: WindowManager,
        val view: ComposeView,
        val params: WindowManager.LayoutParams,
        val lifecycle: LifecycleRegistry,
        val requestId: String,
        val requestState: MutableState<AgentApprovalRequest>,
        /** 窗口高度 clamp 上限（px），构造时算好，不依赖窗口已添加、无事后赋值时序。 */
        val maxHeightPx: Int,
    ) {
        /** 加窗时的初始 flags 基准（含 FLAG_NOT_FOCUSABLE）；每次切焦点从它派生，避免失败后基准被改脏。 */
        val baseFlags: Int = params.flags

        /** 当前窗口是否处于「可聚焦」态（composer 可见、IME 可用）。主线程访问。 */
        var focusable: Boolean = ApprovalOverlayFocusPolicy.INITIAL_FOCUSABLE

        /** 窗口是否曾进入过可聚焦态；用于 detach 时判断是否需要主动收键盘 / 清焦点。主线程访问。 */
        var everFocusable: Boolean = false

        /** 正在进行的弹 IME 重试 Runnable；还原不可聚焦 / 拆窗时取消，避免决策后还弹键盘。主线程访问。 */
        var imeShowRunnable: Runnable? = null

        /**
         * 已成功收敛到窗口 params.height 的卡片内容高度（px）。首次为
         * [ApprovalOverlaySizingPolicy.HEIGHT_UNSET]（对应 WRAP_CONTENT），由内容 onSizeChanged
         * 驱动收敛；去抖用，避免同尺寸反复 updateViewLayout。主线程访问。
         */
        var appliedHeightPx: Int = ApprovalOverlaySizingPolicy.HEIGHT_UNSET
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
            // 记录最后一次内容实测高度：若首帧 onSizeChanged 在 attached 赋值前就发生（被
            // applyHeight 的 `attached ?: return` 丢掉），建窗后补一次 applyHeight 兜底，
            // 避免「首帧回调丢失 → 窗口停在 WRAP_CONTENT → BUG 1 静默复发」。主线程读写。
            var pendingHeightPx = ApprovalOverlaySizingPolicy.HEIGHT_UNSET
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
                        // BUG 1：把内容实测高度收敛成窗口 height，让窗口 frame 精确贴合卡片。
                        // 否则 MATCH_PARENT × WRAP_CONTENT 的系统浮窗会被 WMS 撑成近整屏，
                        // FLAG_NOT_TOUCH_MODAL 没有「窗口外」可穿透，卡片外整屏触摸全被吞。
                        // 带上 requestId 做归属校验，避免迟到的 post 把上一张卡的高度套到新窗口。
                        onCardHeightChanged = { heightPx ->
                            pendingHeightPx = heightPx
                            applyHeight(request.id, heightPx)
                        },
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
                attached = Attached(
                    wm, composeView, params, owner.lifecycleRegistry, request.id, requestState,
                    maxHeightPx = clampHeightPx(overlayContext, wm, params.y),
                )
                // 兜底：首帧回调若早于 attached 赋值被丢弃，这里用记录值补一次收敛。
                val recorded = pendingHeightPx
                if (recorded > 0) applyHeight(request.id, recorded)
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
            if (current != null) {
                // 1) 必要时切窗口 flag / softInputMode（幂等：状态未变不重复改布局）。
                if (current.focusable != focusable) {
                    val lp = current.params
                    // 从加窗时的基准 flags 派生，绝不就地拿 lp.flags 当基准：
                    // updateViewLayout 失败时 lp 不会被写脏，窗口状态与基准始终一致（G4）。
                    val nextFlags = if (focusable) {
                        current.baseFlags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
                    } else {
                        current.baseFlags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    }
                    // focusable 时带 STATE_VISIBLE：窗口由 FLAG_NOT_FOCUSABLE 切为可聚焦、
                    // 真正获焦的那一次由系统据 state 位把 IME 拉起；「获焦之后」的重放（如按
                    // back 收键盘再点输入框）由 requestShowIme 负责——state 位只在获焦时消费一次。
                    val nextSoftInputMode = if (focusable) {
                        WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN or
                            WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
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
                    } else {
                        // 失败：回滚 lp 到快照，focusable 不变，下次 composer 变化会重试。
                        lp.flags = snapshotFlags
                        lp.softInputMode = snapshotSoftInputMode
                        AndroidAgentLogger.warn(
                            "Approval overlay focus update failed: request=${current.requestId}",
                        )
                    }
                }
                // 2) 输入意图存在 → 每次都（重新）确保 IME 弹出：覆盖「按 back 收掉键盘后再点
                //    输入框」的重放，也覆盖「flag 切换与窗口真正获焦之间的异步窗口」（有界重试）。
                //    意图消失 / 决策落地 → 取消重试并主动收键盘（G2 还原也走这里），把焦点还下层 App。
                if (focusable && current.focusable) {
                    requestShowIme(current)
                } else {
                    cancelShowIme(current)
                    if (current.everFocusable) hideKeyboard(current)
                }
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runCatching { applyFocusMode() }
        } else {
            mainHandler.post { runCatching { applyFocusMode() } }
        }
    }

    /**
     * 后台悬浮审批卡弹 IME 的有界重试。
     *
     * 老 BUG 根因：窗口从 FLAG_NOT_FOCUSABLE 切到可聚焦后，WMS 的焦点转移 / 下层 App IME 收起
     * / BasicTextField 建立 InputConnection 都是异步的；卡片里那次 `delay(180) + keyboard.show()`
     * 单次盲等常踩在窗口真正获焦之前，且失败被静默吞掉。这里在宿主层用原生 IMM 做几次短重试，
     * 覆盖这段时序窗口；每次先 view.requestFocus() 再 showSoftInput，已显示时幂等。
     * 配合 softInputMode 的 SOFT_INPUT_STATE_VISIBLE（窗口获焦时系统自动拉起）双保险。
     */
    private fun requestShowIme(current: Attached) {
        cancelShowIme(current)
        val view = current.view
        val imm = runCatching {
            view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        }.getOrNull() ?: return
        val runnable = object : Runnable {
            var attempts = 0
            override fun run() {
                // 窗口已替换 / 已还原不可聚焦 / 已拆窗：停止，别把键盘弹到别人的界面上。
                if (attached !== current || !current.focusable || !view.isAttachedToWindow) {
                    current.imeShowRunnable = null
                    return
                }
                // 窗口级拉 IME（minSdk 33）：不依赖 mServedView / InputConnection，最贴合
                // 「窗口刚从 FLAG_NOT_FOCUSABLE 切到可聚焦」；补上 Compose 尚未把焦点交给
                // BasicTextField 那段时序里视图级 showSoftInput 的空转。
                runCatching {
                    view.windowInsetsController?.show(android.view.WindowInsets.Type.ime())
                }
                // 与下面的 showSoftInput 用同一个焦点目标，避免把 View 级焦点从 AndroidComposeView
                // 挪到外层 ComposeView 而踢掉 BasicTextField 的焦点。
                runCatching { (view.findFocus() ?: view).requestFocus() }
                // 视图级兜底：Compose 聚焦后 findFocus() 返回 AndroidComposeView，showSoftInput
                // 与卡片里 LocalSoftwareKeyboardController.show() 同源，幂等。
                runCatching {
                    val target = view.findFocus() ?: view
                    imm.showSoftInput(target, InputMethodManager.SHOW_IMPLICIT)
                }
                // 已显示就立刻停，避免用户按 back 收键盘后仍被重试拉回来一次。
                val imeVisible = runCatching {
                    view.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime()) == true
                }.getOrDefault(false)
                if (imeVisible || ++attempts >= IME_SHOW_RETRY_MAX) {
                    current.imeShowRunnable = null
                    if (!imeVisible) {
                        AndroidAgentLogger.warn(
                            "Approval overlay IME show retries exhausted: request=${current.requestId}",
                        )
                    }
                    return
                }
                mainHandler.postDelayed(this, IME_SHOW_RETRY_INTERVAL_MS)
            }
        }
        current.imeShowRunnable = runnable
        mainHandler.post(runnable)
    }

    /** 取消未完成的弹 IME 重试（决策落地 / 还原不可聚焦 / 拆窗时）。可重复调用。 */
    private fun cancelShowIme(current: Attached) {
        current.imeShowRunnable?.let { mainHandler.removeCallbacks(it) }
        current.imeShowRunnable = null
    }

    /**
     * BUG 1 修复：把窗口 height 从 WRAP_CONTENT 收敛成卡片内容的实测高度，让窗口 frame
     * 精确贴合卡片，卡片下方重新成为 FLAG_NOT_TOUCH_MODAL 的「窗口外」区域而允许触摸穿透。
     *
     * 与 [setFocusable] 共用同一个 [Attached.params]：两者分别只改 height 与
     * flags/softInputMode，互不覆盖；都先快照再 updateViewLayout，失败回滚到快照，
     * 保证单次失败不把窗口状态改脏。[ApprovalOverlaySizingPolicy.shouldApplyHeight] 去抖，
     * 同尺寸不重复写、避免 WMS 反复重新布局。从 Compose 主线程回调调用，非主线程 post。
     *
     * 归属校验：[expectedRequestId] 必须与当前 [Attached.requestId] 一致才写，避免迟到的
     * post 把上一张卡的高度套到新建的窗口上。
     *
     * @param expectedRequestId 发起该次测量的卡片 request id。
     * @param measuredHeightPx 卡片内容的自然实测高度（px）：onSizeChanged 挂在 verticalScroll
     *   内层，回报的是不受 [CARD_MAX_HEIGHT_DP] 约束的自然内容高度，可能超过可见上限，
     *   因此必须经 [ApprovalOverlaySizingPolicy.targetHeightPx] clamp 后才能作为窗口高度。
     */
    private fun applyHeight(expectedRequestId: String, measuredHeightPx: Int) {
        // 局部函数用裸 return（赋值局部 lambda 没有隐式标签，return@apply 会编译失败）。
        fun applyNow() {
            val current = attached ?: return
            if (current.requestId != expectedRequestId) return
            if (!current.view.isAttachedToWindow) return
            // 先把实测值（可能是 verticalScroll 内层的自然高度 / 整屏 EXACTLY 约束下的回报）
            // clamp 成合法窗口目标高度。
            val target = ApprovalOverlaySizingPolicy.targetHeightPx(measuredHeightPx, current.maxHeightPx)
            if (!ApprovalOverlaySizingPolicy.shouldApplyHeight(current.appliedHeightPx, target)) return
            val lp = current.params
            val snapshotHeight = lp.height
            lp.height = target
            val exception = runCatching {
                current.windowManager.updateViewLayout(current.view, lp)
            }.exceptionOrNull()
            if (exception == null) {
                current.appliedHeightPx = target
            } else {
                // 失败：回滚到快照，下次尺寸变化会重试；窗口状态与基准保持一致。
                lp.height = snapshotHeight
                AndroidAgentLogger.error(
                    "Approval overlay height apply failed",
                    exception,
                )
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runCatching { applyNow() }
        } else {
            mainHandler.post { runCatching { applyNow() } }
        }
    }

    /** 清掉附着在该浮窗上的输入焦点与 IME；可重复调用。 */
    private fun hideKeyboard(current: Attached) {
        runCatching { current.view.clearFocus() }
        runCatching {
            val imm = current.view.context
                .getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(current.view.windowToken, 0)
        }
    }

    private fun detachLocked() {
        val current = attached ?: return
        attached = null
        cancelShowIme(current)
        // 先收键盘再拆窗：曾可聚焦的窗口上 IME 可能附着，直接 removeView 会留残影/焦点。
        // 用 everFocusable 而非 focusable 作判据——决策路径可能已把 focusable 复位（G2）。
        if (current.everFocusable) hideKeyboard(current)
        runCatching { current.windowManager.removeViewImmediate(current.view) }
        // 派发 ON_DESTROY 让 DisposeOnViewTreeLifecycleDestroyed 真正销毁 composition + 收集器。
        runCatching { current.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY) }
    }

    /**
     * 窗口高度 clamp 上限（px）：取「卡片可见上限 [CARD_MAX_HEIGHT_DP]」与「屏幕可用高度」
     * 的较小值，保证竖屏精确贴合、横屏/小屏也不超出显示区（否则窗口等于整屏，又没窗口外
     * 可穿透区，BUG 1 在横屏复发）。屏幕尺寸取不到时退回卡片上限，density 恒为设备密度、不会 <=0。
     *
     * @param windowTopY 窗口顶部 y（状态栏∪挖孔 inset，见 [statusBarHeightPx]）。
     */
    private fun clampHeightPx(context: Context, wm: WindowManager, windowTopY: Int): Int {
        val density = context.resources.displayMetrics.density
        // roundToInt 与 Compose 的 dp->px 舍入对齐，避免 1px 裁切 / 1px 透明死区。
        val cardMax = (CARD_MAX_HEIGHT_DP * density).roundToInt()
        val bottomMargin = (24 * density).roundToInt()
        val screenHeight = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { wm.currentWindowMetrics.bounds.height() }.getOrDefault(0)
        } else {
            @Suppress("DEPRECATION")
            runCatching {
                val point = android.graphics.Point()
                wm.defaultDisplay?.getRealSize(point)
                point.y
            }.getOrDefault(0)
        }
        val available = screenHeight - windowTopY - bottomMargin
        val minUsable = (120 * density).roundToInt()
        // 可用区太小时（极端小屏/大 inset）直接退回卡片上限，绝不把窗口放大到超过可用区。
        return if (available >= minUsable) {
            minOf(cardMax, available)
        } else {
            cardMax
        }
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
        onCardHeightChanged: (Int) -> Unit,
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
                        .heightIn(max = CARD_MAX_HEIGHT_DP.dp)
                        .verticalScroll(rememberScrollState())
                        // onSizeChanged 必须放在 verticalScroll 内层（回报自然内容高度，不会被当前
                        // 窗口高度反向钳住形成「设小后永远长不回去」的棘轮），同时放在 padding
                        // 之前（回报值含上下 padding，窗口 height 与可绘制内容盒精确相等，不裁
                        // 卡片底边、也不留吞触摸的透明死区）。把实测高度回报窗口层收敛 params.height。
                        .onSizeChanged { size -> onCardHeightChanged(size.height) }
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
