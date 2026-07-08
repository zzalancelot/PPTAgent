package com.ppt.agent.business.theme

import java.util.Locale

/**
 * Pure-Kotlin validation of a candidate 5-color theme palette. No LLM, no I/O.
 * Collects **all** violations (does not fail fast) so the retry loop can feed
 * the full list back to the model — same pattern as `OutlineValidator`. See
 * `docs/THEME_COLOR_SPEC.md`.
 */
class ThemeColorValidator {

    /** Returns a list of human-readable violation messages; empty means valid. */
    fun validate(colors: List<String>): List<String> {
        val v = mutableListOf<String>()

        if (colors.size != EXPECTED_SIZE) {
            v += "colors must have exactly $EXPECTED_SIZE entries (found ${colors.size})"
        }

        var wellFormed = colors.size == EXPECTED_SIZE
        colors.forEachIndexed { i, c ->
            if (!HEX_PATTERN.matches(c)) {
                v += "colors[$i] ('$c') must match #RRGGBB (a '#' followed by 6 hex digits)"
                wellFormed = false
            }
        }

        // The checks below assume exactly 5 well-formed #RRGGBB strings; running them on
        // malformed input would either be meaningless or crash while parsing hex digits.
        if (!wellFormed) return v

        if (colors.toSet().size == 1) {
            v += "all 5 colors are identical (${colors.first()}) — not a palette"
        }

        if (colors == DEFAULT_RENDERER_COLORS) {
            v += "colors must not be the current renderer's hard-coded default stack ($DEFAULT_RENDERER_COLORS)"
        }

        val luminances = colors.map { relativeLuminance(it) }
        val backgroundLum = luminances[0]
        val textPrimaryLum = luminances[4]
        if (backgroundLum < DARK_BG_THRESHOLD && textPrimaryLum <= LIGHT_TEXT_THRESHOLD) {
            v += "textPrimary (index 4, luminance=${fmt(textPrimaryLum)}) should be > " +
                "$LIGHT_TEXT_THRESHOLD for readability on a dark background " +
                "(index 0, luminance=${fmt(backgroundLum)})"
        } else if (backgroundLum > LIGHT_BG_THRESHOLD && textPrimaryLum >= DARK_TEXT_THRESHOLD) {
            v += "textPrimary (index 4, luminance=${fmt(textPrimaryLum)}) should be < " +
                "$DARK_TEXT_THRESHOLD for readability on a light background " +
                "(index 0, luminance=${fmt(backgroundLum)})"
        }

        val avgSaturation = colors.map { hslSaturation(it) }.average()
        if (avgSaturation > MAX_AVG_SATURATION) {
            v += "average HSL saturation ${fmt(avgSaturation)} exceeds the Morandi ceiling of " +
                "$MAX_AVG_SATURATION (colors look neon/saturated rather than muted)"
        }

        return v
    }

    /** WCAG-style relative luminance in `[0, 1]`, from an `#RRGGBB` hex string. */
    private fun relativeLuminance(hex: String): Double {
        val (r, g, b) = rgb(hex)
        fun channel(c: Int): Double {
            val s = c / 255.0
            return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b)
    }

    /** HSL saturation in `[0, 1]`, from an `#RRGGBB` hex string. */
    private fun hslSaturation(hex: String): Double {
        val (r, g, b) = rgb(hex)
        val rf = r / 255.0
        val gf = g / 255.0
        val bf = b / 255.0
        val max = maxOf(rf, gf, bf)
        val min = minOf(rf, gf, bf)
        if (max == min) return 0.0
        val lightness = (max + min) / 2.0
        val delta = max - min
        return if (lightness > 0.5) delta / (2.0 - max - min) else delta / (max + min)
    }

    private fun rgb(hex: String): Triple<Int, Int, Int> {
        val clean = hex.removePrefix("#")
        val r = clean.substring(0, 2).toInt(16)
        val g = clean.substring(2, 4).toInt(16)
        val b = clean.substring(4, 6).toInt(16)
        return Triple(r, g, b)
    }

    private fun fmt(value: Double): String = String.format(Locale.ROOT, "%.2f", value)

    private companion object {
        const val EXPECTED_SIZE = 5
        const val MAX_AVG_SATURATION = 0.45
        const val DARK_BG_THRESHOLD = 0.35
        const val LIGHT_TEXT_THRESHOLD = 0.55
        const val LIGHT_BG_THRESHOLD = 0.65
        const val DARK_TEXT_THRESHOLD = 0.45

        val HEX_PATTERN = Regex("^#[0-9A-Fa-f]{6}$")

        // Current PptxWriter hard-coded stack (BG, BG-again, ACCENT, TEXT_SUBTLE, TEXT_LIGHT) —
        // reject an LLM response that just echoes the status quo the whole feature exists to replace.
        val DEFAULT_RENDERER_COLORS = listOf("#1E1E2E", "#1E1E2E", "#89B4FA", "#A6ADC8", "#CDD6F4")
    }
}
