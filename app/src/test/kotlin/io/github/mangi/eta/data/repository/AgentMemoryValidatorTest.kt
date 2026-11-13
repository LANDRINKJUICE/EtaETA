package io.github.mangi.eta.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AgentMemoryValidatorTest {

    private fun doc(bodyBelowDivider: String): String =
        "用户顶部随便写 没格式 也没日期\n\n---\n\n" + bodyBelowDivider

    @Test
    fun pinnedTopIsExemptFromAllChecks() {
        // 顶部区无日期、无格式、任意内容都必须放行
        val content = "我的铁律：随便写，2026 不用日期\n\n---\n\n## 稳定规范\n<!-- eta:id=e00000000001;verified=2026-11-13 -->\n2026-11-13 结论先行的稳定规范\n"
        assertNull(AgentMemoryValidator.validate(content, null))
    }

    @Test
    fun noDividerLegacyFileIsExempt() {
        // 旧格式文件（无分割线）全部豁免，兼容历史数据、不追溯
        val legacy = "# 核心记忆\n老内容 没有日期\n## 关系\n家人"
        assertNull(AgentMemoryValidator.validate(legacy, null))
    }

    @Test
    fun emptyContentIsAllowed() {
        assertNull(AgentMemoryValidator.validate("", "something"))
    }

    @Test
    fun missingDateIsRejected() {
        val content = doc("## 项目\n这是没有日期的废话内容\n")
        val v = AgentMemoryValidator.validate(content, null)
        assertNotNull(v)
        assertEquals("MEMORY_DATE_REQUIRED", v!!.code)
    }

    @Test
    fun datedSectionPasses() {
        val content = doc("## 项目\n2026-11-13 结论：服务端口 8080\n")
        assertNull(AgentMemoryValidator.validate(content, null))
    }

    @Test
    fun duplicateHeadingsAreRejected() {
        val content = doc("## 项目\n2026-11-13 A\n##  项目\n2026-11-13 B\n")
        val v = AgentMemoryValidator.validate(content, null)
        assertNotNull(v)
        assertEquals("MEMORY_DUPLICATE_HEADING", v!!.code)
    }

    @Test
    fun sensitiveContentIsRejected() {
        val content = doc("## 凭证\n2026-11-13 token ghp_abcdefghijklmnopqrstuvwxyz123456\n")
        val v = AgentMemoryValidator.validate(content, null)
        assertNotNull(v)
        assertEquals("MEMORY_SENSITIVE_CONTENT", v!!.code)
    }

    @Test
    fun datedShortSectionPassesRegardlessOfWording() {
        // 有日期、够短，措辞不额外审查（避免过度卡正常编辑）
        val content = doc("## 状态\n2026-11-13 待补\n")
        assertNull(AgentMemoryValidator.validate(content, null))
    }

    @Test
    fun shortDatedSectionWithinLimitPasses() {
        val content = doc("## 项目\n2026-11-13 结论：端口 8080，详见交接文档。\n")
        assertNull(AgentMemoryValidator.validate(content, null))
    }

    @Test
    fun overlongSectionIsRejected() {
        val long = "2026-11-13 " + "废话".repeat(2000) // > 3500 字
        val content = doc("## 项目\n" + long + "\n")
        val v = AgentMemoryValidator.validate(content, null)
        assertNotNull(v)
        assertEquals("MEMORY_SECTION_TOO_LONG", v!!.code)
    }

    @Test
    fun archiveSectionIsExemptFromDateAndLength() {
        val content = doc("# 归档\n这里放过期旧内容，没有日期也行\n")
        assertNull(AgentMemoryValidator.validate(content, null))
    }

    @Test
    fun unchangedLegacySectionIsNotRetroactivelyRejected() {
        // 老数据：已有分割线、某节不带日期；保存时只要正文没变就放行
        val old = doc("## 项目\n老内容 没有日期\n")
        val unchanged = doc("## 项目\n老内容 没有日期\n")
        assertNull(AgentMemoryValidator.validate(unchanged, old))
    }

    @Test
    fun changedSectionStillNeedsDate() {
        val old = doc("## 项目\n老内容 没有日期\n")
        val changed = doc("## 项目\n改过的内容 仍没日期\n")
        val v = AgentMemoryValidator.validate(changed, old)
        assertNotNull(v)
        assertEquals("MEMORY_DATE_REQUIRED", v!!.code)
    }

    @Test
    fun newlyAddedSectionNeedsDate() {
        val old = doc("## 项目\n2026-11-13 老内容\n")
        val added = doc("## 项目\n2026-11-13 老内容\n## 新节\n新增却没日期\n")
        val v = AgentMemoryValidator.validate(added, old)
        assertNotNull(v)
        assertEquals("MEMORY_DATE_REQUIRED", v!!.code)
    }

    @Test
    fun metadataCountsAsDate() {
        // B3：upsert/append 自动生成的元数据带 created/verified，即满足「带日期」，不逼模型写正文日期
        val content = doc("## 项目\n<!-- eta:id=e00000000001;created=2026-11-13;verified=2026-11-13 -->\n端口 18788\n")
        assertNull(AgentMemoryValidator.validate(content, null))
    }

    @Test
    fun existingDuplicateHeadingsAreNotRetroactivelyRejected() {
        // B2：存量就有 2 个同名节，保存时只要不新增重名就放行（不追溯老数据）
        val old = doc("## 改进\n2026-11-13 A\n## 改进\n2026-11-13 B\n")
        assertNull(AgentMemoryValidator.validate(old, old))
    }

    @Test
    fun newlyCreatedDuplicateHeadingIsRejected() {
        // B2：本次新造重名才拒
        val old = doc("## 改进\n2026-11-13 A\n")
        val added = doc("## 改进\n2026-11-13 A\n## 改进\n2026-11-13 B\n")
        val v = AgentMemoryValidator.validate(added, old)
        assertNotNull(v)
        assertEquals("MEMORY_DUPLICATE_HEADING", v!!.code)
    }

    @Test
    fun sensitiveInPinnedTopIsStillRejected() {
        // M1：顶部区格式/日期豁免，但敏感串不豁免
        val content = "顶部写了 token ghp_abcdefghijklmnopqrstuvwxyz123456\n---\n## 项目\n2026-11-13 正常\n"
        val v = AgentMemoryValidator.validate(content, null)
        assertNotNull(v)
        assertEquals("MEMORY_SENSITIVE_CONTENT", v!!.code)
    }

    @Test
    fun existingSensitiveContentIsNotRetroactivelyLocked() {
        // 复审⑥：历史里已有敏感串、本次没新增（同一模式），放行——避免整份记忆被永久锁成只读。
        // 粒度按「模式」：某模式存量已存在即放行该模式；全新模式（如初次出现的私钥）仍拦。
        val old = doc("## 凭证\n2026-11-13 历史 token ghp_abcdefghijklmnopqrstuvwxyz123456\n")
        assertNull(AgentMemoryValidator.validate(old, old))
        // 一个从未出现过的新型敏感串（私钥头）仍要拦
        val withKey = doc("## 凭证\n2026-11-13 -----BEGIN RSA PRIVATE KEY-----\n")
        val v = AgentMemoryValidator.validate(withKey, old)
        assertNotNull(v)
        assertEquals("MEMORY_SENSITIVE_CONTENT", v!!.code)
    }

    @Test
    fun roleplayStoreSkipsSectionRulesButKeepsSensitiveScan() {
        // M7：角色记忆只扫敏感串，不吃分割线以下的日期/字数约束
        val noDate = doc("## 剧情\n角色写了一条 --- 场景分隔后的内容，没有日期\n")
        assertNull(AgentMemoryValidator.validate(noDate, null, enforceSectionRules = false))
        val withToken = doc("## 剧情\ntoken ghp_abcdefghijklmnopqrstuvwxyz123456\n")
        assertEquals(
            "MEMORY_SENSITIVE_CONTENT",
            AgentMemoryValidator.validate(withToken, null, enforceSectionRules = false)!!.code,
        )
    }
}
