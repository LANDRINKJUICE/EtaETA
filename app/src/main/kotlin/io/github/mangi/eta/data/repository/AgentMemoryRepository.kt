package io.github.mangi.eta.data.repository

import android.content.Context
import android.util.AtomicFile
import io.github.mangi.eta.agent.memory.AgentMemorySemanticIndex
import io.github.mangi.eta.data.datastore.SettingsDataStore
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow

internal data class AgentMemorySnapshot(
    val content: String,
    val revision: String,
    val byteSize: Int,
    val lineCount: Int,
)

internal data class AgentMemoryReadResult(
    val snapshot: AgentMemorySnapshot,
    val content: String,
    val startLine: Int?,
    val endLine: Int?,
    val hasMore: Boolean,
    val matchedLines: Int,
)

internal sealed interface AgentMemoryMutation {
    val revision: String

    data class ReplaceRange(
        override val revision: String,
        val startLine: Int,
        val endLine: Int,
        val content: String,
    ) : AgentMemoryMutation

    data class Append(
        override val revision: String,
        val content: String,
    ) : AgentMemoryMutation

    /** 按 Markdown 标题整体替换一节（标题保留，正文换成 content）；节不存在时追加到文末。 */
    data class UpsertSection(
        override val revision: String,
        val heading: String,
        val content: String,
    ) : AgentMemoryMutation

    data class Clear(
        override val revision: String,
    ) : AgentMemoryMutation
}

internal sealed interface AgentMemoryWriteResult {
    data class Success(val snapshot: AgentMemorySnapshot) : AgentMemoryWriteResult
    data class Conflict(val snapshot: AgentMemorySnapshot) : AgentMemoryWriteResult
}

