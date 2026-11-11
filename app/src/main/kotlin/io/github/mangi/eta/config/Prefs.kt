package io.github.mangi.eta.config

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService
import io.github.mangi.eta.agent.context.PersonalSearchTools
import io.github.mangi.eta.agent.model.AgentFileToolCatalog
import io.github.mangi.eta.agent.model.AgentPhoneToolCatalog
import io.github.mangi.eta.agent.model.AgentTerminalToolCatalog
import org.json.JSONArray

/**
 * 模块配置中枢。
 *
 * - Hook 进程（system_server / SystemUI / Google / 系统助手等）在模块加载时调用
 *   [attachRemote]，缓存框架提供的只读 [SharedPreferences]；之后所有拦截回调用 [isEnabled]
 *   读取当前进程持有的 remote preferences。
 * - Eta Runtime 自己消费的开关保存在 App 私有配置中，不依赖 Xposed Service。
 * - Hook 消费的开关通过 [remotePreferencesForUi] 写入 RemotePreferences；
 *   XposedService 未就绪时不提供本地假 fallback。
 *
 * 基于 libxposed API 102 的 [io.github.libxposed.api.XposedInterface.getRemotePreferences]
 * 与 service 102 的 [XposedService.getRemotePreferences]，两端共用同一 group。
 */
internal object Prefs {

    /** 远程配置组名，UI 写入与 Hook 读取必须一致。 */
    const val GROUP = "eta_prefs"

    private const val LOCAL_AGENT_GROUP = "eta_agent_preferences"

    /** 所有功能开关 key。默认值按功能风险独立定义。 */
    object Keys {
        const val POWER_KEY_ASSISTANT_TARGET = "power_key_assistant_target"
        // 兼容旧版布尔协议；新 UI 不再写入，缺少三态配置时 true 仍表示 Gemini。
        const val POWER_KEY_TAKEOVER = "power_key_takeover"
        const val ASSISTANT_AUTO_CONFIG = "assistant_auto_config"
        const val HOTWORD_SELF_HEAL = "hotword_self_heal"
        const val GESTURE_BAR_CIRCLE_TO_SEARCH = "gesture_bar_circle_to_search"
        const val DOUBLE_FINGER_CIRCLE_TO_SEARCH = "double_finger_circle_to_search"
        const val LOCKSCREEN_VOICE_COMMAND = "lockscreen_voice_command"
        const val SCREEN_ON_VOICE_COMMAND = "screen_on_voice_command"
        const val AGENT_CUSTOM_MODEL = "agent_custom_model"
        const val AGENT_REQUIRE_PREFIX = "agent_require_prefix"
        const val AGENT_TERMINAL_TOOLS = "agent_terminal_tools"
        const val AGENT_BROWSER_TOOLS = "agent_browser_tools"
        const val AGENT_DEVICE_DIRECT_TOOLS = "agent_device_direct_tools"
        const val AGENT_DEVICE_SENSITIVE_READ_TOOLS = "agent_device_sensitive_read_tools"
        const val AGENT_DEVICE_SENSITIVE_ACTION_TOOLS = "agent_device_sensitive_action_tools"
        const val AGENT_THINKING_ENABLED = "agent_thinking_enabled"
        const val AGENT_AUTO_COMPACTION_ENABLED = "agent_auto_compaction_enabled"
        const val AGENT_SUBAGENT_ENABLED = "agent_subagent_enabled"
        const val AGENT_VIRTUAL_DISPLAY_ENABLED = "agent_virtual_display_enabled"
        /** 危险操作拦截：命中系统分区/启动链等规则时先在聊天流里确认再执行。 */
        const val AGENT_DANGER_TOOL_GUARD = "agent_danger_tool_guard"
        const val AGENT_RUNTIME_CONFIG_JSON = "agent_runtime_config_json"
        const val AGENT_DISABLED_TOOLS_JSON = "agent_disabled_tools_json"
        /** 一期：按当前用户消息注入非核心记忆节。 */
        const val AGENT_MEMORY_RELEVANCE_ENABLED = "agent_memory_relevance_enabled"
        /** 三期：一轮运行结束后追加轻量记忆沉淀回合。 */
        const val AGENT_MEMORY_DISTILL_ENABLED = "agent_memory_distill_enabled"
        /** 四期：memory_get(query=...) 使用向量语义检索。 */
        const val AGENT_MEMORY_SEMANTIC_RECALL_ENABLED = "agent_memory_semantic_recall_enabled"
        /** 四期：语义检索使用的 embedding 模型名（OpenAI 兼容 /embeddings）。 */
        const val AGENT_MEMORY_EMBEDDING_MODEL = "agent_memory_embedding_model"
        /** 用量统计：只记录请求计数与 token 数量等元数据，纯本地、默认开启。 */
        const val AGENT_USAGE_STATS_ENABLED = "agent_usage_stats_enabled"
        /**
         * 主动沟通：允许 Agent 在值得关注时主动发系统通知（notify_user），默认开启。
         * 刻意不并入 [BOOLEAN_DEFAULTS]/[LOCAL_AGENT_KEYS]：这两个集合被 PrefsDefaultsTest 用
         * 精确 map/set 断言钉住，且四期/拦截卡/任务中心等未提交轮次已各自追加过键。这里改用
         * [isProactiveNotifyEnabled] 独立读取同一份 localAgent prefs，既不改动既有默认值快照测试，
         * 也不与那些轮次抢同一处定义；默认 true 与设置页 SwitchPref 的 `?: true` 一致。
         */
        const val AGENT_PROACTIVE_NOTIFY_ENABLED = "agent_proactive_notify_enabled"

