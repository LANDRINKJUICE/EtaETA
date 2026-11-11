package io.github.mangi.eta.agent.tool

import android.content.Context
import android.os.SystemClock
import io.github.mangi.eta.agent.accessibility.AgentAccessibilityService
import io.github.mangi.eta.core.AndroidAgentLogger
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 宏录制的会话状态机（进程内单例）。
 *
 * 链路：UI/工具的 `macro_record(action=start)` -> [start] 打开录制 -> 无障碍服务把用户的点击、
 * 长按、输入、滚动、按键交给 [capture] -> `stop` 时用 [MacroActionNormalizer] 归一化成
 * gesture_batch 动作序列，交给 `macro_save` 的同一套存储（[GuiMacroStore]）。
 *
 * 设计要点：
 * - 只有一份录制会话，重复 start 直接拒绝，避免两个来源互相覆盖；
 * - 密码框里的输入在捕获阶段就被标记 sensitive，归一化时丢弃并计数，正文永远不会落盘；
 * - 提供时长与步数上限，用户忘了停止也不会无限积累（也防止误触把整场操作录成巨型宏）。
 */
internal object MacroRecorder {
    const val MAX_RECORD_MILLIS = 180_000L
    const val MAX_RAW_STEPS = 400
    private const val MAX_TEXT_CHARS_PER_STEP = 400

    data class Snapshot(
        val startedAtMillis: Long,
        val steps: Int,
        val suggestedName: String,
    )

    data class StopResult(
        val actions: List<MacroAction>,
        val stats: MacroNormalizeStats,
        val durationMillis: Long,
        val suggestedName: String,
        val truncatedByLimit: Boolean,
        /** 触发步数/时长上限而被动停止（审查 m2）。 */
        val stoppedByLimit: Boolean = false,
    )

    private data class Session(
        val startedAtMillis: Long,
        val screenWidth: Int,
        val screenHeight: Int,
        val suggestedName: String,
        val steps: MutableList<RecordedAction> = mutableListOf(),
        var limitReached: Boolean = false,
    )

    private var session: Session? = null
    private var lastStop: StopResult? = null
    private var suppressUntilUptime = 0L

    @Synchronized
    fun isRecording(): Boolean = session != null

    @Synchronized
    fun snapshot(): Snapshot? = session?.let {
        Snapshot(it.startedAtMillis, it.steps.size, it.suggestedName)
    }

    @Synchronized
    fun lastStopResult(): StopResult? = lastStop

    @Synchronized
    fun clearLastStop() {
        lastStop = null
    }

    /** 开始录制；已经有一份会话时返回 null，由调用方回执"已在录制"。 */
    @Synchronized
    fun start(
        context: Context,
        requestedName: String?,
        screenWidth: Int,
        screenHeight: Int,
    ): Snapshot? {
        if (session != null) return null
        val now = SystemClock.elapsedRealtime()
        val name = normalizeName(requestedName) ?: defaultName()
        val created = Session(
            startedAtMillis = now,
            screenWidth = screenWidth.coerceAtLeast(0),
            screenHeight = screenHeight.coerceAtLeast(0),
            suggestedName = name,
        )
        session = created
        lastStop = null
        // 上一轮播下的抑制窗口不能带进新一轮录制（审查 m1），否则开头几步会被无声吞掉。
        suppressUntilUptime = 0L
        updateAccessibilityRecording(context, active = true)
        MacroRecordingNotifications.showRecording(context, created.suggestedName, 0, 0L)
        return Snapshot(created.startedAtMillis, 0, created.suggestedName)
    }

    /** 停止录制并归一化；没有会话时返回 null。 */
    @Synchronized
    fun stop(context: Context): StopResult? {
        val active = session ?: return null
        session = null
        updateAccessibilityRecording(context, active = false)
        MacroRecordingNotifications.cancel(context)
        val options = MacroNormalizeOptions(
            screenWidth = active.screenWidth,
            screenHeight = active.screenHeight,
        )
        val normalized = MacroActionNormalizer.normalize(active.steps.toList(), options)
        val result = StopResult(
            actions = normalized.actions,
            stats = normalized.stats,
            durationMillis = SystemClock.elapsedRealtime() - active.startedAtMillis,
            suggestedName = active.suggestedName,
            truncatedByLimit = active.steps.size >= MAX_RAW_STEPS,
            stoppedByLimit = active.limitReached,
        )
        lastStop = result
        AndroidAgentLogger.debug {
            "Macro recording stopped: raw=${normalized.stats.recorded} " +
                "actions=${normalized.actions.size} sensitive=${normalized.stats.sensitiveSkipped}"
        }
        return result
    }

