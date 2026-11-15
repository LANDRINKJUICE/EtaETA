package io.github.mangi.eta.agent.notify

/**
 * 通知基建的纯逻辑层：ID 分段、channel 分配、优先级映射、文案组装、节流判断。
 *
 * 刻意不引用任何 android.* / R.*：这部分是通知"长什么样、什么时候更新"的唯一裁决点，
 * 能在纯 JVM 单测里钉住边界（ID 段不冲突、重要级走不同 channel、节流 ≥1s、标题/正文截断）。
 * Android 侧 [NotifyUserTool] / [AgentNotifications] 只负责把这里算好的结果搬进
 * Notification.Builder，不再各自决定语义。
 *
 * 既有 channel 一律保留 ID（避免丢失用户已设的每-channel 通知设置），本次只新增两条语义清晰的
 * 主动沟通 channel；完整分级表见 [AgentNotifications]。
 */
internal object NotificationPolicy {

    // ---- 新增 channel：按"是否需要立刻关注"分级 ----
    /** 普通主动沟通 / 危险操作待确认：channel id 唯一取自 Registry（不再另写字面量）。 */
    val CHANNEL_PROACTIVE: String get() = NotificationIdRegistry.NotificationChannel.PROACTIVE.id
    /** 真正紧急的主动沟通：channel id 唯一取自 Registry。 */
    val CHANNEL_ATTENTION: String get() = NotificationIdRegistry.NotificationChannel.ATTENTION.id

    // ---- 通知 ID 分段 ----
    // 已占用：9000 自动任务前台、9001..9290 自动任务结果、9300..9349 主动沟通、9400 待确认、
    // 9500-9501 预留、9502..9560 run 结果(按会话散列)、9600/9601 宏录制、9602..9651 后台唤醒、
    // 9660..9999 审批卡；另有 1107/1108 执行服务、1124 后台命令、4203/4204 语音。
    /** 主动沟通 notify_user：9300..9349，按 key 散列，互不覆盖、也不与既有段相交。 */
    private fun proactiveSegment(): NotificationIdRegistry.IdSegment =
        NotificationIdRegistry.allIdSegments.first { it.owner == "notify_user_proactive" }
    /** 危险操作等待确认：常驻单条，数量变化时原地更新（段值由 Registry 唯一派生）。 */
    val APPROVAL_PENDING_ID: Int get() = NotificationIdRegistry.idOf("approval_pending")
    /**
     * 后台任务失败自动唤醒：9602..9651，按任务 id 散列。
     *
     * 紧跟既有 9600/9601（宏录制）之后取 50 个空位，避让 9000-9299、9300-9349、9400、
     * 9500-9501、9600-9601 全部已占用区间；见 [reservedSegments]。
     */
    private fun jobWakeupSegment(): NotificationIdRegistry.IdSegment =
        NotificationIdRegistry.allIdSegments.first { it.owner == NotificationIdRegistry.OWNER_JOB_WAKEUP }

    /** run 结果通知：按 conversationId 散列（唯一源 Registry），避免多条会话结果互相覆盖。 */
    fun runResultId(conversationId: String): Int =
        NotificationIdRegistry.idOf("run_result", conversationId)


    /**
     * 危险审批卡的后台行动通知：9660..9999，按审批 id 散列，同一张卡原地更新。
     *
     * 为什么不是复用 9400：9400 是「N 个操作等待确认」的单条汇总通知（被动提醒，只有“去确认”
     * 一个动作）；这里每条审批一张卡、带最多 3 个直接决策按钮，语义与生命周期都不同，
     * 混用会让汇总通知被单张卡覆盖掉。段位紧跟 9602..9651 之后取 340 个空位，
     * 避让已占用的全部区间（见 [reservedSegments]）。
     */
    private fun approvalAttentionSegment(): NotificationIdRegistry.IdSegment =
        NotificationIdRegistry.allIdSegments.first { it.owner == "approval_attention" }



    /** 更新节流：常驻通知的"实时"状态不得比 1 秒更频繁，避免系统限流。 */
    const val MIN_REFRESH_INTERVAL_MILLIS = 1_000L

