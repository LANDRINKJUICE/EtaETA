package io.github.mangi.eta.agent.schedule

import android.content.Context
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 自动任务的持久化存储。
 *
 * 用单个 JSON 文件而不是数据库：任务总量上限 50，读取总是整表，写入只发生在用户/Agent 的
 * 显式操作之后，一个文件即可保证"进程被杀或重启后不丢"，也便于备份与人工排查。
 *
 * 写入走"临时文件 + 原子 move"：中途被杀不会留下半截文件，也不会出现"先删旧文件、rename 失败"
 * 导致整表丢失的窗口（审查 M8）。所有读写都在 [LOCK] 上串行化，UI 主线程与后台 run 线程可能同时触发；
 * 任务总量上限 50、读取总是整表，因此不做内存缓存——缓存只会在跨进程/多入口时变成陈旧数据源。
 */
internal class ScheduledTaskStore(private val context: Context) {
    private val appContext = context.applicationContext

    private fun file(): File = File(File(appContext.filesDir, DIR_NAME), FILE_NAME)

    fun load(): List<ScheduledTask> = synchronized(LOCK) {
        val file = file()
        if (!file.isFile) return emptyList()
        val report = runCatching { ScheduledTaskCodec.decodeWithReport(file.readText()) }
            .getOrElse { throwable ->
                AndroidAgentLogger.warnThrottled("scheduled_task_load_failed") {
                    "Scheduled task file unreadable: type=${throwable.safeLogType()}"
                }
                ScheduledTaskCodec.Report(emptyList(), 0)
            }
        if (report.skipped > 0) {
            AndroidAgentLogger.warnThrottled("scheduled_task_skipped_records") {
                "Scheduled task file contained ${report.skipped} unreadable records"
            }
        }
        report.tasks
    }

    fun find(id: String): ScheduledTask? = load().firstOrNull { it.id == id }

    /**
     * 覆盖整表；调用方负责保证 id 唯一与数量上限。
     *
     * 先写临时文件，再用一次原子 move 覆盖目标：不做"先删后改名"，否则两步之间被杀就整表丢失。
     */
    fun saveAll(tasks: List<ScheduledTask>): Boolean = synchronized(LOCK) {
        val trimmed = tasks.take(ScheduledTask.MAX_TASKS)
        val target = file()
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, "$FILE_NAME.tmp")
        return runCatching {
            temp.writeText(ScheduledTaskCodec.encode(trimmed))
            replaceAtomically(temp, target)
            true
        }.getOrElse { throwable ->
            runCatching { temp.delete() }
            AndroidAgentLogger.warnThrottled("scheduled_task_save_failed") {
                "Scheduled task file write failed: type=${throwable.safeLogType()}"
            }
            false
        }
    }

    private fun replaceAtomically(temp: File, target: File) {
        val moved = runCatching {
            Files.move(
                temp.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
            true
        }.getOrDefault(false)
        // 少数文件系统（部分 bind mount）不支持 ATOMIC_MOVE：退回同目录 rename，仍是单步替换。
        if (!moved && !temp.renameTo(target)) error("无法提交任务文件")
    }

    fun upsert(task: ScheduledTask): Boolean = synchronized(LOCK) {
        val current = load()
        val index = current.indexOfFirst { it.id == task.id }
        val next = when {
            index >= 0 -> current.toMutableList().also { it[index] = task }
            current.size >= ScheduledTask.MAX_TASKS -> return false
            else -> current + task
        }
        saveAll(next)
    }

    fun delete(id: String): Boolean = synchronized(LOCK) {
        val current = load()
        val next = current.filterNot { it.id == id }
        if (next.size == current.size) return false
        saveAll(next)
    }

    /** 只改启用状态，下一次触发时间由调度层重新计算后写回。 */
    fun setEnabled(id: String, enabled: Boolean): ScheduledTask? = synchronized(LOCK) {
        val current = load()
        val task = current.firstOrNull { it.id == id } ?: return null
        val updated = task.copy(enabled = enabled)
        if (!saveAll(current.map { if (it.id == id) updated else it })) return null
        updated
    }

    /**
     * 记录一次执行的终态。
     *
     * [nextTriggerAtMillis] 为 0 且任务是一次性规则时，同时把它停用：保留历史结果，但不再排期。
     */
    fun recordRun(
        id: String,
        atMillis: Long,
        outcome: TaskRunOutcome,
        summary: String,
        durationMillis: Long,
        nextTriggerAtMillis: Long,
    ): ScheduledTask? = synchronized(LOCK) {
        val current = load()
        val task = current.firstOrNull { it.id == id } ?: return null
        val updated = task.copy(
            lastRunAtMillis = atMillis,
            lastOutcome = outcome,
            lastResult = summary.take(ScheduledTask.MAX_RESULT_CHARS),
            lastDurationMillis = durationMillis.coerceAtLeast(0L),
            runCount = task.runCount + if (outcome == TaskRunOutcome.SKIPPED) 0 else 1,
            nextTriggerAtMillis = nextTriggerAtMillis.coerceAtLeast(0L),
            // 本轮执行结束：清掉认领标记，下一次到点重新认领。
            claimAtMillis = 0L,
            enabled = task.enabled && (nextTriggerAtMillis > 0L || task.schedule.kind != ScheduleKind.ONCE),
        )
        if (!saveAll(current.map { if (it.id == id) updated else it })) return null
        updated
    }

    /**
     * 到点认领：写回下一次触发时间并记录认领时刻（审查 M2）。
     *
     * 这两件事必须在派发执行之前完成，否则被跳过的任务会永久停摆（审查 B1）。
     */
    fun markClaimed(id: String, nextTriggerAtMillis: Long, claimedAtMillis: Long): ScheduledTask? =
        synchronized(LOCK) {
            val current = load()
            val task = current.firstOrNull { it.id == id } ?: return null
            val updated = task.copy(
                nextTriggerAtMillis = nextTriggerAtMillis.coerceAtLeast(0L),
                claimAtMillis = claimedAtMillis.coerceAtLeast(0L),
            )
            if (!saveAll(current.map { if (it.id == id) updated else it })) return null
            updated
        }

    /** 只更新下次触发时间（重新排期后回写），不改变执行历史。 */
    fun updateNextTrigger(id: String, nextTriggerAtMillis: Long): ScheduledTask? = synchronized(LOCK) {
        val current = load()
        val task = current.firstOrNull { it.id == id } ?: return null
        val updated = task.copy(nextTriggerAtMillis = nextTriggerAtMillis.coerceAtLeast(0L))
        if (!saveAll(current.map { if (it.id == id) updated else it })) return null
        updated
    }

    companion object {
        private const val DIR_NAME = "scheduled-tasks"
        private const val FILE_NAME = "tasks.json"

        /** 进程内唯一的串行化点；store 实例可以有很多，文件只有一个。 */
        private val LOCK = Any()
    }
}
