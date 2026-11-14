package io.github.mangi.eta.agent.notify

/**
 * 全 App **通知 ID / PendingIntent requestCode 段的唯一登记表**（纯逻辑，可在 JVM 单测）。
 *
 * ## 为什么存在（治「散乱」的第一块地基）
 * 改造前，通知 ID 段散落在 `NotificationPolicy` 的注释里、channel 与 `manager.notify` 散在
 * 8 个文件、requestCode 分散硬编码，跨模块没有任何自动校验：新功能加一条通知要自己读注释
 * 避开既有段，撞了也没人发现。这正是一份状态快照 / 一个编排器之前最该先收敛的「约定」。
 *
 * 本表把**所有**已知段连所有者一起登记成数据，`[validate]` 能在纯 JVM 单测里挡住任何
 * 新增段与既有段相交。新功能发通知 → 必须先在 [NotificationChannel] / [IdSegment] 里登记，
 * 跑基线时冲突会直接红灯；`grep` 不到第二个 `manager.notify` 模板可抄（见架构 SOP）。
 *
 * ## 维护铁律（给未来加功能的 Agent）
 * 1. 任何新通知：先在 [allIdSegments] 加一段（带唯一 owner + 语义），单测 `validate()` 会检查
 *    与所有既有段不相交；**不要**只在别的文件里 `manager.notify(硬编码ID)` 了事。
 * 2. 段一旦分配**永不复用 / 不改值**：ID 或 requestCode 改了，用户已设的每-channel 通知设置、
 *    以及 FLAG_UPDATE_CURRENT 的 PendingIntent 记录都会错位。
 * 3. 本表只管「号段不冲突」，不管「该不该打扰 / 用哪个 channel」——那是编排器（AttentionDirector，
 *    见 `文档-Docs/通知与状态层架构SOP.md`）的职责；本表是它脚下最硬的一块地基。
 * 4. 本文件**不引用 android.\* / R.\***，保证能进纯 JVM 基线 `jobwake/run.sh`。
 *
 * 数据来源（逐项核对现网发起点，2026-11-13）：见每个 [IdSegment] 的 owner / 备注。
 */
internal object NotificationIdRegistry {

    /** 一条已登记的号段：owner 必须唯一，便于冲突时一眼看出是谁和谁撞了。 */
    data class IdSegment(
        val owner: String,
        val range: IntRange,
        val note: String = "",
    )

    /** job_wakeup 通知段的 owner；清扫僵尸通知与登记表共用，避免字面量漂移。 */
    const val OWNER_JOB_WAKEUP = "job_wakeup"

    /** 通知 channel 语义分类表：新增 channel 前先查这里，避免随手复用语义不匹配的通道。 */
    enum class NotificationChannel(
        val id: String,
        val importance: String,
        val meaning: String,
    ) {
        EXECUTION("eta_execution", "LOW", "Agent run 执行进行中（含 promoted 流体云）"),
        RUN_RESULTS("eta_run_results", "DEFAULT", "run 完成 / 失败的结果"),
        PROACTIVE("eta_proactive", "DEFAULT", "notify_user 普通主动沟通 + 待确认汇总"),
        ATTENTION("eta_attention", "HIGH+sound", "真正紧急 / 审批卡 heads-up 行动通知"),
        SCHEDULED_TASKS("scheduled_tasks", "DEFAULT", "自动任务进行中 / 结果"),
        BACKGROUND_COMMANDS("eta_background_commands", "DEFAULT", "后台命令结果 / 任务失败自动唤醒（含运行中的常驻阶段通知；被关时降级到 eta_proactive）"),
        MACRO_RECORDING("macro_recording", "LOW", "宏录制进行中 / 结果"),
        ASSISTANT_SPEECH("eta_assistant_speech", "LOW", "语音助手聆听 / 朗读浮窗"),
        READ_ALOUD("eta_read_aloud", "LOW", "聊天朗读播放"),
    }

    /**
     * 全 App 通知 ID 段登记表（含其它模块的历史段）。顺序无意义；[validate] 只关心两两不相交。
     *
     * 注意：`NotificationPolicy.reservedSegments()` 只返回它自己管理的段（当前 5 段，随新功能递增），
     * 全量核对走 [allIdSegments] / [validate]，二者不互相替代。
     */
    val allIdSegments: List<IdSegment> = listOf(
        IdSegment("execution_service", 1_107..1_107, "1107 执行进行中（结果通知已迁至 run_result 段）"),
        IdSegment("background_command", 1_124..1_124, "后台命令结果，带 tag=task.id 隔离"),
        IdSegment("voice", 4_203..4_204, "4203 语音浮窗 / 4204 朗读播放"),
        IdSegment("scheduled_task_running", 9_000..9_000, "自动任务执行中常驻单条"),
        IdSegment("scheduled_task_result", 9_001..9_290, "自动任务结果，按 taskId 散列"),
        IdSegment("notify_user_proactive", 9_300..9_349, "notify_user 主动沟通，按 key 散列"),
        IdSegment("approval_pending", 9_400..9_400, "N 个操作待确认汇总单条"),
        IdSegment("reserved_9500", 9_500..9_501, "预留，暂未使用"),
        IdSegment("run_result", 9_502..9_560, "run 结果通知，按 conversationId 散列（多会话不互相覆盖）"),
        IdSegment("macro_recording", 9_600..9_601, "9600 录制中 / 9601 录制结果"),
        IdSegment(OWNER_JOB_WAKEUP, 9_602..9_651, "后台任务失败自动唤醒，按 jobId 散列"),
        IdSegment("approval_attention", 9_660..9_999, "审批卡行动通知，占用检测分配"),
    )

