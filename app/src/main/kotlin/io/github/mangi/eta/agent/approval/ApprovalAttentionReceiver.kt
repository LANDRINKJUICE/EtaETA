package io.github.mangi.eta.agent.approval

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 后台审批通知上"直接决策"按钮的落点。
 *
 * 通知的 action 用显式 Intent 指向本接收者（Manifest 里 android:exported="false"），
 * 因此外部 App 无法伪造一条广播去替用户放行危险操作；决策不经过打开 Activity，
 * 用户在通知栏点一下就能让等待中的工具线程恢复。
 *
 * 决策出口仍然只有 [AgentApprovalCoordinator.shared.resolve] 一个：
 * - 卡片已超时 / 已失效时 resolve 返回 false，这里不重试、不猜测；
 * - 成功落地后，Coordinator 的 pending 监听会让 [ApprovalAttentionPresenter] 精确撤掉
 *   悬浮层与通知（通知 ID 由 Presenter 占用检测分配并记账，这里不自行按 hash cancel，
 *   否则会撤错别人的通知）。
 *
 * 锁屏时**不 resolve 也不 refresh**：刷新会以"后台 + 悬浮层可用"裁决成 OVERLAY 分支，
 * 把用户刚点的通知撤掉、换成锁屏后面根本看不见的浮窗，等于"点了没反应"。这里直接返回，
 * 通知（VISIBILITY_PRIVATE，按钮仍显示但被拦截）原样留在锁屏上；解锁后 pending 监听或
 * 下一次交互会让 Presenter 自然收敛到可见载体。
 *
 * 「打开处理」按钮不是广播：它复用 AgentNotifications.taskCenterIntent 直达任务中心
 * 待确认区，由聊天流卡片承接需要自由输入或选项过多的场景。
 */
internal class ApprovalAttentionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_DECIDE) return
        val requestId = intent.getStringExtra(EXTRA_REQUEST_ID).orEmpty()
        val optionId = intent.getStringExtra(EXTRA_OPTION_ID).orEmpty()
        if (requestId.isBlank() || optionId.isBlank()) return

        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        val locked = km?.isKeyguardLocked == true
        // S3：锁屏点按钮不决策也不刷新，保留通知让用户解锁后处理。
        if (locked) return

        val resolved = runCatching {
            AgentApprovalCoordinator.shared.resolve(requestId, optionId)
        }.getOrDefault(false)

        // 决策成功 pending 归零、失败（超时/失效）都要重新对账：Presenter 按真实 pending 与
        // 精确记账收敛载体，避免悬浮层/通知与 active 漂移。resolved 仅决定是否记一条诊断日志。
        if (!resolved) {
            // 卡片已不在 active（多半超时）：不重试、不猜测，让 Presenter 收掉残留载体即可。
            io.github.mangi.eta.core.AndroidAgentLogger.warn(
                "Approval attention decision not applied (request expired or invalid)",
            )
        }
        ApprovalAttentionPresenter.refresh()
    }

    companion object {
        const val ACTION_DECIDE = "io.github.mangi.eta.agent.approval.DECIDE"
        const val EXTRA_REQUEST_ID = "approval_request_id"
        const val EXTRA_OPTION_ID = "approval_option_id"

        /** 生成一条只发给本接收者的显式决策 Intent。 */
        fun decideIntent(context: Context, requestId: String, optionId: String): Intent =
            Intent(context, ApprovalAttentionReceiver::class.java)
                .setAction(ACTION_DECIDE)
                .putExtra(EXTRA_REQUEST_ID, requestId)
                .putExtra(EXTRA_OPTION_ID, optionId)
    }
}
