package io.github.mangi.eta.agent.mcp

import android.content.Context
import io.github.mangi.eta.agent.tool.ToolInvoker
import io.github.mangi.eta.core.AgentLogger
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

/**
 * 入站 MCP 服务端：把 Eta 的本地工具目录暴露给 DSH 引擎（以及任何 MCP 客户端）。
 *
 * 只实现协议必需的三件事：`initialize` / `tools/list` / `tools/call`；
 * 传输用最简 HTTP/1.1（逐请求一个连接，`Connection: close`），不引入任何 server 依赖。
 *
 * 工具执行走 [ToolInvoker]，每次调用先经 [approvalGate] 裁决：
 * 只读工具自动放行，其余在 App 内弹审批卡，拒绝即返回 `isError`。
 */
internal class McpToolServer(
    private val context: Context,
    private val logger: AgentLogger,
    private val port: Int = DEFAULT_PORT,
    private val approvalGate: McpApprovalGate,
    private val onToolCall: (McpToolCallRecord) -> Unit = {},
) : AutoCloseable {

    /** 一次工具调用的审计记录。 */
    data class McpToolCallRecord(
        val toolName: String,
        val arguments: String,
        val approved: Boolean,
        val durationMs: Long,
        val resultPreview: String,
    )

    private val lock = Any()
    private var serverSocket: ServerSocket? = null
    private val connections = Collections.synchronizedSet(mutableSetOf<Socket>())
    private val closed = AtomicBoolean(false)
    private val invoker = ToolInvoker(context.applicationContext, logger)
    private val toolCache = Any()

    @Volatile
    private var cachedTools: JSONArray? = null

    val isRunning: Boolean get() = synchronized(lock) { serverSocket != null }

    /** 幂等启动；端口被占用返回 false，不抛异常。 */
    fun start(): Boolean = synchronized(lock) {
        if (serverSocket != null) return true
        if (closed.get()) return false
        val socket = try {
            ServerSocket(port, BACKLOG, InetAddress.getByName("127.0.0.1"))
        } catch (failure: Exception) {
            logger.warn("mcp_server_bind_failed port=$port type=${failure.javaClass.simpleName}")
            return false
        }
        serverSocket = socket
        Thread({ acceptLoop(socket) }, "eta-mcp-accept").start()
        true
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (true) {
            val client = try {
                socket.accept()
            } catch (_: Exception) {
                break
            }
            connections += client
            Thread({ handle(client) }, "eta-mcp-conn").start()
        }
    }

    private fun handle(client: Socket) {
        try {
            client.soTimeout = REQUEST_TIMEOUT_MS
            val request = readRequest(client)
            if (request == null) {
                // 请求行/头非法、Host 不匹配或 body 超限：明确回 400，别让客户端干等到超时。
                writeStatus(client, "400 Bad Request")
                return
            }
            when (request.method) {
                "POST" -> {
                    val response = dispatch(request)
                    if (response != null) writeResponse(client, response) else writeAccepted(client)
                }
                // 会话终止：无状态服务端没有会话要清理，按规范回 2xx 即可。
                "DELETE" -> writeAccepted(client)
                // 不提供服务端主动推送的 SSE 流；按规范用 405 明确拒绝。
                else -> writeStatus(client, "405 Method Not Allowed", allow = "POST, DELETE")
            }
        } catch (_: SocketTimeoutException) {
            // 客户端提前断开或超时：无需响应。
        } catch (failure: Exception) {
            logger.warn("mcp_conn_failed type=${failure.javaClass.simpleName}")
        } finally {
            connections -= client
            runCatching { client.close() }
        }
    }

    private fun dispatch(request: HttpRequest): JSONObject? {
        val body = McpJsonRpc.parse(request.body) ?: return McpJsonRpc.error(
            id = null,
            code = McpJsonRpc.PARSE_ERROR,
            message = "请求体不是合法 JSON",
        )
        val id = body.opt("id")
        if (McpJsonRpc.isNotification(body)) return null
        return when (body.optString("method")) {
            "initialize" -> McpJsonRpc.result(id, initializeResult(body.optJSONObject("params")))
            "tools/list" -> McpJsonRpc.result(id, toolsListResult())
            "tools/call" -> McpJsonRpc.result(id, toolsCallResult(body.optJSONObject("params")))
            "ping" -> McpJsonRpc.result(id, JSONObject())
            else -> McpJsonRpc.error(id, McpJsonRpc.METHOD_NOT_FOUND, "不支持的方法：${body.optString("method")}")
        }
    }

    /** 协商版本：客户端请求的版本在我们支持的列表里就沿用，否则回我们最新的。
     *  回错版本会让官方 SDK 直接断开，所以不能无条件写死。 */
    private fun initializeResult(params: JSONObject?): JSONObject = JSONObject()
        .put("protocolVersion", negotiateVersion(params?.optString("protocolVersion")))
        .put("capabilities", JSONObject().put("tools", JSONObject()))
        .put("serverInfo", JSONObject().put("name", "eta").put("version", SERVER_VERSION))
        .put("resultType", "complete")

    private fun negotiateVersion(requested: String?): String =
        requested?.takeIf { it in SUPPORTED_PROTOCOL_VERSIONS } ?: LATEST_PROTOCOL_VERSION

    private fun toolsListResult(): JSONObject {
        val tools = JSONArray()
        val source = toolsCatalog()
        for (index in 0 until source.length()) {
            val function = source.optJSONObject(index)?.optJSONObject("function") ?: continue
            tools.put(
                JSONObject()
                    .put("name", function.optString("name"))
                    .put("description", function.optString("description"))
                    .put("inputSchema", function.optJSONObject("parameters") ?: emptyObjectSchema())
                    .put("annotations", annotationsFor(function.optString("name"))),
            )
        }
        return JSONObject()
            .put("resultType", "complete")
            .put("ttlMs", TOOL_CACHE_TTL_MS)
            .put("tools", tools)
    }

    private fun toolsCatalog(): JSONArray = cachedTools ?: synchronized(toolCache) {
        cachedTools ?: invoker.catalog().also { cachedTools = it }
    }

    private fun emptyObjectSchema(): JSONObject =
        JSONObject().put("type", "object").put("properties", JSONObject())

    /** 只读工具带 readOnlyHint；破坏性工具带 destructiveHint，供客户端做展示与策略判断。 */
    private fun annotationsFor(name: String): JSONObject {
        val readOnly = McpToolRisk.isReadOnly(name)
        return JSONObject()
            .put("readOnlyHint", readOnly)
            .put("destructiveHint", !readOnly)
            .put("idempotentHint", readOnly)
    }

    private fun toolsCallResult(params: JSONObject?): JSONObject {
        val name = params?.optString("name").orEmpty()
        if (name.isBlank()) return toolError("工具名不能为空")
        val known = knownToolNames()
        if (name !in known) return toolError("未知工具：$name")
        val arguments = params?.optJSONObject("arguments")?.toString() ?: "{}"
        val startedAt = System.currentTimeMillis()
        val decision = approvalGate.await(name, arguments)
        if (!decision.approved) {
            onToolCall(
                McpToolCallRecord(
                    toolName = name,
                    arguments = arguments,
                    approved = false,
                    durationMs = System.currentTimeMillis() - startedAt,
                    resultPreview = decision.reason,
                ),
            )
            return toolError("用户拒绝了该工具调用：${decision.reason}")
        }
        val result = invoker.invoke(name, arguments)
        onToolCall(
            McpToolCallRecord(
                toolName = name,
                arguments = arguments,
                approved = true,
                durationMs = System.currentTimeMillis() - startedAt,
                resultPreview = result.content.take(RESULT_PREVIEW_CHARS),
            ),
        )
        return JSONObject()
            .put("resultType", "complete")
            .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", result.content)))
            .put("isError", !isSuccess(result.content))
    }

    private fun knownToolNames(): Set<String> {
        if (toolNamesCache.isNotEmpty()) return toolNamesCache
        val source = toolsCatalog()
        val names = mutableSetOf<String>()
        for (index in 0 until source.length()) {
            source.optJSONObject(index)?.optJSONObject("function")?.optString("name")
                ?.takeIf(String::isNotBlank)?.let(names::add)
        }
        toolNamesCache = names
        return names
    }

    @Volatile
    private var toolNamesCache: Set<String> = emptySet()

    private fun isSuccess(content: String): Boolean = try {
        JSONObject(content).optBoolean("ok", true)
    } catch (_: Exception) {
        true
    }

    private fun toolError(message: String): JSONObject = JSONObject()
        .put("resultType", "complete")
        .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", message)))
        .put("isError", true)

    // ── HTTP/1.1 最小实现 ──

    private data class HttpRequest(val method: String, val body: String)

    private fun readRequest(socket: Socket): HttpRequest? {
        val input = BufferedInputStream(socket.getInputStream())
        val requestLine = readLine(input) ?: return null
        val parts = requestLine.split(' ')
        if (parts.size < 2) return null
        var contentLength = 0
        var host: String? = null
        while (true) {
            val line = readLine(input) ?: return null
            if (line.isEmpty()) break
            val separator = line.indexOf(':')
            if (separator <= 0) continue
            val headerName = line.substring(0, separator)
            val headerValue = line.substring(separator + 1).trim()
            when {
                headerName.equals("Content-Length", ignoreCase = true) ->
                    contentLength = headerValue.toIntOrNull() ?: 0
                headerName.equals("Host", ignoreCase = true) -> host = headerValue
            }
        }
        // 只接受本机回环 Host，挡住 DNS rebinding 之类的跨站请求。
        val expectedHost = "127.0.0.1:$port"
        if (host != null && !host.equals(expectedHost, ignoreCase = true) &&
            !host.equals("localhost:$port", ignoreCase = true)
        ) {
            logger.warn("mcp_server_host_rejected host=$host")
            return null
        }
        if (contentLength > MAX_BODY_BYTES) return null
        val body = if (contentLength > 0) {
            val buffer = ByteArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val next = input.read(buffer, read, contentLength - read)
                if (next < 0) return null
                read += next
            }
            String(buffer, Charsets.UTF_8)
        } else {
            ""
        }
        return HttpRequest(parts[0].uppercase(), body)
    }

    private fun readLine(input: BufferedInputStream): String? {
        val buffer = ByteArrayOutputStream(128)
        while (true) {
            val next = input.read()
            if (next < 0) return if (buffer.size() == 0) null else buffer.toString(Charsets.UTF_8.name())
            if (next == '\n'.code) return buffer.toString(Charsets.UTF_8.name()).trimEnd('\r')
            if (buffer.size() > MAX_HEADER_LINE_BYTES) return null
            buffer.write(next)
        }
    }

    private fun writeResponse(socket: Socket, json: JSONObject) {
        val body = json.toString().toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: application/json; charset=utf-8\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Connection: close\r\n\r\n"
        BufferedOutputStream(socket.getOutputStream()).apply {
            write(header.toByteArray(Charsets.US_ASCII))
            write(body)
            flush()
        }
    }

    /** 通知类请求：2xx + 空体（MCP 客户端只校验状态码）。 */
    private fun writeAccepted(socket: Socket) {
        writeStatus(socket, "202 Accepted")
    }

    private fun writeStatus(socket: Socket, status: String, allow: String? = null) {
        val allowHeader = allow?.let { "Allow: $it\r\n" }.orEmpty()
        val header = "HTTP/1.1 $status\r\n$allowHeader" +
            "Content-Length: 0\r\nConnection: close\r\n\r\n"
        BufferedOutputStream(socket.getOutputStream()).apply {
            write(header.toByteArray(Charsets.US_ASCII))
            flush()
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(lock) {
            runCatching { serverSocket?.close() }
            serverSocket = null
        }
        connections.toList().forEach { runCatching { it.close() } }
        connections.clear()
        runCatching { invoker.close() }
    }

    companion object {
        const val DEFAULT_PORT = 8790
        private const val BACKLOG = 16
        private const val REQUEST_TIMEOUT_MS = 120_000
        private const val MAX_BODY_BYTES = 4 * 1024 * 1024
        private const val MAX_HEADER_LINE_BYTES = 8 * 1024
        private const val RESULT_PREVIEW_CHARS = 512
        private const val TOOL_CACHE_TTL_MS = 60_000L
        private const val LATEST_PROTOCOL_VERSION = "2026-07-28"
        private val SUPPORTED_PROTOCOL_VERSIONS = setOf(
            "2026-07-28", "2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05",
        )
        private const val SERVER_VERSION = "1"
    }
}
