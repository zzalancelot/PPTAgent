package com.ppt.agent.business.outline

import com.ppt.agent.business.input.PptInput
import com.ppt.agent.framework.ChatMessage
import com.ppt.agent.framework.GatewayModel
import com.ppt.agent.framework.Json
import com.ppt.agent.llm.adapter.LlmAdapter
import java.nio.charset.StandardCharsets

/**
 * Default [OutlinePlanner]. Owns the entire retry orchestration for outline
 * generation — the `LlmAdapter` is a blind passthrough.
 *
 * Retry policy (business layer only):
 *  - At most [MAX_ATTEMPTS] LLM calls (1 initial + up to 2 retries).
 *  - On parse failure / detected truncation: bump `max_tokens` via [TOKEN_LADDER]
 *    before the next call.
 *  - On validation failure: append the violations as a `User` message and retry
 *    within the same budget, **without** bumping tokens.
 *  - All attempts failing yields `Err` ending in [OutlineError.ExhaustedRetries].
 */
class OutlinePlannerImpl(
    private val llmAdapter: LlmAdapter,
    private val validator: OutlineValidator = OutlineValidator(),
) : OutlinePlanner {

    override fun plan(input: PptInput, model: GatewayModel): OutlineResult {
        val errors = mutableListOf<OutlineError>()
        var messages = initialMessages(input)
        var tokenIndex = 0
        var lastError = "no attempts made"

        repeat(MAX_ATTEMPTS) { i ->
            val attempt = i + 1
            val maxTokens = TOKEN_LADDER[tokenIndex.coerceAtMost(TOKEN_LADDER.lastIndex)]
            val overrides = mapOf("max_tokens" to maxTokens.toString())

            val response = try {
                llmAdapter.chat(messages, emptyList(), model, overrides)
            } catch (e: Exception) {
                val message = e.message ?: "llm call failed"
                errors += OutlineError.LlmFailure(message)
                lastError = "LlmFailure: $message"
                return@repeat // retry without bumping tokens
            }

            val text = response.text
            val parsed = Json.parseFirstObject(text)
            if (isTruncated(text, parsed)) {
                errors += OutlineError.TruncatedOutput(attempt, maxTokens)
                lastError = "TruncatedOutput at attempt $attempt (max_tokens=$maxTokens)"
                tokenIndex++
                return@repeat
            }

            val outline = try {
                toOutline(parsed!!)
            } catch (e: Exception) {
                val message = e.message ?: "could not map JSON to OutlineJson"
                errors += OutlineError.InvalidJson(message, attempt)
                lastError = "InvalidJson: $message"
                tokenIndex++
                return@repeat
            }

            // Truncation heuristic: fewer slides than requested usually means the
            // model ran out of tokens mid-array.
            if (outline.slides.size < input.slideCount) {
                errors += OutlineError.TruncatedOutput(attempt, maxTokens)
                lastError = "TruncatedOutput: ${outline.slides.size} < ${input.slideCount} slides"
                tokenIndex++
                return@repeat
            }

            val violations = validator.validate(outline, input)
            if (violations.isEmpty()) {
                return OutlineResult.Ok(outline)
            }

            errors += OutlineError.ValidationFailed(violations, attempt)
            lastError = "ValidationFailed: ${violations.joinToString("; ")}"
            // Large decks need more output tokens on later retries (context grows with feedback).
            if (attempt >= 2 && input.slideCount >= 20) {
                tokenIndex = (tokenIndex + 1).coerceAtMost(TOKEN_LADDER.lastIndex)
            }
            messages = messages + ChatMessage.User(validationFeedback(violations))
        }

        errors += OutlineError.ExhaustedRetries(MAX_ATTEMPTS, lastError)
        return OutlineResult.Err(errors)
    }

    /** True when the response looks incomplete / unparseable and should be retried with more tokens. */
    private fun isTruncated(text: String?, parsed: Map<String, Any>?): Boolean {
        if (text.isNullOrBlank()) return true
        if (parsed == null) return true
        if (!text.trim().endsWith("}")) return true
        return false
    }

    private fun toOutline(parsed: Map<String, Any>): OutlineJson {
        val outline = Json.fromJson(Json.toJson(parsed), OutlineJson::class.java)
        // Gson can leave declared-non-null fields null when the JSON omits them;
        // surface that as a mapping failure rather than a later NPE.
        requireNotNull(outline) { "empty outline" }
        requireNotNull(outline.meta) { "missing 'meta'" }
        requireNotNull(outline.slides) { "missing 'slides'" }
        requireNotNull(outline.sections) { "missing 'sections'" }
        requireNotNull(outline.storyline) { "missing 'storyline'" }
        requireNotNull(outline.consistency) { "missing 'consistency'" }
        return outline
    }

    private fun initialMessages(input: PptInput): List<ChatMessage> {
        val system = loadPrompt(SYSTEM_PROMPT)
        val user = loadPrompt(USER_PROMPT)
            .replace("{topic}", input.topic)
            .replace("{brief}", input.brief)
            .replace("{audience}", input.audience)
            .replace("{slide_count}", input.slideCount.toString())
        return listOf(ChatMessage.System(system), ChatMessage.User(user))
    }

    private fun validationFeedback(violations: List<String>): String = buildString {
        appendLine("The previous outline JSON failed validation. Fix ALL of the following and return corrected JSON only (no commentary):")
        violations.forEach { appendLine("- $it") }
        if (violations.any { it.contains("layoutProfile name") || it.contains("split_narrative") || it.contains("timeline_flow") }) {
            appendLine()
            appendLine("Reminder: layoutProfile (split_narrative, timeline_flow, etc.) belongs on sections[] only. slides[].slideType must be content, comparison, timeline, summary, etc.")
        }
        if (violations.any { it.contains("'summary'") || it.contains("consecutive") }) {
            appendLine()
            appendLine("Reminder: exactly 1 summary slide total; break long content runs with comparison/timeline/quote/section_divider.")
        }
    }.trim()

    private fun loadPrompt(resource: String): String {
        val stream = javaClass.getResourceAsStream(resource)
            ?: error("Missing prompt resource: $resource")
        return stream.use { it.readBytes().toString(StandardCharsets.UTF_8) }
    }

    companion object {
        const val MAX_ATTEMPTS = 3
        val TOKEN_LADDER = listOf(8192, 12288, 16384)

        private const val SYSTEM_PROMPT = "/prompts/outline_planner_system.txt"
        private const val USER_PROMPT = "/prompts/outline_planner_user.txt"
    }
}
