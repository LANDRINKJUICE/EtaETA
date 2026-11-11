package io.github.mangi.eta.agent.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.MainActivity
import io.github.mangi.eta.ui.navigation.TaskCenterDeepLink

/**
 * Eta 通知的公共基建：channel 注册、点击直达的 PendingIntent、以及"主动沟通 / 待确认"两类通知。
 *
 * ## channel 语义分级（审查后整理，保留全部既有 channel ID 以免丢用户设置）
 * | 类别 | channel | 重要性 | 用途 |
 * | --- | --- | --- | --- |
 * | 常驻进行中 | eta_execution / scheduled_tasks / macro_recording / eta_read_aloud / eta_assistant_speech | LOW~DEFAULT | 正在跑、录制、朗读，实时刷新 + 停止按钮 |
 * | 普通结果 | eta_run_results / eta_background_commands / scheduled_tasks(结果) | DEFAULT | 跑完的结果，带查看/停止 action |
 * | 主动沟通（普通） | eta_proactive | DEFAULT | notify_user 普通级，克制 |
 * | 主动沟通（重要） | eta_attention | HIGH + 提示音 | notify_user important，仅真正紧急 |
 *
 * 危险操作"等待确认"复用 eta_proactive（DEFAULT、有角标），但作为常驻单条原地更新数量，
 * 点按直达任务中心待确认区。
 *
 * ## ColorOS 流体云 / Live Alerts 结论（见交付文档）
 * 本 App 已走的是"标准 Android promoted ongoing / Live Update"路径：
 * [io.github.mangi.eta.agent.runtime.AgentExecutionService] 在 API 36+ 用
 * `Notification.ProgressStyle` + `setShortCriticalText` + `android.requestPromotedOngoing`，
 * Manifest 也声明了 POST_PROMOTED_NOTIFICATIONS。ColorOS 的"流体云"是系统侧对这类
 * promoted/ongoing 通知的渲染，第三方 App 没有公开的 ColorOS 私有 SDK/API 去"指定胶囊样式"；
 * 硬编私有 API（Oplus/OPA 内部接口、反射系统服务）会随 ROM 版本崩溃，因此这里不臆造集成，
 * 而是保证通知结构（进行中 + ProgressStyle + 短关键文本 + 实时更新）符合被系统提升的条件。
 * 可行方案与风险详见交付文档"流体云"一节。
 */
internal object AgentNotifications {

    /**
     * 各通知 PendingIntent 的 requestCode。
     *
     * 必须全局唯一：PendingIntent 以 (requestCode, Intent filterEquals) 区分，两处共用同一个
     * requestCode + FLAG_UPDATE_CURRENT 时会互相覆盖 extras（例如把"去确认"的 tab 覆盖成别的分区）。
     */
    object RequestCodes {
        const val APPROVAL_CONTENT = 6_001
        const val APPROVAL_ACTION = 6_002
        const val SCHEDULED_CONTENT = 6_010
        const val BACKGROUND_COMMAND_CONTENT = 6_020
        const val ASSISTANT_SPEECH_STOP = 6_030
        // 后台任务自动唤醒的两个 requestCode 不在这里：它必须按任务分散（见
        // NotificationPolicy.jobWakeupRequestCode / jobWakeupActionRequestCode，
        // 用固定值 + FLAG_UPDATE_CURRENT 会让多条通知的会话参数互相覆盖）。
    }

