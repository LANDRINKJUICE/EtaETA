package io.github.mangi.eta.ui.model

import io.github.mangi.eta.agent.runs.AgentRunSource
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * 任务中心纯展示逻辑的回归测试。
 *
 * 钉住三件用户能直接看见的事：
 * 1. 路由里的分区名解析失败时不能崩、也不能落到随机分区；
 * 2. 运行时长在秒/分/时交界处不能出现 0:60 或负数；
 * 3. token 计数必须带千位分隔（大数是这页的主要信息）。
 */
class TaskCenterFormattingTest {
    @Test
    fun tabNamesRoundTripAndFallBackSafely() {
        TaskCenterTab.entries.forEach { tab ->
            assertEquals(tab, TaskCenterTab.fromNameOrDefault(tab.name))
        }
        // 路由默认值是空串；null 与空白同样按默认分区处理。
        assertEquals(TaskCenterTab.APPROVALS, TaskCenterTab.fromNameOrDefault(""))
        assertEquals(TaskCenterTab.APPROVALS, TaskCenterTab.fromNameOrDefault(null))
        assertEquals(TaskCenterTab.APPROVALS, TaskCenterTab.fromNameOrDefault("   "))
        // 大小写与前后空白的容错（旧链接、手写深链）。
        assertEquals(TaskCenterTab.USAGE, TaskCenterTab.fromNameOrDefault(" usage "))
        assertEquals(TaskCenterTab.SCHEDULED, TaskCenterTab.fromNameOrDefault("scheduled"))
        // 未知名字退回默认分区，绝不抛异常。
        assertEquals(TaskCenterTab.APPROVALS, TaskCenterTab.fromNameOrDefault("nope"))
    }

    @Test
    fun elapsedUsesMinuteSecondBelowOneHour() {
        assertEquals("0:00", formatElapsed(0L))
        assertEquals("0:00", formatElapsed(999L))
        assertEquals("0:01", formatElapsed(1_000L))
        assertEquals("0:59", formatElapsed(59_999L))
        // 进位边界：59 秒 999 毫秒仍是 0:59，60 秒整才变成 1:00。
        assertEquals("1:00", formatElapsed(60_000L))
        assertEquals("59:59", formatElapsed(3_599_000L))
    }

    @Test
    fun elapsedSwitchesToHourFormatAtOneHour() {
        assertEquals("1:00:00", formatElapsed(3_600_000L))
        assertEquals("1:02:03", formatElapsed(3_723_000L))
        assertEquals("100:00:00", formatElapsed(360_000_000L))
    }

    @Test
    fun negativeElapsedIsClampedToZero() {
        // 设备时钟回拨时 startedAt 可能在未来，页面不能出现负时长。
        assertEquals("0:00", formatElapsed(-5_000L))
    }

    @Test
    fun tokensUseGroupingSeparator() {
        assertEquals("0", formatTokens(0L, Locale.US))
        assertEquals("999", formatTokens(999L, Locale.US))
        assertEquals("1,000", formatTokens(1_000L, Locale.US))
        assertEquals("1,234,567", formatTokens(1_234_567L, Locale.US))
        // 脏数据（负数）按 0 显示，避免出现 "-12,345 tokens"。
        assertEquals("0", formatTokens(-1L, Locale.US))
    }

    @Test
    fun onlyTopLevelNonChildRunsAreStoppable() {
        // 顶层运行可以停。
        AgentRunSource.entries.forEach { source ->
            assertEquals(source.name, source != AgentRunSource.SUBAGENT, isStoppableRun(source, isChild = false))
        }
        // 带父 run 的条目一律不能单独停（取消链在父运行上）。
        AgentRunSource.entries.forEach { source ->
            assertFalse(source.name, isStoppableRun(source, isChild = true))
        }
    }
}
