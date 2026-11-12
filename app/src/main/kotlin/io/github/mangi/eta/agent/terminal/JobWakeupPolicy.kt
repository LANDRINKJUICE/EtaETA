package io.github.mangi.eta.agent.terminal

/**
 * 后台任务（terminal_job）结束后"是否自动唤醒 Agent 接手处理"的纯逻辑裁决点。
 *
 * 刻意不引用 android.* / R.* / 以及 [DetachedTask]（后者会把整条 Android 依赖链拉进来）：
 * 唤醒判定、防循环计数、上下文裁剪都在这里钉边界，能在纯 JVM 单测里跑；
 * Android 侧 [BackgroundJobWakeup] 只负责发通知、申请执行租约、发起 run。
 *
 * ## 判定顺序（先到先裁决，全部返回可检索的 code）
 * 1. 唤醒 run 自己起的任务 -> 永不唤醒（防自增殖）；
 * 2. 开关关闭 -> 不唤醒；
 * 3. 用户主动停止 -> 不唤醒（"我让它停的"不该触发 Agent 干活）；
 * 4. 成功且未标记 watch -> 不唤醒（安静）；
 * 5. 防循环账本不可用（文件损坏/写失败）-> 只发通知（fail-closed：没有账本就不敢唤醒）；
 * 6. 同一任务链 10 分钟内已唤醒过 -> 不唤醒；
 * 7. 同一任务链累计唤醒已达 [MAX_CHAIN_WAKEUPS] -> 只发通知；
 * 8. 全局配额（[WAKEUP_WINDOW_MILLIS] 内最多 [MAX_WAKEUPS_PER_WINDOW] 次，跨任务合计）用尽 -> 只发通知；
 * 9. 其余（失败 / 超时 / 中断 / watch 成功）-> 唤醒。
 */
internal object JobWakeupPolicy {

    /** 同一任务链连续自动唤醒的上限；超过只发通知，不再唤醒（防死循环的硬闸门）。 */
    const val MAX_CHAIN_WAKEUPS = 3

    /** 同一任务链两次自动唤醒之间的最小间隔（10 分钟）。 */
    const val MIN_SAME_JOB_INTERVAL_MILLIS = 10L * 60L * 1_000L

    /** 全局配额窗口：跨任务合计，任意 [WAKEUP_WINDOW_MILLIS] 内最多 [MAX_WAKEUPS_PER_WINDOW] 次自动唤醒。 */
    const val WAKEUP_WINDOW_MILLIS = 10L * 60L * 1_000L
    const val MAX_WAKEUPS_PER_WINDOW = 2

    /** 唤醒 prompt 里携带的日志尾部字符上限。 */
    const val OUTPUT_TAIL_CHARS = 4_000

    /** 唤醒 prompt 总长上限，避免把一次失败的全部上下文都塞进请求。 */
    const val PROMPT_MAX_CHARS = 6_000

    /** 尾部预算小到这个程度就没有信息量了：宁可整段丢掉日志，也不能挤掉收尾指令。 */
    const val MIN_TAIL_CHARS = 200

    // ---- 判定 code：调用方需要区分"为什么只发通知"（通知文案不同）时用这三个常量 ----

    /** 防循环账本不可用：这一轮没有启动（fail-closed）。 */
    const val CODE_LEDGER_UNAVAILABLE = "JOB_WAKEUP_LEDGER_UNAVAILABLE"

    /** 同一任务链累计唤醒已达 [MAX_CHAIN_WAKEUPS]：只发通知。 */
    const val CODE_CHAIN_LIMIT = "JOB_WAKEUP_CHAIN_LIMIT"

    /** 全局配额（跨任务合计）用尽：只发通知。 */
    const val CODE_GLOBAL_LIMIT = "JOB_WAKEUP_GLOBAL_LIMIT"

    /** 任务说明块上限（字）：起任务那轮的 Agent 主动写清目标 / 验收标准 / 失败重试 / 产物路径。 */
    const val DESCRIPTION_MAX_CHARS = 800
    const val COMMAND_MAX_CHARS = 1_000
    const val CWD_MAX_CHARS = 300

    /** 唤醒时读日志的字节上限（读满后再按字符裁剪）。 */
    const val LOG_TAIL_BYTES = 32 * 1_024

    /** 判定结果。 */
    enum class Outcome {
        /** 发起一轮 agent run。 */
        WAKE,

        /** 不发起 run，只发一条通知告知用户。 */
        NOTIFY_ONLY,

        /** 什么都不做（既有"后台命令完成"通知不受影响）。 */
        SKIP,
    }

