package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * `notify_user` 工具 schema：让 Agent 在合适时机主动找用户（系统通知），而不是只能被唤醒后说话。
 *
 * 只声明 schema，不含执行逻辑；可见性由 [ToolCatalogVisibility] 控制（角色会话不注入）。
 * 使用纪律写在系统提示 [AgentPromptBuilder]，不在 description 里堆长篇约束。
 */
internal object AgentNotifyToolCatalog {
    const val NAME = "notify_user"

    /**
     * 按开关决定是否注入 schema。
     *
     * 把"设置开关 → 是否注入目录"这一步单独抽出来，既让 [AgentToolCatalog] 的调用点保持一行，
     * 也让这条行为能在纯 JVM 单测里钉住（关闭时一个 schema 都不追加）。
     *
     * @return 是否真的追加了。
     */
    fun appendIf(tools: JSONArray, enabled: Boolean): Boolean {
        if (!enabled) return false
        appendTo(tools)
        return true
    }

    fun appendTo(tools: JSONArray) {
        tools.put(
            AgentToolSchema.function(
                name = NAME,
                description = "在合适时机主动给用户发一条系统通知（不是聊天回复）。仅在无人值守任务/后台长任务有了值得关注的结果、盯梢目标出现变化、或确实紧急需要用户回来时使用；克制、有价值才发。" +
                    "title 简短说明发生了什么，body 给结论与可采取的动作。" +
                    "priority=important 仅用于真正紧急（会走更高优先级 channel 并带提示音），普通情况一律省略或 normal。" +
                    "action=open_conversation（默认）时可用 context 带一段上下文，用户点通知进入对话后作为新消息草稿；" +
                    "action=open_task_center 时点通知直达任务中心。设置里可整体关闭此能力。",
                parameters = JSONObject()
                    .put("type", "object")
                    .put(
                        "properties",
                        JSONObject()
                            .put(
                                "title",
                                JSONObject().put("type", "string")
                                    .put("description", "通知标题，必填，简短说明发生了什么（如'价格已降到目标区间'）"),
                            )
                            .put(
                                "body",
                                JSONObject().put("type", "string")
                                    .put("description", "通知正文：结论、关键数据与用户可采取的动作"),
                            )
                            .put(
                                "priority",
                                JSONObject().put("type", "string")
                                    .put("enum", JSONArray().put("normal").put("important"))
                                    .put("description", "默认 normal；important 仅用于真正紧急，会带提示音与更高优先级"),
                            )
                            .put(
                                "action",
                                JSONObject().put("type", "string")
                                    .put("enum", JSONArray().put("open_conversation").put("open_task_center"))
                                    .put("description", "默认 open_conversation：点通知进入 Agent 对话；open_task_center 直达任务中心"),
                            )
                            .put(
                                "context",
                                JSONObject().put("type", "string")
                                    .put("description", "action=open_conversation 时附带的一段上下文，进入对话后作为新消息草稿；用于让对话带着背景继续"),
                            ),
                    )
                    .put("required", JSONArray().put("title")),
            ),
        )
    }
}
