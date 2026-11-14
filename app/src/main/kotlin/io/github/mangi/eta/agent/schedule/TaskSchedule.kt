package io.github.mangi.eta.agent.schedule

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/**
 * 自动任务的调度规则类型。
 *
 * wireValue 会被写入持久化文件，改名等于丢弃用户已有任务，不要随意调整。
 */
internal enum class ScheduleKind(val wireValue: String) {
    /** 只在 atMillis 触发一次。 */
    ONCE("once"),

    /** 每天 hour:minute 触发（本地时区）。 */
    DAILY("daily"),

    /** 每周 daysOfWeek 里的星期几触发 hour:minute（本地时区）。 */
    WEEKLY("weekly"),

    /** 每 intervalMinutes 分钟触发一次；以"上次计划触发时间"为锚点推进，不随执行耗时漂移。 */
    INTERVAL("interval");

    companion object {
        fun fromWireValue(value: String?): ScheduleKind? =
            entries.firstOrNull { it.wireValue == value?.trim()?.lowercase(Locale.ROOT) }
    }
}

/**
 * 一条任务的调度规则。
 *
 * 字段并非全部类型都使用：ONCE 用 [atMillis]，DAILY/WEEKLY 用 [hour]/[minute]（WEEKLY 另用
 * [daysOfWeek]），INTERVAL 用 [intervalMinutes]。未使用字段保持默认值，编解码时按类型只写出
 * 相关字段，避免同一个值被两种含义解读。
 */
internal data class TaskSchedule(
    val kind: ScheduleKind,
    val atMillis: Long = 0L,
    val hour: Int = 0,
    val minute: Int = 0,
    /** java.time.DayOfWeek.value：1=周一 … 7=周日。 */
    val daysOfWeek: Set<Int> = emptySet(),
    val intervalMinutes: Int = 0,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("kind", kind.wireValue)
        when (kind) {
            ScheduleKind.ONCE -> put("at_millis", atMillis)
            ScheduleKind.DAILY -> {
                put("hour", hour)
                put("minute", minute)
            }
            ScheduleKind.WEEKLY -> {
                put("hour", hour)
                put("minute", minute)
                put("days_of_week", JSONArray().also { array -> daysOfWeek.sorted().forEach(array::put) })
            }
            ScheduleKind.INTERVAL -> put("interval_minutes", intervalMinutes)
        }
    }

    companion object {
        const val MIN_INTERVAL_MINUTES = 1
        const val MAX_INTERVAL_MINUTES = 60 * 24 * 30

        /** 容忍缺字段与越界值：越界返回 null，交给调用方按"非法规则"处理。 */
        fun fromJson(json: JSONObject): TaskSchedule? {
            val kind = ScheduleKind.fromWireValue(json.optString("kind")) ?: return null
            return when (kind) {
                ScheduleKind.ONCE -> {
                    val atMillis = json.optLong("at_millis", 0L)
                    if (atMillis <= 0L) null else TaskSchedule(kind = kind, atMillis = atMillis)
                }
                ScheduleKind.DAILY, ScheduleKind.WEEKLY -> {
                    val hour = json.optInt("hour", -1)
                    val minute = json.optInt("minute", -1)
                    if (hour !in 0..23 || minute !in 0..59) return null
                    val days = if (kind == ScheduleKind.WEEKLY) {
                        val array = json.optJSONArray("days_of_week")
                        val parsed = buildSet {
                            for (index in 0 until (array?.length() ?: 0)) {
                                val day = array!!.optInt(index, 0)
                                if (day in 1..7) add(day)
                            }
                        }
                        if (parsed.isEmpty()) return null
                        parsed
                    } else {
                        emptySet()
                    }
                    TaskSchedule(kind = kind, hour = hour, minute = minute, daysOfWeek = days)
                }
                ScheduleKind.INTERVAL -> {
                    val minutes = json.optInt("interval_minutes", 0)
                    if (minutes !in MIN_INTERVAL_MINUTES..MAX_INTERVAL_MINUTES) return null
                    TaskSchedule(kind = kind, intervalMinutes = minutes)
                }
            }
        }
    }
}

/** 规则合法性检查；返回可展示给模型的错误码，null 表示合法。 */
internal object TaskScheduleValidator {
    const val INVALID_ONCE_TIME = "INVALID_ONCE_TIME"
    const val INVALID_TIME_OF_DAY = "INVALID_TIME_OF_DAY"
    const val INVALID_DAYS_OF_WEEK = "INVALID_DAYS_OF_WEEK"
    const val INVALID_INTERVAL = "INVALID_INTERVAL"

    fun validate(schedule: TaskSchedule): String? = when (schedule.kind) {
        ScheduleKind.ONCE ->
            if (schedule.atMillis <= 0L) INVALID_ONCE_TIME else null
        ScheduleKind.DAILY ->
            if (schedule.hour !in 0..23 || schedule.minute !in 0..59) INVALID_TIME_OF_DAY else null
        ScheduleKind.WEEKLY -> when {
            schedule.hour !in 0..23 || schedule.minute !in 0..59 -> INVALID_TIME_OF_DAY
            schedule.daysOfWeek.isEmpty() || schedule.daysOfWeek.any { it !in 1..7 } -> INVALID_DAYS_OF_WEEK
            else -> null
        }
        ScheduleKind.INTERVAL ->
            if (schedule.intervalMinutes !in
                TaskSchedule.MIN_INTERVAL_MINUTES..TaskSchedule.MAX_INTERVAL_MINUTES
            ) {
                INVALID_INTERVAL
            } else {
                null
            }
    }
}

