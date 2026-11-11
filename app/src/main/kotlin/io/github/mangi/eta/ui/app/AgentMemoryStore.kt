package io.github.mangi.eta.ui.app

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.memory.AgentMemoryContextBuilder
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.data.repository.AgentMemoryRepository
import io.github.mangi.eta.data.repository.AgentMemorySnapshot
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import io.github.mangi.eta.ui.model.AgentMemoryUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 记忆页的读取、编辑与保存状态；与会话和 Agent run 无共享状态。 */
internal class AgentMemoryStore(
    context: Context,
    private val scope: CoroutineScope,
) {
    private val appContext = context.applicationContext

    private var hasLoadedOnce = false

    private data class Loaded(
        val snapshot: AgentMemorySnapshot,
        val enabled: Boolean,
        val coreBudgetChars: Int,
        val relevanceEnabled: Boolean,
        val distillationEnabled: Boolean,
        val semanticRecallEnabled: Boolean,
    )

    var memoryState by mutableStateOf(AgentMemoryUiState())
        private set

    fun refreshMemory() {
        if (!hasLoadedOnce) {
            memoryState = memoryState.copy(isLoading = true, notice = null)
        }
        scope.launch(Dispatchers.IO) {
            runCatching {
                val snapshot = AgentMemoryRepository.snapshot()
                val contextWindow = RuntimeConfigRepository.currentRuntimeConfig()?.contextWindow
                Loaded(
                    snapshot = snapshot,
                    enabled = AgentMemoryRepository.isEnabled(),
                    coreBudgetChars = AgentMemoryContextBuilder.coreBudgetChars(contextWindow),
                    relevanceEnabled = Prefs.isEnabled(Prefs.Keys.AGENT_MEMORY_RELEVANCE_ENABLED),
                    distillationEnabled = Prefs.isEnabled(Prefs.Keys.AGENT_MEMORY_DISTILL_ENABLED),
                    semanticRecallEnabled = Prefs.isEnabled(Prefs.Keys.AGENT_MEMORY_SEMANTIC_RECALL_ENABLED),
                )
            }.fold(
                onSuccess = { loaded ->
                    withContext(Dispatchers.Main) {
                        val keepDraft = hasLoadedOnce && memoryState.hasUnsavedChanges
                        val nextDraft = if (keepDraft) memoryState.draft else loaded.snapshot.content
                        val nextBytes = if (keepDraft) memoryState.draftBytes else loaded.snapshot.byteSize
                        hasLoadedOnce = true
                        memoryState = AgentMemoryUiState(
                            enabled = loaded.enabled,
                            isLoading = false,
                            draft = nextDraft,
                            savedContent = loaded.snapshot.content,
                            draftBytes = nextBytes,
                            coreBudgetChars = loaded.coreBudgetChars,
                            relevanceEnabled = loaded.relevanceEnabled,
                            distillationEnabled = loaded.distillationEnabled,
                            semanticRecallEnabled = loaded.semanticRecallEnabled,
                            notice = memoryState.notice,
                        )
                    }
                },
                onFailure = { throwable ->
                    AndroidAgentLogger.warnThrottled("agent_memory_ui_load_failed") {
                        "Agent memory UI load failed: type=${throwable.safeLogType()}"
                    }
                    withContext(Dispatchers.Main) {
                        memoryState = memoryState.copy(
                            isLoading = false,
                            notice = appContext.getString(R.string.state_ui_failed_to_read_memory_please_try_again_later_caeaa6),
                        )
                    }
                },
            )
        }
    }

    /** 一期相关性注入开关。 */
    fun setRelevanceEnabled(enabled: Boolean) {
        applyAgentSwitch(Prefs.Keys.AGENT_MEMORY_RELEVANCE_ENABLED, enabled) { state ->
            state.copy(relevanceEnabled = enabled)
        }
    }

    /** 三期自动沉淀开关（默认关闭，会多消耗一次模型回合）。 */
    fun setDistillationEnabled(enabled: Boolean) {
        applyAgentSwitch(Prefs.Keys.AGENT_MEMORY_DISTILL_ENABLED, enabled) { state ->
            state.copy(distillationEnabled = enabled)
        }
    }

    /** 四期语义检索开关（默认关闭；不可用时自动回退关键词检索）。 */
    fun setSemanticRecallEnabled(enabled: Boolean) {
        applyAgentSwitch(Prefs.Keys.AGENT_MEMORY_SEMANTIC_RECALL_ENABLED, enabled) { state ->
            state.copy(semanticRecallEnabled = enabled)
        }
    }

    private fun applyAgentSwitch(
        key: String,
        enabled: Boolean,
        apply: (AgentMemoryUiState) -> AgentMemoryUiState,
    ) {
        // commit 会同步落盘，放到 IO 线程；框架可用时一并回写远端，让 hook 侧读到一致的值。
        scope.launch(Dispatchers.IO) {
            val saved = Prefs.setAgentSwitch(key, enabled, EtaApp.serviceInstance)
            withContext(Dispatchers.Main) {
                memoryState = if (saved) {
                    apply(memoryState.copy(notice = null))
                } else {
                    memoryState.copy(
                        notice = appContext.getString(R.string.state_ui_memory_switch_failed_to_save_83b5d6),
                    )
                }
            }
        }
    }

    fun updateMemoryDraft(content: String) {
        memoryState = memoryState.copy(
            draft = content,
            draftBytes = content.toByteArray(Charsets.UTF_8).size,
            notice = null,
        )
    }

    fun setMemoryEnabled(enabled: Boolean) {
        scope.launch(Dispatchers.IO) {
            runCatching { AgentMemoryRepository.setEnabled(enabled) }
                .fold(
                    onSuccess = {
                        withContext(Dispatchers.Main) {
                            memoryState = memoryState.copy(enabled = enabled, notice = null)
                        }
                    },
                    onFailure = { throwable ->
                        AndroidAgentLogger.warnThrottled("agent_memory_toggle_failed") {
                            "Agent memory setting update failed: type=${throwable.safeLogType()}"
                        }
                        withContext(Dispatchers.Main) {
                            memoryState = memoryState.copy(notice = appContext.getString(R.string.state_ui_memory_switch_failed_to_save_83b5d6))
                        }
                    },
                )
        }
    }

    fun saveMemory() {
        if (!memoryState.canSave) return
        val target = memoryState.draft
        memoryState = memoryState.copy(isSaving = true, notice = null)
        scope.launch(Dispatchers.IO) {
            runCatching { AgentMemoryRepository.replaceAll(target) }
                .fold(
                    onSuccess = { snapshot ->
                        withContext(Dispatchers.Main) {
                            memoryState = memoryState.copy(
                                isSaving = false,
                                savedContent = snapshot.content,
                                draft = if (memoryState.draft == target) {
                                    snapshot.content
                                } else {
                                    memoryState.draft
                                },
                                draftBytes = memoryState.draft.toByteArray(Charsets.UTF_8).size,
                                notice = appContext.getString(R.string.state_ui_memory_saved_a2c61c),
                            )
                        }
                    },
                    onFailure = { throwable ->
                        AndroidAgentLogger.warnThrottled("agent_memory_ui_save_failed") {
                            "Agent memory UI save failed: type=${throwable.safeLogType()}"
                        }
                        withContext(Dispatchers.Main) {
                            memoryState = memoryState.copy(
                                isSaving = false,
                                notice = throwable.message ?: appContext.getString(R.string.state_ui_memory_save_failed_1f501e),
                            )
                        }
                    },
                )
        }
    }

    fun clearMemory() {
        if (memoryState.isSaving) return
        memoryState = memoryState.copy(isSaving = true, notice = null)
        scope.launch(Dispatchers.IO) {
            runCatching { AgentMemoryRepository.replaceAll("") }
                .fold(
                    onSuccess = {
                        withContext(Dispatchers.Main) {
                            memoryState = memoryState.copy(
                                isSaving = false,
                                draft = "",
                                savedContent = "",
                                draftBytes = 0,
                                notice = appContext.getString(R.string.state_ui_memory_cleared_b415bb),
                            )
                        }
                    },
                    onFailure = { throwable ->
                        AndroidAgentLogger.warnThrottled("agent_memory_ui_clear_failed") {
                            "Agent memory UI clear failed: type=${throwable.safeLogType()}"
                        }
                        withContext(Dispatchers.Main) {
                            memoryState = memoryState.copy(
                                isSaving = false,
                                notice = throwable.message ?: appContext.getString(R.string.state_ui_memory_clearing_failed_7f0aba),
                            )
                        }
                    },
                )
        }
    }

    fun dismissMemoryNotice() {
        memoryState = memoryState.copy(notice = null)
    }
}
