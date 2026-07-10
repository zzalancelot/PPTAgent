package com.ppt.agent.business.scenario

import com.ppt.agent.business.input.PptInput
import com.ppt.agent.framework.ChatMessage
import com.ppt.agent.framework.GatewayModel
import com.ppt.agent.framework.Json
import com.ppt.agent.llm.adapter.LlmAdapter
import java.nio.charset.StandardCharsets

/**
 * Default [ScenarioPlanner]. Mirrors [ThemeColorPickerImpl] retry shape:
 * max [MAX_ATTEMPTS] calls, flat `max_tokens` at [MAX_TOKENS], parse with
 * [Json.parseFirstObject], validate, retry with feedback on violation.
 */
class ScenarioPlannerImpl(
    private val llmAdapter: LlmAdapter,
    private val validator: ScenarioValidator = ScenarioValidator(),
) : ScenarioPlanner {

    override fun infer(input: PptInput, model: GatewayModel): ScenarioResult {
        val errors = mutableListOf<ScenarioError>()
        var messages = initialMessages(input)
        var lastError = "no attempts made"
        val overrides = mapOf("max_tokens" to MAX_TOKENS.toString())

        repeat(MAX_ATTEMPTS) { i ->
            val attempt = i + 1

            val response = try {
                llmAdapter.chat(messages, emptyList(), model, overrides)
            } catch (e: Exception) {
                val message = e.message ?: "llm call failed"
                errors += ScenarioError.LlmFailure(message)
                lastError = "LlmFailure: $message"
                return@repeat
            }

            val text = response.text
            val parsed = Json.parseFirstObject(text)
            if (text.isNullOrBlank() || parsed == null) {
                errors += ScenarioError.InvalidJson("truncated or unparseable response", attempt)
                lastError = "InvalidJson: truncated or unparseable response at attempt $attempt"
                return@repeat
            }

            val brief = try {
                toBrief(parsed)
            } catch (e: Exception) {
                val message = e.message ?: "could not map JSON to ScenarioBrief"
                errors += ScenarioError.InvalidJson(message, attempt)
                lastError = "InvalidJson: $message"
                return@repeat
            }

            val violations = validator.validate(brief)
            if (violations.isEmpty()) {
                return ScenarioResult.Ok(brief)
            }

            errors += ScenarioError.ValidationFailed(violations, attempt)
            lastError = "ValidationFailed: ${violations.joinToString("; ")}"
            messages = messages + ChatMessage.User(validationFeedback(violations))
        }

        errors += ScenarioError.ExhaustedRetries(MAX_ATTEMPTS, lastError)
        return ScenarioResult.Err(errors)
    }

    private fun toBrief(parsed: Map<String, Any>): ScenarioBrief {
        val json = Json.toJson(parsed)
        return Json.fromJson(json, ScenarioBrief::class.java)
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
        appendLine(
            "The previous ScenarioBrief JSON failed validation. Fix ALL of the following and return " +
                "corrected JSON only (no commentary):",
        )
        violations.forEach { appendLine("- $it") }
    }.trim()

    private fun loadPrompt(resource: String): String {
        val stream = javaClass.getResourceAsStream(resource)
            ?: error("Missing prompt resource: $resource")
        return stream.use { it.readBytes().toString(StandardCharsets.UTF_8) }
    }

    companion object {
        const val MAX_ATTEMPTS = 3
        const val MAX_TOKENS = 2048

        private const val SYSTEM_PROMPT = "/prompts/scenario_planner_system.txt"
        private const val USER_PROMPT = "/prompts/scenario_planner_user.txt"
    }
}
