package com.ppt.agent.gateway.server

import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.openai.OpenAiChatModel
import org.springframework.ai.openai.OpenAiChatOptions

/**
 * Builds an OpenAI-compatible [ChatModel] from a [GatewayCapabilitiesProperties.ModelEntry].
 * DeepSeek, MiMo and MiniMax all expose OpenAI-compatible chat-completions
 * endpoints, so a single builder covers every provider — only `base-url`,
 * `api-key` and `model` differ. In Spring AI 2.0 the connection details live on
 * [OpenAiChatOptions] itself.
 */
object ChatModelFactory {

    fun create(entry: GatewayCapabilitiesProperties.ModelEntry): ChatModel {
        val options = OpenAiChatOptions.builder()
        options.baseUrl(entry.baseUrl)
        // A blank key still yields a usable bean; live calls will fail (and be
        // reported DOWN by the health probe) until a real key is configured.
        options.apiKey(entry.apiKey.ifBlank { "not-configured" })
        options.model(entry.model)
        entry.params["temperature"]?.toDoubleOrNull()?.let { options.temperature(it) }
        entry.params["max_tokens"]?.toIntOrNull()?.let { options.maxTokens(it) }
        entry.params["top_p"]?.toDoubleOrNull()?.let { options.topP(it) }

        return OpenAiChatModel.builder()
            .options(options.build())
            .build()
    }
}
