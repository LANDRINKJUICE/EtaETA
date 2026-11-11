package io.github.mangi.eta.agent.memory

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentMemoryDistillerTest {
    private fun enabledEverything(toolCalls: Int) = AgentMemoryDistiller.shouldDistill(
        enabled = true,
        memoryEnabled = true,
        writable = true,
        chatOperation = true,
        toolCalls = toolCalls,
    )

    @Test
    fun distillationRequiresTheSettingAndEnoughToolCalls() {
        assertFalse(enabledEverything(AgentMemoryDistiller.MIN_TOOL_CALLS - 1))
        assertTrue(enabledEverything(AgentMemoryDistiller.MIN_TOOL_CALLS))

        assertFalse(
            AgentMemoryDistiller.shouldDistill(
                enabled = false,
                memoryEnabled = true,
                writable = true,
                chatOperation = true,
                toolCalls = 10,
            ),
        )
        assertFalse(
            AgentMemoryDistiller.shouldDistill(
                enabled = true,
                memoryEnabled = false,
                writable = true,
                chatOperation = true,
                toolCalls = 10,
            ),
        )
        assertFalse(
            AgentMemoryDistiller.shouldDistill(
                enabled = true,
                memoryEnabled = true,
                writable = false,
                chatOperation = true,
                toolCalls = 10,
            ),
        )
        assertFalse(
            AgentMemoryDistiller.shouldDistill(
                enabled = true,
                memoryEnabled = true,
                writable = true,
                chatOperation = false,
                toolCalls = 10,
            ),
        )
    }

    @Test
    fun instructionForbidsSecretsAndAllowsEndingWithoutWriting() {
        val instruction = AgentMemoryDistiller.instruction()

        assertTrue(instruction.contains("密钥"))
        assertTrue(instruction.contains("验证码"))
        assertTrue(instruction.contains("无需沉淀"))
        assertTrue(instruction.contains("${AgentMemoryDistiller.MAX_TOOL_CALLS} 次工具"))
    }

    @Test
    fun toolBudgetIsAHardCapAndOnlyMemoryToolsAreAllowed() {
        repeat(AgentMemoryDistiller.MAX_TOOL_CALLS) { executed ->
            assertEquals(
                DistillToolDecision.Allow,
                AgentMemoryDistiller.decideToolCall("memory_write", executed),
            )
            assertEquals(
                DistillToolDecision.Allow,
                AgentMemoryDistiller.decideToolCall("memory_get", executed),
            )
        }
        // 第 MAX + 1 次调用必须直接终止回合，而不是把错误结果喂回模型继续空转。
        assertEquals(
            DistillToolDecision.BudgetExhausted,
            AgentMemoryDistiller.decideToolCall("memory_write", AgentMemoryDistiller.MAX_TOOL_CALLS),
        )
        assertEquals(
            DistillToolDecision.BudgetExhausted,
            AgentMemoryDistiller.decideToolCall("memory_get", AgentMemoryDistiller.MAX_TOOL_CALLS + 5),
        )
        assertEquals(DistillToolDecision.Blocked, AgentMemoryDistiller.decideToolCall("terminal", 0))
        assertEquals(
            DistillToolDecision.Blocked,
            AgentMemoryDistiller.decideToolCall("terminal", AgentMemoryDistiller.MAX_TOOL_CALLS + 5),
        )
    }

    @Test
    fun toolBudgetIsSmallEnoughToStopRunawayLoops() {
        assertEquals(3, AgentMemoryDistiller.MAX_TOOL_CALLS)
        assertEquals(setOf("memory_get", "memory_write"), AgentMemoryDistiller.TOOL_NAMES)
    }

    @Test
    fun restrictToolsKeepsOnlyMemoryTools() {
        val tools = JSONArray()
            .put(tool("memory_get"))
            .put(tool("terminal"))
            .put(tool("memory_write"))
            .put(JSONObject().put("type", "function"))

        val filtered = AgentMemoryDistiller.restrictTools(tools)

        assertEquals(2, filtered.length())
        assertEquals("memory_get", filtered.getJSONObject(0).getJSONObject("function").getString("name"))
        assertEquals("memory_write", filtered.getJSONObject(1).getJSONObject("function").getString("name"))
    }

    private fun tool(name: String): JSONObject = JSONObject()
        .put("type", "function")
        .put("function", JSONObject().put("name", name))
}
