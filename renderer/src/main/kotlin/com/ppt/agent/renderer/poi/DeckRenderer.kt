package com.ppt.agent.renderer.poi

import com.ppt.agent.renderer.model.DeckDocument
import java.nio.file.Path

/**
 * Strategy for turning a validated [DeckDocument] into a `.pptx` file. Selected
 * by `RenderMode` in `PptRenderToolImpl`:
 *  - `PROGRAMMATIC` → [PptxWriter]
 *  - `TEMPLATE`     → [TemplatePptxWriter]
 *
 * Implementations may throw [TemplateNotFoundException] / [TemplateLayoutMissingException];
 * the tool maps those to the corresponding `RenderToolError`.
 */
internal interface DeckRenderer {
    fun write(deck: DeckDocument, output: Path)
}

/** The template file could not be located (custom path or bundled resource). */
internal class TemplateNotFoundException(val path: String) :
    RuntimeException("template not found: $path")

/** The template is missing a slide master / layout needed to render. */
internal class TemplateLayoutMissingException(val layoutKind: String) :
    RuntimeException("template layout missing: $layoutKind")
