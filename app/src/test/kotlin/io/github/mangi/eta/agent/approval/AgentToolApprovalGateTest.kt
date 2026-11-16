package io.github.mangi.eta.agent.approval

import io.github.mangi.eta.agent.tool.ToolExecutionDecision
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/** 闸门决策：允许、拒绝、超时、运行被停止、会话授权、等级升级与开关关闭。 */
class AgentToolApprovalGateTest {
    @get:Rule
    val timeout: Timeout = Timeout.seconds(30)

    private fun coordinator(pollIntervalMillis: Long = 5L): AgentApprovalCoordinator =
        AgentApprovalCoordinator(pollIntervalMillis = pollIntervalMillis)

    private fun gate(
        coordinator: AgentApprovalCoordinator,
        grants: AgentSessionApprovalGrants = AgentSessionApprovalGrants(),
        enabled: Boolean = true,
        approvalTimeoutMillis: Long = AGENT_APPROVAL_TIMEOUT_MILLIS,
    ): AgentToolApprovalGate = AgentToolApprovalGate(
        coordinator = coordinator,
        grants = grants,
        enabled = { enabled },
        approvalTimeoutMillis = approvalTimeoutMillis,
    )

    private fun args(command: String): JSONObject = JSONObject().put("command", command)

    /** 等卡片出现后用指定选项回填决策，模拟用户在聊天流里点选；可选在点选前后改变运行状态。 */
    private fun answerNext(
        coordinator: AgentApprovalCoordinator,
        optionId: String,
        freeText: String? = null,
        beforeAnswer: () -> Unit = {},
    ): Thread = Thread {
        val deadline = System.currentTimeMillis() + 3_000
        while (System.currentTimeMillis() < deadline) {
            val pending = coordinator.pending.value.firstOrNull()
            if (pending != null) {
                beforeAnswer()
                if (coordinator.resolve(pending.id, optionId, freeText)) return@Thread
            }
            Thread.sleep(2)
        }
    }.also { it.start() }

    private fun waitForPending(coordinator: AgentApprovalCoordinator): AgentApprovalRequest {
        val deadline = System.currentTimeMillis() + 3_000
        while (System.currentTimeMillis() < deadline) {
            coordinator.pending.value.firstOrNull()?.let { return it }
            Thread.sleep(2)
        }
        throw AssertionError("确认卡没有出现")
    }

    @Test
    fun safeToolRunsWithoutAsking() {
        val coordinator = coordinator()
        val decision = gate(coordinator).check(
            toolName = "terminal",
            args = args("ls /sdcard"),
            runId = "run-1",
            conversationId = "conv-1",
        )
        assertEquals(ToolExecutionDecision.Allow, decision)
        assertTrue(coordinator.pending.value.isEmpty())
    }

    @Test
    fun disabledGuardAlwaysAllows() {
        val coordinator = coordinator()
        val decision = gate(coordinator, enabled = false).check(
            toolName = "terminal",
            args = args("dd if=x of=/dev/block/by-name/boot"),
            runId = "run-1",
            conversationId = "conv-1",
        )
        assertEquals(ToolExecutionDecision.Allow, decision)
        assertTrue(coordinator.pending.value.isEmpty())
    }

    @Test
    fun dangerIsBlockedUntilTheUserAllowsItOnce() {
        val coordinator = coordinator()
        val approvalGate = gate(coordinator)
        val seen = AtomicReference<AgentApprovalRequest>()
        val observer = Thread {
            val pending = waitForPending(coordinator)
            seen.set(pending)
            coordinator.resolve(pending.id, AgentApprovalOption.ALLOW_ONCE)
        }.also { it.start() }

        val decision = approvalGate.check(
            toolName = "terminal",
            args = args("echo x > /system/build.prop"),
            runId = "run-1",
            conversationId = "conv-1",
        )
        observer.join(1_000)
        assertEquals(ToolExecutionDecision.Allow, decision)
        val request = seen.get()
        assertNotNull(request)
        assertEquals(AgentApprovalKind.ToolRisk, request.kind)
        assertEquals("conv-1", request.conversationId)
        assertEquals("run-1", request.runId)
        assertEquals(listOf(ToolRiskCategory.SYSTEM_PARTITION_WRITE), request.categories)
        assertEquals("echo x > /system/build.prop", request.subject)
    }

