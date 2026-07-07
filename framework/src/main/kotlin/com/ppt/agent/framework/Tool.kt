package com.ppt.agent.framework

/**
 * A tool the model may call. The gateway only forwards the *definition*
 * ([name]/[description]/[parametersSchema]); [execute] is invoked by the
 * caller that owns the tool-execution loop, never by the gateway.
 */
interface Tool {
    fun name(): String
    fun description(): String
    fun parametersSchema(): Map<String, Any>
    fun execute(argsJson: String): ToolResult
}

data class ToolResult(val content: String)
