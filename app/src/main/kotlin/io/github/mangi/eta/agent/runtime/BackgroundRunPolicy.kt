package io.github.mangi.eta.agent.runtime

/**
 * 无人值守 / 后台 run 的**统一策略中心**（纯逻辑、零 Android 依赖，可在本机 JVM 基线单测）。
 *
 * 收敛三处过去散落在各入口、口径会漂移的判定，供 Runtime 侧入口、UI 恢复、通知状态层共用：
 * 1. 「无人值守但可回复」的 UI 会话前缀（唤醒、自动任务都归这里）；
 * 2. UI 该为哪些 active run 补建会话（运行一开始就能进聊天列表、可对话）；
 * 3. 进展看门狗：长任务不被总墙钟一刀切误杀，只有持续无进展 / 超硬上限才超时。
 *
 * 前缀常量在此**唯一定义**，[AgentConversationIdentity] 直接引用，避免两处硬编码漂移。
 */
internal object BackgroundRunPolicy {
    /** 后台任务唤醒轮的稳定可回复会话前缀。 */
    const val JOB_WAKEUP_PREFIX = "conv-wakeup-"

    /** 自动 / 计划任务的稳定可回复会话前缀。 */
    const val SCHEDULED_TASK_PREFIX = "conv-schedule-"

    /** 所有「无人值守但可回复、需要 UI 认领、统一进状态层」的会话前缀。 */
    val UNATTENDED_PREFIXES: List<String> = listOf(JOB_WAKEUP_PREFIX, SCHEDULED_TASK_PREFIX)

    /**
     * 是否为无人值守后台会话。这类会话是 `conversationsById` 里的普通可回复 UI 会话，
     * 但由后台入口发起（App 在线时未必发过），需要 UI 主动认领，而不是等终态归档。
     *
     * 普通用户会话（`conv-<uuid>`）、只读归档（`archive-` / `assistant-`）、`run:` 前缀都不算。
     */
    fun isUnattendedConversation(conversationId: String): Boolean =
        conversationId.isNotBlank() && UNATTENDED_PREFIXES.any { conversationId.startsWith(it) }

    /** 一个「Runtime 正在跑」的 active run 的认领候选（零 Android 依赖的投影）。 */
    data class AdoptionCandidate(
        val runId: String,
        val conversationId: String,
        val operation: String = "",
    )

    /**
     * 在 active runs 里挑出「会话内存里还不存在、需要 UI 补建」的 run。
     *
     * **判据只看 `conversationsById` 有没有这条会话，绝不用「有没有 checkpoint」近似**——
     * 后台唤醒 run 一启动就写 checkpoint（[AgentRunCheckpointStore.start]），旧实现用
     * `runId !in knownRunIds` 过滤会把它恒排除，导致「运行中进不了列表、结束才出现」（67d0a33
     * 遗留根因）。补建与「run 是否已 admit」彻底解耦，零竞态。
     *
     * 仅对 [isUnattendedConversation] 的会话补建：普通用户会话若缺失，多半是用户删除 / 备份替换
     * 导致的，不能凭空复活（否则踩掉 [AgentAppState] completed 分支刻意保留的语义）。
     */
    fun conversationsToAdopt(
        activeRuns: List<AdoptionCandidate>,
        existingConversationIds: Set<String>,
    ): List<AdoptionCandidate> {
        val seen = HashSet<String>()
        return activeRuns.filter { run ->
            run.conversationId.isNotBlank() &&
                isUnattendedConversation(run.conversationId) &&
                run.conversationId !in existingConversationIds &&
                seen.add(run.conversationId)
        }
    }

    /**
     * 进展看门狗：长任务不该被「总墙钟」一刀切误杀，只有「持续无进展」或超硬上限才取消。
     *
     * 旧实现 `future.get(10min)` 到点就 `cancel(true)`，一轮长思考 / 多工具 / 子代理 / 等模型
     * 都会被当成超时杀掉，这是用户反馈「后台唤醒常被停止或超时」的确定性根。新实现由
     * `AgentRuntimeClient.run` 的 onEvent 刷新 `lastProgressAtMillis`（单调时钟
     * `SystemClock.elapsedRealtime()`，不受系统对时 / 用户改时间影响），等待循环按小片 `get`
     * 轮询，用这里的判定决定「这一刻该不该取消」。
     *
     * 心跳来源只有 AgentEvent，而 AgentEvent 没有「工具执行中」的周期事件：`ToolStarted` 之后
     * 直接到 `ToolFinished`，一次长工具调用（本机构建 / 下载 / 长命令）期间没有任何事件。
     * 因此调用方在 `ToolStarted`/`HostedToolStarted` 时 `activeTools` 自增、`ToolFinished` 时自减；
     * 只要有工具在跑就不按 idle 判（仍受硬上限约束），避免把长工具误杀成超时。
     *
     * @param activeTools 当前正在执行的工具数（0 = 空闲，可判 idle）。
     * @return true 表示应立即取消这一轮（无进展超时或超过硬上限）。
     */
    fun shouldTimeOut(
        nowMillis: Long,
        startedAtMillis: Long,
        lastProgressAtMillis: Long,
        idleTimeoutMillis: Long,
        hardCapMillis: Long,
        activeTools: Int = 0,
    ): Boolean {
        val elapsed = (nowMillis - startedAtMillis).coerceAtLeast(0L)
        // 硬上限无条件生效，哪怕有进展 / 有工具在跑。
        if (elapsed >= hardCapMillis) return true
        // 有工具在执行：长工具本就不发中间事件，不算无进展。
        if (activeTools > 0) return false
        val lastActivity = maxOf(lastProgressAtMillis, startedAtMillis)
        return (nowMillis - lastActivity).coerceAtLeast(0L) >= idleTimeoutMillis
    }
}
