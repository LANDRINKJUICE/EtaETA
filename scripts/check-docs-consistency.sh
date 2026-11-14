#!/bin/sh
# 文档 / 技能 / 记忆一致性闸（每轮收尾 / 出包前必过）。
# 挂进 check-unfixed.sh，由本地 run.sh + CI pr-check + CI agent-release 三处共用。
#
# 设计原则（对应 SOP §4「别靠文档提醒，靠结构让漏了变红灯」）：
#   - 机器只校验「能可靠判定」的部分，CI 看不到的仓库外文档/记忆明确跳过并打印原因，
#     绝不假装校验过（否则又是一次「CI 全绿但没用」）。
#   - 核心是「强制触发」：本次改动若触及架构/流程敏感路径，必须留一份按 HEAD sha 命名的
#     订正声明（docs-sync/<short7>.ok），证明已核对 SOP / Skill / 记忆指针节并三副本 cmp。
#     声明随 commit 走，换一个 commit 就失效，糊弄不了、也不是靠模型自觉。
#
# 环境变量：
#   BASE         同 check-unfixed.sh：CI 传上一 commit；本地不设则比对工作树相对 HEAD。
#   ETA_DOCS_DIR 文档根目录（本地默认 /workspace/文档-Docs；CI 未设则仓库外不可达，跳过该组）。
#   DOC_SYNC_REQUIRED=1 可强制要求声明（调试用）。
set -eu
cd "$(dirname "$0")/.."

