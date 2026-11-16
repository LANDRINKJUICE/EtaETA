package io.github.mangi.eta.agent.approval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 审批卡「用哪种载体 / 通知上放哪些 action」裁决的纯 JVM 回归。
 *
 * 钉住定稿（2026-11-12）：后台一律走通知、前台聊天流 + 强通知；通知最多 3 个 action，
 * 为 RemoteInput「后台回复」固定预留 1 个位置；危险卡决策按钮只留「允许一次 / 拒绝」两个，
 * 打字 = 拒绝并给纠正建议。这些逻辑不引用 android.*，是执行层接线正确的前提。
 */
class AgentApprovalAttentionPolicyTest {

    private fun dangerRequest(
        allowSession: Boolean = true,
        allowFreeText: Boolean = true,
    ): AgentApprovalRequest = AgentApprovalRequest(
        id = "danger-1",
        kind = AgentApprovalKind.ToolRisk,
        toolName = "terminal",
        conversationId = "conv-1",
        runId = "run-1",
        subject = "rm -rf /system/x",
        options = AgentApprovalOption.dangerOptions(allowSession = allowSession),
        allowFreeText = allowFreeText,
    )

    private fun askRequest(optionCount: Int, allowFreeText: Boolean = false): AgentApprovalRequest =
        AgentApprovalRequest(
            id = "ask-1",
            kind = AgentApprovalKind.AskUser,
            toolName = "ask_user",
            conversationId = "conv-1",
            runId = "run-1",
            subject = "选哪个？",
            question = "选哪个？",
            options = (1..optionCount).map {
                AgentApprovalOption("opt$it", AgentApprovalOptionKind.Choice, label = "选项$it")
            },
            allowFreeText = allowFreeText,
        )

    // ---- decide：载体裁决（三层架构：没有 OVERLAY 了） ----

    @Test
    fun `no pending requests collapses every surface to NONE`() {
        assertEquals(
            AgentApprovalSurface.NONE,
            AgentApprovalAttentionPolicy.decide(etaInForeground = true, pendingCount = 0),
        )
        assertEquals(
            AgentApprovalSurface.NONE,
            AgentApprovalAttentionPolicy.decide(etaInForeground = false, pendingCount = -3),
        )
    }

    @Test
    fun `foreground uses chat inline card`() {
        assertEquals(
            AgentApprovalSurface.CHAT_INLINE,
            AgentApprovalAttentionPolicy.decide(etaInForeground = true, pendingCount = 2),
        )
        assertEquals(
            AgentApprovalSurface.CHAT_INLINE,
            AgentApprovalAttentionPolicy.decide(etaInForeground = true, pendingCount = 1),
        )
    }

    @Test
    fun `background always uses notification`() {
        assertEquals(
            AgentApprovalSurface.NOTIFICATION,
            AgentApprovalAttentionPolicy.decide(etaInForeground = false, pendingCount = 1),
        )
        assertEquals(
            AgentApprovalSurface.NOTIFICATION,
            AgentApprovalAttentionPolicy.decide(etaInForeground = false, pendingCount = 3),
        )
    }

    // ---- shouldEmitAttentionNotification ----

    @Test
    fun `foreground chat inline also emits attention notification`() {
        // 在别的会话 / 设置页时根本不知道触发了卡，前台不能只靠聊天流卡片 + 无声 9400。
        assertTrue(
            "前台 CHAT_INLINE 必须补发 HIGH 行动通知，否则停在别的页面会全盲等超时",
            AgentApprovalAttentionPolicy.shouldEmitAttentionNotification(AgentApprovalSurface.CHAT_INLINE),
        )
    }

    @Test
    fun `notification surface emits attention notification`() {
        assertTrue(
            AgentApprovalAttentionPolicy.shouldEmitAttentionNotification(AgentApprovalSurface.NOTIFICATION),
        )
    }

    @Test
    fun `none does not emit attention notification`() {
        assertFalse(
            AgentApprovalAttentionPolicy.shouldEmitAttentionNotification(AgentApprovalSurface.NONE),
        )
    }

    // ---- replyActionReserved：RemoteInput 打字位 ----

