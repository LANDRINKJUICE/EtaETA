package io.github.mangi.eta.ui.navigation

/**
 * 任务中心深链的**纯解析**逻辑。
 *
 * 刻意不引用 `android.content.Intent`：这层只回答"这条 action + tab extra 应该打开哪个分区"，
 * 因此可以在纯 JVM 单测里钉住三个分支（带 tab / 不带 tab / 完全不是这个 action），
 * 而不需要 Robolectric。真正的 Intent 读取留在 `MainActivityLaunchRequests`。
 *
 * 常量也集中放在这里，作为唯一来源——此前 action 字符串在 MainActivity 与
 * AgentNotifications 各写了一份，两份字符串一旦漂移，通知点击就会静默失效。
 */
internal object TaskCenterDeepLink {
    /** 点击通知直达任务中心。 */
    const val ACTION_OPEN_TASK_CENTER = "io.github.mangi.eta.action.OPEN_TASK_CENTER"

    /** 要打开的分区名（`TaskCenterTab` 的 name；空串表示默认分区）。 */
    const val EXTRA_TASK_CENTER_TAB = "io.github.mangi.eta.extra.TASK_CENTER_TAB"

    /** 未指定分区时落到的分区：待确认区——通知里最需要用户当场处理的就是它。 */
    const val DEFAULT_TAB_NAME = "APPROVALS"

    /**
     * 解析要打开的分区名。
     *
     * @param action 收到的 Intent action。
     * @param rawTab EXTRA_TASK_CENTER_TAB 的原始值（可能为 null / 空白 / 带空格）。
     * @return `null` 表示"这条 Intent 不是任务中心深链，调用方不应改动任何跳转状态"；
     *         否则返回去掉首尾空白的分区名，空串表示走默认分区。
     */
    fun resolveTab(action: String?, rawTab: String?): String? {
        if (action != ACTION_OPEN_TASK_CENTER) return null
        return rawTab?.trim().orEmpty()
    }
}
