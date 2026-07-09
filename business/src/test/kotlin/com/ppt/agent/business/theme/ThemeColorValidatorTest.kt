package com.ppt.agent.business.theme

import kotlin.test.Test
import kotlin.test.assertTrue

class ThemeColorValidatorTest {

    private val validator = ThemeColorValidator()

    /** Dark, low-saturation background/surface/accent set with a light, readable textPrimary. */
    private val validMorandiPalette = listOf("#2B2A28", "#3A3835", "#8C7A6B", "#6E6A63", "#E8E3DC")

    @Test
    fun validFiveColorMorandiPaletteHasNoViolations() {
        assertTrue(validator.validate(validMorandiPalette).isEmpty())
    }

    @Test
    fun fourColorsFailsLengthCheck() {
        val violations = validator.validate(validMorandiPalette.take(4))
        assertTrue(violations.any { it.contains("exactly 5") }, violations.toString())
    }

    @Test
    fun sixColorsFailsLengthCheck() {
        val violations = validator.validate(validMorandiPalette + "#111111")
        assertTrue(violations.any { it.contains("exactly 5") }, violations.toString())
    }

    @Test
    fun badHexValuesAreReported() {
        val colors = listOf("#GGG", "1E1E2E", "#12345", "#ABCDEF", "#123456")
        val violations = validator.validate(colors)
        assertTrue(violations.any { it.contains("colors[0]") && it.contains("#RRGGBB") }, violations.toString())
        assertTrue(violations.any { it.contains("colors[1]") && it.contains("#RRGGBB") }, violations.toString())
        assertTrue(violations.any { it.contains("colors[2]") && it.contains("#RRGGBB") }, violations.toString())
    }

    @Test
    fun allIdenticalColorsIsReported() {
        val violations = validator.validate(List(5) { "#334455" })
        assertTrue(violations.any { it.contains("identical") }, violations.toString())
    }

    @Test
    fun currentRendererDefaultStackIsRejected() {
        val defaults = listOf("#1E1E2E", "#1E1E2E", "#89B4FA", "#A6ADC8", "#CDD6F4")
        val violations = validator.validate(defaults)
        assertTrue(violations.any { it.contains("default") }, violations.toString())
    }

    @Test
    fun darkBackgroundWithDarkTextPrimaryFailsContrastHint() {
        // background (index 0) is very dark; textPrimary (index 4) is also dark — unreadable.
        val colors = listOf("#101010", "#2A2A2A", "#665544", "#554433", "#1A1A1A")
        val violations = validator.validate(colors)
        assertTrue(violations.any { it.contains("textPrimary") }, violations.toString())
    }

    @Test
    fun neonHighSaturationSetFailsMorandiHeuristic() {
        val neon = listOf("#FF0000", "#00FF00", "#0000FF", "#FFFF00", "#FF00FF")
        val violations = validator.validate(neon)
        assertTrue(violations.any { it.contains("saturation") }, violations.toString())
    }
}
