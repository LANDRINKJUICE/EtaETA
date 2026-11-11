package io.github.mangi.eta.agent.approval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 需求 4 意图文本规整（ApprovalIntentPolicy）的纯 JVM 回归。
 *
 * 钉住审查 G1/G2 的不变量：折叠内部空白（防模型用换行伪造额外风险理由）、按码点安全
 * 截断（不切坏 emoji）、空 / 空白降级、卡片与通知两档上限。
 */
class ApprovalIntentPolicyTest {

    @Test
    fun emptyAndBlankInputsNormalizeToEmpty() {
        assertEquals("", ApprovalIntentPolicy.normalize("", ApprovalIntentPolicy.CARD_MAX_CHARS))
        assertEquals("", ApprovalIntentPolicy.normalize("   \t  ", ApprovalIntentPolicy.CARD_MAX_CHARS))
        assertEquals("", ApprovalIntentPolicy.normalize("\n \n", ApprovalIntentPolicy.CARD_MAX_CHARS))
        assertFalse(ApprovalIntentPolicy.shouldShow(ApprovalIntentPolicy.normalize("  ", 120)))
    }

    @Test
    fun trimsLeadingAndTrailingWhitespace() {
        assertEquals("读取系统版本", ApprovalIntentPolicy.normalize("  读取系统版本  ", 120))
    }

    @Test
    fun collapsesInternalWhitespaceToSingleSpace() {
        // 防模型用换行 / 制表符在通知逐行正文里伪装出一条「风险理由」误导放行。
        assertEquals(
            "排查兼容性 · 只读操作",
            ApprovalIntentPolicy.normalize("排查兼容性\n·\t只读操作", 120),
        )
        assertEquals(
            "a b c",
            ApprovalIntentPolicy.normalize("a   b\t\tc\n", 120),
        )
    }

    @Test
    fun normalTextWithinLimitIsUnchanged() {
        val text = "想读取 build.prop 里的系统版本号来排查兼容性"
        assertEquals(text, ApprovalIntentPolicy.normalize(text, ApprovalIntentPolicy.CARD_MAX_CHARS))
        assertTrue(ApprovalIntentPolicy.shouldShow(text))
    }

    @Test
    fun truncatesByCodePointsNotUtf16Units() {
        // 4 个 emoji = 4 个码点 / 8 个 UTF-16 单元。按码点截 2（含省略号）必须保留
        // 1 个完整 emoji + 省略号，不能切坏代理对（String.take(2) 只会得到半个字符）。
        val emojis = "😀😃😄😁"
        val result = ApprovalIntentPolicy.normalize(emojis, 2)
        assertEquals(2, result.codePointCount(0, result.length))
        assertEquals("😀…", result)
        // 结果不含替换字符 / 半代理。
        assertTrue(result.none { it == '\uFFFD' })
    }

    @Test
    fun supplementaryCjkIsNotSplit() {
        // 𠀀 是增补汉字（1 码点 / 代理对 2 单元），截 2（含省略号）必须保留完整字符。
        val text = "𠀀𠀁𠀂"
        val result = ApprovalIntentPolicy.normalize(text, 2)
        assertEquals(2, result.codePointCount(0, result.length))
        assertEquals("𠀀…", result)
    }

    @Test
    fun truncationAddsEllipsisWithinBudget() {
        val longIntent = "目".repeat(200)
        val result = ApprovalIntentPolicy.normalize(longIntent, 10)
        assertEquals(10, result.codePointCount(0, result.length))
        assertTrue(result.endsWith("…"))
        assertTrue(result.startsWith("目"))
    }

    @Test
    fun unicodeSeparatorsAreFoldedToo() {
        // G2 补全：\s 不覆盖 U+2028 行分隔符 / U+3000 全角空格 / NBSP，
        // 这些也必须折叠成空格，否则模型可借 U+2028 在通知里伪造额外一行「风险理由」。
        assertEquals(
            "排查 只读 操作",
            ApprovalIntentPolicy.normalize("排查\u2028只读\u3000操作", 120),
        )
        assertEquals(
            "a b",
            ApprovalIntentPolicy.normalize("a\u00A0\u00A0b", 120),
        )
    }

    @Test
    fun nonPositiveMaxReturnsEmpty() {
        assertEquals("", ApprovalIntentPolicy.normalize("有内容", 0))
        assertEquals("", ApprovalIntentPolicy.normalize("有内容", -5))
    }

    @Test
    fun notificationLimitIsSmallerThanCardLimit() {
        // G1：通知正文预算比卡片小，意图单独限短，避免把命令原文挤出可见正文。
        assertTrue(
            ApprovalIntentPolicy.NOTIFICATION_MAX_CHARS < ApprovalIntentPolicy.CARD_MAX_CHARS,
        )
        val longIntent = "目".repeat(200)
        val card = ApprovalIntentPolicy.normalize(longIntent, ApprovalIntentPolicy.CARD_MAX_CHARS)
        val notification = ApprovalIntentPolicy.normalize(longIntent, ApprovalIntentPolicy.NOTIFICATION_MAX_CHARS)
        assertEquals(ApprovalIntentPolicy.CARD_MAX_CHARS, card.codePointCount(0, card.length))
        assertEquals(ApprovalIntentPolicy.NOTIFICATION_MAX_CHARS, notification.codePointCount(0, notification.length))
    }

    @Test
    fun shouldShowMatchesNonBlankNormalized() {
        assertTrue(ApprovalIntentPolicy.shouldShow("有目的"))
        assertFalse(ApprovalIntentPolicy.shouldShow(""))
        assertFalse(ApprovalIntentPolicy.shouldShow("   "))
    }
}
