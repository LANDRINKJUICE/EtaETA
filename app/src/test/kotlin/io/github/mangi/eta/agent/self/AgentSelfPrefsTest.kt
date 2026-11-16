package io.github.mangi.eta.agent.self

import io.github.mangi.eta.agent.terminal.LinuxDistribution
import io.github.mangi.eta.agent.terminal.LinuxExecutionBackend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `eta_prefs_*` 的纯逻辑骨架：白名单、类型校验、凭据脱敏。
 *
 * 这些规则一旦放松就会变成"Agent 可以往设置里写任意值"的口子，所以钉在纯 JVM 单测里，
 * 不依赖 Robolectric / DataStore：能跑得足够快，也不受设备存储状态影响。
 */
class AgentSelfPrefsTest {

    // ── 白名单 ──────────────────────────────────────────────────────────────

    @Test
    fun `unknown key is not managed`() {
        assertNull(AgentSelfPrefsSpec.find("web.not_registered"))
        assertNull(AgentSelfPrefsSpec.find(""))
        assertNull(AgentSelfPrefsSpec.find(null))
        assertNotNull(AgentSelfPrefsSpec.find("web.custom_search_url"))
    }

    @Test
    fun `key lookup trims whitespace but rejects case changes`() {
        assertEquals("web.search_backend", AgentSelfPrefsSpec.find("  web.search_backend  ")?.key)
        // 键名区分大小写：放过大小写变体会让"同一设置有两个入口"的隐性分叉。
        assertNull(AgentSelfPrefsSpec.find("WEB.SEARCH_BACKEND"))
    }

    @Test
    fun `every registered key is unique and belongs to exactly one domain`() {
        val keys = AgentSelfPrefsSpec.all.map { it.key }
        assertEquals(keys.size, keys.distinct().size)
        val domainTotal = PrefDomain.entries.sumOf { AgentSelfPrefsSpec.byDomain(it).size }
        assertEquals(keys.size, domainTotal)
        assertTrue(PrefDomain.entries.all { AgentSelfPrefsSpec.byDomain(it).isNotEmpty() })
    }

    @Test
    fun `secret keys always live in the encrypted credential store`() {
        AgentSelfPrefsSpec.all.forEach { spec ->
            val credentialTarget = spec.target is PrefTarget.WebCredential ||
                spec.target is PrefTarget.SpeechCredential
            assertEquals("凭据类型与存储位置必须一一对应：${spec.key}", spec.secret, credentialTarget)
        }
    }

    @Test
    fun `enum specs declare options and every default-ish value is legal`() {
        AgentSelfPrefsSpec.all.filter { it.kind == PrefKind.ENUM }.forEach { spec ->
            assertTrue("枚举键必须声明可选值：${spec.key}", spec.options.isNotEmpty())
        }
        AgentSelfPrefsSpec.all.filter { it.kind == PrefKind.INTEGER && it.options.isNotEmpty() }.forEach { spec ->
            spec.options.forEach { option ->
                assertTrue(
                    "${spec.key} 的离散取值必须是整数：$option",
                    (AgentSelfPrefsRules.validate(spec, option) as PrefCheck.Accepted).value.toIntOrNull() != null,
                )
            }
        }
    }

    @Test
    fun `domain ids are stable and map to the documented settings pages`() {
        assertEquals(listOf("web", "speech", "linux"), PrefDomain.entries.map { it.id })
        assertTrue(AgentSelfPrefsSpec.byDomain(PrefDomain.WEB).any { it.key == "web.custom_search_url" })
        assertTrue(AgentSelfPrefsSpec.byDomain(PrefDomain.SPEECH).any { it.key == "speech.tts_provider" })
        assertTrue(AgentSelfPrefsSpec.byDomain(PrefDomain.LINUX).any { it.key == "linux.distribution" })
    }

    // ── 脱敏 ────────────────────────────────────────────────────────────────

    @Test
    fun `mask keeps four leading and four trailing characters for long secrets`() {
        val key = "sk-1234567890abcdefghij"
        assertEquals("sk-1******ghij", AgentSelfPrefsRules.mask(key))
        // 中间固定 6 个星号：不因为长度不同而泄露真实长度。
        assertEquals(14, AgentSelfPrefsRules.mask(key).length)
        assertEquals(
            AgentSelfPrefsRules.mask(key).length,
            AgentSelfPrefsRules.mask("sk-1234567890abcdefghijklmnop").length,
        )
    }

