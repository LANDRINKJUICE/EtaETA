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
    fun detailsWithoutCoreHeadingAreInjectedViaFallbackInsteadOfSilentlyEmpty() {
        // 2026-11-13 改造：没有 # 核心记忆 时不再静默空转，H1 兜底把第一个 H1 区整体常驻注入。
        val context = AgentMemoryContextBuilder.build(
            snapshot("# 项目\n只应按需读取的细节"),
            256_000,
        )

        assertEquals("# 项目\n只应按需读取的细节", context.coreContent)
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

    @Test
    fun pinnedTopIsInjectedUnconditionallyWithoutQuery() {
        val content = "我的铁律：随便写、没格式、没日期\n第二条也照进\n\n---\n\n# 核心记忆\n核心内容\n# 细节\n## 部署\n端口 8080"
        val context = AgentMemoryContextBuilder.build(snapshot(content), 128_000)
        assertTrue(context.coreContent.contains("我的铁律：随便写、没格式、没日期"))
        assertTrue(context.coreContent.contains("第二条也照进"))
        assertTrue(context.injectedSections.isEmpty())
    }

    @Test
    fun pinnedTopNeverSilentlyEmptyWhenFileNonEmpty() {
        val content = "只有顶部一段自由文本\n---\n## 某节\n2026-11-13 内容"
        val context = AgentMemoryContextBuilder.build(snapshot(content), 128_000)
        assertTrue(context.coreContent.isNotBlank())
        assertTrue(context.coreContent.contains("只有顶部一段自由文本"))
    }

    @Test
    fun pinnedTopIsIndependentOfQuery() {
        val content = "顶部固定内容\n---\n## 部署\n2026-11-13 端口 8080"
        val a = AgentMemoryContextBuilder.build(snapshot(content), 128_000, "部署端口")
        val b = AgentMemoryContextBuilder.build(snapshot(content), 128_000, "完全无关的话题")
        assertEquals(a.coreContent, b.coreContent)
        assertTrue(a.coreContent.contains("顶部固定内容"))
    }

    @Test
    fun pinnedTopCoexistsWithLegacyCore() {
        val content = "顶部\n---\n# 核心记忆\n核心\n# 背景\n## 部署\n2026-11-13 端口"
        val context = AgentMemoryContextBuilder.build(snapshot(content), 128_000)
        assertTrue(context.coreContent.contains("顶部"))
        assertTrue(context.coreContent.contains("核心"))
        assertFalse(context.coreContent.contains("# 背景"))
    }

    @Test
    fun singleH1FallbackInjectsWholeFileNotJustHeading() {
        // 复审③：只有一个 H1 时兜底取整份，不能只剩标题行
        val content = "# 项目\n第一段\n## 关系\n家人\n## 偏好\n清淡"
        val context = AgentMemoryContextBuilder.build(snapshot(content), 128_000)
        assertTrue(context.coreContent.contains("第一段"))
        assertTrue(context.coreContent.contains("家人"))
        assertTrue(context.coreContent.contains("清淡"))
    }

    @Test
    fun pinnedFallbackSectionIsNotAlsoInjectedAsRelevant() {
        // 复审④：分支③常驻区既全量注入，就不能再作为相关性候选被注入一次
        val content = "# 工作规范\n## 交付\n2026-11-13 出包走 SOP\n# 项目\n## 当前状态\nsome"
        val context = AgentMemoryContextBuilder.build(snapshot(content), 128_000, "交付 出包 SOP")
        // 常驻区（第一个 H1 区）已在 coreContent 里
        assertTrue(context.coreContent.contains("## 交付"))
        // 相关性注入里不能再出现 ## 交付（避免重复）
        assertFalse(context.injectedSections.contains("## 交付"))
    }

    private fun snapshot(content: String): AgentMemorySnapshot = AgentMemorySnapshot(
        content = content,
        revision = "a".repeat(64),
        byteSize = content.toByteArray(Charsets.UTF_8).size,
        lineCount = content.lines().size,
    )
}
