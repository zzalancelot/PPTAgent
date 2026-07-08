package com.ppt.agent.business

import com.ppt.agent.business.content.ContentResult
import com.ppt.agent.business.content.ModelPool
import com.ppt.agent.business.content.SlideContentGenerator
import com.ppt.agent.business.input.ParseResult
import com.ppt.agent.business.input.PptInput
import com.ppt.agent.business.input.PptInputParser
import com.ppt.agent.business.outline.OutlineJson
import com.ppt.agent.business.outline.OutlinePlanner
import com.ppt.agent.business.outline.OutlineResult
import com.ppt.agent.business.theme.ThemeColorPicker
import com.ppt.agent.business.theme.ThemeColorResult
import com.ppt.agent.framework.ChatMessage
import com.ppt.agent.framework.GatewayModel
import com.ppt.agent.llm.adapter.LlmAdapter

/**
 * Stub implementation proving the business -> llm-adapter -> gateway chain
 * works end to end: sends one `"ping"` user message and returns the response
 * text (or an error message), never touching `gateway-client` directly.
 *
 * [parseInput] delegates straight to [PptInputParser] — the first real step
 * of the business pipeline, ahead of outline/LLM work.
 */
class PptGenerationServiceImpl(
    private val llmAdapter: LlmAdapter,
    private val inputParser: PptInputParser,
    private val outlinePlanner: OutlinePlanner,
    private val slideContentGenerator: SlideContentGenerator,
    private val themeColorPicker: ThemeColorPicker,
) : PptGenerationService {

    override fun pingLlm(model: GatewayModel): String =
        try {
            val response = llmAdapter.chat(listOf(ChatMessage.User("ping")), emptyList(), model)
            response.text ?: "(empty response)"
        } catch (e: Exception) {
            "error: ${e.message}"
        }

    override fun parseInput(json: String): ParseResult = inputParser.parse(json)

    override fun planOutline(input: PptInput, model: GatewayModel): OutlineResult =
        outlinePlanner.plan(input, model)

    override fun generateContent(
        input: PptInput,
        outline: OutlineJson,
        modelPool: List<GatewayModel>,
    ): ContentResult = slideContentGenerator.generate(input, outline, modelPool)

    override fun pickThemeColors(outline: OutlineJson, model: GatewayModel): ThemeColorResult =
        themeColorPicker.pick(outline, model)
}
