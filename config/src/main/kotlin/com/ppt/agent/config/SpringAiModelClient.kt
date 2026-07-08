package com.ppt.agent.config

import com.ppt.agent.framework.ChatMessage
import com.ppt.agent.framework.Json
import com.ppt.agent.framework.ModelClient
import com.ppt.agent.framework.ModelResponse
import com.ppt.agent.framework.ModelStreamEvent
import com.ppt.agent.framework.Tool
import com.ppt.agent.framework.ToolCall
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.Message
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.ToolResponseMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.model.tool.ToolCallingChatOptions
import org.springframework.ai.tool.ToolCallback
import org.springframework.ai.tool.definition.ToolDefinition

/**
 * Bridges the provider-agnostic [ModelClient] contract onto a Spring AI
 * [ChatModel]. This adapter performs exactly **one** model turn: tools are
 * forwarded as *definitions only* with internal tool execution disabled, so the
 * caller owns the tool-execution loop.
 */
class SpringAiModelClient(
    private val chatModel: ChatModel,
    /**
     * Supplies a fresh runtime-options builder per request. The concrete
     * [ChatModel] implementation dictates the required options subtype: e.g.
     * [org.springframework.ai.openai.OpenAiChatModel] casts the prompt's runtime
     * options to `OpenAiChatOptions`, so an OpenAI-backed client must supply
     * `OpenAiChatOptions.builder()` here. Defaults to the provider-neutral
     * builder for tests/fakes.
     */
    private val optionsBuilder: () -> ToolCallingChatOptions.Builder<*> = { ToolCallingChatOptions.builder() },
) : ModelClient {

    override fun chat(
        messages: List<ChatMessage>,
        tools: List<Tool>,
        model: String,
        paramOverrides: Map<String, String>,
    ): ModelResponse = mapResponse(chatModel.call(buildPrompt(messages, tools, model, paramOverrides)))

    fun buildPrompt(
        messages: List<ChatMessage>,
        tools: List<Tool>,
        model: String,
        params: Map<String, String>,
    ): Prompt = Prompt(messages.map(::toSpringMessage), buildOptions(model, params, tools))

    fun mapResponse(response: ChatResponse): ModelResponse {
        val output = response.result?.output
        return ModelResponse(
            text = output?.text?.takeIf { it.isNotEmpty() },
            toolCalls = output?.toolCalls.orEmpty().map(::toFrameworkToolCall),
        )
    }

    /**
     * Maps a single streaming chunk into framework events, appending any text
     * delta into [textBuffer] so a terminal `Done(fullText=...)` can be built.
     */
    fun mapStreamChunk(chunk: ChatResponse, textBuffer: StringBuilder): List<ModelStreamEvent> {
        val output = chunk.result?.output ?: return emptyList()
        val events = mutableListOf<ModelStreamEvent>()
        val delta = output.text
        if (!delta.isNullOrEmpty()) {
            textBuffer.append(delta)
            events += ModelStreamEvent.TextDelta(delta)
        }
        output.toolCalls.orEmpty().forEach { events += ModelStreamEvent.ToolCallRequest(toFrameworkToolCall(it)) }
        return events
    }

    private fun buildOptions(model: String, params: Map<String, String>, tools: List<Tool>): ToolCallingChatOptions {
        val builder = optionsBuilder().model(model)
        params["temperature"]?.toDoubleOrNull()?.let { builder.temperature(it) }
        params["max_tokens"]?.toIntOrNull()?.let { builder.maxTokens(it) }
        params["top_p"]?.toDoubleOrNull()?.let { builder.topP(it) }
        params["top_k"]?.toIntOrNull()?.let { builder.topK(it) }
        if (tools.isNotEmpty()) {
            builder.toolCallbacks(tools.map(::toToolCallback))
        }
        // In Spring AI 2.0 ChatModel.call()/stream() never execute tools on their
        // own (no ToolCallingManager here), so a single turn returns tool-call
        // requests verbatim for the caller to handle.
        return builder.build()
    }

    private fun toSpringMessage(message: ChatMessage): Message = when (message) {
        is ChatMessage.System -> SystemMessage(message.text)
        is ChatMessage.User -> UserMessage(message.text)
        is ChatMessage.Assistant -> AssistantMessage.builder()
            .content(message.text ?: "")
            .toolCalls(message.toolCalls.map { AssistantMessage.ToolCall(it.id, "function", it.name, it.argsJson) })
            .build()
        is ChatMessage.ToolResults -> ToolResponseMessage.builder()
            .responses(message.items.map { ToolResponseMessage.ToolResponse(it.id, it.name, it.content) })
            .build()
    }

    private fun toFrameworkToolCall(call: AssistantMessage.ToolCall): ToolCall =
        ToolCall(id = call.id(), name = call.name(), argsJson = call.arguments())

    private fun toToolCallback(tool: Tool): ToolCallback = DefinitionOnlyToolCallback(tool)

    /**
     * A [ToolCallback] that only carries a tool *definition*. It intentionally
     * refuses execution: the gateway forwards tool calls back to the caller.
     */
    private class DefinitionOnlyToolCallback(tool: Tool) : ToolCallback {
        private val definition: ToolDefinition = ToolDefinition.builder()
            .name(tool.name())
            .description(tool.description())
            .inputSchema(Json.toJson(tool.parametersSchema()))
            .build()

        override fun getToolDefinition(): ToolDefinition = definition

        override fun call(toolInput: String): String =
            throw UnsupportedOperationException("Gateway does not execute tools; the caller owns the loop.")
    }
}
