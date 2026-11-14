package io.github.mangi.eta.agent.notify

import android.app.NotificationManager
import android.content.Context
import android.os.SystemClock
import io.github.mangi.eta.R
import io.github.mangi.eta.config.Prefs
import org.json.JSONObject

/**
 * `notify_user` 工具的执行体：让 Agent 在"值得用户关注"时主动发一条系统通知。
 *
 * 使用纪律写在系统提示（AgentPromptBuilder）：克制、有价值才发、important 仅用于真正紧急。
 * 四道防线保证不滥用、不越权：
 * 1. 设置开关 [Prefs.Keys.AGENT_PROACTIVE_NOTIFY_ENABLED] 默认开、可关；关了直接返回 ok=false；
 * 2. 硬限流（[NotificationPolicy.admission]）：每 run ≤2 条 / 其中重要级 ≤1 条，
 *    以及进程级冷却（普通 30s、重要 60s）；超限返回 NOTIFY_RATE_LIMITED 且不发通知；
 * 3. 系统通知权限 / 该 channel 未被用户关闭时才会真正 notify（否则返回 ok=false，不发也不报错）；
 * 4. 角色会话既看不到该工具（ToolCatalogVisibility），执行侧 conversationToolsAllowed=false 也会拒绝。
 *
 * 限流状态是进程级单例：每次 run 结束时由 [AgentLocalTools.close] 调 [releaseRun] 清掉该 run 的配额，
 * 避免同一个 runId 在进程内被复用时继承上一轮的已用次数。
 *
 * 参数规范化、channel/ID 分配全部委托给纯逻辑 [NotificationPolicy]，这里只做 Android 落地。
 */
internal object NotifyUserTool {
    const val NAME = "notify_user"

    /** 进程级限流与配额；随进程存活，跨 run 复用（这正是"限流"的意义）。 */
    private val rateLimit = NotificationPolicy.RateLimit()
    private val runBudget = NotificationPolicy.RunBudget()

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
        // 用户把高优 channel 单独关掉时，重要级通知也发不出去；如实告知而不是假装成功。
        if (important && !channelEnabled(manager, NotificationPolicy.CHANNEL_ATTENTION)) {
            return result(ok = false, code = "CHANNEL_DISABLED", message = "重要通知 channel 已被关闭", important = true)
        }
        // 硬限流：模型忽略使用纪律时的最后一道闸门。放在所有"参数/权限"校验之后，
        // 只有真正要发出去的通知才消耗额度（失败的调用不该占用冷却）。
        val priority = if (important) NotificationPolicy.Priority.IMPORTANT else NotificationPolicy.Priority.NORMAL
        NotificationPolicy.admission(
            priority = priority,
            runId = runId,
            nowMillis = SystemClock.elapsedRealtime(),
            rateLimit = rateLimit,
            budget = runBudget,
        )?.let { code ->
            return result(
                ok = false,
                code = code,
                message = if (important) {
                    "重要通知发送过于频繁：每分钟最多 1 条、每次运行最多 1 条"
                } else {
                    "主动通知发送过于频繁：每 30 秒最多 1 条、每次运行最多 2 条"
                },
                important = important,
            )
        }
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
        AgentNotifications.showProactive(appContext, spec)
        return result(
            ok = true,
            code = "OK",
            message = "已发送${if (important) "重要" else "普通"}通知：${spec.title}",
            important = important,
        ).let { json ->
            // 附带可自检的结构化字段，方便模型与日志确认实际落地的语义。
            JSONObject(json)
                .put("notification_id", spec.notificationId)
                .put("channel", spec.channelId)
                .put("priority", spec.priority.name.lowercase())
                .put("action", spec.action.name.lowercase())
                .toString()
        }
    }

    /** run 结束清掉该 run 的配额；重复调用幂等。 */
    fun releaseRun(runId: String) {
        runBudget.release(runId)
    }

    /** API 26+ 才能查单 channel；低版本只要全局开关开就放行。 */
    private fun channelEnabled(manager: NotificationManager, channelId: String): Boolean {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) return true
        val channel = runCatching { manager.getNotificationChannel(channelId) }.getOrNull() ?: return true
        return channel.importance != NotificationManager.IMPORTANCE_NONE
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
