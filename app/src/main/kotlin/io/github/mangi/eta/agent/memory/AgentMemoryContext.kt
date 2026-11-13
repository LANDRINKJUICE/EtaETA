package io.github.mangi.eta.agent.memory

import io.github.mangi.eta.data.repository.AgentMemoryEntries
import io.github.mangi.eta.data.repository.AgentMemorySnapshot
import java.time.LocalDate

internal data class AgentMemoryContext(
    val enabled: Boolean,
    val revision: String,
    val byteSize: Int,
    val coreContent: String,
    val coreTruncated: Boolean,
    val headingIndex: String,
    val coreBudgetChars: Int,
    /** 按当前请求相关度选出的非核心节，已带来源标注；空表示本会话没有可注入的节。 */
    val injectedSections: String = "",
    val injectedHeadings: List<String> = emptyList(),
    val injectedTruncated: Boolean = false,
    val injectedBudgetChars: Int = 0,
) {
    companion object {
        val DISABLED = AgentMemoryContext(
            enabled = false,
            revision = "",
            byteSize = 0,
            coreContent = "",
            coreTruncated = false,
            headingIndex = "",
            coreBudgetChars = 0,
        )
    }
}

internal object AgentMemoryContextBuilder {
    fun empty(contextWindow: Int?): AgentMemoryContext = build(
        snapshot = AgentMemorySnapshot(
            content = "",
            revision = EMPTY_SHA256,
            byteSize = 0,
            lineCount = 0,
        ),
        contextWindow = contextWindow,
    )

    /**
     * 组装注入内容。
     *
     * 注入分两块，互不干扰：
     * - **顶部块（无条件全量）**：文件第 1 行到第一条独立 `---` 分割线之间的原文，
     *   每轮必进、与 [query] 无关、不受相关性打分与开关影响。按「位置」切而非按标题名找，
     *   因此只要文件非空就一定有内容，结构上杜绝「找不到 `# 核心记忆` 就静默空转」。
     *   顶部区归用户自由书写（也可由 Agent 代写），格式/日期校验均豁免。
     * - **核心节 + 相关节**：沿用既有 `# 核心记忆` core 提取与 top-k 相关性注入，
     *   仅在顶部块之外生效，行为保持不变。
     *
     * [query] 为会话最新一条用户消息；为空时不注入非核心节（关闭相关性注入，或本轮没有用户输入）。
     */
    fun build(
        snapshot: AgentMemorySnapshot,
        contextWindow: Int?,
        query: String? = null,
        now: LocalDate = LocalDate.now(),
        maxSections: Int = MAX_INJECTED_SECTIONS,
    ): AgentMemoryContext {
        val layout = io.github.mangi.eta.data.repository.MemoryLayout
        val coreBudget = coreBudgetChars(contextWindow)
        val content = snapshot.content

        // 兼容优先 + 空转根治：
        // ① 有用户画的分割线 → 顶部区（分割线前）无条件全量注入，下面走旧 core/相关节逻辑。
        // ② 没有分割线但文件本来就有 # 核心记忆 → 完全走旧路径，既有行为零回归。
        // ③ 既无分割线、又没有 # 核心记忆（旧 core 会空转）→ 用 H1 兜底把第一个 H1 区整体
        //    作为常驻区，保证每轮都有内容注入；不要求用户先画分割线、不迁移数据。
        val hasDivider = layout.dividerIndex(content) >= 0
        var pinned: String
        val remainder: String
        val legacyCore: String
        // 分支③需把常驻区行排除出相关性候选（否则既全量注入又参与打分，重复注入）。
        var excludeRange: IntRange? = null
        if (hasDivider) {
            pinned = layout.pinnedTopByDivider(content)
            remainder = layout.remainderAfterDivider(content)
            legacyCore = extractCore(remainder)
            // 开头就是分割线（顶部区为空）：退回兜底，避免空转复发。
            if (pinned.isEmpty()) {
                val fallback = layout.pinnedTopFallback(remainder)
                if (fallback.isNotBlank()) {
                    // 此时兜底内容来自 remainder 开头，把它作为常驻区补上。
                    pinned = fallback
                }
            }
        } else if (layout.hasCoreHeading(content)) {
            pinned = ""
            remainder = content
            legacyCore = extractCore(content)
        } else {
            pinned = layout.pinnedTopFallback(content)
            remainder = content
            legacyCore = ""
            val count = layout.pinnedTopFallbackLineCount(content)
            if (count > 0) excludeRange = 1..count
        }
        val core = joinPinnedAndCore(pinned, legacyCore)
        val headings = content.lineSequence()
            .filter { line -> HEADING.matches(line.trimEnd()) }
            .joinToString("\n")
            .take(MAX_HEADING_INDEX_CHARS)
        val remaining = (coreBudget - minOf(core.length, coreBudget)).coerceAtLeast(0)
        val injected = if (remaining <= 0) {
            Injected()
        } else {
            injectSections(
                content = remainder,
                query = query,
                now = now,
                maxSections = maxSections,
                budget = minOf(MAX_INJECTED_CHARS, remaining),
                coreRange = excludeRange
                    ?: if (hasDivider || layout.hasCoreHeading(content)) extractCoreRange(remainder) else null,
            )
        }
        return AgentMemoryContext(
            enabled = true,
            revision = snapshot.revision,
            byteSize = snapshot.byteSize,
            coreContent = core.take(coreBudget),
            coreTruncated = core.length > coreBudget,
            headingIndex = headings,
            coreBudgetChars = coreBudget,
            injectedSections = injected.content,
            injectedHeadings = injected.headings,
            injectedTruncated = injected.truncated,
            injectedBudgetChars = injected.budget,
        )
    }

