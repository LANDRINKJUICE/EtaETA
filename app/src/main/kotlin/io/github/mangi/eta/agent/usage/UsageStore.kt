package io.github.mangi.eta.agent.usage

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** 用量记录的落点。实现必须做到"要么很快返回，要么什么都不做"，不能向调用方抛异常。 */
internal interface UsageSink {
    fun offer(record: UsageRecord)
}

/**
 * 本地用量账本的存储层：单个 JSONL 文件，一行一次请求。
 *
 * 为什么不用数据库：记录只追加、只按时间整段读，SQLite 的写入事务与迁移成本换不来好处；
 * 一个文本文件还能在出问题时直接 `grep` 排查，也不会因为一次 schema 升级失败而丢历史。
 *
 * 边界（需求要求"有界"）：
 * - 时间：默认保留 90 天，[append] 时按需裁剪；
 * - 条数：默认最多 20000 条，超出时保留最新的；
 * - 体积：文件超过 [MAX_FILE_BYTES] 时也会触发裁剪，防止一次异常风暴把存储写满。
 *
 * 并发：同一进程内所有实例共用 [LOCK]（文件只有一个），读-改-写整段串行；
 * 裁剪走"临时文件 + 原子 move"，中途被杀不会留下半截账本。
 */
internal class UsageStore(
    private val directory: File,
    private val now: () -> Long = System::currentTimeMillis,
    private val retentionMillis: Long = DEFAULT_RETENTION_MILLIS,
    private val maxRecords: Int = DEFAULT_MAX_RECORDS,
) {
    private fun file(): File = File(directory, FILE_NAME)

    /** 上次裁剪时刻，仅用于把裁剪摊薄到每隔几小时一次；进程重启后从头计。 */
    private var lastPruneAtMillis = 0L

    fun append(record: UsageRecord): Boolean = synchronized(LOCK) {
        runCatching {
            directory.mkdirs()
            file().appendText(record.encode() + "\n")
            maybePruneLocked()
            true
        }.getOrDefault(false)
    }

    /**
     * 读取不早于 [sinceMillis] 的记录，按时间升序。损坏的行直接跳过。
     *
     * 从文件尾部按块回读：账本只追加、时间单调递增，所以一旦遇到早于 sinceMillis 的记录就可以停，
     * 不必把 90 天历史全量解析一遍（页面只画 30 天窗口，越老的行纯属浪费 CPU）。
     * 以 0x0A 切行是 UTF-8 安全的（多字节序列里不会出现 0x0A）。
     */
    fun load(sinceMillis: Long = 0L): List<UsageRecord> = synchronized(LOCK) {
        val target = file()
        if (!target.isFile) return emptyList()
        runCatching { readSince(target, sinceMillis) }.getOrElse { emptyList() }
    }

    private fun readSince(target: File, sinceMillis: Long): List<UsageRecord> {
        val collected = ArrayList<UsageRecord>()
        java.io.RandomAccessFile(target, "r").use { raf ->
            var end = raf.length()
            var carry = ByteArray(0) // 上一块里"还没遇到换行"的前半段，接到下一块（更早的数据）末尾
            var stopped = false
            while (end > 0 && !stopped) {
                val size = minOf(READ_CHUNK_BYTES.toLong(), end).toInt()
                end -= size
                val chunk = ByteArray(size)
                raf.seek(end)
                raf.readFully(chunk)
                val merged = chunk + carry
                var lineEnd = merged.size
                var index = merged.size - 1
                while (index >= 0) {
                    if (merged[index] == NEWLINE) {
                        val line = String(merged, index + 1, lineEnd - index - 1, Charsets.UTF_8).trim()
                        lineEnd = index
                        if (line.isNotEmpty()) {
                            val record = UsageRecord.parse(line)
                            if (record != null) {
                                if (record.atMillis < sinceMillis) {
                                    stopped = true
                                    break
                                }
                                collected.add(record)
                            }
                        }
                    }
                    index--
                }
                carry = merged.copyOfRange(0, lineEnd)
            }
            // 文件第一条记录前面没有换行：最后处理剩下的残段。
            if (!stopped && carry.isNotEmpty()) {
                val record = UsageRecord.parse(String(carry, Charsets.UTF_8).trim())
                if (record != null && record.atMillis >= sinceMillis) collected.add(record)
            }
        }
        collected.sortBy { it.atMillis }
        return collected
    }

    /** 全量行数（含损坏行），仅用于测试与诊断。 */
    fun lineCount(): Int = synchronized(LOCK) {
        val target = file()
        if (!target.isFile) return 0
        runCatching { target.useLines { lines -> lines.count { it.isNotBlank() } } }.getOrDefault(0)
    }

    fun clear(): Boolean = synchronized(LOCK) {
        runCatching { !file().exists() || file().delete() }.getOrDefault(false)
    }

    /**
     * 按时间与条数上限裁剪，返回被删掉的记录数。
     *
     * 只在确有变化时重写文件，避免每次打开页面都产生一次写放大。
     */
    fun prune(atMillis: Long = now()): Int = synchronized(LOCK) {
        lastPruneAtMillis = atMillis
        val target = file()
        if (!target.isFile) return 0
        val cutoff = atMillis - retentionMillis
        val all = runCatching {
            target.useLines { lines -> lines.mapNotNull { line -> UsageRecord.parse(line) } .toList() }
        }.getOrElse { return 0 }
        if (all.isEmpty()) return 0
        val kept = all.filter { record -> record.atMillis >= cutoff }
            .sortedBy { record -> record.atMillis }
            .let { sorted -> if (sorted.size > maxRecords) sorted.takeLast(maxRecords) else sorted }
        val removed = all.size - kept.size
        if (removed <= 0) return 0
        writeAtomically(target, kept.joinToString(separator = "\n", postfix = "\n") { it.encode() })
        removed
    }

    private fun maybePruneLocked() {
        val current = now()
        val overdue = current - lastPruneAtMillis >= PRUNE_INTERVAL_MILLIS || lastPruneAtMillis == 0L
        val oversized = runCatching { file().length() }.getOrDefault(0L) > MAX_FILE_BYTES
        if (overdue || oversized) prune(current)
    }

    private fun writeAtomically(target: File, content: String) {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, "$FILE_NAME.tmp")
        temp.writeText(content)
        val moved = runCatching {
            Files.move(
                temp.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
            true
        }.getOrDefault(false)
        // 部分 bind mount 不支持 ATOMIC_MOVE：退回同目录 rename，仍是单步替换。
        if (!moved && !temp.renameTo(target)) error("无法提交用量账本")
    }

    companion object {
        const val FILE_NAME = "usage.jsonl"
        const val DEFAULT_MAX_RECORDS = 20_000
        const val MAX_FILE_BYTES = 8L * 1024L * 1024L
        const val RETENTION_DAYS = 90L
        const val DEFAULT_RETENTION_MILLIS = RETENTION_DAYS * 24L * 60L * 60L * 1000L
        const val PRUNE_INTERVAL_MILLIS = 6L * 60L * 60L * 1000L

        /** 尾部回读的块大小：8KB 足以装下数百行，也不会一次读进整个账本。 */
        private const val READ_CHUNK_BYTES = 8 * 1024
        private const val NEWLINE = '\n'.code.toByte()

        /** 同一进程内同一文件的唯一串行化点。 */
        private val LOCK = Any()
    }
}

