package io.github.mangi.eta.agent.memory

import io.github.mangi.eta.data.repository.AgentMemorySnapshot
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentMemoryContextBuilderTest {
    @Test
    fun coreBudgetTracksWindowWithSafeUnknownFallback() {
        assertEquals(8_000, AgentMemoryContextBuilder.coreBudgetChars(null))
        assertEquals(8_000, AgentMemoryContextBuilder.coreBudgetChars(128_000))
        assertEquals(16_000, AgentMemoryContextBuilder.coreBudgetChars(256_000))
        assertEquals(32_000, AgentMemoryContextBuilder.coreBudgetChars(1_000_000))
        assertEquals(4_000, AgentMemoryContextBuilder.coreBudgetChars(16_000))
    }

    @Test
    fun injectsOnlyCoreSectionAndBoundedHeadingIndex() {
        val content = "# 核心记忆\n长期偏好\n## 关系\n家人\n# 详细背景\n不应自动注入"
        val context = AgentMemoryContextBuilder.build(snapshot(content), 128_000)

        assertEquals("# 核心记忆\n长期偏好\n## 关系\n家人", context.coreContent)
        assertFalse(context.coreContent.contains("不应自动注入"))
        assertEquals("# 核心记忆\n## 关系\n# 详细背景", context.headingIndex)
        assertFalse(context.coreTruncated)
    }

    @Test
    fun oversizedCoreIsTruncatedWithoutDroppingRevision() {
        val content = "# 核心记忆\n" + "a".repeat(10_000)
        val snapshot = snapshot(content)
        val context = AgentMemoryContextBuilder.build(snapshot, null)

        assertEquals(8_000, context.coreContent.length)
        assertTrue(context.coreTruncated)
        assertEquals(snapshot.revision, context.revision)
    }

    @Test
    fun detailsWithoutCoreHeadingAreIndexedButNotAutomaticallyInjected() {
        val context = AgentMemoryContextBuilder.build(
            snapshot("# 项目\n只应按需读取的细节"),
            256_000,
        )

        assertEquals("", context.coreContent)
        assertEquals("# 项目", context.headingIndex)
    }

    @Test
    fun injectsOnlyTheMostRelevantNonCoreSections() {
        val content = "# 核心记忆\n长期偏好\n# 详细背景\n## 部署\n服务端口 8080\n## 饮食\n喜欢清淡"

        val context = AgentMemoryContextBuilder.build(
            snapshot = snapshot(content),
            contextWindow = 128_000,
            query = "部署端口是多少",
            now = LocalDate.of(2026, 5, 1),
        )

        assertEquals("# 核心记忆\n长期偏好", context.coreContent)
        assertEquals(listOf("## 部署"), context.injectedHeadings)
        assertTrue(context.injectedSections.contains("## 部署"))
        assertTrue(context.injectedSections.contains("服务端口 8080"))
        assertTrue(context.injectedSections.contains("[来源：MEMORY.md 第 4-5 行"))
        assertFalse(context.injectedSections.contains("## 饮食"))
    }

    @Test
    fun injectionIsQueryDependentAndOffWithoutAQuery() {
        val content = "# 核心记忆\n偏好\n# 细节\n## 部署\n端口 8080\n## 饮食\n喜欢清淡的川菜"

        val deploy = AgentMemoryContextBuilder.build(snapshot(content), 128_000, "部署在哪")
        val diet = AgentMemoryContextBuilder.build(snapshot(content), 128_000, "川菜吃什么")
        val noQuery = AgentMemoryContextBuilder.build(snapshot(content), 128_000)

        assertEquals(listOf("## 部署"), deploy.injectedHeadings)
        assertEquals(listOf("## 饮食"), diet.injectedHeadings)
        assertTrue(noQuery.injectedSections.isEmpty())
        assertEquals("", noQuery.injectedHeadings.joinToString())
    }

    @Test
    fun sectionsNestedInsideTheCoreBlockAreNotInjectedTwice() {
        val content = "# 核心记忆\n偏好\n## 部署\n端口 8080\n# 详细背景\n其他"

        val context = AgentMemoryContextBuilder.build(snapshot(content), 128_000, "部署端口")

        assertTrue(context.coreContent.contains("## 部署"))
        assertTrue(context.injectedSections.isEmpty())
    }

    @Test
    fun roleplaySessionsNeverInjectRelevantSectionsFromUserText() {
        val content = "# 核心记忆\n偏好\n# 细节\n## 部署\n端口 8080"
        val snapshot = snapshot(content)

        val roleplayQuery = AgentMemoryInjectionPolicy.recallQuery(
            prompt = "部署端口是多少",
            relevanceEnabled = true,
            roleplay = true,
        )
        val roleplayContext = AgentMemoryContextBuilder.build(snapshot, 128_000, roleplayQuery)

        assertNull(roleplayQuery)
        assertTrue(roleplayContext.injectedSections.isEmpty())
        assertTrue(roleplayContext.injectedHeadings.isEmpty())
        // 同样内容在普通会话里会命中，说明上面的空结果来自 roleplay 门控而不是内容不相关。
        assertTrue(
            AgentMemoryContextBuilder.build(snapshot, 128_000, "部署端口是多少")
                .injectedHeadings.isNotEmpty(),
        )
        assertNull(
            AgentMemoryInjectionPolicy.recallQuery("部署", relevanceEnabled = false, roleplay = false),
        )
    }

    @Test
    fun coreSectionIsNotCutByFencedHeadings() {
        val content = "# 核心记忆\n偏好\n```\n# 不是标题\n```\n## 部署\n端口 8080\n# 详细背景\n其他"

        val context = AgentMemoryContextBuilder.build(snapshot(content), 128_000, "部署端口")

        assertTrue(context.coreContent.contains("# 不是标题"))
        assertTrue(context.coreContent.contains("端口 8080"))
        assertEquals(2, context.coreContent.lines().count { it.trim() == "```" })
        assertTrue(context.injectedSections.isEmpty())
    }

    @Test
    fun coreKeepsPriorityOverRelevantSections() {
        val content = "# 核心记忆\n" + "a".repeat(10_000) + "\n## 部署\n端口 8080"

        val context = AgentMemoryContextBuilder.build(snapshot(content), null, "部署端口")

        assertTrue(context.coreTruncated)
        assertEquals("", context.injectedSections)
        assertEquals(0, context.injectedBudgetChars)
    }

    private fun snapshot(content: String): AgentMemorySnapshot = AgentMemorySnapshot(
        content = content,
        revision = "a".repeat(64),
        byteSize = content.toByteArray(Charsets.UTF_8).size,
        lineCount = content.lines().size,
    )
}
