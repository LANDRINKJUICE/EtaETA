package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.data.db.EtaDatabase
import android.content.Context
import io.github.mangi.eta.agent.model.AgentModelClient
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentRunArchiveStoreTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        EtaDatabase.closeForTests()
        context.deleteDatabase("eta.db")
    }

    @Test
    fun archiveDropsSearchSuggestionsAndKeepsAnswer() {
        val answer = AgentEvent.AssistantBlockDelta(1, AgentEvent.AssistantBlockKind.TEXT, 0, 2, "回答")
        AgentRunArchiveStore.add(context, AgentRunArchiveStore.ArchivedRun(
            handoff = AgentRuntimeWire.EntryHandoff("search", "external_test", "fixture"),
            events = listOf(answer, AgentEvent.SearchSuggestions(1, 0, "<div>不应保存</div>")),
            result = AgentRuntimeWire.RunResult("search", true, "回答"),
            createdAt = 1L,
        ))
        val restored = AgentRunArchiveStore.list(context).single()
        assertEquals(listOf(answer), restored.events)
        assertEquals("回答", restored.result.content)
    }

    @Test
    fun onlyRunsWithAnExternalArchiveConsumerAllocateAnEventBuffer() {
        assertNull(AgentRunArchiveStore.createEventBuffer(null))
        assertNull(AgentRunArchiveStore.createEventBuffer(
            AgentRuntimeWire.EntryHandoff(
                id = "ui-run",
                source = AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE,
                payload = AgentUiHandoffPayload(conversationId = "conversation").toJson(),
            )
        ))
        assertNull(AgentRunArchiveStore.createEventBuffer(
            AgentRuntimeWire.EntryHandoff("invalid-run", "external_test", "invalid payload")
        ))
        assertNotNull(AgentRunArchiveStore.createEventBuffer(
            AgentRuntimeWire.EntryHandoff(
                id = "external-run",
                source = "external_test",
                payload = AgentExternalArchivePayload("请求", "session", "外部入口").toJson(),
            )
        ))
    }

    @Test
    fun saveAndLoadPreservesHandoffEventsAndResult() {
        val createdAt = System.currentTimeMillis()
        val archivedRun = AgentRunArchiveStore.ArchivedRun(
            handoff = AgentRuntimeWire.EntryHandoff(
                id = "run-1",
                source = "external_test",
                payload = AgentExternalArchivePayload(
                    userText = "查一下系统状态",
                    conversationKey = "session-1",
                    title = "外部入口",
                ).toJson(),
            ),
            events = listOf(
                AgentEvent.AssistantBlockDelta(
                    round = 1,
                    kind = AgentEvent.AssistantBlockKind.THINKING,
                    index = 0,
                    deltaChars = 2,
                    delta = "先",
                ),
                AgentEvent.AssistantBlockDelta(
                    round = 1,
                    kind = AgentEvent.AssistantBlockKind.THINKING,
                    index = 0,
                    deltaChars = 2,
                    delta = "看",
                ),
                AgentEvent.ToolStarted(
                    round = 1,
                    toolCallId = "call-1",
                    name = "run_command",
                    argsPreview = "执行命令 · Android · root",
                    command = "uptime",
                ),
                AgentEvent.ToolFinished(
                    round = 1,
                    toolCallId = "call-1",
                    name = "run_command",
                    resultSummary = "ok=true, chars=42",
                    imageCount = 0,
                    imageBytes = 0,
                ),
                AgentEvent.RunFinished(
                    round = 1,
                    contentChars = 12,
                ),
            ),
            result = AgentRuntimeWire.RunResult(
                runId = "run-1",
                ok = true,
                content = "系统状态正常",
                reasoningContent = "先看系统状态",
                transcript = listOf(
                    AgentModelClient.ConversationMessage(
                        role = "assistant",
                        content = "系统状态正常",
                    )
                ),
            ),
            createdAt = createdAt,
            userImagePreviews = listOf(
                "data:image/png;base64,cHJldmlldw==",
            ),
        )

        AgentRunArchiveStore.add(context, archivedRun)

        val restored = AgentRunArchiveStore.list(context).single()

        assertEquals(archivedRun.handoff, restored.handoff)
        assertEquals(archivedRun.result, restored.result)
        assertEquals(archivedRun.createdAt, restored.createdAt)
        assertEquals(archivedRun.userImagePreviews, restored.userImagePreviews)
        assertEquals(
            AgentEvent.AssistantBlockDelta(
                round = 1,
                kind = AgentEvent.AssistantBlockKind.THINKING,
                index = 0,
                deltaChars = 4,
                delta = "先看",
            ),
            restored.events.first()
        )
        assertEquals(4, restored.events.size)
        assertEquals(archivedRun.events[2], restored.events[1])
    }

    /**
     * 「归档落库 -> UI 订阅桥」的数据侧契约：外部归档 run 一旦写进归档表，
     * [AgentRunArchiveStore.archiveChangeFlow] 的首次发射里就能看到它的主键，
     * 且 [AgentRunArchiveStore.list] 能同时取回完整内容与派生会话所需字段。
     * AgentAppState 常驻订阅这个 Flow，据此实时把 run 导入会话列表，不必回前台或点通知。
     */
    @Test
    fun archiveChangeFlowSurfacesNewlyArchivedRun() {
        val archived = AgentRunArchiveStore.ArchivedRun(
            handoff = AgentRuntimeWire.EntryHandoff(
                id = "job-wakeup-run-1",
                source = AgentRuntimeWire.ETA_JOB_WAKEUP_HANDOFF_SOURCE,
                payload = AgentExternalArchivePayload(
                    userText = "后台任务失败，自动接手处理",
                    conversationKey = "job-task-1",
                    title = "后台任务自动处理",
                ).toJson(),
            ),
            events = emptyList(),
            result = AgentRuntimeWire.RunResult("job-wakeup-run-1", true, "已处理完成"),
            createdAt = 42L,
        )
        AgentRunArchiveStore.add(context, archived)

        val ids = runBlocking {
            withTimeout(5_000L) { AgentRunArchiveStore.archiveChangeFlow(context).first() }
        }
        assertTrue("新增归档应出现在变更 Flow 里", ids.contains("job-wakeup-run-1"))

        val payload = AgentExternalArchivePayload.from(
            AgentRunArchiveStore.list(context).single().handoff.payload,
        )
        assertNotNull(payload)
        assertEquals("job-task-1", payload!!.conversationKey)
        assertEquals("后台任务自动处理", payload.title)

        // 导入成功后会删归档行；删行要能触发 Flow 再发射且这次为空，
        // 这正是 UI 订阅不会重复导入该 run 的闭环契约。
        AgentRunArchiveStore.remove(context, "job-wakeup-run-1")
        val afterRemove = runBlocking {
            withTimeout(5_000L) { AgentRunArchiveStore.archiveChangeFlow(context).first() }
        }
        assertTrue("删行后变更 Flow 应为空", afterRemove.isEmpty())
    }

    @Test
    fun removeDeletesByRunIdOrHandoffId() {
        val createdAt = System.currentTimeMillis()
        AgentRunArchiveStore.add(
            context,
            AgentRunArchiveStore.ArchivedRun(
                handoff = AgentRuntimeWire.EntryHandoff(
                    id = "handoff-1",
                    source = "external_test",
                    payload = AgentExternalArchivePayload(
                        userText = "查一下系统状态",
                        conversationKey = "session-1",
                        title = "外部入口",
                    ).toJson(),
                ),
                events = emptyList(),
                result = AgentRuntimeWire.RunResult(
                    runId = "run-1",
                    ok = true,
                    content = "done",
                ),
                createdAt = createdAt,
            )
        )

        AgentRunArchiveStore.remove(context, "run-1")

        assertEquals(emptyList<AgentRunArchiveStore.ArchivedRun>(), AgentRunArchiveStore.list(context))
    }
}
