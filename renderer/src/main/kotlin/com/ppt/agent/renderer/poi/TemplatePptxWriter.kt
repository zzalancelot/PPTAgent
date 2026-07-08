package com.ppt.agent.renderer.poi

import com.ppt.agent.renderer.layout.SlideLayoutKind
import com.ppt.agent.renderer.layout.SlideLayoutMapper
import com.ppt.agent.renderer.layout.toLayoutIndex
import com.ppt.agent.renderer.model.DeckDocument
import com.ppt.agent.renderer.model.SlideDocument
import org.apache.poi.sl.usermodel.Placeholder
import org.apache.poi.xslf.usermodel.XMLSlideShow
import org.apache.poi.xslf.usermodel.XSLFSlide
import org.apache.poi.xslf.usermodel.XSLFTextShape
import java.awt.Color
import java.awt.geom.Rectangle2D
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path

/**
 * `RenderMode.TEMPLATE` [DeckRenderer]. Opens a bundled/custom `.pptx` master
 * template and fills its layout placeholders (title / body / subtitle) per
 * slide, mirroring the field rules of [PptxWriter]. Falls back to a plain text
 * box when a layout lacks an expected placeholder, so output never loses a
 * title or its content. Speaker notes are attached best-effort.
 *
 * @param templatePath custom template; when null, the bundled resource
 *        `/templates/deck-template.pptx` is used.
 */
