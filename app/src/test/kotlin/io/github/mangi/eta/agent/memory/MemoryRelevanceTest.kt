package io.github.mangi.eta.agent.memory

import io.github.mangi.eta.data.repository.AgentMemoryEntries
import io.github.mangi.eta.data.repository.AgentMemoryEntry
import io.github.mangi.eta.data.repository.AgentMemoryEntryMetadata
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryRelevanceTest {
    private val now = LocalDate.of(2026, 5, 1)

    @Test
    fun tokensSplitCjkIntoBigramsAndLowercaseLatinWords() {
        val tokens = MemoryRelevance.tokens("Eta Agent 用 Kotlin 写记忆系统")

        assertTrue("eta" in tokens)
        assertTrue("agent" in tokens)
        assertTrue("kotlin" in tokens)
        assertTrue("记忆" in tokens)
        assertTrue("系统" in tokens)
        assertFalse("的" in tokens)
    }

    @Test
    fun tokensDropStopwords() {
        val tokens = MemoryRelevance.tokens("我们可以怎么帮我一下？")

        assertFalse("我们" in tokens)
        assertFalse("可以" in tokens)
        assertFalse("一下" in tokens)
        assertTrue(MemoryRelevance.tokens("the a of to").isEmpty())
    }

    @Test
    fun selectPrefersSectionsThatShareKeywordsWithTheRequest() {
        val entries = entriesOf(
            "## 项目\nEta 是一个 Android 上的 Agent 宿主应用",
            "## 饮食\n用户喜欢清淡的川菜",
            "## 通勤\n每天坐地铁 2 号线",
        )

        val selected = MemoryRelevance.select(entries, "Android 应用怎么部署 Agent", now, limit = 2)

        assertEquals("## 项目", selected.first().entry.heading)
        assertEquals(1, selected.size)
    }

    @Test
    fun freshnessBreaksTiesBetweenEquallyRelevantSections() {
        val stale = entry(
            heading = "## 项目 A",
            body = "部署 端口",
            verified = now.minusDays(400),
        )
        val fresh = entry(
            heading = "## 项目 B",
            body = "部署 端口",
            verified = now.minusDays(3),
        )

        val selected = MemoryRelevance.select(listOf(stale, fresh), "部署端口", now, limit = 2)

        assertEquals(listOf("## 项目 B", "## 项目 A"), selected.map { it.entry.heading })
        assertTrue(selected.first().score > selected.last().score)
    }

    @Test
    fun staleStateSectionsArePenalised() {
        val staleState = entry(
            heading = "## 当前状态",
            body = "部署 端口",
            verified = now.minusDays(120),
            type = "state",
        )
        val staleFact = entry(
            heading = "## 项目约定",
            body = "部署 端口",
            verified = now.minusDays(120),
        )

        val selected = MemoryRelevance.select(listOf(staleState, staleFact), "部署端口", now, limit = 2)

        assertTrue(MemoryRelevance.isStateLike(staleState))
        assertFalse(MemoryRelevance.isStateLike(staleFact))
        assertEquals(listOf("## 项目约定", "## 当前状态"), selected.map { it.entry.heading })
    }

    @Test
    fun sectionsWithoutKeywordOverlapAreNeverInjected() {
        val entries = entriesOf("## 无关\n今天天气不错")

        assertTrue(MemoryRelevance.select(entries, "Kotlin 协程调度", now, limit = 3).isEmpty())
        assertTrue(MemoryRelevance.select(entries, "", now, limit = 3).isEmpty())
    }

    @Test
    fun scoreReturnsNullWhenNothingMatches() {
        val entry = entry(heading = "## 项目", body = "Eta", verified = null)

        assertNull(MemoryRelevance.score(entry, MemoryRelevance.tokens("Kotlin"), now))
    }

    @Test
    fun unknownDatesProvideNoFreshnessBonus() {
        val entry = entry(heading = "## 项目", body = "Eta Agent", verified = null)

        val scored = MemoryRelevance.score(entry, MemoryRelevance.tokens("Eta"), now)

        requireNotNull(scored)
        assertEquals(0.0, scored.freshness, 0.0001)
        assertEquals(scored.keywordScore, scored.score, 0.0001)
    }

    private fun entriesOf(vararg contents: String): List<AgentMemoryEntry> =
        contents.flatMap { content -> AgentMemoryEntries.parse(content) }

    private fun entry(
        heading: String,
        body: String,
        verified: LocalDate?,
        type: String? = null,
    ): AgentMemoryEntry = AgentMemoryEntry(
        id = AgentMemoryEntries.stableId(heading, body),
        heading = heading,
        level = heading.takeWhile { it == '#' }.length,
        body = body,
        startLine = 1,
        endLine = 2,
        metadata = AgentMemoryEntryMetadata(verified = verified, type = type),
    )
}
