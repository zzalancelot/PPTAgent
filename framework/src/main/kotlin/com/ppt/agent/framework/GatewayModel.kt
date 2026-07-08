package com.ppt.agent.framework

import reactor.core.publisher.Flux

/**
 * The set of models the gateway can route to. Callers pick the target model with
 * this enum; the concrete provider endpoint + model id are resolved server-side.
 *
 * [id] is the stable wire identifier sent over gRPC (matches a `gateway.models`
 * key in the gateway-server configuration).
 */
enum class GatewayModel(val id: String) {
    /** Legacy alias; routes to `deepseek-chat` in gateway-server until fully migrated. */
    DEEPSEEK("deepseek"),
    /** DeepSeek-V4-Pro — stronger model for outline / planning. */
    DEEPSEEK_PRO("deepseek-pro"),
    /** DeepSeek-V4-Flash — cost-efficient model for per-slide content. */
    DEEPSEEK_FLASH("deepseek-flash"),
    MIMO("mimo"),
    MINIMAX("minimax");

    companion object {
        fun fromId(id: String): GatewayModel =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) || it.name.equals(id, ignoreCase = true) }
                ?: throw IllegalArgumentException("Unknown gateway model: '$id'")
    }
}

/** Enum-typed convenience over [ModelClient.chat]. */
fun ModelClient.chat(
    messages: List<ChatMessage>,
    tools: List<Tool>,
    model: GatewayModel,
    paramOverrides: Map<String, String> = emptyMap(),
): ModelResponse = chat(messages, tools, model.id, paramOverrides)

/** Enum-typed convenience over [StreamingModelClient.chatStream]. */
fun StreamingModelClient.chatStream(
    messages: List<ChatMessage>,
    tools: List<Tool>,
    model: GatewayModel,
): Flux<ModelStreamEvent> = chatStream(messages, tools, model.id)