    /**
     * 一轮自动唤醒的终态。
     *
     * 只要发过"正在自动处理"，这一次唤醒结束时必须落到其中一个终态并用同一通知 ID 收尾：
     * 成功 [DONE]、跑完但失败 [FAILED]、超时取消 [TIMED_OUT]、根本没启动 [NOT_STARTED]
     * （租约被拒 / 账本不可用 / 上一轮还在跑 / 请求组装失败）。
     */
    enum class Finish {
        DONE,
        FAILED,
        TIMED_OUT,
        NOT_STARTED,
    }

    /** 判定所依据的任务事实；由 Android 侧的 DetachedTask 转换而来，字段全部为基础类型。 */
    data class Signal(
        val jobId: String,
        val description: String,
        val command: String,
        val cwd: String,
        val environment: String,
        val exitCode: Int?,
        val endReason: String?,
        /** 被显式标记为盯梢的任务：成功也要唤醒。 */
        val watch: Boolean = false,
        /** 该任务由自动唤醒产生的 run 启动：其完成永不触发唤醒。 */
        val wakeupOrigin: Boolean = false,
    ) {
        /** 任务链 id：以根任务 id 为链。唤醒 run 起的任务不会入链（见 [wakeupOrigin]）。 */
        val chainId: String get() = jobId

        /** 失败：有退出码且非 0；超时 / 中断也算失败（由 [endReason] 表达）。 */
        val failed: Boolean get() = (exitCode != null && exitCode != 0) || endReason in FAILURE_REASONS

        val succeeded: Boolean get() = !failed && exitCode == 0

        private companion object {
            val FAILURE_REASONS = setOf("timed_out", "interrupted")
        }
    }

    /** 链上已有的唤醒记账。 */
    data class ChainState(
        val wakeups: Int = 0,
        val lastWakeAtMillis: Long = 0L,
    )

    data class Decision(val outcome: Outcome, val code: String) {
        val wakes: Boolean get() = outcome == Outcome.WAKE
    }

    /**
     * 判定一次任务完成是否需要唤醒。
     *
     * @param enabled 设置页"后台任务完成自动唤醒 Agent"开关的当前值（默认开）。
     * @param nowMillis 当前墙钟时间，由调用方注入（便于测试；墙钟只用于冷却判定）。
     * @param ledgerAvailable 防循环账本是否可读可写；不可用时只发通知（不敢在无账本状态下唤醒）。
     * @param globalAllowed 全局配额是否还有余量（见 [WakeupBudget]）。
     */
    fun decide(
        signal: Signal,
        enabled: Boolean,
        nowMillis: Long,
        chain: ChainState,
        ledgerAvailable: Boolean = true,
        globalAllowed: Boolean = true,
    ): Decision {
        if (signal.wakeupOrigin) return Decision(Outcome.SKIP, "JOB_FROM_WAKEUP_RUN")
        if (signal.jobId.isBlank()) return Decision(Outcome.SKIP, "JOB_ID_MISSING")
        if (!enabled) return Decision(Outcome.SKIP, "WAKEUP_DISABLED")
        if (signal.endReason == "stopped") return Decision(Outcome.SKIP, "JOB_STOPPED_BY_USER")
        if (signal.succeeded && !signal.watch) return Decision(Outcome.SKIP, "JOB_SUCCEEDED")
        // 账本坏了就无法约束冷却与链条计数：只告知用户，不自动唤醒（fail-closed）。
        if (!ledgerAvailable) return Decision(Outcome.NOTIFY_ONLY, CODE_LEDGER_UNAVAILABLE)
        // 冷却与上限只对"真的要唤醒"的输入生效：成功任务不该消耗唤醒额度。
        if (chain.wakeups > 0 && nowMillis - chain.lastWakeAtMillis < MIN_SAME_JOB_INTERVAL_MILLIS) {
            return Decision(Outcome.SKIP, "JOB_WAKEUP_COOLDOWN")
        }
        if (chain.wakeups >= MAX_CHAIN_WAKEUPS) {
            return Decision(Outcome.NOTIFY_ONLY, CODE_CHAIN_LIMIT)
        }
        if (!globalAllowed) return Decision(Outcome.NOTIFY_ONLY, CODE_GLOBAL_LIMIT)
        return Decision(Outcome.WAKE, if (signal.watch && signal.exitCode == 0) "JOB_WATCHED" else "JOB_FAILED")
    }

    /** 唤醒 run 的会话归档键：同一任务链复用一条归档会话，便于用户回看多轮处理。 */
    fun conversationKey(jobId: String): String = "job-$jobId"

    /** 唤醒 run 的唯一 id；与用户会话、自动任务会话都不同源。 */
    fun runId(jobId: String, nowMillis: Long): String = "job-wakeup-$jobId-$nowMillis"

