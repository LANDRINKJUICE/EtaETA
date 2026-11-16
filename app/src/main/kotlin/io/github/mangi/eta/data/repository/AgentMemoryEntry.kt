package io.github.mangi.eta.data.repository

import java.security.MessageDigest
import java.time.LocalDate

/**
 * 一个 Markdown 节的元数据。
 *
 * 元数据以 HTML 注释行保存在节首，例如 `<!-- eta:id=e3f1;created=2026-01-02;verified=2026-03-01 -->`：
 * Markdown 渲染时不可见，旧文件没有这一行时全部字段为 null，解析与注入行为与一期完全一致。
 */
internal data class AgentMemoryEntryMetadata(
    val id: String? = null,
    val created: LocalDate? = null,
    val verified: LocalDate? = null,
    val type: String? = null,
) {
    val isEmpty: Boolean
        get() = id == null && created == null && verified == null && type == null
}

/** MEMORY.md 中由标题界定的一个节（正文不含标题行与元数据注释行）。 */
internal data class AgentMemoryEntry(
    val id: String,
    val heading: String,
    val level: Int,
    val body: String,
    /** 标题行 1 起始行号。 */
    val startLine: Int,
    /** 本节最后一行（含）的 1 起始行号。 */
    val endLine: Int,
    val metadata: AgentMemoryEntryMetadata = AgentMemoryEntryMetadata(),
) {
    /** 最近一次核对（没有核对记录时退化为创建）日期，供新鲜度加权使用。 */
    val lastUpdated: LocalDate?
        get() = metadata.verified ?: metadata.created

    /** 去掉 `#` 的标题文本。 */
    val title: String
        get() = heading.trimStart('#').trim()
}

/**
 * 节解析与元数据戳记的纯函数集合。
 *
 * 只做文本到模型的映射，不读写文件，便于在单元测试里直接验证一期打分、
 * 二期时间戳与四期向量的输入格式。
 */
internal object AgentMemoryEntries {
    /** 单个注释行能携带的字段；`type` 目前用于标记状态类条目（state/status 等）。 */
    private val METADATA = Regex("^\\s*<!--\\s*eta:([^>]*?)-->\\s*$")

    /** 解析全文，按标题切成互不重叠的节（任意 1–3 级标题都是边界）。 */
    fun parse(content: String): List<AgentMemoryEntry> {
        if (content.isEmpty()) return emptyList()
        val lines = content.split('\n')
        val headings = headingIndexes(lines)
        if (headings.isEmpty()) return emptyList()
        val entries = ArrayList<AgentMemoryEntry>(headings.size)
        headings.forEachIndexed { position, index ->
            val level = headingLevel(lines[index])
            val end = headings
                .drop(position + 1)
                .firstOrNull { candidate -> candidate > index }
                ?: lines.size
            val heading = lines[index].trim()
            val rawBody = lines.subList(index + 1, end)
            val metadataAt = rawBody.indexOfFirst { it.isNotBlank() }
            val metadata = rawBody.getOrNull(metadataAt)?.let(::parseMetadataLine)
            val bodyLines = if (metadata == null) {
                rawBody
            } else {
                rawBody.filterIndexed { bodyIndex, _ -> bodyIndex != metadataAt }
            }
            val body = bodyLines.joinToString("\n").trim('\n')
            entries += AgentMemoryEntry(
                id = metadata?.id?.takeIf { it.isNotBlank() } ?: stableId(heading, body),
                heading = heading,
                level = level,
                body = body,
                startLine = index + 1,
                endLine = end,
                metadata = metadata ?: AgentMemoryEntryMetadata(),
            )
        }
        return entries
    }

    /** 解析单行元数据注释；不是 eta 注释或无法解析时返回 null。 */
    fun parseMetadataLine(line: String): AgentMemoryEntryMetadata? {
        val payload = METADATA.matchEntire(line)?.groupValues?.get(1) ?: return null
        val fields = payload.split(';')
            .map(String::trim)
            .filter { it.isNotEmpty() }
            .mapNotNull { part ->
                if (!part.contains('=')) return@mapNotNull null
                part.substringBefore('=').trim().lowercase() to part.substringAfter('=').trim()
            }
            .toMap()
        return AgentMemoryEntryMetadata(
            id = fields["id"]?.takeIf { it.isNotBlank() },
            created = fields["created"]?.let(::parseDate),
            verified = fields["verified"]?.let(::parseDate),
            type = fields["type"]?.takeIf { it.isNotBlank() },
        )
    }

    /** 渲染元数据注释行；字段为空时省略，避免写入无意义的占位。 */
    fun formatMetadata(metadata: AgentMemoryEntryMetadata): String {
        val parts = buildList {
            metadata.id?.takeIf { it.isNotBlank() }?.let { add("id=$it") }
            metadata.created?.let { add("created=$it") }
            metadata.verified?.let { add("verified=$it") }
            metadata.type?.takeIf { it.isNotBlank() }?.let { add("type=$it") }
        }
        return "<!-- eta:" + parts.joinToString(";") + " -->"
    }

