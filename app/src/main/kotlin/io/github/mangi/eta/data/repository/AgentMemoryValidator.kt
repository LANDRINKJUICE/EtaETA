package io.github.mangi.eta.data.repository

/**
 * 记忆写入的硬约束闸门。由 [AgentMemoryStore.writeLocked] 在写盘前调用，是所有写入路径
 * （用户手编保存 / 清空 / memory_write / 自动沉淀 / 角色记忆 / replaceAllIfRevision）的唯一咽喉。
 *
 * 布局（统一走 [MemoryLayout]）：
 * - 无分割线的旧格式文件 → 整份豁免（兼容历史数据、不追溯），但**仍扫敏感串**。
 * - 有分割线：分割线之前 = 顶部常驻区（格式/日期/长度全豁免，仅扫敏感串）；
 *   分割线以下 = Agent 区，套硬约束。
 *
 * 约束：
 * 1. 不带日期写不进去：新增/改动的节须含 `YYYY-MM-DD`；**节元数据 `created`/`verified` 也算带日期**
 *    （与自动过期共用同一个锚，模型用 upsert/append 自动生成的元数据即满足，不额外逼模型写正文日期）。
 * 2. 不写废话 = 单节字数上限（与 memory_write / 沉淀指令统一为 [MAX_SECTION_CHARS]）。
 * 3. 同名标题拒写 —— **增量**：存量就有的重名放行，只拦本次新造的重名（不追溯老数据）。
 * 4. token/密钥/验证码/私钥命中拒写（顶部区与受约束区都扫）。
 * 5. 归档区 `# 归档` 豁免日期/长度（过期物收容所）。
 *
 * 只校验「本次新增/改动的节」，存量且正文未变的节放行。角色记忆（[enforceSectionRules]=false）
 * 只扫敏感串，不吃分割线以下的格式约束（剧情记忆常见 `---` 场景分隔）。
 */
internal object AgentMemoryValidator {

    data class Violation(val code: String, val message: String)

    /** 与 AgentMemoryStore.MAX_WRITE_CONTENT_CHARS、沉淀指令保持同一口径。 */
    const val MAX_SECTION_CHARS = 3_500

    fun validate(
        content: String,
        oldContent: String? = null,
        enforceSectionRules: Boolean = true,
    ): Violation? {
        // 清空始终允许。
        if (content.isEmpty()) return null

        // 4. 敏感串：全文件（含顶部区、含无分割线旧文件）扫；但「存量已存在」放行——
        //    只拦本次新增的敏感串，避免历史里一处 token 把整份记忆永久锁成只读。
        SENSITIVE_PATTERNS.firstOrNull { pattern ->
            pattern.containsMatchIn(content) &&
                !(oldContent != null && pattern.containsMatchIn(oldContent))
        }?.let {
            return Violation("MEMORY_SENSITIVE_CONTENT", "本次写入新增了疑似密钥/令牌/验证码/私钥，禁止写入")
        }

        val divider = MemoryLayout.dividerIndex(content)
        if (divider < 0 || !enforceSectionRules) return null // 旧格式 / 角色记忆：只过上面的敏感串

        val lines = content.split('\n')
        val bodyLines = lines.subList(divider + 1, lines.size)
        val headings = MemoryLayout.headingIndexes(bodyLines)
        if (headings.isEmpty()) return null
        val globalOffset = divider + 1

        // 旧内容的「分割线以下」节正文（按归一化标题），用于增量判定「正文是否改动」。
        val oldBodies = sectionBodies(oldContent)

        // 3. 同名标题：统计「多余出现次数」（每个标题 max(0, count-1) 之和），
        //    只在本次比旧内容**增加**时才拒（存量重名放行，不追溯老数据）。
        val currentHeadings = headings.map { MemoryLayout.normalizeHeading(bodyLines[it]) }
        val oldHeadingList = if (oldContent != null) headingList(oldContent) else emptyList()
        val currentExcess = excessCount(currentHeadings)
        val oldExcess = excessCount(oldHeadingList)
        if (currentExcess > oldExcess) {
            val dup = currentHeadings.groupingBy { it }.eachCount().filter { it.value > 1 }.keys.first()
            return Violation("MEMORY_DUPLICATE_HEADING", "分割线以下新增了同名标题「$dup」，请整理后再保存")
        }

        for (pos in headings.indices) {
            val start = headings[pos]
            val end = headings.getOrNull(pos + 1) ?: bodyLines.size
            val heading = bodyLines[start].trim()
            val normalized = MemoryLayout.normalizeHeading(bodyLines[start])
            val lineNo = globalOffset + start + 1
            if (heading == MemoryLayout.ARCHIVE_HEADING) continue // 归档区豁免

            val rawBodyLines = bodyLines.subList(start + 1, end)
            val metadata = rawBodyLines.firstNotNullOfOrNull { AgentMemoryEntries.parseMetadataLine(it) }
            val sectionBody = rawBodyLines
                .filter { AgentMemoryEntries.parseMetadataLine(it) == null }
                .joinToString("\n")
            val trimmedBody = sectionBody.trim()
            if (trimmedBody.isEmpty()) continue

            // 存量且正文未变：放行（不追溯老数据），敏感串已在上面全文件扫过。
            if (oldContent != null && oldBodies[normalized] == trimmedBody) continue

            // 1. 带日期：正文 YYYY-MM-DD 或节元数据 created/verified 任一即可。
            val hasBodyDate = DATE_REGEX.containsMatchIn(trimmedBody)
            val hasMetadataDate = metadata?.created != null || metadata?.verified != null
            if (!hasBodyDate && !hasMetadataDate) {
                return Violation(
                    "MEMORY_DATE_REQUIRED",
                    "「$heading」缺少日期：分割线以下新增/改动的记忆须带 YYYY-MM-DD 或由 upsert/append 生成元数据（第 $lineNo 行）",
                )
            }

            // 2/5. 单节字数上限。
            if (trimmedBody.length > MAX_SECTION_CHARS) {
                return Violation(
                    "MEMORY_SECTION_TOO_LONG",
                    "「$heading」正文 ${trimmedBody.length} 字，超过 $MAX_SECTION_CHARS 字上限，请精简（第 $lineNo 行）",
                )
            }
        }
        return null
    }

