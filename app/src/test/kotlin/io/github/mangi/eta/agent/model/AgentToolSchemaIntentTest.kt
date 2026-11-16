package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 需求 4：给所有工具统一注入可选 `intent`（一句简短中文「你要干什么」）的纯 JVM 回归。
 *
 * `AgentToolSchema.function` 是全部工具 schema 的唯一入口，在这里注入能保证任何会命中
 * 危险闸门的工具都允许模型带上意图说明。intent 必须可选（不进 required）、不能破坏既有
 * properties，且对没有 properties 的无参工具要安全跳过（不崩、不凭空造参数）。
 */
class AgentToolSchemaIntentTest {

    private fun parametersWithCommand(): JSONObject = JSONObject()
        .put("type", "object")
        .put(
            "properties",
            JSONObject().put("command", JSONObject().put("type", "string")),
        )
        .put("required", JSONArray(listOf("command")))
        .put("additionalProperties", false)

    @Test
    fun injectsOptionalIntentIntoProperties() {
        val tool = AgentToolSchema.function("terminal", "执行 Shell 命令", parametersWithCommand())
        val params = tool.getJSONObject("function").getJSONObject("parameters")
        val properties = params.getJSONObject("properties")
        val intent = properties.getJSONObject("intent")
        assertEquals("string", intent.getString("type"))
        assertTrue(intent.getString("description").contains("目的"))
    }

    @Test
    fun intentIsNotRequired() {
        val tool = AgentToolSchema.function("terminal", "执行 Shell 命令", parametersWithCommand())
        val params = tool.getJSONObject("function").getJSONObject("parameters")
        val required = params.getJSONArray("required").stringValues()
        assertFalse(
            "intent 必须可选，不能进 required（否则每个工具调用都强制模型填目的）",
            required.contains("intent"),
        )
        assertTrue(required.contains("command"))
    }

    @Test
    fun keepsExistingPropertiesAndAdditionalPropertiesFalse() {
        val tool = AgentToolSchema.function("terminal", "执行 Shell 命令", parametersWithCommand())
        val params = tool.getJSONObject("function").getJSONObject("parameters")
        val properties = params.getJSONObject("properties")
        assertEquals("string", properties.getJSONObject("command").getString("type"))
        // intent 已写进 properties，additionalProperties=false 下模型传 intent 不会被 schema 拒绝。
        assertFalse(params.getBoolean("additionalProperties"))
        assertTrue(properties.has("intent"))
    }

    @Test
    fun parametersWithoutPropertiesAreLeftUntouchedAndDoNotCrash() {
        // 无参 / 自定义 parameters 的工具：没有 properties 键时安全跳过，不凭空造参数。
        val bare = JSONObject().put("type", "object")
        val tool = AgentToolSchema.function("probe", "无参工具", bare)
        val params = tool.getJSONObject("function").getJSONObject("parameters")
        assertFalse(params.has("properties"))
        assertEquals("probe", tool.getJSONObject("function").getString("name"))
    }

    private fun JSONArray.stringValues(): List<String> =
        (0 until length()).map { getString(it) }
}
