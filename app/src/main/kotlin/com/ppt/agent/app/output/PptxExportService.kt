package com.ppt.agent.app.output

import com.ppt.agent.business.content.SlideDeckContent
import com.ppt.agent.framework.Json
import com.ppt.agent.renderer.PptRenderTool
import com.ppt.agent.renderer.PptRenderToolImpl
import com.ppt.agent.renderer.RenderMode
import com.ppt.agent.renderer.RenderToolResult
import org.springframework.stereotype.Service
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

data class PptxExportInfo(
    val fileName: String,
    val downloadPath: String,
    val absolutePath: Path,
    val slideCount: Int,
)

sealed class PptxExportResult {
    data class Ok(val info: PptxExportInfo) : PptxExportResult()
    data class Err(val message: String) : PptxExportResult()
}

/**
 * Writes [SlideDeckContent] to a temp JSON file and invokes the standalone
 * [PptRenderTool]. Output lands in [PptOutputProperties.dir] for download.
 */
@Service
class PptxExportService(
    private val properties: PptOutputProperties,
    private val renderTool: PptRenderTool = PptRenderToolImpl(),
) {

    fun render(
        deck: SlideDeckContent,
        topic: String,
        themeColors: List<String>,
        sectionLayouts: Map<String, String> = emptyMap(),
    ): PptxExportResult {
        val outputDir = Path.of(properties.dir).toAbsolutePath().normalize()
        try {
            Files.createDirectories(outputDir)
        } catch (e: Exception) {
            return PptxExportResult.Err("could not create output directory '$outputDir': ${e.message}")
        }

        val fileName = buildFileName(topic)
        val outputPptx = outputDir.resolve(fileName).normalize()
        if (!outputPptx.startsWith(outputDir)) {
            return PptxExportResult.Err("invalid output path")
        }

        val tempJson = Files.createTempFile("ppt-deck-", ".json")
        try {
            Files.writeString(tempJson, deckJson(deck, themeColors, sectionLayouts), StandardCharsets.UTF_8)
            return when (
                val result = renderTool.render(tempJson, outputPptx, RenderMode.PROGRAMMATIC)
            ) {
                is RenderToolResult.Ok ->
                    PptxExportResult.Ok(
                        PptxExportInfo(
                            fileName = fileName,
                            downloadPath = "/v1/ppt/download/$fileName",
                            absolutePath = outputPptx,
                            slideCount = result.slideCount,
                        ),
                    )
                is RenderToolResult.Err ->
                    PptxExportResult.Err(result.errors.joinToString("; ") { it.toString() })
            }
        } catch (e: Exception) {
            return PptxExportResult.Err(e.message ?: "render failed")
        } finally {
            Files.deleteIfExists(tempJson)
        }
    }

    private fun deckJson(deck: SlideDeckContent, themeColors: List<String>, sectionLayouts: Map<String, String>): String {
        val meta = linkedMapOf<String, Any?>(
            "topic" to deck.meta.topic,
            "slideCount" to deck.meta.slideCount,
            "language" to deck.meta.language,
            "modelsUsed" to deck.meta.modelsUsed,
            "themeColors" to themeColors,
            "sectionLayouts" to sectionLayouts,
        )
        return Json.toJson(mapOf("meta" to meta, "slides" to deck.slides))
    }

    fun resolveDownload(fileName: String): Path? {
        if (!SAFE_FILE_NAME.matches(fileName)) return null
        val outputDir = Path.of(properties.dir).toAbsolutePath().normalize()
        val candidate = outputDir.resolve(fileName).normalize()
        if (!candidate.startsWith(outputDir) || !Files.isRegularFile(candidate)) return null
        return candidate
    }

    private fun buildFileName(topic: String): String {
        val slug =
            topic
                .trim()
                .lowercase()
                .replace(Regex("[^a-z0-9\\u4e00-\\u9fff]+"), "-")
                .trim('-')
                .take(40)
                .ifBlank { "deck" }
        val ts = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now())
        return "$slug-$ts.pptx"
    }

    companion object {
        private val SAFE_FILE_NAME = Regex("^[a-z0-9\\u4e00-\\u9fff._-]+\\.pptx$", RegexOption.IGNORE_CASE)
    }
}