    /** 解析某内容「分割线以下」各节的归一化标题 -> 去元数据后正文。无分割线返回空。 */
    private fun sectionBodies(content: String?): Map<String, String> {
        if (content == null) return emptyMap()
        val d = MemoryLayout.dividerIndex(content)
        if (d < 0) return emptyMap()
        val lines = content.split('\n')
        val body = lines.subList(d + 1, lines.size)
        val heads = MemoryLayout.headingIndexes(body)
        return heads.mapIndexed { pos, start ->
            val end = heads.getOrNull(pos + 1) ?: body.size
            val text = body.subList(start + 1, end)
                .filter { AgentMemoryEntries.parseMetadataLine(it) == null }
                .joinToString("\n").trim()
            MemoryLayout.normalizeHeading(body[start]) to text
        }.toMap()
    }

    /** 某内容「分割线以下」所有节的归一化标题列表（保留重复）。 */
    private fun headingList(content: String): List<String> {
        val d = MemoryLayout.dividerIndex(content)
        if (d < 0) return emptyList()
        val lines = content.split('\n')
        val body = lines.subList(d + 1, lines.size)
        return MemoryLayout.headingIndexes(body).map { MemoryLayout.normalizeHeading(body[it]) }
    }

    /** 多余出现次数之和：每个标题 max(0, count-1)。用于增量判定重名。 */
    private fun excessCount(items: List<String>): Int =
        items.groupingBy { it }.eachCount().values.sumOf { (it - 1).coerceAtLeast(0) }

    private val DATE_REGEX = Regex("""\b\d{4}-\d{2}-\d{2}\b""")
    private val SENSITIVE_PATTERNS = listOf(
        Regex("""gh[pousr]_[A-Za-z0-9]{20,}"""),
        Regex("""(?:sk|pk)-[A-Za-z0-9]{20,}"""),
        Regex("""-----BEGIN (?:RSA |EC |OPENSSH |)PRIVATE KEY-----"""),
        Regex("""(?i)\b(?:api[_-]?key|access[_-]?token|bearer)\s*[:=]\s*\S{12,}"""),
        Regex("""(?i)\b(?:password|passwd|secret)\s*[:=]\s*\S{8,}"""),
    )
}
