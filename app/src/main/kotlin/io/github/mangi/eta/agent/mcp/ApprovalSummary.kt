package io.github.mangi.eta.agent.mcp

import io.github.mangi.eta.agent.tool.AgentToolRequirements
import io.github.mangi.eta.agent.tool.RootRequirement
import org.json.JSONObject

/**
 * 审批卡展示用的摘要与风险分级。
 *
 * 只从参数里挑出对用户有意义的那一项（命令 / 路径 / 包名 / 文本），
 * 避免把整段 JSON 塞进卡片。
 */
internal object ApprovalSummary {

    /** 只读工具在闸门处已提前放行，不会走到这里，因此只有 Root 与 Mutating 两种结果。 */
    fun riskOf(toolName: String): McpApprovalGate.ApprovalRisk {
        val requirement = AgentToolRequirements.find(toolName)
        return if (requirement?.rootRequirement == RootRequirement.NONE) {
            McpApprovalGate.ApprovalRisk.Mutating
        } else {
            McpApprovalGate.ApprovalRisk.Root
        }
    }

    /** 取参数里最能说明"要干什么"的字段；找不到就回退到截断后的原始 JSON。 */
    fun describe(arguments: String): String {
        val json = try {
            JSONObject(arguments)
        } catch (_: Exception) {
            return arguments.take(MAX_SUMMARY_CHARS)
        }
        val picked = SUMMARY_FIELDS.asSequence()
            .mapNotNull { field -> json.optString(field).takeIf { it.isNotBlank() } }
            .firstOrNull()
        if (picked != null) return picked.take(MAX_SUMMARY_CHARS)
        return arguments.take(MAX_SUMMARY_CHARS)
    }

    private val SUMMARY_FIELDS = listOf(
        "command", "script", "path", "file_path", "package", "package_name",
        "url", "uri", "text", "query", "target", "name", "key", "value",
    )

    private const val MAX_SUMMARY_CHARS = 300
}
