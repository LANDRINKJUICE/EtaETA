package io.github.mangi.eta.agent.approval

import android.app.KeyguardManager
import android.app.NotificationManager
import android.os.PowerManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import io.github.mangi.eta.agent.accessibility.AgentAccessibilityService
import io.github.mangi.eta.agent.notify.AgentNotifications
import io.github.mangi.eta.agent.notify.ApprovalNotificationBridge
import io.github.mangi.eta.agent.notify.NotificationPolicy
import io.github.mangi.eta.core.AndroidAgentLogger

/**
 * 「这张审批卡该出现在哪里」的 Android 执行层，消费 [AgentApprovalAttentionPolicy.decide] 的结论。
 *
 * 进程内单例，只在 App 主进程 install 一次（幂等）。监听两个信号：
 * - [AgentApprovalCoordinator.shared] 的 pending 变化（发卡 / 决策 / 超时 / 撤卡）；
 * - [AgentForegroundTracker] 的前后台变化。
 *
 * 每次信号都重新裁决、收敛到一个且仅一个载体，绝不叠加：
 * - 前台：聊天流卡片为主；9400 汇总通知**不抑制**（用户可能停在别的会话/设置页，
 *   归属会话的卡片并不在屏上，9400 是"有 N 个操作等确认"的最后可见痕迹）；
 * - 后台且悬浮层可用：悬浮层复用 [io.github.mangi.eta.ui.components.AgentApprovalRequestCard]；
 * - 悬浮层不可用（无权限 / 锁屏 / 加窗失败）：eta_attention heads-up 通知 + 决策按钮兜底；
 * - 没有待确认：全部收起。
 *
 * **决策入口始终只有 [AgentApprovalCoordinator.shared.resolve] 一个**：悬浮层卡片与通知按钮
 * 都调用它，Presenter 只负责"把卡片送到用户眼前"和"决策后收起载体"，不产生第二条放行通路。
 *
 * 审查后加固（两轮）：
 * - 所有窗口/通知操作都切回主线程；[doRefresh] 整体 runCatching，失败不会崩主线程；
 * - 锁屏不建悬浮层（窗口在 keyguard 之后不可见），保持通知载体；
 * - 加窗失败用「冷却时间」而非永久标志：一次瞬时失败不会把首选载体永久降级；
 * - 通知 ID 由 [NotificationPolicy.allocateApprovalNotificationId] 按占用检测**唯一分配**，
 *   [activeNotifications] 精确记账，撤销只按记账 ID，不用 hash 兜底（避免撤错别人的通知）；
 * - 启动时清扫 9660..9999 段，撤掉上次进程被杀留下的僵尸行动通知；
 * - 通知权限/channel 被关时不静默（放行 9400 作为唯一可见痕迹 + 记日志）。
 */
internal object ApprovalAttentionPresenter {

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var installed = false

    private var appContext: Context? = null

    /** 已发出的行动通知：审批 id -> 实际使用的通知 ID（占用检测分配，保证唯一）。主线程访问。 */
    private val activeNotifications = linkedMapOf<String, Int>()

    /** 加窗失败后的重试冷却截止时刻（elapsedRealtime）；0 表示立即可用。成功/权限变化即清零。 */
    private var overlayRetryAfterMillis = 0L

    /** 上一轮的悬浮权限/无障碍状态，用于「权限由无到有」时立刻复位冷却。 */
    private var lastCanDrawOverlays = false
    private var lastAccessibilityReady = false

    fun install(context: Context) {
        if (installed) return
        synchronized(this) {
            if (installed) return
            val ctx = context.applicationContext
            appContext = ctx
            // G1：进程被杀时内存 Map 丢失，上次发出的行动通知没人撤。启动时清整段；
            // 340 次 cancel 是同步 binder，挪到后台线程，避免拖慢冷启动主线程。
            Thread({ sweepStaleNotifications(ctx) }, "approval-notif-sweep").start()
            AgentForegroundTracker.addListener { mainHandler.post { doRefresh() } }
            AgentApprovalCoordinator.shared.addPendingListener { mainHandler.post { doRefresh() } }
            // 启动时按真实 pending 收敛一次。
            mainHandler.post { doRefresh() }
            // G10：installed 放到最后写，避免别的线程看到 installed=true 却 appContext 还没就绪。
            installed = true
        }
    }

    /** 重新裁决一次；可从任意线程调用，实际操作切主线程。Receiver 在决策未落地时用它对账。 */
    fun refresh() {
        mainHandler.post { doRefresh() }
    }

    private fun doRefresh() {
        runCatching { doRefreshInner() }.onFailure { throwable ->
            AndroidAgentLogger.warn(
                "Approval attention refresh failed: type=${throwable.javaClass.simpleName}",
            )
        }
    }

