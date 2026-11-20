package io.github.mangi.eta.agent.dsh

import android.content.Context
import io.github.mangi.eta.agent.runtime.AgentExecutionService
import io.github.mangi.eta.agent.terminal.DaemonStartResult
import io.github.mangi.eta.agent.terminal.DetachedTaskSupervisor
import io.github.mangi.eta.agent.terminal.LinuxEnvironmentPaths
import io.github.mangi.eta.agent.terminal.TerminalEnvironment
import io.github.mangi.eta.agent.terminal.TerminalRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal sealed interface DshEngineResult {
    data class Ready(val url: String) : DshEngineResult
    data class Failed(val code: String) : DshEngineResult
}

/**
 * DSH Web 引擎的进程管理：terminal_daemon 常驻 `dsh --profile web --no-open`，
 * 从 daemon 日志按启动行抓 launch token。模式与上游已删除的 Kimi Web 集成一致，
 * 复用 DetachedTaskSupervisor 的 setsid 常驻、pid+token 认领与日志读取。
 */
internal class DshEngineLauncher(
    private val context: Context,
    private val daemonSupervisor: DetachedTaskSupervisor,
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
            launchUrlFromLogs(logs.text)?.let { return DshEngineResult.Ready(it) }
            delay(waitIntervalMs)
        }
        return DshEngineResult.Failed("URL_TIMEOUT")
    }

    /** 找到当前运行中引擎的任务 id（stop 用）；无运行实例返回 null。 */
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
        /** daemon 命令不带 &：supervisor 自行后台化并接管日志与所有权。 */
        const val COMMAND =
            "export DSH_HOME=/opt/eta/dsh-home && dsh --profile web --no-open --host 127.0.0.1 --port 3080"
        const val LAUNCH_ID = "dsh-launch"
        const val DEFAULT_PORT = 3080

        fun launchUrlFromLogs(text: String): String? =
            LAUNCH_URL_REGEX.findAll(text).lastOrNull()?.groupValues?.get(1)?.trim()

        /** 启动行 `dsh web: http://127.0.0.1:3080/?token=<43 位 base64url>`。
         *  捕获组只取 URL 本体（不带 "dsh web: " 前缀）；token 收口在 43 字符，
         *  行尾 CR（日志为 CRLF）不会进入捕获组；再叠 trim() 双保险。 */
        private val LAUNCH_URL_REGEX = Regex(
            "dsh web: (http://127[.]0[.]0[.]1:[0-9]+/[?]token=[A-Za-z0-9_-]{43})",
        )
    }
}
