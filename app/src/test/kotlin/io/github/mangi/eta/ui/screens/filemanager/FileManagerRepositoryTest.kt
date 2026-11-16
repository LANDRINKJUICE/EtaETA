package io.github.mangi.eta.ui.screens.filemanager

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * FileManagerRepository 纯函数与 java 直读路径的单测。
 * Root 分支无法在 JVM 覆盖（依赖真机 su），由交接文档声明边界、云端/真机验证。
 */
class FileManagerRepositoryTest {

    @get:Rule
    val temp = TemporaryFolder()

    // ------------------------------------------------------------------
    // normalizePath
    // ------------------------------------------------------------------

    @Test
    fun normalizePath_resolvesDotDotLexically() {
        assertEquals("/a/c", FileManagerRepository.normalizePath("/a/b/../c"))
        assertEquals("/storage/emulated/0", FileManagerRepository.normalizePath("/storage/emulated/0/"))
        assertEquals("/a/b", FileManagerRepository.normalizePath("/a//b"))
        assertEquals("/a", FileManagerRepository.normalizePath("/a/./b/.."))
    }

    @Test
    fun normalizePath_rejectsNonAbsolute() {
        assertNull(FileManagerRepository.normalizePath("a/b"))
        assertNull(FileManagerRepository.normalizePath(""))
        assertNull(FileManagerRepository.normalizePath("  "))
        assertNull(FileManagerRepository.normalizePath("/a\nb"))
        assertNull(FileManagerRepository.normalizePath("/.."))
        assertNull(FileManagerRepository.normalizePath("/a/../../.."))
    }

    // ------------------------------------------------------------------
    // shellQuote
    // ------------------------------------------------------------------

    @Test
    fun shellQuote_wrapsSimpleValue() {
        assertEquals("'/tmp/a b'", FileManagerRepository.shellQuote("/tmp/a b"))
    }

    @Test
    fun shellQuote_escapesSingleQuotesPosixStyle() {
        assertEquals("'a'\\''b'", FileManagerRepository.shellQuote("a'b"))
    }

    // ------------------------------------------------------------------
    // parseStatOutput
    // ------------------------------------------------------------------

    @Test
    fun parseStatOutput_parsesRowsAndKeepsPipesInNames() {
        val output = """
            directory|4096|1700000000|dir name
            regular file|123|1700000001|a|b.txt
            regular empty file|0|1700000002|.hidden
            broken||x|skipme
        """.trimIndent()
        val entries = FileManagerRepository.parseStatOutput(output, "/root")
        assertEquals(3, entries.size)
        assertEquals("/root/dir name", entries[0].path)
        assertTrue(entries[0].isDir)
        assertEquals("/root/a|b.txt", entries[1].path)
        assertEquals(123L, entries[1].sizeBytes)
        assertEquals(".hidden", entries[2].name)
        assertFalse(entries[2].isDir)
    }

    @Test
    fun parseStatOutput_skipsDotsAndBlankLines() {
        val entries = FileManagerRepository.parseStatOutput("directory|4096|1|.\ndirectory|4096|1|..\n\n", "/root")
        assertTrue(entries.isEmpty())
    }

    // ------------------------------------------------------------------
    // mode 换算
    // ------------------------------------------------------------------

    @Test
    fun modeTextRoundTrip_allOctalValues() {
        for (mode in 0..511) {
            val text = FileManagerRepository.modeBitsToText(mode)
            assertEquals(mode, FileManagerRepository.textToModeBits(text))
        }
    }

    @Test
    fun modeBitsToText_knownSamples() {
        assertEquals("rw-r--r--", FileManagerRepository.modeBitsToText(0b110100100))
        assertEquals("rwxr-xr-x", FileManagerRepository.modeBitsToText(0b111101101))
    }

    @Test
    fun textToModeBits_rejectsMalformed() {
        assertNull(FileManagerRepository.textToModeBits("rwxrwxr"))
        assertNull(FileManagerRepository.textToModeBits("rwzr--r--"))
        assertNull(FileManagerRepository.textToModeBits(""))
    }

