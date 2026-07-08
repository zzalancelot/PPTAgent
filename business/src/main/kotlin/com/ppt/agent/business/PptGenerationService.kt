package com.ppt.agent.business

import com.ppt.agent.business.content.ContentResult
import com.ppt.agent.business.content.ModelPool
import com.ppt.agent.business.input.ParseResult
import com.ppt.agent.business.input.PptInput
import com.ppt.agent.business.outline.OutlineJson
import com.ppt.agent.business.outline.OutlineResult
import com.ppt.agent.framework.GatewayModel

/**
 * PPT domain entry point. Covers input parsing and outline planning today; no
 * slide content or `.pptx` rendering. See BUSINESS_ADAPTER_SPEC.md non-goals.
 */
interface PptGenerationService {
    /** Placeholder entry point for future JSON-in -> pptx-out pipeline. */
    fun pingLlm(model: GatewayModel): String

    /** Parses + validates the PPT generation input JSON. See INPUT_PARSER_SPEC.md. */
    fun parseInput(json: String): ParseResult

    /** Plans a structured slide-deck outline for a validated [input]. See OUTLINE_AGENT_SPEC.md. */
    fun planOutline(input: PptInput, model: GatewayModel = GatewayModel.DEEPSEEK): OutlineResult

    /** Expands an [outline] into final on-slide copy. See SLIDE_CONTENT_SPEC.md. */
    fun generateContent(
        input: PptInput,
        outline: OutlineJson,
        modelPool: List<GatewayModel> = ModelPool.DEFAULT,
    ): ContentResult
}
