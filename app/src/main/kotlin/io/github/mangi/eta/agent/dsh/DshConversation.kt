package io.github.mangi.eta.agent.dsh

import io.github.mangi.eta.core.AndroidAgentLogger
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** follow 流上需要渲染的事件：文本/思考增量与 attempt 边界。 */
internal sealed interface DshStreamEvent {
    data class AttemptStarted(val attemptKey: String) : DshStreamEvent
    data class TextDelta(val attemptKey: String, val delta: String) : DshStreamEvent
    data class ReasoningDelta(val attemptKey: String, val delta: String) : DshStreamEvent
    /** attempt 已落盘：text 为全文，用于替换流式气泡（settle 语义）。 */
    data class AttemptCommitted(val attemptKey: String, val text: String) : DshStreamEvent
    data class AttemptAbandoned(val attemptKey: String) : DshStreamEvent
    /** 引擎侧流级失败（error 帧）：不再静默，交由上层以错误收尾。 */
    data class StreamFailed(val message: String) : DshStreamEvent
    /** snapshot 里已经落盘的对话历史，用于附加既有会话时回填气泡。 */
    data class HistoryLoaded(val entries: List<HistoryEntry>) : DshStreamEvent
    /** snapshot 投影里的当前模型选择（provider 到 model），用于选择器回显。 */
    data class ModelSelection(val provider: String, val model: String) : DshStreamEvent
}

/** 一条已落盘的历史消息：角色 + 纯文本正文。 */
internal data class HistoryEntry(val role: String, val text: String)

/**
 * 单个 DSH 会话：session/follow 流的打开、prompt 与帧分派。
 * 每个会话独立 streamId；多条会话共享 [DshSocketHub] 的单条 remote.mux Socket。
 */
internal class DshConversation(
    val sessionId: String,
    private val client: DshWebClient,
    private val sockets: DshSocketHub,
) {
    val streamId = "eta-${UUID.randomUUID()}"
    private var assistantStreamRevision = -1
    private var attemptCounter = 0
    private val attemptKeys = mutableMapOf<String, String>()
    val events = MutableSharedFlow<DshStreamEvent>(
        replay = 0,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** [activate] 为 true 时请求引擎在后台激活该会话（冷会话恢复）；附加只读场景传 false。 */
    fun openFollow(activate: Boolean = true) {
        sockets.register(streamId, ::onFollowFrame, ::onStreamFailure)
        sockets.send(client.openStreamFrame(
            streamId = streamId,
            endpoint = "session/follow",
            args = JSONObject()
                .put("address", JSONObject().put("kind", "session").put("sessionId", sessionId))
                .put("activate", activate)
                .put("assistantStream", true),
        ))
    }

    suspend fun prompt(text: String): Boolean = try {
        client.rpc("session", "prompt", JSONObject()
            .put("requestId", UUID.randomUUID().toString())
            .put("sessionId", sessionId)
            .put("mode", "queue")
            .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text)))
            .put("clientTimeZone", java.util.TimeZone.getDefault().id),
        )
        true
    } catch (failure: DshRpcException) {
        AndroidAgentLogger.warn("dsh_prompt_failed code=${failure.code}")
        false
    }

    /** remote.mux item 帧到达：value = SessionFollowFrame。 */
    fun onFollowFrame(value: JSONObject) {
        when (value.optString("type")) {
            "snapshot" -> {
                assistantStreamRevision = value.optJSONObject("assistantStream")?.optInt("revision", -1) ?: -1
                historyEntries(value.optJSONArray("records"))?.let { entries ->
                    events.tryEmit(DshStreamEvent.HistoryLoaded(entries))
                }
                modelSelection(value)?.let(events::tryEmit)
            }
            "event" -> handleDurableEvent(value.optJSONObject("event") ?: return)
            "assistant-stream" -> handleAssistantStream(value.optJSONObject("frame") ?: return)
        }
    }

    private fun handleDurableEvent(event: JSONObject) {
        if (event.optString("type") != "assistant/message") return
        val attemptKey = attemptKeys.values.lastOrNull() ?: return
        val text = extractAssistantText(event.optJSONObject("data")) ?: return
        events.tryEmit(DshStreamEvent.AttemptCommitted(attemptKey, text))
    }

    private fun handleAssistantStream(frame: JSONObject) {
        val revision = frame.optInt("revision", Int.MIN_VALUE)
        if (assistantStreamRevision >= 0 && revision != assistantStreamRevision + 1) {
            // revision 必须连续递增；断裂视为丢帧，弃用后续增量，等 durable 事件兜底。
            AndroidAgentLogger.warn("dsh_stream_revision_gap expected=${assistantStreamRevision + 1} got=$revision")
            return
        }
        assistantStreamRevision = revision
        val attemptId = frame.optString("attemptId")
        when (frame.optString("type")) {
            "start" -> {
                attemptCounter += 1
                val key = "dsh-$attemptId-$attemptCounter"
                attemptKeys[attemptId] = key
                events.tryEmit(DshStreamEvent.AttemptStarted(key))
            }
            "chunk" -> {
                val key = attemptKeys[attemptId] ?: return
                val chunk = frame.optJSONObject("chunk") ?: return
                when (chunk.optString("type")) {
                    "text-delta" -> events.tryEmit(DshStreamEvent.TextDelta(key, chunk.optString("text")))
                    "reasoning-delta" -> events.tryEmit(DshStreamEvent.ReasoningDelta(key, chunk.optString("text")))
                }
            }
            "end" -> {
                val key = attemptKeys[attemptId] ?: return
                if (frame.optJSONObject("outcome")?.optString("kind") == "abandoned") {
                    events.tryEmit(DshStreamEvent.AttemptAbandoned(key))
                }
                // committed 的最终文本以 durable assistant/message 事件为准（settle）。
            }
        }
    }

    /** assistant/message 的 data 形如 {turn,step,message:{role,content:[…]},usage,stream}；
     *  兼容旧版把 content/blocks 直接挂在 data 上的写法。 */
    private fun extractAssistantText(data: JSONObject?): String? {
        data ?: return null
        val blocks = data.optJSONObject("message")?.optJSONArray("content")
            ?: data.optJSONArray("blocks")
            ?: data.optJSONArray("content")
            ?: return null
        return textOfBlocks(blocks)
    }

    private fun textOfBlocks(blocks: JSONArray): String? {
        val text = buildString {
            for (i in 0 until blocks.length()) {
                val block = blocks.optJSONObject(i) ?: continue
                if (block.optString("type") == "text") append(block.optString("text"))
            }
        }
        return text.ifBlank { null }
    }

    /** snapshot.projections.values.modelSelection.next（或 lastUsed）→ 当前模型选择。 */
    private fun modelSelection(snapshot: JSONObject): DshStreamEvent.ModelSelection? {
        val values = snapshot.optJSONObject("projections")?.optJSONObject("values") ?: return null
        val selection = values.optJSONObject("modelSelection")?.let {
            it.optJSONObject("next") ?: it.optJSONObject("lastUsed")
        } ?: return null
        val provider = selection.optString("provider")
        val model = selection.optString("model")
        if (provider.isBlank() || model.isBlank()) return null
        return DshStreamEvent.ModelSelection(provider, model)
    }

    /** snapshot.records → 历史气泡：只取 user/assistant 的正文，工具与系统事件不进对话正文。 */
    private fun historyEntries(records: JSONArray?): List<HistoryEntry>? {
        records ?: return null
        if (records.length() == 0) return null
        val entries = mutableListOf<HistoryEntry>()
        for (i in 0 until records.length()) {
            val event = records.optJSONObject(i)?.optJSONObject("event") ?: continue
            val data = event.optJSONObject("data") ?: continue
            when (event.optString("type")) {
                "user/message" -> {
                    val text = textOfBlocks(data.optJSONArray("content") ?: continue)
                    if (text != null) entries += HistoryEntry("user", text)
                }
                "assistant/message" -> {
                    val text = extractAssistantText(data)
                    if (text != null) entries += HistoryEntry("assistant", text)
                }
            }
        }
        return entries.ifEmpty { null }
    }

    /** 流级失败（引擎 error 帧）：上抛给运行层，避免界面无声卡住。 */
    private fun onStreamFailure(message: String) {
        events.tryEmit(DshStreamEvent.StreamFailed(message))
    }

    fun close() {
        sockets.send(client.cancelStreamFrame(streamId))
        sockets.unregister(streamId)
    }
}