    /**
     * 常驻通知耗时心跳的间隔。
     *
     * 秒级心跳对"只想知道跑了多久"没有增量信息，却要在每次唤醒时点亮 CPU；5s 粒度足够，
     * 且把后台唤醒次数降到 1/5。文案仍按秒展示，只是每 5s 跳一次。
     */
    const val SLOW_REFRESH_INTERVAL_MILLIS = 5_000L

    // ---- 主动沟通的硬限流（即便模型忽略使用纪律，也不能刷屏）----
    /** 普通级主动沟通的进程级最小间隔。 */
    const val NORMAL_NOTIFY_MIN_INTERVAL_MILLIS = 30_000L
    /** 重要级主动沟通的进程级最小间隔（更长的冷却，避免"狼来了"）。 */
    const val IMPORTANT_NOTIFY_MIN_INTERVAL_MILLIS = 60_000L
    /** 同一次 run 内最多发几条。 */
    const val MAX_NOTIFY_PER_RUN = 2
    /** 同一次 run 内最多发几条重要级。 */
    const val MAX_IMPORTANT_NOTIFY_PER_RUN = 1
    /** 展示长度上限：过长会把通知撑爆、也可能被系统截断成无意义片段。 */
    const val TITLE_MAX_CHARS = 60
    const val BODY_MAX_CHARS = 240
    const val CONTEXT_MAX_CHARS = 4_000

    enum class Priority { NORMAL, IMPORTANT }

    /** 主动沟通的两条可选动作（点击通知主体 / 通知上的按钮）。 */
    enum class Action { OPEN_CONVERSATION, OPEN_TASK_CENTER }

    data class Proactive(
        val notificationId: Int,
        val channelId: String,
        val importance: Int,
        val title: String,
        val body: String,
        val action: Action,
        val context: String,
        val priority: Priority,
        /** 是否附带提示音（仅重要级）。 */
        val withSound: Boolean,
        /** 发起该通知的 UI 会话 id；非空时通知栏回复回到这个会话，null 表示后台/归档来源（不挂回复）。 */
        val conversationId: String? = null,
    )

    /**
     * 把模型的 notify_user 参数收敛成一条可发布的通知规格。
     *
     * - 优先级：仅 "important" 走高优 channel + 提示音，其余（含未知值）一律按普通级；
     * - 动作：默认 open_conversation（带上下文进对话）；open_task_center 直达任务中心；
     * - 上下文：只有在 open_conversation 且非空时才会带进新消息草稿，裁到 [CONTEXT_MAX_CHARS]；
     * - 标题/正文：去空白、裁到上限；空白标题回退到调用方给的 [defaultTitle]（已本地化）。
     *
     * [importanceDefault] / [importanceHigh]：把 Android 的 IMPORTANCE_* 常量由调用方传入，
     * 保持本文件不依赖 android.jar，同时让分级在单测里可断言。
     */
    fun proactive(
        rawTitle: String,
        rawBody: String,
        rawPriority: String?,
        rawAction: String?,
        rawContext: String?,
        defaultTitle: String,
        notificationKey: String = "$rawTitle|$rawBody",
        importanceDefault: Int,
        importanceHigh: Int,
        conversationId: String? = null,
    ): Proactive {
        val priority = if (rawPriority?.trim()?.equals("important", ignoreCase = true) == true) {
            Priority.IMPORTANT
        } else {
            Priority.NORMAL
        }
        val action = when (rawAction?.trim()?.lowercase()) {
            "open_task_center" -> Action.OPEN_TASK_CENTER
            else -> Action.OPEN_CONVERSATION
        }
        val title = clamp(rawTitle, TITLE_MAX_CHARS).ifBlank { clamp(defaultTitle, TITLE_MAX_CHARS) }
        val body = clamp(rawBody, BODY_MAX_CHARS)
        // 只有"打开对话"才把上下文带成草稿；任务中心不需要草稿参数。
        val context = if (action == Action.OPEN_CONVERSATION) {
            (rawContext ?: "").trim().take(CONTEXT_MAX_CHARS)
        } else {
            ""
        }
        // 把会话 id 并入散列 key：不同会话即使标题/正文相同，也各自一条通知、各自的回复按钮，
        // 不会被「同主题原地更新」互相覆盖、回复串到另一个会话（多会话「各自对应」的关键）。
        val normalizedConversationId = conversationId?.trim()?.takeIf { it.isNotBlank() }
        val effectiveKey = if (normalizedConversationId == null) {
            notificationKey
        } else {
            "$notificationKey|$normalizedConversationId"
        }
        return Proactive(
            notificationId = proactiveId(effectiveKey),
            channelId = if (priority == Priority.IMPORTANT) CHANNEL_ATTENTION else CHANNEL_PROACTIVE,
            importance = if (priority == Priority.IMPORTANT) importanceHigh else importanceDefault,
            title = title,
            body = body,
            action = action,
            context = context,
            priority = priority,
            withSound = priority == Priority.IMPORTANT,
            conversationId = conversationId?.trim()?.takeIf { it.isNotBlank() },
        )
    }

