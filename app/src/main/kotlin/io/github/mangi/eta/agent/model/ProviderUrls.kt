package io.github.mangi.eta.agent.model

internal object ProviderUrls {
    fun normalizeBaseUrl(baseUrl: String): String =
        baseUrl.trim().trimEnd('/')

    fun openAiChatCompletionsUrl(baseUrl: String): String =
        appendPath(baseUrl, "chat/completions")

    fun openAiResponsesUrl(baseUrl: String): String =
        appendPath(baseUrl, "responses")

    fun openAiModelsUrl(baseUrl: String): String =
        appendPath(baseUrl, "models")

    /** 语义检索用的 OpenAI 兼容向量接口。 */
    fun openAiEmbeddingsUrl(baseUrl: String): String =
        appendPath(baseUrl, "embeddings")

    fun anthropicMessagesUrl(baseUrl: String): String =
        appendPath(baseUrl, "v1/messages")

    fun anthropicModelsUrl(baseUrl: String): String =
        appendPath(baseUrl, "v1/models")

    fun geminiInteractionsUrl(baseUrl: String): String =
        appendPath(baseUrl, "interactions")

    fun geminiModelsUrl(baseUrl: String): String =
        appendPath(baseUrl, "models")

    private fun appendPath(baseUrl: String, path: String): String =
        "${normalizeBaseUrl(baseUrl)}/${path.trimStart('/')}"
}