    private fun joinPinnedAndCore(pinned: String, legacyCore: String): String = when {
        pinned.isEmpty() -> legacyCore
        legacyCore.isEmpty() -> pinned
        else -> pinned + "\n\n" + legacyCore
    }

    fun coreBudgetChars(contextWindow: Int?): Int {
        val resolvedWindow = contextWindow?.takeIf { it > 0 } ?: DEFAULT_CONTEXT_WINDOW
        return (resolvedWindow / CONTEXT_WINDOW_DIVISOR)
            .coerceIn(MIN_CORE_CHARS, MAX_CORE_CHARS)
    }

    private data class Injected(
        val content: String = "",
        val headings: List<String> = emptyList(),
        val truncated: Boolean = false,
        val budget: Int = 0,
    )

    private fun injectSections(
        content: String,
        query: String?,
        now: LocalDate,
        maxSections: Int,
        budget: Int,
        coreRange: IntRange?,
    ): Injected {
        if (budget <= 0 || maxSections <= 0 || query.isNullOrBlank() || content.isEmpty()) {
            return Injected(budget = budget.coerceAtLeast(0))
        }
        val candidates = AgentMemoryEntries.parse(content)
            .filter { entry -> entry.heading.trim() != CORE_HEADING }
            .filter { entry -> coreRange == null || entry.startLine !in coreRange }
        if (candidates.isEmpty()) return Injected(budget = budget)
        val selected = MemoryRelevance.select(
            entries = candidates,
            query = query,
            now = now,
            limit = maxSections,
        )
        if (selected.isEmpty()) return Injected(budget = budget)
        val rendered = StringBuilder()
        val headings = mutableListOf<String>()
        var truncated = false
        for (section in selected) {
            val block = renderSection(section)
            val separator = if (rendered.isEmpty()) 0 else 1
            val available = budget - rendered.length - separator
            if (available <= MIN_SECTION_CHARS) {
                truncated = true
                break
            }
            if (separator > 0) rendered.append('\n')
            if (block.length <= available) {
                rendered.append(block)
            } else {
                rendered.append(block.take((available - TRUNCATED_MARKER.length).coerceAtLeast(0)))
                rendered.append(TRUNCATED_MARKER)
                truncated = true
            }
            headings += section.entry.heading
            if (truncated) break
        }
        return Injected(
            content = rendered.toString().trimEnd('\n'),
            headings = headings,
            truncated = truncated,
            budget = budget,
        )
    }

