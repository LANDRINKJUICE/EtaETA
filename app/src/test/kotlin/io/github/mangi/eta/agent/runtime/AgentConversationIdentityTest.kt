package io.github.mangi.eta.agent.runtime

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class AgentConversationIdentityTest {
    @Test
    fun legacyAndStructuredUiHandoffsUseTheSameConversation() {
        val legacy = AgentRuntimeWire.EntryHandoff("run-a", AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE, "conv-a")
        val structured = legacy.copy(payload = AgentUiHandoffPayload("conv-a").toJson())
        assertEquals("conv-a", AgentConversationIdentity.fromHandoff(legacy, "run-a"))
        assertEquals("conv-a", AgentConversationIdentity.fromHandoff(structured, "run-b"))
    }

    @Test
    fun externalRunsKeepThePersistedArchiveIdentity() {
        val payload = AgentExternalArchivePayload("请求", "dialog-42", "会话").toJson()
        val handoff = AgentRuntimeWire.EntryHandoff("run-a", "xiaoai", payload)
        assertEquals("archive-777ba66ea9576d0e68c49b39", AgentConversationIdentity.fromHandoff(handoff, "run-a"))
        assertEquals(AgentConversationIdentity.fromHandoff(handoff, "run-a"),
            AgentConversationIdentity.fromHandoff(handoff.copy(id = "run-b"), "run-b"))
        assertNotEquals(AgentConversationIdentity.fromHandoff(handoff, "run-a"),
            AgentConversationIdentity.fromHandoff(handoff.copy(source = "breeno"), "run-a"))
    }

    @Test
    fun requestsWithoutAConversationRemainRunScoped() {
        assertEquals("run:run-a", AgentConversationIdentity.fromHandoff(null, "run-a"))
        assertNotEquals(AgentConversationIdentity.fromHandoff(null, "run-a"),
            AgentConversationIdentity.fromHandoff(null, "run-b"))
    }

    @Test
    fun voiceArchiveKeepsItsPublishedPrefixAndDigest() {
        assertEquals("assistant-fa2945f5951c8f096f360631",
            AgentConversationIdentity.archiveConversationId(AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE, "voice-conversation"))
    }

    // 后台任务唤醒轮改用「真实可回复 UI 会话」：这三组守住"不退化成只读归档"的根因。
    @Test
    fun jobWakeupConversationIsStableAndReplyable() {
        val a = AgentConversationIdentity.jobWakeupConversationId("job-42")
        // 同一任务链（同一 conversationKey）稳定复用同一会话 → 多次唤醒 / 用户追问进同一处。
        assertEquals(a, AgentConversationIdentity.jobWakeupConversationId("job-42"))
        // 前缀是普通 conv-，isUiConversation 必须为 true（否则发消息被只读拦截、弹新空会话）。
        assertTrue(a.startsWith(AgentConversationIdentity.JOB_WAKEUP_CONVERSATION_PREFIX))
        assertTrue("唤醒会话必须是可回复的 UI 会话: $a", AgentConversationIdentity.isUiConversation(a))
        // 绝不能落进归档/语音/回退三种只读前缀（否则 isReadOnlyExternalArchiveConversation 命中）。
        assertTrue(!a.startsWith(AgentConversationIdentity.EXTERNAL_ARCHIVE_PREFIX))
        assertTrue(!a.startsWith(AgentConversationIdentity.VOICE_ARCHIVE_PREFIX))
        assertTrue(!a.startsWith(AgentConversationIdentity.RUN_FALLBACK_PREFIX))
    }

    @Test
    fun jobWakeupConversationsAreIsolatedPerTaskChain() {
        assertNotEquals(
            AgentConversationIdentity.jobWakeupConversationId("job-1"),
            AgentConversationIdentity.jobWakeupConversationId("job-2"),
        )
    }

    @Test
    fun jobWakeupHandoffResolvesToItsUiConversation() {
        // 唤醒轮的 AGENT_UI handoff → fromHandoff 必须解析回那个可回复会话 id，
        // 这样 RuntimeRunRegistry.conversationId / ActiveRunSnapshot 才能被 UI preparingRuns 认领。
        val id = AgentConversationIdentity.jobWakeupConversationId("job-77")
        val handoff = AgentRuntimeWire.EntryHandoff(
            "run-x", AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE,
            AgentUiHandoffPayload(id).toJson(),
        )
        assertEquals(id, AgentConversationIdentity.fromHandoff(handoff, "run-x"))
        assertTrue(AgentConversationIdentity.isUiConversation(
            AgentConversationIdentity.fromHandoff(handoff, "run-x")))
    }

    @Test
    fun jobWakeupEntryTagSurvivesSerialization() {
        // 唤醒轮以 AGENT_UI handoff 启动但保留"唤醒轮"语义（模型配置/防循环/wakeupOrigin
        // 三处判定改看它）。entryTag 必须 toJson→from 往返不丢，且普通 UI 会话不被误判。
        val tagged = AgentUiHandoffPayload(
            conversationId = "conv-wakeup-abc",
            entryTag = AgentUiHandoffPayload.ENTRY_TAG_JOB_WAKEUP,
        )
        val restored = AgentUiHandoffPayload.from(tagged.toJson())
        assertEquals(AgentUiHandoffPayload.ENTRY_TAG_JOB_WAKEUP, restored.entryTag)
        assertEquals("conv-wakeup-abc", restored.conversationId)

        val handoff = AgentRuntimeWire.EntryHandoff(
            "run-x", AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE, tagged.toJson(),
        )
        assertTrue("唤醒轮必须被 isJobWakeup 识别", AgentUiHandoffPayload.isJobWakeup(handoff))

        val plain = AgentRuntimeWire.EntryHandoff(
            "run-y", AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE,
            AgentUiHandoffPayload("conv-normal").toJson(),
        )
        assertTrue("普通 UI 会话不能被误判为唤醒轮", !AgentUiHandoffPayload.isJobWakeup(plain))
        // 旧版 payload（无 entryTag 字段）反序列化后同样不被误判，向后兼容。
        val legacy = AgentRuntimeWire.EntryHandoff(
            "run-z", AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE,
            "{\"type\":\"agent_ui_handoff\",\"version\":2,\"conversationId\":\"conv-old\",\"supplements\":[]}",
        )
        assertTrue(!AgentUiHandoffPayload.isJobWakeup(legacy))
        assertEquals("conv-old", AgentUiHandoffPayload.from(legacy.payload).conversationId)
    }
}