/**
 * remote.mux 单 Socket 共享层：每个 API Peer 仅允许一条 WebSocket（重复连接 409），
 * 按 streamId 把 item 帧分派回各 [DshConversation]；onFailure 时整体重建。
 */
internal class DshSocketHub(private val client: DshWebClient) {
    private val lock = Any()
    private var webSocket: WebSocket? = null
    private val streams = mutableMapOf<String, (JSONObject) -> Unit>()
    private val failures = mutableMapOf<String, (String) -> Unit>()

    fun isOpen(): Boolean = synchronized(lock) { webSocket != null }

    fun ensureOpen() {
        synchronized(lock) {
            if (webSocket != null) return
            webSocket = DshWebClient.client.newWebSocket(client.webSocketRequest(), object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val frame = try {
                        JSONObject(text)
                    } catch (_: Exception) {
                        return
                    }
                    when (frame.optString("type")) {
                        "item" -> {
                            val handler: ((JSONObject) -> Unit)?
                            synchronized(lock) { handler = streams[frame.optString("streamId")] }
                            val value = frame.optJSONObject("value") ?: return
                            handler?.invoke(value)
                        }
                        "error" -> {
                            // 流级失败必须让上层看见，否则界面停在"没反应"。
                            val listener: ((String) -> Unit)?
                            synchronized(lock) { listener = failures[frame.optString("streamId")] }
                            val message = frame.optJSONObject("error")?.optString("message")
                                ?.ifBlank { null } ?: "DSH 流已中断"
                            listener?.invoke(message)
                        }
                        "end" -> Unit
                        else -> Unit
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    synchronized(lock) { this@DshSocketHub.webSocket = null }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    synchronized(lock) { this@DshSocketHub.webSocket = null }
                }
            })
        }
    }

    fun register(streamId: String, handler: (JSONObject) -> Unit, onFailure: (String) -> Unit) {
        synchronized(lock) {
            streams[streamId] = handler
            failures[streamId] = onFailure
        }
    }

    fun unregister(streamId: String) {
        synchronized(lock) {
            streams.remove(streamId)
            failures.remove(streamId)
        }
    }

    fun send(payload: String): Boolean =
        synchronized(lock) { webSocket }?.send(payload) ?: false

    fun close() {
        synchronized(lock) {
            webSocket?.close(1000, "client shutdown")
            webSocket = null
            streams.clear()
            failures.clear()
        }
    }
}
