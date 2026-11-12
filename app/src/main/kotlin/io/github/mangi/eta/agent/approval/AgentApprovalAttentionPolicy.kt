package io.github.mangi.eta.agent.approval

/**
 * 「这次审批卡该出现在哪里」的唯一裁决点。
 *
 * 审批卡原来只存在于聊天流里：用户在别的 App（看视频、聊天）时永远看不到，
 * 工具只能干等到 2 分钟超时。这里把「用哪种载体提醒用户」收敛成纯逻辑，
 * 让前后台判定、降级条件、通知按钮映射都能在纯 JVM 单测里钉住，
 * Android 侧（[ApprovalAttentionPresenter]）只负责执行结论。
 *
 * 三条硬规则：
 * 1. Eta 在前台时主载体是聊天流卡片，不弹悬浮层；但**执行层仍要补发 eta_attention 强通知**
 *    （方案 1）——「在前台」不等于「看得到卡片」：用户常停在别的会话 / 设置页 / 任务中心，
 *    归属会话的卡片并不在屏上，仅靠 9400 无声汇总通知根本注意不到。前台与后台一样发
 *    HIGH heads-up 行动通知（带直接决策按钮），抑制 9400 避免重复。
 * 2. 后台优先悬浮层卡片（可直接点选、可填自由输入，与聊天流是同一个 Composable）；
 * 3. 悬浮层不可用（无权限 / 宿主服务不在 / addView 失败）才退成带按钮的 heads-up 通知。
 *
 * 任何分支都只是「把卡片送到用户眼前」，决策入口仍只有 [AgentApprovalCoordinator.resolve]
 * 一个，不会出现第二条放行通路。
 */
internal enum class AgentApprovalSurface {
    /** 没有待确认请求：所有载体都应收起。 */
    NONE,

    /**
     * Eta 在前台：主载体是聊天流里的卡片；执行层额外补发 eta_attention 强通知，
     * 覆盖「前台但不在归属会话 / 在设置页」看不到卡片的场景（见头注释规则 1）。
     */
    CHAT_INLINE,

    /** Eta 在后台：悬浮层卡片（复用聊天流同一个 Composable）。 */
    OVERLAY,

    /** 悬浮层不可用：eta_attention heads-up 通知 + 按钮。 */
    NOTIFICATION,
}

internal object AgentApprovalAttentionPolicy {

    /**
     * 单条通知最多几个 action。
     *
     * 3 是折叠态可见的上限：Android 折叠通知只展示前 3 个 action，第 4 个会被塞进
     * 「更多」里，等于没显示。危险卡正好三个（允许一次 / 本会话允许同类 / 拒绝），
     * 询问卡超过 3 个选项就不拆按钮，改为「打开处理」。
     */
    const val NOTIFICATION_ACTION_LIMIT = 3

    /** 裁决当前应该用哪种载体。[pendingCount] <= 0 时一律 [AgentApprovalSurface.NONE]。 */
    fun decide(
        etaInForeground: Boolean,
        pendingCount: Int,
        overlayUsable: Boolean,
    ): AgentApprovalSurface = when {
        pendingCount <= 0 -> AgentApprovalSurface.NONE
        etaInForeground -> AgentApprovalSurface.CHAT_INLINE
        overlayUsable -> AgentApprovalSurface.OVERLAY
        else -> AgentApprovalSurface.NOTIFICATION
    }

    /**
     * 悬浮层能不能用：`SYSTEM_ALERT_WINDOW` 已授予，或无障碍服务在跑
     * （`TYPE_ACCESSIBILITY_OVERLAY` 不需要悬浮窗权限，与既有 AgentOverlay 的取型逻辑一致）。
     *
     * 这里只判断「有没有资格建窗口」；真正的建窗失败（ROM 拒绝、BadToken）由调用方按
     * [decide] 的结果执行后，用 addView 的实际结果再降级一次。
     */
    fun overlayUsable(canDrawOverlays: Boolean, accessibilityOverlayReady: Boolean): Boolean =
        canDrawOverlays || accessibilityOverlayReady

    /**
     * 方案 1：当前载体是否要补发 eta_attention HIGH 行动通知（heads-up + 直接决策按钮）。
     *
     * - [AgentApprovalSurface.CHAT_INLINE]：要。前台≠看得到卡片——用户常停在别的会话 /
     *   设置页 / 任务中心，归属会话的聊天流卡片并不在屏上；只留 9400 无声汇总通知会"全盲等超时"。
     * - [AgentApprovalSurface.NOTIFICATION]：要（本来就是通知兜底）。
     * - [AgentApprovalSurface.OVERLAY]：不要（悬浮层已把卡片送到眼前，再弹通知是重复打扰；
     *   addView 失败时会由调用方降级成 NOTIFICATION，那时才发）。
     * - [AgentApprovalSurface.NONE]：不要。
     *
     * 抽成纯逻辑以便单测钉住"前台也强提醒"这个产品决定，避免执行层改着改着又退回弱提醒。
     *
     * ⚠️ 若将来把 [AgentApprovalSurface.CHAT_INLINE] 改回 `false`（前台不强提醒），
     * 必须同步让 [ApprovalAttentionPresenter] 的 CHAT_INLINE 分支恢复
     * `ApprovalNotificationBridge.setSuppressed(ctx, false)`：否则 9400 会停留在上一次的抑制态、
     * 悬浮层又被关闭，前台将完全没有可见痕迹（比改回之前更糟）。单测已硬钉此值为 true。
     */
    fun shouldEmitAttentionNotification(surface: AgentApprovalSurface): Boolean = when (surface) {
        AgentApprovalSurface.CHAT_INLINE, AgentApprovalSurface.NOTIFICATION -> true
        AgentApprovalSurface.OVERLAY, AgentApprovalSurface.NONE -> false
    }

    /**
     * 通知上直接做成按钮的选项 id（顺序即展示顺序，最多 [NOTIFICATION_ACTION_LIMIT] 个）。
     *
     * - 危险卡：允许一次 /（本次会话允许同类，CRITICAL 或无法归属会话时请求里本来就没有）/ 拒绝。
     *   「拒绝并说明」必须带自由文本，通知按钮无法收集输入，因此不出现在通知上；
     *   用户点通知主体可以进任务中心，或等悬浮层/聊天流里补说明。
     * - 询问卡：模型给的选项 ≤ 3 时逐个做成按钮；否则返回空列表，调用方改用「打开处理」。
     */
    fun notificationActions(request: AgentApprovalRequest): List<String> = when (request.kind) {
        AgentApprovalKind.ToolRisk -> buildList {
            val ids = request.options.map { it.id }.toSet()
            if (AgentApprovalOption.ALLOW_ONCE in ids) add(AgentApprovalOption.ALLOW_ONCE)
            if (AgentApprovalOption.ALLOW_SESSION in ids) add(AgentApprovalOption.ALLOW_SESSION)
            if (AgentApprovalOption.DENY in ids) add(AgentApprovalOption.DENY)
        }.take(NOTIFICATION_ACTION_LIMIT)

        AgentApprovalKind.AskUser -> request.options
            .map { it.id }
            .distinct()
            .takeIf { it.isNotEmpty() && it.size <= NOTIFICATION_ACTION_LIMIT }
            .orEmpty()
    }

    /**
     * 是否需要额外给一个「打开处理」按钮：通知上放不下全部选项（询问卡 > 3 个选项、
     * 或只有自由输入没有任何选项）。危险卡永远有三个以内的按钮，不需要。
     */
    fun needsOpenAction(request: AgentApprovalRequest): Boolean =
        notificationActions(request).isEmpty()
}