    @Test
    fun denialReturnsUserDeniedWithTheCorrectionHint() {
        val coordinator = coordinator()
        val answer = answerNext(coordinator, AgentApprovalOption.DENY_WITH_FEEDBACK, "只读检查即可")

        val decision = gate(coordinator).check(
            toolName = "terminal",
            args = args("mount -o rw,remount /system"),
            runId = "run-1",
            conversationId = "conv-1",
        )
        answer.join(1_000)
        val reject = decision as ToolExecutionDecision.Reject
        assertEquals(AgentToolApprovalGate.CODE_USER_DENIED, reject.code)
        assertTrue(reject.message.contains("只读检查即可"))
        assertTrue(reject.message.contains("不要换个写法重试"))
    }

    @Test
    fun plainDenialHasNoCorrectionHint() {
        val coordinator = coordinator()
        val answer = answerNext(coordinator, AgentApprovalOption.DENY)
        val decision = gate(coordinator).check(
            toolName = "terminal",
            args = args("rm -rf /data"),
            runId = "run-1",
            conversationId = "conv-1",
        )
        answer.join(1_000)
        val reject = decision as ToolExecutionDecision.Reject
        assertEquals(AgentToolApprovalGate.CODE_USER_DENIED, reject.code)
        assertFalse(reject.message.contains("纠正建议"))
    }

    @Test
    fun timeoutRejectsWithUserNotConfirmed() {
        val coordinator = coordinator()
        val decision = gate(coordinator, approvalTimeoutMillis = 30L).check(
            toolName = "terminal",
            args = args("dd if=x of=/dev/block/by-name/boot"),
            runId = "run-2",
            conversationId = "conv-1",
        )
        val reject = decision as ToolExecutionDecision.Reject
        assertEquals(AgentToolApprovalGate.CODE_USER_NOT_CONFIRMED, reject.code)
        assertTrue(reject.message.contains("操作未执行"))
        assertTrue("超时后不应残留卡片", coordinator.pending.value.isEmpty())
    }

    @Test
    fun stoppingTheRunRejectsInsteadOfAllowing() {
        val coordinator = coordinator()
        val stopped = AtomicReference(false)
        val stopper = Thread {
            waitForPending(coordinator)
            stopped.set(true)
        }.also { it.start() }
        val decision = gate(coordinator).check(
            toolName = "terminal",
            args = args("rm -rf /data"),
            runId = "run-3",
            conversationId = "conv-1",
            isRunActive = { !stopped.get() },
        )
        stopper.join(1_000)
        val reject = decision as ToolExecutionDecision.Reject
        assertEquals(AgentToolApprovalGate.CODE_USER_NOT_CONFIRMED, reject.code)
        assertTrue(coordinator.pending.value.isEmpty())
    }

    @Test
    fun allowingThenStoppingBeforeExecutionStillRejects() {
        // M2：用户点了允许，但运行在"允许之后、执行之前"被停止 → 不能再执行。
        val coordinator = coordinator()
        val stopped = AtomicReference(false)
        val answer = answerNext(
            coordinator,
            AgentApprovalOption.ALLOW_ONCE,
            beforeAnswer = { stopped.set(true) },
        )
        val decision = gate(coordinator).check(
            toolName = "terminal",
            args = args("echo x > /system/build.prop"),
            runId = "run-4",
            conversationId = "conv-1",
            isRunActive = { !stopped.get() },
        )
        answer.join(1_000)
        val reject = decision as ToolExecutionDecision.Reject
        assertEquals(AgentToolApprovalGate.CODE_USER_NOT_CONFIRMED, reject.code)
        assertTrue(reject.message.contains("没有人确认这张卡"))
    }

    @Test
    fun sessionGrantLetsTheSameKindThroughTheSecondTime() {
        val coordinator = coordinator()
        val grants = AgentSessionApprovalGrants()
        val approvalGate = gate(coordinator, grants)
        val answer = answerNext(coordinator, AgentApprovalOption.ALLOW_SESSION)

        // 第一次：写入 /data/adb（SYSTEM_DATA_WRITE/HIGH）需要确认。
        assertEquals(
            ToolExecutionDecision.Allow,
            approvalGate.check(
                toolName = "terminal",
                args = args("mkdir -p /data/adb/modules/demo"),
                runId = "run-1",
                conversationId = "conv-1",
            ),
        )
        answer.join(1_000)
        assertTrue(
            grants.isGranted(
                "conv-1",
                "terminal",
                listOf(RiskFinding(ToolRiskCategory.SYSTEM_DATA_WRITE, ToolRiskLevel.HIGH, "x")),
            ),
        )
        // 第二次同类操作不再弹卡。
        assertEquals(
            ToolExecutionDecision.Allow,
            approvalGate.check(
                toolName = "terminal",
                args = args("mkdir -p /data/adb/modules/demo2"),
                runId = "run-2",
                conversationId = "conv-1",
            ),
        )
        assertTrue(coordinator.pending.value.isEmpty())
    }

