package io.github.mangi.eta.agent.schedule

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 调度规则计算的回归测试。
 *
 * 这些用例钉住用户真正能感知的语义：每天/每周在本地时区的固定时刻触发、跨天跨周正确前进、
 * 间隔任务按"上次计划时间"推进而不是随执行耗时漂移、一次性任务过期后不再排期。
 */
class TaskScheduleCalculatorTest {
    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    private fun at(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
        zoneId: ZoneId = zone,
    ): Long = ZonedDateTime.of(LocalDateTime.of(year, month, day, hour, minute), zoneId)
        .toInstant()
        .toEpochMilli()

    @Test
    fun `daily keeps today when the time is still ahead`() {
        val schedule = TaskSchedule(kind = ScheduleKind.DAILY, hour = 8, minute = 30)
        val now = at(2026, 5, 10, 7, 0)
        val next = TaskScheduleCalculator.nextAfter(schedule, now, zone)
        assertEquals(at(2026, 5, 10, 8, 30), next)
    }

    @Test
    fun `daily rolls to tomorrow when the time already passed`() {
        val schedule = TaskSchedule(kind = ScheduleKind.DAILY, hour = 8, minute = 30)
        val now = at(2026, 5, 10, 9, 0)
        assertEquals(at(2026, 5, 11, 8, 30), TaskScheduleCalculator.nextAfter(schedule, now, zone))
    }

    @Test
    fun `daily is strictly after the reference instant`() {
        val schedule = TaskSchedule(kind = ScheduleKind.DAILY, hour = 8, minute = 30)
        val exactly = at(2026, 5, 10, 8, 30)
        // 与触发时刻完全相等时必须排到下一天，否则同一时刻会被反复命中。
        assertEquals(at(2026, 5, 11, 8, 30), TaskScheduleCalculator.nextAfter(schedule, exactly, zone))
    }

    @Test
    fun `weekly picks the next matching weekday`() {
        // 2026-05-10 是周日（7），规则只允许周一(1)与周三(3)。
        val schedule = TaskSchedule(kind = ScheduleKind.WEEKLY, hour = 9, minute = 0, daysOfWeek = setOf(1, 3))
        val now = at(2026, 5, 10, 12, 0)
        val next = TaskScheduleCalculator.nextAfter(schedule, now, zone)
        assertEquals(at(2026, 5, 11, 9, 0), next)
    }

    @Test
    fun `weekly skips to next week after the last window`() {
        val schedule = TaskSchedule(kind = ScheduleKind.WEEKLY, hour = 9, minute = 0, daysOfWeek = setOf(1))
        // 周一 10:00 已经过了当天的 09:00。
        val now = at(2026, 5, 11, 10, 0)
        val next = TaskScheduleCalculator.nextAfter(schedule, now, zone)
        assertEquals(at(2026, 5, 18, 9, 0), next)
    }

    @Test
    fun `weekly with empty days has no trigger`() {
        val schedule = TaskSchedule(kind = ScheduleKind.WEEKLY, hour = 9, minute = 0, daysOfWeek = emptySet())
        assertNull(TaskScheduleCalculator.nextAfter(schedule, at(2026, 5, 10, 0, 0), zone))
    }

    @Test
    fun `once in the future triggers at the exact time`() {
        val target = at(2026, 6, 1, 20, 0)
        val schedule = TaskSchedule(kind = ScheduleKind.ONCE, atMillis = target)
        assertEquals(target, TaskScheduleCalculator.nextAfter(schedule, at(2026, 5, 10, 0, 0), zone))
    }

    @Test
    fun `once in the past never triggers again`() {
        val target = at(2026, 5, 1, 20, 0)
        val schedule = TaskSchedule(kind = ScheduleKind.ONCE, atMillis = target)
        assertNull(TaskScheduleCalculator.nextAfter(schedule, at(2026, 5, 10, 0, 0), zone))
    }

    @Test
    fun `interval advances from the previous planned trigger, not from now`() {
        val schedule = TaskSchedule(kind = ScheduleKind.INTERVAL, intervalMinutes = 60)
        val previous = at(2026, 5, 10, 10, 0)
        // 实际执行到 10:05 才结束，下一轮仍应落在 11:00。
        val now = at(2026, 5, 10, 10, 5)
        assertEquals(at(2026, 5, 10, 11, 0), TaskScheduleCalculator.nextRun(schedule, previous, now, zone))
    }

    @Test
    fun `interval catches up to the nearest future slot after a long gap`() {
        val schedule = TaskSchedule(kind = ScheduleKind.INTERVAL, intervalMinutes = 30)
        val previous = at(2026, 5, 10, 8, 0)
        val now = at(2026, 5, 10, 10, 10)
        val next = TaskScheduleCalculator.nextRun(schedule, previous, now, zone)
        assertEquals(at(2026, 5, 10, 10, 30), next)
        assertTrue(next!! > now)
    }

