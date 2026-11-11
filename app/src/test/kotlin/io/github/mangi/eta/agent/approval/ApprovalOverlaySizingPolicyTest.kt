package io.github.mangi.eta.agent.approval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BUG 1（卡片显示期间整屏划不动）窗口高度收敛的纯 JVM 回归。
 *
 * 钉住不变量：窗口高度必须收敛到卡片内容实测高度且 clamp 到上限，绝不回到近整屏；
 * 只有正数、且与已应用值不同的目标高度才会被写回；0 / 负哨兵值（WRAP_CONTENT /
 * MATCH_PARENT 等）绝不写回，否则 FLAG_NOT_TOUCH_MODAL 又没有「窗口外」可穿透。
 */
class ApprovalOverlaySizingPolicyTest {

    @Test
    fun unsetSentinelMatchesWrapContent() {
        // 与 WindowManager.LayoutParams.WRAP_CONTENT 对齐（该常量为 -2）。
        assertEquals(-2, ApprovalOverlaySizingPolicy.HEIGHT_UNSET)
    }

    // ── targetHeightPx：clamp 到上限，绝不把整屏测量值直接写回窗口 ──

    @Test
    fun targetHeightUsesMeasuredWhenBelowMax() {
        assertEquals(640, ApprovalOverlaySizingPolicy.targetHeightPx(640, 1400))
    }

    @Test
    fun targetHeightClampsFullScreenMeasurementToMax() {
        // 系统 overlay 在 EXACTLY 整屏约束下回报近整屏高度，必须被 clamp，否则 BUG 依旧。
        assertEquals(1400, ApprovalOverlaySizingPolicy.targetHeightPx(2600, 1400))
        assertEquals(1400, ApprovalOverlaySizingPolicy.targetHeightPx(99999, 1400))
    }

    @Test
    fun targetHeightAtExactMaxBoundary() {
        // measured == max 的边界：必须原样返回 max，不能被误判为 UNSET。
        assertEquals(1400, ApprovalOverlaySizingPolicy.targetHeightPx(1400, 1400))
    }

    @Test
    fun targetHeightNeverBelowOnePixel() {
        // coerceIn(1, max) 下界：合法正实测值永远 >=1，不会得到 0/负窗口高度。
        assertTrue(ApprovalOverlaySizingPolicy.targetHeightPx(1, 1400) >= 1)
        assertEquals(1, ApprovalOverlaySizingPolicy.targetHeightPx(1, 1400))
    }

    @Test
    fun targetHeightRejectsNonPositiveMeasurement() {
        for (measured in listOf(0, -1, -2, -100)) {
            assertEquals(
                "measured=$measured 不应得到合法目标高度",
                ApprovalOverlaySizingPolicy.HEIGHT_UNSET,
                ApprovalOverlaySizingPolicy.targetHeightPx(measured, 1400),
            )
        }
    }

    @Test
    fun targetHeightRejectsNonPositiveMax() {
        for (max in listOf(0, -1, -2)) {
            assertEquals(
                ApprovalOverlaySizingPolicy.HEIGHT_UNSET,
                ApprovalOverlaySizingPolicy.targetHeightPx(640, max),
            )
        }
    }

    // ── shouldApplyHeight：去抖 + 只写正数 ──

    @Test
    fun firstPositiveTargetIsApplied() {
        assertTrue(
            "首次拿到正的目标高度必须收敛窗口，否则窗口仍停在近整屏 WRAP_CONTENT",
            ApprovalOverlaySizingPolicy.shouldApplyHeight(
                appliedHeightPx = ApprovalOverlaySizingPolicy.HEIGHT_UNSET,
                targetHeightPx = 640,
            ),
        )
    }

    @Test
    fun changedHeightIsApplied() {
        // composer 展开 / 命令文字变化导致内容变高，窗口必须跟着长，否则按钮被裁。
        assertTrue(ApprovalOverlaySizingPolicy.shouldApplyHeight(640, 880))
        // 内容变矮（composer 收起）也要收敛，否则窗口下方留透明吞触摸区。
        assertTrue(ApprovalOverlaySizingPolicy.shouldApplyHeight(880, 640))
    }

    @Test
    fun sameHeightIsSuppressed() {
        assertFalse(
            "同尺寸不重复 updateViewLayout，避免无谓重新布局与测量回环",
            ApprovalOverlaySizingPolicy.shouldApplyHeight(640, 640),
        )
    }

    @Test
    fun nonPositiveTargetsAreNeverApplied() {
        // HEIGHT_UNSET / 0 / 负哨兵绝不写回。
        for (applied in listOf(ApprovalOverlaySizingPolicy.HEIGHT_UNSET, 640)) {
            for (target in listOf(ApprovalOverlaySizingPolicy.HEIGHT_UNSET, 0, -1, -100)) {
                assertFalse(
                    "applied=$applied target=$target 不应写回",
                    ApprovalOverlaySizingPolicy.shouldApplyHeight(applied, target),
                )
            }
        }
    }

    @Test
    fun fullChainFullScreenMeasurementConvergesToMax() {
        // 端到端：整屏实测 -> clamp 成上限 -> 首次必须写回。
        val target = ApprovalOverlaySizingPolicy.targetHeightPx(2634, 1400)
        assertEquals(1400, target)
        assertTrue(
            ApprovalOverlaySizingPolicy.shouldApplyHeight(
                ApprovalOverlaySizingPolicy.HEIGHT_UNSET,
                target,
            ),
        )
    }
}