    @Test
    fun `mask hides short secrets entirely instead of leaking most of them`() {
        assertEquals("****", AgentSelfPrefsRules.mask("short"))
        assertEquals("****", AgentSelfPrefsRules.mask("123456789012345"))
        assertEquals("", AgentSelfPrefsRules.mask(""))
        assertEquals("", AgentSelfPrefsRules.mask(null))
        // 16 位是分界：刚好 16 位才做前 4 后 4。
        assertEquals("1234******cdef", AgentSelfPrefsRules.mask("1234567890abcdef"))
    }

    @Test
    fun `masked value never contains the middle of the original secret`() {
        val secret = "ABCD-middle-secret-WXYZ"
        val masked = AgentSelfPrefsRules.mask(secret)
        assertTrue(masked.startsWith("ABCD"))
        assertTrue(masked.endsWith("WXYZ"))
        assertTrue("脱敏结果不能包含原始中段", !masked.contains("middle"))
    }

    // ── 类型校验 ────────────────────────────────────────────────────────────

    private fun spec(key: String): PrefSpec = requireNotNull(AgentSelfPrefsSpec.find(key))

    private fun accepted(key: String, raw: String): String =
        when (val check = AgentSelfPrefsRules.validate(spec(key), raw)) {
            is PrefCheck.Accepted -> check.value
            is PrefCheck.Rejected -> throw AssertionError("期望接受 $key=$raw，实际拒绝：${check.message}")
        }

    private fun rejected(key: String, raw: String): PrefCheck.Rejected =
        when (val check = AgentSelfPrefsRules.validate(spec(key), raw)) {
            is PrefCheck.Accepted -> throw AssertionError("期望拒绝 $key=$raw，实际归一化为：${check.value}")
            is PrefCheck.Rejected -> check
        }

    @Test
    fun `boolean accepts human spellings and normalizes them`() {
        listOf("true", "TRUE", "1", "on", "yes").forEach { raw ->
            assertEquals("true", accepted("speech.auto_speak", raw))
        }
        listOf("false", "FALSE", "0", "off", "no").forEach { raw ->
            assertEquals("false", accepted("speech.auto_speak", raw))
        }
        val error = rejected("speech.auto_speak", "maybe")
        assertEquals(AgentSelfPrefsRules.CODE_VALUE_INVALID, error.code)
        assertTrue(error.message.contains("speech.auto_speak"))
    }

    @Test
    fun `enum is case insensitive and reports the allowed values`() {
        assertEquals("CUSTOM", accepted("web.search_backend", "custom"))
        assertEquals("DEEPSEEK", accepted("web.search_backend", " DeepSeek "))
        assertEquals("debian", accepted("linux.distribution", "DEBIAN"))
        val error = rejected("web.search_backend", "bing")
        assertEquals(AgentSelfPrefsRules.CODE_VALUE_INVALID, error.code)
        assertTrue(error.message.contains("BUILTIN"))
        // 枚举不许被空串清空：真正的"关闭"是 NONE / BUILTIN 这类显式取值。
        assertEquals(AgentSelfPrefsRules.CODE_VALUE_INVALID, rejected("speech.tts_provider", "").code)
    }

    @Test
    fun `integer respects discrete options and bounds`() {
        assertEquals("2000", accepted("speech.auto_send_silence_ms", "2000"))
        assertEquals("0", accepted("speech.auto_send_silence_ms", " 0 "))
        assertEquals(AgentSelfPrefsRules.CODE_VALUE_INVALID, rejected("speech.auto_send_silence_ms", "1234").code)
        assertEquals(AgentSelfPrefsRules.CODE_VALUE_INVALID, rejected("speech.auto_send_silence_ms", "2s").code)
    }

    @Test
    fun `decimal respects min and max`() {
        assertEquals("1.5", accepted("speech.minimax_tts_speed", "1.5"))
        assertEquals("2", accepted("speech.minimax_tts_speed", "2.0"))
        assertEquals(AgentSelfPrefsRules.CODE_VALUE_INVALID, rejected("speech.minimax_tts_speed", "0.1").code)
        assertEquals(AgentSelfPrefsRules.CODE_VALUE_INVALID, rejected("speech.minimax_tts_speed", "3").code)
        assertEquals(AgentSelfPrefsRules.CODE_VALUE_INVALID, rejected("speech.minimax_tts_speed", "fast").code)
    }

