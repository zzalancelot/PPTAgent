package com.ppt.agent.framework

import reactor.core.publisher.Flux

/**
 * Synchronous, single-turn model client. Upper layers depend only on this
 * interface and pass a **capability alias** (e.g. `fast-chat`) as [model] —
 * never a concrete provider model id.
 */
interface ModelClient {
    /**
     * [paramOverrides] is blind transport plumbing forwarded to the gateway
     * (e.g. `max_tokens`). Upper layers own any retry/escalation decisions; this
     * client just forwards the map as-is over the wire.
     */
    fun chat(
        messages: List<ChatMessage>,
        tools: List<Tool>,
        model: String,
        paramOverrides: Map<String, String> = emptyMap(),
    ): ModelResponse
}

/**
 * Streaming, single-turn model client. [capability] is a capability alias.
 */
interface StreamingModelClient {
    fun chatStream(messages: List<ChatMessage>, tools: List<Tool>, capability: String): Flux<ModelStreamEvent>
}
