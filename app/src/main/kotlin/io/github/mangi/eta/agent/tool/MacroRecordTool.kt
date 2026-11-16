package io.github.mangi.eta.agent.tool

import android.content.Context
import android.view.Display
import io.github.mangi.eta.agent.accessibility.AgentAccessibilityService
import org.json.JSONArray
import org.json.JSONObject

/**
 * `macro_record` 工具：把用户自己的屏幕操作录成 GUI 宏。
 *
 * 与 `macro_save` 的分工：录制负责"生成动作序列"，保存仍然是 `macro_save`（同一份存储格式），
 * 因此用户可以先看回执、确认命名，再落盘；传 `save=true` 时本工具直接代存，省掉一个回合。
 */
internal object MacroRecordTool {
    const val NAME = "macro_record"

    fun execute(context: Context, args: JSONObject): String {
        val appContext = context.applicationContext
        return when (val action = args.optString("action").trim().lowercase()) {
            "start" -> start(appContext, args)
            "stop" -> stop(appContext, args)
            "cancel" -> cancel(appContext)
            "status" -> status()
            else -> error("INVALID_ARGUMENT", "未知 action：$action（可用 start/stop/cancel/status）")
        }
    }

    private fun start(context: Context, args: JSONObject): String {
        val service = AgentAccessibilityService.current()
            ?: return error(
                "ACCESSIBILITY_UNAVAILABLE",
                "录制需要 Eta 无障碍服务正在运行；请先在设置里开启后再试",
            )
        val size = service.displaySize(Display.DEFAULT_DISPLAY)
        val snapshot = MacroRecorder.start(
            context = context,
            requestedName = args.optString("name"),
            screenWidth = size?.first ?: 0,
            screenHeight = size?.second ?: 0,
        ) ?: return error("ALREADY_RECORDING", "已经在录制中；先 action=stop 或 action=cancel 再重新开始")
        return JSONObject()
            .put("ok", true)
            .put("tool", NAME)
            .put("recording", true)
            .put("suggested_name", snapshot.suggestedName)
            .put("screen", JSONObject().put("width", size?.first ?: 0).put("height", size?.second ?: 0))
            .put(
                "message",
                "已开始录制。请让用户在手机上按平常的方式操作（点击、长按、输入、滑动、按键），" +
                    "完成后调用 macro_record(action=\"stop\")。密码框内的输入不会被记录。" +
                    "录制期间不要同时让 Agent 操作手机，否则会混入 Agent 自己的动作。",
            )
            .toString()
    }

    private fun stop(context: Context, args: JSONObject): String {
        // 录制也可能被通知上的"停止并保存"结束：这时会话已经关闭，但捕获结果还留在内存里，
        // 取一次就清掉，避免同一份录制被重复保存。
        var stoppedOutsideChat = false
        val result = MacroRecorder.stop(context) ?: run {
            val pending = MacroRecorder.lastStopResult() ?: return error(
                "NOT_RECORDING",
                "当前没有正在进行的录制，也没有待确认的录制结果",
            )
            MacroRecorder.clearLastStop()
            stoppedOutsideChat = true
            pending
        }
        val save = args.optBoolean("save", false)
        val requestedName = args.optString("name").trim()
        val name = MacroRecorder.normalizeName(requestedName)
            ?: MacroRecorder.normalizeName(result.suggestedName)
            ?: MacroRecorder.defaultName()
        val response = JSONObject()
            .put("ok", true)
            .put("tool", NAME)
            .put("recording", false)
            .put("suggested_name", name)
            .put("actions_count", result.actions.size)
            .put("raw_steps", result.stats.recorded)
            .put("duration_ms", result.durationMillis)
            .put("sensitive_skipped", result.stats.sensitiveSkipped)
            .put("debounced", result.stats.debounced)
            .put("merged_swipes", result.stats.mergedSwipes)
            .put("merged_texts", result.stats.mergedTexts)
            .put("truncated", result.stats.truncated)
            .put("clamped_delays", result.stats.clampedDelays)
            .put("hit_step_limit", result.truncatedByLimit)
            .put("stopped_outside_chat", stoppedOutsideChat)
        if (result.actions.isEmpty()) {
            response.put("saved", false)
                .put("message", "没有录到可回放的动作（只录到了密码输入或空白操作），这次录制已丢弃")
            return response.toString()
        }
        if (save) {
            val saved = runCatching {
                GuiMacroStore.save(
                    name = name,
                    description = args.optString("description").trim(),
                    actions = JSONArray().also { array -> result.actions.forEach { array.put(it.toJson()) } },
                    overwrite = args.optBoolean("overwrite", false),
                    source = GuiMacroStore.SOURCE_RECORDING,
                )
            }.getOrElse { throwable ->
                return error("MACRO_SAVE_FAILED", throwable.message ?: "保存宏失败")
            }
            MacroRecorder.clearLastStop()
            return response.put("saved", true).put("name", name)
                .put("macro", saved)
                .put("message", "已录制并保存为宏 $name，可用 macro_run 回放")
                .toString()
        }
        response.put("saved", false)
            .put(
                "message",
                (if (stoppedOutsideChat) {
                    "录制是用户在通知里停止的，Eta 已按建议名自动保存过一份；下面是同一份录制结果。"
                } else {
                    "录制已停止，共 ${result.actions.size} 个动作。"
                }) +
                    "是否另存为更合适的名字由用户决定：同意后调用 " +
                    "macro_save(name=..., description=..., actions=<下面的 actions>)；" +
                    "想换个名字也可以在这一步指定。",
            )
            .put("actions", JSONArray().also { array -> result.actions.forEach { array.put(it.toJson()) } })
        if (result.stats.sensitiveSkipped > 0) {
            response.put(
                "sensitive_note",
                "有 ${result.stats.sensitiveSkipped} 次输入命中密码框，内容没有录制，" +
                    "回放时需要用户自己输入；不要把占位文本当成真实密码写入宏",
            )
        }
        return response.toString()
    }

    private fun cancel(context: Context): String {
        val cancelled = MacroRecorder.cancel(context)
        return JSONObject()
            .put("ok", cancelled)
            .put("tool", NAME)
            .put("recording", false)
            .put("code", if (cancelled) JSONObject.NULL else "NOT_RECORDING")
            .put("message", if (cancelled) "已取消录制，丢弃本次捕获的动作" else "当前没有正在进行的录制")
            .toString()
    }

    private fun status(): String {
        val snapshot = MacroRecorder.snapshot()
        return JSONObject()
            .put("ok", true)
            .put("tool", NAME)
            .put("recording", snapshot != null)
            .put("steps", snapshot?.steps ?: 0)
            .put("elapsed_ms", MacroRecorder.elapsedMillis())
            .put("suggested_name", snapshot?.suggestedName ?: MacroRecorder.defaultName())
            .toString()
    }

    private fun error(code: String, message: String): String = JSONObject()
        .put("ok", false)
        .put("tool", NAME)
        .put("code", code)
        .put("message", message)
        .toString()
}
