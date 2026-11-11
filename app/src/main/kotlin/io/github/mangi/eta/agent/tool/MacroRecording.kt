package io.github.mangi.eta.agent.tool

import org.json.JSONObject

/** 录制到的原始动作类型。与 gesture_batch 的动作名不是一一对应，归一化后才落地。 */
internal enum class RecordedKind {
    TAP,
    LONG_PRESS,
    /** 用户的一次连续拖动；多次微滑会在归一化时合并。 */
    SWIPE,
    /** 由无障碍滚动事件推断的滑动（没有真实手势坐标）。 */
    SCROLL,
    TEXT,
    KEY,
}

/**
 * 无障碍服务捕获到的一步原始操作。
 *
 * 这是"事实"层：只记录观察到的东西，不做任何取舍；去抖、合并、截断全部在
 * [MacroActionNormalizer] 里完成，便于单独测试。
 */
internal data class RecordedAction(
    val kind: RecordedKind,
    /** 捕获时刻（单调时钟毫秒），用来计算动作间隔。 */
    val atMillis: Long,
    val x: Int = 0,
    val y: Int = 0,
    val deltaX: Int = 0,
    val deltaY: Int = 0,
    val text: String = "",
    val button: String = "",
    val targetText: String = "",
    val targetDesc: String = "",
    /** 事件所属窗口；滚动事件按窗口分组，避免把两个窗口的滚动拼成一次手势（审查 M6）。 */
    val windowId: Int = 0,
    /** 命中密码框时为 true：内容一律不入库，只统计条数。 */
    val sensitive: Boolean = false,
    /** 按压或拖动持续时长；未知为 0。 */
    val durationMs: Int = 0,
)

/** 归一化后的一个 gesture_batch 动作；[toJson] 的输出可直接交给 gesture_batch / macro_save。 */
internal data class MacroAction(
    val type: String,
    val x: Int? = null,
    val y: Int? = null,
    val x1: Int? = null,
    val y1: Int? = null,
    val x2: Int? = null,
    val y2: Int? = null,
    val text: String? = null,
    val button: String? = null,
    val direction: String? = null,
    val amount: String? = null,
    val ms: Int? = null,
    val durationMs: Int? = null,
    val targetText: String? = null,
    val targetDesc: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("type", type)
        when (type) {
            "tap", "long_press" -> {
                put("x", x ?: 0)
                put("y", y ?: 0)
                put("coordinate_space", "screen")
            }
            "swipe" -> {
                put("x1", x1 ?: 0)
                put("y1", y1 ?: 0)
                put("x2", x2 ?: 0)
                put("y2", y2 ?: 0)
                (durationMs ?: 300).let { put("duration_ms", it) }
                put("coordinate_space", "screen")
            }
            "scroll" -> {
                put("direction", direction.orEmpty())
                if (amount != null) put("amount", amount)
            }
            "type_text" -> put("text", text.orEmpty())
            "press_key" -> put("button", button.orEmpty())
            "wait_ms" -> put("ms", ms ?: 300)
        }
        // 目标文本/desc 不参与回放（坐标已足够），保留下来便于人工核对宏内容。
        if (targetText != null) put("target_text", targetText)
        if (targetDesc != null) put("target_desc", targetDesc)
    }
}

/** 归一化统计；工具结果里要给用户解释"为什么少了 N 步"。 */
internal data class MacroNormalizeStats(
    val recorded: Int = 0,
    /** 命中密码框被丢弃的输入条数。 */
    val sensitiveSkipped: Int = 0,
    /** 去抖丢弃的重复点击。 */
    val debounced: Int = 0,
    /** 被合并掉的微滑段数。 */
    val mergedSwipes: Int = 0,
    /** 合并后的连续输入次数。 */
    val mergedTexts: Int = 0,
    /** 被合并成一次滑动的滚动事件数（审查 M6）。 */
    val mergedScrolls: Int = 0,
    /** 因超出上限被截断的动作数。 */
    val truncated: Int = 0,
    /** 被夹到上限的长间隔个数。 */
    val clampedDelays: Int = 0,
) {
    val droppedCount: Int get() = sensitiveSkipped + debounced + mergedSwipes + mergedTexts + truncated
}

internal data class MacroNormalizeResult(
    val actions: List<MacroAction>,
    val stats: MacroNormalizeStats,
)

