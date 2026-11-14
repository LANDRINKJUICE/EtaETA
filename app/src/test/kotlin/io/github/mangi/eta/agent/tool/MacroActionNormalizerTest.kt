package io.github.mangi.eta.agent.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 录制动作归一化的回归测试。
 *
 * 这些用例对应"录出来的宏能不能可靠回放"：去抖、合并微滑、截断长间隔、密码输入必须丢弃、
 * 动作总数必须落在 gesture_batch 的上限内，且输出字段能被 gesture_batch 直接消费。
 */
class MacroActionNormalizerTest {
    private val options = MacroNormalizeOptions(screenWidth = 1080, screenHeight = 2400)

    private fun tap(at: Long, x: Int, y: Int, text: String = "", desc: String = "") = RecordedAction(
        kind = RecordedKind.TAP,
        atMillis = at,
        x = x,
        y = y,
        targetText = text,
        targetDesc = desc,
    )

    @Test
    fun `tap keeps target text for review and stays screen scoped`() {
        val result = MacroActionNormalizer.normalize(
            listOf(tap(1_000L, 540, 1_200, text = "发送")),
            options,
        )
        assertEquals(1, result.actions.size)
        val json = result.actions.first().toJson()
        assertEquals("tap", json.getString("type"))
        assertEquals(540, json.getInt("x"))
        assertEquals(1200, json.getInt("y"))
        assertEquals("screen", json.getString("coordinate_space"))
        assertEquals("发送", json.getString("target_text"))
    }

    @Test
    fun `duplicate taps inside the debounce window collapse into one`() {
        val result = MacroActionNormalizer.normalize(
            listOf(
                tap(1_000L, 540, 1_200),
                tap(1_060L, 542, 1_203),
                tap(1_120L, 539, 1_198),
            ),
            options,
        )
        assertEquals(1, result.actions.size)
        assertEquals(2, result.stats.debounced)
    }

    @Test
    fun `taps far apart in time are both kept`() {
        val result = MacroActionNormalizer.normalize(
            listOf(tap(1_000L, 540, 1_200), tap(2_000L, 540, 1_200)),
            options,
        )
        assertEquals(2, result.actions.count { it.type == "tap" })
        assertEquals(0, result.stats.debounced)
    }

    @Test
    fun `consecutive micro swipes merge into one drag`() {
        val result = MacroActionNormalizer.normalize(
            listOf(
                RecordedAction(kind = RecordedKind.SWIPE, atMillis = 1_000L, x = 540, y = 1_800, deltaX = 0, deltaY = -60),
                RecordedAction(kind = RecordedKind.SWIPE, atMillis = 1_120L, x = 540, y = 1_740, deltaX = 0, deltaY = -60),
                RecordedAction(kind = RecordedKind.SWIPE, atMillis = 1_240L, x = 540, y = 1_680, deltaX = 0, deltaY = -60),
            ),
            options,
        )
        val swipes = result.actions.filter { it.type == "swipe" }
        assertEquals(1, swipes.size)
        assertEquals(2, result.stats.mergedSwipes)
        assertEquals(1_800, swipes.first().y1)
        assertEquals(1_620, swipes.first().y2)
    }

    @Test
    fun `opposite direction swipes are not merged`() {
        val result = MacroActionNormalizer.normalize(
            listOf(
                RecordedAction(kind = RecordedKind.SWIPE, atMillis = 1_000L, x = 540, y = 1_800, deltaY = -60),
                RecordedAction(kind = RecordedKind.SWIPE, atMillis = 1_100L, x = 540, y = 1_740, deltaY = 60),
            ),
            options,
        )
        assertEquals(2, result.actions.count { it.type == "swipe" })
        assertEquals(0, result.stats.mergedSwipes)
    }

    @Test
    fun `positive scroll delta means content moved up so the finger swipes up`() {
        // 审查 M5：AOSP 里 scrollDeltaY 为正 = 内容上移 = 手指上滑（y 变小）。
        val result = MacroActionNormalizer.normalize(
            listOf(
                RecordedAction(
                    kind = RecordedKind.SCROLL,
                    atMillis = 1_000L,
                    x = 540,
                    y = 1_200,
                    deltaY = 300,
                    windowId = 7,
                ),
            ),
            options,
        )
        val swipe = result.actions.first { it.type == "swipe" }
        assertEquals(540, swipe.x1)
        assertEquals(540, swipe.x2)
        assertTrue("finger must move up", swipe.y2!! < swipe.y1!!)
        assertTrue(swipe.y1!! in 0 until options.screenHeight)
        assertTrue(swipe.y2!! in 0 until options.screenHeight)
    }

