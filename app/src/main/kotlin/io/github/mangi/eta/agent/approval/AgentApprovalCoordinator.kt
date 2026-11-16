package io.github.mangi.eta.agent.approval

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 等待用户确认的挂起/恢复中枢。
 *
 * 工具执行发生在 Runtime 的 run 工作线程上，UI 在主线程；两者在同一进程内，因此用进程内
 * 单例 + [StateFlow] 做"发布卡片 / 回填决策"：
 *
 * 1. 执行线程调用 [await]：登记请求并发布到 [pending]，然后在 [signals] 上阻塞等待；
 * 2. 聊天流的卡片由 Compose 直接收集 [pending] 渲染，用户点选时调用 [resolve]；
 * 3. [resolve] 与"超时/停止/撤卡"以 `active` 上的移除为唯一线性化点：谁成功摘下来谁生效，
 *    因此不会出现"UI 显示已允许但工具已按超时返回"的竞态，也不会留下无人消费的决策。
 *
 * 等待用 [LinkedBlockingQueue.poll] 而不是 sleep 轮询：决策、撤卡、进程内取消都会立刻
 * [signals] 唤醒等待者；无信号时最多阻塞 [pollIntervalMillis]，用于周期性检查取消标记。
 */
internal class AgentApprovalCoordinator(
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val idFactory: () -> String = { "approval-${SEQUENCE.incrementAndGet()}-${System.nanoTime()}" },
    private val pollIntervalMillis: Long = DEFAULT_POLL_INTERVAL_MILLIS,
    private val wakeQueue: LinkedBlockingQueue<String> = LinkedBlockingQueue(),
) {
    private val active = ConcurrentHashMap<String, AgentApprovalRequest>()
    private val decisions = ConcurrentHashMap<String, AgentApprovalResolution>()
    private val pendingListeners = CopyOnWriteArrayList<(List<AgentApprovalRequest>) -> Unit>()

    /** 保护"决策写入 + 从 active 摘下"的原子性；同时也保护超时/撤卡路径。 */
    private val lock = Any()

    private val pendingState = MutableStateFlow<List<AgentApprovalRequest>>(emptyList())
    private val resolvedState = MutableStateFlow<List<ResolvedApproval>>(emptyList())

    /** 当前等待用户确认的卡片，按发布顺序排列。 */
    val pending: StateFlow<List<AgentApprovalRequest>> = pendingState.asStateFlow()

    /** 最近完成的决策，UI 用它短暂回显"已允许/已拒绝"。 */
    val resolved: StateFlow<List<ResolvedApproval>> = resolvedState.asStateFlow()

    fun now(): Long = clock()

    fun newRequestId(): String = idFactory()

    /**
     * 观察等待列表变化（通知、浮层角标等）。返回注销函数。
     * 回调在改动线程上同步执行，宿主应自行切回主线程。
     */
    fun addPendingListener(listener: (List<AgentApprovalRequest>) -> Unit): () -> Unit {
        pendingListeners += listener
        return { pendingListeners.remove(listener) }
    }

    /** 组装一个危险操作拦截请求；CRITICAL 与无会话 id 的请求不提供"本会话允许同类"。 */
    fun dangerRequest(
        verdict: ToolRiskVerdict.NeedsConfirm,
        runId: String,
        conversationId: String?,
        timeoutMillis: Long = AGENT_APPROVAL_TIMEOUT_MILLIS,
        /** 模型提供的一句简短中文意图；见 [AgentApprovalRequest.intent]。默认空，向后兼容所有调用点。 */
        intent: String = "",
    ): AgentApprovalRequest {
        val created = clock()
        val allowSession = verdict.level != ToolRiskLevel.CRITICAL && !conversationId.isNullOrBlank()
        return AgentApprovalRequest(
            id = newRequestId(),
            kind = AgentApprovalKind.ToolRisk,
            toolName = verdict.toolName,
            conversationId = conversationId,
            runId = runId,
            subject = verdict.subject,
            intent = intent,
            findings = verdict.findings,
            options = AgentApprovalOption.dangerOptions(allowSession = allowSession),
            allowFreeText = true,
            createdAtMillis = created,
            expiresAtMillis = created + timeoutMillis,
        )
    }

    fun askUserRequest(
        question: String,
        explanation: String,
        options: List<AgentApprovalOption>,
        allowFreeText: Boolean,
        runId: String,
        conversationId: String?,
        timeoutMillis: Long = AGENT_APPROVAL_TIMEOUT_MILLIS,
    ): AgentApprovalRequest {
        val created = clock()
        return AgentApprovalRequest(
            id = newRequestId(),
            kind = AgentApprovalKind.AskUser,
            toolName = ASK_USER_TOOL_NAME,
            conversationId = conversationId,
            runId = runId,
            subject = question,
            question = question,
            explanation = explanation,
            options = options,
            allowFreeText = allowFreeText,
            createdAtMillis = created,
            expiresAtMillis = created + timeoutMillis,
        )
    }

    private fun publish(request: AgentApprovalRequest) {
        synchronized(lock) {
            active[request.id] = request
            publishPendingLocked { current -> current.filterNot { it.id == request.id } + request }
        }
    }

    private fun publishPendingLocked(transform: (List<AgentApprovalRequest>) -> List<AgentApprovalRequest>) {
        var updated: List<AgentApprovalRequest> = emptyList()
        pendingState.update { current ->
            updated = transform(current)
            updated
        }
        wake()
        pendingListeners.forEach { listener -> runCatching { listener(updated) } }
    }

    private fun withdraw(requestId: String) {
        synchronized(lock) {
            active.remove(requestId) ?: return
            decisions.remove(requestId)
            publishPendingLocked { current -> current.filterNot { it.id == requestId } }
            // 让等待者立刻看到 active 里没有自己。
            wake()
        }
    }

    /**
     * 用户在卡片上做出选择。
     *
     * @return 是否成功落地；卡片已过期、已失效或选项不存在时返回 false（调用方据此提示"已失效"）。
     */
    fun resolve(requestId: String, optionId: String, freeText: String? = null): Boolean {
        synchronized(lock) {
            val request = active[requestId] ?: return false
            val option = request.options.firstOrNull { it.id == optionId }
                ?: freeTextOnlyOption(request, optionId)
                ?: return false
            val resolution = AgentApprovalResolution(
                requestId = requestId,
                optionId = optionId,
                kind = option.kind,
                freeText = freeText?.trim()?.takeIf { it.isNotEmpty() },
                atMillis = clock(),
            )
            // 决策先写入、再摘下 active：等待者无论走"读到决策"还是"发现 active 为空"，
            // 都能看到一致的状态；摘下失败说明超时/撤卡已经认领，本次决策作废。
            decisions[requestId] = resolution
            if (!active.remove(requestId, request)) {
                decisions.remove(requestId, resolution)
                return false
            }
            publishPendingLocked { current -> current.filterNot { it.id == requestId } }
            resolvedState.update { current ->
                (current.filterNot { it.request.id == requestId } + ResolvedApproval(request, resolution))
                    .takeLast(RESOLVED_HISTORY_LIMIT)
            }
            wake()
            return true
        }
    }

    /** 询问卡的自由输入：只有允许自由文本时才接受，按 [AgentApprovalOptionKind.Choice] 记录。 */
    private fun freeTextOnlyOption(
        request: AgentApprovalRequest,
        optionId: String,
    ): AgentApprovalOption? {
        if (!request.allowFreeText) return null
        if (optionId != AgentApprovalOption.FREE_TEXT_ONLY) return null
        return AgentApprovalOption(optionId, AgentApprovalOptionKind.Choice)
    }

    /**
     * 阻塞等待用户选择。[isAborted] 由调用方提供（运行被停止或取消时返回 true）。
     * 返回前一定会把请求从等待列表里撤下。
     */
    fun await(
        request: AgentApprovalRequest,
        isAborted: () -> Boolean = { false },
    ): AgentApprovalOutcome {
        publish(request)
        try {
            while (true) {
                val settled = settle(request, isAborted)
                if (settled != null) return settled
                val remaining = request.expiresAtMillis - clock()
                val waitMillis = remaining.coerceIn(1L, pollIntervalMillis)
                try {
                    wakeQueue.poll(waitMillis, TimeUnit.MILLISECONDS)
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    settle(request, isAborted = { true })
                    return AgentApprovalOutcome.Cancelled
                }
            }
        } finally {
            synchronized(lock) {
                active.remove(request.id)
                decisions.remove(request.id)
                publishPendingLocked { current -> current.filterNot { it.id == request.id } }
            }
        }
    }

    /** 检查一次是否已有结论；需要取消/超时时也在锁内完成认领，保证只有一方生效。 */
    private fun settle(
        request: AgentApprovalRequest,
        isAborted: () -> Boolean,
    ): AgentApprovalOutcome? = synchronized(lock) {
        decisions.remove(request.id)?.let { resolution ->
            return AgentApprovalOutcome.Decided(resolution)
        }
        if (!active.containsKey(request.id)) {
            return AgentApprovalOutcome.Cancelled
        }
        if (isAborted()) {
            decisions.remove(request.id)
            active.remove(request.id)
            return AgentApprovalOutcome.Cancelled
        }
        if (clock() >= request.expiresAtMillis) {
            // 与 resolve 竞争：摘下成功才算超时生效；失败说明决策已经在路上。
            decisions.remove(request.id)?.let { resolution ->
                return AgentApprovalOutcome.Decided(resolution)
            }
            if (active.remove(request.id, request)) {
                return AgentApprovalOutcome.TimedOut
            }
            decisions.remove(request.id)?.let { resolution ->
                return AgentApprovalOutcome.Decided(resolution)
            }
            return AgentApprovalOutcome.Cancelled
        }
        null
    }

    private fun wake() {
        // 等待者是轮询 + 信号混合：信号只用于立刻唤醒，不承载具体语义。
        if (wakeQueue.size < WAKE_QUEUE_LIMIT) wakeQueue.offer(SIGNAL)
    }

    /** 运行结束或被停止：撤下该运行的所有卡片，等待中的调用返回"未确认"。 */
    fun abandonRun(runId: String) {
        pending.value.filter { it.runId == runId }.forEach { withdraw(it.id) }
    }

    fun abandonConversation(conversationId: String) {
        pending.value.filter { it.conversationId == conversationId }.forEach { withdraw(it.id) }
    }

    /** 会话被删除、进程退出等场景：清空全部等待中的卡片。 */
    fun clear() {
        synchronized(lock) {
            pending.value.forEach { request -> active.remove(request.id) }
            decisions.clear()
            publishPendingLocked { emptyList() }
            resolvedState.update { emptyList() }
            wake()
        }
    }

    companion object {
        private const val DEFAULT_POLL_INTERVAL_MILLIS = 100L
        private const val RESOLVED_HISTORY_LIMIT = 4
        private const val ASK_USER_TOOL_NAME = "ask_user"
        private const val WAKE_QUEUE_LIMIT = 256
        private const val SIGNAL = "wake"
        private val SEQUENCE = AtomicLong()

        /** 进程内共享实例：Runtime 执行器发布，Compose 卡片消费。 */
        val shared = AgentApprovalCoordinator()
    }
}

/** 便于调用方以简短形式访问进程内实例。 */
internal val agentApprovalCoordinator: AgentApprovalCoordinator
    get() = AgentApprovalCoordinator.shared
