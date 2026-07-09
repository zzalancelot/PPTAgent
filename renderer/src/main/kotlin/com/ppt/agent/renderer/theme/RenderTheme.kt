package com.ppt.agent.renderer.theme

import com.ppt.agent.renderer.layout.SlideLayoutKind
import com.ppt.agent.renderer.model.DeckMeta
import java.awt.Color

/**
 * Resolved 5-color Morandi palette for programmatic rendering.
 * Index semantics match `business.theme.ThemePalette` / `docs/THEME_COLOR_SPEC.md`.
 */
internal data class RenderTheme(
    val background: Color,
    val surface: Color,
    val accent: Color,
    val accentMuted: Color,
    val textPrimary: Color,
) {
    /** Main slide background; section dividers use [surface] for visual separation. */
    fun slideBackground(kind: SlideLayoutKind): Color =
        if (kind == SlideLayoutKind.SECTION_DIVIDER) surface else background

    companion object {
        /** Legacy hard-coded stack when deck JSON has no `themeColors`. */
        val DEFAULT = RenderTheme(
            background = Color(30, 30, 46),
            surface = Color(40, 40, 58),
            accent = Color(137, 180, 250),
            accentMuted = Color(166, 173, 200),
            textPrimary = Color(245, 245, 245),
        )

        fun fromMeta(meta: DeckMeta?): RenderTheme {
            val hexes = meta?.themeColors?.filter { it.isNotBlank() } ?: return DEFAULT
            if (hexes.size != 5) return DEFAULT
            return try {
                RenderTheme(
                    background = parseHex(hexes[0]),
                    surface = parseHex(hexes[1]),
                    accent = parseHex(hexes[2]),
                    accentMuted = parseHex(hexes[3]),
                    textPrimary = parseHex(hexes[4]),
                )
            } catch (_: Exception) {
                DEFAULT
            }
        }

        private fun parseHex(hex: String): Color {
            val clean = hex.trim().removePrefix("#")
            require(clean.length == 6) { "invalid hex: $hex" }
            val r = clean.substring(0, 2).toInt(16)
            val g = clean.substring(2, 4).toInt(16)
            val b = clean.substring(4, 6).toInt(16)
            return Color(r, g, b)
        }
    }
}
