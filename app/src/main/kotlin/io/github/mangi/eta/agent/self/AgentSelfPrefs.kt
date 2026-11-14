package io.github.mangi.eta.agent.self

import io.github.mangi.eta.agent.terminal.LinuxDistribution

/**
 * Eta 自身配置域的**登记表与取值规则**（纯逻辑，不依赖 Android，可在纯 JVM 单测里跑）。
 *
 * 为什么要有这一层：
 * - Agent 过去只能改工具组开关（[Prefs.Keys.LOCAL_AGENT_KEYS]），网页工具、语音、Linux 环境
 *   这三类设置只能让用户到设置页手点，或者用 root 直接改文件（后者绕过了类型校验、加密存储与
 *   DataStore 的并发约束）。这里把三类配置改造成"键 → 类型 → 存储位置"的显式白名单，
 *   [AgentSelfPrefsTools] 只按登记表读写，不再有任何"猜字段"的路径。
 * - 白名单制是硬约束：未登记的键一律 `PREF_KEY_UNKNOWN`，即使底层存储里存在该字段也读不到。
 * - 存储位置与设置页完全一致（[PrefTarget] 指向 DataStore 里的 WebToolsSettings /
 *   SpeechSettings，凭据指向 AndroidKeyStore 加密的凭据仓库），所以写进去的值设置页立刻可见，
 *   反之亦然。
 *
 * 命名约定：`<域>.<字段>`，例如 `web.custom_search_url`。键一旦发布就不要改名——它是模型侧
 * 调用面的一部分。
 */

/** 可被 Agent 读写的配置域。 */
internal enum class PrefDomain(val id: String, val title: String) {
    WEB("web", "网页搜索与读取"),
    SPEECH("speech", "语音与朗读"),
    LINUX("linux", "Linux 工具环境"),
}

/**
 * 取值类型。类型决定校验规则与写入时的 JSON 原语类型：
 * - [BOOLEAN] / [INTEGER] / [DECIMAL] 写成 JSON 布尔或数字，而不是字符串；
 * - [ENUM] 只接受 [PrefSpec.options] 里的值（大小写不敏感，落盘时归一化成登记表里的大小写）；
 * - [URL] 必须是 http(s)，允许 `{{query}}` 这类模板占位符（网页工具允许本机 http 服务）；
 * - [HTTPS_URL] 必须是**不含认证信息、查询参数与片段**的 HTTPS 地址，对齐语音运行期的
 *   `SpeechProtocol.speechBaseUrl`：写进去以后一定会被这个规则再校验一次，所以这里先拦；
 * - [OSS_BUCKET] / [OSS_PREFIX] 对齐语音 OSS 上传期的 bucket 名与目录前缀规则；
 * - [TEXT] / [TEMPLATE] 是自由文本（请求头、请求体模板、字段名），允许为空以清空配置；
 * - [SECRET] 是凭据，只存在于 AndroidKeyStore 加密的凭据仓库，读取一律脱敏。
 */
internal enum class PrefKind {
    BOOLEAN,
    INTEGER,
    DECIMAL,
    ENUM,
    URL,
    HTTPS_URL,
    OSS_BUCKET,
    OSS_PREFIX,
    TEXT,
    TEMPLATE,
    SECRET,
}

/**
 * 键的落盘位置。刻意做成数据而不是函数：这样整张登记表可以被单测直接遍历核对，
 * 也避免出现"登记了却没人写"的空键。
 */
internal sealed interface PrefTarget {
    /** DataStore 里 `web_tools_settings_v1` 的字段路径（`customSearch.url` 这种点分路径）。 */
    data class WebSetting(val path: String) : PrefTarget

    /** 网页工具凭据仓库（AES/GCM + AndroidKeyStore）的字段路径。 */
    data class WebCredential(val path: String) : PrefTarget

    /** DataStore 里 `speech_settings_v1` 的字段路径。 */
    data class SpeechSetting(val path: String) : PrefTarget

    /** 语音凭据仓库的字段路径。 */
    data class SpeechCredential(val path: String) : PrefTarget

    /** `linux_distribution`：当前生效的发行版。 */
    data object LinuxDistribution : PrefTarget

    /** `linux_backend.<distribution>`：某个发行版使用的执行后端。 */
    data class LinuxBackend(val distribution: String) : PrefTarget
}

/** 一个可管理的配置键。 */
internal data class PrefSpec(
    val key: String,
    val domain: PrefDomain,
    val kind: PrefKind,
    val target: PrefTarget,
    val description: String,
    /** [PrefKind.ENUM] 的合法值；[PrefKind.INTEGER] 也可用它限定离散取值。 */
    val options: List<String> = emptyList(),
    val minValue: Double? = null,
    val maxValue: Double? = null,
    val maxLength: Int? = null,
    /**
     * 是否允许空串。默认不允许——空串对多数键不是"恢复默认"而是"配置缺失"；
     * 只有语义上明确以空表示"未配置 / 按默认"的键（网页自定义接口地址、清空凭据、OSS 前缀等）
     * 才打开，避免模型误用空串把设置写坏。
     */
    val allowEmpty: Boolean = false,
) {
    val secret: Boolean get() = kind == PrefKind.SECRET

    /** 凭据仓库里的键（用于向用户解释"改写走加密存储"）。 */
    val inCredentialStore: Boolean
        get() = target is PrefTarget.WebCredential || target is PrefTarget.SpeechCredential
}

