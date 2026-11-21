package io.github.mangi.eta.agent.dsh

import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * DSH web 引擎的访问凭据派生。
 *
 * Harness 侧 `processLaunchToken` 的取值是**由持久化签名密钥派生**的：以
 * `client-connection/browser-session` 记录里的 secret 作为 HMAC 密钥、
 * `dsh-launch-token-v1` 作为派生上下文，因此同一 Harness home 的启动 token
 * 跨进程重启保持不变。ETA 在这里用同一算法复现，从而不再依赖
 * 「从 daemon 日志抓 `dsh web:` 启动行」这条脆弱路径。
 *
 * 这条路径失效才是「组件不可用」的根因：启动 token 原先每次进程重启都重新随机，
 * 引擎一旦被插件自愈重启（例如 dsh-purge 的 restart-web），ETA 手里抓到的旧 URL
 * 立刻变成 401；而 ETA 又因为自管的 daemon 记录里没有该进程，会再起一个实例去抢
 * 3080，撞上 EADDRINUSE 后整片依赖 `webServer` 的插件全部挂起，日志里再也不会
 * 出现可用的启动行。
 */
internal object DshWebAuth {

    /** 派生上下文，与 Harness 侧 `processLaunchToken` 的派生输入保持一致。 */
    private const val TOKEN_DERIVE_CONTEXT = "dsh-launch-token-v1"
    private const val HMAC_ALGORITHM = "HmacSHA256"

    /** 凭据文件相对 rootfs 的路径，与引擎命令里的 `DSH_HOME` 保持一致。 */
    private const val CREDENTIALS_RELATIVE_PATH = "opt/eta/dsh-home/.credentials.yaml"

    /** 浏览器会话凭据记录的 key。 */
    private const val RECORD_KEY = "client-connection/browser-session"

    /**
     * 签发 cookie 的有效期，对齐官方 `cookieMaxAgeDays` 的默认值（30 天）。
     *
     * 服务端会拒绝 `expiresAt - issuedAt` 超过自己配置上限的 cookie，因此这里只能
     * 取官方默认值而不能更长；用户把 `cookieMaxAgeDays` 调小时，本 cookie 会被判过期，
     * 下次启动重新签发即可。
     */
    private const val COOKIE_MAX_AGE_SECONDS = 30L * 24 * 3600

    /**
     * 读取 rootfs 内持久化的浏览器会话签名密钥。
     * 文件缺失、记录缺失或格式不符时返回 `null`，调用方需回退到日志抓取。
     */
    fun readSigningSecret(rootfs: File): ByteArray? {
        val file = File(rootfs, CREDENTIALS_RELATIVE_PATH)
        if (!file.isFile) return null
        val text = runCatching { file.readText() }.getOrNull() ?: return null
        val record = text.substringAfter("$RECORD_KEY:", missingDelimiterValue = "\u0000")
            .takeIf { it.isNotEmpty() } ?: return null
        val value = Regex("""secret:\s*['"]?([^'"\s]+)['"]?""")
            .find(record)?.groupValues?.get(1) ?: return null
        return runCatching {
            Base64.getUrlDecoder().decode(value.trimEnd('='))
        }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    /** 由签名密钥派生启动 token（43 位 base64url，与 Harness 侧一致）。 */
    fun launchToken(secret: ByteArray): String {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(secret, HMAC_ALGORITHM))
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(mac.doFinal(TOKEN_DERIVE_CONTEXT.toByteArray(Charsets.UTF_8)))
    }

    /** 组装带 token 的入口地址，供 WebView 直接加载。 */
    fun authenticatedUrl(host: String, port: Int, token: String): String =
        "http://$host:$port/?token=$token"

    /** 引擎的干净入口地址（不带 token），配合 [mintCookie] 使用。 */
    fun cleanUrl(host: String, port: Int): String = "http://$host:$port/"