    /**
     * 给一节正文补齐元数据：已有注释行时只刷新 `verified`（缺失的 `created`/`id` 一并补上），
     * 没有时在正文最前插入一行。正文本身逐字节保留。
     *
     * [previous] 是同一标题在文件里已有的元数据：整体替换节正文时用它继承 `id`/`created`，
     * 使"更新已有主题"不会丢掉创建时间与向量标识。
     */
    fun stampSection(
        heading: String,
        body: String,
        date: LocalDate,
        previous: AgentMemoryEntryMetadata? = null,
    ): String {
        val bodyLines = if (body.isEmpty()) emptyList() else body.split('\n')
        return stampBodyLines(heading, bodyLines, date, previous).joinToString("\n")
    }

    /** 给追加内容里的每个节补齐元数据；标题之前的前导文本原样保留。 */
    fun stampSections(content: String, date: LocalDate): String {
        if (content.isBlank()) return content
        val entries = parse(content)
        if (entries.isEmpty()) return content
        val lines = content.split('\n').toMutableList()
        // 从后往前替换，前面的行号才不会失效。
        entries.asReversed().forEach { entry ->
            val bodyStart = entry.startLine.coerceAtMost(lines.size)
            val bodyEnd = entry.endLine.coerceAtMost(lines.size)
            if (bodyStart > bodyEnd) return@forEach
            val stamped = stampBodyLines(entry.heading, lines.subList(bodyStart, bodyEnd).toList(), date)
            lines.subList(bodyStart, bodyEnd).clear()
            lines.addAll(bodyStart, stamped)
        }
        return lines.joinToString("\n")
    }

    /** 由标题与正文派生稳定 id：正文改变才会变，仅刷新 verified 时保持不变。 */
    fun stableId(heading: String, body: String): String =
        "e" + sha256(heading.trim() + "\n" + body.trim()).take(12)

    /**
     * 生成/更新一节的元数据行。
     *
     * `id`/`created` 的来源优先级是"文件里已有的同名节 > 正文自带的注释行 > 现算稳定 id"：
     * 正文里的注释行可能是模型手写或从别处粘贴来的，不能覆盖这一节真实的历史标识。
     * 任何来源的 id 都必须匹配 [ID_PATTERN]，否则丢弃重算，避免脏 id 污染向量缓存键。
     * 同一节里多出的 `<!-- eta:… -->` 行会被丢弃，避免元数据行重复进入 prompt。
     */
    private fun stampBodyLines(
        heading: String,
        bodyLines: List<String>,
        date: LocalDate,
        previous: AgentMemoryEntryMetadata? = null,
    ): List<String> {
        val firstContent = bodyLines.indexOfFirst { it.isNotBlank() }
        val inline = bodyLines.getOrNull(firstContent)?.let(::parseMetadataLine)
        // 首个非空行之前的行都是空行，删除其他 eta 注释不会移动 firstContent。
        val cleaned = bodyLines.filterIndexed { index, line ->
            index == firstContent || parseMetadataLine(line) == null
        }
        val bodyForId = cleaned.filterIndexed { index, _ -> index != firstContent }.joinToString("\n")
        val id = sanitizeId(previous?.id)
            ?: sanitizeId(inline?.id)
            ?: stableId(heading, bodyForId)
        val metadata = AgentMemoryEntryMetadata(
            id = id,
            created = previous?.created ?: inline?.created ?: date,
            verified = date,
            type = previous?.type ?: inline?.type,
        )
        if (firstContent >= 0 && inline != null) {
            return cleaned.toMutableList().also { it[firstContent] = formatMetadata(metadata) }
        }
        val insertAt = if (firstContent >= 0) firstContent else cleaned.size
        return cleaned.toMutableList().also { it.add(insertAt, formatMetadata(metadata)) }
    }

    /** `id` 只接受本模块生成的稳定格式，其他写法（手写、粘贴、旧版本）一律重算。 */
    private fun sanitizeId(candidate: String?): String? =
        candidate?.trim()?.takeIf { ID_PATTERN.matches(it) }

    private fun parseDate(raw: String): LocalDate? {
        val candidate = raw.trim().substringBefore('T').substringBefore(' ')
        if (candidate.isEmpty()) return null
        return runCatching { LocalDate.parse(candidate) }.getOrNull()
    }

    /** 稳定 id 的合法形态：`e` + 12 位小写十六进制。 */
    private val ID_PATTERN = Regex("^e[0-9a-f]{12}$")

    /** 围栏代码块之外的 1–3 级标题行下标；核心节范围判定也复用同一套围栏规则。 */
    fun headingIndexes(lines: List<String>): List<Int> {
        val result = mutableListOf<Int>()
        var fence: String? = null
        lines.forEachIndexed { index, line ->
            val trimmed = line.trimStart()
            val marker = when {
                trimmed.startsWith("```") -> "```"
                trimmed.startsWith("~~~") -> "~~~"
                else -> null
            }
            if (marker != null) {
                fence = if (fence == marker) null else marker
                return@forEachIndexed
            }
            if (fence == null && headingLevel(line) in 1..3) result += index
        }
        return result
    }

    private fun headingLevel(line: String): Int {
        if (line.length - line.trimStart().length >= 4) return 0
        val trimmed = line.trim()
        val level = trimmed.takeWhile { it == '#' }.length
        if (level !in 1..3) return 0
        return if (trimmed.length > level && trimmed[level] == ' ') level else 0
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
}
