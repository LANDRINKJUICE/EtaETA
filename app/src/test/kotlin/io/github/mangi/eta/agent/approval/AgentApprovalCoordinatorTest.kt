package io.github.mangi.eta.agent.approval

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/**
 * 挂起/恢复、超时、撤卡与竞态语义。
 *
 * 超时类用例用很短的真实超时（配合小轮询间隔），既确定又不依赖虚拟时钟。
 */
class AgentApprovalCoordinatorTest {
    @get:Rule
    val timeout: Timeout = Timeout.seconds(30)

    private fun verdict(
        category: ToolRiskCategory = ToolRiskCategory.SYSTEM_PARTITION_WRITE,
        level: ToolRiskLevel = ToolRiskLevel.CRITICAL,
    ): ToolRiskVerdict.NeedsConfirm = ToolRiskVerdict.NeedsConfirm(
        toolName = "terminal",
        subject = "echo x > /system/build.prop",
        findings = listOf(RiskFinding(category, level, "重定向写入 /system/build.prop")),
    )

    private fun coordinator(pollIntervalMillis: Long = 5L): AgentApprovalCoordinator =
        AgentApprovalCoordinator(pollIntervalMillis = pollIntervalMillis)

    private fun askOptions(): List<AgentApprovalOption> = listOf(
        AgentApprovalOption("option_1", AgentApprovalOptionKind.Choice, "家庭群"),
        AgentApprovalOption("option_2", AgentApprovalOptionKind.Choice, "工作群"),
    )

    @Test
    fun decisionArrivingBeforeTheDeadlineResumesTheWaiter() {
        val coordinator = coordinator()
        val request = coordinator.dangerRequest(verdict(), "run-1", "conv-1")
        val latch = CountDownLatch(1)
        val outcome = AtomicReference<AgentApprovalOutcome>()

        Thread {
            outcome.set(coordinator.await(request))
            latch.countDown()
        }.start()

        waitUntil { coordinator.pending.value.any { it.id == request.id } }
        assertTrue(coordinator.resolve(request.id, AgentApprovalOption.ALLOW_ONCE))
        assertTrue(latch.await(5, TimeUnit.SECONDS))

        val decided = outcome.get() as AgentApprovalOutcome.Decided
        assertEquals(AgentApprovalOption.ALLOW_ONCE, decided.resolution.optionId)
        assertEquals(AgentApprovalOptionKind.AllowOnce, decided.resolution.kind)
        assertTrue(coordinator.pending.value.isEmpty())
    }

    @Test
    fun sessionGrantDecisionIsRecordedWithItsKind() {
        val coordinator = coordinator()
        val request = coordinator.dangerRequest(verdict(level = ToolRiskLevel.HIGH), "run-1", "conv-1")
        val outcome = AtomicReference<AgentApprovalOutcome>()
        val latch = CountDownLatch(1)
        Thread {
            outcome.set(coordinator.await(request))
            latch.countDown()
        }.start()
        waitUntil { coordinator.pending.value.isNotEmpty() }
        coordinator.resolve(request.id, AgentApprovalOption.ALLOW_SESSION)
        latch.await(5, TimeUnit.SECONDS)
        assertEquals(
            AgentApprovalOptionKind.AllowSession,
            (outcome.get() as AgentApprovalOutcome.Decided).resolution.kind,
        )
    }

    @Test
    fun denialCarriesTheUserFreeText() {
        val coordinator = coordinator()
        val request = coordinator.dangerRequest(verdict(), "run-1", "conv-1")
        val outcome = AtomicReference<AgentApprovalOutcome>()
        val latch = CountDownLatch(1)
        Thread {
            outcome.set(coordinator.await(request))
            latch.countDown()
        }.start()
        waitUntil { coordinator.pending.value.isNotEmpty() }
        coordinator.resolve(request.id, AgentApprovalOption.DENY_WITH_FEEDBACK, "  改用只读方式检查  ")
        latch.await(5, TimeUnit.SECONDS)
        val resolution = (outcome.get() as AgentApprovalOutcome.Decided).resolution
        assertEquals(AgentApprovalOptionKind.Deny, resolution.kind)
        assertEquals("改用只读方式检查", resolution.freeText)
        assertEquals(1, coordinator.resolved.value.size)
    }

    @Test
    fun noDecisionTimesOutAndNeverDefaultsToAllow() {
        val coordinator = coordinator(pollIntervalMillis = 5L)
        val request = coordinator.dangerRequest(verdict(), "run-1", null, timeoutMillis = 40L)
        assertEquals(AgentApprovalOutcome.TimedOut, coordinator.await(request))
        assertTrue(coordinator.pending.value.isEmpty())
        // 超时后卡片已经失效：迟到的点选不生效，也不会出现在"已决策"回显里。
        assertFalse(coordinator.resolve(request.id, AgentApprovalOption.ALLOW_ONCE))
        assertTrue(coordinator.resolved.value.isEmpty())
    }

