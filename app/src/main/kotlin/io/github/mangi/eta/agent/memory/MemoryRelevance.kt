package io.github.mangi.eta.agent.memory

import io.github.mangi.eta.data.repository.AgentMemoryEntry
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Locale

/** 一个被打分选中的记忆节。 */
internal data class ScoredMemorySection(
    val entry: AgentMemoryEntry,
    /** 与查询文本的关键词重合度，0..1。 */
    val keywordScore: Double,
    /** 由 verified/created 日期换算的新鲜度奖励，0..0.15。 */
    val freshness: Double,
    val score: Double,
)

/**
 * 一期相关性打分：关键词重合度为主，时间新鲜度为辅。
 *
 * 纯函数实现，不读设置、不读文件：调用方决定是否启用（不启用时不传查询文本）。
 */
internal object MemoryRelevance {
    /** 超过这个天数未核对的状态类条目视为过期，分数减半。 */
    const val STALE_STATE_DAYS = 90L

    private const val FRESH_DAYS = 30L
    private const val RECENT_DAYS = 90L
    private const val FRESH_BONUS = 0.15
    private const val RECENT_BONUS = 0.08
    private const val BODY_WEIGHT = 0.7
    private const val HEADING_WEIGHT = 0.3
    private const val STALE_STATE_PENALTY = 0.5

    /** 关键词完全不相交的节不参与注入：只靠新鲜度排序会把无关内容塞进上下文。 */
    fun select(
        entries: List<AgentMemoryEntry>,
        query: String,
        now: LocalDate,
        limit: Int,
    ): List<ScoredMemorySection> {
        if (limit <= 0) return emptyList()
        val queryTokens = tokens(query)
        if (queryTokens.isEmpty()) return emptyList()
        return entries
            .mapNotNull { entry -> score(entry, queryTokens, now) }
            .sortedWith(
                compareByDescending<ScoredMemorySection> { it.score }
                    .thenByDescending { it.entry.lastUpdated ?: LocalDate.MIN }
                    .thenBy { it.entry.startLine },
            )
            .take(limit)
    }

    /** 单节打分；没有任何关键词重合时返回 null。 */
    fun score(
        entry: AgentMemoryEntry,
        queryTokens: Set<String>,
        now: LocalDate,
    ): ScoredMemorySection? {
        if (queryTokens.isEmpty()) return null
        val bodyTokens = tokens(entry.body)
        val headingTokens = tokens(entry.heading)
        val bodyMatched = queryTokens.count { it in bodyTokens }
        val headingMatched = queryTokens.count { it in headingTokens }
        if (bodyMatched == 0 && headingMatched == 0) return null
        val keyword = BODY_WEIGHT * bodyMatched.toDouble() / queryTokens.size +
            HEADING_WEIGHT * headingMatched.toDouble() / queryTokens.size
        val age = ageInDays(entry, now)
        val freshness = when {
            age == null -> 0.0
            age <= FRESH_DAYS -> FRESH_BONUS
            age <= RECENT_DAYS -> RECENT_BONUS
            else -> 0.0
        }
        val penalty = if (isStateLike(entry) && age != null && age > STALE_STATE_DAYS) {
            STALE_STATE_PENALTY
        } else {
            1.0
        }
        return ScoredMemorySection(
            entry = entry,
            keywordScore = keyword,
            freshness = freshness,
            score = (keyword + freshness) * penalty,
        )
    }

    /**
     * 状态类条目：元数据显式写着 `type=state|status`，或标题表明它是当前状态/进度。
     * 这类内容会随时间失效，因此过期后降权。
     */
    fun isStateLike(entry: AgentMemoryEntry): Boolean {
        val explicit = entry.metadata.type?.lowercase(Locale.ROOT)
        if (explicit in STATE_TYPES) return true
        return STATE_HEADING_HINTS.any { hint -> entry.title.contains(hint, ignoreCase = true) }
    }

    private fun ageInDays(entry: AgentMemoryEntry, now: LocalDate): Long? =
        entry.lastUpdated?.let { date -> ChronoUnit.DAYS.between(date, now) }