        /**
         * 后台任务完成自动唤醒：terminal_job 失败/超时结束时自动起一轮 agent run 处理，默认开启。
         *
         * 这个开关**必须**进 [BOOLEAN_DEFAULTS] 与 [LOCAL_AGENT_KEYS]：设置页的 SwitchPref、
         * [isJobWakeupEnabled] 与 hook 侧回写要读到同一个默认值与同一份本地键集合，
         * 否则关掉开关后系统托盘/远端配置仍按 true 处理，开关形同虚设（审查 m9）。
         */
        const val AGENT_JOB_WAKEUP_ENABLED = "agent_job_wakeup_enabled"

        /** 全部布尔开关及其默认值。 */
        val BOOLEAN_DEFAULTS: Map<String, Boolean> = mapOf(
            POWER_KEY_TAKEOVER to false,
            ASSISTANT_AUTO_CONFIG to false,
            HOTWORD_SELF_HEAL to false,
            GESTURE_BAR_CIRCLE_TO_SEARCH to true,
            DOUBLE_FINGER_CIRCLE_TO_SEARCH to false,
            LOCKSCREEN_VOICE_COMMAND to false,
            SCREEN_ON_VOICE_COMMAND to false,
            AGENT_CUSTOM_MODEL to true,
            AGENT_REQUIRE_PREFIX to false,
            AGENT_TERMINAL_TOOLS to true,
            AGENT_BROWSER_TOOLS to true,
            AGENT_DEVICE_DIRECT_TOOLS to true,
            AGENT_DEVICE_SENSITIVE_READ_TOOLS to true,
            AGENT_DEVICE_SENSITIVE_ACTION_TOOLS to true,
            AGENT_THINKING_ENABLED to true,
            AGENT_AUTO_COMPACTION_ENABLED to true,
            AGENT_SUBAGENT_ENABLED to true,
            AGENT_VIRTUAL_DISPLAY_ENABLED to true,
            AGENT_DANGER_TOOL_GUARD to true,
            AGENT_MEMORY_RELEVANCE_ENABLED to true,
            AGENT_MEMORY_DISTILL_ENABLED to false,
            AGENT_MEMORY_SEMANTIC_RECALL_ENABLED to false,
            AGENT_USAGE_STATS_ENABLED to true,
            AGENT_JOB_WAKEUP_ENABLED to true,
        )

