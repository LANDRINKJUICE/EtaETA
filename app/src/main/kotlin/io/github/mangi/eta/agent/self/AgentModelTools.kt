package io.github.mangi.eta.agent.self

import io.github.mangi.eta.data.model.AnthropicProviderSetting
import io.github.mangi.eta.data.model.CustomProviderSetting
import io.github.mangi.eta.data.model.GeminiProviderSetting
import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.OpenAiCompatibleProviderSetting
import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.data.model.runtimeProviderType
import io.github.mangi.eta.data.repository.ModelRepository
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/** Agent 对模型提供商与模型的直接管理；凭据只写不回显，所有写操作读回验证。 */
internal object AgentModelTools {

    val TOOL_NAMES = setOf(
        "eta_provider_list",
        "eta_provider_upsert",
        "eta_provider_delete",
        "eta_model_list",
        "eta_model_upsert",
        "eta_model_delete",
        "eta_model_select",
    )

    fun execute(name: String, args: JSONObject): String = try {
        when (name) {
            "eta_provider_list" -> providerList()
            "eta_provider_upsert" -> providerUpsert(args)
            "eta_provider_delete" -> providerDelete(args)
            "eta_model_list" -> modelList(args)
            "eta_model_upsert" -> modelUpsert(args)
            "eta_model_delete" -> modelDelete(args)
            "eta_model_select" -> modelSelect(args)
            else -> error("未知模型管理工具：$name")
        }.toString()
    } catch (failure: Exception) {
        if (failure is InterruptedException || failure is kotlinx.coroutines.CancellationException) throw failure
        JSONObject()
            .put("ok", false)
            .put("code", "MODEL_TOOL_ERROR")
            .put("message", failure.message ?: failure.javaClass.simpleName)
            .toString()
    }

    private fun providerJson(provider: ProviderSetting): JSONObject = JSONObject()
        .put("id", provider.id)
        .put("name", provider.name)
        .put("type", provider.runtimeProviderType)
        .put("base_url", provider.baseUrl)
        .put("enabled", provider.isEnabled)
        .put("auth_mode", provider.authMode)
        .put("has_api_key", provider.apiKey.isNotBlank() || io.github.mangi.eta.data.model.ProviderAuthModes.isOAuth(provider.authMode))
        .put("model_count", provider.models.size)

    private fun modelJson(model: Model): JSONObject = JSONObject()
        .put("id", model.id)
        .put("model_id", model.modelId)
        .put("display_name", model.displayName)
        .put("enabled", model.isEnabled)
        .put("context_window_override", model.contextWindowOverride ?: JSONObject.NULL)
        .put("supports_vision", model.supportsVision)
        .put("supports_tools", model.supportsTools)
        .put("supports_reasoning", model.supportsReasoning)

    private fun providerList(): JSONObject {
        // 用 repairSelection 后的生效值，避免首次启动或选择失效时返回 null。
        val settings = runBlocking { ProviderRepository.repairSelection() }
        val providers = runBlocking { ProviderRepository.allProviders() }
        return JSONObject()
            .put("ok", true)
            .put("selected_provider_id", settings.selectedProviderId ?: JSONObject.NULL)
            .put("selected_model_id", settings.selectedModelId ?: JSONObject.NULL)
            .put(
                "providers",
                JSONArray().apply { providers.forEach { put(providerJson(it)) } },
            )
    }