    @Test
    fun `negative scroll delta means the finger swipes down`() {
        val result = MacroActionNormalizer.normalize(
            listOf(
                RecordedAction(
                    kind = RecordedKind.SCROLL,
                    atMillis = 1_000L,
                    x = 540,
                    y = 1_200,
                    deltaY = -300,
                    windowId = 7,
                ),
            ),
            options,
        )
        val swipe = result.actions.first { it.type == "swipe" }
        assertTrue("finger must move down", swipe.y2!! > swipe.y1!!)
    }

    @Test
    fun `one gesture produces one swipe instead of stacking every scroll event`() {
        // 审查 M6：一次快速长滑会被系统切成多个 TYPE_VIEW_SCROLLED；只能产出一条 swipe。
        val result = MacroActionNormalizer.normalize(
            listOf(
                RecordedAction(kind = RecordedKind.SCROLL, atMillis = 1_000L, x = 540, y = 1_200, deltaY = 120, windowId = 3),
                RecordedAction(kind = RecordedKind.SCROLL, atMillis = 1_100L, x = 540, y = 1_200, deltaY = 120, windowId = 3),
                RecordedAction(kind = RecordedKind.SCROLL, atMillis = 1_200L, x = 540, y = 1_200, deltaY = 120, windowId = 3),
                RecordedAction(kind = RecordedKind.SCROLL, atMillis = 1_300L, x = 540, y = 1_200, deltaY = 120, windowId = 3),
            ),
            options,
        )
        val swipes = result.actions.filter { it.type == "swipe" }
        assertEquals(1, swipes.size)
        assertEquals(3, result.stats.mergedScrolls)
        // 位移 = 累计 480px（未到 600 上限），方向向上。
        assertEquals(480, swipes.first().y1!! - swipes.first().y2!!)
    }

    @Test
    fun `scroll gesture travel is capped so replay does not swipe across the whole screen`() {
        val events = (0 until 10).map { index ->
            RecordedAction(
                kind = RecordedKind.SCROLL,
                atMillis = 1_000L + index * 50L,
                x = 540,
                y = 1_200,
                deltaY = 200,
                windowId = 3,
            )
        }
        val result = MacroActionNormalizer.normalize(events, options)
        val swipe = result.actions.first { it.type == "swipe" }
        assertEquals(options.maxScrollSwipePx, swipe.y1!! - swipe.y2!!)
    }

    @Test
    fun `scroll events from different windows are not merged`() {
        val result = MacroActionNormalizer.normalize(
            listOf(
                RecordedAction(kind = RecordedKind.SCROLL, atMillis = 1_000L, x = 540, y = 1_200, deltaY = 100, windowId = 1),
                RecordedAction(kind = RecordedKind.SCROLL, atMillis = 1_100L, x = 540, y = 1_200, deltaY = 100, windowId = 2),
            ),
            options,
        )
        assertEquals(2, result.actions.count { it.type == "swipe" })
        assertEquals(0, result.stats.mergedScrolls)
    }

    @Test
    fun `scroll events separated by a long pause are separate gestures`() {
        val result = MacroActionNormalizer.normalize(
            listOf(
                RecordedAction(kind = RecordedKind.SCROLL, atMillis = 1_000L, x = 540, y = 1_200, deltaY = 100, windowId = 1),
                RecordedAction(kind = RecordedKind.SCROLL, atMillis = 3_000L, x = 540, y = 1_200, deltaY = 100, windowId = 1),
            ),
            options,
        )
        assertEquals(2, result.actions.count { it.type == "swipe" })
    }

    @Test
    fun `long gaps are clamped to the delay ceiling`() {
        val result = MacroActionNormalizer.normalize(
            listOf(tap(1_000L, 100, 100), tap(60_000L, 800, 2_000)),
            options,
        )
        val waits = result.actions.filter { it.type == "wait_ms" }
        assertEquals(1, waits.size)
        assertEquals(options.maxDelayMs.toInt(), waits.first().ms)
        assertEquals(1, result.stats.clampedDelays)
    }

    @Test
    fun `short gaps do not produce wait actions`() {
        val result = MacroActionNormalizer.normalize(
            listOf(tap(1_000L, 100, 100), tap(1_050L, 800, 2_000)),
            options,
        )
        assertTrue(result.actions.none { it.type == "wait_ms" })
    }

