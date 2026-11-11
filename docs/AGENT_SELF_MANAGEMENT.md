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

## 5. 设置键读写工具（eta_prefs_\*）

工具组开关以外，Agent 现在可以按**白名单**读写设置页里的三类配置，不需要用户手点设置页，
也不走 root 改文件：

| 工具 | 作用 |
|---|---|
| `eta_prefs_list` | 列出已登记键（可按 domain=web / speech / linux 过滤）及当前值、类型、可选值、存储位置 |
| `eta_prefs_get` | 按 key 读取单个键 |
| `eta_prefs_set` | 按 key 写入，做类型校验并读回验证 |

登记表在 `agent/self/AgentSelfPrefs.kt`（纯逻辑，无 Android 依赖），执行侧在
`agent/self/AgentSelfPrefsTools.kt`。

- **白名单制**：只有登记表里的键可读写，其它键返回 `PREF_KEY_UNKNOWN`；键名区分大小写。
- **类型校验**（对齐运行期规则，不只对齐输入框）：布尔（true/false/1/0/on/off/yes/no 归一化成
  true/false）、整数（可限定离散取值）、小数（可限定上下界）、枚举（大小写不敏感，落盘归一化）、
  网页 URL（http/https，允许 `{{query}}` 这类模板占位符）、**语音服务地址**（必须是不含认证信息、
  查询参数与片段的 HTTPS 地址，与 `SpeechProtocol.speechBaseUrl` 同一套规则）、**OSS Bucket**
  （3~63 位小写字母/数字/短横线且首尾为字母或数字）与**目录前缀**（不允许 `.` / `..` 段）、
  文本长度上限（如 Gemini 风格提示 500 字）。非法值返回 `PREF_VALUE_INVALID` 且不写入。
- **前置条件**：登记表管不了的设备能力单独拦，例如未授权 Root 时切 `chroot` 返回
  `PREF_PRECONDITION_FAILED`（与终端页后端选择门槛一致）。
- **写入路径与设置页一致**：网页工具 / 语音的设置写进同一份 DataStore
  （`web_tools_settings_v1` / `speech_settings_v1`），且是在 **`dataStore.edit {}` 事务内**
  读原始 JSON → 打补丁 → 写回（`SettingsDataStore.patch*SettingsRaw`），不会与设置页的并发写互相覆盖；
  凭据（API Key、OSS Secret）只走 AndroidKeyStore + AES/GCM 的凭据仓库
  （`eta_web_tools_secrets` / `eta_speech_secrets`），与设置页保存逻辑相同；Linux 发行版与后端写
  `linux_distribution` / `linux_backend.<发行版>`。工具内部另有一把单例互斥锁，把"校验 → 写 → 读回"
  整段串行化。
- **解析失败拒绝写入**：现有存储内容解析不出来时返回 `PREF_SETTINGS_UNREADABLE` 并**放弃写入**，
  绝不用默认值覆盖用户配置（旧实现会静默回落默认值，等于丢设置）。
- **需要确认**：服务地址、凭据、Linux 执行环境这三类键（`eta_prefs_list` 里
  `requires_confirmation=true`）先走危险操作确认卡，用户点同意后才写入；普通布尔 / 枚举键直接放行。
- **读回验证**：写完立即重读比值，不一致返回 `PREF_VERIFY_FAILED`，不会假装成功。
- **凭据脱敏**：读取一律脱敏——长度 ≥ 16 保留前 4 后 4、中间固定 6 个星号（不泄露真实长度），
  更短的值整体显示 `****`；写入返回的也是脱敏确认值。
- **凭据不可读**：解密失败时返回 `PREF_CREDENTIALS_UNREADABLE`，不覆盖已有凭据，
  需要用户在设置页重新填写保存。

已登记的键（共 65 个）：

- 网页搜索与读取（20）：`web.search_backend`、`web.fetch_backend`、`web.deepseek_base_url`、
  `web.deepseek_api_key`、`web.custom_search_{method,url,api_key,headers,body_template,results_path,title_field,url_field,snippet_field}`、
  `web.custom_fetch_{method,url,api_key,headers,body_template,content_field,title_field}`
