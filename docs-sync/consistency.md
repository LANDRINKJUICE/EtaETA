# 文档 / 技能 / 记忆一致性核对留痕

> 每次改动触及架构/流程/通知/后台 run 敏感路径，或构建被强制要求声明时，本文件「本轮」段随该轮更新，并在 commit message 带 `[docs-synced]`。
> 由 `scripts/check-docs-consistency.sh`（挂进 `check-unfixed.sh`，本地 run.sh + CI 三处共用）强制触发；
> 仓库外文档（SOP 三副本 / 交接真实落盘 + 双副本 cmp）经统一 `DOCS_REACHABLE` 判定：本地可达真校验、
> CI 不可达诚实跳过并打印原因（绝不假装校验过、也不把「看不到」误判成「没做」）；仓库内证据
> （`[docs-synced]` 标记 + 本段四类关键字 + 轮次 token + 留痕本轮必改）CI/本地都强制。
> 本文件是给人 / 给下一轮看的事实记录；换一轮必须重写「本轮」段（旧轮留痕不算本轮核对）。

## 本轮（2026-11-15，通知发布层「从根重构」· 云端 CI 失败后从根修文档闸）

- **背景**：用户要求「从根源链路修、拒绝打补丁、不留任何隐患、极致完美、不撒谎」。核查发现 `e44dd50`+`b046fc6` 的「一次统一」只收编了 12 个 notify 调用点，事实来源/准入/幂等/落地结果/护栏/本地编译闸都未真正收敛（独立 code-reviewer 三路审查 + 一手 rg 实证：谎报已发送、并发 run 吞通知、前台双表面、fail-open 撞号、前缀三处字面量、限流劈两半、android 侧本地从不编译、护栏可绕过、留痕永久绿）。
- **架构基准 commit**：`b046fc6`（本轮工作起点，`eaa890c` 的父提交）。留痕不再绑 commit 短 sha（绑 sha 会鸡生蛋：commit 才能知道自己的 sha，写进留痕又改内容/sha、amend 死循环），改绑「每轮唯一的交接文档名 token」，与 commit 结构完全解耦。
- **SOP（唯一事实源，三副本 cmp 一致）**：`通知与状态层架构SOP.md` §3.3 据实重写为「从根重构」现状（单一出口/单一裁决/单一主键/单一终态工厂/准入落地成功才记账/结构护栏机器强制），删掉已不存在的 `canPublish` 等过期 API 描述；主 + mounts + sdcard 三副本 cmp 一致。
- **Skill（薄路由，仅指针）**：`eta-attention`「不变纪律」第 1 条据实更新为从根重构后的结构（AttentionSubject/NotificationKey/publishTerminal/check-compile 闸等），仍不复制架构正文。
- **记忆指针节**：`## 通知 / 状态层架构 SOP`、`## 当前状态`（type=state 每轮覆写）据实订正，去掉「一次统一已交付」过期口径。
- **源码改动（架构敏感路径）**：新增 `AttentionSubject.kt`（会话分类唯一源）、`NotificationKey.kt`（id+tag 唯一句柄）、`check-ctor-args.py`（全仓构造点闸）、`check-compile.sh`（本地真编译闸）、`.github/workflows/compile-check.yml`（全分支 push 编译）；重写 `AttentionIntent/AttentionDirector/AttentionPublisher/NotifyUserTool`；`NotificationPolicy` 全部 ID/requestCode 数学委托 `NotificationIdRegistry`（双向绑死、删死代码 admission）；`BackgroundRunPolicy` 前缀委托 `AttentionSubject`；12 个发起点 + 4 个 FGS 全改 Registry 派生、删死常量/未用 import；`run.sh` 硬化（flock/修 set -e 吞 RC/覆盖率自证/覆盖边界声明）；`check-unfixed.sh` 加 FGS 白名单 + 复数/别名 API 覆盖 + 构造点闸；`check-docs-consistency.sh` 留痕绑本轮交接名 token（二审从「绑 SYNC_SHA 短 sha」改来，消鸡生蛋死锁）。
- **验证（据实）**：纯 JVM 基线 `OK (668 tests)` 五道闸全绿；本机 `:app:compileDebugKotlin` `BUILD SUCCESSFUL`（`check-compile.sh` 闸，含 android 侧全量）；契约构造点闸自检（正常绿/注入非法参数红/还原绿）；`git diff --check` 干净。**云端 CI 全量单测/lint/assembleRelease 另走（用户要求去云端验证）**；未擅自出包/装机。
- **交接文档**：`文档-Docs/交接/2026-11-15-从根重构-通知发布层.md`（主 + 副本 cmp 一致）。
- **独立二审闭环（2026-11-15，两路 code-reviewer）**：从根修掉二审抓出的确定性缺陷——反射测试签名(`@JvmOverloads`+测试改 3 参)、`publish` 局部变量 `cancel` 改名、准入 gate/commit 非原子(`reserve`/`rollback`/`decrement` 原子预留)、`publishTerminal` 幂等键由 conversationId 改 **runId** + 终态归属改 `wakeupRound` 参数化(删 `beginSubject` 竞态)、`check-ctor-args.py` 补必填齐备/注释/raw string/撇号处理(去掉误伤的 copy 覆盖，诚实标注边界)、`check-unfixed.sh`/`check-docs-consistency.sh` 加 untracked + BASE 不可解析 fail-closed + FGS 接线断言、文档留痕由「绑 SYNC_SHA」改「绑本轮交接名 token」(消除鸡生蛋死锁)、`compile-check.yml` 补 `fetch-depth: 2`、`run.sh` 覆盖率自证修正(不误伤 Robolectric)+**把 `check-compile.sh` 接线进收尾**、channel 双源收口(删 `CHANNEL_PROACTIVE/ATTENTION` 字面量、Builder 加枚举重载)、`showApprovalAttention`/Presenter 落地结果如实记账、删死变量/死常量/悬空 KDoc/过期注释、`RequestCodes` 与 1125 号段委托 Registry。
- **验证边界订正（据实）**：`check-ctor-args.py` 只兜命名实参/必填，位置实参/`.copy` 由 `check-compile.sh` 兜；`compile-check.yml` 只编译(全量单测/lint 需单独 workflow_dispatch 或 PR 触发，Robolectric 仅云端 x86_64)；「三副本」mounts 与 sdcard 是同一物理目录 bind mount，实为主 + 1 份独立副本。
- **轮次 token**：`check-docs-consistency.sh` 从本文件「本轮」段解析交接文档名做 token，每轮换名即失效、与 commit sha 解耦(无鸡生蛋)；「交接文件真实落盘 + 双副本 cmp」仅在本地 `DOCS_REACHABLE=1` 时真校验，CI 不可达诚实跳过（不误判成没写交接）。本轮 token = `2026-11-15-从根重构-通知发布层`。
- **云端 CI 失败 → 从根修文档闸（2026-11-15，后台唤醒 `job_8907ec4f`）**：CI run `43736971801`（head `eaa890c`）的「Lint 与单元测试」job 在「未修项闸」判红，根因不是编译/单测，而是 `check-docs-consistency.sh` 组 3(b)「交接真实落盘核验」硬查本地路径，而交接按铁律放在 eta-src 仓库外、CI checkout 看不到 → 把「环境不可达」误判成「没写交接」判红（本地能读到故本地七道闸全绿、云端独红）；同文件组 2（SOP 三副本）对仓库外不可达已诚实跳过，两处同类依赖处理分叉是确定性根因。**从根修**（非补丁、非绕过）：抽出统一 `DOCS_REACHABLE` / `docs_reachable_or_skip()` 供组 2 与组 3(b) 共用（一次判定、两处共用）；用 `CI=true` 区分「CI 不可达跳过」与「本地配错判红」，杜绝本地挂载掉线静默假绿；CI 不可达时仓库内强制闸（`[docs-synced]` + 本轮段四类关键字 + 轮次 token + 留痕本轮必改）仍全跑、不退化成无条件绿；独立 code-reviewer 二审又抓出并同批从根修掉的确定性缺陷：四类关键字原 grep 全文（上轮/注释里出现过就算本轮核对过=永久绿）收窄为只查「本轮」段、sed 范围把下段标题算进本轮段改 awk 精确切段、「改了 app/src 却不动 consistency.md」仍全绿(S1)用 `git diff RANGE -- CL` 证明留痕本轮必改、双副本 cmp 只一份时静默不 cmp 改两份都要求、token 拼路径未校验 `../` 加合法性校验、成功行按实际执行内容区分（不再无条件 ✅）、成功行/注释里「绑 SYNC_SHA 短 sha / docs-sync/<short7>.ok」等过期不实描述全部订正。**验证（据实，全套正负向）**：`sh -n`+`dash -n` 过；本地可达绿（SOP 三副本 + 交接双副本 cmp 真校验）；`CI=true`+空文档根模拟绿且组 2/组 3(b) 诚实跳过；负向「本地配错 / 本轮段缺关键字 / 缺 token / token 含 ../ / 留痕本轮未改(S1) / 双副本缺失」逐项判红；本地全套 `check-unfixed.sh`（12 项闸）全绿；`git diff --check` 干净。独立 code-reviewer 审查闭环（阻断项 S1 已修+验证）。
- **第二轮 CI 判红 → 从根修「本轮」判定口径（2026-11-15，重推 `b3b9d95` 后 PR Check 再红）**：首轮根修 amend 重推 `b3b9d95` 后，`Compile Check` run `43741179407`=success，但 `PR Check` run `43741186001` 又在「未修项闸」判红：`架构改动 commit e501d48 的 message 缺 [docs-synced] 标记`。根因：`pr-check.yml` 的 `BASE=PR base sha`（`300b0d18`，fork 上游，跨几十个 commit），旧 (a) 段用 `git log RANGE` 找「范围内最后一个改敏感路径的 commit」取 message，`git log` 按提交时间倒序被上游历史里时间戳更晚的 `e501d48` 截胡 → 把「历史某次没带标记」误判成「本轮没订正」；且与「本轮是否订正」无关。首次修成「锚定 HEAD」，独立 code-reviewer 三审又抓出阻断项：`pr-check.yml` 在 `pull_request` 下默认检出 GitHub 合成的 **merge 提交**，`git diff-tree HEAD` 对 merge 默认不输出差异 → (a) 段在 PR Check 上**静默关闸**(B1)；`HIT` 用 `git diff BASE..HEAD`、`HEAD_SENSITIVE` 用 `git diff-tree HEAD` 两套口径对 merge 结论相反(B3)；(b) 留痕必改仍是范围级、本轮没更新 `consistency.md` 却仍绿(B2/S1 活例)。**从根重构（非补丁）**：① 抽唯一事实源 `ROUND_RANGE` + `CHANGED`(app/src 改动面) + `CL_TOUCHED`，HIT/(a)/(b) 全从同一 `git diff ROUND_RANGE` 派生，消除两套口径矛盾；② (a) 改为「本轮改动面命中敏感 → 本轮提交 `ROUND_HEAD` 的 message 必须带 `[docs-synced]`」，本轮改动面没命中 app/src（纯脚本/文档收尾）则 ⏭ 不强求 message、由 (b) 证明；③ merge 兜底：HEAD 是 merge 时改用 `HEAD^2`(PR head) 作本轮提交与改动面，诚实打印，绝不静默关闸；④ `pr-check.yml` checkout 加 `ref: PR head.sha`（显式检出 PR head 真实 commit，从根消除合成 merge）+ `fetch-depth: 0` 保证 `BASE=base.sha` 可解析；⑤ `HEAD` 变量改 `ROUND_HEAD`、git 命令失败不静默吞(取 rc 即 fatal)、订正脚本头注释里虚构的「`DOC_SYNC_REQUIRED=1` 常开」承诺与 BASE 语义、「绑范围」等过期口径。**验证（据实）**：sh -n/dash -n 过；本地可达绿、PR 场景 `BASE=300b0d18`+CI=true 绿且 (a) 正确显示「本轮提交 b3b9d95 含 [docs-synced]」(不再翻 e501d48)、CI 模拟 DOC_SYNC_REQUIRED=1 绿、全套 `check-unfixed.sh` 12 闸绿、`BASE` 不可解析 fail-closed；隔离 git 副本造 `--no-ff` merge 验证 (a) 正确用 `HEAD^2` 且 merge+本轮改 CL 应绿、merge 但本轮没改 CL(S1) 应红；负向「缺标记 / 本轮段缺关键字 / 缺 token / token 含 ../ / 留痕本轮未改 / 本地配错 / 双副本缺失」逐项判红；`git diff --check` 干净。独立 code-reviewer 三审闭环（B1/B2/B3 阻断项已修+验证）。
- **订正声明**：本 commit message 带 `[docs-synced]`，已核对 SOP + Skill + 本文件 + 交接 + 记忆指针节；不夸大、不假绿。

## 上一轮（2026-11-15，通知发布层一次统一）

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
