# 文档 / 技能 / 记忆一致性核对留痕

> 每次改动触及架构/流程/通知/后台 run 敏感路径时，本文件随该轮更新，并在 commit message 带 `[docs-synced]`。
> 由 `scripts/check-docs-consistency.sh`（挂进 `check-unfixed.sh`，本地 run.sh + CI 两处共用）强制触发；
> SOP 在仓库外，CI 对该组诚实跳过、仅本地校验三副本 cmp，本文件是给人 / 给下一轮看的事实记录。

## 本轮（2026-11-15，通知发布层一次统一 · 进行中）

- **背景**：用户要求通知架构「一次从根统一、追溯根源链路、不打补丁兜底、核查闭环到位、验到底再交」。本轮把通知发布层从「12 个发起点各自 `manager.notify` + 10 处 `createNotificationChannel` + 各自挑号段 + 两套前台 + 6 处各判打扰」收敛为**单一编排器 + 单一物理出口 + 唯一号段源 + 单一前台**。
- **SOP（唯一事实源，三副本 cmp 一致）**：`通知与状态层架构SOP.md` 新增 §3.3「通知发布层统一」（落地结构 + 仍规划中边界 + 现状「完整编译验证进行中、未提交/未装机」）；订正 §3.1 结尾 / §5 第 1·2 条口径（从「AttentionDirector 仍规划中/仅部分推进」改为「物理出口已统一、完整意图编排数据化仍规划中」）。主 + mounts + sdcard 三副本 cmp 一致。
- **Skill（薄路由，仅指针不复制正文）**：`eta-attention`「不变纪律」第 1 条据实更新——区分「§3.1 后台 run 中心 + §3.3 发布层统一已落地（AttentionIntent/AttentionDirector/AttentionPublisher/AppForeground/号段唯一源/结构护栏）」与「§2 完整意图编排数据化 / ToolMeta / ProgressUpdate / 二选一仲裁仍规划中」；「失败处理」订正「本机无 Android SDK」为「本机 SDK 已装、可跑 compileDebugKotlin，完整出包仍走云端 CI」。frontmatter 未动。
- **记忆指针节**：`## 通知 / 状态层架构 SOP` 等指针节待本轮编译绿、commit 前按真实状态 upsert（去掉过期「规划中」现状）。本轮尚未改记忆——编译验证未闭环前不写「已交付」定稿。
- **源码改动（架构敏感路径）**：新增 `agent/notify/{AttentionIntent,AttentionDirector,AttentionPublisher,AppForeground}.kt` + `AttentionDirectorTest`；改 `AgentNotifications`/`ScheduledTaskNotifications`/`ScheduledTaskService`/`BackgroundCommandNotification`/`JobWakeupNotifications`/`MacroRecordingNotifications`/`AgentExecutionService`/`AgentRuntimeService`/`ReadAloudPlaybackService`/`AssistantSpeechForeground`/`NotificationIdRegistry`/`check-unfixed.sh`。独立 code-reviewer 二审抓出 5 个功能 bug（important channel 错位 / macro 两 key 撞号 / showFinished channel 兜底失效 / publishFailureResult 唤醒轮双发 / ScheduledTask 两套散列撤错），已全部从根修复。
- **验证（据实）**：纯 JVM `OK (665 tests)` 五道闸全绿（含「全仓 notify/channel 只准在 AttentionPublisher」结构护栏）+ 资源引用闸 + `git diff --check` 干净；本机 `:app:compileDebugKotlin` 两次 `BUILD SUCCESSFUL / GRADLE_RC=0`（首次全量 9m53s、修完 5 个 bug 增量重编 2m9s，零 `e:` 错误）；SDK/NDK/cmake 已装齐。云端 CI assemble+lint+出包按 `推送构建出包交付SOP.md` 另走。未提交、未推送、未装机。
- **交接文档**：`文档-Docs/交接/2026-11-15-实现-通知发布层一次统一.md`（主 + 副本 cmp 一致）。
- **订正声明**：本 commit message 带 `[docs-synced]`（改架构/通知敏感路径、已核对 SOP + Skill + 本文件 + 交接）；compile/commit 前以最终验证结果为准，不夸大、不假绿。

## 上轮（2026-11-15，询问卡开关 + 废弃两次身份改动后推送构建）

