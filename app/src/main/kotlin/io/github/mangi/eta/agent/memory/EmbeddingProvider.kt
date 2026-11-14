package io.github.mangi.eta.agent.memory

import io.github.mangi.eta.data.repository.AgentMemoryEntry
import java.util.Locale
import kotlin.math.sqrt
import org.json.JSONArray

/** 文本向量提供方；实现方负责模型与传输，调用方只关心"能不能用"。 */
internal interface EmbeddingProvider {
    /** provider + 模型指纹；变化时此前缓存的向量视为失效。 */
    val fingerprint: String

    suspend fun embed(texts: List<String>): List<FloatArray>
}

/** 单条语义命中：节 + 余弦相似度。 */
internal data class SemanticMemoryHit(
    val entry: AgentMemoryEntry,
    val score: Double,
)

/** 向量计算与序列化的纯函数集合，便于单元测试。 */
internal object MemoryVectors {
    /** 余弦相似度；维度不一致或零向量返回 0。 */
    fun cosine(left: FloatArray, right: FloatArray): Double {
        if (left.isEmpty() || left.size != right.size) return 0.0
        var dot = 0.0
        var leftNorm = 0.0
        var rightNorm = 0.0
        for (index in left.indices) {
            dot += left[index] * right[index]
            leftNorm += left[index] * left[index]
            rightNorm += right[index] * right[index]
        }
        if (leftNorm <= 0.0 || rightNorm <= 0.0) return 0.0
        return dot / (sqrt(leftNorm) * sqrt(rightNorm))
    }

    /** 取相似度最高的 [limit] 条；低于 [minScore] 的丢弃，结果按分数降序。 */
    fun topK(
        query: FloatArray,
        candidates: List<Pair<String, FloatArray>>,
        limit: Int,
        minScore: Double = 0.0,
    ): List<Pair<String, Double>> {
        if (limit <= 0 || query.isEmpty()) return emptyList()
        return candidates
            .map { (id, vector) -> id to cosine(query, vector) }
            .filter { (_, score) -> score >= minScore }
            .sortedByDescending { (_, score) -> score }
            .take(limit)
    }

    /** 序列化为 JSON 数组，保留 5 位小数以控制文件体积。 */
    fun encode(vector: FloatArray): JSONArray = JSONArray().also { array ->
        vector.forEach { value ->
            array.put(String.format(Locale.US, "%.5f", value).toDouble())
        }
    }

    fun decode(array: JSONArray?): FloatArray? {
        if (array == null || array.length() == 0) return null
        val values = FloatArray(array.length())
        for (index in 0 until array.length()) {
            val value = array.optDouble(index, Double.NaN)
            if (value.isNaN()) return null
            values[index] = value.toFloat()
        }
        return values
    }
}
