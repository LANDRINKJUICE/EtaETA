package io.github.mangi.eta.agent.usage

import io.github.mangi.eta.agent.runs.AgentRunSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用量记录编解码与来源映射的回归测试。
 *
 * 重点：存储格式必须能容忍半截/脏行（不能因为一条坏记录让整个统计页空白），
 * 来源映射必须覆盖现有全部入口（聊天 / 语音 / 自动任务 / 外部助手）。
 */
class UsageRecordTest {
    private fun record(
        atMillis: Long = 1_700_000_000_000L,
        source: AgentRunSource = AgentRunSource.CHAT,
        prompt: Int = 120,
        completion: Int = 30,
        cached: Int = 0,
        estimated: Boolean = false,
    ) = UsageRecord(
        atMillis = atMillis,
        source = source,
        purpose = "chat",
        providerId = "builtin-openai",
        providerName = "OpenAI",
        model = "gpt-5.5",
        promptTokens = prompt,
        completionTokens = completion,
        cachedTokens = cached,
        estimated = estimated,
        runId = "run-123",
    )

    @Test
    fun encodeThenParseRoundTrip() {
        val original = record(source = AgentRunSource.SUBAGENT, cached = 64, estimated = true)
        val parsed = UsageRecord.parse(original.encode())
        assertEquals(original, parsed)
    }

    @Test
    fun parseRejectsBrokenOrIncompleteLines() {
        assertNull(UsageRecord.parse(null))
        assertNull(UsageRecord.parse(""))
        assertNull(UsageRecord.parse("not json"))
        assertNull(UsageRecord.parse("""{"src":"chat"}"""))
    }

    @Test
    fun parseFallsBackToUnknownSourceAndClampsTokens() {
        val parsed = UsageRecord.parse(
            """{"at":1700000000000,"src":"from-the-future","prompt":-5,"completion":9999999999,"cached":-1}""",
        )
        assertEquals(AgentRunSource.UNKNOWN, parsed?.source)
        assertEquals(0, parsed?.promptTokens)
        assertEquals(UsageRecord.MAX_TOKENS, parsed?.completionTokens)
        assertEquals(0, parsed?.cachedTokens)
    }

    @Test
    fun parseTruncatesOverlongNamingFields() {
        val parsed = UsageRecord.parse(
            """{"at":1,"provider":"${"p".repeat(500)}","model":"${"m".repeat(500)}","run":"${"r".repeat(500)}"}""",
        )
        assertEquals(UsageRecord.MAX_FIELD_CHARS, parsed?.providerId?.length)
        assertEquals(UsageRecord.MAX_FIELD_CHARS, parsed?.model?.length)
        assertEquals(UsageRecord.MAX_ID_CHARS, parsed?.runId?.length)
    }

    @Test
    fun estimateTokensRoundsUpAndNeverGoesNegative() {
        assertEquals(0, UsageRecord.estimateTokens(0))
        assertEquals(0, UsageRecord.estimateTokens(-10))
        assertEquals(1, UsageRecord.estimateTokens(1))
        assertEquals(1, UsageRecord.estimateTokens(4))
        assertEquals(2, UsageRecord.estimateTokens(5))
        assertEquals(250, UsageRecord.estimateTokens(1_000))
    }

    @Test
    fun handoffSourcesMapToExpectedRunSources() {
        assertEquals(AgentRunSource.CHAT, AgentRunSource.fromHandoffSource(null))
        assertEquals(AgentRunSource.CHAT, AgentRunSource.fromHandoffSource(""))
        assertEquals(AgentRunSource.CHAT, AgentRunSource.fromHandoffSource("agent_ui"))
        assertEquals(AgentRunSource.VOICE, AgentRunSource.fromHandoffSource("eta_voice"))
        assertEquals(AgentRunSource.SCHEDULED, AgentRunSource.fromHandoffSource("eta_scheduled_task"))
        assertEquals(AgentRunSource.EXTERNAL, AgentRunSource.fromHandoffSource("breeno"))
        assertEquals(AgentRunSource.EXTERNAL, AgentRunSource.fromHandoffSource("xiaoai"))
    }

    @Test
    fun wireNamesRoundTripThroughStorage() {
        AgentRunSource.entries.forEach { source ->
            assertEquals(source, AgentRunSource.fromWireName(source.wireName))
        }
        assertEquals(AgentRunSource.UNKNOWN, AgentRunSource.fromWireName("  "))
        assertEquals(AgentRunSource.UNKNOWN, AgentRunSource.fromWireName(null))
        assertEquals(AgentRunSource.CHAT, AgentRunSource.fromWireName(" CHAT "))
    }

    @Test
    fun totalTokensAddsPromptAndCompletion() {
        assertEquals(150, record(prompt = 120, completion = 30).totalTokens)
        assertTrue(UsageRecord.parse(record().encode()) != null)
    }
}
