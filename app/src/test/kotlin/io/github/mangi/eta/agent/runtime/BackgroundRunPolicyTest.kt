package io.github.mangi.eta.agent.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 守住「所有后台 run 一启动就能被 UI 认领进聊天列表 + 长任务不被总墙钟误杀」的纯逻辑判据。
 * 对应 67d0a33 遗留根因（用 checkpoint 近似会话是否存在）与 10min 一刀切超时。
 */
class BackgroundRunPolicyTest {

    /**
     * 结构护栏（SOP §4.4 机器红灯）：所有无人值守会话前缀必须登记进
     * [BackgroundRunPolicy.UNATTENDED_PREFIXES] 并被识别，否则 `conversationsToAdopt` 永远不
     * 认领它 → 又退化成「运行中不进列表、散乱一地」。将来在别处加新前缀却忘了登记到这张表，
     * 必须同步在这里的「已知前缀」清单里补上（编译/测试会提醒口径漂移）。
     */
    @Test
    fun everyRegisteredBackgroundPrefixIsRecognized() {
        val knownPrefixes = listOf(
            BackgroundRunPolicy.JOB_WAKEUP_PREFIX,
            BackgroundRunPolicy.SCHEDULED_TASK_PREFIX,
        )
        knownPrefixes.forEach { prefix ->
            assertTrue("前缀 $prefix 必须登记进 UNATTENDED_PREFIXES", prefix in BackgroundRunPolicy.UNATTENDED_PREFIXES)
            assertTrue("前缀 $prefix 必须被 isUnattendedConversation 识别",
                BackgroundRunPolicy.isUnattendedConversation(prefix + "abc123"))
        }
        // UNATTENDED_PREFIXES 本身不能有重复项，也不能漏掉任何已知前缀。
        assertEquals("UNATTENDED_PREFIXES 不应有重复项",
            BackgroundRunPolicy.UNATTENDED_PREFIXES.size,
            BackgroundRunPolicy.UNATTENDED_PREFIXES.toSet().size)
        assertEquals("UNATTENDED_PREFIXES 必须恰好覆盖所有已知后台前缀",
            knownPrefixes.toSet(), BackgroundRunPolicy.UNATTENDED_PREFIXES.toSet())
    }

    @Test
    fun unattendedPrefixesAreRecognized() {
        assertTrue(BackgroundRunPolicy.isUnattendedConversation("conv-wakeup-abc"))
        assertTrue(BackgroundRunPolicy.isUnattendedConversation("conv-schedule-abc"))
        // 普通用户会话、只读归档、run: 前缀都不算无人值守后台会话。
        assertFalse(BackgroundRunPolicy.isUnattendedConversation("conv-normal-uuid"))
        assertFalse(BackgroundRunPolicy.isUnattendedConversation("archive-abc"))
        assertFalse(BackgroundRunPolicy.isUnattendedConversation("assistant-abc"))
        assertFalse(BackgroundRunPolicy.isUnattendedConversation("run:scheduled-1"))
        assertFalse(BackgroundRunPolicy.isUnattendedConversation(""))
    }

    @Test
    fun checkpointPresenceDoesNotBlockAdoption() {
        // 67d0a33 根因复现：唤醒 run 一启动就有 checkpoint，旧实现据此不认领。
        // 新判据只看 conversationsById 里有没有会话；candidate 有没有 checkpoint 不参与判定，
        // 因此「有 checkpoint 的唤醒 active run + 会话缺失」必须命中补建。
        val wakeup = BackgroundRunPolicy.AdoptionCandidate("run-w", "conv-wakeup-x")
        val scheduled = BackgroundRunPolicy.AdoptionCandidate("run-s", "conv-schedule-y")
        val result = BackgroundRunPolicy.conversationsToAdopt(
            activeRuns = listOf(wakeup, scheduled),
            existingConversationIds = emptySet(),
        )
        assertEquals(listOf("run-w", "run-s"), result.map { it.runId })
    }

    @Test
    fun existingConversationIsNotReadopted() {
        val run = BackgroundRunPolicy.AdoptionCandidate("run-w", "conv-wakeup-x")
        assertTrue(BackgroundRunPolicy.conversationsToAdopt(
            listOf(run), setOf("conv-wakeup-x"),
        ).isEmpty())
    }

    @Test
    fun normalUserConversationIsNeverRevived() {
        // 用户删掉 / 备份替换掉的普通会话，不能被凭空复活。
        val normal = BackgroundRunPolicy.AdoptionCandidate("run-n", "conv-normal-deleted")
        assertTrue(BackgroundRunPolicy.conversationsToAdopt(
            listOf(normal), emptySet(),
        ).isEmpty())
    }

