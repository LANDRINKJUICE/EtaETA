package io.github.mangi.eta.agent.schedule

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.runtime.AgentRuntimeClient
import io.github.mangi.eta.agent.runtime.AgentRuntimeWire
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

/**
 * 到点执行一条自动任务。
 *
 * 链路：[ScheduledTaskReceiver] 收到闹钟 -> **[advanceSchedule] 先把下一轮排期落盘并挂好闹钟** ->
 * 起 [ScheduledTaskService] 前台服务 -> [execute] 只决定"这一轮现在跑不跑" -> 普通 agent run ->
 * 结果写回存储 + 通知。
 *
 * 排期与派发分离是硬要求（审查 B1）：闹钟是一次性消耗品，如果先被并发闸门拦下、
 * 之后才推进下一次触发，被拦下的任务就再也不会被排期。因此 [advanceSchedule] 在启动服务之前
 * 完成，[execute] 里的闸门只影响"这一次跑不跑"，不影响后续。
 *
 * 这里刻意不复用聊天入口：没有会话、没有 UI handoff，conversationId 由唯一 runId 派生，
 * 因此不会和用户正在进行的对话互相打断；同时仍然走完整的 Runtime 管线（工具、危险闸门、
 * 记忆、技能都在），不是"绕过确认的后门"。
 */
internal object ScheduledTaskRunner {
    private const val PROMPT_MAX_CHARS = 4_000

    /** 无人值守单次运行的墙钟上限（审查 m11）：超时取消，避免一条任务长期占住前台服务与模型配额。 */
    const val MAX_RUN_MILLIS = 10L * 60L * 1_000L

    /** 闸门判定"另一个自动任务正在跑"时，一次性任务的重试点间隔。 */
    private const val BUSY_RETRY_DELAY_MILLIS = 2L * 60L * 1_000L

    private val running = AtomicBoolean(false)

    /** [advanceSchedule] 的结果：是否派发执行，以及给前台通知用的标题。 */
    data class AdvanceOutcome(val shouldRun: Boolean, val title: String = "")

    /**
     * 到点先排期，再决定是否派发。
     *
     * @return `shouldRun=true` 表示这一轮需要执行；`false` 表示已停用、已删除或已被认领（重复广播）。
     */
    fun advanceSchedule(context: Context, taskId: String): AdvanceOutcome {
        val appContext = context.applicationContext
        val store = ScheduledTaskStore(appContext)
        val scheduler = ScheduledTaskScheduler(appContext)
        val task = store.find(taskId) ?: run {
            scheduler.cancel(taskId)
            return AdvanceOutcome(shouldRun = false)
        }
        val now = System.currentTimeMillis()
        return when (val decision = ScheduledTaskAdvance.decide(task, now)) {
            ScheduledTaskAdvance.Decision.Skip -> {
                scheduler.cancel(task.id)
                store.updateNextTrigger(task.id, 0L)
                AdvanceOutcome(shouldRun = false, title = task.title)
            }
            ScheduledTaskAdvance.Decision.AlreadyClaimed -> {
                AndroidAgentLogger.debug { "Scheduled task ${task.id} already claimed; skip duplicate alarm" }
                AdvanceOutcome(shouldRun = false, title = task.title)
            }
            is ScheduledTaskAdvance.Decision.Run -> {
                // 先认领并写回：即使接下来的服务启动失败、进程被杀，下一次排期也已经落地，
                // 一次性任务也会在同一次开机/重启后被认领窗口保护住，不会重复执行。
                store.markClaimed(
                    id = task.id,
                    nextTriggerAtMillis = decision.nextTriggerAtMillis,
                    claimedAtMillis = now,
                )
                val next = decision.nextTriggerAtMillis
                if (next > 0L) {
                    scheduler.schedule(task.id, next)
                } else {
                    scheduler.cancel(task.id)
                }
                AdvanceOutcome(shouldRun = true, title = task.title)
            }
        }
    }

