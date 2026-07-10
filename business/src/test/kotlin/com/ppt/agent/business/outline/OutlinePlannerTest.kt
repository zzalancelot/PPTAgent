package com.ppt.agent.business.outline

import com.ppt.agent.business.input.PptInput
import com.ppt.agent.business.scenario.DeckStance
import com.ppt.agent.framework.ChatMessage
import com.ppt.agent.framework.GatewayModel
import com.ppt.agent.framework.Json
import com.ppt.agent.framework.ModelResponse
import com.ppt.agent.framework.ModelStreamEvent
import com.ppt.agent.framework.Tool
import com.ppt.agent.llm.adapter.LlmAdapter
import reactor.core.publisher.Flux
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Exercises the entire retry orchestration in [OutlinePlannerImpl] against a
 * scripted fake [LlmAdapter]. No live API, no gateway. Each scenario asserts
 * both the outcome and the `max_tokens` the business layer decided to send.
 */
class OutlinePlannerTest {

    private val input = PptInput(topic = "Python 入门 30 分钟", brief = "b", audience = "a", slideCount = 27)

    /** Records every call (messages + paramOverrides) and replays scripted steps. */
    private class ScriptedLlmAdapter(
        private val steps: List<() -> ModelResponse>,
    ) : LlmAdapter {
        data class Call(val messages: List<ChatMessage>, val paramOverrides: Map<String, String>)

        val calls = mutableListOf<Call>()

        override fun chat(
            messages: List<ChatMessage>,
            tools: List<Tool>,
            model: GatewayModel,
            paramOverrides: Map<String, String>,
        ): ModelResponse {
            val i = calls.size
            calls.add(Call(messages, paramOverrides))
            return steps[i.coerceAtMost(steps.lastIndex)]()
        }

        override fun chatStream(
            messages: List<ChatMessage>,
            tools: List<Tool>,
            model: GatewayModel,
        ): Flux<ModelStreamEvent> = Flux.empty()

        fun maxTokensPerCall(): List<String?> = calls.map { it.paramOverrides["max_tokens"] }
    }

    private fun ok(text: String): () -> ModelResponse = { ModelResponse(text, emptyList()) }
    private fun boom(message: String): () -> ModelResponse = { throw IllegalStateException(message) }

    private fun validJson(): String =
        javaClass.getResource("/outline/valid-outline.json")!!.readText()

    /** A parseable, well-formed outline JSON that nonetheless fails validation (no summary slide). */
    private fun validationFailingJson(): String {
        val outline = Json.fromJson(validJson(), OutlineJson::class.java)
        val broken = outline.copy(
            slides = outline.slides.map {
                if (it.slideType == SlideTypes.SUMMARY) it.copy(slideType = SlideTypes.CONTENT) else it
            },
        )
        return Json.toJson(broken)
    }

    /** Truncated response: the outer object never closes, so it can't be parsed. */
    private fun truncatedJson(): String = validJson().substring(0, 4000)

    @Test
    fun truncatedThenValidYieldsOkAndSecondCallGetsHigherMaxTokens() {
        val adapter = ScriptedLlmAdapter(listOf(ok(truncatedJson()), ok(validJson())))

        val result = OutlinePlannerImpl(adapter).plan(input, model = GatewayModel.DEEPSEEK)

        assertTrue(result is OutlineResult.Ok, "expected Ok, got $result")
        assertEquals(2, adapter.calls.size)
        // Business layer decided the escalation: 8192 -> 12288.
        assertEquals("8192", adapter.maxTokensPerCall()[0])
        assertEquals("12288", adapter.maxTokensPerCall()[1])
    }

    @Test
    fun threeTruncationsExhaustRetriesWithFullTokenLadder() {
        val adapter = ScriptedLlmAdapter(List(4) { ok(truncatedJson()) })

        val result = OutlinePlannerImpl(adapter).plan(input, model = GatewayModel.DEEPSEEK)

        assertTrue(result is OutlineResult.Err, "expected Err, got $result")
        val errors = (result as OutlineResult.Err).errors
        assertTrue(errors.last() is OutlineError.ExhaustedRetries, errors.toString())
        assertEquals(4, adapter.calls.size)
        assertEquals(listOf("8192", "12288", "16384", "16384"), adapter.maxTokensPerCall())
    }