    @Test
    fun `url requires http or https and tolerates templates`() {
        assertEquals("http://127.0.0.1:8899/search", accepted("web.custom_search_url", "http://127.0.0.1:8899/search"))
        assertEquals(
            "http://127.0.0.1:8899/search?q={{query}}&n={{count}}",
            accepted("web.custom_search_url", "http://127.0.0.1:8899/search?q={{query}}&n={{count}}"),
        )
        // 空串是合法状态：表示"未配置自定义接口"。
        assertEquals("", accepted("web.custom_search_url", "   "))
        assertEquals(
            AgentSelfPrefsRules.CODE_VALUE_INVALID,
            rejected("web.custom_search_url", "ftp://example.com/search").code,
        )
        assertEquals(
            AgentSelfPrefsRules.CODE_VALUE_INVALID,
            rejected("web.custom_search_url", "127.0.0.1:8899/search").code,
        )
        assertEquals(
            AgentSelfPrefsRules.CODE_VALUE_INVALID,
            rejected("web.custom_search_url", "https://exa mple.com").code,
        )
        assertEquals(
            AgentSelfPrefsRules.CODE_VALUE_INVALID,
            rejected("web.custom_search_url", "https://").code,
        )
    }

    @Test
    fun `secret accepts empty to clear and never constrains format`() {
        assertEquals("", accepted("web.custom_search_api_key", ""))
        assertEquals("sk-anything-at-all", accepted("web.custom_search_api_key", " sk-anything-at-all "))
        assertTrue(AgentSelfPrefsSpec.find("web.custom_search_api_key")!!.secret)
        assertTrue(AgentSelfPrefsSpec.find("speech.oss_access_key_secret")!!.secret)
    }


    // ── 运行时对齐的校验（M3）──────────────────────────────────────────────

    @Test
    fun `speech base urls require https without credentials or query`() {
        assertEquals(
            "https://openspeech.bytedance.com",
            accepted("speech.doubao_tts_base_url", "https://openspeech.bytedance.com"),
        )
        // 与 SpeechProtocol.speechBaseUrl 完全一致的拒绝面。
        listOf(
            "http://openspeech.bytedance.com",
            "https://user:pass@openspeech.bytedance.com",
            "https://openspeech.bytedance.com?a=b",
            "https://openspeech.bytedance.com#frag",
            "",
        ).forEach { raw ->
            val error = rejected("speech.doubao_tts_base_url", raw)
            assertEquals("应拒绝：$raw", AgentSelfPrefsRules.CODE_VALUE_INVALID, error.code)
        }
        // 通义留空 = 按区域默认，这是设置页的既有语义，不能因为收紧校验而丢掉。
        assertEquals("", accepted("speech.qwen_asr_base_url", " "))
        assertEquals(
            "https://dashscope-intl.aliyuncs.com",
            accepted("speech.qwen_asr_base_url", "https://dashscope-intl.aliyuncs.com"),
        )
        assertEquals(
            AgentSelfPrefsRules.CODE_VALUE_INVALID,
            rejected("speech.qwen_asr_base_url", "http://dashscope.aliyuncs.com").code,
        )
        assertEquals(
            AgentSelfPrefsRules.CODE_VALUE_INVALID,
            rejected("speech.oss_endpoint", "").code,
        )
    }

    @Test
    fun `oss bucket follows the runtime bucket rule`() {
        assertEquals("eta-speech-bucket", accepted("speech.oss_bucket", "eta-speech-bucket"))
        assertEquals("", accepted("speech.oss_bucket", ""))
        listOf("ab", "Eta-Bucket", "-bucket-", "bucket_1", "a".repeat(64)).forEach { raw ->
            val error = rejected("speech.oss_bucket", raw)
            assertEquals("应拒绝 bucket：$raw", AgentSelfPrefsRules.CODE_VALUE_INVALID, error.code)
        }
    }

    @Test
    fun `oss prefix rejects parent directory segments`() {
        assertEquals("eta-speech/", accepted("speech.oss_prefix", "eta-speech/"))
        assertEquals("", accepted("speech.oss_prefix", ""))
        assertEquals(AgentSelfPrefsRules.CODE_VALUE_INVALID, rejected("speech.oss_prefix", "../escape").code)
        assertEquals(AgentSelfPrefsRules.CODE_VALUE_INVALID, rejected("speech.oss_prefix", "a/./b").code)
    }

