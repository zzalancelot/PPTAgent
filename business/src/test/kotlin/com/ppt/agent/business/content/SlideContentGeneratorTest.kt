package com.ppt.agent.business.content

import com.ppt.agent.business.input.PptInput
import com.ppt.agent.business.outline.OutlineJson
import com.ppt.agent.framework.ChatMessage
import com.ppt.agent.framework.GatewayModel
import com.ppt.agent.framework.Json
import com.ppt.agent.framework.ModelResponse
import com.ppt.agent.framework.ModelStreamEvent
import com.ppt.agent.framework.Tool
import com.ppt.agent.llm.adapter.LlmAdapter
import reactor.core.publisher.Flux
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SlideContentGeneratorTest {

    private val input = PptInput(topic = "Python 入门 30 分钟", brief = "b", audience = "a", slideCount = 27)

    private fun outline(): OutlineJson =
        Json.fromJson(javaClass.getResource("/outline/valid-outline.json")!!.readText(), OutlineJson::class.java)

    private fun template(): String =
        javaClass.getResource("/content/single-slide-response.json")!!.readText()

    private enum class Behavior { VALID, TRUNCATED, THROW, THIN }

    /**
     * Scriptable fake adapter. Decides each response from the requested slide
     * index (parsed out of the prompt), the model, and how many times that
     * (index, model) pair has already been called. Tracks live concurrency and
     * the `max_tokens` sent per attempt.
     */
    private class RecordingContentAdapter(
        private val validTemplate: String,
        private val delayMillis: Long = 0,
        private val decide: (index: Int, model: GatewayModel, modelCallNo: Int) -> Behavior,
    ) : LlmAdapter {

        val concurrent = AtomicInteger(0)
        val maxConcurrent = AtomicInteger(0)
        val callsByKey = ConcurrentHashMap<String, Int>()
        val tokensByKey = ConcurrentHashMap<String, MutableList<String>>()

        private val indexRegex = Regex("\"index\"\\s*:\\s*(\\d+)")

        override fun chat(
            messages: List<ChatMessage>,
            tools: List<Tool>,
            model: GatewayModel,
            paramOverrides: Map<String, String>,
        ): ModelResponse {
            val idx = extractIndex(messages)
            val key = "$idx|${model.id}"
            val callNo = callsByKey.merge(key, 1, Int::plus)!!
            tokensByKey.computeIfAbsent(key) { Collections.synchronizedList(mutableListOf<String>()) }
                .add(paramOverrides["max_tokens"] ?: "")

            val now = concurrent.incrementAndGet()
            maxConcurrent.updateAndGet { max(it, now) }
            try {
                if (delayMillis > 0) Thread.sleep(delayMillis)
                return when (decide(idx, model, callNo)) {
                    Behavior.VALID -> ModelResponse(validResponse(idx), emptyList())
                    Behavior.TRUNCATED -> ModelResponse("{\"index\": $idx, \"title\": \"incomplete", emptyList())
                    Behavior.THROW -> throw IllegalStateException("model $model unavailable")
                    Behavior.THIN -> ModelResponse(thinResponse(idx), emptyList())
                }
            } finally {
                concurrent.decrementAndGet()
            }
        }

        override fun chatStream(
            messages: List<ChatMessage>,
            tools: List<Tool>,
            model: GatewayModel,
        ): Flux<ModelStreamEvent> = Flux.empty()

        private fun extractIndex(messages: List<ChatMessage>): Int {
            val text = messages.joinToString("\n") {
                when (it) {
                    is ChatMessage.System -> it.text
                    is ChatMessage.User -> it.text
                    else -> ""
                }
            }
            return indexRegex.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: -1
        }

        private fun validResponse(idx: Int): String {
            val map = Json.parseFirstObject(validTemplate)!!.toMutableMap()
            map["index"] = idx
            return Json.toJson(map)
        }

        /** Valid JSON, but density-rule-thin: too few bullets, a short subtitle, and short notes. */
        private fun thinResponse(idx: Int): String = Json.toJson(
            linkedMapOf(
                "index" to idx,
                "title" to "Thin Title",
                "subtitle" to "short",
                "bullets" to listOf("one", "two"),
                "speakerNotes" to "too short",
                "bodyText" to null,
            ),
        )
    }

    @Test
    fun allSlidesSucceedYieldsOkWith27Slides() {
        val adapter = RecordingContentAdapter(template()) { _, _, _ -> Behavior.VALID }

        val result = SlideContentGeneratorImpl(adapter).generate(input, outline())

        assertTrue(result is ContentResult.Ok, "expected Ok, got $result")
        val deck = (result as ContentResult.Ok).deck
        assertEquals(27, deck.slides.size)
        assertEquals((1..27).toList(), deck.slides.map { it.index })
        assertEquals(27, adapter.callsByKey.values.sum())
        // 7 sections mapped to a model id.
        assertEquals(7, deck.meta.modelsUsed.size)
        assertEquals("deepseek", deck.meta.modelsUsed["opening"])
        assertEquals("deepseek", deck.meta.modelsUsed["setup"])
    }

    @Test
    fun concurrencyNeverExceedsMaxParallelSlides() {
        val adapter = RecordingContentAdapter(template(), delayMillis = 25) { _, _, _ -> Behavior.VALID }

        val result = SlideContentGeneratorImpl(adapter).generate(input, outline())

        assertTrue(result is ContentResult.Ok)
        assertTrue(
            adapter.maxConcurrent.get() <= ContentGenerationConfig.MAX_PARALLEL_SLIDES,
            "peak concurrency ${adapter.maxConcurrent.get()} exceeded ${ContentGenerationConfig.MAX_PARALLEL_SLIDES}",
        )
        assertTrue(adapter.maxConcurrent.get() >= 2, "expected real parallelism, peak was ${adapter.maxConcurrent.get()}")
    }

    @Test
    fun slideFailingThreeTimesOnAssignedModelSucceedsOnFallbackModel() {
        // Slide 2 is in the 'opening' section → assigned DEEPSEEK; alternate is MIMO.
        val adapter = RecordingContentAdapter(template()) { idx, model, _ ->
            if (idx == 2 && model == GatewayModel.DEEPSEEK) Behavior.TRUNCATED else Behavior.VALID
        }

        val result = SlideContentGeneratorImpl(adapter).generate(input, outline(), modelPool = ModelPool.MULTI)
        assertEquals(4, adapter.callsByKey["2|deepseek"], "should exhaust 4 attempts on DEEPSEEK")
        assertEquals(1, adapter.callsByKey["2|mimo"], "should succeed on first MIMO fallback attempt")
        // Token ladder on the primary model, then reset on the fallback model.
        assertEquals(listOf("8192", "12288", "16384", "24576"), adapter.tokensByKey["2|deepseek"]!!.toList())
        assertEquals(listOf("8192"), adapter.tokensByKey["2|mimo"]!!.toList())
    }

    @Test
    fun unrecoverableSlideFailureYieldsErrWithSlideFailed() {
        // Slide 5 is in 'setup' → assigned MIMO; alternate MINIMAX. Fail on every model.
        val adapter = RecordingContentAdapter(template()) { idx, _, _ ->
            if (idx == 5) Behavior.TRUNCATED else Behavior.VALID
        }

        val result = SlideContentGeneratorImpl(adapter).generate(input, outline(), modelPool = ModelPool.MULTI)

        assertTrue(result is ContentResult.Err, "expected Err, got $result")
        val errors = (result as ContentResult.Err).errors
        val slideFailed = errors.filterIsInstance<ContentError.SlideFailed>().firstOrNull()
        assertTrue(slideFailed != null, "expected a SlideFailed error: $errors")
        assertEquals(5, slideFailed!!.index)
        assertEquals("setup", slideFailed.sectionId)
        val partial = errors.filterIsInstance<ContentError.PartialFailure>().firstOrNull()
        assertTrue(partial != null && partial.failedIndices.contains(5), "expected PartialFailure listing slide 5: $errors")
        // 4 attempts on assigned MIMO, then 2 on the MINIMAX fallback.
        assertEquals(4, adapter.callsByKey["5|mimo"])
        assertEquals(2, adapter.callsByKey["5|minimax"])
    }

    @Test
    fun transientExceptionOnFirstAttemptIsRetriedAndSucceeds() {
        // Slide 3 (opening → DEEPSEEK): throw once, then succeed. No token bump on exceptions.
        val adapter = RecordingContentAdapter(template()) { idx, model, callNo ->
            if (idx == 3 && model == GatewayModel.DEEPSEEK && callNo == 1) Behavior.THROW else Behavior.VALID
        }

        val result = SlideContentGeneratorImpl(adapter).generate(input, outline())

        assertTrue(result is ContentResult.Ok, "expected Ok after transient error, got $result")
        assertEquals(2, adapter.callsByKey["3|deepseek"])
        // Exception is not a truncation signal → tokens stay at baseline for both attempts.
        assertEquals(listOf("8192", "8192"), adapter.tokensByKey["3|deepseek"]!!.toList())
    }

    @Test
    fun thinContentFailsDensityValidationThenRetrySucceedsWithoutTokenBump() {
        // Slide 4 (opening → DEEPSEEK, type 'content'): first attempt is valid JSON but too thin
        // (2 bullets, short subtitle/notes) → fails density validation and must retry.
        val adapter = RecordingContentAdapter(template()) { idx, _, callNo ->
            if (idx == 4 && callNo == 1) Behavior.THIN else Behavior.VALID
        }

        val result = SlideContentGeneratorImpl(adapter).generate(input, outline())

        assertTrue(result is ContentResult.Ok, "expected Ok after validation retry, got $result")
        assertEquals(2, adapter.callsByKey["4|deepseek"], "should retry once after thin content fails validation")
        // Validation failures append feedback and retry WITHOUT bumping tokens.
        assertEquals(listOf("8192", "8192"), adapter.tokensByKey["4|deepseek"]!!.toList())
    }
}
