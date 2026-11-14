package io.github.mangi.eta.agent.memory

import io.github.mangi.eta.data.repository.AgentMemorySnapshot
import java.time.LocalDate

/**
 * 记忆上下文按会话固定，保证系统提示在多轮对话间逐字节不变。
 *
 * 记忆位于系统提示中，而系统提示是请求前缀的开头：每次写入都重建它，会让该会话此前的
 * 全部历史失去缓存。本会话自己写入的内容已经以工具调用的形式留在历史里，模型无需从系统
 * 提示再看一遍，因此只要记忆没有被其他来源（别的会话、用户在设置页编辑）改动，就继续沿用
 * 首次注入的快照；被外部改动或上下文被压缩（历史里的写入可能已被摘要掉）时才重新注入。
 *
 * 一期相关性注入是查询相关的：先用"关键词集合签名"做一次廉价比较，签名相同就直接复用
 * 缓存的上下文（不做全文解析与打分）；签名不同时才重新构建，并且只在选中的节真正变化时
 * 替换会话内的快照，因此同一话题连续追问不会反复改写系统提示。
 *
 * 只在进程内保存：进程重启后首轮重新注入一次，代价是一次缓存未命中，不影响正确性；
 * 写入仍以 revision 做乐观并发校验，沿用旧快照不会覆盖他处的修改。
 */
internal object ConversationMemoryPins {
    private class Pin(
        val context: AgentMemoryContext,
        val contextWindow: Int?,
        var knownRevision: String,
        /** 构建该上下文时用到的查询关键词签名，用于跳过完全相同的重复查询。 */
        var querySignature: String,
    )

    private const val MAX_PINS = 32

    private val pins = object : LinkedHashMap<String, Pin>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pin>?): Boolean = size > MAX_PINS
    }

    @Synchronized
    fun contextFor(
        conversationId: String?,
        snapshot: AgentMemorySnapshot,
        contextWindow: Int?,
        query: String? = null,
        now: LocalDate = LocalDate.now(),
    ): AgentMemoryContext {
        val signature = querySignature(query)
        val pin = conversationId?.let(pins::get)
        if (
            pin != null &&
            pin.knownRevision == snapshot.revision &&
            pin.contextWindow == contextWindow &&
            pin.querySignature == signature
        ) {
            return pin.context
        }
        val fresh = AgentMemoryContextBuilder.build(snapshot, contextWindow, query, now)
        if (conversationId != null) {
            val current = pins[conversationId]
            if (
                current != null &&
                current.knownRevision == snapshot.revision &&
                current.contextWindow == contextWindow &&
                current.context.injectedSections == fresh.injectedSections
            ) {
                // 措辞变了但选中的节没变：沿用旧实例，并记下新签名避免下次重算。
                current.querySignature = signature
                return current.context
            }
            pins[conversationId] = Pin(fresh, contextWindow, snapshot.revision, signature)
        }
        return fresh
    }

    /** 本会话的 memory_write 成功后调用：这次变化已在历史中，不算外部改动。 */
    @Synchronized
    fun recordOwnWrite(conversationId: String?, revision: String) {
        if (conversationId == null || revision.isBlank()) return
        pins[conversationId]?.knownRevision = revision
    }

    @Synchronized
    fun forget(conversationId: String?) {
        if (conversationId != null) pins.remove(conversationId)
    }

    /** 查询关键词的规范签名：同一组关键词无论顺序/重复都等价，分数与选中集合也必然相同。 */
    private fun querySignature(query: String?): String {
        val trimmed = query?.trim().orEmpty()
        if (trimmed.isEmpty()) return ""
        return MemoryRelevance.tokens(trimmed).sorted().joinToString("\u0001")
    }
}
