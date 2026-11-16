package io.github.mangi.eta.agent.terminal

import io.github.mangi.eta.core.AgentLogger
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import org.json.JSONArray
import org.json.JSONObject

/** App 持有后台命令；Agent run 只提交和查询，结束回复不会释放任务。 */
internal class BackgroundTerminalJobs(
    private val supervisor: DetachedTaskSupervisor,
    private val logger: AgentLogger,
    private val acquireLease: (() -> Unit) -> Boolean = { true },
    private val releaseLease: () -> Unit = {},
    private val onCompleted: (DetachedTask) -> Unit = {},
    /**
     * 任务刚结束时的自动唤醒钩子（见 [BackgroundJobWakeup]）。
     *
     * 与 [onCompleted] 分开：后者只负责"发通知"，是既有行为；唤醒是新增通路，
     * 任何一侧失败都不得影响另一侧，也不得把 [completionNotified] 卡住。
     */
    private val onCompletedWakeup: (DetachedTask) -> Unit = {},
    private val completionNotificationsEnabled: () -> Boolean = { true },
    /**
     * 关闭唤醒宿主（[BackgroundJobWakeup.close]）。
     *
     * 由 [TerminalRuntime] 装配传入：唤醒线程与租约跟着任务宿主一起收，
     * 否则 App 关闭后台命令后仍可能起一条无人值守 run（m1/m2）。
     */
    private val closeWakeup: () -> Unit = {},
) : AutoCloseable {
    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "eta-background-commands").apply { isDaemon = true }
    }
    private val waitLock = ReentrantLock()
    private val changed = waitLock.newCondition()
    private var monitor: ScheduledFuture<*>? = null
    private var leased = false
    private var closed = false
    /** 正在回调中（已完成但尚未 markCompletionNotified）的任务：回调在锁外跑，靠它防重复派发。 */
    private val dispatching = mutableSetOf<String>()

    @Synchronized
    fun start(
        launcher: DetachedTaskSupervisor,
        command: String,
        cwd: String,
        identity: String,
        environment: TerminalEnvironment,
        description: String,
        timeoutMs: Long,
        watch: Boolean = false,
        wakeupOrigin: Boolean = false,
    ): String {
        if (closed) return failure("BACKGROUND_CLOSED", "后台命令宿主已关闭")
        if (!lease()) return failure("BACKGROUND_START_NOT_ALLOWED", "系统未允许启动后台执行，请返回 Eta 后重试")
        return when (val result = launcher.start(command, cwd, identity, environment,
            commandTask = true, description = description, timeoutMs = timeoutMs.coerceAtLeast(0),
            watch = watch, wakeupOrigin = wakeupOrigin)) {
            is DaemonStartResult.Failed -> {
                if (monitor == null) release()
                failure(result.code, result.message)
            }
            is DaemonStartResult.Started -> {
                monitor()
                taskJson(result.task).put("action", "start").toString()
            }
        }
    }

    fun list(activeOnly: Boolean): String {
        refresh()
        val tasks = supervisor.list().filter { it.task.commandTask && (!activeOnly || it.task.completedAt == null) }
        return base("list").put("tasks", JSONArray(tasks.map { taskJson(it.task) })).toString()
    }

    fun read(id: String, maxBytes: Int = 16_000): String {
        refresh()
        val task = supervisor.findTask(id)?.takeIf { it.commandTask }
            ?: return failure("JOB_NOT_FOUND", "未找到后台命令，请用 terminal_job 的 list 查看任务")
        val logs = supervisor.readLogs(id, maxBytes)
        val result = taskJson(task).put("action", "read")
        if (!logs.ok) return result.put("ok", false).put("code", logs.code).put("message", logs.message).toString()
        return result.put("output", logs.text).put("output_truncated", logs.truncated).toString()
    }

    fun stop(id: String): String {
        val task = supervisor.findTask(id)?.takeIf { it.commandTask }
            ?: return failure("JOB_NOT_FOUND", "未找到后台命令")
        if (!supervisor.stopCommand(task.id)) return failure("JOB_STOP_FAILED", "无法停止后台命令，请检查当前身份授权")
        refresh()
        return taskJson(supervisor.findTask(id) ?: task).put("action", "stop").toString()
    }

    fun waitFor(id: String, timeoutMs: Long, isCancelled: () -> Boolean): String {
        resume()
        if (supervisor.findTask(id)?.commandTask != true) return failure("JOB_NOT_FOUND", "未找到后台命令")
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs.coerceIn(0, 60_000))
        waitLock.withLock {
            while (supervisor.findTask(id)?.completedAt == null) {
                if (isCancelled()) return failure("CANCELLED", "已取消等待；后台命令仍保留，可单独停止")
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) break
                changed.awaitNanos(minOf(remaining, TimeUnit.MILLISECONDS.toNanos(200)))
            }
        }
        val task = supervisor.findTask(id) ?: return failure("JOB_NOT_FOUND", "后台命令已清理")
        return taskJson(task).put("action", "wait").put("wait_timed_out", task.completedAt == null).toString()
    }

    /** 重新打开 App 后从同一份任务记录恢复观察，不重新执行命令。 */
    fun resume() {
        if (isClosed()) return
        refresh()
        val active = synchronized(this) {
            supervisor.list().any { it.task.commandTask && it.task.completedAt == null } && lease()
        }
        if (active) synchronized(this) { monitor() }
    }

    /**
     * 每秒一次的任务巡检。
     *
     * 只在这一处收口"任务结束"的两个回调，且**回调一律在同步块之外**：
     * [onCompleted] 会发通知、[onCompletedWakeup] 会起一轮无人值守 run，
     * 持锁做这些会把同一时刻的 start/stop/read 全部堵在后面（m8）。
     * 重复派发由 [dispatching] 兜住，通知成功后才 [DetachedTaskSupervisor.markCompletionNotified]。
     */
    private fun refresh() {
        if (isClosed()) return
        val finished = collectFinished()
        finished.forEach { status ->
            try {
                // 两个回调各管一段、各自收口异常：通知失败不影响唤醒，唤醒失败也不影响通知。
                val notified = runCatching { onCompleted(status.task) }
                    .onFailure { failure ->
                        logger.warn("Background command notification failed: type=${failure.javaClass.simpleName}")
                    }
                    .isSuccess
                // 唤醒失败不参与 completionNotified：唤醒有自己的记账与冷却，重放会被冷却挡掉，
                // 不能因为"通知没发出去"把这一条记录永久留在待处理集合里反复重跑。
                runCatching { onCompletedWakeup(status.task) }
                    .onFailure { failure ->
                        logger.warn("Background command wakeup failed: type=${failure.javaClass.simpleName}")
                    }
                // 标记在回调之后：通知真的发出去了才归档这一条记录。
                if (notified) supervisor.markCompletionNotified(status.task.id)
            } finally {
                endDispatch(status.task.id)
            }
        }
        settleIdle()
        waitLock.withLock { changed.signalAll() }
    }

    /** 收敛超时任务并取出"已结束但还没通知"的任务；只做状态判断，不调用任何回调。 */
    @Synchronized
    private fun collectFinished(): List<DetachedTaskStatus> {
        if (closed) return emptyList()
        return try {
            var tasks = supervisor.list().filter { it.task.commandTask }
            val now = System.currentTimeMillis()
            val expired = tasks.filter { it.running && it.task.timeoutMs > 0 && now - it.task.startedAt >= it.task.timeoutMs }
            expired.forEach { supervisor.stopCommand(it.task.id, "timed_out") }
            if (expired.isNotEmpty()) tasks = supervisor.list().filter { it.task.commandTask }
            val pending = tasks.filter {
                it.task.completedAt != null && !it.task.completionNotified && it.task.id !in dispatching
            }
            pending.forEach { dispatching += it.task.id }
            pending
        } catch (failure: Exception) {
            logger.warn("Background command refresh failed: type=${failure.javaClass.simpleName}")
            emptyList()
        }
    }

    @Synchronized
    private fun endDispatch(id: String) {
        dispatching -= id
    }

    /** 没有运行中的任务就撤掉定时器并归还执行租约（原逻辑，放在回调之后执行）。 */
    @Synchronized
    private fun settleIdle() {
        if (closed) return
        val running = try {
            supervisor.list().any { it.task.commandTask && it.running }
        } catch (failure: Exception) {
            logger.warn("Background command refresh failed: type=${failure.javaClass.simpleName}")
            // 状态不可读时保持租约，避免同一轮巡检里反复 acquire/release。
            true
        }
        if (running) return
        monitor?.cancel(false)
        monitor = null
        release()
    }

    @Synchronized
    private fun isClosed(): Boolean = closed

    private fun monitor() {
        if (monitor == null) monitor = executor.scheduleWithFixedDelay(::refresh, 0, 1, TimeUnit.SECONDS)
    }

    private fun lease(): Boolean {
        if (!leased) leased = acquireLease { stopAll() }
        return leased
    }

    private fun release() {
        if (leased) {
            leased = false
            releaseLease()
        }
    }

    /** 用户/系统要求停掉全部后台命令；不持锁调用 [refresh]，否则回调又会被锁住。 */
    fun stopAll() {
        val running = synchronized(this) {
            supervisor.list().filter { it.task.commandTask && it.running }
        }
        running.forEach { supervisor.stopCommand(it.task.id) }
        refresh()
    }

    /**
     * 关闭宿主：停掉全部后台命令、撤定时器、还租约，并连带关闭唤醒宿主。
     *
     * 只对状态位加锁、不整体持有监视器：停止任务会触发一次巡检，回调不该在锁里跑。
     */
    override fun close() {
        stopAll()
        synchronized(this) {
            closed = true
            monitor?.cancel(false)
            monitor = null
            executor.shutdownNow()
            release()
            waitLock.withLock { changed.signalAll() }
        }
        // 唤醒宿主跟着任务宿主一起关闭：不再受理新的唤醒，进行中的那一轮自行收尾。
        runCatching { closeWakeup() }
            .onFailure { failure ->
                logger.warn("Background command wakeup close failed: type=${failure.javaClass.simpleName}")
            }
    }

    private fun taskJson(task: DetachedTask): JSONObject = base("status")
        .put("job_id", task.id).put("description", task.description)
        .put("environment", task.environment.wireName).put("identity", task.identity).put("cwd", task.cwd)
        .put("status", task.commandStatus).put("running", task.completedAt == null)
        .put("started_at", task.startedAt).put("completed_at", task.completedAt ?: JSONObject.NULL)
        .put("exit_code", task.exitCode ?: JSONObject.NULL).put("end_reason", task.endReason ?: JSONObject.NULL)
        .put("output_path", supervisor.hostDaemonPath(task, task.logPath)).put("output_environment", "android")
        .put("timeout_ms", task.timeoutMs)
        .put("watch", task.watch)
        .put("completion_notifications_enabled", completionNotificationsEnabled())

    private fun base(action: String) = JSONObject().put("ok", true).put("tool", "terminal_job").put("action", action)
    private fun failure(code: String, message: String) = JSONObject().put("ok", false).put("tool", "terminal_job")
        .put("code", code).put("message", message).toString()
}