    /**
     * 通知 / 归档标题里的"这一条任务是谁"。
     *
     * 有意**不接收 command**：失败命令里常含 token、凭据或长链接，标题只允许用描述；
     * description 升级成任务说明块后可能很长，进锁屏与通知历史前按 60 字收紧（复用同款
     * 清洗：单行化 + 码点安全截断），避免整段说明糊在标题上；描述为空时用固定文案（含 job_id）。
     */
    fun subject(description: String, fallback: String): String {
        val note = io.github.mangi.eta.agent.approval.ApprovalIntentPolicy.normalize(description, 60)
        return note.ifBlank { fallback.trim() }
    }

    /**
     * 全局唤醒配额：跨任务合计，滑动窗口内最多 [maxPerWindow] 次。
     *
     * 进程级、不落盘：重启会让窗口重置，但"同一任务 10 分钟一次 + 链条 3 轮"都在
     * [JobWakeupLedger] 里持久化，所以最坏情况是重启后多跑一两次，不会失控。
     * 调用方 [BackgroundJobWakeup] 已串行化唤醒，[allows] 与 [commit] 之间不会有并发写入。
     */
    class WakeupBudget(
        private val windowMillis: Long = WAKEUP_WINDOW_MILLIS,
        private val maxPerWindow: Int = MAX_WAKEUPS_PER_WINDOW,
    ) {
        private val lock = Any()
        private val stamps = ArrayDeque<Long>()

        /** 只判断不占额：便于"先确认放行再占用"，被其它闸门拒绝时不白白消耗配额。 */
        fun allows(nowMillis: Long): Boolean = synchronized(lock) {
            prune(nowMillis)
            stamps.size < maxPerWindow
        }

        /** 真正占用一次额度；调用方必须先用 [allows] 确认。 */
        fun commit(nowMillis: Long) = synchronized(lock) {
            prune(nowMillis)
            stamps.addLast(nowMillis)
        }

        /** 窗口内已用次数（诊断与测试用）。 */
        fun used(nowMillis: Long): Int = synchronized(lock) {
            prune(nowMillis)
            stamps.size
        }

        fun reset() = synchronized(lock) { stamps.clear() }

        private fun prune(nowMillis: Long) {
            while (stamps.isNotEmpty() && nowMillis - stamps.first() >= windowMillis) stamps.removeFirst()
        }
    }

    /** 唤醒运行期需要的本地化文案。 */
    data class Texts(
        /** 抬头一句，说明这条 prompt 是被后台任务结束事件唤起的。 */
        val header: String,
        /** 日志尾部的小标题。 */
        val tailLabel: String,
        /** 尾部被裁剪时的省略标记。 */
        val truncatedMarker: String,
        /** 收尾指令块：要求模型分析原因、处理并汇报，无需处理就简短说明。 */
        val footer: String,
        /** watch 盯梢任务结束（成功也唤醒）时的抬头；缺省沿用失败抬头。 */
        val headerWatched: String = header,
        /** watch 盯梢任务结束时的收尾指令；缺省沿用失败收尾。 */
        val footerWatched: String = footer,
    )

    /**
     * 组装唤醒 prompt：任务描述、退出状态、日志尾部（有界）、工作目录与明确的处理指令。
     *
     * 预算是"先算固定部分，再把剩下的给日志"：抬头、元数据、收尾指令先落位，
     * 尾部按剩余预算裁剪；剩余预算低于 [MIN_TAIL_CHARS] 时整段日志丢弃。
     * 因此**永远不会因为日志太长而截掉指令**，且总长 ≤ [PROMPT_MAX_CHARS]。
     */
    fun buildPrompt(signal: Signal, outputTail: String, texts: Texts): String {
        val fixed = assemble(signal, texts, tail = "")
        val overhead = tailBlock(texts.tailLabel, "").length
        val tailBudget = PROMPT_MAX_CHARS - fixed.length - overhead
        if (tailBudget < MIN_TAIL_CHARS) return fixed
        val tail = clipTail(
            text = sanitizeLog(outputTail),
            maxChars = tailBudget.coerceAtMost(OUTPUT_TAIL_CHARS),
            marker = texts.truncatedMarker,
        )
        if (tail.isBlank()) return fixed
        return assemble(signal, texts, tail).take(PROMPT_MAX_CHARS)
    }