    /** 点击通知直达任务中心（可带 tab 名，默认 APPROVALS = 待确认区）；唯一来源见 [TaskCenterDeepLink]。 */
    const val ACTION_OPEN_TASK_CENTER = TaskCenterDeepLink.ACTION_OPEN_TASK_CENTER
    const val EXTRA_TASK_CENTER_TAB = TaskCenterDeepLink.EXTRA_TASK_CENTER_TAB
    /** 注册本次新增的两条 channel；重复调用是幂等的。 */
    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching {
            manager.createNotificationChannel(
                NotificationChannel(
                    NotificationPolicy.CHANNEL_PROACTIVE,
                    context.getString(R.string.notify_channel_proactive),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = context.getString(R.string.notify_channel_proactive_desc)
                    setShowBadge(true)
                    enableVibration(false)
                    setSound(null, null)
                },
            )
            manager.createNotificationChannel(
                NotificationChannel(
                    NotificationPolicy.CHANNEL_ATTENTION,
                    context.getString(R.string.notify_channel_attention),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = context.getString(R.string.notify_channel_attention_desc)
                    setShowBadge(true)
                    enableVibration(true)
                    setSound(
                        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build(),
                    )
                },
            )
        }
    }

    /** 仅打开 App（不注入草稿）。 */
    fun appIntent(context: Context, requestCode: Int): PendingIntent =
        PendingIntent.getActivity(
            context,
            requestCode,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /**
     * 通知主体：打开对话并把 [draft] 作为新消息草稿。
     *
     * 复用既有的系统分享注入通路（ACTION_SEND + EXTRA_TEXT）：MainActivityLaunchRequests 已经
     * 把它收敛成 `sharedContent`，再由 AgentAppRoot 注入到输入框草稿位，不新增一条 UI 通路。
     */
    fun conversationIntent(context: Context, requestCode: Int, draft: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, draft)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** 通知主体 / action：打开任务中心指定分区（默认待确认区 APPROVALS）。 */
    fun taskCenterIntent(context: Context, requestCode: Int, tab: String = "APPROVALS"): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(ACTION_OPEN_TASK_CENTER)
            .putExtra(EXTRA_TASK_CENTER_TAB, tab)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * 通知主体：打开按 [source] + [conversationKey] 归档的会话。
     *
     * 无人值守的 run（语音、外部助手、自动任务、后台任务唤醒）不在用户的会话列表里，
     * 但结果会归档；这是唯一能"点通知直接看到那一轮到底做了什么"的通路，
     * 与 [AgentRuntimeService.openEtaFromResult] 的归档入口语义一致。
     *
     * [distinctData]：给 Intent 一个唯一 data URI。requestCode 相同 + FLAG_UPDATE_CURRENT 时，
     * PendingIntent 只按 (requestCode, action/data/type/class/category) 区分（extras 不参与），
     * 多条通知会互相覆盖参数；调用方除按 key 派生 requestCode 外，再用 data 兜一层。
     */
    fun archivedConversationIntent(
        context: Context,
        requestCode: Int,
        source: String,
        conversationKey: String,
        distinctData: String? = null,
    ): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(MainActivity.ACTION_OPEN_ARCHIVED_CONVERSATION)
            .putExtra(MainActivity.EXTRA_HANDOFF_SOURCE, source)
            .putExtra(MainActivity.EXTRA_CONVERSATION_KEY, conversationKey)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        distinctData?.takeIf(String::isNotBlank)?.let { data -> intent.data = Uri.parse(data) }
        return PendingIntent.getActivity(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** 通用 builder：兼容 O 以下，统一小图标、时间、点击行为。 */
    fun builder(context: Context, channelId: String): Notification.Builder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, channelId)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }.setSmallIcon(R.drawable.ic_notification)
            .setWhen(System.currentTimeMillis())

    /**
     * 发布一条 notify_user 主动沟通通知。[spec] 的语义全部由 [NotificationPolicy.proactive] 决定。
     */
    fun showProactive(context: Context, spec: NotificationPolicy.Proactive) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        ensureChannels(context)
        val open = when (spec.action) {
            NotificationPolicy.Action.OPEN_TASK_CENTER -> taskCenterIntent(context, spec.notificationId)
            // 只有明确带了 context 才把它作为"新消息草稿"注入；否则只打开 App，
            // 不把通知正文偷偷塞进输入框（那会让用户以为自己打过这些字）。
            NotificationPolicy.Action.OPEN_CONVERSATION ->
                if (spec.context.isNotBlank()) {
                    conversationIntent(context, spec.notificationId, spec.context)
                } else {
                    appIntent(context, spec.notificationId)
                }
        }
        val notification = builder(context, spec.channelId)
            .setContentTitle(spec.title)
            .setContentText(spec.body)
            .setStyle(Notification.BigTextStyle().bigText(spec.body))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(!spec.withSound)
            .setCategory(Notification.CATEGORY_MESSAGE)
            // 普通级也给一个"打开对话"的副动作：带上下文的通知点击进对话，按钮给任务中心。
            .addAction(
                Notification.Action.Builder(
                    null,
                    context.getString(R.string.notify_action_open_task_center),
                    taskCenterIntent(context, spec.notificationId, "RUNNING"),
                ).build(),
            )
            .build()
        runCatching { manager.notify(spec.notificationId, notification) }
    }

    /**
     * 危险操作等待确认：常驻单条，数量变化时原地更新（同一 ID），点击直达任务中心待确认区。
     * [count] <= 0 时清除通知。
     */
    fun showApprovalPending(context: Context, count: Int) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (count <= 0) {
            runCatching { manager.cancel(NotificationPolicy.APPROVAL_PENDING_ID) }
            return
        }
        ensureChannels(context)
        // 标题/正文的本地化模板交给纯逻辑格式化（count 只有一个来源，避免两处各自拼错）。
        val (title, body) = NotificationPolicy.approvalPending(
            count = count,
            titleFmt = context.getString(R.string.notify_approval_title),
            bodyFmt = context.getString(R.string.notify_approval_body),
        )
        val notification = builder(context, NotificationPolicy.CHANNEL_PROACTIVE)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(taskCenterIntent(context, RequestCodes.APPROVAL_CONTENT))
            // 不再 setOngoing：进程被杀/运行结束后残留的通知必须能被用户滑掉，
            // 否则会变成一条永远躺在通知栏、点进去又什么都没有的僵尸提醒。
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_REMINDER)
            .addAction(
                Notification.Action.Builder(
                    null,
                    context.getString(R.string.notify_action_review),
                    taskCenterIntent(context, RequestCodes.APPROVAL_ACTION),
                ).build(),
            )
            .build()
        runCatching { manager.notify(NotificationPolicy.APPROVAL_PENDING_ID, notification) }
    }

    fun cancelApprovalPending(context: Context) {
        runCatching {
            context.getSystemService(NotificationManager::class.java)
                ?.cancel(NotificationPolicy.APPROVAL_PENDING_ID)
        }
    }

    /**
     * 后台审批卡的行动通知（悬浮层不可用时的兜底，见
     * [io.github.mangi.eta.agent.approval.AgentApprovalAttentionPolicy] 规则 3）。
     *
     * 与 9400 汇总通知不同：每张卡一条、走 eta_attention HIGH channel（heads-up + 提示音），
     * 带最多 [AgentApprovalAttentionPolicy] 裁决出的直接决策按钮；用户不打开 App 就能放行/拒绝。
     * 决策按钮走显式广播 [io.github.mangi.eta.agent.approval.ApprovalAttentionReceiver]，
     * 最终仍落到 Coordinator.resolve 单一入口。
     */
    fun showApprovalAttention(
        context: Context,
        request: io.github.mangi.eta.agent.approval.AgentApprovalRequest,
        notificationId: Int,
    ) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        ensureChannels(context)
        val policy = io.github.mangi.eta.agent.approval.AgentApprovalAttentionPolicy
        val actions = policy.notificationActions(request)
        val danger = request.kind == io.github.mangi.eta.agent.approval.AgentApprovalKind.ToolRisk
        val title = context.getString(
            if (danger) R.string.approval_danger_title else R.string.approval_ask_title,
        )
        // 通知正文必须带上风险理由，不能只放命令原文就给一键放行按钮：把 findings 的 detail
        // 拼进来，用户不打开 App 也能看到为什么危险。同时截断，避免整段原始命令上锁屏/通知栏。
        val body = buildList {
            add(if (danger) request.subject else request.question.ifBlank { request.explanation })
            if (danger) {
                request.findings.forEach { finding ->
                    finding.detail.takeIf { it.isNotBlank() }?.let { add(it) }
                }
            }
        }.filter { it.isNotBlank() }
            .joinToString("\n")
            .let { raw ->
                if (raw.length > MAX_APPROVAL_BODY_CHARS) {
                    raw.take(MAX_APPROVAL_BODY_CHARS) + "..."
                } else {
                    raw
                }
            }
            .ifBlank { title }

        // 点通知主体：打开任务中心待确认区（在那里有完整的聊天流卡片与自由输入）。
        val open = taskCenterIntent(context, RequestCodes.APPROVAL_CONTENT)
        val notification = builder(context, NotificationPolicy.CHANNEL_ATTENTION)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_REMINDER)
            // 敏感操作原文不上锁屏：只显示标题，解锁后才看得到正文与风险理由。
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .apply {
                actions.forEach { optionId ->
                    val label = actionLabel(context, request, optionId)
                    val requestCode = NotificationPolicy.approvalActionRequestCode(request.id, optionId)
                    // 显式广播给 exported=false 的接收者：外部 App 无法伪造替用户放行。
                    // 关键：给 Intent 一个按 (审批 id, 选项 id) 唯一的 data，使其进入
                    // filterEquals。PendingIntent 只按 (requestCode, filterEquals) 区分，
                    // extras 不参与比较；仅靠窄散列段分 requestCode 会碰撞，导致 FLAG_UPDATE_CURRENT
                    // 把后一个按钮的 requestId/optionId 覆盖到前一个，出现「点 A 卡放行 B 卡」。
                    val intent = io.github.mangi.eta.agent.approval.ApprovalAttentionReceiver
                        .decideIntent(context, request.id, optionId)
                        .setData(android.net.Uri.parse("eta://eta.approval/${request.id}/$optionId"))
                    val pending = PendingIntent.getBroadcast(
                        context,
                        requestCode,
                        intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    )
                    addAction(Notification.Action.Builder(null, label, pending).build())
                }
                // 选项过多或只有自由输入：放不下按钮，给一个"打开处理"直达任务中心。
                if (policy.needsOpenAction(request)) {
                    addAction(
                        Notification.Action.Builder(
                            null,
                            context.getString(R.string.notify_action_review),
                            taskCenterIntent(context, RequestCodes.APPROVAL_ACTION),
                        ).build(),
                    )
                }
            }
            .build()
        runCatching { manager.notify(notificationId, notification) }
    }

    /**
     * 按钮文案：询问卡用模型给的 option.label（否则 N 个按钮全是同一个"去处理"，用户不知道在选什么）；
     * 危险卡的内置选项复用聊天流已有的 option 字符串资源，保证三语一致、不自造一套。
     */
    private fun actionLabel(
        context: Context,
        request: io.github.mangi.eta.agent.approval.AgentApprovalRequest,
        optionId: String,
    ): String {
        val custom = request.options.firstOrNull { it.id == optionId }?.label.orEmpty()
        if (custom.isNotBlank()) return custom
        val res = when (optionId) {
            io.github.mangi.eta.agent.approval.AgentApprovalOption.ALLOW_ONCE ->
                R.string.approval_option_allow_once
            io.github.mangi.eta.agent.approval.AgentApprovalOption.ALLOW_SESSION ->
                R.string.approval_option_allow_session
            io.github.mangi.eta.agent.approval.AgentApprovalOption.DENY ->
                R.string.approval_option_deny
            else -> R.string.notify_action_review
        }
        return context.getString(res)
    }

    /** 通知正文上限：命令原文可能含路径/URL/口令，不整段上锁屏或通知栏。 */
    private const val MAX_APPROVAL_BODY_CHARS = 160

    fun cancelApprovalAttention(context: Context, notificationId: Int) {
        runCatching {
            context.getSystemService(NotificationManager::class.java)?.cancel(notificationId)
        }
    }
}