internal data class MacroNormalizeOptions(
    /** 屏幕尺寸；>0 时用来丢弃越界坐标。 */
    val screenWidth: Int = 0,
    val screenHeight: Int = 0,
    /** 这个窗口内的同位置重复点击视为抖动。 */
    val debounceMs: Long = 250,
    val debounceDistancePx: Int = 8,
    /** 这个窗口内的连续微滑视为同一次拖动。 */
    val swipeMergeWindowMs: Long = 400,
    /** 单段位移小于该值的拖动视为微滑，可参与合并。 */
    val microSwipeMaxPx: Int = 80,
    /** 相邻动作间隔超过该值才插入 wait_ms。 */
    val minGapForWaitMs: Long = 180,
    /** 间隔上限：超过就截断，避免回放时长时间空等。 */
    val maxDelayMs: Long = 3_000,
    /**
     * 一次滚动手势合成滑动时的位移上限（像素，审查 M6）。
     *
     * 无障碍只给滚动增量、不给手指轨迹，因此把"同一次手势里所有滚动事件"累计后映射成一段
     * 位移；上限防止一次快速长滑在回放时变成跨越整屏的暴力滑动。
     */
    val maxScrollSwipePx: Int = 600,
    /** 连续输入合并窗口。 */
    val textMergeWindowMs: Long = 3_000,
    /** 与 GuiMacroStore.MAX_ACTIONS 一致；这里刻意不引用它，保持归一化纯逻辑可独立编译测试。 */
    val maxActions: Int = MacroActionNormalizer.DEFAULT_MAX_ACTIONS,
)

/**
 * 把无障碍捕获到的原始动作归一化成 gesture_batch 可回放的动作序列。
 *
 * 全部是纯函数（只用到入参与系统无关的数学），因此可以脱离 Android 直接单元测试。
 * 设计取舍：
 * - 抖动（同位置极短间隔重复点击）保留第一次；
 * - 一次拖动常被系统切成多个 SCROLL/SWIPE 事件，同方向的微滑按窗口合并，避免回放时变成抽搐；
 * - 间隔只保留"有意义"的部分：太短的省略（人眼看不出差别），太长的截断（回放不需要等）。
 */
internal object MacroActionNormalizer {
    /** 与 GuiMacroStore.MAX_ACTIONS 相同的上限，gesture_batch 与 macro_save 都按这个数校验。 */
    const val DEFAULT_MAX_ACTIONS = 50

    /**
     * 把"一次手势产生的多个滚动事件"折叠成一段手指位移（审查 M5 / M6）。
     *
     * 语义（AOSP）：`scrollDeltaY` 为正表示**内容上移**，也就是手指向上滑。
     * 手指位移与内容位移方向相反，所以这里取反号；旧实现直接用 delta 方向，回放会上下颠倒。
     *
     * 分组：同 windowId、且相邻事件间隔不超过 [MacroNormalizeOptions.swipeMergeWindowMs]；
     * 累计该组每个事件在主轴上的 |delta| 作为位移长度，上限 [MacroNormalizeOptions.maxScrollSwipePx]。
     * 这样一个快速长滑只会产出**一条** swipe，而不是每个事件追加 ±120px。
     */
    private fun collapseScrollGestures(
        recorded: List<RecordedAction>,
        options: MacroNormalizeOptions,
    ): CollapsedScrolls {
        val result = ArrayList<RecordedAction>(recorded.size)
        var mergedCount = 0
        var index = 0
        while (index < recorded.size) {
            val current = recorded[index]
            if (current.kind != RecordedKind.SCROLL) {
                result += current
                index++
                continue
            }
            var absX = kotlin.math.abs(current.deltaX).toLong()
            var absY = kotlin.math.abs(current.deltaY).toLong()
            var netX = current.deltaX.toLong()
            var netY = current.deltaY.toLong()
            var anchorX = current.x
            var anchorY = current.y
            var lastAt = current.atMillis
            var groupSize = 1
            var next = index + 1
            while (next < recorded.size) {
                val candidate = recorded[next]
                if (candidate.kind != RecordedKind.SCROLL) break
                if (candidate.windowId != current.windowId) break
                if (candidate.atMillis - lastAt > options.swipeMergeWindowMs) break
                absX += kotlin.math.abs(candidate.deltaX).toLong()
                absY += kotlin.math.abs(candidate.deltaY).toLong()
                netX += candidate.deltaX.toLong()
                netY += candidate.deltaY.toLong()
                if (candidate.x > 0 && candidate.y > 0) {
                    anchorX = candidate.x
                    anchorY = candidate.y
                }
                lastAt = candidate.atMillis
                groupSize++
                next++
            }
            mergedCount += groupSize - 1
            val horizontal = absX > absY
            val magnitude = minOf(if (horizontal) absX else absY, options.maxScrollSwipePx.toLong())
            // 手指位移 = -内容位移方向（正 delta 表示内容上移，即手指上滑）。
            val travelX = if (horizontal) -signOf(netX) * magnitude else 0L
            val travelY = if (horizontal) 0L else -signOf(netY) * magnitude
            result += RecordedAction(
                kind = RecordedKind.SCROLL,
                atMillis = lastAt,
                x = anchorX,
                y = anchorY,
                deltaX = travelX.toInt(),
                deltaY = travelY.toInt(),
                windowId = current.windowId,
            )
            index = next
        }
        return CollapsedScrolls(actions = result, mergedCount = mergedCount)
    }

