package com.ppt.agent.business.outline

import com.ppt.agent.business.input.PptInput
import com.ppt.agent.framework.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pure-Kotlin validation tests. The "valid" baseline is the hand-checked
 * 27-slide fixture at `src/test/resources/outline/valid-outline.json`; invalid
 * cases are produced by copying that baseline and breaking one rule at a time.
 */
class OutlineValidatorTest {

    private val validator = OutlineValidator()
    private val input = PptInput(topic = "Python 入门 30 分钟", brief = "b", audience = "a", slideCount = 27)

    private fun loadValid(): OutlineJson {
        val text = javaClass.getResource("/outline/valid-outline.json")!!.readText()
        return Json.fromJson(text, OutlineJson::class.java)
    }

    @Test
    fun validFixtureHasNoViolations() {
        val violations = validator.validate(loadValid(), input)
        assertTrue(violations.isEmpty(), "expected no violations, got: $violations")
    }

    @Test
    fun slideCountMismatchIsReported() {
        val outline = loadValid().let { it.copy(slides = it.slides.dropLast(1)) } // 26 slides, meta says 27
        val violations = validator.validate(outline, input)
        assertTrue(violations.any { it.contains("slides.size") }, violations.toString())
    }

    @Test
    fun metaSlideCountMismatchIsReported() {
        val outline = loadValid().let { it.copy(meta = it.meta.copy(slideCount = 30)) }
        val violations = validator.validate(outline, input)
        assertTrue(violations.any { it.contains("meta.slideCount") }, violations.toString())
    }

    @Test
    fun duplicateIndicesAreReported() {
        val outline = loadValid().let { base ->
            val slides = base.slides.toMutableList()
            slides[1] = slides[1].copy(index = 1) // two slides now share index 1
            base.copy(slides = slides)
        }
        val violations = validator.validate(outline, input)
        assertTrue(violations.any { it.contains("unique") }, violations.toString())
    }

    @Test
    fun nonConsecutiveIndicesAreReported() {
        val outline = loadValid().let { base ->
            val slides = base.slides.toMutableList()
            slides[1] = slides[1].copy(index = 99) // gap: 99 instead of 2
            base.copy(slides = slides)
        }
        val violations = validator.validate(outline, input)
        assertTrue(violations.any { it.contains("1..27") }, violations.toString())
    }

    @Test
    fun overlappingSectionRangesAreReported() {
        val outline = loadValid().let { base ->
            val sections = base.sections.toMutableList()
            sections[0] = sections[0].copy(slideRange = listOf(1, 5)) // overlaps section starting at 5
            base.copy(sections = sections)
        }
        val violations = validator.validate(outline, input)
        assertTrue(violations.any { it.contains("overlaps") }, violations.toString())
    }

    @Test
    fun sectionRangeGapIsReported() {
        val outline = loadValid().let { base ->
            val sections = base.sections.toMutableList()
            sections[0] = sections[0].copy(slideRange = listOf(2, 4)) // slide 1 no longer covered
            base.copy(sections = sections)
        }
        val violations = validator.validate(outline, input)
        assertTrue(violations.any { it.contains("no gaps") }, violations.toString())
    }

    @Test
    fun malformedSectionRangeIsReported() {
        val outline = loadValid().let { base ->
            val sections = base.sections.toMutableList()
            sections[0] = sections[0].copy(slideRange = listOf(1)) // not [start, end]
            base.copy(sections = sections)
        }
        val violations = validator.validate(outline, input)
        assertTrue(violations.any { it.contains("[start, end]") }, violations.toString())
    }

    @Test
    fun missingSummaryTypeIsReported() {
        val outline = loadValid().let { base ->
            val slides = base.slides.map {
                if (it.slideType == SlideTypes.SUMMARY) it.copy(slideType = SlideTypes.CONTENT) else it
            }
            base.copy(slides = slides)
        }
        val violations = validator.validate(outline, input)
        assertTrue(violations.any { it.contains("'summary'") }, violations.toString())
    }

