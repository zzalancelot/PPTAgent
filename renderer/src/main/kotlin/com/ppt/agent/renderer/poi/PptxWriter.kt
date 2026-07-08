package com.ppt.agent.renderer.poi

import com.ppt.agent.renderer.layout.SlideLayoutKind
import com.ppt.agent.renderer.layout.SlideLayoutMapper
import com.ppt.agent.renderer.model.DeckDocument
import com.ppt.agent.renderer.model.SlideDocument
import org.apache.poi.sl.usermodel.Placeholder
import org.apache.poi.sl.usermodel.TextParagraph.TextAlign
import org.apache.poi.xslf.usermodel.XMLSlideShow
import org.apache.poi.xslf.usermodel.XSLFSlide
import org.apache.poi.xslf.usermodel.XSLFTextBox
import java.awt.Color
import java.awt.Dimension
import java.awt.geom.Rectangle2D
import java.nio.file.Files
import java.nio.file.Path

/**
 * Renders a [DeckDocument] to a `.pptx` file using Apache POI (XSLF). Draws
 * every slide from scratch — no external template. Programmatic dark 16:9 theme
 * with light text and one accent color; CJK-friendly fonts. Speaker notes are
 * attached (best effort) when present.
 */
internal class PptxWriter : DeckRenderer {

    override fun write(deck: DeckDocument, output: Path) {
        val slides = deck.slides.orEmpty()
        val ppt = XMLSlideShow()
        try {
            // Widescreen 16:9 = 13.333in × 7.5in → points (72pt/in) = 960 × 540.
            ppt.pageSize = Dimension(SLIDE_W.toInt(), SLIDE_H.toInt())

            slides.sortedBy { it.index ?: Int.MAX_VALUE }.forEach { slide ->
                val kind = SlideLayoutMapper.mapOrNull(slide.slideType ?: "") ?: SlideLayoutKind.BULLETS
                renderSlide(ppt, slide, kind)
            }

            val parent = output.toAbsolutePath().parent
            if (parent != null) Files.createDirectories(parent)
            Files.newOutputStream(output).use { ppt.write(it) }
        } finally {
            ppt.close()
        }
    }

    private fun renderSlide(ppt: XMLSlideShow, slide: SlideDocument, kind: SlideLayoutKind) {
        val poiSlide = ppt.createSlide()
        paintBackground(poiSlide)

        when (kind) {
            SlideLayoutKind.TITLE -> renderCentered(poiSlide, slide, titleColor = TEXT_LIGHT, titleSize = 44.0)
            SlideLayoutKind.SECTION_DIVIDER -> renderCentered(poiSlide, slide, titleColor = ACCENT, titleSize = 40.0)
            SlideLayoutKind.AGENDA, SlideLayoutKind.BULLETS -> renderBullets(poiSlide, slide)
            SlideLayoutKind.CLOSING -> renderBullets(poiSlide, slide, titleColor = ACCENT)
            SlideLayoutKind.TWO_COLUMN -> renderTwoColumn(poiSlide, slide)
            SlideLayoutKind.BODY_TEXT -> renderBodyText(poiSlide, slide)
        }

        attachNotes(ppt, poiSlide, slide.speakerNotes)
    }

    // ---- layouts ----

    private fun renderCentered(slide: XSLFSlide, doc: SlideDocument, titleColor: Color, titleSize: Double) {
        val title = box(slide, MARGIN, 200.0, SLIDE_W - 2 * MARGIN, 120.0)
        addLine(title, doc.title.orEmpty(), titleSize, titleColor, bold = true, align = TextAlign.CENTER, font = CJK)

        val sub = doc.subtitle
        if (!sub.isNullOrBlank()) {
            val subBox = box(slide, MARGIN, 330.0, SLIDE_W - 2 * MARGIN, 80.0)
            addLine(subBox, sub, 22.0, TEXT_SUBTLE, bold = false, align = TextAlign.CENTER, font = CJK)
        }
    }

    private fun renderBullets(slide: XSLFSlide, doc: SlideDocument, titleColor: Color = TEXT_LIGHT) {
        addTitle(slide, doc.title.orEmpty(), titleColor)
        doc.subtitle?.takeIf { it.isNotBlank() }?.let { subtitleUnderTitle(slide, it) }

        // bodyText (when present) sits between subtitle and bullets, in a muted, smaller font —
        // it never replaces the bullets, it bridges into them.
        var y = CONTENT_TOP
        val bodyText = doc.bodyText
        if (!bodyText.isNullOrBlank()) {
            val bodyBox = box(slide, MARGIN, y, SLIDE_W - 2 * MARGIN, BODY_TEXT_H)
            addLine(bodyBox, bodyText, BODY_TEXT_SIZE, TEXT_SUBTLE, font = CJK)
            y += BODY_TEXT_H + BODY_TEXT_GAP
        }

        val bullets = doc.bullets.orEmpty().filter { it.isNotBlank() }
        if (bullets.isEmpty()) return

        val bodyHeight = (CONTENT_TOP + CONTENT_H - y).coerceAtLeast(MIN_BULLETS_H)
        val body = box(slide, MARGIN, y, SLIDE_W - 2 * MARGIN, bodyHeight)
        // Prefer a smaller font over silently truncating when there's more to fit.
        val size = if (bullets.size > MANY_BULLETS_THRESHOLD) SMALL_BULLET_SIZE else DEFAULT_BULLET_SIZE
        bullets.forEachIndexed { i, text ->
            addBullet(body, text, size, TEXT_BODY, first = i == 0)
        }
    }