    /**
     * 中英混合分词：拉丁词按字母数字切分并小写，CJK 连续片段取二元组（单字片段保留自身）。
     * 丢弃停用词与单字符拉丁词，保证打分对自然语言提问有稳定信号。
     */
    fun tokens(text: String): Set<String> {
        if (text.isBlank()) return emptySet()
        val result = LinkedHashSet<String>()
        val latin = StringBuilder()
        val cjk = StringBuilder()

        fun flushLatin() {
            if (latin.isEmpty()) return
            val token = latin.toString().lowercase(Locale.ROOT)
            latin.setLength(0)
            if (token.length >= 2 && token !in LATIN_STOPWORDS) result += token
        }

        fun flushCjk() {
            if (cjk.isEmpty()) return
            val run = cjk.toString()
            cjk.setLength(0)
            if (run.length == 1) {
                if (run !in CJK_STOPWORDS) result += run
                return
            }
            for (index in 0 until run.length - 1) {
                val bigram = run.substring(index, index + 2)
                if (bigram !in CJK_BIGRAM_STOPWORDS) result += bigram
            }
        }

        text.forEach { character ->
            when {
                isCjkLike(character) -> {
                    flushLatin()
                    cjk.append(character)
                }
                character.isLetterOrDigit() -> {
                    flushCjk()
                    latin.append(character)
                }
                else -> {
                    flushLatin()
                    flushCjk()
                }
            }
        }
        flushLatin()
        flushCjk()
        return result
    }

    private fun isCjkLike(character: Char): Boolean {
        val code = character.code
        return code in 0x3040..0x30FF || // 平假名/片假名
            code in 0x3400..0x4DBF || // 扩展 A
            code in 0x4E00..0x9FFF || // 基本汉字
            code in 0xF900..0xFAFF || // 兼容汉字
            code in 0xAC00..0xD7AF // 谚文
    }

    private val STATE_TYPES = setOf("state", "status", "progress", "进度", "状态")

    private val STATE_HEADING_HINTS = listOf(
        "状态", "当前", "进度", "进行中", "计划中", "待办", "current", "status", "progress", "ongoing",
    )

    private val CJK_STOPWORDS = setOf(
        "的", "了", "是", "在", "我", "你", "他", "她", "它", "们", "和", "与", "也", "就", "都", "而",
        "及", "或", "但", "把", "被", "给", "对", "这", "那", "有", "个", "吗", "呢", "吧", "啊", "呀",
        "不", "没", "要", "会", "能", "想", "让", "从", "到", "向", "再", "又", "更", "最", "很", "太",
        "还", "并", "且", "以", "为", "着", "过", "上", "下", "里", "好", "去", "来", "做", "用",
    )

    private val CJK_BIGRAM_STOPWORDS = setOf(
        "我们", "你们", "他们", "它们", "这个", "那个", "这些", "那些", "什么", "怎么", "如何", "可以",
        "需要", "帮我", "一下", "一些", "现在", "今天", "明天", "昨天", "然后", "因为", "所以", "如果",
        "还是", "已经", "只是", "并且", "以及", "之类", "等等", "时候", "自己", "一个", "能否", "请问",
        "麻烦", "谢谢", "看看", "是否", "多少", "哪些", "哪个", "怎样", "这边", "那里",
    )

    private val LATIN_STOPWORDS = setOf(
        "the", "an", "and", "or", "of", "to", "in", "on", "for", "is", "are", "was", "were", "be",
        "been", "being", "it", "this", "that", "these", "those", "with", "as", "at", "by", "from",
        "not", "no", "do", "does", "did", "can", "could", "should", "would", "will", "you", "your",
        "yours", "i", "me", "my", "we", "our", "they", "them", "he", "she", "his", "her", "but",
        "if", "then", "than", "so", "just", "about", "into", "over", "under", "again", "more", "most",
        "some", "any", "all", "each", "other", "such", "only", "own", "same", "too", "very", "also",
        "please", "help", "now", "today", "tomorrow", "yesterday", "want", "need", "make", "get",
    )
}
