package io.github.mangi.eta.agent.schedule

import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** 最近一次执行的结果分类；wireValue 写入持久化文件。 */
internal enum class TaskRunOutcome(val wireValue: String) {
    /** 从未执行过。 */
    NEVER("never"),

    /** 运行完成且模型正常返回。 */
    OK("ok"),

    /** 运行失败（模型错误、Runtime 不可用、异常）。 */
    FAILED("failed"),

    /** 触发时任务已被删除或已禁用，本次没有执行。 */
    SKIPPED("skipped");

    companion object {
        fun fromWireValue(value: String?): TaskRunOutcome =
            entries.firstOrNull { it.wireValue == value?.trim()?.lowercase(java.util.Locale.ROOT) } ?: NEVER
    }
}

/**
 * 一条自动任务。
 *
 * 指令正文是自然语言 prompt，到点后原样作为一次 agent run 的用户消息执行；这里不解析、
 * 不改写，避免把"用户想说的话"变成模板。
 */
internal data class ScheduledTask(
    val id: String,
    val title: String,
    val prompt: String,
    val schedule: TaskSchedule,
    val enabled: Boolean = true,
    /** 下一次计划触发时间（毫秒时间戳）；0 表示尚未排期或已无未来触发点。 */
    val nextTriggerAtMillis: Long = 0L,
    val createdAtMillis: Long = 0L,
    val lastRunAtMillis: Long = 0L,
    val lastOutcome: TaskRunOutcome = TaskRunOutcome.NEVER,
    /** 最近一次执行结果摘要（单行、截断），用于列表与通知。 */
    val lastResult: String = "",
    val lastDurationMillis: Long = 0L,
    val runCount: Int = 0,
    /**
     * 本轮到点执行的"认领"时间。
     *
     * 到点后先把 next_trigger 推进/清零再派发执行；一次性任务没有下一轮可推进，
     * 因此用这个时间戳标记"这一轮已经被认领"，让重复广播（开机补跑与闹钟成对到达）
     * 无法把同一个任务执行两遍。执行结束写回结果时清零。
     */
    val claimAtMillis: Long = 0L,
    /** 创建来源：agent / ui，仅供展示与排查。 */
    val source: String = SOURCE_AGENT,
) {
    val hasFutureTrigger: Boolean get() = nextTriggerAtMillis > 0L

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("prompt", prompt)
        put("schedule", schedule.toJson())
        put("enabled", enabled)
        put("next_trigger_at", nextTriggerAtMillis)
        put("created_at", createdAtMillis)
        put("last_run_at", lastRunAtMillis)
        put("last_outcome", lastOutcome.wireValue)
        put("last_result", lastResult)
        put("last_duration_ms", lastDurationMillis)
        put("run_count", runCount)
        put("claim_at", claimAtMillis)
        put("source", source)
    }

    companion object {
        const val SOURCE_AGENT = "agent"
        const val SOURCE_UI = "ui"

        const val MAX_TASKS = 50
        const val MAX_TITLE_CHARS = 120
        const val MAX_PROMPT_CHARS = 4_000
        const val MAX_RESULT_CHARS = 500

        fun newId(): String = UUID.randomUUID().toString()

        /** 容忍缺字段：坏记录返回 null，由调用方跳过，不用一条坏数据废掉整个文件。 */
        fun fromJson(json: JSONObject): ScheduledTask? {
            val id = json.optString("id").trim()
            if (id.isEmpty() || id.length > 64) return null
            val prompt = json.optString("prompt").trim()
            if (prompt.isEmpty()) return null
            val scheduleJson = json.optJSONObject("schedule") ?: return null
            val schedule = TaskSchedule.fromJson(scheduleJson) ?: return null
            val title = json.optString("title").trim().ifEmpty { defaultTitle(prompt) }
            return ScheduledTask(
                id = id,
                title = title.take(MAX_TITLE_CHARS),
                prompt = prompt.take(MAX_PROMPT_CHARS),
                schedule = schedule,
                enabled = json.optBoolean("enabled", true),
                nextTriggerAtMillis = json.optLong("next_trigger_at", 0L).coerceAtLeast(0L),
                createdAtMillis = json.optLong("created_at", 0L).coerceAtLeast(0L),
                lastRunAtMillis = json.optLong("last_run_at", 0L).coerceAtLeast(0L),
                lastOutcome = TaskRunOutcome.fromWireValue(json.optString("last_outcome")),
                lastResult = json.optString("last_result").take(MAX_RESULT_CHARS),
                lastDurationMillis = json.optLong("last_duration_ms", 0L).coerceAtLeast(0L),
                runCount = json.optInt("run_count", 0).coerceAtLeast(0),
                claimAtMillis = json.optLong("claim_at", 0L).coerceAtLeast(0L),
                source = json.optString("source").ifBlank { SOURCE_AGENT },
            )
        }

        /** 没有标题时用指令正文首行做标题，避免列表里出现一排空名字。 */
        fun defaultTitle(prompt: String): String =
            prompt.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(MAX_TITLE_CHARS) ?: ""

        /** 结果摘要：压成单行并截断；过长时保留开头，模型正文开头通常已经是结论。 */
        fun summarizeResult(content: String): String {
            val single = content.replace(Regex("\\s+"), " ").trim()
            if (single.length <= MAX_RESULT_CHARS) return single
            return single.take(MAX_RESULT_CHARS - 1) + "…"
        }
    }
}

/** 任务列表的文件编解码；与文件 IO 分开，便于纯逻辑测试。 */
internal object ScheduledTaskCodec {
    fun encode(tasks: List<ScheduledTask>): String =
        JSONObject()
            .put("version", 1)
            .put("tasks", JSONArray().also { array -> tasks.forEach { array.put(it.toJson()) } })
            .toString(2)

    /** 逐条解码：坏记录跳过并计数，不抛异常（文件可能来自旧版本或被人工编辑）。 */
    fun decode(text: String): List<ScheduledTask> = decodeWithReport(text).tasks

    data class Report(val tasks: List<ScheduledTask>, val skipped: Int)

    fun decodeWithReport(text: String): Report {
        if (text.isBlank()) return Report(emptyList(), 0)
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return Report(emptyList(), 0)
        val array = root.optJSONArray("tasks") ?: return Report(emptyList(), 0)
        val tasks = mutableListOf<ScheduledTask>()
        var skipped = 0
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index)
            val task = item?.let(ScheduledTask::fromJson)
            if (task == null) skipped++ else tasks += task
        }
        return Report(tasks.distinctBy { it.id }.take(ScheduledTask.MAX_TASKS), skipped)
    }
}