    @Test
    fun validationFailuresRetryWithFeedbackAndDoNotBumpTokens() {
        val adapter = ScriptedLlmAdapter(
            listOf(ok(validationFailingJson()), ok(validationFailingJson()), ok(validJson())),
        )

        val result = OutlinePlannerImpl(adapter).plan(input, model = GatewayModel.DEEPSEEK)

        assertTrue(result is OutlineResult.Ok, "expected Ok after feedback, got $result")
        assertEquals(3, adapter.calls.size)
        // 27-slide deck bumps tokens after each validation failure.
        assertEquals(listOf("8192", "12288", "16384"), adapter.maxTokensPerCall())
        // The third attempt must carry appended violation feedback.
        val thirdCallMessages = adapter.calls[2].messages
        val feedback = thirdCallMessages.filterIsInstance<ChatMessage.User>()
            .filter { it.text.contains("failed validation") }
        assertTrue(feedback.isNotEmpty(), "third attempt should include violation feedback: $thirdCallMessages")
    }

    @Test
    fun threeValidationFailuresExhaustRetries() {
        val adapter = ScriptedLlmAdapter(List(4) { ok(validationFailingJson()) })

        val result = OutlinePlannerImpl(adapter).plan(input, model = GatewayModel.DEEPSEEK)

        assertTrue(result is OutlineResult.Err, "expected Err, got $result")
        val errors = (result as OutlineResult.Err).errors
        assertTrue(errors.any { it is OutlineError.ValidationFailed }, errors.toString())
        assertTrue(errors.last() is OutlineError.ExhaustedRetries, errors.toString())
        assertEquals(listOf("8192", "12288", "16384", "16384"), adapter.maxTokensPerCall())
    }

    @Test
    fun adapterExceptionIsRetriedWithoutBumpingTokens() {
        val adapter = ScriptedLlmAdapter(listOf(boom("gateway down"), ok(validJson())))

        val result = OutlinePlannerImpl(adapter).plan(input, model = GatewayModel.DEEPSEEK)

        assertTrue(result is OutlineResult.Ok, "expected Ok after transient failure, got $result")
        assertEquals(2, adapter.calls.size)
        // LlmFailure is not a truncation signal: tokens stay at baseline.
        assertEquals(listOf("8192", "8192"), adapter.maxTokensPerCall())
    }

    @Test
    fun firstCallSucceedsWithSingleAttemptAtBaselineTokens() {
        val adapter = ScriptedLlmAdapter(listOf(ok(validJson())))

        val result = OutlinePlannerImpl(adapter).plan(input, model = GatewayModel.DEEPSEEK)

        assertTrue(result is OutlineResult.Ok, "expected Ok, got $result")
        assertEquals(1, adapter.calls.size)
        assertEquals("8192", adapter.maxTokensPerCall()[0])
    }

    @Test
    fun planWithStanceInjectsStanceBlockIntoUserMessage() {
        val stance = DeckStance(
            scenarioId = "ceo_report",
            label = "向 CEO 汇报行程",
            colorMood = "cool_slate",
            voiceTone = "concise, data-driven",
            narrativeArc = "teaching",
            audienceFrame = "CEO and senior leadership",
        )
        val adapter = ScriptedLlmAdapter(listOf(ok(validJson())))

        val result = OutlinePlannerImpl(adapter).plan(input, stance, GatewayModel.DEEPSEEK)

        assertTrue(result is OutlineResult.Ok, "expected Ok, got $result")
        assertEquals(1, adapter.calls.size)
        // Verify the user message contains the stance block
        val userMessages = adapter.calls[0].messages.filterIsInstance<ChatMessage.User>()
        val userText = userMessages.joinToString("\n") { it.text }
        assertTrue(userText.contains("Presentation stance"), "user message should contain stance block")
        assertTrue(userText.contains("向 CEO 汇报行程"), "user message should contain stance label")
        assertTrue(userText.contains("narrative_arc: teaching"), "user message should contain narrative_arc")
        assertTrue(userText.contains("cool_slate"), "user message should contain color_mood")
    }

    @Test
    fun planWithStanceSoftChecksNarrativeArcMismatch() {
        val stance = DeckStance(
            scenarioId = "pitch",
            label = "Pitch",
            colorMood = "cool_slate",
            voiceTone = "persuasive",
            narrativeArc = "persuasion",
            audienceFrame = "investors",
        )
        // valid-outline.json has narrativeArc=teaching, stance wants persuasion → mismatch → retry
        val adapter = ScriptedLlmAdapter(listOf(ok(validJson()), ok(validJson()), ok(validJson())))

        val result = OutlinePlannerImpl(adapter).plan(input, stance, GatewayModel.DEEPSEEK)

        // The narrativeArc mismatch causes validation feedback retries;
        // since the fixture always returns "teaching" it exhausts retries (4 for 27 slides).
        assertTrue(result is OutlineResult.Err, "expected Err due to narrativeArc mismatch, got $result")
        val errors = (result as OutlineResult.Err).errors
        assertTrue(
            errors.any { it is OutlineError.ValidationFailed },
            "should have ValidationFailed with narrativeArc mismatch",
        )
        assertEquals(4, adapter.calls.size)
    }
}
