package io.github.mangi.eta.agent.schedule

import java.time.ZoneId

/**
 * "这一轮到点了，接下来怎么排"的纯决策。抽出来单独测试，也让 Receiver 与 Runner 共用同一套规则。
 *
 * 关键约束（对应审查 B1 / M2）：
 * - **排期必须在派发之前完成**。旧实现在 execute() 的并发闸门之后才推进下一次触发，
 *   一旦闹钟已被系统消耗、而这次执行又被闸门跳过，任务就再也不会被排期（永久停摆）。
 *   现在 Receiver 收到闹钟后先 [decide] 并把结果落盘/挂闹钟，再决定是否派发执行。
 * - **一次性任务要先认领再执行**。一次性任务没有"下一轮"可推进，重复广播（开机补跑与
 *   闹钟成对到达、或补跑与手工 run_now 叠加）会把同一条指令执行两遍；因此第一次收到时
 *   写 claim 时间戳，认领窗口内的第二次直接判定为"已认领"。
 */
internal object ScheduledTaskAdvance {
    /** 认领窗口：窗口内的重复广播视为同一轮。窗口过期后（例如进程死在执行中途）允许重跑。 */
    const val ONCE_CLAIM_WINDOW_MILLIS = 15L * 60L * 1_000L

    sealed interface Decision {
        /** 需要执行；[nextTriggerAtMillis] 为写回的下一轮时间（0 表示没有下一轮）。 */
        data class Run(val nextTriggerAtMillis: Long) : Decision

        /** 已在认领窗口内被认领过：本次不执行，也不改动任何状态。 */
        data object AlreadyClaimed : Decision

        /** 任务已删除或已停用：取消闹钟即可。 */
        data object Skip : Decision
    }

    fun decide(
        task: ScheduledTask,
        nowMillis: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Decision {
        if (!task.enabled) return Decision.Skip
        return when (task.schedule.kind) {
            ScheduleKind.ONCE -> decideOnce(task, nowMillis)
            ScheduleKind.DAILY, ScheduleKind.WEEKLY, ScheduleKind.INTERVAL -> {
                val next = TaskScheduleCalculator.nextRun(
                    schedule = task.schedule,
                    previousTriggerMillis = task.nextTriggerAtMillis,
                    nowMillis = nowMillis,
                    zone = zone,
                )
                Decision.Run(nextTriggerAtMillis = next ?: 0L)
            }
        }
    }

    private fun decideOnce(task: ScheduledTask, nowMillis: Long): Decision {
        if (task.nextTriggerAtMillis > 0L) {
            // 正常路径：闹钟到点。一次性任务没有下一轮，写回 0 并由调用方写入认领时间。
            return Decision.Run(nextTriggerAtMillis = 0L)
        }
        // next_trigger 已经是 0：说明这一轮已经被认领（补跑派发或上一次广播）。
        val claimedRecently = task.claimAtMillis > 0L &&
            nowMillis - task.claimAtMillis in 0 until ONCE_CLAIM_WINDOW_MILLIS
        return if (claimedRecently) Decision.AlreadyClaimed else Decision.Run(nextTriggerAtMillis = 0L)
    }
}
