package io.github.mangi.eta.agent.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 通知纯逻辑回归：ID/channel 分配、优先级映射、文案组装、节流器。
 *
 * 这些是全 JVM 可跑的纯逻辑（不引用 android.jar），钉住：
 * - 主动沟通的 ID 段不与既有段相交；
 * - important 走更高 channel + 提示音，普通级绝不；
 * - 标题/正文/上下文按上限裁剪，空白标题回退默认；
 * - 节流器 ≥1s 且内容相同时抑制。
 */
class NotificationPolicyTest {

    private val importanceDefault = 3
    private val importanceHigh = 4

    private fun proactive(
        title: String = "结果已就绪",
        body: String = "任务完成，点击查看",
        priority: String? = null,
        action: String? = null,
        context: String? = null,
        key: String = "k",
    ) = NotificationPolicy.proactive(
        rawTitle = title,
        rawBody = body,
        rawPriority = priority,
        rawAction = action,
        rawContext = context,
        defaultTitle = "默认标题",
        notificationKey = key,
        importanceDefault = importanceDefault,
        importanceHigh = importanceHigh,
    )

    // ---- ID 分配 ----

    @Test
    fun `proactive ids land in the reserved segment and never collide with existing ranges`() {
        val existing = (9_000..9_290) + (9_500..9_501) + (9_600..9_601) + (1_107..1_108) + (1_124..1_124) + (4_203..4_204)
        val ids = (0 until 400).map { NotificationPolicy.proactiveId("主题-$it") }
        ids.forEach { id ->
            assertTrue("id=$id 应落在 9300..9349", id in 9_300..9_349)
            assertFalse("id=$id 不得撞既有段", id in existing)
        }
        // 段可用宽度 50；大量 key 必然复用（这是设计：同主题更新而非堆叠），但不会越界。
        assertEquals(50, ids.toSet().size)
    }

    @Test
    fun `same key always maps to the same id so repeated notifications update in place`() {
        assertEquals(
            NotificationPolicy.proactiveId("价格提醒"),
            NotificationPolicy.proactiveId("价格提醒"),
        )
        assertNotEquals(
            NotificationPolicy.proactiveId("价格提醒"),
            NotificationPolicy.proactiveId("价格提醒-2"),
        )
    }

    @Test
    fun `reserved segments exclude every existing notification id`() {
        val existing = listOf(
            9_000..9_290,
            9_500..9_501,
            9_600..9_601,
            1_107..1_108,
            1_124..1_124,
            4_203..4_204,
        )
        NotificationPolicy.reservedSegments().forEach { mine ->
            existing.forEach { theirs ->
                assertTrue(
                    "自定义段 $mine 与既有段 $theirs 相交",
                    mine.first > theirs.last || mine.last < theirs.first,
                )
            }
        }
        assertTrue(NotificationPolicy.reservedSegments().contains(9_602..9_651))
    }

    @Test
    fun `approval pending id is a single constant outside the proactive segment`() {
        val id = NotificationPolicy.APPROVAL_PENDING_ID
        assertTrue(id in 9_300..9_349 == false)
        assertFalse((0 until 400).map { NotificationPolicy.proactiveId("k$it") }.contains(id))
    }

    @Test
    fun `approval attention ids land in their own segment and stay stable per request`() {
        val existing = (1_107..1_108) + 1_124 + (4_203..4_204) + (9_000..9_290) + (9_300..9_349) +
            9_400 + (9_500..9_501) + (9_600..9_601) + (9_602..9_651)
        val ids = (0 until 400).map { NotificationPolicy.approvalAttentionId("approval-$it") }
        ids.forEach { id ->
            assertTrue("id=$id 应落在 9660..9999", id in 9_660..9_999)
            assertFalse("id=$id 不得撞既有段", id in existing)
        }
        assertEquals(
            NotificationPolicy.approvalAttentionId("approval-42"),
            NotificationPolicy.approvalAttentionId("approval-42"),
        )
        assertTrue(NotificationPolicy.reservedSegments().contains(9_660..9_999))
    }