/**
 * 异步落盘：模型返回后立刻入队，真正的文件写入在独立守护线程上完成。
 *
 * 主链路零阻塞的具体做法：
 * - [offer] 只做一次有界队列的 `offer`，队列满时**直接丢弃**这条统计（统计的价值远低于一次回答）；
 * - 写盘异常在 worker 内部吞掉，绝不冒泡到 run 线程；
 * - [close] 只置位并唤醒线程，不 join，避免退出 App 时卡住主线程。
 */
internal class UsageLogSink(
    private val store: UsageStore,
    queueCapacity: Int = DEFAULT_QUEUE_CAPACITY,
) : UsageSink, AutoCloseable {
    private val queue = ArrayBlockingQueue<UsageRecord>(queueCapacity)
    private val running = AtomicBoolean(true)
    private val pending = AtomicInteger(0)

    /** 已丢弃的记录数（队列满或已关闭），仅用于诊断。 */
    // 必须声明在 worker 启动之前：worker 在构造函数里就被 start()，若它先跑而 dropped 还没初始化，
    // 这里就是一个典型的 this-escape（读到 null）。
    private val dropped = AtomicInteger(0)

    private val worker = Thread(::loop, "eta-usage-log").apply {
        isDaemon = true
        priority = Thread.MIN_PRIORITY
        start()
    }

    fun droppedCount(): Int = dropped.get()

    override fun offer(record: UsageRecord) {
        if (!running.get()) {
            dropped.incrementAndGet()
            return
        }
        if (!queue.offer(record)) {
            dropped.incrementAndGet()
            return
        }
        pending.incrementAndGet()
    }

    /** 等待队列排空（导出/测试用）；超时返回 false。 */
    fun flush(timeoutMillis: Long = 2_000L): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (pending.get() <= 0) return true
            runCatching { Thread.sleep(10L) }
        }
        return pending.get() <= 0
    }

    override fun close() {
        if (!running.compareAndSet(true, false)) return
        worker.interrupt()
    }

    private fun loop() {
        while (true) {
            val record = try {
                queue.poll(POLL_INTERVAL_MILLIS, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                null
            }
            if (record == null) {
                if (!running.get()) return
                continue
            }
            try {
                store.append(record)
            } catch (_: Throwable) {
                // 统计落盘失败不影响任何主流程，也不重试（避免坏盘时反复写同一行）。
            } finally {
                pending.decrementAndGet()
            }
        }
    }

    companion object {
        const val DEFAULT_QUEUE_CAPACITY = 512
        private const val POLL_INTERVAL_MILLIS = 500L
    }
}
