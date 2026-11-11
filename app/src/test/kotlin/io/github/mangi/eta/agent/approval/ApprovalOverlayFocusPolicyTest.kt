package io.github.mangi.eta.agent.approval

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 后台悬浮审批卡「自由输入 composer ↂ 窗口焦点」的纯 JVM 回归。
 *
 * 钉住 BUG 修复的核心不变量：默认 FLAG_NOT_FOCUSABLE 的浮窗里，BasicTextField 拿不到
 * 焦点、IME 弹不出来；只有 composer 展开且决策尚未落地时才允许窗口临时可聚焦，其余
 * 一切状态（初始 / 收起 / 决策落地 / 卡片消失）都必须回到不可聚焦，避免吞掉下层 App
 * 的返回手势或残留输入焦点。
 */
class ApprovalOverlayFocusPolicyTest {

    @Test
    fun initialStateIsNotFocusable() {
        assertFalse(ApprovalOverlayFocusPolicy.INITIAL_FOCUSABLE)
        assertFalse(
            ApprovalOverlayFocusPolicy.shouldBeFocusable(
                composerActive = false,
                decisionConsumed = false,
            ),
        )
    }

    @Test
    fun composerOpenBecomesFocusable() {
        assertTrue(
            "composer 展开必须让窗口可聚焦，否则 IME 弹不出来（用户实测 BUG）",
            ApprovalOverlayFocusPolicy.shouldBeFocusable(
                composerActive = true,
                decisionConsumed = false,
            ),
        )
    }

    @Test
    fun composerClosedGoesBackToNotFocusable() {
        assertFalse(
            ApprovalOverlayFocusPolicy.shouldBeFocusable(
                composerActive = false,
                decisionConsumed = false,
            ),
        )
    }

    @Test
    fun decisionConsumedForcesNotFocusableEvenIfComposerStillOpen() {
        // 决策落地（无论是否走 composer 的 Send）都必须立刻还原焦点，
        // 不能因为 composerActive 仍为 true 而让收起动画期间的窗口继续持有输入焦点。
        assertFalse(
            ApprovalOverlayFocusPolicy.shouldBeFocusable(
                composerActive = true,
                decisionConsumed = true,
            ),
        )
        assertFalse(
            ApprovalOverlayFocusPolicy.shouldBeFocusable(
                composerActive = false,
                decisionConsumed = true,
            ),
        )
    }

    @Test
    fun truthTableIsExactlyComposerActiveAndNotConsumed() {
        val expected = { active: Boolean, consumed: Boolean -> active && !consumed }
        for (active in listOf(false, true)) {
            for (consumed in listOf(false, true)) {
                org.junit.Assert.assertEquals(
                    "composerActive=$active decisionConsumed=$consumed",
                    expected(active, consumed),
                    ApprovalOverlayFocusPolicy.shouldBeFocusable(active, consumed),
                )
            }
        }
    }

    // ── S1 回归：composerVisible 必须覆盖询问卡（!danger）默认展开，否则询问卡 IME 永远起不来 ──

    @Test
    fun dangerCardComposerOnlyVisibleWhenDenyWithFeedbackOpened() {
        // 危险卡：未点「拒绝并说明」时不展开；点开后展开。
        assertFalse(ApprovalOverlayFocusPolicy.composerVisible(danger = true, allowFreeText = true, composerOpen = false))
        assertTrue(ApprovalOverlayFocusPolicy.composerVisible(danger = true, allowFreeText = true, composerOpen = true))
    }

    @Test
    fun askCardComposerVisibleByDefaultWhenFreeTextAllowed() {
        // 询问卡：composerOpen 恒为 false，但 composer 默认可见 —— 这正是 S1 漏掉的场景。
        assertTrue(
            "询问卡 allowFreeText 时 composer 必须默认可见，否则浮窗里自由输入拿不到焦点",
            ApprovalOverlayFocusPolicy.composerVisible(danger = false, allowFreeText = true, composerOpen = false),
        )
        assertFalse(ApprovalOverlayFocusPolicy.composerVisible(danger = false, allowFreeText = false, composerOpen = false))
    }

    @Test
    fun noFreeTextMeansNoComposerForEitherCard() {
        assertFalse(ApprovalOverlayFocusPolicy.composerVisible(danger = true, allowFreeText = false, composerOpen = true))
        assertFalse(ApprovalOverlayFocusPolicy.composerVisible(danger = false, allowFreeText = false, composerOpen = false))
    }

    @Test
    fun askCardVisibleComposerDrivesFocusableWindow() {
        // 串起完整链路：询问卡 composer 可见 -> 用户点输入区 -> 窗口应可聚焦 -> IME 才能弹。
        val visible = ApprovalOverlayFocusPolicy.composerVisible(danger = false, allowFreeText = true, composerOpen = false)
        assertTrue(visible)
        val wanted = ApprovalOverlayFocusPolicy.focusWanted(visible, danger = false, composerOpen = false, inputRequested = true)
        assertTrue(wanted)
        assertTrue(ApprovalOverlayFocusPolicy.shouldBeFocusable(composerActive = wanted, decisionConsumed = false))
    }

    // ── focusWanted：不能因 composer 默认可见就抢下层焦点；必须有明确输入意图 ──

    @Test
    fun askCardNotFocusableUntilUserTapsInputArea() {
        val visible = true // 询问卡 composer 默认可见
        assertFalse(
            "询问卡一弹出就可聚焦会收掉下层 App 正在用的 IME，必须等用户点输入区",
            ApprovalOverlayFocusPolicy.focusWanted(visible, danger = false, composerOpen = false, inputRequested = false),
        )
        assertTrue(
            ApprovalOverlayFocusPolicy.focusWanted(visible, danger = false, composerOpen = false, inputRequested = true),
        )
    }

    @Test
    fun dangerCardFocusableWhenDenyWithFeedbackOpenedEvenWithoutTap() {
        // 危险卡点「拒绝并说明」本身就是输入意图，无需再点输入区。
        val visible = true
        assertTrue(
            ApprovalOverlayFocusPolicy.focusWanted(visible, danger = true, composerOpen = true, inputRequested = false),
        )
        assertFalse(
            ApprovalOverlayFocusPolicy.focusWanted(visible, danger = true, composerOpen = false, inputRequested = false),
        )
    }

    @Test
    fun invisibleComposerNeverWantsFocus() {
        assertFalse(
            ApprovalOverlayFocusPolicy.focusWanted(
                composerVisible = false, danger = false, composerOpen = false, inputRequested = true,
            ),
        )
        assertFalse(
            ApprovalOverlayFocusPolicy.focusWanted(
                composerVisible = false, danger = true, composerOpen = true, inputRequested = false,
            ),
        )
    }
}
