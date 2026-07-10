package com.ppt.agent.gateway.server

import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.model.tool.ToolCallingChatOptions
import org.springframework.ai.openai.OpenAiChatModel
import org.springframework.ai.openai.OpenAiChatOptions
import java.time.Duration

/** Chat model plus per-provider request quirks for [ProviderChatModels]. */
data class ProviderModelBundle(
    val chatModel: ChatModel,
    val tokenLimitConfigurer: (ToolCallingChatOptions.Builder<*>, Int) -> Unit = { builder, limit ->
        builder.maxTokens(limit)
    },
    val optionsCustomizer: (ToolCallingChatOptions.Builder<*>) -> Unit = {},
)

/**
 * Builds an OpenAI-compatible [ChatModel] from a [GatewayCapabilitiesProperties.ModelEntry].
 *
 * Spring AI 2.0 / openai-java appends `/chat/completions` to [OpenAiChatOptions.baseUrl]
 * (not `/v1/chat/completions`). DeepSeek accepts both; MiMo requires `/v1` — set
 * `params.api_root: v1`. MiMo also expects `max_completion_tokens` and prefers
 * `thinking: disabled` so the visible `content` field is populated.
 */
object ChatModelFactory {

    private const val DEFAULT_TIMEOUT_SECONDS = 600L

    fun create(entry: GatewayCapabilitiesProperties.ModelEntry): ChatModel =
        createBundle(entry).chatModel

    fun createBundle(entry: GatewayCapabilitiesProperties.ModelEntry): ProviderModelBundle {
        val useMaxCompletionTokens = entry.params["use_max_completion_tokens"] == "true"
        val options = OpenAiChatOptions.builder()
        options.baseUrl(normalizeBaseUrl(entry))
        options.apiKey(entry.apiKey.ifBlank { "not-configured" })
        options.model(entry.model)
        entry.params["temperature"]?.toDoubleOrNull()?.let { options.temperature(it) }
        entry.params["max_tokens"]?.toIntOrNull()?.let { limit ->
            if (useMaxCompletionTokens) {
                options.maxCompletionTokens(limit)
            } else {
                options.maxTokens(limit)
            }
        }
        entry.params["top_p"]?.toDoubleOrNull()?.let { options.topP(it) }
        val timeoutSeconds = entry.params["timeout_seconds"]?.toLongOrNull() ?: DEFAULT_TIMEOUT_SECONDS
        options.timeout(Duration.ofSeconds(timeoutSeconds))
        thinkingExtraBody(entry.params["thinking"])?.let { options.extraBody(it) }

        return ProviderModelBundle(
            chatModel = OpenAiChatModel.builder().options(options.build()).build(),
            tokenLimitConfigurer = if (useMaxCompletionTokens) {
                { builder, limit ->
                    (builder as OpenAiChatOptions.Builder).maxCompletionTokens(limit)
                }
            } else {
                { builder, limit -> builder.maxTokens(limit) }
            },
            optionsCustomizer = thinkingExtraBody(entry.params["thinking"])?.let { body ->
                { builder -> (builder as OpenAiChatOptions.Builder).extraBody(body) }
            } ?: {},
        )
    }

    internal fun normalizeBaseUrl(entry: GatewayCapabilitiesProperties.ModelEntry): String {
        val base = entry.baseUrl.trimEnd('/')
        val apiRoot = entry.params["api_root"]?.trim('/') ?: return base
        return if (base.endsWith("/$apiRoot")) base else "$base/$apiRoot"
    }

    internal fun thinkingExtraBody(thinking: String?): Map<String, Any>? =
        when (thinking?.lowercase()) {
            "disabled" -> mapOf("thinking" to mapOf("type" to "disabled"))
            else -> null
        }
}
