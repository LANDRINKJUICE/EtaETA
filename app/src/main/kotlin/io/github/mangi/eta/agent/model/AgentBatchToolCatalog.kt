package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/** 连续 GUI 动作批处理与宏工具 schema；执行在 AgentLocalTools。 */
internal object AgentBatchToolCatalog {
    fun appendTo(tools: JSONArray) {
        val actionSchema = JSONObject()
            .put("type", "object")
            .put(
                "properties",
                JSONObject()
                    .put(
                        "type",
                        JSONObject()
                            .put("type", "string")
                            .put(
                                "enum",
                                JSONArray().put("tap").put("tap_area").put("tap_text").put("long_press")
                                    .put("swipe").put("scroll").put("type_text").put("press_key").put("wait_ms"),
                            ),
                    )
                    .put("x", JSONObject().put("type", "integer"))
                    .put("y", JSONObject().put("type", "integer"))
                    .put("x1", JSONObject().put("type", "integer"))
                    .put("y1", JSONObject().put("type", "integer"))
                    .put("x2", JSONObject().put("type", "integer"))
                    .put("y2", JSONObject().put("type", "integer"))
                    .put("coordinate_space", AgentToolSchema.coordinateSpace())
                    .put("text", JSONObject().put("type", "string"))
                    .put("button", JSONObject().put("type", "string"))
                    .put("direction", JSONObject().put("type", "string"))
                    .put("amount", JSONObject().put("type", "string"))
                    .put("duration_ms", JSONObject().put("type", "integer"))
                    .put("ms", JSONObject().put("type", "integer"))
                    .put("submit", JSONObject().put("type", "boolean")),
            )
            .put("required", JSONArray().put("type"))
        tools
            .put(
                AgentToolSchema.function(
                    name = "gesture_batch",
                    description = "一次调用执行 1..50 个连续 GUI 动作，中间不观察、不经过模型回合，速度远快于逐个 tap。动作字段与同名工具一致，coordinate_space 缺省为 screen；tap_text 按当前可见文字/desc 实时找节点点击。失败立即中断并返回 stopped_at；observe=final（默认）只在全部成功后附带一次轻量观察，observe=none 不观察。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("actions", JSONObject().put("type", "array").put("items", actionSchema))
                                .put(
                                    "observe",
                                    JSONObject().put("type", "string").put("enum", JSONArray().put("final").put("none")),
                                ),
                        )
                        .put("required", JSONArray().put("actions")),
                ),
            )
            .put(
                AgentToolSchema.function(
                    name = "macro_list",
                    description = "列出已保存的 GUI 宏（命名的 gesture_batch 动作序列）。",
                    parameters = JSONObject().put("type", "object"),
                ),
            )
            .put(
                AgentToolSchema.function(
                    name = "macro_save",
                    description = "把验证成功的动作序列保存为 GUI 宏；字符串值可用 {{参数}} 占位，macro_run 时注入。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("name", JSONObject().put("type", "string"))
                                .put("description", JSONObject().put("type", "string"))
                                .put("actions", JSONObject().put("type", "array").put("items", actionSchema)),
                        )
                        .put("required", JSONArray().put("name").put("actions")),
                ),
            )
            .put(
                AgentToolSchema.function(
                    name = "macro_run",
                    description = "按名称执行 GUI 宏；params 提供 {{占位}} 参数，observe 同 gesture_batch。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("name", JSONObject().put("type", "string"))
                                .put("params", JSONObject().put("type", "object"))
                                .put("observe", JSONObject().put("type", "string")),
                        )
                        .put("required", JSONArray().put("name")),
                ),
            )
            .put(
                AgentToolSchema.function(
                    name = "macro_delete",
                    description = "按名称删除 GUI 宏。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put("properties", JSONObject().put("name", JSONObject().put("type", "string")))
                        .put("required", JSONArray().put("name")),
                ),
            )
    }
}
