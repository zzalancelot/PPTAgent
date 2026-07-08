package com.ppt.agent.renderer

import java.nio.file.Path

/**
 * Agent tool: deck JSON file → pptx file. Stateless; safe to call from a CLI,
 * tests, or a future orchestrator. No LLM, no network, no Spring.
 */
interface PptRenderTool {
    fun render(inputJson: Path, outputPptx: Path): RenderToolResult
}

sealed class RenderToolResult {
    data class Ok(
        val outputPath: Path,
        val slideCount: Int,
    ) : RenderToolResult()

    data class Err(
        val errors: List<RenderToolError>,
    ) : RenderToolResult()
}

sealed class RenderToolError {
    data class InvalidJson(val message: String) : RenderToolError()
    data class ValidationFailed(val violations: List<String>) : RenderToolError()
    data class UnsupportedSlideType(val index: Int, val slideType: String) : RenderToolError()
    data class IoFailure(val message: String) : RenderToolError()
}