    @Test
    fun `approval attention base and range cover the whole 9660 to 9999 segment`() {
        assertEquals(9_660, NotificationPolicy.approvalAttentionBase())
        assertEquals(340, NotificationPolicy.approvalAttentionRange())
        // hash 分布本身不均匀（连续 requestId 会聚集），所以多卡时的 ID 唯一性由
        // ApprovalAttentionPresenter 的占用检测分配保证，而不是靠 hash 段宽度。这里只钉住
        // 所有 id 都落在段内、且 base/range 与分配器口径一致。
        val ids = (0 until 400).map { NotificationPolicy.approvalAttentionId("approval-$it") }
        ids.forEach { id ->
            assertTrue(
                "id=$id 应落在 base..base+range",
                id in NotificationPolicy.approvalAttentionBase() until
                    NotificationPolicy.approvalAttentionBase() + NotificationPolicy.approvalAttentionRange(),
            )
        }
        // 分配器从 base 起、跨度 range，恰好覆盖整段。
        assertEquals(
            NotificationPolicy.approvalAttentionBase() + NotificationPolicy.approvalAttentionRange() - 1,
            9_999,
        )
    }

    @Test
    fun `allocateApprovalNotificationId gives distinct ids within one refresh round`() {
        // 复现 S1：同一轮里出现两张新卡，used 必须把本轮已分配的也算进去，否则都拿到 base。
        val used = mutableSetOf<Int>()
        val a = NotificationPolicy.allocateApprovalNotificationId(used)
        used += a
        val b = NotificationPolicy.allocateApprovalNotificationId(used)
        used += b
        assertTrue("a/b 必须都落在段内", a in 9_660..9_999 && b in 9_660..9_999)
        assertNotEquals("同一轮两张卡不能共用一个通知 ID", a, b)
        assertTrue("分配结果必须计入 used", used.containsAll(listOf(a, b)))
    }

    @Test
    fun `allocateApprovalNotificationId skips all used slots and never leaves the segment`() {
        val base = NotificationPolicy.approvalAttentionBase()
        val range = NotificationPolicy.approvalAttentionRange()
        // 前 5 个槽被占，应从第 6 个开始分配。
        val used = (base until base + 5).toSet()
        assertEquals(base + 5, NotificationPolicy.allocateApprovalNotificationId(used))
        // 段满：回退到 base，绝不越界。
        val full = (base until base + range).toSet()
        assertEquals(base, NotificationPolicy.allocateApprovalNotificationId(full))
    }

    @Test
    fun `approval action request codes are distinct per option and per request`() {
        val codes = listOf(
            NotificationPolicy.approvalActionRequestCode("a1", "allow_once"),
            NotificationPolicy.approvalActionRequestCode("a1", "allow_session"),
            NotificationPolicy.approvalActionRequestCode("a1", "deny"),
            NotificationPolicy.approvalActionRequestCode("a2", "allow_once"),
        )
        codes.forEach { code -> assertTrue("requestCode=$code 应落在 6400..6699", code in 6_400..6_699) }
        // 同一张卡的三个按钮必须不同，否则 FLAG_UPDATE_CURRENT 会互相覆盖 extras。
        assertEquals(3, codes.take(3).toSet().size)
        assertEquals(
            NotificationPolicy.approvalActionRequestCode("a1", "allow_once"),
            codes[0],
        )
    }

    @Test
    fun `job wakeup ids land in their own segment and stay stable per job`() {
        val existing = (1_107..1_108) + 1_124 + (4_203..4_204) + (9_000..9_290) + (9_300..9_349) +
            9_400 + (9_500..9_501) + (9_600..9_601)
        val ids = (0 until 400).map { NotificationPolicy.jobWakeupId("job_$it") }
        ids.forEach { id ->
            assertTrue("id=$id 应落在 9602..9651", id in 9_602..9_651)
            assertFalse("id=$id 不得撞既有段", id in existing)
        }
        // 同一任务反复唤醒复用同一 ID（原地更新而非堆叠）。
        assertEquals(NotificationPolicy.jobWakeupId("job_ab12"), NotificationPolicy.jobWakeupId("job_ab12"))
    }

    // ---- 优先级 / channel ----

    @Test
    fun `normal priority uses the default channel without sound`() {
        val spec = proactive(priority = "normal")
        assertEquals(NotificationPolicy.Priority.NORMAL, spec.priority)
        assertEquals(NotificationPolicy.CHANNEL_PROACTIVE, spec.channelId)
        assertEquals(importanceDefault, spec.importance)
        assertFalse(spec.withSound)
    }

