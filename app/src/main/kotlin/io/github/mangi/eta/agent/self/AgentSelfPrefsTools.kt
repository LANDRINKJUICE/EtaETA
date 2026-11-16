package io.github.mangi.eta.agent.self

import android.content.Context
import io.github.mangi.eta.agent.runtime.AgentRunCancelledException
import io.github.mangi.eta.agent.terminal.LinuxDistribution
import io.github.mangi.eta.agent.terminal.LinuxExecutionBackend
import io.github.mangi.eta.agent.terminal.TerminalRuntime
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.model.SpeechCredentials
import io.github.mangi.eta.data.model.SpeechSettings
import io.github.mangi.eta.data.model.WebToolsCredentials
import io.github.mangi.eta.data.model.WebToolsSettings
import io.github.mangi.eta.data.repository.LinuxEnvironmentSettingsRepository
import io.github.mangi.eta.data.repository.SpeechSettingsRepository
import io.github.mangi.eta.data.repository.WebToolsSettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.json.JSONArray
import org.json.JSONObject

/**
 * `eta_prefs_*` 的执行侧：把 [AgentSelfPrefsSpec] 的登记表接到**设置页同一份存储**上。
 *
 * 四条不变量：
 * 1. 只按登记表读写，键不在 [AgentSelfPrefsSpec.byKey] 里就直接拒绝（白名单制）；
 * 2. 写 JSON 设置走 [SettingsDataStore.patchWebToolsSettingsRaw] /
 *    [SettingsDataStore.patchSpeechSettingsRaw]：**在 `dataStore.edit {}` 事务内**读原始字符串、
 *    打补丁、写回，不会把并发写者的更新覆盖掉；
 * 3. 现有存储内容解析不出来时**拒绝写入**，绝不用默认值覆盖用户配置（[CODE_SETTINGS_UNREADABLE]）；
 * 4. 写入后必须读回校验，读回值与写入值不一致时返回失败而不是"看起来成功"。
 *
 * 凭据（[PrefTarget.WebCredential] / [PrefTarget.SpeechCredential]）只经
 * [WebToolsSettingsRepository.saveCredentials] / [SpeechSettingsRepository.saveCredentials]
 * 落盘（AndroidKeyStore + AES/GCM），明文凭据既不出现在返回里，也不会被写进 DataStore 的普通设置 JSON。
 */
/** 按点分路径改写 JSON 对象；路径不存在时保持原样，由读回校验暴露出来。 */
private fun withPath(root: JsonObject, path: String, value: JsonElement): JsonObject {
    val segments = path.split('.')
    fun apply(current: JsonObject, index: Int): JsonObject {
        if (index > segments.lastIndex) return current
        val name = segments[index]
        return buildJsonObject {
            current.forEach { (key, child) ->
                when {
                    key != name -> put(key, child)
                    index == segments.lastIndex -> put(key, value)
                    child is JsonObject -> put(key, apply(child, index + 1))
                    else -> put(key, child)
                }
            }
        }
    }
    return apply(root, 0)
}

private fun pathValue(root: JsonObject, path: String): String? {
    var current: JsonElement = root
    for (segment in path.split('.')) {
        current = (current as? JsonObject)?.get(segment) ?: return null
    }
    if (current is JsonNull) return null
    if (current !is JsonPrimitive) return current.toString()
    return current.content
}

/** 带稳定错误码的存储失败：破坏性写入必须能被调用方按码识别，而不是靠解析消息。 */
private class PrefStoreFailure(val code: String, message: String) : Exception(message)

internal object AgentSelfPrefsTools {
    val TOOL_NAMES = setOf("eta_prefs_list", "eta_prefs_get", "eta_prefs_set")

