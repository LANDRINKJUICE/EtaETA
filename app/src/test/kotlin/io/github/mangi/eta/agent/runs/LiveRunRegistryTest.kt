package io.github.mangi.eta.agent.runs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 任务中心「运行中」登记表的回归测试。
 *
 * 钉住的语义：登记与收尾必须成对且幂等（异常路径不能把 run 永久留在列表里）、
 * 顺序稳定（UI 不会每次刷新跳位）、标题只做展示用的截断、容量上限不会无限增长。
 */
class LiveRunRegistryTest {
    private val clock = longArrayOf(1_000L, 2_000L, 3_000L, 4_000L, 5_000L)
    private var clockIndex = 0

    private fun newRegistry(capacity: Int = LiveRunRegistry.MAX_ACTIVE_RUNS): LiveRunRegistry =
        LiveRunRegistry(clock = { clock[clockIndex++ % clock.size] }, capacity = capacity)

    @Test
    fun beginPublishesEntryAndCloseRemovesIt() {
        val registry = newRegistry()
        assertEquals(emptyList<LiveRunRegistry.Entry>(), registry.active.value)

        val registration = registry.begin(runId = "run-1", source = AgentRunSource.CHAT, title = "  帮我查一下\n天气  ")
        val active = registry.active.value
        assertEquals(1, active.size)
        assertEquals("run-1", active.single().runId)
        assertEquals(AgentRunSource.CHAT, active.single().source)
        assertEquals("帮我查一下 天气", active.single().title)
        assertNull(active.single().parentRunId)
        assertEquals(1_000L, active.single().startedAtMillis)

        registration.close()
        assertEquals(emptyList<LiveRunRegistry.Entry>(), registry.active.value)
    }

    @Test
    fun closeIsIdempotentAndFinishOfUnknownRunIsNoOp() {
        val registry = newRegistry()
        val registration = registry.begin(runId = "run-2", source = AgentRunSource.SCHEDULED)
        registration.close()
        registration.close()
        registry.finish("never-registered")
        assertEquals(emptyList<LiveRunRegistry.Entry>(), registry.active.value)
    }

    @Test
    fun entriesKeepRegistrationOrder() {
        val registry = newRegistry()
        val first = registry.begin(runId = "a", source = AgentRunSource.CHAT)
        val second = registry.begin(runId = "b", source = AgentRunSource.SUBAGENT)
        val third = registry.begin(runId = "c", source = AgentRunSource.DISTILL)
        assertEquals(listOf("a", "b", "c"), registry.active.value.map { it.runId })
        assertEquals(listOf(1L, 2L, 3L), registry.active.value.map { it.sequence })

        second.close()
        assertEquals(listOf("a", "c"), registry.active.value.map { it.runId })
        first.close()
        third.close()
        assertEquals(emptyList<LiveRunRegistry.Entry>(), registry.active.value)
    }

    @Test
    fun capacityDropsOldestEntry() {
        val registry = newRegistry(capacity = 2)
        registry.begin(runId = "a", source = AgentRunSource.CHAT)
        registry.begin(runId = "b", source = AgentRunSource.CHAT)
        registry.begin(runId = "c", source = AgentRunSource.CHAT)
        assertEquals(listOf("b", "c"), registry.active.value.map { it.runId })
    }

    @Test
    fun childRunKeepsParentAndRejectsSelfReference() {
        val registry = newRegistry()
        registry.begin(runId = "parent", source = AgentRunSource.CHAT)
        registry.begin(runId = "child", source = AgentRunSource.SUBAGENT, parentRunId = "parent")
        registry.begin(runId = "self", source = AgentRunSource.SUBAGENT, parentRunId = "self")
        val entries = registry.active.value.associateBy { it.runId }
        assertEquals("parent", entries.getValue("child").parentRunId)
        assertTrue(entries.getValue("child").isChild)
        assertNull(entries.getValue("self").parentRunId)
    }

    @Test
    fun longTitleIsTruncatedForDisplay() {
        val registry = newRegistry()
        registry.begin(runId = "long", source = AgentRunSource.CHAT, title = "x".repeat(500))
        assertEquals(LiveRunRegistry.Entry.MAX_TITLE_CHARS, registry.active.value.single().title.length)
    }

    @Test
    fun clearRemovesEverything() {
        val registry = newRegistry()
        registry.begin(runId = "a", source = AgentRunSource.CHAT)
        registry.begin(runId = "b", source = AgentRunSource.VOICE)
        registry.clear()
        assertEquals(emptyList<LiveRunRegistry.Entry>(), registry.active.value)
    }

    @Test(expected = IllegalArgumentException::class)
    fun blankRunIdIsRejected() {
        newRegistry().begin(runId = "  ", source = AgentRunSource.CHAT)
    }
}