    @Test
    fun `important priority escalates to the attention channel with sound`() {
        val spec = proactive(priority = "IMPORTANT")
        assertEquals(NotificationPolicy.Priority.IMPORTANT, spec.priority)
        assertEquals(NotificationPolicy.CHANNEL_ATTENTION, spec.channelId)
        assertEquals(importanceHigh, spec.importance)
        assertTrue(spec.withSound)
    }

    @Test
    fun `unknown priority falls back to normal instead of accidentally escalating`() {
        listOf(null, "", "urgent", "high", "1").forEach { raw ->
            val spec = proactive(priority = raw)
            assertEquals("priority=$raw 应回落普通级", NotificationPolicy.Priority.NORMAL, spec.priority)
            assertEquals(NotificationPolicy.CHANNEL_PROACTIVE, spec.channelId)
            assertFalse(spec.withSound)
        }
    }

    @Test
    fun `attention channel is distinct from the normal channel`() {
        assertNotEquals(NotificationPolicy.CHANNEL_PROACTIVE, NotificationPolicy.CHANNEL_ATTENTION)
    }

    // ---- 动作 ----

    @Test
    fun `default action opens the conversation`() {
        assertEquals(NotificationPolicy.Action.OPEN_CONVERSATION, proactive().action)
        assertEquals(NotificationPolicy.Action.OPEN_CONVERSATION, proactive(action = "").action)
        assertEquals(NotificationPolicy.Action.OPEN_CONVERSATION, proactive(action = "unknown").action)
    }

    @Test
    fun `open task center action is honored case insensitively`() {
        assertEquals(
            NotificationPolicy.Action.OPEN_TASK_CENTER,
            proactive(action = "OPEN_TASK_CENTER").action,
        )
    }

    // ---- 上下文 ----

    @Test
    fun `context is kept only for open conversation`() {
        assertEquals("背景资料", proactive(context = "背景资料").context)
        assertEquals("", proactive(action = "open_task_center", context = "背景资料").context)
    }

    @Test
    fun `conversation without context carries no draft`() {
        // 没有 context 时不得把正文当草稿注入：通知正文不是用户打的字。
        val spec = proactive(body = "价格降到 299 了", context = null)
        assertEquals(NotificationPolicy.Action.OPEN_CONVERSATION, spec.action)
        assertEquals("", spec.context)
        assertEquals("价格降到 299 了", spec.body)
    }

    @Test
    fun `context is trimmed and truncated to the cap`() {
        val long = "  " + "x".repeat(NotificationPolicy.CONTEXT_MAX_CHARS + 100) + "  "
        val spec = proactive(context = long)
        assertEquals(NotificationPolicy.CONTEXT_MAX_CHARS, spec.context.length)
        assertFalse(spec.context.startsWith(" "))
    }

    // ---- 文案组装 ----

    @Test
    fun `blank title falls back to the provided default title`() {
        assertEquals("默认标题", proactive(title = "   ").title)
    }

    @Test
    fun `title and body are trimmed and clamped`() {
        val spec = proactive(
            title = "  " + "T".repeat(NotificationPolicy.TITLE_MAX_CHARS + 20) + "  ",
            body = "  " + "B".repeat(NotificationPolicy.BODY_MAX_CHARS + 20) + "  ",
        )
        assertEquals(NotificationPolicy.TITLE_MAX_CHARS, spec.title.length)
        assertEquals(NotificationPolicy.BODY_MAX_CHARS, spec.body.length)
        assertFalse(spec.title.startsWith(" "))
        assertFalse(spec.body.startsWith(" "))
    }

    @Test
    fun `title validation rejects blank but accepts content`() {
        assertEquals("INVALID_ARGUMENT", NotificationPolicy.validate("   "))
        assertEquals(null, NotificationPolicy.validate("有内容"))
    }

    // ---- 危险操作待确认文案 ----

    @Test
    fun `approval pending formats the count and clamps negatives`() {
        assertEquals("3 项待确认" to "共 3 个操作", NotificationPolicy.approvalPending(3, "%1\$d 项待确认", "共 %1\$d 个操作"))
        assertEquals("0 项待确认" to "共 0 个操作", NotificationPolicy.approvalPending(-5, "%1\$d 项待确认", "共 %1\$d 个操作"))
    }

    // ---- 常驻通知实时文案 ----

