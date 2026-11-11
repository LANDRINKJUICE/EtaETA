package io.github.mangi.eta.agent.usage

import io.github.mangi.eta.agent.runs.AgentRunSource
import java.util.Calendar
import java.util.TimeZone

/** 一个统计窗口内的累计值。计数用 Int、token 用 Long：单机 30 天也不会溢出，但求和无歧义。 */
internal data class UsageWindow(
    val requests: Int = 0,
    val promptTokens: Long = 0L,
    val completionTokens: Long = 0L,
    val cachedTokens: Long = 0L,
    /** 其中按字符数估算而非服务端回报的请求数。 */
    val estimatedRequests: Int = 0,
) {
    val totalTokens: Long get() = promptTokens + completionTokens

    fun accept(record: UsageRecord): UsageWindow = UsageWindow(
        requests = requests + 1,
        promptTokens = promptTokens + record.promptTokens,
        completionTokens = completionTokens + record.completionTokens,
        cachedTokens = cachedTokens + record.cachedTokens,
        estimatedRequests = estimatedRequests + if (record.estimated) 1 else 0,
    )

    companion object {
        val EMPTY = UsageWindow()
    }
}

/** 一个分组（来源或模型）在统计窗口内的累计值。 */
internal data class UsageGroup(
    val key: String,
    val window: UsageWindow,
)

/**
 * 用量页要展示的全部聚合结果。
 *
 * 窗口全部按**本地时区的自然日**切分（今日 = 今天 0 点起，7 日 = 含今天在内的 7 个自然日），
 * 不做"最近 24 小时"滚动窗口：用户看到的"今天用了多少"应当和日历一致。
 * [bySource] 与 [byModel] 基于 30 日窗口，因为这是唯一有足够样本做比例判断的范围。
 */
internal data class UsageSummary(
    val today: UsageWindow = UsageWindow.EMPTY,
    val week: UsageWindow = UsageWindow.EMPTY,
    val month: UsageWindow = UsageWindow.EMPTY,
    val bySource: List<UsageGroup> = emptyList(),
    val byModel: List<UsageGroup> = emptyList(),
    val generatedAtMillis: Long = 0L,
) {
    val isEmpty: Boolean get() = month.requests == 0
}

/** 纯函数聚合器：不读文件、不碰 Android，方便用固定时区与固定时间做回归测试。 */
internal object UsageAggregator {
    const val WEEK_DAYS = 7
    const val MONTH_DAYS = 30

    /** 统计窗口的起点：本地时区下「今天往前 days-1 天」的 0 点。 */
    fun windowStart(
        nowMillis: Long,
        days: Int,
        zone: TimeZone = TimeZone.getDefault(),
    ): Long {
        val span = days.coerceAtLeast(1)
        val calendar = Calendar.getInstance(zone).apply {
            timeInMillis = nowMillis
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (span > 1) add(Calendar.DAY_OF_MONTH, -(span - 1))
        }
        return calendar.timeInMillis
    }

    fun summarize(
        records: List<UsageRecord>,
        nowMillis: Long,
        zone: TimeZone = TimeZone.getDefault(),
    ): UsageSummary {
        val todayStart = windowStart(nowMillis, 1, zone)
        val weekStart = windowStart(nowMillis, WEEK_DAYS, zone)
        val monthStart = windowStart(nowMillis, MONTH_DAYS, zone)

        var today = UsageWindow.EMPTY
        var week = UsageWindow.EMPTY
        var month = UsageWindow.EMPTY
        val bySource = linkedMapOf<AgentRunSource, UsageWindow>()
        val byModel = linkedMapOf<String, UsageWindow>()

        records.forEach { record ->
            // 未来时间戳（设备时钟回拨过）不算进任何窗口，避免出现"今天用了负数天"的怪数据。
            if (record.atMillis > nowMillis) return@forEach
            if (record.atMillis >= monthStart) {
                month = month.accept(record)
                bySource[record.source] = (bySource[record.source] ?: UsageWindow.EMPTY).accept(record)
                val modelKey = record.model.ifBlank { record.providerName }.ifBlank { UNKNOWN_MODEL }
                byModel[modelKey] = (byModel[modelKey] ?: UsageWindow.EMPTY).accept(record)
            }
            if (record.atMillis >= weekStart) week = week.accept(record)
            if (record.atMillis >= todayStart) today = today.accept(record)
        }

        return UsageSummary(
            today = today,
            week = week,
            month = month,
            bySource = bySource.map { (source, window) -> UsageGroup(source.wireName, window) }
                .sortedWith(GROUP_ORDER),
            byModel = byModel.map { (model, window) -> UsageGroup(model, window) }
                .sortedWith(GROUP_ORDER),
            generatedAtMillis = nowMillis,
        )
    }

    private const val UNKNOWN_MODEL = "unknown"

    /** 先按 token 总量降序，再按请求数降序，最后按 key 保证顺序稳定（UI 不会每次刷新跳位）。 */
    private val GROUP_ORDER: Comparator<UsageGroup> = compareByDescending<UsageGroup> { it.window.totalTokens }
        .thenByDescending { it.window.requests }
        .thenBy { it.key }
}
