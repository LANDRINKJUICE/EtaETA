# Eta Agent 自管理与体验优化改造说明

本文档记录 `feat/agent-self-memory` 分支对 Eta 的源码改造内容、使用方式和构建签名流程。

## 1. Agent 对 Eta 的自我认知

- 每次运行时向系统提示注入 Eta 当前状态：
  - 版本号
  - 工具组开关
  - 被单独禁用的工具
  - MCP 服务器与工具数量
- 角色会话和子代理不会获得这些自管理工具，也不会看到自管理提示。
- 所有 `eta_*` 工具都加入敏感工具策略，参数和结果不会持久化到会话历史。

## 2. Eta 配置工具

| 工具 | 作用 |
|---|---|
| `eta_get_config` | 读取版本、开关、禁用工具、MCP 配置 |
| `eta_set_config` | 修改本地 Agent 工具组开关 |
| `eta_set_tool_enabled` | 启用或禁用单个 Agent 工具 |

关闭立即生效，开启从下一轮运行生效。组开关通过工具禁用集写入，不再使用旧的 legacy 布尔开关。

## 3. MCP 管理工具

| 工具 | 作用 |
|---|---|
| `eta_mcp_list` | 列出 MCP 服务器 |
| `eta_mcp_upsert` | 新增或更新 MCP 服务器，默认自动发现工具 |
| `eta_mcp_delete` | 删除服务器及凭据 |
| `eta_mcp_refresh` | 刷新工具列表 |

首次成功发现工具时默认全部启用；用户手动全关过的服务器不会被刷新自动重新打开。

## 4. 模型与技能管理

### 模型提供商

- `eta_provider_list`
- `eta_provider_upsert`
- `eta_provider_delete`

支持 OpenAI-compatible、Anthropic、Gemini。内置提供商禁止修改 `base_url`，避免真实 API Key 被发送到被替换的地址。

### 模型

- `eta_model_list`
- `eta_model_upsert`
- `eta_model_delete`
- `eta_model_select`

支持新增模型、修改上下文窗口、视觉/工具/推理能力，以及切换默认模型。切换后会读回验证。

### Skills

- `eta_skill_list`
- `eta_skill_set_enabled`
- `eta_skill_delete`

技能启用或卸载从下一轮运行生效。

## 5. 记忆升级

`memory_write` 新增 `upsert_section`：

- 按 Markdown 标题整体替换一节正文
- 节不存在时自动追加
- 支持 fenced code block，不会把代码块里的 `# 注释` 误判成标题
- 存在多个同名标题时拒绝写入，避免误删

## 6. 子代理优化

- `subagent` 新增：
  - `timeout_seconds`
  - `max_turns`
- 返回遥测：
  - `rounds`
  - `elapsed_ms`
  - `tool_calls`
- 成功后把摘要写入 `subagent-reports/`。
- 新增 `analyst` 子代理：
  - 只读检索 + 受限终端
  - 写命令和重定向在工具层启发式拦截

## 7. GUI 批处理与宏

### `gesture_batch`

一次执行 1–50 步动作：

- `tap`
- `tap_area`
- `tap_text`
- `long_press`
- `swipe`
- `scroll`
- `type_text`
- `press_key`
- `wait_ms`

失败立即中断并返回 `stopped_at`。成功后可只在末尾观察一次，显著减少模型回合和 token 消耗。

### GUI 宏

- `macro_save`
- `macro_list`
- `macro_run`
- `macro_delete`

宏保存在 `terminal-user/workspace/gui-macros/`，支持 `{{参数}}` 占位。同名宏需要 `overwrite=true` 才能覆盖。

## 8. 通知与流体云

- 执行通知显示当前工具动作、参数摘要、轮次和子代理进度
- 完成或中断时发送结果通知
- 后台命令通知显示退出码、耗时和日志尾部
- 支持 Android 16 Live Update / realme UI 状态栏胶囊
- 通知刷新做了节流，避免流式输出导致系统限流

## 9. 流式渲染优化

- Markdown 流式解析目标按 40ms 帧预算合并
- 减少每个 token 触发完整解析和 Compose 测量造成的卡顿
- 不改变 Markdown 观感、逐字显现动画或排版结构

## 10. 构建与签名

### 云端构建

工作流：`.github/workflows/agent-release.yml`

特点：

- 只构建正式 Release APK
- 不构建 Debug 包
- 不读取签名 Secrets
- 输出未签名 Release APK

### 自动下载与签名

脚本：`/workspace/脚本-Scripts/auto-eta-release.sh`

自动完成：

1. 监听最新 Release 构建
2. 下载 APK
3. 使用 `eta-fork.jks` 签名
4. 验证签名
5. 输出到 `Download/Eta/Eta-signed.apk`

签名信息：

- Keystore：`/workspace/signing/eta-fork.jks`
- Store password：`EtaFork-2026`
- Alias：`eta-fork`
- Key password：`EtaFork-2026`

后续更新必须继续使用同一份签名密钥，否则 Android 会拒绝覆盖安装。
