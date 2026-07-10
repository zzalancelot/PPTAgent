package com.ppt.agent.business.content

import com.ppt.agent.business.input.PptInput
import com.ppt.agent.business.outline.OutlineJson
import com.ppt.agent.business.outline.OutlineSlide
import com.ppt.agent.business.scenario.DeckStance
import com.ppt.agent.framework.ChatMessage
import com.ppt.agent.framework.GatewayModel
import com.ppt.agent.framework.Json
import com.ppt.agent.llm.adapter.LlmAdapter
import java.nio.charset.StandardCharsets
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore

/**
 * Default [SlideContentGenerator]. Owns the entire orchestration for slide
 * content generation — the `LlmAdapter` is a blind passthrough.
 *
 * Execution model:
 *  - One LLM call per slide; all slides submitted at once, at most
 *    [ContentGenerationConfig.MAX_PARALLEL_SLIDES] in flight (a [Semaphore]).
 *  - Model per slide is fixed by its section (see [ModelAssignmentPolicy]).
 *
 * Per-slide retry policy (business layer only):
 *  - Up to [PRIMARY_ATTEMPTS] attempts on the section-assigned model, escalating
 *    `max_tokens` via [PRIMARY_TOKEN_LADDER] on parse/truncation.
 *  - If all fail, one **alternate** model (next in the pool, round-robin) gets up
 *    to [FALLBACK_ATTEMPTS] attempts with [FALLBACK_TOKEN_LADDER].
 *  - Validation failures append a `User` feedback message and retry within the
 *    same budget **without** bumping tokens.
 *  - Any slide still failing fails the whole job (no partial deck in v1).
 */
