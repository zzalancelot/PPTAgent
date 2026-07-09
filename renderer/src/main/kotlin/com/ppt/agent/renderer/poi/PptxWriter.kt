package com.ppt.agent.renderer.poi

import com.ppt.agent.renderer.layout.Density
import com.ppt.agent.renderer.layout.LayoutProfileResolver
import com.ppt.agent.renderer.layout.LayoutProfileTokens
import com.ppt.agent.renderer.layout.SlideLayoutKind
import com.ppt.agent.renderer.layout.SlideLayoutMapper
import com.ppt.agent.renderer.layout.TitleAlign
import com.ppt.agent.renderer.model.DeckDocument
import com.ppt.agent.renderer.model.SlideDocument
import com.ppt.agent.renderer.theme.RenderTheme
import org.apache.poi.sl.usermodel.Placeholder
import org.apache.poi.sl.usermodel.ShapeType
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
 * every slide from scratch — no external template. Uses an optional 5-color
 * Morandi palette from `meta.themeColors` when present; otherwise falls back
 * to the legacy dark theme. Applies per-chapter [LayoutProfileTokens] for
 * typographic diversity (PROGRAMMATIC mode only).
 */
internal class PptxWriter : DeckRenderer {

    override fun write(deck: DeckDocument, output: Path) {
        val theme = RenderTheme.fromMeta(deck.meta)
        val slides = deck.slides.orEmpty()
        val ppt = XMLSlideShow()
        try {
            ppt.pageSize = Dimension(SLIDE_W.toInt(), SLIDE_H.toInt())

            slides.sortedBy { it.index ?: Int.MAX_VALUE }.forEach { slide ->
                val kind = SlideLayoutMapper.mapOrNull(slide.slideType ?: "") ?: SlideLayoutKind.BULLETS
                val tokens = LayoutProfileResolver.forSlide(deck.meta, slide.sectionId)
                renderSlide(ppt, slide, kind, theme, tokens)
            }

            val parent = output.toAbsolutePath().parent
            if (parent != null) Files.createDirectories(parent)
            Files.newOutputStream(output).use { ppt.write(it) }
        } finally {
            ppt.close()
        }
    }

    private fun renderSlide(
        ppt: XMLSlideShow,
        slide: SlideDocument,
        kind: SlideLayoutKind,
        theme: RenderTheme,
        tokens: LayoutProfileTokens,
    ) {
        val poiSlide = ppt.createSlide()
        paintBackground(poiSlide, theme.slideBackground(kind))

        when (kind) {
            SlideLayoutKind.TITLE ->
                renderCentered(poiSlide, slide, titleColor = theme.textPrimary, titleSize = 44.0, theme = theme, tokens = tokens)
            SlideLayoutKind.SECTION_DIVIDER ->
                renderCentered(poiSlide, slide, titleColor = theme.accent, titleSize = 40.0, theme = theme, tokens = tokens)
            SlideLayoutKind.AGENDA, SlideLayoutKind.BULLETS ->
                renderBullets(poiSlide, slide, theme, tokens = tokens)
            SlideLayoutKind.CLOSING ->
                renderBullets(poiSlide, slide, theme, titleColor = theme.accent, tokens = tokens)
            SlideLayoutKind.TWO_COLUMN ->
                renderTwoColumn(poiSlide, slide, theme, tokens)
            SlideLayoutKind.BODY_TEXT ->
                renderBodyText(poiSlide, slide, theme, tokens)
        }

        // Accent decorations
        if (tokens.leftAccentBar) {
            drawLeftAccentBar(poiSlide, theme)
        }
        if (tokens.topAccentRule) {
            drawTopAccentRule(poiSlide, theme)
        }

        attachNotes(ppt, poiSlide, slide.speakerNotes)
    }

    private fun renderCentered(
        slide: XSLFSlide,
        doc: SlideDocument,
        titleColor: Color,
        titleSize: Double,
        theme: RenderTheme,
        tokens: LayoutProfileTokens,
    ) {
        val scaledTitleSize = titleSize * tokens.titleScale
        val title = box(slide, MARGIN, 200.0, SLIDE_W - 2 * MARGIN, 120.0)
        addLine(title, doc.title.orEmpty(), scaledTitleSize, titleColor, bold = true, align = TextAlign.CENTER, font = CJK)

        val sub = doc.subtitle
        if (!sub.isNullOrBlank()) {
            val subBox = box(slide, MARGIN, 330.0, SLIDE_W - 2 * MARGIN, 80.0)
            addLine(subBox, sub, 22.0 * tokens.bodyScale, theme.accentMuted, bold = false, align = TextAlign.CENTER, font = CJK)
        }
    }

