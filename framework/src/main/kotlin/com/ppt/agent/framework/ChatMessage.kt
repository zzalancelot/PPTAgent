package com.ppt.agent.framework

/** A single message in a conversation turn. */
sealed class ChatMessage {
    data class System(val text: String) : ChatMessage()
    data class User(val text: String) : ChatMessage()
    data class Assistant(val text: String?, val toolCalls: List<ToolCall>) : ChatMessage()
    data class ToolResults(val items: List<ToolResultItem>) : ChatMessage()
}

/** A tool invocation requested by the model. [argsJson] is a raw JSON string. */
data class ToolCall(val id: String, val name: String, val argsJson: String)

/** The result of executing a tool, fed back to the model. */
data class ToolResultItem(val id: String, val name: String, val content: String)

/** The result of a single non-streaming model turn. */
data class ModelResponse(val text: String?, val toolCalls: List<ToolCall>)
