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
  # CI：BASE 必须可解析。旧实现 `git diff BASE..HEAD || git diff --` 在 fetch-depth 不足时静默
  # 退化成空 diff → 打印「✅ 无新增待修标记」的假绿（二审抓到的 fail-open）。从根改成 fail-closed。
  if ! git rev-parse "$BASE^{commit}" >/dev/null 2>&1; then
    echo "=== 未修项闸：❌ BASE=$BASE 不可解析（CI checkout 缺 fetch-depth: 2？）===" >&2
    echo "处置：拒绝在取不到比对基准时假装通过；workflow 需 fetch-depth >= 2 且 BASE 指向已 fetch 的 commit。" >&2
    exit 1
  fi
  DIFF=$(git diff "$BASE..HEAD" -- app/src)
else
  # 工作树 + 暂存区 相对 HEAD；并并入**未跟踪新文件**（旧实现漏 untracked，新建 .kt 里的 TODO 逃检）。
  DIFF=$(git diff HEAD -- app/src 2>/dev/null; git diff --cached HEAD -- app/src 2>/dev/null)
  for f in $(git ls-files --others --exclude-standard -- 'app/src/*.kt' 2>/dev/null || true); do
    DIFF="$DIFF
$(sed 's/^/+/' "$f")"
  done
fi
NEW=$(printf '%s\n' "$DIFF" | grep -E '^\+[^+]' | grep -nE 'TODO|FIXME|@Ignore|@Disabled' || true)
if [ -n "$NEW" ]; then
  echo "=== 未修项闸：❌ 本次改动新增了待修标记，禁止收尾/出包 ===" >&2
  printf '%s\n' "$NEW" >&2
  echo "处置：修掉它；不许用 TODO/FIXME/@Ignore 把确定性缺陷留到『下一轮』。" >&2
  exit 1
fi
echo "未修项闸：✅ 本次改动无新增待修标记"

# 通知架构统一护栏（2026-11-15 从根重构）：物理出口只能有一个。
# 全仓 notify/notifyAsPackage/createNotificationChannel(s) 只允许出现在 AttentionPublisher.kt；
# 新增发起点若绕过唯一编排器直接发通知 -> 红灯。覆盖复数 API 与别名，堵住旧正则的绕过面。
# 不依赖 rg（CI/本地都确定有 grep -r），避免工具缺失时 fail-open 误报"通过"。
NOTIFY_STRUCT=$(grep -rnE '\.notify\(|\.notifyAsPackage\(|createNotificationChannels?\(' app/src/main/kotlin --include='*.kt' \
  | grep -vE '^\S+:[0-9]+:[[:space:]]*(\*|//)' \
  | grep -v 'agent/notify/AttentionPublisher.kt' || true)
if [ -n "$NOTIFY_STRUCT" ]; then
  echo "=== 通知架构护栏：❌ 存在绕过唯一出口 AttentionPublisher 的 notify/channel 调用 ===" >&2
  printf '%s\n' "$NOTIFY_STRUCT" >&2
  echo "处置：所有通知必须构造 AttentionIntent 交 AttentionPublisher 发布。" >&2
  exit 1
fi
echo "通知架构护栏：✅ notify/notifyAsPackage/channel 调用全部收敛到 AttentionPublisher（唯一出口）"

# 撤号也必须走唯一出口：只检查 `manager.cancel(` 字面且同文件确实持有 NotificationManager 的情形
# （带 tag 的通知被裸 cancel(id) 静默撤不掉 → 僵尸）。双重约束精确圈定，不误伤：
#   - AlarmManager.cancel(ScheduledTaskScheduler)：文件无 NotificationManager → 不命中
#   - CoroutineScope/job.cancel()：不是 `manager.cancel(` 字面 → 不命中
#   - AttentionPublisher.cancel(...)：调用的是出口自身、且本文件被排除 → 不命中
CANCEL_STRUCT=""
for f in $(grep -rl 'NotificationManager' app/src/main/kotlin --include='*.kt' 2>/dev/null || true); do
  case "$f" in
    *agent/notify/AttentionPublisher.kt) continue ;;
  esac
  HITS=$(grep -nE '[A-Za-z_][A-Za-z0-9_]*\.cancel\(' "$f" \
    | grep -vE '^[0-9]+:[[:space:]]*(\*|//)' \
    | grep -E 'manager\.cancel\(|notificationManager\.cancel\(' || true)
  if [ -n "$HITS" ]; then
    while IFS= read -r h; do
      [ -z "$h" ] && continue
      CANCEL_STRUCT="$CANCEL_STRUCT\n  $f:$h"
    done <<EOF
