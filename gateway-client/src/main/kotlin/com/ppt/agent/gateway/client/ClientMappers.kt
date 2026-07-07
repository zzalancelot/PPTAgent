package com.ppt.agent.gateway.client

import com.ppt.agent.framework.ChatMessage
import com.ppt.agent.framework.Json
import com.ppt.agent.framework.ModelResponse
import com.ppt.agent.framework.ModelStreamEvent
import com.ppt.agent.framework.Tool
import com.ppt.agent.gateway.v1.ChatEvent
import com.ppt.agent.gateway.v1.Role
import com.ppt.agent.framework.ToolCall as FwToolCall
import com.ppt.agent.gateway.v1.ChatResponse as ProtoChatResponse
import com.ppt.agent.gateway.v1.Message as ProtoMessage
import com.ppt.agent.gateway.v1.ToolCall as ProtoToolCall
import com.ppt.agent.gateway.v1.ToolResultItem as ProtoToolResultItem
import com.ppt.agent.gateway.v1.ToolSpec as ProtoToolSpec

/** Maps framework types to/from the gateway proto wire types. */
internal object ClientMappers {

    fun toProtoMessage(message: ChatMessage): ProtoMessage {
        val builder = ProtoMessage.newBuilder()
        when (message) {
            is ChatMessage.System -> builder.setRole(Role.SYSTEM).setText(message.text)
            is ChatMessage.User -> builder.setRole(Role.USER).setText(message.text)
            is ChatMessage.Assistant -> {
                builder.setRole(Role.ASSISTANT)
                message.text?.let { builder.setText(it) }
                builder.addAllToolCalls(message.toolCalls.map(::toProtoToolCall))
            }
            is ChatMessage.ToolResults -> {
                builder.setRole(Role.TOOL_RESULTS)
                builder.addAllToolResults(
                    message.items.map {
                        ProtoToolResultItem.newBuilder()
                            .setId(it.id)
                            .setName(it.name)
                            .setContent(it.content)
                            .build()
                    },
                )
            }
        }
        return builder.build()
    }

    fun toProtoTool(tool: Tool): ProtoToolSpec = ProtoToolSpec.newBuilder()
        .setName(tool.name())
        .setDescription(tool.description())
        .setParametersSchemaJson(Json.toJson(tool.parametersSchema()))
        .build()

    fun toProtoToolCall(call: FwToolCall): ProtoToolCall = ProtoToolCall.newBuilder()
        .setId(call.id)
        .setName(call.name)
        .setArgsJson(call.argsJson)
        .build()

    fun toFrameworkToolCall(call: ProtoToolCall): FwToolCall =
        FwToolCall(call.id, call.name, call.argsJson)

    fun toModelResponse(response: ProtoChatResponse): ModelResponse = ModelResponse(
        text = response.text.takeIf { it.isNotEmpty() },
        toolCalls = response.toolCallsList.map(::toFrameworkToolCall),
    )

    fun toStreamEvent(event: ChatEvent): ModelStreamEvent = when (event.eventCase) {
        ChatEvent.EventCase.TEXT_DELTA -> ModelStreamEvent.TextDelta(event.textDelta.content)
        ChatEvent.EventCase.TOOL_CALL -> ModelStreamEvent.ToolCallRequest(toFrameworkToolCall(event.toolCall.call))
        ChatEvent.EventCase.DONE -> ModelStreamEvent.Done(
            fullText = event.done.fullText.takeIf { it.isNotEmpty() },
            toolCalls = event.done.toolCallsList.map(::toFrameworkToolCall),
        )
        ChatEvent.EventCase.ERROR -> ModelStreamEvent.Failed(
            message = event.error.message,
            code = event.error.code.takeIf { it.isNotEmpty() },
        )
        else -> ModelStreamEvent.Failed("empty stream event", "EMPTY_EVENT")
    }
}
