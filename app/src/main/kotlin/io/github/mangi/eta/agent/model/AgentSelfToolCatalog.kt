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
                    description = "修改 Eta 工具组开关（仅限 eta_get_config 返回的 writable_switches 白名单键），并读回验证。关闭立即生效，开启从下一轮运行生效；无需用户打开设置页。",
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
        appendModelTools(tools)
        appendPrefTools(tools)
        appendSkillTools(tools)
    }

    /**
     * 网页搜索与读取、语音与朗读、Linux 工具环境三类设置域。
     *
     * 与 `eta_set_config`（工具组开关）分开：那边的键是布尔开关，这边的键有类型、枚举、
     * URL 与加密凭据，语义完全不同，合成一个工具会让 schema 说不清。
     */
    private fun appendPrefTools(tools: JSONArray) {
        tools
            .put(
                AgentToolSchema.function(
                    name = "eta_prefs_list",
                    description = "列出 Eta 可管理的设置键（网页搜索与读取 / 语音与朗读 / Linux 工具环境）：每项的键名、类型、当前值、可选值、存储位置与说明。凭据类值已脱敏。可用 domain 只列某一个域（web / speech / linux）。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject().put(
                                "domain",
                                JSONObject()
                                    .put("type", "string")
                                    .put("enum", JSONArray().put("web").put("speech").put("linux"))
                                    .put("description", "可选，只列指定域。"),
                            ),
                        ),
                ),
            )
            .put(
                AgentToolSchema.function(
                    name = "eta_prefs_get",
                    description = "按 key 读取一个已登记的 Eta 设置键的当前值、类型、可选值与存储位置；凭据类返回脱敏值（前 4 后 4）。键名先用 eta_prefs_list 查。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put("properties", JSONObject().put("key", JSONObject().put("type", "string")))
                        .put("required", JSONArray().put("key")),
                ),
            )
            .put(
                AgentToolSchema.function(
                    name = "eta_prefs_set",
                    description = "写入一个已登记的 Eta 设置键（与设置页同一份存储），自动做类型校验并读回验证；凭据走加密仓库，返回脱敏确认值。只接受 eta_prefs_list 列出的键，传空串可清除凭据或文本值。服务地址、凭据、Linux 执行环境这几类键（eta_prefs_list 里 requires_confirmation=true）会先弹危险操作确认卡，等用户确认后才写入。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("key", JSONObject().put("type", "string"))
                                .put(
                                    "value",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "新值；布尔写 true/false，数字写数值字符串，枚举写可选值之一。"),
                                ),
                        )
                        .put("required", JSONArray().put("key").put("value")),
                ),
            )
    }

    private fun appendModelTools(tools: JSONArray) {
        tools
            .put(
                AgentToolSchema.function(
                    name = "eta_provider_list",
                    description = "列出全部模型提供商、凭据可用性、模型数量与当前默认选择；模型明细用 eta_model_list 查询。",
                    parameters = JSONObject().put("type", "object"),
                ),
            )
            .put(
                AgentToolSchema.function(
                    name = "eta_provider_upsert",
                    description = "新增或更新模型提供商。新增时 name/base_url 必填，type 支持 openai_compatible（默认）/anthropic/gemini；更新时传 id，仅修改给出的字段；api_key 传入即更新，不回显明文。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("id", JSONObject().put("type", "string"))
                                .put("type", JSONObject().put("type", "string"))
                                .put("name", JSONObject().put("type", "string"))
                                .put("base_url", JSONObject().put("type", "string"))
                                .put("api_key", JSONObject().put("type", "string"))
                                .put("enabled", JSONObject().put("type", "boolean")),
                        ),
                ),
            )
            .put(
                AgentToolSchema.function(
                    name = "eta_provider_delete",
                    description = "按 id 删除模型提供商及其凭据。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put("properties", JSONObject().put("id", JSONObject().put("type", "string")))
                        .put("required", JSONArray().put("id")),
                ),
            )
            .put(
                AgentToolSchema.function(
                    name = "eta_model_list",
                    description = "列出指定提供商的模型及能力、上下文窗口覆盖值。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put("properties", JSONObject().put("provider_id", JSONObject().put("type", "string")))
                        .put("required", JSONArray().put("provider_id")),
                ),
            )
            .put(
                AgentToolSchema.function(
                    name = "eta_model_upsert",
                    description = "新增或修复模型。新增时 model_id 必填；更新时传 id（模型内部 id）或同 model_id 匹配。可选 enabled、context_window（上下文窗口覆盖）、vision/tools/reasoning（能力布尔）。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("provider_id", JSONObject().put("type", "string"))
                                .put("id", JSONObject().put("type", "string"))
                                .put("model_id", JSONObject().put("type", "string"))
                                .put("display_name", JSONObject().put("type", "string"))
                                .put("enabled", JSONObject().put("type", "boolean"))
                                .put("context_window", JSONObject().put("type", "integer"))
                                .put("vision", JSONObject().put("type", "boolean"))
                                .put("tools", JSONObject().put("type", "boolean"))
                                .put("reasoning", JSONObject().put("type", "boolean")),
                        )
                        .put("required", JSONArray().put("provider_id")),
                ),
            )
            .put(
                AgentToolSchema.function(
                    name = "eta_model_delete",
                    description = "按模型内部 id 删除模型。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("provider_id", JSONObject().put("type", "string"))
                                .put("id", JSONObject().put("type", "string")),
                        )
                        .put("required", JSONArray().put("provider_id").put("id")),
                ),
            )
            .put(
                AgentToolSchema.function(
                    name = "eta_model_select",
                    description = "切换当前默认模型（传模型内部 id）或默认提供商（传 provider_id，自动选其首选模型），返回切换后的实际选择。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("id", JSONObject().put("type", "string"))
                                .put("provider_id", JSONObject().put("type", "string")),
                        ),
                ),
            )
    }

    private fun appendSkillTools(tools: JSONArray) {
        tools
            .put(
                AgentToolSchema.function(
                    name = "eta_skill_list",
                    description = "列出全部已安装 Skills（含已禁用）及启用状态。",
                    parameters = JSONObject().put("type", "object"),
                ),
            )
            .put(
                AgentToolSchema.function(
                    name = "eta_skill_set_enabled",
                    description = "按 id 启用或禁用已安装的 Skill；从下一轮运行生效。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("id", JSONObject().put("type", "string"))
                                .put("enabled", JSONObject().put("type", "boolean")),
                        )
                        .put("required", JSONArray().put("id").put("enabled")),
                ),
            )
            .put(
                AgentToolSchema.function(
                    name = "eta_skill_delete",
                    description = "按 id 卸载 Skill，从下一轮运行生效（内置 Skill 可能被系统保护，失败时如实返回）。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put("properties", JSONObject().put("id", JSONObject().put("type", "string")))
                        .put("required", JSONArray().put("id")),
                ),
            )
    }
}
