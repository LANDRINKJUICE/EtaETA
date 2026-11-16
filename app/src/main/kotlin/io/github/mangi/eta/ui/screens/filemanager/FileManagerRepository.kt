package io.github.mangi.eta.ui.screens.filemanager

import io.github.mangi.eta.agent.device.BoundedRootCommandExecutor
import io.github.mangi.eta.agent.device.RootAccess
import io.github.mangi.eta.core.AndroidAgentLogger
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 双窗文件管理器的存储层：java.io 直读优先，受限路径（如 /data 目录）回落 Root Shell。
 *
 * 纯函数（normalizePath / shellQuote / parseStatOutput / 排序比较）与真实 IO 分离，
 * 便于 JVM 单测；Root Shell 命令全部由 Eta 内部构造，外部输入只允许出现在
 * 经过 [normalizePath] 校验与 [shellQuote] 引用的路径参数里，不拼接任意脚本。
 */
internal object FileManagerRepository {

    const val DEFAULT_LEFT_DIR = "/storage/emulated/0/"
    const val DEFAULT_RIGHT_DIR = "/data/local/tmp/eta/"

    /** 文本编辑单次读取上限；超出按「仅查看」处理，杜绝把截断内容写回毁掉原文件。 */
    const val MAX_TEXT_READ_BYTES = 512L * 1024L

    /**
     * Root 流式读取单文件上限（压缩/预览受限文件时逐文件读进内存的护栏）。
     * BoundedRootCommandExecutor 输出硬上限 2MB；二进制走 base64（膨胀 4/3），
     * 原始字节上限取 1.5MB，超出如实报错，不假装读完。
     */
    const val MAX_ROOT_FILE_BYTES = 1_500_000L

    /** 递归搜索结果上限。 */
    const val MAX_SEARCH_RESULTS = 500

    /** Shell 输入按 96KB 分块，低于 BoundedRootCommandExecutor 的 128KB 单次上限。 */
    private const val ROOT_INPUT_CHUNK_BYTES = 96 * 1024

    private val executor: BoundedRootCommandExecutor by lazy {
        BoundedRootCommandExecutor(AndroidAgentLogger)
    }

    // ------------------------------------------------------------------
    // 数据模型
    // ------------------------------------------------------------------

    data class FsEntry(
        val name: String,
        val path: String,
        val isDir: Boolean,
        val sizeBytes: Long,
        val mtimeEpochSeconds: Long,
    )

    data class FileDetails(
        val path: String,
        val isDir: Boolean,
        val sizeBytes: Long,
        val mtimeEpochSeconds: Long,
        /** 形如 rwxr-xr-x；拿不到完整九位时为 java 侧近似值。 */
        val modeText: String?,
        val owner: String?,
        val group: String?,
        /** 目录内的直接子项数；文件为 null。 */
        val childCount: Int?,
    )

    sealed interface ListResult {
        data class Success(val entries: List<FsEntry>) : ListResult
        data object NotDirectory : ListResult
        data object Unreadable : ListResult
        data class Failed(val code: String) : ListResult
    }

    sealed interface OpResult {
        data object Success : OpResult
        data class Failed(val code: String) : OpResult
    }

    sealed interface TextReadResult {
        data class Text(val content: String, val truncated: Boolean) : TextReadResult
        data object Binary : TextReadResult
        data class Failed(val code: String) : TextReadResult
    }

    enum class SortMode { NAME, SIZE, DATE, TYPE }

    // ------------------------------------------------------------------
    // 纯函数（JVM 单测覆盖）
    // ------------------------------------------------------------------