    private fun renderTwoColumn(slide: XSLFSlide, doc: SlideDocument) {
        addTitle(slide, doc.title.orEmpty(), TEXT_LIGHT)
        val bullets = doc.bullets.orEmpty().filter { it.isNotBlank() }
        val mid = (bullets.size + 1) / 2
        val left = bullets.take(mid)
        val right = bullets.drop(mid)
        val size = if (bullets.size > MANY_BULLETS_THRESHOLD) SMALL_BULLET_SIZE else DEFAULT_BULLET_SIZE

        val colW = (SLIDE_W - 3 * MARGIN) / 2
        val leftBox = box(slide, MARGIN, CONTENT_TOP, colW, CONTENT_H)
        left.forEachIndexed { i, t -> addBullet(leftBox, t, size, TEXT_BODY, first = i == 0) }

        val rightBox = box(slide, MARGIN * 2 + colW, CONTENT_TOP, colW, CONTENT_H)
        right.forEachIndexed { i, t -> addBullet(rightBox, t, size, TEXT_BODY, first = i == 0) }
    }

    private fun renderBodyText(slide: XSLFSlide, doc: SlideDocument) {
        addTitle(slide, doc.title.orEmpty(), TEXT_LIGHT)
        val isCode = doc.slideType == "code_or_demo"
        val font = if (isCode) CODE else CJK
        val bodyText = doc.bodyText
        val fromBullets = doc.bullets.orEmpty().filter { it.isNotBlank() }.joinToString("\n")
        val text = when {
            !bodyText.isNullOrBlank() -> bodyText
            fromBullets.isNotBlank() -> fromBullets
            else -> doc.subtitle.orEmpty()
        }

        val body = box(slide, MARGIN, 150.0, SLIDE_W - 2 * MARGIN, 340.0)
        text.split("\n").forEachIndexed { i, line ->
            addLine(body, line, if (isCode) 16.0 else 20.0, TEXT_BODY, font = font, newParagraph = i > 0)
        }
    }

    // ---- primitives ----

    private fun addTitle(slide: XSLFSlide, title: String, color: Color) {
        val box = box(slide, MARGIN, MARGIN, SLIDE_W - 2 * MARGIN, 90.0)
        addLine(box, title, 30.0, color, bold = true, font = CJK)
    }

    private fun subtitleUnderTitle(slide: XSLFSlide, subtitle: String) {
        val box = box(slide, MARGIN, 110.0, SLIDE_W - 2 * MARGIN, 40.0)
        addLine(box, subtitle, 18.0, TEXT_SUBTLE, font = CJK)
    }

    private fun paintBackground(slide: XSLFSlide) {
        slide.background.setFillColor(BG)
    }

    private fun box(slide: XSLFSlide, x: Double, y: Double, w: Double, h: Double): XSLFTextBox {
        val box = slide.createTextBox()
        box.anchor = Rectangle2D.Double(x, y, w, h)
        return box
    }

    /** Appends a plain line to [box], as a new run in the last paragraph or a fresh paragraph. */
    private fun addLine(
        box: XSLFTextBox,
        text: String,
        size: Double,
        color: Color,
        bold: Boolean = false,
        align: TextAlign = TextAlign.LEFT,
        font: String = CJK,
        newParagraph: Boolean = false,
    ) {
        val para = if (newParagraph || box.textParagraphs.isEmpty()) box.addNewTextParagraph() else box.textParagraphs.last()
        para.textAlign = align
        val run = para.addNewTextRun()
        run.setText(text)
        run.fontSize = size
        run.setFontColor(color)
        run.isBold = bold
        run.setFontFamily(font)
    }

    private fun addBullet(box: XSLFTextBox, text: String, size: Double, color: Color, first: Boolean) {
        val para = box.addNewTextParagraph()
        para.setBullet(true)
        para.textAlign = TextAlign.LEFT
        if (!first) para.setSpaceBefore(6.0)
        val run = para.addNewTextRun()
        run.setText(text)
        run.fontSize = size
        run.setFontColor(color)
        run.setFontFamily(CJK)
    }

    /** Best-effort speaker notes. Never fails the render if the notes slide can't be built. */
    private fun attachNotes(ppt: XMLSlideShow, slide: XSLFSlide, notes: String?) {
        if (notes.isNullOrBlank()) return
        try {
            val notesSlide = ppt.getNotesSlide(slide)
            val bodyPh = notesSlide.placeholders.firstOrNull { it.placeholder == Placeholder.BODY }
            if (bodyPh != null) {
                bodyPh.text = notes
            }
        } catch (_: Exception) {
            // Notes are a nice-to-have; ignore any POI hiccup here.
        }
    }

    companion object {
        // Points (1/72 inch). 13.333in × 7.5in widescreen.
        private const val SLIDE_W = 960.0
        private const val SLIDE_H = 540.0
        private const val MARGIN = 48.0

        // Shared content area (below title/subtitle) for BULLETS / TWO_COLUMN layouts.
        private const val CONTENT_TOP = 150.0
        private const val CONTENT_H = 340.0
        private const val MIN_BULLETS_H = 120.0

        // bodyText, when rendered above the bullets, gets a compact reserved band.
        private const val BODY_TEXT_H = 60.0
        private const val BODY_TEXT_GAP = 10.0
        private const val BODY_TEXT_SIZE = 16.0

        // Prefer a smaller bullet font over silent truncation once a slide is bullet-heavy.
        private const val MANY_BULLETS_THRESHOLD = 5
        private const val DEFAULT_BULLET_SIZE = 20.0
        private const val SMALL_BULLET_SIZE = 17.0

        private val BG = Color(30, 30, 46)
        private val TEXT_LIGHT = Color(245, 245, 245)
        private val TEXT_BODY = Color(205, 214, 244)
        private val TEXT_SUBTLE = Color(166, 173, 200)
        private val ACCENT = Color(137, 180, 250)

        private const val CJK = "PingFang SC"
        private const val CODE = "Menlo"
    }
}