    /** 按 key 稳定散列到主动沟通段；同一主题反复通知复用同一 ID（唯一源 Registry）。 */
    fun proactiveId(key: String): Int =
        NotificationIdRegistry.idOf("notify_user_proactive", key)

    /** 后台任务自动唤醒通知：同一任务反复唤醒复用同一 ID（唯一源 Registry）。 */
    fun jobWakeupId(jobId: String): Int =
        NotificationIdRegistry.idOf(NotificationIdRegistry.OWNER_JOB_WAKEUP, jobId)

    /** 自动唤醒通知"点主体进会话"的 requestCode：6200..6249，按任务分散。 */
    fun jobWakeupRequestCode(jobId: String): Int =
        NotificationIdRegistry.hashedIn("job_wakeup_content", jobId)

    /** 自动唤醒通知"打开任务中心"按钮的 requestCode：按任务分散（唯一源 Registry 段）。 */
    fun jobWakeupActionRequestCode(jobId: String): Int =
        NotificationIdRegistry.hashedIn("job_wakeup_action", jobId)

    /** 单卡场景的兼容 hash（Presenter 多卡时用 [allocateApprovalNotificationId] 占用检测分配）。 */
    fun approvalAttentionId(requestId: String): Int =
        NotificationIdRegistry.idOf("approval_attention", requestId)

    /**
     * notify_user 通知栏「回复」action 的 PendingIntent requestCode 段：6700..6749。
     * 必须按通知 ID 分散：PendingIntent 只按 (requestCode, filterEquals) 区分，extras 不参与比较；
     * 与既有 6001/6002/6010/6020/6030、6200..6299（唤醒）、6400..6699（审批按钮）全部不相交。
     * 段值同步登记在 NotificationIdRegistry.allRequestCodeSegments（见 notify_user_reply）。
     */
    fun notifyReplyRequestCode(notificationId: Int): Int =
        NotificationIdRegistry.mapIntoSegment("notify_user_reply", notificationId)

    /** 行动通知 ID 段起点与宽度：Presenter 做碰撞占用检测时按此分配，避免两张卡 hash 到同一 ID。 */
    fun approvalAttentionBase(): Int = approvalAttentionSegment().range.first
    fun approvalAttentionRange(): Int = approvalAttentionSegment().range.count()

    /**
     * 在 [approvalAttentionBase]..base+range 段内分配一个未被 [used] 占用的 ID（纯函数，可单测）。
     *
     * [used] 必须包含**本轮已经分配出去的** ID，否则同一轮里出现的多张卡会都拿到 base，
     * 后一条 notify 覆盖前一条。段满时回退到 base（宁可覆盖也不越界）。
     */
    fun allocateApprovalNotificationId(used: Collection<Int>): Int {
        val seg = approvalAttentionSegment()
        for (offset in 0 until seg.range.count()) {
            val id = seg.range.first + offset
            if (id !in used) return id
        }
        return seg.range.first
    }

    /** 审批通知按钮的 requestCode：6400..6699，按 (审批 id, 选项 id) 分散，互不覆盖。 */
    fun approvalActionRequestCode(requestId: String, optionId: String): Int =
        NotificationIdRegistry.hashedIn("approval_decision", "$requestId|$optionId")

    /** 参数校验：标题必填（模型必须给一个能读懂的标题）。返回错误码或 null。 */
    fun validate(rawTitle: String): String? =
        if (rawTitle.isBlank()) "INVALID_ARGUMENT" else null

