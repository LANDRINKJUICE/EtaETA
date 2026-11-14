package io.github.mangi.eta.agent.approval

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/** ask_user 的参数校验、结果结构与"未回答"语义。 */
class AgentAskUserToolTest {
    @get:Rule
    val timeout: Timeout = Timeout.seconds(30)

    private fun coordinator(pollIntervalMillis: Long = 5L): AgentApprovalCoordinator =
        AgentApprovalCoordinator(pollIntervalMillis = pollIntervalMillis)

    private fun request(
        question: String = "这张照片要发到哪个群？",
        options: Int = 2,
        allowFreeText: Boolean = false,
        explanation: String = "相册里有两张相似的照片，我不确定你要发哪张。",
    ): JSONObject {
        val array = JSONArray()
        repeat(options) { index ->
            array.put(
                JSONObject()
                    .put("label", "选项${index + 1}")
                    .put("description", "第${index + 1}个后果"),
            )
        }
        return JSONObject()
            .put("question", question)
            .put("explanation", explanation)
            .put("options", array)
            .put("allow_free_text", allowFreeText)
    }

    private fun answer(
        coordinator: AgentApprovalCoordinator,
        optionId: String,
        freeText: String? = null,
    ): Thread = Thread {
        val deadline = System.currentTimeMillis() + 3_000
        while (System.currentTimeMillis() < deadline) {
            val pending = coordinator.pending.value.firstOrNull()
            if (pending != null && coordinator.resolve(pending.id, optionId, freeText)) return@Thread
            Thread.sleep(2)
        }
    }.also { it.start() }

    @Test
    fun answeredQuestionReturnsTheChosenOption() {
        val coordinator = coordinator()
        val answer = answer(coordinator, "option_2")
        val result = JSONObject(AgentAskUserTool.execute(request(), "run-1", "conv-1", coordinator))
        answer.join(1_000)
        assertTrue(result.getBoolean("ok"))
        assertEquals("option_2", result.getString("choice_id"))
        assertEquals("选项2", result.getString("choice"))
        assertTrue(result.getBoolean("answered"))
        assertEquals(AgentAskUserTool.ANSWERED_VIA_OPTION, result.getString("answered_via"))
        assertTrue(coordinator.pending.value.isEmpty())
    }

    @Test
    fun freeTextAnswerIsReturnedVerbatimAndMarked() {
        val coordinator = coordinator()
        val answer = answer(coordinator, AgentApprovalOption.FREE_TEXT_ONLY, "发到“家人”群")
        val result = JSONObject(
            AgentAskUserTool.execute(
                request(allowFreeText = true),
                "run-1",
                "conv-1",
                coordinator,
            ),
        )
        answer.join(1_000)
        assertTrue(result.getBoolean("ok"))
        assertEquals(AgentApprovalOption.FREE_TEXT_ONLY, result.getString("choice_id"))
        assertEquals("发到“家人”群", result.getString("free_text"))
        assertEquals(AgentAskUserTool.ANSWERED_VIA_FREE_TEXT, result.getString("answered_via"))
    }

    @Test
    fun unansweredQuestionNeverInventsAChoice() {
        val coordinator = coordinator(pollIntervalMillis = 5L)
        val result = JSONObject(
            AgentAskUserTool.execute(
                args = request(),
                runId = "run-1",
                conversationId = "conv-1",
                coordinator = coordinator,
                timeoutMillis = 40L,
            ),
        )
        assertFalse(result.getBoolean("ok"))
        assertEquals("USER_NO_ANSWER", result.getString("code"))
        assertFalse(result.has("choice"))
        assertTrue(result.getString("message").contains("不要臆测"))
        assertTrue(coordinator.pending.value.isEmpty())
    }

    @Test
    fun stoppedRunCountsAsUnanswered() {
        val coordinator = coordinator()
        val result = JSONObject(
            AgentAskUserTool.execute(request(), "run-1", "conv-1", coordinator) { false },
        )
        assertFalse(result.getBoolean("ok"))
        assertEquals("USER_NO_ANSWER", result.getString("code"))
        assertTrue(result.getString("message").contains("不要臆测"))
        assertTrue(coordinator.pending.value.isEmpty())
        assertFalse(result.has("answered_via"))
    }

    @Test
    fun questionAndOptionCountAreValidated() {
        val coordinator = coordinator()
        val blank = JSONObject(AgentAskUserTool.execute(JSONObject(), "run-1", null, coordinator))
        assertEquals("INVALID_ARGUMENT", blank.getString("code"))

        val tooFew = JSONObject(
            AgentAskUserTool.execute(request(options = 1), "run-1", null, coordinator),
        )
        assertEquals("INVALID_ARGUMENT", tooFew.getString("code"))

        val tooMany = JSONObject(
            AgentAskUserTool.execute(request(options = 5), "run-1", null, coordinator),
        )
        assertEquals("INVALID_ARGUMENT", tooMany.getString("code"))

        val missingOptions = JSONObject(
            AgentAskUserTool.execute(
                JSONObject().put("question", "要不要继续？"),
                "run-1",
                null,
                coordinator,
            ),
        )
        assertEquals("INVALID_ARGUMENT", missingOptions.getString("code"))
        assertTrue(coordinator.pending.value.isEmpty())
    }

    @Test
    fun theCardCarriesQuestionOptionsAndFreeTextFlag() {
        val coordinator = coordinator()
        val answer = answer(coordinator, "option_1")
        AgentAskUserTool.execute(request(allowFreeText = true), "run-1", "conv-1", coordinator)
        answer.join(1_000)
        val resolved = coordinator.resolved.value.single()
        assertEquals(AgentApprovalKind.AskUser, resolved.request.kind)
        assertEquals("这张照片要发到哪个群？", resolved.request.question)
        assertEquals(listOf("option_1", "option_2"), resolved.request.options.map { it.id })
        assertTrue(resolved.request.allowFreeText)
        assertEquals(AgentApprovalOptionKind.Choice, resolved.resolution.kind)
    }
}