    @Test
    fun `gemini style has a length ceiling`() {
        assertEquals("平静地朗读", accepted("speech.gemini_tts_style", "平静地朗读"))
        assertEquals(
            AgentSelfPrefsRules.CODE_VALUE_INVALID,
            rejected("speech.gemini_tts_style", "x".repeat(501)).code,
        )
        assertEquals("x".repeat(500), accepted("speech.gemini_tts_style", "x".repeat(500)))
    }

    // ── 前置条件与确认（M2 / M3）──────────────────────────────────────────

    @Test
    fun `chroot backend requires root authorization`() {
        val backend = spec("linux.backend_debian")
        assertNull(AgentSelfPrefsRules.precondition(backend, "chroot", rootAvailable = true))
        assertNull(AgentSelfPrefsRules.precondition(backend, "proot", rootAvailable = false))
        val rejectedBackend = AgentSelfPrefsRules.precondition(backend, "chroot", rootAvailable = false)
            as? PrefCheck.Rejected
            ?: throw AssertionError("未授权 Root 时切 chroot 必须被前置条件拦下")
        assertEquals(AgentSelfPrefsRules.CODE_PRECONDITION_FAILED, rejectedBackend.code)
        assertTrue(rejectedBackend.message.contains("Root"))
        // 非后端键不受这条约束。
        assertNull(AgentSelfPrefsRules.precondition(spec("linux.distribution"), "chroot", rootAvailable = false))
        assertNull(AgentSelfPrefsRules.precondition(spec("speech.auto_speak"), "true", rootAvailable = false))
    }

    @Test
    fun `only address credential and environment keys require confirmation`() {
        listOf(
            "web.custom_search_url",
            "web.deepseek_api_key",
            "speech.oss_access_key_secret",
            "speech.minimax_tts_base_url",
            "speech.oss_endpoint",
            "linux.distribution",
            "linux.backend_alpine",
        ).forEach { key ->
            assertNotNull("$key 应需要确认", AgentSelfPrefsRules.confirmationReason(spec(key)))
        }
        listOf(
            "speech.auto_speak",
            "speech.tts_provider",
            "speech.qwen_voice",
            "web.search_backend",
            "speech.gemini_tts_style",
            "speech.auto_send_silence_ms",
        ).forEach { key ->
            assertNull("$key 不该需要确认", AgentSelfPrefsRules.confirmationReason(spec(key)))
        }
    }

    // ── Linux 后端键由发行版生成（m5）────────────────────────────────────

    @Test
    fun `every linux distribution has exactly one backend key`() {
        val fromRegistry = AgentSelfPrefsSpec.all
            .mapNotNull { (it.target as? PrefTarget.LinuxBackend)?.distribution }
        assertEquals(LinuxDistribution.entries.map { it.wireName }.toSet(), fromRegistry.toSet())
        assertEquals(LinuxDistribution.entries.size, fromRegistry.size)
        LinuxDistribution.entries.forEach { distribution ->
            val spec = AgentSelfPrefsSpec.find("linux.backend_${distribution.wireName}")
                ?: throw AssertionError("缺少后端键：${distribution.wireName}")
            assertEquals(PrefKind.ENUM, spec.kind)
            assertEquals(
                LinuxExecutionBackend.entries.map { it.wireName },
                spec.options,
            )
        }
        // 发行版键本身也必须在登记表里（否则分发选择只能靠设置页）。
        LinuxDistribution.entries.forEach { distribution ->
            assertEquals(
                "应能设 ${distribution.wireName}",
                distribution.wireName,
                accepted("linux.distribution", distribution.wireName.uppercase()),
            )
        }
    }

    @Test
    fun `text and template kinds stay free form`() {
        assertEquals("", accepted("web.custom_search_headers", ""))
        assertEquals("X-Api-Key: abc", accepted("web.custom_search_headers", "  X-Api-Key: abc  "))
        assertEquals(
            """{"query": {{query_json}}, "count": {{count}}}""",
            accepted("web.custom_search_body_template", """{"query": {{query_json}}, "count": {{count}}}"""),
        )
    }
}
