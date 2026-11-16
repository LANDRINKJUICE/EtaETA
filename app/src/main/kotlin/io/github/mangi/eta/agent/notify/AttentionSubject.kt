package io.github.mangi.eta.agent.notify

/**
 * 通知所归属「会话身份」的**唯一分类源**（纯 JVM、零 android.*，进纯 JVM 基线）。
 *
 * 2026-11-15 从根重构：过去「这条 run 是不是无人值守 / 是不是唤醒轮」由各发起点手填
 * `unattended: Boolean`、`isWakeupConversation` 又另写一份 `"conv-wakeup-"` 字面量，
 * 而 [io.github.mangi.eta.agent.runtime.BackgroundRunPolicy] 自称前缀「唯一定义」——
 * 三处真相、靠人肉同步，改前缀就会让唤醒轮重新双发且无测试会红。
 *
 * 现在把前缀与分类**收口到这一个纯逻辑对象**：
 *  - [BackgroundRunPolicy] 的前缀常量引用这里（不再各自写字面量）；
 *  - [AttentionDirector] / [AttentionPublisher] 从会话 id 直接派生 unattended / wakeup，
 *    发起点不再填「事实」，只填「这是哪条通知 + 策略开关」。
 *
 * 注意本对象只做通知需要的分类（唤醒 / 自动任务 / 其它），不做「可回复 UI 会话 vs 归档」
 * 的细分——那是 [io.github.mangi.eta.agent.runtime.AgentConversationIdentity.isUiConversation]
 * 的职责，避免把会话身份算法再抄一份进来。
 */
internal object AttentionSubject {

    /** 后台任务唤醒轮的稳定可回复会话前缀——全项目唯一字面量定义。 */
    const val JOB_WAKEUP_PREFIX = "conv-wakeup-"

    /** 自动 / 计划任务的稳定可回复会话前缀——全项目唯一字面量定义。 */
    const val SCHEDULED_TASK_PREFIX = "conv-schedule-"

    /** 通知裁决需要的会话种类。 */
    enum class Kind { WAKEUP, SCHEDULED, OTHER }

    fun kindOf(conversationId: String?): Kind = when {
        conversationId.isNullOrBlank() -> Kind.OTHER
        conversationId.startsWith(JOB_WAKEUP_PREFIX) -> Kind.WAKEUP
        conversationId.startsWith(SCHEDULED_TASK_PREFIX) -> Kind.SCHEDULED
        else -> Kind.OTHER
    }

    /**
     * 是否无人值守会话（唤醒轮 / 自动任务）：即便 App 在前台也照发结果通知，
     * 否则前台跑完会完全静默。唯一判据，替代过去散落的 `unattended = true` 硬编码。
     */
    fun isUnattended(conversationId: String?): Boolean =
        kindOf(conversationId).let { it == Kind.WAKEUP || it == Kind.SCHEDULED }

    /** 唤醒轮会话：终态唯一由 9602 就地变形承担，run_result 段一律不发（避免双发）。 */
    fun isWakeup(conversationId: String?): Boolean = kindOf(conversationId) == Kind.WAKEUP

    /** 终态发布者归属：把「谁发这条终态」收敛成数据，避免两处各写前缀比较。 */
    enum class TerminalOwner { RUN_RESULT, JOB_WAKEUP_9602 }

    fun terminalOwnerOf(conversationId: String?): TerminalOwner =
        if (isWakeup(conversationId)) TerminalOwner.JOB_WAKEUP_9602 else TerminalOwner.RUN_RESULT
}
