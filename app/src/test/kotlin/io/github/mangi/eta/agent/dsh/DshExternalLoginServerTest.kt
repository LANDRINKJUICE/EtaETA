package io.github.mangi.eta.agent.dsh

import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 外部浏览器中转端点的行为验证。
 *
 * 核心断言：`/dsh-login` 必须回 200 + HTML 桥接页（用 JS 写 cookie 再跳转），且这条
 * `Set-Cookie` **不能**带 `SameSite=Strict` —— 那正是 DSH 自己那条把外部
 * 跳转挡在 401 的策略，ETA 侧必须绕开。
 */
class DshExternalLoginServerTest {

    @Test
    fun loginUrl_pointsAtLoopbackLoginPath() {
        val server = DshExternalLoginServer(
            port = DshExternalLoginServer.DEFAULT_PORT,
            targetUrl = "http://127.0.0.1:3080/",
            cookieProvider = { null },
            logger = {},
            warnLogger = {},
        )
        assertEquals(
            "http://127.0.0.1:${DshExternalLoginServer.DEFAULT_PORT}/dsh-login",
            server.loginUrl,
        )
    }

    @Test
    fun isStarted_isFalseBeforeStartAndAfterClose() {
        val server = DshExternalLoginServer(
            port = 0,
            targetUrl = "http://127.0.0.1:3080/",
            cookieProvider = { "dsh-session-VPhEEcLKeqRDBoBalzN2Nm7CnfxKhLE00pKIDWxt1sw" },
            logger = {},
            warnLogger = {},
        )
        assertTrue("新建实例不应处于已启动状态", !server.isStarted)
        server.close()
        assertTrue("关闭后不应处于已启动状态", !server.isStarted)
    }

    /**
     * cookie 拿不到时回 503 + 可读提示，而不是把 DSH 的 401 页面丢给浏览器。
     */
    @Test
    fun loginWithoutCookieReturnsServiceUnavailable() {
        val server = DshExternalLoginServer(
            port = freePort(),
            targetUrl = "http://127.0.0.1:3080/",
            cookieProvider = { null },
            logger = {},
            warnLogger = {},
        )
        server.start()
        try {
            val (status, body) = get(server.loginUrl)
            assertEquals(503, status)
            assertTrue("应提示回 App 重试", body.contains("DSH 尚未就绪"))
        } finally {
            server.close()
        }
    }

    /**
     * 关键用例：回 200 + HTML 桥接页，用 JS 写 cookie 再跳转。
     *
     * 不能回 303：实测 Chrome 对「外部应用跳转」过来的 303 响应不保存 Cookie
     * （交接 §8.1.1 的既有结论）。脚本写 cookie 不受该限制。
     * 同时 Set-Cookie 仍不得带 SameSite=Strict。
     */
    @Test
    fun loginWithCookieReturnsBridgeHtmlAndSameSiteFreeCookie() {
        val cookie = "dsh-auth-abc=v1.payload.sig"
        val server = DshExternalLoginServer(
            port = freePort(),
            targetUrl = "http://127.0.0.1:3080/",
            cookieProvider = { cookie },
            logger = {},
            warnLogger = {},
        )
        server.start()
        try {
            val connection = URL(server.loginUrl).openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.requestMethod = "GET"
            try {
                assertEquals(200, connection.responseCode)
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                assertTrue("必须是 HTML 桥接页", body.contains("document.cookie"))
                assertTrue("必须用 JS 写会话 cookie", body.contains(cookie.substringBefore('=')))
                assertTrue("必须跳转到 DSH", body.contains("location.replace"))
                assertTrue("跳转目标必须是 3080", body.contains("http://127.0.0.1:3080/"))
                val setCookie = connection.getHeaderField("Set-Cookie")
                assertNotNull(setCookie)
                assertTrue("必须下发会话 cookie", setCookie!!.startsWith("$cookie;"))
                assertTrue("必须带 Path=/", setCookie.contains("Path=/"))
                assertTrue(
                    "不得带 SameSite=Strict：外部跳转会被浏览器丢弃",
                    !setCookie.contains("SameSite", ignoreCase = true),
                )
            } finally {
                connection.disconnect()
            }
        } finally {
            server.close()
        }
    }

    /** 非登录路径一律 404，避免这个端点变成通用本地服务。 */
    @Test
    fun otherPathsReturn404() {
        val server = DshExternalLoginServer(
            port = freePort(),
            targetUrl = "http://127.0.0.1:3080/",
            cookieProvider = { "dsh-session-VPhEEcLKeqRDBoBalzN2Nm7CnfxKhLE00pKIDWxt1sw" },
            logger = {},
            warnLogger = {},
        )
        server.start()
        try {
            val base = server.loginUrl.removeSuffix("/dsh-login")
            val connection = URL("$base/anything").openConnection() as HttpURLConnection
            try {
                assertEquals(404, connection.responseCode)
            } finally {
                connection.disconnect()
            }
        } finally {
            server.close()
        }
    }

    /** 端口被占时 start() 不抛异常，只是没起来（外部打开是增强项）。 */
    @Test
    fun startOnOccupiedPortDoesNotThrow() {
        val port = freePort()
        val first = DshExternalLoginServer(port, "http://127.0.0.1:3080/", { "a=b" }, {}, {})
        first.start()
        val second = DshExternalLoginServer(port, "http://127.0.0.1:3080/", { "a=b" }, {}, {})
        try {
            second.start() // 不应抛
            assertTrue("第二个实例不该抢占成功", !second.isStarted)
        } finally {
            second.close()
            first.close()
        }
    }

    private fun get(url: String): Pair<Int, String> {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        return try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            status to body
        } finally {
            connection.disconnect()
        }
    }

    /** 借一个当前空闲的端口号，避免测试之间互相干扰。 */
    private fun freePort(): Int = Socket().use { socket ->
        socket.bind(java.net.InetSocketAddress("127.0.0.1", 0))
        socket.localPort
    }
}