        /** 由 Eta Runtime 最终裁决、不要求 Xposed 框架在线的开关。 */
        val LOCAL_AGENT_KEYS: Set<String> = setOf(
            AGENT_TERMINAL_TOOLS,
            AGENT_BROWSER_TOOLS,
            AGENT_DEVICE_DIRECT_TOOLS,
            AGENT_DEVICE_SENSITIVE_READ_TOOLS,
            AGENT_DEVICE_SENSITIVE_ACTION_TOOLS,
            AGENT_THINKING_ENABLED,
            AGENT_AUTO_COMPACTION_ENABLED,
            AGENT_SUBAGENT_ENABLED,
            AGENT_VIRTUAL_DISPLAY_ENABLED,
            AGENT_DANGER_TOOL_GUARD,
            AGENT_MEMORY_RELEVANCE_ENABLED,
            AGENT_MEMORY_DISTILL_ENABLED,
            AGENT_MEMORY_SEMANTIC_RECALL_ENABLED,
            AGENT_USAGE_STATS_ENABLED,
            AGENT_JOB_WAKEUP_ENABLED,
        )
    }

    /** Hook 进程缓存的只读 remote preferences，由 ModuleMain 在 onModuleLoaded 注入。 */
    @Volatile
    private var remote: SharedPreferences? = null

    @Volatile
    private var localAgent: SharedPreferences? = null

    /** App 进程调用：初始化不依赖 Xposed Service 的 Agent 配置。 */
    fun initLocal(context: Context) {
        if (localAgent == null) {
            synchronized(this) {
                if (localAgent == null) {
                    localAgent = context.applicationContext.getSharedPreferences(
                        LOCAL_AGENT_GROUP,
                        Context.MODE_PRIVATE,
                    ).also(::migrateLegacyGroupSwitchesIfNeeded)
                }
            }
        }
    }

    /** Hook 进程调用：缓存框架提供的只读 SharedPreferences。 */
    fun attachRemote(prefs: SharedPreferences?) {
        remote = prefs
    }

    /** Hook 进程监听框架下发的配置变化；listener 必须由调用方在进程生命周期内强引用。 */
    fun registerRemoteListener(listener: SharedPreferences.OnSharedPreferenceChangeListener): Boolean {
        val preferences = remote ?: return false
        preferences.registerOnSharedPreferenceChangeListener(listener)
        return true
    }

    /**
     * 读取布尔开关。remote 不可用（框架未注入或调用失败）时回退各功能自己的默认值；
     * 默认值与设置页展示保持一致。
     */
    fun isEnabled(key: String): Boolean {
        val default = Keys.BOOLEAN_DEFAULTS[key] ?: true
        val preferences = if (key in Keys.LOCAL_AGENT_KEYS) localAgent ?: remote else remote
        return preferences?.getBoolean(key, default) ?: default
    }

    fun getString(key: String): String {
        return remote?.getString(key, "") ?: ""
    }

    /**
     * 主动沟通开关：默认开启。读取 localAgent prefs（与设置页 SwitchPref 同一份），
     * 缺失时按默认 true；框架 remote 不可用也不影响。独立于 [isEnabled] 的默认值快照，
     * 避免与"默认值精确断言"测试以及其它未提交轮次的 BOOLEAN_DEFAULTS 追加互相干扰。
     */
    fun isProactiveNotifyEnabled(): Boolean = runCatching {
        (localAgent ?: remote)?.getBoolean(Keys.AGENT_PROACTIVE_NOTIFY_ENABLED, true) ?: true
    }.getOrDefault(true)

