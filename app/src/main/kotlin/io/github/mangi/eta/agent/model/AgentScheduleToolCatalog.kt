package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/** 自动任务工具 schema；执行在 ScheduledTaskTool。 */
internal object AgentScheduleToolCatalog {
    fun appendTo(tools: JSONArray) {
        val scheduleSchema = JSONObject()
            .put("type", "object")
            .put(
                "properties",
                JSONObject()
                    .put(
                        "kind",
                        JSONObject().put("type", "string")
                            .put("enum", JSONArray().put("once").put("daily").put("weekly").put("interval"))
                            .put("description", "once=指定时间执行一次；daily/weekly=每天/每周几的固定时刻；interval=固定间隔重复"),
                    )
                    .put(
                        "at",
                        JSONObject().put("type", "string")
                            .put("description", "kind=once 的本地时间，例如 2026-11-12 09:00；也接受 ISO-8601 带偏移的写法"),
                    )
                    .put("hour", JSONObject().put("type", "integer").put("description", "kind=daily/weekly 的小时 0..23"))
                    .put("minute", JSONObject().put("type", "integer").put("description", "kind=daily/weekly 的分钟 0..59"))
                    .put(
                        "days_of_week",
                        JSONObject().put("type", "array")
                            .put("items", JSONObject().put("type", "integer"))
                            .put("description", "kind=weekly 的星期，1=周一 … 7=周日"),
                    )
                    .put(
                        "interval_minutes",
                        JSONObject().put("type", "integer")
                            .put("description", "kind=interval 的间隔分钟数，1..43200"),
                    ),
            )
            .put("required", JSONArray().put("kind"))

        tools.put(
            AgentToolSchema.function(
                name = "schedule_task",
                description = "管理到点自动执行的 agent 任务。action=create 创建（需要 prompt 与 schedule），" +
                    "list 列出全部任务与下次触发时间，enable/disable 启停，delete 删除，run_now 立刻跑一次验证。" +
                    "任务到点后会在后台无人值守地执行 prompt：照常把已授权任务做完并产出结果，不要求用户回来再批。" +
                    "安全闸门只硬守系统分区/启动链/安卓侧手机数据等关键部位，对所有来源一律照样弹卡，两分钟没人点则那一条不执行、不谎报完成，其余工作照常完成由模型自行判断。",
                parameters = JSONObject()
                    .put("type", "object")
                    .put(
                        "properties",
                        JSONObject()
                            .put(
                                "action",
                                JSONObject().put("type", "string")
                                    .put(
                                        "enum",
                                        JSONArray().put("create").put("list").put("enable")
                                            .put("disable").put("delete").put("run_now"),
                                    ),
                            )
                            .put("id", JSONObject().put("type", "string").put("description", "list 返回的任务 id"))
                            .put("title", JSONObject().put("type", "string").put("description", "简短名称，缺省取 prompt 首行"))
                            .put(
                                "prompt",
                                JSONObject().put("type", "string")
                                    .put("description", "到点后要执行的自然语言指令，写清目标与验收标准"),
                            )
                            .put("schedule", scheduleSchema)
                            .put("enabled", JSONObject().put("type", "boolean").put("description", "创建时是否立即启用，默认 true")),
                    )
                    .put("required", JSONArray().put("action")),
            ),
        )
    }
}