fatal() { echo "=== 文档一致性闸：❌ $1 ===" >&2; exit 1; }
skip()  { echo "文档一致性闸：⏭  $1"; }

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
DOCS="${ETA_DOCS_DIR:-/workspace/文档-Docs}"
SOP_MAIN="$DOCS/通知与状态层架构SOP.md"
SOP_MOUNT="/workspace/mounts/Eta/文档-Docs/通知与状态层架构SOP.md"
SOP_SDCARD="/sdcard/Download/Eta/文档-Docs/通知与状态层架构SOP.md"
TOOL_SOP_MAIN="$DOCS/工具与功能登记约定SOP.md"
TOOL_SOP_MOUNT="/workspace/mounts/Eta/文档-Docs/工具与功能登记约定SOP.md"
TOOL_SOP_SDCARD="/sdcard/Download/Eta/文档-Docs/工具与功能登记约定SOP.md"
if [ -r "$SOP_MAIN" ] && [ -r "$SOP_MOUNT" ] && [ -r "$SOP_SDCARD" ]; then
  for pair in "$SOP_MAIN|$SOP_MOUNT" "$SOP_MAIN|$SOP_SDCARD" \
              "$TOOL_SOP_MAIN|$TOOL_SOP_MOUNT" "$TOOL_SOP_MAIN|$TOOL_SOP_SDCARD"; do
    a=${pair%%|*}; b=${pair##*|}
    [ -r "$a" ] && [ -r "$b" ] || { skip "副本不可读，跳过三副本 cmp：$(basename "$a")"; continue; }
    cmp -s "$a" "$b" || fatal "SOP 三副本不一致：$(basename "$a") 的两份内容不同（改 SOP 记得同步三副本 cmp）"
  done
  echo "SOP 三副本：✅ 通知 / 工具登记 两份 SOP 主副本与两份副本均一致"
else
  skip "SOP 在仓库外且当前环境不可读（CI checkout 只有 eta-src），本组仅在本地生效"
fi

# ---------- 组 3：架构改动 → 强制「文档/技能/记忆订正声明」 ----------
# 命中这些路径 = 本轮改了架构/流程/通知/后台 run，按收尾纪律必须核对并订正 SOP/Skill/记忆指针节。
SENSITIVE='app/src/main/kotlin/io/github/mangi/eta/agent/runtime/|app/src/main/kotlin/io/github/mangi/eta/agent/notify/|app/src/main/kotlin/io/github/mangi/eta/agent/schedule/|app/src/main/kotlin/io/github/mangi/eta/agent/terminal/BackgroundJobWakeup|app/src/main/kotlin/io/github/mangi/eta/ui/app/AgentAppState|app/src/main/assets/builtin_skills/'
if [ -n "${BASE:-}" ]; then
  # CI：比对「上一 commit..HEAD」，覆盖已提交的本次改动（CI 上代码都已 commit）。
  CHANGED=$(git diff "$BASE..HEAD" --name-only -- app/src 2>/dev/null || true)
else
  # 本地：以「本轮待提交的工作」为准——工作树 + 暂存（含 scripts/、docs-sync/ 等非 app/src）。
  # 只有整个工作树 + 暂存都完全干净（改动全已 commit）时，才回落到 HEAD^..HEAD 看最近一次提交；
  # 否则本轮还在收尾、不该回溯到上一轮的历史架构 commit 去要求它的旧 message 带标记。
  CHANGED=$(git diff HEAD --name-only -- app/src 2>/dev/null; git diff --cached HEAD --name-only -- app/src 2>/dev/null)
  OTHER=$(git diff HEAD --name-only 2>/dev/null; git diff --cached HEAD --name-only 2>/dev/null)
  if [ -z "$(printf '%s' "$OTHER" | tr -d '[:space:]')" ]; then
    CHANGED=$(git diff HEAD^..HEAD --name-only -- app/src 2>/dev/null || true)
  fi
fi
HIT=$(printf '%s\n' "$CHANGED" | grep -E "$SENSITIVE" || true)

if [ -n "$HIT" ] || [ "${DOC_SYNC_REQUIRED:-0}" = "1" ]; then
  # (a) commit message 标记：比对范围内「最近一次触及敏感路径的 commit」必须写明已做订正。
  #     用 commit message 而非按 sha 命名的文件——后者有「声明要随本 commit 提交、但 sha 只有
  #     commit 后才知道」的鸡生蛋问题。锚定「架构改动那次 commit」而非笼统的最新 commit：
  #     后续纯脚本 commit 不带标记不误伤，换一次架构改动就必须重新带标记，糊弄不了。
  if [ -n "${BASE:-}" ]; then
    RANGE="$BASE..HEAD"
  else
    RANGE="HEAD^..HEAD"
  fi
  # 找出比对范围内最后一个改到敏感路径的 commit，取其 message。
  SYNC_SHA=""
  for c in $(git log --format=%H "$RANGE" 2>/dev/null); do
    if git diff-tree --no-commit-id --name-only -r "$c" -- app/src 2>/dev/null | grep -qE "$SENSITIVE"; then
      SYNC_SHA="$c"; break
    fi
  done
  [ -z "$SYNC_SHA" ] && SYNC_SHA=$(git rev-parse HEAD)
  MSG=$(git log -1 --format=%B "$SYNC_SHA" 2>/dev/null || true)
  case "$MSG" in
    *"[docs-synced]"*) echo "文档订正标记：✅ 架构改动 commit $(git log -1 --format=%h "$SYNC_SHA") 含 [docs-synced]" ;;
    *)
      echo "本轮改动触及架构/流程敏感路径：" >&2
      printf '%s\n' "$HIT" | sed 's/^/  /' >&2
      fatal "架构改动 commit $(git log -1 --format=%h "$SYNC_SHA") 的 message 缺 [docs-synced] 标记。按收尾纪律必须先：核对并订正相关 SOP(+三副本 cmp)、薄路由 Skill 的纪律/章节号引用、记忆指针节过期现状、写交接；然后让这次架构改动的 commit message 带上 [docs-synced]（amend 或下一次架构 commit 补），否则出包不放行。"
      ;;
  esac
  # (b) 核对清单留痕：docs-sync/consistency.md 记录本轮核对了哪些对象、交接文件名，
  #     内容含四类关键字防「随手打勾」。这是给人/给下一轮看的事实，不是靠 sha 绑定。
  CL="docs-sync/consistency.md"
  if [ ! -f "$CL" ]; then
    fatal "缺少核对留痕 $CL（需写明已核对的 SOP / Skill / 记忆节 + 本轮交接文件名）"
  fi
  for kw in SOP Skill 记忆 交接; do
    grep -q "$kw" "$CL" || fatal "$CL 未包含「$kw」，留痕不完整"
  done
  echo "文档订正留痕：✅ $CL（SOP / Skill / 记忆 / 交接 齐全）"
else
  echo "文档订正声明：本轮未触及架构敏感路径，无需声明"
fi

echo "文档一致性闸：✅ 通过"
