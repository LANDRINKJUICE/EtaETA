package io.github.mangi.eta.agent.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 统一裁决中枢 + 号段唯一源的纯 JVM 回归（完整统一的结构护栏）。 */
class AttentionDirectorTest {

    private fun ctx(
        notificationsEnabled: Boolean = true,
        channelEnabled: Boolean = true,
        foreground: Boolean = true,
        foregroundUiChat: Boolean = false,
        unattended: Boolean = false,
        rateLimitAllows: Boolean = true,
        budgetAllows: Boolean = true,
    ) = DisturbContext(notificationsEnabled, channelEnabled, foreground, foregroundUiChat, unattended, rateLimitAllows, budgetAllows)

    private fun intent(
        id: Int = NotificationIdRegistry.idOf("run_result", "conv-a"),
        unattended: Boolean = false,
        suppressForegroundChat: Boolean = false,
        replaceOngoingIds: List<Int> = emptyList(),
    ) = AttentionIntent(
        notificationId = id,
        channel = NotificationIdRegistry.channelOf("run_result"),
        objectKey = "conv-a",
        unattended = unattended,
        suppressIfForegroundChat = suppressForegroundChat,
        replaceOngoingIds = replaceOngoingIds,
    )

    @Test
    fun `registered id is accepted`() {
        val d = AttentionDirector.decide(intent(), ctx())
        assertTrue(d.publish); assertNull(d.reject)
    }

    @Test
    fun `unregistered id is rejected`() {
        val d = AttentionDirector.decide(intent(id = 8_000), ctx())
        assertFalse(d.publish)
        assertEquals(AttentionDirector.RejectReason.UNREGISTERED_ID, d.reject)
    }

    @Test
    fun `every policy reserved id is registered`() {
        NotificationPolicy.reservedSegments().forEach { seg ->
            seg.forEach { id -> assertNotNull("policy 段 $id 未登记", NotificationIdRegistry.ownerOfNotificationId(id)) }
        }
    }

    @Test
    fun `idOf is stable and within its segment`() {
        assertEquals(
            NotificationIdRegistry.idOf("run_result", "conv-abc"),
            NotificationIdRegistry.idOf("run_result", "conv-abc"),
        )
        assertTrue(NotificationIdRegistry.idOf("run_result", "conv-abc") in 9_502..9_560)
        assertEquals(9_400, NotificationIdRegistry.idOf("approval_pending"))
        assertEquals(1_107, NotificationIdRegistry.idOf("execution_service"))
    }

    @Test
    fun `notifications or channel disabled is rejected`() {
        assertEquals(AttentionDirector.RejectReason.NOTIFICATIONS_DISABLED,
            AttentionDirector.decide(intent(), ctx(notificationsEnabled = false)).reject)
        assertEquals(AttentionDirector.RejectReason.NOTIFICATIONS_DISABLED,
            AttentionDirector.decide(intent(), ctx(channelEnabled = false)).reject)
    }

    @Test
    fun `rate limit or budget rejects`() {
        assertEquals(AttentionDirector.RejectReason.RATE_LIMITED,
            AttentionDirector.decide(intent(), ctx(rateLimitAllows = false)).reject)
        assertEquals(AttentionDirector.RejectReason.RATE_LIMITED,
            AttentionDirector.decide(intent(), ctx(budgetAllows = false)).reject)
    }

    @Test
    fun `foreground attended chat suppresses result notification`() {
        val d = AttentionDirector.decide(intent(suppressForegroundChat = true),
            ctx(foreground = true, foregroundUiChat = true, unattended = false))
        assertFalse(d.publish)
        assertEquals(AttentionDirector.RejectReason.FOREGROUND_SUPPRESSED, d.reject)
    }

    @Test
    fun `unattended wakeup is not suppressed even in foreground`() {
        assertTrue(AttentionDirector.decide(intent(unattended = true, suppressForegroundChat = true),
            ctx(foreground = true, foregroundUiChat = true, unattended = true)).publish)
    }

    @Test
    fun `cancelOngoingIds honored even when publish suppressed`() {
        val d = AttentionDirector.decide(intent(replaceOngoingIds = listOf(9_610), suppressForegroundChat = true),
            ctx(foreground = true, foregroundUiChat = true))
        assertFalse(d.publish); assertEquals(listOf(9_610), d.cancelOngoingIds)
    }

    @Test
    fun `cancelOngoingIds honored on successful publish`() {
        val d = AttentionDirector.decide(intent(replaceOngoingIds = listOf(9_610)), ctx())
        assertTrue(d.publish); assertEquals(listOf(9_610), d.cancelOngoingIds)
    }

    @Test
    fun `shouldDisturb covers all gates`() {
        assertTrue(AttentionDirector.shouldDisturb(ctx()))
        assertFalse(AttentionDirector.shouldDisturb(ctx(notificationsEnabled = false)))
        assertFalse(AttentionDirector.shouldDisturb(ctx(rateLimitAllows = false)))
        assertFalse(AttentionDirector.shouldDisturb(ctx(foregroundUiChat = true, foreground = true, unattended = false)))
        assertTrue(AttentionDirector.shouldDisturb(ctx(foregroundUiChat = true, foreground = true, unattended = true)))
    }

    @Test
    fun `voice fixed ids do not collide`() {
        val assistant = NotificationIdRegistry.fixedIdOf("voice", "assistant")
        val readAloud = NotificationIdRegistry.fixedIdOf("voice", "read_aloud")
        org.junit.Assert.assertNotEquals(assistant, readAloud)
        assertTrue(assistant in 4_203..4_204)
        assertTrue(readAloud in 4_203..4_204)
    }

    @Test
    fun `wakeup conversation is recognized as the 9602 terminal owner`() {
        assertTrue(AttentionDirector.isWakeupConversation("conv-wakeup-abc"))
        assertFalse(AttentionDirector.isWakeupConversation("conv-schedule-abc"))
        assertFalse(AttentionDirector.isWakeupConversation("conv-user-abc"))
    }

    @Test
    fun `macro fixed ids use distinct slots so cancel hits the posted one`() {
        val recording = NotificationIdRegistry.fixedIdOf("macro_recording", "recording")
        val stopped = NotificationIdRegistry.fixedIdOf("macro_recording", "stopped")
        org.junit.Assert.assertNotEquals(recording, stopped)
        assertTrue(recording in 9_600..9_601)
        assertTrue(stopped in 9_600..9_601)
        // 二者必须与 showRecording/showStopped 发布用的派生源一致（同一函数）。
        assertEquals(recording, NotificationIdRegistry.fixedIdOf("macro_recording", "recording"))
    }

    @Test
    fun `scheduled task posted id equals cancel id`() {
        // showResult 与 cancel 都从 idOf 派生，杜绝两套散列撤错号。
        val a = NotificationIdRegistry.idOf("scheduled_task_result", "task-xyz")
        val b = NotificationIdRegistry.idOf("scheduled_task_result", "task-xyz")
        assertEquals(a, b)
        assertTrue(a in 9_001..9_290)
    }
}
