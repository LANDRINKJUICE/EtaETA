package io.github.mangi.eta.agent.mcp

import io.github.mangi.eta.agent.tool.AgentToolRequirements
import io.github.mangi.eta.agent.tool.RootRequirement

/**
 * 工具风险分级：决定一次 MCP 工具调用是否需要先经审批卡。
 *
 * 分级来自 Eta 既有的 [AgentToolRequirements]（每个工具的 Root 需求表），
 * 不另建一套名单——两套名单必然漂移，这里只做"是否打扰用户"的判断。
 */
internal object McpToolRisk {
    /** 只读工具：不改设备状态、不写文件、不需要 root，自动放行。 */
    fun isReadOnly(name: String): Boolean {
        if (name in WRITE_TOOL_NAMES) return false
        val requirement = AgentToolRequirements.find(name) ?: return false
        return requirement.rootRequirement == RootRequirement.NONE && name !in ACTION_TOOL_NAMES
    }

    /** 需要用户确认的工具：root 需求、写操作、以及所有会改变界面或设备的动作。 */
    fun requiresApproval(name: String): Boolean = !isReadOnly(name)

    private val WRITE_TOOL_NAMES = setOf(
        "write_file", "edit_file", "memory_write", "character_memory_write",
        "set_clipboard", "paste_text", "skills_install_from_github",
    )

    /** 无 root 需求但会改变设备/界面的动作，仍需确认。 */
    private val ACTION_TOOL_NAMES = setOf(
        "tap", "tap_area", "tap_element", "long_press", "long_press_element",
        "swipe", "scroll", "scroll_element", "type_text", "input_text",
        "replace_text", "clear_text", "press_key", "open_system_panel",
        "launch_app", "open_uri", "present_to_user", "release_gui",
        "set_alarm", "set_timer", "set_volume", "media_control",
        "terminal", "terminal_job", "terminal_daemon",
        "generate_image", "browser_use",
    )
}
