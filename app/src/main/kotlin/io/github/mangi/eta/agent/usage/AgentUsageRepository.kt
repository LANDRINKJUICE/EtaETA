package io.github.mangi.eta.agent.usage

import android.content.Context
import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.core.AndroidAgentLogger
import java.io.File
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 用量统计的 Android 侧装配：目录、开关、读取与清理。
 *
 * 纯逻辑都在 [UsageStore] / [UsageAggregator] / [UsageTelemetry]；这里只负责把 Context 与
 * `Prefs` 接上去，保证埋点对象本身不依赖 Android，从而可以被单元测试直接驱动。
 *
 * 数据落地在 `filesDir/usage/usage.jsonl`：卸载即清除，不会进入任何网络请求，也没有导出路径。
 */
internal object AgentUsageRepository {
    private val initialized = AtomicBoolean(false)

    @Volatile
    private var sink: UsageLogSink? = null

    fun store(context: Context): UsageStore = UsageStore(File(context.applicationContext.filesDir, DIR_NAME))

    fun isEnabled(): Boolean = runCatching {
        Prefs.isEnabled(Prefs.Keys.AGENT_USAGE_STATS_ENABLED)
    }.getOrDefault(true)

    /** 开关与设置页共用同一份 Pref；写入失败返回 false，调用方负责提示。 */
    fun setEnabled(enabled: Boolean): Boolean = runCatching {
        Prefs.setAgentSwitch(Prefs.Keys.AGENT_USAGE_STATS_ENABLED, enabled, EtaApp.serviceInstance)
    }.getOrDefault(false)

    /** 进程启动时调用一次：装配异步落点，并把超过保留期的旧记录裁掉。 */
    fun initialize(context: Context) {
        if (!initialized.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        val created = UsageLogSink(store(appContext))
        sink = created
        UsageTelemetry.install(sink = created, enabled = ::isEnabled)
        Thread({
            runCatching { store(appContext).prune() }
        }, "eta-usage-prune").apply {
            isDaemon = true
            start()
        }
    }

    /** 等待队列落盘（打开用量页、导出前调用），失败不影响 UI。 */
    fun flush(timeoutMillis: Long = 1_500L) {
        runCatching { sink?.flush(timeoutMillis) }
    }

    /**
     * 读取并聚合。
     *
     * 只加载 30 日窗口内（含少量冗余）的记录：更早的数据既不在页面上展示，也没有聚合价值。
     */
    fun summarize(
        context: Context,
        nowMillis: Long = System.currentTimeMillis(),
        zone: TimeZone = TimeZone.getDefault(),
    ): UsageSummary {
        val since = UsageAggregator.windowStart(nowMillis, UsageAggregator.MONTH_DAYS, zone)
        val records = runCatching { store(context).load(since) }.getOrElse { throwable ->
            AndroidAgentLogger.warn("Agent usage load failed: ${throwable.javaClass.simpleName}")
            emptyList()
        }
        return UsageAggregator.summarize(records, nowMillis, zone)
    }

    fun clear(context: Context): Boolean = runCatching { store(context).clear() }.getOrDefault(false)

    private const val DIR_NAME = "usage"
}
