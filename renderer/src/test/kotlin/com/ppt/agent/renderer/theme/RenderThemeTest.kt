package com.ppt.agent.renderer.theme

import com.ppt.agent.renderer.layout.SlideLayoutKind
import com.ppt.agent.renderer.model.DeckDocument
import com.ppt.agent.renderer.model.DeckMeta
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class RenderThemeTest {

    private val morandi = listOf("#2B2A28", "#3A3835", "#8C7A6B", "#6E6A63", "#E8E3DC")

    @Test
    fun parsesFiveColorPaletteFromMeta() {
        val theme = RenderTheme.fromMeta(DeckMeta("t", 1, "zh", emptyMap(), morandi))
        assertEquals(43, theme.background.red)
        assertEquals(42, theme.background.green)
        assertEquals(40, theme.background.blue)
        assertEquals(140, theme.accent.red)
        assertEquals(232, theme.textPrimary.red)
    }

    @Test
    fun sectionDividerUsesSurfaceBackground() {
        val theme = RenderTheme.fromMeta(DeckMeta("t", 1, "zh", emptyMap(), morandi))
        assertNotEquals(theme.slideBackground(SlideLayoutKind.BULLETS), theme.slideBackground(SlideLayoutKind.SECTION_DIVIDER))
        assertEquals(theme.surface, theme.slideBackground(SlideLayoutKind.SECTION_DIVIDER))
        assertEquals(theme.background, theme.slideBackground(SlideLayoutKind.BULLETS))
    }

    @Test
    fun fallsBackToDefaultWhenThemeColorsMissing() {
        val theme = RenderTheme.fromMeta(DeckMeta("t", 1, "zh", emptyMap(), null))
        assertEquals(RenderTheme.DEFAULT.background, theme.background)
    }

    @Test
    fun fallsBackToDefaultWhenThemeColorsWrongSize() {
        val theme = RenderTheme.fromMeta(DeckMeta("t", 1, "zh", emptyMap(), listOf("#111111")))
        assertEquals(RenderTheme.DEFAULT.background, theme.background)
    }
}
