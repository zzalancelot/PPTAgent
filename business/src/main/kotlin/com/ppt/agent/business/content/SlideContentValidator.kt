package com.ppt.agent.business.content

import com.ppt.agent.business.outline.OutlineJson
import com.ppt.agent.business.outline.OutlineSlide
import com.ppt.agent.business.outline.SlideTypes

/**
 * Bullet-count / field-length ranges for the density upgrade (richer per-slide
 * copy). See `docs/SLIDE_CONTENT_DENSITY_SPEC.md`.
 */
object ContentDensityRules {
    const val CONTENT_BULLETS_MIN = 4
    const val CONTENT_BULLETS_MAX = 6
    const val AGENDA_BULLETS_MIN = 5
    const val AGENDA_BULLETS_MAX = 7
    const val SUMMARY_BULLETS_MIN = 5
    const val SUMMARY_BULLETS_MAX = 6
    const val CODE_BULLETS_MIN = 3
    const val CODE_BULLETS_MAX = 5
    const val SPEAKER_NOTES_MIN_CHARS = 80 // ~4 short Chinese sentences
    const val SUBTITLE_MIN_CHARS = 8
}

/**
 * Pure-Kotlin validation of generated slide content. No LLM, no I/O. Per-slide
 * checks feed the retry loop; deck-level checks run once all slides complete.
 * Collects **all** violations (does not fail fast).
 */
class SlideContentValidator {

    /** Slide types whose bullets may be empty (e.g. a bare title or divider). */
    val bulletsOptionalTypes: Set<String> =
        setOf(SlideTypes.TITLE, SlideTypes.SECTION_DIVIDER, SlideTypes.QA)

    /** Slide types with an explicit rich-bullet range (excludes `code_or_demo`, `agenda`, `summary` — each has its own range). */
    val bulletHeavyTypes: Set<String> = RICH_CONTENT_TYPES

    /** Slide types for which speaker notes are strongly recommended. */
    private val notesRecommendedTypes: Set<String> = SPEAKER_NOTES_REQUIRED_TYPES

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
            in RICH_CONTENT_TYPES -> {
                val range = ContentDensityRules.CONTENT_BULLETS_MIN..ContentDensityRules.CONTENT_BULLETS_MAX
                if (bullets !in range) {
                    v += "slide ${outlineSlide.index} (${content.slideType}) must have " +
                        "${range.first}-${range.last} bullets (found $bullets)"
                }
            }
            SlideTypes.CODE_OR_DEMO -> {
                val range = ContentDensityRules.CODE_BULLETS_MIN..ContentDensityRules.CODE_BULLETS_MAX
                if (bullets !in range) {
                    v += "slide ${outlineSlide.index} (${content.slideType}) must have " +
                        "${range.first}-${range.last} bullets (found $bullets)"
                }
            }
            SlideTypes.AGENDA -> {
                val range = ContentDensityRules.AGENDA_BULLETS_MIN..ContentDensityRules.AGENDA_BULLETS_MAX
                if (bullets !in range) {
                    v += "slide ${outlineSlide.index} (agenda) must have ${range.first}-${range.last} bullets (found $bullets)"
                }
            }
            SlideTypes.SUMMARY -> {
                val range = ContentDensityRules.SUMMARY_BULLETS_MIN..ContentDensityRules.SUMMARY_BULLETS_MAX
                if (bullets !in range) {
                    v += "slide ${outlineSlide.index} (summary) must have ${range.first}-${range.last} bullets (found $bullets)"
                }
            }
            in bulletsOptionalTypes -> if (bullets > MAX_BULLETS) {
                v += "slide ${outlineSlide.index} (${content.slideType}) has too many bullets ($bullets > $MAX_BULLETS)"
            }
            else -> if (bullets > MAX_BULLETS) {
                v += "slide ${outlineSlide.index} (${content.slideType}) has too many bullets ($bullets > $MAX_BULLETS)"
            }
        }

        if (content.slideType in SUBTITLE_REQUIRED_TYPES) {
            val subtitle = content.subtitle?.trim()
            if (subtitle.isNullOrBlank() || subtitle.length < ContentDensityRules.SUBTITLE_MIN_CHARS) {
                v += "slide ${outlineSlide.index} (${content.slideType}) requires a subtitle of at least " +
                    "${ContentDensityRules.SUBTITLE_MIN_CHARS} characters"
            }
        }

        if (content.slideType in SPEAKER_NOTES_REQUIRED_TYPES) {
            val notes = content.speakerNotes?.trim()
            if (notes.isNullOrBlank() || notes.length < ContentDensityRules.SPEAKER_NOTES_MIN_CHARS) {
                v += "slide ${outlineSlide.index} (${content.slideType}) requires speakerNotes of at least " +
                    "${ContentDensityRules.SPEAKER_NOTES_MIN_CHARS} characters"
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
        /** Fallback cap for slide types without an explicit range (was 6; raised so it never undercuts agenda's 7). */
        const val MAX_BULLETS = 8

        /** content, comparison, timeline, framework, case_study — NOT code_or_demo (its own 3–5 range). */
        val RICH_CONTENT_TYPES: Set<String> = setOf(
            SlideTypes.CONTENT,
            SlideTypes.COMPARISON,
            SlideTypes.TIMELINE,
            SlideTypes.FRAMEWORK,
            SlideTypes.CASE_STUDY,
        )

        val SUBTITLE_REQUIRED_TYPES: Set<String> = RICH_CONTENT_TYPES

        val SPEAKER_NOTES_REQUIRED_TYPES: Set<String> = RICH_CONTENT_TYPES +
            setOf(SlideTypes.SUMMARY, SlideTypes.CALL_TO_ACTION, SlideTypes.CODE_OR_DEMO)
    }
}
