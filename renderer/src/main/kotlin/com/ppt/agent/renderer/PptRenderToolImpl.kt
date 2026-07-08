package com.ppt.agent.renderer

import com.ppt.agent.framework.Json
import com.ppt.agent.renderer.layout.SlideLayoutMapper
import com.ppt.agent.renderer.model.RootDocument
import com.ppt.agent.renderer.poi.DeckRenderer
import com.ppt.agent.renderer.poi.PptxWriter
import com.ppt.agent.renderer.poi.TemplateLayoutMissingException
import com.ppt.agent.renderer.poi.TemplateNotFoundException
import com.ppt.agent.renderer.poi.TemplatePptxWriter
import com.ppt.agent.renderer.validate.DeckValidator
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * Default [PptRenderTool]. Orchestrates: read → parse (unwrap `.content`) →
 * validate → render. The render step is dispatched by [RenderMode] to a
 * [DeckRenderer] strategy (programmatic POI draw, or template fill). Returns
 * collected errors instead of throwing.
 */
class PptRenderToolImpl(
    private val validator: DeckValidator = DeckValidator(),
) : PptRenderTool {

    private val programmaticRenderer: DeckRenderer = PptxWriter()

    override fun render(
        inputJson: Path,
        outputPptx: Path,
        mode: RenderMode,
        templatePath: Path?,
    ): RenderToolResult {
        val text = try {
            Files.readString(inputJson, StandardCharsets.UTF_8)
        } catch (e: Exception) {
            return RenderToolResult.Err(listOf(RenderToolError.IoFailure("could not read '$inputJson': ${e.message}")))
        }

        val root = try {
            Json.fromJson(text, RootDocument::class.java)
        } catch (e: Exception) {
            return RenderToolResult.Err(listOf(RenderToolError.InvalidJson(e.message ?: "invalid JSON")))
        } ?: return RenderToolResult.Err(listOf(RenderToolError.InvalidJson("input JSON was empty or null")))

        val deck = root.toDeck()

        val violations = validator.validate(deck)
        if (violations.isNotEmpty()) {
            return RenderToolResult.Err(listOf(RenderToolError.ValidationFailed(violations)))
        }

        val unsupported = deck.slides.orEmpty().mapNotNull { slide ->
            val type = slide.slideType
            if (type == null || SlideLayoutMapper.mapOrNull(type) == null) {
                RenderToolError.UnsupportedSlideType(slide.index ?: -1, type ?: "(null)")
            } else {
                null
            }
        }
        if (unsupported.isNotEmpty()) {
            return RenderToolResult.Err(unsupported)
        }

        // Fail fast with a clear error when a custom template path was given but doesn't exist.
        if (mode == RenderMode.TEMPLATE && templatePath != null && !Files.exists(templatePath)) {
            return RenderToolResult.Err(listOf(RenderToolError.TemplateNotFound(templatePath.toString())))
        }

        val renderer: DeckRenderer = when (mode) {
            RenderMode.PROGRAMMATIC -> programmaticRenderer
            RenderMode.TEMPLATE -> TemplatePptxWriter(templatePath)
        }

        return try {
            renderer.write(deck, outputPptx)
            RenderToolResult.Ok(outputPptx, deck.slides.orEmpty().size)
        } catch (e: TemplateNotFoundException) {
            RenderToolResult.Err(listOf(RenderToolError.TemplateNotFound(e.path)))
        } catch (e: TemplateLayoutMissingException) {
            RenderToolResult.Err(listOf(RenderToolError.TemplateLayoutMissing(e.layoutKind)))
        } catch (e: Exception) {
            RenderToolResult.Err(listOf(RenderToolError.IoFailure("failed to write pptx to '$outputPptx': ${e.message}")))
        }
    }
}
