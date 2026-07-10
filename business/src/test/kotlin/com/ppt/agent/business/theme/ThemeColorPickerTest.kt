package com.ppt.agent.business.theme

import com.ppt.agent.business.outline.OutlineJson
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

class ThemeColorPickerTest {

    private fun outline(): OutlineJson =
        Json.fromJson(javaClass.getResource("/outline/valid-outline.json")!!.readText(), OutlineJson::class.java)

    private val validPalette = listOf("#2B2A28", "#3A3835", "#8C7A6B", "#6E6A63", "#E8E3DC")
    private val thinPalette = listOf("#2B2A28", "#3A3835", "#8C7A6B", "#6E6A63") // only 4 — fails length check

    private fun colorsJson(colors: List<String>): String = Json.toJson(mapOf("colors" to colors))

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
    fun returnsParsedPaletteOnValidJson() {
        val adapter = ScriptedLlmAdapter(listOf(colorsJson(validPalette)))

        val result = ThemeColorPickerImpl(adapter).pick(outline(), model = GatewayModel.DEEPSEEK_FLASH)

        val ok = assertIs<ThemeColorResult.Ok>(result, "expected Ok, got $result")
        assertEquals(validPalette, ok.palette.colors)
        assertEquals(1, adapter.callCount)
    }

    @Test
    fun retriesOnceWhenFirstResponseHasFourColorsAndSecondHasFive() {
        val adapter = ScriptedLlmAdapter(listOf(colorsJson(thinPalette), colorsJson(validPalette)))

        val result = ThemeColorPickerImpl(adapter).pick(outline(), model = GatewayModel.DEEPSEEK_FLASH)

        val ok = assertIs<ThemeColorResult.Ok>(result, "expected Ok after one retry, got $result")
        assertEquals(validPalette, ok.palette.colors)
        assertEquals(2, adapter.callCount)
        // Validation-failure retry appends exactly one new User feedback message.
        assertEquals(1, adapter.lastMessagesByCall[1].size - adapter.lastMessagesByCall[0].size)
    }

    @Test
    fun exhaustsRetriesAndReturnsErrWhenEveryResponseIsInvalid() {
        val adapter = ScriptedLlmAdapter(List(4) { colorsJson(thinPalette) })

        val result = ThemeColorPickerImpl(adapter).pick(outline(), model = GatewayModel.DEEPSEEK_FLASH)

        val err = assertIs<ThemeColorResult.Err>(result, "expected Err, got $result")
        assertEquals(4, adapter.callCount)
        assertIs<ThemeColorError.ExhaustedRetries>(err.errors.last())
    }

    @Test
    fun malformedJsonIsRetried() {
        val adapter = ScriptedLlmAdapter(listOf("not json at all", colorsJson(validPalette)))

        val result = ThemeColorPickerImpl(adapter).pick(outline(), model = GatewayModel.DEEPSEEK_FLASH)

        val ok = assertIs<ThemeColorResult.Ok>(result, "expected Ok after retry, got $result")
        assertEquals(validPalette, ok.palette.colors)
        assertEquals(2, adapter.callCount)
    }
}
