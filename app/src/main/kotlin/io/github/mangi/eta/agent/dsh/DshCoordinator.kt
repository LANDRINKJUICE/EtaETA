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
    private val launchMutex = Mutex()

    /** 启动（或复用）引擎，返回可直接交给 WebView 的带 token 地址；失败返回 null。 */
    suspend fun launchWebUrl(): String? = launchMutex.withLock {
        when (val launched = engine.launch(currentEnvironment())) {
            is DshEngineResult.Ready -> launched.url
            is DshEngineResult.Failed -> {
                AndroidAgentLogger.warn("dsh_launch_failed code=${launched.code}")
                null
            }
        }
    }

    /** 停止引擎（用户在环境页手动停止时调用）。 */
    suspend fun stop(): Boolean = engine.stop(currentEnvironment())

    private fun currentEnvironment() =
        LinuxEnvironmentSettingsRepository.current(context).terminalEnvironment
}