    /** 危险操作等待确认的文案与数量：常驻单条，数量变化时原地更新。 */
    fun approvalPending(count: Int, titleFmt: String, bodyFmt: String): Pair<String, String> {
        val safe = count.coerceAtLeast(0)
        return String.format(titleFmt, safe) to String.format(bodyFmt, safe)
    }

    /**
     * 常驻通知的实时文案：阶段 + 进度（可选）+ 耗时。
     * [elapsedMillis] 用单调时钟算出的差值，负数（时钟回拨）按 0 处理。
     */
    fun ongoingText(stage: String, progress: Int?, elapsedMillis: Long, elapsedFmt: String): String {
        val seconds = (elapsedMillis / 1_000L).coerceAtLeast(0L)
        val elapsed = String.format(elapsedFmt, seconds)
        val stageText = clamp(stage, BODY_MAX_CHARS)
        return when {
            progress != null -> "$stageText · ${progress.coerceIn(0, 100)}% · $elapsed"
            else -> "$stageText · $elapsed"
        }
    }

    /**
     * 进程级硬限流：普通级与重要级各自一个冷却桶。
     *
     * 为什么分桶：重要级是"真的紧急"，不应被刚发过的一条普通提醒挡住；反之普通级也不该
     * 借重要级的额度频繁打扰。时钟由调用方注入（elapsedRealtime），系统时间回拨不影响判定。
     */
    class RateLimit(
        private val normalIntervalMillis: Long = NORMAL_NOTIFY_MIN_INTERVAL_MILLIS,
        private val importantIntervalMillis: Long = IMPORTANT_NOTIFY_MIN_INTERVAL_MILLIS,
    ) {
        private val lock = Any()
        private var lastNormalMillis: Long = NEVER
        private var lastImportantMillis: Long = NEVER

        /**
         * 原子「判断+占额」（gate 与 commit 同一把锁，杜绝并发超发）：
         * true = 已占用本次额度，调用方在落地失败时必须 [rollback]。
         */
        fun reserve(priority: Priority, nowMillis: Long): Boolean = synchronized(lock) {
            if (!wouldAllowUnlocked(priority, nowMillis)) return false
            if (priority == Priority.IMPORTANT) lastImportantMillis = nowMillis else lastNormalMillis = nowMillis
            true
        }

        /** 回退一次 [reserve] 的占用（落地失败时调用，保证「落地成功才记账」）。 */
        fun rollback(priority: Priority) = synchronized(lock) {
            if (priority == Priority.IMPORTANT) lastImportantMillis = NEVER else lastNormalMillis = NEVER
        }

        /** 只判断不占额（诊断/单测用）；生产准入一律走 [reserve]。 */
        fun wouldAllow(priority: Priority, nowMillis: Long): Boolean = synchronized(lock) {
            wouldAllowUnlocked(priority, nowMillis)
        }

        /** true 表示放行并占用本次额度；false 表示仍在冷却期（单测用，等价于 [reserve]）。 */
        fun tryAcquire(priority: Priority, nowMillis: Long): Boolean = reserve(priority, nowMillis)

        /** 锁内判据（调用方已持有 [lock]）。 */
        private fun wouldAllowUnlocked(priority: Priority, nowMillis: Long): Boolean {
            val important = priority == Priority.IMPORTANT
            val last = if (important) lastImportantMillis else lastNormalMillis
            val interval = if (important) importantIntervalMillis else normalIntervalMillis
            val elapsed = if (last == NEVER) Long.MAX_VALUE else nowMillis - last
            return elapsed >= interval
        }

        /** 距该优先级上次放行的时间；从未放过返回 null（诊断/测试用）。 */
        fun lastEmitMillis(priority: Priority): Long? = synchronized(lock) {
            val last = if (priority == Priority.IMPORTANT) lastImportantMillis else lastNormalMillis
            if (last == NEVER) null else last
        }

        fun reset() = synchronized(lock) {
            lastNormalMillis = NEVER
            lastImportantMillis = NEVER
        }

        private companion object {
            const val NEVER = Long.MIN_VALUE
        }
    }