    private const val CODE_DOMAIN_UNKNOWN = "PREF_DOMAIN_UNKNOWN"
    private const val CODE_MISSING_VALUE = "PREF_VALUE_MISSING"
    private const val CODE_WRITE_FAILED = "PREF_WRITE_FAILED"
    private const val CODE_VERIFY_FAILED = "PREF_VERIFY_FAILED"
    private const val CODE_CREDENTIALS_UNREADABLE = "PREF_CREDENTIALS_UNREADABLE"
    private const val CODE_SETTINGS_UNREADABLE = "PREF_SETTINGS_UNREADABLE"
    private const val CODE_TOOL_ERROR = "PREF_TOOL_ERROR"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
    }

    /**
     * 本进程内的写串行化。
     *
     * DataStore 事务本身保证单次读-改-写原子，但"校验 → 写 → 读回校验"这段跨了多次事务，
     * 两个并发的 `eta_prefs_set` 交错时读回结果会被对方改写。锁只管 Eta 内这条路径，
     * 不替代事务（两者都需要）。
     */
    private val writeLock = Mutex()

    fun execute(name: String, args: JSONObject, context: Context): String = try {
        val result = when (name) {
            "eta_prefs_list" -> list(args, context)
            "eta_prefs_get" -> get(args, context)
            "eta_prefs_set" -> set(args, context)
            else -> JSONObject()
                .put("ok", false)
                .put("code", CODE_TOOL_ERROR)
                .put("message", "未知配置读写工具：$name")
        }
        result.toString()
    } catch (failure: Exception) {
        if (isCancellation(failure)) throw failure
        JSONObject()
            .put("ok", false)
            .put("code", CODE_TOOL_ERROR)
            .put("message", failure.message?.takeIf(String::isNotBlank)
                ?: "读取 Eta 配置失败（${failure.javaClass.name}）")
            .toString()
    }

    /** 取消（Stop / 线程中断）不能被这条兜底吞掉，否则 Stop 之后工具还会继续跑。 */
    private fun isCancellation(failure: Throwable): Boolean =
        failure is CancellationException ||
            failure is InterruptedException ||
            failure is AgentRunCancelledException

    // ── 工具实现 ────────────────────────────────────────────────────────────

    private fun list(args: JSONObject, context: Context): JSONObject {
        val requested = args.optString("domain").trim()
        val domains = if (requested.isEmpty()) {
            PrefDomain.entries.toList()
        } else {
            val matched = PrefDomain.entries.firstOrNull { it.id.equals(requested, ignoreCase = true) }
                ?: return JSONObject()
                    .put("ok", false)
                    .put("code", CODE_DOMAIN_UNKNOWN)
                    .put(
                        "message",
                        "未知配置域：$requested；可用：${PrefDomain.entries.joinToString(", ") { it.id }}",
                    )
            listOf(matched)
        }
        // 一次读盘快照：65 个键共用同一份数据，不再逐个键去阻塞读。
        val snapshot = try {
            runBlocking { readSnapshot(context, includeCredentials = true) }
        } catch (failure: Exception) {
            if (isCancellation(failure)) throw failure
            return storageError(failure)
        }
        val payload = JSONArray()
        domains.forEach { domain ->
            val entries = JSONArray()
            AgentSelfPrefsSpec.byDomain(domain).forEach { spec -> entries.put(entryJson(spec, snapshot)) }
            payload.put(
                JSONObject()
                    .put("domain", domain.id)
                    .put("title", domain.title)
                    .put("keys", entries),
            )
        }
        return JSONObject()
            .put("ok", true)
            .put("domains", payload)
            .put("total_keys", domains.sumOf { AgentSelfPrefsSpec.byDomain(it).size })
            .put("notes", notes())
    }

    private fun get(args: JSONObject, context: Context): JSONObject {
        val key = args.optString("key").trim()
        if (key.isEmpty()) return unknownKey(key)
        val spec = AgentSelfPrefsSpec.find(key) ?: return unknownKey(key)
        val snapshot = try {
            runBlocking { readSnapshot(context, includeCredentials = true) }
        } catch (failure: Exception) {
            if (isCancellation(failure)) throw failure
            return storageError(failure)
        }
        return JSONObject()
            .put("ok", true)
            .put("domain", spec.domain.id)
            .put("pref", entryJson(spec, snapshot))
    }

    private fun unknownKey(key: String): JSONObject = JSONObject()
        .put("ok", false)
        .put("code", AgentSelfPrefsRules.CODE_KEY_UNKNOWN)
        .put("message", "未登记的配置键：$key；可管理键见 eta_prefs_list")

    private fun set(args: JSONObject, context: Context): JSONObject {
        val key = args.optString("key").trim()
        val spec = AgentSelfPrefsSpec.find(key) ?: return unknownKey(key)
        if (!args.has("value")) {
            return JSONObject()
                .put("ok", false)
                .put("key", spec.key)
                .put("code", CODE_MISSING_VALUE)
                .put("message", "缺少 value 参数")
        }
        val normalized = when (val check = AgentSelfPrefsRules.validate(spec, rawArgument(args))) {
            is PrefCheck.Rejected -> return JSONObject()
                .put("ok", false)
                .put("key", spec.key)
                .put("code", check.code)
                .put("message", check.message)
                .put("options", JSONArray(spec.options))
            is PrefCheck.Accepted -> check.value
        }
        return runBlocking { writeLock.withLock { commitPref(context, spec, normalized) } }
    }

    private suspend fun commitPref(context: Context, spec: PrefSpec, normalized: String): JSONObject {
        // 登记表管不了的前置条件（需要设备能力）在这里拦：例如未授权 Root 时不许切 chroot。
        AgentSelfPrefsRules.precondition(spec, normalized, TerminalRuntime.rootAvailable)?.let { rejected ->
            return JSONObject()
                .put("ok", false)
                .put("key", spec.key)
                .put("code", rejected.code)
                .put("message", rejected.message)
        }
        val previous = readQuietly(context, spec)
        try {
            writeValue(context, spec, normalized)
        } catch (failure: Exception) {
            if (isCancellation(failure)) throw failure
            val code = (failure as? PrefStoreFailure)?.code ?: CODE_WRITE_FAILED
            return JSONObject()
                .put("ok", false)
                .put("key", spec.key)
                .put("code", code)
                .put("message", failure.message?.takeIf(String::isNotBlank)
                    ?: "写入失败（${failure.javaClass.name}）")
        }
        val verifyRead = readForVerify(context, spec)
        val verifiedRaw = verifyRead.value
        val verified = valuesMatch(spec, normalized, verifiedRaw)
        val result = JSONObject()
            .put("ok", verified)
            .put("key", spec.key)
            .put("domain", spec.domain.id)
            .put("storage", storageLabel(spec))
            .put("value", display(spec, normalized))
            .put("previous", display(spec, previous))
            .put("verified", verified)
        if (!verified) {
            val reason = verifyRead.failure?.message
                ?: "读回值为 ${display(spec, verifiedRaw)}"
            result
                .put("code", verifyRead.failure?.code ?: CODE_VERIFY_FAILED)
                .put(
                    "message",
                    "写入后读回校验未通过（$reason）；" +
                        "设置可能未生效，请用 eta_prefs_get 复核，不要假设已写入。",
                )
        } else {
            result.put(
                "note",
                if (spec.secret) {
                    "已在加密凭据仓库内更新并读回校验；返回值为脱敏后的确认值。"
                } else {
                    "已在 dataStore.edit 事务内打补丁并读回校验；重新进入设置页即可看到新值。"
                },
            )
        }
        return result
    }

    // ── 读盘快照 ────────────────────────────────────────────────────────────

    /** 把读盘硬失败（例如凭据解不开）翻译成带稳定错误码的结果。 */
    private fun storageError(failure: Exception): JSONObject = JSONObject()
        .put("ok", false)
        .put("code", (failure as? PrefStoreFailure)?.code ?: CODE_TOOL_ERROR)
        .put("message", failure.message?.takeIf(String::isNotBlank)
            ?: "读取 Eta 配置失败（${failure.javaClass.name}）")

    /** 写入前记录旧值；旧值读不出来不影响写入本身（写入路径会给出准确的错误码）。 */
    private fun readQuietly(context: Context, spec: PrefSpec): String? = try {
        runBlocking { readSnapshot(context, spec.inCredentialStore).valueOf(spec).value }
    } catch (failure: Exception) {
        if (isCancellation(failure)) throw failure
        null
    }

    /** 写入后的校验读；读不出来时把原因带进校验失败消息，而不是静默当成"已生效"。 */
    private fun readForVerify(context: Context, spec: PrefSpec): PrefRead = try {
        runBlocking { readSnapshot(context, spec.inCredentialStore).valueOf(spec) }
    } catch (failure: Exception) {
        if (isCancellation(failure)) throw failure
        PrefRead(
            value = null,
            failure = failure as? PrefStoreFailure
                ?: PrefStoreFailure(CODE_VERIFY_FAILED, failure.message ?: failure.javaClass.name),
        )
    }

    /** 一次读盘得到的全部原始内容；`list` / `get` / `set` 的读都基于它，避免逐键重复读。 */
    private class PrefSnapshot(
        val webSettings: SnapshotValue,
        val speechSettings: SnapshotValue,
        val webCredentials: SnapshotValue,
        val speechCredentials: SnapshotValue,
        val linuxDistribution: String,
        val linuxBackends: Map<String, String>,
    ) {
        fun valueOf(spec: PrefSpec): PrefRead = when (val target = spec.target) {
            is PrefTarget.WebSetting -> webSettings.read(target.path)
            is PrefTarget.WebCredential -> webCredentials.read(target.path)
            is PrefTarget.SpeechSetting -> speechSettings.read(target.path)
            is PrefTarget.SpeechCredential -> speechCredentials.read(target.path)
            PrefTarget.LinuxDistribution -> PrefRead(linuxDistribution, null)
            is PrefTarget.LinuxBackend -> PrefRead(linuxBackends[target.distribution], null)
        }
    }

    /** JSON 形态的一份存储：正常时是对象，解析不出来时只带失败原因（此时一律拒绝写入）。 */
    private class SnapshotValue(val root: JsonObject?, val failure: PrefStoreFailure?) {
        fun read(path: String): PrefRead =
            failure?.let { PrefRead(null, it) } ?: PrefRead(root?.let { pathValue(it, path) }, null)
    }

    private data class PrefRead(val value: String?, val failure: PrefStoreFailure?)

    /** 本次不涉及凭据时的占位：凭据键永远不会走到它（调用方按 `spec.inCredentialStore` 传参）。 */
    private val EMPTY_SNAPSHOT = SnapshotValue(JsonObject(emptyMap()), null)

    /**
     * @param includeCredentials 是否连凭据仓库一起读。凭据解密要走 AndroidKeyStore，
     *   只有目标键本身是凭据（或 `list` / `get` 要展示全部键）时才需要。
     */
    private suspend fun readSnapshot(context: Context, includeCredentials: Boolean): PrefSnapshot = PrefSnapshot(
        webSettings = decodeStored(
            raw = SettingsDataStore.webToolsSettingsRaw(),
            serializer = WebToolsSettings.serializer(),
            fallback = WebToolsSettings(),
            label = "网页工具设置",
        ),
        speechSettings = decodeStored(
            raw = SettingsDataStore.speechSettingsRaw(),
            serializer = SpeechSettings.serializer(),
            fallback = SpeechSettings(),
            label = "语音设置",
        ),
        webCredentials = if (includeCredentials) {
            SnapshotValue(
                root = credentialRoot(
                    label = "网页工具凭据",
                    load = { WebToolsSettingsRepository.credentials(context) },
                    serializer = WebToolsCredentials.serializer(),
                ),
                failure = null,
            )
        } else {
            EMPTY_SNAPSHOT
        },
        speechCredentials = if (includeCredentials) {
            SnapshotValue(
                root = credentialRoot(
                    label = "语音凭据",
                    load = { SpeechSettingsRepository.credentials(context) },
                    serializer = SpeechCredentials.serializer(),
                ),
                failure = null,
            )
        } else {
            EMPTY_SNAPSHOT
        },
        linuxDistribution = LinuxEnvironmentSettingsRepository.selectedFlow(context).first().wireName,
        linuxBackends = LinuxDistribution.entries.associate { distribution ->
            distribution.wireName to
                LinuxEnvironmentSettingsRepository.backendFlow(context, distribution).first().wireName
        },
    )

    /**
     * 解析已落盘的 JSON。
     *
     * 关键区别：`raw == null`（从没写过）才允许按默认值起手；**存在但解析不出来**时返回带失败的
     * 快照，调用方据此拒绝写入与展示，而不是把默认值当成真相。
     */
    private fun <T> decodeStored(
        raw: String?,
        serializer: KSerializer<T>,
        fallback: T,
        label: String,
    ): SnapshotValue {
        if (raw == null) {
            return SnapshotValue(json.encodeToJsonElement(serializer, fallback) as JsonObject, null)
        }
        return try {
            val decoded = json.decodeFromString(serializer, raw)
            SnapshotValue(json.encodeToJsonElement(serializer, decoded) as JsonObject, null)
        } catch (failure: Exception) {
            if (isCancellation(failure)) throw failure
            SnapshotValue(
                root = null,
                failure = PrefStoreFailure(
                    CODE_SETTINGS_UNREADABLE,
                    "$label 现有存储内容无法解析（${failure.message ?: failure.javaClass.name}）；" +
                        "请先在设置页确认并保存，Agent 不会用默认值覆盖它",
                ),
            )
        }
    }

    private suspend fun <T> credentialRoot(
        label: String,
        load: suspend () -> T,
        serializer: KSerializer<T>,
    ): JsonObject = try {
        json.encodeToJsonElement(serializer, load()) as JsonObject
    } catch (failure: Exception) {
        if (isCancellation(failure)) throw failure
        // 凭据解不开时不能当作"全部为空"：那会让后续写入覆盖掉其它仍然有效的凭据。
        throw PrefStoreFailure(
            CODE_CREDENTIALS_UNREADABLE,
            "$label 无法解密（${failure.message ?: failure.javaClass.name}），请先在设置页重新填写并保存",
        )
    }

    // ── 写入 ────────────────────────────────────────────────────────────────

    private suspend fun writeValue(context: Context, spec: PrefSpec, normalized: String) {
        val element = jsonElementOf(spec, normalized)
        when (val target = spec.target) {
            is PrefTarget.WebSetting -> {
                val applied = SettingsDataStore.patchWebToolsSettingsRaw { raw ->
                    patchedRaw(raw, WebToolsSettings.serializer(), WebToolsSettings(), target.path, element, "网页工具设置")
                }
                if (!applied) throw PrefStoreFailure(CODE_WRITE_FAILED, "网页工具设置未能写入存储")
            }
            is PrefTarget.WebCredential -> {
                val edited = json.decodeFromString(
                    WebToolsCredentials.serializer(),
                    withPath(credentialRoot("网页工具凭据", { WebToolsSettingsRepository.credentials(context) }, WebToolsCredentials.serializer()), target.path, element).toString(),
                )
                WebToolsSettingsRepository.saveCredentials(context, edited)
            }
            is PrefTarget.SpeechSetting -> {
                val applied = SettingsDataStore.patchSpeechSettingsRaw { raw ->
                    patchedRaw(raw, SpeechSettings.serializer(), SpeechSettings(), target.path, element, "语音设置")
                }
                if (!applied) throw PrefStoreFailure(CODE_WRITE_FAILED, "语音设置未能写入存储")
            }
            is PrefTarget.SpeechCredential -> {
                val edited = json.decodeFromString(
                    SpeechCredentials.serializer(),
                    withPath(credentialRoot("语音凭据", { SpeechSettingsRepository.credentials(context) }, SpeechCredentials.serializer()), target.path, element).toString(),
                )
                SpeechSettingsRepository.saveCredentials(context, edited)
            }
            PrefTarget.LinuxDistribution -> {
                val distribution = requireDistribution(normalized)
                LinuxEnvironmentSettingsRepository.select(distribution)
            }
            is PrefTarget.LinuxBackend -> {
                val distribution = requireDistribution(target.distribution)
                val backend = LinuxExecutionBackend.entries.firstOrNull { it.wireName == normalized }
                    ?: throw PrefStoreFailure(CODE_WRITE_FAILED, "未知执行后端：$normalized")
                LinuxEnvironmentSettingsRepository.selectBackend(distribution, backend)
            }
        }
    }

    /**
     * 在事务内算出要落盘的新 JSON：现有内容解析不出来就抛
     * [CODE_SETTINGS_UNREADABLE]，让整个事务失败（存储保持原值），绝不写默认值。
     */
    private fun <T> patchedRaw(
        raw: String?,
        serializer: KSerializer<T>,
        fallback: T,
        path: String,
        value: JsonElement,
        label: String,
    ): String {
        val current = decodeStored(raw, serializer, fallback, label)
        current.failure?.let { throw it }
        val root = current.root ?: throw PrefStoreFailure(CODE_SETTINGS_UNREADABLE, "$label 内容不可用")
        val patched = withPath(root, path, value)
        // 落盘前用同一份模型定义解码一次，确保写进去的内容一定读得回来。
        json.decodeFromString(serializer, patched.toString())
        return patched.toString()
    }

    private fun jsonElementOf(spec: PrefSpec, normalized: String): JsonElement = when (spec.kind) {
        PrefKind.BOOLEAN -> JsonPrimitive(normalized == "true")
        PrefKind.INTEGER -> JsonPrimitive(normalized.toInt())
        PrefKind.DECIMAL -> JsonPrimitive(normalized.toDouble())
        else -> JsonPrimitive(normalized)
    }

    // ── 展示与校验辅助 ──────────────────────────────────────────────────────

    private fun entryJson(spec: PrefSpec, snapshot: PrefSnapshot): JSONObject {
        val read = snapshot.valueOf(spec)
        val raw = read.value
        val entry = JSONObject()
            .put("key", spec.key)
            .put("type", spec.kind.name.lowercase())
            .put("secret", spec.secret)
            .put("configured", !raw.isNullOrEmpty())
            .put("value", if (spec.secret) AgentSelfPrefsRules.mask(raw) else raw ?: JSONObject.NULL)
            .put("storage", storageLabel(spec))
            .put("description", spec.description)
            .put("requires_confirmation", AgentSelfPrefsRules.confirmationReason(spec) != null)
        if (spec.options.isNotEmpty()) entry.put("options", JSONArray(spec.options))
        if (spec.allowEmpty) entry.put("allows_empty", true)
        read.failure?.let { failure ->
            entry.put(
                "error",
                JSONObject().put("code", failure.code).put("message", failure.message.orEmpty()),
            )
        }
        return entry
    }

    private fun display(spec: PrefSpec, raw: String?): Any =
        if (spec.secret) AgentSelfPrefsRules.mask(raw) else raw ?: JSONObject.NULL

    private fun valuesMatch(spec: PrefSpec, expected: String, actual: String?): Boolean {
        if (actual == null) return false
        return when (spec.kind) {
            PrefKind.BOOLEAN -> actual == expected
            PrefKind.INTEGER -> actual.toIntOrNull() == expected.toIntOrNull()
            PrefKind.DECIMAL -> actual.toDoubleOrNull() == expected.toDoubleOrNull()
            else -> actual == expected
        }
    }

    private fun storageLabel(spec: PrefSpec): String = when (val target = spec.target) {
        is PrefTarget.WebSetting -> "DataStore:web_tools_settings_v1#${target.path}"
        is PrefTarget.WebCredential -> "AndroidKeyStore:eta_web_tools_secrets#${target.path}"
        is PrefTarget.SpeechSetting -> "DataStore:speech_settings_v1#${target.path}"
        is PrefTarget.SpeechCredential -> "AndroidKeyStore:eta_speech_secrets#${target.path}"
        PrefTarget.LinuxDistribution -> "DataStore:linux_distribution"
        is PrefTarget.LinuxBackend -> "DataStore:linux_backend.${target.distribution}"
    }

    private fun requireDistribution(wireName: String): LinuxDistribution =
        LinuxDistribution.entries.firstOrNull { it.wireName == wireName }
            ?: throw PrefStoreFailure(CODE_WRITE_FAILED, "未知 Linux 发行版：$wireName")

    private fun rawArgument(args: JSONObject): String = when (val value = args.opt("value")) {
        null, JSONObject.NULL -> ""
        is Boolean -> value.toString()
        is Number -> {
            val asDouble = value.toDouble()
            if (asDouble.isFinite() && asDouble == Math.floor(asDouble)) {
                asDouble.toLong().toString()
            } else {
                value.toString()
            }
        }
        else -> value.toString()
    }

    private fun notes(): String = buildString {
        append("所有键都写入设置页使用的同一份存储（DataStore / AndroidKeyStore 凭据仓库）：")
        append("JSON 设置在 dataStore.edit 事务内打补丁，写后读回校验；")
        append("现有存储内容解析不出来时拒绝写入（$CODE_SETTINGS_UNREADABLE），不会用默认值覆盖。")
        append("凭据读取一律脱敏（长度 ≥ ${AgentSelfPrefsRules.MASK_FULL_HIDE_BELOW} 时保留前 4 后 4，更短的整体隐藏），空串表示清除。")
        append("服务地址、凭据、Linux 执行环境这几类键写入前需要用户确认（见 requires_confirmation）；")
        append("凭据无法解密时返回 $CODE_CREDENTIALS_UNREADABLE，需要用户在设置页重新填写。")
    }
}