    /** 把实际执行交给前台服务，保证 run 期间进程不被回收。 */
    fun dispatch(context: Context, taskId: String, title: String = "") {
        val appContext = context.applicationContext
        val intent = Intent(appContext, ScheduledTaskService::class.java)
            .setAction(ScheduledTaskService.ACTION_RUN_TASK)
            .putExtra(ScheduledTaskService.EXTRA_TASK_ID, taskId)
            // 标题随 Intent 带过去，服务端不必在主线程读任务文件（审查 m6）。
            .putExtra(ScheduledTaskService.EXTRA_TASK_TITLE, title)
        val started = runCatching { appContext.startForegroundService(intent) }
        if (started.isSuccess) return
        AndroidAgentLogger.warnThrottled("scheduled_task_service_start_failed") {
            "Scheduled task foreground service refused: type=${started.exceptionOrNull()?.safeLogType()}"
        }
        // 前台服务被系统拒绝（后台限制）时仍尽力执行一次；排期已经在 advanceSchedule 里完成，
        // 所以即使进程被回收，任务本身也不会丢（审查 M7）。
        runCatching { ScheduledTaskService.startFallbackWorker(appContext, taskId) }
    }

    /**
     * 真正执行；调用方必须已经在非主线程（[ScheduledTaskService] 的工作线程）。
     *
     * 排期已经在 [advanceSchedule] 完成，这里只负责"跑或不跑"，以及写回执行结果。
     */
    fun execute(context: Context, taskId: String): ScheduledTask? {
        val appContext = context.applicationContext
        val store = ScheduledTaskStore(appContext)
        val scheduler = ScheduledTaskScheduler(appContext)
        val task = store.find(taskId) ?: run {
            AndroidAgentLogger.debug { "Scheduled task $taskId no longer exists; skip" }
            return null
        }
        if (!task.enabled) {
            scheduler.cancel(task.id)
            return task
        }
        val now = System.currentTimeMillis()
        if (!running.compareAndSet(false, true)) {
            // 同一时刻只跑一条：并发多任务会互相抢 GUI 与模型配额，用户也看不懂通知。
            // 注意：排期已经推进过，这里跳过不会导致停摆；一次性任务补一次延时重试。
            val retryAt = if (task.schedule.kind == ScheduleKind.ONCE) {
                (now + BUSY_RETRY_DELAY_MILLIS).also { retry ->
                    store.markClaimed(task.id, nextTriggerAtMillis = retry, claimedAtMillis = 0L)
                    scheduler.schedule(task.id, retry)
                }
            } else {
                task.nextTriggerAtMillis
            }
            AndroidAgentLogger.debug {
                "Scheduled task ${task.id} skipped: another scheduled run is active; retryAt=$retryAt"
            }
            store.recordRun(
                id = task.id,
                atMillis = now,
                outcome = TaskRunOutcome.SKIPPED,
                summary = appContext.getString(io.github.mangi.eta.R.string.schedule_skipped_busy_summary),
                durationMillis = 0L,
                nextTriggerAtMillis = retryAt,
            )
            return store.find(task.id)
        }
        try {
            val startedAt = SystemClock.elapsedRealtime()
            val result = runOnce(appContext, task)
            val durationMillis = SystemClock.elapsedRealtime() - startedAt
            val succeeded = result.ok
            val summary = ScheduledTask.summarizeResult(
                result.content.ifBlank { result.error.orEmpty() },
            )
            val updated = store.recordRun(
                id = task.id,
                atMillis = now,
                outcome = if (succeeded) TaskRunOutcome.OK else TaskRunOutcome.FAILED,
                summary = summary,
                durationMillis = durationMillis,
                // 下一轮已经由 advanceSchedule 写入；这里沿用，不覆盖。
                nextTriggerAtMillis = task.nextTriggerAtMillis,
            )
            ScheduledTaskNotifications.showResult(
                context = appContext,
                task = updated ?: task,
                succeeded = succeeded,
                summary = summary,
            )
            AndroidAgentLogger.debug {
                "Scheduled task ${task.id} finished: ok=$succeeded durationMs=$durationMillis"
            }
            return updated ?: task
        } finally {
            running.set(false)
        }
    }

