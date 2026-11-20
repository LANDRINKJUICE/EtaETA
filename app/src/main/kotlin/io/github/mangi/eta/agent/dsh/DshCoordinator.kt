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
    private val webProxy = DshWebProxy()
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
                sockets.ensureOpen()
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
            conversation.openFollow(activate = true)
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

    /** 接回已绑定的引擎会话（冷会话只读挂载，不抢占运行中的 Agent）；失败返回 null。 */
    suspend fun attachSession(conversationKey: String, sessionId: String): DshConversation? {
        if (ensureConnected() != null) return null
        conversations[conversationKey]?.let { existing ->
            if (existing.sessionId == sessionId) return existing
            existing.close()
            conversations.remove(conversationKey)
        }
        val conversation = DshConversation(sessionId, client, sockets)
        // 附加只读：不 activate，避免与网页端或其他客户端抢占同一会话的运行权。
        conversation.openFollow(activate = false)
        conversations[conversationKey] = conversation
        return conversation
    }

    /** 引擎侧的模型目录：provider 分组 + 默认选择，供 DSH 会话的模型选择器使用。 */
    suspend fun modelCatalog(): JSONObject? = try {
        if (ensureConnected() != null) null
        else client.rpc("session", "modelCatalog", args = null)
    } catch (failure: DshRpcException) {
        AndroidAgentLogger.warn("dsh_model_catalog_failed code=${failure.code}")
        null
    } catch (failure: Exception) {
        AndroidAgentLogger.warn("dsh_model_catalog_failed type=${failure.javaClass.simpleName}")
        null
    }

    /** 为已绑定的引擎会话选择模型；成功返回引擎确认后的选择。 */
    suspend fun selectModel(
        sessionId: String,
        provider: String,
        model: String,
        reasoningEffort: String?,
    ): JSONObject? = try {
        if (ensureConnected() != null) null
        else {
            val request = JSONObject()
                .put("sessionId", sessionId)
                .put("provider", provider)
                .put("model", model)
            if (reasoningEffort != null) request.put("reasoningEffort", reasoningEffort)
            client.rpc("session", "selectModel", request)
        }
    } catch (failure: DshRpcException) {
        AndroidAgentLogger.warn("dsh_select_model_failed code=${failure.code}")
        null
    } catch (failure: Exception) {
        AndroidAgentLogger.warn("dsh_select_model_failed type=${failure.javaClass.simpleName}")
        null
    }

    /** 释放本地句柄（引擎会话本身保留，便于下次接回）。 */
    fun detachSession(conversationKey: String) {
        conversations.remove(conversationKey)?.close()
    }

    suspend fun sendPrompt(conversationKey: String, text: String): Boolean {
        var conversation = conversations[conversationKey]
        if (conversation == null) {
            conversation = openSession(conversationKey) ?: return false
            conversations[conversationKey] = conversation
        }
        return conversation.prompt(text)
    }

    /** 供网页入口：确保引擎运行、本地代理就绪，返回代理地址（浏览器打开即用，无需种 Cookie）。 */
    suspend fun launchUrlForWebView(): String? {
        // ensureConnected 返回 null 表示成功；返回错误码表示失败。
        val error = ensureConnected()
        if (error != null) return null
        if (webProxyReady()) return webProxy.proxyUrl
        // 就绪失败最常见于引擎曾重启、旧令牌失效：重启引擎换新 token 再试一次。
        if (restartEngine()) {
            if (webProxyReady()) return webProxy.proxyUrl
        }
        return null
    }

    /** 代理就绪三步：token 换引擎 Cookie → 启动监听 → 探测入口返回 200。 */
    private suspend fun webProxyReady(): Boolean {
        val token = lastLaunchUrl?.substringAfterLast("token=", "").orEmpty()
        if (token.isBlank() || !webProxy.authenticate(token)) return false
        if (!webProxy.start()) return false
        return probeProxy()
    }

    /** 探测本地入口：代理注入凭据后，引擎应直接返回 200 页面。用带超时的独立探测端，避免挂住。 */
    private val probeClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    private suspend fun probeProxy(): Boolean = withContext(Dispatchers.IO) {
        try {
            probeClient.newCall(
                Request.Builder().url(webProxy.proxyUrl).build(),
            ).execute().use { it.code == 200 }
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
        webProxy.stop()
        ensureConnected() == null
    } catch (_: Exception) {
        false
    }

    private fun currentEnvironment() =
        LinuxEnvironmentSettingsRepository.current(context).terminalEnvironment


}
