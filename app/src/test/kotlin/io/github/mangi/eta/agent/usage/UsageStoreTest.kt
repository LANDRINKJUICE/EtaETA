package io.github.mangi.eta.agent.usage

import io.github.mangi.eta.agent.runs.AgentRunSource
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用量账本存储层的回归测试。
 *
 * 钉住的语义：追加-读取保真、脏行跳过、90 天保留、条数上限保留最新、并发追加不丢记录、
 * 异步落点不阻塞调用方且在关闭后丢弃新记录。
 */
class UsageStoreTest {
    private val dir: File = Files.createTempDirectory("eta-usage-test").toFile()

    @After
    fun cleanup() {
        dir.deleteRecursively()
    }

    private fun record(
        atMillis: Long,
        source: AgentRunSource = AgentRunSource.CHAT,
        prompt: Int = 100,
        completion: Int = 20,
        estimated: Boolean = false,
    ) = UsageRecord(
        atMillis = atMillis,
        source = source,
        purpose = "chat",
        providerId = "builtin-openai",
        providerName = "OpenAI",
        model = "gpt-5.5",
        promptTokens = prompt,
        completionTokens = completion,
        cachedTokens = 0,
        estimated = estimated,
        runId = "run-$atMillis",
    )

    private fun store(
        nowMillis: Long = NOW,
        retentionMillis: Long = UsageStore.DEFAULT_RETENTION_MILLIS,
        maxRecords: Int = UsageStore.DEFAULT_MAX_RECORDS,
    ) = UsageStore(
        directory = dir,
        now = { nowMillis },
        retentionMillis = retentionMillis,
        maxRecords = maxRecords,
    )

    @Test
    fun appendThenLoadRoundTripKeepsFieldValues() {
        val target = store()
        assertTrue(target.append(record(NOW - 1_000)))
        assertTrue(target.append(record(NOW - 5_000, source = AgentRunSource.SCHEDULED, estimated = true)))

        val loaded = target.load()
        assertEquals(2, loaded.size)
        // 按时间升序：更早的在前。
        assertEquals(AgentRunSource.SCHEDULED, loaded.first().source)
        assertTrue(loaded.first().estimated)
        assertEquals(AgentRunSource.CHAT, loaded.last().source)
    }

    @Test
    fun loadFiltersBySinceTimestamp() {
        val target = store()
        target.append(record(NOW - 10L * DAY))
        target.append(record(NOW - 1_000))
        assertEquals(1, target.load(NOW - DAY).size)
        assertEquals(2, target.load().size)
    }

    private fun writeLedger(vararg records: UsageRecord) {
        dir.mkdirs()
        File(dir, UsageStore.FILE_NAME).writeText(records.joinToString("\n") { it.encode() } + "\n")
    }

    @Test
    fun brokenLinesAreSkippedInsteadOfFailingTheWholeFile() {
        dir.mkdirs()
        File(dir, UsageStore.FILE_NAME).writeText(
            listOf(
                record(NOW - 1_000).encode(),
                "{not json at all",
                "",
                """{"src":"chat"}""",
                record(NOW - 2_000).encode(),
            ).joinToString("\n"),
        )
        assertEquals(2, store().load().size)
    }

    @Test
    fun pruneDropsRecordsOlderThanRetention() {
        writeLedger(
            record(NOW - 11L * DAY),
            record(NOW - 9L * DAY),
            record(NOW - 1_000),
        )
        val target = store(retentionMillis = 10L * DAY)

        assertEquals(1, target.prune(NOW))
        val loaded = target.load()
        assertEquals(2, loaded.size)
        assertTrue(loaded.all { it.atMillis >= NOW - 10L * DAY })
    }

    @Test
    fun firstAppendInProcessAlsoPrunesExpiredRecords() {
        writeLedger(record(NOW - 11L * DAY), record(NOW - 9L * DAY))
        val target = store(retentionMillis = 10L * DAY)
        assertTrue(target.append(record(NOW - 1_000)))
        // 过期记录在写入时被顺带裁掉，只留下 9 天前与新写入的两条。
        assertEquals(2, target.load().size)
    }

    @Test
    fun pruneKeepsNewestRecordsWhenOverTheCap() {
        val target = store(maxRecords = 3)
        // 依次写入 NOW-10s .. NOW-6s，保留最新 3 条即 NOW-8s / NOW-7s / NOW-6s。
        repeat(5) { index -> target.append(record(NOW - (10 - index) * 1_000L)) }
        assertEquals(2, target.prune(NOW))
        val loaded = target.load()
        assertEquals(3, loaded.size)
        assertEquals(NOW - 8_000, loaded.first().atMillis)
        assertEquals(NOW - 6_000, loaded.last().atMillis)
    }

