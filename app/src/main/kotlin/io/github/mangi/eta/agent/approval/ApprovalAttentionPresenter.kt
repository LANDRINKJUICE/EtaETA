package io.github.mangi.eta.agent.approval

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
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
 * 三层架构（2026-11-12 定稿）里本类只管「裁决层载体」：
 * - 前台：聊天流卡片为主 + **eta_attention HIGH 强通知**。前台≠看得到卡片：用户常停在别的
 *   会话 / 设置页，归属会话的卡片并不在屏上；仅靠 9400 无声汇总通知（DEFAULT、不 heads-up）
 *   注意不到，故与后台一样发 heads-up 行动通知并抑制 9400 避免重复；
 * - 后台：eta_attention heads-up 通知 + 决策按钮 + RemoteInput 后台回复（唯一载体）。
 *   悬浮审批卡已移除 —— 真机证实 TYPE_ACCESSIBILITY_OVERLAY 浮窗拿不到 IME 焦点，后台打字
 *   改由通知的 RemoteInput 承接（SystemUI 托管键盘）。本类不再建任何窗口、不探测悬浮窗权限；
 * - 没有待确认：全部收起。
 *
 * 决策入口始终只有 [AgentApprovalCoordinator.shared.resolve] 一个：通知按钮（点选）与
 * RemoteInput（打字）都回灌到它，Presenter 只负责「把通知送到用户眼前」和「决策后收起载体」。
 *
 * 保留的加固（经两轮审查）：
 * - 所有通知操作都切回主线程；[doRefresh] 整体 runCatching，失败不会崩主线程；
 * - 通知 ID 由 [NotificationPolicy.allocateApprovalNotificationId] 按占用检测**唯一分配**，
 *   [activeNotifications] 精确记账，撤销只按记账 ID，不用 hash 兜底（避免撤错别人的通知）；
 * - 启动时清扫 9660..9999 段，撤掉上次进程被杀留下的僵尸行动通知；
 * - 通知权限 / channel 被关时不静默（放行 9400 作为唯一可见痕迹 + 记日志）；
 * - 内容签名去重：前后台切换 / 无关的 pending 变化会触发 refresh，但对同一张卡内容未变时
 *   跳过 notify，避免重绑通知行收起用户正在打字的 RemoteInput 输入框（SystemUI 行为）。
 */
internal object ApprovalAttentionPresenter {

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var installed = false

    private var appContext: Context? = null

    /** 已发出的行动通知：审批 id -> 实际使用的通知 ID（占用检测分配，保证唯一）。主线程访问。 */
    private val activeNotifications = linkedMapOf<String, Int>()

    /**
     * 已发出通知的内容签名：审批 id -> 签名。与 [activeNotifications] 同步增删，主线程访问。
     * 前后台切换 / 无关 pending 变化时内容签名不变，跳过 notify，保护正在打字的 RemoteInput。
     */
    private val notificationSignatures = linkedMapOf<String, String>()

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

    /** 重新裁决一次；可从任意线程调用，实际操作切主线程。Receiver 在决策落地后用它对账。 */
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
        val surface = AgentApprovalAttentionPolicy.decide(
            etaInForeground = foreground,
            pendingCount = pending.size,
        )

        when (surface) {
            AgentApprovalSurface.NONE -> {
                clearNotifications(ctx)
                ApprovalNotificationBridge.setSuppressed(ctx, false)
            }

            AgentApprovalSurface.CHAT_INLINE,
            AgentApprovalSurface.NOTIFICATION,
            -> {
                // 前台与后台都发 eta_attention HIGH heads-up 行动通知（带决策按钮 + RemoteInput），
                // 并抑制 9400 避免重复；showNotifications 内部会在通知权限 / channel 不可用时
                // 自动放行 9400 作为最后可见痕迹（沿用既有降级，不另造通路）。
                //
                // 注意：**不要先 clearNotifications**。showNotifications 自身就是幂等收敛器：
                // 它撤掉不在 pending 里的旧 ID、复用仍存活卡的 ID 原地更新（onlyAlertOnce 生效）、
                // 只给新卡分配新 ID；从后台切回前台同样收敛。若先 cancel 再 notify，同一 ID 的
                // 通知会被当成"新通知"重新 heads-up + 响铃，多卡 / 连续决策时必现重复打扰。
                // shouldEmitAttentionNotification 在此恒 true，仅作为产品决定的纯逻辑锚点（有单测）。
                if (AgentApprovalAttentionPolicy.shouldEmitAttentionNotification(surface)) {
                    showNotifications(ctx, pending)
                }
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
        val keptSignatures = linkedMapOf<String, String>()
        val previousSignatures = notificationSignatures.toMap()
        pending.forEach { request ->
            val notificationId = kept[request.id]
                ?: activeNotifications[request.id]?.takeIf { it !in kept.values }
                ?: NotificationPolicy.allocateApprovalNotificationId(used)
            used += notificationId
            val signature = contentSignature(request, notificationId)
            // 内容没变、且这一 ID 上一轮确实发出过：跳过 notify，别收起正在展开的 RemoteInput。
            val unchanged = previousSignatures[request.id] == signature &&
                activeNotifications[request.id] == notificationId
            val shown = if (unchanged) {
                true
            } else {
                runCatching {
                    AgentNotifications.showApprovalAttention(ctx, request, notificationId)
                    true
                }.getOrDefault(false)
            }
            if (shown) {
                kept[request.id] = notificationId
                keptSignatures[request.id] = signature
            }
        }
        activeNotifications.clear()
        activeNotifications.putAll(kept)
        notificationSignatures.clear()
        notificationSignatures.putAll(keptSignatures)
    }

    /**
     * 通知内容签名：囊括 showApprovalAttention 真正用来渲染的所有 request 字段 + 通知 ID。
     * 任何一个变了都必须重发通知（标题/正文/按钮/深链/回复位）；否则跳过重绑。
     */
    private fun contentSignature(request: AgentApprovalRequest, notificationId: Int): String = buildString {
        append(notificationId).append('|')
        append(request.id).append('|')
        append(request.kind).append('|')
        append(request.conversationId).append('|')
        append(request.subject).append('|')
        append(request.intent).append('|')
        append(request.question).append('|')
        append(request.explanation).append('|')
        append(request.allowFreeText).append('|')
        request.findings.forEach { append(it.category).append(':').append(it.detail).append('|') }
        request.options.forEach { append(it.id).append(':').append(it.kind).append(':').append(it.label).append('|') }
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
        notificationSignatures.clear()
    }

    private fun cancelOne(ctx: Context, requestId: String) {
        // G2：未记账就不撤。hash 重算出的 ID 可能正被另一张卡实际占用，会撤错别人的通知。
        val notificationId = activeNotifications.remove(requestId) ?: return
        notificationSignatures.remove(requestId)
        runCatching { AgentNotifications.cancelApprovalAttention(ctx, notificationId) }
    }
}