    private fun providerUpsert(args: JSONObject): JSONObject {
        val id = args.optString("id").trim()
        val existing = if (id.isNotEmpty()) {
            runBlocking { ProviderRepository.providerById(id) } ?: error("Provider 不存在：$id")
        } else {
            null
        }
        val name = args.optString("name").trim().ifEmpty { existing?.name.orEmpty() }
        val baseUrl = args.optString("base_url").trim().ifEmpty { existing?.baseUrl.orEmpty() }
        require(name.isNotEmpty()) { "新增提供商时 name 必填" }
        require(baseUrl.startsWith("https://") || baseUrl.startsWith("http://")) {
            "base_url 必须是 http(s) 地址"
        }
        val apiKey = if (args.has("api_key")) args.optString("api_key", "") else existing?.apiKey.orEmpty()
        val enabled = if (args.has("enabled")) args.getBoolean("enabled") else existing?.isEnabled ?: true
        val type = args.optString("type").trim()
            .ifEmpty { existing?.runtimeProviderType ?: "openai_compatible" }
        require(type in setOf("openai_compatible", "anthropic", "gemini")) {
            "type 仅支持 openai_compatible / anthropic / gemini"
        }
        if (existing != null) {
            require(type == existing.runtimeProviderType) { "不支持通过此工具改变提供商类型" }
        }
        // 内置提供商禁止改 base_url，避免真实 API Key 被发往被替换的域名；改 key/名称/启停允许。
        if (existing != null && existing.isBuiltIn && baseUrl != existing.baseUrl) {
            return JSONObject()
                .put("ok", false)
                .put("code", "BUILTIN_BASE_URL_LOCKED")
                .put("message", "内置提供商不允许修改 base_url；如需自定义端点请新建提供商")
        }
        val saved = runBlocking {
            if (existing == null) {
                val newId = ProviderRepository.newId()
                val created = when (type) {
                    "anthropic" -> AnthropicProviderSetting(newId, name, baseUrl, apiKey = apiKey)
                    "gemini" -> GeminiProviderSetting(newId, name, baseUrl, apiKey = apiKey)
                    else -> OpenAiCompatibleProviderSetting(newId, name, baseUrl, apiKey = apiKey)
                }
                ProviderRepository.addProvider(created)
            } else {
                val updated = when (existing) {
                    is OpenAiCompatibleProviderSetting -> existing.copy(
                        name = name, baseUrl = baseUrl, apiKey = apiKey, isEnabled = enabled,
                    )
                    is AnthropicProviderSetting -> existing.copy(
                        name = name, baseUrl = baseUrl, apiKey = apiKey, isEnabled = enabled,
                    )
                    is GeminiProviderSetting -> existing.copy(
                        name = name, baseUrl = baseUrl, apiKey = apiKey, isEnabled = enabled,
                    )
                    is CustomProviderSetting -> existing.copy(
                        name = name, baseUrl = baseUrl, apiKey = apiKey, isEnabled = enabled,
                    )
                }
                ProviderRepository.updateProvider(updated)
                ProviderRepository.providerById(existing.id)
            }
        }
        return JSONObject()
            .put("ok", true)
            .put("provider", saved?.let(::providerJson) ?: JSONObject.NULL)
    }

    private fun providerDelete(args: JSONObject): JSONObject {
        val id = args.getString("id")
        val existing = runBlocking { ProviderRepository.providerById(id) }
        if (existing == null || existing.isBuiltIn) {
            return JSONObject()
                .put("ok", false)
                .put("code", if (existing == null) "NOT_FOUND" else "BUILTIN_PROVIDER")
                .put("message", if (existing == null) "Provider 不存在：$id" else "内置提供商不可删除，可在设置页重置")
        }
        runBlocking { ProviderRepository.deleteProvider(id) }
        val stillThere = runBlocking { ProviderRepository.providerById(id) } != null
        val settings = runBlocking { ProviderRepository.repairSelection() }
        return JSONObject().put("ok", !stillThere).put("deleted", if (stillThere) JSONObject.NULL else id)
            .put("selected_provider_id", settings.selectedProviderId ?: JSONObject.NULL)
            .put("selected_model_id", settings.selectedModelId ?: JSONObject.NULL)
    }

    private fun modelList(args: JSONObject): JSONObject {
        val provider = runBlocking { ProviderRepository.providerById(args.getString("provider_id")) }
            ?: error("Provider 不存在")
        return JSONObject().put("ok", true).put("provider_id", provider.id)
            .put(
                "models",
                JSONArray().apply { provider.models.forEach { put(modelJson(it)) } },
            )
    }

