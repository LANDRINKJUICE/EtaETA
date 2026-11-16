package io.github.mangi.eta.agent.schedule

import android.content.Context
import io.github.mangi.eta.R
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.json.JSONObject

/**
 * `schedule_task` 工具：创建、查看、启停与删除"到点自动执行的 agent 任务"。
 *
 * 一个工具多 action，和现有工具风格一致（terminal 也是 action 分发）。所有落库与排期都经过
 * [ScheduledTaskStore] 与 [ScheduledTaskScheduler]，工具层只做参数校验与人类可读回执。
 *
 * 创建本身是普通工具调用（不弹危险确认卡）：它不直接碰系统分区，真正的危险动作发生在到点
 * 执行之后，由既有的危险闸门兜底。
 */
internal object ScheduledTaskTool {
    const val NAME = "schedule_task"

    fun execute(context: Context, args: JSONObject): String {
        val appContext = context.applicationContext
        return when (val action = args.optString("action").trim().lowercase(Locale.ROOT)) {
            "create" -> create(appContext, args)
            "list", "" -> list(appContext)
            "enable" -> setEnabled(appContext, args, enabled = true)
            "disable" -> setEnabled(appContext, args, enabled = false)
            "delete", "cancel" -> delete(appContext, args)
            "run_now" -> runNow(appContext, args)
            else -> error("INVALID_ARGUMENT", "未知 action：$action（可用 create/list/enable/disable/delete/run_now）")
        }
    }

    private fun create(context: Context, args: JSONObject): String {
        val prompt = args.optString("prompt").ifBlank { args.optString("instruction") }.trim()
        if (prompt.isEmpty()) return error("INVALID_ARGUMENT", "prompt 不能为空：用一句自然语言写清到点后要做什么")
        if (prompt.length > ScheduledTask.MAX_PROMPT_CHARS) {
            return error("INVALID_ARGUMENT", "prompt 最多 ${ScheduledTask.MAX_PROMPT_CHARS} 字")
        }
        val schedule = parseSchedule(args.optJSONObject("schedule") ?: args.optJSONObject("recurrence"))
            ?: return error(
                "INVALID_ARGUMENT",
                "schedule 缺失或格式不对。示例：" +
                    "{\"kind\":\"once\",\"at\":\"2026-11-12 09:00\"}、" +
                    "{\"kind\":\"daily\",\"hour\":8,\"minute\":30}、" +
                    "{\"kind\":\"weekly\",\"hour\":9,\"minute\":0,\"days_of_week\":[1,3,5]}、" +
                    "{\"kind\":\"interval\",\"interval_minutes\":180}",
            )
        TaskScheduleValidator.validate(schedule)?.let { code ->
            return error(code, scheduleErrorText(code))
        }
        val now = System.currentTimeMillis()
        val next = TaskScheduleCalculator.nextAfter(schedule, now)
            ?: return error(
                TaskScheduleValidator.INVALID_ONCE_TIME,
                "这个触发时间已经过去了（当前 ${TaskScheduleCalculator.format(now)}），请改到未来时间",
            )
        val store = ScheduledTaskStore(context)
        val existing = store.load()
        if (existing.size >= ScheduledTask.MAX_TASKS) {
            return error("TASK_LIMIT_REACHED", "自动任务数量已达上限 ${ScheduledTask.MAX_TASKS}，请先删除不需要的任务")
        }
        val title = args.optString("title").trim().ifEmpty { ScheduledTask.defaultTitle(prompt) }
        val enabled = args.optBoolean("enabled", true)
        val task = ScheduledTask(
            id = ScheduledTask.newId(),
            title = title.take(ScheduledTask.MAX_TITLE_CHARS),
            prompt = prompt,
            schedule = schedule,
            enabled = enabled,
            nextTriggerAtMillis = if (enabled) next else 0L,
            createdAtMillis = now,
            source = ScheduledTask.SOURCE_AGENT,
        )
        if (!store.upsert(task)) return error("TASK_SAVE_FAILED", "任务保存失败，请检查存储空间后重试")
        val exactness = if (enabled) {
            ScheduledTaskScheduler(context).schedule(task.id, next)
        } else {
            ScheduledTaskScheduler.Exactness.EXACT
        }
        val created = store.find(task.id) ?: task
        return JSONObject()
            .put("ok", true)
            .put("tool", NAME)
            .put("id", created.id)
            .put("title", created.title)
            .put("enabled", created.enabled)
            .put("schedule", describeSchedule(context, created.schedule))
            .put("next_trigger", if (created.enabled) formatIso(next) else JSONObject.NULL)
            .put("next_trigger_local", if (created.enabled) TaskScheduleCalculator.format(next) else JSONObject.NULL)
            .put("alarm_accuracy", alarmAccuracyLabel(context, exactness))
            .put("unattended_note", context.getString(R.string.schedule_tool_unattended_note))
            .toString()
    }

