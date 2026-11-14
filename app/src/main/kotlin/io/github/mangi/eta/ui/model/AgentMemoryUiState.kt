package io.github.mangi.eta.ui.model

import androidx.compose.runtime.Immutable
import io.github.mangi.eta.data.repository.AgentMemoryStore

@Immutable
data class AgentMemoryUiState(
    val enabled: Boolean = true,
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val draft: String = "",
    val savedContent: String = "",
    /** 最近一次从仓库读到的 revision；保存时用于 replaceAllIfRevision 乐观锁。 */
    val revision: String = "",
    val draftBytes: Int = 0,
    val maxBytes: Int = AgentMemoryStore.MAX_FILE_BYTES,
    val coreBudgetChars: Int = 8_000,
    /** 一期：按当前用户消息注入非核心记忆节。 */
    val relevanceEnabled: Boolean = true,
    /** 三期：运行结束后自动沉淀记忆。 */
    val distillationEnabled: Boolean = false,
    /** 四期：memory_get 使用向量语义检索。 */
    val semanticRecallEnabled: Boolean = false,
    /** 分割线以下按日期自动过期、到期移入 # 归档（阈值固定 90 天，默认关）。 */
    val autoExpireEnabled: Boolean = false,
    val notice: String? = null,
) {
    val hasUnsavedChanges: Boolean get() = draft != savedContent
    val canSave: Boolean
        get() = !isLoading && !isSaving && hasUnsavedChanges &&
            draftBytes <= maxBytes
}
