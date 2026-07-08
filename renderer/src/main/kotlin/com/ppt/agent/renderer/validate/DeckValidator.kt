package com.ppt.agent.renderer.validate

import com.ppt.agent.renderer.model.DeckDocument

/**
 * Pure structural validation of a parsed [DeckDocument] before rendering. No
 * I/O, no POI. Collects **all** violations so the CLI/tool can report them at
 * once. Empty result means the deck is renderable.
 */
class DeckValidator {

    fun validate(deck: DeckDocument): List<String> {
        val v = mutableListOf<String>()

        val meta = deck.meta
        val slides = deck.slides

        if (meta == null) {
            v += "missing 'meta'"
        }
        if (slides == null) {
            v += "missing 'slides'"
        }
        // Structure missing → nothing more to check meaningfully.
        if (meta == null || slides == null) return v

        if (meta.topic.isNullOrBlank()) {
            v += "meta.topic must not be blank"
        }

        val declared = meta.slideCount
        if (declared == null) {
            v += "meta.slideCount is required"
        } else if (slides.size != declared) {
            v += "slides.size (${slides.size}) must equal meta.slideCount ($declared)"
        }

        // Indices must be exactly 1..N, unique and contiguous.
        val n = slides.size
        val indices = slides.mapNotNull { it.index }
        if (indices.size != slides.size) {
            v += "every slide must have an integer 'index'"
        }
        val distinct = indices.toSet()
        if (distinct.size != indices.size) {
            v += "slide index values must be unique (found duplicates in ${indices.sorted()})"
        }
        if (distinct != (1..n).toSet()) {
            v += "slide index values must be exactly 1..$n with no gaps (found ${distinct.sorted()})"
        }

        // Each slide title must be non-blank.
        slides.forEach { slide ->
            if (slide.title.isNullOrBlank()) {
                val where = slide.index?.let { "slide $it" } ?: "a slide"
                v += "$where title must not be blank"
            }
        }

        return v
    }
}