    private fun list(context: Context): String {
        val tasks = ScheduledTaskStore(context).load()
        val array = org.json.JSONArray()
        val scheduler = ScheduledTaskScheduler(context)
        tasks.forEach { task -> array.put(snapshot(context, task, scheduler)) }
        return JSONObject()
            .put("ok", true)
            .put("tool", NAME)
            .put("count", tasks.size)
            .put("alarm_accuracy", if (scheduler.canScheduleExactAlarms()) "exact" else "inexact")
            .put("tasks", array)
            .toString()
    }

    private fun setEnabled(context: Context, args: JSONObject, enabled: Boolean): String {
        val id = args.optString("id").trim()
        if (id.isEmpty()) return error("INVALID_ARGUMENT", "id 不能为空；先用 action=list 取当前任务")
        val store = ScheduledTaskStore(context)
        val task = store.find(id) ?: return error("TASK_NOT_FOUND", "没有找到任务：$id")
        val scheduler = ScheduledTaskScheduler(context)
        if (!enabled) {
            val updated = store.setEnabled(id, false) ?: return error("TASK_SAVE_FAILED", "更新任务失败")
            store.updateNextTrigger(id, 0L)
            scheduler.cancel(id)
            return JSONObject()
                .put("ok", true)
                .put("tool", NAME)
                .put("id", id)
                .put("enabled", false)
                .put("title", updated.title)
                .toString()
        }
        val now = System.currentTimeMillis()
        val next = TaskScheduleCalculator.nextAfter(task.schedule, now)
            ?: return error(
                "NO_FUTURE_TRIGGER",
                "这条任务已经没有未来的触发点（一次性任务的时间已过），请删除后重新创建",
            )
        store.setEnabled(id, true)
        store.updateNextTrigger(id, next)
        val exactness = scheduler.schedule(id, next)
        return JSONObject()
            .put("ok", true)
            .put("tool", NAME)
            .put("id", id)
            .put("title", task.title)
            .put("enabled", true)
            .put("next_trigger", formatIso(next))
            .put("next_trigger_local", TaskScheduleCalculator.format(next))
            .put("alarm_accuracy", alarmAccuracyLabel(context, exactness))
            .toString()
    }

    private fun delete(context: Context, args: JSONObject): String {
        val id = args.optString("id").trim()
        if (id.isEmpty()) return error("INVALID_ARGUMENT", "id 不能为空；先用 action=list 取当前任务")
        val store = ScheduledTaskStore(context)
        val task = store.find(id) ?: return error("TASK_NOT_FOUND", "没有找到任务：$id")
        ScheduledTaskScheduler(context).cancel(id)
        ScheduledTaskNotifications.cancel(context, id)
        val deleted = store.delete(id)
        return JSONObject()
            .put("ok", deleted)
            .put("tool", NAME)
            .put("id", id)
            .put("title", task.title)
            .put("code", if (deleted) JSONObject.NULL else "TASK_NOT_FOUND")
            .toString()
    }

