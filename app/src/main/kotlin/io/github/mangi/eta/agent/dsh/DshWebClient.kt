package io.github.mangi.eta.agent.dsh

import io.github.mangi.eta.core.AndroidAgentLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * DSH Web 引擎的客户端：鉴权交换、一元 RPC 与 remote.mux WebSocket 请求构造。
 *
 * 协议依据 资料目录《DSH协议规格》（deepseek-harness master 2e39e70 源码取证）：
 * - /connect 302 交换 API Cookie；OkHttp 必须关闭自动重定向，从 302 响应手动收 Set-Cookie；
 * - 一元调用 POST /api/<ns>/<method>，body 为 client-request 信封，payload 必须恰好含 args；
 * - WS /api/remote.mux 以 {type,streamId,...} 信封复用逻辑流，open 帧 payload 为 {args:...}；
 * - 每个 API Peer 只允许一条 remote.mux Socket，Socket 关闭即吊销 Cookie，需重走 /connect。
 */
internal class DshWebClient(
    private val host: String = "127.0.0.1",
    private val port: Int = DshEngineLauncher.DEFAULT_PORT,
) {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val authority = "$host:$port"
    private val baseUrl = "http://$authority"

    @Volatile
    private var authCookie: String? = null

    fun hasSession(): Boolean = authCookie != null

    /** 用引擎启动行里的 launch token 换 API Cookie；interactions=answer 允许注册 $events。 */
    suspend fun connect(launchToken: String): Boolean = withContext(Dispatchers.IO) {
        disconnect()
        val noRedirect = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .followRedirects(false)
            .build()
        val request = Request.Builder()
            .url("$baseUrl/connect?token=$launchToken&interactions=answer")
            .header("Host", authority)
            .build()
        try {
            noRedirect.newCall(request).execute().use { response ->
                if (response.code != 302) return@withContext false
                val cookie = response.headers("Set-Cookie")
                    .firstOrNull { it.startsWith("dsh-auth-") }
                    ?.substringBefore(';')
                    ?: return@withContext false
                authCookie = cookie
                true
            }
        } catch (failure: Exception) {
            AndroidAgentLogger.warn("dsh_connect_failed type=${failure.javaClass.simpleName}")
            false
        }
    }

    fun disconnect() {
        authCookie = null
    }

    /** 一元 RPC：POST /api/<namespace>/<method>；返回 result.value；业务错误抛 [DshRpcException]。 */
    suspend fun rpc(namespace: String, method: String, args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val cookie = authCookie ?: throw DshRpcException("NOT_CONNECTED", "未连接 DSH 引擎")
        val rpcId = UUID.randomUUID().toString()
        val endpoint = "$namespace/$method"
        val envelope = JSONObject()
            .put("type", "client-request")
            .put("rpcId", rpcId)
            .put("method", endpoint)
            .put("payload", JSONObject().put("args", args))
        val request = Request.Builder()
            .url("$baseUrl/api/$endpoint")
            .header("Content-Type", "application/json")
            .header("Cookie", cookie)
            .post(envelope.toString().toRequestBody(jsonMediaType))
            .build()
        try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (response.code != 200) throw DshRpcException("HTTP_${response.code}", body.take(300))
                val parsed = JSONObject(body)
                if (parsed.optString("rpcId") != rpcId) throw DshRpcException("RPC_ID_MISMATCH", "响应 rpcId 与请求不一致")
                val result = parsed.optJSONObject("result") ?: throw DshRpcException("BAD_RESPONSE", body.take(300))
                if (result.optBoolean("ok")) {
                    result.optJSONObject("value") ?: JSONObject()
                } else {
                    val error = result.optJSONObject("error")
                    throw DshRpcException(
                        error?.optString("code") ?: "UNKNOWN",
                        error?.optString("message") ?: body.take(300),
                    )
                }
            }
        } catch (failure: DshRpcException) {
            throw failure
        } catch (failure: Exception) {
            throw DshRpcException("TRANSPORT", failure.message ?: failure.javaClass.simpleName)
        }
    }

    /** remote.mux WebSocket 握手请求（携带 API Cookie）。 */
    fun webSocketRequest(): Request {
        val cookie = requireNotNull(authCookie) { "未连接 DSH 引擎" }
        return Request.Builder()
            .url("ws://$authority/api/remote.mux")
            .header("Cookie", cookie)
            .build()
    }

    /** open 帧：endpoint 如 session/follow；payload 必须为恰好含 args 的对象。 */
    fun openStreamFrame(streamId: String, endpoint: String, args: JSONObject): String =
        JSONObject()
            .put("type", "open")
            .put("streamId", streamId)
            .put("endpoint", endpoint)
            .put("payload", JSONObject().put("args", args))
            .toString()

    fun cancelStreamFrame(streamId: String): String =
        JSONObject().put("type", "cancel").put("streamId", streamId).toString()

    companion object {
        /** WS 常连：心跳为协议层 Ping（OkHttp 自动回 Pong），读超时归零。 */
        val client: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .retryOnConnectionFailure(false)
                .build()
        }
    }
}

/** DSH RPC 业务错误（result.ok=false 或传输失败）。 */
internal class DshRpcException(val code: String, message: String) : Exception("[$code] $message")
