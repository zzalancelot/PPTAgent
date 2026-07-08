package com.ppt.agent.business

import com.ppt.agent.framework.ChatMessage
import com.ppt.agent.framework.GatewayModel
import com.ppt.agent.framework.ModelResponse
import com.ppt.agent.framework.ModelStreamEvent
import com.ppt.agent.framework.Tool
import com.ppt.agent.llm.adapter.LlmAdapter
import reactor.core.publisher.Flux
import kotlin.test.Test
import kotlin.test.assertEquals

private class FakeLlmAdapter(
    private val behavior: (List<ChatMessage>, List<Tool>, GatewayModel) -> ModelResponse =
        { _, _, _ -> ModelResponse(text = "pong", toolCalls = emptyList()) },
) : LlmAdapter {
    var callCount = 0
    var lastMessages: List<ChatMessage>? = null
    var lastModel: GatewayModel? = null

    override fun chat(messages: List<ChatMessage>, tools: List<Tool>, model: GatewayModel): ModelResponse {
        callCount++
        lastMessages = messages
        lastModel = model
        return behavior(messages, tools, model)
    }

    override fun chatStream(
        messages: List<ChatMessage>,
        tools: List<Tool>,
        model: GatewayModel,
    ): Flux<ModelStreamEvent> = Flux.empty()
}

/** No Spring context: verifies the service calls [LlmAdapter], not the gateway. */
class PptGenerationServiceTest {

    @Test
    fun pingLlmSendsASinglePingMessageAndReturnsTheAdapterResponseText() {
        val fakeAdapter = FakeLlmAdapter()
        val service = PptGenerationServiceImpl(fakeAdapter)

        val result = service.pingLlm(GatewayModel.DEEPSEEK)

        assertEquals("pong", result)
        assertEquals(1, fakeAdapter.callCount)
        assertEquals(listOf(ChatMessage.User("ping")), fakeAdapter.lastMessages)
        assertEquals(GatewayModel.DEEPSEEK, fakeAdapter.lastModel)
    }

    @Test
    fun pingLlmReturnsAnErrorMessageInsteadOfThrowingWhenTheAdapterFails() {
        val fakeAdapter = FakeLlmAdapter { _, _, _ -> throw IllegalStateException("gateway down") }
        val service = PptGenerationServiceImpl(fakeAdapter)

        val result = service.pingLlm(GatewayModel.DEEPSEEK)

        assertEquals("error: gateway down", result)
    }
}