    /** 立刻执行一次，主要用于让用户/模型验证任务内容，不影响原有排期。 */
    private fun runNow(context: Context, args: JSONObject): String {
        val id = args.optString("id").trim()
        if (id.isEmpty()) return error("INVALID_ARGUMENT", "id 不能为空")
        val store = ScheduledTaskStore(context)
        val task = store.find(id) ?: return error("TASK_NOT_FOUND", "没有找到任务：$id")
        if (!task.enabled) return error("TASK_DISABLED", "任务已停用，请先 action=enable 再试")
        ScheduledTaskRunner.dispatch(context, id, task.title)
        return JSONObject()
            .put("ok", true)
            .put("tool", NAME)
            .put("id", id)
            .put("title", task.title)
            .put("message", "已在后台开始执行；结果会写入通知与任务列表")
            .toString()
    }

    private fun snapshot(
        context: Context,
        task: ScheduledTask,
        scheduler: ScheduledTaskScheduler,
    ): JSONObject = JSONObject()
        .put("id", task.id)
        .put("title", task.title)
        .put("prompt", task.prompt.take(200))
        .put("enabled", task.enabled)
        .put("schedule", describeSchedule(context, task.schedule))
        .put(
            "next_trigger",
            if (task.enabled && task.nextTriggerAtMillis > 0) formatIso(task.nextTriggerAtMillis) else JSONObject.NULL,
        )
        .put(
            "next_trigger_local",
            if (task.enabled && task.nextTriggerAtMillis > 0) {
                TaskScheduleCalculator.format(task.nextTriggerAtMillis)
            } else {
                JSONObject.NULL
            },
        )
        .put("alarm_accuracy", if (scheduler.canScheduleExactAlarms()) "exact" else "inexact")
        .put("last_outcome", task.lastOutcome.wireValue)
        .put(
            "last_run_at",
            if (task.lastRunAtMillis > 0) formatIso(task.lastRunAtMillis) else JSONObject.NULL,
        )
        .put("last_result", task.lastResult)
        .put("run_count", task.runCount)

    private fun alarmAccuracyLabel(context: Context, exactness: ScheduledTaskScheduler.Exactness): String =
        when (exactness) {
            ScheduledTaskScheduler.Exactness.EXACT -> "exact"
            ScheduledTaskScheduler.Exactness.INEXACT ->
                "inexact:" + context.getString(R.string.schedule_tool_inexact_note)
            ScheduledTaskScheduler.Exactness.FAILED ->
                "failed:" + context.getString(R.string.schedule_tool_alarm_failed_note)
        }

    private fun scheduleErrorText(code: String): String = when (code) {
        TaskScheduleValidator.INVALID_TIME_OF_DAY -> "hour 需为 0..23、minute 需为 0..59"
        TaskScheduleValidator.INVALID_DAYS_OF_WEEK -> "days_of_week 用 1=周一 … 7=周日，至少一个"
        TaskScheduleValidator.INVALID_INTERVAL ->
            "interval_minutes 需为 ${TaskSchedule.MIN_INTERVAL_MINUTES}..${TaskSchedule.MAX_INTERVAL_MINUTES}"
        else -> "触发规则不合法（时间需在未来）"
    }

    private fun error(code: String, message: String): String = JSONObject()
        .put("ok", false)
        .put("tool", NAME)
        .put("code", code)
        .put("message", message)
        .toString()

    /** 调度规则 → 给人看的一句话（跟随系统语言）。 */
    fun describeSchedule(context: Context, schedule: TaskSchedule): String = when (schedule.kind) {
        ScheduleKind.ONCE -> context.getString(
            R.string.schedule_desc_once,
            TaskScheduleCalculator.format(schedule.atMillis),
        )
        ScheduleKind.DAILY -> context.getString(
            R.string.schedule_desc_daily,
            String.format(Locale.ROOT, "%02d:%02d", schedule.hour, schedule.minute),
        )
        ScheduleKind.WEEKLY -> context.getString(
            R.string.schedule_desc_weekly,
            schedule.daysOfWeek.sorted().joinToString(context.getString(R.string.schedule_day_separator)) {
                dayName(context, it)
            },
            String.format(Locale.ROOT, "%02d:%02d", schedule.hour, schedule.minute),
        )
        ScheduleKind.INTERVAL -> {
            val minutes = schedule.intervalMinutes
            when {
                minutes % (60 * 24) == 0 ->
                    context.getString(R.string.schedule_desc_interval_days, minutes / (60 * 24))
                minutes % 60 == 0 -> context.getString(R.string.schedule_desc_interval_hours, minutes / 60)
                else -> context.getString(R.string.schedule_desc_interval_minutes, minutes)
            }
        }
    }

