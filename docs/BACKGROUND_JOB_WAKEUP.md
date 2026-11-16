# 后台任务完成自动唤醒 Agent

本文档记录 `terminal_job`（后台命令）失败后自动唤醒一轮 Agent 接手处理的实现、纪律与验证方式。

## 1. 目标

后台任务结束时会发系统通知，但不会唤醒 Agent——用户必须自己回来叫。本轮补上这条链路：
**任务失败/超时/被中断时，Eta 自动起一轮 agent run，带上任务上下文，让 Agent 自主分析并处理**
（典型场景：盯 CI 的任务失败 → 自动修复 → 直到成功）。

## 2. 链路

```
terminal_job start
   └─ DetachedTaskSupervisor 落盘任务记录（含 watch / wakeup_origin）
        └─ BackgroundTerminalJobs.refresh()  每秒巡检
             ├─ onCompleted        → BackgroundCommandNotification（既有：结果通知）
             └─ onCompletedWakeup  → BackgroundJobWakeup.onJobFinished
                                      ├─ JobWakeupPolicy.decide   （纯逻辑：是否唤醒）
                                      ├─ JobWakeupLedger          （纯逻辑：冷却与链条计数落盘）
                                      ├─ JobWakeupNotifications   （通知：正在自动处理）
                                      └─ AgentRuntimeClient.run   （完整 Runtime 管线跑一轮）
```

## 3. 判定规则（`JobWakeupPolicy`）

| 顺序 | 条件 | 结果 | code |
|---|---|---|---|
| 1 | 任务由唤醒 run 自己启动（`wakeup_origin`） | 不唤醒 | `JOB_FROM_WAKEUP_RUN` |
| 2 | 设置开关关闭 | 不唤醒 | `WAKEUP_DISABLED` |
| 3 | 用户主动 `stop` | 不唤醒 | `JOB_STOPPED_BY_USER` |
| 4 | 成功且未标记 watch | 不唤醒（安静） | `JOB_SUCCEEDED` |
| 5 | 防循环账本不可用（文件损坏/读写失败） | 只发通知（fail-closed） | `JOB_WAKEUP_LEDGER_UNAVAILABLE` |
| 6 | 同链 10 分钟内已唤醒 | 不唤醒 | `JOB_WAKEUP_COOLDOWN` |
| 7 | 同链累计唤醒 ≥ 3 | 只发通知 | `JOB_WAKEUP_CHAIN_LIMIT` |
| 8 | 全局配额（10 分钟 2 次，跨任务合计）用尽 | 只发通知 | `JOB_WAKEUP_GLOBAL_LIMIT` |
| 9 | 其余（失败/超时/中断/watch 成功） | 唤醒 | `JOB_FAILED` / `JOB_WATCHED` |

- "链"以根任务 id 为单位；唤醒 run 里新起的任务不入链，因此链条长度在真实场景下几乎总是 1，
  3 轮上限是兜底闸门（例如 App 重启后同一条失败记录被重新发现）。
- 成功/被用户停止的任务**不消耗唤醒额度**（否则一条反复成功的任务会把链条计数吃满）。

## 4. 防死循环的三道闸门

1. **来源标记**：`AgentLocalTools(jobWakeupRun=true)`（由 handoff source `eta_job_wakeup` 决定）
   → `RootShellTerminalController(jobWakeupOrigin=true)` → 任务记录 `wakeup_origin=true` → 永不唤醒。
2. **10 分钟冷却 + 3 轮上限**：`JobWakeupLedger` 落盘（`filesDir/terminal-job-wakeup.json`），
   跨进程存活；24 小时保留期、最多 64 条，文件有界。读失败/文件损坏时 `read()` 返回
   `available=false`，判定降级为"只发通知"，写失败同理（`record().available=false` → 不启动 run）。
3. **全局配额**：`JobWakeupPolicy.WakeupBudget`，进程级滑动窗口 10 分钟最多 2 次，跨任务合计；
   只有在租约拿到、账本写成功、真的要唤醒时才 `commit`。
4. **串行执行**：同时只跑一条无人值守唤醒 run（与自动任务同一纪律）；
   上一条未结束时新的唤醒直接跳过并记日志，不排队。

