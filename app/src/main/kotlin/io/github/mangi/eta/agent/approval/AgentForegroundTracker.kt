package io.github.mangi.eta.agent.approval

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 「Eta 现在是不是在用户眼前」的进程内判定。
 *
 * 后台弹卡只想在 App 不在前台时才把卡片送到别的 App 之上；前台时聊天流里的
 * [io.github.mangi.eta.ui.components.AgentApprovalRequestCard] 才是唯一载体，不能再叠一层浮窗。
 *
 * 用 [Application.ActivityLifecycleCallbacks] 的 started 计数判定，比"顶层 Activity 类名"
 * 或 UsageStats 都轻，也不申请任何权限。
 *
 * 两点被审查后加固：
 * 1. install 幂等（双检），重复安装会让 started 计数翻倍、[isForeground] 恒 true，整条后台链静默失效；
 * 2. 「变后台」做 250ms 去抖：Activity 切换/旋屏时 onStop(old) 与 onStart(new) 不同帧会产生瞬时 0，
 *    若立即判后台会闪一层浮窗。[isForeground] 返回的是去抖后的稳定值，进前台则立即生效。
 */
internal object AgentForegroundTracker {

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var startedCount = 0

    /** 去抖后的稳定前台值；外部裁决读这个，而不是即时 startedCount。 */
    @Volatile
    private var foregroundStable = false

    @Volatile
    private var installed = false

    private val listeners = CopyOnWriteArrayList<(Boolean) -> Unit>()

    /** onStop 后排队待确认的「变后台」任务；onStart 到来时取消。 */
    private var pendingBackground: Runnable? = null

    fun install(application: Application) {
        if (installed) return
        synchronized(this) {
            if (installed) return
            installed = true
            application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                override fun onActivityStarted(activity: Activity) {
                    startedCount += 1
                    // 新 Activity 起来：取消待定的「变后台」，立即稳定为前台。
                    pendingBackground?.let { mainHandler.removeCallbacks(it) }
                    pendingBackground = null
                    publish(true)
                }

                override fun onActivityStopped(activity: Activity) {
                    startedCount = (startedCount - 1).coerceAtLeast(0)
                    if (startedCount > 0) return
                    // 延迟一帧再确认后台，跨过旋屏 / Activity 切换的瞬时 0。
                    pendingBackground?.let { mainHandler.removeCallbacks(it) }
                    val task = Runnable {
                        pendingBackground = null
                        if (startedCount == 0) publish(false)
                    }
                    pendingBackground = task
                    mainHandler.postDelayed(task, BACKGROUND_DEBOUNCE_MILLIS)
                }

                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
                override fun onActivityResumed(activity: Activity) = Unit
                override fun onActivityPaused(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
                override fun onActivityDestroyed(activity: Activity) = Unit
            })
        }
    }

    /** 回调在改动线程同步执行，宿主应自行切回主线程。返回注销函数。 */
    fun addListener(listener: (Boolean) -> Unit): () -> Unit {
        listeners += listener
        return { listeners.remove(listener) }
    }

    fun isForeground(): Boolean = foregroundStable

    private fun publish(value: Boolean) {
        if (value == foregroundStable) return
        foregroundStable = value
        listeners.forEach { listener -> runCatching { listener(value) } }
    }

    private const val BACKGROUND_DEBOUNCE_MILLIS = 250L
}
