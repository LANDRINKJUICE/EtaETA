package io.github.mangi.eta.agent.dsh

import android.content.Context
import io.github.mangi.eta.agent.runtime.AgentExecutionService
import io.github.mangi.eta.agent.terminal.DaemonStartResult
import io.github.mangi.eta.agent.terminal.DetachedTaskSupervisor
import io.github.mangi.eta.agent.terminal.LinuxEnvironmentPaths
import io.github.mangi.eta.agent.terminal.TerminalEnvironment
import io.github.mangi.eta.agent.terminal.TerminalRuntime
import io.github.mangi.eta.core.AndroidAgentLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class DshEngineStatus(
    val taskId: String? = null,
    val running: Boolean = false,
    val launchUrl: String? = null,
    val code: String? = null,
)

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

    suspend fun status(environment: TerminalEnvironment): DshEngineStatus = withContext(Dispatchers.IO) {
        val distribution = environment.linuxDistribution
            ?: return@withContext DshEngineStatus(code = "INVALID_ENVIRONMENT")
        val rootfs = LinuxEnvironmentPaths.rootfsDir(context, distribution)
        if (!LinuxEnvironmentPaths.rootfsReady(rootfs.path)) {
            return@withContext DshEngineStatus(code = "LINUX_ENVIRONMENT_NOT_READY")
        }
        val identity = TerminalRuntime.defaultIdentity(environment, rootfs.path)
        if (identity == "root" && !TerminalRuntime.rootAvailable) {
            return@withContext DshEngineStatus(code = "ROOT_REQUIRED")
        }
        val matches = daemonSupervisor.list().filter {
            it.task.environment == environment && it.task.identity == identity &&
                it.task.backend == LinuxEnvironmentPaths.backendOf(rootfs.path) &&
                it.task.command.trim() == COMMAND
        }
        val task = matches.lastOrNull { it.running } ?: matches.lastOrNull()
            ?: return@withContext DshEngineStatus()
        if (!task.running) return@withContext DshEngineStatus(task.task.id, code = "DSH_EXITED")
        val logs = daemonSupervisor.readLogs(task.task.id)
        DshEngineStatus(
            taskId = task.task.id,
            running = true,
            launchUrl = if (logs.ok) launchUrlFromLogs(logs.text) else null,
            code = if (logs.ok) null else logs.code,
        )
    }

    suspend fun stop(environment: TerminalEnvironment): Boolean = launchMutex.withLock {
        withContext(Dispatchers.IO) {
            status(environment).taskId?.let(daemonSupervisor::stop) ?: false
        }
    }

    companion object {
        /** daemon 命令不带 &：supervisor 自行后台化并接管日志与所有权。 */
        const val COMMAND =
            "export DSH_HOME=/opt/eta/dsh-home && dsh --profile web --no-open --host 127.0.0.1 --port 3080"
        const val LAUNCH_ID = "dsh-launch"
        const val DEFAULT_PORT = 3080

        fun launchUrlFromLogs(text: String): String? = LAUNCH_URL_REGEX.findAll(text).lastOrNull()?.value

        /** 启动行 `dsh web: http://127.0.0.1:3080/?token=<43 位 base64url>`；token 字符集收紧防吃进 ANSI 尾巴。 */
        private val LAUNCH_URL_REGEX = Regex(
            "dsh web: http://127\\.0\\.0\\.1:\\d+/\\?token=" + "[A-Za" + "z0-9_" + "-]{43}",
        )
    }
}
