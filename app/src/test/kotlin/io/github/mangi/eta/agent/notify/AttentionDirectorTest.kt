package io.github.mangi.eta.agent.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 统一裁决中枢 + 号段唯一源 + 会话分类唯一源的纯 JVM 回归（2026-11-15 从根重构后的结构护栏）。
 *
 * 钉住：号段合法性 / ID↔channel 匹配 / 系统开关 / 前台静默（unattended 由 subject 派生）/
 * 收尾句柄 / 终态归属 / fail-closed / Policy↔Registry 双向一致 / 全 channel 值域可达。
 */
class AttentionDirectorTest {

    private fun ctx(
        notificationsEnabled: Boolean = true,
        channelEnabled: Boolean = true,
        appForeground: Boolean = false,
    ) = DisturbContext(notificationsEnabled, channelEnabled, appForeground)

    private fun intent(
        id: Int = NotificationIdRegistry.idOf("run_result", "conv-a"),
        channel: NotificationIdRegistry.NotificationChannel = NotificationIdRegistry.channelOf("run_result"),
        subject: String? = "conv-a",
        suppressWhenForeground: Boolean = false,
        replaceOngoing: List<NotificationKey> = emptyList(),
    ) = AttentionIntent(
        key = NotificationKey(id),
        channel = channel,
        subject = subject,
        suppressWhenForeground = suppressWhenForeground,
        replaceOngoing = replaceOngoing,
    )