    @Test
    fun `password inputs are dropped and counted`() {
        val result = MacroActionNormalizer.normalize(
            listOf(
                tap(1_000L, 540, 900, text = "密码"),
                RecordedAction(kind = RecordedKind.TEXT, atMillis = 1_500L, text = "", sensitive = true),
                RecordedAction(kind = RecordedKind.TEXT, atMillis = 1_600L, text = "hunter2", sensitive = true),
            ),
            options,
        )
        assertEquals(1, result.actions.size)
        assertEquals(2, result.stats.sensitiveSkipped)
        assertTrue(result.actions.none { it.type == "type_text" })
    }

    @Test
    fun `appended text on the same target merges into one action`() {
        val result = MacroActionNormalizer.normalize(
            listOf(
                RecordedAction(kind = RecordedKind.TEXT, atMillis = 1_000L, text = "hel", targetDesc = "search"),
                RecordedAction(kind = RecordedKind.TEXT, atMillis = 1_300L, text = "lo", targetDesc = "search"),
            ),
            options,
        )
        val typed = result.actions.filter { it.type == "type_text" }
        assertEquals(1, typed.size)
        assertEquals("hello", typed.first().text)
        assertEquals(1, result.stats.mergedTexts)
    }

    @Test
    fun `text on different targets is not merged`() {
        val result = MacroActionNormalizer.normalize(
            listOf(
                RecordedAction(kind = RecordedKind.TEXT, atMillis = 1_000L, text = "a", targetDesc = "search"),
                RecordedAction(kind = RecordedKind.TEXT, atMillis = 1_200L, text = "b", targetDesc = "comment"),
            ),
            options,
        )
        assertEquals(2, result.actions.count { it.type == "type_text" })
    }

    @Test
    fun `out of bounds taps are dropped`() {
        val result = MacroActionNormalizer.normalize(
            listOf(tap(1_000L, 5_000, 100), tap(2_000L, -10, 100), tap(3_000L, 500, 500)),
            options,
        )
        val taps = result.actions.filter { it.type == "tap" }
        assertEquals(1, taps.size)
        assertEquals(500, taps.first().x)
    }

    @Test
    fun `action count is capped at the gesture batch limit`() {
        val recorded = (0 until 80).map { index ->
            tap(index * 1_000L, 100 + index, 200 + index)
        }
        val result = MacroActionNormalizer.normalize(recorded, options.copy(maxActions = 10))
        assertTrue(result.actions.size <= 10)
        assertTrue(result.stats.truncated > 0)
    }

    @Test
    fun `supported keys are recorded and unknown keys are ignored`() {
        val result = MacroActionNormalizer.normalize(
            listOf(
                RecordedAction(kind = RecordedKind.KEY, atMillis = 1_000L, button = "back"),
                RecordedAction(kind = RecordedKind.KEY, atMillis = 2_000L, button = "F13"),
                RecordedAction(kind = RecordedKind.KEY, atMillis = 3_000L, button = "ENTER"),
            ),
            options,
        )
        val keys = result.actions.filter { it.type == "press_key" }
        assertEquals(2, keys.size)
        assertEquals("BACK", keys.first().button)
        assertEquals("ENTER", keys.last().button)
    }

    @Test
    fun `trailing wait is removed`() {
        val result = MacroActionNormalizer.normalize(listOf(tap(1_000L, 100, 100)), options)
        assertFalse(result.actions.last().type == "wait_ms")
    }

    @Test
    fun `long press is preserved as its own action type`() {
        val result = MacroActionNormalizer.normalize(
            listOf(
                RecordedAction(
                    kind = RecordedKind.LONG_PRESS,
                    atMillis = 1_000L,
                    x = 300,
                    y = 400,
                    targetText = "复制",
                ),
            ),
            options,
        )
        assertEquals("long_press", result.actions.single().type)
        assertEquals("复制", result.actions.single().toJson().getString("target_text"))
    }

    @Test
    fun `empty recording produces no actions`() {
        val result = MacroActionNormalizer.normalize(emptyList(), options)
        assertTrue(result.actions.isEmpty())
        assertEquals(0, result.stats.recorded)
        assertEquals(0, result.stats.droppedCount)
    }

    @Test
    fun `whitespace only text is ignored`() {
        val result = MacroActionNormalizer.normalize(
            listOf(RecordedAction(kind = RecordedKind.TEXT, atMillis = 1_000L, text = "")),
            options,
        )
        assertNull(result.actions.firstOrNull())
    }
}
