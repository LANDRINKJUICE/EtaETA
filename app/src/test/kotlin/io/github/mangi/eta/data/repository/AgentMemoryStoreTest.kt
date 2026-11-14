package io.github.mangi.eta.data.repository

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentMemoryStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun utf8LimitIsMeasuredInBytesAndFailedWritePreservesOldFile() {
        val store = store()
        val original = store.replaceAll("安全内容")

        assertEquals(12, original.byteSize)
        assertThrows(AgentMemoryException::class.java) {
            store.replaceAll("a".repeat(AgentMemoryStore.MAX_FILE_BYTES + 1))
        }

        assertEquals(original, store.snapshot())
        assertEquals(
            AgentMemoryStore.MAX_FILE_BYTES,
            store.replaceAll("a".repeat(AgentMemoryStore.MAX_FILE_BYTES)).byteSize,
        )
    }

    @Test
    fun supportsPagingCaseInsensitiveSearchAndLineMetadata() {
        val store = store()
        store.replaceAll("# 核心记忆\n喜欢 Kotlin\n## 项目\nEta Agent\n其他")

        val page = store.read(startLine = 3, maxChars = 20)
        assertEquals(3, page.startLine)
        assertTrue(page.content.startsWith("3: ## 项目"))
        assertTrue(page.hasMore)

        val search = store.read(query = "eta agent", maxChars = 200)
        assertEquals(1, search.matchedLines)
        assertTrue(search.content.contains("4: Eta Agent"))
        assertTrue(search.content.contains("3: ## 项目"))
        assertTrue(search.content.contains("5: 其他"))
        assertFalse(search.hasMore)
    }

    @Test
    fun mutationsAreAtomicRevisionCheckedAndCanDeleteOrClear() {
        val store = store()
        val initial = store.replaceAll("# 核心记忆\n旧偏好\n## 项目\n旧项目")

        val replaced = store.mutate(
            AgentMemoryMutation.ReplaceRange(
                revision = initial.revision,
                startLine = 2,
                endLine = 2,
                content = "新偏好",
            ),
        ) as AgentMemoryWriteResult.Success
        assertEquals("# 核心记忆\n新偏好\n## 项目\n旧项目", replaced.snapshot.content)

        val conflict = store.mutate(
            AgentMemoryMutation.Append(initial.revision, "## 冲突追加"),
        ) as AgentMemoryWriteResult.Conflict
        assertEquals(replaced.snapshot.revision, conflict.snapshot.revision)
        assertFalse(store.snapshot().content.contains("冲突追加"))

        val appended = store.mutate(
            AgentMemoryMutation.Append(replaced.snapshot.revision, "## 新章节\n内容"),
        ) as AgentMemoryWriteResult.Success
        val deleted = store.mutate(
            AgentMemoryMutation.ReplaceRange(
                revision = appended.snapshot.revision,
                startLine = 4,
                endLine = 4,
                content = "",
            ),
        ) as AgentMemoryWriteResult.Success
        assertFalse(deleted.snapshot.content.contains("旧项目"))

        val cleared = store.mutate(
            AgentMemoryMutation.Clear(deleted.snapshot.revision),
        ) as AgentMemoryWriteResult.Success
        assertEquals("", cleared.snapshot.content)
        assertEquals(0, cleared.snapshot.byteSize)
        assertEquals(0, cleared.snapshot.lineCount)
    }

    @Test
    fun sectionWritesStampMetadataAndRefreshVerifiedWithoutTouchingRevisionSemantics() {
        val firstDay = LocalDate.of(2026, 5, 1)
        var clock = firstDay
        val store = AgentMemoryStore(temporaryFolder.newFolder(), today = { clock })
        val initial = store.replaceAll("# 核心记忆\n偏好")

        val created = store.mutate(
            AgentMemoryMutation.UpsertSection(initial.revision, "## 项目", "Eta 用 Kotlin"),
        ) as AgentMemoryWriteResult.Success
        val createdText = created.snapshot.content
        assertTrue(createdText.startsWith("# 核心记忆\n偏好\n## 项目\n<!-- eta:"))
        assertTrue(createdText.contains("created=2026-05-01"))
        assertTrue(createdText.contains("verified=2026-05-01"))

        val project = store.entries().single { it.heading == "## 项目" }
        assertEquals(firstDay, project.metadata.created)
        assertEquals(firstDay, project.metadata.verified)
        assertEquals("Eta 用 Kotlin", project.body)

        clock = firstDay.plusDays(30)
        val reVerified = store.mutate(
            AgentMemoryMutation.UpsertSection(created.snapshot.revision, "## 项目", "Eta 用 Kotlin"),
        ) as AgentMemoryWriteResult.Success
        val refreshed = store.entries().single { it.heading == "## 项目" }
        assertEquals(project.id, refreshed.id)
        assertEquals(firstDay, refreshed.metadata.created)
        assertEquals(firstDay.plusDays(30), refreshed.metadata.verified)
        assertEquals("Eta 用 Kotlin", refreshed.body)
        assertEquals(1, reVerified.snapshot.content.lines().count { it.startsWith("<!-- eta:") })
    }

    @Test
    fun replacingSectionBodyKeepsIdAndCreatedAndAdvancesVerified() {
        var clock = LocalDate.of(2026, 5, 1)
        val store = AgentMemoryStore(temporaryFolder.newFolder(), today = { clock })
        val initial = store.replaceAll("# 核心记忆\n偏好")

        val created = store.mutate(
            AgentMemoryMutation.UpsertSection(initial.revision, "## 项目", "Eta 用 Kotlin"),
        ) as AgentMemoryWriteResult.Success
        val before = store.entries().single { it.heading == "## 项目" }

        clock = clock.plusDays(45)
        val updated = store.mutate(
            AgentMemoryMutation.UpsertSection(
                created.snapshot.revision,
                "## 项目",
                "Eta 用 Kotlin 与 Compose",
            ),
        ) as AgentMemoryWriteResult.Success
        val after = store.entries().single { it.heading == "## 项目" }

        assertEquals(before.id, after.id)
        assertEquals(before.metadata.created, after.metadata.created)
        assertEquals(clock, after.metadata.verified)
        assertEquals("Eta 用 Kotlin 与 Compose", after.body)
        assertTrue(updated.snapshot.content.contains("Compose"))
    }

    @Test
    fun appendStampsEveryNewSectionButLeavesPreambleAndReplaceRangeAlone() {
        val today = LocalDate.of(2026, 5, 1)
        val store = store(today)
        val initial = store.replaceAll("# 核心记忆\n偏好")

        val appended = store.mutate(
            AgentMemoryMutation.Append(initial.revision, "前言\n## 服务\n端口 8080\n## 偏好\n中文"),
        ) as AgentMemoryWriteResult.Success

        assertTrue(appended.snapshot.content.contains("前言"))
        val entries = store.entries()
        assertEquals(listOf("# 核心记忆", "## 服务", "## 偏好"), entries.map { it.heading })
        entries.drop(1).forEach { entry -> assertEquals(today, entry.metadata.verified) }

        val replaced = store.mutate(
            AgentMemoryMutation.ReplaceRange(appended.snapshot.revision, 1, 1, "# 核心记忆"),
        ) as AgentMemoryWriteResult.Success
        assertEquals(appended.snapshot.content, replaced.snapshot.content)
    }

    private fun store(today: LocalDate = LocalDate.of(2026, 5, 1)): AgentMemoryStore =
        AgentMemoryStore(temporaryFolder.newFolder(), today = { today })
}