    @Test
    fun `registered id with matching channel is accepted`() {
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
    fun `id channel mismatch is rejected before any notify`() {
        // run_result 段的 owner 只允许 RUN_RESULTS；落到 ATTENTION 会被系统静默丢弃，必须挡在裁决层。
        val d = AttentionDirector.decide(
            intent(channel = NotificationIdRegistry.NotificationChannel.ATTENTION), ctx(),
        )
        assertFalse(d.publish)
        assertEquals(AttentionDirector.RejectReason.CHANNEL_MISMATCH, d.reject)
    }

    @Test
    fun `job wakeup channel fallback is explicitly allowed`() {
        val id = NotificationIdRegistry.idOf(NotificationIdRegistry.OWNER_JOB_WAKEUP, "job-1")
        for (ch in listOf(
            NotificationIdRegistry.NotificationChannel.BACKGROUND_COMMANDS,
            NotificationIdRegistry.NotificationChannel.PROACTIVE,
        )) {
            assertTrue(AttentionDirector.decide(intent(id = id, channel = ch, subject = "conv-wakeup-x"), ctx()).publish)
        }
    }

    @Test
    fun `every policy reserved id is registered`() {
        NotificationPolicy.reservedSegments().forEach { seg ->
            seg.forEach { id -> assertNotNull("policy 段 $id 未登记", NotificationIdRegistry.ownerOfNotificationId(id)) }
        }
    }

    @Test
    fun `policy and registry derive the exact same ids in both directions`() {
        // 从根杜绝「测试测影子副本」：Policy 的每个派生函数必须与 Registry 完全相等。
        repeat(200) { i ->
            assertEquals(
                NotificationIdRegistry.idOf("notify_user_proactive", "k$i"),
                NotificationPolicy.proactiveId("k$i"),
            )
            assertEquals(
                NotificationIdRegistry.idOf(NotificationIdRegistry.OWNER_JOB_WAKEUP, "job$i"),
                NotificationPolicy.jobWakeupId("job$i"),
            )
            assertEquals(
                NotificationIdRegistry.idOf("run_result", "conv$i"),
                NotificationPolicy.runResultId("conv$i"),
            )
            assertEquals(
                NotificationIdRegistry.idOf("approval_attention", "a$i"),
                NotificationPolicy.approvalAttentionId("a$i"),
            )
        }
        assertEquals(NotificationIdRegistry.idOf("approval_pending"), NotificationPolicy.APPROVAL_PENDING_ID)
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
        assertEquals(AttentionDirector.RejectReason.CHANNEL_DISABLED,
            AttentionDirector.decide(intent(), ctx(channelEnabled = false)).reject)
    }

    @Test
    fun `foreground attended chat suppresses result notification`() {
        val d = AttentionDirector.decide(intent(suppressWhenForeground = true), ctx(appForeground = true))
        assertFalse(d.publish)
        assertEquals(AttentionDirector.RejectReason.FOREGROUND_SUPPRESSED, d.reject)
    }

    @Test
    fun `unattended subject is never suppressed even in foreground`() {
        // unattended 由 subject 经 AttentionSubject 派生，不再由发起点手填。
        for (s in listOf("conv-wakeup-abc", "conv-schedule-abc")) {
            assertTrue(
                AttentionDirector.decide(
                    intent(subject = s, suppressWhenForeground = true), ctx(appForeground = true),
                ).publish,
            )
        }
    }

    @Test
    fun `replaceOngoing honored even when publish suppressed`() {
        val keys = listOf(NotificationKey(9_610))
        val d = AttentionDirector.decide(
            intent(replaceOngoing = keys, suppressWhenForeground = true), ctx(appForeground = true),
        )
        assertFalse(d.publish); assertEquals(keys, d.cancelOngoing)
    }

    @Test
    fun `replaceOngoing honored on successful publish`() {
        val keys = listOf(NotificationKey(9_610))
        val d = AttentionDirector.decide(intent(replaceOngoing = keys), ctx())
        assertTrue(d.publish); assertEquals(keys, d.cancelOngoing)
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
    fun `fixedIdOf is fail closed on typo key`() {
        // 旧实现 else->0 会让拼错 key 静默得到段首、顶掉语音助手通知；必须抛错。
        runCatching { NotificationIdRegistry.fixedIdOf("voice", "read_aoud") }
            .also { assertTrue("拼错 key 必须 fail-closed", it.isFailure) }
    }

    @Test
    fun `every owner has allowed channels and every channel is reachable`() {
        assertTrue(NotificationIdRegistry.everyOwnerHasAllowedChannels())
    }

    @Test
    fun `wakeup subject owns the 9602 terminal and others own run_result`() {
        assertEquals(AttentionSubject.TerminalOwner.JOB_WAKEUP_9602,
            AttentionSubject.terminalOwnerOf("conv-wakeup-abc"))
        assertEquals(AttentionSubject.TerminalOwner.RUN_RESULT,
            AttentionSubject.terminalOwnerOf("conv-schedule-abc"))
        assertEquals(AttentionSubject.TerminalOwner.RUN_RESULT,
            AttentionSubject.terminalOwnerOf("conv-user-abc"))
        // 前缀唯一定义：BackgroundRunPolicy 直接引用 AttentionSubject，杜绝第三处字面量。
        assertEquals(AttentionSubject.JOB_WAKEUP_PREFIX,
            io.github.mangi.eta.agent.runtime.BackgroundRunPolicy.JOB_WAKEUP_PREFIX)
        assertEquals(AttentionSubject.SCHEDULED_TASK_PREFIX,
            io.github.mangi.eta.agent.runtime.BackgroundRunPolicy.SCHEDULED_TASK_PREFIX)
    }

    @Test
    fun `macro fixed ids use distinct slots so cancel hits the posted one`() {
        val recording = NotificationIdRegistry.fixedIdOf("macro_recording", "recording")
        val stopped = NotificationIdRegistry.fixedIdOf("macro_recording", "stopped")
        org.junit.Assert.assertNotEquals(recording, stopped)
        assertTrue(recording in 9_600..9_601)
        assertTrue(stopped in 9_600..9_601)
        assertEquals(recording, NotificationIdRegistry.fixedIdOf("macro_recording", "recording"))
    }

    @Test
    fun `scheduled task posted id equals cancel id`() {
        val a = NotificationIdRegistry.idOf("scheduled_task_result", "task-xyz")
        val b = NotificationIdRegistry.idOf("scheduled_task_result", "task-xyz")
        assertEquals(a, b)
        assertTrue(a in 9_001..9_290)
    }
}
