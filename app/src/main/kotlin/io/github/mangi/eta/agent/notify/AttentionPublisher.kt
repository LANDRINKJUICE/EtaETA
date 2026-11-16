package io.github.mangi.eta.agent.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import io.github.mangi.eta.R
import java.util.concurrent.ConcurrentHashMap

/**
 * 通知的**唯一物理出口**（2026-11-15 从根重构 + 独立二审闭环）。
 *
 * 全仓 `manager.notify(` / `createNotificationChannel(` / `manager.cancel(` 只此一处
 * （`scripts/check-unfixed.sh` 机器强制）。所有发起点构造 [AttentionIntent] 交
 * [AttentionDirector.decide] 裁决、把已构造好的 Notification 传进 [publish]，由这里统一落地
 * notify 与终态收尾（撤 ongoing）。
 *
 * 从根解决的确定性缺陷：
 *  - **限流不谎报 / 不白烧 / 不超发**：仅 `notify_user` 带 [AttentionIntent.AdmissionClaim]；
 *    出口用原子的 [NotificationPolicy.RateLimit.reserve] + [NotificationPolicy.RunBudget.reserve]
 *    先占额，notify 落地失败则 rollback（回退额度）。gate 与 commit 同一把锁，杜绝并发超发与
 *    「吞掉 tryAcquire 返回值」。
 *  - **落地结果如实回流**：`manager == null` / notify 抛异常返回 `PUBLISH_FAILED`。
 *  - **撤号不漂移**：notify / cancel / replaceOngoing 全用 [NotificationKey]（id + tag）。
 *  - **终态单一工厂**：[publishTerminal] 按 **runId** 幂等（不是 conversationId——避免同会话
 *    跨轮 / 唤醒会话追问互相吞），终态归属由调用方传 `wakeupRound`（本次 run 是否唤醒轮），
 *    不按会话前缀永久绑定（唤醒会话里用户追问应正常拿 run_result）。
 *
 * 前台服务 `startForeground` 载体是系统强制可见的白名单豁免：不经 decide/publish，但 ID 与
 * channel 一律取 [NotificationIdRegistry]；[foregroundOwners] 由 `check-unfixed.sh` 强制接线。
 */
internal object AttentionPublisher {

    @Volatile private var channelsEnsured = false

    /** 主动沟通（notify_user）的进程级限流与每-run 配额；唯一持有者=唯一出口。 */
    private val rateLimit = NotificationPolicy.RateLimit()
    private val runBudget = NotificationPolicy.RunBudget()

    /** 终态幂等账本：按 **runId** 记「本轮终态已处理」，跨轮/跨会话不互相吞。 */
    private val publishedTerminals = ConcurrentHashMap.newKeySet<String>()

    /**
     * FGS 前台服务载体白名单：owner → 该 Service 构造通知用的 channel。
     * `check-unfixed.sh` 据此断言「startForeground 只允许出现在这些 owner 对应的文件、且 channel 取自 Registry」。
     */
    val foregroundOwners: Map<String, NotificationIdRegistry.NotificationChannel> = mapOf(
        "execution_service" to NotificationIdRegistry.NotificationChannel.EXECUTION,
        "scheduled_task_running" to NotificationIdRegistry.NotificationChannel.SCHEDULED_TASKS,
        "voice" to NotificationIdRegistry.NotificationChannel.ASSISTANT_SPEECH,
        "voice_read_aloud" to NotificationIdRegistry.NotificationChannel.READ_ALOUD,
    )