    private fun dayName(context: Context, dayOfWeek: Int): String = context.getString(
        when (dayOfWeek) {
            1 -> R.string.schedule_day_mon
            2 -> R.string.schedule_day_tue
            3 -> R.string.schedule_day_wed
            4 -> R.string.schedule_day_thu
            5 -> R.string.schedule_day_fri
            6 -> R.string.schedule_day_sat
            else -> R.string.schedule_day_sun
        },
    )

    private fun formatIso(millis: Long): String =
        Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX"))

    /**
     * 解析模型给的触发规则。
     *
     * `once` 同时接受 `at_millis`（毫秒时间戳）与 `at`（本地时间字符串）；字符串格式刻意收得比较紧，
     * 解析失败一律当"规则非法"返回，不做模糊猜测——猜错时间比报错代价高得多。
     */
    private fun parseSchedule(json: JSONObject?): TaskSchedule? {
        if (json == null) return null
        val kind = ScheduleKind.fromWireValue(json.optString("kind")) ?: return null
        return when (kind) {
            ScheduleKind.ONCE -> {
                val atMillis = json.optLong("at_millis", 0L).takeIf { it > 0L }
                    ?: parseLocalDateTime(json.optString("at").trim())
                    ?: return null
                TaskSchedule(kind = kind, atMillis = atMillis)
            }
            ScheduleKind.DAILY -> TaskSchedule(
                kind = kind,
                hour = json.optInt("hour", -1),
                minute = json.optInt("minute", -1),
            )
            ScheduleKind.WEEKLY -> TaskSchedule(
                kind = kind,
                hour = json.optInt("hour", -1),
                minute = json.optInt("minute", -1),
                daysOfWeek = buildSet {
                    val array = json.optJSONArray("days_of_week")
                    for (index in 0 until (array?.length() ?: 0)) {
                        val day = array!!.optInt(index, 0)
                        if (day in 1..7) add(day)
                    }
                },
            )
            ScheduleKind.INTERVAL -> TaskSchedule(
                kind = kind,
                intervalMinutes = json.optInt("interval_minutes", 0),
            )
        }
    }

    private val LOCAL_DATE_TIME_PATTERNS = listOf(
        "yyyy-MM-dd HH:mm",
        "yyyy-MM-dd'T'HH:mm",
        "yyyy-MM-dd HH:mm:ss",
        "yyyy-MM-dd'T'HH:mm:ss",
        "yyyy/MM/dd HH:mm",
        "MM-dd HH:mm",
    )

    private fun parseLocalDateTime(text: String): Long? {
        if (text.isEmpty()) return null
        // 带时区偏移的 ISO 字符串优先按偏移解析，避免把 09:00+08:00 当成设备时区的 09:00。
        runCatching { return OffsetDateTime.parse(text).toInstant().toEpochMilli() }
        runCatching { return Instant.parse(text).toEpochMilli() }
        val zone = ZoneId.systemDefault()
        val now = LocalDateTime.now(zone)
        LOCAL_DATE_TIME_PATTERNS.forEach { pattern ->
            val parsed = runCatching {
                LocalDateTime.parse(text, DateTimeFormatter.ofPattern(pattern))
            }.getOrNull()
            if (parsed != null) {
                // MM-dd HH:mm 这种不写年份的格式，落到今年；已经过去则顺延到明年。
                val resolved = if (parsed.year == 1970) parsed.withYear(now.year) else parsed
                val candidate = when {
                    parsed.year != 1970 -> resolved
                    resolved.isAfter(now) -> resolved
                    else -> resolved.plusYears(1)
                }
                return candidate.atZone(zone).toInstant().toEpochMilli()
            }
        }
        return null
    }
}
