package io.github.mangi.eta.agent.terminal

import android.content.Context
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.runtime.AgentExternalArchivePayload
import io.github.mangi.eta.agent.runtime.AgentRuntimeWire
import io.github.mangi.eta.core.AgentLogger
import io.github.mangi.eta.core.safeLogType
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 后台命令结束后的自动唤醒接线：判定 -> 通知 -> 申请执行租约 -> 发起一轮 agent run。
 *
 * 链路：[BackgroundTerminalJobs] 巡检发现任务结束 -> [onJobFinished] ->
 * [JobWakeupPolicy] 判定 -> （需要唤醒时）[JobWakeupNotifications] 告知 ->
 * [AgentRuntimeClient.run] 走完整 Runtime 管线（工具、危险闸门、记忆、技能都在）跑一轮 ->
 * 结束后释放租约。
 *
 * 三条硬约束：
 * 1. **不阻塞巡检**：本方法要立刻返回，真正的判定/读取/运行都在自己的线程上做，
 *    否则会把"每秒一次的任务巡检"拖住；
 * 2. **无人值守**：危险操作仍会被闸门拦下并等待用户确认，无人确认就超时且不执行——
 *    唤醒不是绕过确认的后门，只是"替用户先看一眼";
 * 3. **不覆盖用户会话**：唤醒 run 的 conversationId 由 source + conversationKey 派生
 *    （见 [JobWakeupPolicy.conversationKey]），与用户会话、角色会话都隔离。
 *
 * 防死循环由 [JobWakeupPolicy] + [JobWakeupLedger] + [JobWakeupPolicy.WakeupBudget] 共同保证：
 * 来源标记（唤醒 run 起的任务不再唤醒）、10 分钟冷却、同一任务链最多
 * [JobWakeupPolicy.MAX_CHAIN_WAKEUPS] 轮、跨任务全局配额（10 分钟 2 次）。
 *
 * **通知收尾**：只要发出过"正在自动处理"，这一轮结束（成功/失败/超时/没能启动）必须用同一
 * 通知 ID 更新成终态——不能留一条永远显示"正在处理"的通知。
 */