    private data class CollapsedScrolls(val actions: List<RecordedAction>, val mergedCount: Int)

    private fun signOf(value: Long): Long = when {
        value > 0L -> 1L
        value < 0L -> -1L
        else -> 0L
    }

    fun normalize(
        recorded: List<RecordedAction>,
        options: MacroNormalizeOptions = MacroNormalizeOptions(),
    ): MacroNormalizeResult {
        val collapsed = collapseScrollGestures(recorded, options)
        var sensitiveSkipped = 0
        var debounced = 0
        var mergedSwipes = 0
        var mergedTexts = 0
        var clampedDelays = 0

        val actions = mutableListOf<MacroAction>()
        var lastRawAt = 0L
        var pendingWaitMs = 0L
        var truncated = 0

        fun append(action: MacroAction) {
            // 需要为"间隔"和"动作本身"都留位置：否则带 wait 的最后一步会把总数顶出上限。
            val waitSlots = if (pendingWaitMs > 0L) 1 else 0
            if (actions.size + waitSlots + 1 > options.maxActions) {
                truncated++
                pendingWaitMs = 0L
                return
            }
            if (pendingWaitMs > 0L) {
                val clamped = minOf(pendingWaitMs, options.maxDelayMs)
                if (clamped < pendingWaitMs) clampedDelays++
                actions += MacroAction(type = "wait_ms", ms = clamped.toInt())
                pendingWaitMs = 0L
            }
            actions += action
        }

        collapsed.actions.forEach { raw ->
            val gap = if (lastRawAt == 0L) 0L else (raw.atMillis - lastRawAt).coerceAtLeast(0L)
            if (gap > 0L) pendingWaitMs += gap
            if (pendingWaitMs >= 1L && pendingWaitMs < options.minGapForWaitMs) pendingWaitMs = 0L
            lastRawAt = raw.atMillis

            if (raw.sensitive) {
                sensitiveSkipped++
                return@forEach
            }

            when (raw.kind) {
                RecordedKind.TAP -> {
                    if (!inBounds(raw.x, raw.y, options)) return@forEach
                    val previous = actions.lastOrNull()
                    val sameSpot = previous?.type == "tap" &&
                        kotlin.math.abs((previous.x ?: 0) - raw.x) <= options.debounceDistancePx &&
                        kotlin.math.abs((previous.y ?: 0) - raw.y) <= options.debounceDistancePx
                    if (sameSpot && gap < options.debounceMs) {
                        debounced++
                        pendingWaitMs = 0L
                        return@forEach
                    }
                    append(tapAction(raw, longPress = false))
                }

                RecordedKind.LONG_PRESS -> {
                    if (!inBounds(raw.x, raw.y, options)) return@forEach
                    val previous = actions.lastOrNull()
                    if (previous?.type == "long_press" &&
                        previous.x == raw.x && previous.y == raw.y &&
                        gap < options.debounceMs
                    ) {
                        debounced++
                        pendingWaitMs = 0L
                        return@forEach
                    }
                    append(tapAction(raw, longPress = true))
                }

                RecordedKind.SWIPE -> {
                    val previous = actions.lastOrNull()
                    val small = kotlin.math.abs(raw.deltaX) <= options.microSwipeMaxPx &&
                        kotlin.math.abs(raw.deltaY) <= options.microSwipeMaxPx
                    if (small && previous?.type == "swipe" && gap < options.swipeMergeWindowMs) {
                        val merged = mergeSwipe(previous, raw)
                        if (merged != null) {
                            actions[actions.lastIndex] = merged
                            mergedSwipes++
                            pendingWaitMs = 0L
                            return@forEach
                        }
                    }
                    if (raw.deltaX == 0 && raw.deltaY == 0) return@forEach
                    if (!inBounds(raw.x, raw.y, options)) return@forEach
                    append(swipeFromRecorded(raw, options))
                }

                RecordedKind.SCROLL -> {
                    // 预处理阶段已经把"同一次手势的多个滚动事件"合并成一段手指位移（带方向、已限幅）。
                    val travelX = raw.deltaX
                    val travelY = raw.deltaY
                    if (travelX == 0 && travelY == 0) return@forEach
                    val anchorX = if (raw.x > 0) raw.x else options.screenWidth / 2
                    val anchorY = if (raw.y > 0) raw.y else options.screenHeight / 2
                    if (anchorX <= 0 || anchorY <= 0) return@forEach
                    append(
                        MacroAction(
                            type = "swipe",
                            x1 = clampX(anchorX - travelX / 2, options),
                            y1 = clampY(anchorY - travelY / 2, options),
                            x2 = clampX(anchorX + travelX / 2, options),
                            y2 = clampY(anchorY + travelY / 2, options),
                            durationMs = 300,
                        ),
                    )
                }

                RecordedKind.TEXT -> {
                    val text = raw.text
                    if (text.isEmpty()) return@forEach
                    val previous = actions.lastOrNull()
                    val sameTarget = previous?.type == "type_text" &&
                        (previous.targetDesc ?: "") == raw.targetDesc &&
                        gap < options.textMergeWindowMs
                    if (sameTarget) {
                        actions[actions.lastIndex] = previous!!.copy(text = (previous.text.orEmpty() + text))
                        mergedTexts++
                        pendingWaitMs = 0L
                        return@forEach
                    }
                    append(
                        MacroAction(
                            type = "type_text",
                            text = text,
                            targetText = raw.targetText.takeIf { it.isNotBlank() },
                            targetDesc = raw.targetDesc.takeIf { it.isNotBlank() },
                        ),
                    )
                }

                RecordedKind.KEY -> {
                    val button = raw.button.trim().uppercase()
                    if (button !in SUPPORTED_KEYS) return@forEach
                    append(MacroAction(type = "press_key", button = button))
                }
            }
        }

        // 末尾的 wait_ms 没有意义（等完就结束了），去掉。
        while (actions.isNotEmpty() && actions.last().type == "wait_ms") {
            actions.removeAt(actions.lastIndex)
        }

        return MacroNormalizeResult(
            actions = actions,
            stats = MacroNormalizeStats(
                recorded = recorded.size,
                sensitiveSkipped = sensitiveSkipped,
                mergedScrolls = collapsed.mergedCount,
                debounced = debounced,
                mergedSwipes = mergedSwipes,
                mergedTexts = mergedTexts,
                truncated = truncated,
                clampedDelays = clampedDelays,
            ),
        )
    }

