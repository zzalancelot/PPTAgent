package com.ppt.agent.renderer.validate

import com.ppt.agent.framework.Json
import com.ppt.agent.renderer.model.DeckDocument
import com.ppt.agent.renderer.model.DeckMeta
import com.ppt.agent.renderer.model.RootDocument
import com.ppt.agent.renderer.model.SlideDocument
import kotlin.test.Test
import kotlin.test.assertTrue

class DeckValidatorTest {

    private val validator = DeckValidator()

    private fun slide(index: Int, title: String = "T", type: String = "content") =
        SlideDocument(index, "s", type, title, null, listOf("a", "b"), null, null)

    private fun deck(meta: DeckMeta, slides: List<SlideDocument>) = DeckDocument(meta, slides)

    private fun meta(topic: String = "Topic", count: Int = 2) = DeckMeta(topic, count, "zh-CN", emptyMap())

    @Test
    fun validFixturePasses() {
        val text = javaClass.getResource("/fixtures/valid-deck.json")!!.readText()
        val document = Json.fromJson(text, RootDocument::class.java).toDeck()

        assertTrue(validator.validate(document).isEmpty())
    }

    @Test
    fun slideCountMismatchFails() {
        val d = deck(meta(count = 3), listOf(slide(1), slide(2)))
        assertTrue(validator.validate(d).any { it.contains("must equal meta.slideCount") })
    }

    @Test
    fun nonContiguousIndicesFail() {
        val d = deck(meta(count = 2), listOf(slide(1), slide(3)))
        assertTrue(validator.validate(d).any { it.contains("1..2") })
    }

    @Test
    fun duplicateIndicesFail() {
        val d = deck(meta(count = 2), listOf(slide(1), slide(1)))
        assertTrue(validator.validate(d).any { it.contains("unique") })
    }

    @Test
    fun blankTitleFails() {
        val d = deck(meta(count = 1), listOf(slide(1, title = "   ")))
        assertTrue(validator.validate(d).any { it.contains("title must not be blank") })
    }

    @Test
    fun blankTopicFails() {
        val d = deck(meta(topic = "  ", count = 1), listOf(slide(1)))
        assertTrue(validator.validate(d).any { it.contains("meta.topic must not be blank") })
    }

    @Test
    fun missingMetaAndSlidesFail() {
        val violations = validator.validate(DeckDocument(null, null))
        assertTrue(violations.any { it.contains("missing 'meta'") })
        assertTrue(violations.any { it.contains("missing 'slides'") })
    }
}
