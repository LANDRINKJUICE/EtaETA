package io.github.mangi.eta.data.repository

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentMemoryEntryTest {
    private val today = LocalDate.of(2026, 5, 1)

    @Test
    fun legacySectionsParseWithoutMetadata() {
        val content = "# 核心记忆\n长期偏好\n## 项目\nEta 用 Kotlin\n### 子项\n细节"
        val entries = AgentMemoryEntries.parse(content)

        assertEquals(listOf("# 核心记忆", "## 项目", "### 子项"), entries.map { it.heading })
        assertEquals(listOf(1, 3, 5), entries.map { it.startLine })
        assertEquals(listOf(2, 4, 6), entries.map { it.endLine })
        assertEquals("Eta 用 Kotlin", entries[1].body)
        assertTrue(entries[1].metadata.isEmpty)
        assertNull(entries[1].lastUpdated)
        assertTrue(entries[1].id.startsWith("e"))
        assertEquals(1, entries[0].level)
        assertEquals("项目", entries[1].title)
    }

    @Test
    fun metadataCommentLineIsParsedAndRemovedFromBody() {
        val content = "## 当前服务\n<!-- eta:id=e123;created=2026-01-02;verified=2026-04-20;type=state -->\n端口 8080"

        val entry = AgentMemoryEntries.parse(content).single()

        assertEquals("e123", entry.id)
        assertEquals(LocalDate.of(2026, 1, 2), entry.metadata.created)
        assertEquals(LocalDate.of(2026, 4, 20), entry.metadata.verified)
        assertEquals("state", entry.metadata.type)
        assertEquals("端口 8080", entry.body)
        assertEquals(LocalDate.of(2026, 4, 20), entry.lastUpdated)
    }

    @Test
    fun headingsInsideFencedBlocksAreNotSectionBoundaries() {
        val content = "## 配置\n```\n# 注释\n```\n## 其他\n内容"

        val entries = AgentMemoryEntries.parse(content)

        assertEquals(listOf("## 配置", "## 其他"), entries.map { it.heading })
        assertTrue(entries[0].body.contains("# 注释"))
    }

    @Test
    fun stampSectionAddsMetadataOnFirstWriteAndRefreshesVerifiedLater() {
        val stamped = AgentMemoryEntries.stampSection("## 项目", "Eta 用 Kotlin", today)
        val first = AgentMemoryEntries.parse("## 项目\n$stamped").single()

        assertEquals(today, first.metadata.created)
        assertEquals(today, first.metadata.verified)
        assertEquals("Eta 用 Kotlin", first.body)

        val reVerified = AgentMemoryEntries.stampSection(
            heading = "## 项目",
            body = stamped,
            date = today.plusDays(10),
        )
        val second = AgentMemoryEntries.parse("## 项目\n$reVerified").single()

        assertEquals(first.id, second.id)
        assertEquals(today, second.metadata.created)
        assertEquals(today.plusDays(10), second.metadata.verified)
        assertEquals("Eta 用 Kotlin", second.body)
        assertEquals(1, reVerified.lines().count { it.startsWith("<!-- eta:") })
    }

    @Test
    fun changingTheBodyKeepsIdAndCreatedButAdvancesVerified() {
        val previous = AgentMemoryEntryMetadata(
            id = "e0123456789ab",
            created = LocalDate.of(2026, 1, 2),
            verified = LocalDate.of(2026, 1, 2),
            type = "state",
        )

        val stamped = AgentMemoryEntries.stampSection(
            heading = "## 项目",
            body = "新的正文",
            date = LocalDate.of(2026, 5, 1),
            previous = previous,
        )
        val entry = AgentMemoryEntries.parse("## 项目\n$stamped").single()

        assertEquals("e0123456789ab", entry.id)
        assertEquals(LocalDate.of(2026, 1, 2), entry.metadata.created)
        assertEquals(LocalDate.of(2026, 5, 1), entry.metadata.verified)
        assertEquals("state", entry.metadata.type)
        assertEquals("新的正文", entry.body)
    }

    @Test
    fun handWrittenInlineIdCannotHijackTheInheritedId() {
        val previous = AgentMemoryEntryMetadata(
            id = "eaaaabbbbcccc",
            created = LocalDate.of(2026, 1, 1),
        )
        val body = "<!-- eta:id=handwritten;created=2020-01-01 -->\n正文"

        val stamped = AgentMemoryEntries.stampSection(
            heading = "## 项目",
            body = body,
            date = LocalDate.of(2026, 5, 1),
            previous = previous,
        )
        val entry = AgentMemoryEntries.parse("## 项目\n$stamped").single()

        assertEquals("eaaaabbbbcccc", entry.id)
        assertEquals(LocalDate.of(2026, 1, 1), entry.metadata.created)
        assertEquals(LocalDate.of(2026, 5, 1), entry.metadata.verified)
        assertEquals("正文", entry.body)
    }

    @Test
    fun invalidInlineIdIsReplacedByAStableId() {
        val stamped = AgentMemoryEntries.stampSection(
            heading = "## 项目",
            body = "<!-- eta:id=abc -->\n正文",
            date = LocalDate.of(2026, 5, 1),
        )
        val entry = AgentMemoryEntries.parse("## 项目\n$stamped").single()

        assertTrue(entry.id.matches(Regex("^e[0-9a-f]{12}$")))
        assertEquals(AgentMemoryEntries.stableId("## 项目", "正文"), entry.id)
        assertEquals(today, entry.metadata.created)
    }

    @Test
    fun extraMetadataLinesAreCollapsedIntoOne() {
        val body = "<!-- eta:id=ebbbbbbbbbbb;created=2026-01-01 -->\n正文\n" +
            "<!-- eta:id=ecccccccccccc;created=2026-02-02 -->\n尾部"

        val stamped = AgentMemoryEntries.stampSections("## 项目\n$body", today)

        assertEquals(1, stamped.lines().count { it.startsWith("<!-- eta:") })
        assertTrue(stamped.contains("正文"))
        assertTrue(stamped.contains("尾部"))
    }

    @Test
    fun stampingIsIdempotentForRepeatedSameDayWrites() {
        val once = AgentMemoryEntries.stampSection("## 项目", "正文", today)
        val twice = AgentMemoryEntries.stampSection("## 项目", once, today)

        assertEquals(once, twice)
    }

    @Test
    fun stableIdTracksBodyButNotVerificationDate() {
        val first = AgentMemoryEntries.stableId("## 项目", "正文 A")
        val sameBody = AgentMemoryEntries.stableId("## 项目", "正文 A")
        val otherBody = AgentMemoryEntries.stableId("## 项目", "正文 B")

        assertEquals(first, sameBody)
        assertNotEquals(first, otherBody)
    }

    @Test
    fun stampSectionsKeepsPreambleAndStampsEverySection() {
        val content = "前言\n## 项目\nEta\n## 偏好\n中文"

        val stamped = AgentMemoryEntries.stampSections(content, today)
        val entries = AgentMemoryEntries.parse(stamped)

        assertTrue(stamped.startsWith("前言\n"))
        assertEquals(2, entries.size)
        entries.forEach { entry ->
            assertEquals(today, entry.metadata.verified)
            assertEquals(today, entry.metadata.created)
        }
        assertEquals("Eta", entries[0].body)
        assertEquals("中文", entries[1].body)
    }
}
