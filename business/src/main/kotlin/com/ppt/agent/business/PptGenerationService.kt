package com.ppt.agent.business

import com.ppt.agent.business.content.ContentResult
import com.ppt.agent.business.content.ModelPool
import com.ppt.agent.business.input.ParseResult
import com.ppt.agent.business.input.PptInput
import com.ppt.agent.business.outline.OutlineJson
import com.ppt.agent.business.outline.OutlineResult
import com.ppt.agent.business.scenario.DeckStance
import com.ppt.agent.business.scenario.ScenarioResult
import com.ppt.agent.business.theme.ThemeColorResult
import com.ppt.agent.framework.GatewayModel

/**
 * PPT domain entry point. Covers input parsing, outline planning, slide
 * content, and theme-color picking today; no `.pptx` rendering. See
 * BUSINESS_ADAPTER_SPEC.md non-goals.
 */
interface PptGenerationService {
    /** Placeholder entry point for future JSON-in -> pptx-out pipeline. */
    fun pingLlm(model: GatewayModel): String

    /** Parses + validates the PPT generation input JSON. See INPUT_PARSER_SPEC.md. */
    fun parseInput(json: String): ParseResult

    /** Plans a structured slide-deck outline for a validated [input]. See OUTLINE_AGENT_SPEC.md. */
    fun planOutline(input: PptInput, stance: DeckStance? = null, model: GatewayModel = GatewayModel.DEEPSEEK): OutlineResult

    /** Infers 3–5 presentation scenarios for a given input. See SCENARIO_STANCE_PROMPT.md. */
    fun inferScenarios(input: PptInput, model: GatewayModel = GatewayModel.DEEPSEEK): ScenarioResult

    /** Expands an [outline] into final on-slide copy. See SLIDE_CONTENT_SPEC.md. */
    fun generateContent(
        input: PptInput,
        outline: OutlineJson,
        stance: DeckStance? = null,
        modelPool: List<GatewayModel> = ModelPool.DEFAULT,
    ): ContentResult

    /** Picks a 5-color Morandi theme palette tailored to [outline]. See THEME_COLOR_SPEC.md. */
    fun pickThemeColors(
        outline: OutlineJson,
        stance: DeckStance? = null,
        model: GatewayModel = GatewayModel.DEEPSEEK,
    ): ThemeColorResult
}