    @Test
    fun lateDecisionAfterTimeoutIsRejected() {
        val coordinator = coordinator()
        val request = coordinator.askUserRequest(
            question = "发送到哪个群？",
            explanation = "",
            options = askOptions(),
            allowFreeText = false,
            runId = "run-1",
            conversationId = "conv-1",
            timeoutMillis = 30L,
        )
        assertEquals(AgentApprovalOutcome.TimedOut, coordinator.await(request))
        assertFalse(coordinator.resolve(request.id, "option_1"))
    }

    @Test
    fun racingResolveAndTimeoutNeverLeaksADecision() {
        // 反复在接近超时的时刻点选：要么 resolve 成功且等待者拿到决策，要么超时且 resolve 失败。
        // 不允许出现"UI 显示已允许、工具却按超时返回"，也不允许留下无人消费的决策。
        repeat(30) { round ->
            val coordinator = coordinator(pollIntervalMillis = 1L)
            val request = coordinator.dangerRequest(verdict(), "run-$round", "conv-1", timeoutMillis = 30L)
            val outcome = AtomicReference<AgentApprovalOutcome>()
            val latch = CountDownLatch(1)
            Thread {
                outcome.set(coordinator.await(request))
                latch.countDown()
            }.start()
            val observed = runCatching {
                waitUntil(2_000L) { coordinator.pending.value.any { it.id == request.id } }
            }.isSuccess
            val resolved = observed && coordinator.resolve(request.id, AgentApprovalOption.ALLOW_ONCE)
            assertTrue(latch.await(5, TimeUnit.SECONDS))
            if (resolved) {
                assertTrue(
                    "resolve 成功时等待者必须拿到决策",
                    outcome.get() is AgentApprovalOutcome.Decided,
                )
                assertEquals(1, coordinator.resolved.value.size)
            } else {
                assertTrue(
                    "resolve 失败时等待者不能是 Decided",
                    outcome.get() !is AgentApprovalOutcome.Decided,
                )
                assertTrue("失败的点选不能出现在已决策回显里", coordinator.resolved.value.isEmpty())
            }
            assertTrue(coordinator.pending.value.isEmpty())
        }
    }

    @Test
    fun stoppingTheRunCancelsTheWait() {
        val coordinator = coordinator()
        val request = coordinator.dangerRequest(verdict(), "run-2", "conv-1")
        val aborted = AtomicReference(false)
        val latch = CountDownLatch(1)
        val result = AtomicReference<AgentApprovalOutcome>()
        Thread {
            result.set(coordinator.await(request) { aborted.get() })
            latch.countDown()
        }.start()
        waitUntil { coordinator.pending.value.isNotEmpty() }
        aborted.set(true)
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertEquals(AgentApprovalOutcome.Cancelled, result.get())
        assertTrue(coordinator.pending.value.isEmpty())
    }

    @Test
    fun abandoningARunWithdrawsItsCardsAndWakesTheWaiter() {
        val coordinator = coordinator(pollIntervalMillis = 5_000L)
        val request = coordinator.dangerRequest(verdict(), "run-9", "conv-1")
        val latch = CountDownLatch(1)
        val result = AtomicReference<AgentApprovalOutcome>()
        Thread {
            result.set(coordinator.await(request))
            latch.countDown()
        }.start()
        waitUntil { coordinator.pending.value.isNotEmpty() }
        coordinator.abandonRun("run-9")
        // 轮询间隔很长，只有唤醒机制生效才能这么快返回。
        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertEquals(AgentApprovalOutcome.Cancelled, result.get())
        assertTrue(coordinator.pending.value.isEmpty())
        assertFalse(coordinator.resolve(request.id, AgentApprovalOption.ALLOW_ONCE))
    }

