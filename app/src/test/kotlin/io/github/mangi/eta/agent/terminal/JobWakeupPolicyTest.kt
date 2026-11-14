package io.github.mangi.eta.agent.terminal

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 后台任务自动唤醒的纯逻辑回归：唤醒判定、防循环记账、上下文裁剪。
 *
 * 这些是全 JVM 可跑的纯逻辑（不引用 android.jar），钉住：
 * - 失败 / 超时 / 中断唤醒，成功安静，用户主动停止不唤醒；
 * - 唤醒 run 自己起的任务永不唤醒；
 * - 同一任务 10 分钟冷却、同一任务链最多 3 轮，且成功任务不消耗额度；
 * - 日志尾部有界、行对齐、剥掉 ANSI 控制序列，prompt 不随日志增长。
 */
class JobWakeupPolicyTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private val minute = 60_000L

    private fun signal(
        jobId: String = "job_ab12cd34",
        exitCode: Int? = 1,
        endReason: String? = null,
        watch: Boolean = false,
        wakeupOrigin: Boolean = false,
        description: String = "监控 CI",
        command: String = "sh scripts/watch.sh",
        cwd: String = "/data/local/tmp/eta",
        environment: String = "android",
    ) = JobWakeupPolicy.Signal(
        jobId = jobId,
        description = description,
        command = command,
        cwd = cwd,
        environment = environment,
        exitCode = exitCode,
        endReason = endReason,
        watch = watch,
        wakeupOrigin = wakeupOrigin,
    )

    private val texts = JobWakeupPolicy.Texts(
        header = "后台任务已失败，已自动唤醒你接手处理。",
        tailLabel = "任务输出末尾",
        truncatedMarker = "…（更早输出已省略）",
        footer = "请分析失败原因并处理，处理完汇报；如果无需处理就简短说明。",
    )

    // ---- 唤醒判定 ----

    @Test
    fun `failed job wakes the agent`() {
        val decision = JobWakeupPolicy.decide(signal(exitCode = 1), enabled = true, nowMillis = 0L,
            chain = JobWakeupPolicy.ChainState())
        assertEquals(JobWakeupPolicy.Outcome.WAKE, decision.outcome)
        assertEquals("JOB_FAILED", decision.code)
        assertTrue(decision.wakes)
    }

    @Test
    fun `successful job stays quiet unless it is watched`() {
        val quiet = JobWakeupPolicy.decide(signal(exitCode = 0), enabled = true, nowMillis = 0L,
            chain = JobWakeupPolicy.ChainState())
        assertEquals(JobWakeupPolicy.Outcome.SKIP, quiet.outcome)
        assertEquals("JOB_SUCCEEDED", quiet.code)

        val watched = JobWakeupPolicy.decide(signal(exitCode = 0, watch = true), enabled = true,
            nowMillis = 0L, chain = JobWakeupPolicy.ChainState())
        assertEquals(JobWakeupPolicy.Outcome.WAKE, watched.outcome)
        assertEquals("JOB_WATCHED", watched.code)
    }

    @Test
    fun `timeout and interruption count as failure`() {
        listOf("timed_out", "interrupted").forEach { reason ->
            val decision = JobWakeupPolicy.decide(
                signal(exitCode = null, endReason = reason), enabled = true, nowMillis = 0L,
                chain = JobWakeupPolicy.ChainState(),
            )
            assertEquals(reason, JobWakeupPolicy.Outcome.WAKE, decision.outcome)
        }
    }

    @Test
    fun `user stopped job never wakes the agent`() {
        val decision = JobWakeupPolicy.decide(
            signal(exitCode = null, endReason = "stopped"), enabled = true, nowMillis = 0L,
            chain = JobWakeupPolicy.ChainState(),
        )
        assertEquals(JobWakeupPolicy.Outcome.SKIP, decision.outcome)
        assertEquals("JOB_STOPPED_BY_USER", decision.code)
    }

    @Test
    fun `job started by a wakeup run is never woken again`() {
        val decision = JobWakeupPolicy.decide(
            signal(exitCode = 1, wakeupOrigin = true, description = "唤醒 run 里重启的 CI 监控"),
            enabled = true, nowMillis = 0L, chain = JobWakeupPolicy.ChainState(),
        )
        assertEquals(JobWakeupPolicy.Outcome.SKIP, decision.outcome)
        assertEquals("JOB_FROM_WAKEUP_RUN", decision.code)
    }

    @Test
    fun `switch off disables waking`() {
        val decision = JobWakeupPolicy.decide(signal(exitCode = 1), enabled = false, nowMillis = 0L,
            chain = JobWakeupPolicy.ChainState())
        assertEquals(JobWakeupPolicy.Outcome.SKIP, decision.outcome)
        assertEquals("WAKEUP_DISABLED", decision.code)
    }

    @Test
    fun `same job is woken at most once per ten minutes`() {
        val chain = JobWakeupPolicy.ChainState(wakeups = 1, lastWakeAtMillis = 0L)
        val tooSoon = JobWakeupPolicy.decide(signal(), enabled = true, nowMillis = 9 * minute, chain = chain)
        assertEquals(JobWakeupPolicy.Outcome.SKIP, tooSoon.outcome)
        assertEquals("JOB_WAKEUP_COOLDOWN", tooSoon.code)

        val later = JobWakeupPolicy.decide(signal(), enabled = true, nowMillis = 11 * minute, chain = chain)
        assertEquals(JobWakeupPolicy.Outcome.WAKE, later.outcome)
    }

    @Test
    fun `chain stops waking after three rounds and only notifies`() {
        val chain = JobWakeupPolicy.ChainState(
            wakeups = JobWakeupPolicy.MAX_CHAIN_WAKEUPS,
            lastWakeAtMillis = 0L,
        )
        val decision = JobWakeupPolicy.decide(signal(), enabled = true, nowMillis = 60 * minute, chain = chain)
        assertEquals(JobWakeupPolicy.Outcome.NOTIFY_ONLY, decision.outcome)
        assertEquals("JOB_WAKEUP_CHAIN_LIMIT", decision.code)
        assertFalse(decision.wakes)
    }

    @Test
    fun `cooldown wins over chain limit so a burst never re-fires`() {
        val chain = JobWakeupPolicy.ChainState(wakeups = 5, lastWakeAtMillis = 1_000L)
        val decision = JobWakeupPolicy.decide(signal(), enabled = true, nowMillis = 2_000L, chain = chain)
        assertEquals(JobWakeupPolicy.Outcome.SKIP, decision.outcome)
        assertEquals("JOB_WAKEUP_COOLDOWN", decision.code)
    }

    @Test
    fun `successful and stopped jobs do not consume wakeup quota`() {
        val chain = JobWakeupPolicy.ChainState(wakeups = 5, lastWakeAtMillis = 0L)
        val success = JobWakeupPolicy.decide(signal(exitCode = 0), enabled = true, nowMillis = 60 * minute, chain = chain)
        assertEquals("JOB_SUCCEEDED", success.code)
        val stopped = JobWakeupPolicy.decide(
            signal(exitCode = null, endReason = "stopped"), enabled = true, nowMillis = 60 * minute, chain = chain,
        )
        assertEquals("JOB_STOPPED_BY_USER", stopped.code)
    }

    @Test
    fun `wakeup run id and conversation key are namespaced per job`() {
        assertEquals("job-job_ab12cd34", JobWakeupPolicy.conversationKey("job_ab12cd34"))
        assertEquals("job-wakeup-job_ab12cd34-1700000000000", JobWakeupPolicy.runId("job_ab12cd34", 1_700_000_000_000L))
    }

    // ---- 持久记账 ----

    private fun ledger(file: File = File(temporary.root, "job-wakeup.json")) = JobWakeupLedger(file)

    @Test
    fun `ledger counts wakeups and survives process restart`() {
        val file = File(temporary.root, "job-wakeup.json")
        val first = JobWakeupLedger(file)
        assertEquals(JobWakeupPolicy.ChainState(), first.read("job_a").state)
        first.record("job_a", 1_000L)
        first.record("job_a", 2_000L)
        val second = JobWakeupLedger(file)
        assertEquals(JobWakeupPolicy.ChainState(wakeups = 2, lastWakeAtMillis = 2_000L), second.read("job_a").state)
        assertEquals(JobWakeupPolicy.ChainState(), second.read("job_b").state)
    }

    @Test
    fun `ledger keeps counters after a chain is used three times`() {
        val ledger = ledger()
        repeat(JobWakeupPolicy.MAX_CHAIN_WAKEUPS) { index -> ledger.record("job_a", index * 1_000L) }
        val chain = ledger.read("job_a").state
        assertEquals(JobWakeupPolicy.MAX_CHAIN_WAKEUPS, chain.wakeups)
        val decision = JobWakeupPolicy.decide(
            signal(jobId = "job_a"), enabled = true, nowMillis = 60 * minute, chain = chain,
        )
        assertEquals(JobWakeupPolicy.Outcome.NOTIFY_ONLY, decision.outcome)
    }

    @Test
    fun `ledger drops entries older than the retention window`() {
        val file = File(temporary.root, "job-wakeup.json")
        val ledger = JobWakeupLedger(file)
        ledger.record("job_old", 0L)
        // 24 小时后的一次写入触发收敛：过期链不再参与判定，文件本身保持有界。
        ledger.record("job_new", 25L * 60L * 60L * 1_000L)
        val snapshot = ledger.snapshot()
        assertFalse(snapshot.containsKey("job_old"))
        assertTrue(snapshot.containsKey("job_new"))
    }

    @Test
    fun `ledger stays bounded when many chains wake`() {
        val ledger = ledger()
        repeat(200) { index -> ledger.record("job_$index", index.toLong()) }
        assertTrue("snapshot=${ledger.snapshot().size}", ledger.snapshot().size <= 64)
    }

    @Test
    fun `corrupted ledger file degrades to empty state instead of throwing`() {
        val file = File(temporary.root, "job-wakeup.json").apply { writeText("{ not json") }
        val ledger = JobWakeupLedger(file)
        // 文件损坏时读要降级（available=false），写成功则把账本修回来。
        val broken = ledger.read("job_a")
        assertEquals(JobWakeupPolicy.ChainState(), broken.state)
        assertFalse("损坏的文件必须降级", broken.available)
        val repaired = ledger.record("job_a", 5L)
        assertTrue(repaired.available)
        assertEquals(JobWakeupPolicy.ChainState(wakeups = 1, lastWakeAtMillis = 5L), repaired.state)
        assertEquals(JobWakeupPolicy.ChainState(wakeups = 1, lastWakeAtMillis = 5L), ledger.read("job_a").state)
    }

    @Test
    fun `blank chain id never writes an entry`() {
        val ledger = ledger()
        assertEquals(JobWakeupPolicy.ChainState(), ledger.record("", 1L).state)
        assertTrue(ledger.snapshot().isEmpty())
    }

    // ---- 上下文裁剪 ----

    @Test
    fun `log tail is bounded and starts on a line boundary`() {
        val raw = (1..400).joinToString("\n") { "line-$it-" + "x".repeat(40) }
        val tail = JobWakeupPolicy.clipTail(raw, maxChars = 500, marker = "…（省略）")
        assertTrue("len=${tail.length}", tail.length <= 500)
        assertTrue(tail.startsWith("…（省略）"))
        // 首行是裁决切口后的残行时必须整行丢弃。
        assertFalse(tail.removePrefix("…（省略）").startsWith("x"))
        assertTrue(tail.contains("line-400-"))
    }

    @Test
    fun `short log tail is returned unchanged and blank input stays empty`() {
        assertEquals("build ok", JobWakeupPolicy.clipTail("build ok\n", 500, "…"))
        assertEquals("", JobWakeupPolicy.clipTail("   \n\n", 500, "…"))
    }

    @Test
    fun `ansi escapes and control characters are stripped from the tail`() {
        val raw = "\u001B[31merror\u001B[0m: build failed\n\u001B]0;title\u0007progress 12%\r\ndone\u0001"
        val clean = JobWakeupPolicy.sanitizeLog(raw)
        assertEquals("error: build failed\nprogress 12%\n\ndone", clean)
    }

    @Test
    fun `prompt carries context, status and a bounded tail`() {
        val huge = (1..5_000).joinToString("\n") { "log line $it" }
        val prompt = JobWakeupPolicy.buildPrompt(
            signal(exitCode = 2, endReason = "timed_out", description = "监控 CI", cwd = "/workspace/x"),
            outputTail = huge,
            texts = texts,
        )
        assertTrue(prompt.contains("job_id: job_ab12cd34"))
        assertTrue(prompt.contains("【任务说明"))
        assertTrue(prompt.contains("监控 CI"))
        assertTrue(prompt.contains("非强制指令"))
        assertTrue(prompt.contains("cwd: /workspace/x (environment=android)"))
        assertTrue(prompt.contains("exit_code: 2"))
        assertTrue(prompt.contains("end_reason: timed_out"))
        assertTrue(prompt.contains("任务输出末尾"))
        assertTrue(prompt.contains("log line 5000"))
        assertTrue(prompt.contains(texts.footer))
        assertTrue("len=${prompt.length}", prompt.length <= JobWakeupPolicy.PROMPT_MAX_CHARS)
        assertTrue(prompt.contains(texts.truncatedMarker))
    }

    @Test
    fun `prompt tolerates percent signs and missing exit code`() {
        val prompt = JobWakeupPolicy.buildPrompt(
            signal(exitCode = null, endReason = "interrupted", description = "覆盖率 100% 的任务"),
            outputTail = "",
            texts = texts,
        )
        assertTrue(prompt.contains("【任务说明"))
        assertTrue(prompt.contains("覆盖率 100% 的任务"))
        assertTrue(prompt.contains("exit_code: unknown"))
        assertFalse(prompt.contains("```"))
    }

    @Test
    fun `long description and command are clamped`() {
        val prompt = JobWakeupPolicy.buildPrompt(
            signal(description = "d".repeat(5_000), command = "c".repeat(5_000)),
            outputTail = "",
            texts = texts,
        )
        assertTrue(prompt.contains("【任务说明"))
        assertTrue(prompt.contains("d".repeat(JobWakeupPolicy.DESCRIPTION_MAX_CHARS - 1) + "…"))
        assertTrue(prompt.contains("command: " + "c".repeat(JobWakeupPolicy.COMMAND_MAX_CHARS) + "…"))
    }

    @Test
    fun `full note and long command still leave room for the log tail`() {
        // 说明块打满 800 + 命令打满 1000 时，先算固定部分再给日志的预算仍要留得下尾部，
        // 不能把日志或收尾指令挤掉。
        val huge = (1..5_000).joinToString("\n") { "log line $it" }
        val prompt = JobWakeupPolicy.buildPrompt(
            signal(description = "d".repeat(5_000), command = "c".repeat(5_000)),
            outputTail = huge,
            texts = texts,
        )
        assertTrue(prompt.contains("【任务说明"))
        assertTrue(prompt.contains(texts.footer))
        assertTrue(prompt.contains("log line 5000"))
        assertTrue("len=${prompt.length}", prompt.length <= JobWakeupPolicy.PROMPT_MAX_CHARS)
    }

    @Test
    fun `wake mapping matches the supervisor status vocabulary`() {
        // 与 DetachedTask.commandStatus 的取值一一对应；只有 stopped 是"用户主动停止"。
        val cases = listOf(
            Triple("completed", null to 0, JobWakeupPolicy.Outcome.SKIP),
            Triple("failed", null to 1, JobWakeupPolicy.Outcome.WAKE),
            Triple("timed_out", "timed_out" to null, JobWakeupPolicy.Outcome.WAKE),
            Triple("stopped", "stopped" to null, JobWakeupPolicy.Outcome.SKIP),
            Triple("interrupted", "interrupted" to null, JobWakeupPolicy.Outcome.WAKE),
        )
        cases.forEach { (status, input, expected) ->
            val decision = JobWakeupPolicy.decide(
                signal(exitCode = input.second, endReason = input.first),
                enabled = true,
                nowMillis = 0L,
                chain = JobWakeupPolicy.ChainState(),
            )
            assertEquals(status, expected, decision.outcome)
        }
    }

    // ---- 全局配额（WakeupBudget）----

    @Test
    fun `global budget allows two wakeups per ten minute window`() {
        val budget = JobWakeupPolicy.WakeupBudget()
        val start = 1_000L
        assertTrue(budget.allows(start))
        budget.commit(start)
        assertTrue(budget.allows(start + minute))
        budget.commit(start + minute)
        // 窗口内第三次必须被拒，且 allows 不占额度（被别的闸门拒掉时不该白白消耗配额）。
        assertFalse(budget.allows(start + 2 * minute))
        assertEquals(2, budget.used(start + 2 * minute))
        // 滑动窗口：第一次的时间点滑出后重新有余量。
        assertTrue(budget.allows(start + 11 * minute))
        assertEquals(0, budget.used(start + 11 * minute))
        budget.reset()
        assertTrue(budget.allows(start))
    }

    @Test
    fun `global budget exhaustion degrades to notify only`() {
        val decision = JobWakeupPolicy.decide(
            signal(), enabled = true, nowMillis = 0L, chain = JobWakeupPolicy.ChainState(),
            globalAllowed = false,
        )
        assertEquals(JobWakeupPolicy.Outcome.NOTIFY_ONLY, decision.outcome)
        assertEquals(JobWakeupPolicy.CODE_GLOBAL_LIMIT, decision.code)
        assertFalse(decision.wakes)
    }

    // ---- 账本降级 ----

    @Test
    fun `unavailable ledger degrades to notify only`() {
        val file = File(temporary.root, "job-wakeup.json").apply { writeText("{ not json") }
        val read = JobWakeupLedger(file).read("job_a")
        assertFalse("损坏的账本必须降级", read.available)
        val decision = JobWakeupPolicy.decide(
            signal(jobId = "job_a"), enabled = true, nowMillis = 0L,
            chain = read.state, ledgerAvailable = read.available,
        )
        assertEquals(JobWakeupPolicy.Outcome.NOTIFY_ONLY, decision.outcome)
        assertEquals(JobWakeupPolicy.CODE_LEDGER_UNAVAILABLE, decision.code)
        assertFalse(decision.wakes)
    }

    // ---- prompt 预算 ----

    @Test
    fun `prompt budget keeps the instructions and drops the log when nothing is left`() {
        // 收尾指令占满预算：宁可整段丢掉日志，也不能把指令挤出去（模型看不到指令就会瞎干）。
        val longFooter = "F".repeat(5_700)
        val crowded = texts.copy(footer = longFooter)
        val prompt = JobWakeupPolicy.buildPrompt(
            signal(),
            outputTail = (1..5_000).joinToString("\n") { "log line $it" },
            texts = crowded,
        )
        assertTrue(prompt.contains("job_id: job_ab12cd34"))
        assertTrue(prompt.contains(longFooter))
        assertTrue("收尾指令必须在结尾：${prompt.takeLast(40)}", prompt.endsWith(longFooter))
        assertFalse("预算不够时日志必须整段丢掉", prompt.contains(crowded.tailLabel))
        assertFalse(prompt.contains("```"))
        assertTrue("len=${prompt.length}", prompt.length <= JobWakeupPolicy.PROMPT_MAX_CHARS)
    }

    // ---- 标题里的主体 ----

    @Test
    fun `blank description falls back to the localized subject without the command`() {
        assertEquals("后台命令 job_ab12cd34", JobWakeupPolicy.subject("   ", "后台命令 job_ab12cd34"))
        assertEquals("监控 CI", JobWakeupPolicy.subject("  监控 CI  ", "后台命令 job_ab12cd34"))
        // 描述为空时标题只用回退文案（含 job_id），绝不退到含 token 的命令行。
        val secret = "sh deploy.sh --token=s3cret"
        val blank = signal(description = "", command = secret)
        val subject = JobWakeupPolicy.subject(blank.description, "后台命令 ${blank.jobId}")
        assertFalse(subject.contains("s3cret"))
        assertTrue(subject.contains(blank.jobId))
        // 命令本身仍要进 prompt（Agent 需要它复现问题），只是不进通知标题。
        assertTrue(JobWakeupPolicy.buildPrompt(blank, "", texts).contains(secret))
    }

    @Test
    fun `watched successful job uses watch wording instead of failure wording`() {
        val watchTexts = texts.copy(
            headerWatched = "任务按盯梢设置结束，请核对结果。",
            footerWatched = "核对结果即可，无需处理就简短说明。",
        )
        val prompt = JobWakeupPolicy.buildPrompt(
            signal(exitCode = 0, watch = true),
            outputTail = "OK (562 tests)",
            texts = watchTexts,
        )
        assertTrue(prompt.contains(watchTexts.headerWatched))
        assertTrue(prompt.contains(watchTexts.footerWatched))
        assertFalse(prompt.contains(texts.header))
        assertFalse(prompt.contains(texts.footer))
        assertTrue(prompt.contains("OK (562 tests)"))
        assertTrue(prompt.length <= JobWakeupPolicy.PROMPT_MAX_CHARS)
    }

    @Test
    fun `failed job keeps failure wording even when watched`() {
        val watchTexts = texts.copy(
            headerWatched = "任务按盯梢设置结束，请核对结果。",
            footerWatched = "核对结果即可，无需处理就简短说明。",
        )
        val prompt = JobWakeupPolicy.buildPrompt(
            signal(exitCode = 1, watch = true),
            outputTail = "",
            texts = watchTexts,
        )
        assertTrue(prompt.contains(texts.header))
        assertTrue(prompt.contains(texts.footer))
        assertFalse(prompt.contains(watchTexts.headerWatched))
        assertFalse(prompt.contains(watchTexts.footerWatched))
    }
}
