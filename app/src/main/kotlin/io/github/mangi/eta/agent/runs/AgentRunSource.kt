package io.github.mangi.eta.agent.runs

import java.util.Locale

/**
 * 一次 Agent 运行（或一次模型请求）的来源。
 *
 * 用途有两处，且必须共用同一套取值：
 * 1. 任务中心「运行中」分区：说明当前这条 run 是用户聊天、子代理、自动任务还是记忆沉淀触发的；
 * 2. 用量统计：按来源分组，让人知道 token 花在聊天、自动任务还是沉淀回合上。
 *
 * 取值里的 handoff 字面量与 [io.github.mangi.eta.agent.runtime.AgentRuntimeWire] 的常量保持一致；
 * 这里刻意只保留字符串字面量，使本文件不依赖 Android/Runtime 协议层，方便纯逻辑测试。
 */
internal enum class AgentRunSource(val wireName: String) {
    /** 用户在 Eta 里发起的聊天运行。 */
    CHAT("chat"),

    /** 语音助手入口发起的运行。 */
    VOICE("voice"),

    /** 到点自动执行的自动任务（无人值守）。 */
    SCHEDULED("scheduled"),

    /** 后台命令失败/超时后自动唤醒的一轮运行（无人值守）。 */
    JOB_WAKEUP("job_wakeup"),

    /** 父 Agent 通过 subagent 工具发起的子运行。 */
    SUBAGENT("subagent"),

    /** 一轮运行结束后的记忆沉淀回合。 */
    DISTILL("distill"),

    /** 系统助手（Breeno / 小爱）等外部入口代理过来的运行。 */
    EXTERNAL("external"),

    /** 历史记录或未知来源；只用于兼容旧数据，不会被新写入。 */
    UNKNOWN("unknown"),
    ;

    companion object {
        /** handoff.source 的取值；与 AgentRuntimeWire 中的常量一一对应。 */
        private const val HANDOFF_AGENT_UI = "agent_ui"
        private const val HANDOFF_ETA_VOICE = "eta_voice"
        private const val HANDOFF_ETA_SCHEDULED_TASK = "eta_scheduled_task"
        private const val HANDOFF_ETA_JOB_WAKEUP = "eta_job_wakeup"

        /** UI 侧没有 handoff 来源时按聊天处理：那是唯一有用户在看着的入口。 */
        fun fromHandoffSource(handoffSource: String?): AgentRunSource = when (handoffSource) {
            null, "", HANDOFF_AGENT_UI -> CHAT
            HANDOFF_ETA_VOICE -> VOICE
            HANDOFF_ETA_SCHEDULED_TASK -> SCHEDULED
            HANDOFF_ETA_JOB_WAKEUP -> JOB_WAKEUP
            else -> EXTERNAL
        }

        fun fromWireName(value: String?): AgentRunSource {
            val normalized = value.orEmpty().trim().lowercase(Locale.ROOT)
            return entries.firstOrNull { it.wireName == normalized } ?: UNKNOWN
        }
    }
}
