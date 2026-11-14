package io.github.mangi.eta.data.repository

import android.util.AtomicFile
import io.github.mangi.eta.agent.memory.MemoryVectors
import java.io.File
import org.json.JSONObject

/**
 * 记忆节向量的本地缓存（单文件 JSON + AtomicFile）。
 *
 * 文件按 embedding 指纹隔离：换模型或换服务商后旧向量整体失效，不做增量兼容。
 *
 * 容量按维度反推，避免"条数上限看起来很大、实际写不进去"的冷启动重复付费：
 * 每条约 `维度 × 9 + 48` 个字符（5 位小数 + 分隔符 + id），条数上限取
 * `MAX_PAYLOAD_CHARS / 每节成本` 与 [MAX_ENTRIES] 的较小值；写入时用累计字符数判断，
 * 不做每次循环的整串序列化（旧实现的 O(n²)）。
 */
internal class AgentMemoryEmbeddingStore(
    rootDir: File,
) {
    private val memoryDir = File(rootDir, DIRECTORY_NAME)
    private val atomicFile = AtomicFile(File(memoryDir, FILE_NAME))
    private val lock = Any()

    /** 读取与当前指纹匹配的向量；指纹不符或文件损坏时返回空表。 */
    fun load(fingerprint: String): Map<String, FloatArray> = synchronized(lock) {
        val file = atomicFile.baseFile
        if (!file.exists()) return@synchronized emptyMap()
        val bytes = runCatching { atomicFile.openRead().use { it.readBytes() } }
            .getOrNull()
            ?: return@synchronized emptyMap()
        if (bytes.isEmpty() || bytes.size > MAX_FILE_BYTES) return@synchronized emptyMap()
        val root = runCatching { JSONObject(bytes.toString(Charsets.UTF_8)) }.getOrNull()
            ?: return@synchronized emptyMap()
        if (root.optString("fingerprint") != fingerprint) return@synchronized emptyMap()
        val vectors = root.optJSONObject("vectors") ?: return@synchronized emptyMap()
        val result = LinkedHashMap<String, FloatArray>()
        vectors.keys().forEach { id ->
            MemoryVectors.decode(vectors.optJSONArray(id))?.let { result[id] = it }
        }
        result
    }

    fun save(fingerprint: String, vectors: Map<String, FloatArray>) = synchronized(lock) {
        val dimension = vectors.values.firstOrNull { it.isNotEmpty() }?.size ?: 0
        val entryBudget = entryLimit(dimension)
        val payload = JSONObject()
        var stored = 0
        var usedChars = 0
        for ((id, vector) in vectors) {
            if (stored >= entryBudget) break
            if (vector.isEmpty() || vector.size > MAX_DIMENSIONS) continue
            val encoded = MemoryVectors.encode(vector)
            val cost = encoded.toString().length + ID_OVERHEAD_CHARS
            if (usedChars + cost > MAX_PAYLOAD_CHARS) break
            payload.put(id, encoded)
            usedChars += cost
            stored += 1
        }
        val body = JSONObject().put("fingerprint", fingerprint).put("vectors", payload)
        val bytes = body.toString().toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_FILE_BYTES) return@synchronized
        if (!memoryDir.exists() && !memoryDir.mkdirs() && !memoryDir.isDirectory) return@synchronized
        val output = runCatching { atomicFile.startWrite() }.getOrNull() ?: return@synchronized
        runCatching {
            output.write(bytes)
            atomicFile.finishWrite(output)
        }.onFailure {
            runCatching { atomicFile.failWrite(output) }
        }
    }

    fun clear() = synchronized(lock) {
        runCatching { atomicFile.delete() }
    }

    companion object {
        const val MAX_FILE_BYTES = 1024 * 1024
        const val MAX_DIMENSIONS = 4_096
        /** 绝对条数上限；实际可达条数还要看 [entryLimit] 的维度换算。 */
        const val MAX_ENTRIES = 512

        private const val MAX_PAYLOAD_CHARS = 780_000
        private const val ID_OVERHEAD_CHARS = 48
        private const val FLOAT_JSON_CHARS = 9
        private const val DIRECTORY_NAME = "memory"
        private const val FILE_NAME = "MEMORY.embeddings.json"

        /** 给定维度下真正能落盘的节数量上限（与字符预算自洽，不再出现"声称 256 实际 30"）。 */
        fun entryLimit(dimension: Int): Int {
            if (dimension <= 0) return MAX_ENTRIES
            val perEntry = dimension.toLong() * FLOAT_JSON_CHARS + ID_OVERHEAD_CHARS
            val byBudget = (MAX_PAYLOAD_CHARS / perEntry).toInt()
            return byBudget.coerceIn(1, MAX_ENTRIES)
        }
    }
}
