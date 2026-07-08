package com.ppt.agent.gateway.server.http

import com.ppt.agent.framework.ChatMessage
import com.ppt.agent.framework.ModelStreamEvent
import com.ppt.agent.gateway.server.CapabilityRegistry
import com.ppt.agent.gateway.server.ProviderChatModels
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.codec.ServerSentEvent
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Flux

/**
 * Convenience HTTP facade over the gateway, served on `server.port` (9091)
 * alongside the ops endpoints. This is meant for manual verification; the
 * primary contract remains gRPC on the configured gRPC port.
 *
 * The `model` field/param maps to a [com.ppt.agent.framework.GatewayModel] id
 * (`deepseek` / `mimo` / `minimax`); when blank the registry default is used.
 */
@RestController
class ChatHttpController(
    private val registry: CapabilityRegistry,
    private val providers: ProviderChatModels,
) {

    /** Full (non-streaming) chat. Accepts either `prompt` or a `messages` list. */
    @PostMapping("/v1/chat")
    fun chat(@RequestBody request: ChatHttpRequest): Map<String, Any?> {
        val spec = registry.resolve(request.model)
        val response = providers.chat(spec, request.toMessages(), emptyList())
        return mapOf(
            "resolved" to mapOf(
                "capability" to spec.capability,
                "provider" to spec.provider,
                "model" to spec.model,
            ),
            "text" to response.text,
            "toolCalls" to response.toolCalls.map {
                mapOf("id" to it.id, "name" to it.name, "argsJson" to it.argsJson)
            },
        )
    }

    /** Browser-friendly variant: `GET /v1/chat?prompt=hi&model=deepseek`. */
    @GetMapping("/v1/chat")
    fun chatGet(
        @RequestParam prompt: String,
        @RequestParam(required = false) model: String?,
    ): Map<String, Any?> = chat(ChatHttpRequest().apply { this.model = model; this.prompt = prompt })

    /** Streaming chat as Server-Sent Events. Emits `delta`, then a terminal `done`/`error`. */
    @GetMapping("/v1/chat/stream", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun streamGet(
        @RequestParam prompt: String,
        @RequestParam(required = false) model: String?,
    ): Flux<ServerSentEvent<String>> =
        stream(ChatHttpRequest().apply { this.model = model; this.prompt = prompt })

    /** Streaming chat as Server-Sent Events (POST + JSON body). */
    @PostMapping("/v1/chat/stream", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun stream(@RequestBody request: ChatHttpRequest): Flux<ServerSentEvent<String>> {
        val spec = registry.resolve(request.model)
        return providers.stream(spec, request.toMessages(), emptyList()).map { event ->
            when (event) {
                is ModelStreamEvent.TextDelta ->
                    ServerSentEvent.builder(event.content).event("delta").build()
                is ModelStreamEvent.ToolCallRequest ->
                    ServerSentEvent.builder(event.call.argsJson).event("tool_call").build()
                is ModelStreamEvent.Done ->
                    ServerSentEvent.builder(event.fullText ?: "").event("done").build()
                is ModelStreamEvent.Failed ->
                    ServerSentEvent.builder(event.message).event("error").build()
            }
        }
    }

    @ExceptionHandler(CapabilityRegistry.UnknownCapabilityException::class)
    fun onUnknownCapability(e: CapabilityRegistry.UnknownCapabilityException): ResponseEntity<Map<String, Any?>> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
            mapOf("error" to "unknown_model", "message" to e.message, "known" to registry.aliases()),
        )

    @ExceptionHandler(IllegalArgumentException::class)
    fun onBadRequest(e: IllegalArgumentException): ResponseEntity<Map<String, Any?>> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST).body(mapOf("error" to "bad_request", "message" to e.message))
}

/** Mutable DTO so Jackson can bind without the Kotlin module. */
class ChatHttpRequest {
    var model: String? = null
    var prompt: String? = null
    var messages: List<ChatMessageDto>? = null

    fun toMessages(): List<ChatMessage> {
        val fromList = messages
        return when {
            !fromList.isNullOrEmpty() -> fromList.map { it.toMessage() }
            !prompt.isNullOrBlank() -> listOf(ChatMessage.User(prompt!!))
            else -> throw IllegalArgumentException("Provide either 'prompt' or a non-empty 'messages' list.")
        }
    }
}

class ChatMessageDto {
    var role: String? = null
    var text: String? = null

    fun toMessage(): ChatMessage {
        val content = text ?: ""
        return when (role?.lowercase()) {
            "system" -> ChatMessage.System(content)
            "assistant" -> ChatMessage.Assistant(content, emptyList())
            else -> ChatMessage.User(content)
        }
    }
}