    @Test
    fun `reply action reserved only when free text allowed`() {
        assertTrue(AgentApprovalAttentionPolicy.replyActionReserved(dangerRequest(allowFreeText = true)))
        assertTrue(AgentApprovalAttentionPolicy.replyActionReserved(askRequest(2, allowFreeText = true)))
        assertFalse(AgentApprovalAttentionPolicy.replyActionReserved(askRequest(2, allowFreeText = false)))
    }

    // ---- notificationActions：危险卡 ----

    @Test
    fun `danger card keeps exactly allow-once and deny, reserving third slot for reply`() {
        val actions = AgentApprovalAttentionPolicy.notificationActions(dangerRequest())
        assertEquals(
            listOf(AgentApprovalOption.ALLOW_ONCE, AgentApprovalOption.DENY),
            actions,
        )
        // 「本会话允许同类」让位给打字；「拒绝并说明」由 RemoteInput 打字承载。
        assertFalse(actions.contains(AgentApprovalOption.ALLOW_SESSION))
        assertFalse(actions.contains(AgentApprovalOption.DENY_WITH_FEEDBACK))
        assertFalse(AgentApprovalAttentionPolicy.needsOpenAction(dangerRequest()))
    }

    @Test
    fun `danger card without allow-session still keeps both critical decisions`() {
        // CRITICAL / 无会话 id 时请求里本来没有 ALLOW_SESSION，仍要保留允许一次 + 拒绝。
        val actions = AgentApprovalAttentionPolicy.notificationActions(dangerRequest(allowSession = false))
        assertEquals(listOf(AgentApprovalOption.ALLOW_ONCE, AgentApprovalOption.DENY), actions)
    }

    @Test
    fun `danger card never drops deny even when free text not allowed`() {
        // 防御性：万一 allowFreeText 被关，决策按钮最多可占满 3 个，但拒绝必须在。
        val actions = AgentApprovalAttentionPolicy.notificationActions(dangerRequest(allowFreeText = false))
        assertTrue(actions.contains(AgentApprovalOption.DENY))
        assertTrue(actions.contains(AgentApprovalOption.ALLOW_ONCE))
    }

    // ---- notificationActions：询问卡 ----

    @Test
    fun `ask card with two options exposes them and reserves reply slot`() {
        val request = askRequest(2, allowFreeText = true)
        assertEquals(listOf("opt1", "opt2"), AgentApprovalAttentionPolicy.notificationActions(request))
        assertFalse(AgentApprovalAttentionPolicy.needsOpenAction(request))
    }

    @Test
    fun `ask card with three options drops all decisions and needs open action`() {
        // 预留 1 个打字位后决策预算只剩 2，3 个选项平铺不下 → 「去确认」进前台看完整选项。
        val request = askRequest(3, allowFreeText = true)
        assertTrue(AgentApprovalAttentionPolicy.notificationActions(request).isEmpty())
        assertTrue(AgentApprovalAttentionPolicy.needsOpenAction(request))
    }

    @Test
    fun `ask card without free text can expose three decisions`() {
        // 没有打字位时决策预算是 3。
        val request = askRequest(3, allowFreeText = false)
        assertEquals(listOf("opt1", "opt2", "opt3"), AgentApprovalAttentionPolicy.notificationActions(request))
        assertFalse(AgentApprovalAttentionPolicy.needsOpenAction(request))
    }

    @Test
    fun `ask card with more than three options needs an open-to-handle action`() {
        val request = askRequest(4, allowFreeText = false)
        assertTrue(AgentApprovalAttentionPolicy.notificationActions(request).isEmpty())
        assertTrue(AgentApprovalAttentionPolicy.needsOpenAction(request))
    }

    @Test
    fun `ask card with only free text needs an open-to-handle action`() {
        val request = askRequest(0, allowFreeText = true)
        assertTrue(AgentApprovalAttentionPolicy.notificationActions(request).isEmpty())
        assertTrue(AgentApprovalAttentionPolicy.needsOpenAction(request))
    }

