package com.ppt.agent.business.scenario

import com.ppt.agent.business.input.PptInput
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
import kotlin.test.assertIs

class ScenarioPlannerTest {

    private val input = PptInput(topic = "京都三日", brief = "部门季度汇报行程方案", audience = "CEO", slideCount = 27)

    private fun validBriefJson(): String = Json.toJson(
        mapOf(
            "scenarios" to listOf(
                mapOf(
                    "id" to "ceo_report",
                    "label" to "向 CEO 汇报行程",
                    "description" to "季度汇报行程方案",
                    "audienceFrame" to "CEO and senior leadership",
                    "colorMood" to "cool_slate",
                    "voiceTone" to "concise, data-driven",
                    "narrativeArc" to "teaching",
                    "confidence" to 0.9,
                ),
                mapOf(
                    "id" to "client_pitch",
                    "label" to "高端客户方案推介",
                    "description" to "向高端客户推介旅行方案",
                    "audienceFrame" to "premium clients",
                    "colorMood" to "neutral_stone",
                    "voiceTone" to "persuasive, premium",
                    "narrativeArc" to "persuasion",
                    "confidence" to 0.7,
                ),
                mapOf(
                    "id" to "team_guide",
                    "label" to "团队出行攻略",
                    "description" to "团队出行实用攻略",
                    "audienceFrame" to "team members",
                    "colorMood" to "warm_earth",
                    "voiceTone" to "friendly, practical",
                    "narrativeArc" to "general",
                    "confidence" to 0.5,
                ),
            ),
            "recommendedScenarioId" to "ceo_report",
            "recommendationRationale" to "The brief mentions quarterly report to CEO",
        ),
    )

    /** Invalid: only 2 scenarios (min is 3). */
    private fun tooFewScenariosJson(): String = Json.toJson(
        mapOf(
            "scenarios" to listOf(
                mapOf(
                    "id" to "aaa",
                    "label" to "A",
                    "description" to "d",
                    "audienceFrame" to "a",
                    "colorMood" to "cool_slate",
                    "voiceTone" to "v",
                    "narrativeArc" to "teaching",
                    "confidence" to 0.9,
                ),
                mapOf(
                    "id" to "bbb",
                    "label" to "B",
                    "description" to "d",
                    "audienceFrame" to "a",
                    "colorMood" to "warm_earth",
                    "voiceTone" to "v",
                    "narrativeArc" to "general",
                    "confidence" to 0.7,
                ),
            ),
            "recommendedScenarioId" to "aaa",
            "recommendationRationale" to "r",
        ),
    )

    /** Scriptable fake adapter: hands back one scripted response per call, in order. */
    private class ScriptedLlmAdapter(private val responses: List<String>) : LlmAdapter {
        var callCount = 0
        val lastMessagesByCall = mutableListOf<List<ChatMessage>>()

        override fun chat(
            messages: List<ChatMessage>,
            tools: List<Tool>,
            model: GatewayModel,
            paramOverrides: Map<String, String>,
        ): ModelResponse {
            lastMessagesByCall += messages
            val text = responses[callCount.coerceAtMost(responses.lastIndex)]
            callCount++
            return ModelResponse(text, emptyList())
        }

        override fun chatStream(
            messages: List<ChatMessage>,
            tools: List<Tool>,
            model: GatewayModel,
        ): Flux<ModelStreamEvent> = Flux.empty()
    }

    @Test
    fun validJsonReturnsOkWithParsedBrief() {
        val adapter = ScriptedLlmAdapter(listOf(validBriefJson()))

        val result = ScenarioPlannerImpl(adapter).infer(input)

        val ok = assertIs<ScenarioResult.Ok>(result, "expected Ok, got $result")
        assertEquals(3, ok.brief.scenarios.size)
        assertEquals("ceo_report", ok.brief.recommendedScenarioId)
        assertEquals(1, adapter.callCount)
    }

    @Test
    fun invalidJsonRetriedThenSucceeds() {
        val adapter = ScriptedLlmAdapter(listOf("not json at all", validBriefJson()))

        val result = ScenarioPlannerImpl(adapter).infer(input)

        val ok = assertIs<ScenarioResult.Ok>(result, "expected Ok after retry, got $result")
        assertEquals(3, ok.brief.scenarios.size)
        assertEquals(2, adapter.callCount)
    }

    @Test
    fun validationFailureRetriedWithFeedbackThenSucceeds() {
        val adapter = ScriptedLlmAdapter(listOf(tooFewScenariosJson(), validBriefJson()))

        val result = ScenarioPlannerImpl(adapter).infer(input)

        val ok = assertIs<ScenarioResult.Ok>(result, "expected Ok after validation retry, got $result")
        assertEquals(3, ok.brief.scenarios.size)
        assertEquals(2, adapter.callCount)
        // Validation-failure retry appends exactly one new User feedback message.
        assertEquals(1, adapter.lastMessagesByCall[1].size - adapter.lastMessagesByCall[0].size)
    }

    @Test
    fun exhaustsRetriesAndReturnsErrWhenEveryResponseIsInvalid() {
        val adapter = ScriptedLlmAdapter(List(4) { tooFewScenariosJson() })

        val result = ScenarioPlannerImpl(adapter).infer(input)

        val err = assertIs<ScenarioResult.Err>(result, "expected Err, got $result")
        assertEquals(4, adapter.callCount)
        assertIs<ScenarioError.ExhaustedRetries>(err.errors.last())
    }

    @Test
    fun llmExceptionIsRetriedAndSucceeds() {
        var callNo = 0
        val adapter = object : LlmAdapter {
            override fun chat(
                messages: List<ChatMessage>,
                tools: List<Tool>,
                model: GatewayModel,
                paramOverrides: Map<String, String>,
            ): ModelResponse {
                callNo++
                if (callNo == 1) throw IllegalStateException("model unavailable")
                return ModelResponse(validBriefJson(), emptyList())
            }

            override fun chatStream(
                messages: List<ChatMessage>,
                tools: List<Tool>,
                model: GatewayModel,
            ): Flux<ModelStreamEvent> = Flux.empty()
        }

        val result = ScenarioPlannerImpl(adapter).infer(input)

        val ok = assertIs<ScenarioResult.Ok>(result, "expected Ok after transient error, got $result")
        assertEquals(3, ok.brief.scenarios.size)
        assertEquals(2, callNo)
    }
}
