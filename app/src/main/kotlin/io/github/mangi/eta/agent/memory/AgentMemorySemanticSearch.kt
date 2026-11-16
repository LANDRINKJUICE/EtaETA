package io.github.mangi.eta.agent.memory

import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.data.repository.AgentMemoryEmbeddingStore
import io.github.mangi.eta.data.repository.AgentMemoryEntries
import io.github.mangi.eta.data.repository.AgentMemoryEntry
import io.github.mangi.eta.data.repository.AgentMemoryRepository
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** 语义检索结果；[content] 沿用 memory_get 的"行号: 内容"格式，便于后续 replace_range。 */
internal data class SemanticSearchOutcome(
    val content: String,
    val hits: List<SemanticMemoryHit>,
    val hasMore: Boolean,
    val indexedEntries: Int,
)

/**
 * 四期语义检索：向量缓存 + 余弦 top-k。
 *
 * 任何一步不可用（没有 provider、网络失败、向量为空、分数过低）都返回 null，
 * 调用方随即回退到一期关键词检索，因此开关打开也不会让 memory_get 变差。
 */
internal object AgentMemorySemanticIndex {
    private const val MIN_SCORE = 0.30
    private const val EMBED_BATCH = 8
    private const val MAX_EMBED_PER_CALL = 24
    private const val MAX_EMBED_CHARS = 2_000

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var store: AgentMemoryEmbeddingStore? = null

    private val cache = LinkedHashMap<String, FloatArray>()
    private var cacheFingerprint: String = ""

    fun init(rootDir: File) {
        if (store == null) store = AgentMemoryEmbeddingStore(rootDir)
    }

    suspend fun search(
        query: String,
        snapshotContent: String,
        limit: Int,
        maxChars: Int,
        provider: EmbeddingProvider?,
    ): SemanticSearchOutcome? {
        val embeddings = provider ?: return null
        if (query.isBlank() || limit <= 0 || snapshotContent.isBlank()) return null
        val entries = AgentMemoryEntries.parse(snapshotContent).filter { it.body.isNotBlank() }
        if (entries.isEmpty()) return null
        runCatching { ensureVectors(embeddings, entries) }
            .onFailure { throwable ->
                AndroidAgentLogger.warnThrottled("memory_embedding_failed") {
                    "Memory embedding failed: type=${throwable.safeLogType()}"
                }
            }
        // 向量不足时不阻断：哪怕只缓存了一部分节也能做 top-k。
        val vectors = cachedVectors(entries)
        if (vectors.isEmpty()) return null
        val queryVector = runCatching { embeddings.embed(listOf(query)).firstOrNull() }
            .getOrNull()
            ?: return null
        val byId = entries.associateBy { it.id }
        val hits = MemoryVectors
            .topK(queryVector, vectors.map { (id, vector) -> id to vector }, limit, MIN_SCORE)
            .mapNotNull { (id, score) -> byId[id]?.let { entry -> SemanticMemoryHit(entry, score) } }
        if (hits.isEmpty()) return null
        val rendered = render(snapshotContent, hits, maxChars)
        return SemanticSearchOutcome(
            content = rendered.first,
            hits = hits,
            hasMore = rendered.second,
            indexedEntries = vectors.size,
        )
    }

    /** memory_write 成功后的后台刷新：只补齐缺失向量，失败静默（下次检索再试）。 */
    fun refreshAsync(loadProvider: () -> EmbeddingProvider?) {
        scope.launch {
            runCatching {
                val provider = loadProvider() ?: return@launch
                val content = AgentMemoryRepository.snapshot().content
                val entries = AgentMemoryEntries.parse(content).filter { it.body.isNotBlank() }
                if (entries.isEmpty()) return@launch
                ensureVectors(provider, entries)
            }.onFailure { throwable ->
                AndroidAgentLogger.warnThrottled("memory_embedding_refresh_failed") {
                    "Memory embedding refresh failed: type=${throwable.safeLogType()}"
                }
            }
        }
    }

    fun reset() {
        synchronized(cache) {
            cache.clear()
            cacheFingerprint = ""
        }
        store = null
    }

    /**
     * 补齐缺失向量：锁内只做缓存读写（规划要嵌入哪些节、落盘新向量），
     * 真正的网络调用全部在锁外，避免一次慢请求把所有语义检索串行化。
     */
    private suspend fun ensureVectors(
        provider: EmbeddingProvider,
        entries: List<AgentMemoryEntry>,
    ) {
        val missing = planEmbedding(provider.fingerprint, entries)
        if (missing.isEmpty()) return
        val computed = LinkedHashMap<String, FloatArray>()
        missing.chunked(EMBED_BATCH).forEach { chunk ->
            val vectors = provider.embed(chunk.map { entry -> embeddingText(entry) })
            if (vectors.size != chunk.size) return@forEach
            chunk.forEachIndexed { index, entry -> computed[entry.id] = vectors[index] }
        }
        if (computed.isNotEmpty()) commitEmbeddings(provider.fingerprint, computed)
    }

    /** 锁内：按指纹加载磁盘缓存、剔除已消失的节，返回需要外部计算向量的节（有界）。 */
    private fun planEmbedding(
        fingerprint: String,
        entries: List<AgentMemoryEntry>,
    ): List<AgentMemoryEntry> = synchronized(cache) {
        val file = store
        if (cacheFingerprint != fingerprint) {
            cache.clear()
            if (file != null) cache.putAll(file.load(fingerprint))
            cacheFingerprint = fingerprint
        }
        val known = entries.mapTo(mutableSetOf()) { it.id }
        cache.keys.retainAll(known)
        entries.filter { it.id !in cache }.take(MAX_EMBED_PER_CALL)
    }

    /** 锁内：写入新算好的向量并持久化（磁盘写入也是纯本地操作）。 */
    private fun commitEmbeddings(fingerprint: String, computed: Map<String, FloatArray>) {
        synchronized(cache) {
            if (cacheFingerprint != fingerprint) return
            cache.putAll(computed)
            store?.save(fingerprint, cache.toMap())
        }
    }

    private fun cachedVectors(entries: List<AgentMemoryEntry>): Map<String, FloatArray> =
        synchronized(cache) {
            entries.mapNotNull { entry -> cache[entry.id]?.let { entry.id to it } }.toMap()
        }

    private fun embeddingText(entry: AgentMemoryEntry): String =
        (entry.heading + "\n" + entry.body).take(MAX_EMBED_CHARS)

    /** 复用 memory_get 的行号格式；命中之间用 `…` 表示省略。 */
    private fun render(
        content: String,
        hits: List<SemanticMemoryHit>,
        maxChars: Int,
    ): Pair<String, Boolean> {
        val lines = content.split('\n')
        val output = StringBuilder()
        var hasMore = false
        hits.forEachIndexed { position, hit ->
            if (position > 0) output.append('\n').append("…").append('\n')
            val range = hit.entry.startLine..hit.entry.endLine
            val lastLine = range.lastOrNull { lineNumber -> lines.getOrNull(lineNumber - 1)?.isNotBlank() == true }
                ?: hit.entry.startLine
            for (lineNumber in hit.entry.startLine..lastLine) {
                val line = lines.getOrNull(lineNumber - 1) ?: continue
                val rendered = "$lineNumber: $line"
                if (output.length + rendered.length + 1 > maxChars) {
                    hasMore = true
                    return@forEachIndexed
                }
                output.append(rendered).append('\n')
            }
        }
        return output.toString().trimEnd('\n') to hasMore
    }
}