    private fun modelUpsert(args: JSONObject): JSONObject {
        val providerId = args.getString("provider_id")
        val provider = runBlocking { ProviderRepository.providerById(providerId) }
            ?: error("Provider 不存在：$providerId")
        val id = args.optString("id").trim()
        val modelId = args.optString("model_id").trim()
        val existing = provider.models.firstOrNull {
            (id.isNotEmpty() && it.id == id) || (id.isEmpty() && modelId.isNotEmpty() && it.modelId == modelId)
        }
        if (id.isNotEmpty() && existing == null) error("模型不存在：$id")
        require(existing != null || modelId.isNotEmpty()) { "新增模型时 model_id 必填" }
        val displayName = args.optString("display_name").trim()
            .ifEmpty { existing?.displayName ?: modelId }
        val enabled = if (args.has("enabled")) args.getBoolean("enabled") else existing?.isEnabled ?: true
        val contextWindow = if (args.has("context_window")) {
            args.getInt("context_window")
        } else {
            existing?.contextWindowOverride
        }
        val draft = (existing ?: Model(id = ModelRepository.newId(), modelId = modelId, displayName = displayName)).copy(
            modelId = modelId.ifEmpty { existing?.modelId.orEmpty() },
            displayName = displayName,
            isEnabled = enabled,
            contextWindowOverride = contextWindow,
            // Agent 宿主的模型默认允许工具调用；视觉能力保持显式声明。
            toolCall = if (args.has("tools")) args.getBoolean("tools") else (existing?.toolCall ?: true),
            attachment = if (args.has("vision")) args.getBoolean("vision") else existing?.attachment,
            reasoningOverride = if (args.has("reasoning")) args.getBoolean("reasoning") else existing?.reasoningOverride,
        )
        val saved = runBlocking { ModelRepository.saveModel(providerId, draft) }
        return JSONObject().put("ok", true).put("model", modelJson(saved))
    }

    private fun modelDelete(args: JSONObject): JSONObject {
        val providerId = args.getString("provider_id")
        val id = args.getString("id")
        val provider = runBlocking { ProviderRepository.providerById(providerId) }
            ?: error("Provider 不存在：$providerId")
        if (provider.models.none { it.id == id }) error("模型不存在：$id")
        runBlocking { ModelRepository.deleteModel(providerId, id) }
        val stillThere = runBlocking { ProviderRepository.providerById(providerId) }
            ?.models?.any { it.id == id } == true
        val settings = runBlocking { ProviderRepository.repairSelection() }
        return JSONObject().put("ok", !stillThere).put("deleted", if (stillThere) JSONObject.NULL else id)
            .put("selected_provider_id", settings.selectedProviderId ?: JSONObject.NULL)
            .put("selected_model_id", settings.selectedModelId ?: JSONObject.NULL)
    }

    private fun modelSelect(args: JSONObject): JSONObject {
        val expectedModelId: String?
        val expectedProviderId: String?
        if (args.has("id")) {
            val modelId = args.getString("id")
            val provider = runBlocking { ProviderRepository.providerByModelId(modelId) }
                ?: error("模型不存在：$modelId")
            require(provider.isEnabled) { "提供商已禁用：${provider.name}" }
            require(provider.models.any { it.id == modelId && it.isEnabled }) { "模型已禁用：$modelId" }
            runBlocking { RuntimeConfigRepository.setSelectedModelId(modelId) }
            expectedModelId = modelId
            expectedProviderId = provider.id
        } else if (args.has("provider_id")) {
            val providerId = args.getString("provider_id")
            val provider = runBlocking { ProviderRepository.providerById(providerId) }
                ?: error("Provider 不存在：$providerId")
            require(provider.isEnabled) { "提供商已禁用：${provider.name}" }
            runBlocking { RuntimeConfigRepository.setSelectedProviderId(providerId) }
            expectedModelId = null
            expectedProviderId = providerId
        } else {
            error("id（模型内部 id）或 provider_id 至少提供一个")
        }
        val settings = runBlocking { ProviderRepository.repairSelection() }
        val verified = settings.selectedProviderId == expectedProviderId &&
            (expectedModelId == null || settings.selectedModelId == expectedModelId)
        return JSONObject()
            .put("ok", verified)
            .put("verified", verified)
            .put("selected_provider_id", settings.selectedProviderId ?: JSONObject.NULL)
            .put("selected_model_id", settings.selectedModelId ?: JSONObject.NULL)
    }
}
