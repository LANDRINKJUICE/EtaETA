package io.github.mangi.eta.agent.usage

/**
 * 模型调用的用量埋点入口。
 *
 * 设计目标（按优先级）：
 * 1. **不阻塞、不影响主链路**：所有分支都包在 runCatching 里，异常一律吞掉；真正的写盘在
 *    [UsageLogSink] 的守护线程上完成，这里只做一次有界队列入队。
 * 2. **默认不侵入内部旁路**：只有调用方显式传入 [UsageAttribution] 时才记账，因此"顺手调一下
 *    模型"的内部实现（语音转写改写等）不会污染统计口径。
 * 3. **口径可解释**：服务端回报了 token 就用真实值，缺字段时才按字符数估算，并把 `estimated`
 *    落到记录里，页面上能与真实值区分显示。
 *
 * 进程内唯一入口，由 [io.github.mangi.eta.EtaApp] 在启动时 [install] 具体落点与开关。
 */
internal object UsageTelemetry {
    private object NoopSink : UsageSink {
        override fun offer(record: UsageRecord) = Unit
    }

    @Volatile
    private var sink: UsageSink = NoopSink

    @Volatile
    private var enabledProvider: () -> Boolean = { false }

    /** 装配落点与开关；重复调用会替换旧落点（[closePrevious] 由调用方负责）。 */
    fun install(sink: UsageSink, enabled: () -> Boolean) {
        this.sink = sink
        this.enabledProvider = enabled
    }

    /** 回到"不记录"状态；测试与退出流程使用。 */
    fun reset() {
        sink = NoopSink
        enabledProvider = { false }
    }

    fun isEnabled(): Boolean = safeEnabled()

    /**
     * 记录一次已完成的模型请求。
     *
     * @param attribution 归属；null 表示这次调用不参与统计。
     * @param promptChars prompt 的字符数（惰性求值：只有服务端没回报 input token 时才会真正计算）。
     * @param completionChars 本轮产出（正文 + 思考）字符数。
     */
    fun recordModelRound(
        attribution: UsageAttribution?,
        purpose: String,
        providerId: String,
        providerName: String,
        model: String,
        promptTokens: Int?,
        completionTokens: Int?,
        cachedTokens: Int?,
        promptChars: () -> Int,
        completionChars: Int,
        nowMillis: Long = System.currentTimeMillis(),
    ) {
        if (attribution == null) return
        if (!safeEnabled()) return
        val record = runCatching {
            buildRecord(
                attribution = attribution,
                purpose = purpose,
                providerId = providerId,
                providerName = providerName,
                model = model,
                promptTokens = promptTokens,
                completionTokens = completionTokens,
                cachedTokens = cachedTokens,
                promptChars = promptChars,
                completionChars = completionChars,
                nowMillis = nowMillis,
            )
        }.getOrNull() ?: return
        val target = sink
        runCatching { target.offer(record) }
    }

    /** 纯构造逻辑：不读开关、不写盘，便于单测直接钉住"何时估算、估算多少"。 */
    internal fun buildRecord(
        attribution: UsageAttribution,
        purpose: String,
        providerId: String,
        providerName: String,
        model: String,
        promptTokens: Int?,
        completionTokens: Int?,
        cachedTokens: Int?,
        promptChars: () -> Int,
        completionChars: Int,
        nowMillis: Long,
    ): UsageRecord {
        // 服务端报 0 但实际有内容时按"没报"处理：0 只可能是端点没填 usage，估算比 0 更有信息量。
        val reportedPrompt = promptTokens?.takeIf { it > 0 }
        val reportedCompletion = completionTokens?.takeIf { it > 0 }
        val prompt = reportedPrompt?.let(UsageRecord::tokens)
            ?: UsageRecord.estimateTokens(runCatching(promptChars).getOrDefault(0))
        val completion = reportedCompletion?.let(UsageRecord::tokens)
            ?: UsageRecord.estimateTokens(completionChars)
        return UsageRecord(
            atMillis = nowMillis,
            source = attribution.source,
            purpose = UsageRecord.normalizePurpose(purpose),
            providerId = UsageRecord.sanitize(providerId),
            providerName = UsageRecord.sanitize(providerName),
            model = UsageRecord.sanitize(model),
            promptTokens = prompt,
            completionTokens = completion,
            cachedTokens = cachedTokens?.let(UsageRecord::tokens) ?: 0,
            estimated = reportedPrompt == null || reportedCompletion == null,
            runId = UsageRecord.sanitize(attribution.runId, UsageRecord.MAX_ID_CHARS),
        )
    }

    private fun safeEnabled(): Boolean = runCatching { enabledProvider() }.getOrDefault(false)
}
