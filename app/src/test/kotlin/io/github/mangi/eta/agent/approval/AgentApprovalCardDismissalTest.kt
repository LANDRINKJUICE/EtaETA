package io.github.mangi.eta.agent.approval

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/**
 * 钉住线上症状：**点了确认卡按钮之后，卡片必须当帧从聊天流消失**。
 *
 * 线上证据是"工具已经拿到 USER_DENIED，但卡片还在、内容还和别的消息叠在一起"，
 * 也就是决策通道是通的，问题在"渲染源（pending）与它的投影"这一侧。因此这里同时锁两件事：
 *
 * 1. 状态侧：决策／撤卡之后 [AgentApprovalCoordinator.pending] 必须是空的，
 *    而且 `resolved` 只用于 6 秒回显；
 * 2. 投影侧：[awaitingConfirmationIn] 在决策之后必须返回空列表——它决定列表里还有没有
 *    这一格。子代理发起的卡片 `conversationId == null`，在任何会话都不许"卡住不走"。
 */
class AgentApprovalCardDismissalTest {
    @get:Rule
    val timeout: Timeout = Timeout.seconds(30)

    private fun coordinator(pollIntervalMillis: Long = 5L): AgentApprovalCoordinator =
        AgentApprovalCoordinator(pollIntervalMillis = pollIntervalMillis)

    private fun subagentVerdict(): ToolRiskVerdict.NeedsConfirm = ToolRiskVerdict.NeedsConfirm(
        toolName = "terminal",
        subject = "python3 - <<'PY'\nopen('/system/build.prop','w').write('x')\nPY",
        findings = listOf(
            RiskFinding(
                ToolRiskCategory.INDIRECT_EXECUTION,
                ToolRiskLevel.MEDIUM,
                "无法读取脚本内容的间接执行：python3",
            ),
        ),
    )

    private fun awaitInBackground(
        coordinator: AgentApprovalCoordinator,
        request: AgentApprovalRequest,
    ): Pair<AtomicReference<AgentApprovalOutcome>, CountDownLatch> {
        val outcome = AtomicReference<AgentApprovalOutcome>()
        val latch = CountDownLatch(1)
        Thread {
            outcome.set(coordinator.await(request))
            latch.countDown()
        }.start()
        waitUntil { coordinator.pending.value.any { it.id == request.id } }
        return outcome to latch
    }

    @Test
    fun subagentCardWithoutConversationLeavesTheStreamOnceDenied() {
        val coordinator = coordinator()
        // 子代理／后台 run 发起的危险命令：runId 是父 run，conversationId 可能为 null。
        val request = coordinator.dangerRequest(subagentVerdict(), "run-parent", conversationId = null)
        val (outcome, latch) = awaitInBackground(coordinator, request)
        assertTrue(request.options.any { it.id == AgentApprovalOption.DENY })

        assertTrue("拒绝必须成功落地", coordinator.resolve(request.id, AgentApprovalOption.DENY))
        assertTrue(latch.await(5, TimeUnit.SECONDS))

        // 1) 渲染源：卡片已经不在等待列表里。
        assertTrue("决策后 pending 必须为空，否则聊天流会继续画这张卡", coordinator.pending.value.isEmpty())
        // 2) 投影：当前会话（含"没有会话 id"的草稿态）都不该再看到这张卡。
        assertTrue(coordinator.pending.value.awaitingConfirmationIn(null).isEmpty())
        assertTrue(coordinator.pending.value.awaitingConfirmationIn("conv-active").isEmpty())

        assertEquals(AgentApprovalOptionKind.Deny, (outcome.get() as AgentApprovalOutcome.Decided).resolution.kind)
    }

    @Test
    fun deniedSubagentCardStillEchoesForSixSecondsThenStops() {
        val coordinator = coordinator()
        val request = coordinator.dangerRequest(subagentVerdict(), "run-parent", conversationId = null)
        val (_, latch) = awaitInBackground(coordinator, request)
        coordinator.resolve(request.id, AgentApprovalOption.DENY)
        assertTrue(latch.await(5, TimeUnit.SECONDS))

        val resolved = coordinator.resolved.value
        assertEquals("已决策要留下一条回显", 1, resolved.size)
        val decidedAt = resolved.single().resolution.atMillis

        assertEquals(
            "回显在窗口内可见（子代理卡片 conversationId 为 null 时也可见）",
            1,
            resolved.resolvedFeedbackIn(conversationId = "conv-active", nowMillis = decidedAt + 1_000, windowMillis = 6_000).size,
        )
        assertTrue(
            "窗口结束后回显必须收起，否则聊天流会残留一条永远不消失的卡片",
            resolved.resolvedFeedbackIn(
                conversationId = "conv-active",
                nowMillis = decidedAt + 6_001,
                windowMillis = 6_000,
            ).isEmpty(),
        )
        // resolved 是永不自动清空的历史：正因如此，聊天流的回显心跳只能按窗口判断退出，
        // 不能用 `while (resolved.isNotEmpty())`（那样第一次决策之后就永不退出）。
        assertTrue("resolved 历史不会被清空，回显必须靠窗口过滤", resolved.isNotEmpty())
    }

