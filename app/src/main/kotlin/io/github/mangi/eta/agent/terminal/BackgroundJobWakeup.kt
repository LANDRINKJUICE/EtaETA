package io.github.mangi.eta.agent.terminal

import android.content.Context
import android.content.Intent
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.runtime.AgentConversationIdentity
import io.github.mangi.eta.agent.runtime.AgentRuntimeWire
import io.github.mangi.eta.agent.runtime.AgentUiHandoffPayload
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
 * 唤醒轮启动时发出的「有新 UI 会话」信号。App 在前台时 [io.github.mangi.eta.ui.MainActivity]
 * 收到后调 `AgentAppState.refreshRuntimeResults()`，立刻把这个 active run 认领成会话列表里
 * 一条可见、可对话的会话（处理中就看得到），不依赖"回前台 ON_RESUME"或常驻 Room Flow。
 * App 没打开时无人接收、零副作用；无常驻订阅、无轮询，避开 265ef05 被回滚的延迟坑。
 */
internal const val ACTION_WAKEUP_RUN_STARTED = "io.github.mangi.eta.action.WAKEUP_RUN_STARTED"
internal const val EXTRA_WAKEUP_CONVERSATION_ID = "io.github.mangi.eta.extra.WAKEUP_CONVERSATION_ID"

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
 * 2. **继续执行已授权任务、判定统一**：本轮是在线会话里用户已授权任务的延续轮
 *    （例如构建失败后重跑构建、取包、交付 APK），要照常执行到完成，不降级成建议、不要求
 *    用户回来再批。安全闸门只硬守关键部位（系统分区 / 启动链 / 安卓侧手机数据等），且对所有
 *    来源一律照样弹卡、统一 2 分钟超时：有人点就按 Allow/Deny 走，没人点则回「未确认、未执行、
 *    可能无人值守」交模型自行判断其余任务照常做完，绝不偷偷执行或谎报完成。ask_user 同理走
 *    普通询问卡（"别发询问卡"由话术引导、不靠机制特判），没人点也是同一超时兜底。
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
    /** 上一条无人值守 run 还在跑：发一条"这次没接手"的可见通知，不伪装成终态。 */
    private val notifyBusy: (DetachedTask) -> Unit = {},
    /** 运行中阶段更新：把这一轮 AgentEvent 映射成的短文案，同 ID 更新「正在自动处理」通知。 */
    private val notifyStage: (DetachedTask, String, Boolean) -> Unit = { _, _, _ -> },
    private val readOutputTail: (DetachedTask, Int) -> String = { _, _ -> "" },
    /**
     * 跑一轮 agent run 并返回其结果；返回 null 表示客户端没有拿到结果。
     * 由 [TerminalRuntime] 接 [io.github.mangi.eta.agent.runtime.AgentRuntimeClient.run]，
     * 这样"跑完是否成功"能一路透到通知终态。
     */
    private val runWakeup: (
        AgentRuntimeWire.RunRequest,
        (io.github.mangi.eta.agent.runtime.AgentEvent) -> Unit,
    ) -> AgentRuntimeWire.RunResult? = { _, _ -> null },
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

    /** 本轮是否因用户停止 / 宿主关闭而中断（区别于墙钟超时）；终态文案据此选 STOPPED 而非 TIMED_OUT。 */
    @Volatile private var stoppedByUser = false

    /**
     * 已收尾的任务 id：超时 / 被停止 / 宿主关闭会先发终态通知再真正结束 run，
     * 期间 Runtime 仍可能回调几个迟到事件。没有这道护栏，updateRunning 会把终态通知
     * 复活成一条永不消失的 ongoing「正在调用 xx」僵尸。
     */
    private val finishedTasks = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<String, Boolean>(),
    )

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
            // 但要给一条可见通知，别让用户以为「完全没反应」。
            logger.warn("Background job wakeup skipped: jobId=${task.id} reason=wakeup_already_running")
            runCatching { notifyBusy(task) }
                .onFailure { failure ->
                    logger.warn("wakeup busy notice failed: type=${failure.javaClass.simpleName}")
                }
            return
        }
        val leaseId = LEASE_PREFIX + task.id
        leaseReleased.set(false)
        stoppedByUser = false
        if (!acquireLease(leaseId) {
                // 用户在执行通知里停止任务：正在跑的唤醒 run 也一并中断，不留孤儿。
                stoppedByUser = true
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
            // run 即将真正启动：通知前台 UI 立刻把这个 AGENT_UI active run 认领成会话列表里
            // 一条可见、可对话的会话（无常驻订阅、App 未打开时无人收、零副作用）。
            notifyUiRunStarted(request)
            outcome = when (val result = runWithTimeout(task, request, signal.watch)) {
                null -> if (stoppedByUser) JobWakeupPolicy.Finish.STOPPED else JobWakeupPolicy.Finish.TIMED_OUT
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
        watched: Boolean,
    ): AgentRuntimeWire.RunResult? {
        if (closed.get()) {
            logger.info("Background job wakeup not submitted: jobId=${task.id} reason=host_closed")
            return null
        }
        val worker = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "eta-job-wakeup-run-${task.id.take(6)}").apply { isDaemon = true }
        }
        try {
            val future = worker.submit<AgentRuntimeWire.RunResult?> {
                runWakeup(request) { event ->
                    // 终态已落地后丢弃迟到事件，避免把终态通知复活成 ongoing 僵尸。
                    if (task.id !in finishedTasks) {
                        val text = stageOf(event)
                        if (text != null) {
                            runCatching { notifyStage(task, text, watched) }
                                .onFailure { failure ->
                                    logger.warn("wakeup stage notice failed: type=${failure.javaClass.simpleName}")
                                }
                        }
                    }
                }
            }
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
        // 唤醒轮用「真实、可回复的 UI 会话」而非只读归档：
        //  - source = AGENT_UI：RuntimeRunRegistry 据 AgentUiHandoffPayload.conversationId
        //    生成 ActiveRunSnapshot，UI 的 recoverRuntimeRuns/preparingRuns 能认领并 attachRun，
        //    处理中就能在会话列表看到实时事件；
        //  - 会话 id 走 conv-wakeup- 前缀（isUiConversation=true），不再是 archive- 只读副本，
        //    结束后发消息进同一会话、可追问/指挥，不弹新空会话；
        //  - 不再用 AgentExternalArchivePayload：AgentRuntimeService.persistArchivedRun 对它
        //    from() 返回 null，不再写归档表，也就没有"终态才导入"的延迟与只读拦截。
        //  - 刻意不带 promptSupplement：它语义是"用户插话补充"，会把首条 user 消息 id 改成
        //    user-<runId>-supplement-1；而 UI 认领时造的占位是 user-<runId>，会重复。
        //    保持默认 user-<runId> 与占位对齐，首屏可见文案由会话标题 + 占位消息承担。
        val conversationId = AgentConversationIdentity.jobWakeupConversationId(conversationKey)
        val handoff = AgentRuntimeWire.EntryHandoff(
            id = "job-wakeup-$runId",
            source = AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE,
            payload = AgentUiHandoffPayload(
                conversationId = conversationId,
                // 保留"这是唤醒轮"的语义：模型配置 / 防循环 / wakeupOrigin 三处判定
                // 改看这个标记（见 AgentUiHandoffPayload.isJobWakeup），不依赖 source。
                entryTag = AgentUiHandoffPayload.ENTRY_TAG_JOB_WAKEUP,
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

    /** 唤醒 run 启动时广播一次，让前台 UI 实时认领（见 [ACTION_WAKEUP_RUN_STARTED]）。 */
    private fun notifyUiRunStarted(request: AgentRuntimeWire.RunRequest) {
        val conversationId = request.handoff
            ?.takeIf { it.source == AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE }
            ?.let { AgentUiHandoffPayload.from(it.payload).conversationId }
            ?.takeIf(String::isNotBlank)
            ?: return
        runCatching {
            val intent = Intent(ACTION_WAKEUP_RUN_STARTED)
                .setPackage(context.packageName)
                .putExtra(EXTRA_WAKEUP_CONVERSATION_ID, conversationId)
            context.applicationContext.sendBroadcast(intent)
        }.onFailure { failure ->
            logger.warn("wakeup ui run started broadcast failed: type=${failure.safeLogType()}")
        }
    }

    /**
     * 把一个 Runtime 事件映射成「运行中通知」的阶段文案；null 表示该事件不值得更新。
     * 只挑能告诉用户「在干嘛」的关键节点，避免每个 delta 都重绑通知。
     * 放在 Android 侧（不进纯逻辑 JobWakeupPolicy），因为要读 R.string 与 AgentEvent。
     */
    private fun stageOf(event: io.github.mangi.eta.agent.runtime.AgentEvent): String? = when (event) {
        is io.github.mangi.eta.agent.runtime.AgentEvent.ToolStarted ->
            context.getString(R.string.job_wakeup_stage_tool, event.name)
        is io.github.mangi.eta.agent.runtime.AgentEvent.HostedToolStarted ->
            context.getString(R.string.job_wakeup_stage_tool, event.name)
        is io.github.mangi.eta.agent.runtime.AgentEvent.ProviderRequestStarted ->
            context.getString(R.string.job_wakeup_stage_thinking)
        is io.github.mangi.eta.agent.runtime.AgentEvent.ProviderResponseStarted ->
            context.getString(R.string.job_wakeup_stage_answering)
        is io.github.mangi.eta.agent.runtime.AgentEvent.SubagentProgress ->
            context.getString(R.string.job_wakeup_stage_subagent)
        is io.github.mangi.eta.agent.runtime.AgentEvent.RoundStarted ->
            context.getString(R.string.job_wakeup_stage_round, event.round.toString())
        else -> null
    }

    /** 唤醒 run 的指令文案：明确"没有人在线"，且不能把"没执行"写成"已完成"。 */
    private fun promptTexts(context: Context): JobWakeupPolicy.Texts = JobWakeupPolicy.Texts(
        header = context.getString(R.string.job_wakeup_prompt_header),
        tailLabel = context.getString(R.string.job_wakeup_prompt_tail_label),
        truncatedMarker = context.getString(R.string.job_wakeup_prompt_truncated),
        footer = context.getString(R.string.job_wakeup_prompt_footer),
        headerWatched = context.getString(R.string.job_wakeup_prompt_header_watch),
        footerWatched = context.getString(R.string.job_wakeup_prompt_footer_watch),
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
        // 先登记再发终态：无论通知成功与否，后续迟到事件都不再更新这条通知。
        finishedTasks.add(task.id)
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
        stoppedByUser = true
        activeWorker?.cancel(true)
        executor.shutdownNow()
    }

    private companion object {
        const val LEASE_PREFIX = "terminal-job-wakeup:"

        /** 无人值守单轮墙钟上限：与 [io.github.mangi.eta.agent.schedule.ScheduledTaskRunner.MAX_RUN_MILLIS] 对齐。 */
        const val RUN_TIMEOUT_MILLIS = 10L * 60L * 1_000L
    }
}
