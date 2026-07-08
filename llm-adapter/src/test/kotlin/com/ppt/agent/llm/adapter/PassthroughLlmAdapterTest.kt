package com.ppt.agent.llm.adapter

import com.ppt.agent.framework.ChatMessage
import com.ppt.agent.framework.GatewayModel
import com.ppt.agent.framework.ModelClient
import com.ppt.agent.framework.ModelResponse
import com.ppt.agent.framework.ModelStreamEvent
import com.ppt.agent.framework.StreamingModelClient
import com.ppt.agent.framework.Tool
import reactor.core.publisher.Flux
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

private class FakeModelClient(private val response: ModelResponse) : ModelClient {
    var lastMessages: List<ChatMessage>? = null
    var lastTools: List<Tool>? = null
    var lastModel: String? = null
    var lastParamOverrides: Map<String, String>? = null

    override fun chat(
        messages: List<ChatMessage>,
        tools: List<Tool>,
        model: String,
        paramOverrides: Map<String, String>,
    ): ModelResponse {
        lastMessages = messages
        lastTools = tools
        lastModel = model
        lastParamOverrides = paramOverrides
        return response
    }
}

private class FakeStreamingModelClient(private val events: Flux<ModelStreamEvent>) : StreamingModelClient {
    var lastMessages: List<ChatMessage>? = null
    var lastTools: List<Tool>? = null
    var lastCapability: String? = null

    override fun chatStream(
        messages: List<ChatMessage>,
        tools: List<Tool>,
        capability: String,
    ): Flux<ModelStreamEvent> {
        lastMessages = messages
        lastTools = tools
        lastCapability = capability
        return events
    }
}

/** No Spring context: exercises [PassthroughLlmAdapter] against fakes only. */
class PassthroughLlmAdapterTest {

    private val messages = listOf(ChatMessage.User("hi"))
    private val tools = emptyList<Tool>()

    @Test
    fun chatDelegatesToModelClientWithSameArgsAndReturnsSameResponse() {
        val response = ModelResponse(text = "hello", toolCalls = emptyList())
        val fakeModelClient = FakeModelClient(response)
        val fakeStreamingClient = FakeStreamingModelClient(Flux.empty())
        val adapter = PassthroughLlmAdapter(fakeModelClient, fakeStreamingClient)

        val result = adapter.chat(messages, tools, GatewayModel.DEEPSEEK)

        assertSame(response, result)
        assertEquals(messages, fakeModelClient.lastMessages)
        assertEquals(tools, fakeModelClient.lastTools)
        assertEquals(GatewayModel.DEEPSEEK.id, fakeModelClient.lastModel)
        // Default: no overrides forwarded.
        assertEquals(emptyMap(), fakeModelClient.lastParamOverrides)
    }

    @Test
    fun chatForwardsParamOverridesUntouchedToTheModelClient() {
        val response = ModelResponse(text = "hello", toolCalls = emptyList())
        val fakeModelClient = FakeModelClient(response)
        val adapter = PassthroughLlmAdapter(fakeModelClient, FakeStreamingModelClient(Flux.empty()))
        val overrides = mapOf("max_tokens" to "12288", "temperature" to "0.3")

        adapter.chat(messages, tools, GatewayModel.DEEPSEEK, overrides)

        // Blind passthrough: the map arrives at the transport layer exactly as given.
        assertEquals(overrides, fakeModelClient.lastParamOverrides)
    }

    @Test
    fun chatStreamDelegatesToStreamingModelClientWithSameArgsAndReturnsSameFlux() {
        val events = Flux.just<ModelStreamEvent>(ModelStreamEvent.Done(fullText = "done"))
        val fakeModelClient = FakeModelClient(ModelResponse(text = null, toolCalls = emptyList()))
        val fakeStreamingClient = FakeStreamingModelClient(events)
        val adapter = PassthroughLlmAdapter(fakeModelClient, fakeStreamingClient)

        val result = adapter.chatStream(messages, tools, GatewayModel.MIMO)

        assertSame(events, result)
        assertEquals(messages, fakeStreamingClient.lastMessages)
        assertEquals(tools, fakeStreamingClient.lastTools)
        assertEquals(GatewayModel.MIMO.id, fakeStreamingClient.lastCapability)
    }
}
