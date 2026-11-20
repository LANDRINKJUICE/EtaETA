package io.github.mangi.eta.agent.dsh

import io.github.mangi.eta.core.AndroidAgentLogger
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.atomic.AtomicReference

/**
 * DSH Web 本地透明入口：浏览器访问 http://127.0.0.1:8793/，由本代理持引擎 Cookie 转发到引擎。
 *
 * 存在原因（2026-11-20 实测取证）：token→Cookie 交换依赖浏览器在 303 重定向链内立即保存并回传
 * Set-Cookie，同一 Chrome 上"地址栏输入"可行而"外部跳转"不可行。把凭据收到 App 侧统一持有后，
 * 浏览器无需种任何 Cookie——任何浏览器、任何打开方式都能直接进入，且入口 URL 不再携带进程 token。
 *
 * 转发规则（最小改写）：
 * - 请求头：Host/Origin/Referer 重写为引擎 authority；注入引擎 Cookie（浏览器若带旧 Cookie 则替换）；
 *   Connection 置 close（逐请求代理，引擎响应后关闭连接，无需解析 keep-alive）。
 * - 请求体与 WebSocket 升级后的帧：双向字节泵透传。
 * - 响应：原样透传（引擎响应的 Set-Cookie 对代理链路无害，浏览器种下也会在下一次请求时被替换）。
 */
internal class DshWebProxy(
    private val engineHost: String = "127.0.0.1",
    private val enginePort: Int = 3080,
) {
    private val lock = Any()
    private var server: ServerSocket? = null
    private val engineCookie = AtomicReference<String?>(null)

    val proxyUrl: String get() = "http://127.0.0.1:$PROXY_PORT/"

    /** 启动监听；幂等。端口被占等绑定失败返回 false，不抛出。 */
    fun start(): Boolean = synchronized(lock) {
        if (server != null) return true
        try {
            val s = ServerSocket(PROXY_PORT, 64, InetAddress.getByName("127.0.0.1"))
            server = s
            Thread({ acceptLoop(s) }, "dsh-web-proxy").start()
            true
        } catch (_: Exception) {
            false
        }
    }

    fun stop() {
        synchronized(lock) {
            server?.close()
            server = null
        }
        engineCookie.set(null)
    }

    /** 用引擎启动 token 换取引擎 Cookie（引擎重启后 token 轮换，需重新换取）。 */
    fun authenticate(launchToken: String): Boolean {
        val cookie = exchangeCookie(launchToken) ?: return false
        engineCookie.set(cookie)
        return true
    }

    private fun exchangeCookie(launchToken: String): String? = try {
        val conn = URL("http://$engineHost:$enginePort/?token=$launchToken").openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = false
        conn.connectTimeout = 5_000
        conn.readTimeout = 5_000
        try {
            if (conn.responseCode != 303) return null
            conn.headerFields.values.flatten()
                .firstOrNull { it.startsWith("dsh-auth-") }
                ?.substringBefore(';')
        } finally {
            conn.disconnect()
        }
    } catch (failure: Exception) {
        AndroidAgentLogger.warn("dsh_proxy_auth_failed type=${failure.javaClass.simpleName}")
        null
    }

    private fun acceptLoop(server: ServerSocket) {
        while (true) {
            val client = try {
                server.accept()
            } catch (_: Exception) {
                break
            }
            Thread({ handle(client) }, "dsh-web-proxy-conn").start()
        }
    }

    private fun handle(client: Socket) {
        try {
            client.soTimeout = 30_000
            val engine = Socket(engineHost, enginePort)
            try {
                rewriteRequest(client.getInputStream(), engine.getOutputStream())
                // 头已转发；浏览器→引擎方向（请求体/WS 帧）用独立线程持续泵送。
                val upstream = Thread({
                    pump(client.getInputStream(), engine.getOutputStream())
                }, "dsh-web-proxy-up")
                upstream.isDaemon = true
                upstream.start()
                // 当前线程泵引擎→浏览器（响应头/体/WS 帧）。
                pump(engine.getInputStream(), client.getOutputStream())
                upstream.join(1_000)
            } finally {
                runCatching { engine.close() }
            }
        } catch (failure: Exception) {
            AndroidAgentLogger.warn("dsh_proxy_conn_failed type=${failure.javaClass.simpleName}")
        } finally {
            runCatching { client.close() }
        }
    }

    /**
     * 读浏览器请求头并重写后写入引擎。行文本不含行尾（由本方法统一补 CRLF）。
     * WebSocket 升级请求（含 Upgrade 头）保留 Connection/Upgrade 原样透传以完成握手；
     * 普通请求 Connection 置 close（逐请求代理）。
     */
    private fun rewriteRequest(input: InputStream, output: OutputStream) {
        val authority = "$engineHost:$enginePort"
        val lines = mutableListOf<String>()
        while (true) {
            val line = readHeaderLine(input) ?: break
            if (line.isEmpty()) break
            lines.add(line)
        }
        if (lines.isEmpty()) return
        val isUpgrade = lines.any { it.lowercase().startsWith("upgrade:") }
        val cookie = engineCookie.get()
        var sawCookieHeader = false
        val rewritten = StringBuilder()
        for (line in lines) {
            val lower = line.lowercase()
            when {
                lower.startsWith("host:") -> rewritten.append("Host: ").append(authority).append("\r\n")
                lower.startsWith("origin:") -> rewritten.append("Origin: http://").append(authority).append("\r\n")
                lower.startsWith("referer:") -> rewritten.append("Referer: http://").append(authority).append("/\r\n")
                lower.startsWith("connection:") && !isUpgrade -> rewritten.append("Connection: close\r\n")
                lower.startsWith("cookie:") -> {
                    sawCookieHeader = true
                    if (cookie != null) rewritten.append("Cookie: ").append(cookie).append("\r\n")
                }
                else -> rewritten.append(line).append("\r\n")
            }
        }
        if (!sawCookieHeader && cookie != null) {
            rewritten.append("Cookie: ").append(cookie).append("\r\n")
        }
        rewritten.append("\r\n")
        output.write(rewritten.toString().toByteArray(Charsets.ISO_8859_1))
        output.flush()
    }

    private fun pump(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(16 * 1024)
        try {
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                output.write(buffer, 0, n)
                output.flush()
            }
        } catch (_: Exception) {
            // 任一端关闭即结束
        } finally {
            runCatching { input.close() }
            runCatching { output.close() }
        }
    }

    /** 读一行请求头；返回 null=流结束，空串=头结束空行，其余为不含行尾的行文本。 */
    private fun readHeaderLine(input: InputStream): String? {
        val out = ByteArrayOutputStream(128)
        while (true) {
            val b = input.read()
            if (b < 0) return null
            if (b == '\n'.code) {
                val size = out.size()
                if (size == 0) return "" // 裸 LF（防御）
                if (size == 1 && out.toByteArray()[0].toInt() == '\r'.code) return "" // CRLF 空行
                return out.toString(Charsets.ISO_8859_1.name()).trimEnd('\r')
            }
            out.write(b)
        }
    }

    companion object {
        const val PROXY_PORT = 8793
    }
}
