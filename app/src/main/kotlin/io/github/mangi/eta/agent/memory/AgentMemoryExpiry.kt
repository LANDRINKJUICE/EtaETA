package io.github.mangi.eta.agent.memory

import io.github.mangi.eta.data.repository.AgentMemoryEntries
import io.github.mangi.eta.data.repository.MemoryLayout
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

/**
 * 记忆自动过期的单一事实源：默认阈值、日期识别、到期判定、到期归档。
 * 纯 Kotlin、不读文件、不发模型回合，便于 JVM 单测。
 *
 * 约定：只整理「分割线以下」（[MemoryLayout]）。按节的**核对日期**算年龄：
 * 优先取元数据 `verified`，退化 `created`，再退化正文里第一个 `YYYY-MM-DD`。
 * 超过 [AgentMemoryExpiryDefaults.DAYS] 天的整节（含其下所有子节）移到文件末尾 `# 归档`
 * 区（不静默删除，可查可恢复）。顶部常驻区永不过期、无日期的节不动、已在归档区不重复处理。
 */
internal object AgentMemoryExpiryDefaults {
    const val DAYS = 90
}

internal object AgentMemoryExpiry {

    /**
     * @param days 过期天数阈值。
     * @return 整理后的全文；无变化时返回原串（调用方据此避免无谓写盘 / revision 抖动）。
     */
    fun archiveExpired(content: String, now: LocalDate, days: Int = AgentMemoryExpiryDefaults.DAYS): String {
        if (content.isEmpty()) return content
        val divider = MemoryLayout.dividerIndex(content)
        if (divider < 0) return content // 无分割线的旧格式：不整理，兼容历史

        val lines = content.split('\n').toMutableList()
        val bodyStart = divider + 1
        val headIdx = MemoryLayout.headingIndexes(lines.subList(bodyStart, lines.size))
            .map { it + bodyStart }

        data class Block(val start: Int, val end: Int, val level: Int, val heading: String, val text: String)
        val blocks = headIdx.mapIndexed { pos, start ->
            val level = MemoryLayout.headingLevel(lines[start])
            // B5：块边界取「下一个同级或更高级标题」，与 upsertSection 一致，子节随父一起搬走。
            val end = headIdx.drop(pos + 1).firstOrNull { next ->
                MemoryLayout.headingLevel(lines[next]) <= level
            } ?: lines.size
            Block(start, end, level, lines[start].trim(), lines.subList(start, end).joinToString("\n"))
        }

        val archiveStart = blocks.firstOrNull { it.heading == MemoryLayout.ARCHIVE_HEADING }?.start ?: -1

        // 只收集「最外层过期块」：父块范围天然包含子块，若子块也过期要跳过，避免重复归档与重叠删除。
        // 用「是否被已选块覆盖」判定，单次遍历，不产生任何重叠 range。
        val expiredBlocks = mutableListOf<Block>()
        var coveredUntil = -1
        for (block in blocks) {
            if (block.start < coveredUntil) continue // 已被更外层过期块覆盖
            if (block.heading == MemoryLayout.ARCHIVE_HEADING) continue
            if (archiveStart >= 0 && block.start >= archiveStart) continue // 已在归档区
            val date = verifiedDate(block.text) ?: continue // 无日期：不追溯
            if (ChronoUnit.DAYS.between(date, now) <= days) continue
            expiredBlocks += block
            coveredUntil = block.end
        }
        if (expiredBlocks.isEmpty()) return content

        // 一次性构造新列表：跳过被删区间（不重叠、无需反向 removeAt，O(n) 且不会误删后续内容）。
        val remove = BooleanArray(lines.size)
        for (block in expiredBlocks) {
            for (i in block.start until block.end) remove[i] = true
        }
        val kept = lines.filterIndexed { i, _ -> !remove[i] }.toMutableList()

        // 归档区：已有就复用，没有就在文末新建。
        var archiveLine = kept.indexOfFirst { it.trim() == MemoryLayout.ARCHIVE_HEADING }
        if (archiveLine < 0) {
            if (kept.isNotEmpty() && kept.last().isNotBlank()) kept += ""
            kept += MemoryLayout.ARCHIVE_HEADING
            archiveLine = kept.size - 1
        }
        // M2/M3：过期内容插到归档标题之后；若归档区已有同名节则去掉自身标题只留正文，避免同名累积。
        val insertAt = archiveLine + 1
        val additions = mutableListOf<String>()
        val existingHeadings = kept.map { MemoryLayout.normalizeHeading(it.trim()) }.toHashSet()
        for (block in expiredBlocks) {
            val tlines = block.text.split('\n').toMutableList()
            val first = tlines.firstOrNull()?.trim().orEmpty()
            if (MemoryLayout.isHeading(tlines.firstOrNull().orEmpty()) &&
                MemoryLayout.normalizeHeading(first) in existingHeadings
            ) {
                tlines.removeAt(0) // 同名：去掉标题，只保留正文
            }
            additions += ""
            additions += tlines
        }
        kept.addAll(insertAt, additions)
        return kept.joinToString("\n")
    }

    /** 节的核对日期：元数据 verified > created > 正文第一个 YYYY-MM-DD。 */
    fun verifiedDate(sectionText: String): LocalDate? {
        // 元数据行通常在标题下一行；只有 id 没日期时继续退化到正文。
        sectionText.lineSequence().take(4).forEach { line ->
            val meta = AgentMemoryEntries.parseMetadataLine(line) ?: return@forEach
            val date = meta.verified ?: meta.created
            if (date != null) return date
        }
        return firstDate(sectionText)
    }

    /** 文本里第一个 YYYY-MM-DD。 */
    fun firstDate(text: String): LocalDate? {
        val match = DATE_REGEX.find(text) ?: return null
        return try {
            LocalDate.parse(match.value)
        } catch (_: DateTimeParseException) {
            null
        }
    }

    private val DATE_REGEX = Regex("""\b(\d{4}-\d{2}-\d{2})\b""")
}