    @Test
    fun `interval after a very long gap uses arithmetic, not a step loop`() {
        // 审查 M1：旧的 1000 步追赶循环在"一分钟间隔 + 关机一个月"时会静默判死（返回 null）。
        val schedule = TaskSchedule(kind = ScheduleKind.INTERVAL, intervalMinutes = 1)
        val anchor = at(2026, 1, 1, 0, 0)
        val now = at(2026, 2, 1, 0, 0) // 31 天后，远超 1000 分钟
        val next = TaskScheduleCalculator.nextRun(schedule, anchor, now, zone)
        assertNotNull(next)
        // 结果必须严格晚于 now，并且仍与 anchor 对齐（整分钟步长的整数倍）。
        assertTrue(next!! > now)
        assertEquals(0L, (next - anchor) % (60_000L * 1))
        assertEquals(60_000L, next - now)
    }

    @Test
    fun `interval lands exactly on the next step when now is aligned`() {
        val schedule = TaskSchedule(kind = ScheduleKind.INTERVAL, intervalMinutes = 15)
        val anchor = at(2026, 5, 10, 8, 0)
        // now 正好等于 anchor + 4 步：结果必须是 anchor + 5 步，而不是重复返回 now。
        val now = at(2026, 5, 10, 9, 0)
        assertEquals(at(2026, 5, 10, 9, 15), TaskScheduleCalculator.nextRun(schedule, anchor, now, zone))
    }

    @Test
    fun `interval with a future anchor still returns the nearest aligned future slot`() {
        // 时钟被往回调：anchor 在 10:00，now 是 09:00，步长 30 分钟 → 最近的对齐时刻是 09:30。
        // 关键约束不是"等于 anchor"，而是"严格晚于 now 且与 anchor 对齐"，否则时钟回拨会让任务卡住。
        val schedule = TaskSchedule(kind = ScheduleKind.INTERVAL, intervalMinutes = 30)
        val anchor = at(2026, 5, 10, 10, 0)
        val now = at(2026, 5, 10, 9, 0)
        val next = TaskScheduleCalculator.nextRun(schedule, anchor, now, zone)
        assertEquals(at(2026, 5, 10, 9, 30), next)
        assertTrue(next!! > now)
        assertEquals(0L, (anchor - next) % (30L * 60_000L))
    }

    @Test
    fun `interval without a previous trigger starts one step after now`() {
        val schedule = TaskSchedule(kind = ScheduleKind.INTERVAL, intervalMinutes = 15)
        val now = at(2026, 5, 10, 10, 3)
        assertEquals(at(2026, 5, 10, 10, 18), TaskScheduleCalculator.nextRun(schedule, 0L, now, zone))
    }

    @Test
    fun `daily across daylight saving boundary stays at the same wall clock time`() {
        // 纽约 2026-03-08 02:30 不存在（春季前跳）；规则应落在可行的墙钟时刻而不是崩溃。
        val newYork = ZoneId.of("America/New_York")
        val schedule = TaskSchedule(kind = ScheduleKind.DAILY, hour = 2, minute = 30)
        val now = ZonedDateTime.of(LocalDateTime.of(2026, 3, 7, 12, 0), newYork).toInstant().toEpochMilli()
        val next = TaskScheduleCalculator.nextAfter(schedule, now, newYork)
        assertNotNull(next)
        val local = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(next!!), newYork)
        // 02:30 在跳表当天不存在，java.time 会顺延到可行的墙钟时刻（03:30），不能崩溃也不能跳到别的日子。
        assertEquals(8, local.dayOfMonth)
        assertTrue(local.hour in 2..3)
        assertEquals(30, local.minute)
    }

    @Test
    fun `validator rejects out of range fields`() {
        assertEquals(
            TaskScheduleValidator.INVALID_TIME_OF_DAY,
            TaskScheduleValidator.validate(TaskSchedule(kind = ScheduleKind.DAILY, hour = 24, minute = 0)),
        )
        assertEquals(
            TaskScheduleValidator.INVALID_DAYS_OF_WEEK,
            TaskScheduleValidator.validate(TaskSchedule(kind = ScheduleKind.WEEKLY, hour = 8, minute = 0)),
        )
        assertEquals(
            TaskScheduleValidator.INVALID_INTERVAL,
            TaskScheduleValidator.validate(TaskSchedule(kind = ScheduleKind.INTERVAL, intervalMinutes = 0)),
        )
        assertNull(
            TaskScheduleValidator.validate(TaskSchedule(kind = ScheduleKind.ONCE, atMillis = 1L)),
        )
    }

    @Test
    fun `format renders local wall clock`() {
        assertEquals("2026-05-10 08:30", TaskScheduleCalculator.format(at(2026, 5, 10, 8, 30), zone))
    }
}
