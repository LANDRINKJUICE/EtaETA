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
import io.github.mangi.eta.data.repository.AgentMemoryWriteResult
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
        val autoExpireEnabled: Boolean,
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
                    autoExpireEnabled = Prefs.isEnabled(Prefs.Keys.AGENT_MEMORY_AUTO_EXPIRE_ENABLED),
                )
            }.fold(
                onSuccess = { loaded ->
                    withContext(Dispatchers.Main) {
                        val keepDraft = hasLoadedOnce && memoryState.hasUnsavedChanges
                        val nextDraft = if (keepDraft) memoryState.draft else loaded.snapshot.content
                        val nextBytes = if (keepDraft) memoryState.draftBytes else loaded.snapshot.byteSize
                        // M4：保留草稿时 revision 也要保留草稿基线，否则乐观锁失效、会静默覆盖后台写入。
                        val nextRevision = if (keepDraft) memoryState.revision else loaded.snapshot.revision
                        hasLoadedOnce = true
                        memoryState = AgentMemoryUiState(
                            enabled = loaded.enabled,
                            isLoading = false,
                            draft = nextDraft,
                            savedContent = loaded.snapshot.content,
                            revision = nextRevision,
                            draftBytes = nextBytes,
                            coreBudgetChars = loaded.coreBudgetChars,
                            relevanceEnabled = loaded.relevanceEnabled,
                            distillationEnabled = loaded.distillationEnabled,
                            semanticRecallEnabled = loaded.semanticRecallEnabled,
                            autoExpireEnabled = loaded.autoExpireEnabled,
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

    /** 分割线以下按日期自动过期（默认关闭；开启时立即整理一次 + 启动时整理）。 */
    fun setAutoExpireEnabled(enabled: Boolean) {
        applyAgentSwitch(Prefs.Keys.AGENT_MEMORY_AUTO_EXPIRE_ENABLED, enabled) { state ->
            state.copy(autoExpireEnabled = enabled)
        }
        // M5：打开开关那一刻就整理，不等下次冷启动；整理后刷新页面让用户看到结果。
        if (enabled) {
            scope.launch(Dispatchers.IO) {
                runCatching {
                    AgentMemoryRepository.archiveExpiredIfEnabled(enabled = true)
                }.onFailure { throwable ->
                    AndroidAgentLogger.warnThrottled("agent_memory_expiry_failed") {
                        "Memory auto-expire failed: type=${throwable.safeLogType()}"
                    }
                }
                withContext(Dispatchers.Main) { refreshMemory() }
            }
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
        val baseRevision = memoryState.revision
        memoryState = memoryState.copy(isSaving = true, notice = null)
        scope.launch(Dispatchers.IO) {
            // 用乐观锁保存：避免用户在编辑器停留期间、后台 run 写入的新记忆被静默覆盖。
            runCatching { AgentMemoryRepository.replaceAllIfRevision(target, baseRevision) }
                .fold(
                    onSuccess = { result ->
                        withContext(Dispatchers.Main) {
                            when (result) {
                                is AgentMemoryWriteResult.Success -> {
                                    val snapshot = result.snapshot
                                    memoryState = memoryState.copy(
                                        isSaving = false,
                                        savedContent = snapshot.content,
                                        revision = snapshot.revision,
                                        draft = if (memoryState.draft == target) {
                                            snapshot.content
                                        } else {
                                            memoryState.draft
                                        },
                                        draftBytes = memoryState.draft.toByteArray(Charsets.UTF_8).size,
                                        notice = appContext.getString(R.string.state_ui_memory_saved_a2c61c),
                                    )
                                }
                                is AgentMemoryWriteResult.Conflict -> {
                                    // 以冲突返回的最新快照刷新基线与已存内容，用户可对比后重存，不必退出重进。
                                    memoryState = memoryState.copy(
                                        isSaving = false,
                                        savedContent = result.snapshot.content,
                                        revision = result.snapshot.revision,
                                        notice = appContext.getString(R.string.memory_save_conflict),
                                    )
                                }
                            }
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
                    onSuccess = { snapshot ->
                        withContext(Dispatchers.Main) {
                            memoryState = memoryState.copy(
                                isSaving = false,
                                draft = "",
                                savedContent = "",
                                revision = snapshot.revision, // B6：回写清空后的 revision，避免「清空→保存」必冲突
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
