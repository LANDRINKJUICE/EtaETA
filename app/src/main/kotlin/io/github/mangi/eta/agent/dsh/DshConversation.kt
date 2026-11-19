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
import java.util.concurrent.atomic.AtomicInteger

/** follow 流上需要渲染的事件：文本/思考增量与 attempt 边界。 */
internal sealed interface DshStreamEvent {
    data class AttemptStarted(val attemptKey: String) : DshStreamEvent
    data class TextDelta(val attemptKey: String, val delta: String) : DshStreamEvent
    data class ReasoningDelta(val attemptKey: String, val delta: String) : DshStreamEvent
    /** attempt 已落盘：text 为全文，用于替换流式气泡（settle 语义）。 */
    data class AttemptCommitted(val attemptKey: String, val text: String) : DshStreamEvent
    data class AttemptAbandoned(val attemptKey: String) : DshStreamEvent
}

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

    fun openFollow() {
        sockets.register(streamId, ::onFollowFrame)
        sockets.send(client.openStreamFrame(
            streamId = streamId,
            endpoint = "session/follow",
            args = JSONObject()
                .put("address", JSONObject().put("kind", "session").put("sessionId", sessionId))
                .put("activate", true)
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

    private fun extractAssistantText(data: JSONObject?): String? {
        data ?: return null
        // wire 事件的 data 结构随版本略有差异，宽松提取全部 text 块。
        val blocks = data.optJSONArray("blocks") ?: data.optJSONArray("content") ?: return null
        val text = buildString {
            for (i in 0 until blocks.length()) {
                val block = blocks.optJSONObject(i) ?: continue
                if (block.optString("type") == "text") append(block.optString("text"))
            }
        }
        return text.ifBlank { null }
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
    private val observers = mutableMapOf<Any, (String) -> Unit>()
    private val reconnectListeners = AtomicInteger(0)

    fun isOpen(): Boolean = synchronized(lock) { webSocket != null }

    /** 注册 Socket 级故障通知（如 Socket 关闭导致 Cookie 吊销）。 */
    fun addFaultListener(key: Any, listener: (String) -> Unit) {
        synchronized(lock) { observers[key] = listener }
    }

    fun removeFaultListener(key: Any) {
        synchronized(lock) { observers.remove(key) }
    }

    fun ensureOpen(onFatal: (String) -> Unit) {
        synchronized(lock) {
            if (webSocket != null) return
            webSocket = client.client.newWebSocket(client.webSocketRequest(), object : WebSocketListener() {
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
                            frame.optJSONObject("value")?.let(handler::invoke)
                        }
                        "error" -> onFatal(frame.optJSONObject("error")?.optString("message") ?: "stream error")
                        "end" -> Unit
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    synchronized(lock) { this@DshSocketHub.webSocket = null }
                    notifyFaults(t.message ?: "socket failure")
                }

                override fun onClosed(webSocket: WebSocket, response: Response) {
                    synchronized(lock) { this@DshSocketHub.webSocket = null }
                }
            })
        }
    }

    private fun notifyFaults(message: String) {
        val listeners: List<(String) -> Unit>
        synchronized(lock) { listeners = observers.values.toList() }
        listeners.forEach { runCatching { it(message) } }
    }

    fun register(streamId: String, handler: (JSONObject) -> Unit) {
        synchronized(lock) { streams[streamId] = handler }
    }

    fun unregister(streamId: String) {
        synchronized(lock) { streams.remove(streamId) }
    }

    fun send(payload: String): Boolean =
        synchronized(lock) { webSocket }?.send(payload) ?: false

    fun close() {
        synchronized(lock) {
            webSocket?.close(1000, "client shutdown")
            webSocket = null
            streams.clear()
            observers.clear()
        }
    }
}
