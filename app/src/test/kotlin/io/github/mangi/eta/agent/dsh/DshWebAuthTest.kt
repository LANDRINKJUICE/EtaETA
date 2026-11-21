package io.github.mangi.eta.agent.dsh

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 覆盖「启动 token 由持久化密钥派生」这一根修复。
 *
 * 修复前 token 每次进程重启都重新随机，ETA 从日志抓到的旧 URL 在重启后立刻 401；
 * 修复后 token 跨重启不变，ETA 可直接复算，不再依赖启动行。
 */
class DshWebAuthTest {

    /** 把一段文本编码成 base64url，用于构造测试用的 secret 字面量。 */
    private fun b64u(value: String): String =
        Base64.getUrlEncoder().withoutPadding()
            .encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    /** 把字节编码成 base64url，用于比对派生结果。 */
    private fun b64uBytes(value: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value)

    private fun secretBytes(value: String): ByteArray =
        Base64.getUrlDecoder().decode(value)

    private fun writeCredentials(rootfs: File, secret: String): File {
        val dir = File(rootfs, "opt/eta/dsh-home")
        dir.mkdirs()
        val file = File(dir, ".credentials.yaml")
        file.writeText(
            "version: 1\n" +
                "records:\n" +
                "  client-connection/browser-session:\n" +
                "    kind: grant\n" +
                "    payload:\n" +
                "      version: 1\n" +
                "      secret: $secret\n",
        )
        return file
    }

    @Test
    fun launchToken_derivesFromSecretAndStaysStable() {
        val secret = b64u("eta-test-secret-bytes-0000000000000000")
        val first = DshWebAuth.launchToken(secretBytes(secret))
        val second = DshWebAuth.launchToken(secretBytes(secret))

        assertEquals(first, second)
        assertEquals(43, first.length)
        // base64url 无 padding，不含 base64url 字符
        assertFalse(first.contains('+'))
        assertFalse(first.contains('/'))
        assertFalse(first.contains('='))
    }

