package com.ppt.agent.renderer.poi

import com.ppt.agent.framework.Json
import com.ppt.agent.renderer.model.DeckDocument
import com.ppt.agent.renderer.model.RootDocument
import org.apache.poi.xslf.usermodel.XMLSlideShow
import org.apache.poi.xslf.usermodel.XSLFTextShape
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TemplatePptxWriterTest {

    private fun deck(): DeckDocument =
        Json.fromJson(javaClass.getResource("/fixtures/valid-deck.json")!!.readText(), RootDocument::class.java).toDeck()

    private fun tempPptx(): Path = Files.createTempFile("template-writer", ".pptx").also { it.toFile().deleteOnExit() }

    @Test
    fun rendersDeckIntoTemplateWithTwentySevenSlides() {
        val output = tempPptx()

        TemplatePptxWriter(null).write(deck(), output)

        assertTrue(Files.size(output) > 0, "template output should not be empty")
        XMLSlideShow(Files.newInputStream(output)).use { ppt ->
            assertEquals(27, ppt.slides.size)
        }
    }

    @Test
    fun filledSlidesContainFixtureTitleText() {
        val output = tempPptx()

        TemplatePptxWriter(null).write(deck(), output)

        XMLSlideShow(Files.newInputStream(output)).use { ppt ->
            val allText = ppt.slides
                .flatMap { slide -> slide.shapes.filterIsInstance<XSLFTextShape>() }
                .map { it.text }
            assertTrue(
                allText.any { it.contains("Python") },
                "expected at least one slide to carry fixture title text mentioning Python",
            )
        }
    }
}
