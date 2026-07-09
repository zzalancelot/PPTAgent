package com.ppt.agent.renderer.poi

import com.ppt.agent.framework.Json
import com.ppt.agent.renderer.model.DeckDocument
import com.ppt.agent.renderer.model.DeckMeta
import com.ppt.agent.renderer.model.RootDocument
import com.ppt.agent.renderer.model.SlideDocument
import org.apache.poi.xslf.usermodel.XMLSlideShow
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class PptxWriterThemeTest {

    private val morandi = listOf("#2B2A28", "#3A3835", "#8C7A6B", "#6E6A63", "#E8E3DC")

    private fun deckWithTheme(): DeckDocument {
        val base = Json.fromJson(
            javaClass.getResource("/fixtures/valid-deck.json")!!.readText(),
            RootDocument::class.java,
        ).toDeck()
        return base.copy(meta = base.meta?.copy(themeColors = morandi))
    }

    private fun tempPptx(): java.nio.file.Path =
        Files.createTempFile("themed-writer", ".pptx").also { it.toFile().deleteOnExit() }

    @Test
    fun appliesMorandiBackgroundToFirstSlide() {
        val output = tempPptx()
        PptxWriter().write(deckWithTheme(), output)

        XMLSlideShow(Files.newInputStream(output)).use { ppt ->
            val bg = ppt.slides.first().background.fillColor
            assertEquals(43, bg.red)
            assertEquals(42, bg.green)
            assertEquals(40, bg.blue)
        }
    }

    @Test
    fun sectionDividerSlideUsesSurfaceColor() {
        val deck = DeckDocument(
            meta = DeckMeta(topic = "t", slideCount = 1, language = "zh", themeColors = morandi),
            slides = listOf(
                SlideDocument(
                    index = 1,
                    sectionId = "s1",
                    slideType = "section_divider",
                    title = "Section",
                    subtitle = "subtitle",
                    bullets = emptyList(),
                    speakerNotes = null,
                    bodyText = null,
                ),
            ),
        )
        val output = tempPptx()
        PptxWriter().write(deck, output)

        XMLSlideShow(Files.newInputStream(output)).use { ppt ->
            val bg = ppt.slides.single().background.fillColor
            assertEquals(58, bg.red)
            assertEquals(56, bg.green)
            assertEquals(53, bg.blue)
        }
    }
}
