package io.github.mangi.eta.agent.approval

import org.json.JSONObject

/**
 * `ask_user` 工具：Agent 拿不准用户意图时，在聊天流里插入一张询问卡。
 *
 * 选项由模型自己生成，因此天然贴合当下场景；用户点选（或补充自由文本）后，结果作为工具结果
 * 返回给模型。超时或会话结束一律返回"用户未回答"，不臆造选择。
 */
internal object AgentAskUserTool {
    const val NAME = "ask_user"

    const val MIN_OPTIONS = 2
    const val MAX_OPTIONS = 4

    /** 结果里标注用户是怎么回答的：点选项，还是自己写了自由文本。 */
    const val ANSWERED_VIA_OPTION = "option"
    const val ANSWERED_VIA_FREE_TEXT = "free_text"
    private const val MAX_QUESTION_CHARS = 400
    private const val MAX_EXPLANATION_CHARS = 2_000
    private const val MAX_OPTION_LABEL_CHARS = 120
    private const val MAX_OPTION_DESCRIPTION_CHARS = 300

    fun execute(
        args: JSONObject,
        runId: String,
        conversationId: String?,
        coordinator: AgentApprovalCoordinator = AgentApprovalCoordinator.shared,
        timeoutMillis: Long = AGENT_APPROVAL_TIMEOUT_MILLIS,
        isRunActive: () -> Boolean = { true },
    ): String {
        val question = args.optString("question").trim()
        if (question.isEmpty()) return failure("INVALID_ARGUMENT", "question 不能为空")
        if (question.length > MAX_QUESTION_CHARS) {
            return failure("INVALID_ARGUMENT", "question 最多 $MAX_QUESTION_CHARS 字")
        }
        val explanation = args.optString("explanation").trim().take(MAX_EXPLANATION_CHARS)
        val rawOptions = args.optJSONArray("options")
            ?: return failure("INVALID_ARGUMENT", "options 必须是 2 到 4 个选项的数组")
        if (rawOptions.length() < MIN_OPTIONS || rawOptions.length() > MAX_OPTIONS) {
            return failure("INVALID_ARGUMENT", "options 需要 $MIN_OPTIONS 到 $MAX_OPTIONS 个选项")
        }
        val options = mutableListOf<AgentApprovalOption>()
        for (index in 0 until rawOptions.length()) {
            val item = rawOptions.optJSONObject(index)
                ?: return failure("INVALID_ARGUMENT", "options[$index] 必须是对象")
            val label = item.optString("label").trim()
            if (label.isEmpty()) return failure("INVALID_ARGUMENT", "options[$index].label 不能为空")
            if (label.length > MAX_OPTION_LABEL_CHARS) {
                return failure("INVALID_ARGUMENT", "options[$index].label 最多 $MAX_OPTION_LABEL_CHARS 字")
            }
            options += AgentApprovalOption(
                id = "option_${index + 1}",
                kind = AgentApprovalOptionKind.Choice,
                label = label,
                description = item.optString("description").trim().take(MAX_OPTION_DESCRIPTION_CHARS),
            )
        }
        val request = coordinator.askUserRequest(
            question = question,
            explanation = explanation,
            options = options,
            allowFreeText = args.optBoolean("allow_free_text", false),
            runId = runId,
            conversationId = conversationId,
            timeoutMillis = timeoutMillis,
        )
        return when (val outcome = coordinator.await(request, isAborted = { !isRunActive() })) {
            is AgentApprovalOutcome.Decided -> {
                val chosen = options.firstOrNull { it.id == outcome.resolution.optionId }
                JSONObject()
                    .put("ok", true)
                    .put("tool", NAME)
                    .put("choice_id", outcome.resolution.optionId)
                    .put("choice", chosen?.label.orEmpty())
                    .put("free_text", outcome.resolution.freeText ?: JSONObject.NULL)
                    .put("answered", true)
                    // 用户没有点选项、而是自己输入了答案时明确标注，避免模型把自由文本当成某个选项。
                    .put(
                        "answered_via",
                        if (outcome.resolution.optionId == AgentApprovalOption.FREE_TEXT_ONLY) {
                            ANSWERED_VIA_FREE_TEXT
                        } else {
                            ANSWERED_VIA_OPTION
                        },
                    )
                    .toString()
            }
            AgentApprovalOutcome.TimedOut,
            AgentApprovalOutcome.Cancelled,
            -> JSONObject()
                .put("ok", false)
                .put("tool", NAME)
                .put("code", "USER_NO_ANSWER")
                .put("message", "用户没有回答（等待超时或会话已结束）。不要臆测用户的选择，" +
                    "也不要假设某个选项已被采纳；可以先把不需要该信息的部分做完，或直接向用户说明你需要的决定。")
                .toString()
        }
    }

    private fun failure(code: String, message: String): String = JSONObject()
        .put("ok", false)
        .put("tool", NAME)
        .put("code", code)
        .put("message", message)
        .toString()
}