    @Test
    fun criticalRiskNeverGrantsSessionApproval() {
        val coordinator = coordinator()
        val grants = AgentSessionApprovalGrants()
        val approvalGate = gate(coordinator, grants)
        // CRITICAL 卡片只有三个选项，ALLOW_SESSION 无法落地。
        val request = AtomicReference<AgentApprovalRequest>()
        val observer = Thread {
            val pending = waitForPending(coordinator)
            request.set(pending)
            if (pending.options.any { it.id == AgentApprovalOption.ALLOW_SESSION }) {
                coordinator.resolve(pending.id, AgentApprovalOption.ALLOW_SESSION)
            } else {
                coordinator.resolve(pending.id, AgentApprovalOption.ALLOW_ONCE)
            }
        }.also { it.start() }
        assertEquals(
            ToolExecutionDecision.Allow,
            approvalGate.check(
                toolName = "terminal",
                args = args("dd if=x of=/dev/block/by-name/boot"),
                runId = "run-1",
                conversationId = "conv-1",
            ),
        )
        observer.join(1_000)
        assertFalse(request.get()!!.options.any { it.id == AgentApprovalOption.ALLOW_SESSION })
        assertTrue(grants.snapshot().isEmpty())
    }

    @Test
    fun sessionGrantDoesNotCoverAHigherLevelOfTheSameCategory() {
        // B3 回归：允许 reboot recovery（BOOT_CHAIN_WRITE/MEDIUM）后，dd 写 boot（同类别 CRITICAL）仍要确认。
        val coordinator = coordinator()
        val grants = AgentSessionApprovalGrants()
        val approvalGate = gate(coordinator, grants)
        val answer = answerNext(coordinator, AgentApprovalOption.ALLOW_SESSION)
        assertEquals(
            ToolExecutionDecision.Allow,
            approvalGate.check(
                toolName = "terminal",
                args = args("reboot recovery"),
                runId = "run-1",
                conversationId = "conv-1",
            ),
        )
        answer.join(1_000)
        val latch = CountDownLatch(1)
        Thread {
            val pending = waitForPending(coordinator)
            coordinator.resolve(pending.id, AgentApprovalOption.DENY)
            latch.countDown()
        }.start()
        val decision = approvalGate.check(
            toolName = "terminal",
            args = args("dd if=/sdcard/boot.img of=/dev/block/by-name/boot"),
            runId = "run-2",
            conversationId = "conv-1",
        )
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertEquals(
            AgentToolApprovalGate.CODE_USER_DENIED,
            (decision as ToolExecutionDecision.Reject).code,
        )
    }

    @Test
    fun sessionGrantDoesNotApplyToAnotherConversation() {
        val grants = AgentSessionApprovalGrants()
        val coordinator = coordinator()
        val approvalGate = gate(coordinator, grants)
        val latch = CountDownLatch(1)
        val decision = AtomicReference<ToolExecutionDecision>()
        val caller = Thread {
            decision.set(
                approvalGate.check(
                    toolName = "terminal",
                    args = args("mkdir -p /data/adb/modules/x"),
                    runId = "run-1",
                    conversationId = "conv-2",
                ),
            )
            latch.countDown()
        }.also { it.start() }

        val pending = waitForPending(coordinator)
        assertEquals("conv-2", pending.conversationId)
        coordinator.resolve(pending.id, AgentApprovalOption.ALLOW_ONCE)
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertEquals(ToolExecutionDecision.Allow, decision.get())
        caller.join(1_000)
    }

