package io.github.mangi.eta.agent.approval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 后台审批卡"用哪种载体"裁决的纯 JVM 回归。
 *
 * 钉住 AgentApprovalAttentionPolicy 的三条硬规则：前台只走聊天流、后台优先悬浮层、
 * 悬浮层不可用才退通知；以及通知按钮映射（危险卡 ≤3、询问卡 ≤3、其余"打开处理"）。
 * 这些逻辑不引用 android.*，能在纯 JVM 上跑，是执行层接线正确的前提。
 */
class AgentApprovalAttentionPolicyTest {

    private fun dangerRequest(
        allowSession: Boolean = true,
    ): AgentApprovalRequest = AgentApprovalRequest(
        id = "danger-1",
        kind = AgentApprovalKind.ToolRisk,
        toolName = "terminal",
        conversationId = "conv-1",
        runId = "run-1",
        subject = "rm -rf /system/x",
        options = AgentApprovalOption.dangerOptions(allowSession = allowSession),
        allowFreeText = true,
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

    // ---- decide：载体裁决 ----

    @Test
    fun `no pending requests collapses every surface to NONE`() {
        assertEquals(
            AgentApprovalSurface.NONE,
            AgentApprovalAttentionPolicy.decide(
                etaInForeground = true,
                pendingCount = 0,
                overlayUsable = true,
            ),
        )
        assertEquals(
            AgentApprovalSurface.NONE,
            AgentApprovalAttentionPolicy.decide(
                etaInForeground = false,
                pendingCount = -3,
                overlayUsable = false,
            ),
        )
    }

    @Test
    fun `foreground uses chat inline card and never a second surface`() {
        assertEquals(
            AgentApprovalSurface.CHAT_INLINE,
            AgentApprovalAttentionPolicy.decide(
                etaInForeground = true,
                pendingCount = 2,
                overlayUsable = true,
            ),
        )
        // 即便悬浮层不可用，前台也只走聊天流，不额外发通知（避免重复打扰）。
        assertEquals(
            AgentApprovalSurface.CHAT_INLINE,
            AgentApprovalAttentionPolicy.decide(
                etaInForeground = true,
                pendingCount = 1,
                overlayUsable = false,
            ),
        )
    }

    @Test
    fun `background prefers overlay when usable`() {
        assertEquals(
            AgentApprovalSurface.OVERLAY,
            AgentApprovalAttentionPolicy.decide(
                etaInForeground = false,
                pendingCount = 1,
                overlayUsable = true,
            ),
        )
    }

    @Test
    fun `background falls back to notification when overlay is unusable`() {
        assertEquals(
            AgentApprovalSurface.NOTIFICATION,
            AgentApprovalAttentionPolicy.decide(
                etaInForeground = false,
                pendingCount = 1,
                overlayUsable = false,
            ),
        )
    }

    // ---- overlayUsable ----

    @Test
    fun `overlay is usable with either draw-over permission or accessibility overlay`() {
        assertTrue(AgentApprovalAttentionPolicy.overlayUsable(true, false))
        assertTrue(AgentApprovalAttentionPolicy.overlayUsable(false, true))
        assertTrue(AgentApprovalAttentionPolicy.overlayUsable(true, true))
        assertFalse(AgentApprovalAttentionPolicy.overlayUsable(false, false))
    }

    // ---- notificationActions：危险卡 ----

    @Test
    fun `danger card maps to at most three decision buttons without free-text option`() {
        val actions = AgentApprovalAttentionPolicy.notificationActions(dangerRequest())
        assertEquals(3, actions.size)
        assertEquals(
            listOf(
                AgentApprovalOption.ALLOW_ONCE,
                AgentApprovalOption.ALLOW_SESSION,
                AgentApprovalOption.DENY,
            ),
            actions,
        )
        // "拒绝并说明" 必须带自由输入，通知按钮无法收集，不能出现在通知上。
        assertFalse(actions.contains(AgentApprovalOption.DENY_WITH_FEEDBACK))
        assertFalse(AgentApprovalAttentionPolicy.needsOpenAction(dangerRequest()))
    }

    @Test
    fun `critical danger card drops allow-session and still has two decision buttons`() {
        val actions = AgentApprovalAttentionPolicy.notificationActions(dangerRequest(allowSession = false))
        assertEquals(
            listOf(AgentApprovalOption.ALLOW_ONCE, AgentApprovalOption.DENY),
            actions,
        )
        assertFalse(AgentApprovalAttentionPolicy.needsOpenAction(dangerRequest(allowSession = false)))
    }

    // ---- notificationActions：询问卡 ----

    @Test
    fun `ask card with three or fewer options exposes them directly`() {
        val actions = AgentApprovalAttentionPolicy.notificationActions(askRequest(3))
        assertEquals(listOf("opt1", "opt2", "opt3"), actions)
        assertFalse(AgentApprovalAttentionPolicy.needsOpenAction(askRequest(3)))
    }

    @Test
    fun `ask card with more than three options needs an open-to-handle action`() {
        assertTrue(AgentApprovalAttentionPolicy.notificationActions(askRequest(4)).isEmpty())
        assertTrue(AgentApprovalAttentionPolicy.needsOpenAction(askRequest(4)))
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

    @Test
    fun `notification action limit is three`() {
        assertEquals(3, AgentApprovalAttentionPolicy.NOTIFICATION_ACTION_LIMIT)
    }

    // ── 方案 1：前台也发 eta_attention 强通知，覆盖「前台但不在归属会话/在设置页」 ──

    @Test
    fun `foreground chat inline also emits attention notification`() {
        // 用户实测：在别的会话 / 设置页时根本不知道触发了卡。前台不能只靠聊天流卡片 + 无声 9400。
        assertTrue(
            "前台 CHAT_INLINE 必须补发 HIGH 行动通知，否则停在别的页面会全盲等超时",
            AgentApprovalAttentionPolicy.shouldEmitAttentionNotification(AgentApprovalSurface.CHAT_INLINE),
        )
    }

    @Test
    fun `notification fallback still emits attention notification`() {
        assertTrue(
            AgentApprovalAttentionPolicy.shouldEmitAttentionNotification(AgentApprovalSurface.NOTIFICATION),
        )
    }

    @Test
    fun `overlay and none do not double-emit attention notification`() {
        // 悬浮层已把卡片送到眼前，再弹通知是重复打扰；没有待确认则什么都不发。
        assertFalse(
            AgentApprovalAttentionPolicy.shouldEmitAttentionNotification(AgentApprovalSurface.OVERLAY),
        )
        assertFalse(
            AgentApprovalAttentionPolicy.shouldEmitAttentionNotification(AgentApprovalSurface.NONE),
        )
    }

    @Test
    fun `decide still routes foreground to chat inline`() {
        // 方案 1 不改变「前台主载体仍是聊天流卡片」的裁决，只改执行层补发通知。
        assertEquals(
            AgentApprovalSurface.CHAT_INLINE,
            AgentApprovalAttentionPolicy.decide(
                etaInForeground = true,
                pendingCount = 1,
                overlayUsable = true,
            ),
        )
    }
}
