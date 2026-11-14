package io.github.mangi.eta.agent.terminal

import java.io.File
import org.json.JSONObject

/**
 * 自动唤醒的持久记账：每个任务链唤醒过几次、最后一次是什么时候。
 *
 * 为什么要落盘：进程可能在任何时候被系统回收，"同一任务 10 分钟内最多唤醒一次"与
 * "同一任务链最多连续 3 轮"必须跨进程存活，否则 App 重启就能把闸门清零，
 * 一条每 10 秒失败一次的任务足以把模型配额刷爆。
 *
 * 两条纪律：
 * - **写是原子的**：先写临时文件再 `renameTo` 覆盖，任何时刻读到的都是完整 JSON，
 *   断电/被杀不会留下半截文件；
 * - **不可用即降级**：读失败（文件存在但解析不了 / IO 报错）或写失败都会把
 *   `degraded` 抛给调用方，由 [JobWakeupPolicy] 把这一轮降级成"只发通知不唤醒"——
 *   没有账本就不敢自动唤醒（fail-closed），但也不会因此什么都不说。
 *
 * 纯 JVM 实现（只依赖 org.json 与 File），单测直接跑。
 */
internal class JobWakeupLedger(
    private val file: File,
    private val retentionMillis: Long = RETENTION_MILLIS,
    private val maxChains: Int = MAX_CHAINS,
    /** 读写失败的回调：Android 侧接日志，纯逻辑测试可注入收集器。 */
    private val onFailure: (String) -> Unit = {},
) {

    /** 读取结果：状态 + 是否降级（文件损坏/不可读）。 */
    data class Read(
        val state: JobWakeupPolicy.ChainState,
        val degraded: Boolean,
    ) {
        val available: Boolean get() = !degraded
    }

    /** 记录结果：状态 + 是否成功落盘。 */
    data class Record(
        val state: JobWakeupPolicy.ChainState,
        val persisted: Boolean,
    ) {
        val available: Boolean get() = persisted
    }

    /** 读取某条链的记账；文件不存在算"全新状态"，损坏或读不动算降级。 */
    fun read(chainId: String): Read {
        if (chainId.isBlank()) return Read(JobWakeupPolicy.ChainState(), degraded = false)
        return synchronized(LOCK) {
            val chains = readChains(reportFailure = true)
            val entry = chains?.get(chainId)
            when {
                chains == null -> Read(EMPTY, degraded = true)
                entry == null -> Read(EMPTY, degraded = false)
                else -> Read(
                    JobWakeupPolicy.ChainState(
                        wakeups = entry.optInt(KEY_WAKEUPS, 0).coerceAtLeast(0),
                        lastWakeAtMillis = entry.optLong(KEY_LAST_WAKE_AT, 0L),
                    ),
                    degraded = false,
                )
            }
        }
    }

    /** 记一次唤醒并返回记账后的状态；写失败时 [Record.persisted] 为 false，调用方必须据此降级。 */
    fun record(chainId: String, nowMillis: Long): Record {
        if (chainId.isBlank()) return Record(EMPTY, persisted = false)
        return synchronized(LOCK) {
            // 读失败也照样尝试写：写成功等于把账本修回来，写失败则如实报告降级。
            val chains = readChains(reportFailure = false) ?: linkedMapOf()
            val previous = chains[chainId]
            val wakeups = (previous?.optInt(KEY_WAKEUPS, 0) ?: 0).coerceAtLeast(0) + 1
            val entry = JSONObject()
                .put(KEY_WAKEUPS, wakeups)
                .put(KEY_LAST_WAKE_AT, nowMillis)
                .put(KEY_FIRST_WAKE_AT, previous?.optLong(KEY_FIRST_WAKE_AT, nowMillis) ?: nowMillis)
            chains[chainId] = entry
            val persisted = writeChains(chains, nowMillis)
            if (!persisted) onFailure("JOB_WAKEUP_LEDGER_WRITE_FAILED")
            Record(
                state = JobWakeupPolicy.ChainState(wakeups = wakeups, lastWakeAtMillis = nowMillis),
                persisted = persisted,
            )
        }
    }

    /** 当前全部记账（诊断与测试用）。 */
    fun snapshot(): Map<String, JobWakeupPolicy.ChainState> = synchronized(LOCK) {
        (readChains(reportFailure = false) ?: linkedMapOf()).mapValues { (_, entry) ->
            JobWakeupPolicy.ChainState(
                wakeups = entry.optInt(KEY_WAKEUPS, 0).coerceAtLeast(0),
                lastWakeAtMillis = entry.optLong(KEY_LAST_WAKE_AT, 0L),
            )
        }
    }

    /** 清空记账；设置页关掉开关或用户手动重置时使用。 */
    fun clear() = synchronized(LOCK) {
        runCatching { file.delete() }
            .onFailure { failure -> onFailure("JOB_WAKEUP_LEDGER_CLEAR_FAILED:${failure.javaClass.simpleName}") }
    }

    /** 读取全部链；文件不存在返回空表，损坏 / IO 失败返回 null（由调用方降级）。 */
    private fun readChains(reportFailure: Boolean): MutableMap<String, JSONObject>? {
        val raw = try {
            if (file.isFile) file.readText() else return linkedMapOf()
        } catch (failure: Exception) {
            if (reportFailure) onFailure("JOB_WAKEUP_LEDGER_READ_FAILED:${failure.javaClass.simpleName}")
            return null
        }
        if (raw.isBlank()) return linkedMapOf()
        val root = runCatching { JSONObject(raw) }.getOrNull()
        if (root == null) {
            if (reportFailure) onFailure("JOB_WAKEUP_LEDGER_CORRUPTED")
            return null
        }
        val chains = root.optJSONObject(KEY_CHAINS) ?: return linkedMapOf()
        val out = linkedMapOf<String, JSONObject>()
        chains.keys().forEach { key ->
            chains.optJSONObject(key)?.let { out[key] = it }
        }
        return out
    }

    /** 写回时顺手做保留期与条数收敛，避免文件随历史任务无限增长；返回是否真的落盘。 */
    private fun writeChains(chains: MutableMap<String, JSONObject>, nowMillis: Long): Boolean {
        val kept = chains.entries
            .filter { (_, entry) -> nowMillis - entry.optLong(KEY_LAST_WAKE_AT, 0L) <= retentionMillis }
            .sortedByDescending { (_, entry) -> entry.optLong(KEY_LAST_WAKE_AT, 0L) }
            .take(maxChains)
        val payload = JSONObject()
        kept.forEach { (key, entry) -> payload.put(key, entry) }
        val encoded = JSONObject().put(VERSION_KEY, VERSION).put(KEY_CHAINS, payload).toString()
        return try {
            file.parentFile?.mkdirs()
            val temporary = File(file.parentFile, file.name + TEMP_SUFFIX)
            temporary.writeText(encoded)
            if (!temporary.renameTo(file)) {
                // 某些文件系统上 rename 可能失败：退化为直接写，至少保证内容正确。
                file.writeText(encoded)
                temporary.delete()
            }
            true
        } catch (failure: Exception) {
            onFailure("JOB_WAKEUP_LEDGER_WRITE_FAILED:${failure.javaClass.simpleName}")
            false
        }
    }

    private companion object {
        /** 记录文件的读-改-写必须串行：App 进程内允许多个实例共存。 */
        val LOCK = Any()
        val EMPTY = JobWakeupPolicy.ChainState()
        const val VERSION = 1
        const val VERSION_KEY = "version"
        const val KEY_CHAINS = "chains"
        const val KEY_WAKEUPS = "wakeups"
        const val KEY_LAST_WAKE_AT = "last_wake_at"
        const val KEY_FIRST_WAKE_AT = "first_wake_at"
        const val TEMP_SUFFIX = ".tmp"

        /** 超过保留期的链没有判定价值，直接丢掉。 */
        const val RETENTION_MILLIS = 24L * 60L * 60L * 1_000L

        /** 记账条数上限：冷却只有 10 分钟，留 64 条足够覆盖并发任务。 */
        const val MAX_CHAINS = 64
    }
}
