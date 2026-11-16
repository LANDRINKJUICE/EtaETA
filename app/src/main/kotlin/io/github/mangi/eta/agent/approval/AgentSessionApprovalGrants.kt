package io.github.mangi.eta.agent.approval

import java.util.concurrent.ConcurrentHashMap

/**
 * "本次会话内允许同类操作"的授权记忆。
 *
 * 授权键 = 工具名 + 风险类别 + 风险等级，作用域 = 会话（会话 id 为空时退化为进程级作用域，
 * 但那种情况根本不会提供"本会话允许同类"选项）。升级等级不会被既有授权覆盖：
 * 例如"允许本会话内重启到 recovery"（BOOT_CHAIN_WRITE/MEDIUM）不会放开
 * `dd of=/dev/block/by-name/boot`（BOOT_CHAIN_WRITE/CRITICAL）。
 *
 * CRITICAL 永不接受会话授权，只能"仅允许一次"。
 *
 * 只存在内存里：进程退出、会话被删除即失效，不写入任何偏好或数据库。
 */
internal class AgentSessionApprovalGrants {
    private val grants = ConcurrentHashMap<String, MutableSet<String>>()

    fun grant(conversationId: String?, toolName: String, findings: Collection<RiskFinding>) {
        if (findings.isEmpty()) return
        val grantable = findings.filter { it.level != ToolRiskLevel.CRITICAL }
        if (grantable.isEmpty()) return
        val scope = scopeKey(conversationId)
        val keys = grants.getOrPut(scope) { ConcurrentHashMap.newKeySet() }
        grantable.forEach { finding -> keys += grantKey(toolName, finding) }
    }

    /**
     * 只有命中的**全部**风险项（类别 + 等级）都被授权时才跳过确认，
     * 避免"允许一次 /system 写入"顺带放开删除类或更高级别的风险。
     */
    fun isGranted(
        conversationId: String?,
        toolName: String,
        findings: Collection<RiskFinding>,
    ): Boolean {
        if (findings.isEmpty()) return false
        if (findings.any { it.level == ToolRiskLevel.CRITICAL }) return false
        val keys = grants[scopeKey(conversationId)] ?: return false
        return findings.all { finding -> grantKey(toolName, finding) in keys }
    }

    fun clear(conversationId: String?) {
        grants.remove(scopeKey(conversationId))
    }

    /** 测试与诊断用：当前全部授权键。 */
    fun snapshot(): Map<String, Set<String>> =
        grants.mapValues { (_, keys) -> keys.toSet() }

    private fun scopeKey(conversationId: String?): String =
        conversationId?.takeIf { it.isNotBlank() } ?: GLOBAL_SCOPE

    private fun grantKey(toolName: String, finding: RiskFinding): String =
        "${toolName.trim()}|${finding.category.wireValue}|${finding.level.name.lowercase()}"

    companion object {
        private const val GLOBAL_SCOPE = "\u0000global"

        /** 进程内共享的授权记忆。 */
        val shared = AgentSessionApprovalGrants()
    }
}