## 5. 唤醒 run 的隔离

- handoff source：`AgentRuntimeWire.ETA_JOB_WAKEUP_HANDOFF_SOURCE = "eta_job_wakeup"`。
- conversationId：`AgentConversationIdentity.archiveConversationId(source, "job-<jobId>")`，
  与用户会话、角色会话、自动任务会话完全隔离；同一任务的多轮唤醒复用同一条归档会话。
- 模型配置取 Runtime 侧当前配置（`AgentRuntimeRequestConfigResolver.usesDefaultRuntimeConfig`），
  因为唤醒时没有用户在入口选模型。
- 任务中心 source 标签：`task_center_source_job_wakeup`。
- 危险操作照旧走闸门：无人确认 → 超时 → 不执行；prompt 里明确写了"没有用户在线"。

## 6. 上下文（prompt）

`JobWakeupPolicy.buildPrompt` 组装：job_id、description、command、cwd + environment、
exit_code、end_reason、以及**有界的日志尾部**（默认最后 4000 字符，`LOG_TAIL_BYTES=32KB` 先读、
`clipTail` 行对齐裁剪、`sanitizeLog` 剥掉 ANSI/控制序列），最后附上"分析失败原因并处理、
处理完汇报；无需处理就简短说明"的收尾指令。整段 prompt 上限 6000 字符。

## 7. 开关与通知

- 设置项："后台任务完成自动唤醒 Agent"，默认**开**，COUI 与 MIUIX 两套设置页都有；
  文案见 `res/values*/job_wakeup.xml`。
- 通知：`JobWakeupNotifications`
  - ID 段 9602..9651（`NotificationPolicy.jobWakeupId`，按任务 id 散列，重复唤醒原地更新）；
  - channel 复用 `eta_background_commands`（"后台命令结果"），不新增一条要用户单独静音的 channel；
  - 点击 → 打开该任务链的归档会话（`MainActivity.ACTION_OPEN_ARCHIVED_CONVERSATION`），
    用户能看到 Agent 到底做了什么；另有"打开任务中心"按钮；
  - 超过 3 轮 / 全局配额用尽时改发"已停止自动处理"通知，不再唤醒；账本不可用则发"未自动处理"。
  - **终态收尾**：发过"正在自动处理"之后，同一通知 ID 必须以终态更新
    （`JobWakeupPolicy.Finish`：`DONE` / `FAILED` / `TIMED_OUT` / `NOT_STARTED`），
    由 `execute()` 的 finally 统一收口；租约被拒、账本不可用、请求组装失败都落地成 `NOT_STARTED`。
  - 文案：`res/values{,-b+zh+Hans,-b+zh+Hant}/job_wakeup.xml`，标题一律带任务主体
    （`job_wakeup_unknown_subject` 含 `%1$s`），绝不回退到含 token 的命令行。

## 8. 验证

- 纯逻辑单测：`app/src/test/kotlin/io/github/mangi/eta/agent/terminal/JobWakeupPolicyTest.kt`
  （判定、记账与降级、全局配额窗口、prompt 预算与主体回退、裁剪、ANSI 清洗），
  以及 `NotificationPolicyTest` 的通知 ID 段与 PendingIntent requestCode 段断言。
- 纯 JVM 套件：`toolchain/jobwake/run.sh`（基线 550 项，本轮 556 项全绿）。
- Android 侧改动文件的类型检查：`toolchain/jobwake-android/`（手写 android/框架桩，
  分 passA=BackgroundJobWakeup、passB=JobWakeupNotifications、passC=BackgroundTerminalJobs、
  passD=AgentTerminalToolCatalog 编译）。

## 9. 已知边界

- App 进程被杀期间任务完成 → 进程重启后巡检会补上（`completion_notified` 未置位的记录）。
- 后台启动前台服务被系统拒绝（`BACKGROUND_START_NOT_FOUND` 类限制）时**不**假装在处理：
  只记日志并保留既有结果通知，用户回到 Eta 后可自行处理。
- 唤醒 run 与前台的自动任务共享"一次只跑一条"的纪律，任务密集失败时可能被跳过。
