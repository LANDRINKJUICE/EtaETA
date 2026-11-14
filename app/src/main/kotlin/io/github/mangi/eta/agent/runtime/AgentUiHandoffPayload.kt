package io.github.mangi.eta.agent.runtime

import org.json.JSONArray
import org.json.JSONObject

internal data class AgentUiHandoffPayload(
    val conversationId: String,
    val promptSupplement: Supplement? = null,
    val supplements: List<Supplement> = emptyList(),
    /**
     * 入口来源标记。后台任务唤醒轮以 AGENT_UI handoff 启动（这样它是真实可回复的 UI 会话），
     * 但仍需保留"这是无人值守唤醒轮"的语义，供模型配置 / 防循环 / wakeupOrigin 判定使用。
     * 见 [ENTRY_TAG_JOB_WAKEUP]。默认空 = 普通用户 UI 会话，旧 payload 反序列化天然兼容。
     */
    val entryTag: String = "",
) {
    val lastSupplementIndex: Int
        get() = (listOfNotNull(promptSupplement?.index) + supplements.map { it.index }).maxOrNull() ?: 0

    fun promptMessageId(runId: String): String =
        promptSupplement?.let { "user-$runId-supplement-${it.index}" } ?: "user-$runId"

    data class Supplement(
        val index: Int,
        val text: String,
        val createdAt: Long,
    )

    fun toJson(): String =
        JSONObject()
            .put("type", TYPE)
            .put("version", VERSION)
            .put("conversationId", conversationId)
            .put("entryTag", entryTag)
            .also { json ->
                promptSupplement?.let { json.put("promptSupplement", it.toJson()) }
            }
            .put(
                "supplements",
                JSONArray().also { array ->
                    supplements.forEach { supplement ->
                        array.put(
                            supplement.toJson()
                        )
                    }
                }
            )
            .toString()

    companion object {
        private const val TYPE = "agent_ui_handoff"
        private const val VERSION = 2
        /** 后台任务自动唤醒轮的入口标记（见 [AgentUiHandoffPayload.entryTag]）。 */
        const val ENTRY_TAG_JOB_WAKEUP = "job_wakeup"

        /**
         * 这轮是不是「后台任务唤醒轮」：它用 AGENT_UI handoff（真实可回复 UI 会话），
         * 但入口标记仍是唤醒轮。模型配置 / 防循环 / wakeupOrigin 三处判定统一走这里，
         * 避免把 source 改回 ETA_JOB_WAKEUP 导致 UI 会话解析链全线失效。
         */
        fun isJobWakeup(handoff: AgentRuntimeWire.EntryHandoff?): Boolean =
            handoff
                ?.takeIf { it.source == AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE }
                ?.let { from(it.payload).entryTag == ENTRY_TAG_JOB_WAKEUP }
                ?: false

        fun from(raw: String): AgentUiHandoffPayload {
            val trimmed = raw.trim()
            if (!trimmed.startsWith("{")) {
                return AgentUiHandoffPayload(conversationId = trimmed)
            }
            return runCatching {
                val json = JSONObject(trimmed)
                if (json.optString("type") != TYPE) {
                    return@runCatching AgentUiHandoffPayload(conversationId = trimmed)
                }
                val supplementsJson = json.optJSONArray("supplements") ?: JSONArray()
                AgentUiHandoffPayload(
                    conversationId = json.optString("conversationId"),
                    promptSupplement = json.optJSONObject("promptSupplement")
                        ?.toSupplement(defaultIndex = 1),
                    supplements = (0 until supplementsJson.length()).mapNotNull { index ->
                        supplementsJson.optJSONObject(index)?.toSupplement(index + 1)
                    },
                    entryTag = json.optString("entryTag"),
                )
            }.getOrDefault(AgentUiHandoffPayload(conversationId = trimmed))
        }

        private fun JSONObject.toSupplement(defaultIndex: Int): Supplement? {
            val text = optString("text").trim()
            if (text.isBlank()) return null
            return Supplement(
                index = optInt("index", defaultIndex),
                text = text,
                createdAt = optLong("createdAt"),
            )
        }
    }

    private fun Supplement.toJson(): JSONObject =
        JSONObject()
            .put("index", index)
            .put("text", text)
            .put("createdAt", createdAt)
}