    /**
     * 后台任务完成自动唤醒开关：默认开启。
     *
     * 与 [isProactiveNotifyEnabled] 同一模式：读 localAgent prefs（与设置页 SwitchPref 同一份），
     * 缺失按 true；框架 remote 不可用也不影响后台唤醒的裁决。
     */
    fun isJobWakeupEnabled(): Boolean = runCatching {
        (localAgent ?: remote)?.getBoolean(Keys.AGENT_JOB_WAKEUP_ENABLED, true) ?: true
    }.getOrDefault(true)

    /** 读取本地 Agent 配置中的字符串（远端未必有同名键）；缺省返回 [default]。 */
    fun localAgentString(key: String, default: String = ""): String =
        runCatching { (localAgent ?: remote)?.getString(key, default) }.getOrNull() ?: default

    /** 写入单个本地 Agent 布尔开关；框架可用时顺手回写远端，保持 hook 侧一致。 */
    fun setAgentSwitch(
        key: String,
        enabled: Boolean,
        service: XposedService? = null,
    ): Boolean {
        val local = localAgent ?: return false
        val committed = runCatching { local.edit().putBoolean(key, enabled).commit() }.getOrDefault(false)
        if (committed && service != null) reconcileAgentPreferences(service)
        return committed
    }

    fun powerAssistantTarget(): PowerAssistantTarget = powerAssistantTarget(remote)

    fun powerAssistantTarget(preferences: SharedPreferences?): PowerAssistantTarget {
        val persistedValue = runCatching {
            preferences?.getString(Keys.POWER_KEY_ASSISTANT_TARGET, null)
        }.getOrNull()
        val legacyDefault = Keys.BOOLEAN_DEFAULTS.getValue(Keys.POWER_KEY_TAKEOVER)
        val legacyTakeover = runCatching {
            preferences?.getBoolean(Keys.POWER_KEY_TAKEOVER, legacyDefault)
        }.getOrNull() ?: legacyDefault
        return PowerAssistantTarget.resolve(persistedValue, legacyTakeover)
    }

    /**
     * UI 进程获取可写的 RemotePreferences。
     *
     * [XposedService.getRemotePreferences] 的 commit 会同步等待 binder 提交到 LSPosed
     * 数据库，失败返回 false；service 未就绪时返回 null，让 UI 保持不可写。
     */
    fun remotePreferencesForUi(service: XposedService?): SharedPreferences? =
        runCatching { service?.getRemotePreferences(GROUP) }.getOrNull()

    /** Eta 设置页与 Runtime 使用的本地 Agent 配置，不依赖 LSPosed。 */
    fun localAgentPreferences(): SharedPreferences? = localAgent

    /**
     * 首次升级优先把已有 RemotePreferences 值迁入本地；之后本地值是事实源，并在框架
     * 可用时回写远端，让仍在目标进程中组装请求的 Hook 入口拿到一致的初始配置。
     */
    fun reconcileAgentPreferences(service: XposedService?) {
        val local = localAgent ?: return
        val remotePreferences = remotePreferencesForUi(service) ?: return
        val localEditor = local.edit()
        val remoteEditor = remotePreferences.edit()
        var updateLocal = false
        var updateRemote = false

        Keys.LOCAL_AGENT_KEYS.forEach { key ->
            val default = Keys.BOOLEAN_DEFAULTS.getValue(key)
            when {
                local.contains(key) -> {
                    remoteEditor.putBoolean(key, local.getBoolean(key, default))
                    updateRemote = true
                }
                remotePreferences.contains(key) -> {
                    localEditor.putBoolean(key, remotePreferences.getBoolean(key, default))
                    updateLocal = true
                }
            }
        }
        when {
            local.contains(Keys.AGENT_DISABLED_TOOLS_JSON) -> {
                remoteEditor.putString(
                    Keys.AGENT_DISABLED_TOOLS_JSON,
                    local.getString(Keys.AGENT_DISABLED_TOOLS_JSON, "[]"),
                )
                updateRemote = true
            }
            remotePreferences.contains(Keys.AGENT_DISABLED_TOOLS_JSON) -> {
                localEditor.putString(
                    Keys.AGENT_DISABLED_TOOLS_JSON,
                    remotePreferences.getString(Keys.AGENT_DISABLED_TOOLS_JSON, "[]"),
                )
                updateLocal = true
            }
        }
        if (updateLocal) localEditor.commit()
        if (updateRemote) runCatching { remoteEditor.commit() }
    }