    @Test
    fun launchToken_matchesReferenceHmacDerivation() {
        // 与 Harness 侧 processLaunchToken 的派生算法逐位对齐。
        val secret = b64u("reference-derivation-key-1234567890abcdef")
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secretBytes(secret), "HmacSHA256"))
        val expected = b64uBytes(mac.doFinal("dsh-launch-token-v1".toByteArray(StandardCharsets.UTF_8)))

        assertEquals(expected, DshWebAuth.launchToken(secretBytes(secret)))
    }

    @Test
    fun launchToken_differsPerSecret() {
        val a = DshWebAuth.launchToken(secretBytes(b64u("secret-one-00000000000000000000")))
        val b = DshWebAuth.launchToken(secretBytes(b64u("secret-two-00000000000000000000")))
        assertFalse(a == b)
    }

    @Test
    fun readSigningSecret_parsesCredentialsFile() {
        val rootfs = Files.createTempDirectory("dsh-auth-rootfs").toFile()
        val secret = b64u("persisted-signing-secret-0000000000000000")
        writeCredentials(rootfs, secret)

        val parsed = DshWebAuth.readSigningSecret(rootfs)
        assertNotNull(parsed)
        assertEquals(secretBytes(secret), parsed)
    }

    @Test
    fun readSigningSecret_returnsNullWhenFileMissing() {
        val rootfs = Files.createTempDirectory("dsh-auth-empty").toFile()
        assertNull(DshWebAuth.readSigningSecret(rootfs))
    }

    @Test
    fun readSigningSecret_returnsNullWhenRecordMissing() {
        val rootfs = Files.createTempDirectory("dsh-auth-norec").toFile()
        File(rootfs, "opt/eta/dsh-home/.credentials.yaml").apply {
            parentFile.mkdirs()
            writeText("version: 1\nrecords:\n  some-other-record:\n    kind: grant\n")
        }
        assertNull(DshWebAuth.readSigningSecret(rootfs))
    }

    @Test
    fun authenticatedUrl_carriesTokenAsQuery() {
        val url = DshWebAuth.authenticatedUrl("127.0.0.1", 3080, "abc123token")
        assertEquals("http://127.0.0.1:3080/?token=abc123token", url)
    }

    @Test
    fun cleanUrl_hasNoToken() {
        assertEquals("http://127.0.0.1:3080/", DshWebAuth.cleanUrl("127.0.0.1", 3080))
    }

    @Test
    fun mintCookie_producesNameAndSignedValue() {
        val secret = secretBytes(b64u("eta-test-secret-bytes-0000000000000000"))
        val cookie = DshWebAuth.mintCookie(secret, "127.0.0.1", 3080)

        val eq = cookie.indexOf('=')
        assertTrue(eq > 0)
        val name = cookie.substring(0, eq)
        val value = cookie.substring(eq + 1)
        // 值为 v1.<body>.<sig> 三段
        assertEquals(3, value.split('.').size)
        assertTrue(value.startsWith("v1."))
        // cookie 名以固定前缀开头
        assertTrue(name.startsWith("dsh-auth-"))
    }

    @Test
    fun mintCookie_nameMatchesSha256OfAuthority() {
        val secret = secretBytes(b64u("eta-test-secret-bytes-0000000000000000"))
        val cookie = DshWebAuth.mintCookie(secret, "127.0.0.1", 3080)
        val name = cookie.substringBefore('=')

        val digest = MessageDigest.getInstance("SHA-256")
            .digest("127.0.0.1:3080".toByteArray(StandardCharsets.UTF_8))
        val expected = "dsh-auth-" +
            Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
        assertEquals(expected, name)
    }

    /**
     * 服务端（browser-auth 的 decodeCookie）会逐字段校验：version 必须为 1、
     * authority 必须与请求一致、issuedAt/expiresAt 必须是安全整数且未过期。
     * 这里按同一规则反解自己签发的 cookie，确保格式真的能被接受，而不是只看着像。
     */
    @Test
    fun mintCookie_payloadSatisfiesServerValidation() {
        val secret = secretBytes(b64u("eta-test-secret-bytes-0000000000000000"))
        val authority = "127.0.0.1:3080"
        val cookie = DshWebAuth.mintCookie(secret, "127.0.0.1", 3080)

        val value = cookie.substringAfter('=')
        val parts = value.split('.')
        assertEquals(3, parts.size)
        assertEquals("v1", parts[0])

        val body = parts[1]
        val payload = String(
            Base64.getUrlDecoder().decode(body),
            StandardCharsets.UTF_8,
        )
        assertTrue(payload.contains("\"version\":1"))
        assertTrue(payload.contains("\"authority\":\"$authority\""))

        // 签名必须是 HMAC-SHA256(body) 的 base64url
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret, "HmacSHA256"))
        val expectedSignature = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(mac.doFinal(body.toByteArray(StandardCharsets.UTF_8)))
        assertEquals(expectedSignature, parts[2])

        // 有效期必须是正数且不超过 30 天（服务端上限）
        val issuedAt = Regex(""""issuedAt":(\d+)""").find(payload)!!.groupValues[1].toLong()
        val expiresAt = Regex(""""expiresAt":(\d+)""").find(payload)!!.groupValues[1].toLong()
        assertTrue(expiresAt > issuedAt)
        assertTrue(expiresAt - issuedAt <= 30L * 24 * 3600 * 1000)
    }

    @Test
    fun mintCookie_differsPerAuthority() {
        val secret = secretBytes(b64u("eta-test-secret-bytes-0000000000000000"))
        val a = DshWebAuth.mintCookie(secret, "127.0.0.1", 3080)
        val b = DshWebAuth.mintCookie(secret, "127.0.0.1", 3081)
        assertFalse(a == b)
    }

    @Test
    fun probeUsable_acceptsRespondingServer() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.responseHeaders.add("Content-Type", "text/plain")
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }
        server.start()
        val port = server.address.port
        try {
            assertTrue(DshWebAuth.probeUsable("http://127.0.0.1:$port/", 2_000))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun probeUsable_rejectsUnreachableAddress() {
        // 端口 1 通常无人监听，连接必然失败。
        assertFalse(DshWebAuth.probeUsable("http://127.0.0.1:1/", 500))
    }

    /** cookie 路径必须真的把 Cookie 头发出去，且只在服务端回 200 时判为可用。 */
    @Test
    fun probeWithCookie_sendsCookieHeaderAndAccepts200() {
        var seenCookie: String? = null
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            seenCookie = exchange.requestHeaders.getFirst("Cookie")
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }
        server.start()
        val port = server.address.port
        try {
            val cookie = "dsh-auth-test=v1.payload.sig"
            assertTrue(DshWebAuth.probeWithCookie("http://127.0.0.1:$port/", cookie, 2_000))
            assertEquals(cookie, seenCookie)
        } finally {
            server.stop(0)
        }
    }

    /** 服务端回 401 时必须判为不可用，不能把「有响应」当成「已认证」。 */
    @Test
    fun probeWithCookie_rejectsUnauthorized() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.sendResponseHeaders(401, -1)
            exchange.close()
        }
        server.start()
        val port = server.address.port
        try {
            assertFalse(DshWebAuth.probeWithCookie("http://127.0.0.1:$port/", "bad=1", 2_000))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun isPortListening_detectsListenerAndFreePort() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.start()
        val port = server.address.port
        try {
            assertTrue(DshWebAuth.isPortListening("127.0.0.1", port, 2_000))
            assertFalse(DshWebAuth.isPortListening("127.0.0.1", 1, 500))
        } finally {
            server.stop(0)
        }
    }
}