    private fun renderBullets(
        slide: XSLFSlide,
        doc: SlideDocument,
        theme: RenderTheme,
        titleColor: Color = theme.textPrimary,
        tokens: LayoutProfileTokens,
    ) {
        val titleAlign = if (tokens.titleAlign == TitleAlign.CENTER) TextAlign.CENTER else TextAlign.LEFT
        addTitle(slide, doc.title.orEmpty(), titleColor, tokens, titleAlign)
        doc.subtitle?.takeIf { it.isNotBlank() }?.let { subtitleUnderTitle(slide, it, theme, tokens) }

        val contentTop = CONTENT_TOP * tokens.contentTopScale
        var y = contentTop
        val bodyText = doc.bodyText
        if (!bodyText.isNullOrBlank()) {
            val bodyBox = box(slide, MARGIN, y, SLIDE_W - 2 * MARGIN, BODY_TEXT_H)
            addLine(bodyBox, bodyText, BODY_TEXT_SIZE * tokens.bodyScale, theme.accentMuted, font = CJK)
            y += BODY_TEXT_H + BODY_TEXT_GAP
        }

        val bullets = doc.bullets.orEmpty().filter { it.isNotBlank() }
        if (bullets.isEmpty()) return

        val bodyHeight = (contentTop + CONTENT_H - y).coerceAtLeast(MIN_BULLETS_H)
        val body = box(slide, MARGIN, y, SLIDE_W - 2 * MARGIN, bodyHeight)
        val baseSize = if (bullets.size > MANY_BULLETS_THRESHOLD) SMALL_BULLET_SIZE else DEFAULT_BULLET_SIZE
        val size = baseSize * tokens.bodyScale
        val spaceBefore = bulletSpaceBefore(tokens.density)
        bullets.forEachIndexed { i, text ->
            addBullet(body, text, size, theme.textPrimary, first = i == 0, spaceBefore = spaceBefore)
        }
    }

    private fun renderTwoColumn(slide: XSLFSlide, doc: SlideDocument, theme: RenderTheme, tokens: LayoutProfileTokens) {
        val titleAlign = if (tokens.titleAlign == TitleAlign.CENTER) TextAlign.CENTER else TextAlign.LEFT
        addTitle(slide, doc.title.orEmpty(), theme.textPrimary, tokens, titleAlign)
        val bullets = doc.bullets.orEmpty().filter { it.isNotBlank() }
        val mid = (bullets.size + 1) / 2
        val left = bullets.take(mid)
        val right = bullets.drop(mid)
        val baseSize = if (bullets.size > MANY_BULLETS_THRESHOLD) SMALL_BULLET_SIZE else DEFAULT_BULLET_SIZE
        val size = baseSize * tokens.bodyScale
        val contentTop = CONTENT_TOP * tokens.contentTopScale
        val spaceBefore = bulletSpaceBefore(tokens.density)

        val colW = (SLIDE_W - 3 * MARGIN) / 2
        val leftBox = box(slide, MARGIN, contentTop, colW, CONTENT_H)
        left.forEachIndexed { i, t -> addBullet(leftBox, t, size, theme.textPrimary, first = i == 0, spaceBefore = spaceBefore) }

        val rightBox = box(slide, MARGIN * 2 + colW, contentTop, colW, CONTENT_H)
        right.forEachIndexed { i, t -> addBullet(rightBox, t, size, theme.textPrimary, first = i == 0, spaceBefore = spaceBefore) }
    }

    private fun renderBodyText(slide: XSLFSlide, doc: SlideDocument, theme: RenderTheme, tokens: LayoutProfileTokens) {
        val titleAlign = if (tokens.titleAlign == TitleAlign.CENTER) TextAlign.CENTER else TextAlign.LEFT
        addTitle(slide, doc.title.orEmpty(), theme.textPrimary, tokens, titleAlign)
        val isCode = doc.slideType == "code_or_demo"
        val font = if (isCode) CODE else CJK
        val bodyText = doc.bodyText
        val fromBullets = doc.bullets.orEmpty().filter { it.isNotBlank() }.joinToString("\n")
        val text = when {
            !bodyText.isNullOrBlank() -> bodyText
            fromBullets.isNotBlank() -> fromBullets
            else -> doc.subtitle.orEmpty()
        }

        val contentTop = CONTENT_TOP * tokens.contentTopScale
        val body = box(slide, MARGIN, contentTop, SLIDE_W - 2 * MARGIN, 340.0)
        val baseSize = if (isCode) 16.0 else 20.0
        text.split("\n").forEachIndexed { i, line ->
            addLine(body, line, baseSize * tokens.bodyScale, theme.textPrimary, font = font, newParagraph = i > 0)
        }
    }

