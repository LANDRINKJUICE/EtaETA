package io.github.mangi.eta.agent.terminal

import org.json.JSONObject

/** 一条守护任务记录。pid + token 用于跨 App 重启后的认领，以及停止前防止 PID 复用误杀。 */
internal data class DetachedTask(
    val id: String,
    val pid: Long,
    val token: String,
    val command: String,
    val cwd: String,
    val identity: String,
    val environment: TerminalEnvironment,
    val logPath: String,
    val startedAt: Long,
    val backend: LinuxExecutionBackend = LinuxExecutionBackend.CHROOT,
    val hostWorkspace: String? = null,
    val commandTask: Boolean = false,
    val description: String = "",
    val timeoutMs: Long = 0,
    /** 盯梢任务：成功结束时也允许唤醒 Agent（见 [JobWakeupPolicy]）。 */
    val watch: Boolean = false,
    /** 由自动唤醒产生的 run 启动：其完成永不触发新的唤醒（防自增殖）。 */
    val wakeupOrigin: Boolean = false,
    val completedAt: Long? = null,
    val exitCode: Int? = null,
    val endReason: String? = null,
    val completionNotified: Boolean = false,
    val isolatedGroup: Boolean? = null,
)

internal data class DetachedTaskStatus(
    val task: DetachedTask,
    val running: Boolean,
)

internal sealed interface DaemonStartResult {
    data class Started(val task: DetachedTask) : DaemonStartResult
    data class Failed(val code: String, val message: String) : DaemonStartResult
}

internal data class DaemonLogsResult(
    val ok: Boolean,
    val text: String = "",
    val truncated: Boolean = false,
    val code: String = "",
    val message: String = "",
)

internal fun DetachedTask.toRecordJson(): JSONObject = JSONObject()
    .put("id", id)
    .put("pid", pid)
    .put("token", token)
    .put("command", command)
    .put("cwd", cwd)
    .put("identity", identity)
    .put("environment", environment.wireName)
    .put("log_path", logPath)
    .put("started_at", startedAt)
    .put("backend", backend.wireName)
    .put("host_workspace", hostWorkspace ?: JSONObject.NULL)
    .put("command_task", commandTask)
    .put("description", description)
    .put("timeout_ms", timeoutMs)
    .put("watch", watch)
    .put("wakeup_origin", wakeupOrigin)
    .put("completed_at", completedAt ?: JSONObject.NULL)
    .put("exit_code", exitCode ?: JSONObject.NULL)
    .put("end_reason", endReason ?: JSONObject.NULL)
    .put("completion_notified", completionNotified)
    .put("isolated_group", isolatedGroup ?: JSONObject.NULL)

internal fun JSONObject.toDetachedTask(): DetachedTask = DetachedTask(
    id = getString("id"),
    pid = getLong("pid"),
    token = getString("token"),
    command = getString("command"),
    cwd = getString("cwd"),
    identity = getString("identity"),
    environment = when (optString("environment")) {
        TerminalEnvironment.ALPINE.wireName -> TerminalEnvironment.ALPINE
        TerminalEnvironment.DEBIAN.wireName -> TerminalEnvironment.DEBIAN
        else -> TerminalEnvironment.ANDROID
    },
    logPath = getString("log_path"),
    startedAt = getLong("started_at"),
    backend = LinuxExecutionBackend.entries.firstOrNull { it.wireName == optString("backend") } ?: LinuxExecutionBackend.CHROOT,
    hostWorkspace = optString("host_workspace").takeIf { it.isNotBlank() && it != "null" },
    commandTask = optBoolean("command_task", false),
    description = optString("description"),
    timeoutMs = optLong("timeout_ms", 0),
    watch = optBoolean("watch", false),
    wakeupOrigin = optBoolean("wakeup_origin", false),
    completedAt = if (isNull("completed_at")) null else getLong("completed_at"),
    exitCode = if (isNull("exit_code")) null else getInt("exit_code"),
    endReason = if (isNull("end_reason")) null else getString("end_reason"),
    completionNotified = optBoolean("completion_notified", false),
    isolatedGroup = if (isNull("isolated_group")) null else getBoolean("isolated_group"),
)

internal val DetachedTask.commandStatus: String get() = when {
    completedAt == null -> "running"
    endReason != null -> endReason
    exitCode == 0 -> "completed"
    else -> "failed"
}

/**
 * 转成 [JobWakeupPolicy] 的判定输入。
 *
 * 放在这里而不是判定侧：任务记录是唯一事实来源，字段名变化时编译器会在这里报错，
 * 不会让某条判定悄悄退化成"永远不唤醒"。
 */
internal fun DetachedTask.toWakeupSignal(): JobWakeupPolicy.Signal = JobWakeupPolicy.Signal(
    jobId = id,
    description = description,
    command = command,
    cwd = cwd,
    environment = environment.wireName,
    exitCode = exitCode,
    endReason = endReason,
    watch = watch,
    wakeupOrigin = wakeupOrigin,
)