class SlideContentGeneratorImpl(
    private val llmAdapter: LlmAdapter,
    private val validator: SlideContentValidator = SlideContentValidator(),
) : SlideContentGenerator {

    override fun generate(
        input: PptInput,
        outline: OutlineJson,
        stance: DeckStance?,
        modelPool: List<GatewayModel>,
    ): ContentResult {
        require(modelPool.isNotEmpty()) { "model pool must not be empty" }

        val assignments = ModelAssignmentPolicy.assignBySection(outline, modelPool)
        val semaphore = Semaphore(ContentGenerationConfig.MAX_PARALLEL_SLIDES)
        val threads = outline.slides.size.coerceIn(1, 32)
        val executor = Executors.newFixedThreadPool(threads)

        val outcomes: List<SlideOutcome> = try {
            val futures = outline.slides.map { slide ->
                executor.submit(
                    Callable {
                        semaphore.acquire()
                        try {
                            generateOneSlide(input, outline, slide, assignments, modelPool, stance)
                        } finally {
                            semaphore.release()
                        }
                    },
                )
            }
            futures.map { it.get() }
        } finally {
            executor.shutdownNow()
        }

        val failures = outcomes.filterIsInstance<SlideOutcome.Failed>()
        if (failures.isNotEmpty()) {
            val errors = mutableListOf<ContentError>()
            failures.sortedBy { it.index }.forEach {
                errors += ContentError.SlideFailed(it.index, it.sectionId, it.message)
            }
            val failedIndices = failures.map { it.index }.sorted()
            errors += ContentError.PartialFailure(failedIndices, "${failedIndices.size} slide(s) failed to generate")
            return ContentResult.Err(errors)
        }

        val slides = outcomes.filterIsInstance<SlideOutcome.Succeeded>()
            .map { it.content }
            .sortedBy { it.index }

        val deck = SlideDeckContent(
            meta = ContentMeta(
                topic = outline.meta.topic,
                slideCount = slides.size,
                language = outline.meta.language,
                modelsUsed = assignments.mapValues { it.value.id },
            ),
            slides = slides,
        )

        val deckViolations = validator.validateDeck(deck, outline)
        if (deckViolations.isNotEmpty()) {
            return ContentResult.Err(
                listOf(ContentError.PartialFailure(emptyList(), "deck validation failed: ${deckViolations.joinToString("; ")}")),
            )
        }
        return ContentResult.Ok(deck)
    }

    /** Runs the assigned model then a single alternate-model fallback for one slide. */
    private fun generateOneSlide(
        input: PptInput,
        outline: OutlineJson,
        slide: OutlineSlide,
        assignments: Map<String, GatewayModel>,
        pool: List<GatewayModel>,
        stance: DeckStance? = null,
    ): SlideOutcome {
        val assigned = assignments.getValue(slide.sectionId)
        val baseMessages = buildMessages(outline, slide, stance)

        val primary = attemptWithModel(baseMessages, assigned, slide, PRIMARY_ATTEMPTS, PRIMARY_TOKEN_LADDER)
        if (primary is AttemptResult.Success) return SlideOutcome.Succeeded(primary.content)

        val alternate = pickAlternate(assigned, pool)
        if (alternate != null) {
            val fallback = attemptWithModel(baseMessages, alternate, slide, FALLBACK_ATTEMPTS, FALLBACK_TOKEN_LADDER)
            if (fallback is AttemptResult.Success) return SlideOutcome.Succeeded(fallback.content)
            return SlideOutcome.Failed(
                slide.index,
                slide.sectionId,
                "primary(${assigned.id}): ${(primary as AttemptResult.Failure).lastError}; " +
                    "fallback(${alternate.id}): ${(fallback as AttemptResult.Failure).lastError}",
            )
        }
        return SlideOutcome.Failed(
            slide.index,
            slide.sectionId,
            "primary(${assigned.id}): ${(primary as AttemptResult.Failure).lastError}; no alternate model available",
        )
    }

    /** A bounded sequence of attempts against a single [model], threading the token ladder + feedback. */
    private fun attemptWithModel(
        baseMessages: List<ChatMessage>,
        model: GatewayModel,
        slide: OutlineSlide,
        maxAttempts: Int,
        tokenLadder: List<Int>,
    ): AttemptResult {
        var messages = baseMessages
        var tokenIndex = 0
        var lastError = "no attempts made"

        repeat(maxAttempts) {
            val maxTokens = tokenLadder[tokenIndex.coerceAtMost(tokenLadder.lastIndex)]
            val overrides = mapOf("max_tokens" to maxTokens.toString())

            val response = try {
                llmAdapter.chat(messages, emptyList(), model, overrides)
            } catch (e: Exception) {
                lastError = "LlmFailure: ${e.message ?: "llm call failed"}"
                return@repeat // transient: retry without bumping tokens
            }

            val text = response.text
            val parsed = Json.parseFirstObject(text)
            if (isTruncated(text, parsed)) {
                lastError = "Truncated (max_tokens=$maxTokens)"
                tokenIndex++
                return@repeat
            }

            val content = try {
                toSlideContent(parsed!!, slide)
            } catch (e: Exception) {
                lastError = "InvalidJson: ${e.message ?: "could not map slide JSON"}"
                tokenIndex++
                return@repeat
            }

            val violations = validator.validateSlide(content, slide)
            if (violations.isEmpty()) return AttemptResult.Success(content)

            lastError = "ValidationFailed: ${violations.joinToString("; ")}"
            messages = messages + ChatMessage.User(validationFeedback(violations)) // no token bump
        }
        return AttemptResult.Failure(lastError)
    }

    /** The next model after [assigned] in [pool] (round-robin), or null if the pool has no other model. */
    private fun pickAlternate(assigned: GatewayModel, pool: List<GatewayModel>): GatewayModel? {
        if (pool.size < 2) return null
        val idx = pool.indexOf(assigned)
        if (idx < 0) return pool.first { it != assigned }
        val next = pool[(idx + 1) % pool.size]
        return if (next == assigned) null else next
    }

    /** Truncation / parse-failure heuristics. Parsed object ⇒ not truncated. */
    private fun isTruncated(text: String?, parsed: Map<String, Any>?): Boolean {
        if (text.isNullOrBlank()) return true
        if (parsed == null) return true
        return false
    }

    private fun toSlideContent(parsed: Map<String, Any>, slide: OutlineSlide): SlideContent {
        val payload = Json.fromJson(Json.toJson(parsed), LlmSlidePayload::class.java)
        requireNotNull(payload) { "empty slide payload" }
        val title = payload.title
        requireNotNull(title) { "missing 'title'" }
        return SlideContent(
            index = payload.index ?: slide.index,
            sectionId = slide.sectionId,
            slideType = slide.slideType,
            title = title,
            subtitle = payload.subtitle,
            bullets = payload.bullets ?: emptyList(),
            speakerNotes = payload.speakerNotes,
            bodyText = payload.bodyText,
        )
    }

    private fun buildMessages(outline: OutlineJson, slide: OutlineSlide, stance: DeckStance? = null): List<ChatMessage> {
        val system = loadPrompt(SYSTEM_PROMPT)
        val prev = outline.slides.firstOrNull { it.index == slide.index - 1 }
        val next = outline.slides.firstOrNull { it.index == slide.index + 1 }

        val user = loadPrompt(USER_PROMPT)
            .replace("{topic}", outline.meta.topic)
            .replace("{audience}", outline.meta.audience)
            .replace("{tone}", outline.meta.tone)
            .replace("{narrative_arc}", outline.meta.narrativeArc)
            .replace("{key_terms_json}", Json.toJson(outline.consistency.keyTerms))
            .replace("{slide_json}", Json.toJson(slide))
            .replace("{prev_slide_hint}", slideHint(prev))
            .replace("{next_slide_hint}", slideHint(next))
            .replace("{consistency_note}", consistencyNote(outline))

        val stanceBlock = if (stance != null) {
            buildString {
                appendLine()
                appendLine("## Presentation stance (scenario)")
                appendLine("scenario: ${stance.label}")
                appendLine("voice: ${stance.voiceTone}")
                appendLine("audience frame: ${stance.audienceFrame}")
                appendLine("narrative arc: ${stance.narrativeArc}")
                appendLine()
                append("Write slide copy that matches this voice. Do not change slide structure — outline fixes slideType and titles.")
            }
        } else {
            ""
        }

        return listOf(ChatMessage.System(system), ChatMessage.User(user + stanceBlock))
    }

    private fun slideHint(slide: OutlineSlide?): String =
        if (slide == null) "(none)" else "\"${slide.title}\" — ${slide.intent}"

    private fun consistencyNote(outline: OutlineJson): String {
        val c = outline.consistency
        return buildString {
            appendLine("Deck one-liner: ${outline.meta.oneLiner}")
            appendLine("Language: ${outline.meta.language}")
            appendLine("Story hook: ${outline.storyline.hook}")
            appendLine("Promise: ${outline.storyline.promise}")
            appendLine("Forbidden terms (never use): ${c.forbiddenTerms.joinToString(", ").ifBlank { "(none)" }}")
            appendLine("Preferred phrases: ${c.preferredPhrases.joinToString(", ").ifBlank { "(none)" }}")
            appendLine("Avoid patterns: ${c.avoidPatterns.joinToString(", ").ifBlank { "(none)" }}")
            append("Differentiation: ${c.differentiationNote}")
        }.trim()
    }

    private fun validationFeedback(violations: List<String>): String = buildString {
        appendLine("The previous slide JSON failed validation. Fix ALL of the following and return corrected JSON only (no commentary):")
        violations.forEach { appendLine("- $it") }
    }.trim()

    private fun loadPrompt(resource: String): String {
        val stream = javaClass.getResourceAsStream(resource)
            ?: error("Missing prompt resource: $resource")
        return stream.use { it.readBytes().toString(StandardCharsets.UTF_8) }
    }

    /** Result of running one model's bounded attempt sequence for a slide. */
    private sealed interface AttemptResult {
        data class Success(val content: SlideContent) : AttemptResult
        data class Failure(val lastError: String) : AttemptResult
    }

    /** Outcome of a whole per-slide task (primary + fallback). */
    private sealed interface SlideOutcome {
        data class Succeeded(val content: SlideContent) : SlideOutcome
        data class Failed(val index: Int, val sectionId: String, val message: String) : SlideOutcome
    }

    companion object {
        const val PRIMARY_ATTEMPTS = 4
        const val FALLBACK_ATTEMPTS = 2
        val PRIMARY_TOKEN_LADDER = listOf(8192, 12288, 16384, 24576)
        val FALLBACK_TOKEN_LADDER = listOf(8192, 12288)

        private const val SYSTEM_PROMPT = "/prompts/slide_content_system.txt"
        private const val USER_PROMPT = "/prompts/slide_content_user.txt"
    }
}