    private fun addTitle(slide: XSLFSlide, title: String, color: Color, tokens: LayoutProfileTokens, align: TextAlign = TextAlign.LEFT) {
        val box = box(slide, MARGIN, MARGIN, SLIDE_W - 2 * MARGIN, 90.0)
        addLine(box, title, 30.0 * tokens.titleScale, color, bold = true, align = align, font = CJK)
    }

    private fun subtitleUnderTitle(slide: XSLFSlide, subtitle: String, theme: RenderTheme, tokens: LayoutProfileTokens) {
        val box = box(slide, MARGIN, 110.0, SLIDE_W - 2 * MARGIN, 40.0)
        addLine(box, subtitle, 18.0 * tokens.bodyScale, theme.accentMuted, font = CJK)
    }

    private fun bulletSpaceBefore(density: Density): Double = when (density) {
        Density.AIRY -> 10.0
        Density.STANDARD -> 6.0
        Density.DENSE -> 3.0
    }

    /** Draws a thin vertical accent bar on the left edge. */
    private fun drawLeftAccentBar(slide: XSLFSlide, theme: RenderTheme) {
        val bar = slide.createAutoShape()
        bar.setShapeType(ShapeType.RECT)
        bar.anchor = Rectangle2D.Double(0.0, MARGIN, ACCENT_BAR_W, SLIDE_H - 2 * MARGIN)
        bar.setFillColor(theme.accentMuted)
        bar.setLineColor(theme.accentMuted)
    }

    /** Draws a thin horizontal rule under the title region. */
    private fun drawTopAccentRule(slide: XSLFSlide, theme: RenderTheme) {
        val rule = slide.createAutoShape()
        rule.setShapeType(ShapeType.RECT)
        rule.anchor = Rectangle2D.Double(MARGIN, TITLE_RULE_Y, SLIDE_W - 2 * MARGIN, ACCENT_RULE_H)
        rule.setFillColor(theme.accent)
        rule.setLineColor(theme.accent)
    }

    private fun paintBackground(slide: XSLFSlide, color: Color) {
        slide.background.setFillColor(color)
    }

    private fun box(slide: XSLFSlide, x: Double, y: Double, w: Double, h: Double): XSLFTextBox {
        val box = slide.createTextBox()
        box.anchor = Rectangle2D.Double(x, y, w, h)
        return box
    }

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

    private fun addBullet(box: XSLFTextBox, text: String, size: Double, color: Color, first: Boolean, spaceBefore: Double = 6.0) {
        val para = box.addNewTextParagraph()
        para.setBullet(true)
        para.textAlign = TextAlign.LEFT
        if (!first) para.setSpaceBefore(spaceBefore)
        val run = para.addNewTextRun()
        run.setText(text)
        run.fontSize = size
        run.setFontColor(color)
        run.setFontFamily(CJK)
    }

    private fun attachNotes(ppt: XMLSlideShow, slide: XSLFSlide, notes: String?) {
        if (notes.isNullOrBlank()) return
        try {
            val notesSlide = ppt.getNotesSlide(slide)
            val bodyPh = notesSlide.placeholders.firstOrNull { it.placeholder == Placeholder.BODY }
            if (bodyPh != null) {
                bodyPh.text = notes
            }
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val SLIDE_W = 960.0
        private const val SLIDE_H = 540.0
        private const val MARGIN = 48.0
        private const val CONTENT_TOP = 150.0
        private const val CONTENT_H = 340.0
        private const val MIN_BULLETS_H = 120.0
        private const val BODY_TEXT_H = 60.0
        private const val BODY_TEXT_GAP = 10.0
        private const val BODY_TEXT_SIZE = 16.0
        private const val MANY_BULLETS_THRESHOLD = 5
        private const val DEFAULT_BULLET_SIZE = 20.0
        private const val SMALL_BULLET_SIZE = 17.0
        private const val CJK = "PingFang SC"
        private const val CODE = "Menlo"
        private const val ACCENT_BAR_W = 7.0
        private const val ACCENT_RULE_H = 2.0
        private const val TITLE_RULE_Y = 108.0
    }
}