    @Test
    fun repeatedClicksAfterTheDecisionNeverBringTheCardBack() {
        val coordinator = coordinator()
        val request = coordinator.dangerRequest(subagentVerdict(), "run-parent", conversationId = null)
        val (_, latch) = awaitInBackground(coordinator, request)
        assertTrue(coordinator.resolve(request.id, AgentApprovalOption.DENY))
        assertTrue(latch.await(5, TimeUnit.SECONDS))

        // 用户连点：后续点选只返回 false（卡片失效），绝不能重新出现在等待列表里。
        repeat(3) {
            assertFalse(coordinator.resolve(request.id, AgentApprovalOption.DENY))
            assertTrue(coordinator.pending.value.isEmpty())
            assertTrue(coordinator.pending.value.awaitingConfirmationIn(null).isEmpty())
        }
        assertEquals("连点不会额外产出回显", 1, coordinator.resolved.value.size)
    }

    @Test
    fun runEndingWithoutDecisionAlsoClearsTheCard() {
        val coordinator = coordinator()
        val request = coordinator.dangerRequest(subagentVerdict(), "run-parent", conversationId = null)
        val (outcome, latch) = awaitInBackground(coordinator, request)

        // 父 run 结束（取消／完成）时 AgentRuntimeRunExecutor 会 abandonRun。
        coordinator.abandonRun("run-parent")
        assertTrue(latch.await(5, TimeUnit.SECONDS))

        assertTrue("run 结束也必须把卡片从等待列表撤下", coordinator.pending.value.isEmpty())
        assertTrue(coordinator.pending.value.awaitingConfirmationIn("conv-active").isEmpty())
        assertEquals(AgentApprovalOutcome.Cancelled, outcome.get())
        assertTrue("没有决策就不该有回显", coordinator.resolved.value.isEmpty())
    }

    @Test
    fun feedbackHeartbeatStopsOnceTheWindowIsOver() {
        val coordinator = coordinator()
        val request = coordinator.dangerRequest(subagentVerdict(), "run-parent", conversationId = null)
        val (_, latch) = awaitInBackground(coordinator, request)
        coordinator.resolve(request.id, AgentApprovalOption.DENY)
        assertTrue(latch.await(5, TimeUnit.SECONDS))

        val resolved = coordinator.resolved.value
        val decidedAt = resolved.single().resolution.atMillis
        assertEquals(
            "窗口内按秒刷新回显",
            APPROVAL_FEEDBACK_TICK_MILLIS,
            approvalFeedbackRefreshDelayMillis(resolved, decidedAt + 1_000, 6_000),
        )
        assertEquals(
            "临近窗口末尾缩短睡眠，正好在窗口结束时醒一次",
            500L,
            approvalFeedbackRefreshDelayMillis(resolved, decidedAt + 5_500, 6_000),
        )
        assertNull(
            "窗口结束后心跳必须停掉，否则聊天体会被永远每秒重组",
            approvalFeedbackRefreshDelayMillis(resolved, decidedAt + 6_000, 6_000),
        )
        assertNull(
            "没有回显时不心跳",
            approvalFeedbackRefreshDelayMillis(emptyList(), decidedAt, 6_000),
        )
    }

    @Test
    fun cardBelongingToAnotherConversationStaysHidden() {
        val coordinator = coordinator()
        val request = coordinator.dangerRequest(subagentVerdict(), "run-1", conversationId = "conv-a")
        val (_, latch) = awaitInBackground(coordinator, request)

        assertTrue(
            "别的会话不应看到这张卡",
            coordinator.pending.value.awaitingConfirmationIn("conv-b").isEmpty(),
        )
        assertEquals(
            "归属的会话要看得到",
            1,
            coordinator.pending.value.awaitingConfirmationIn("conv-a").size,
        )

        coordinator.resolve(request.id, AgentApprovalOption.DENY)
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertTrue(coordinator.pending.value.awaitingConfirmationIn("conv-a").isEmpty())
    }

    private fun waitUntil(timeoutMillis: Long = 3_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(2)
        }
        assertTrue("等待条件超时", condition())
    }
}