    /** PendingIntent requestCode 段登记表（同样全局唯一，避免 FLAG_UPDATE_CURRENT 互相覆盖）。 */
    val allRequestCodeSegments: List<IdSegment> = listOf(
        IdSegment("approval_content", 6_001..6_001, "审批通知主体"),
        IdSegment("approval_action", 6_002..6_002, "审批通知去确认"),
        IdSegment("scheduled_content", 6_010..6_010, "自动任务通知主体"),
        IdSegment("background_command_content", 6_020..6_020, "后台命令通知主体"),
        IdSegment("assistant_speech_stop", 6_030..6_030, "语音停止 action"),
        IdSegment("job_wakeup_content", 6_200..6_249, "自动唤醒进会话，按任务分散"),
        IdSegment("job_wakeup_action", 6_250..6_299, "自动唤醒进任务中心，按任务分散"),
        IdSegment("approval_decision", 6_400..6_699, "审批按钮，按 (审批id,选项id) 散列"),
        IdSegment("notify_user_reply", 6_700..6_749, "notify_user 通知栏回复 RemoteInput action，按通知 ID 分散"),
        // showProactive 的 contentIntent / 任务中心副按钮直接用 spec.notificationId 当 requestCode（9300..9349）。
        IdSegment("notify_user_content", 9_300..9_349, "notify_user 通知主体/副按钮，借用通知 ID 作 requestCode"),
        // 下列各模块用的是裸常量 / 单值，不是连续段；列在此处作为「已占用点」登记，
        // 提醒新功能连这些点也不要复用，且 PendingIntent 必须自带唯一 data URI 防覆盖。
        // execution_service_codes 的 0 还被 ReadAloud open、Macro 录制/结果 contentIntent 裸用：
        // 当前三者 Intent 的 action/component/data 全相同、filterEquals 相等，是同一个 PendingIntent
        // 对象（今天都只是「打开 MainActivity 无 extras」所以无感）；谁先加 extra 就会静默互相覆盖，
        // 这类改动必须先分段，登记表正是把这个存量事实记下来的地方。
        IdSegment("execution_service_codes", 0..1, "1107 open(0)/stop(1)；0 亦被朗读/宏录制裸用"),
        IdSegment("run_result_code", 20..20, "run 结果通知 open（UI/归档/任务中心共用，靠 data+action 区分）"),
        IdSegment("run_result_task_center", 9_503..9_561, "run 结果通知「打开任务中心」按钮，按 notificationId+1 分散"),
        IdSegment("runtime_result_open", 1_108..1_108, "运行时结果浮窗 open（AgentRuntimeService.RESULT_OPEN_REQUEST_CODE）"),
        IdSegment("voice_handoff", 4_543_553..4_543_553, "语音交接前台会话（0x455441）"),
        IdSegment("macro_codes", 9_600..9_601, "宏录制通知的停止/结果（与通知 ID 同数值不同空间）"),
        // 不进段表：ScheduledTaskScheduler.requestCodeFor 把随机 UUID 散列到 31 位（0..2^31-1），
        // 靠 Intent 自带唯一 data URI 区分，不与任何固定段冲突；别把它误登记成一个巨大段。
    )

    /**
     * 校验号段两两不相交。返回冲突明细列表（空 = 干净）。
     * 通知 ID 与 requestCode 是**两个独立空间**，分别校验、不互相比较（1108 与 rc=1108 不冲突）。
     * 用双重循环即可：段数是个位数，不需要区间树这种过度设计。
     */
    fun conflictsInSpace(segments: List<IdSegment>): List<String> {
        val found = mutableListOf<String>()
        for (i in segments.indices) {
            for (j in i + 1 until segments.size) {
                val a = segments[i]
                val b = segments[j]
                if (a.range.first <= b.range.last && b.range.first <= a.range.last) {
                    found += "${a.owner}(${a.range}) 与 ${b.owner}(${b.range}) 相交"
                }
            }
        }
        return found
    }

    /** 全量冲突 = 通知 ID 空间冲突 + requestCode 空间冲突。 */
    fun conflicts(): List<String> =
        conflictsInSpace(allIdSegments) + conflictsInSpace(allRequestCodeSegments)

    /** 单测/诊断入口：true 表示登记表干净，false 表示有段冲突（conflicts() 给出明细）。 */
    fun validate(): Boolean = conflicts().isEmpty()

    /** 查某个通知 ID 归谁（诊断用）；未登记返回 null。 */
    fun ownerOfNotificationId(id: Int): String? =
        allIdSegments.firstOrNull { id in it.range }?.owner

    /** 新功能加段前自查：这段是否完全空闲（不与任何已登记段相交）。 */
    fun isNotificationIdRangeFree(range: IntRange): Boolean =
        allIdSegments.none { range.first <= it.range.last && it.range.first <= range.last }
}
