package io.github.mangi.eta.agent.approval

/**
 * 后台悬浮审批卡「窗口高度收敛」的纯逻辑裁决，不引用任何 android.*，可在纯 JVM 上回归。
 *
 * 背景（用户实测 BUG 1）：悬浮窗用 MATCH_PARENT × WRAP_CONTENT + FLAG_LAYOUT_IN_SCREEN，
 * 系统浮窗会被 WMS 撑成近整屏（真机 dumpsys：contentSize≈整屏）；FLAG_NOT_TOUCH_MODAL 只在
 * 「窗口边界之外」允许触摸穿透，整屏窗口没有窗口外区域，于是透明浮窗吞掉卡片外的全部触摸，
 * 卡片显示期间整屏划不动（系统边缘手势仍可用，说明不是 ANR / 触摸全失效）。
 *
 * 修法：把窗口 params.height 从 WRAP_CONTENT 收敛成卡片内容的实测高度，卡片下方重新变成
 * 「窗口外」而穿透。内容高度随 composer 展开／命令文字变化而变化，onSizeChanged 持续驱动；
 * 本对象只负责「此次实测值应收敛成哪个窗口高度、是否需要写回」这一纯判定，实际
 * updateViewLayout 由 [ApprovalAttentionOverlay] 执行（它负责所属窗口实例校验、失败回滚）。
 *
 * 防御：系统 overlay 窗口在 EXACTLY 整屏测量约束下、或 verticalScroll 给子节点无限高约束时，
 * onSizeChanged 可能回报整屏 / 自然内容高度；若照单收敛，窗口仍近整屏、BUG 依旧。因此窗口
 * 高度一律 clamp 到一个上限（卡片可见上限与屏幕可用高度的较小值，由调用方换算后通过
 * targetHeightPx 的 maxHeightPx 参数传入），保证窗口 frame 绝不回到整屏。
 */
internal object ApprovalOverlaySizingPolicy {

    /**
     * 初始哨兵：窗口尚未收敛到内容高度（沿用 `WRAP_CONTENT = -2` 的语义）。
     * 数值与 `WindowManager.LayoutParams.WRAP_CONTENT` 一致，但这里刻意保持 android-free，
     * 让该文件能进纯 JVM 测试清单。
     */
    const val HEIGHT_UNSET: Int = -2

    /**
     * 把卡片实测高度换算成「应当写入窗口 params.height 的目标高度」：
     * - 实测值非正（0 或负哨兵，如 MATCH_PARENT/WRAP_CONTENT）→ [HEIGHT_UNSET]（不写回）；
     * - 上限非正 → [HEIGHT_UNSET]（拿不到合法上限时不收敛，宁可维持原状也不乱设）；
     * - 否则 clamp 到 `[1, maxHeightPx]`，确保窗口 frame 永远不会回到近整屏。
     *
     * @param measuredHeightPx 卡片内容本次实测高度（px，verticalScroll 内层回报的自然高度，
     *   可能超过卡片可见上限）。
     * @param maxHeightPx 窗口允许的最大高度（px，调用方由卡片可见上限换算）。
     */
    fun targetHeightPx(measuredHeightPx: Int, maxHeightPx: Int): Int =
        if (measuredHeightPx <= 0 || maxHeightPx <= 0) {
            HEIGHT_UNSET
        } else {
            measuredHeightPx.coerceIn(1, maxHeightPx)
        }

    /**
     * 是否把 [targetHeightPx] 应用为窗口 height。
     * - 必须为正：[HEIGHT_UNSET]（含 0 / 负哨兵）不是合法的卡片内容高度，写回去窗口会重新
     *   被撑成整屏、卡片外的触摸又会被吞；
     * - 与已应用值不同才应用：去抖，避免同尺寸反复 updateViewLayout 触发无谓重新布局，
     *   也避免「改 height → WMS 重新测量 → 又回调同一高度」形成回环。
     *
     * @param appliedHeightPx 上一次已成功应用到窗口的高度（首次为 [HEIGHT_UNSET]）。
     * @param targetHeightPx [targetHeightPx] 的结果。
     */
    fun shouldApplyHeight(appliedHeightPx: Int, targetHeightPx: Int): Boolean =
        targetHeightPx > 0 && targetHeightPx != appliedHeightPx
}