    @Test
    fun `ongoing text shows stage and elapsed and floors negative durations`() {
        assertEquals("运行中 · 7s", NotificationPolicy.ongoingText("运行中", null, 7_800, "%1\$ds"))
        assertEquals("运行中 · 0s", NotificationPolicy.ongoingText("运行中", null, -1_000, "%1\$ds"))
    }

    @Test
    fun `ongoing text includes progress when provided`() {
        assertEquals("下载中 · 42% · 3s", NotificationPolicy.ongoingText("下载中", 42, 3_000, "%1\$ds"))
        // 进度越界被夹到 0..100。
        assertEquals("导入中 · 100% · 1s", NotificationPolicy.ongoingText("导入中", 250, 1_000, "%1\$ds"))
        assertEquals("导入中 · 0% · 1s", NotificationPolicy.ongoingText("导入中", -7, 1_000, "%1\$ds"))
    }

    // ---- 节流器 ----

    @Test
    fun `throttle emits first, then suppresses within the interval`() {
        val throttle = NotificationPolicy.Throttle(1_000)
        assertTrue(throttle.shouldEmit("a", 0))
        // 内容相同 → 抑制。
        assertFalse(throttle.shouldEmit("a", 5_000))
        // 内容不同但未到 1s → 抑制。
        assertFalse(throttle.shouldEmit("b", 400))
        // 到 1s 且内容不同 → 放行。
        assertTrue(throttle.shouldEmit("b", 1_000))
        assertTrue(throttle.shouldEmit("c", 2_100))
    }

    @Test
    fun `throttle reset makes the next update immediate`() {
        val throttle = NotificationPolicy.Throttle(1_000)
        assertTrue(throttle.shouldEmit("a", 0))
        assertFalse(throttle.shouldEmit("b", 10))
        throttle.reset()
        assertTrue(throttle.shouldEmit("b", 10))
    }

    @Test
    fun `default minimum interval is at least one second`() {
        assertTrue(NotificationPolicy.MIN_REFRESH_INTERVAL_MILLIS >= 1_000L)
    }

    @Test
    fun `throttle serializes concurrent callers so only one emit wins the window`() {
        // 同一时刻（固定时钟）+ 各自不同的内容：窗口未到，只有抢到第一发的线程能发出去。
        // 这直接验证互斥：若没有同步，两个线程可能同时读到 MIN_VALUE 都判定可发。
        val throttle = NotificationPolicy.Throttle(1_000)
        val emitted = java.util.concurrent.atomic.AtomicInteger()
        val startGate = java.util.concurrent.CountDownLatch(1)
        val threads = (0 until 8).map { index ->
            Thread {
                startGate.await()
                repeat(200) { round ->
                    if (throttle.shouldEmit("t$index-$round", 5_000L)) {
                        emitted.incrementAndGet()
                    }
                }
            }
        }
        threads.forEach { it.start() }
        startGate.countDown()
        threads.forEach { it.join() }
        // 固定时钟下整个窗口只放行一次；内容各不相同也不影响这个结论。
        assertEquals(1, emitted.get())
    }

