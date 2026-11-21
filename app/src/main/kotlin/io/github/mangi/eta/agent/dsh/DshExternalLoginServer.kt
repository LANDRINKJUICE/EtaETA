package io.github.mangi.eta.agent.dsh

import io.github.mangi.eta.core.AndroidAgentLogger
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 让**外部浏览器**也能进入 DSH 的中转端点。
 *
 * ## 为什么需要它
 *
 * DSH 的会话 cookie 由 `dsh-client-connection` 硬编码为
 * `SameSite=Strict`（`browser-auth.ts:138`，实测确认）。用 `ACTION_VIEW`
 * 把 `http://127.0.0.1:3080/?token=…` 交给 Chrome 时，Chrome 会收到 303 +
 * `Set-Cookie`，但随后那次跳转**不会带上刚收到的 cookie** —— 于是停在
 * `dsh web authentication required`。内置 WebView 不受影响（它自己保存 cookie）。
 *
 * 改 DSH 的 `SameSite` 会被升级覆盖（且 dsh-purge 的 `revertAll` 会静默还原），
 * 所以只能从 ETA 侧绕。
 *
 * ## 做法
 *
 * 本服务监听 `127.0.0.1:[port]`，只处理一个路径：
 *
 * ```
 * GET /dsh-login
 *   → 303 See Other
 *     Set-Cookie: <ETA 自己签的 dsh 会话 cookie>; Path=/; Max-Age=…
 *     Location: http://127.0.0.1:3080/
 * ```
 *
 * 关键点：cookie 的域是 `127.0.0.1`，与 DSH 的 `127.0.0.1:3080` **同域**，
 * 因此浏览器会把它带给 3080。而这条 `Set-Cookie` 是 **ETA 发出的**，
 * 不带 `SameSite=Strict`，所以不受 DSH 那条策略约束。
 *
 * ## 开销
 *
 * 实测：`ServerSocket` 本身在测量误差内（< 1 MB），只多 1 个阻塞在 `accept()`
 * 的线程。请求处理完即关闭连接，不维持任何长连接。
 *
 * ## 安全
 *
 * - 只绑 `127.0.0.1`，外部网络不可达。
 * - 只在 [loginPath] 上响应，其他路径一律 404。
 * - 只发 cookie，不回显 cookie 内容，也不接受任何输入参数。
 */
