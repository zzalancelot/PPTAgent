package com.ppt.agent.gateway.server

import com.ppt.agent.config.SpringAiModelClient
import com.ppt.agent.framework.ChatMessage
import com.ppt.agent.framework.ModelResponse
import com.ppt.agent.framework.ModelStreamEvent
import com.ppt.agent.framework.Tool
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.ai.chat.model.ChatModel
import reactor.core.publisher.Flux

/**
 * Routes a resolved [CapabilitySpec] to the concrete provider [ChatModel].
 * Unary turns go through [SpringAiModelClient]; streaming uses
 * `ChatModel.stream()` directly. Emits `gateway.provider.*` metrics.
 */
class ProviderChatModels(
    private val clients: Map<String, SpringAiModelClient>,
    private val chatModels: Map<String, ChatModel>,
    private val meterRegistry: MeterRegistry,
) {
    fun chat(spec: CapabilitySpec, messages: List<ChatMessage>, tools: List<Tool>): ModelResponse {
        val client = clientFor(spec.provider)
        val sample = Timer.start(meterRegistry)
        return try {
            val response = client.chat(messages, tools, spec.model, spec.params)
            record(spec, "success", sample)
            response
        } catch (e: Exception) {
            record(spec, if (RateLimit.is429(e)) "rate_limited" else "error", sample)
            throw e
        }
    }

    fun stream(spec: CapabilitySpec, messages: List<ChatMessage>, tools: List<Tool>): Flux<ModelStreamEvent> {
        val client = clientFor(spec.provider)
        val chatModel = chatModelFor(spec.provider)
        val prompt = client.buildPrompt(messages, tools, spec.model, spec.params)
        val buffer = StringBuilder()
        val sample = Timer.start(meterRegistry)
        return chatModel.stream(prompt)
            .concatMapIterable { chunk -> client.mapStreamChunk(chunk, buffer) }
            .concatWith(Flux.defer { Flux.just<ModelStreamEvent>(ModelStreamEvent.Done(buffer.toString())) })
            .doOnComplete { record(spec, "success", sample) }
            .onErrorResume { e ->
                record(spec, if (RateLimit.is429(e)) "rate_limited" else "error", sample)
                Flux.just(ModelStreamEvent.Failed(e.message ?: "stream failed", RateLimit.codeOf(e)))
            }
    }

    private fun clientFor(provider: String): SpringAiModelClient =
        clients[provider] ?: throw IllegalArgumentException("No chat client configured for provider '$provider'")

    private fun chatModelFor(provider: String): ChatModel =
        chatModels[provider] ?: throw IllegalArgumentException("No chat model configured for provider '$provider'")

    private fun record(spec: CapabilitySpec, outcome: String, sample: Timer.Sample) {
        meterRegistry.counter(
            "gateway.provider.requests",
            "provider", spec.provider,
            "capability", spec.capability,
            "outcome", outcome,
        ).increment()
        sample.stop(
            Timer.builder("gateway.provider.latency")
                .tag("provider", spec.provider)
                .tag("capability", spec.capability)
                .tag("outcome", outcome)
                .register(meterRegistry),
        )
    }
}
