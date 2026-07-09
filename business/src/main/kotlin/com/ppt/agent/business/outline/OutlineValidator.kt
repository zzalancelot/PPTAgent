package com.ppt.agent.business.outline

import com.ppt.agent.business.input.PptInput

/**
 * Pure-Kotlin structural validation of an [OutlineJson] against the source
 * [PptInput]. No LLM, no I/O. Collects **all** violations (does not fail fast)
 * so the retry loop can feed the full list back to the model.
 */
class OutlineValidator {

    /** Returns a list of human-readable violation messages; empty means valid. */
    fun validate(outline: OutlineJson, input: PptInput): List<String> {
        val violations = mutableListOf<String>()

        checkSlideCount(outline, input, violations)
        checkIndices(outline, violations)
        checkSectionRanges(outline, input, violations)
        checkRequiredTypes(outline, violations)
        checkNarrativeArc(outline, violations)
        checkBulletHints(outline, violations)
        checkConsecutiveTypes(outline, violations)
        checkKeyTerms(outline, violations)
        checkKnownSlideTypes(outline, violations)
        checkLayoutProfiles(outline, violations)

        return violations
    }

    // Rule 1: slides.size == meta.slideCount == input.slideCount
    private fun checkSlideCount(outline: OutlineJson, input: PptInput, v: MutableList<String>) {
        val actual = outline.slides.size
        if (actual != input.slideCount) {
            v += "slides.size ($actual) must equal input.slideCount (${input.slideCount})"
        }
        if (outline.meta.slideCount != input.slideCount) {
            v += "meta.slideCount (${outline.meta.slideCount}) must equal input.slideCount (${input.slideCount})"
        }
    }

    // Rule 2: index values are 1..N, unique and consecutive
    private fun checkIndices(outline: OutlineJson, v: MutableList<String>) {
        val n = outline.slides.size
        val indices = outline.slides.map { it.index }
        val distinct = indices.toSet()
        if (distinct.size != indices.size) {
            v += "slide index values must be unique (found duplicates in $indices)"
        }
        val expected = (1..n).toSet()
        if (distinct != expected) {
            v += "slide index values must be exactly 1..$n with no gaps (found ${distinct.sorted()})"
        }
    }

    // Rule 3: each section.slideRange covers slides without gaps/overlaps
    private fun checkSectionRanges(outline: OutlineJson, input: PptInput, v: MutableList<String>) {
        val n = if (outline.slides.isNotEmpty()) outline.slides.size else input.slideCount
        val covered = mutableSetOf<Int>()
        var structurallyOk = true
        for (section in outline.sections) {
            val range = section.slideRange
            if (range.size != 2) {
                v += "section '${section.id}' slideRange must be [start, end] (got $range)"
                structurallyOk = false
                continue
            }
            val (start, end) = range
            if (start > end) {
                v += "section '${section.id}' slideRange start ($start) must be <= end ($end)"
                structurallyOk = false
                continue
            }
            for (i in start..end) {
                if (!covered.add(i)) {
                    v += "section '${section.id}' slideRange overlaps slide $i"
                    structurallyOk = false
                }
            }
        }
        if (structurallyOk && covered != (1..n).toSet()) {
            v += "section slideRanges must cover slides 1..$n with no gaps (covered ${covered.sorted()})"
        }
    }

    // Rule 4: required types: title x1, agenda x1, section_divider >= 2, summary x1
    private fun checkRequiredTypes(outline: OutlineJson, v: MutableList<String>) {
        val counts = outline.slides.groupingBy { it.slideType }.eachCount()
        fun countOf(type: String) = counts[type] ?: 0
        if (countOf(SlideTypes.TITLE) != 1) v += "expected exactly 1 '${SlideTypes.TITLE}' slide (found ${countOf(SlideTypes.TITLE)})"
        if (countOf(SlideTypes.AGENDA) != 1) v += "expected exactly 1 '${SlideTypes.AGENDA}' slide (found ${countOf(SlideTypes.AGENDA)})"
        if (countOf(SlideTypes.SECTION_DIVIDER) < 2) v += "expected at least 2 '${SlideTypes.SECTION_DIVIDER}' slides (found ${countOf(SlideTypes.SECTION_DIVIDER)})"
        if (countOf(SlideTypes.SUMMARY) != 1) v += "expected exactly 1 '${SlideTypes.SUMMARY}' slide (found ${countOf(SlideTypes.SUMMARY)})"
    }