    @Synchronized
    fun cancel(context: Context): Boolean {
        if (session == null) return false
        session = null
        lastStop = null
        updateAccessibilityRecording(context, active = false)
        MacroRecordingNotifications.cancel(context)
        return true
    }

    /**
     * 录制期间刷新"还在录"的通知（步数会变化）。由服务在捕获后按节流调用。
     */
    @Synchronized
    fun refreshNotification(context: Context) {
        val active = session ?: return
        // 已用时取单调时钟差值；调用侧（无障碍服务）已按 ≥1s 节流，这里不再二次节流。
        val elapsed = SystemClock.elapsedRealtime() - active.startedAtMillis
        MacroRecordingNotifications.showRecording(context, active.suggestedName, active.steps.size, elapsed)
    }

    /**
     * 无障碍服务每捕获一步都会调这里；不在录制、超过上限、或处于 Eta 自己派发手势的抑制窗口时
     * 直接丢弃。返回 true 表示这一步被记录了。
     */
    @Synchronized
    fun capture(action: RecordedAction): Boolean {
        val active = session ?: return false
        val now = SystemClock.elapsedRealtime()
        if (action.atMillis <= suppressUntilUptime) return false
        if (now - active.startedAtMillis > MAX_RECORD_MILLIS) {
            // 到上限不再静默丢弃：标记出来，由 enforceLimits 主动收尾（审查 m2）。
            active.limitReached = true
            return false
        }
        if (active.steps.size >= MAX_RAW_STEPS) {
            active.limitReached = true
            return false
        }
        val sanitized = when (action.kind) {
            RecordedKind.TEXT -> action.copy(
                text = action.text.take(MAX_TEXT_CHARS_PER_STEP),
                sensitive = action.sensitive,
            )
            else -> action
        }
        active.steps += sanitized
        return true
    }

    /**
     * Eta 自己派发手势后短暂抑制录制：否则"Agent 点了一下"会被录成"用户点了一下"。
     * 由无障碍服务在 dispatchGesture 前调用。
     */
    @Synchronized
    fun suppressFor(durationMillis: Long) {
        val until = SystemClock.elapsedRealtime() + durationMillis.coerceIn(0L, 3_000L)
        if (until > suppressUntilUptime) suppressUntilUptime = until
    }

    /**
     * 达到步数或时长上限时主动收尾（审查 m2）：停止录制、按建议名保存，并通过通知告诉用户
     * "已经到上限、录制在这里结束"。由无障碍服务在每次捕获后调用（内部先做廉价判断）。
     */
    fun enforceLimits(context: Context): Boolean {
        val active = session ?: return false
        val elapsed = SystemClock.elapsedRealtime() - active.startedAtMillis
        val limitReached = active.limitReached ||
            active.steps.size >= MAX_RAW_STEPS ||
            elapsed >= MAX_RECORD_MILLIS
        if (!limitReached) return false
        val result = runCatching { stop(context) }.getOrNull() ?: return false
        runCatching { MacroRecordingStopHandler.finish(context, result, requestedName = null) }
        AndroidAgentLogger.debug {
            "Macro recording auto-stopped at limit: steps=${result.stats.recorded} " +
                "durationMs=${result.durationMillis}"
        }
        return true
    }

    /** 已被录制时长（毫秒）；未录制时 0。 */
    @Synchronized
    fun elapsedMillis(): Long =
        session?.let { SystemClock.elapsedRealtime() - it.startedAtMillis } ?: 0L

    private fun updateAccessibilityRecording(context: Context, active: Boolean) {
        runCatching {
            AgentAccessibilityService.current()?.setMacroRecordingActive(active)
        }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("macro_recording_toggle_failed") {
                "Macro recording toggle failed: type=${throwable.javaClass.simpleName}"
            }
        }
    }

    fun normalizeName(raw: String?): String? {
        val cleaned = raw?.trim()?.lowercase(Locale.ROOT)?.replace(Regex("[^a-z0-9_-]"), "-")
            ?.trim('-') ?: return null
        if (cleaned.isEmpty()) return null
        return cleaned.take(64)
    }

    fun defaultName(): String =
        "rec-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.ROOT).format(Date())
}