    /**
     * 词法归一化绝对路径：剥空白、合并连续斜杠、解析 `.` 与 `..`；
     * 空串、非 `/` 开头、含换行、`..` 弹出根返回 null。
     */
    fun normalizePath(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || !trimmed.startsWith("/") || '\n' in trimmed || '\r' in trimmed) return null
        val segments = mutableListOf<String>()
        trimmed.split("/").forEach { segment ->
            when (segment) {
                "", "." -> Unit
                ".." -> if (segments.isEmpty()) return null else segments.removeAt(segments.lastIndex)
                else -> segments += segment
            }
        }
        return "/" + segments.joinToString("/")
    }

    /** 单引号安全引用；内容里的单引号按 POSIX 规则断开重连。 */
    fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    /**
     * 解析 `stat -c '%F|%s|%Y|%n'` 输出：按前 3 个分隔符切分，其余全部归入文件名，
     * 容忍文件名含 `|`；畸形行与 `.`/`..` 残留跳过。
     */
    fun parseStatOutput(output: String, parentDir: String): List<FsEntry> {
        val entries = mutableListOf<FsEntry>()
        output.lineSequence().forEach { line ->
            if (line.isBlank()) return@forEach
            val first = line.indexOf('|')
            val second = if (first < 0) -1 else line.indexOf('|', first + 1)
            val third = if (second < 0) -1 else line.indexOf('|', second + 1)
            if (first < 0 || second < 0 || third < 0) return@forEach
            val type = line.substring(0, first)
            val size = line.substring(first + 1, second).toLongOrNull() ?: return@forEach
            val mtime = line.substring(second + 1, third).toLongOrNull() ?: return@forEach
            val name = line.substring(third + 1)
            if (name.isEmpty() || name == "." || name == "..") return@forEach
            entries += FsEntry(
                name = name,
                path = parentDir.trimEnd('/') + "/" + name,
                isDir = type.startsWith("directory"),
                sizeBytes = size,
                mtimeEpochSeconds = mtime,
            )
        }
        return entries
    }

    /** 八进制 mode（如 0644）→ 9 位 rwx 文本。 */
    fun modeBitsToText(modeBits: Int): String {
        val sb = StringBuilder(9)
        for (shift in intArrayOf(6, 3, 0)) {
            val bits = (modeBits shr shift) and 7
            sb.append(if (bits and 4 != 0) 'r' else '-')
            sb.append(if (bits and 2 != 0) 'w' else '-')
            sb.append(if (bits and 1 != 0) 'x' else '-')
        }
        return sb.toString()
    }

    /** 9 位 rwx 文本 → 八进制 mode 数值；格式非法返回 null。 */
    fun textToModeBits(text: String): Int? {
        if (text.length != 9) return null
        var mode = 0
        for (group in 0 until 3) {
            var bits = 0
            for (i in 0 until 3) {
                val c = text[group * 3 + i]
                val bitValue = when (i) {
                    0 -> 4
                    1 -> 2
                    else -> 1
                }
                val expected = when (i) {
                    0 -> 'r'
                    1 -> 'w'
                    else -> 'x'
                }
                when (c) {
                    expected -> bits = bits or bitValue
                    '-' -> Unit
                    else -> return null
                }
            }
            mode = (mode shl 3) or bits
        }
        return mode
    }

    /** 文件排序比较：文件夹优先可配置，同组按 [mode] 排序，再按名称兜底保证顺序稳定。 */
    fun compareEntries(a: FsEntry, b: FsEntry, mode: SortMode, ascending: Boolean, foldersFirst: Boolean): Int {
        if (foldersFirst && a.isDir != b.isDir) return if (a.isDir) -1 else 1
        val primary = when (mode) {
            SortMode.NAME -> a.name.lowercase().compareTo(b.name.lowercase())
            SortMode.SIZE -> a.sizeBytes.compareTo(b.sizeBytes)
            SortMode.DATE -> a.mtimeEpochSeconds.compareTo(b.mtimeEpochSeconds)
            SortMode.TYPE -> extensionOf(a.name).lowercase().compareTo(extensionOf(b.name).lowercase())
                .takeIf { it != 0 } ?: a.name.lowercase().compareTo(b.name.lowercase())
        }
        val ordered = if (ascending) primary else -primary
        return if (ordered != 0) ordered else a.name.compareTo(b.name)
    }

    fun extensionOf(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot <= 0 || dot == name.length - 1) "" else name.substring(dot + 1)
    }

    // ------------------------------------------------------------------
    // 访问决策与 Root 执行助手
    // ------------------------------------------------------------------

    private fun rootGranted(): Boolean = RootAccess.isGranted

    /**
     * Root 输出上限：与 BoundedRootCommandExecutor 内部 private 闸一致（2MB）。
     * 传入更大值会被执行器内部 clamp，但这里显式声明以约束 base64 读取的上限计算。
     * 若执行器闸值变更，此处须同步订正。
     */
    private const val ROOT_OUTPUT_CAP_BYTES = 2 * 1024 * 1024

    private fun rootRun(
        script: String,
        timeoutMillis: Long = 30_000L,
        maxOutputBytes: Int = 256 * 1024,
        input: ByteArray? = null,
    ): BoundedRootCommandExecutor.Result = executor.execute(
        command = script,
        timeoutMillis = timeoutMillis,
        maxOutputBytes = maxOutputBytes.coerceAtMost(ROOT_OUTPUT_CAP_BYTES),
        input = input,
    )

    private fun BoundedRootCommandExecutor.Result.okOr(code: String): FileManagerRepository.OpResult =
        if (ok) {
            OpResult.Success
        } else {
            OpResult.Failed("$code(exit=$exitCode${if (stderr.isBlank()) "" else " ${stderr.take(120)}"})")
        }

    // ------------------------------------------------------------------
    // 列目录
    // ------------------------------------------------------------------

    fun list(dir: String): ListResult {
        val normalized = normalizePath(dir) ?: return ListResult.Failed("INVALID_PATH")
        val file = File(normalized)
        if (!file.exists() || !file.isDirectory) return ListResult.NotDirectory
        val javaEntries = runCatching { file.listFiles() }.getOrNull()
        if (javaEntries != null) {
            return ListResult.Success(
                javaEntries.map { child ->
                    FsEntry(
                        name = child.name,
                        path = child.absolutePath,
                        isDir = child.isDirectory,
                        sizeBytes = if (child.isDirectory) 0L else child.length(),
                        mtimeEpochSeconds = child.lastModified() / 1000L,
                    )
                },
            )
        }
        // java 直读失败（受限目录）：有 Root 走 stat 列举；没有 Root 如实上报。
        if (!rootGranted()) return ListResult.Unreadable
        val quoted = shellQuote(normalized)
        val result = rootRun(
            script = """
                if [ ! -d $quoted ]; then exit 41; fi
                if [ ! -r $quoted ] || [ ! -x $quoted ]; then exit 42; fi
                cd $quoted || exit 42
                stat -c '%F|%s|%Y|%n' -- .[!.]* * 2>/dev/null
                exit 0
            """.trimIndent(),
            maxOutputBytes = 8 * 1024 * 1024,
        )
        return when {
            result.exitCode == 41 -> ListResult.NotDirectory
            result.exitCode == 42 -> ListResult.Unreadable
            result.exitCode != 0 -> ListResult.Failed("ROOT_LIST(exit=${result.exitCode})")
            else -> ListResult.Success(parseStatOutput(result.stdout, normalized))
        }
    }

    fun fileDetails(path: String): FileDetails? {
        val normalized = normalizePath(path) ?: return null
        val file = File(normalized)
        if (!file.exists()) return null
        if (rootGranted()) {
            val quoted = shellQuote(normalized)
            val result = rootRun(script = "stat -c '%F|%s|%Y|%a|%U|%G' $quoted")
            val line = result.stdout.lineSequence().firstOrNull { it.isNotBlank() }
            if (result.ok && line != null) {
                val parts = line.split('|')
                if (parts.size >= 6) {
                    val isDir = parts[0].startsWith("directory")
                    val childCount = if (isDir) runCatching { file.list()?.size }.getOrNull() else null
                    return FileDetails(
                        path = normalized,
                        isDir = isDir,
                        sizeBytes = parts[1].toLongOrNull() ?: 0L,
                        mtimeEpochSeconds = parts[2].toLongOrNull() ?: 0L,
                        modeText = parts[3].toIntOrNull(8)?.let { modeBitsToText(it) },
                        owner = parts[4],
                        group = parts[5],
                        childCount = childCount,
                    )
                }
            }
        }
        // 无 Root 时给出 java 侧能拿到的信息，owner/group 如实留空。
        val childCount = if (file.isDirectory) runCatching { file.list()?.size }.getOrNull() else null
        val mode = buildString {
            append(if (file.canRead()) 'r' else '-')
            append(if (file.canWrite()) 'w' else '-')
            append(if (file.canExecute()) 'x' else '-')
            append("------")
        }
        return FileDetails(
            path = normalized,
            isDir = file.isDirectory,
            sizeBytes = if (file.isDirectory) 0L else file.length(),
            mtimeEpochSeconds = file.lastModified() / 1000L,
            modeText = mode,
            owner = null,
            group = null,
            childCount = childCount,
        )
    }

    // ------------------------------------------------------------------
    // 新建 / 重命名 / 删除
    // ------------------------------------------------------------------

    fun makeDir(path: String): OpResult {
        val normalized = normalizePath(path) ?: return OpResult.Failed("INVALID_PATH")
        val file = File(normalized)
        if (file.exists()) return OpResult.Failed("ALREADY_EXISTS")
        if (file.parentFile?.canWrite() == true) {
            return if (file.mkdirs()) OpResult.Success else OpResult.Failed("IO_ERROR")
        }
        if (!rootGranted()) return OpResult.Failed("ROOT_REQUIRED")
        return rootRun("mkdir -p ${shellQuote(normalized)}").okOr("ROOT_MKDIR")
    }

    fun createFile(path: String): OpResult {
        val normalized = normalizePath(path) ?: return OpResult.Failed("INVALID_PATH")
        val file = File(normalized)
        if (file.exists()) return OpResult.Failed("ALREADY_EXISTS")
        if (file.parentFile?.canWrite() == true) {
            return try {
                if (file.createNewFile()) OpResult.Success else OpResult.Failed("IO_ERROR")
            } catch (e: java.io.IOException) {
                OpResult.Failed("IO_ERROR(${e.message?.take(120)})")
            }
        }
        if (!rootGranted()) return OpResult.Failed("ROOT_REQUIRED")
        return rootRun(": > ${shellQuote(normalized)}").okOr("ROOT_CREATE")
    }

    fun rename(oldPath: String, newName: String): OpResult {
        val normalized = normalizePath(oldPath) ?: return OpResult.Failed("INVALID_PATH")
        val cleanName = newName.trim()
        if (cleanName.isEmpty() || '/' in cleanName || cleanName == "." || cleanName == "..") {
            return OpResult.Failed("INVALID_NAME")
        }
        val parent = File(normalized).parentFile ?: return OpResult.Failed("INVALID_PATH")
        val target = normalizePath(parent.absolutePath + "/" + cleanName)
            ?: return OpResult.Failed("INVALID_PATH")
        val source = File(normalized)
        if (parent.canWrite()) {
            return if (source.renameTo(File(target))) OpResult.Success else OpResult.Failed("IO_ERROR")
        }
        if (!rootGranted()) return OpResult.Failed("ROOT_REQUIRED")
        return rootRun("mv -f ${shellQuote(normalized)} ${shellQuote(target)}").okOr("ROOT_RENAME")
    }

    fun delete(paths: List<String>, onProgress: (Int, Int, String) -> Unit = { _, _, _ -> }): OpResult {
        if (paths.isEmpty()) return OpResult.Success
        val normalized = paths.map { normalizePath(it) ?: return OpResult.Failed("INVALID_PATH") }
        var done = 0
        // java 可删的先删，受限的留给 Root 逐条处理，进度按项推进。
        val rootTargets = mutableListOf<String>()
        for (path in normalized) {
            val file = File(path)
            if (file.canWrite() || file.isDirectory && file.exists() && file.parentFile?.canWrite() == true) {
                if (deleteRecursivelyJava(file)) {
                    done++
                    onProgress(done, normalized.size, path)
                } else {
                    return OpResult.Failed("IO_ERROR(${file.name})")
                }
            } else {
                rootTargets += path
            }
        }
        if (rootTargets.isEmpty()) return OpResult.Success
        if (!rootGranted()) return OpResult.Failed("ROOT_REQUIRED")
        for (path in rootTargets) {
            val result = rootRun("rm -rf ${shellQuote(path)}")
            if (!result.ok) return result.okOr("ROOT_DELETE")
            done++
            onProgress(done, normalized.size, path)
        }
        return OpResult.Success
    }

    private fun deleteRecursivelyJava(file: File): Boolean {
        if (!file.exists()) return true
        val children = file.listFiles()
        if (children != null) {
            for (child in children) {
                if (!deleteRecursivelyJava(child)) return false
            }
        }
        return file.delete()
    }

    // ------------------------------------------------------------------
    // 复制 / 移动（目标 = 对侧窗格当前目录，MT 语义）
    // ------------------------------------------------------------------

    fun copyOrMove(
        sources: List<String>,
        targetDir: String,
        move: Boolean,
        onProgress: (Int, Int, String) -> Unit = { _, _, _ -> },
    ): OpResult {
        if (sources.isEmpty()) return OpResult.Success
        val normalizedSources = sources.map { normalizePath(it) ?: return OpResult.Failed("INVALID_PATH") }
        val normalizedTarget = normalizePath(targetDir) ?: return OpResult.Failed("INVALID_PATH")
        val target = File(normalizedTarget)
        if (!target.isDirectory) return OpResult.Failed("NOT_DIRECTORY")
        val targetWritable = target.canWrite()
        val allJavaReadable = normalizedSources.all { File(it).canRead() }
        if (targetWritable && allJavaReadable) {
            return copyOrMoveJava(normalizedSources, target, move, onProgress)
        }
        if (!rootGranted()) return OpResult.Failed("ROOT_REQUIRED")
        // 混合场景：java 可处理的逐项处理，剩余统一 Root 一条命令（进度按项推进）。
        val rootSources = mutableListOf<String>()
        var done = 0
        val total = normalizedSources.size
        for (path in normalizedSources) {
            val source = File(path)
            if (targetWritable && source.canRead()) {
                val result = copyOrMoveJava(listOf(path), target, move) { d, t, n -> onProgress(done + d, total, n) }
                if (result is OpResult.Failed) return result
            } else {
                rootSources += path
            }
            done++
            onProgress(done, total, path)
        }
        if (rootSources.isEmpty()) return OpResult.Success
        val script = buildString {
            append(if (move) "mv -f " else "cp -a -f ")
            rootSources.forEach { append(shellQuote(it)); append(' ') }
            append(shellQuote(normalizedTarget))
        }
        return rootRun(script, timeoutMillis = 120_000L).okOr(if (move) "ROOT_MOVE" else "ROOT_COPY")
    }

    private fun copyOrMoveJava(
        sources: List<String>,
        targetDir: File,
        move: Boolean,
        onProgress: (Int, Int, String) -> Unit,
    ): OpResult {
        // 重名目标按覆盖处理（与 MT 行为一致）；同名目录互拷是确定性冲突，直接报错不静默合并。
        val plan = mutableListOf<Pair<File, File>>()
        for (sourcePath in sources) {
            val source = File(sourcePath)
            val dest = File(targetDir, source.name)
            if (source.isDirectory && dest.isDirectory) return OpResult.Failed("DIR_EXISTS(${source.name})")
            if (move) {
                // 同分区改名是零拷贝快路径。
                if (source.renameTo(dest)) {
                    onProgress(1, 1, source.name)
                    continue
                }
            }
            collectCopyPlan(source, dest, plan)
        }
        if (plan.isEmpty()) return OpResult.Success
        var done = 0
        for ((source, dest) in plan) {
            val result = copyOneJava(source, dest)
            if (result is OpResult.Failed) return result
            if (move) {
                if (source.isDirectory) {
                    // 目录内容拷完后整体删除；子项已随 plan 逐个处理过，不重复移动。
                    deleteRecursivelyJava(source)
                } else if (!source.delete()) {
                    return OpResult.Failed("IO_ERROR(delete ${source.name})")
                }
            }
            done++
            onProgress(done, plan.size, source.name)
        }
        return OpResult.Success
    }

    private fun collectCopyPlan(source: File, dest: File, plan: MutableList<Pair<File, File>>) {
        if (source.isDirectory) {
            dest.mkdirs()
            source.listFiles()?.forEach { child ->
                collectCopyPlan(child, File(dest, child.name), plan)
            }
        } else {
            plan += source to dest
        }
    }

    private fun copyOneJava(source: File, dest: File): OpResult {
        return try {
            if (source.isDirectory) {
                dest.mkdirs()
                OpResult.Success
            } else {
                dest.parentFile?.mkdirs()
                FileInputStream(source).use { input ->
                    FileOutputStream(dest).use { output ->
                        input.copyTo(output, DEFAULT_BUFFER_SIZE)
                    }
                }
                OpResult.Success
            }
        } catch (e: java.io.IOException) {
            OpResult.Failed("IO_ERROR(${e.message?.take(120)})")
        } catch (e: SecurityException) {
            OpResult.Failed("IO_ERROR(denied)")
        }
    }

    // ------------------------------------------------------------------
    // 压缩 / 解压（受限路径经 staging 中转，内存有界）
    // ------------------------------------------------------------------

    fun zip(
        sources: List<String>,
        targetZipPath: String,
        stagingDir: File?,
        onProgress: (Int, Int, String) -> Unit = { _, _, _ -> },
    ): OpResult {
        if (sources.isEmpty()) return OpResult.Success
        val normalized = sources.map { normalizePath(it) ?: return OpResult.Failed("INVALID_PATH") }
        val normalizedTarget = normalizePath(targetZipPath) ?: return OpResult.Failed("INVALID_PATH")
        if (!normalizedTarget.endsWith(".zip")) return OpResult.Failed("INVALID_NAME")
        val targetFile = File(normalizedTarget)
        val targetWritable = targetFile.parentFile?.canWrite() == true

        val staging = if (!targetWritable && stagingDir != null) {
            File(stagingDir, "fm-zip-${System.currentTimeMillis()}.zip")
        } else {
            targetFile
        }
        val result = writeZip(normalized, staging, onProgress)
        if (result != OpResult.Success) {
            staging.delete()
            return result
        }
        if (staging != targetFile) {
            if (!rootGranted()) {
                staging.delete()
                return OpResult.Failed("ROOT_REQUIRED")
            }
            val moveResult = rootRun("mv -f ${shellQuote(staging.absolutePath)} ${shellQuote(normalizedTarget)}")
            staging.delete()
            return moveResult.okOr("ROOT_ZIP_PLACE")
        }
        return OpResult.Success
    }

    private fun writeZip(sources: List<String>, targetFile: File, onProgress: (Int, Int, String) -> Unit): OpResult {
        val plan = mutableListOf<Pair<File, String>>() // (文件或目录, zip 内相对名)
        for (path in sources) {
            val file = File(path)
            if (file.isDirectory) {
                collectZipPlan(file, file.name, plan)
            } else {
                plan += file to file.name
            }
        }
        var done = 0
        return try {
            FileOutputStream(targetFile).use { fileOut ->
                ZipOutputStream(fileOut).use { zipOut ->
                    for ((file, entryName) in plan) {
                        if (file.isDirectory) {
                            zipOut.putNextEntry(ZipEntry(entryName.trimEnd('/') + "/"))
                            zipOut.closeEntry()
                        } else {
                            zipOut.putNextEntry(ZipEntry(entryName))
                            if (file.canRead()) {
                                FileInputStream(file).use { it.copyTo(zipOut, DEFAULT_BUFFER_SIZE) }
                            } else {
                                if (!rootGranted()) return OpResult.Failed("ROOT_REQUIRED")
                                val read = readBytesViaRoot(file.absolutePath, MAX_ROOT_FILE_BYTES)
                                    ?: return OpResult.Failed("ROOT_READ(${file.name})")
                                zipOut.write(read.first)
                            }
                            zipOut.closeEntry()
                            done++
                            onProgress(done, plan.size, file.name)
                        }
                    }
                }
            }
            OpResult.Success
        } catch (e: java.io.IOException) {
            OpResult.Failed("IO_ERROR(${e.message?.take(120)})")
        }
    }

    private fun collectZipPlan(dir: File, relName: String, plan: MutableList<Pair<File, String>>) {
        val children = dir.listFiles()
        if (children == null || children.isEmpty()) {
            plan += dir to relName + "/" // 空目录或无权限目录按空条目占位
            return
        }
        for (child in children) {
            val childRel = relName + "/" + child.name
            if (child.isDirectory) collectZipPlan(child, childRel, plan) else plan += child to childRel
        }
    }

    fun unzip(zipPath: String, targetDir: String, stagingDir: File?): OpResult {
        val normalizedZip = normalizePath(zipPath) ?: return OpResult.Failed("INVALID_PATH")
        val normalizedTarget = normalizePath(targetDir) ?: return OpResult.Failed("INVALID_PATH")
        val zipFile = File(normalizedZip)
        if (!zipFile.isFile) return OpResult.Failed("NOT_FILE")
        val localZip: File
        if (zipFile.canRead()) {
            localZip = zipFile
        } else {
            if (!rootGranted()) return OpResult.Failed("ROOT_REQUIRED")
            val staging = stagingDir ?: return OpResult.Failed("UNSUPPORTED")
            localZip = File(staging, "fm-unzip-${System.currentTimeMillis()}.zip")
            val result = rootRun("cp -f ${shellQuote(normalizedZip)} ${shellQuote(localZip.absolutePath)}")
            if (!result.ok) {
                localZip.delete()
                return result.okOr("ROOT_ZIP_FETCH")
            }
        }
        val targetWritable = File(normalizedTarget).canWrite()
        val result = extractZip(localZip, File(normalizedTarget), targetWritable, stagingDir)
        if (localZip != zipFile) localZip.delete()
        return result
    }

    private fun extractZip(
        zipFile: File,
        targetDir: File,
        targetWritable: Boolean,
        stagingDir: File?,
    ): OpResult {
        return try {
            ZipInputStream(zipFile.inputStream().buffered()).use { zipIn ->
                var entry: ZipEntry? = zipIn.nextEntry
                while (entry != null) {
                    // 防路径穿越：zip 内条目名不得带 .. 段或绝对路径。
                    val safeName = sanitizeZipEntryName(entry.name)
                        ?: return OpResult.Failed("UNSAFE_ENTRY(${entry.name.take(60)})")
                    val dest = File(targetDir, safeName)
                    if (entry.isDirectory) {
                        if (targetWritable) dest.mkdirs() else rootRun("mkdir -p ${shellQuote(dest.absolutePath)}")
                    } else {
                        dest.parentFile?.let { parent ->
                            if (targetWritable) parent.mkdirs() else rootRun("mkdir -p ${shellQuote(parent.absolutePath)}")
                        }
                        if (targetWritable) {
                            FileOutputStream(dest).use { zipIn.copyTo(it, DEFAULT_BUFFER_SIZE) }
                        } else {
                            if (!rootGranted()) return OpResult.Failed("ROOT_REQUIRED")
                            if (stagingDir == null) return OpResult.Failed("UNSUPPORTED")
                            // 受限目标：先落到 staging 再逐块灌入 Root cat（分块追加，绕开单次 128KB 上限）。
                            val staged = File(
                                stagingDir,
                                "fm-entry-" + System.currentTimeMillis() + "-" + entry.name.takeLast(24).replace('/', '_'),
                            )
                            FileOutputStream(staged).use { zipIn.copyTo(it, DEFAULT_BUFFER_SIZE) }
                            val bytes = staged.readBytes()
                            staged.delete()
                            val writeResult = writeViaRootChunked(dest.absolutePath, bytes)
                            if (writeResult is OpResult.Failed) return writeResult
                        }
                    }
                    zipIn.closeEntry()
                    entry = zipIn.nextEntry
                }
            }
            OpResult.Success
        } catch (e: java.io.IOException) {
            OpResult.Failed("IO_ERROR(${e.message?.take(120)})")
        }
    }

    /** zip 条目名清洗：剥绝对前缀与 `.` 段，`..` 段一律拒绝，剥空返回 null。 */
    internal fun sanitizeZipEntryName(name: String): String? {
        val segments = mutableListOf<String>()
        name.split('/').forEach { segment ->
            when (segment) {
                "", "." -> Unit
                ".." -> return null
                else -> segments += segment
            }
        }
        return if (segments.isEmpty()) null else segments.joinToString("/")
    }

    // ------------------------------------------------------------------
    // 文本读写（编辑器）
    // ------------------------------------------------------------------

    fun readText(path: String): TextReadResult {
        val normalized = normalizePath(path) ?: return TextReadResult.Failed("INVALID_PATH")
        val file = File(normalized)
        if (!file.isFile) return TextReadResult.Failed("NOT_FILE")
        if (file.canRead()) {
            return try {
                val bytes = file.readBytes()
                val truncated = bytes.size > MAX_TEXT_READ_BYTES
                val payload = if (truncated) bytes.copyOf(MAX_TEXT_READ_BYTES.toInt()) else bytes
                if (payload.contains(0.toByte())) TextReadResult.Binary
                else TextReadResult.Text(payload.decodeToString(), truncated)
            } catch (e: java.io.IOException) {
                TextReadResult.Failed("IO_ERROR(${e.message?.take(120)})")
            }
        }
        if (!rootGranted()) return TextReadResult.Failed("ROOT_REQUIRED")
        val (bytes, truncated) = readBytesViaRoot(normalized, MAX_TEXT_READ_BYTES)
            ?: return TextReadResult.Failed("ROOT_READ")
        val payload = bytes
        if (payload.contains(0.toByte())) return TextReadResult.Binary
        return TextReadResult.Text(payload.decodeToString(), truncated)
    }

    fun writeText(path: String, content: String, stagingDir: File?): OpResult {
        val normalized = normalizePath(path) ?: return OpResult.Failed("INVALID_PATH")
        val file = File(normalized)
        val bytes = content.toByteArray(Charsets.UTF_8)
        if (file.canWrite() || !file.exists() && file.parentFile?.canWrite() == true) {
            return try {
                FileOutputStream(file).use { it.write(bytes) }
                OpResult.Success
            } catch (e: java.io.IOException) {
                OpResult.Failed("IO_ERROR(${e.message?.take(120)})")
            }
        }
        if (!rootGranted()) return OpResult.Failed("ROOT_REQUIRED")
        return writeViaRootChunked(normalized, bytes)
    }

    /** [readBytesViaRoot] 的公开入口：UI 侧图片预览等需要原始字节的场景。 */
    fun readFileBytes(path: String, maxBytes: Long): Pair<ByteArray, Boolean>? {
        val normalized = normalizePath(path) ?: return null
        val file = File(normalized)
        if (file.canRead()) {
            val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
            val truncated = bytes.size > maxBytes
            return (if (truncated) bytes.copyOf(maxBytes.toInt()) else bytes) to truncated
        }
        if (!rootGranted()) return null
        return readBytesViaRoot(normalized, maxBytes)
    }

    // ------------------------------------------------------------------
    // 递归搜索
    // ------------------------------------------------------------------

    fun search(dir: String, query: String): List<FsEntry> {
        val normalized = normalizePath(dir) ?: return emptyList()
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        val root = File(normalized)
        if (root.canRead() && root.list() != null) {
            val results = mutableListOf<FsEntry>()
            val queue = ArrayDeque<File>()
            queue.add(root)
            while (queue.isNotEmpty() && results.size < MAX_SEARCH_RESULTS) {
                val current = queue.removeFirst()
                val children = runCatching { current.listFiles() }.getOrNull() ?: continue
                for (child in children) {
                    if (results.size >= MAX_SEARCH_RESULTS) break
                    if (child.name.contains(trimmed, ignoreCase = true)) {
                        results += FsEntry(
                            name = child.name,
                            path = child.absolutePath,
                            isDir = child.isDirectory,
                            sizeBytes = if (child.isDirectory) 0L else child.length(),
                            mtimeEpochSeconds = child.lastModified() / 1000L,
                        )
                    }
                    if (child.isDirectory) queue.add(child)
                }
            }
            return results
        }
        if (!rootGranted()) return emptyList()
        val result = rootRun(
            script = "find ${shellQuote(normalized)} -maxdepth 10 -iname ${shellQuote("*" + trimmed + "*")} 2>/dev/null | head -n ${MAX_SEARCH_RESULTS + 1}",
            maxOutputBytes = 4 * 1024 * 1024,
        )
        if (!result.ok) return emptyList()
        return result.stdout.lineSequence().filter { it.isNotBlank() }.take(MAX_SEARCH_RESULTS).map { line ->
            val file = File(line)
            FsEntry(
                name = file.name,
                path = line,
                isDir = file.isDirectory,
                sizeBytes = if (file.isDirectory) 0L else runCatching { file.length() }.getOrDefault(0L),
                mtimeEpochSeconds = runCatching { file.lastModified() / 1000L }.getOrDefault(0L),
            )
        }.toList()
    }

    // ------------------------------------------------------------------
    // 权限
    // ------------------------------------------------------------------

    fun chmod(path: String, modeBits: Int): OpResult {
        val normalized = normalizePath(path) ?: return OpResult.Failed("INVALID_PATH")
        if (!rootGranted()) return OpResult.Failed("ROOT_REQUIRED")
        return rootRun("chmod " + modeBits.toString(8).padStart(3, '0') + " " + shellQuote(normalized)).okOr("ROOT_CHMOD")
    }

    // ------------------------------------------------------------------
    // Root 流式 IO 内部件
    // ------------------------------------------------------------------

    /**
     * 经 Root 读文件原始字节：统一走 `base64` 中转，避免 String 解码破坏字节，
     * 截断判定基于精确字节数；上限 [MAX_ROOT_FILE_BYTES]（base64 后低于执行器 2MB 输出闸）。
     * 返回 (字节, 是否被截断)；命令失败返回 null。
     */
    private fun readBytesViaRoot(path: String, maxBytes: Long): Pair<ByteArray, Boolean>? {
        val result = rootRun(
            script = "base64 ${shellQuote(path)}",
            maxOutputBytes = ROOT_OUTPUT_CAP_BYTES,
        )
        if (!result.ok) return null
        val bytes = runCatching {
            android.util.Base64.decode(result.stdout, android.util.Base64.DEFAULT)
        }.getOrNull() ?: return null
        val truncated = bytes.size > maxBytes
        return (if (truncated) bytes.copyOf(maxBytes.toInt()) else bytes) to truncated
    }

    /**
     * 经 Root 写整文件：96KB 分块，首块 `cat >`、后续 `cat >>` 追加。
     * 任一块失败立即中止并如实上报（不假装写完）。
     */
    private fun writeViaRootChunked(path: String, bytes: ByteArray): OpResult {
        val quoted = shellQuote(path)
        var offset = 0
        var first = true
        // 空内容也要写一次（清空目标文件），所以首块无条件执行。
        while (first || offset < bytes.size) {
            val end = minOf(offset + ROOT_INPUT_CHUNK_BYTES, bytes.size)
            val chunk = if (offset >= bytes.size) ByteArray(0) else bytes.copyOfRange(offset, end)
            val result = rootRun(
                script = if (first) "cat > $quoted" else "cat >> $quoted",
                input = chunk,
            )
            if (!result.ok) return result.okOr("ROOT_WRITE")
            first = false
            offset = end
        }
        return OpResult.Success
    }
}
