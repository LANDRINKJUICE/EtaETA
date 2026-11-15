package io.github.mangi.eta.agent.notify

/**
 * 「Eta 现在是不是在用户眼前」的**唯一真相源**（通知架构侧）。
 *
 * ## 为什么存在（2026-11-15 一次统一）
 * 改造前有两套独立的前台判定：`MainActivity.isInForeground`（startedInstances 计数，无去抖）
 * 与 `AgentForegroundTracker.isForeground()`（ActivityLifecycle 计数 + 250ms 去抖）。
 * 通知的「该不该打扰」在 `AgentExecutionService`、`ApprovalAttentionPresenter` 等处各取各的，
 * 口径会漂移：一个有去抖一个没有，切页/旋屏瞬时 0 时可能一边判前台一边判后台。
 *
 * 本对象把**去抖后的稳定前台值**作为唯一对外口径：底层复用现成的、已被审批层验证过的
 * [io.github.mangi.eta.agent.approval.AgentForegroundTracker]（它带 250ms 去抖、install 幂等、
 * 由 Application 生命周期驱动），通知决策一律读这里，不再直接读 `MainActivity.isInForeground`。
 *
 * 这样「前台真相」只有一个实现、一个值；纯逻辑裁决层 [AttentionDirector] 只接收一个布尔，
 * 不依赖任何 Android 类，可纯 JVM 完整单测。
 */
internal object AppForeground {

    /**
     * 稳定前台值（去抖后）。所有通知/打扰裁决统一从这里取。
     * 底层是 `AgentForegroundTracker` 的 foregroundStable，进前台立即生效、退后台 250ms 去抖。
     */
    fun isForeground(): Boolean =
        io.github.mangi.eta.agent.approval.AgentForegroundTracker.isForeground()
}