- 语音与朗读（42）：`speech.{asr_provider,tts_provider,recognition_enabled,auto_speak,chat_auto_speak,refine_transcript,language,auto_send_silence_ms}`、
  `speech.qwen_{asr,tts}_{region,base_url,api_key}`、`speech.qwen_voice`、
  `speech.doubao_{asr,tts}_{base_url,app_id,resource_id,legacy_auth,api_key}`、`speech.doubao_voice`、
  `speech.gemini_tts_{base_url,api_key,model,voice,style}`、
  `speech.minimax_tts_{base_url,api_key,model,voice,speed}`、
  `speech.oss_{region,endpoint,bucket,prefix,access_key_id,access_key_secret}`
- Linux 工具环境（3）：`linux.distribution`、`linux.backend_alpine`、`linux.backend_debian`

查看当前值请以 `eta_prefs_list` 的实时返回为准（键表会随设置页扩展而增长）。

## 6. 记忆升级

`memory_write` 新增 `upsert_section`：

- 按 Markdown 标题整体替换一节正文
- 节不存在时自动追加
- 支持 fenced code block，不会把代码块里的 `# 注释` 误判成标题
- 存在多个同名标题时拒绝写入，避免误删

## 7. 子代理优化

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

## 8. GUI 批处理与宏

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

## 9. 通知与流体云

- 执行通知显示当前工具动作、参数摘要、轮次和子代理进度
- 完成或中断时发送结果通知
- 后台命令通知显示退出码、耗时和日志尾部
- 支持 Android 16 Live Update / realme UI 状态栏胶囊
- 通知刷新做了节流，避免流式输出导致系统限流

## 10. 流式渲染优化

- Markdown 流式解析目标按 40ms 帧预算合并
- 减少每个 token 触发完整解析和 Compose 测量造成的卡顿
- 不改变 Markdown 观感、逐字显现动画或排版结构

## 11. 待复现问题：release 包的 `wg5` 堆栈

现象：`feature/agent-intelligence-rounds` 上 terminal 工具（`environment=linux` / `android`）全部返回
`TOOL_ERROR wg5`，连 `echo ok` 都失败；`wg5` 在源码里搜不到，是 release 包经 R8 混淆后的类名
（安装包 dex 里存在 `Lwg5;` 这类三字符类名）。

已做的加固（不改根因判定，只让现场可观测、失败可解释）：

- `agent/tool/ToolExecutionGuard.kt`：危险操作确认闸门自身抛异常时 fail closed 成固定码
  `DANGER_GATE_UNAVAILABLE`，消息里带工具名与**完整类名**（可对照 mapping.txt）；捕获范围收窄为
  `Exception` + `LinkageError`（`NoClassDefFoundError` 等链接期错误），取消 / 中断
  （`AgentRunCancelledException`、`CancellationException`、`InterruptedException`）原样上抛，
  OOM 这类进程级 `Error` 也不再被折叠；消息里注明"若所有工具都返回本错误码，说明是闸门自身故障"。
- `AgentLocalTools`：兜底错误消息在无 message 时带上工具名与完整类名，不再只回一个混淆短标识。
- CI：release 构建把 `app/build/outputs/mapping/release/mapping.txt` 与 APK 同批归档。

仍待做（未完成，不要在本文档里当成已解决）：在 release 包上复现 `wg5` 并用归档的 mapping.txt
反解真实类名与调用点，确认是闸门、工具分发还是被裁剪的依赖，再决定是否要补 keep 规则。

## 12. 构建与签名

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
5. 输出到 `Download/Eta/apk/Eta-signed.apk`

签名信息：

- Keystore：`Download/Eta/签名/eta-fork.jks`
- Store password：`EtaFork-2026`
- Alias：`eta-fork`
- Key password：`EtaFork-2026`

后续更新必须继续使用同一份签名密钥，否则 Android 会拒绝覆盖安装。