    /** 所有 channel 的唯一注册入口（幂等）。完整保留改造前各 channel 的 sound/vibration/badge 语义。 */
    fun ensureAllChannels(context: Context) {
        if (channelsEnsured) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        var allOk = true
        NotificationIdRegistry.NotificationChannel.entries.forEach { ch ->
            val importance = when (ch) {
                NotificationIdRegistry.NotificationChannel.ATTENTION -> NotificationManager.IMPORTANCE_HIGH
                NotificationIdRegistry.NotificationChannel.EXECUTION,
                NotificationIdRegistry.NotificationChannel.MACRO_RECORDING,
                NotificationIdRegistry.NotificationChannel.ASSISTANT_SPEECH,
                NotificationIdRegistry.NotificationChannel.READ_ALOUD -> NotificationManager.IMPORTANCE_LOW
                else -> NotificationManager.IMPORTANCE_DEFAULT
            }
            val label = channelLabel(context, ch)
            val ok = runCatching {
                manager.createNotificationChannel(NotificationChannel(ch.id, label, importance).apply {
                    when (ch) {
                        NotificationIdRegistry.NotificationChannel.PROACTIVE -> {
                            description = context.getString(R.string.notify_channel_proactive_desc)
                            setShowBadge(true); enableVibration(false); setSound(null, null)
                        }
                        NotificationIdRegistry.NotificationChannel.ATTENTION -> {
                            description = context.getString(R.string.notify_channel_attention_desc)
                            setShowBadge(true); enableVibration(true)
                            setSound(
                                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                                AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
                            )
                        }
                        NotificationIdRegistry.NotificationChannel.MACRO_RECORDING -> setShowBadge(false)
                        NotificationIdRegistry.NotificationChannel.SCHEDULED_TASKS -> setShowBadge(true)
                        else -> Unit
                    }
                })
            }.isSuccess
            if (!ok) allOk = false
        }
        if (allOk) channelsEnsured = true
    }

    private fun channelLabel(context: Context, ch: NotificationIdRegistry.NotificationChannel): String =
        when (ch) {
            NotificationIdRegistry.NotificationChannel.EXECUTION -> context.getString(R.string.execution_channel)
            NotificationIdRegistry.NotificationChannel.RUN_RESULTS -> context.getString(R.string.execution_result_channel)
            NotificationIdRegistry.NotificationChannel.PROACTIVE -> context.getString(R.string.notify_channel_proactive)
            NotificationIdRegistry.NotificationChannel.ATTENTION -> context.getString(R.string.notify_channel_attention)
            NotificationIdRegistry.NotificationChannel.SCHEDULED_TASKS -> context.getString(R.string.schedule_notification_channel)
            NotificationIdRegistry.NotificationChannel.BACKGROUND_COMMANDS -> context.getString(R.string.background_command_channel)
            NotificationIdRegistry.NotificationChannel.MACRO_RECORDING -> context.getString(R.string.macro_recording_channel)
            NotificationIdRegistry.NotificationChannel.ASSISTANT_SPEECH -> context.getString(R.string.assistant_speech_channel)
            NotificationIdRegistry.NotificationChannel.READ_ALOUD -> context.getString(R.string.read_aloud_channel)
        }

    fun channelEnabled(context: Context, ch: NotificationIdRegistry.NotificationChannel): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        val channel = runCatching { manager.getNotificationChannel(ch.id) }.getOrNull() ?: return true
        return channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    /** 只放**客观事实**，全部从统一源取；不回填意图开关、不放恒真假闸门。 */
    fun defaultContext(context: Context, intent: AttentionIntent): DisturbContext {
        val manager = context.getSystemService(NotificationManager::class.java)
        return DisturbContext(
            notificationsEnabled = manager?.areNotificationsEnabled() ?: false,
            channelEnabled = channelEnabled(context, intent.channel),
            appForeground = AppForeground.isForeground(),
        )
    }

