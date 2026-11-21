package io.github.mangi.eta.agent.dsh

import android.content.Context
import io.github.mangi.eta.agent.device.BoundedRootCommandExecutor
import io.github.mangi.eta.agent.device.RootAccess
import io.github.mangi.eta.agent.runtime.AgentExecutionService
import io.github.mangi.eta.agent.terminal.DaemonStartResult
import io.github.mangi.eta.agent.terminal.DetachedTaskSupervisor
import io.github.mangi.eta.agent.terminal.LinuxEnvironmentPaths
import io.github.mangi.eta.agent.terminal.TerminalEnvironment
import io.github.mangi.eta.agent.terminal.TerminalRuntime
import io.github.mangi.eta.agent.terminal.shellQuote
import io.github.mangi.eta.core.AndroidAgentLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

internal sealed interface DshEngineResult {
    data class Ready(val url: String) : DshEngineResult
    data class Failed(val code: String) : DshEngineResult
}

/**
 * DSH 引擎的进程管理：以 terminal_daemon 方式常驻 `dsh --profile web --no-open`，
 * 并从 daemon 日志里抓取启动行中的访问 URL。
 *
 * 最小实现：只负责「进程在跑」与「拿到带 token 的 URL」，不解析会话协议。
 */
internal class DshEngineLauncher(
    private val context: Context,
    private val daemonSupervisor: DetachedTaskSupervisor,
    private val rootExecutor: BoundedRootCommandExecutor,
    private val waitAttempts: Int = 90,
    private val waitIntervalMs: Long = 1_000,
) {
    private val launchMutex = Mutex()

    suspend fun launch(environment: TerminalEnvironment): DshEngineResult = launchMutex.withLock {
        withContext(Dispatchers.IO) {
            val distribution = environment.linuxDistribution
                ?: return@withContext DshEngineResult.Failed("INVALID_ENVIRONMENT")
            val rootfs = LinuxEnvironmentPaths.rootfsDir(context, distribution)
            if (!LinuxEnvironmentPaths.rootfsReady(rootfs.absolutePath)) {
                return@withContext DshEngineResult.Failed("LINUX_ENVIRONMENT_NOT_READY")
            }
            val identity = TerminalRuntime.defaultIdentity(environment, rootfs.absolutePath)
            val job = currentCoroutineContext().job
            // 后台常驻进程需要前台服务租约；免 Root Linux 用普通应用身份。
            if (identity == "user" && !AgentExecutionService.acquire(context, LAUNCH_ID) { job.cancel() }) {
                return@withContext DshEngineResult.Failed("BACKGROUND_START_NOT_ALLOWED")
            }
            try {
                currentCoroutineContext().ensureActive()
                ensureRunning(environment, identity, LinuxEnvironmentPaths.backendOf(rootfs.absolutePath))
            } finally {
                if (identity == "user") AgentExecutionService.release(LAUNCH_ID)
            }
        }
    }

    private suspend fun ensureRunning(
        environment: TerminalEnvironment,
        identity: String,
        backend: io.github.mangi.eta.agent.terminal.LinuxExecutionBackend,
    ): DshEngineResult {
        val distribution = environment.linuxDistribution
            ?: return DshEngineResult.Failed("INVALID_ENVIRONMENT")
        val rootfs = LinuxEnvironmentPaths.rootfsDir(context, distribution)

        // [eta-fix] 先尝试复用已在监听的引擎。插件自愈重启（dsh-purge 的 restart-web）会拉起
        // 一个不在 supervisor 记录里的新进程；若此时再起一个实例，必然撞上 EADDRINUSE，
        // 整片依赖 webServer 的插件全部挂起，日志里再也不会出现可用的启动行，
        // 最终表现就是「dsh 组件不可用」。复用既有实例可彻底绕开这条失败路径。
        //
        // 复用失败（Occupied）时**不直接判死**，继续走下面的原有流程：
        // 引擎可能正在启动、凭据尚未落盘，原有路径仍有机会通过日志行拿到地址。
        // 只有在原有流程也失败时，才在末尾把 Occupied 作为更准确的失败码报出去。
        val reuseOutcome = reuseListeningEngine(rootfs)
        if (reuseOutcome is ReuseOutcome.Ready) return reuseOutcome.result

        val existing = daemonSupervisor.list().firstOrNull {
            it.running && it.task.environment == environment && it.task.identity == identity &&
                it.task.backend == backend && it.task.command.trim() == COMMAND
        }
        val taskId = existing?.task?.id ?: when (val started = daemonSupervisor.start(
            command = COMMAND, cwd = "/workspace", identity = identity, environment = environment,
        )) {
            is DaemonStartResult.Started -> started.task.id
            is DaemonStartResult.Failed -> return DshEngineResult.Failed(started.code)
        }
        repeat(waitAttempts) {
            val status = daemonSupervisor.list().firstOrNull { it.task.id == taskId }
            if (status == null || !status.running) return DshEngineResult.Failed("DSH_EXITED")
            val logs = daemonSupervisor.readLogs(taskId)
            if (!logs.ok) return DshEngineResult.Failed(logs.code.ifBlank { "LOGS_UNAVAILABLE" })

            // [eta-fix] 就绪判定不再只依赖启动行：先用派生 token 探测（与 Harness 侧
            // 修复后的稳定 token 对齐），失败再退到持久化密钥签发的 cookie。两条路径
            // 都跨重启有效，比等日志行可靠，也不受日志轮转或截断影响。
            readyFromCredentials(rootfs)?.let { return it }

            launchUrlFromLogs(logs.text)?.let { return DshEngineResult.Ready(it) }
            delay(waitIntervalMs)
        }
        // 原有流程也失败了。若端口从一开始就被非本引擎的进程占着，
        // 报 PORT_OCCUPIED 比 URL_TIMEOUT 更能指向真正的原因。
        return if (reuseOutcome is ReuseOutcome.Occupied) {
            DshEngineResult.Failed("PORT_OCCUPIED")
        } else {
            DshEngineResult.Failed("URL_TIMEOUT")
        }
    }

    /** 端口复用的三种结论，让调用方能区分「可复用」「被别人占着」「端口空着」。 */
    private sealed interface ReuseOutcome {
        data class Ready(val result: DshEngineResult) : ReuseOutcome
        data object Occupied : ReuseOutcome
        data object Free : ReuseOutcome
    }

    /**
     * 探测 [PORT] 上是否已有可复用的引擎：
     * - 端口空着 → [ReuseOutcome.Free]，由调用方正常启动；
     * - 端口在监听且凭据能认证 → [ReuseOutcome.Ready]，直接复用；
     * - 端口在监听、凭据也在、但认证不通过 → [ReuseOutcome.Occupied]（确实不是我们的 dsh）。
     *
     * **凭据不存在时必须返回 [ReuseOutcome.Free]**：首次运行或凭据被清掉时，
     * 端口可能刚好被上一次残留的引擎听着，此时没有凭据就无法判定归属，
     * 若误报 Occupied 会让全新安装直接失败。交给正常启动流程处理更安全
     * ——真正冲突时 supervisor 会给出 EADDRINUSE，不会静默出错。
     */
    private suspend fun reuseListeningEngine(rootfs: File): ReuseOutcome =
        withContext(Dispatchers.IO) {
            if (!DshWebAuth.isPortListening(HOST, PORT)) return@withContext ReuseOutcome.Free
            val secret = signingSecret(rootfs) ?: return@withContext ReuseOutcome.Free
            val ready = readyWithSecret(secret)
                ?: return@withContext ReuseOutcome.Occupied
            ReuseOutcome.Ready(ready)
        }

    /**
     * 取签名密钥。直读失败时退回 root 通道。
     *
     * `dsh-home` 是 `700 root`、凭据是 `600 root`，ETA 以普通应用 uid 运行时直读必然
     * `Permission denied`（实测）。若此时当作「没有凭据」，复用判定会返回 Free，
     * 接着新起实例撞 `EADDRINUSE` —— 就是「dsh 组件不可用」的老毛病。
     */
    private suspend fun signingSecret(rootfs: File): ByteArray? {
        DshWebAuth.readSigningSecret(rootfs)?.let { return it }
        if (!RootAccess.isGranted) return null
        return DshWebAuth.readSigningSecretViaRoot(rootfs) { path ->
            runCatching {
                rootExecutor.execute("cat ${shellQuote(path)}")
                    .takeIf { it.ok }?.stdout
            }.getOrNull()
        }
    }

    private suspend fun readyFromCredentials(rootfs: File): DshEngineResult? =
        withContext(Dispatchers.IO) {
            val secret = signingSecret(rootfs) ?: return@withContext null
            readyWithSecret(secret)
        }

    private fun readyWithSecret(secret: ByteArray): DshEngineResult? {
        val tokenUrl = DshWebAuth.authenticatedUrl(HOST, PORT, DshWebAuth.launchToken(secret))
        if (DshWebAuth.probeUsable(tokenUrl)) return DshEngineResult.Ready(tokenUrl)

        val clean = DshWebAuth.cleanUrl(HOST, PORT)
        val cookie = DshWebAuth.mintCookie(secret, HOST, PORT)
        if (DshWebAuth.probeWithCookie(clean, cookie)) {
            DshWebAuth.installCookie(clean, cookie)
            return DshEngineResult.Ready(clean)
        }
        return null
    }

    /** 找到当前运行中引擎的任务 id（停止用）；无运行实例返回 null。 */
    private suspend fun runningTaskId(environment: TerminalEnvironment): String? = withContext(Dispatchers.IO) {
        val distribution = environment.linuxDistribution ?: return@withContext null
        val rootfs = LinuxEnvironmentPaths.rootfsDir(context, distribution)
        if (!LinuxEnvironmentPaths.rootfsReady(rootfs.path)) return@withContext null
        val identity = TerminalRuntime.defaultIdentity(environment, rootfs.path)
        val backend = LinuxEnvironmentPaths.backendOf(rootfs.path)
        val matches = daemonSupervisor.list().filter {
            it.task.environment == environment && it.task.identity == identity &&
                it.task.backend == backend && it.task.command.trim() == COMMAND
        }
        (matches.lastOrNull { it.running } ?: matches.lastOrNull())?.task?.id
    }

    suspend fun stop(environment: TerminalEnvironment): Boolean = launchMutex.withLock {
        withContext(Dispatchers.IO) {
            runningTaskId(environment)?.let(daemonSupervisor::stop) ?: false
        }
    }

    companion object {
        const val HOST = "127.0.0.1"
        const val PORT = 3080

        /** daemon 命令不带 &：supervisor 自行后台化并接管日志与所有权。 */
        const val COMMAND =
            "export DSH_HOME=/opt/eta/dsh-home && dsh --profile web --no-open --host $HOST --port $PORT"
        const val LAUNCH_ID = "dsh-launch"

        /** 启动行 `dsh web: http://127.0.0.1:3080/?token=<43 位 base64url>`。
         *  捕获组只取 URL 本体，token 收口在 43 字符，行尾 CR 不会进入捕获组。 */
        private val LAUNCH_URL_REGEX = Regex(
            "dsh web: (http://127[.]0[.]0[.]1:[0-9]+/[?]token=[A-Za-z0-9_-]{43})",
        )

        fun launchUrlFromLogs(text: String): String? =
            LAUNCH_URL_REGEX.findAll(text).lastOrNull()?.groupValues?.get(1)?.trim()
    }
}
