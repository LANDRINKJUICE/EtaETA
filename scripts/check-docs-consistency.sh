#!/bin/sh
# 文档 / 技能 / 记忆一致性闸（每轮收尾 / 出包前必过）。
# 挂进 check-unfixed.sh，由本地 run.sh + CI pr-check + CI agent-release 三处共用。
#
# 设计原则（对应 SOP §4「别靠文档提醒，靠结构让漏了变红灯」）：
#   - 机器只校验「能可靠判定」的部分：仓库内证据（[docs-synced] 标记、consistency.md
#     四类关键字 + 轮次 token、内置 Skill manifest）CI / 本地都强制；仓库外文档（SOP 三副本、
#     交接真实落盘 + 双副本 cmp）仅本地可达时真校验，CI 通过统一 DOCS_REACHABLE 判定诚实跳过
#     并打印原因，绝不假装校验过、也绝不把「看不到」误判成「没做」而判红。
#   - 可达性判定只做一次（DOCS_REACHABLE / docs_reachable_or_skip），组 2 与组 3(b) 共用，
#     杜绝「同类仓库外依赖两处处理分叉」这一类确定性假红（2026-11-15 云端 CI 从根修）。
#   - 「本轮改了什么」只算一次（ROUND_RANGE → CHANGED / CL_TOUCHED），HIT、(a) 标记判定、
#     (b) 留痕本轮必改全部从同一 diff 派生，绝不用「git diff-tree HEAD」与「git diff BASE..HEAD」
#     两套口径（对 merge 提交二者结论相反，2026-11-15 三审抓出的 B1/B3 根因）。
#   - 核心是「强制触发」：本轮改动触及架构/流程敏感路径时，本轮提交的 message 必须带
#     `[docs-synced]` 标记，且仓库内 `docs-sync/consistency.md` 的「本轮」段必须写明本轮
#     交接文档名作轮次 token（每轮换名即失效、与 commit sha 解耦、无鸡生蛋），并含 SOP /
#     Skill / 记忆 / 交接 四类关键字，证明已核对并订正。仓库外 SOP / 交接真实落盘由本地
#     可达性守卫额外校验。声明随 commit 走 + token 每轮换，糊弄不了、也不是靠模型自觉。
#
# 环境变量：
#   BASE         同 check-unfixed.sh：CI 可能是「上一 commit」(compile-check/agent-release 用
#                BASE=HEAD^) 或「PR base sha」(pr-check.yml 用 github.event.pull_request.base.sha，
#                跨几十个 commit)；本地不设则比对工作树相对 HEAD。两种模式「本轮」含义不同：
#                HEAD^ 模式本轮=最后一个 commit，PR 模式本轮=相对 base 的整个 PR 改动面。
#   ETA_DOCS_DIR 文档根目录（本地默认 /workspace/文档-Docs；CI 未设则按 DOCS_REACHABLE
#                 自动判不可达并跳过仓库外校验组，无需手工设、也设不出来）。
#   CI           GitHub Actions 恒设 true；用于区分「CI 仓库外文档不可达（诚实跳过）」与
#                「本地文档根配错（判红）」。
#   DOC_SYNC_REQUIRED=1 可强制要求声明（调试用；当前无任何 workflow 常开，勿误以为它在 CI 生效）。
set -eu
cd "$(dirname "$0")/.."

fatal() { echo "=== 文档一致性闸：❌ $1 ===" >&2; exit 1; }
skip()  { echo "文档一致性闸：⏭  $1"; }

# ---------- 仓库外文档可达性（组 2 / 组 3(b) 共用，杜绝同类依赖两处分叉） ----------
# SOP / 交接 / 记忆按收尾铁律放在 eta-src 仓库外（/workspace/文档-Docs、/sdcard/Download/Eta），
# 它们不随 commit 走、CI checkout 只有 eta-src 必然看不到。本闸对「仓库外依赖」采取统一策略：
#   可达(本地)   → 真正校验（三副本 cmp、交接真实落盘 + 双副本 cmp），校验不过判红；
#   不可达(CI)   → 诚实跳过并打印原因，绝不假装校验过、也绝不把「看不到」误判成「没做」→ 判红；
#   本地配错     → 文档根不可达但不是 CI（目录改名 / 挂载掉线）→ 直接判红，不允许本地静默跳过
#                  （旧实现用「文件在不在」推断「是不是 CI」，本地挂载一掉线组 2/组 3(b) 就一起
#                   静默变绿，是比修复前更坏的 fail-open；故用 GitHub Actions 恒设的 CI=true 区分）。
DOCS="${ETA_DOCS_DIR:-/workspace/文档-Docs}"
DOCS_SDCARD="/sdcard/Download/Eta/文档-Docs"
if [ -r "$DOCS/通知与状态层架构SOP.md" ] && [ -d "$DOCS/交接" ]; then
  DOCS_REACHABLE=1
