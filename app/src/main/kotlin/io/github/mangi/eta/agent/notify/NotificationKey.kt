package io.github.mangi.eta.agent.notify

/**
 * 一条通知的**唯一句柄**（通知 ID + 可选 tag）。
 *
 * 2026-11-15 从根重构：过去 `replaceOngoingIds: List<Int>` 只带 ID、不带 tag，而 `run_result` /
 * `background_command` 等通知是 `manager.notify(tag, id, n)` 发布的。用裸 Int 去 `cancel(id)` 在
 * 带 tag 的通知上**静默撤不掉**，终态收尾就会留下「正在处理」的僵尸。把句柄收敛成 (id, tag)
 * 一个类型，[AttentionPublisher] 的 notify / cancel 与 `replaceOngoing` 共用它，让「撤错号」
 * 在类型层不可表达——这是结构约束，不是靠人记得传 tag。
 *
 * 纯数据、零 android.*，进纯 JVM 基线。
 */
internal data class NotificationKey(
    val id: Int,
    val tag: String? = null,
)