internal class AgentMemoryException(
    val code: String,
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/** 单一 MEMORY.md 的有界、原子文件存储。 */
internal class AgentMemoryStore(
    rootDir: File,
    /** 元数据时间戳来源；测试注入固定日期，运行时取当天。 */
    private val today: () -> LocalDate = { LocalDate.now() },
    /**
     * 是否对「分割线以下」套记忆硬约束（日期/同名/字数）。现实 MEMORY.md = true；
     * 角色剧情记忆 = false（剧情里常见 `---` 场景分隔，不该被现实记忆的日期约束误拦），
     * 但敏感串扫描始终开启。
     */
    private val enforceSectionRules: Boolean = true,
) {
    private val memoryDir = File(rootDir, DIRECTORY_NAME)
    private val atomicFile = AtomicFile(File(memoryDir, FILE_NAME))
    private val lock = Any()

    fun snapshot(): AgentMemorySnapshot = synchronized(lock) {
        snapshotLocked()
    }

    /** 解析全文为结构化节；旧文件没有元数据注释时得到无元数据条目。 */
    fun entries(): List<AgentMemoryEntry> = synchronized(lock) {
        AgentMemoryEntries.parse(snapshotLocked().content)
    }

    fun read(
        query: String? = null,
        startLine: Int = DEFAULT_START_LINE,
        maxChars: Int = DEFAULT_READ_CHARS,
    ): AgentMemoryReadResult = synchronized(lock) {
        val snapshot = snapshotLocked()
        val boundedChars = maxChars.coerceIn(MIN_READ_CHARS, MAX_READ_CHARS)
        if (query.isNullOrBlank()) {
            page(snapshot, startLine, boundedChars)
        } else {
            search(snapshot, query.trim(), boundedChars)
        }
    }

    fun mutate(mutation: AgentMemoryMutation): AgentMemoryWriteResult = synchronized(lock) {
        val current = snapshotLocked()
        if (mutation.revision != current.revision) {
            return@synchronized AgentMemoryWriteResult.Conflict(current)
        }
        val updated = when (mutation) {
            is AgentMemoryMutation.ReplaceRange -> replaceRange(current, mutation)
            is AgentMemoryMutation.Append ->
                append(current, AgentMemoryEntries.stampSections(mutation.content, today()))

            is AgentMemoryMutation.UpsertSection -> upsertSection(current, mutation)
            is AgentMemoryMutation.Clear -> ""
        }
        writeLocked(updated, current.content)
        AgentMemoryWriteResult.Success(snapshotOf(updated))
    }

    fun replaceAll(content: String): AgentMemorySnapshot = synchronized(lock) {
        writeLocked(content, snapshotLocked().content)
        snapshotOf(content)
    }

    fun replaceAllIfRevision(content: String, revision: String): AgentMemoryWriteResult = synchronized(lock) {
        val current = snapshotLocked()
        if (current.revision != revision) return@synchronized AgentMemoryWriteResult.Conflict(current)
        writeLocked(content, current.content)
        AgentMemoryWriteResult.Success(snapshotOf(content))
    }

    /**
     * 受信任的整篇替换（自动过期整理专用）：带 revision 乐观锁，且只扫敏感串、
     * 不吃「分割线以下」的用户写入约束（整理是系统内务，不该被日期/重名规则拦）。
     * 冲突时放弃本轮（下次启动/开开关再整理），不覆盖期间发生的新写入。
     */
    fun replaceAllTrustedIfRevision(content: String, revision: String): AgentMemoryWriteResult = synchronized(lock) {
        val current = snapshotLocked()
        if (current.revision != revision) return@synchronized AgentMemoryWriteResult.Conflict(current)
        // 只复用敏感串闸门（enforceSectionRules=false），其余 section 约束跳过。
        AgentMemoryValidator.validate(content, current.content, enforceSectionRules = false)?.let { violation ->
            throw AgentMemoryException(code = violation.code, message = violation.message)
        }
        // 绕过 writeLocked 的 section 校验，但仍走同一套文件上限/原子写。
        writeRawLocked(content)
        AgentMemoryWriteResult.Success(snapshotOf(content))
    }

    private fun snapshotLocked(): AgentMemorySnapshot {
        val file = atomicFile.baseFile
        if (!file.exists()) return snapshotOf("")
        val bytes = try {
            atomicFile.openRead().use { it.readBytes() }
        } catch (throwable: IOException) {
            throw AgentMemoryException(
                code = "MEMORY_READ_FAILED",
                message = "无法读取记忆文件",
                cause = throwable,
            )
        }
        if (bytes.size > MAX_FILE_BYTES) {
            throw AgentMemoryException(
                code = "MEMORY_TOO_LARGE",
                message = "记忆文件超过 1 MiB 安全上限",
            )
        }
        val content = bytes.toString(Charsets.UTF_8)
        return snapshotOf(content, bytes)
    }

    private fun writeLocked(content: String, oldContent: String) {
        // 单一咽喉：所有写入路径（手编保存 / 清空 / memory_write / 自动沉淀 / 角色记忆 /
        // replaceAllIfRevision）都会经过这里。分割线以下套硬约束，顶部区豁免；
        // 增量校验：只卡本次新增/改动的节，存量历史节放行（不追溯老数据）。
        AgentMemoryValidator.validate(content, oldContent, enforceSectionRules)?.let { violation ->
            throw AgentMemoryException(code = violation.code, message = violation.message)
        }
        writeRawLocked(content)
    }

    /** 只做文件上限与原子写，不含业务校验；供受信任的内务写入复用。 */
    private fun writeRawLocked(content: String) {
        val bytes = content.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_FILE_BYTES) {
            throw AgentMemoryException(
                code = "MEMORY_TOO_LARGE",
                message = "记忆文件不能超过 1 MiB UTF-8 字节",
            )
        }
        if (!memoryDir.exists() && !memoryDir.mkdirs() && !memoryDir.isDirectory) {
            throw AgentMemoryException(
                code = "MEMORY_WRITE_FAILED",
                message = "无法创建记忆目录",
            )
        }
        val output = try {
            atomicFile.startWrite()
        } catch (throwable: IOException) {
            throw AgentMemoryException(
                code = "MEMORY_WRITE_FAILED",
                message = "无法开始写入记忆文件",
                cause = throwable,
            )
        }
        try {
            output.write(bytes)
            atomicFile.finishWrite(output)
        } catch (throwable: Throwable) {
            atomicFile.failWrite(output)
            throw AgentMemoryException(
                code = "MEMORY_WRITE_FAILED",
                message = "无法保存记忆文件",
                cause = throwable,
            )
        }
    }

    private fun replaceRange(
        snapshot: AgentMemorySnapshot,
        mutation: AgentMemoryMutation.ReplaceRange,
    ): String {
        val lines = snapshot.content.memoryLines().toMutableList()
        if (
            mutation.startLine < 1 ||
            mutation.endLine < mutation.startLine ||
            mutation.endLine > lines.size
        ) {
            throw AgentMemoryException(
                code = "MEMORY_RANGE_INVALID",
                message = "替换行范围无效；请重新读取记忆后再试",
            )
        }
        val replacement = mutation.content.memoryLines()
        lines.subList(mutation.startLine - 1, mutation.endLine).clear()
        if (replacement.isNotEmpty()) {
            lines.addAll(mutation.startLine - 1, replacement)
        }
        return lines.joinToString("\n")
    }

    private fun append(snapshot: AgentMemorySnapshot, content: String): String {
        if (content.isEmpty()) return snapshot.content
        if (snapshot.content.isEmpty()) return content
        return snapshot.content.trimEnd('\n') + "\n" + content
    }

    private fun upsertSection(
        snapshot: AgentMemorySnapshot,
        mutation: AgentMemoryMutation.UpsertSection,
    ): String {
        val heading = mutation.heading.trim()
        if (!HEADING_LINE.matches(heading)) {
            throw AgentMemoryException(
                code = "MEMORY_HEADING_INVALID",
                message = "heading 必须是 1 到 3 级 Markdown 标题，如 '## 当前服务'",
            )
        }
        val level = heading.takeWhile { it == '#' }.length
        val lines = snapshot.content.memoryLines().toMutableList()

        // 只把围栏代码块之外的行当作标题候选，避免正文里的 '# 注释' 被误判为节边界。
        val headingCandidates = headingLineIndexes(lines)
        val starts = headingCandidates.filter { lines[it].trim() == heading }
        if (starts.size > 1) {
            throw AgentMemoryException(
                code = "MEMORY_HEADING_AMBIGUOUS",
                message = "记忆中有多个同名标题 '$heading'，请先调用 memory_get 确认后改用 replace_range",
            )
        }
        val start = starts.firstOrNull() ?: -1
        if (start < 0) {
            val appended = listOf(heading) +
                AgentMemoryEntries.stampSection(heading, mutation.content, today()).memoryLines()
            return append(snapshot, appended.joinToString("\n"))
        }
        // 节的结束位置：下一个同级或更高级标题。
        val end = headingCandidates.firstOrNull { index ->
            if (index <= start) return@firstOrNull false
            val line = lines[index]
            val lineLevel = line.takeWhile { it == '#' }.length
            lineLevel in 1..level && line.getOrNull(lineLevel) == ' '
        } ?: lines.size
        // 替换同一标题时继承已有元数据：id 与 created 属于这一节的历史，不能被覆盖。
        val previous = AgentMemoryEntries
            .parse(lines.subList(start, end).joinToString("\n"))
            .firstOrNull()
            ?.metadata
        val section = listOf(heading) +
            AgentMemoryEntries
                .stampSection(heading, mutation.content, today(), previous)
                .memoryLines()
        lines.subList(start, end).clear()
        lines.addAll(start, section)
        return lines.joinToString("\n")
    }

    /** 全文扫描一次，返回围栏外 Markdown 标题行的下标。 */
    private fun headingLineIndexes(lines: List<String>): List<Int> {
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
            if (fence == null && line.length - line.trimStart().length < 4 && HEADING_LINE.matches(line.trim())) {
                result += index
            }
        }
        return result
    }

    private val HEADING_LINE = Regex("^#{1,3}\\s+.+$")

    private fun page(
        snapshot: AgentMemorySnapshot,
        requestedStartLine: Int,
        maxChars: Int,
    ): AgentMemoryReadResult {
        val lines = snapshot.content.memoryLines()
        if (lines.isEmpty()) {
            return AgentMemoryReadResult(snapshot, "", null, null, false, 0)
        }
        val startIndex = (requestedStartLine - 1).coerceIn(0, lines.size)
        if (startIndex >= lines.size) {
            return AgentMemoryReadResult(snapshot, "", null, null, false, 0)
        }
        val output = StringBuilder()
        var endIndex = startIndex
        while (endIndex < lines.size) {
            val rendered = "${endIndex + 1}: ${lines[endIndex]}"
            val separatorLength = if (output.isEmpty()) 0 else 1
            if (output.isNotEmpty() && output.length + separatorLength + rendered.length > maxChars) break
            if (output.isNotEmpty()) output.append('\n')
            output.append(rendered.take(maxChars - output.length))
            endIndex++
            if (output.length >= maxChars) break
        }
        return AgentMemoryReadResult(
            snapshot = snapshot,
            content = output.toString(),
            startLine = startIndex + 1,
            endLine = endIndex,
            hasMore = endIndex < lines.size,
            matchedLines = endIndex - startIndex,
        )
    }

    private fun search(
        snapshot: AgentMemorySnapshot,
        query: String,
        maxChars: Int,
    ): AgentMemoryReadResult {
        val lines = snapshot.content.memoryLines()
        val matched = lines.indices.filter { index ->
            lines[index].contains(query, ignoreCase = true)
        }
        val included = linkedSetOf<Int>()
        matched.forEach { index ->
            for (candidate in (index - SEARCH_CONTEXT_LINES)..(index + SEARCH_CONTEXT_LINES)) {
                if (candidate in lines.indices) included += candidate
            }
        }
        val output = StringBuilder()
        var lastIncluded: Int? = null
        var renderedCount = 0
        for (index in included) {
            val rendered = "${index + 1}: ${lines[index]}"
            val gap = when {
                output.isEmpty() -> ""
                lastIncluded != null && index > lastIncluded + 1 -> "\n…\n"
                else -> "\n"
            }
            if (output.isNotEmpty() && output.length + gap.length + rendered.length > maxChars) break
            output.append(gap).append(rendered.take(maxChars - output.length - gap.length))
            lastIncluded = index
            renderedCount++
            if (output.length >= maxChars) break
        }
        return AgentMemoryReadResult(
            snapshot = snapshot,
            content = output.toString(),
            startLine = included.firstOrNull()?.plus(1),
            endLine = lastIncluded?.plus(1),
            hasMore = renderedCount < included.size,
            matchedLines = matched.size,
        )
    }

    private fun snapshotOf(
        content: String,
        bytes: ByteArray = content.toByteArray(Charsets.UTF_8),
    ): AgentMemorySnapshot = AgentMemorySnapshot(
        content = content,
        revision = sha256(bytes),
        byteSize = bytes.size,
        lineCount = content.memoryLines().size,
    )

    private fun String.memoryLines(): List<String> =
        if (isEmpty()) emptyList() else split('\n')

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString(separator = "") { byte ->
                (byte.toInt() and 0xff).toString(16).padStart(2, '0')
            }

    companion object {
        const val MAX_FILE_BYTES = 1024 * 1024
        const val DEFAULT_READ_CHARS = 12_000
        const val MAX_READ_CHARS = 32_000
        const val MIN_READ_CHARS = 1
        const val MAX_WRITE_CONTENT_CHARS = 3_500
        private const val DIRECTORY_NAME = "memory"
        private const val FILE_NAME = "MEMORY.md"
        private const val DEFAULT_START_LINE = 1
        private const val SEARCH_CONTEXT_LINES = 1
    }
}