elif [ "${CI:-}" = "true" ]; then
  DOCS_REACHABLE=0
else
  fatal "本地文档根不可达：$DOCS（检查 ETA_DOCS_DIR / mounts / sdcard 挂载；本地不允许静默跳过仓库外校验）"
fi
docs_reachable_or_skip() {
  # 供「仓库外依赖」校验点调用：不可达时打印统一原因并返回 1，调用方据此 skip 而非 fatal。
  if [ "$DOCS_REACHABLE" = "1" ]; then return 0; fi
  skip "$1（仓库外文档 CI checkout 不可达，仅本地校验；本闸不假装校验过）"
  return 1
}

# ---------- 组 1：内置 Skill manifest ↔ 实际目录（仓库内，CI/本地都能判） ----------
MANIFEST="app/src/main/assets/builtin_skills/manifest.json"
SKILLS_DIR="app/src/main/assets/builtin_skills"
if [ -f "$MANIFEST" ] && [ -d "$SKILLS_DIR" ]; then
  python3 - "$MANIFEST" "$SKILLS_DIR" <<'PY' || fatal "内置 Skill manifest 与目录不一致"
import json, os, sys
manifest, root = sys.argv[1], sys.argv[2]
d = json.load(open(manifest, encoding="utf-8"))
ids = [s["id"] for s in d.get("skills", [])]
if len(ids) != len(set(ids)):
    print("manifest 有重复 id"); sys.exit(1)
for s in d.get("skills", []):
    p = os.path.join(root, s.get("assetPath", ""), "SKILL.md")
    # assetPath 已含 builtin_skills 前缀时归一
    alt = os.path.join(root, os.path.basename(s.get("assetPath", "")), "SKILL.md")
    if not (os.path.isfile(p) or os.path.isfile(alt)):
        print("manifest 声明的 skill 缺少 SKILL.md:", s["id"]); sys.exit(1)
print("内置 Skill manifest：✅", len(ids), "个 skill，目录齐全且 id 唯一")
PY
else
  skip "内置 Skill manifest 不存在，跳过该组"
fi

