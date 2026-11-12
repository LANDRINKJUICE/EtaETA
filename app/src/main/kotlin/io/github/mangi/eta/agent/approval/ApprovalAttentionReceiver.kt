package io.github.mangi.eta.agent.approval

import android.app.KeyguardManager
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 后台审批通知上「直接决策」按钮与「后台回复」RemoteInput 的落点。
 *
 * 通知的 action 用显式 Intent 指向本接收者（Manifest 里 android:exported="false"），
 * 因此外部 App 无法伪造一条广播去替用户放行危险操作；决策不经过打开 Activity，
 * 用户在通知栏点一下 / 直接打一句话就能让等待中的工具线程恢复。
 *
 * 决策出口仍然只有 [AgentApprovalCoordinator.shared.resolve] 一个：
 * - 点选按钮：照旧 [AgentApprovalCoordinator.resolve] (requestId, optionId)；
 * - 后台回复（RemoteInput）：用 [RemoteInput.getResultsFromIntent] 取出用户打的字，回灌
 *   `resolve(requestId, FREE_TEXT_ONLY, text)` —— 这正是前台聊天流自由输入用的现成链路，
 *   模型怎么收到文字、工具线程怎么恢复全是复用，一个字没改。
 *   - 危险卡 → [AgentApprovalOptionKind.Choice] → Reject，**那条危险命令不执行**，文字作为
 *     「用户的纠正建议」返回模型（与聊天流「拒绝并说明」语义一致，不是第二次指令）；
 *   - 询问卡 → Choice → 正常回答，结果里标注 `answered_via=free_text`。
 *
 * 卡片已超时 / 已失效时 resolve 返回 false，这里不重试、不猜测；成功落地后 Coordinator 的
 * pending 监听会让 [ApprovalAttentionPresenter] 精确撤掉通知（通知 ID 由 Presenter 占用检测
 * 分配并记账，这里不自行按 hash cancel，否则会撤错别人的通知）。
 *
 * 锁屏策略（审查后收窄）：RemoteInput 在锁屏由 SystemUI 托管键盘，是后台打字的主要场景，
 * 拒绝 / 打字（=拒绝并说明）在锁屏都允许；但「放行」类按钮锁屏可点是安全回归 —— 持锁机
 * 的人不该能替用户放行危险命令，故放行决策在锁屏被拦下、通知原样保留，解锁后再点即可。
 * 悬浮审批卡已移除，也不存在「点了通知刷新后把通知换成看不见的浮窗」的老问题。
 */
internal class ApprovalAttentionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return
        val requestId = intent.getStringExtra(EXTRA_REQUEST_ID).orEmpty()
        if (requestId.isBlank()) return

        when (intent.action) {
            ACTION_DECIDE -> {
                val optionId = intent.getStringExtra(EXTRA_OPTION_ID).orEmpty()
                if (optionId.isBlank()) return
                // 锁屏只拦「放行」类：拒绝、后台打字（=拒绝并说明）不受影响；通知保留，解锁后再点。
                if (optionId == AgentApprovalOption.ALLOW_ONCE ||
                    optionId == AgentApprovalOption.ALLOW_SESSION
                ) {
                    val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
                    if (km?.isKeyguardLocked == true) {
                        io.github.mangi.eta.core.AndroidAgentLogger.warn(
                            "Approval allow ignored while keyguard locked; notification kept",
                        )
                        ApprovalAttentionPresenter.refresh()
                        return
                    }
                }
                val resolved = runCatching {
                    AgentApprovalCoordinator.shared.resolve(requestId, optionId)
                }.getOrDefault(false)
                logIfExpired(resolved)
            }

            ACTION_REPLY -> {
                // RemoteInput 把用户输入的文字塞进 results Bundle（key = RESULT_KEY）。
                val results = runCatching { RemoteInput.getResultsFromIntent(intent) }.getOrNull()
                // 与前台卡片口径一致（freeText.take(1_000)），避免超长粘贴直接回灌模型。
                val text = results?.getCharSequence(RESULT_KEY)?.toString()?.trim()?.take(1_000).orEmpty()
                if (text.isEmpty()) {
                    // 空回复不决策，别把空串当成用户回答；留一条诊断线索便于真机排查。
                    io.github.mangi.eta.core.AndroidAgentLogger.warn(
                        "Approval reply ignored: empty text",
                    )
                    return
                }
                val resolved = runCatching {
                    AgentApprovalCoordinator.shared.resolve(
                        requestId,
                        AgentApprovalOption.FREE_TEXT_ONLY,
                        text,
                    )
                }.getOrDefault(false)
                logIfExpired(resolved)
            }

            else -> return
        }

        // 决策成功 pending 归零、失败（超时/失效）都要重新对账：Presenter 按真实 pending 与
        // 精确记账收敛通知，避免通知与 active 漂移。resolved 仅决定是否记一条诊断日志。
        ApprovalAttentionPresenter.refresh()
    }

    private fun logIfExpired(resolved: Boolean) {
        if (!resolved) {
            io.github.mangi.eta.core.AndroidAgentLogger.warn(
                "Approval attention decision not applied (request expired or invalid)",
            )
        }
    }

    companion object {
        const val ACTION_DECIDE = "io.github.mangi.eta.agent.approval.DECIDE"
        const val ACTION_REPLY = "io.github.mangi.eta.agent.approval.REPLY"
        const val EXTRA_REQUEST_ID = "approval_request_id"
        const val EXTRA_OPTION_ID = "approval_option_id"

        /** RemoteInput 的结果 key；[android.app.RemoteInput.Builder] 与这里用同一个常量。 */
        const val RESULT_KEY = "approval_reply_text"

        /** 生成一条只发给本接收者的显式决策 Intent。 */
        fun decideIntent(context: Context, requestId: String, optionId: String): Intent =
            Intent(context, ApprovalAttentionReceiver::class.java)
                .setAction(ACTION_DECIDE)
                .putExtra(EXTRA_REQUEST_ID, requestId)
                .putExtra(EXTRA_OPTION_ID, optionId)

        /** 生成一条「后台回复」RemoteInput action 用的显式广播 Intent（带 requestId）。 */
        fun replyIntent(context: Context, requestId: String): Intent =
            Intent(context, ApprovalAttentionReceiver::class.java)
                .setAction(ACTION_REPLY)
                .putExtra(EXTRA_REQUEST_ID, requestId)
    }
}
