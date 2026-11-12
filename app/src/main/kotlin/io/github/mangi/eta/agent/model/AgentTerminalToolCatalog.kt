package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

internal object AgentTerminalToolCatalog {
    val names = setOf("terminal", "terminal_job", "terminal_daemon")

    /**
     * @param jobWakeup 设置页"后台任务完成自动唤醒"开关的当前值。关掉时不能再宣告
     *   "失败会自动被接手"，否则模型会把失败当成有人兜底而放任不管（审查 m7）。
     */
    fun appendTo(tools: JSONArray, jobWakeup: Boolean = true) {
        tools.put(
            AgentToolSchema.function(
                name = "terminal",
                description = "执行 Shell 命令，直接提供 command 即可。默认环境、身份和工作目录见本次运行上下文。" +
                    "需要保留 cd、环境变量等 Shell 状态时，先 action=open，再通过 session_id 执行命令，最后 action=close。" +
                    "单次命令结束后进程会回收，勿用 & 挂后台；耗时或后台命令使用 terminal_job，跨运行的后台服务使用 terminal_daemon。" +
                    "已知文件的读写、编辑和搜索优先使用对应文件工具。",
                parameters = parameters(
                    commandProperties()
                        .put("action", action("exec", "open", "close", "check_root")
                            .put("description", "默认 exec 执行命令；open 创建持久 Shell；close 关闭会话；check_root 用固定的 su -c 'id -u' 检测并更新 Root 授权，不需要 command，可能显示系统授权请求。"))
                        .put("session_id", text("open 返回的会话 ID。exec 沿用会话的目录、环境和身份；切换目录请在会话中执行 cd。")),
                ),
            ),
        )
        tools.put(
            AgentToolSchema.function(
                name = "terminal_job",
                description = "启动可在本次回复结束后继续运行的后台命令。start 立即返回 job_id，完成或失败时发送系统通知；返回的 completion_notifications_enabled 表示当前通知是否可用。" +
                    wakeupPolicy(jobWakeup) +
                    "list 查看任务，read 查看当前状态和日志，wait 在需要结果时等待，stop 终止任务。" +
                    "日志保存为文件，read 返回 output_path；预览截断时可用 read_file 分页读取完整日志。" +
                    "后台命令使用独立 Shell；不继承 terminal 会话。启动后可继续其他工作或回复用户，不必轮询等待。",
                parameters = parameters(
                    executionProperties()
                        .put("action", action("start", "list", "read", "wait", "stop"))
                        .put("command", text("start 要执行的完整命令。"))
                        .put("description", text("可选：给未来被唤醒的 Agent 的任务说明。写清目标、验收标准、失败后怎么重试、产物路径与交付位置，以及用户可能不在线、尽量别发询问卡或碰关键部位、别擅自替用户做决定等策略；这段会作为上下文与策略注入唤醒轮 prompt（非强制指令，机制兜底固定）。忘了就留空，退回默认话术。也用于列表、完成通知与归档标题。"))
                        .put("timeout_ms", integer("start 的命令运行上限，默认 600000 毫秒；0 表示不设超时。"))
                        .put("watch", bool(if (jobWakeup) {
                            "start 是否盯梢这条任务：默认 false，只在失败/超时/中断时才会自动唤醒；设为 true 后正常结束也会唤醒一轮 agent 查看结果。"
                        } else {
                            "start 是否盯梢这条任务：当前已关闭自动唤醒，该参数不会产生任何唤醒，只在任务记录里留痕。"
                        }))
                        .put("job_id", text("start 或 list 返回的任务 ID；read、wait、stop 使用。"))
                        .put("active_only", bool("list 是否只返回运行中的任务，默认 true。"))
                        .put("max_bytes", integer("read 返回的日志尾部字节数，默认 16000，最多 65536；完整日志保存在 output_path。"))
                        .put("wait_ms", integer("wait 最长等待毫秒数，默认 30000，最多 60000；等待超时不会停止命令。")),
                    "action",
                ),
            ),
        )
        tools.put(
            AgentToolSchema.function(
                name = "terminal_daemon",
                description = "管理需要跨 Agent 运行保留的后台服务，例如 Web 服务。start 启动并返回 task_id，" +
                    "list 查看任务，logs 读取日志，stop 停止任务。服务不会随当前运行结束自动回收，" +
                    "但仍可能因进程退出、系统回收、权限变化或设备重启而终止。",
                parameters = parameters(
                    executionProperties()
                        .put("action", action("start", "list", "logs", "stop"))
                        .put("command", text("start 要执行的命令，使用实际环境支持的 Shell 语法。"))
                        .put("task_id", text("start 或 list 返回的任务 ID；logs、stop 使用。")),
                    "action",
                ),
            ),
        )
    }

    /** "失败自动唤醒"的宣告片段：开关为开/关两套说法，避免模型对没人兜底的失败掉以轻心。 */
    private fun wakeupPolicy(jobWakeup: Boolean): String = if (jobWakeup) {
        "任务失败、超时或被中断时，Eta 会自动唤醒一轮 agent 来分析原因并处理（成功不打扰，watch=true 时成功也会唤醒）；" +
            "从自动唤醒那一轮里新起的任务不会再次触发唤醒。"
    } else {
        "用户已关闭自动唤醒：任务失败、超时或被中断时不会有人接手，也不会发唤醒通知，需要自己用 list/read 或等下一轮查看结果；" +
            "这次运行里不要假设失败会被别人处理。"
    }

    private fun executionProperties(): JSONObject = JSONObject()
        .put("environment", text("执行环境（默认 android）：操作 Android 系统/应用/设备状态用 android；Git、写代码、运行 Python/Node 脚本、jq/rg/curl、包管理与 APK 分析请显式传 linux。工作区与共享目录在两端映射同一物理文件夹，按命令性质选环境并使用对应路径。")
            .put("enum", JSONArray(listOf("android", "linux"))))
        .put("identity", text("可选的宿主执行身份；Android 已确认 Root 授权时默认 root，否则 user。已授权时无需先尝试 user 或重复检测；显式 user 使用 Eta App UID。Linux 内模拟 root 不提供 Android 特权。")
            .put("enum", JSONArray(listOf("user", "root"))))
        .put("cwd", text("可选工作目录；省略时使用当前环境默认工作区，相对路径也以默认工作区为基准。"))

    private fun commandProperties(): JSONObject = executionProperties()
        .put("command", text("要执行的命令，使用实际环境支持的 Shell 语法。"))
        .put("timeout_ms", integer("命令超时毫秒，默认 30000，最多 180000。超时会终止命令；会话命令超时也会关闭会话。"))
        .put("merge_stderr", bool("是否把 stderr 合并到 stdout，默认 false。"))

    private fun parameters(properties: JSONObject, vararg required: String): JSONObject = JSONObject()
        .put("type", "object")
        .put("properties", properties)
        .put("required", JSONArray(required.toList()))
        .put("additionalProperties", false)

    private fun action(vararg values: String): JSONObject = text("要执行的动作。")
        .put("enum", JSONArray(values.toList()))

    private fun text(description: String) = JSONObject().put("type", "string").put("description", description)
    private fun bool(description: String) = JSONObject().put("type", "boolean").put("description", description)
    private fun integer(description: String) = JSONObject().put("type", "integer").put("description", description)
}
