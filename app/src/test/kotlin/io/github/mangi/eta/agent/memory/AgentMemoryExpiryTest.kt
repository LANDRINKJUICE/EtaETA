package io.github.mangi.eta.agent.memory

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentMemoryExpiryTest {

    private val now = LocalDate.of(2026, 11, 13)

    private fun doc(body: String): String = "顶部常驻 随便写\n---\n\n" + body

    @Test
    fun noDividerMeansNoChange() {
        val legacy = "# 核心记忆\n老内容没有日期\n## 关系\n家人"
        assertEquals(legacy, AgentMemoryExpiry.archiveExpired(legacy, now))
    }

    @Test
    fun pinnedTopNeverMoved() {
        val content = doc("## 旧节\n2026-01-01 很旧的内容\n")
        val result = AgentMemoryExpiry.archiveExpired(content, now, 90)
        assertTrue(result.startsWith("顶部常驻 随便写\n---"))
    }

    @Test
    fun oldDatedSectionMovesToArchive() {
        val content = doc("## 旧状态\n2026-01-01 这条早该过期\n## 新的\n2026-11-10 还新\n")
        val result = AgentMemoryExpiry.archiveExpired(content, now, 90)
        assertTrue(result.contains("# 归档"))
        assertTrue(result.contains("2026-01-01 这条早该过期"))
        assertTrue(result.contains("## 新的"))
        assertTrue(result.contains("2026-11-10 还新"))
        // 旧节不在归档区之前残留
        val beforeArchive = result.substringBefore("# 归档")
        assertFalse(beforeArchive.contains("2026-01-01 这条早该过期"))
    }

    @Test
    fun recentSectionIsNotMoved() {
        val content = doc("## 当前\n2026-11-10 还新\n")
        val result = AgentMemoryExpiry.archiveExpired(content, now, 90)
        assertEquals(content, result) // 无变化
    }

    @Test
    fun sectionWithoutDateIsLeftAlone() {
        val content = doc("## 老格式\n内容没有日期 不动它\n")
        assertEquals(content, AgentMemoryExpiry.archiveExpired(content, now))
    }

    @Test
    fun alreadyArchivedSectionsAreNotRepeated() {
        val content = doc("## 活的\n2026-11-10 还新\n\n# 归档\n## 陈年\n2020-01-01 老档案\n")
        val result = AgentMemoryExpiry.archiveExpired(content, now, 90)
        assertEquals(content, result) // 归档区里的旧节不再处理，无变化
    }

    @Test
    fun firstDateParsing() {
        assertEquals(LocalDate.of(2026, 11, 13), AgentMemoryExpiry.firstDate("截至 2026-11-13 的状态"))
        assertEquals(null, AgentMemoryExpiry.firstDate("没有日期"))
    }

    @Test
    fun verifiedIsPreferredOverCreatedForAge() {
        // B4：created 很旧但 verified 是最近 → 不该被归档
        val section = "## 活跃\n<!-- eta:id=e00000000001;created=2026-01-01;verified=2026-11-10 -->\n还在维护\n"
        val content = doc(section)
        assertEquals(content, AgentMemoryExpiry.archiveExpired(content, now, 90))
    }

    @Test
    fun childSubsectionsMoveWithParent() {
        // B5：父节连同其下 ### 子节一起归档，不留孤儿
        val content = doc("## 旧项目\n<!-- eta:created=2026-01-01;verified=2026-01-01 -->\n概述\n### 子节A\n细节A\n### 子节B\n细节B\n## 新的\n2026-11-10 还新\n")
        val result = AgentMemoryExpiry.archiveExpired(content, now, 90)
        val before = result.substringBefore("# 归档")
        assertFalse(before.contains("子节A"))
        assertFalse(before.contains("子节B"))
        assertTrue(result.contains("子节A"))
        assertTrue(result.contains("子节B"))
        assertTrue(before.contains("## 新的"))
    }

    @Test
    fun parentAndChildBothExpiredAreNotDuplicatedOrDestructive() {
        // 复审阻断①：父子都过期时不能重复归档、不能反向删除误删后面的新节
        val content = doc(
            "## A\n<!-- eta:created=2026-01-01;verified=2026-01-01 -->\n父正文\n" +
                "### A1\n<!-- eta:created=2026-01-01;verified=2026-01-01 -->\n子正文\n" +
                "## B\n<!-- eta:created=2026-11-10;verified=2026-11-10 -->\n新节正文\n",
        )
        val result = AgentMemoryExpiry.archiveExpired(content, now, 90)
        // 新节 B 必须完整保留在归档区之前
        val before = result.substringBefore("# 归档")
        assertTrue("新节 B 不能被误删", before.contains("## B"))
        assertTrue("新节 B 正文不能被误删", before.contains("新节正文"))
        assertFalse("父 A 应移走", before.contains("## A\n"))
        // 子节正文在归档里只出现一次（不重复）
        val occurrences = result.split("子正文").size - 1
        assertEquals(1, occurrences)
    }

    @Test
    fun alreadyArchivedSameTitleDoesNotAccumulateDuplicate() {
        // M3：把同名节再次过期，归档区不产生第二个同名标题
        val first = doc("## 旧事\n<!-- eta:created=2026-01-01;verified=2026-01-01 -->\n第一次\n")
        val once = AgentMemoryExpiry.archiveExpired(first, now, 90)
        // 制造第二个同名过期节
        val second = once.replace("# 归档", "## 旧事\n<!-- eta:created=2026-01-02;verified=2026-01-02 -->\n第二次\n\n# 归档")
        val twice = AgentMemoryExpiry.archiveExpired(second, now, 90)
        val titleCount = twice.lineSequence().count { it.trim() == "## 旧事" }
        assertTrue("同名标题不应无限累积, 实际=$titleCount", titleCount <= 1)
    }

    @Test
    fun metadataWithIdOnlyFallsBackToBodyDate() {
        // 复审⑦：元数据只有 id 没日期时，退化到正文日期
        val content = doc("## 老节\n<!-- eta:id=e00000000001 -->\n2026-01-01 旧内容\n")
        val result = AgentMemoryExpiry.archiveExpired(content, now, 90)
        assertTrue(result.contains("# 归档"))
    }

    @Test
    fun dividerRecognition() {
        val layout = io.github.mangi.eta.data.repository.MemoryLayout
        assertTrue(layout.isDividerLine("---"))
        assertTrue(layout.isDividerLine("  ***  "))
        assertTrue(layout.isDividerLine("_____"))
        assertFalse(layout.isDividerLine("--"))
        assertFalse(layout.isDividerLine("# 标题"))
    }
}
