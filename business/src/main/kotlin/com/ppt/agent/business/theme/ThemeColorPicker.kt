package com.ppt.agent.business.theme

import com.ppt.agent.business.outline.OutlineJson
import com.ppt.agent.framework.GatewayModel

/**
 * Picks a 5-color Morandi-style theme palette tailored to a deck's
 * [OutlineJson] — one LLM call per deck, run after the outline is ready and
 * before render. No parallelism: one palette per deck. See
 * `docs/THEME_COLOR_SPEC.md`.
 */
interface ThemeColorPicker {
    fun pick(outline: OutlineJson, model: GatewayModel = GatewayModel.DEEPSEEK_FLASH): ThemeColorResult
}

sealed class ThemeColorResult {
    data class Ok(val palette: ThemePalette) : ThemeColorResult()
    data class Err(val errors: List<ThemeColorError>) : ThemeColorResult()
}

/**
 * Five Morandi colors, each `#RRGGBB`. Index semantics are fixed — the array
 * order below is the on-wire contract with the LLM and (in a follow-up task)
 * the renderer:
 *
 * | Index | Role         | Usage (future renderer)                            |
 * |-------|--------------|-----------------------------------------------------|
 * | 0     | background   | Slide background fill                               |
 * | 1     | surface      | Cards, secondary panels, agenda blocks              |
 * | 2     | accent       | Titles, section dividers, key highlights            |
 * | 3     | accentMuted  | Subtitles, borders, decorative shapes               |
 * | 4     | textPrimary  | Body bullets and main readable text on `background` |
 */
data class ThemePalette(val colors: List<String>)

sealed class ThemeColorError {
    data class LlmFailure(val message: String) : ThemeColorError()
    data class InvalidJson(val message: String, val attempt: Int) : ThemeColorError()
    data class ValidationFailed(val violations: List<String>, val attempt: Int) : ThemeColorError()
    data class ExhaustedRetries(val attempts: Int, val lastError: String) : ThemeColorError()
}