- **背景**：用户废弃前两次会话的身份认知改动（`b91d9e8` 并入 DEFAULT_SYSTEM_PROMPT + 子代理继承、以及随后 6 文件 provider 兜底补丁）并整体回滚；本地回到 `0f24dff`（文档闸，正当 commit，保留）。本轮在 `0f24dff` 之上提交「询问卡」设置开关，推送构建交付；远端 `feature`/`pull/2` 原停在 `b91d9e8`，本次为 force-with-lease 非快进推送，抹掉已废弃的身份 commit。
- **SOP（唯一事实源，三副本 cmp 一致）**：`通知与状态层架构SOP.md` 口径本就正确（§3.2/§5 区分「后台 run 已统一」与「`AttentionDirector`/状态真相源/二选一仲裁仍规划中」），未改正文；`工具与功能登记约定SOP.md` 未改。主 + mounts + sdcard 三副本 cmp 一致。
- **Skill（薄路由，仅指针不复制正文）**：`eta-attention`「不变纪律」第 1 条订正——原把「一份快照 + 一个编排器 / 所有通知收口统一编排器」写成既有事实（误导），改为区分「§3.1 后台 run 统一 + `NotificationIdRegistry` 号段登记已落地」与「`AttentionDirector` 等仍规划中、别当现成 API 接」，指向 SOP §3.2/§5。其余 3 条纪律与 frontmatter 未动。
- **记忆指针节**：`## 通知 / 状态层架构 SOP` 原文已写明「`AttentionDirector` 仍是规划项、别写成已实现」，与 SOP 一致，无需订正；本轮未改记忆。
- **源码改动**：新增「询问卡」开关 `AGENT_ASK_USER_ENABLED`（设置页 COUI/MIUIX 两处 + 三语文案 + Prefs 三处登记 + `PrefsDefaultsTest` 快照 + 两条新单测），模型目录/系统提示/执行侧三处按同一开关联动（照 `notify_user` 双保险范式）；命中 `agent/runtime/AgentRuntimeRunExecutor.kt` 敏感路径，故本 commit 带 `[docs-synced]`。
- **交接文档**：`文档-Docs/交接/2026-11-15-推送构建-询问卡开关.md`（主 + 副本一致）。
- **验证**：纯 JVM 快闸 `OK (650 tests)`、三道闸全绿、三语 XML 无重复键且 `aapt2 compile` 全过、`git diff --check` 干净、SOP 三副本 cmp 一致、独立 code-reviewer 两轮审查闭环。

## 上轮（2026-11-14，b8f2391 后台任务全统一）

- **SOP（唯一事实源，三副本 cmp 一致）**：
  - `通知与状态层架构SOP.md`：新增 §3.1 后台 run 统一机制（已落地三中心）、§3.2 目标架构现状口径、§4.2 新后台入口硬约束、§4.3 号段纪律、§4.4 护栏/收尾 grep 自检；§1 加「改造前诊断」提示；§5 标后台 run 大类已收敛。主 + mounts + sdcard 三副本 cmp 一致。
  - `工具与功能登记约定SOP.md`（姊妹 SOP）：§4.2 加交叉纪律——新功能会在后台跑 agent 必须按通知 SOP §4.2 接统一中心。三副本 cmp 一致。
- **Skill（薄路由，仅指针不复制正文）**：
  - `eta-attention`：三条→四条纪律，新增「后台 run 一律走中心，不自带通知/会话/启动广播」，指向 SOP §3.1/§4.2；修正旧「第 4.2 节」引用（号段纪律已挪 §4.3）。
  - `eta-tool-registry`：触发后第一步加交叉指针到通知 SOP §4.2。
  - 内置 Skill manifest（仓库内）与本脚本组 1 校验一致。
- **记忆指针节（upsert 订正，只放指针）**：
  - `## 通知/状态层架构 SOP`：去掉「AttentionDirector 规划中」当现状的旧口径，指向 §3.1 已落地统一中心 + §4.2 硬约束 + 第 4 条纪律。
  - `## 工具/功能登记 SOP`：加交叉纪律指针。
  - `## 收尾检查`：把「文档/技能/记忆一致性核对」加成每轮收尾强制动作（和清垃圾/进程并列）。
  - `## Eta 源码改造`：订正「无本地编译环境靠 CI 验证」为「只走云端验证、不是装不了、是不想耗本机资源」，去掉过期静态数字。
  - `## 当前状态`（type=state）：覆写为 b8f2391 + CI 待出 + 本轮订正。
- **交接文档**：`文档-Docs/交接/2026-11-14-推送构建-b8f2391-后台任务全统一.md`（主 + 副本一致），取代停在 5e1d737 阶段的旧 ae91ed5 交接。
- **护栏单测**：`app/src/test/.../BackgroundRunPolicyTest.kt` 加 `everyRegisteredBackgroundPrefixIsRecognized`（SOP §4.4 机器红灯）。

> 注：MEMORY.md 在 App 私有区、CI checkout 看不到，脚本组 3 不假装在 CI 校验了记忆内容；
> 记忆订正靠本文件留痕 + commit 标记 `[docs-synced]` + 收尾强制动作三者共同约束。
