package io.github.mangi.eta.agent.dsh

import android.content.Context
import io.github.mangi.eta.agent.device.BoundedRootCommandExecutor
import io.github.mangi.eta.agent.terminal.DetachedTaskSupervisor
import io.github.mangi.eta.agent.terminal.LinuxEnvironmentPaths
import io.github.mangi.eta.agent.terminal.SharedFolderMounts
import io.github.mangi.eta.agent.terminal.terminalEnvironment
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.data.repository.LinuxEnvironmentSettingsRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * DSH 引擎的总协调器（最小版）：只负责把引擎拉起来并交回带 token 的访问地址。
 *
 * 不解析会话协议、不做流式翻译——网页由 [io.github.mangi.eta.ui.screens.dsh.DshWebActivity]
 * 直接承载，App 只当容器。
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
    /**
     * 读取 rootfs 内 `600 root` 的凭据需要 root 通道：ETA 以普通应用 uid 运行时
     * 直读会 `Permission denied`，导致复用判定失效、新起实例撞 EADDRINUSE。
     * 该执行器与 Coordinator 同生命周期（它自己管理子进程，不需要每次重建）。
     */
    private val rootExecutor = BoundedRootCommandExecutor(AndroidAgentLogger)
    private val engine = DshEngineLauncher(context, daemonSupervisor, rootExecutor)

    /**
     * 外部浏览器中转服务；首次成功启动引擎后才创建。
     *
     * `@Volatile`：写入发生在 `launchWebUrl()` 的 IO 协程里，
     * 读取发生在 `launchDshWeb()` 的同一协程后续（也可能从主线程调用），
     * 不加会读到 null 导致外部打开退回 3080 而 401。
     */
    @Volatile
    private var externalLogin: DshExternalLoginServer? = null

    /** 中转服务当前下发的会话 cookie（每次引擎就绪时刷新）。 */
    @Volatile
    private var latestSessionCookie: String? = null
    private val launchMutex = Mutex()

    /** 启动（或复用）引擎，返回可直接交给 WebView 的带 token 地址；失败返回 null。 */
    suspend fun launchWebUrl(): String? = launchMutex.withLock {
        when (val launched = engine.launch(currentEnvironment())) {
            is DshEngineResult.Ready -> {
                // 中转是增强项：任何失败都不能影响内置打开。
                runCatching { prepareExternalLogin() }
                    .onFailure { AndroidAgentLogger.warn("dsh_external_login_failed ${it.javaClass.simpleName}") }
                launched.url
            }
            is DshEngineResult.Failed -> {
                AndroidAgentLogger.warn("dsh_launch_failed code=${launched.code}")
                null
            }
        }
    }

    /**
     * 外部浏览器可用的中转地址；引擎未就绪或会话不可用时为 null。
     *
     * 走中转而不是把 `?token=` 直接交给外部浏览器：DSH 的会话 cookie 硬编码
     * `SameSite=Strict`，外部应用跳转过来时浏览器不会带上它，最终停在 401。
     * 详见 [DshExternalLoginServer] 的类注释。
     */
    fun externalLoginUrl(): String? {
        val server = externalLogin
        if (server == null) {
            AndroidAgentLogger.warn("dsh_external_login_url_null reason=no_server")
            return null
        }
        if (!server.isStarted) {
            AndroidAgentLogger.warn("dsh_external_login_url_null reason=not_started")
            return null
        }
        return server.loginUrl
    }

    /**
     * 起中转服务并刷新它要下发的 cookie。
     *
     * 幂等：重复调用只更新 cookie，不重复绑定端口。绑定失败只记日志
     * —— 外部打开是增强项，不能反过来拖垮内置打开。
     */
    private suspend fun prepareExternalLogin() {
        val cookie = engine.currentSessionCookie() ?: run {
            AndroidAgentLogger.warn("dsh_external_login_no_cookie")
            return
        }
        AndroidAgentLogger.info("dsh_external_login_cookie_ready len=${cookie.length}")
        val server = externalLogin ?: DshExternalLoginServer(
            targetUrl = DshWebAuth.cleanUrl(DshEngineLauncher.HOST, DshEngineLauncher.PORT),
            cookieProvider = { latestSessionCookie },
        ).also {
            it.start()
            externalLogin = it
        }
        latestSessionCookie = cookie
        server.start()
        AndroidAgentLogger.info("dsh_external_login_ready started=${server.isStarted} url=${server.loginUrl}")
    }

    /** 停止引擎（用户在环境页手动停止时调用）。 */
    suspend fun stop(): Boolean = engine.stop(currentEnvironment())

    private fun currentEnvironment() =
        LinuxEnvironmentSettingsRepository.current(context).terminalEnvironment
}
