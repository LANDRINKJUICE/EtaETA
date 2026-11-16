package io.github.mangi.eta.ui.model

import io.github.mangi.eta.agent.runs.AgentRunSource
import java.util.Locale

/**
 * 任务中心的纯展示逻辑：分区枚举、时长与 token 计数格式化。
 *
 * 刻意与 [TaskCenterUiState] 分开：这些函数被单元测试直接钉住（边界、千位分隔、非法分区名），
 * 而 UiState 文件依赖 Compose 注解，无法在纯 JVM 测试里编译。
 */

/** 任务中心的五个分区；顺序就是标签栏顺序，也是"现在最需要处理什么"的优先级。 */
internal enum class TaskCenterTab {
    /** 危险操作拦截卡 / ask_user：需要用户当场决策。 */
    APPROVALS,

    /** 到点自动执行的自动任务。 */
    SCHEDULED,

    /** 正在跑的 Agent run、子代理，以及后台命令与守护服务。 */
    RUNNING,

    /** 已保存的 GUI 宏。 */
    MACROS,

    /** 用量与花费。 */
    USAGE,
    ;

    companion object {
        /**
         * 路由参数 → 分区。
         *
         * 导航只传名字，因此这里必须容忍空串（默认分区）与历史/拼错的名字：
         * 解析失败退回 [APPROVALS]，绝不抛异常，也不让页面打不开。
         */
        fun fromNameOrDefault(name: String?): TaskCenterTab {
            val normalized = name?.trim().orEmpty()
            if (normalized.isEmpty()) return APPROVALS
            return entries.firstOrNull { it.name == normalized }
                ?: entries.firstOrNull { it.name.equals(normalized, ignoreCase = true) }
                ?: APPROVALS
        }
    }
}

/**
 * 紧凑时长：秒级用 m:ss，超过一小时用 h:mm:ss。
 *
 * 负数（设备时钟回拨导致 startedAt 在未来）按 0 处理，不显示负时长。
 */
internal fun formatElapsed(millis: Long): String {
    val totalSeconds = (millis / 1_000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, seconds)
    }
}

/** token 计数带千位分隔，方便一眼比较数量级；负数只可能来自脏数据，按 0 显示。 */
internal fun formatTokens(value: Long, locale: Locale = Locale.getDefault()): String =
    String.format(locale, "%,d", value.coerceAtLeast(0L))

/**
 * 「运行中」这一行能否从任务中心单独停止。
 *
 * 只有顶层 run 有独立的 Runtime 会话；子代理（无论是否有父 run）都挂在父 run 的取消链上，
 * 对子代理 id 直接发 cancel 不会产生任何效果。宁可少一个按钮，也不给一个点了没反应的按钮。
 */
internal fun isStoppableRun(source: AgentRunSource, isChild: Boolean): Boolean =
    !isChild && source != AgentRunSource.SUBAGENT