/** 校验结果：要么给出归一化后的可落盘字符串，要么给出稳定的错误码与说明。 */
internal sealed interface PrefCheck {
    data class Accepted(val value: String) : PrefCheck

    data class Rejected(val code: String, val message: String) : PrefCheck
}

/**
 * 取值规则。
 *
 * 所有规则都必须是**确定性**的：同一输入永远得到同一归一化结果，否则"写后读回校验"会假失败。
 */
internal object AgentSelfPrefsRules {
    const val CODE_KEY_UNKNOWN = "PREF_KEY_UNKNOWN"
    const val CODE_VALUE_INVALID = "PREF_VALUE_INVALID"
    const val CODE_PRECONDITION_FAILED = "PREF_PRECONDITION_FAILED"

    /** 短于该长度的凭据整体隐藏，不做"前 4 后 4"，否则等于把大部分内容念出来。 */
    const val MASK_FULL_HIDE_BELOW = 16

    /** 与 `SpeechProtocol` 的运行期 bucket 规则一致：3~63 位小写字母/数字/短横线，首尾为字母或数字。 */
    val OSS_BUCKET_PATTERN by lazy { Regex("[a-z0-9][a-z0-9-]{1,61}[a-z0-9]") }

    private val PLACEHOLDER by lazy { Regex("\\{\\{[A-Za-z0-9_]+\\}\\}") }
    private val URL_SCHEMES = listOf("http://", "https://")

    /**
     * 凭据脱敏：空值返回空串（调用方显示"未设置"），长度 ≥ 16 时保留前 4 位与后 4 位、
     * 中间固定 6 个星号（不泄露真实长度），更短的值整体用 `****` 覆盖。
     */
    fun mask(value: String?): String {
        val text = value.orEmpty()
        if (text.isEmpty()) return ""
        if (text.length < MASK_FULL_HIDE_BELOW) return "****"
        return text.take(4) + "******" + text.takeLast(4)
    }

    /** 校验并归一化一个待写入的值。 */
    fun validate(spec: PrefSpec, raw: String?): PrefCheck {
        val text = raw.orEmpty().trim()
        spec.maxLength?.let { limit ->
            if (text.length > limit) {
                return reject("${spec.key} 最多 $limit 字，当前 ${text.length} 字")
            }
        }
        return when (spec.kind) {
            PrefKind.SECRET, PrefKind.TEXT, PrefKind.TEMPLATE -> PrefCheck.Accepted(text)
            PrefKind.BOOLEAN -> validateBoolean(spec, text)
            PrefKind.INTEGER -> validateInteger(spec, text)
            PrefKind.DECIMAL -> validateDecimal(spec, text)
            PrefKind.ENUM -> validateEnum(spec, text)
            PrefKind.URL -> validateUrl(spec, text, requireHttps = false)
            PrefKind.HTTPS_URL -> validateUrl(spec, text, requireHttps = true)
            PrefKind.OSS_BUCKET -> validateOssBucket(spec, text)
            PrefKind.OSS_PREFIX -> validateOssPrefix(spec, text)
        }
    }

    /**
     * 运行时前置条件：登记表管不了的部分（需要设备能力或外部状态）集中在这里，
     * 由执行侧在写入前调用。返回 null 表示可以写。
     */
    fun precondition(spec: PrefSpec, value: String, rootAvailable: Boolean): PrefCheck.Rejected? = when {
        // 对齐终端页门槛：chroot 需要 Root 授权，未授权时只能选 proot。
        spec.target is PrefTarget.LinuxBackend && value == CHROOT_BACKEND && !rootAvailable ->
            PrefCheck.Rejected(
                CODE_PRECONDITION_FAILED,
                "切换到 chroot 需要 Root 授权；当前未授权，只能使用 proot（与终端页的后端选择门槛一致）",
            )
        else -> null
    }

    /**
     * 该键写入前是否需要用户在聊天流内确认；返回 null 表示可以直接写。
     *
     * 判定放在这里而不是执行侧：它是"这个键有多敏感"的静态属性，危险判定器
     * （[io.github.mangi.eta.agent.approval.AgentToolRiskEvaluator]）与单测共用同一份规则，
     * 不会出现两处各写一套、改一处漏一处。
     */
    fun confirmationReason(spec: PrefSpec): String? = when {
        spec.secret -> "写入或清除凭据（加密存储），之后会用它访问外部服务"
        spec.kind == PrefKind.URL || spec.kind == PrefKind.HTTPS_URL ->
            "修改服务地址：请求与其中的凭据会被发往新地址"
        spec.target is PrefTarget.LinuxDistribution || spec.target is PrefTarget.LinuxBackend ->
            "切换 Linux 执行环境：之后所有终端命令都在新环境里跑"
        else -> null
    }