    private fun doRefreshInner() {
        val ctx = appContext ?: return
        val pending = AgentApprovalCoordinator.shared.pending.value
        val foreground = AgentForegroundTracker.isForeground()
        val canDrawOverlays = runCatching { Settings.canDrawOverlays(ctx) }.getOrDefault(false)
        val accessibilityReady = AgentAccessibilityService.current() != null
        val keyguardLocked = isKeyguardLocked(ctx)

        // S2：用冷却而非永久失败标志。权限/无障碍「由无到有」时立刻复位冷却。
        if ((canDrawOverlays && !lastCanDrawOverlays) ||
            (accessibilityReady && !lastAccessibilityReady)
        ) {
            overlayRetryAfterMillis = 0L
        }
        lastCanDrawOverlays = canDrawOverlays
        lastAccessibilityReady = accessibilityReady

        val overlayUsable = SystemClock.elapsedRealtime() >= overlayRetryAfterMillis &&
            !keyguardLocked && // S3：锁屏下浮窗在 keyguard 之后，用户看不见，不能算可用载体
            AgentApprovalAttentionPolicy.overlayUsable(canDrawOverlays, accessibilityReady)
        val surface = AgentApprovalAttentionPolicy.decide(
            etaInForeground = foreground,
            pendingCount = pending.size,
            overlayUsable = overlayUsable,
        )

        when (surface) {
            AgentApprovalSurface.NONE -> {
                ApprovalAttentionOverlay.dismiss()
                clearNotifications(ctx)
                ApprovalNotificationBridge.setSuppressed(ctx, false)
            }

            AgentApprovalSurface.CHAT_INLINE -> {
                ApprovalAttentionOverlay.dismiss()
                clearNotifications(ctx)
                // G3：前台不抑制 9400。用户可能停在别的会话/设置页，归属会话的卡片并不在屏上；
                // 9400 是「有 N 个操作等确认」的被动提醒，重复成本远低于"全盲等超时"。
                ApprovalNotificationBridge.setSuppressed(ctx, false)
            }

            AgentApprovalSurface.OVERLAY -> {
                clearNotifications(ctx)
                ApprovalNotificationBridge.setSuppressed(ctx, true)
                val target = pending.first()
                val ok = ApprovalAttentionOverlay.show(ctx, target, onDecided = { refresh() })
                if (ok) {
                    overlayRetryAfterMillis = 0L
                } else {
                    // 规则 3：addView 实际失败再降级一次。用冷却防止每次 refresh 重复重试刷日志。
                    if (overlayRetryAfterMillis == 0L) {
                        AndroidAgentLogger.warn(
                            "Approval overlay addView failed; falling back to attention notification",
                        )
                    }
                    overlayRetryAfterMillis = SystemClock.elapsedRealtime() + OVERLAY_RETRY_COOLDOWN_MILLIS
                    // 冷却到期后主动重试一次悬浮层（不依赖外部信号），实现自愈。
                    mainHandler.postDelayed({ doRefresh() }, OVERLAY_RETRY_COOLDOWN_MILLIS)
                    ApprovalAttentionOverlay.dismiss()
                    showNotifications(ctx, pending)
                }
            }

            AgentApprovalSurface.NOTIFICATION -> {
                ApprovalAttentionOverlay.dismiss()
                ApprovalNotificationBridge.setSuppressed(ctx, true)
                showNotifications(ctx, pending)
            }
        }
    }

    private fun showNotifications(ctx: Context, pending: List<AgentApprovalRequest>) {
        // 通知权限 / channel 被关时，notify 会静默失败；此时不能撤掉 9400，
        // 否则"三条规则都满足却什么都看不到"。放行 9400 作为唯一可见痕迹并记日志。
        if (!notificationsUsable(ctx)) {
            AndroidAgentLogger.warn(
                "Approval attention notifications unavailable (permission/channel disabled); " +
                    "keeping the pending-approval summary notification",
            )
            ApprovalNotificationBridge.setSuppressed(ctx, false)
            return
        }
        ApprovalNotificationBridge.setSuppressed(ctx, true)

        val currentIds = pending.map { it.id }.toSet()
        (activeNotifications.keys - currentIds).forEach { requestId -> cancelOne(ctx, requestId) }

        // S1：used 必须同时包含上一轮仍存活的 + **本轮已分配的**，否则同一轮两张新卡都拿到 base。
        val used = activeNotifications.values.toMutableSet()
        val kept = linkedMapOf<String, Int>()
        pending.forEach { request ->
            val notificationId = kept[request.id]
                ?: activeNotifications[request.id]?.takeIf { it !in kept.values }
                ?: NotificationPolicy.allocateApprovalNotificationId(used)
            used += notificationId
            val shown = runCatching {
                AgentNotifications.showApprovalAttention(ctx, request, notificationId)
                true
            }.getOrDefault(false)
            if (shown) kept[request.id] = notificationId
        }
        activeNotifications.clear()
        activeNotifications.putAll(kept)
    }

    private fun isKeyguardLocked(ctx: Context): Boolean {
        val km = ctx.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
        // 无锁屏设置下 isKeyguardLocked 可能为 false，叠加息屏判断堵住口袋误触/息屏换隐形浮窗。
        return km?.isKeyguardLocked == true || pm?.isInteractive == false
    }

    private fun notificationsUsable(ctx: Context): Boolean {
        val manager = ctx.getSystemService(NotificationManager::class.java) ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = manager.getNotificationChannel(NotificationPolicy.CHANNEL_ATTENTION)
            if (channel != null && channel.importance == NotificationManager.IMPORTANCE_NONE) return false
        }
        // API 24+ 框架自带；不引入 androidx.core 额外依赖。
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            runCatching { manager.areNotificationsEnabled() }.getOrDefault(true)
        } else {
            true
        }
    }

    private fun sweepStaleNotifications(ctx: Context) {
        val base = NotificationPolicy.approvalAttentionBase()
        for (offset in 0 until NotificationPolicy.approvalAttentionRange()) {
            runCatching { AgentNotifications.cancelApprovalAttention(ctx, base + offset) }
        }
    }

    private fun clearNotifications(ctx: Context) {
        activeNotifications.keys.toList().forEach { requestId -> cancelOne(ctx, requestId) }
        activeNotifications.clear()
    }

    private fun cancelOne(ctx: Context, requestId: String) {
        // G2：未记账就不撤。hash 重算出的 ID 可能正被另一张卡实际占用，会撤错别人的通知。
        val notificationId = activeNotifications.remove(requestId) ?: return
        runCatching { AgentNotifications.cancelApprovalAttention(ctx, notificationId) }
    }

    private const val OVERLAY_RETRY_COOLDOWN_MILLIS = 30_000L
}