$HITS
EOF
  fi
done
if [ -n "$CANCEL_STRUCT" ]; then
  echo "=== 撤号护栏：❌ 存在绕过唯一出口 AttentionPublisher 的通知撤号 ===" >&2
  printf '%b\n' "$CANCEL_STRUCT" >&2
  echo "处置：撤通知一律走 AttentionPublisher.cancel(context, NotificationKey(id, tag))。" >&2
  exit 1
fi
echo "撤号护栏：✅ 全仓通知撤号收敛到 AttentionPublisher（带 tag 句柄）"

# 前台服务（FGS）载体白名单护栏：startForeground 是系统强制可见载体、不经 decide/publish，
# 但只允许出现在这 4 个已登记文件，且同文件必须出现 ensureAllChannels（保证 channel 注册统一）。
# 旧护栏完全漏检 startForeground，等于「唯一裁决」对 FGS 一半是空话；这里从根补上。
FGS_ALLOWED_RE='agent/runtime/AgentExecutionService\.kt|agent/schedule/ScheduledTaskService\.kt|agent/voice/AssistantSpeechForeground\.kt|agent/voice/ReadAloudPlaybackService\.kt'
FGS_HITS=$(grep -rnE 'startForeground\(' app/src/main/kotlin --include='*.kt' \
  | grep -vE '^\S+:[0-9]+:[[:space:]]*(\*|//)' || true)
FGS_BAD=$(printf '%s\n' "$FGS_HITS" | grep -vE "$FGS_ALLOWED_RE" || true)
if [ -n "$FGS_BAD" ]; then
  echo "=== FGS 护栏：❌ startForeground 出现在未登记文件（FGS 白名单外）===" >&2
  printf '%s\n' "$FGS_BAD" >&2
  echo "处置：FGS 载体必须登记进 AttentionPublisher.foregroundOwners 且只在这几个文件出现。" >&2
  exit 1
fi
echo "FGS 护栏：✅ startForeground 全部落在已登记白名单文件"

# FGS 载体接线断言（二审：旧护栏只有文件名白名单，注释却声称「同文件必须 ensureAllChannels、
# channel 取自 Registry」——声明强于实现。这里把两句注释落成机器校验，杜绝自欺欺人假绿）。
for f in $(printf '%s\n' "$FGS_HITS" | sed 's/:.*//' | sort -u); do
  [ -z "$f" ] && continue
  if ! grep -q 'ensureAllChannels' "$f"; then
    echo "=== FGS 接线护栏：❌ $f 有 startForeground 却未调用 ensureAllChannels（channel 未统一注册）===" >&2
    exit 1
  fi
  # 号段/channel 取自 Registry：Service 文件负责 startForeground 的 id（idOf），channel 由它调用的
  # 通知工厂（同包，已用 channelOf）负责；故这里只要求文件出现 Registry 派生（idOf/channelOf/NotificationChannel）。
  if ! grep -qE 'NotificationIdRegistry\.(idOf|channelOf|fixedIdOf)|NotificationIdRegistry\.NotificationChannel' "$f"; then
    echo "=== FGS 接线护栏：❌ $f 的 FGS 通知 id/channel 未取自 NotificationIdRegistry ===" >&2
    exit 1
  fi
done
echo "FGS 接线护栏：✅ 每个 FGS 文件均 ensureAllChannels 且号段/channel 取自 Registry"

# 契约构造点命名实参一致性（从根防「改 data class 漏改 android 侧构造点」的编译阻断，
# 不依赖 git diff / android.jar，本地 0.2s 即挡；见脚本头注释）。
python3 scripts/check-ctor-args.py || exit 1

# 资源引用完整性闸：三方合并 / 改 strings.xml 后，R.string.xxx 必须仍有定义。
# 本机 aapt2 只验 XML 合法性、纯 JVM 不编译 Android res，丢了字符串只有云端 Kotlin 编译才报；
# 这道纯文本闸把这类回归挡在推送前（2026-11-14 上游合并踩过 Unresolved reference）。
python3 "$(dirname "$0")/check-res-refs.py" || exit 1

# 文档 / 技能 / 记忆一致性闸：本轮触及架构/流程敏感路径却没做「订正声明」即判红（收尾纪律，
# 不靠模型自觉）；另校验内置 Skill manifest 与 SOP 三副本（后者仅在本地可达、CI 诚实跳过）。
# 本地 run.sh + CI pr-check + CI agent-release 三处共用，和上面两道闸一起跑。
sh "$(dirname "$0")/check-docs-consistency.sh" || exit 1