    /**
     * 用持久化签名密钥直接签发浏览器会话 cookie。
     *
     * 这条路径与启动 token 无关：cookie 的签名密钥持久化在 `.credentials.yaml`，
     * 跨进程重启不变，因此即便引擎没有加载任何 token 相关插件，也能凭 cookie 通过认证。
     * 返回 `name=value`，调用方交给 [installCookie] 写入 CookieManager。
     *
     * 有效期取 [COOKIE_MAX_AGE_SECONDS]（官方 `cookieMaxAgeDays` 的默认值 30 天）。
     * 服务端校验里有一条 `expiresAt - issuedAt > maxAgeMilliseconds` 就拒绝，
     * 所以这里**不能**比官方默认值更长；调短则每次启动重签，代价可忽略。
     */
    fun mintCookie(secret: ByteArray, host: String, port: Int): String {
        val authority = "$host:$port"
        val name = cookieName(authority)
        val now = System.currentTimeMillis()
        val expiresAt = now + COOKIE_MAX_AGE_SECONDS * 1000
        val payload = """{"version":1,"authority":"$authority","issuedAt":$now,"expiresAt":$expiresAt}"""
        val body = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(payload.toByteArray(Charsets.UTF_8))
        val signature = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(hmac(secret, body))
        return "$name=v1.$body.$signature"
    }

    /** 与 Harness 侧一致的 cookie 名派生：dsh-auth-(HMAC-SHA256(HttpOnly))。 */
    private fun cookieName(authority: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(authority.toByteArray(Charsets.UTF_8))
        return "dsh-auth-" + Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    private fun hmac(secret: ByteArray, body: String): ByteArray {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(secret, HMAC_ALGORITHM))
        return mac.doFinal(body.toByteArray(Charsets.UTF_8))
    }

    /**
     * 把签发好的 cookie 写入应用级 CookieManager，供 WebView 后续请求携带。
     *
     * `CookieManager` 的方法都标注 `@AnyThread`，无需切主线程；但必须 `flush()`：
     * `setCookie` 只写内存，WebView 首次导航在独立渲染进程里读 cookie，不落盘就带不上，
     * 表现是干净地址仍然 401。
     */
    fun installCookie(url: String, cookie: String) {
        runCatching {
            val manager = android.webkit.CookieManager.getInstance()
            manager.setCookie(url, cookie)
            manager.flush()
        }
    }

    /**
     * 用签发好的 cookie 探测入口地址是否可用（返回 200 表示 cookie 被接受）。
     * 与 [probeUsable] 互补：token 失效时仍可凭持久化密钥签发的 cookie 通过认证。
     */
    fun probeWithCookie(url: String, cookie: String, timeoutMs: Int = 2_000): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        return runCatching {
            val connection = uri.toURL().openConnection() as? HttpURLConnection
                ?: return@runCatching false
            connection.requestMethod = "GET"
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Cookie", cookie)
            try {
                connection.responseCode == 200
            } finally {
                connection.disconnect()
            }
        }.getOrDefault(false)
    }

    /**
     * 探测入口地址当前是否可用：带 token 的请求应被接受（303 发放 Cookie 或直接 200）。
     * 连接被拒、超时、非预期状态码都返回 `false`，用于判断引擎是否真的就绪。
     */
    fun probeUsable(url: String, timeoutMs: Int = 2_000): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        return runCatching {
            val connection = uri.toURL().openConnection() as? HttpURLConnection
                ?: return@runCatching false
            connection.requestMethod = "GET"
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.instanceFollowRedirects = false
            try {
                connection.responseCode in 200..303
            } finally {
                connection.disconnect()
            }
        }.getOrDefault(false)
    }

    /**
     * 探测端口上是否有进程在监听（不校验身份）。
     * 用于区分「端口被别的进程占着」与「引擎完全没起来」，避免把 EADDRINUSE
     * 误判成引擎启动失败。
     */
    fun isPortListening(host: String, port: Int, timeoutMs: Int = 2_000): Boolean {
        return runCatching {
            java.net.Socket().use { socket ->
                socket.connect(java.net.InetSocketAddress(host, port), timeoutMs)
                socket.isConnected
            }
        }.getOrDefault(false)
    }
}
