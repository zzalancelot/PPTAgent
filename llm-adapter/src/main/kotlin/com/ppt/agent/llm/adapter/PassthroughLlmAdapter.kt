package com.ppt.agent.llm.adapter

import com.ppt.agent.framework.ChatMessage
import com.ppt.agent.framework.GatewayModel
import com.ppt.agent.framework.ModelClient
import com.ppt.agent.framework.ModelResponse
import com.ppt.agent.framework.ModelStreamEvent
import com.ppt.agent.framework.StreamingModelClient
import com.ppt.agent.framework.Tool
import com.ppt.agent.framework.chat
import com.ppt.agent.framework.chatStream
import reactor.core.publisher.Flux

/**
 * Default [LlmAdapter]: a direct delegate to [ModelClient] / [StreamingModelClient].
 *
 * No extra prompts, no retries, no message rewriting today. Keep this the
 * default; future capability-compensation variants are separate strategy
 * beans selected by config, not changes to this class.
 */
class PassthroughLlmAdapter(
    private val modelClient: ModelClient,
    private val streamingModelClient: StreamingModelClient,
) : LlmAdapter {

    override fun chat(messages: List<ChatMessage>, tools: List<Tool>, model: GatewayModel): ModelResponse =
        modelClient.chat(messages, tools, model)

    override fun chatStream(
        messages: List<ChatMessage>,
        tools: List<Tool>,
        model: GatewayModel,
    ): Flux<ModelStreamEvent> = streamingModelClient.chatStream(messages, tools, model)
}
