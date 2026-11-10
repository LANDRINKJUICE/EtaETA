package io.github.mangi.eta.agent.self

import io.github.mangi.eta.data.model.AnthropicProviderSetting
import io.github.mangi.eta.data.model.GeminiProviderSetting
import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.OpenAiCompatibleProviderSetting
import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.data.model.displayApiKeySummary
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
        .put("api_key", provider.displayApiKeySummary)
        .put(
            "models",
            JSONArray().apply {
                provider.models.forEach { model -> put(modelJson(model)) }
            },
        )

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
        val settings = runBlocking { ProviderRepository.settings() }
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
                    else -> error("该提供商类型不支持通过此工具修改")
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
        runBlocking { ProviderRepository.deleteProvider(id) }
        return JSONObject().put("ok", true).put("deleted", id)
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
            displayName = displayName,
            isEnabled = enabled,
            contextWindowOverride = contextWindow,
            attachment = if (args.has("vision")) args.getBoolean("vision") else existing?.attachment,
            toolCall = if (args.has("tools")) args.getBoolean("tools") else existing?.toolCall,
            reasoningOverride = if (args.has("reasoning")) args.getBoolean("reasoning") else existing?.reasoningOverride,
        )
        val saved = runBlocking { ModelRepository.saveModel(providerId, draft) }
        return JSONObject().put("ok", true).put("model", modelJson(saved))
    }

    private fun modelDelete(args: JSONObject): JSONObject {
        val providerId = args.getString("provider_id")
        val id = args.getString("id")
        runBlocking { ModelRepository.deleteModel(providerId, id) }
        return JSONObject().put("ok", true).put("deleted", id)
    }

    private fun modelSelect(args: JSONObject): JSONObject {
        if (args.has("id")) {
            runBlocking { RuntimeConfigRepository.setSelectedModelId(args.getString("id")) }
        } else if (args.has("provider_id")) {
            runBlocking { RuntimeConfigRepository.setSelectedProviderId(args.getString("provider_id")) }
        } else {
            error("id（模型内部 id）或 provider_id 至少提供一个")
        }
        val settings = runBlocking { ProviderRepository.settings() }
        return JSONObject()
            .put("ok", true)
            .put("selected_provider_id", settings.selectedProviderId ?: JSONObject.NULL)
            .put("selected_model_id", settings.selectedModelId ?: JSONObject.NULL)
    }
}