    @Test
    fun duplicateConversationIsAdoptedOnce() {
        val a = BackgroundRunPolicy.AdoptionCandidate("run-1", "conv-wakeup-shared")
        val b = BackgroundRunPolicy.AdoptionCandidate("run-2", "conv-wakeup-shared")
        val result = BackgroundRunPolicy.conversationsToAdopt(listOf(a, b), emptySet())
        assertEquals(1, result.size)
        assertEquals("run-1", result.single().runId)
    }

    @Test
    fun blankConversationIdIsIgnored() {
        val blank = BackgroundRunPolicy.AdoptionCandidate("run-x", "")
        assertTrue(BackgroundRunPolicy.conversationsToAdopt(listOf(blank), emptySet()).isEmpty())
    }

    @Test
    fun adoptionIsStableAcrossRepeatedCalls() {
        // 幂等：同一输入两次调用结果一致（认领是纯投影，无副作用）。
        val runs = listOf(
            BackgroundRunPolicy.AdoptionCandidate("run-w", "conv-wakeup-x"),
            BackgroundRunPolicy.AdoptionCandidate("run-n", "conv-normal"),
        )
        assertEquals(
            BackgroundRunPolicy.conversationsToAdopt(runs, emptySet()),
            BackgroundRunPolicy.conversationsToAdopt(runs, emptySet()),
        )
    }

    @Test
    fun longRunWithSteadyProgressIsNotKilledByTotalClock() {
        // 旧实现：总墙钟 10min 到点就杀。新：只要有进展，超过 10min 也不取消（受硬上限约束）。
        val started = 0L
        // 跑了 30 分钟，但每 30s 都有进展（lastProgress 紧跟 now）。
        assertFalse(BackgroundRunPolicy.shouldTimeOut(
            nowMillis = 30L * 60_000L,
            startedAtMillis = started,
            lastProgressAtMillis = 30L * 60_000L - 5_000L,
            idleTimeoutMillis = 3L * 60_000L,
            hardCapMillis = 60L * 60_000L,
        ))
    }

    @Test
    fun idleRunIsCancelled() {
        // 持续无进展（长思考/卡死/模型无响应）才按 idle 超时取消。
        assertTrue(BackgroundRunPolicy.shouldTimeOut(
            nowMillis = 4L * 60_000L,
            startedAtMillis = 0L,
            lastProgressAtMillis = 0L,
            idleTimeoutMillis = 3L * 60_000L,
            hardCapMillis = 60L * 60_000L,
        ))
    }

    @Test
    fun hardCapAlwaysWinsEvenWithProgress() {
        assertTrue(BackgroundRunPolicy.shouldTimeOut(
            nowMillis = 61L * 60_000L,
            startedAtMillis = 0L,
            lastProgressAtMillis = 61L * 60_000L,
            idleTimeoutMillis = 3L * 60_000L,
            hardCapMillis = 60L * 60_000L,
        ))
    }

    @Test
    fun progressJustBeforeIdleWindowKeepsRunAlive() {
        assertFalse(BackgroundRunPolicy.shouldTimeOut(
            nowMillis = 4L * 60_000L,
            startedAtMillis = 0L,
            lastProgressAtMillis = 3L * 60_000L,
            idleTimeoutMillis = 3L * 60_000L,
            hardCapMillis = 60L * 60_000L,
        ))
    }

    @Test
    fun longRunningToolIsNotKilledByIdleTimeout() {
        // 长工具执行期间不发中间事件：即使 idle 窗口已过，只要 activeTools>0 就不按 idle 杀。
        assertFalse(BackgroundRunPolicy.shouldTimeOut(
            nowMillis = 20L * 60_000L,
            startedAtMillis = 0L,
            lastProgressAtMillis = 0L,
            idleTimeoutMillis = 10L * 60_000L,
            hardCapMillis = 60L * 60_000L,
            activeTools = 1,
        ))
    }

    @Test
    fun hardCapStillWinsWhileToolIsRunning() {
        assertTrue(BackgroundRunPolicy.shouldTimeOut(
            nowMillis = 61L * 60_000L,
            startedAtMillis = 0L,
            lastProgressAtMillis = 61L * 60_000L,
            idleTimeoutMillis = 10L * 60_000L,
            hardCapMillis = 60L * 60_000L,
            activeTools = 2,
        ))
    }

    @Test
    fun idleAfterLastToolFinishedIsStillCancelled() {
        assertTrue(BackgroundRunPolicy.shouldTimeOut(
            nowMillis = 15L * 60_000L,
            startedAtMillis = 0L,
            lastProgressAtMillis = 3L * 60_000L,
            idleTimeoutMillis = 10L * 60_000L,
            hardCapMillis = 60L * 60_000L,
            activeTools = 0,
        ))
    }
}
