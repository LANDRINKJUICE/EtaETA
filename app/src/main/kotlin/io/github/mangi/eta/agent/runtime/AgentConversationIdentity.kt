package io.github.mangi.eta.agent.runtime

import java.security.MessageDigest

/** 入口、归档与浏览器共同使用的会话身份；外部归档标识保持已发布算法不变。 */
internal object AgentConversationIdentity {
    const val EXTERNAL_ARCHIVE_PREFIX = "archive-"
    const val VOICE_ARCHIVE_PREFIX = "assistant-"
    const val RUN_FALLBACK_PREFIX = "run:"
    /** 后台任务唤醒 / 自动任务这类无人值守入口使用的真实 UI 会话前缀。
     *  必须与 [EXTERNAL_ARCHIVE_PREFIX] 区分：这类会话是 conversationsById 里可回复的普通会话，
     *  不是只读归档副本（否则发消息会被 isReadOnlyExternalArchiveConversation 拦掉、弹新空会话）。 */
    const val JOB_WAKEUP_CONVERSATION_PREFIX = "conv-wakeup-"

    /**
     * 是否为「可回复的 UI 会话 id」（在 conversationsById 里、能按会话投递消息）。
     * 后台/归档 run 的三种身份由 fromHandoff 派生，统一在这里判定，避免各处硬写前缀漂移：
     * 将来新增后台身份只改这一处。
     */
    fun isUiConversation(conversationId: String): Boolean =
        conversationId.isNotBlank() &&
            !conversationId.startsWith(RUN_FALLBACK_PREFIX) &&
            !conversationId.startsWith(EXTERNAL_ARCHIVE_PREFIX) &&
            !conversationId.startsWith(VOICE_ARCHIVE_PREFIX)

    fun fromHandoff(handoff: AgentRuntimeWire.EntryHandoff?, fallbackRunId: String): String {
        if (handoff?.source == AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE) {
            AgentUiHandoffPayload.from(handoff.payload).conversationId.takeIf(String::isNotBlank)?.let { return it }
        } else if (handoff != null) {
            AgentExternalArchivePayload.from(handoff.payload)?.let { payload ->
                return archiveConversationId(handoff.source, payload.conversationKey)
            }
        }
        return "run:$fallbackRunId"
    }

    fun archiveConversationId(source: String, conversationKey: String): String {
        val prefix = if (source == AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE) VOICE_ARCHIVE_PREFIX else EXTERNAL_ARCHIVE_PREFIX
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$source:$conversationKey".toByteArray(Charsets.UTF_8))
            .take(12)
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
        return prefix + digest
    }

    /**
     * 后台任务唤醒轮使用的**稳定、可回复** UI 会话 id。
     *
     * 与 [archiveConversationId] 不同：这里不走 `archive-` 只读前缀，而是普通 `conv-` 前缀，
     * 让 [isUiConversation] 判定为 true、让 `AgentAppState` 能把它当成普通会话认领、实时接事件、
     * 并在结束后继续发消息（用户点进唤醒会话追问 / 指挥，不再弹到新空会话）。
     *
     * 由 `conversationKey`（同一任务链恒定）派生：同一条任务链的多次唤醒进同一会话，可连续对话；
     * 不同任务链互不串味。SHA-256 保证跨进程/重启稳定，不依赖 UUID 随机。
     */
    fun jobWakeupConversationId(conversationKey: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("job-wakeup:$conversationKey".toByteArray(Charsets.UTF_8))
            .take(12)
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
        return JOB_WAKEUP_CONVERSATION_PREFIX + digest
    }
}