internal object AgentMemoryRepository {
    @Volatile
    private lateinit var store: AgentMemoryStore

    fun init(context: Context) {
        if (!::store.isInitialized) {
            store = AgentMemoryStore(context.applicationContext.filesDir)
        }
        // 四期向量缓存与记忆文件同目录；未启用语义检索时不会产生任何文件。
        AgentMemorySemanticIndex.init(context.applicationContext.filesDir)
    }

    fun snapshot(): AgentMemorySnapshot {
        ensureInitialized()
        return store.snapshot()
    }

    fun read(
        query: String? = null,
        startLine: Int = 1,
        maxChars: Int = AgentMemoryStore.DEFAULT_READ_CHARS,
    ): AgentMemoryReadResult {
        ensureInitialized()
        return store.read(query, startLine, maxChars)
    }

    fun mutate(mutation: AgentMemoryMutation): AgentMemoryWriteResult {
        ensureInitialized()
        return store.mutate(mutation)
    }

    fun replaceAll(content: String): AgentMemorySnapshot {
        ensureInitialized()
        return store.replaceAll(content)
    }

    /** 带 revision 乐观锁的整篇替换（UI 编辑保存用，避免覆盖进行中 run 的写入）。 */
    fun replaceAllIfRevision(content: String, revision: String): AgentMemoryWriteResult {
        ensureInitialized()
        return store.replaceAllIfRevision(content, revision)
    }

