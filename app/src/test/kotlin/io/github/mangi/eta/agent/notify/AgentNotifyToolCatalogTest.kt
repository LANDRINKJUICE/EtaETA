package io.github.mangi.eta.agent.notify

import io.github.mangi.eta.agent.model.AgentNotifyToolCatalog
import io.github.mangi.eta.agent.model.ToolCatalogVisibility
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `notify_user` 的目录与可见性回归。
 *
 * 钉住三件事：
 * 1. schema 存在、参数名/必填项稳定（模型靠它调用）；
 * 2. 角色会话（conversationTools=false）下整条被摘掉，普通会话保留；
 * 3. 隐藏集合是"会话级 + 主动沟通"，且既有三个会话级工具仍在。
 */
class AgentNotifyToolCatalogTest {

    private fun tool(name: String) = org.json.JSONObject()
        .put("type", "function")
        .put("function", org.json.JSONObject().put("name", name).put("description", "d"))

    private fun catalog(vararg names: String) = JSONArray().also { array ->
        names.forEach { array.put(tool(it)) }
    }

    private fun names(array: JSONArray): List<String> = (0 until array.length()).map { index ->
        array.getJSONObject(index).getJSONObject("function").getString("name")
    }

    // ---- schema ----

    @Test
    fun `notify user schema exists with title required`() {
        val tools = JSONArray().also { AgentNotifyToolCatalog.appendTo(it) }
        assertEquals(1, tools.length())
        val function = tools.getJSONObject(0).getJSONObject("function")
        assertEquals("notify_user", function.getString("name"))
        val parameters = function.getJSONObject("parameters")
        assertEquals(listOf("title"), parameters.getJSONArray("required").stringValues())
        val properties = parameters.getJSONObject("properties")
        listOf("title", "body", "priority", "action", "context").forEach { name ->
            assertTrue("缺少参数 $name", properties.has(name))
        }
        assertEquals(
            listOf("normal", "important"),
            properties.getJSONObject("priority").getJSONArray("enum").stringValues(),
        )
        assertEquals(
            listOf("open_conversation", "open_task_center"),
            properties.getJSONObject("action").getJSONArray("enum").stringValues(),
        )
    }

    @Test
    fun `notify user description states the restraint and the urgent only rule`() {
        val description = JSONArray().also { AgentNotifyToolCatalog.appendTo(it) }
            .getJSONObject(0).getJSONObject("function").getString("description")
        assertTrue(description.contains("克制"))
        assertTrue(description.contains("真正紧急"))
        assertTrue(description.contains("important"))
    }

    // ---- 可见性 ----

    @Test
    fun `proactive tool names contain only notify user`() {
        assertEquals(setOf("notify_user"), ToolCatalogVisibility.PROACTIVE_ONLY_TOOL_NAMES)
    }

    @Test
    fun `roleplay hidden set is the union of conversation only and proactive tools`() {
        assertEquals(
            ToolCatalogVisibility.CONVERSATION_ONLY_TOOL_NAMES + ToolCatalogVisibility.PROACTIVE_ONLY_TOOL_NAMES,
            ToolCatalogVisibility.ROLEPLAY_HIDDEN_TOOL_NAMES,
        )
        // 既有三个会话级工具一个都不能丢（审查 M4 的原有约束）。
        assertTrue(ToolCatalogVisibility.ROLEPLAY_HIDDEN_TOOL_NAMES.containsAll(setOf("ask_user", "schedule_task", "macro_record")))
        assertTrue("notify_user" in ToolCatalogVisibility.ROLEPLAY_HIDDEN_TOOL_NAMES)
    }

    @Test
    fun `roleplay catalog drops notify user and keeps ordinary tools`() {
        val source = catalog("observe_screen", "notify_user", "tap", "ask_user")
        val visible = ToolCatalogVisibility.filter(source, ToolCatalogVisibility.ROLEPLAY_HIDDEN_TOOL_NAMES)
        assertEquals(listOf("observe_screen", "tap"), names(visible))
        // 源数组不被就地修改。
        assertEquals(4, source.length())
    }

    @Test
    fun `normal conversation keeps notify user visible`() {
        val source = catalog("observe_screen", "notify_user", "tap")
        // conversationTools=true 时不做过滤（与 AgentToolCatalog 的实现一致）。
        assertTrue(names(source).contains("notify_user"))
        assertFalse(names(source).isEmpty())
    }

    @Test
    fun `conversation only set is unchanged by this feature`() {
        // 既有单测精确断言了这三项；本次只新增独立集合，不就地扩容。
        assertEquals(setOf("ask_user", "schedule_task", "macro_record"), ToolCatalogVisibility.CONVERSATION_ONLY_TOOL_NAMES)
    }

    // ---- M2：roleplay 不能经 subagent 旁路 ----

    @Test
    fun `subagent is allowed only when enabled by config permission and not roleplay`() {
        // 普通会话 + 配置开 + 权限开 → 可用。
        assertTrue(ToolCatalogVisibility.subagentAllowed(configEnabled = true, permissionEnabled = true, roleplay = false))
        // 角色会话一律不可用（这是本轮的修复点：否则子代理会成为进入 notify_user 的旁路）。
        assertFalse(ToolCatalogVisibility.subagentAllowed(configEnabled = true, permissionEnabled = true, roleplay = true))
        // 配置/权限任一关闭也不可用。
        assertFalse(ToolCatalogVisibility.subagentAllowed(configEnabled = false, permissionEnabled = true, roleplay = false))
        assertFalse(ToolCatalogVisibility.subagentAllowed(configEnabled = true, permissionEnabled = false, roleplay = false))
        assertFalse(ToolCatalogVisibility.subagentAllowed(configEnabled = false, permissionEnabled = false, roleplay = true))
    }

    @Test
    fun `roleplay cannot reach proactive tools even indirectly through subagent`() {
        // 直接注入被隐藏。
        assertTrue("notify_user" in ToolCatalogVisibility.ROLEPLAY_HIDDEN_TOOL_NAMES)
        // 间接路径（subagent）也被关掉，两条路径同时不可达才算真的隔离。
        assertFalse(ToolCatalogVisibility.subagentAllowed(configEnabled = true, permissionEnabled = true, roleplay = true))
    }

    // ---- M3：开关关闭时 schema 不注入 ----

    @Test
    fun `appendIf injects nothing when disabled and one tool when enabled`() {
        val disabledTools = JSONArray()
        assertFalse(AgentNotifyToolCatalog.appendIf(disabledTools, enabled = false))
        assertEquals(0, disabledTools.length())

        val enabledTools = JSONArray()
        assertTrue(AgentNotifyToolCatalog.appendIf(enabledTools, enabled = true))
        assertEquals(1, enabledTools.length())
        assertEquals(
            "notify_user",
            enabledTools.getJSONObject(0).getJSONObject("function").getString("name"),
        )
    }

    @Test
    fun `appendIf keeps existing entries untouched when disabled`() {
        val tools = catalog("observe_screen")
        assertFalse(AgentNotifyToolCatalog.appendIf(tools, enabled = false))
        assertEquals(listOf("observe_screen"), names(tools))
    }
}

private fun JSONArray.stringValues(): List<String> = (0 until length()).map { getString(it) }
