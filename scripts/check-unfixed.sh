#!/bin/sh
# 未修项硬闸：本次改动里若新增「待修标记」(TODO/FIXME/@Ignore/@Disabled)，直接非零退出。
# 本地 run.sh、CI pr-check、CI agent-release 三处共用；只认「新增行」，存量不误报。
# 依据：顶部铁律 2「不留 bug、不留半成品，不找借口推脱」——把 bug 藏进注释/跳过测试
# 当作「下一轮」，机器不让过。要放行只有两条：修掉，或经独立审查确认是已接受的取舍。
#
# BASE 语义：
#   本地(未提交改动)  : 不设 BASE 或 BASE=HEAD → 比对工作树相对 HEAD
#   CI(代码已提交)     : BASE=<目标分支/上一commit> → 比对 BASE..HEAD 之间新增的行
set -eu
cd "$(dirname "$0")/.."
if [ -n "${BASE:-}" ]; then
  DIFF=$(git diff "$BASE..HEAD" -- app/src 2>/dev/null || git diff -- app/src)
else
  # 工作树 + 暂存区 相对 HEAD
  DIFF=$(git diff HEAD -- app/src 2>/dev/null; git diff --cached HEAD -- app/src 2>/dev/null)
fi
NEW=$(printf '%s\n' "$DIFF" | grep -E '^\+[^+]' | grep -nE 'TODO|FIXME|@Ignore|@Disabled' || true)
if [ -n "$NEW" ]; then
  echo "=== 未修项闸：❌ 本次改动新增了待修标记，禁止收尾/出包 ===" >&2
  printf '%s\n' "$NEW" >&2
  echo "处置：修掉它；不许用 TODO/FIXME/@Ignore 把确定性缺陷留到『下一轮』。" >&2
  exit 1
fi
echo "未修项闸：✅ 本次改动无新增待修标记"
