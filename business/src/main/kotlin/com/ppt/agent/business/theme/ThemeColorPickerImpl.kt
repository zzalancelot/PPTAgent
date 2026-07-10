package com.ppt.agent.business.theme

import com.ppt.agent.business.outline.OutlineJson
import com.ppt.agent.business.scenario.DeckStance
import com.ppt.agent.framework.ChatMessage
import com.ppt.agent.framework.GatewayModel
import com.ppt.agent.framework.Json
import com.ppt.agent.llm.adapter.LlmAdapter
import java.nio.charset.StandardCharsets

/**
 * Default [ThemeColorPicker]. Owns the entire retry orchestration for the
 * theme-color call — the `LlmAdapter` is a blind passthrough. Mirrors
 * `OutlinePlannerImpl`'s retry shape.
 *
 * Retry policy (business layer only):
 *  - At most [MAX_ATTEMPTS] LLM calls (1 initial + up to 2 retries).
 *  - `max_tokens` is flat at [MAX_TOKENS] — a 5-entry color array is tiny, no
 *    token ladder needed.
 *  - On LLM failure / truncated / unparseable response: retry (same budget).
 *  - On validation failure: append the violations as a `User` message and
 *    retry within the same budget, **without** bumping tokens.
 *  - All attempts failing yields `Err` ending in [ThemeColorError.ExhaustedRetries].
 */
class ThemeColorPickerImpl(
    private val llmAdapter: LlmAdapter,
    private val validator: ThemeColorValidator = ThemeColorValidator(),
) : ThemeColorPicker {

    override fun pick(outline: OutlineJson, stance: DeckStance?, model: GatewayModel): ThemeColorResult {
        val errors = mutableListOf<ThemeColorError>()
        var messages = initialMessages(outline, stance)
        var lastError = "no attempts made"
        val overrides = mapOf("max_tokens" to MAX_TOKENS.toString())

        repeat(MAX_ATTEMPTS) { i ->
            val attempt = i + 1

            val response = try {
                llmAdapter.chat(messages, emptyList(), model, overrides)
            } catch (e: Exception) {
                val message = e.message ?: "llm call failed"
                errors += ThemeColorError.LlmFailure(message)
                lastError = "LlmFailure: $message"
                return@repeat // retry, same budget
            }

            val text = response.text
            val parsed = Json.parseFirstObject(text)
            if (isTruncated(text, parsed)) {
                errors += ThemeColorError.InvalidJson("truncated or unparseable response", attempt)
                lastError = "InvalidJson: truncated or unparseable response at attempt $attempt"
                return@repeat
            }

            val colors = try {
                toColors(parsed!!)
            } catch (e: Exception) {
                val message = e.message ?: "could not map JSON to a colors array"
                errors += ThemeColorError.InvalidJson(message, attempt)
                lastError = "InvalidJson: $message"
                return@repeat
            }

            val violations = validator.validate(colors)
            if (violations.isEmpty()) {
                return ThemeColorResult.Ok(ThemePalette(colors))
            }

            errors += ThemeColorError.ValidationFailed(violations, attempt)
            lastError = "ValidationFailed: ${violations.joinToString("; ")}"
            // Do NOT bump tokens: append feedback and retry within the same budget.
            messages = messages + ChatMessage.User(validationFeedback(violations))
        }

        errors += ThemeColorError.ExhaustedRetries(MAX_ATTEMPTS, lastError)
        return ThemeColorResult.Err(errors)
    }

    /** True when the response looks incomplete / unparseable and should be retried. */
    private fun isTruncated(text: String?, parsed: Map<String, Any>?): Boolean {
        if (text.isNullOrBlank()) return true
        // Json.parseFirstObject is already tolerant of leading/trailing noise (e.g. ``` fences).
        // If it found a JSON object, treat it as complete even if extra characters follow.
        if (parsed == null) return true
        return false
    }

    private fun toColors(parsed: Map<String, Any>): List<String> {
        val raw = parsed["colors"] ?: error("missing 'colors'")
        val list = raw as? List<*> ?: error("'colors' must be an array")
        return list.map { it as? String ?: error("'colors' entries must be strings") }
    }

    private fun initialMessages(outline: OutlineJson, stance: DeckStance? = null): List<ChatMessage> {
        val system = loadPrompt(SYSTEM_PROMPT)
        val sections = outline.sections.map { SectionSummary(it.id, it.title, it.purpose) }
        val user = loadPrompt(USER_PROMPT)
            .replace("{topic}", outline.meta.topic)
            .replace("{audience}", outline.meta.audience)
            .replace("{tone}", outline.meta.tone)
            .replace("{narrative_arc}", outline.meta.narrativeArc)
            .replace("{one_liner}", outline.meta.oneLiner)
            .replace("{language}", outline.meta.language)
            .replace("{storyline_json}", Json.toJson(outline.storyline))
            .replace("{sections_json}", Json.toJson(sections))

        val stanceAddendum = if (stance != null) {
            "\ncolor_mood: ${stance.colorMood}\nvoice_tone: ${stance.voiceTone}"
        } else {
            ""
        }

        return listOf(ChatMessage.System(system), ChatMessage.User(user + stanceAddendum))
    }

    private fun validationFeedback(violations: List<String>): String = buildString {
        appendLine(
            "The previous color palette JSON failed validation. Fix ALL of the following and return " +
                "corrected JSON only (no commentary):",
        )
        violations.forEach { appendLine("- $it") }
    }.trim()

    private fun loadPrompt(resource: String): String {
        val stream = javaClass.getResourceAsStream(resource)
            ?: error("Missing prompt resource: $resource")
        return stream.use { it.readBytes().toString(StandardCharsets.UTF_8) }
    }

    /** `sections_json` projection: structure only, no per-slide detail. */
    private data class SectionSummary(val id: String, val title: String, val purpose: String)

    companion object {
        const val MAX_ATTEMPTS = 3
        const val MAX_TOKENS = 512

        private const val SYSTEM_PROMPT = "/prompts/theme_color_system.txt"
        private const val USER_PROMPT = "/prompts/theme_color_user.txt"
    }
}
