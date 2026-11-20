package io.github.mangi.eta.agent.mcp

import io.github.mangi.eta.core.AgentLogger
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 审批闸门：MCP 工具调用与 DSH 引擎自身审批的统一等待点。
 *
 * 只读工具立即放行；其余请求挂起成一张 [PendingApproval]，由 UI 展示审批卡，
 * 用户点「允许」/「拒绝」后完成对应的 [CompletableDeferred]。
 *
 * 超时与无应答一律按拒绝处理（fail-closed），避免工具调用永久挂住。
 */
internal class McpApprovalGate(private val logger: AgentLogger) {

    data class Decision(val approved: Boolean, val reason: String)

    data class PendingApproval(
        val id: String,
        val source: ApprovalSource,
        val toolName: String,
        val arguments: String,
        /** 卡片上展示的摘要：命令、路径、包名等。 */
        val summary: String,
        val risk: ApprovalRisk,
    )

    /** 审批来源。当前只有 MCP 一条：DSH 侧走 danger-full-access 不再询问，
     *  Eta 自有工具走既有权限系统，不经过本闸门。 */
    enum class ApprovalSource(val wireValue: String) {
        /** DSH 引擎经 MCP 调用 Eta 工具。 */
        Mcp("mcp"),
    }

    enum class ApprovalRisk { Mutating, Root }

    private val pending = ConcurrentHashMap<String, CompletableDeferred<Decision>>()
    /** 本会话内"总是允许"的工具名：只在内存里，进程重启即失效。 */
    private val alwaysAllowed = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val mutableQueue = MutableStateFlow<List<PendingApproval>>(emptyList())
    val queue: StateFlow<List<PendingApproval>> = mutableQueue.asStateFlow()

    /** 阻塞等待一次裁决；调用方必须在工作线程。 */
    fun await(toolName: String, arguments: String): Decision {
        if (!McpToolRisk.requiresApproval(toolName)) {
            return Decision(approved = true, reason = "只读工具自动放行")
        }
        if (toolName in alwaysAllowed) {
            return Decision(approved = true, reason = "本会话已允许")
        }
        val approval = PendingApproval(
            id = "approval-${System.nanoTime()}",
            source = ApprovalSource.Mcp,
            toolName = toolName,
            arguments = arguments,
            summary = ApprovalSummary.describe(arguments),
            risk = ApprovalSummary.riskOf(toolName),
        )
        return request(approval)
    }

    private fun request(approval: PendingApproval): Decision {
        val deferred = CompletableDeferred<Decision>()
        pending[approval.id] = deferred
        mutableQueue.update { it + approval }
        val decision = try {
            // 阻塞等待 UI 裁决；没有 UI 应答时按拒绝收尾。
            runBlocking { withTimeoutOrNull(TIMEOUT_MS) { deferred.await() } }
        } finally {
            pending.remove(approval.id)
            mutableQueue.update { current -> current.filterNot { it.id == approval.id } }
        }
        if (decision == null) {
            logger.warn("approval_timeout tool=${approval.toolName} source=${approval.source.wireValue}")
            return Decision(approved = false, reason = "等待审批超时")
        }
        return decision
    }

    /** UI 裁决入口：返回 false 表示该请求已不存在（超时或已处理）。 */
    fun resolve(id: String, approved: Boolean, reason: String = ""): Boolean {
        val deferred = pending.remove(id) ?: return false
        mutableQueue.update { current -> current.filterNot { it.id == id } }
        return deferred.complete(
            Decision(
                approved = approved,
                reason = reason.ifBlank { if (approved) "用户允许" else "用户拒绝" },
            ),
        )
    }

    /** 记住"本会话总是允许"；下次同名工具直接放行。 */
    fun allowForSession(toolName: String) {
        alwaysAllowed += toolName
    }

    companion object {
        private const val TIMEOUT_MS = 120_000L
    }
}