    @Test
    fun tooFewSectionDividersIsReported() {
        val outline = loadValid().let { base ->
            var replaced = 0
            val slides = base.slides.map {
                if (it.slideType == SlideTypes.SECTION_DIVIDER && replaced++ < 4) {
                    it.copy(slideType = SlideTypes.CONTENT, bulletHints = listOf("a", "b"))
                } else {
                    it
                }
            }
            base.copy(slides = slides)
        }
        val violations = validator.validate(outline, input)
        assertTrue(violations.any { it.contains("section_divider") }, violations.toString())
    }

    @Test
    fun invalidNarrativeArcIsReported() {
        val outline = loadValid().let { it.copy(meta = it.meta.copy(narrativeArc = "bogus_arc")) }
        val violations = validator.validate(outline, input)
        assertTrue(violations.any { it.contains("narrativeArc") }, violations.toString())
    }

    @Test
    fun contentSlideWithTooFewBulletHintsIsReported() {
        val outline = loadValid().let { base ->
            val slides = base.slides.toMutableList()
            val i = slides.indexOfFirst { it.slideType == SlideTypes.CONTENT }
            slides[i] = slides[i].copy(bulletHints = listOf("only one"))
            base.copy(slides = slides)
        }
        val violations = validator.validate(outline, input)
        assertTrue(violations.any { it.contains("2-5 bulletHints") }, violations.toString())
    }

    @Test
    fun moreThanThreeConsecutiveIdenticalTypesIsReported() {
        val outline = loadValid().let { base ->
            val slides = base.slides.map {
                if (it.index in 2..8) it.copy(slideType = SlideTypes.CONTENT, bulletHints = listOf("a", "b")) else it
            }
            base.copy(slides = slides)
        }
        val violations = validator.validate(outline, input)
        assertTrue(violations.any { it.contains("consecutive") }, violations.toString())
    }

    @Test
    fun emptyKeyTermsIsReported() {
        val outline = loadValid().let { it.copy(consistency = it.consistency.copy(keyTerms = emptyList())) }
        val violations = validator.validate(outline, input)
        assertTrue(violations.any { it.contains("keyTerms") }, violations.toString())
    }

    @Test
    fun unknownSlideTypeIsReported() {
        val outline = loadValid().let { base ->
            val slides = base.slides.toMutableList()
            slides[10] = slides[10].copy(slideType = "mystery_type")
            base.copy(slides = slides)
        }
        val violations = validator.validate(outline, input)
        assertTrue(violations.any { it.contains("unknown slideType") }, violations.toString())
    }

    @Test
    fun rejectsLayoutProfileNameUsedAsSlideType() {
        val outline = loadValid().let { base ->
            val slides = base.slides.toMutableList()
            slides[11] = slides[11].copy(slideType = "split_narrative")
            base.copy(slides = slides)
        }
        val violations = validator.validate(outline, input)
        assertTrue(violations.any { it.contains("layoutProfile name") }, violations.toString())
    }

    @Test
    fun rejectsUnknownLayoutProfile() {
        val outline = loadValid().let { base ->
            val sections = base.sections.toMutableList()
            sections[0] = sections[0].copy(layoutProfile = "neon_future")
            base.copy(sections = sections)
        }
        val violations = validator.validate(outline, input)
        assertTrue(violations.any { it.contains("unknown layoutProfile") }, violations.toString())
    }

    @Test
    fun rejectsUniformLayoutProfilesWhenManySections() {
        val outline = loadValid().let { base ->
            val sections = base.sections.map { it.copy(layoutProfile = LayoutProfiles.EDITORIAL_LEFT) }
            base.copy(sections = sections)
        }
        val violations = validator.validate(outline, input)
        assertTrue(violations.any { it.contains("at least 3 different layoutProfile") }, violations.toString())
    }

    @Test
    fun collectsAllViolationsInsteadOfFailingFast() {
        val outline = loadValid().let {
            it.copy(
                meta = it.meta.copy(narrativeArc = "bogus_arc"),
                consistency = it.consistency.copy(keyTerms = emptyList()),
            )
        }
        val violations = validator.validate(outline, input)
        assertEquals(
            2,
            violations.count { v -> v.contains("narrativeArc") || v.contains("keyTerms") },
            violations.toString(),
        )
    }
}