    @Test
    fun clearRemovesTheLedger() {
        val target = store()
        target.append(record(NOW - 1_000))
        assertTrue(target.clear())
        assertTrue(target.load().isEmpty())
        assertEquals(0, target.lineCount())
    }

    @Test
    fun concurrentAppendsAreAllPersisted() {
        val target = store()
        val threads = 6
        val perThread = 40
        val latch = CountDownLatch(threads)
        repeat(threads) { threadIndex ->
            Thread {
                try {
                    repeat(perThread) { index ->
                        target.append(record(NOW - 1_000L * (threadIndex * perThread + index)))
                    }
                } finally {
                    latch.countDown()
                }
            }.start()
        }
        assertTrue(latch.await(30, TimeUnit.SECONDS))
        assertEquals(threads * perThread, target.load().size)
    }

    @Test
    fun asyncSinkPersistsRecordsWithoutBlockingTheCaller() {
        val target = store()
        val sink = UsageLogSink(target)
        try {
            repeat(50) { index -> sink.offer(record(NOW - index * 1_000L)) }
            assertTrue(sink.flush(5_000))
            assertEquals(50, target.load().size)
        } finally {
            sink.close()
        }
    }

    @Test
    fun asyncSinkDropsRecordsAfterClose() {
        val target = store()
        val sink = UsageLogSink(target)
        sink.close()
        sink.offer(record(NOW))
        sink.flush(200)
        assertTrue(target.load().isEmpty())
        assertEquals(1, sink.droppedCount())
    }


    @Test
    fun asyncSinkDropsWhenQueueIsFull() {
        // 容量 1：生产者（纯入队）远快于消费者（每条一次开文件+写盘），队列必然被填满。
        // 要钉住的语义是"满则丢、绝不阻塞调用方、并计入 droppedCount"，而不是具体丢多少条。
        val target = store()
        val sink = UsageLogSink(target, queueCapacity = 1)
        try {
            var offered = 0
            while (sink.droppedCount() == 0 && offered < MAX_OFFER_ATTEMPTS) {
                sink.offer(record(NOW - offered * 1_000L))
                offered++
            }
            assertTrue("队列满时必须计入 droppedCount", sink.droppedCount() > 0)
            assertTrue("应该是在有限次入队内就满的", offered < MAX_OFFER_ATTEMPTS)
            // 调用方全程没有被阻塞；已落盘数量不超过注入总数。
            assertTrue(sink.flush(5_000))
            assertTrue(target.load(0L).size <= offered)
        } finally {
            sink.close()
        }
    }

    @Test
    fun loadOnlyParsesTheTailWhenSinceIsRecent() {
        // 30 天窗口只关心最近的记录：load(since) 从文件尾部回读，遇到更早的即停。
        // 跨度刻意控制在 90 天保留期内，避免 append 的裁剪把老记录先删掉（那会污染本用例）。
        val target = store()
        val base = NOW - 80 * DAY
        repeat(80) { index -> target.append(record(base + index * DAY)) }
        val since = base + 70 * DAY
        val recent = target.load(since)
        assertEquals(10, recent.size)
        assertTrue(recent.all { it.atMillis >= since })
        // 结果仍然按时间升序，供聚合器直接使用。
        assertEquals(recent.sortedBy { it.atMillis }, recent)
        // since=0 时仍能拿到全部（尾部回读到文件头，含文件第一篇记录）。
        assertEquals(80, target.load(0L).size)
    }

    @Test
    fun loadSkipsDamagedLinesWhenReadingTheTail() {
        val target = store()
        repeat(5) { index -> target.append(record(NOW - index * 1_000L)) }
        // 手工追加一行损坏数据：尾部回读必须跳过它而不是整段失败。
        java.io.File(dir, UsageStore.FILE_NAME).appendText("{not json}\n")
        assertEquals(5, target.load(0L).size)
    }

    companion object {
        /** 2026-05-20 12:00 UTC 附近的一个固定时刻。 */
        const val NOW = 1_774_000_000_000L
        /** 队列满用例的入队上限：容量 1 时实际几十条就会触发丢弃，留足余量避免偶发。 */
        const val MAX_OFFER_ATTEMPTS = 50_000
        const val DAY = 24L * 60L * 60L * 1000L
    }
}