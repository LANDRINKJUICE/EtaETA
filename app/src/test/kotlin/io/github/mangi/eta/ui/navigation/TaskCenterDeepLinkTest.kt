package io.github.mangi.eta.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 任务中心深链解析的回归测试（对应 MainActivityLaunchRequests 的
 * `ACTION_OPEN_TASK_CENTER` 分支）。
 *
 * 把这层抽成不依赖 Intent 的纯函数，是为了在没有 android.jar 的纯 JVM 环境里也能钉住三个分支：
 * 带指定分区、不带/空白分区（回默认分区）、以及"根本不是这条 action"（不得改动任何跳转状态）。
 */
class TaskCenterDeepLinkTest {

    @Test
    fun `explicit tab is trimmed and returned`() {
        assertEquals(
            "SCHEDULED",
            TaskCenterDeepLink.resolveTab(TaskCenterDeepLink.ACTION_OPEN_TASK_CENTER, "SCHEDULED"),
        )
        assertEquals(
            "APPROVALS",
            TaskCenterDeepLink.resolveTab(TaskCenterDeepLink.ACTION_OPEN_TASK_CENTER, "  APPROVALS  "),
        )
    }

    @Test
    fun `missing or blank tab falls back to the default partition`() {
        // 空串表示"走默认分区"，由路由层用 TaskCenterTab.fromNameOrDefault 落到 APPROVALS。
        assertEquals("", TaskCenterDeepLink.resolveTab(TaskCenterDeepLink.ACTION_OPEN_TASK_CENTER, null))
        assertEquals("", TaskCenterDeepLink.resolveTab(TaskCenterDeepLink.ACTION_OPEN_TASK_CENTER, ""))
        assertEquals("", TaskCenterDeepLink.resolveTab(TaskCenterDeepLink.ACTION_OPEN_TASK_CENTER, "   "))
    }

    @Test
    fun `other actions never touch task center state`() {
        assertNull(TaskCenterDeepLink.resolveTab("android.intent.action.MAIN", "APPROVALS"))
        assertNull(TaskCenterDeepLink.resolveTab("io.github.mangi.eta.action.OPEN_TERMINAL", "RUNNING"))
        assertNull(TaskCenterDeepLink.resolveTab(null, "APPROVALS"))
        // 大小写不同不算命中：action 是精确匹配的协议字段。
        assertNull(TaskCenterDeepLink.resolveTab(TaskCenterDeepLink.ACTION_OPEN_TASK_CENTER.lowercase(), "APPROVALS"))
    }

    @Test
    fun `default tab name matches the approvals partition`() {
        assertEquals("APPROVALS", TaskCenterDeepLink.DEFAULT_TAB_NAME)
    }
}