    /** 后端 wire name；与 LinuxExecutionBackend 对齐（那边在 Android 依赖文件里，这里保持纯字符串）。 */
    const val CHROOT_BACKEND = "chroot"
    const val PROOT_BACKEND = "proot"

    private fun reject(message: String): PrefCheck = PrefCheck.Rejected(CODE_VALUE_INVALID, message)

    private fun validateBoolean(spec: PrefSpec, text: String): PrefCheck = when (text.lowercase()) {
        "true", "1", "on", "yes" -> PrefCheck.Accepted("true")
        "false", "0", "off", "no" -> PrefCheck.Accepted("false")
        else -> reject("${spec.key} 只接受 true 或 false，收到「$text」")
    }

    private fun validateInteger(spec: PrefSpec, text: String): PrefCheck {
        val parsed = text.toIntOrNull()
            ?: return reject("${spec.key} 需要整数，收到「$text」")
        if (spec.options.isNotEmpty()) {
            if (parsed.toString() !in spec.options) {
                return reject("${spec.key} 只接受 ${spec.options.joinToString(" / ")}，收到「$text」")
            }
            return PrefCheck.Accepted(parsed.toString())
        }
        spec.minValue?.let { min ->
            if (parsed < min) return reject("${spec.key} 不能小于 ${min.toInt()}")
        }
        spec.maxValue?.let { max ->
            if (parsed > max) return reject("${spec.key} 不能大于 ${max.toInt()}")
        }
        return PrefCheck.Accepted(parsed.toString())
    }

    private fun validateDecimal(spec: PrefSpec, text: String): PrefCheck {
        val parsed = text.toDoubleOrNull()
            ?: return reject("${spec.key} 需要数字，收到「$text」")
        if (parsed.isNaN() || parsed.isInfinite()) return reject("${spec.key} 需要有限数字")
        spec.minValue?.let { min ->
            if (parsed < min) return reject("${spec.key} 不能小于 $min")
        }
        spec.maxValue?.let { max ->
            if (parsed > max) return reject("${spec.key} 不能大于 $max")
        }
        return PrefCheck.Accepted(trimNumber(parsed))
    }

    private fun trimNumber(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

    private fun validateEnum(spec: PrefSpec, text: String): PrefCheck {
        if (text.isEmpty()) {
            return reject("${spec.key} 不能为空，可选值：${spec.options.joinToString(" / ")}")
        }
        val matched = spec.options.firstOrNull { it.equals(text, ignoreCase = true) }
            ?: return reject("${spec.key} 只接受 ${spec.options.joinToString(" / ")}，收到「$text」")
        return PrefCheck.Accepted(matched)
    }

    private fun validateUrl(spec: PrefSpec, text: String, requireHttps: Boolean): PrefCheck {
        if (text.isEmpty()) {
            return if (spec.allowEmpty) {
                PrefCheck.Accepted("")
            } else {
                reject("${spec.key} 不能为空，需要${if (requireHttps) " HTTPS" else " HTTP(S)"} 地址")
            }
        }
        if (text.any { it.isWhitespace() }) return reject("URL 不能包含空白字符：$text")
        // 模板占位符不是合法 URL 字符，先替换成占位再判断结构。
        val probe = PLACEHOLDER.replace(text, "placeholder")
        val scheme = URL_SCHEMES.firstOrNull { probe.startsWith(it, ignoreCase = true) }
            ?: return reject("URL 必须以 http:// 或 https:// 开头：$text")
        if (requireHttps && !probe.startsWith("https://", ignoreCase = true)) {
            return reject("${spec.key} 必须是 HTTPS 地址（明文 HTTP 会泄露凭据）：$text")
        }
        val rest = probe.substring(scheme.length)
        val authority = rest.substringBefore('/').substringBefore('?').substringBefore('#')
        if (authority.isEmpty()) return reject("URL 缺少主机名：$text")
        if (requireHttps) {
            // 对齐 SpeechProtocol.speechBaseUrl：不带认证信息、查询参数与片段。
            if (authority.contains('@')) return reject("URL 不能包含认证信息（user:password@）：$text")
            if ('?' in probe || '#' in probe) return reject("URL 不能包含查询参数或片段：$text")
        }
        return PrefCheck.Accepted(text)
    }

    private fun validateOssBucket(spec: PrefSpec, text: String): PrefCheck {
        if (text.isEmpty()) {
            return if (spec.allowEmpty) PrefCheck.Accepted("") else reject("${spec.key} 不能为空")
        }
        if (!OSS_BUCKET_PATTERN.matches(text)) {
            return reject(
                "${spec.key} 不是合法的 OSS Bucket 名：需 3~63 位小写字母、数字或短横线，且首尾为字母或数字",
            )
        }
        return PrefCheck.Accepted(text)
    }

    private fun validateOssPrefix(spec: PrefSpec, text: String): PrefCheck {
        if (text.isEmpty()) return PrefCheck.Accepted("")
        if (text.split('/').any { it == ".." || it == "." }) {
            return reject("${spec.key} 不允许 . 或 .. 路径段（会越出 bucket 目录）")
        }
        return PrefCheck.Accepted(text)
    }
}

/**
 * 全部登记的键。新增键只需要往 [all] 里加一行，并在 [AgentSelfPrefsTools] 支持的
 * [PrefTarget] 里已有对应实现——登记表本身不含任何 Android 依赖。
 */
internal object AgentSelfPrefsSpec {
    private const val DEFAULT_RESULTS_PATH = "results"
    private const val DEFAULT_TITLE_FIELD = "title"

