package io.github.mangi.eta.agent.usage

import io.github.mangi.eta.agent.runs.AgentRunSource
import java.util.Locale
import org.json.JSONObject

/**
 * 一次模型请求的用量元数据。
 *
 * 隐私红线：这里只保留计数与归类字段，**绝不**包含 prompt、回复正文、工具参数或任何用户内容；
 * [providerId]/[providerName]/[model] 只用于分组展示，长度也有上限，避免异常配置把行写爆。
 */
internal data class UsageRecord(
    /** 请求结束时刻（epoch millis）。 */
    val atMillis: Long,
    val source: AgentRunSource,
    /** 请求用途：chat / compaction / rewrite_reply 等，取 ProviderRequestPurpose 的小写名。 */
    val purpose: String,
    val providerId: String,
    val providerName: String,
    val model: String,
    val promptTokens: Int,
    val completionTokens: Int,
    val cachedTokens: Int,
    /** true 表示 prompt 或 completion 至少一侧是按字符数估算的，不是服务端回报的真实值。 */
    val estimated: Boolean,
    val runId: String,
) {
    val totalTokens: Int get() = promptTokens + completionTokens

    /** 单行 JSON（JSONL 存储格式）。字段顺序固定，便于人工排查。 */
    fun encode(): String = JSONObject()
        .put(KEY_AT, atMillis)
        .put(KEY_SOURCE, source.wireName)
        .put(KEY_PURPOSE, purpose)
        .put(KEY_PROVIDER_ID, providerId)
        .put(KEY_PROVIDER_NAME, providerName)
        .put(KEY_MODEL, model)
        .put(KEY_PROMPT, promptTokens)
        .put(KEY_COMPLETION, completionTokens)
        .put(KEY_CACHED, cachedTokens)
        .put(KEY_ESTIMATED, estimated)
        .put(KEY_RUN_ID, runId)
        .toString()

    companion object {
        const val MAX_FIELD_CHARS = 64
        const val MAX_ID_CHARS = 128

        private const val KEY_AT = "at"
        private const val KEY_SOURCE = "src"
        private const val KEY_PURPOSE = "purpose"
        private const val KEY_PROVIDER_ID = "provider"
        private const val KEY_PROVIDER_NAME = "provider_name"
        private const val KEY_MODEL = "model"
        private const val KEY_PROMPT = "prompt"
        private const val KEY_COMPLETION = "completion"
        private const val KEY_CACHED = "cached"
        private const val KEY_ESTIMATED = "estimated"
        private const val KEY_RUN_ID = "run"

        /**
         * 解析一行记录；损坏的行返回 null。
         *
         * 存储层只做"读得出来就统计，读不出来就跳过"，绝不让一条坏行挡住整页数据。
         */
        fun parse(line: String?): UsageRecord? {
            if (line.isNullOrBlank()) return null
            val json = runCatching { JSONObject(line) }.getOrNull() ?: return null
            val at = json.optLong(KEY_AT, 0L)
            if (at <= 0L) return null
            return UsageRecord(
                atMillis = at,
                source = AgentRunSource.fromWireName(json.optString(KEY_SOURCE)),
                purpose = sanitize(json.optString(KEY_PURPOSE)),
                providerId = sanitize(json.optString(KEY_PROVIDER_ID)),
                providerName = sanitize(json.optString(KEY_PROVIDER_NAME)),
                model = sanitize(json.optString(KEY_MODEL)),
                promptTokens = tokens(json.optInt(KEY_PROMPT, 0)),
                completionTokens = tokens(json.optInt(KEY_COMPLETION, 0)),
                cachedTokens = tokens(json.optInt(KEY_CACHED, 0)),
                estimated = json.optBoolean(KEY_ESTIMATED, false),
                runId = sanitize(json.optString(KEY_RUN_ID), MAX_ID_CHARS),
            )
        }

        internal fun sanitize(value: String, limit: Int = MAX_FIELD_CHARS): String =
            value.trim().replace(Regex("\\s+"), " ").take(limit)

        /** 负数与异常大值一律夹紧：统计页不该因为一条脏数据出现负 token。 */
        internal fun tokens(value: Int): Int = value.coerceIn(0, MAX_TOKENS)

        /** 单次请求的 token 上限（1e9），远超任何现有模型上下文，只用于挡住脏数据。 */
        const val MAX_TOKENS = 1_000_000_000

        /** 估算用的字符→token 经验比：中英混排下 4 字符约 1 token，来源不足时宁可偏保守。 */
        const val CHARS_PER_TOKEN = 4

        fun estimateTokens(chars: Int): Int {
            if (chars <= 0) return 0
            return ((chars + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN).coerceAtMost(MAX_TOKENS)
        }

        fun normalizePurpose(value: String): String =
            value.trim().lowercase(Locale.ROOT).ifBlank { "unknown" }.take(MAX_FIELD_CHARS)
    }
}

/**
 * 一条模型请求归属到哪条运行、哪类来源。
 *
 * 不作为可空参数随意传递：[io.github.mangi.eta.agent.model.AgentLoop] 只在拿到归属时才记账，
 * 因此所有"顺手调一下模型但不属于任何 run"的内部旁路（例如语音转写改写）默认不入账。
 */
internal data class UsageAttribution(
    val source: AgentRunSource,
    val runId: String,
)
