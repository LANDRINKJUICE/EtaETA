package io.github.mangi.eta.agent.usage

import io.github.mangi.eta.agent.runs.AgentRunSource
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用量窗口与分组的回归测试。
 *
 * 全部用固定时区（Asia/Shanghai）+ 固定"现在"，因此结果与时区、夏令时、运行机器无关；
 * 这些用例钉住用户能感知的口径：今日/7 日/30 日按本地自然日切分，分组按 token 总量降序。
 */
class UsageAggregationTest {
    private val zone = TimeZone.getTimeZone("Asia/Shanghai")

    /** 2026-05-20 10:00 Asia/Shanghai。 */
    private val now = localMillis(2026, 5, 20, 10, 0)

    private fun localMillis(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
    ): Long = java.util.Calendar.getInstance(zone).apply {
        clear()
        set(year, month - 1, day, hour, minute, 0)
    }.timeInMillis

    private fun record(
        atMillis: Long,
        source: AgentRunSource = AgentRunSource.CHAT,
        model: String = "gpt-5.5",
        prompt: Int = 100,
        completion: Int = 20,
        cached: Int = 0,
        estimated: Boolean = false,
    ) = UsageRecord(
        atMillis = atMillis,
        source = source,
        purpose = "chat",
        providerId = "builtin-openai",
        providerName = "OpenAI",
        model = model,
        promptTokens = prompt,
        completionTokens = completion,
        cachedTokens = cached,
        estimated = estimated,
        runId = "run",
    )

    @Test
    fun windowStartAlignsToLocalMidnight() {
        assertEquals(localMillis(2026, 5, 20, 0, 0), UsageAggregator.windowStart(now, 1, zone))
        assertEquals(localMillis(2026, 5, 14, 0, 0), UsageAggregator.windowStart(now, 7, zone))
        assertEquals(localMillis(2026, 4, 21, 0, 0), UsageAggregator.windowStart(now, 30, zone))
        // 非法天数按 1 天处理，不抛异常。
        assertEquals(localMillis(2026, 5, 20, 0, 0), UsageAggregator.windowStart(now, 0, zone))
    }

    @Test
    fun windowsCountRequestsByLocalCalendar() {
        val records = listOf(
            record(localMillis(2026, 5, 20, 9, 30)),
            record(localMillis(2026, 5, 17, 23, 59)),
            record(localMillis(2026, 5, 10, 8, 0)),
            record(localMillis(2026, 4, 1, 8, 0)),
        )
        val summary = UsageAggregator.summarize(records, now, zone)
        assertEquals(1, summary.today.requests)
        assertEquals(2, summary.week.requests)
        assertEquals(3, summary.month.requests)
        assertEquals(3, summary.month.totalTokens.let { it / 120 }.toInt())
    }

    @Test
    fun futureTimestampsAreIgnored() {
        val summary = UsageAggregator.summarize(
            listOf(record(now + 60_000), record(localMillis(2026, 5, 20, 1, 0))),
            now,
            zone,
        )
        assertEquals(1, summary.today.requests)
    }

    @Test
    fun tokenSumsAndEstimatedCountsAreExact() {
        val records = listOf(
            record(localMillis(2026, 5, 20, 9, 0), prompt = 1_000, completion = 200, cached = 300),
            record(localMillis(2026, 5, 20, 9, 10), prompt = 500, completion = 100, estimated = true),
            record(localMillis(2026, 5, 19, 9, 0), prompt = 250, completion = 50, estimated = true),
        )
        val summary = UsageAggregator.summarize(records, now, zone)
        assertEquals(2, summary.today.requests)
        assertEquals(1_800L, summary.today.totalTokens)
        assertEquals(300L, summary.today.cachedTokens)
        assertEquals(1, summary.today.estimatedRequests)
        assertEquals(3, summary.week.requests)
        assertEquals(2, summary.week.estimatedRequests)
    }