    private val OPENAI_KEY_HINT = "读取时脱敏（前 4 后 4），写入走 AndroidKeyStore 加密的凭据仓库；空串表示清除。"

    val all: List<PrefSpec> = listOf(
        // ── 网页搜索与读取 ────────────────────────────────────────────────────
        PrefSpec(
            key = "web.search_backend",
            domain = PrefDomain.WEB,
            kind = PrefKind.ENUM,
            target = PrefTarget.WebSetting("searchBackend"),
            description = "网页搜索使用的后端：BUILTIN 内置默认、DEEPSEEK、CUSTOM 自定义接口。",
            options = listOf("BUILTIN", "DEEPSEEK", "CUSTOM"),
        ),
        PrefSpec(
            key = "web.fetch_backend",
            domain = PrefDomain.WEB,
            kind = PrefKind.ENUM,
            target = PrefTarget.WebSetting("fetchBackend"),
            description = "网页读取使用的后端：BUILTIN 内置默认、DEEPSEEK、CUSTOM 自定义接口。",
            options = listOf("BUILTIN", "DEEPSEEK", "CUSTOM"),
        ),
        PrefSpec(
            key = "web.deepseek_base_url",
            domain = PrefDomain.WEB,
            kind = PrefKind.URL,
            target = PrefTarget.WebSetting("deepSeek.baseUrl"),
            description = "DeepSeek 网页工具接口的 Base URL，默认 https://api.deepseek.com；留空表示回退到模型供应商或官方默认地址。",
            allowEmpty = true,
        ),
        PrefSpec(
            key = "web.deepseek_api_key",
            domain = PrefDomain.WEB,
            kind = PrefKind.SECRET,
            target = PrefTarget.WebCredential("deepSeekApiKey"),
            description = "DeepSeek 网页工具专用 API Key（留空时复用 DeepSeek 模型提供商的密钥）。$OPENAI_KEY_HINT",
        ),
        PrefSpec(
            key = "web.custom_search_method",
            domain = PrefDomain.WEB,
            kind = PrefKind.ENUM,
            target = PrefTarget.WebSetting("customSearch.method"),
            description = "自定义搜索的请求方式。",
            options = listOf("POST", "GET"),
        ),
        PrefSpec(
            key = "web.custom_search_url",
            domain = PrefDomain.WEB,
            kind = PrefKind.URL,
            target = PrefTarget.WebSetting("customSearch.url"),
            description = "自定义搜索接口地址，支持 {{query}} / {{query_json}} / {{count}} 占位符（如 http://127.0.0.1:8899/search）；留空表示未配置自定义搜索。",
            allowEmpty = true,
        ),
        PrefSpec(
            key = "web.custom_search_api_key",
            domain = PrefDomain.WEB,
            kind = PrefKind.SECRET,
            target = PrefTarget.WebCredential("customSearchApiKey"),
            description = "自定义搜索的 API Key（填写后自动附加 Bearer 鉴权头）。$OPENAI_KEY_HINT",
        ),
        PrefSpec(
            key = "web.custom_search_headers",
            domain = PrefDomain.WEB,
            kind = PrefKind.TEXT,
            target = PrefTarget.WebSetting("customSearch.headersText"),
            description = "自定义搜索请求头，多行，每行 `Header: Value`。",
        ),
        PrefSpec(
            key = "web.custom_search_body_template",
            domain = PrefDomain.WEB,
            kind = PrefKind.TEMPLATE,
            target = PrefTarget.WebSetting("customSearch.bodyTemplate"),
            description = "自定义搜索的请求体模板（JSON），支持 {{query_json}} / {{count}} 占位符。",
        ),
        PrefSpec(
            key = "web.custom_search_results_path",
            domain = PrefDomain.WEB,
            kind = PrefKind.TEXT,
            target = PrefTarget.WebSetting("customSearch.resultsPath"),
            description = "搜索结果数组在响应 JSON 里的路径，默认 $DEFAULT_RESULTS_PATH。",
        ),
        PrefSpec(
            key = "web.custom_search_title_field",
            domain = PrefDomain.WEB,
            kind = PrefKind.TEXT,
            target = PrefTarget.WebSetting("customSearch.titleField"),
            description = "结果项里的标题字段名，默认 $DEFAULT_TITLE_FIELD。",
        ),
        PrefSpec(
            key = "web.custom_search_url_field",
            domain = PrefDomain.WEB,
            kind = PrefKind.TEXT,
            target = PrefTarget.WebSetting("customSearch.urlField"),
            description = "结果项里的链接字段名，默认 url。",
        ),
        PrefSpec(
            key = "web.custom_search_snippet_field",
            domain = PrefDomain.WEB,
            kind = PrefKind.TEXT,
            target = PrefTarget.WebSetting("customSearch.snippetField"),
            description = "结果项里的摘要字段名，默认 snippet。",
        ),
        PrefSpec(
            key = "web.custom_fetch_method",
            domain = PrefDomain.WEB,
            kind = PrefKind.ENUM,
            target = PrefTarget.WebSetting("customFetch.method"),
            description = "自定义读取的请求方式。",
            options = listOf("POST", "GET"),
        ),
        PrefSpec(
            key = "web.custom_fetch_url",
            domain = PrefDomain.WEB,
            kind = PrefKind.URL,
            target = PrefTarget.WebSetting("customFetch.url"),
            description = "自定义读取接口地址，支持 {{url}} / {{raw_url}} / {{url_json}} 占位符；留空表示未配置自定义读取。",
            allowEmpty = true,
        ),
        PrefSpec(
            key = "web.custom_fetch_api_key",
            domain = PrefDomain.WEB,
            kind = PrefKind.SECRET,
            target = PrefTarget.WebCredential("customFetchApiKey"),
            description = "自定义读取的 API Key（填写后自动附加 Bearer 鉴权头）。$OPENAI_KEY_HINT",
        ),
        PrefSpec(
            key = "web.custom_fetch_headers",
            domain = PrefDomain.WEB,
            kind = PrefKind.TEXT,
            target = PrefTarget.WebSetting("customFetch.headersText"),
            description = "自定义读取请求头，多行，每行 `Header: Value`。",
        ),
        PrefSpec(
            key = "web.custom_fetch_body_template",
            domain = PrefDomain.WEB,
            kind = PrefKind.TEMPLATE,
            target = PrefTarget.WebSetting("customFetch.bodyTemplate"),
            description = "自定义读取的请求体模板（JSON），支持 {{url_json}} 占位符。",
        ),
        PrefSpec(
            key = "web.custom_fetch_content_field",
            domain = PrefDomain.WEB,
            kind = PrefKind.TEXT,
            target = PrefTarget.WebSetting("customFetch.contentField"),
            description = "响应里的正文字段路径，留空表示直接取响应文本。",
        ),
        PrefSpec(
            key = "web.custom_fetch_title_field",
            domain = PrefDomain.WEB,
            kind = PrefKind.TEXT,
            target = PrefTarget.WebSetting("customFetch.titleField"),
            description = "响应里的标题字段名，默认 $DEFAULT_TITLE_FIELD。",
        ),

        // ── 语音与朗读 ────────────────────────────────────────────────────────
        PrefSpec(
            key = "speech.asr_provider",
            domain = PrefDomain.SPEECH,
            kind = PrefKind.ENUM,
            target = PrefTarget.SpeechSetting("asr"),
            description = "语音识别提供商：QWEN_REALTIME 实时、QWEN_FLASH 快速、QWEN_FILE 文件、DOUBAO 豆包。",
            options = listOf("QWEN_REALTIME", "QWEN_FLASH", "QWEN_FILE", "DOUBAO"),
        ),
        PrefSpec(
            key = "speech.tts_provider",
            domain = PrefDomain.SPEECH,
            kind = PrefKind.ENUM,
            target = PrefTarget.SpeechSetting("tts"),
            description = "语音合成提供商：NONE 关闭朗读、QWEN、DOUBAO、GEMINI、MINIMAX。",
            options = listOf("NONE", "QWEN", "DOUBAO", "GEMINI", "MINIMAX"),
        ),
        PrefSpec(
            key = "speech.recognition_enabled",
            domain = PrefDomain.SPEECH,
            kind = PrefKind.BOOLEAN,
            target = PrefTarget.SpeechSetting("recognitionEnabled"),
            description = "是否启用 Eta 自身的语音输入；关闭时保留服务配置。",
        ),
        PrefSpec(
            key = "speech.auto_speak",
            domain = PrefDomain.SPEECH,
            kind = PrefKind.BOOLEAN,
            target = PrefTarget.SpeechSetting("autoSpeak"),
            description = "助手浮窗回答完成后是否自动播报。",
        ),
        PrefSpec(
            key = "speech.chat_auto_speak",
            domain = PrefDomain.SPEECH,
            kind = PrefKind.BOOLEAN,
            target = PrefTarget.SpeechSetting("chatAutoSpeak"),
            description = "聊天页回答完成后是否自动朗读最终回答。",
        ),
        PrefSpec(
            key = "speech.refine_transcript",
            domain = PrefDomain.SPEECH,
            kind = PrefKind.BOOLEAN,
            target = PrefTarget.SpeechSetting("refineTranscript"),
            description = "识别结果交付前是否用当前对话模型保守纠错。",
        ),
        PrefSpec(
            key = "speech.language",
            domain = PrefDomain.SPEECH,
            kind = PrefKind.TEXT,
            target = PrefTarget.SpeechSetting("language"),
            description = "语音识别语言提示（如 zh / en），留空表示自动。",
        ),
        PrefSpec(
            key = "speech.auto_send_silence_ms",
            domain = PrefDomain.SPEECH,
            kind = PrefKind.INTEGER,
            target = PrefTarget.SpeechSetting("autoSendSilenceMs"),
            description = "助手浮窗停顿多久自动发送（毫秒），0 表示只在点击完成或松手时发送。",
            options = listOf("0", "1000", "1500", "2000", "3000"),
        ),
        PrefSpec(
            key = "speech.qwen_asr_region",
            domain = PrefDomain.SPEECH,
            kind = PrefKind.ENUM,
            target = PrefTarget.SpeechSetting("qwenAsr.region"),
            description = "通义语音识别区域：BEIJING 国内、SINGAPORE 国际。",
            options = listOf("BEIJING", "SINGAPORE"),
        ),
        PrefSpec(
            key = "speech.qwen_asr_base_url",
            domain = PrefDomain.SPEECH,
            kind = PrefKind.HTTPS_URL,
            target = PrefTarget.SpeechSetting("qwenAsr.baseUrl"),
            description = "通义语音识别的 Base URL，留空按区域默认；填写时须为不含认证信息与查询参数的 HTTPS 地址。",
            allowEmpty = true,
        ),
        PrefSpec(
            key = "speech.qwen_asr_api_key",
            domain = PrefDomain.SPEECH,
            kind = PrefKind.SECRET,
            target = PrefTarget.SpeechCredential("qwenAsr"),
            description = "通义语音识别的 API Key。$OPENAI_KEY_HINT",
        ),
        PrefSpec(
            key = "speech.qwen_tts_region",
            domain = PrefDomain.SPEECH,
            kind = PrefKind.ENUM,
            target = PrefTarget.SpeechSetting("qwenTts.region"),
            description = "通义语音合成的区域：BEIJING 国内、SINGAPORE 国际。",
            options = listOf("BEIJING", "SINGAPORE"),
        ),
        PrefSpec(
            key = "speech.qwen_tts_base_url",
            domain = PrefDomain.SPEECH,
            kind = PrefKind.HTTPS_URL,
            target = PrefTarget.SpeechSetting("qwenTts.baseUrl"),
            description = "通义语音合成的 Base URL，留空按区域默认；填写时须为不含认证信息与查询参数的 HTTPS 地址。",
            allowEmpty = true,
        ),
        PrefSpec(
            key = "speech.qwen_tts_api_key",
            domain = PrefDomain.SPEECH,
            kind = PrefKind.SECRET,
            target = PrefTarget.SpeechCredential("qwenTts"),
            description = "通义语音合成的 API Key。$OPENAI_KEY_HINT",
        ),
        PrefSpec(
            key = "speech.qwen_voice",
            domain = PrefDomain.SPEECH,
            kind = PrefKind.TEXT,
            target = PrefTarget.SpeechSetting("qwenVoice"),
            description = "通义语音合成音色，默认 Cherry。",
        ),
        PrefSpec(
            key = "speech.doubao_asr_base_url",
            domain = PrefDomain.SPEECH,
            kind = PrefKind.HTTPS_URL,
            target = PrefTarget.SpeechSetting("doubaoAsr.baseUrl"),
            description = "豆包语音识别的 Base URL。",
        ),
        PrefSpec(
            key = "speech.doubao_asr_app_id",
            domain = PrefDomain.SPEECH,
            kind = PrefKind.TEXT,
            target = PrefTarget.SpeechSetting("doubaoAsr.appId"),
            description = "豆包语音识别的 App ID。",
        ),
        PrefSpec(
            key = "speech.doubao_asr_resource_id",
            domain = PrefDomain.SPEECH,
            kind = PrefKind.TEXT,
            target = PrefTarget.SpeechSetting("doubaoAsr.resourceId"),
            description = "豆包语音识别的资源 ID。",
        ),
        PrefSpec(
            key = "speech.doubao_asr_legacy_auth",
            domain = PrefDomain.SPEECH,
            kind = PrefKind.BOOLEAN,
            target = PrefTarget.SpeechSetting("doubaoAsr.legacyAuth"),
            description = "豆包语音识别是否使用旧版鉴权头。",
        ),
        PrefSpec(
            key = "speech.doubao_asr_api_key",
            domain = PrefDomain.SPEECH,
            kind = PrefKind.SECRET,
            target = PrefTarget.SpeechCredential("doubaoAsr"),
            description = "豆包语音识别的 Access Token。$OPENAI_KEY_HINT",
        ),
        PrefSpec(
            key = "speech.doubao_tts_base_url",
            domain = PrefDomain.SPEECH,
            kind = PrefKind.HTTPS_URL,
            target = PrefTarget.SpeechSetting("doubaoTts.baseUrl"),
            description = "豆包语音合成的 Base URL。",
        ),
        PrefSpec(
            key = "speech.doubao_tts_app_id",
            domain = PrefDomain.SPEECH,
            kind = PrefKind.TEXT,
            target = PrefTarget.SpeechSetting("doubaoTts.appId"),
            description = "豆包语音合成的 App ID。",
        ),
        PrefSpec(
            key = "speech.doubao_tts_resource_id",
            domain = PrefDomain.SPEECH,
            kind = PrefKind.TEXT,
            target = PrefTarget.SpeechSetting("doubaoTts.resourceId"),
            description = "豆包语音合成的资源 ID。",
        ),
        PrefSpec(
            key = "speech.doubao_tts_legacy_auth",
            domain = PrefDomain.SPEECH,
            kind = PrefKind.BOOLEAN,
            target = PrefTarget.SpeechSetting("doubaoTts.legacyAuth"),
            description = "豆包语音合成是否使用旧版鉴权头。",
        ),
        PrefSpec(
            key = "speech.doubao_tts_api_key",
            domain = PrefDomain.SPEECH,
            kind = PrefKind.SECRET,
            target = PrefTarget.SpeechCredential("doubaoTts"),
            description = "豆包语音合成的 Access Token。$OPENAI_KEY_HINT",
        ),
        PrefSpec(
            key = "speech.doubao_voice",
            domain = PrefDomain.SPEECH,
            kind = PrefKind.TEXT,
            target = PrefTarget.SpeechSetting("doubaoVoice"),
            description = "豆包语音合成音色。",
        ),
        PrefSpec(
            key = "speech.gemini_tts_base_url",
            domain = PrefDomain.SPEECH,
            kind = PrefKind.HTTPS_URL,
            target = PrefTarget.SpeechSetting("geminiTts.baseUrl"),
            description = "Gemini 语音合成的 Base URL。",
        ),
        PrefSpec(
            key = "speech.gemini_tts_api_key",
            domain = PrefDomain.SPEECH,
            kind = PrefKind.SECRET,
            target = PrefTarget.SpeechCredential("geminiTts"),
            description = "Gemini 语音合成的 API Key。$OPENAI_KEY_HINT",
        ),
        PrefSpec(
            key = "speech.gemini_tts_model",
            domain = PrefDomain.SPEECH,
            kind = PrefKind.ENUM,
            target = PrefTarget.SpeechSetting("geminiTts.model"),
            description = "Gemini 语音合成模型：FLASH 或 FLASH_LITE。",
            options = listOf("FLASH", "FLASH_LITE"),
        ),
        PrefSpec(
            key = "speech.gemini_tts_voice",
            domain = PrefDomain.SPEECH,
            kind = PrefKind.TEXT,
            target = PrefTarget.SpeechSetting("geminiTts.voice"),
            description = "Gemini 语音合成音色，默认 Kore。",
        ),
        PrefSpec(
            key = "speech.gemini_tts_style",
            domain = PrefDomain.SPEECH,
            kind = PrefKind.TEXT,
            target = PrefTarget.SpeechSetting("geminiTts.style"),
            description = "Gemini 语音合成风格提示词，最长 500 字（与设置页输入上限一致）。",
            maxLength = 500,
        ),
        PrefSpec(
            key = "speech.minimax_tts_base_url",
            domain = PrefDomain.SPEECH,
            kind = PrefKind.HTTPS_URL,
            target = PrefTarget.SpeechSetting("minimaxTts.baseUrl"),
            description = "MiniMax 语音合成的 Base URL。",
        ),
        PrefSpec(
            key = "speech.minimax_tts_api_key",
            domain = PrefDomain.SPEECH,
            kind = PrefKind.SECRET,
            target = PrefTarget.SpeechCredential("minimaxTts"),
            description = "MiniMax 语音合成的 API Key。$OPENAI_KEY_HINT",
        ),
        PrefSpec(
            key = "speech.minimax_tts_model",
            domain = PrefDomain.SPEECH,
            kind = PrefKind.ENUM,
            target = PrefTarget.SpeechSetting("minimaxTts.model"),
            description = "MiniMax 语音合成模型：TURBO 或 HD。",
            options = listOf("TURBO", "HD"),
        ),
        PrefSpec(
            key = "speech.minimax_tts_voice",
            domain = PrefDomain.SPEECH,
            kind = PrefKind.TEXT,
            target = PrefTarget.SpeechSetting("minimaxTts.voice"),
            description = "MiniMax 语音合成音色。",
        ),
        PrefSpec(
            key = "speech.minimax_tts_speed",
            domain = PrefDomain.SPEECH,
            kind = PrefKind.DECIMAL,
            target = PrefTarget.SpeechSetting("minimaxTts.speed"),
            description = "MiniMax 语音合成的语速倍数，默认 1.0。",
            minValue = 0.5,
            maxValue = 2.0,
        ),
        PrefSpec(
            key = "speech.oss_region",
            domain = PrefDomain.SPEECH,
            kind = PrefKind.TEXT,
            target = PrefTarget.SpeechSetting("oss.region"),
            description = "语音文件上传用的 OSS 区域，默认 cn-beijing。",
        ),
        PrefSpec(
            key = "speech.oss_endpoint",
            domain = PrefDomain.SPEECH,
            kind = PrefKind.HTTPS_URL,
            target = PrefTarget.SpeechSetting("oss.endpoint"),
            description = "语音文件上传用的 OSS Endpoint。",
        ),
        PrefSpec(
            key = "speech.oss_bucket",
            domain = PrefDomain.SPEECH,
            kind = PrefKind.OSS_BUCKET,
            target = PrefTarget.SpeechSetting("oss.bucket"),
            description = "语音文件上传用的 OSS Bucket（3~63 位小写字母/数字/短横线，首尾为字母或数字）；留空表示未配置。",
            allowEmpty = true,
        ),
        PrefSpec(
            key = "speech.oss_prefix",
            domain = PrefDomain.SPEECH,
            kind = PrefKind.OSS_PREFIX,
            target = PrefTarget.SpeechSetting("oss.prefix"),
            description = "语音文件在 OSS 上的路径前缀，默认 eta-speech/；不允许 . 或 .. 路径段。",
            allowEmpty = true,
        ),
        PrefSpec(
            key = "speech.oss_access_key_id",
            domain = PrefDomain.SPEECH,
            kind = PrefKind.SECRET,
            target = PrefTarget.SpeechCredential("ossAccessKeyId"),
            description = "OSS AccessKey ID。$OPENAI_KEY_HINT",
        ),
        PrefSpec(
            key = "speech.oss_access_key_secret",
            domain = PrefDomain.SPEECH,
            kind = PrefKind.SECRET,
            target = PrefTarget.SpeechCredential("ossAccessKeySecret"),
            description = "OSS AccessKey Secret。$OPENAI_KEY_HINT",
        ),

        // ── Linux 工具环境 ────────────────────────────────────────────────────
        PrefSpec(
            key = "linux.distribution",
            domain = PrefDomain.LINUX,
            kind = PrefKind.ENUM,
            target = PrefTarget.LinuxDistribution,
            description = "当前生效的 Linux 发行版（terminal 在 environment=linux 时使用），由终端页的发行版选择写入。",
            options = listOf("alpine", "debian"),
        ),
    ) + linuxBackendSpecs()