    /**
     * 唯一发布入口。顺序即契约：
     * 撤 ongoing（无条件，先于一切）→ 准入占额（原子 reserve）→ 裁决 → 建 channel → notify
     * → 落地失败 rollback。任何一步不放行都不留僵尸、不占额、不谎报。
     */
    fun publish(
        context: Context,
        intent: AttentionIntent,
        notification: Notification,
        ctx: DisturbContext = defaultContext(context, intent),
    ): AttentionDirector.AttentionDecision {
        val cancelOngoing = intent.replaceOngoing
        // (0) 收尾先行：无论最终发不发，进行中通知都要撤（带 tag 句柄），绝不残留 setOngoing 僵尸。
        cancelOngoing.forEach { cancel(context, it) }

        // (1) 主动沟通准入：原子占额（reserve = 判断+占用同一把锁），被拒绝时零副作用。
        val claim = intent.admission
        if (claim != null) {
            val priority = if (claim.important) NotificationPolicy.Priority.IMPORTANT else NotificationPolicy.Priority.NORMAL
            if (!runBudget.reserve(claim.runId, claim.important) ||
                !rateLimit.reserve(priority, android.os.SystemClock.elapsedRealtime())
            ) {
                // 只可能有一个 reserve 成功、另一个失败：把已占的那个回退，保持原子。
                runBudget.decrement(claim.runId, claim.important)
                rateLimit.rollback(priority)
                return AttentionDirector.AttentionDecision(
                    false, cancelOngoing, AttentionDirector.RejectReason.RATE_LIMITED,
                )
            }
        }

        // (2) 唯一裁决。
        val decision = AttentionDirector.decide(intent, ctx)

        // (3) 落地；失败或拒绝一律回退已占额度（落地成功才真正记账）。
        fun rollbackAdmission() {
            if (claim != null) {
                val priority = if (claim.important) NotificationPolicy.Priority.IMPORTANT else NotificationPolicy.Priority.NORMAL
                rateLimit.rollback(priority)
                runBudget.decrement(claim.runId, claim.important)
            }
        }
        if (!decision.publish) {
            rollbackAdmission()
            return decision
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager == null) {
            rollbackAdmission()
            return AttentionDirector.AttentionDecision(false, cancelOngoing, AttentionDirector.RejectReason.PUBLISH_FAILED)
        }
        ensureAllChannels(context)
        val posted = runCatching {
            if (intent.key.tag == null) manager.notify(intent.key.id, notification)
            else manager.notify(intent.key.tag, intent.key.id, notification)
        }.isSuccess
        if (!posted) {
            rollbackAdmission()
            return AttentionDirector.AttentionDecision(false, cancelOngoing, AttentionDirector.RejectReason.PUBLISH_FAILED)
        }
        return AttentionDirector.AttentionDecision(true, cancelOngoing)
    }

    /**
     * 终态**单一工厂**：所有「一轮 run 结束」的结果通知都走这里。
     *  - 按 [runId] 幂等（跨轮不互相吞）；失败/被抑制时移除标记允许重试。
     *  - [wakeupRound] 由调用方按「本次 run 是不是后台唤醒轮」传入，不按会话前缀永久绑定：
     *    唤醒轮会话里用户后续追问是普通 run，应正常拿 run_result 通知。
     *  - [build] 只在真正要落地时调用，避免被抑制时白构造 Notification。
     */
    fun publishTerminal(
        context: Context,
        runId: String,
        subject: String,
        wakeupRound: Boolean = false,
        suppressWhenForeground: Boolean = true,
        build: (key: NotificationKey) -> Notification,
    ): AttentionDirector.AttentionDecision {
        if (wakeupRound) {
            // 唤醒轮终态唯一由 JobWakeupNotifications.showFinished 用 9602 就地变形承担（finally 必达）。
            return AttentionDirector.AttentionDecision(publish = false, cancelOngoing = emptyList())
        }
        if (runId.isBlank() || !publishedTerminals.add(runId)) {
            return AttentionDirector.AttentionDecision(publish = false, cancelOngoing = emptyList())
        }
        val key = NotificationKey(NotificationIdRegistry.idOf("run_result", subject), subject)
        val intent = AttentionIntent(
            key = key,
            channel = NotificationIdRegistry.channelOf("run_result"),
            suppressWhenForeground = suppressWhenForeground,
        )
        val decision = publish(context, intent, build(key))
        if (!decision.publish) publishedTerminals.remove(runId) // 被抑制/失败：允许重试
        return decision
    }

    /** run 结束清掉该 run 的主动沟通配额；重复调用幂等。 */
    fun releaseRun(runId: String) {
        runBudget.release(runId)
    }

    /** 撤掉一条进行中通知（带唯一句柄，不会漏 tag）。 */
    fun cancel(context: Context, key: NotificationKey) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        runCatching {
            if (key.tag == null) manager.cancel(key.id) else manager.cancel(key.tag, key.id)
        }
    }

    fun cancel(context: Context, id: Int) = cancel(context, NotificationKey(id))

    fun ensureResultChannel(context: Context) = ensureAllChannels(context)
}
