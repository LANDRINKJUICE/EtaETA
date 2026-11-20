package io.github.mangi.eta.agent.mcp

import org.json.JSONObject

/**
 * MCP 服务端用的 JSON-RPC 2.0 信封。
 *
 * 与 [McpHttpClient] 的客户端实现保持同一套字段语义：请求回显 `id`，
 * 通知（无 `id`）只回 2xx 空体，业务错误用 JSON-RPC error 而不是 HTTP 4xx
 * （4xx 会让客户端判定协议不兼容并降级到 legacy 握手）。
 */
internal object McpJsonRpc {
    /** 解析失败：请求体不是 JSON。 */
    const val PARSE_ERROR = -32700

    /** 方法不支持。 */
    const val METHOD_NOT_FOUND = -32601

    fun result(id: Any?, result: JSONObject): JSONObject = JSONObject()
        .put("jsonrpc", "2.0")
        .put("id", id)
        .put("result", result)

    fun error(id: Any?, code: Int, message: String): JSONObject = JSONObject()
        .put("jsonrpc", "2.0")
        .put("id", id)
        .put("error", JSONObject().put("code", code).put("message", message))

    /** 解析请求体；非 JSON 或非对象返回 null。 */
    fun parse(body: String): JSONObject? = try {
        JSONObject(body)
    } catch (_: Exception) {
        null
    }

    /** 通知（没有 id）不需要回 body。 */
    fun isNotification(request: JSONObject): Boolean =
        !request.has("id") || request.isNull("id")
}
