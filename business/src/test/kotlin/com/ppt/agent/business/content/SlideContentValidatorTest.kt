package com.ppt.agent.business.content

import com.ppt.agent.business.outline.ConsistencyRules
import com.ppt.agent.business.outline.OutlineJson
import com.ppt.agent.business.outline.OutlineMeta
import com.ppt.agent.business.outline.OutlineSection
import com.ppt.agent.business.outline.OutlineSlide
import com.ppt.agent.business.outline.SlideTypes
import com.ppt.agent.business.outline.Storyline
import kotlin.test.Test
import kotlin.test.assertTrue

class SlideContentValidatorTest {

    private val validator = SlideContentValidator()

    private fun outlineSlide(index: Int, type: String, sectionId: String = "s1") = OutlineSlide(
        index = index,
        sectionId = sectionId,
        slideType = type,
        title = "outline title",
        subtitleHint = null,
        intent = "intent",
        bulletHints = emptyList(),
        visualHint = null,
        transition = null,
    )

    private fun slideContent(
        index: Int,
        type: String,
        title: String = "A Title",
        bullets: List<String> = listOf("a", "b", "c"),
        sectionId: String = "s1",
        notes: String? = "notes",
    ) = SlideContent(
        index = index,
        sectionId = sectionId,
        slideType = type,
        title = title,
        subtitle = null,
        bullets = bullets,
        speakerNotes = notes,
        bodyText = null,
    )

    @Test
    fun validContentSlideHasNoViolations() {
        val outline = outlineSlide(5, SlideTypes.CONTENT)
        val content = slideContent(5, SlideTypes.CONTENT, bullets = listOf("one", "two", "three"))
        assertTrue(validator.validateSlide(content, outline).isEmpty())
    }

    @Test
    fun indexMismatchIsReported() {
        val outline = outlineSlide(5, SlideTypes.CONTENT)
        val content = slideContent(6, SlideTypes.CONTENT)
        assertTrue(validator.validateSlide(content, outline).any { it.contains("must match outline slide index") })
    }

    @Test
    fun blankTitleIsReported() {
        val outline = outlineSlide(5, SlideTypes.CONTENT)
        val content = slideContent(5, SlideTypes.CONTENT, title = "   ")
        assertTrue(validator.validateSlide(content, outline).any { it.contains("title must not be blank") })
    }

    @Test
    fun contentSlideWithTooFewBulletsIsReported() {
        val outline = outlineSlide(5, SlideTypes.CONTENT)
        val content = slideContent(5, SlideTypes.CONTENT, bullets = listOf("only one"))
        assertTrue(validator.validateSlide(content, outline).any { it.contains("2-4 bullets") })
    }

    @Test
    fun contentSlideWithTooManyBulletsIsReported() {
        val outline = outlineSlide(5, SlideTypes.CONTENT)
        val content = slideContent(5, SlideTypes.CONTENT, bullets = listOf("1", "2", "3", "4", "5"))
        assertTrue(validator.validateSlide(content, outline).any { it.contains("2-4 bullets") })
    }

    @Test
    fun comparisonSlideAlsoRequiresTwoToFourBullets() {
        val outline = outlineSlide(5, SlideTypes.COMPARISON)
        val content = slideContent(5, SlideTypes.COMPARISON, bullets = listOf("only one"))
        assertTrue(validator.validateSlide(content, outline).any { it.contains("2-4 bullets") })
    }

    @Test
    fun titleSlideMayHaveNoBullets() {
        val outline = outlineSlide(1, SlideTypes.TITLE)
        val content = slideContent(1, SlideTypes.TITLE, bullets = emptyList())
        assertTrue(validator.validateSlide(content, outline).isEmpty())
    }

    @Test
    fun qaSlideMayHaveNoBullets() {
        val outline = outlineSlide(27, SlideTypes.QA)
        val content = slideContent(27, SlideTypes.QA, bullets = emptyList())
        assertTrue(validator.validateSlide(content, outline).isEmpty())
    }

    @Test
    fun sectionDividerWithExcessiveBulletsIsReported() {
        val outline = outlineSlide(5, SlideTypes.SECTION_DIVIDER)
        val content = slideContent(5, SlideTypes.SECTION_DIVIDER, bullets = listOf("1", "2", "3", "4", "5", "6", "7"))
        assertTrue(validator.validateSlide(content, outline).any { it.contains("too many bullets") })
    }

    // ---- deck-level ----

    private fun outlineWith(vararg slides: OutlineSlide): OutlineJson = OutlineJson(
        meta = OutlineMeta("t", "a", slides.size, "en", "teaching", "clear", "one"),
        storyline = Storyline("h", "p", emptyList(), emptyList(), emptyList(), "m"),
        sections = listOf(OutlineSection("s1", "s1", "p", listOf(1, slides.size))),
        slides = slides.toList(),
        consistency = ConsistencyRules(emptyList(), emptyList(), emptyList(), emptyList(), ""),
    )

    @Test
    fun deckWithMatchingSizeAndCompleteIndicesIsValid() {
        val outline = outlineWith(outlineSlide(1, SlideTypes.TITLE), outlineSlide(2, SlideTypes.CONTENT))
        val deck = SlideDeckContent(
            meta = ContentMeta("t", 2, "en", emptyMap()),
            slides = listOf(slideContent(1, SlideTypes.TITLE), slideContent(2, SlideTypes.CONTENT)),
        )
        assertTrue(validator.validateDeck(deck, outline).isEmpty())
    }

    @Test
    fun deckWithWrongSlideCountIsReported() {
        val outline = outlineWith(outlineSlide(1, SlideTypes.TITLE), outlineSlide(2, SlideTypes.CONTENT))
        val deck = SlideDeckContent(
            meta = ContentMeta("t", 1, "en", emptyMap()),
            slides = listOf(slideContent(1, SlideTypes.TITLE)),
        )
        val violations = validator.validateDeck(deck, outline)
        assertTrue(violations.any { it.contains("expected 2") }, violations.toString())
    }

    @Test
    fun deckWithMissingIndexIsReported() {
        val outline = outlineWith(
            outlineSlide(1, SlideTypes.TITLE),
            outlineSlide(2, SlideTypes.CONTENT),
            outlineSlide(3, SlideTypes.SUMMARY),
        )
        // three slides but index 2 duplicated / 3 missing
        val deck = SlideDeckContent(
            meta = ContentMeta("t", 3, "en", emptyMap()),
            slides = listOf(
                slideContent(1, SlideTypes.TITLE),
                slideContent(2, SlideTypes.CONTENT),
                slideContent(2, SlideTypes.CONTENT),
            ),
        )
        val violations = validator.validateDeck(deck, outline)
        assertTrue(violations.any { it.contains("missing") }, violations.toString())
    }
}