    // ------------------------------------------------------------------
    // 排序
    // ------------------------------------------------------------------

    private fun entry(name: String, isDir: Boolean = false, size: Long = 0L, mtime: Long = 0L) =
        FileManagerRepository.FsEntry(name = name, path = "/x/$name", isDir = isDir, sizeBytes = size, mtimeEpochSeconds = mtime)

    @Test
    fun compareEntries_foldersFirstAlways() {
        val file = entry("zzz.txt")
        val dir = entry("aaa", isDir = true)
        // 降序也不能让文件排到文件夹前面
        assertTrue(
            FileManagerRepository.compareEntries(dir, file, FileManagerRepository.SortMode.NAME, ascending = false, foldersFirst = true) < 0,
        )
    }

    @Test
    fun compareEntries_sizeDescendingThenNameFallback() {
        val small = entry("b.txt", size = 1)
        val big = entry("a.txt", size = 10)
        assertTrue(
            FileManagerRepository.compareEntries(big, small, FileManagerRepository.SortMode.SIZE, ascending = false, foldersFirst = false) < 0,
        )
        val same1 = entry("B.txt", size = 5)
        val same2 = entry("a.txt", size = 5)
        // 大小相同时按原始名称做稳定兜底排序（区分大小写），双向符号一致且非零
        val forward = FileManagerRepository.compareEntries(same1, same2, FileManagerRepository.SortMode.SIZE, ascending = true, foldersFirst = false)
        val backward = FileManagerRepository.compareEntries(same2, same1, FileManagerRepository.SortMode.SIZE, ascending = true, foldersFirst = false)
        assertTrue(forward != 0)
        assertEquals(forward, -backward)
    }

    @Test
    fun compareEntries_typeSortThenName() {
        val c = entry("b.c")
        val kt = entry("a.kt")
        assertTrue(
            FileManagerRepository.compareEntries(c, kt, FileManagerRepository.SortMode.TYPE, ascending = true, foldersFirst = false) < 0,
        )
    }

    // ------------------------------------------------------------------
    // zip 条目名清洗
    // ------------------------------------------------------------------

    @Test
    fun sanitizeZipEntryName_stripsAbsoluteAndDotSegments() {
        assertEquals("a/b", FileManagerRepository.sanitizeZipEntryName("/a/b"))
        assertEquals("a/b", FileManagerRepository.sanitizeZipEntryName("./a/./b"))
        assertNull(FileManagerRepository.sanitizeZipEntryName("../evil"))
        assertNull(FileManagerRepository.sanitizeZipEntryName("a/../../evil"))
        assertNull(FileManagerRepository.sanitizeZipEntryName("/"))
        assertNull(FileManagerRepository.sanitizeZipEntryName(""))
    }

    // ------------------------------------------------------------------
    // 真实 IO（java 直读路径，TemporaryFolder）
    // ------------------------------------------------------------------

    @Test
    fun makeDir_createFile_rename_roundTrip() {
        val base = temp.root
        val dir = File(base, "fm").absolutePath
        assertEquals(FileManagerRepository.OpResult.Success, FileManagerRepository.makeDir(dir))
        assertTrue(File(dir).isDirectory)
        // 已存在如实报错
        assertTrue(FileManagerRepository.makeDir(dir) is FileManagerRepository.OpResult.Failed)

        val file = dir + "/note.txt"
        assertEquals(FileManagerRepository.OpResult.Success, FileManagerRepository.createFile(file))
        assertTrue(FileManagerRepository.createFile(file) is FileManagerRepository.OpResult.Failed)

        assertEquals(FileManagerRepository.OpResult.Success, FileManagerRepository.rename(file, "renamed.md"))
        assertTrue(File(dir, "renamed.md").isFile)
    }

    @Test
    fun rename_rejectsIllegalNames() {
        val base = temp.newFolder("r")
        val file = File(base, "x.txt").apply { writeText("x") }
        assertTrue(FileManagerRepository.rename(file.absolutePath, "a/b") is FileManagerRepository.OpResult.Failed)
        assertTrue(FileManagerRepository.rename(file.absolutePath, "..") is FileManagerRepository.OpResult.Failed)
        assertTrue(FileManagerRepository.rename(file.absolutePath, "") is FileManagerRepository.OpResult.Failed)
        assertTrue(file.isFile)
    }