    @Test
    fun `ask card deduplicates option ids`() {
        val request = AgentApprovalRequest(
            id = "ask-dup",
            kind = AgentApprovalKind.AskUser,
            toolName = "ask_user",
            conversationId = "conv-1",
            runId = "run-1",
            subject = "q",
            options = listOf(
                AgentApprovalOption("x", AgentApprovalOptionKind.Choice),
                AgentApprovalOption("x", AgentApprovalOptionKind.Choice),
                AgentApprovalOption("y", AgentApprovalOptionKind.Choice),
            ),
        )
        assertEquals(listOf("x", "y"), AgentApprovalAttentionPolicy.notificationActions(request))
    }

    // ---- 通知 action 总数上限（最关键的回归钉子） ----

    @Test
    fun `notification action limit is three and decision budget leaves one for reply`() {
        assertEquals(3, AgentApprovalAttentionPolicy.NOTIFICATION_ACTION_LIMIT)
        assertEquals(2, AgentApprovalAttentionPolicy.NOTIFICATION_DECISION_ACTION_LIMIT)
    }

    @Test
    fun `every request produces at most three notification actions`() {
        // 决策按钮 + 后台回复 + 去确认，任何组合都不得越过 Android 折叠态 3 个的硬上限。
        val requests = listOf(
            dangerRequest(),
            dangerRequest(allowSession = false),
            dangerRequest(allowFreeText = false),
            askRequest(0, allowFreeText = true),
            askRequest(2, allowFreeText = true),
            askRequest(3, allowFreeText = true),
            askRequest(4, allowFreeText = true),
            askRequest(3, allowFreeText = false),
        )
        requests.forEach { request ->
            val count = AgentApprovalAttentionPolicy.notificationActionCount(request)
            assertTrue(
                "request=${request.id} kind=${request.kind} -> $count actions (>3)",
                count <= AgentApprovalAttentionPolicy.NOTIFICATION_ACTION_LIMIT,
            )
        }
    }

    @Test
    fun `action count matches the exact actions rendered on the notification`() {
        // 精确计数，钉死「policy 说 N、执行层放 N」——只断言 <=3 会让「少放一个」照样全绿。
        // 危险卡：2 决策 + 1 回复 = 3。
        assertEquals(3, AgentApprovalAttentionPolicy.notificationActionCount(dangerRequest()))
        // 询问卡 2 选项 + freeText：2 决策 + 1 回复 = 3。
        assertEquals(3, AgentApprovalAttentionPolicy.notificationActionCount(askRequest(2, allowFreeText = true)))
        // 询问卡 3 选项 + freeText：决策放不下 → 1 回复 + 1 去确认 = 2（M2 回归：去确认不能被吞）。
        assertEquals(2, AgentApprovalAttentionPolicy.notificationActionCount(askRequest(3, allowFreeText = true)))
        assertTrue(AgentApprovalAttentionPolicy.needsOpenAction(askRequest(3, allowFreeText = true)))
        // 纯自由输入询问卡：1 回复 + 1 去确认 = 2。
        assertEquals(2, AgentApprovalAttentionPolicy.notificationActionCount(askRequest(0, allowFreeText = true)))
        // 询问卡 3 选项、无 freeText：3 决策 = 3，无回复位、无去确认。
        assertEquals(3, AgentApprovalAttentionPolicy.notificationActionCount(askRequest(3, allowFreeText = false)))
    }

    @Test
    fun `danger options always contain deny so notification never loses the reject button`() {
        // 真正的安全不变量：危险卡通知绝不只剩放行按钮。policy 用 filter { it in ids }，
        // 兜底来自 dangerOptions 恒含 DENY；两种情况都钉住。
        for (allowSession in listOf(true, false)) {
            val ids = AgentApprovalOption.dangerOptions(allowSession = allowSession).map { it.id }
            assertTrue(
                "allowSession=$allowSession 时 dangerOptions 必须含 DENY",
                ids.contains(AgentApprovalOption.DENY),
            )
            assertTrue(
                "allowSession=$allowSession 时危险卡通知必须含 DENY 决策按钮",
                AgentApprovalAttentionPolicy
                    .notificationActions(dangerRequest(allowSession = allowSession))
                    .contains(AgentApprovalOption.DENY),
            )
        }
    }
}
