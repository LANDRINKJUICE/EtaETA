package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.self.AgentSelfTools
import org.json.JSONArray
import org.json.JSONObject

/** 声明 Agent 读取与控制 Eta 自身配置、MCP 服务器的工具；执行逻辑在 [AgentSelfTools]。 */
internal object AgentSelfToolCatalog {
    fun appendTo(tools: JSONArray) {
        tools
            .put(
                AgentToolSchema.function(
                    name = "eta_get_config",
                    description = "读取 Eta 自身配置：版本、各工具组开关、可写开关键白名单、被单独禁用的工具、MCP 服务器列表。",
                    parameters = JSONObject().put("type", "object"),
                ),
            )
            .put(
                AgentToolSchema.function(
                    name = "eta_set_config",
                    description = "修改 Eta 工具组开关（仅限 eta_get_config 返回的 writable_switches 白名单键），并读回验证。修改即时生效，无需用户打开设置页。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("key", JSONObject().put("type", "string"))
                                .put("value", JSONObject().put("type", "boolean")),
                        )
                        .put("required", JSONArray().put("key").put("value")),
                ),
            )
            .put(
                AgentToolSchema.function(
                    name = "eta_set_tool_enabled",
                    description = "启用或禁用单个 Agent 工具（按工具名，如 web_search、memory_write），写入 Eta 工具列表并读回验证。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("tool", JSONObject().put("type", "string"))
                                .put("enabled", JSONObject().put("type", "boolean")),
                        )
                        .put("required", JSONArray().put("tool").put("enabled")),
                ),
            )
            .put(
                AgentToolSchema.function(
                    name = "eta_mcp_list",
                    description = "列出 Eta 已配置的 MCP 服务器及其工具启用情况。",
                    parameters = JSONObject().put("type", "object"),
                ),
            )
            .put(
                AgentToolSchema.function(
                    name = "eta_mcp_upsert",
                    description = "新增或更新 MCP 服务器。新增时不传 id，name/url 必填；更新时传 id，仅修改给出的字段。默认自动发现工具，新服务器默认启用发现的全部工具。bearer_token 可选，写入后由安全存储保管。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("id", JSONObject().put("type", "string"))
                                .put("name", JSONObject().put("type", "string"))
                                .put("url", JSONObject().put("type", "string"))
                                .put("enabled", JSONObject().put("type", "boolean"))
                                .put(
                                    "protocol_mode",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "auto 或具体协议版本日期；默认 auto。"),
                                )
                                .put("bearer_token", JSONObject().put("type", "string"))
                                .put(
                                    "discover",
                                    JSONObject()
                                        .put("type", "boolean")
                                        .put("description", "是否立即连接服务器发现工具，默认 true。"),
                                ),
                        ),
                ),
            )
            .put(
                AgentToolSchema.function(
                    name = "eta_mcp_delete",
                    description = "按 id 删除 MCP 服务器及其凭据。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put("properties", JSONObject().put("id", JSONObject().put("type", "string")))
                        .put("required", JSONArray().put("id")),
                ),
            )
            .put(
                AgentToolSchema.function(
                    name = "eta_mcp_refresh",
                    description = "按 id 重新连接 MCP 服务器并刷新工具列表。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put("properties", JSONObject().put("id", JSONObject().put("type", "string")))
                        .put("required", JSONArray().put("id")),
                ),
            )
    }
}
