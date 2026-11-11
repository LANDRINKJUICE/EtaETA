package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import io.github.mangi.eta.config.Prefs
import org.json.JSONArray

/** 声明模型可见的工具及其 JSON Schema；不包含任何执行逻辑。 */
internal object AgentToolCatalog {
    fun build(
        terminalTools: Boolean,
        browserTools: Boolean,
        deviceDirectTools: Boolean = true,
        deviceSensitiveReadTools: Boolean = false,
        deviceSensitiveActionTools: Boolean = false,
        skillGitHubDiscovery: Boolean = false,
        skillGitHubInstall: Boolean = false,
        memoryTools: Boolean = false,
        memoryWritable: Boolean = true,
        selfTools: Boolean = true,
        /**
         * 是否公开"会话级工具"（ask_user / schedule_task / macro_record）。角色会话为 false：
         * 这三件工具都会把用户拉回现实交互或写入持久状态，与沉浸式角色会话冲突（审查 M4）。
         */
        conversationTools: Boolean = true,
        /**
         * 是否公开主动沟通工具（notify_user）。用户在设置里关掉"主动通知"时为 false：
         * 既不注入 schema（模型不会尝试调用），执行侧 [io.github.mangi.eta.agent.notify.NotifyUserTool]
         * 也仍会独立校验开关，形成双保险。
         */
        proactiveNotify: Boolean = true,
        capabilities: AgentToolCapabilities = AgentToolCapabilities(rootAvailable = true),
        localWebSearch: Boolean = true,
        disabledTools: Set<String> = emptySet(),
    ): JSONArray =
        capabilities.project(JSONArray().also { tools ->
            AgentContextAppToolCatalog.appendTo(tools)
            AgentGestureToolCatalog.appendTo(tools)
            AgentBatchToolCatalog.appendTo(tools)
            AgentScheduleToolCatalog.appendTo(tools)
            AgentNotifyToolCatalog.appendIf(tools, proactiveNotify)
            AgentTextSystemToolCatalog.appendTo(tools)
            AgentDeviceToolCatalog.appendTo(
                tools,
                directTools = deviceDirectTools,
                sensitiveReadTools = deviceSensitiveReadTools,
                sensitiveActionTools = deviceSensitiveActionTools,
            )
            if (browserTools) {
                AgentWebToolCatalog.appendTo(tools, includeSearch = localWebSearch)
                AgentBrowserToolCatalog.appendTo(tools)
            }
            AgentSkillToolCatalog.appendTo(
                tools,
                githubDiscovery = skillGitHubDiscovery,
                githubInstall = skillGitHubInstall,
            )
            if (memoryTools) AgentMemoryToolCatalog.appendTo(tools, writable = memoryWritable)
            if (selfTools) AgentSelfToolCatalog.appendTo(tools)
            if (terminalTools) {
                AgentFileVisionToolCatalog.appendTo(tools)
                AgentTerminalToolCatalog.appendTo(tools)
                AgentFileToolCatalog.appendTo(tools)
            }
        }).let { projected ->
            val visible = if (conversationTools) {
                projected
            } else {
                ToolCatalogVisibility.filter(projected, ToolCatalogVisibility.ROLEPLAY_HIDDEN_TOOL_NAMES)
            }
            if (disabledTools.isEmpty()) {
                visible
            } else {
                JSONArray().also { filtered ->
                    for (index in 0 until visible.length()) {
                        val tool = visible.getJSONObject(index)
                        val name = tool.getJSONObject("function").getString("name")
                        if (Prefs.isToolEnabledInSet(name, disabledTools)) {
                            filtered.put(tool)
                        }
                    }
                }
            }
        }
}
