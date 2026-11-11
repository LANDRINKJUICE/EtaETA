package io.github.mangi.eta.agent.notify

import android.content.Context
import android.os.Handler
import android.os.Looper
import io.github.mangi.eta.agent.approval.AgentApprovalCoordinator

/**
 * 把"有操作在等用户确认"这件事变成一条随数量实时更新的常驻通知。
 *
 * 之前这条信息只作为执行常驻通知里的一行文案（`execution_pending_approval`），
 * 只有在执行服务前台存活时才看得到、也无法从通知直接跳到待确认区。
 * 这里在进程内挂一次 [AgentApprovalCoordinator] 的监听：数量变化就原地更新同一条通知，
 * 归零就撤掉；点击直达任务中心待确认区（见 [AgentNotifications.showApprovalPending]）。
 *
 * 只在 App 主进程安装（[io.github.mangi.eta.AppProcessPolicy] 判定），幂等。
 */
internal object ApprovalNotificationBridge {
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile
    private var installed = false

    fun install(context: Context) {
        if (installed) return
        synchronized(this) {
            if (installed) return
            installed = true
            val appContext = context.applicationContext
            AgentApprovalCoordinator.shared.addPendingListener { pending ->
                deliver(appContext, pending.size)
            }
            // 对账：上一次进程留下的"待确认"通知可能对应早已失效的请求（进程被杀时没人撤回）。
            // 启动时按当前真实数量收敛一次——0 就撤掉，>0 就刷新成正确数量。
            deliver(appContext, AgentApprovalCoordinator.shared.pending.value.size)
        }
    }

    private fun deliver(context: Context, count: Int) {
        mainHandler.post {
            if (count <= 0) {
                AgentNotifications.cancelApprovalPending(context)
            } else {
                AgentNotifications.showApprovalPending(context, count)
            }
        }
    }
}
