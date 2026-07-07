package com.ppt.agent.framework

/** Events emitted while streaming a single model turn. */
sealed class ModelStreamEvent {
    data class TextDelta(val content: String) : ModelStreamEvent()
    data class ToolCallRequest(val call: ToolCall) : ModelStreamEvent()
    data class Done(val fullText: String?, val toolCalls: List<ToolCall> = emptyList()) : ModelStreamEvent()
    data class Failed(val message: String, val code: String? = null) : ModelStreamEvent()
}
