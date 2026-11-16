package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具目录可见性回归（审查 M4）。
 *
 * 角色会话必须看不到 ask_user / schedule_task / macro_record：它们都会把用户拉回现实交互，
 * 或写入"以后要执行什么"这类持久状态。
 */
class ToolCatalogVisibilityTest {
    private fun tool(name: String): JSONObject = JSONObject().put(
        "type",
        "function",
    ).put("function", JSONObject().put("name", name).put("description", "d"))

    private fun catalog(vararg names: String): JSONArray = JSONArray().also { array ->
        names.forEach { array.put(tool(it)) }
    }

    private fun names(array: JSONArray): List<String> = (0 until array.length()).map { index ->
        array.getJSONObject(index).getJSONObject("function").getString("name")
    }

    @Test
    fun `conversation only tools are the three documented names`() {
        assertEquals(
            setOf("ask_user", "schedule_task", "macro_record"),
            ToolCatalogVisibility.CONVERSATION_ONLY_TOOL_NAMES,
        )
    }

    @Test
    fun `roleplay catalog drops conversation only tools and keeps the rest`() {
        val source = catalog("observe_screen", "ask_user", "tap", "schedule_task", "macro_record", "memory_get")
        val visible = ToolCatalogVisibility.filter(source, ToolCatalogVisibility.CONVERSATION_ONLY_TOOL_NAMES)
        assertEquals(listOf("observe_screen", "tap", "memory_get"), names(visible))
        // 原数组不被就地修改：同一份目录可能被普通会话复用。
        assertEquals(6, source.length())
    }

    @Test
    fun `empty hidden set returns the same instance`() {
        val source = catalog("observe_screen", "ask_user")
        assertTrue(ToolCatalogVisibility.filter(source, emptySet()) === source)
    }

    @Test
    fun `malformed entries are skipped instead of crashing`() {
        val source = JSONArray()
            .put("not-an-object")
            .put(JSONObject().put("type", "function"))
            .put(tool("tap"))
        val visible = ToolCatalogVisibility.filter(source, setOf("ask_user"))
        // 读不出名字的条目无法判断可见性，直接丢弃。
        assertEquals(listOf("tap"), names(visible))
    }
}
