package io.github.mangi.eta.agent.memory

import org.json.JSONArray
import org.json.JSONObject

/** 沉淀回合里一次工具调用的裁决。 */
internal enum class DistillToolDecision {
    /** 记忆工具且预算未用尽。 */
    Allow,

    /** 非记忆工具：沉淀回合不得继续执行任务。 */
    Blocked,

    /** 已达 [AgentMemoryDistiller.MAX_TOOL_CALLS]，调用方应立刻终止整个沉淀回合。 */
    BudgetExhausted,
}

/**
 * 三期的"记忆沉淀回合"决策与提示词。
 *
 * 只做纯计算：是否该跑、指令文本、工具收敛规则。真正的模型调用由运行执行器发起，
 * 因此这里的行为可以直接在单元测试里验证。
 */
internal object AgentMemoryDistiller {
    /** 本轮工具调用少于此值时通常没有值得长期保留的内容，不打扰模型。 */
    const val MIN_TOOL_CALLS = 3

    /** 沉淀回合最多允许的工具调用次数，防止模型反复重试记忆写入而空转。 */
    const val MAX_TOOL_CALLS = 3

    /** 沉淀回合只开放记忆工具：它不应该继续执行任务。 */
    val TOOL_NAMES: Set<String> = setOf("memory_get", "memory_write")

    fun shouldDistill(
        enabled: Boolean,
        memoryEnabled: Boolean,
        writable: Boolean,
        chatOperation: Boolean,
        toolCalls: Int,
        minToolCalls: Int = MIN_TOOL_CALLS,
    ): Boolean = enabled &&
        memoryEnabled &&
        writable &&
        chatOperation &&
        toolCalls >= minToolCalls

    /** 沉淀回合的系统指令；强调"没有价值就直接结束"，并禁止写入凭据。 */
    fun instruction(): String = buildString {
        appendLine("本轮任务已经结束。现在只做一次极短的记忆沉淀判断，不要重复执行任务、不要继续操作手机或读取数据。")
        appendLine("只有当本轮出现了跨对话仍有价值的稳定事实、用户偏好或纠正、重要关系、进行中的项目、确认过的环境与约定时，才用 memory_write 写入 MEMORY.md：")
        appendLine("优先 mode=upsert_section 更新已有主题（纠正过期事实），其次 mode=append 新增一节；单节正文不超过 3500 字符。")
        appendLine("没有值得长期保留的内容就直接结束回复，不要写，也不要为了有产出而编造或复述本轮的一次性结果。")
        appendLine("禁止写入密钥、token、密码、验证码、证件号、银行卡号等凭据与敏感号码；也不写一次性请求、临时状态或与用户无关的推理过程。")
        append("最多调用 ${MAX_TOOL_CALLS} 次工具。完成后用一句话说明写了哪一节；没有写就回答“无需沉淀”。")
    }

    /**
     * 单次工具调用的裁决；[executedCalls] 是本回合已经执行过的调用次数。
     *
     * 这是"最多 [MAX_TOOL_CALLS] 次工具调用"的唯一判定点：调用方收到
     * [DistillToolDecision.BudgetExhausted] 必须终止回合，而不是把错误结果喂回模型空转。
     */
    fun decideToolCall(toolName: String, executedCalls: Int): DistillToolDecision = when {
        toolName !in TOOL_NAMES -> DistillToolDecision.Blocked
        executedCalls >= MAX_TOOL_CALLS -> DistillToolDecision.BudgetExhausted
        else -> DistillToolDecision.Allow
    }

    /** 只保留记忆工具的目录副本；为空时模型也没有别的工具可调。 */
    fun restrictTools(tools: JSONArray): JSONArray = JSONArray().also { filtered ->
        for (index in 0 until tools.length()) {
            val tool = tools.optJSONObject(index) ?: continue
            val name = tool.optJSONObject("function")?.optString("name").orEmpty()
            if (name in TOOL_NAMES) filtered.put(tool)
        }
    }

    fun toolBlockedResult(): String = JSONObject()
        .put("ok", false)
        .put("code", "DISTILL_TOOL_BLOCKED")
        .put("message", "沉淀回合只允许 memory_get / memory_write，本次未执行")
        .toString()

    fun budgetExhaustedResult(): String = JSONObject()
        .put("ok", false)
        .put("code", "DISTILL_BUDGET_EXHAUSTED")
        .put("message", "沉淀回合的工具调用已用尽，请立刻结束回复")
        .toString()
}
