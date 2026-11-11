package io.github.mangi.eta.agent.runs

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 进程内「此刻正在跑什么」的轻量登记表。
 *
 * 存在的理由：Runtime 自己的 [io.github.mangi.eta.agent.runtime.RuntimeRunRegistry] 持有 Session、
 * controller、Messenger 等重对象，只适合服务内部使用；任务中心需要的只是"哪几条 run 还活着、什么来源、
 * 从什么时候开始"，因此这里用一份只读投影单独记账，任何 UI 层都可以安全订阅。
 *
 * 约定：
 * - 登记表只在同一进程内使用（AgentRuntimeService 与 UI 同进程，未声明 android:process）；
 * - [begin] 返回的 [Registration] 必须在运行结束时 close()，重复 close 是幂等的；
 * - 超过 [capacity] 时丢弃最旧的登记，保证异常路径也不会让列表无限增长；
 * - 所有读写在 [lock] 内完成，[active] 是唯一对外可观察状态，按登记顺序（先开始的在前）。
 */
internal class LiveRunRegistry(
    private val clock: () -> Long = System::currentTimeMillis,
    private val capacity: Int = MAX_ACTIVE_RUNS,
) {
    init {
        require(capacity > 0) { "capacity 必须为正数" }
    }

    data class Entry(
        val runId: String,
        val source: AgentRunSource,
        val title: String,
        /** 子代理运行时指向父 run；顶层 run 为 null。 */
        val parentRunId: String?,
        val startedAtMillis: Long,
        /** 单调递增的登记序号，仅用于稳定排序。 */
        val sequence: Long,
    ) {
        val isChild: Boolean get() = parentRunId != null

        companion object {
            const val MAX_TITLE_CHARS = 120
        }
    }

    private val lock = Any()
    private val sequence = AtomicLong()
    private val entries = LinkedHashMap<String, Entry>()
    private val activeState = MutableStateFlow<List<Entry>>(emptyList())

    /** 当前活跃登记，先开始的在前。 */
    val active: StateFlow<List<Entry>> = activeState.asStateFlow()

    fun snapshot(): List<Entry> = activeState.value

    /**
     * 登记一条运行。
     *
     * @param title 面向用户的短标题（会话名、子代理类型等），会被截断到 [Entry.MAX_TITLE_CHARS]。
     * @param parentRunId 子代理运行时传入父 run id。
     */
    fun begin(
        runId: String,
        source: AgentRunSource,
        title: String = "",
        parentRunId: String? = null,
    ): Registration {
        require(runId.isNotBlank()) { "runId 不能为空" }
        val entry = Entry(
            runId = runId,
            source = source,
            title = title.trim().replace(WHITESPACE, " ").take(Entry.MAX_TITLE_CHARS),
            parentRunId = parentRunId?.takeIf { it.isNotBlank() && it != runId },
            startedAtMillis = clock(),
            sequence = sequence.incrementAndGet(),
        )
        synchronized(lock) {
            entries[runId] = entry
            if (entries.size > capacity) {
                entries.keys.firstOrNull()?.let { oldest -> entries.remove(oldest) }
            }
            publishLocked()
        }
        return Registration(runId)
    }

    /** 运行结束；未知 runId 静默忽略（重复收尾是正常路径）。 */
    fun finish(runId: String) {
        synchronized(lock) {
            if (entries.remove(runId) == null) return
            publishLocked()
        }
    }

    /** 进程级清空（停止全部、退出调试等）。 */
    fun clear() {
        synchronized(lock) {
            if (entries.isEmpty()) return
            entries.clear()
            publishLocked()
        }
    }

    private fun publishLocked() {
        activeState.value = entries.values.toList()
    }

    /** 一次登记句柄；close() 幂等。 */
    inner class Registration internal constructor(val runId: String) : AutoCloseable {
        private val closed = java.util.concurrent.atomic.AtomicBoolean(false)

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            finish(runId)
        }
    }

    companion object {
        /** 同时活跃运行的软上限；Runtime 侧自己的上限是 8，这里留一点余量给子代理。 */
        const val MAX_ACTIVE_RUNS = 32
        private val WHITESPACE = Regex("\\s+")
    }
}

/** 进程内共享实例：Runtime 执行器登记，任务中心订阅。 */
internal val agentLiveRuns = LiveRunRegistry()
