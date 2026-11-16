package io.github.mangi.eta.agent.approval

/**
 * 危险确认卡「意图 / 你要干什么」文本的纯逻辑规整，不引用任何 android.*，可在纯 JVM 上回归。
 *
 * 模型调工具时可选带一个简短中文 `intent`，经 [AgentToolApprovalGate] 读出后显示在确认卡
 * 与 heads-up 通知里。文本来自模型、会显示给用户并伴随一键放行按钮，所以必须：
 * - 折叠所有内部空白（换行 / 制表 / 连续空格）为单个空格：否则模型写
 *   `"目的\n· 只读操作，可放行"` 会在通知的逐行正文里伪装出一条「风险理由」，误导放行决策；
 * - 按**码点**而非 UTF-16 单元截断：直接 `String.take(n)` 可能把 emoji 代理对切成两半，
 *   显示成乱码方块（替换字符）；
 * - 卡片与通知共用本对象，避免两处口径漂移；两处用不同的 [maxChars]（通知预算更小，
 *   不能让意图把命令原文挤出可见正文）。
 */
internal object ApprovalIntentPolicy {

    /** 卡片里意图的展示上限（字，按 Unicode 码点计）。 */
    const val CARD_MAX_CHARS = 120

    /** 通知正文预算更小：意图单独限到 48 字，保证命令原文仍占大头。 */
    const val NOTIFICATION_MAX_CHARS = 48

    /**
     * 空白折叠模式：`\s` 覆盖 ASCII 空白（含换行/制表），`\p{Z}` 补全 Unicode 分隔符
     * （全角空格 U+3000、不换行空格、U+2028/U+2029 行/段分隔符等）。只靠 `\s` 时模型
     * 可用 U+2028 在通知逐行正文里伪造一条额外「风险理由」，这里一并封死。
     * 提成静态字段避免每次审批事件重新编译正则。
     */
    private val WHITESPACE = Regex("[\\s\\p{Z}]+")

    /**
     * 规整模型给的原始 intent：折叠空白 → trim → 按码点截断到 [maxChars]
     * （超限时补一个省略号，提示"句子被截断"而不是看起来没写完）。
     *
     * @param raw 工具参数里的原始值（可能含换行、超长、emoji）。
     * @param maxChars 展示上限（码点数，含省略号）；<=0 时返回空串。
     * @return 可直接拼进卡片 / 通知的单行文本；输入为空 / 全空白时返回空串（调用方据此跳过显示）。
     */
    fun normalize(raw: String, maxChars: Int): String {
        if (maxChars <= 0) return ""
        val collapsed = raw.replace(WHITESPACE, " ").trim()
        if (collapsed.isEmpty()) return ""
        // codePointCount 按 Unicode 码点计数，offsetByCodePoints 取到完整码点边界，
        // 不会把 emoji / 增补汉字的代理对切成半个字符。
        val count = collapsed.codePointCount(0, collapsed.length)
        return if (count <= maxChars) {
            collapsed
        } else {
            // 留 1 个码点给省略号，且保持代理对完整。
            val keep = (maxChars - 1).coerceAtLeast(0)
            val head = if (keep > 0) collapsed.substring(0, collapsed.offsetByCodePoints(0, keep)) else ""
            head + "…"
        }
    }

    /** 该文本是否值得展示（规整后非空）。卡片 / 通知用同一个守卫，避免显示空「目的：」。 */
    fun shouldShow(normalized: String): Boolean = normalized.isNotBlank()
}
