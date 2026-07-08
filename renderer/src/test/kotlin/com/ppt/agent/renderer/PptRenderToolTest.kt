package com.ppt.agent.renderer

import com.ppt.agent.renderer.cli.runCli
import org.apache.poi.xslf.usermodel.XMLSlideShow
import org.apache.poi.xslf.usermodel.XSLFTextShape
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PptRenderToolTest {

    private fun fixtureText(): String = javaClass.getResource("/fixtures/valid-deck.json")!!.readText()

    private fun tempFile(prefix: String, suffix: String, text: String? = null): Path {
        val p = Files.createTempFile(prefix, suffix)
        if (text != null) Files.writeString(p, text)
        p.toFile().deleteOnExit()
        return p
    }

    private fun firstSlideText(pptx: Path): String =
        XMLSlideShow(Files.newInputStream(pptx)).use { ppt ->
            ppt.slides.first().shapes.filterIsInstance<XSLFTextShape>().joinToString(" ") { it.text }
        }

    private fun slideCount(pptx: Path): Int =
        XMLSlideShow(Files.newInputStream(pptx)).use { it.slides.size }

    @Test
    fun rendersFixtureToTwentySevenSlidePptx() {
        val input = tempFile("deck", ".json", fixtureText())
        val output = tempFile("deck", ".pptx")

        val result = PptRenderToolImpl().render(input, output)

        assertTrue(result is RenderToolResult.Ok, "expected Ok, got $result")
        assertEquals(27, (result as RenderToolResult.Ok).slideCount)
        assertTrue(Files.size(output) > 0, "output pptx should not be empty")
        assertEquals(27, slideCount(output))
        assertTrue(firstSlideText(output).contains("Python"), "first slide should mention Python")
    }

    @Test
    fun acceptsWrappedContentJson() {
        val wrapped = "{ \"content\": ${fixtureText()} }"
        val input = tempFile("wrapped", ".json", wrapped)
        val output = tempFile("wrapped", ".pptx")

        val result = PptRenderToolImpl().render(input, output)

        assertTrue(result is RenderToolResult.Ok, "expected Ok, got $result")
        assertEquals(27, (result as RenderToolResult.Ok).slideCount)
        assertEquals(27, slideCount(output))
    }

    @Test
    fun reportsValidationErrorForBadDeck() {
        val badJson = """{"meta":{"topic":"X","slideCount":5},"slides":[]}"""
        val input = tempFile("bad", ".json", badJson)
        val output = tempFile("bad", ".pptx")

        val result = PptRenderToolImpl().render(input, output)

        assertTrue(result is RenderToolResult.Err, "expected Err, got $result")
        assertTrue((result as RenderToolResult.Err).errors.any { it is RenderToolError.ValidationFailed })
    }

    @Test
    fun reportsInvalidJson() {
        val input = tempFile("broken", ".json", "{ not valid json ")
        val output = tempFile("broken", ".pptx")

        val result = PptRenderToolImpl().render(input, output)

        assertTrue(result is RenderToolResult.Err)
        assertTrue((result as RenderToolResult.Err).errors.any { it is RenderToolError.InvalidJson })
    }

    @Test
    fun reportsUnsupportedSlideType() {
        val json = """{"meta":{"topic":"X","slideCount":1},"slides":[
            {"index":1,"sectionId":"s","slideType":"hologram","title":"T","bullets":[]}]}"""
        val input = tempFile("unsupported", ".json", json)
        val output = tempFile("unsupported", ".pptx")

        val result = PptRenderToolImpl().render(input, output)

        assertTrue(result is RenderToolResult.Err)
        val err = (result as RenderToolResult.Err).errors.filterIsInstance<RenderToolError.UnsupportedSlideType>().firstOrNull()
        assertTrue(err != null && err.index == 1 && err.slideType == "hologram", "expected UnsupportedSlideType: ${result.errors}")
    }

    @Test
    fun cliRendersSuccessfullyAndReturnsZero() {
        val input = tempFile("cli", ".json", fixtureText())
        val output = tempFile("cli", ".pptx")

        val code = runCli(arrayOf("--input", input.toString(), "--output", output.toString()))

        assertEquals(0, code)
        assertEquals(27, slideCount(output))
    }

    @Test
    fun cliReturnsOneWhenArgumentsMissing() {
        assertEquals(1, runCli(arrayOf("--input")))
    }
}
