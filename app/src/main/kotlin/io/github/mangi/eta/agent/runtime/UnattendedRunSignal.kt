package io.github.mangi.eta.agent.runtime

import android.content.Context
import android.content.Intent
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType

/**
 * 「无人值守后台 run 已启动」的统一信号（唯一收敛点）。
 *
 * 唤醒轮 / 自动任务 / 任何以后新增的后台 run 都走完整 Runtime 管线，在
 * [AgentRuntimeService] 的 admit 成功后由此**一处**发出。把信号放在 admit 之后而非入口，
 * 是因为此刻 run 已登记进 `RuntimeRunRegistry`、UI 的 `queryActiveRuns()` 必能命中——
 * 从根上消除「广播发在 run 提交之前、UI 查 active 空转」的竞态（历史 R2）。
 *
 * App 在前台时 [io.github.mangi.eta.ui.MainActivity] 收到后触发对账，把这条 run 认领成
 * 会话列表里可见、可对话的会话（处理中就看得到）；App 未打开时无人接收、零副作用，
 * 回前台走既有 ON_RESUME 对账。无常驻订阅、无轮询（避开 265ef05 的延迟坑）。
 */
internal const val ACTION_UNATTENDED_RUN_STARTED = "io.github.mangi.eta.action.UNATTENDED_RUN_STARTED"
internal const val EXTRA_UNATTENDED_CONVERSATION_ID = "io.github.mangi.eta.extra.UNATTENDED_CONVERSATION_ID"
internal const val EXTRA_UNATTENDED_RUN_ID = "io.github.mangi.eta.extra.UNATTENDED_RUN_ID"

/** 仅无人值守可回复会话需要主动认领；普通用户会话本就在 UI 内存里。 */
internal fun sendUnattendedRunStarted(context: Context, conversationId: String, runId: String) {
    if (!BackgroundRunPolicy.isUnattendedConversation(conversationId)) return
    runCatching {
        val intent = Intent(ACTION_UNATTENDED_RUN_STARTED)
            .setPackage(context.packageName)
            .putExtra(EXTRA_UNATTENDED_CONVERSATION_ID, conversationId)
            .putExtra(EXTRA_UNATTENDED_RUN_ID, runId)
        context.applicationContext.sendBroadcast(intent)
    }.onFailure { failure ->
        AndroidAgentLogger.warn("unattended run started broadcast failed: type=${failure.safeLogType()}")
    }
}