    /**
     * 启动时执行一次自动过期整理：开关打开才动；把分割线以下超阈值天数的整节移入 # 归档。
     * 返回是否发生了改动（供调用方决定是否刷新 UI / 记日志）。纯本地、不发模型回合。
     */
    fun archiveExpiredIfEnabled(
        enabled: Boolean,
        now: java.time.LocalDate = java.time.LocalDate.now(),
    ): Boolean {
        if (!enabled) return false
        ensureInitialized()
        // 记忆总开关关着时不整理、不写盘。
        if (runCatching { kotlinx.coroutines.runBlocking { isEnabled() } }.getOrDefault(true).not()) return false
        val current = store.snapshot()
        val updated = io.github.mangi.eta.agent.memory.AgentMemoryExpiry
            .archiveExpired(current.content, now)
        if (updated == current.content) return false
        // 带 revision 乐观锁的受信任写入：冲突就放弃本轮（下次再整理），不覆盖期间的新写入。
        return when (store.replaceAllTrustedIfRevision(updated, current.revision)) {
            is AgentMemoryWriteResult.Success -> true
            is AgentMemoryWriteResult.Conflict -> false
        }
    }

    /** 结构化节视图，供相关性注入与语义检索共用。 */
    fun entries(): List<AgentMemoryEntry> {
        ensureInitialized()
        return store.entries()
    }

    fun enabledFlow(): Flow<Boolean> = SettingsDataStore.memoryEnabledFlow()

    suspend fun isEnabled(): Boolean = SettingsDataStore.settings().memoryEnabled

    suspend fun setEnabled(enabled: Boolean) = SettingsDataStore.setMemoryEnabled(enabled)

    private fun ensureInitialized() {
        check(::store.isInitialized) {
            "AgentMemoryRepository.init(context) must be called in Application.onCreate()"
        }
    }
}