    @Test
    fun abandoningAnotherRunKeepsUnrelatedCards() {
        val coordinator = coordinator()
        val first = coordinator.dangerRequest(verdict(), "run-a", "conv-1")
        val second = coordinator.dangerRequest(verdict(), "run-b", "conv-2")
        val latch = CountDownLatch(2)
        listOf(first, second).map { request ->
            Thread {
                coordinator.await(request)
                latch.countDown()
            }.also { it.start() }
        }
        waitUntil { coordinator.pending.value.size == 2 }
        coordinator.abandonRun("run-a")
        waitUntil { coordinator.pending.value.none { it.id == first.id } }
        assertTrue(coordinator.pending.value.any { it.id == second.id })
        coordinator.resolve(second.id, AgentApprovalOption.ALLOW_ONCE)
        assertTrue(latch.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun freeTextOnlyAnswerWorksOnlyWhenTheCardAllowsFreeText() {
        val coordinator = coordinator()
        val permissive = coordinator.askUserRequest(
            question = "补一句说明",
            explanation = "",
            options = askOptions(),
            allowFreeText = true,
            runId = "run-1",
            conversationId = "conv-1",
        )
        val latch = CountDownLatch(1)
        val result = AtomicReference<AgentApprovalOutcome>()
        Thread {
            result.set(coordinator.await(permissive))
            latch.countDown()
        }.start()
        waitUntil { coordinator.pending.value.any { it.id == permissive.id } }
        assertTrue(coordinator.resolve(permissive.id, AgentApprovalOption.FREE_TEXT_ONLY, "自己想一个"))
        latch.await(5, TimeUnit.SECONDS)
        val resolution = (result.get() as AgentApprovalOutcome.Decided).resolution
        assertEquals(AgentApprovalOption.FREE_TEXT_ONLY, resolution.optionId)
        assertEquals("自己想一个", resolution.freeText)

        val strict = coordinator.askUserRequest(
            question = "只点选项",
            explanation = "",
            options = askOptions(),
            allowFreeText = false,
            runId = "run-2",
            conversationId = "conv-1",
        )
        val latch2 = CountDownLatch(1)
        Thread {
            coordinator.await(strict)
            latch2.countDown()
        }.start()
        waitUntil { coordinator.pending.value.any { it.id == strict.id } }
        assertFalse(coordinator.resolve(strict.id, AgentApprovalOption.FREE_TEXT_ONLY, "偷偷输入"))
        coordinator.resolve(strict.id, "option_2")
        assertTrue(latch2.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun unknownOptionIsRejected() {
        val coordinator = coordinator()
        val request = coordinator.dangerRequest(verdict(), "run-1", null)
        val latch = CountDownLatch(1)
        Thread {
            coordinator.await(request)
            latch.countDown()
        }.start()
        waitUntil { coordinator.pending.value.isNotEmpty() }
        assertFalse(coordinator.resolve(request.id, "not-an-option"))
        coordinator.abandonRun("run-1")
        assertTrue(latch.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun nonCriticalRequestOffersTheFourStandardOptions() {
        val coordinator = coordinator()
        val request = coordinator.dangerRequest(
            verdict(level = ToolRiskLevel.HIGH),
            "run-1",
            "conv-1",
        )
        assertEquals(AgentApprovalKind.ToolRisk, request.kind)
        assertEquals(
            listOf(
                AgentApprovalOption.ALLOW_ONCE,
                AgentApprovalOption.ALLOW_SESSION,
                AgentApprovalOption.DENY,
                AgentApprovalOption.DENY_WITH_FEEDBACK,
            ),
            request.options.map { it.id },
        )
        assertEquals(ToolRiskLevel.HIGH, request.level)
        assertEquals(listOf(ToolRiskCategory.SYSTEM_PARTITION_WRITE), request.categories)
        assertTrue(request.expiresAtMillis > request.createdAtMillis)
        assertTrue(request.allowFreeText)
    }

    @Test
    fun criticalRequestHidesTheSessionGrantOption() {
        val coordinator = coordinator()
        val request = coordinator.dangerRequest(verdict(level = ToolRiskLevel.CRITICAL), "run-1", "conv-1")
        assertEquals(
            listOf(
                AgentApprovalOption.ALLOW_ONCE,
                AgentApprovalOption.DENY,
                AgentApprovalOption.DENY_WITH_FEEDBACK,
            ),
            request.options.map { it.id },
        )
    }

    @Test
    fun requestWithoutConversationHidesTheSessionGrantOption() {
        val coordinator = coordinator()
        val request = coordinator.dangerRequest(verdict(level = ToolRiskLevel.HIGH), "run-1", null)
        assertFalse(request.options.any { it.id == AgentApprovalOption.ALLOW_SESSION })
        assertFalse(coordinator.resolve(request.id, AgentApprovalOption.ALLOW_SESSION))
    }

    @Test
    fun pendingListenerSeesPublishAndResolve() {
        val coordinator = coordinator()
        val events = AtomicInteger()
        val lastSize = AtomicReference(-1)
        val unregister = coordinator.addPendingListener { pending ->
            events.incrementAndGet()
            lastSize.set(pending.size)
        }
        val request = coordinator.dangerRequest(verdict(), "run-1", "conv-1")
        val latch = CountDownLatch(1)
        Thread {
            coordinator.await(request)
            latch.countDown()
        }.start()
        waitUntil { coordinator.pending.value.isNotEmpty() }
        coordinator.resolve(request.id, AgentApprovalOption.DENY)
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        unregister()
        assertTrue("监听器应被通知过", events.get() >= 2)
        assertEquals(0, lastSize.get())
    }

    @Test
    fun clearDropsEverything() {
        val coordinator = coordinator()
        val request = coordinator.dangerRequest(verdict(), "run-1", "conv-1")
        val latch = CountDownLatch(1)
        Thread {
            coordinator.await(request)
            latch.countDown()
        }.start()
        waitUntil { coordinator.pending.value.isNotEmpty() }
        coordinator.clear()
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertTrue(coordinator.pending.value.isEmpty())
        assertTrue(coordinator.resolved.value.isEmpty())
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
