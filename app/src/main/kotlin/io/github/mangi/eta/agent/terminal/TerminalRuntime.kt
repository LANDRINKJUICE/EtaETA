package io.github.mangi.eta.agent.terminal

import android.content.Context
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.agent.device.RootAccess
import io.github.mangi.eta.agent.runtime.AgentExecutionService
import java.io.File

internal enum class LinuxExecutionBackend(val wireName: String) {
    CHROOT("chroot"),
    PROOT("proot"),
}

/** App 进程的终端运行条件；读取能力不会触发 su 授权。 */
internal object TerminalRuntime {
    const val ROOT_WORKSPACE_PATH = "/data/local/tmp/eta"
    const val LINUX_WORKSPACE_PATH = "/workspace"

    @Volatile private var appContext: Context? = null
    private var backgroundJobs: BackgroundTerminalJobs? = null

    /** 任务完成自动唤醒的记录文件：与任务记录同目录，重启后仍能约束冷却与链条计数。 */
    fun wakeupLedgerFile(context: Context): File = File(context.applicationContext.filesDir, "terminal-job-wakeup.json")

    @Synchronized
    fun backgroundJobs(context: Context): BackgroundTerminalJobs {
        val app = context.applicationContext
        return backgroundJobs ?: run {
            val supervisor = DetachedTaskSupervisor(AndroidAgentLogger, DetachedTaskSupervisor.defaultRecordsFile(app))
            val wakeup = BackgroundJobWakeup(
                context = app,
                logger = AndroidAgentLogger,
                ledgerFile = wakeupLedgerFile(app),
                enabled = { io.github.mangi.eta.config.Prefs.isJobWakeupEnabled() },
                acquireLease = { id, onStop -> AgentExecutionService.acquire(app, id, onStop = onStop) },
                releaseLease = { id -> AgentExecutionService.release(id) },
                notifyRunning = { task, watched -> JobWakeupNotifications.showRunning(app, task, watched) },
                notifyFinished = { task, finish -> JobWakeupNotifications.showFinished(app, task, finish) },
                notifyChainLimit = { task, globalLimit -> JobWakeupNotifications.showChainLimit(app, task, globalLimit) },
                notifyBusy = { task -> JobWakeupNotifications.showBusy(app, task) },
                notifyStage = { task, stage, watched ->
                    JobWakeupNotifications.updateRunning(app, task, stage, watched)
                },
                readOutputTail = { task, maxBytes ->
                    // 读出的原始文本由 JobWakeupPolicy 统一剥控制序列并按字符裁剪。
                    BackgroundCommandNotification.logTail(task, maxBytes, JobWakeupPolicy.OUTPUT_TAIL_CHARS)
                },
                // 把 Runtime 的真实结果透出来：通知终态要区分"处理完"与"跑挂了"。
                // 第二个参数是 onEvent：唤醒 run 的实时事件不再丢弃，由 BackgroundJobWakeup
                // 映射成阶段文案、经 JobWakeupNotifications 更新到运行中的那条通知上。
                runWakeup = { request, onEvent ->
                    io.github.mangi.eta.agent.runtime.AgentRuntimeClient(app, AndroidAgentLogger)
                        .run(request, onEvent)
                },
            )
            BackgroundTerminalJobs(
                supervisor = supervisor,
                logger = AndroidAgentLogger,
                acquireLease = { onStop -> AgentExecutionService.acquire(app, "terminal-background-commands", onStop = onStop) },
                releaseLease = { AgentExecutionService.release("terminal-background-commands") },
                onCompleted = { BackgroundCommandNotification.show(app, it) },
                // 任务结束的自动唤醒：与"发通知"各走一条通路，互不影响。
                onCompletedWakeup = wakeup::onJobFinished,
                completionNotificationsEnabled = { app.getSystemService(android.app.NotificationManager::class.java).areNotificationsEnabled() },
                // 任务宿主关闭时连带关掉唤醒宿主：不留一个还能起无人值守 run 的孤儿线程。
                closeWakeup = wakeup::close,
            ).also { jobs -> backgroundJobs = jobs }
        }
    }

    fun acquireUserTask(id: String, onStop: () -> Unit): Boolean =
        appContext?.let { AgentExecutionService.acquire(it, id, onStop = onStop) } ?: true

    fun releaseUserTask(id: String) {
        if (appContext != null) AgentExecutionService.release(id)
    }

    fun initialize(context: Context) {
        appContext = context.applicationContext
        File(userWorkspacePath).mkdirs()
        // 进程上次被回收时，运行中的唤醒通知是 ongoing、不会被自动清掉：启动时扫一次号段。
        JobWakeupNotifications.cancelStale(context.applicationContext)
    }

    val rootAvailable: Boolean get() = RootAccess.isGranted
    val publicStorageGranted: Boolean get() = appContext != null && android.os.Environment.isExternalStorageManager()
    val nativeLibraryDir: File? get() = appContext?.applicationInfo?.nativeLibraryDir?.let(::File)
    val userWorkspacePath: String get() = appContext?.let {
        TerminalPrivateStorage.workspace(it.filesDir).absolutePath
    } ?: File(System.getProperty("java.io.tmpdir"), "eta-terminal-workspace").absolutePath
    val temporaryDirectory: File get() = appContext?.let { File(it.cacheDir, "terminal/proot") }
        ?: File(System.getProperty("java.io.tmpdir"), "eta-proot")

    fun defaultIdentity(environment: TerminalEnvironment, rootfsPath: String? = null): String = when {
        environment.isLinux -> if (LinuxEnvironmentPaths.backendOf(rootfsPath) == LinuxExecutionBackend.PROOT) "user" else "root"
        rootAvailable -> "root"
        else -> "user"
    }

    fun workspace(identity: String): String =
        if (identity == "root") ROOT_WORKSPACE_PATH else userWorkspacePath

    fun nativeExecutable(name: String): File? = nativeLibraryDir?.let { File(it, name) }
        ?.takeIf { it.isFile && it.canExecute() }
}
