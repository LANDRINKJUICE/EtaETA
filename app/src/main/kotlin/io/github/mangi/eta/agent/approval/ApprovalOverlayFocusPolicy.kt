package io.github.mangi.eta.agent.approval

/**
 * 后台审批悬浮层「输入焦点模式」的纯逻辑裁决，不引用任何 android.*，可在纯 JVM 上回归。
 *
 * 背景（用户实测 BUG）：悬浮窗默认 `FLAG_NOT_FOCUSABLE` +
 * SOFT_INPUT_ADJUST_NOTHING，不抢下层 App 的焦点、不吞返回手势；代价是卡片里的
 * `BasicTextField`（DENY_WITH_FEEDBACK 的「拒绝并说明」、询问卡的自由输入 composer）
 * 拿不到焦点、IME 弹不出来，自由文本没法填，Send 永远灰。
 *
 * 修法（与 AgentRuntimeService.setCapsuleInputMode / OverlaySupplementInput 同一套）：
 * 仅当用户明确要打字时（危险卡点「拒绝并说明」、询问卡点输入区），临时去掉
 * FLAG_NOT_FOCUSABLE 并改用 SOFT_INPUT_ADJUST_PAN；意图消失 / 决策完成 / 卡片消失时
 * 立刻加回 flag 并把焦点还给下层 App。本对象只负责「此刻该可聚焦还是不该」这一纯判定，
 * 窗口 flag 的实际切换由 `ApprovalAttentionOverlay` 执行。
 *
 * 不变量：
 * - 只有「composer 可见 且 用户已表达输入意图」才可聚焦；其余一切状态（未表达意图 /
 *   决策中 / 载体消失）都不可聚焦，避免浮窗长期抢着下层 App 的输入焦点；
 * - 决策一旦落地，无论 composer 当时是否打开，都必须立刻回到不可聚焦，避免窗口
 *   在收起动画 / 载体切换期间继续持有输入焦点、遮挡下层 App 的返回手势。
 */
internal object ApprovalOverlayFocusPolicy {

    /**
     * @param composerActive 是否到了需要 IME 的输入意图时刻（见 [focusWanted]）。
     * @param decisionConsumed 决策是否已落地；true 时强制回到不可聚焦。
     * @return true 表示悬浮窗此刻应去掉 FLAG_NOT_FOCUSABLE 并使用 ADJUST_PAN。
     */
    fun shouldBeFocusable(composerActive: Boolean, decisionConsumed: Boolean): Boolean =
        composerActive && !decisionConsumed

    /**
     * 卡片的自由输入 composer 此刻是否可见。与 `AgentApprovalRequestCard` 的可见性判断
     * 共用同一口径（抽到这里以便纯 JVM 回归，避免两处漂移）：
     * - 危险卡 [danger]=true：要点「拒绝并说明」(composerOpen) 才展开；
     * - 询问卡 [danger]=false：只要 [allowFreeText] 就默认展开（这正是 S1 盲区的来源——
     *   询问卡的 composerOpen 恒为 false，若用 composerOpen 当焦点信号，询问卡 IME 永远起不来）。
     */
    fun composerVisible(danger: Boolean, allowFreeText: Boolean, composerOpen: Boolean): Boolean =
        allowFreeText && (composerOpen || !danger)

    /**
     * 悬浮窗此刻是否应该切到「可聚焦」。
     *
     * 关键取舍：不能让询问卡 composer 一可见就抢走下层 App 的输入焦点（会收掉别人正在用的
     * IME、返回键失效，最长持续到卡片超时）。所以「composer 可见」只是前提，还必须叠加
     * 用户明确的输入意图：
     * - 危险卡：点了「拒绝并说明」([composerOpen]=true) 即意图；
     * - 询问卡：composer 默认可见，但要点了输入区 ([inputRequested]=true) 才算意图。
     *
     * 两种卡由此走同一条确定性路径：用户动作 → 窗口可聚焦 → 延迟一帧 requestFocus + 弹 IME。
     *
     * @param composerVisible [composerVisible] 的结果。
     * @param danger 是否危险拦截卡。
     * @param composerOpen 危险卡是否点开了「拒绝并说明」。
     * @param inputRequested 询问卡用户是否点过 composer 输入区。
     */
    fun focusWanted(
        composerVisible: Boolean,
        danger: Boolean,
        composerOpen: Boolean,
        inputRequested: Boolean,
    ): Boolean = composerVisible && (composerOpen || (!danger && inputRequested))

    /**
     * 初始 / 还原态：不可聚焦。抽成常量语义，避免执行层散落魔法布尔。
     */
    const val INITIAL_FOCUSABLE = false
}