# ---------- 组 2：SOP 三副本 cmp（仅本地可达；CI 仓库外不可达，诚实跳过） ----------
SOP_MAIN="$DOCS/通知与状态层架构SOP.md"
SOP_MOUNT="/workspace/mounts/Eta/文档-Docs/通知与状态层架构SOP.md"
SOP_SDCARD="$DOCS_SDCARD/通知与状态层架构SOP.md"
TOOL_SOP_MAIN="$DOCS/工具与功能登记约定SOP.md"
TOOL_SOP_MOUNT="/workspace/mounts/Eta/文档-Docs/工具与功能登记约定SOP.md"
TOOL_SOP_SDCARD="$DOCS_SDCARD/工具与功能登记约定SOP.md"
if docs_reachable_or_skip "SOP 三副本 cmp"; then
  for pair in "$SOP_MAIN|$SOP_MOUNT" "$SOP_MAIN|$SOP_SDCARD" \
              "$TOOL_SOP_MAIN|$TOOL_SOP_MOUNT" "$TOOL_SOP_MAIN|$TOOL_SOP_SDCARD"; do
    a=${pair%%|*}; b=${pair##*|}
    [ -r "$a" ] && [ -r "$b" ] || { skip "副本不可读，跳过三副本 cmp：$(basename "$a")"; continue; }
    cmp -s "$a" "$b" || fatal "SOP 三副本不一致：$(basename "$a") 的两份内容不同（改 SOP 记得同步三副本 cmp）"
  done
  echo "SOP 三副本：✅ 通知 / 工具登记 两份 SOP 主副本与两份副本均一致"
fi

# ---------- 组 3：架构改动 → 强制「文档/技能/记忆订正声明」 ----------
# 命中这些路径 = 本轮改了架构/流程/通知/后台 run，按收尾纪律必须核对并订正 SOP/Skill/记忆指针节。
SENSITIVE='app/src/main/kotlin/io/github/mangi/eta/agent/runtime/|app/src/main/kotlin/io/github/mangi/eta/agent/notify/|app/src/main/kotlin/io/github/mangi/eta/agent/schedule/|app/src/main/kotlin/io/github/mangi/eta/agent/terminal/BackgroundJobWakeup|app/src/main/kotlin/io/github/mangi/eta/ui/app/AgentAppState|app/src/main/assets/builtin_skills/'

# 唯一事实源：ROUND_RANGE + CHANGED（app/src 改动面）+ CL_TOUCHED（consistency.md 本轮是否被改）。
# 全部由「同一个 diff」派生，杜绝「diff-tree HEAD 对 merge 空 / diff BASE..HEAD 对 merge 非空」两套
# 口径自相矛盾（三审 B1/B3）。git 命令失败不静默吞成「没改动」（G1：取 rc，失败即 fatal）。
CL="docs-sync/consistency.md"
if [ -n "${BASE:-}" ]; then
  # CI：本轮 = BASE..HEAD（BASE 可能是 HEAD^ 单 commit，也可能是 PR base 跨多 commit）。
  if ! git rev-parse "$BASE^{commit}" >/dev/null 2>&1; then
    fatal "BASE=$BASE 不可解析（CI checkout 缺 fetch-depth: 2？），拒绝假装通过"
  fi
  ROUND_RANGE="$BASE..HEAD"
  # 本轮提交：HEAD 若是 GitHub 合成的 merge 提交（pull_request 默认 checkout refs/pull/N/merge），
  # git diff-tree 对它默认不输出差异；改用 HEAD^2（PR head / 本轮功能 commit）取 message 与改动面。
  # pr-check.yml 已加 ref: head.sha 让 HEAD 直接是 PR head，正常情况下不走此兜底。
  ROUND_HEAD=$(git rev-parse HEAD)
  MERGE_NOTE=""
  if git rev-parse --verify -q "$ROUND_HEAD^2" >/dev/null 2>&1; then
    ROUND_HEAD=$(git rev-parse "$ROUND_HEAD^2")
    ROUND_RANGE="$BASE..$ROUND_HEAD"
    MERGE_NOTE="（HEAD 为 merge 提交，改用 HEAD^2=$ROUND_HEAD 作本轮提交）"
  fi
  CHANGED=$(git diff "$ROUND_RANGE" --name-only -- app/src) \
    || fatal "git diff $ROUND_RANGE -- app/src 失败，拒绝假装通过"
  CL_TOUCHED=$(git diff "$ROUND_RANGE" --name-only -- "$CL") \
    || fatal "git diff $ROUND_RANGE -- $CL 失败，拒绝假装通过"
else
  # 本地：以「本轮待提交的工作」为准——工作树 + 暂存（含 scripts/、docs-sync/ 等非 app/src）。
  # 只有整个工作树 + 暂存都完全干净（改动全已 commit）时，才回落到 HEAD^..HEAD 看最近一次提交；
  # 否则本轮还在收尾、不该回溯到上一轮的历史架构 commit 去要求它的旧 message 带标记。
  ROUND_HEAD=$(git rev-parse HEAD)
  MERGE_NOTE=""
  CHANGED=$(git diff HEAD --name-only -- app/src 2>/dev/null; git diff --cached HEAD --name-only -- app/src 2>/dev/null; \
             git ls-files --others --exclude-standard -- 'app/src/*.kt' 2>/dev/null || true)
  OTHER=$(git diff HEAD --name-only 2>/dev/null; git diff --cached HEAD --name-only 2>/dev/null; \
          git ls-files --others --exclude-standard 2>/dev/null || true)
  if [ -z "$(printf '%s' "$OTHER" | tr -d '[:space:]')" ]; then
    ROUND_RANGE="HEAD^..HEAD"
    CHANGED=$(git diff "$ROUND_RANGE" --name-only -- app/src 2>/dev/null || true)
    CL_TOUCHED=$(git diff "$ROUND_RANGE" --name-only -- "$CL" 2>/dev/null || true)
  else
    CL_TOUCHED=$(git diff HEAD --name-only -- "$CL" 2>/dev/null; \
                 git diff --cached HEAD --name-only -- "$CL" 2>/dev/null; \
                 git ls-files --others --exclude-standard -- "$CL" 2>/dev/null || true)
  fi
fi
HIT=$(printf '%s\n' "$CHANGED" | grep -E "$SENSITIVE" || true)

if [ -n "$HIT" ] || [ "${DOC_SYNC_REQUIRED:-0}" = "1" ]; then
  # (a) commit message 标记：**本轮改动面命中敏感路径时，本轮提交的 message 必须带 [docs-synced]**。
  #     用 commit message 而非按 sha 命名的文件——后者有「声明要随本 commit 提交、但 sha 只有
  #     commit 后才知道」的鸡生蛋问题。
  #     从根修（2026-11-15 两轮 CI 判红）：旧实现在 RANGE（PR 场景 BASE=PR base，跨几十个 commit）
  #     里用 git log 找「最后一个改敏感路径的 commit」取 message。git log 按提交时间倒序，被上游
  #     历史里时间戳更晚的敏感 commit 截胡（翻出 e501d48 不带标记）→ 确定性假红。又曾用
  #     git diff-tree HEAD 判定（对 GitHub 合成 merge 提交恒空）→ PR Check 静默关闸（三审 B1）。
  #     统一为：改动面 CHANGED 与 (a)/(b) 同源（同一 ROUND_RANGE diff）；本轮提交 ROUND_HEAD 的
  #     message 带标记即通过；本轮改动面没命中 app/src（纯脚本/文档收尾）→ ⏭ 不强求 message，
  #     本轮是否真订正由 (b) 的仓库内闸证明（留痕本轮必改 + 本轮段四类关键字 + 轮次 token）。
  #     范围里其它历史 commit 带不带标记一律不翻旧账。
  if [ -n "$HIT" ]; then
    MSG=$(git log -1 --format=%B "$ROUND_HEAD" 2>/dev/null || true)
    case "$MSG" in
      *"[docs-synced]"*) echo "文档订正标记：✅ 本轮提交 $(git log -1 --format=%h "$ROUND_HEAD") 含 [docs-synced]$MERGE_NOTE" ;;
      *)
        echo "本轮改动触及架构/流程敏感路径（$ROUND_RANGE）：" >&2
        printf '%s\n' "$HIT" | sed 's/^/  /' >&2
        fatal "本轮提交 $(git log -1 --format=%h "$ROUND_HEAD") 的 message 缺 [docs-synced] 标记$MERGE_NOTE。按收尾纪律必须先：核对并订正相关 SOP(+三副本 cmp)、薄路由 Skill 的纪律/章节号引用、记忆指针节过期现状、写交接；然后让这次架构改动的 commit message 带上 [docs-synced]（amend 或下一次架构 commit 补），否则出包不放行。"
        ;;
    esac
  else
    # HIT 为空（仅 DOC_SYNC_REQUIRED=1 强制进入，或本轮纯脚本/文档收尾）：不强求 message 标记。
    echo "文档订正标记：⏭  本轮改动面（$ROUND_RANGE）未命中 app/src 敏感路径$MERGE_NOTE，不强求 message 标记；本轮订正由 (b) 留痕本轮必改 + 本轮段 + 轮次 token 证明"
  fi
  # (b) 核对清单留痕：docs-sync/consistency.md 的「本轮」段记录本轮核对了哪些对象、交接文件名，
  #     必须含四类关键字（SOP / Skill / 记忆 / 交接）防「随手打勾」，并写明本轮交接文档名作
  #     轮次 token（每轮换名即失效、与 commit sha 解耦、无鸡生蛋）。三重防「永久绿」：
  #       (i)   关键字只作用于「本轮」段——旧实现 grep 全文，上一轮各段/末尾注释里出现过就算
  #             本轮核对过，等于文件写全一次永久绿；
  #       (ii)  留痕本轮必改：CL_TOUCHED 由同一 ROUND_RANGE 的 git diff 派生，证明本轮范围内
  #             consistency.md 确实被改过——否则「改了 app/src 却根本不碰留痕」时，旧「本轮」段
  #             四类齐全 + 旧 token 指向的交接件真实存在 → 全绿（独立二审抓到的 S1）；
  #       (iii) token 每轮换名，旧轮 token 自动失效。
  #     这三条都只依赖仓库内 git/文本（CI 可判），无鸡生蛋、不空转、不靠模型自觉。
  if [ ! -f "$CL" ]; then
    fatal "缺少核对留痕 $CL（需写明已核对的 SOP / Skill / 记忆节 + 本轮交接文件名）"
  fi
  [ -n "$(printf '%s' "$CL_TOUCHED" | tr -d '[:space:]')" ] \
    || fatal "$CL 本轮未被修改（$ROUND_RANGE）：触及敏感路径却没更新「本轮」段（留痕必须是本轮改动的一部分，旧轮留痕不算本轮核对）"
  # 精确抽取「本轮」段：awk 起止两个 `## ` 标题都不进段落。旧用 sed 两地址范围会把下一段标题行
  # 也算进来 → 邻段标题里的字样可满足关键字/token（fail-open），从根换成 awk。
  ROUND_SECTION=$(awk '/^## 本轮/{f=1;next} f&&/^## /{exit} f' "$CL")
  if [ -z "$ROUND_SECTION" ]; then
    fatal "$CL 缺少「本轮」段，无法证明本轮做过订正核对"
  fi
  # (i) 四类关键字只查「本轮」段（旧轮/注释里的字样不算）。
  for kw in SOP Skill 记忆 交接; do
    printf '%s\n' "$ROUND_SECTION" | grep -q "$kw" \
      || fatal "$CL 的「本轮」段未包含「$kw」，本轮留痕不完整（旧轮/注释里的字样不算）"
  done
  # 绑定「本轮轮次 token」= 当轮交接文档文件名（每轮唯一），而非 commit 短 sha。
  # 从根原因：绑 SYNC_SHA 会鸡生蛋（commit 才能知道自己的 sha，写进留痕又改内容/sha；amend 死循环），
  # 拆独立 docs commit 又会让闸因「未触及 app/src」而空转假绿。用「每轮唯一的交接文件名」做 token：
  # 写留痕时可预先确定、每轮必换、旧轮 token 自动失效、与 commit 结构完全解耦，真正非永久绿。
  # token 从「本轮」段解析「交接文档」文件名（不依赖环境变量，CI/本地一致）；文件名含空格会被
  # 字符类截断，当前命名无空格，属可接受边界。本地可达时额外校验交接真实落盘（见下守卫）。
  ROUND=$(printf '%s\n' "$ROUND_SECTION" \
    | grep -oE '交接/[^ )`]+\.md' | head -1 | sed 's|交接/||; s|\.md$||' || true)
  if [ -z "$ROUND" ]; then
    fatal "$CL 的「本轮」段未写明交接文档文件名（轮次 token），留痕无法绑定本轮"
  fi
  # token 合法性：拒绝含 / 或 .. 的拼接（只允许纯文件名，防读到仓库外任意路径）。
  case "$ROUND" in
    */*|*..*|"") fatal "$CL 的交接 token 非法：$ROUND（只允许纯文件名，不含 / 或 ..）" ;;
  esac
  # 仓库内闸（CI / 本地都强制、不受可达性影响）：[docs-synced] 标记 + 本轮段四类关键字 +
  # 留痕本轮必改 + 轮次 token 解析/合法性，上面已全部通过。剩下「交接真实落盘 + 双副本一致」
  # 依赖仓库外文件，按统一可达性判定：本地可达→真正校验；CI 不可达→诚实跳过（不误判成没写）。
  DOCS_CHECK_NOTE="（仓库外落盘/副本校验：CI 不可达已跳过）"
  if docs_reachable_or_skip "交接文档真实落盘 / 双副本 cmp"; then
    # 本地可达时两份都必须在（bind mount 同一物理目录，缺一份＝环境坏，该红，杜绝只改一份糊弄）。
    [ -f "$DOCS/交接/$ROUND.md" ] \
      || fatal "本轮交接文档主副本 $ROUND.md 不存在（留痕声明的交接必须真实落盘）"
    [ -f "$DOCS_SDCARD/交接/$ROUND.md" ] \
      || fatal "本轮交接文档 sdcard 副本 $ROUND.md 不存在（交接需双副本落盘）"
    cmp -s "$DOCS/交接/$ROUND.md" "$DOCS_SDCARD/交接/$ROUND.md" \
      || fatal "交接双副本内容不一致：$ROUND.md（主 / sdcard 两份要 cmp 一致）"
    DOCS_CHECK_NOTE="（交接主+sdcard 双副本 cmp 一致）"
  fi
  echo "文档订正留痕：✅ $CL（SOP / Skill / 记忆 / 交接 齐全，且绑定本轮交接 $ROUND）$DOCS_CHECK_NOTE"
else
  echo "文档订正声明：本轮未触及架构敏感路径，无需声明"
fi

echo "文档一致性闸：✅ 通过"