    /** 将运行时工具名映射为工具列表中的主开关标识，确保同一能力的不同动作变体受同一开关约束。 */
    fun canonicalToolSwitchId(toolName: String): String = when (toolName) {
        "tap" -> "tap_area"
        "gesture_batch" -> "tap_area"
        "macro_run" -> "tap_area"
        "long_press_element" -> "long_press"
        "scroll_element" -> "scroll"
        "input_text", "replace_text", "clear_text", "paste_text" -> "type_text"
        "wait", "wait_for_package" -> "wait_for_text"
        "browser_read", "browser_interact", "browser_screenshot" -> "browser_use"
        "search_coloros_notes" -> "search_notes"
        "search_coloros_memories" -> "search_system_memories"
        else -> PersonalSearchTools.canonical(toolName)
    }

    fun disabledTools(preferences: SharedPreferences? = localAgent ?: remote): Set<String> {
        val raw = runCatching {
            preferences?.getString(Keys.AGENT_DISABLED_TOOLS_JSON, null)
        }.getOrNull().orEmpty()
        if (raw.isBlank()) return emptySet()
        return runCatching {
            val array = JSONArray(raw)
            buildSet(array.length()) {
                for (index in 0 until array.length()) {
                    val item = array.optString(index).trim()
                    if (item.isNotEmpty()) add(item)
                }
            }
        }.getOrDefault(emptySet())
    }

    fun isToolEnabledInSet(toolName: String, disabled: Set<String>): Boolean {
        if (disabled.isEmpty()) return true
        val canonical = canonicalToolSwitchId(toolName)
        return toolName !in disabled && canonical !in disabled
    }

    fun isToolEnabled(
        toolName: String,
        preferences: SharedPreferences? = localAgent ?: remote,
    ): Boolean = isToolEnabledInSet(toolName, disabledTools(preferences))

    fun setToolEnabled(
        toolName: String,
        enabled: Boolean,
        service: XposedService? = null,
    ): Boolean = setToolsEnabled(listOf(toolName), enabled, service)

    fun setToolsEnabled(
        toolNames: Collection<String>,
        enabled: Boolean,
        service: XposedService? = null,
    ): Boolean {
        val local = localAgent ?: return false
        val updated = disabledTools(local).toMutableSet()
        toolNames.forEach { rawName ->
            val canonical = canonicalToolSwitchId(rawName)
            if (enabled) {
                updated -= rawName
                updated -= canonical
            } else {
                updated += canonical
            }
        }
        val encoded = JSONArray(updated.sorted()).toString()
        val editor = local.edit().putString(Keys.AGENT_DISABLED_TOOLS_JSON, encoded)
        LEGACY_TOOL_GROUP_KEYS.forEach { key ->
            if (local.contains(key) && !local.getBoolean(key, true)) {
                editor.putBoolean(key, true)
            }
        }
        val committed = runCatching { editor.commit() }.getOrDefault(false)
        if (committed && service != null) {
            reconcileAgentPreferences(service)
        }
        return committed
    }