    @Test
    fun groupsAreSortedByTokenVolumeThenRequests() {
        val records = listOf(
            record(localMillis(2026, 5, 20, 9, 0), source = AgentRunSource.CHAT, model = "m-small", prompt = 10),
            record(localMillis(2026, 5, 20, 9, 5), source = AgentRunSource.SCHEDULED, model = "m-big", prompt = 900),
            record(localMillis(2026, 5, 20, 9, 10), source = AgentRunSource.SCHEDULED, model = "m-big", prompt = 900),
            record(localMillis(2026, 5, 19, 9, 0), source = AgentRunSource.DISTILL, model = "m-mid", prompt = 400),
        )
        val summary = UsageAggregator.summarize(records, now, zone)
        assertEquals(listOf("scheduled", "distill", "chat"), summary.bySource.map { it.key })
        assertEquals(listOf("m-big", "m-mid", "m-small"), summary.byModel.map { it.key })
        assertEquals(2, summary.bySource.first().window.requests)
        assertEquals(1_840L, summary.bySource.first().window.totalTokens)
    }

    @Test
    fun emptyInputProducesEmptySummary() {
        val summary = UsageAggregator.summarize(emptyList(), now, zone)
        assertTrue(summary.isEmpty)
        assertEquals(0, summary.month.requests)
        assertTrue(summary.bySource.isEmpty())
        assertTrue(summary.byModel.isEmpty())
        assertEquals(now, summary.generatedAtMillis)
    }

    @Test
    fun blankModelFallsBackToProviderName() {
        val summary = UsageAggregator.summarize(
            listOf(record(localMillis(2026, 5, 20, 9, 0), model = "")),
            now,
            zone,
        )
        assertEquals(listOf("OpenAI"), summary.byModel.map { it.key })
    }

    // ---- 跨夏令时（America/New_York 2026-03-08 02:00 进入 EDT）----

    @Test
    fun `windowsFollowLocalMidnightAcrossSpringForwardDst`() {
        val ny = TimeZone.getTimeZone("America/New_York")
        fun nyMillis(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
            java.util.Calendar.getInstance(ny).apply {
                clear()
                set(year, month - 1, day, hour, minute, 0)
            }.timeInMillis

        // 2026-03-08 是美东春季前跳：当地 02:00 直接变成 03:00，这一天只有 23 小时。
        val afterDst = nyMillis(2026, 3, 8, 12, 0)
        // 当天 0 点仍是 EST（UTC-5），次日 0 点已是 EDT（UTC-4）：
        // 若按"固定 24h"切窗，今日窗口会错位一小时，把前一天 23:30 的用量算进今天。
        assertEquals(nyMillis(2026, 3, 8, 0, 0), UsageAggregator.windowStart(afterDst, 1, ny))
        assertEquals(nyMillis(2026, 3, 2, 0, 0), UsageAggregator.windowStart(afterDst, 7, ny))

        val records = listOf(
            record(nyMillis(2026, 3, 8, 0, 30)),   // 今天
            record(nyMillis(2026, 3, 7, 23, 30)),  // 昨天（跨 DST 前夜，不得算进今天）
            record(nyMillis(2026, 3, 7, 0, 30)),   // 前天（在 7 日窗口内）
            record(nyMillis(2026, 3, 1, 23, 30)),  // 7 日窗口之前
        )
        val summary = UsageAggregator.summarize(records, afterDst, ny)
        assertEquals(1, summary.today.requests)
        assertEquals(3, summary.week.requests)
        assertEquals(4, summary.month.requests)
    }

    @Test
    fun `windowsFollowLocalMidnightAcrossFallBackDst`() {
        val ny = TimeZone.getTimeZone("America/New_York")
        fun nyMillis(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
            java.util.Calendar.getInstance(ny).apply {
                clear()
                set(year, month - 1, day, hour, minute, 0)
            }.timeInMillis

        // 2026-11-01 是美东秋季回拨：当地 02:00 回到 01:00，这一天有 25 小时。
        val afterFallBack = nyMillis(2026, 11, 1, 12, 0)
        assertEquals(nyMillis(2026, 11, 1, 0, 0), UsageAggregator.windowStart(afterFallBack, 1, ny))
        val records = listOf(
            record(nyMillis(2026, 11, 1, 1, 30)),  // 今天（回拨后的那一个小时）
            record(nyMillis(2026, 10, 31, 23, 30)),// 昨天
        )
        val summary = UsageAggregator.summarize(records, afterFallBack, ny)
        assertEquals(1, summary.today.requests)
        assertEquals(2, summary.week.requests)
    }
}