    /**
     * 每次 run 的配额：总 ≤[maxPerRun]、其中重要级 ≤[maxImportantPerRun]。
     *
     * runId 为空（例如没有会话上下文的旁路调用）归入同一个共享桶，不会因此获得无限额度。
     * 运行结束时必须 [release]，否则同一个进程里复用的 runId 会继承上一轮的已用次数。
     */
    class RunBudget(
        private val maxPerRun: Int = MAX_NOTIFY_PER_RUN,
        private val maxImportantPerRun: Int = MAX_IMPORTANT_NOTIFY_PER_RUN,
    ) {
        private class Used(var total: Int = 0, var important: Int = 0)

        private val lock = Any()
        private val used = java.util.concurrent.ConcurrentHashMap<String, Used>()

        /** 只判断不占额：便于"先确认放行再占用"，避免被其它闸门拒绝时白白消耗配额。 */
        fun wouldAllow(runId: String, important: Boolean): Boolean = synchronized(lock) {
            val entry = used[runId.orSharedBucket()] ?: return true
            if (entry.total >= maxPerRun) return false
            !(important && entry.important >= maxImportantPerRun)
        }

        /** 真正占用一次额度；调用方必须先用 [wouldAllow] 确认。 */
        fun commit(runId: String, important: Boolean) = synchronized(lock) {
            val entry = used.getOrPut(runId.orSharedBucket()) { Used() }
            entry.total += 1
            if (important) entry.important += 1
        }

        /** 判断+占用的便捷组合（单测与简单调用方使用）。 */
        fun tryAcquire(runId: String, important: Boolean): Boolean = reserve(runId, important)

        /** 原子「判断+占额」（gate 与 commit 同一把锁），落地失败时由 [decrement] 回退。 */
        fun reserve(runId: String, important: Boolean): Boolean = synchronized(lock) {
            if (!wouldAllow(runId, important)) return false
            commit(runId, important)
            true
        }

        /** 回退一次 [reserve]/[commit] 的占用（落地失败时调用）。 */
        fun decrement(runId: String, important: Boolean) = synchronized(lock) {
            val entry = used[runId.orSharedBucket()] ?: return
            entry.total = (entry.total - 1).coerceAtLeast(0)
            if (important) entry.important = (entry.important - 1).coerceAtLeast(0)
        }

        /** run 结束清表；重复调用幂等。 */
        fun release(runId: String) {
            used.remove(runId.orSharedBucket())
        }

        /** 当前仍在记账的 run 数（诊断/测试用）。 */
        fun activeRuns(): Int = used.size

        private fun String.orSharedBucket(): String = ifBlank { SHARED_BUCKET }

        private companion object {
            const val SHARED_BUCKET = "-"
        }
    }

    /**
     * 更新节流器：返回 true 表示这次刷新可以发出去。
     * 规则：内容相同抑制（避免无意义重绘）；距上次发出不足 [minIntervalMillis]（默认 1s）也抑制。
     * 单调时钟由调用方注入（SystemClock.elapsedRealtime / nanoTime），不受系统时间回拨影响。
     */
    class Throttle(private val minIntervalMillis: Long = MIN_REFRESH_INTERVAL_MILLIS) {
        private var lastEmitMillis: Long = Long.MIN_VALUE
        private var lastContent: String? = null
        private val lock = Any()

        fun shouldEmit(content: String, nowMillis: Long): Boolean = synchronized(lock) {
            if (content == lastContent) return false
            val elapsed = if (lastEmitMillis == Long.MIN_VALUE) Long.MAX_VALUE else nowMillis - lastEmitMillis
            if (elapsed < minIntervalMillis) return false
            lastEmitMillis = nowMillis
            lastContent = content
            true
        }

        /** 常驻通知重新开始一轮：清空，让第一条"开始"立刻可见。 */
        fun reset() = synchronized(lock) {
            lastEmitMillis = Long.MIN_VALUE
            lastContent = null
        }
    }

    private fun clamp(value: String, max: Int): String = value.trim().let { if (it.length > max) it.take(max) else it }

    /** 本文件涉及的 ID 段（唯一源 Registry），供单测/文档核对，确认不与既有段相交。 */
    fun reservedSegments(): List<IntRange> = listOf(
        proactiveSegment().range,
        APPROVAL_PENDING_ID..APPROVAL_PENDING_ID,
        jobWakeupSegment().range,
        NotificationIdRegistry.allIdSegments.first { it.owner == "run_result" }.range,
        approvalAttentionSegment().range,
    )
}