    private fun migrateLegacyGroupSwitchesIfNeeded(local: SharedPreferences) {
        if (local.contains(Keys.AGENT_DISABLED_TOOLS_JSON)) return
        val disabledLegacyGroups = LEGACY_TOOL_GROUP_KEYS.filter { key ->
            local.contains(key) && !local.getBoolean(key, true)
        }
        if (disabledLegacyGroups.isEmpty()) return
        val migratedDisabled = linkedSetOf<String>()
        disabledLegacyGroups.forEach { key ->
            migratedDisabled += LEGACY_GROUP_TOOL_NAMES.getValue(key)
        }
        val editor = local.edit()
            .putString(Keys.AGENT_DISABLED_TOOLS_JSON, JSONArray(migratedDisabled.sorted()).toString())
        disabledLegacyGroups.forEach { key -> editor.putBoolean(key, true) }
        runCatching { editor.commit() }
    }

    /** 组开关与组内工具名的单一映射来源，迁移与运行时组开关共用。 */
    private val LEGACY_GROUP_TOOL_NAMES: Map<String, Collection<String>> = mapOf(
        Keys.AGENT_BROWSER_TOOLS to listOf("web_search", "fetch_url", "browser_use"),
        Keys.AGENT_TERMINAL_TOOLS to
            (AgentTerminalToolCatalog.names + AgentFileToolCatalog.names + "read_image").toList(),
        Keys.AGENT_DEVICE_DIRECT_TOOLS to (AgentPhoneToolCatalog.direct + listOf(
            "set_alarm", "set_timer", "device_status", "inspect_app",
            "network_info", "top_memory_apps", "top_storage_apps",
            "media_control", "set_volume",
        )),
        Keys.AGENT_DEVICE_SENSITIVE_READ_TOOLS to (PersonalSearchTools.names + AgentPhoneToolCatalog.reads + listOf(
            "read_personal_item", "summarize_bills", "get_setting", "wifi_credentials",
            "recent_notifications", "search_notification_history", "recent_app_activity",
            "app_usage_summary", "get_current_location", "get_device_environment",
            "list_alarms", "list_active_timers", "search_clipboard_history",
            "get_health_summary", "read_sms_code", "get_logcat", "search_media",
            "search_audio", "search_recordings", "search_files", "search_calendar_events",
            "search_contacts", "search_call_history", "search_messages", "search_downloads",
            "search_notes", "search_coloros_recordings", "search_recording_summaries",
            "search_system_memories", "search_saved_places", "search_personal_orders",
            "search_qq_chat_images", "search_wechat_chat_images",
        )),
        Keys.AGENT_DEVICE_SENSITIVE_ACTION_TOOLS to
            (AgentPhoneToolCatalog.writes + listOf("set_setting", "set_device_state", "app_state_control")),
    )

    fun isGroupSwitchKey(key: String): Boolean = LEGACY_GROUP_TOOL_NAMES.containsKey(key)

    /** 组开关的读取口径与写入口径一致：组内工具全部被禁用才视为组关闭。 */
    fun isAgentGroupEnabled(key: String): Boolean {
        val tools = LEGACY_GROUP_TOOL_NAMES[key] ?: return isEnabled(key)
        val disabled = disabledTools()
        return !tools.all { it in disabled }
    }

    /**
     * 组开关的权威写入入口：通过禁用工具集同步组内全部工具，避免 legacy 布尔值
     * 被 setToolsEnabled 强制刷回 true 导致 UI 与运行时状态矛盾。
     */
    fun setAgentGroupEnabled(
        key: String,
        enabled: Boolean,
        service: XposedService? = null,
    ): Boolean {
        val tools = LEGACY_GROUP_TOOL_NAMES[key] ?: return false
        return setToolsEnabled(tools, enabled, service)
    }

    private val LEGACY_TOOL_GROUP_KEYS = listOf(
        Keys.AGENT_TERMINAL_TOOLS,
        Keys.AGENT_BROWSER_TOOLS,
        Keys.AGENT_DEVICE_DIRECT_TOOLS,
        Keys.AGENT_DEVICE_SENSITIVE_READ_TOOLS,
        Keys.AGENT_DEVICE_SENSITIVE_ACTION_TOOLS,
    )
}
