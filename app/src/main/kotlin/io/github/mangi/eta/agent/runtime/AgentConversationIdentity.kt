package io.github.mangi.eta.agent.runtime

import java.security.MessageDigest

/** 入口、归档与浏览器共同使用的会话身份；外部归档标识保持已发布算法不变。 */
internal object AgentConversationIdentity {
    const val EXTERNAL_ARCHIVE_PREFIX = "archive-"
    const val VOICE_ARCHIVE_PREFIX = "assistant-"
    const val RUN_FALLBACK_PREFIX = "run:"

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
}
