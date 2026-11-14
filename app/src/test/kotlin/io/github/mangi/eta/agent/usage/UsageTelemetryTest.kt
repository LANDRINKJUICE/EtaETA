package io.github.mangi.eta.agent.usage

import io.github.mangi.eta.agent.runs.AgentRunSource
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 埋点入口的回归测试。
 *
 * 钉住两件事：
 * 1. 口径：服务端给了 token 就用真实值；没给（或给了 0）才按字符估算，并且必须打 `estimated`；
 * 2. 韧性：未装落点、开关关闭、归属为空、落点抛异常时，都只是"不记账"，绝不能向主链路抛错。
 */
class UsageTelemetryTest {
    private val records = CopyOnWriteArrayList<UsageRecord>()

    @After
    fun tearDown() {
        UsageTelemetry.reset()
    }

    private fun install(enabled: Boolean = true, sink: UsageSink = collector()) {
        UsageTelemetry.install(sink = sink, enabled = { enabled })
    }

    private fun collector(): UsageSink = object : UsageSink {
        override fun offer(record: UsageRecord) {
            records += record
        }
    }

    private fun round(
        attribution: UsageAttribution? = UsageAttribution(AgentRunSource.CHAT, "run-1"),
        promptTokens: Int? = 1_000,
        completionTokens: Int? = 200,
        cachedTokens: Int? = null,
        promptChars: Int = 4_000,
        completionChars: Int = 800,
    ) = UsageTelemetry.recordModelRound(
        attribution = attribution,
        purpose = "CHAT",
        providerId = "builtin-openai",
        providerName = "OpenAI",
        model = "gpt-5.5",
        promptTokens = promptTokens,
        completionTokens = completionTokens,
        cachedTokens = cachedTokens,
        promptChars = { promptChars },
        completionChars = completionChars,
        nowMillis = 1_774_000_000_000L,
    )

    @Test
    fun reportedTokensAreStoredAsExactValues() {
        install()
        round(cachedTokens = 320)
        val record = records.single()
        assertEquals(1_000, record.promptTokens)
        assertEquals(200, record.completionTokens)
        assertEquals(320, record.cachedTokens)
        assertFalse(record.estimated)
        assertEquals(AgentRunSource.CHAT, record.source)
        assertEquals("chat", record.purpose)
        assertEquals("run-1", record.runId)
        assertEquals(1_774_000_000_000L, record.atMillis)
    }

    @Test
    fun missingUsageFallsBackToCharacterEstimateAndFlagsItself() {
        install()
        round(promptTokens = null, completionTokens = null, promptChars = 4_001, completionChars = 801)
        val record = records.single()
        assertEquals(1_001, record.promptTokens)
        assertEquals(201, record.completionTokens)
        assertTrue(record.estimated)
    }

    @Test
    fun zeroReportedTokensAreTreatedAsMissing() {
        install()
        round(promptTokens = 0, completionTokens = 0, promptChars = 40, completionChars = 4)
        val record = records.single()
        assertEquals(10, record.promptTokens)
        assertEquals(1, record.completionTokens)
        assertTrue(record.estimated)
    }

    @Test
    fun negativeReportedTokensAreClampedToZero() {
        install()
        round(promptTokens = -100, completionTokens = -1, promptChars = 0, completionChars = 0)
        val record = records.single()
        assertEquals(0, record.promptTokens)
        assertEquals(0, record.completionTokens)
        assertTrue(record.estimated)
    }

    @Test
    fun nullAttributionIsNotRecorded() {
        install()
        round(attribution = null)
        assertTrue(records.isEmpty())
    }

    @Test
    fun disabledSwitchRecordsNothing() {
        install(enabled = false)
        round()
        assertTrue(records.isEmpty())
    }

    @Test
    fun nothingIsRecordedBeforeInstall() {
        UsageTelemetry.reset()
        round()
        assertTrue(records.isEmpty())
        assertFalse(UsageTelemetry.isEnabled())
    }

    @Test
    fun throwingSinkNeverBreaksTheCaller() {
        val explosive = object : UsageSink {
            override fun offer(record: UsageRecord) {
                error("disk on fire")
            }
        }
        install(sink = explosive)
        round()
        assertTrue(records.isEmpty())
    }

    @Test
    fun throwingEnabledProviderIsTreatedAsDisabled() {
        UsageTelemetry.install(sink = collector(), enabled = { error("prefs unavailable") })
        round()
        assertTrue(records.isEmpty())
    }

    @Test
    fun promptCharsLambdaIsOnlyEvaluatedWhenEstimationIsNeeded() {
        install()
        var evaluations = 0
        UsageTelemetry.recordModelRound(
            attribution = UsageAttribution(AgentRunSource.SCHEDULED, "run-2"),
            purpose = "chat",
            providerId = "p",
            providerName = "Provider",
            model = "m",
            promptTokens = 500,
            completionTokens = 100,
            cachedTokens = 0,
            promptChars = {
                evaluations += 1
                4_000
            },
            completionChars = 400,
            nowMillis = 1_774_000_000_000L,
        )
        assertEquals(0, evaluations)
        assertEquals(1, records.size)
    }
}