    @Test
    fun copyOrMove_copiesTreeWithProgress_thenMoveRemovesSource() {
        val src = temp.newFolder("src")
        File(src, "a.txt").writeText("alpha")
        File(src, "sub").mkdirs()
        File(src, "sub/b.txt").writeText("beta")
        val dst = temp.newFolder("dst")

        val seen = mutableListOf<String>()
        val copyResult = FileManagerRepository.copyOrMove(
            sources = listOf(src.absolutePath),
            targetDir = dst.absolutePath,
            move = false,
        ) { done, total, _ -> seen.add("$done/$total") }
        assertTrue(copyResult is FileManagerRepository.OpResult.Success)
        assertEquals("alpha", File(dst, "src/a.txt").readText())
        assertEquals("beta", File(dst, "src/sub/b.txt").readText())
        assertTrue(seen.isNotEmpty())

        val moveResult = FileManagerRepository.copyOrMove(
            sources = listOf(File(dst, "src").absolutePath),
            targetDir = temp.newFolder("dst2").absolutePath,
            move = true,
        )
        assertTrue(moveResult is FileManagerRepository.OpResult.Success)
        assertFalse(File(dst, "src").exists())
        assertEquals("beta", File(temp.root, "dst2/src/sub/b.txt").readText())
    }

    @Test
    fun copyOrMove_failsWhenTargetIsNotDirectory() {
        val src = temp.newFolder("s2")
        val targetFile = temp.newFile("t.txt")
        val result = FileManagerRepository.copyOrMove(listOf(src.absolutePath), targetFile.absolutePath, move = false)
        assertTrue(result is FileManagerRepository.OpResult.Failed)
    }

    @Test
    fun delete_removesFilesAndDirs() {
        val victim = temp.newFolder("victim")
        File(victim, "deep").mkdirs()
        File(victim, "deep/x").writeText("x")
        val progress = mutableListOf<Int>()
        assertEquals(
            FileManagerRepository.OpResult.Success,
            FileManagerRepository.delete(listOf(victim.absolutePath)) { done, _, _ -> progress.add(done) },
        )
        assertFalse(victim.exists())
        assertTrue(progress.isNotEmpty())
    }

    @Test
    fun zip_unzip_roundTripPreservesContent() {
        val src = temp.newFolder("zsrc")
        File(src, "one.txt").writeText("第一")
        File(src, "two.bin").writeBytes(byteArrayOf(1, 2, 0, 3))
        File(src, "nested").mkdirs()
        File(src, "nested/three.txt").writeText("third")
        temp.newFolder("out")
        val zipPath = File(temp.root, "out/archive.zip").absolutePath

        val zipResult = FileManagerRepository.zip(
            sources = listOf(src.absolutePath),
            targetZipPath = zipPath,
            stagingDir = temp.newFolder("stage"),
        )
        assertTrue("$zipResult", zipResult is FileManagerRepository.OpResult.Success)
        assertTrue(File(zipPath).length() > 0)

        val target = temp.newFolder("zdst")
        val unzipResult = FileManagerRepository.unzip(zipPath, target.absolutePath, temp.newFolder("stage2"))
        assertTrue("$unzipResult", unzipResult is FileManagerRepository.OpResult.Success)
        assertEquals("第一", File(target, "zsrc/one.txt").readText())
        assertTrue(File(target, "zsrc/two.bin").exists())
        assertTrue(File(target, "zsrc/two.bin").readBytes().contentEquals(byteArrayOf(1, 2, 0, 3)))
        assertEquals("third", File(target, "zsrc/nested/three.txt").readText())
    }

    @Test
    fun zip_rejectsNonZipTarget() {
        val src = temp.newFolder("zs2")
        val result = FileManagerRepository.zip(listOf(src.absolutePath), File(temp.root, "out.tar").absolutePath, null)
        assertTrue(result is FileManagerRepository.OpResult.Failed)
    }