    /**
     * 每个发行版一个后端键，直接由 [LinuxDistribution] 生成。
     *
     * 这样新增发行版时后端键必然跟着长出来，不会出现"发行版能选、后端却没法用工具设"的缺口；
     * 单测再钉一次"每个发行版都有且只有一个后端键"的不变量。
     */
    private fun linuxBackendSpecs(): List<PrefSpec> = LinuxDistribution.entries.map { distribution ->
        PrefSpec(
            key = "linux.backend_${distribution.wireName}",
            domain = PrefDomain.LINUX,
            kind = PrefKind.ENUM,
            target = PrefTarget.LinuxBackend(distribution.wireName),
            description = "${distribution.name} 使用 chroot 还是 proot 执行；切到 chroot 需要 Root 授权（与终端页门槛一致）。",
            options = listOf(AgentSelfPrefsRules.CHROOT_BACKEND, AgentSelfPrefsRules.PROOT_BACKEND),
        )
    }

    val byKey: Map<String, PrefSpec> = all.associateBy { it.key }

    init {
        val duplicates = all.groupBy { it.key }.filterValues { it.size > 1 }.keys
        check(duplicates.isEmpty()) { "重复登记的配置键：$duplicates" }
        val badTargets = all.filter { spec ->
            val credentialTarget = spec.target is PrefTarget.WebCredential ||
                spec.target is PrefTarget.SpeechCredential
            // 凭据只允许放进加密仓库，明文字段也不允许冒用 SECRET。
            credentialTarget != spec.secret
        }.map { it.key }
        check(badTargets.isEmpty()) { "凭据类型与存储位置的登记不一致：$badTargets" }
        val backendDistributions = all.mapNotNull { (it.target as? PrefTarget.LinuxBackend)?.distribution }
        val missingBackends = LinuxDistribution.entries.map { it.wireName } - backendDistributions.toSet()
        check(missingBackends.isEmpty()) { "以下发行版缺少后端键：$missingBackends" }
        val duplicatedBackends = backendDistributions.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        check(duplicatedBackends.isEmpty()) { "以下发行版登记了重复的后端键：$duplicatedBackends" }
    }

    fun find(key: String?): PrefSpec? = byKey[key.orEmpty().trim()]

    fun byDomain(domain: PrefDomain): List<PrefSpec> = all.filter { it.domain == domain }
}