    /**
     * 执行一次并施加墙钟上限。
     *
     * [AgentRuntimeClient.run] 是阻塞调用；放进单线程池后用 `future.get(timeout)` 等待，
     * 超时则中断工作线程——客户端会在中断里向 Runtime 发 MSG_CANCEL，run 被真正取消，
     * 而不是留下一个仍在后台烧模型的孤儿任务（审查 m11）。
     */
    private fun runOnce(context: Context, task: ScheduledTask): AgentRuntimeWire.RunResult {
        val runId = "scheduled-${task.id.take(8)}-${System.currentTimeMillis()}"
        val request = runCatching { buildRequest(context, task, runId) }.getOrElse { throwable ->
            AndroidAgentLogger.warn("Scheduled task request build failed: type=${throwable.safeLogType()}")
            return failureResult(runId, throwable.message ?: throwable.javaClass.simpleName)
        }
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "scheduled-task-run-${task.id.take(6)}").apply { isDaemon = true }
        }
        return try {
            val future = executor.submit<AgentRuntimeWire.RunResult> {
                AgentRuntimeClient(context, AndroidAgentLogger).run(request) { }
            }
            future.get(MAX_RUN_MILLIS, TimeUnit.MILLISECONDS)
        } catch (timeout: TimeoutException) {
            AndroidAgentLogger.warn("Scheduled task ${task.id} exceeded $MAX_RUN_MILLIS ms; cancelling")
            failureResult(
                runId,
                context.getString(io.github.mangi.eta.R.string.schedule_run_timeout),
            )
        } catch (throwable: Throwable) {
            if (throwable is InterruptedException) Thread.currentThread().interrupt()
            AndroidAgentLogger.warn("Scheduled task run failed: type=${throwable.safeLogType()}")
            failureResult(runId, throwable.message ?: throwable.javaClass.simpleName)
        } finally {
            executor.shutdownNow()
        }
    }

    private fun failureResult(runId: String, error: String): AgentRuntimeWire.RunResult =
        AgentRuntimeWire.RunResult(
            runId = runId,
            ok = false,
            content = "",
            error = error,
            contextSnapshotRef = runId,
        )

    private fun buildRequest(
        context: Context,
        task: ScheduledTask,
        runId: String,
    ): AgentRuntimeWire.RunRequest {
        val config = AgentModelClient.loadConfig()
        val handoff = AgentRuntimeWire.EntryHandoff(
            id = "scheduled-$runId",
            source = AgentRuntimeWire.ETA_SCHEDULED_TASK_HANDOFF_SOURCE,
            payload = JSONObject()
                .put("task_id", task.id)
                .put("title", task.title)
                .put("trigger_at", task.nextTriggerAtMillis)
                .toString(),
            dismissEntrySurfaceOnForegroundOperation = false,
        )
        return AgentRuntimeWire.RunRequest(
            runId = runId,
            prompt = buildPrompt(context, task),
            config = config,
            images = emptyList(),
            history = emptyList(),
            handoff = handoff,
        )
    }

    /**
     * 无人值守的指令包装：明确告诉模型"没有人在线"，并说明危险闸门在无人确认时会超时且不执行，
     * 避免它把"没执行"写成"已完成"，也避免它为了完成任务而反复重试同一操作。
     */
    internal fun buildPrompt(context: Context, task: ScheduledTask): String = buildString {
        appendLine(context.getString(io.github.mangi.eta.R.string.schedule_run_prompt_header, task.title))
        appendLine()
        appendLine(task.prompt.take(PROMPT_MAX_CHARS))
        appendLine()
        append(context.getString(io.github.mangi.eta.R.string.schedule_run_prompt_footer))
    }.trim()
}