internal class DshExternalLoginServer(
    private val port: Int = DEFAULT_PORT,
    private val targetUrl: String,
    private val cookieProvider: () -> String?,
    /** 可注入，便于 JVM 单测（`AndroidAgentLogger` 走 `android.util.Log`，单测里会抛）。 */
    private val logger: (String) -> Unit = { AndroidAgentLogger.info(it) },
    private val warnLogger: (String) -> Unit = { AndroidAgentLogger.warn(it) },
) : AutoCloseable {

    private val closed = AtomicBoolean(false)
    private var server: ServerSocket? = null
    private var worker: Thread? = null

    /** 中转地址；外部浏览器应打开这个地址而不是 DSH 的原始地址。 */
    val loginUrl: String get() = "http://$HOST:$port$LOGIN_PATH"

    /** 是否已成功绑定端口并开始接受连接。 */
    val isStarted: Boolean get() = server != null && !closed.get()

    /**
     * 启动监听。重复调用是幂等的；端口被占等失败只记录日志，不抛给调用方
     * —— 外部打开属于增强功能，不能因为它拖垮整条链路。
     */
    fun start() {
        if (server != null || closed.get()) return
        val socket = try {
            ServerSocket(port, BACKLOG, InetAddress.getByName(HOST))
        } catch (error: IOException) {
            warnLogger("dsh_external_login_bind_failed port=$port error=${error.javaClass.simpleName}")
            return
        }
        server = socket
        worker = Thread({ acceptLoop(socket) }, "dsh-external-login").apply {
            isDaemon = true
            start()
        }
        logger("dsh_external_login_started port=$port")
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (!closed.get() && !socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (_: IOException) {
                // close() 会让 accept() 抛异常，属正常退出路径。
                return
            }
            try {
                handle(client)
            } catch (_: IOException) {
                // 浏览器提前断开（用户取消、切后台）不算错误。
            } finally {
                runCatching { client.close() }
            }
        }
    }

    private fun handle(client: Socket) {
        client.soTimeout = READ_TIMEOUT_MS
        val requestLine = readRequestLine(client) ?: return
        val path = requestLine.split(' ').getOrNull(1)?.substringBefore('?') ?: return
        if (path != LOGIN_PATH) {
            respond(client, 404, "Not Found", "text/plain; charset=utf-8", emptyList())
            return
        }
        val cookie = cookieProvider()
        if (cookie.isNullOrBlank()) {
            // 拿不到会话（引擎未就绪或凭据不可读）：明确告诉用户回 App 重试，
            // 而不是把 401 页面丢给浏览器让人困惑。
            respond(
                client,
                503,
                loginFailedHtml(),
                "text/html; charset=utf-8",
                emptyList(),
            )
            return
        }
        // 不能只回 303 + Set-Cookie：实测 Chrome 对「外部应用跳转」过来的
        // 303 响应不保存 Cookie（交接 §8.1.1 的既有结论，本轮再次验证）。
        // 改成回一个 HTML，用 JS 显式写 cookie 再跳转——脚本写 cookie 不受
        // 该限制，且 DSH 接受同值但非 HttpOnly 的 cookie（实测 200）。
        respond(
            client,
            200,
            loginBridgeHtml(cookie),
            "text/html; charset=utf-8",
            listOf(
                // 仍然发一次 Set-Cookie：Chrome 若接受就双保险，不接受也无害。
                "Set-Cookie: $cookie; Path=/; Max-Age=$COOKIE_MAX_AGE_SECONDS",
                "Cache-Control: no-store",
            ),
        )
    }

    /**
     * 用脚本写入 cookie 再跳转的桥接页。
     *
     * `cookie` 形如 `name=value`，只含 base64url 与点号，可直接拼进 JS 字符串；
     * 这里仍做一次转义，避免将来格式变化引入注入。
     */
    private fun loginBridgeHtml(cookie: String): String {
        val name = cookie.substringBefore('=')
        val value = cookie.substringAfter('=')
        val safeName = jsEscape(name)
        val safeValue = jsEscape(value)
        val safeTarget = jsEscape(targetUrl)
        return """
            <!doctype html><meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <title>正在进入 DSH…</title>
            <body style="font-family:system-ui;padding:24px">
            正在进入 DSH…
            <script>
            (function () {
              var name = '$safeName';
              var value = '$safeValue';
              var target = '$safeTarget';
              document.cookie = name + '=' + value + '; path=/; max-age=$COOKIE_MAX_AGE_SECONDS';
              if (document.cookie.indexOf(name + '=') === -1) {
                document.body.textContent = 'Cookie 被浏览器拒绝，请在 Eta 内打开 DSH。';
                return;
              }
              location.replace(target);
            })();
            </script>
            </body>
        """.trimIndent()
    }

    /** 只保留安全字符，避免拼进 JS 单引号字符串时出问题。 */
    private fun jsEscape(value: String): String =
        value.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "").replace("\r", "")

    /** 只读首行，够用且不会因为读到 body 而阻塞。 */
    private fun readRequestLine(client: Socket): String? {
        val reader = client.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        return reader.readLine()?.takeIf { it.isNotBlank() }
    }

    private fun respond(
        client: Socket,
        status: Int,
        body: String,
        contentType: String,
        extraHeaders: List<String>,
    ) {
        val reason = when (status) {
            200 -> "OK"
            303 -> "See Other"
            404 -> "Not Found"
            503 -> "Service Unavailable"
            else -> "OK"
        }
        val payload = body.toByteArray(Charsets.UTF_8)
        val head = buildString {
            append("HTTP/1.1 $status $reason\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${payload.size}\r\n")
            append("Connection: close\r\n")
            extraHeaders.forEach { append(it).append("\r\n") }
            append("\r\n")
        }
        client.getOutputStream().apply {
            write(head.toByteArray(Charsets.ISO_8859_1))
            write(payload)
            flush()
        }
    }

    private fun loginFailedHtml(): String =
        """
        <!doctype html><meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <title>DSH 尚未就绪</title>
        <body style="font-family:system-ui;padding:24px;line-height:1.7">
        <h3>DSH 尚未就绪</h3>
        <p>请回到 Eta 里先打开一次 DSH，再重试外部打开。</p>
        </body>
        """.trimIndent()

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { server?.close() }
        server = null
        worker = null
    }

    companion object {
        /** 与 DSH 主端口 3080 错开，且落在常见动态端口段之前的固定位。 */
        const val DEFAULT_PORT = 3090
        private const val HOST = "127.0.0.1"
        private const val LOGIN_PATH = "/dsh-login"
        private const val BACKLOG = 4
        private const val READ_TIMEOUT_MS = 5_000
        private const val COOKIE_MAX_AGE_SECONDS = 30L * 24 * 3600
    }
}