    /** 只有这些按键能作为宏动作回放；字符键不录制（内容应由 type_text 表达）。 */
    val SUPPORTED_KEYS = setOf("BACK", "HOME", "ENTER", "RECENTS", "NOTIFICATIONS", "QUICK_SETTINGS")

    private fun tapAction(raw: RecordedAction, longPress: Boolean): MacroAction = MacroAction(
        type = if (longPress) "long_press" else "tap",
        x = raw.x,
        y = raw.y,
        targetText = raw.targetText.takeIf { it.isNotBlank() },
        targetDesc = raw.targetDesc.takeIf { it.isNotBlank() },
    )

    private fun swipeFromRecorded(raw: RecordedAction, options: MacroNormalizeOptions): MacroAction =
        MacroAction(
            type = "swipe",
            x1 = clampX(raw.x, options),
            y1 = clampY(raw.y, options),
            x2 = clampX(raw.x + raw.deltaX, options),
            y2 = clampY(raw.y + raw.deltaY, options),
            durationMs = raw.durationMs.takeIf { it in 50..3_000 } ?: 300,
        )

    private fun mergeSwipe(previous: MacroAction, raw: RecordedAction): MacroAction? {
        if (previous.type != "swipe") return null
        val previousDx = (previous.x2 ?: 0) - (previous.x1 ?: 0)
        val previousDy = (previous.y2 ?: 0) - (previous.y1 ?: 0)
        val sameAxis = if (kotlin.math.abs(previousDx) >= kotlin.math.abs(previousDy)) {
            kotlin.math.abs(raw.deltaY) <= kotlin.math.abs(raw.deltaX) && previousDx * raw.deltaX >= 0
        } else {
            kotlin.math.abs(raw.deltaX) <= kotlin.math.abs(raw.deltaY) && previousDy * raw.deltaY >= 0
        }
        if (!sameAxis) return null
        return previous.copy(
            x2 = (previous.x2 ?: 0) + raw.deltaX,
            y2 = (previous.y2 ?: 0) + raw.deltaY,
        )
    }

    private fun inBounds(x: Int, y: Int, options: MacroNormalizeOptions): Boolean {
        if (x < 0 || y < 0) return false
        if (options.screenWidth > 0 && x >= options.screenWidth) return false
        if (options.screenHeight > 0 && y >= options.screenHeight) return false
        return true
    }

    private fun clampX(value: Int, options: MacroNormalizeOptions): Int =
        if (options.screenWidth > 0) value.coerceIn(0, options.screenWidth - 1) else value.coerceAtLeast(0)

    private fun clampY(value: Int, options: MacroNormalizeOptions): Int =
        if (options.screenHeight > 0) value.coerceIn(0, options.screenHeight - 1) else value.coerceAtLeast(0)
}