internal class BackgroundJobWakeup(
    private val context: Context,
    private val logger: AgentLogger,
    ledgerFile: File,
    private val enabled: () -> Boolean = { true },
    private val acquireLease: (String, () -> Unit) -> Boolean = { _, _ -> true },
    private val releaseLease: (String) -> Unit = {},
    private val notifyRunning: (DetachedTask, Boolean) -> Unit = { _, _ -> },
    /** 终态通知：成功/失败/超时/没能启动，与 [notifyRunning] 同一通知 ID。 */
    private val notifyFinished: (DetachedTask, JobWakeupPolicy.Finish) -> Unit = { _, _ -> },
    /** 只告知不唤醒：第二个参数为 true 表示"跨任务全局配额用尽"（区别于单链上限）。 */
    private val notifyChainLimit: (DetachedTask, Boolean) -> Unit = { _, _ -> },
    private val readOutputTail: (DetachedTask, Int) -> String = { _, _ -> "" },
    /**
     * 跑一轮 agent run 并返回其结果；返回 null 表示客户端没有拿到结果。
     * 由 [TerminalRuntime] 接 [io.github.mangi.eta.agent.runtime.AgentRuntimeClient.run]，
     * 这样"跑完是否成功"能一路透到通知终态。
     */
    private val runWakeup: (AgentRuntimeWire.RunRequest) -> AgentRuntimeWire.RunResult? = { null },
    private val clock: () -> Long = System::currentTimeMillis,
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "eta-job-wakeup").apply { isDaemon = true }
    },
) : AutoCloseable {

    private val ledger = JobWakeupLedger(ledgerFile)

    /** 跨任务全局配额：滑动窗口内最多 [JobWakeupPolicy.MAX_WAKEUPS_PER_WINDOW] 次。 */
    private val budget = JobWakeupPolicy.WakeupBudget()

    /** 无人值守 run 串行执行：与自动任务同一纪律，避免两条 run 抢模型配额与 GUI。 */
    private val running = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    /**
     * 租约是否已经释放（true = 没有持有）。
     *
     * 执行线程的 finally 与派发失败路径都会走到释放，只能释放一次：重复
     * `AgentExecutionService.release` 会把别的 run 持有的同名租约一起放掉。
     */
    private val leaseReleased = AtomicBoolean(true)

    /** 正在跑的那一轮；用户在执行通知里点"停止"时会中断它（客户端随即向 Runtime 发 MSG_CANCEL）。 */
    @Volatile private var activeWorker: java.util.concurrent.Future<*>? = null

    /**
     * 任务完成的入口；由 [BackgroundTerminalJobs] 在巡检线程调用。
     *
     * 只做"判定 + 记账 + 派发"，不做网络与模型调用。
     */
    fun onJobFinished(task: DetachedTask) {
        if (!task.commandTask) return
        val signal = task.toWakeupSignal()
        val nowMillis = clock()
        // 账本读不出来（文件损坏 / IO 失败）时 JobWakeupPolicy 会降级成"只发通知"。
        val chain = ledger.read(signal.chainId)
        val decision = JobWakeupPolicy.decide(
            signal = signal,
            enabled = enabled(),
            nowMillis = nowMillis,
            chain = chain.state,
            ledgerAvailable = chain.available,
            globalAllowed = budget.allows(nowMillis),
        )
        when (decision.outcome) {
            JobWakeupPolicy.Outcome.SKIP -> logger.info(
                "Background job wakeup skipped: jobId=${task.id} code=${decision.code}",
            )
            JobWakeupPolicy.Outcome.NOTIFY_ONLY -> {
                // 仍然告知用户"这条任务没人接手"，只是不再自动唤醒。
                when (decision.code) {
                    // 账本不可用 = 这次根本没启动，用"未自动处理"的终态文案，不谎称处理过。
                    JobWakeupPolicy.CODE_LEDGER_UNAVAILABLE ->
                        notifyFinishedSafely(task, JobWakeupPolicy.Finish.NOT_STARTED)
                    else -> notifyChainLimitSafely(
                        task,
                        globalLimit = decision.code == JobWakeupPolicy.CODE_GLOBAL_LIMIT,
                    )
                }
                logger.warn("Background job wakeup limited: jobId=${task.id} code=${decision.code}")
            }
            JobWakeupPolicy.Outcome.WAKE -> wake(task, signal, decision.code)
        }
    }

    private fun wake(task: DetachedTask, signal: JobWakeupPolicy.Signal, code: String) {
        if (closed.get()) return
        if (!running.compareAndSet(false, true)) {
            // 上一条无人值守 run 还没结束：本次不再排队（排队会让"处理"离失败越来越远）。
            logger.warn("Background job wakeup skipped: jobId=${task.id} reason=wakeup_already_running")
            return
        }
        val leaseId = LEASE_PREFIX + task.id
        leaseReleased.set(false)
        if (!acquireLease(leaseId) {
                // 用户在执行通知里停止任务：正在跑的唤醒 run 也一并中断，不留孤儿。
                activeWorker?.cancel(true)
            }) {
            leaseReleased.set(true)
            running.set(false)
            // 后台限制（例如进程刚被回收）下不再硬跑：磁盘上的任务记录与通知都还在，
            // 用户回到 Eta 后仍能自己处理，绝不假装"已经在自动处理"。
            logger.warn("Background job wakeup aborted: jobId=${task.id} reason=execution_lease_denied")
            notifyFinishedSafely(task, JobWakeupPolicy.Finish.NOT_STARTED)
            return
        }
        // 先记账再启动：进程在 run 期间被杀也不会把冷却与链条计数清零。
        val nowMillis = clock()
        val recorded = ledger.record(signal.chainId, nowMillis)
        if (!recorded.available) {
            // 账本写不进去就不知道这条链跑过几轮：不敢唤醒（fail-closed），但要说清楚没跑。
            logger.warn(
                "Background job wakeup aborted: jobId=${task.id} reason=wakeup_ledger_unavailable",
            )
            notifyFinishedSafely(task, JobWakeupPolicy.Finish.NOT_STARTED)
            releaseLeaseOnce(leaseId)
            return
        }
        // 真正要唤醒才占用全局配额：被其它闸门拦下的调用不该消耗额度。
        budget.commit(nowMillis)
        notifyRunningSafely(task, signal.watch)
        logger.info(
            "Background job wakeup started: jobId=${task.id} code=$code exitCode=${task.exitCode} " +
                "endReason=${task.endReason ?: "-"} chainWakeups=${recorded.state.wakeups} " +
                "windowWakeups=${budget.used(nowMillis)}",
        )
        runCatching { executor.execute { execute(task, signal) } }
            .onFailure { failure ->
                logger.warn("Background job wakeup dispatch failed: type=${failure.javaClass.simpleName}")
                notifyFinishedSafely(task, JobWakeupPolicy.Finish.NOT_STARTED)
                releaseLeaseOnce(leaseId)
            }
    }

    /**
     * 真正跑一轮；墙钟上限与自动任务一致，超时中断会让客户端向 Runtime 发 MSG_CANCEL。
     *
     * 无论从哪条路径离开，finally 都会把"正在自动处理"更新成终态并释放租约。
     */
    private fun execute(task: DetachedTask, signal: JobWakeupPolicy.Signal) {
        val startedAt = System.currentTimeMillis()
        var outcome = JobWakeupPolicy.Finish.NOT_STARTED
        try {
            // 宿主已关闭：本轮不再发起（关闭方也不该看到一条"正在处理"）。
            if (closed.get()) {
                logger.info("Background job wakeup abandoned: jobId=${task.id} reason=host_closed")
                return
            }
            val tail = runCatching { readOutputTail(task, JobWakeupPolicy.LOG_TAIL_BYTES) }
                .getOrElse { failure ->
                    logger.warn("Background job wakeup log read failed: type=${failure.safeLogType()}")
                    ""
                }
            val prompt = JobWakeupPolicy.buildPrompt(signal, tail, promptTexts(context))
            val runId = JobWakeupPolicy.runId(task.id, clock())
            val request = runCatching { buildRequest(task, signal, prompt, runId) }
                .getOrElse { throwable ->
                    logger.warn("Background job wakeup request build failed: type=${throwable.safeLogType()}")
                    null
                }
            if (request == null) return
            outcome = when (val result = runWithTimeout(task, request)) {
                null -> JobWakeupPolicy.Finish.TIMED_OUT
                else -> if (result.ok) JobWakeupPolicy.Finish.DONE else JobWakeupPolicy.Finish.FAILED
            }
            logger.info(
                "Background job wakeup finished: jobId=${task.id} outcome=$outcome " +
                    "durationMs=${System.currentTimeMillis() - startedAt}",
            )
        } catch (throwable: Throwable) {
            if (throwable is InterruptedException) Thread.currentThread().interrupt()
            logger.warn("Background job wakeup failed: type=${throwable.safeLogType()}")
            // 运行期异常就是"这一轮处理失败"，不能留着"未启动"的糊弄话术。
            if (outcome == JobWakeupPolicy.Finish.NOT_STARTED) outcome = JobWakeupPolicy.Finish.FAILED
        } finally {
            notifyFinishedSafely(task, outcome)
            releaseLeaseOnce(LEASE_PREFIX + task.id)
        }
    }

    /**
     * 在独立线程上跑一轮，并最多等 [RUN_TIMEOUT_MILLIS]。
     *
     * 返回 null 表示这一轮没有正常收尾（等待超时 / 被取消 / 宿主已关闭）；
     * 返回值里的 `ok` 是 Runtime 给出的真实成败。submit 前后都判 closed：
     * 关闭过程中提交的新工作没人收尾，通知就会永远停在"正在处理"。
     */
    private fun runWithTimeout(
        task: DetachedTask,
        request: AgentRuntimeWire.RunRequest,
    ): AgentRuntimeWire.RunResult? {
        if (closed.get()) {
            logger.info("Background job wakeup not submitted: jobId=${task.id} reason=host_closed")
            return null
        }
        val worker = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "eta-job-wakeup-run-${task.id.take(6)}").apply { isDaemon = true }
        }
        try {
            val future = worker.submit<AgentRuntimeWire.RunResult?> { runWakeup(request) }
            activeWorker = future
            if (closed.get()) {
                logger.warn("Background job wakeup cancelled: jobId=${task.id} reason=host_closed")
                future.cancel(true)
            }
            return future.get(RUN_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        } catch (timeout: TimeoutException) {
            logger.warn("Background job wakeup exceeded $RUN_TIMEOUT_MILLIS ms; cancelling jobId=${task.id}")
            return null
        } catch (cancelled: CancellationException) {
            logger.warn("Background job wakeup cancelled: jobId=${task.id}")
            return null
        } catch (execution: ExecutionException) {
            logger.warn("Background job wakeup threw: type=${execution.cause?.safeLogType() ?: execution.safeLogType()}")
            // 跑的时候抛了异常：如实当作"这一轮处理失败"，而不是"没跑完"。
            return AgentRuntimeWire.RunResult(
                runId = request.runId,
                ok = false,
                content = "",
                error = execution.cause?.javaClass?.simpleName ?: execution.javaClass.simpleName,
            )
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            logger.warn("Background job wakeup interrupted: jobId=${task.id}")
            return null
        } finally {
            activeWorker = null
            worker.shutdownNow()
        }
    }

    private fun buildRequest(
        task: DetachedTask,
        signal: JobWakeupPolicy.Signal,
        prompt: String,
        runId: String,
    ): AgentRuntimeWire.RunRequest {
        val conversationKey = JobWakeupPolicy.conversationKey(task.id)
        val handoff = AgentRuntimeWire.EntryHandoff(
            id = "job-wakeup-$runId",
            source = AgentRuntimeWire.ETA_JOB_WAKEUP_HANDOFF_SOURCE,
            // 外部归档 payload：Runtime 侧据此把这一轮归档，通知点击才能回到"对应会话"。
            payload = AgentExternalArchivePayload(
                userText = context.getString(R.string.job_wakeup_archive_user),
                conversationKey = conversationKey,
                title = context.getString(
                    R.string.job_wakeup_archive_title,
                    JobWakeupNotifications.subjectOf(context, task),
                ),
            ).toJson(),
            dismissEntrySurfaceOnForegroundOperation = false,
        )
        return AgentRuntimeWire.RunRequest(
            runId = runId,
            prompt = prompt,
            config = AgentModelClient.loadConfig(),
            images = emptyList(),
            history = emptyList(),
            handoff = handoff,
        )
    }

    /** 唤醒 run 的指令文案：明确"没有人在线"，且不能把"没执行"写成"已完成"。 */
    private fun promptTexts(context: Context): JobWakeupPolicy.Texts = JobWakeupPolicy.Texts(
        header = context.getString(R.string.job_wakeup_prompt_header),
        tailLabel = context.getString(R.string.job_wakeup_prompt_tail_label),
        truncatedMarker = context.getString(R.string.job_wakeup_prompt_truncated),
        footer = context.getString(R.string.job_wakeup_prompt_footer),
    )

    /** 释放租约与"正在跑"标记；只生效一次（finally 与派发失败路径都可能到这里）。 */
    private fun releaseLeaseOnce(leaseId: String) {
        if (!leaseReleased.compareAndSet(false, true)) return
        running.set(false)
        runCatching { releaseLease(leaseId) }
            .onFailure { failure ->
                logger.warn("Background job wakeup lease release failed: type=${failure.javaClass.simpleName}")
            }
    }

    private fun notifyRunningSafely(task: DetachedTask, watched: Boolean) {
        runCatching { notifyRunning(task, watched) }
            .onFailure { failure ->
                logger.warn("Background job wakeup notice failed: type=${failure.javaClass.simpleName}")
            }
    }

    private fun notifyFinishedSafely(task: DetachedTask, outcome: JobWakeupPolicy.Finish) {
        runCatching { notifyFinished(task, outcome) }
            .onFailure { failure ->
                logger.warn("Background job wakeup finish notice failed: type=${failure.javaClass.simpleName}")
            }
    }

    private fun notifyChainLimitSafely(task: DetachedTask, globalLimit: Boolean) {
        runCatching { notifyChainLimit(task, globalLimit) }
            .onFailure { failure ->
                logger.warn("Background job wakeup notice failed: type=${failure.javaClass.simpleName}")
            }
    }

    /** App 关闭后台任务宿主时调用：不再接受新的唤醒，进行中的 run 一并中断。 */
    @Synchronized
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        activeWorker?.cancel(true)
        executor.shutdownNow()
    }

    private companion object {
        const val LEASE_PREFIX = "terminal-job-wakeup:"

        /** 无人值守单轮墙钟上限：与 [io.github.mangi.eta.agent.schedule.ScheduledTaskRunner.MAX_RUN_MILLIS] 对齐。 */
        const val RUN_TIMEOUT_MILLIS = 10L * 60L * 1_000L
    }
}
