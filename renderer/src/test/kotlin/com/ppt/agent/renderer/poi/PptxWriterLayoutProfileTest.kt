package com.ppt.agent.renderer.poi

import com.ppt.agent.renderer.model.DeckDocument
import com.ppt.agent.renderer.model.DeckMeta
import com.ppt.agent.renderer.model.SlideDocument
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PptxWriterLayoutProfileTest {

    private val morandi = listOf("#2B2A28", "#3A3835", "#8C7A6B", "#6E6A63", "#E8E3DC")

    private fun tinyDeck(sectionLayouts: Map<String, String>? = null): DeckDocument = DeckDocument(
        meta = DeckMeta(
            topic = "Test",
            slideCount = 3,
            language = "en",
            themeColors = morandi,
            sectionLayouts = sectionLayouts,
        ),
        slides = listOf(
            SlideDocument(1, "s1", "title", "Title Slide", "sub", emptyList(), null, null),
            SlideDocument(2, "s1", "content", "Content", null, listOf("a", "b", "c"), null, null),
            SlideDocument(3, "s2", "section_divider", "Section", "sub", emptyList(), null, null),
        ),
    )

    private fun tempPptx(): java.nio.file.Path =
        Files.createTempFile("layout-profile-test", ".pptx").also { it.toFile().deleteOnExit() }

    @Test
    fun defaultProfileWritesSuccessfully() {
        val output = tempPptx()
        PptxWriter().write(tinyDeck(), output)
        assertTrue(Files.size(output) > 0, "output file should be non-empty")
    }

    @Test
    fun centeredImpactWritesSuccessfully() {
        val output = tempPptx()
        val deck = tinyDeck(sectionLayouts = mapOf("s1" to "centered_impact", "s2" to "pitch_bold"))
        PptxWriter().write(deck, output)
        assertTrue(Files.size(output) > 0, "output file should be non-empty")
    }

    @Test
    fun differentProfilesProduceDifferentOutput() {
        val outDefault = tempPptx()
        PptxWriter().write(tinyDeck(), outDefault)

        val outStyled = tempPptx()
        val styledDeck = tinyDeck(sectionLayouts = mapOf("s1" to "centered_impact", "s2" to "dense_reference"))
        PptxWriter().write(styledDeck, outStyled)

        val sizeDefault = Files.size(outDefault)
        val sizeStyled = Files.size(outStyled)
        assertNotEquals(sizeDefault, sizeStyled, "different layout profiles should produce different file sizes")
    }

    @Test
    fun unknownProfileFallsBackGracefully() {
        val output = tempPptx()
        val deck = tinyDeck(sectionLayouts = mapOf("s1" to "neon_future", "s2" to "unknown"))
        PptxWriter().write(deck, output)
        assertTrue(Files.size(output) > 0, "output should write successfully with unknown profiles")
    }
}
