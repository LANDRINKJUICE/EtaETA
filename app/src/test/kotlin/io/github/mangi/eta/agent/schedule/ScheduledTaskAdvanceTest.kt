package io.github.mangi.eta.agent.schedule

import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "到点这一轮怎么排、要不要再跑"的回归测试（审查 B1 / M2）。
 *
 * B1 的现场是：闹钟被系统消耗掉，执行又被并发闸门跳过 → 任务永久停摆。
 * 修复后排期与派发分离，因此这里钉住三件事：
 * 1. 重复任务每次都给出**未来**的下一次触发（与是否执行无关）；
 * 2. 一次性任务第一次到点会给出"没有下一轮"，第二次（认领窗口内）不再执行；
 * 3. 认领窗口过期（进程死在执行中途）后允许重跑，不会永久漏掉用户的任务。
 */
class ScheduledTaskAdvanceTest {
    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private fun at(day: Int, hour: Int, minute: Int): Long =
        java.time.ZonedDateTime.of(java.time.LocalDateTime.of(2026, 5, day, hour, minute), zone)
            .toInstant()
            .toEpochMilli()

    private fun daily(nextTrigger: Long = at(10, 9, 0)) = ScheduledTask(
        id = "daily",
        title = "digest",
        prompt = "digest",
        schedule = TaskSchedule(kind = ScheduleKind.DAILY, hour = 9, minute = 0),
        nextTriggerAtMillis = nextTrigger,
    )

    private fun once(nextTrigger: Long, claimAt: Long = 0L) = ScheduledTask(
        id = "once",
        title = "one shot",
        prompt = "one shot",
        schedule = TaskSchedule(kind = ScheduleKind.ONCE, atMillis = at(10, 9, 0)),
        nextTriggerAtMillis = nextTrigger,
        claimAtMillis = claimAt,
    )

    @Test
    fun `repeat task always advances to a future trigger even when the run is skipped`() {
        // 到点时刻就是 next_trigger；无论后面跑不跑，决策都必须给出明天 09:00。
        val now = at(10, 9, 0)
        val decision = ScheduledTaskAdvance.decide(daily(), now, zone)
        assertEquals(
            ScheduledTaskAdvance.Decision.Run(nextTriggerAtMillis = at(11, 9, 0)),
            decision,
        )
    }

    @Test
    fun `repeat task after a long offline gap jumps to the nearest future slot`() {
        val now = at(20, 12, 0)
        val decision = ScheduledTaskAdvance.decide(daily(), now, zone) as ScheduledTaskAdvance.Decision.Run
        assertEquals(at(21, 9, 0), decision.nextTriggerAtMillis)
        assertTrue(decision.nextTriggerAtMillis > now)
    }

    @Test
    fun `interval task aligns on the previous planned trigger`() {
        val task = ScheduledTask(
            id = "interval",
            title = "ping",
            prompt = "ping",
            schedule = TaskSchedule(kind = ScheduleKind.INTERVAL, intervalMinutes = 30),
            nextTriggerAtMillis = at(10, 9, 0),
        )
        // 实际执行晚了 7 分钟，下一轮仍应落在 09:30。
        val decision = ScheduledTaskAdvance.decide(task, at(10, 9, 7), zone) as ScheduledTaskAdvance.Decision.Run
        assertEquals(at(10, 9, 30), decision.nextTriggerAtMillis)
    }

    @Test
    fun `once task claims once and rejects the duplicate alarm inside the window`() {
        val now = at(10, 9, 0)
        val first = ScheduledTaskAdvance.decide(once(nextTrigger = at(10, 9, 0)), now, zone)
        assertEquals(ScheduledTaskAdvance.Decision.Run(nextTriggerAtMillis = 0L), first)

        // 认领后写回 next_trigger=0 + claim_at=now；紧接着来的第二个广播不能重复执行。
        val claimed = once(nextTrigger = 0L, claimAt = now)
        assertEquals(
            ScheduledTaskAdvance.Decision.AlreadyClaimed,
            ScheduledTaskAdvance.decide(claimed, now + 1_000L, zone),
        )
    }

    @Test
    fun `once task with an expired claim is allowed to run again`() {
        val now = at(10, 9, 0)
        val staleClaim = now - ScheduledTaskAdvance.ONCE_CLAIM_WINDOW_MILLIS - 1L
        val task = once(nextTrigger = 0L, claimAt = staleClaim)
        assertEquals(
            ScheduledTaskAdvance.Decision.Run(nextTriggerAtMillis = 0L),
            ScheduledTaskAdvance.decide(task, now, zone),
        )
    }

    @Test
    fun `once task without any claim marker is treated as pending`() {
        assertEquals(
            ScheduledTaskAdvance.Decision.Run(nextTriggerAtMillis = 0L),
            ScheduledTaskAdvance.decide(once(nextTrigger = 0L, claimAt = 0L), at(10, 9, 0), zone),
        )
    }

    @Test
    fun `disabled task never runs and needs no schedule`() {
        assertEquals(
            ScheduledTaskAdvance.Decision.Skip,
            ScheduledTaskAdvance.decide(daily().copy(enabled = false), at(10, 9, 0), zone),
        )
    }

    @Test
    fun `claim window is left closed and right open`() {
        val now = at(10, 9, 0)
        val edge = now - ScheduledTaskAdvance.ONCE_CLAIM_WINDOW_MILLIS
        // 正好等于窗口长度时算过期（窗口是 [0, WINDOW)）：进程死在执行中途后不会被永久锁死。
        assertEquals(
            ScheduledTaskAdvance.Decision.Run(nextTriggerAtMillis = 0L),
            ScheduledTaskAdvance.decide(once(nextTrigger = 0L, claimAt = edge), now, zone),
        )
        assertEquals(
            ScheduledTaskAdvance.Decision.AlreadyClaimed,
            ScheduledTaskAdvance.decide(once(nextTrigger = 0L, claimAt = edge + 1L), now, zone),
        )
    }
}
