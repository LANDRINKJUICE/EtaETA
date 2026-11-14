package io.github.mangi.eta.agent.schedule

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.runtime.AgentConversationIdentity
import io.github.mangi.eta.agent.runtime.AgentRuntimeClient
import io.github.mangi.eta.agent.runtime.AgentRuntimeWire
import io.github.mangi.eta.agent.runtime.AgentUiHandoffPayload
import io.github.mangi.eta.agent.runtime.BackgroundRunPolicy
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

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
 * 自动任务与后台唤醒一样，是「无人值守但可回复」的 AGENT_UI 会话：用稳定的
 * `conv-schedule-<hash(taskId)>` 会话 id（[AgentConversationIdentity.scheduledTaskConversationId]），
 * 运行一开始就能被 UI 认领进聊天列表、实时看到进度、结束后可继续对话，且统一投影到状态层。
 * 仍然走完整的 Runtime 管线（工具、危险闸门、记忆、技能都在），不是"绕过确认的后门"。
 */
internal object ScheduledTaskRunner {
    private const val PROMPT_MAX_CHARS = 4_000

    /**
     * 无进展超时：持续没有 AgentEvent（长思考 / 卡死 / 模型无响应）才取消，
     * 不再用「总墙钟 10 分钟」一刀切把正常的长任务误杀（用户反馈「常被停止 / 超时」的根）。
     */
    // idle 阈值不小于旧的总墙钟 10 分钟（见 BackgroundJobWakeup 同款注释）。
    const val IDLE_TIMEOUT_MILLIS = 10L * 60L * 1_000L
    /** 硬上限：无论是否有进展，一轮最多跑 60 分钟，防止卡死任务永久占住前台服务与模型配额。 */
    const val MAX_RUN_MILLIS = 60L * 60L * 1_000L
    /** 等待循环的轮询片长：兼顾取消灵敏度与线程唤醒开销。 */
    private const val WAIT_SLICE_MILLIS = 2L * 1_000L

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
            // 稳定可回复会话 id：执行中通知按会话深链直达，用户点进去就能观测 / 对话这个任务。
            .putExtra(
                ScheduledTaskService.EXTRA_CONVERSATION_ID,
                io.github.mangi.eta.agent.runtime.AgentConversationIdentity.scheduledTaskConversationId(taskId),
            )
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
            // 结果通知统一交给 Runtime 的 run_result 段（AgentExecutionService.showRunResult）：
            // 自动任务现在是可回复的 UI 会话，那条通知按会话深链、带通知栏回复、成功/失败都有，
            // 不再用 scheduled_task_result 段单发一条（避免双发 + 深链分叉）。状态层与结果层
            // 各自收敛到唯一出口，符合通知架构 SOP 的「统一编排、同 ID 变形」。
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
            // 构建请求就失败 = 根本没进 Runtime，不会触发 run_result 终态通知。
            // 这里用 scheduled_task_result 段发一条可见的失败兜底，避免「失败完全静默」；
            // 正常进入 Runtime 的成败仍由 run_result 段统一承担，不会双发。
            val error = throwable.message ?: throwable.javaClass.simpleName
            runCatching {
                ScheduledTaskNotifications.showResult(
                    context = context,
                    task = task,
                    succeeded = false,
                    summary = ScheduledTask.summarizeResult(error),
                )
            }
            return failureResult(runId, error)
        }
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "scheduled-task-run-${task.id.take(6)}").apply { isDaemon = true }
        }
        val startedAt = SystemClock.elapsedRealtime()
        val lastProgressAt = AtomicLong(startedAt)
        val activeTools = AtomicInteger(0)
        return try {
            val future = executor.submit<AgentRuntimeWire.RunResult> {
                // 每个 AgentEvent 都是「这一轮还活着」的证据；用单调时钟，不受系统对时影响。
                // 工具执行期间没有中间事件，记活跃工具数，看门狗不按 idle 误杀长工具。
                AgentRuntimeClient(context, AndroidAgentLogger).run(request) { event ->
                    lastProgressAt.set(SystemClock.elapsedRealtime())
                    when (event) {
                        is io.github.mangi.eta.agent.runtime.AgentEvent.ToolStarted,
                        is io.github.mangi.eta.agent.runtime.AgentEvent.HostedToolStarted -> activeTools.incrementAndGet()
                        is io.github.mangi.eta.agent.runtime.AgentEvent.ToolFinished,
                        is io.github.mangi.eta.agent.runtime.AgentEvent.HostedToolFinished ->
                            activeTools.updateAndGet { current -> (current - 1).coerceAtLeast(0) }
                        else -> Unit
                    }
                }
            }
            // 轮询等待：小片 future.get，按「持续无进展 / 硬上限」判定，而不是总墙钟。
            while (true) {
                try {
                    return future.get(WAIT_SLICE_MILLIS, TimeUnit.MILLISECONDS)
                } catch (sliceTimeout: TimeoutException) {
                    val now = SystemClock.elapsedRealtime()
                    if (BackgroundRunPolicy.shouldTimeOut(
                            nowMillis = now,
                            startedAtMillis = startedAt,
                            lastProgressAtMillis = lastProgressAt.get(),
                            idleTimeoutMillis = IDLE_TIMEOUT_MILLIS,
                            hardCapMillis = MAX_RUN_MILLIS,
                            activeTools = activeTools.get(),
                        )
                    ) {
                        AndroidAgentLogger.warn(
                            "Scheduled task ${task.id} watchdog timeout " +
                                "(idle=${IDLE_TIMEOUT_MILLIS}ms hardCap=${MAX_RUN_MILLIS}ms); cancelling",
                        )
                        future.cancel(true)
                        return failureResult(
                            runId,
                            context.getString(io.github.mangi.eta.R.string.schedule_run_timeout),
                        )
                    }
                }
            }
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
        // 无人值守但可回复的真实 UI 会话：稳定派生、同一任务多次触发 / 用户追问进同一会话。
        val conversationId = AgentConversationIdentity.scheduledTaskConversationId(task.id)
        val handoff = AgentRuntimeWire.EntryHandoff(
            id = "scheduled-$runId",
            source = AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE,
            payload = AgentUiHandoffPayload(
                conversationId = conversationId,
                entryTag = AgentUiHandoffPayload.ENTRY_TAG_SCHEDULED_TASK,
            ).toJson(),
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
     * 无人值守的指令包装：告诉模型"用户可能不在线"，它是已授权任务的延续轮、要把任务照常做到完成；
     * 安全闸门只硬守关键部位，命中时对所有来源一律照样弹卡、两分钟没人点则那一条不执行、绝不谎报完成，
     * 未执行的事实交模型自行判断其余任务照常完成。
     */
    internal fun buildPrompt(context: Context, task: ScheduledTask): String = buildString {
        appendLine(context.getString(io.github.mangi.eta.R.string.schedule_run_prompt_header, task.title))
        appendLine()
        appendLine(task.prompt.take(PROMPT_MAX_CHARS))
        appendLine()
        append(context.getString(io.github.mangi.eta.R.string.schedule_run_prompt_footer))
    }.trim()
}