    @Test
    fun requestWithoutConversationCannotGrantSessionWideApproval() {
        val coordinator = coordinator()
        val grants = AgentSessionApprovalGrants()
        val approvalGate = gate(coordinator, grants)
        val request = AtomicReference<AgentApprovalRequest>()
        val observer = Thread {
            val pending = waitForPending(coordinator)
            request.set(pending)
            coordinator.resolve(pending.id, AgentApprovalOption.ALLOW_ONCE)
        }.also { it.start() }
        assertEquals(
            ToolExecutionDecision.Allow,
            approvalGate.check(
                toolName = "terminal",
                args = args("mkdir -p /data/adb/modules/y"),
                runId = "run-1",
                conversationId = null,
            ),
        )
        observer.join(1_000)
        assertFalse(request.get()!!.options.any { it.id == AgentApprovalOption.ALLOW_SESSION })
        assertTrue(grants.snapshot().isEmpty())
    }

    @Test
    fun defaultCwdIsPassedToTheEvaluator() {
        val coordinator = coordinator()
        val decision = gate(coordinator).check(
            toolName = "terminal",
            args = args("mkdir -p build"),
            runId = "run-1",
            conversationId = "conv-1",
            defaultCwd = "/data/data/io.github.mangi.eta/files/terminal-user/workspace",
        )
        assertEquals(ToolExecutionDecision.Allow, decision)
        assertTrue(coordinator.pending.value.isEmpty())
    }

    @Test
    fun indirectExecutionIsNoLongerGatedOnline() {
        // 判定收敛：INDIRECT_EXECUTION 不是关键部位，在线也不弹卡——正常跑脚本不被打扰。
        val coordinator = coordinator()
        val decision = gate(coordinator).check(
            toolName = "terminal",
            args = args("sh /tmp/install.sh"),
            runId = "run-1",
            conversationId = "conv-1",
        )
        assertEquals(ToolExecutionDecision.Allow, decision)
        assertTrue(coordinator.pending.value.isEmpty())
    }

    // ---- 判定与处置对所有来源统一：命中关键部位照样弹卡，2 分钟没人点回「未确认/未执行」交模型自判 ----

    @Test
    fun criticalBoundaryShowsCardAndTimesOutToNotExecuted() {
        // 核心：不再按来源预判。命中关键部位一律照样弹卡；没人点（20ms 模拟超时）才回未执行，
        // 并明确「可能无人值守 / 用户没替你做决定 / 其余任务照常做完」。
        val coordinator = coordinator()
        val decision = gate(coordinator, approvalTimeoutMillis = 20L).check(
            toolName = "terminal",
            args = args("echo x > /system/build.prop"),
            runId = "run-u1",
            conversationId = null,
        )
        val reject = decision as ToolExecutionDecision.Reject
        assertEquals(AgentToolApprovalGate.CODE_USER_NOT_CONFIRMED, reject.code)
        assertTrue(reject.message.contains("无人值守"))
        assertTrue(reject.message.contains("未执行"))
        assertTrue(reject.message.contains("不代表用户已同意"))
        assertTrue(reject.message.contains("照常做到完成"))
        assertTrue("超时后不应残留卡片", coordinator.pending.value.isEmpty())
    }

    @Test
    fun normalWorkIsAllowedWithoutAnyCardForAllSources() {
        // 正常活（跑脚本 / 编译 / 重跑构建 / 取包 / 交付 / 清理）一律放行、真正做完，不弹卡、不降级。
        val coordinator = coordinator()
        listOf(
            "sh /workspace/toolchain/jobwake/run.sh",
            "cp /workspace/a.md /workspace/文档-Docs/交接/a.md",
            "git push origin main",
            "mkdir -p /sdcard/Download/Eta/apk && cp /workspace/out.apk /sdcard/Download/Eta/apk/",
            "find /workspace -name '*.log' -delete",
        ).forEach { command ->
            assertEquals(
                ToolExecutionDecision.Allow,
                gate(coordinator).check(
                    toolName = "terminal",
                    args = args(command),
                    runId = "run-u4",
                    conversationId = null,
                ),
            )
        }
        assertTrue(coordinator.pending.value.isEmpty())
    }

    @Test
    fun onlineUserCanApproveACardWithinTheTimeout() {
        // 在线有人点：命中关键部位照常弹卡，允许后执行（弹卡仍有意义）。
        val coordinator = coordinator()
        val answer = answerNext(coordinator, AgentApprovalOption.ALLOW_ONCE)
        val decision = gate(coordinator, approvalTimeoutMillis = 2_000L).check(
            toolName = "terminal",
            args = args("echo x > /system/build.prop"),
            runId = "run-o1",
            conversationId = "conv-1",
        )
        answer.join(2_000)
        assertEquals(ToolExecutionDecision.Allow, decision)
    }
}
