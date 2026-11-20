package io.github.mangi.eta.agent.dsh

import android.content.Context
import io.github.mangi.eta.agent.terminal.DetachedTaskSupervisor
import io.github.mangi.eta.agent.terminal.LinuxEnvironmentPaths
import io.github.mangi.eta.agent.terminal.SharedFolderMounts
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.data.repository.LinuxEnvironmentSettingsRepository
import io.github.mangi.eta.agent.terminal.terminalEnvironment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * DSH 集成的总协调器：引擎进程 → 鉴权 → 会话生命周期。
 *
 * 每个绑定为 DSH 的 Eta 会话持有一个 [DshConversation]；引擎 token 随进程重启轮换，
 * 由 [ensureConnected] 按需从 daemon 日志重新获取并重走 /connect。
 */
internal class DshCoordinator(private val context: Context) {
    private val daemonSupervisor = DetachedTaskSupervisor(
        logger = AndroidAgentLogger,
        recordsFile = DetachedTaskSupervisor.defaultRecordsFile(context),
        linuxRootfsPathProvider = { environment ->
            environment.linuxDistribution?.let { distribution ->
                LinuxEnvironmentPaths.rootfsDir(context, distribution).absolutePath
            }
        },
        linuxSharedMountsProvider = { SharedFolderMounts.current() },
    )
    private val engine = DshEngineLauncher(context, daemonSupervisor)
    private val client = DshWebClient()
    private val sockets = DshSocketHub(client)
    private val conversations = mutableMapOf<String, DshConversation>()
    private val connectMutex = Mutex()

    /** 每次连接会话的引擎启动 URL（WebView 入口用），进程未运行时为 null。 */
    @Volatile
    var lastLaunchUrl: String? = null
        private set

    /** 确保「引擎运行 + API Cookie 就绪」，幂等。失败返回错误码。 */
    private suspend fun ensureConnected(): String? = connectMutex.withLock {
        if (client.hasSession() && sockets.isOpen()) return null
        when (val launched = engine.launch(currentEnvironment())) {
            is DshEngineResult.Failed -> return launched.code
            is DshEngineResult.Ready -> {
                lastLaunchUrl = launched.url
                val token = launched.url.substringAfterLast("token=", "")
                if (token.isBlank() || !client.connect(token)) return "AUTH_FAILED"
                sockets.ensureOpen { }
                null
            }
        }
    }

    /** 新建（或复用）DSH 会话，返回会话句柄；失败返回 null。 */
    suspend fun openSession(conversationKey: String): DshConversation? {
        if (ensureConnected() != null) return null
        conversations[conversationKey]?.let { return it }
        return try {
            val value = client.rpc("session", "create", JSONObject().put("cwd", "/workspace"))
            val sessionId = value.optString("sessionId").takeIf { it.isNotBlank() } ?: return null
            val conversation = DshConversation(sessionId, client, sockets)
            conversation.openFollow()
            conversations[conversationKey] = conversation
            conversation
        } catch (failure: DshRpcException) {
            AndroidAgentLogger.warn("dsh_session_create_failed code=${failure.code}")
            null
        } catch (failure: Exception) {
            AndroidAgentLogger.warn("dsh_session_create_failed type=${failure.javaClass.simpleName}")
            null
        }
    }

    fun conversationEvents(conversationKey: String): Flow<DshStreamEvent>? =
        conversations[conversationKey]?.events

    suspend fun sendPrompt(conversationKey: String, text: String): Boolean {
        var conversation = conversations[conversationKey]
        if (conversation == null) {
            conversation = openSession(conversationKey) ?: return false
            conversations[conversationKey] = conversation
        }
        return conversation.prompt(text)
    }

    fun closeSession(conversationKey: String) {
        conversations.remove(conversationKey)?.close()
    }

    /** 供首页 WebView 入口：确保引擎运行并返回经过 303 自检的启动 URL。 */
    suspend fun launchUrlForWebView(): String? {
        // ensureConnected 返回 null 表示成功；返回错误码表示失败。
        val error = ensureConnected()
        if (error != null) return null
        val url = lastLaunchUrl ?: return null
        if (verifyLaunchUrl(url)) return url
        // 自检失败最常见于引擎曾重启、旧 URL 令牌失效：重启引擎换新 token 再验一次。
        if (restartEngine()) {
            val fresh = lastLaunchUrl ?: return null
            if (verifyLaunchUrl(fresh)) return fresh
        }
        return null
    }

    /** 浏览器同款探测：带 token 请求根路径，合法令牌应答 303（token→Cookie 交换）。 */
    private suspend fun verifyLaunchUrl(url: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val probe = OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .followRedirects(false)
                .build()
            probe.newCall(Request.Builder().url(url).build()).execute().use { it.code == 303 }
        } catch (_: Exception) {
            false
        }
    }

    /** 重启引擎并重建连接（旧 API Cookie 随旧进程失效，一并作废）。
     *  stop 失败（如进程已死残留记录）不阻断重启：disconnect/close 后 ensureConnected
     *  的 launch() 会按命令复用逻辑重新拉起，旧 Cookie 已作废无需回退。 */
    private suspend fun restartEngine(): Boolean = try {
        runCatching { engine.stop(currentEnvironment()) }
        client.disconnect()
        sockets.close()
        ensureConnected() == null
    } catch (_: Exception) {
        false
    }

    fun shutdown() {
        conversations.values.forEach(DshConversation::close)
        conversations.clear()
        sockets.close()
        client.disconnect()
    }

    private fun currentEnvironment() =
        LinuxEnvironmentSettingsRepository.current(context).terminalEnvironment


}
