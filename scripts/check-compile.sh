#! /bin/sh
# 本机「全量 Kotlin 真编译」闸（2026-11-15 从根新增）。
#
# 为什么必须存在：纯 JVM 基线 jobwake-main.list 是 android-free 白名单，故意不编译
# AttentionPublisher.kt 等 import android.app.* 的文件。b046fc6 的 `No parameter with name
# 'title' found` 编译阻断在 665 tests 全绿 + 三道本地闸全绿的情况下被放行，云端 gradle 才炸。
# 本闸把 :app:compileDebugKotlin 纳入本地收尾，第一次让 android 侧文件在**推送前**被真编译。
#
# 用法：sh scripts/check-compile.sh
#   - 需要本机 Android SDK（/workspace/toolchain/android-sdk）；缺 SDK 时【非零退出并明说未验证】，
#     绝不允许静默跳过（静默跳过正是上次踩的坑）。
#   - CI 上不需要（CI 本就跑 :app:testDebugUnitTest / assembleRelease 会编译全量 main）。
set -eu
cd "$(dirname "$0")/.."
SDK="${ANDROID_HOME:-/workspace/toolchain/android-sdk}"
if [ ! -f "$SDK/platforms/android-37.0/android.jar" ]; then
  echo "=== 本地真编译闸：❌ 未找到 Android SDK（$SDK），本次【未验证 android 侧编译】===" >&2
  echo "处置：装齐 SDK 后重跑，或走云端 CI；不许把『纯 JVM 基线绿』当成『可编译』。" >&2
  exit 1
fi
export ANDROID_HOME="$SDK"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-/workspace/toolchain/gradle-home}"
LOG="${ETA_COMPILE_LOG:-/workspace/临时-Temp/check-compile.log}"
mkdir -p "$(dirname "$LOG")"
echo "== 本机 :app:compileDebugKotlin（全量 main 源集，含 android 侧文件）=="
./gradlew --offline --no-daemon --no-configuration-cache :app:compileDebugKotlin > "$LOG" 2>&1 || {
  RC=$?
  echo "=== 本地真编译闸：❌ GRADLE_RC=$RC，编译失败 ===" >&2
  grep -nE 'e: |error:|FAILURE:|BUILD FAILED' "$LOG" | head -60 >&2 || true
  tail -20 "$LOG" >&2
  exit "$RC"
}
echo "本地真编译闸：✅ BUILD SUCCESSFUL（android 侧文件已真编译，日志 $LOG）"
