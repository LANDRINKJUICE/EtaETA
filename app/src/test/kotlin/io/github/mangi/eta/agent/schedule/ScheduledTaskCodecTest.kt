package io.github.mangi.eta.agent.schedule

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 任务模型与文件编解码的回归测试。
 *
 * 存储文件可能被旧版本写入、被人工编辑、或保存到一半被杀；解码必须逐条容错，
 * 不能因为一条坏记录丢掉整张表。摘要与标题的收口同样在这里钉住。
 */
class ScheduledTaskCodecTest {
    private fun onceTask(id: String = "t1", atMillis: Long = 1_800_000_000_000L) = ScheduledTask(
        id = id,
        title = "Morning report",
        prompt = "Summarize my notifications",
        schedule = TaskSchedule(kind = ScheduleKind.ONCE, atMillis = atMillis),
        enabled = true,
        nextTriggerAtMillis = atMillis,
        createdAtMillis = 1_700_000_000_000L,
    )

    @Test
    fun `round trips a full task`() {
        val task = onceTask().copy(
            lastRunAtMillis = 1_790_000_000_000L,
            lastOutcome = TaskRunOutcome.FAILED,
            lastResult = "模型调用失败",
            lastDurationMillis = 4_200L,
            runCount = 3,
            source = ScheduledTask.SOURCE_UI,
        )
        val decoded = ScheduledTask.fromJson(JSONObject(task.toJson().toString()))
        assertEquals(task, decoded)
    }

    @Test
    fun `encodes and decodes the whole list`() {
        val tasks = listOf(
            onceTask("a"),
            ScheduledTask(
                id = "b",
                title = "Weekday digest",
                prompt = "digest",
                schedule = TaskSchedule(
                    kind = ScheduleKind.WEEKLY,
                    hour = 8,
                    minute = 5,
                    daysOfWeek = setOf(1, 3, 5),
                ),
            ),
            ScheduledTask(
                id = "c",
                title = "Ping",
                prompt = "ping",
                schedule = TaskSchedule(kind = ScheduleKind.INTERVAL, intervalMinutes = 90),
            ),
        )
        val text = ScheduledTaskCodec.encode(tasks)
        assertEquals(tasks, ScheduledTaskCodec.decode(text))
    }

    @Test
    fun `bad records are skipped without losing the good ones`() {
        val good = ScheduledTaskCodec.encode(listOf(onceTask("keep")))
        val root = JSONObject(good)
        val array = root.getJSONArray("tasks")
        array.put(JSONObject().put("id", "missing-prompt").put("schedule", JSONObject().put("kind", "once").put("at_millis", 1L)))
        array.put(JSONObject().put("id", "bad-schedule").put("prompt", "x").put("schedule", JSONObject().put("kind", "daily").put("hour", 99)))
        val report = ScheduledTaskCodec.decodeWithReport(root.toString())
        assertEquals(1, report.tasks.size)
        assertEquals("keep", report.tasks.first().id)
        assertEquals(2, report.skipped)
    }

    @Test
    fun `blank and malformed files decode to an empty list`() {
        assertTrue(ScheduledTaskCodec.decode("").isEmpty())
        assertTrue(ScheduledTaskCodec.decode("not json").isEmpty())
        assertTrue(ScheduledTaskCodec.decode("{}").isEmpty())
    }

    @Test
    fun `duplicate ids collapse to the first record`() {
        val text = ScheduledTaskCodec.encode(listOf(onceTask("dup"), onceTask("dup")))
        assertEquals(1, ScheduledTaskCodec.decode(text).size)
    }

    @Test
    fun `title falls back to the first non blank prompt line`() {
        val json = JSONObject(onceTask().toJson().toString()).put("title", "").put("prompt", "\n\n  清理下载目录  \n然后汇报")
        val decoded = ScheduledTask.fromJson(json)
        assertEquals("清理下载目录", decoded?.title)
    }

    @Test
    fun `record without id or prompt is rejected`() {
        assertNull(ScheduledTask.fromJson(JSONObject().put("prompt", "x").put("schedule", JSONObject().put("kind", "interval").put("interval_minutes", 5))))
        assertNull(ScheduledTask.fromJson(JSONObject().put("id", "x").put("schedule", JSONObject().put("kind", "interval").put("interval_minutes", 5))))
    }

    @Test
    fun `unknown outcome decodes as never`() {
        val json = JSONObject(onceTask().toJson().toString()).put("last_outcome", "something-else")
        assertEquals(TaskRunOutcome.NEVER, ScheduledTask.fromJson(json)?.lastOutcome)
    }

    @Test
    fun `summarize keeps a single bounded line`() {
        val long = "第一行\n\n" + "x".repeat(ScheduledTask.MAX_RESULT_CHARS * 2)
        val summary = ScheduledTask.summarizeResult(long)
        assertFalse(summary.contains("\n"))
        assertTrue(summary.length <= ScheduledTask.MAX_RESULT_CHARS)
        assertTrue(summary.endsWith("…"))
    }

    @Test
    fun `disabled once task keeps history but has no next trigger`() {
        val task = onceTask().copy(enabled = false, nextTriggerAtMillis = 0L, lastOutcome = TaskRunOutcome.OK)
        val decoded = ScheduledTask.fromJson(JSONObject(task.toJson().toString()))
        assertFalse(decoded!!.enabled)
        assertFalse(decoded.hasFutureTrigger)
        assertEquals(TaskRunOutcome.OK, decoded.lastOutcome)
    }
}
