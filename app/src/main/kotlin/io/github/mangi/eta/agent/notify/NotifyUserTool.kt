package io.github.mangi.eta.agent.notify

import android.app.NotificationManager
import android.content.Context
import io.github.mangi.eta.R
import io.github.mangi.eta.config.Prefs
import org.json.JSONObject

/**
 * `notify_user` 工具的执行体：让 Agent 在「值得用户关注」时主动发一条系统通知。
 *
 * 2026-11-15 从根重构：准入（限流/配额）与落地**统一交给唯一出口 [AttentionPublisher]**，
 * 工具不再持有自己的限流单例、不再先扣额度再发（旧实现：channel 被关 / 权限被撤时 publish
 * 被静默拒绝，工具仍回 `ok=true "已发送"` 且额度已白烧——确定性谎报）。现在：
 *  1. 工具只做参数 / 全局开关 / 权限预检，并把 [AttentionIntent.AdmissionClaim] 注入出口；
 *  2. 出口 gate（不占额）→ 裁决 → notify 真正落地成功才 commit 配额；
 *  3. 工具按 [AttentionDirector.AttentionDecision.reject] 如实映射结果，绝不谎报。
 *
 * 使用纪律仍在系统提示：克制、有价值才发、important 仅用于真正紧急。角色会话既看不到该工具
 * （ToolCatalogVisibility），执行侧 conversationToolsAllowed=false 也会拒绝。
 */
internal object NotifyUserTool {
    const val NAME = "notify_user"

    fun execute(context: Context, args: JSONObject, runId: String = "", conversationId: String? = null): String {
        val appContext = context.applicationContext
        if (!Prefs.isProactiveNotifyEnabled()) {
            return result(ok = false, code = "NOTIFY_DISABLED", message = "主动沟通开关已关闭", important = false)
        }
        val title = args.optString("title").trim()
        val validation = NotificationPolicy.validate(title)
        if (validation != null) {
            return result(ok = false, code = validation, message = "title 不能为空", important = false)
        }
        val manager = appContext.getSystemService(NotificationManager::class.java)
        if (manager == null || !manager.areNotificationsEnabled()) {
            return result(ok = false, code = "NOTIFICATIONS_UNAVAILABLE", message = "系统通知未授权", important = false)
        }
        val important = args.optString("priority").trim().equals("important", ignoreCase = true)
        val spec = NotificationPolicy.proactive(
            rawTitle = title,
            rawBody = args.optString("body"),
            rawPriority = args.optString("priority"),
            rawAction = args.optString("action"),
            rawContext = args.optString("context"),
            defaultTitle = appContext.getString(R.string.notify_default_title),
            importanceDefault = NotificationManager.IMPORTANCE_DEFAULT,
            importanceHigh = NotificationManager.IMPORTANCE_HIGH,
            conversationId = conversationId,
        )
        // 唯一出口裁决 + 落地；准入声明交给出口，落地成功才占额。
        val decision = AgentNotifications.showProactive(
            appContext, spec,
            admission = AttentionIntent.AdmissionClaim(important = important, runId = runId),
        )
        if (!decision.publish) {
            val (code, message) = when (decision.reject) {
                AttentionDirector.RejectReason.CHANNEL_DISABLED,
                AttentionDirector.RejectReason.NOTIFICATIONS_DISABLED ->
                    "CHANNEL_DISABLED" to "通知 channel 已被关闭或系统通知不可用，未发送"
                AttentionDirector.RejectReason.PUBLISH_FAILED ->
                    "NOTIFICATIONS_UNAVAILABLE" to "通知未能落地，未发送"
                AttentionDirector.RejectReason.RATE_LIMITED,
                AttentionDirector.RejectReason.FOREGROUND_SUPPRESSED ->
                    "NOTIFY_RATE_LIMITED" to "主动通知发送过于频繁或前台静默，未发送（未占用额度）"
                AttentionDirector.RejectReason.UNREGISTERED_ID,
                AttentionDirector.RejectReason.CHANNEL_MISMATCH ->
                    "INTERNAL_ERROR" to "通知号段/渠道登记异常，未发送"
                null -> "INTERNAL_ERROR" to "通知未能落地（未知原因），未发送"
            }
            return result(ok = false, code = code, message = message, important = important)
        }
        return result(
            ok = true,
            code = "OK",
            message = "已发送${if (important) "重要" else "普通"}通知：${spec.title}",
            important = important,
        ).let { json ->
            JSONObject(json)
                .put("notification_id", spec.notificationId)
                .put("channel", spec.channelId)
                .put("priority", spec.priority.name.lowercase())
                .put("action", spec.action.name.lowercase())
                .toString()
        }
    }

    /** run 结束清掉该 run 的配额；转发给唯一出口（唯一持有者）。重复调用幂等。 */
    fun releaseRun(runId: String) {
        AttentionPublisher.releaseRun(runId)
    }

    private fun result(ok: Boolean, code: String, message: String, important: Boolean): String =
        JSONObject()
            .put("ok", ok)
            .put("tool", NAME)
            .put("code", code)
            .put("message", message)
            .put("important", important)
            .toString()
}
