package io.github.mangi.eta.agent.memory

import io.github.mangi.eta.agent.model.AgentHttpClient
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.ProviderUrls
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.data.model.ProviderTypes
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * 基于已配置模型提供商的 OpenAI 兼容 `/embeddings` 客户端。
 *
 * 只复用 baseUrl/apiKey：订阅登录（OAuth）与 Gemini/Anthropic 原生协议不在这里适配，
 * 这类场景由 [AgentMemoryEmbeddings.providerFor] 返回 null，调用方自动回退到关键词检索。
 */
internal class OpenAiCompatibleEmbeddingProvider(
    baseUrl: String,
    private val apiKey: String,
    private val model: String,
    private val client: OkHttpClient = AgentHttpClient.client,
) : EmbeddingProvider {
    private val endpoint = ProviderUrls.openAiEmbeddingsUrl(baseUrl)

    override val fingerprint: String = "${ProviderUrls.normalizeBaseUrl(baseUrl)}|$model"

    override suspend fun embed(texts: List<String>): List<FloatArray> = withContext(Dispatchers.IO) {
        if (texts.isEmpty()) return@withContext emptyList()
        val payload = JSONObject()
            .put("model", model)
            .put("input", JSONArray(texts))
            .toString()
        val request = Request.Builder()
            .url(endpoint)
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .apply {
                if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey")
            }
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("embeddings 请求失败：HTTP ${response.code}")
            }
            parse(body)
        }
    }

    private fun parse(body: String): List<FloatArray> {
        val data = JSONObject(body).optJSONArray("data")
            ?: throw IOException("embeddings 响应缺少 data")
        val ordered = ArrayList<Pair<Int, FloatArray>>(data.length())
        for (index in 0 until data.length()) {
            val item = data.optJSONObject(index) ?: continue
            val vector = MemoryVectors.decode(item.optJSONArray("embedding"))
                ?: throw IOException("embeddings 响应缺少向量")
            ordered += item.optInt("index", index) to vector
        }
        ordered.sortBy { it.first }
        val vectors = ordered.map { it.second }
        val dimension = vectors.firstOrNull()?.size ?: 0
        if (vectors.any { it.size != dimension }) throw IOException("embeddings 向量维度不一致")
        if (vectors.size != data.length()) throw IOException("embeddings 响应向量数量不足")
        return vectors
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

/** 从当前生效的模型配置派生 embedding 提供方；不可用时返回 null，由调用方降级。 */
internal object AgentMemoryEmbeddings {
    const val DEFAULT_MODEL = "text-embedding-3-small"

    fun providerFor(config: AgentModelClient.ModelConfig): EmbeddingProvider? {
        if (config.apiKey.isBlank()) return null
        if (config.providerType != ProviderTypes.OPENAI_COMPATIBLE) return null
        if (config.baseUrl.isBlank()) return null
        val model = Prefs
            .localAgentString(Prefs.Keys.AGENT_MEMORY_EMBEDDING_MODEL, DEFAULT_MODEL)
            .trim()
            .ifBlank { DEFAULT_MODEL }
        return OpenAiCompatibleEmbeddingProvider(
            baseUrl = config.baseUrl,
            apiKey = config.apiKey,
            model = model,
        )
    }
}