    @Test
    fun `throttle stays consistent after concurrent churn`() {
        // 并发乱打之后状态仍然可用：清空后能立刻再发一条。
        val throttle = NotificationPolicy.Throttle(1_000)
        val threads = (0 until 4).map { index ->
            Thread {
                repeat(100) { round -> throttle.shouldEmit("c$index-$round", 1_000L + round) }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        throttle.reset()
        assertTrue(throttle.shouldEmit("final", 9_999L))
    }

    // ---- 主动沟通硬限流（M1）----

    @Test
    fun `rate limit allows the first notification then blocks within the cooldown`() {
        val limit = NotificationPolicy.RateLimit(normalIntervalMillis = 30_000, importantIntervalMillis = 60_000)
        assertTrue(limit.tryAcquire(NotificationPolicy.Priority.NORMAL, 1_000))
        // 同优先级冷却未到：拒绝。
        assertFalse(limit.tryAcquire(NotificationPolicy.Priority.NORMAL, 30_999))
        // 正好到 30s：放行。
        assertTrue(limit.tryAcquire(NotificationPolicy.Priority.NORMAL, 31_000))
        assertTrue(limit.tryAcquire(NotificationPolicy.Priority.NORMAL, 61_000))
    }

    @Test
    fun `important and normal use independent cooldowns`() {
        val limit = NotificationPolicy.RateLimit(normalIntervalMillis = 30_000, importantIntervalMillis = 60_000)
        assertTrue(limit.tryAcquire(NotificationPolicy.Priority.NORMAL, 1_000))
        // 普通级刚发过，但重要级桶是空的：紧急提醒不该被普通提醒挡住。
        assertTrue(limit.tryAcquire(NotificationPolicy.Priority.IMPORTANT, 2_000))
        // 重要级冷却 60s。
        assertFalse(limit.tryAcquire(NotificationPolicy.Priority.IMPORTANT, 61_999))
        assertTrue(limit.tryAcquire(NotificationPolicy.Priority.IMPORTANT, 62_000))
    }

    @Test
    fun `rate limit survives clock going backwards`() {
        val limit = NotificationPolicy.RateLimit(normalIntervalMillis = 30_000, importantIntervalMillis = 60_000)
        assertTrue(limit.tryAcquire(NotificationPolicy.Priority.NORMAL, 100_000))
        // 时钟回拨成更小的值：仍视为冷却未到，不会因为"负 elapsed"而误放行。
        assertFalse(limit.tryAcquire(NotificationPolicy.Priority.NORMAL, 50_000))
    }

    @Test
    fun `rate limit reset makes the next emit immediate`() {
        val limit = NotificationPolicy.RateLimit(normalIntervalMillis = 30_000, importantIntervalMillis = 60_000)
        assertTrue(limit.tryAcquire(NotificationPolicy.Priority.NORMAL, 1_000))
        assertFalse(limit.tryAcquire(NotificationPolicy.Priority.NORMAL, 2_000))
        limit.reset()
        assertTrue(limit.tryAcquire(NotificationPolicy.Priority.NORMAL, 2_000))
        assertEquals(null, limit.lastEmitMillis(NotificationPolicy.Priority.IMPORTANT))
    }

    @Test
    fun `default cooldowns are thirty seconds for normal and sixty for important`() {
        assertEquals(30_000L, NotificationPolicy.NORMAL_NOTIFY_MIN_INTERVAL_MILLIS)
        assertEquals(60_000L, NotificationPolicy.IMPORTANT_NOTIFY_MIN_INTERVAL_MILLIS)
    }

    @Test
    fun `run budget caps total and important per run`() {
        val budget = NotificationPolicy.RunBudget(maxPerRun = 2, maxImportantPerRun = 1)
        assertTrue(budget.tryAcquire("run-a", important = true))
        // 重要级只剩 1 条上限，已用掉。
        assertFalse(budget.tryAcquire("run-a", important = true))
        // 普通级还能再发 1 条（总量 2）。
        assertTrue(budget.tryAcquire("run-a", important = false))
        // 总量到顶。
        assertFalse(budget.tryAcquire("run-a", important = false))
        assertFalse(budget.tryAcquire("run-a", important = true))
    }

    @Test
    fun `run budget is scoped per run and released on finish`() {
        val budget = NotificationPolicy.RunBudget(maxPerRun = 2, maxImportantPerRun = 1)
        assertTrue(budget.tryAcquire("run-1", important = false))
        assertTrue(budget.tryAcquire("run-1", important = false))
        assertFalse(budget.tryAcquire("run-1", important = false))
        // 另一个 run 不受影响。
        assertTrue(budget.tryAcquire("run-2", important = true))
        assertEquals(2, budget.activeRuns())
        // 运行结束后清表：同一 runId 复用时重新获得配额。
        budget.release("run-1")
        assertEquals(1, budget.activeRuns())
        assertTrue(budget.tryAcquire("run-1", important = false))
        // 重复 release 幂等。
        budget.release("run-1")
        budget.release("never-seen")
    }

    @Test
    fun `blank run id shares one bucket instead of getting unlimited quota`() {
        val budget = NotificationPolicy.RunBudget(maxPerRun = 2, maxImportantPerRun = 1)
        assertTrue(budget.tryAcquire("", important = false))
        assertTrue(budget.tryAcquire("   ", important = false))
        assertFalse(budget.tryAcquire("", important = false))
        assertEquals(1, budget.activeRuns())
    }

    @Test
    fun `wouldAllow does not consume quota`() {
        val budget = NotificationPolicy.RunBudget(maxPerRun = 1, maxImportantPerRun = 1)
        assertTrue(budget.wouldAllow("run", important = false))
        assertTrue(budget.wouldAllow("run", important = false))
        budget.commit("run", important = false)
        assertFalse(budget.wouldAllow("run", important = false))
    }

    @Test
    fun `admission checks budget first then rate limit and consumes both`() {
        val rate = NotificationPolicy.RateLimit(normalIntervalMillis = 30_000, importantIntervalMillis = 60_000)
        val budget = NotificationPolicy.RunBudget(maxPerRun = 2, maxImportantPerRun = 1)
        assertEquals(
            null,
            NotificationPolicy.admission(NotificationPolicy.Priority.NORMAL, "run", 1_000, rate, budget),
        )
        // 冷却期内：限流拒绝（此时不再占用 run 配额）。
        assertEquals(
            NotificationPolicy.CODE_RATE_LIMITED,
            NotificationPolicy.admission(NotificationPolicy.Priority.NORMAL, "run", 2_000, rate, budget),
        )
        assertEquals(1, budget.activeRuns())
        // 冷却过后放行并占用第二个名额。
        assertEquals(
            null,
            NotificationPolicy.admission(NotificationPolicy.Priority.NORMAL, "run", 31_000, rate, budget),
        )
        // 第三次：run 配额已满，即使冷却也拒绝。
        assertEquals(
            NotificationPolicy.CODE_RATE_LIMITED,
            NotificationPolicy.admission(NotificationPolicy.Priority.NORMAL, "run", 99_000, rate, budget),
        )
    }

    @Test
    fun `slow refresh interval is five seconds and still at least one second`() {
        assertEquals(5_000L, NotificationPolicy.SLOW_REFRESH_INTERVAL_MILLIS)
        assertTrue(NotificationPolicy.SLOW_REFRESH_INTERVAL_MILLIS >= NotificationPolicy.MIN_REFRESH_INTERVAL_MILLIS)
    }

    @Test
    fun `job wakeup request codes stay in their own segments and never collide`() {
        // 既有 requestCode：执行服务 20、执行结果 1107/1108、待确认 6001/6002、
        // 自动任务 6010、后台命令 6020、语音停止 6030。
        val existing = setOf(20, 1_107, 1_108, 6_001, 6_002, 6_010, 6_020, 6_030)
        val bodies = mutableSetOf<Int>()
        val actions = mutableSetOf<Int>()
        (0 until 400).map { "job_$it" }.forEach { job ->
            val body = NotificationPolicy.jobWakeupRequestCode(job)
            val action = NotificationPolicy.jobWakeupActionRequestCode(job)
            assertTrue("body=$body 应落在 6200..6249", body in 6_200..6_249)
            assertTrue("action=$action 应落在 6250..6299", action in 6_250..6_299)
            assertFalse("body=$body 不得撞既有段", body in existing)
            assertFalse("action=$action 不得撞既有段", action in existing)
            bodies += body
            actions += action
        }
        // 两段不相交：同一条任务的主体与按钮共用一个 requestCode 会让 PendingIntent 互相覆盖。
        assertTrue((6_200..6_249).intersect((6_250..6_299).toSet()).isEmpty())
        assertTrue(bodies.none { it in 6_250..6_299 })
        // 按任务分散：不同任务必须能落到不同 requestCode（固定值会让多条通知指向同一会话）。
        assertTrue("bodies=$bodies", bodies.size > 1)
        assertTrue("actions=$actions", actions.size > 1)
    }

    @Test
    fun `proactive carries conversation id for notification direct reply`() {
        fun build(id: String?) = NotificationPolicy.proactive(
            rawTitle = "价格提醒", rawBody = "降到 299 了", rawPriority = null,
            rawAction = null, rawContext = null, defaultTitle = "默认标题",
            importanceDefault = 3, importanceHigh = 4, conversationId = id,
        )
        assertEquals("conv-abc", build("conv-abc").conversationId)
        assertEquals(null, build("   ").conversationId)
        assertEquals(null, build(null).conversationId)
        // 不带新参数时保持旧行为（null），现有用例不受影响。
        assertEquals(null, NotificationPolicy.proactive(
            rawTitle = "价格提醒", rawBody = "降到 299 了", rawPriority = null,
            rawAction = null, rawContext = null, defaultTitle = "默认标题",
            importanceDefault = 3, importanceHigh = 4,
        ).conversationId)
    }

}