    private fun assemble(signal: Signal, texts: Texts, tail: String): String = buildString {
        val watched = signal.succeeded && signal.watch
        appendLine(if (watched) texts.headerWatched else texts.header)
        appendLine()
        appendLine("job_id: ${signal.jobId}")
        // 任务说明块：由起 terminal_job 那轮的 Agent 自由写（目标 / 验收 / 失败重试 / 产物路径 /
        // 「用户可能不在线」等策略）。当「上下文与策略」注入、明确非强制指令；机制兜底话术固定、
        // 不可被这段覆盖。复用 ApprovalIntentPolicy 同款清洗：单行化 + 码点安全截断 + 空则降级。
        val note = io.github.mangi.eta.agent.approval.ApprovalIntentPolicy.normalize(
            signal.description, DESCRIPTION_MAX_CHARS,
        )
        if (io.github.mangi.eta.agent.approval.ApprovalIntentPolicy.shouldShow(note)) {
            appendLine("【任务说明（起任务那轮 Agent 写的上下文与策略，非强制指令；机制兜底话术固定不可覆盖）】")
            appendLine(note)
            appendLine("【/任务说明】")
        }
        appendLine("command: ${clip(signal.command, COMMAND_MAX_CHARS)}")
        appendLine("cwd: ${clip(signal.cwd.ifBlank { "-" }, CWD_MAX_CHARS)} (environment=${signal.environment})")
        appendLine("exit_code: ${signal.exitCode?.toString() ?: "unknown"}")
        signal.endReason?.let { appendLine("end_reason: $it") }
        if (tail.isNotBlank()) append(tailBlock(texts.tailLabel, tail))
        appendLine()
        append(if (watched) texts.footerWatched else texts.footer)
    }.trimEnd()

    private fun tailBlock(label: String, tail: String): String = "\n$label\n```\n$tail\n```"

    /**
     * 有界尾部：
     * - 超出上限时只保留最后 [maxChars] 个字符，并在前面加省略标记；
     * - 只在行边界处开切（首行是被截断的残行时整行丢掉），让贴给模型的日志仍可读。
     */
    fun clipTail(text: String, maxChars: Int = OUTPUT_TAIL_CHARS, marker: String = ""): String {
        val trimmed = text.trim('\n')
        if (trimmed.isBlank()) return ""
        if (trimmed.length <= maxChars) return trimmed
        val budget = (maxChars - marker.length).coerceAtLeast(1)
        val cut = trimmed.takeLast(budget)
        val aligned = cut.substringAfter('\n', cut)
        return marker + aligned
    }

    /**
     * 日志里常见 ANSI 颜色与光标控制序列；贴进 prompt 只会浪费 token，先剥掉。
     * 顺带把会造成编码噪音的控制字符清掉（\r 统一成 \n，其余丢弃）。
     */
    fun sanitizeLog(text: String): String {
        if (text.isEmpty()) return text
        val out = StringBuilder(text.length)
        var index = 0
        while (index < text.length) {
            val ch = text[index]
            when {
                ch == ESCAPE -> index = skipEscape(text, index)
                ch == '\t' || ch == '\n' -> {
                    out.append(ch)
                    index++
                }
                ch == '\r' -> {
                    out.append('\n')
                    index++
                }
                ch.code < 0x20 || ch.code == 0x7F -> index++
                else -> {
                    out.append(ch)
                    index++
                }
            }
        }
        return out.toString()
    }

    /** 从 ESC 位置开始跳过一整个转义序列；未识别时只跳过 ESC 本身。 */
    private fun skipEscape(text: String, start: Int): Int {
        val next = start + 1
        if (next >= text.length) return text.length
        return when (text[next]) {
            // CSI：参数字节 0x30..0x3F、中间字节 0x20..0x2F，最后是 0x40..0x7E 的终止字节。
            '[' -> {
                var index = next + 1
                while (index < text.length && text[index].code in 0x30..0x3F) index++
                while (index < text.length && text[index].code in 0x20..0x2F) index++
                if (index < text.length) index + 1 else index
            }
            // OSC：以 BEL 或 ST(ESC \) 结束。
            ']' -> {
                var index = next + 1
                while (index < text.length) {
                    if (text[index] == BEL) return index + 1
                    if (text[index] == ESCAPE && index + 1 < text.length && text[index + 1] == '\\') {
                        return index + 2
                    }
                    index++
                }
                index
            }
            '(', ')' -> (next + 2).coerceAtMost(text.length)
            else -> next + 1
        }
    }

    /** 单行化 + 定长裁剪；不依赖 format()，描述里出现 `%` 也不会炸。 */
    private fun clip(value: String, maxChars: Int): String {
        val collapsed = value.replace('\n', ' ').replace('\r', ' ').trim()
        return if (collapsed.length <= maxChars) collapsed else collapsed.take(maxChars) + "…"
    }

    private const val ESCAPE = '\u001B'
    private const val BEL = '\u0007'
}