    private fun renderSection(section: ScoredMemorySection): String {
        val entry = section.entry
        val source = buildString {
            append("[来源：MEMORY.md 第 ")
            append(entry.startLine)
            append('-')
            append(entry.endLine)
            append(" 行")
            entry.lastUpdated?.let { date ->
                append("；最近核对 ")
                append(date)
            }
            entry.metadata.type?.let { type ->
                append("；类型 ")
                append(type)
            }
            // 故意不渲染具体分数：措辞变化不应改写系统提示，否则会话缓存会被无谓地打掉。
            append(']')
        }
        return buildString {
            append(entry.heading)
            append('\n')
            append(source)
            if (entry.body.isNotBlank()) {
                append('\n')
                append(entry.body)
            }
        }
    }

    /**
     * 与 [extractCore] 一致的核心块行号（1 起始、闭区间）；没有核心标题时为 null。
     *
     * 标题判定复用 [AgentMemoryEntries.headingIndexes] 的围栏规则：核心节正文里用 ``` 包住的
     * `# 注释` 不会被当成新的 1 级标题，否则核心节会被截断并留下未闭合围栏。
     */
    private fun extractCoreRange(content: String): IntRange? {
        if (content.isEmpty()) return null
        val lines = content.split('\n')
        val headings = AgentMemoryEntries.headingIndexes(lines)
        val start = headings.firstOrNull { index -> lines[index].trim() == CORE_HEADING } ?: return null
        val endExclusive = headings
            .firstOrNull { index -> index > start && lines[index].trim().startsWith("# ") }
            ?: lines.size
        if (endExclusive <= start) return null
        return (start + 1)..endExclusive
    }

    private fun extractCore(content: String): String {
        val range = extractCoreRange(content) ?: return ""
        val lines = content.split('\n')
        // 元数据注释行不进系统提示，与条目解析口径一致。
        return lines.subList(range.first - 1, range.last)
            .filter { AgentMemoryEntries.parseMetadataLine(it) == null }
            .joinToString("\n")
    }

    private val HEADING = Regex("^#{1,2}\\s+.+$")
    private const val CORE_HEADING = "# 核心记忆"
    /** 窗口未知时用于按比例分配记忆、世界书等注入预算的保守基准，不参与压缩判定。 */
    const val DEFAULT_CONTEXT_WINDOW = 128_000
    private const val CONTEXT_WINDOW_DIVISOR = 16
    private const val MIN_CORE_CHARS = 4_000
    private const val MAX_CORE_CHARS = 32_000
    private const val MAX_HEADING_INDEX_CHARS = 4_000
    const val MAX_INJECTED_SECTIONS = 3
    private const val MAX_INJECTED_CHARS = 4_000
    private const val MIN_SECTION_CHARS = 80
    private const val TRUNCATED_MARKER = "…（节已截断，完整内容用 memory_get 读取）"
    private const val EMPTY_SHA256 =
        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
}

/**
 * 决定本轮是否把"最新一条用户消息"作为相关性注入的查询文本。
 *
 * 角色会话的现实记忆保持原样（只注入核心节与标题索引）：剧情文本可能包含虚构事实，
 * 拿它去和相关记忆打分既无意义又会把剧情内容带进现实记忆的注入块。
 */
internal object AgentMemoryInjectionPolicy {
    fun recallQuery(
        prompt: String,
        relevanceEnabled: Boolean,
        roleplay: Boolean,
    ): String? = prompt.takeIf { !roleplay && relevanceEnabled && it.isNotBlank() }
}