    @Test
    fun unzip_rejectsPathTraversalEntries() {
        // 构造一个带 ../ 条目的恶意 zip
        val zipFile = temp.newFile("evil.zip")
        java.util.zip.ZipOutputStream(zipFile.outputStream()).use { zos ->
            zos.putNextEntry(java.util.zip.ZipEntry("../escape.txt"))
            zos.write("boom".toByteArray())
            zos.closeEntry()
        }
        val target = temp.newFolder("evilDst")
        val result = FileManagerRepository.unzip(zipFile.absolutePath, target.absolutePath, null)
        assertTrue(result is FileManagerRepository.OpResult.Failed)
        assertFalse(File(target.parentFile, "escape.txt").exists())
    }

    @Test
    fun readText_writeText_roundTripUtf8AndBinary() {
        val textFile = temp.newFile("cn.txt")
        textFile.writeText("你好，世界\nline2")
        val read = FileManagerRepository.readText(textFile.absolutePath)
        assertTrue(read is FileManagerRepository.TextReadResult.Text)
        assertEquals("你好，世界\nline2", (read as FileManagerRepository.TextReadResult.Text).content)
        assertFalse(read.truncated)

        val newFile = File(temp.root, "written.md")
        assertEquals(
            FileManagerRepository.OpResult.Success,
            FileManagerRepository.writeText(newFile.absolutePath, "保存内容 ✓", null),
        )
        assertEquals("保存内容 ✓", newFile.readText())

        val binFile = temp.newFile("bin.dat")
        binFile.writeBytes(byteArrayOf(0, 1, 2))
        assertTrue(FileManagerRepository.readText(binFile.absolutePath) is FileManagerRepository.TextReadResult.Binary)
    }

    @Test
    fun readText_reportsTruncationBeyondLimit() {
        val bigFile = temp.newFile("big.txt")
        bigFile.writeText("x".repeat((FileManagerRepository.MAX_TEXT_READ_BYTES + 1024).toInt()))
        val read = FileManagerRepository.readText(bigFile.absolutePath)
        assertTrue(read is FileManagerRepository.TextReadResult.Text)
        assertTrue((read as FileManagerRepository.TextReadResult.Text).truncated)
        assertEquals(FileManagerRepository.MAX_TEXT_READ_BYTES, read.content.length.toLong())
    }

    @Test
    fun list_reportsJavaEntriesForReadableDir() {
        val dir = temp.newFolder("ldir")
        File(dir, "folder").mkdirs()
        File(dir, "item.txt").writeText("hi")
        File(dir, ".dot").writeText("d")
        val result = FileManagerRepository.list(dir.absolutePath)
        assertTrue(result is FileManagerRepository.ListResult.Success)
        val names = (result as FileManagerRepository.ListResult.Success).entries.map { it.name }.toSet()
        assertEquals(setOf("folder", "item.txt", ".dot"), names)
    }

    @Test
    fun list_reportsNotDirectoryForMissingPath() {
        val result = FileManagerRepository.list(temp.root.resolve("no-such-dir").absolutePath)
        assertTrue(result is FileManagerRepository.ListResult.NotDirectory)
    }

    @Test
    fun fileDetails_returnsJavaApproximationWithoutRoot() {
        val file = temp.newFile("d.txt").apply { writeText("hello") }
        val details = FileManagerRepository.fileDetails(file.absolutePath)
        assertNotNull(details)
        assertFalse(details!!.isDir)
        assertEquals(5L, details.sizeBytes)
        assertNotNull(details.modeText)
        assertEquals(9, details.modeText!!.length)
    }

    @Test
    fun extensionOf_basicCases() {
        assertEquals("txt", FileManagerRepository.extensionOf("a.txt"))
        assertEquals("", FileManagerRepository.extensionOf("noext"))
        assertEquals("", FileManagerRepository.extensionOf(".hidden"))
        assertEquals("gz", FileManagerRepository.extensionOf("archive.tar.gz"))
    }
}
