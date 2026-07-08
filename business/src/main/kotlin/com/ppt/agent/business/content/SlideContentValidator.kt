package com.ppt.agent.business.content

import com.ppt.agent.business.outline.OutlineJson
import com.ppt.agent.business.outline.OutlineSlide
import com.ppt.agent.business.outline.SlideTypes

/**
 * Pure-Kotlin validation of generated slide content. No LLM, no I/O. Per-slide
 * checks feed the retry loop; deck-level checks run once all slides complete.
 * Collects **all** violations (does not fail fast).
 */
class SlideContentValidator {

    /** Slide types whose bullets may be empty (e.g. a bare title or divider). */
    val bulletsOptionalTypes: Set<String> =
        setOf(SlideTypes.TITLE, SlideTypes.SECTION_DIVIDER, SlideTypes.QA)

    /** Slide types that must carry 2–4 bullets of on-slide copy. */
    val bulletHeavyTypes: Set<String> = SlideTypes.CONTENT_LIKE

    /** Slide types for which speaker notes are strongly recommended. */
    private val notesRecommendedTypes: Set<String> = setOf(SlideTypes.CONTENT, SlideTypes.CODE_OR_DEMO)

    /** Validates a single generated slide against its source [OutlineSlide]. */
    fun validateSlide(content: SlideContent, outlineSlide: OutlineSlide): List<String> {
        val v = mutableListOf<String>()

        if (content.index != outlineSlide.index) {
            v += "slide index (${content.index}) must match outline slide index (${outlineSlide.index})"
        }
        if (content.title.isBlank()) {
            v += "slide ${outlineSlide.index} title must not be blank"
        }

        val bullets = content.bullets.size
        when (content.slideType) {
            in bulletHeavyTypes -> if (bullets !in 2..4) {
                v += "slide ${outlineSlide.index} (${content.slideType}) must have 2-4 bullets (found $bullets)"
            }
            in bulletsOptionalTypes -> if (bullets > MAX_BULLETS) {
                v += "slide ${outlineSlide.index} (${content.slideType}) has too many bullets ($bullets > $MAX_BULLETS)"
            }
            else -> if (bullets > MAX_BULLETS) {
                v += "slide ${outlineSlide.index} (${content.slideType}) has too many bullets ($bullets > $MAX_BULLETS)"
            }
        }

        return v
    }

    /**
     * Deck-level checks, run after all slides succeed: full slide count and a
     * complete, gap-free `1..N` index set.
     */
    fun validateDeck(deck: SlideDeckContent, outline: OutlineJson): List<String> {
        val v = mutableListOf<String>()
        val expected = outline.slides.size

        if (deck.slides.size != expected) {
            v += "deck has ${deck.slides.size} slides, expected $expected"
        }
        val indices = deck.slides.map { it.index }.toSet()
        val complete = (1..expected).toSet()
        if (indices != complete) {
            val missing = complete - indices
            v += "deck indices must be exactly 1..$expected (missing: ${missing.sorted()})"
        }
        return v
    }

    /** True when [content]'s type recommends speaker notes but none were produced. */
    fun notesRecommendedButMissing(content: SlideContent): Boolean =
        content.slideType in notesRecommendedTypes && content.speakerNotes.isNullOrBlank()

    private companion object {
        const val MAX_BULLETS = 6
    }
}
