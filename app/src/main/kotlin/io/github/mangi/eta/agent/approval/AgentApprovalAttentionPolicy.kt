package io.github.mangi.eta.agent.approval

/**
 * 「这次审批卡该出现在哪里 / 通知上放哪些 action」的唯一裁决点（纯逻辑，可 JVM 单测）。
 *
 * 三层架构（2026-11-12 定稿）：状态 = 灵动岛只读、裁决 = 通知、深聊 = 前台会话。
 * 后台审批一律走 eta_attention heads-up 通知：
 *  - 前台：聊天流卡片为主 + 同样补发强通知（前台 ≠ 看得到卡片：用户常停在别的会话 / 设置页）；
 *  - 后台：通知是唯一载体。悬浮审批卡的打字能力已被真机证伪 —— 设备启用 Eta 无障碍服务时
 *    浮窗走 TYPE_ACCESSIBILITY_OVERLAY，这种窗口永远拿不到 IME 焦点，系统逐个取消 show 请求。
 *    继续在 overlay 里堆 flag / 重试无解，故整条悬浮卡载体移除，后台打字改由通知的
 *    [android.app.RemoteInput] 承接（SystemUI 托管键盘，锁屏 / 后台都必弹）。
 *
 * 决策入口始终只有 [AgentApprovalCoordinator.resolve] 一个：通知按钮（点选）与
 * 「后台回复」RemoteInput（打字）都回灌到这里，执行层只负责把卡片 / 通知送到用户眼前。
 */
internal enum class AgentApprovalSurface {
    /** 没有待确认请求：所有载体都应收起。 */
    NONE,

    /** Eta 在前台：主载体是聊天流卡片，执行层额外补发 eta_attention 强通知。 */
    CHAT_INLINE,

    /** Eta 在后台：eta_attention heads-up 通知 + 决策按钮 + RemoteInput 后台回复。 */
    NOTIFICATION,
}

internal object AgentApprovalAttentionPolicy {

    /**
     * 单条通知最多几个 action。折叠态只展示前 3 个，第 4 个会被塞进「更多」等于没显示。
     */
    const val NOTIFICATION_ACTION_LIMIT = 3

    /**
     * 决策按钮的预算：固定给「后台回复」RemoteInput action 留 1 个位置。
     * 定稿取舍 —— 我自己说占 1 个 action，决策按钮平铺最多剩 2；放不下的选项点通知主体
     * 进前台聊天流卡片（那里保留全部选项 + 自由输入框），不是功能丢失，只是横幅上的裁剪。
     */
    const val NOTIFICATION_DECISION_ACTION_LIMIT = NOTIFICATION_ACTION_LIMIT - 1

    /** 裁决当前应该用哪种载体。[pendingCount] <= 0 时一律 [AgentApprovalSurface.NONE]。 */
    fun decide(
        etaInForeground: Boolean,
        pendingCount: Int,
    ): AgentApprovalSurface = when {
        pendingCount <= 0 -> AgentApprovalSurface.NONE
        etaInForeground -> AgentApprovalSurface.CHAT_INLINE
        else -> AgentApprovalSurface.NOTIFICATION
    }

    /**
     * 是否要发 eta_attention HIGH 行动通知（heads-up + 决策按钮 + RemoteInput）。
     * CHAT_INLINE 也要：前台不等于看得到卡片，只靠 9400 无声汇总通知会「全盲等超时」。
     * 抽成纯逻辑以便单测钉住这个产品决定。
     */
    fun shouldEmitAttentionNotification(surface: AgentApprovalSurface): Boolean = when (surface) {
        AgentApprovalSurface.CHAT_INLINE, AgentApprovalSurface.NOTIFICATION -> true
        AgentApprovalSurface.NONE -> false
    }

    /**
     * 是否给这条通知预留一个「后台回复」RemoteInput action（占 1 个 action 位）。
     * 危险卡恒 true：`allowFreeText=true`，打字 = 拒绝命令并给模型一句纠正建议；
     * 询问卡看模型是否开 `allow_free_text`。不预留时决策按钮可用满 3 个位置。
     */
    fun replyActionReserved(request: AgentApprovalRequest): Boolean = request.allowFreeText

    /**
     * 通知上的「决策按钮」选项 id（顺序即展示顺序）。已为「后台回复」让出 1 个位置，
     * 因此本列表长度恒 ≤ [NOTIFICATION_DECISION_ACTION_LIMIT]（无回复位时才到
     * [NOTIFICATION_ACTION_LIMIT]）。
     *
     * - 危险卡：只保留最关键的「允许一次 / 拒绝」两个。砍掉「本会话允许同类」是定稿取舍
     *   （会话授权是便捷项、不是安全必需，点通知主体进前台卡片仍可点）；「拒绝并说明」由
     *   「后台回复」打字位承载 —— 打字即拒绝 + 文字当纠正建议，语义与聊天流一致。
     *   **绝不砍「拒绝」**：否则危险卡通知只剩放行按钮，是安全风险。
     * - 询问卡：选项数在决策预算内就平铺，超了返回空列表（调用方改用「去确认」进前台，
     *   或由「后台回复」打字位直接回答）。
     */
    fun notificationActions(request: AgentApprovalRequest): List<String> {
        val ids = request.options.map { it.id }.toSet()
        val budget = if (replyActionReserved(request)) {
            NOTIFICATION_DECISION_ACTION_LIMIT
        } else {
            NOTIFICATION_ACTION_LIMIT
        }
        return when (request.kind) {
            AgentApprovalKind.ToolRisk -> listOf(
                AgentApprovalOption.ALLOW_ONCE,
                AgentApprovalOption.DENY,
            ).filter { it in ids }

            AgentApprovalKind.AskUser -> request.options
                .map { it.id }
                .distinct()
                .takeIf { it.isNotEmpty() && it.size <= budget }
                .orEmpty()
        }
    }

    /**
     * 决策按钮一个都放不下时（选项过多 / 纯自由输入），给「去确认」直达前台聊天流卡片。
     * 即便通知上已有「后台回复」打字位，仍保留一个进前台看完整上下文的入口。
     */
    fun needsOpenAction(request: AgentApprovalRequest): Boolean =
        notificationActions(request).isEmpty()

    /**
     * 防御性断言用：这条通知最终会放几个 action。纯逻辑，单测钉住「任何请求都 ≤3」，
     * 避免以后加减 action 时把第 4 个悄悄塞进「更多」里等于没显示。
     * 组成 = 决策按钮 + 后台回复（可选）+ 去确认（可选）。
     */
    fun notificationActionCount(request: AgentApprovalRequest): Int =
        notificationActions(request).size +
            (if (replyActionReserved(request)) 1 else 0) +
            (if (needsOpenAction(request)) 1 else 0)
}