internal class TemplatePptxWriter(
    private val templatePath: Path?,
) : DeckRenderer {

    override fun write(deck: DeckDocument, output: Path) {
        openTemplateStream().use { input ->
            val ppt = XMLSlideShow(input)
            try {
                removeSampleSlides(ppt)

                val master = ppt.slideMasters.firstOrNull()
                    ?: throw TemplateLayoutMissingException("<no slide master>")
                val layouts = master.slideLayouts
                if (layouts.isEmpty()) throw TemplateLayoutMissingException("<no slide layouts>")

                val size = ppt.pageSize
                slideW = size.width.toDouble()
                slideH = size.height.toDouble()

                deck.slides.orEmpty().sortedBy { it.index ?: Int.MAX_VALUE }.forEach { slide ->
                    val kind = SlideLayoutMapper.mapOrNull(slide.slideType ?: "") ?: SlideLayoutKind.BULLETS
                    val index = kind.toLayoutIndex().coerceIn(0, layouts.size - 1)
                    val poiSlide = ppt.createSlide(layouts[index])
                    fill(poiSlide, slide, kind)
                    attachNotes(ppt, poiSlide, slide.speakerNotes)
                }

                val parent = output.toAbsolutePath().parent
                if (parent != null) Files.createDirectories(parent)
                Files.newOutputStream(output).use { ppt.write(it) }
            } finally {
                ppt.close()
            }
        }
    }

    private fun openTemplateStream(): InputStream {
        val path = templatePath
        if (path != null) {
            if (!Files.exists(path)) throw TemplateNotFoundException(path.toString())
            return Files.newInputStream(path)
        }
        return javaClass.getResourceAsStream(BUNDLED_TEMPLATE)
            ?: throw TemplateNotFoundException(BUNDLED_TEMPLATE)
    }

    private fun removeSampleSlides(ppt: XMLSlideShow) {
        while (ppt.slides.isNotEmpty()) ppt.removeSlide(0)
    }

    // ---- placeholder filling (mirrors PptxWriter field rules) ----

    private fun fill(slide: XSLFSlide, doc: SlideDocument, kind: SlideLayoutKind) {
        val placeholders = slide.placeholders.toList()
        val titlePh = placeholders.firstOrNull { it.placeholder in TITLE_KINDS }
        val fillable = placeholders.filter { it.placeholder !in TITLE_KINDS && it.placeholder !in META_KINDS }

        fillTitle(slide, titlePh, doc.title.orEmpty())

        when (kind) {
            SlideLayoutKind.TITLE, SlideLayoutKind.SECTION_DIVIDER -> {
                val sub = doc.subtitle
                if (!sub.isNullOrBlank()) fillSingle(slide, fillable.firstOrNull(), sub, y = 330.0, mono = false)
            }
            SlideLayoutKind.AGENDA, SlideLayoutKind.BULLETS, SlideLayoutKind.CLOSING ->
                fillBulletsOrBody(slide, fillable.firstOrNull(), doc)
            SlideLayoutKind.TWO_COLUMN -> fillTwoColumn(slide, fillable, doc)
            SlideLayoutKind.BODY_TEXT -> fillBodyText(slide, fillable.firstOrNull(), doc)
        }
    }

    private fun fillTitle(slide: XSLFSlide, ph: XSLFTextShape?, title: String) {
        if (ph != null) {
            setSingle(ph, title)
        } else {
            textBox(slide, MARGIN, MARGIN, slideW - 2 * MARGIN, 90.0, title, size = 30.0, bold = true)
        }
    }

    private fun fillSingle(slide: XSLFSlide, ph: XSLFTextShape?, text: String, y: Double, mono: Boolean) {
        if (ph != null) {
            setSingle(ph, text, mono)
        } else {
            textBox(slide, MARGIN, y, slideW - 2 * MARGIN, 80.0, text, size = 20.0, mono = mono)
        }
    }

    private fun fillBulletsOrBody(slide: XSLFSlide, ph: XSLFTextShape?, doc: SlideDocument) {
        val bullets = doc.bullets.orEmpty().filter { it.isNotBlank() }
        val bodyText = doc.bodyText?.takeIf { it.isNotBlank() }

        if (bullets.isEmpty()) {
            val body = bodyText ?: doc.subtitle.orEmpty()
            if (body.isNotBlank()) fillSingle(slide, ph, body, y = CONTENT_TOP, mono = false)
            return
        }

        val bulletSize = if (bullets.size > MANY_BULLETS_THRESHOLD) SMALL_BULLET_SIZE else DEFAULT_BULLET_SIZE

        if (ph != null) {
            // Template layouts typically expose one fillable placeholder — bodyText (if any)
            // becomes its first, non-bulleted paragraph, followed by the bulleted list.
            if (bodyText != null) setBodyThenBullets(ph, bodyText, bullets) else setBullets(ph, bullets)
            return
        }

        var y = CONTENT_TOP
        if (bodyText != null) {
            textBox(slide, MARGIN, y, slideW - 2 * MARGIN, BODY_TEXT_H, bodyText, size = BODY_TEXT_SIZE)
            y += BODY_TEXT_H + BODY_TEXT_GAP
        }
        val height = (slideH - CONTENT_BOTTOM_MARGIN - y).coerceAtLeast(MIN_BULLETS_H)
        bulletBox(slide, MARGIN, y, slideW - 2 * MARGIN, height, bullets, size = bulletSize)
    }

    private fun fillTwoColumn(slide: XSLFSlide, fillable: List<XSLFTextShape>, doc: SlideDocument) {
        val bullets = doc.bullets.orEmpty().filter { it.isNotBlank() }
        val mid = (bullets.size + 1) / 2
        val left = bullets.take(mid)
        val right = bullets.drop(mid)
        val colW = (slideW - 3 * MARGIN) / 2

        val leftPh = fillable.getOrNull(0)
        if (leftPh != null) setBullets(leftPh, left) else bulletBox(slide, MARGIN, 150.0, colW, slideH - 200.0, left)

        val rightPh = fillable.getOrNull(1)
        if (rightPh != null) setBullets(rightPh, right) else bulletBox(slide, MARGIN * 2 + colW, 150.0, colW, slideH - 200.0, right)
    }

    private fun fillBodyText(slide: XSLFSlide, ph: XSLFTextShape?, doc: SlideDocument) {
        val isCode = doc.slideType == "code_or_demo"
        val bodyText = doc.bodyText
        val fromBullets = doc.bullets.orEmpty().filter { it.isNotBlank() }.joinToString("\n")
        val text = when {
            !bodyText.isNullOrBlank() -> bodyText
            fromBullets.isNotBlank() -> fromBullets
            else -> doc.subtitle.orEmpty()
        }
        if (ph != null) {
            setMultiline(ph, text, mono = isCode)
        } else {
            textBox(slide, MARGIN, 150.0, slideW - 2 * MARGIN, slideH - 200.0, text, size = if (isCode) 16.0 else 20.0, mono = isCode)
        }
    }

    // ---- placeholder writers ----

    private fun setSingle(ph: XSLFTextShape, text: String, mono: Boolean = false) {
        ph.clearText()
        val para = ph.addNewTextParagraph()
        val run = para.addNewTextRun()
        run.setText(text)
        if (mono) run.setFontFamily(CODE_FONT)
    }

    private fun setMultiline(ph: XSLFTextShape, text: String, mono: Boolean) {
        ph.clearText()
        text.split("\n").forEach { line ->
            val para = ph.addNewTextParagraph()
            val run = para.addNewTextRun()
            run.setText(line)
            if (mono) run.setFontFamily(CODE_FONT)
        }
    }

    private fun setBullets(ph: XSLFTextShape, bullets: List<String>) {
        ph.clearText()
        bullets.forEach { text ->
            val para = ph.addNewTextParagraph()
            para.setBullet(true)
            para.addNewTextRun().setText(text)
        }
    }

    /** bodyText as a plain (non-bulleted) first paragraph, then the bulleted list — same placeholder. */
    private fun setBodyThenBullets(ph: XSLFTextShape, bodyText: String, bullets: List<String>) {
        ph.clearText()
        val bodyPara = ph.addNewTextParagraph()
        bodyPara.addNewTextRun().setText(bodyText)
        bullets.forEach { text ->
            val para = ph.addNewTextParagraph()
            para.setBullet(true)
            para.addNewTextRun().setText(text)
        }
    }

    // ---- text-box fallbacks (dark text for the light template background) ----

    private fun textBox(
        slide: XSLFSlide,
        x: Double,
        y: Double,
        w: Double,
        h: Double,
        text: String,
        size: Double,
        bold: Boolean = false,
        mono: Boolean = false,
    ) {
        val box = slide.createTextBox()
        box.anchor = Rectangle2D.Double(x, y, w, h)
        text.split("\n").forEach { line ->
            val para = box.addNewTextParagraph()
            val run = para.addNewTextRun()
            run.setText(line)
            run.fontSize = size
            run.isBold = bold
            run.setFontColor(FALLBACK_TEXT)
            run.setFontFamily(if (mono) CODE_FONT else CJK_FONT)
        }
    }

    private fun bulletBox(
        slide: XSLFSlide,
        x: Double,
        y: Double,
        w: Double,
        h: Double,
        bullets: List<String>,
        size: Double = DEFAULT_BULLET_SIZE,
    ) {
        val box = slide.createTextBox()
        box.anchor = Rectangle2D.Double(x, y, w, h)
        bullets.forEach { text ->
            val para = box.addNewTextParagraph()
            para.setBullet(true)
            val run = para.addNewTextRun()
            run.setText(text)
            run.fontSize = size
            run.setFontColor(FALLBACK_TEXT)
            run.setFontFamily(CJK_FONT)
        }
    }

    /** Best-effort speaker notes — never fails the render (same contract as [PptxWriter]). */
    private fun attachNotes(ppt: XMLSlideShow, slide: XSLFSlide, notes: String?) {
        if (notes.isNullOrBlank()) return
        try {
            val notesSlide = ppt.getNotesSlide(slide)
            val bodyPh = notesSlide.placeholders.firstOrNull { it.placeholder == Placeholder.BODY }
            if (bodyPh != null) bodyPh.text = notes
        } catch (_: Exception) {
            // ignore
        }
    }

    // Page size is read from the template at render time; defaults to 16:9 points.
    private var slideW: Double = 960.0
    private var slideH: Double = 540.0

    companion object {
        const val BUNDLED_TEMPLATE = "/templates/deck-template.pptx"
        private const val MARGIN = 48.0
        private const val CJK_FONT = "PingFang SC"
        private const val CODE_FONT = "Consolas"
        private val FALLBACK_TEXT = Color(34, 38, 46)

        // Shared content area (below title/subtitle) for the no-placeholder fallback path.
        private const val CONTENT_TOP = 150.0
        private const val CONTENT_BOTTOM_MARGIN = 50.0
        private const val MIN_BULLETS_H = 120.0

        // bodyText, when rendered above the bullets, gets a compact reserved band.
        private const val BODY_TEXT_H = 60.0
        private const val BODY_TEXT_GAP = 10.0
        private const val BODY_TEXT_SIZE = 16.0

        // Prefer a smaller bullet font over silent truncation once a slide is bullet-heavy.
        private const val MANY_BULLETS_THRESHOLD = 5
        private const val DEFAULT_BULLET_SIZE = 20.0
        private const val SMALL_BULLET_SIZE = 17.0

        private val TITLE_KINDS = setOf(Placeholder.TITLE, Placeholder.CENTERED_TITLE)
        private val META_KINDS = setOf(
            Placeholder.DATETIME,
            Placeholder.SLIDE_NUMBER,
            Placeholder.FOOTER,
            Placeholder.HEADER,
        )
    }
}