    // Rule 5: narrativeArc in allowed set
    private fun checkNarrativeArc(outline: OutlineJson, v: MutableList<String>) {
        if (outline.meta.narrativeArc !in ALLOWED_NARRATIVE_ARCS) {
            v += "meta.narrativeArc '${outline.meta.narrativeArc}' must be one of $ALLOWED_NARRATIVE_ARCS"
        }
    }

    // Rule 6: content-like slides need 2-5 bulletHints
    private fun checkBulletHints(outline: OutlineJson, v: MutableList<String>) {
        for (slide in outline.slides) {
            if (slide.slideType in SlideTypes.CONTENT_LIKE) {
                val size = slide.bulletHints.size
                if (size !in 2..5) {
                    v += "slide ${slide.index} (${slide.slideType}) must have 2-5 bulletHints (found $size)"
                }
            }
        }
    }

    // Rule 7: no more than 3 consecutive identical slideType
    private fun checkConsecutiveTypes(outline: OutlineJson, v: MutableList<String>) {
        val ordered = outline.slides.sortedBy { it.index }
        var run = 0
        var prev: String? = null
        for (slide in ordered) {
            run = if (slide.slideType == prev) run + 1 else 1
            prev = slide.slideType
            if (run == 4) {
                v += "more than 3 consecutive '${slide.slideType}' slides around index ${slide.index}"
            }
        }
    }

    // Rule 8: keyTerms non-empty
    private fun checkKeyTerms(outline: OutlineJson, v: MutableList<String>) {
        if (outline.consistency.keyTerms.isEmpty()) {
            v += "consistency.keyTerms must not be empty"
        }
    }

    // Bonus: slideType must be a known enum value
    private fun checkKnownSlideTypes(outline: OutlineJson, v: MutableList<String>) {
        for (slide in outline.slides) {
            if (slide.slideType in LayoutProfiles.ALL && slide.slideType !in SlideTypes.ALL) {
                v += "slide ${slide.index} slideType '${slide.slideType}' is a section layoutProfile name, not a valid slideType — " +
                    "put '$slide.slideType' on section.layoutProfile only; use content/comparison/timeline/etc. for slides"
                continue
            }
            if (slide.slideType !in SlideTypes.ALL) {
                v += "slide ${slide.index} has unknown slideType '${slide.slideType}'"
            }
        }
    }

    // Rules 9-11: layoutProfile validation
    private fun checkLayoutProfiles(outline: OutlineJson, v: MutableList<String>) {
        // Rule 9: valid enum
        for (section in outline.sections) {
            if (section.layoutProfile !in LayoutProfiles.ALL) {
                v += "section '${section.id}' has unknown layoutProfile '${section.layoutProfile}'"
            }
        }

        // Rule 10: deck-level diversity
        val sectionCount = outline.sections.size
        val distinctProfiles = outline.sections.map { it.layoutProfile }.toSet().size
        val minRequired = when {
            sectionCount >= 5 -> 3
            sectionCount >= 3 -> 2
            else -> 1
        }
        if (distinctProfiles < minRequired) {
            v += "deck with $sectionCount sections must use at least $minRequired different layoutProfile values (found $distinctProfiles)"
        }

        // Rule 11: coherence with slide types
        for (section in outline.sections) {
            if (section.slideRange.size != 2) continue
            val (start, end) = section.slideRange
            val contentSlides = outline.slides.filter { slide ->
                slide.index in start..end &&
                    slide.slideType != SlideTypes.SECTION_DIVIDER &&
                    slide.slideType != SlideTypes.AGENDA
            }
            val timelineCount = contentSlides.count { it.slideType == SlideTypes.TIMELINE }
            val comparisonCount = contentSlides.count { it.slideType == SlideTypes.COMPARISON }

            if (timelineCount >= 2 && section.layoutProfile !in setOf(LayoutProfiles.TIMELINE_FLOW, LayoutProfiles.SPLIT_NARRATIVE)) {
                v += "section '${section.id}' has $timelineCount timeline slides but layoutProfile '${section.layoutProfile}' (prefer timeline_flow or split_narrative)"
            }
            if (comparisonCount >= 2 && section.layoutProfile !in setOf(LayoutProfiles.SPLIT_NARRATIVE, LayoutProfiles.EDITORIAL_LEFT)) {
                v += "section '${section.id}' has $comparisonCount comparison slides but layoutProfile '${section.layoutProfile}' (prefer split_narrative or editorial_left)"
            }
        }
    }
}
