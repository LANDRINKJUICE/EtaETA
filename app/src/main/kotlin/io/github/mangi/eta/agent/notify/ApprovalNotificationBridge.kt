package io.github.mangi.eta.agent.notify

import android.content.Context
import android.os.Handler
import android.os.Looper
import io.github.mangi.eta.agent.approval.AgentApprovalCoordinator

/**
 * 把"有操作在等用户确认"这件事变成一条随数量实时更新的常驻通知（9400 汇总通知）。
 *
 * 之前这条信息只作为执行常驻通知里的一行文案（`execution_pending_approval`），
 * 只有在执行服务前台存活时才看得到、也无法从通知直接跳到待确认区。
 * 这里在进程内挂一次 [AgentApprovalCoordinator] 的监听：数量变化就原地更新同一条通知，
 * 归零就撤掉；点击直达任务中心待确认区（见 [AgentNotifications.showApprovalPending]）。
 *
 * 只在 App 主进程安装（[io.github.mangi.eta.AppProcessPolicy] 判定），幂等。
 *
 * 后台弹卡接入后（方案 1 起）：前台与后台都会由 Presenter 发 eta_attention HIGH 行动通知，
 * 这条 9400 汇总通知属于重复打扰，由 [io.github.mangi.eta.agent.approval.ApprovalAttentionPresenter]
 * 通过 [setSuppressed] 抑制；只有当"行动通知不可用（权限/channel 被关）"或载体收起时才解除抑制，
 * 保证 9400 始终作为最后一道可见痕迹，不会出现"三条规则都满足却什么都看不到"。
 */
internal object ApprovalNotificationBridge {
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile
    private var installed = false

    /** 是否被后台弹卡载体抑制；主线程访问。 */
    @Volatile
    private var suppressed = false

    fun install(context: Context) {
        if (installed) return
        synchronized(this) {
            if (installed) return
            installed = true
            val appContext = context.applicationContext
            AgentApprovalCoordinator.shared.addPendingListener { pending ->
                deliver(appContext)
            }
            // 对账：上一次进程留下的"待确认"通知可能对应早已失效的请求（进程被杀时没人撤回）。
            deliver(appContext)
        }
    }

    /**
     * 抑制/解除抑制汇总通知。切换瞬间按当前真实 pending 数量收敛一次，避免残留。
     * 由 Presenter 在主线程调用；撤销 9400 也依赖本类自己的 pending 监听兜底，install 顺序不可颠倒。
     */
    fun setSuppressed(context: Context, value: Boolean) {
        // G12：deliver 自己会 post 到主线程并重读当前 pending 数量，这里不再套外层 post，
        // 也避免把调用瞬间的 count 快照固定下来（pending 可能在 post 之间变化）。
        if (suppressed == value) return
        suppressed = value
        deliver(context.applicationContext)
    }

    private fun deliver(context: Context) {
        mainHandler.post {
            val count = AgentApprovalCoordinator.shared.pending.value.size
            if (suppressed || count <= 0) {
                AgentNotifications.cancelApprovalPending(context)
            } else {
                AgentNotifications.showApprovalPending(context, count)
            }
        }
    }
}
