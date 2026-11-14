package io.github.mangi.eta.agent.model

import org.json.JSONArray

/**
 * 工具目录的可见性收口（纯逻辑，便于单元测试）。
 *
 * 角色会话需要看到同一份工具集，但"会话级工具"（ask_user / schedule_task / macro_record）
 * 会把用户拉回现实交互或写入持久状态，与沉浸式角色会话冲突，因此在发布给模型之前整条摘掉
 * （审查 M4）。执行侧在 AgentLocalTools 里有一道同样的拦截：目录不可见 + 执行拒绝，双保险。
 */
internal object ToolCatalogVisibility {
    /** 只在普通会话里公开的工具名。 */
    val CONVERSATION_ONLY_TOOL_NAMES = setOf("ask_user", "schedule_task", "macro_record")

    /**
     * 主动沟通类工具：角色会话同样不可见。
     *
     * 与 [CONVERSATION_ONLY_TOOL_NAMES] 分开维护（审查 M4 的既有集合被单测精确断言，不能就地扩容），
     * 但两者在"角色会话不可见"这一点上同源；[io.github.mangi.eta.agent.model.AgentToolCatalog]
     * 在 conversationTools=false 时按两者的并集过滤。
     */
    val PROACTIVE_ONLY_TOOL_NAMES = setOf("notify_user")

    /** 角色会话需要隐藏的全部工具（会话级 + 主动沟通）。 */
    val ROLEPLAY_HIDDEN_TOOL_NAMES: Set<String> =
        CONVERSATION_ONLY_TOOL_NAMES + PROACTIVE_ONLY_TOOL_NAMES

    /**
     * subagent 是否可用。
     *
     * 角色会话必须关掉：子代理跑的是**同一套本地工具**（含 notify_user / ask_user / schedule_task），
     * 只要还能派子代理，角色会话就能借它绕过"角色会话不可见"的目录限制，在不破坏聊天体验的前提下
     * 把用户拉回现实交互。所以这里把 roleplay 作为与"配置开关 / 权限开关"同级的硬条件。
     */
    fun subagentAllowed(configEnabled: Boolean, permissionEnabled: Boolean, roleplay: Boolean): Boolean =
        configEnabled && permissionEnabled && !roleplay

    /**
     * 按需过滤掉指定工具。
     *
     * @param hidden 需要隐藏的工具名；为空时原样返回（避免无意义的复制）。
 * 读不出 `function.name` 的畸形条目会被丢弃，而不是原样保留。
     */
    fun filter(tools: JSONArray, hidden: Set<String>): JSONArray {
        if (hidden.isEmpty()) return tools
        return JSONArray().also { kept ->
            for (index in 0 until tools.length()) {
                val tool = tools.optJSONObject(index) ?: continue
                // 读不出名字的条目无法判断可见性，一律丢弃：宁可不发布，也不发布一个未知工具。
                val name = tool.optJSONObject("function")?.optString("name").orEmpty()
                if (name.isNotBlank() && name !in hidden) kept.put(tool)
            }
        }
    }
}
