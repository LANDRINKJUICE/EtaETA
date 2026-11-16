package io.github.mangi.eta.data.repository

/**
 * 记忆文件布局的单一事实源：分割线、标题判定、归档标题、顶部常驻区兜底。
 *
 * 设计（2026-11-13 定案 + 空转根治，兼容优先）：
 * - **分割线** = fence-aware 的第一条独立 `---`/`***`/`___`。它是用户主动画的「常驻区 / 受约束区」界。
 * - 顶部常驻区（无条件全量注入、格式/日期/敏感串之外豁免）默认 = 文件开头到分割线之前。
 * - **空转兜底**：当文件没有 `# 核心记忆`（旧 core 会返回空、静默啥也不注入）且也没有分割线时，
 *   注入层把「文件开头到第二个 H1 之前」（即第一个 H1 区整体，现网是 `# Eta 工作规范`+`## 铁律`）
 *   当作常驻区，保证每轮都有内容、根治空转，且不要求用户先画分割线、不迁移任何数据。
 *   若文件本来就有 `# 核心记忆`，注入层走旧路径、完全不变（既有行为零回归）。
 * - 校验/过期只作用于「分割线以下」；无分割线的旧格式文件按「整份豁免」兼容历史数据（不追溯）。
 *
 * 注入、校验、过期、预览都走这里，避免口径漂移。
 */
internal object MemoryLayout {

    const val ARCHIVE_HEADING = "# 归档"
    const val CORE_HEADING = "# 核心记忆"

    /** fence-aware 的第一条独立水平分割线下标；无则 -1。 */
    fun dividerIndex(content: String): Int = firstDivider(content.split('\n'))

    /** 文件是否含 `# 核心记忆`（旧 core 有内容可提）。 */
    fun hasCoreHeading(content: String): Boolean =
        content.split('\n').any { it.trim() == CORE_HEADING }

    /**
     * 空转兜底用的常驻区：无 `# 核心记忆` 且无分割线时，把文件开头到「第二个 H1 之前」
     * （第一个 H1 区整体）作为常驻区。其余情况返回空（交给正常路径）。
     */
    fun pinnedTopFallback(content: String): String {
        if (content.isEmpty()) return ""
        if (hasCoreHeading(content)) return ""
        if (dividerIndex(content) >= 0) return ""
        val lines = content.split('\n')
        val h1 = h1Indexes(lines)
        // 只有一个 H1 时没有第二个 H1 作分界，整份都算常驻区（保证非空、不复发空转）；
        // 有 >=2 个 H1 时取到第二个 H1 之前（第一个 H1 区整体）。
        val end = if (h1.size >= 2) h1[1] else lines.size
        return lines.subList(0, end).joinToString("\n").trimEnd('\n')
    }

    /** [pinnedTopFallback] 对应的常驻区行数（1-based 闭区间用 1..count）；用于把常驻区排除出相关性候选。 */
    fun pinnedTopFallbackLineCount(content: String): Int {
        if (content.isEmpty() || hasCoreHeading(content) || dividerIndex(content) >= 0) return 0
        val lines = content.split('\n')
        val h1 = h1Indexes(lines)
        return if (h1.size >= 2) h1[1] else lines.size
    }

    /** 有分割线时，分割线之前的顶部常驻区原文；无分割线返回空。 */
    fun pinnedTopByDivider(content: String): String {
        val d = dividerIndex(content)
        if (d <= 0) return ""
        return content.split('\n').subList(0, d).joinToString("\n").trimEnd('\n')
    }

    /** 去掉常驻区（及其后那条分割线）后的剩余内容，交给 core/相关节/校验/过期。 */
    fun remainderAfterDivider(content: String): String {
        val lines = content.split('\n')
        val d = dividerIndex(content)
        if (d < 0) return content
        return lines.subList((d + 1).coerceAtMost(lines.size), lines.size).joinToString("\n")
    }

    private fun firstDivider(lines: List<String>): Int {
        var fence: String? = null
        for (i in lines.indices) {
            val ts = lines[i].trimStart()
            val marker = when { ts.startsWith("```") -> "```"; ts.startsWith("~~~") -> "~~~"; else -> null }
            if (marker != null) { fence = if (fence == marker) null else marker; continue }
            if (fence == null && isDividerLine(lines[i])) return i
        }
        return -1
    }

    private fun h1Indexes(lines: List<String>): List<Int> {
        var fence: String? = null
        val result = mutableListOf<Int>()
        for (i in lines.indices) {
            val ts = lines[i].trimStart()
            val marker = when { ts.startsWith("```") -> "```"; ts.startsWith("~~~") -> "~~~"; else -> null }
            if (marker != null) { fence = if (fence == marker) null else marker; continue }
            if (fence == null && headingLevel(lines[i]) == 1) result += i
        }
        return result
    }

    fun isDividerLine(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.length < 3) return false
        val char = trimmed[0]
        if (char != '-' && char != '*' && char != '_') return false
        return trimmed.all { it == char }
    }

    /** 1-3 级 ATX 标题级数；非标题返回 0（缩进 ≥4 空格不算，# 后须空格）。 */
    fun headingLevel(line: String): Int {
        if (line.length - line.trimStart().length >= 4) return 0
        val trimmed = line.trim()
        val level = trimmed.takeWhile { it == '#' }.length
        if (level !in 1..3) return 0
        return if (trimmed.length > level && trimmed[level] == ' ') level else 0
    }

    fun isHeading(line: String): Boolean = headingLevel(line) in 1..3

    /** 围栏外 1-3 级标题下标。 */
    fun headingIndexes(lines: List<String>): List<Int> {
        val result = mutableListOf<Int>()
        var fence: String? = null
        for (i in lines.indices) {
            val ts = lines[i].trimStart()
            val marker = when { ts.startsWith("```") -> "```"; ts.startsWith("~~~") -> "~~~"; else -> null }
            if (marker != null) { fence = if (fence == marker) null else marker; continue }
            if (fence == null && isHeading(lines[i])) result += i
        }
        return result
    }

    fun normalizeHeading(line: String): String = line.trim().lowercase().replace(Regex("\\s+"), " ")
}
