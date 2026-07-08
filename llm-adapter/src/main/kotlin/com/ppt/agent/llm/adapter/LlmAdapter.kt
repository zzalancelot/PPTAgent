package com.ppt.agent.llm.adapter

import com.ppt.agent.framework.ChatMessage
import com.ppt.agent.framework.GatewayModel
import com.ppt.agent.framework.ModelResponse
import com.ppt.agent.framework.ModelStreamEvent
import com.ppt.agent.framework.Tool
import reactor.core.publisher.Flux

/**
 * Single seam for all business LLM calls. Mirrors the existing gateway turn
 * API (`ModelClient` / `StreamingModelClient`) but lives above transport, so
 * business code depends only on this module and never on `gateway-client`.
 *
 * One model turn per call; tool execution stays on the caller, same contract
 * as `ModelClient`.
 *
 * This is also the extension point for future model-capability compensation
 * (extra system prompts per [GatewayModel], multi-step fallback, structured-
 * output repair) — those land as alternative implementations of this
 * interface, not as changes to callers.
 */
interface LlmAdapter {
    /**
     * [paramOverrides] is an optional blind passthrough to the gateway (e.g.
     * `max_tokens`). Retry, token-ladder and truncation decisions belong to the
     * caller (business layer) — the adapter never inspects or mutates the map.
     */
    fun chat(
        messages: List<ChatMessage>,
        tools: List<Tool>,
        model: GatewayModel,
        paramOverrides: Map<String, String> = emptyMap(),
    ): ModelResponse

    fun chatStream(messages: List<ChatMessage>, tools: List<Tool>, model: GatewayModel): Flux<ModelStreamEvent>
}