/**
 * 下次触发时间计算。
 *
 * 全部是纯函数：给定时钟、时区与锚点，不读取系统状态，便于单元测试覆盖跨天、跨周与夏令时。
 * 返回 null 表示这条规则已经没有未来触发点（一次性任务已过期）。
 */
internal object TaskScheduleCalculator {
    private const val ONE_DAY_MILLIS = 24L * 60L * 60L * 1000L

    /** 首次排期：从 [fromMillis] 之后最早的一次。 */
    fun nextAfter(
        schedule: TaskSchedule,
        fromMillis: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Long? = when (schedule.kind) {
        ScheduleKind.ONCE -> schedule.atMillis.takeIf { it > fromMillis }
        ScheduleKind.INTERVAL -> fromMillis + schedule.intervalMinutes * 60_000L
        ScheduleKind.DAILY, ScheduleKind.WEEKLY -> nextWallClock(
            schedule = schedule,
            fromMillis = fromMillis,
            zone = zone,
        )
    }

    /**
     * 重复任务的下一轮：以"上一次计划触发时间"为锚点推进，设备关机或长时间无人值守后
     * 只落在最近的未来时刻，不补跑遗留轮次。
     *
     * @param previousTriggerMillis 上一次计划触发时间；INTERVAL 需要它来保持节奏。
     * @param nowMillis 当前时间；计算结果的严格下界。
     */
    fun nextRun(
        schedule: TaskSchedule,
        previousTriggerMillis: Long,
        nowMillis: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Long? = when (schedule.kind) {
        ScheduleKind.ONCE -> schedule.atMillis.takeIf { it > nowMillis }
        ScheduleKind.INTERVAL -> nextIntervalSlot(
            schedule = schedule,
            anchorMillis = if (previousTriggerMillis > 0L) previousTriggerMillis else nowMillis,
            nowMillis = nowMillis,
        )
        ScheduleKind.DAILY, ScheduleKind.WEEKLY -> nextWallClock(
            schedule = schedule,
            fromMillis = nowMillis,
            zone = zone,
        )
    }

    /** 一次性任务过期多久之内仍然补跑；再久就当作错过，避免开机后突然执行几天前的指令。 */
    const val ONCE_CATCH_UP_WINDOW_MILLIS = ONE_DAY_MILLIS

    /**
     * 间隔任务的下一轮：`candidate = anchor + (k+1)·step`，其中 `k = floor((now-anchor)\/step)`。
     *
     * 用算术跳跃而不是循环追赶：设备关机一个月之后，一分钟间隔的任务也只做一次除法，
     * 既不会因为步数上限被"静默判死"（旧实现的 1000 步上限），也不会产生累积误差。
     * anchor 在未来（时钟被调回）时 k 为负：结果仍是**严格晚于 now 的、与 anchor 对齐的最近时刻**，
     * 它可能落在 anchor 之前（例如 anchor=10:00、now=09:00、步长 30 分钟 → 09:30）。
     */
    private fun nextIntervalSlot(
        schedule: TaskSchedule,
        anchorMillis: Long,
        nowMillis: Long,
    ): Long? {
        val step = schedule.intervalMinutes * 60_000L
        if (step <= 0L) return null
        if (anchorMillis <= 0L) return nowMillis + step
        val k = Math.floorDiv(nowMillis - anchorMillis, step)
        var candidate = anchorMillis + (k + 1) * step
        // 溢出或极端时钟跳变时的兜底：保证严格晚于 now，并且仍然对齐 anchor。
        while (candidate <= nowMillis) candidate += step
        return candidate
    }

    private fun nextWallClock(
        schedule: TaskSchedule,
        fromMillis: Long,
        zone: ZoneId,
    ): Long? {
        if (schedule.hour !in 0..23 || schedule.minute !in 0..59) return null
        val searchDays = if (schedule.kind == ScheduleKind.WEEKLY) 8 else 2
        val today = Instant.ofEpochMilli(fromMillis).atZone(zone).toLocalDate()
        for (offset in 0..searchDays) {
            val date = today.plusDays(offset.toLong())
            if (schedule.kind == ScheduleKind.WEEKLY && date.dayOfWeek.value !in schedule.daysOfWeek) continue
            val candidateMillis = LocalDateTime.of(date, java.time.LocalTime.of(schedule.hour, schedule.minute))
                .atZone(zone)
                .toInstant()
                .toEpochMilli()
            if (candidateMillis > fromMillis) return candidateMillis
        }
        return null
    }

    /** ISO-8601 本地时间，用于工具结果与通知副标题；不参与业务判断。 */
    fun format(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(millis).atZone(zone)
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
}
