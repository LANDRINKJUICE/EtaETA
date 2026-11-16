package io.github.mangi.eta.agent.approval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/** "本次会话内允许同类"的授权粒度、等级隔离与失效范围。 */
class AgentSessionApprovalGrantsTest {
    @get:Rule
    val timeout: Timeout = Timeout.seconds(30)

    private fun finding(
        category: ToolRiskCategory = ToolRiskCategory.SYSTEM_PARTITION_WRITE,
        level: ToolRiskLevel = ToolRiskLevel.CRITICAL,
        detail: String = "写入 /system/build.prop",
    ): RiskFinding = RiskFinding(category, level, detail)

    @Test
    fun grantedFindingsSkipTheSameToolInTheSameConversation() {
        val grants = AgentSessionApprovalGrants()
        val findings = listOf(finding(level = ToolRiskLevel.MEDIUM))
        grants.grant("conv-1", "terminal", findings)
        assertTrue(grants.isGranted("conv-1", "terminal", findings))
    }

    @Test
    fun grantsAreScopedToTheConversation() {
        val grants = AgentSessionApprovalGrants()
        val findings = listOf(finding(level = ToolRiskLevel.MEDIUM))
        grants.grant("conv-1", "terminal", findings)
        assertFalse(grants.isGranted("conv-2", "terminal", findings))
    }

    @Test
    fun grantsAreScopedToTheTool() {
        val grants = AgentSessionApprovalGrants()
        val findings = listOf(finding(level = ToolRiskLevel.MEDIUM))
        grants.grant("conv-1", "terminal", findings)
        assertFalse(grants.isGranted("conv-1", "write_file", findings))
    }

    @Test
    fun everyFindingMustBeGranted() {
        val grants = AgentSessionApprovalGrants()
        val single = finding(level = ToolRiskLevel.HIGH)
        val combined = listOf(
            single,
            finding(category = ToolRiskCategory.DESTRUCTIVE_DELETE, level = ToolRiskLevel.HIGH, detail = "递归删除 /data"),
        )
        grants.grant("conv-1", "terminal", listOf(single))
        assertFalse(grants.isGranted("conv-1", "terminal", combined))
        grants.grant("conv-1", "terminal", combined)
        assertTrue(grants.isGranted("conv-1", "terminal", combined))
    }

    @Test
    fun criticalRiskNeverAcceptsASessionGrant() {
        val grants = AgentSessionApprovalGrants()
        val critical = finding(level = ToolRiskLevel.CRITICAL)
        grants.grant("conv-1", "terminal", listOf(critical))
        assertFalse("CRITICAL 不能落会话授权", grants.isGranted("conv-1", "terminal", listOf(critical)))
        assertTrue(grants.snapshot().isEmpty())
    }

    @Test
    fun levelIsPartOfTheGrantKeySoEscalationStillAsks() {
        // B3 回归：允许"重启到 recovery"（BOOT_CHAIN_WRITE/MEDIUM）不能放开
        // `dd of=/dev/block/by-name/boot`（BOOT_CHAIN_WRITE/CRITICAL）。
        val grants = AgentSessionApprovalGrants()
        val reboot = finding(
            category = ToolRiskCategory.BOOT_CHAIN_WRITE,
            level = ToolRiskLevel.MEDIUM,
            detail = "重启到 recovery 模式",
        )
        val dd = finding(
            category = ToolRiskCategory.BOOT_CHAIN_WRITE,
            level = ToolRiskLevel.CRITICAL,
            detail = "dd 写入 /dev/block/by-name/boot",
        )
        grants.grant("conv-1", "terminal", listOf(reboot))
        assertTrue(grants.isGranted("conv-1", "terminal", listOf(reboot)))
        assertFalse("更高等级的同类别风险必须重新确认", grants.isGranted("conv-1", "terminal", listOf(dd)))

        // 反向也不成立：CRITICAL 的授权（虽然不会落盘）不会放开 MEDIUM。
        val higher = AgentSessionApprovalGrants()
        higher.grant("conv-1", "terminal", listOf(dd))
        assertFalse(higher.isGranted("conv-1", "terminal", listOf(reboot)))
    }

    @Test
    fun emptyFindingsNeverMatch() {
        val grants = AgentSessionApprovalGrants()
        grants.grant("conv-1", "terminal", emptyList())
        assertFalse(grants.isGranted("conv-1", "terminal", emptyList()))
        assertTrue(grants.snapshot().isEmpty())
    }

    @Test
    fun clearingAConversationRevokesItsGrants() {
        val grants = AgentSessionApprovalGrants()
        val findings = listOf(finding(level = ToolRiskLevel.HIGH))
        grants.grant("conv-1", "terminal", findings)
        grants.clear("conv-1")
        assertFalse(grants.isGranted("conv-1", "terminal", findings))
        assertTrue(grants.snapshot().isEmpty())
    }

    @Test
    fun nullConversationFallsBackToAProcessWideScope() {
        val grants = AgentSessionApprovalGrants()
        val findings = listOf(finding(level = ToolRiskLevel.HIGH))
        grants.grant(null, "terminal", findings)
        assertTrue(grants.isGranted(null, "terminal", findings))
        assertTrue(grants.isGranted("  ", "terminal", findings))
        assertFalse(grants.isGranted("conv-1", "terminal", findings))
    }

    @Test
    fun grantsAreNotPersistedToAnyPreferenceStore() {
        val grants = AgentSessionApprovalGrants()
        grants.grant(
            "conv-1",
            "terminal",
            listOf(finding(category = ToolRiskCategory.SYSTEM_DATA_WRITE, level = ToolRiskLevel.HIGH)),
        )
        // 只有内存快照，没有写盘入口：新实例等价于进程重启后自然失效。
        assertEquals(
            setOf("terminal|system_data_write|high"),
            grants.snapshot().values.single(),
        )
        assertTrue(AgentSessionApprovalGrants().snapshot().isEmpty())
    }
}
