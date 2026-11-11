package io.github.mangi.eta.agent.memory

import io.github.mangi.eta.data.repository.AgentMemoryEmbeddingStore
import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentMemorySemanticSearchTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val content = listOf(
        "# 核心记忆",
        "长期偏好",
        "",
        "## 部署",
        "服务端口 8080",
        "",
        "## 饮食",
        "喜欢清淡",
    ).joinToString("\n")

    @After
    fun tearDown() {
        AgentMemorySemanticIndex.reset()
    }

    @Test
    fun semanticSearchRanksEntriesByVectorSimilarityWithLineNumbers() {
        AgentMemorySemanticIndex.init(temporaryFolder.newFolder())

        val outcome = runBlocking {
            AgentMemorySemanticIndex.search(
                query = "部署",
                snapshotContent = content,
                limit = 3,
                maxChars = 4_000,
                provider = DeployAwareEmbeddingProvider(),
            )
        }

        requireNotNull(outcome)
        assertEquals(listOf("## 部署"), outcome.hits.map { it.entry.heading })
        assertTrue(outcome.content.contains("4: ## 部署"))
        assertTrue(outcome.content.contains("5: 服务端口 8080"))
        assertEquals(3, outcome.indexedEntries)
    }

    @Test
    fun missingProviderFallsBackToKeywordRetrieval() {
        AgentMemorySemanticIndex.init(temporaryFolder.newFolder())

        val outcome = runBlocking {
            AgentMemorySemanticIndex.search(
                query = "部署",
                snapshotContent = content,
                limit = 3,
                maxChars = 4_000,
                provider = null,
            )
        }

        assertNull(outcome)
    }

    @Test
    fun failingEmbeddingProviderFallsBackInsteadOfFailingTheRead() {
        AgentMemorySemanticIndex.init(temporaryFolder.newFolder())

        val outcome = runBlocking {
            AgentMemorySemanticIndex.search(
                query = "部署",
                snapshotContent = content,
                limit = 3,
                maxChars = 4_000,
                provider = object : EmbeddingProvider {
                    override val fingerprint: String = "fake|broken"
                    override suspend fun embed(texts: List<String>): List<FloatArray> =
                        throw IOException("网络不可用")
                },
            )
        }

        assertNull(outcome)
    }

    @Test
    fun lowSimilarityHitsAreDroppedByTheMinimumScore() {
        AgentMemorySemanticIndex.init(temporaryFolder.newFolder())
        val weakProvider = object : EmbeddingProvider {
            override val fingerprint: String = "fake|weak"
            // 与查询的余弦相似度约 0.25，低于 MIN_SCORE(0.3)：必须整体降级回关键词检索。
            override suspend fun embed(texts: List<String>): List<FloatArray> = texts.map { text ->
                if (text == "部署") floatArrayOf(1f, 0f) else floatArrayOf(1f, 3.873f)
            }
        }

        val outcome = runBlocking {
            AgentMemorySemanticIndex.search("部署", content, limit = 3, maxChars = 4_000, provider = weakProvider)
        }

        assertNull(outcome)
    }

    @Test
    fun vectorStoreCapacityIsDerivedFromDimension() {
        val large = AgentMemoryEmbeddingStore.entryLimit(1_536)
        val small = AgentMemoryEmbeddingStore.entryLimit(256)

        assertTrue(large in 1 until AgentMemoryEmbeddingStore.MAX_ENTRIES)
        assertTrue(small >= large)
        assertEquals(AgentMemoryEmbeddingStore.MAX_ENTRIES, AgentMemoryEmbeddingStore.entryLimit(0))
        // 声明的条数必须真的放得进字符预算，否则冷启动会反复重算同一批向量。
        assertTrue(large.toLong() * (1_536L * 9 + 48) <= 800_000L)
    }

    @Test
    fun vectorsArePersistedPerFingerprint() {
        val root = temporaryFolder.newFolder()
        val store = AgentMemoryEmbeddingStore(root)
        val vectors = mapOf("e1" to floatArrayOf(1f, 0f), "e2" to floatArrayOf(0f, 1f))

        store.save("base|model-a", vectors)

        val sameFingerprint = store.load("base|model-a")
        assertEquals(setOf("e1", "e2"), sameFingerprint.keys)
        assertEquals(1f, sameFingerprint.getValue("e1")[0], 0.0001f)
        assertTrue(store.load("base|model-b").isEmpty())
        assertTrue(AgentMemoryEmbeddingStore(File(root, "missing")).load("base|model-a").isEmpty())
    }

    private class DeployAwareEmbeddingProvider : EmbeddingProvider {
        override val fingerprint: String = "fake|deploy"

        override suspend fun embed(texts: List<String>): List<FloatArray> = texts.map { text ->
            if (text.contains("部署")) floatArrayOf(1f, 0f) else floatArrayOf(0f, 1f)
        }
    }
}
