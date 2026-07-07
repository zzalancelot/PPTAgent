package com.ppt.agent.gateway.server

import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import org.springframework.ai.chat.prompt.Prompt
import reactor.core.publisher.Flux
import java.util.concurrent.atomic.AtomicInteger

/** A deterministic [ChatModel] test double. Counts invocations. */
class FakeChatModel(
    private val behavior: (Prompt) -> ChatResponse,
) : ChatModel {

    val callCount = AtomicInteger(0)

    override fun call(prompt: Prompt): ChatResponse {
        callCount.incrementAndGet()
        return behavior(prompt)
    }

    override fun stream(prompt: Prompt): Flux<ChatResponse> {
        callCount.incrementAndGet()
        return Flux.just(behavior(prompt))
    }

    companion object {
        fun replying(text: String): FakeChatModel =
            FakeChatModel { ChatResponse(listOf(Generation(AssistantMessage(text)))) }

        fun failing(error: () -> Throwable): FakeChatModel =
            FakeChatModel { throw error() }
    }
}
