package com.ppt.agent.gateway.client

import com.ppt.agent.framework.ChatMessage
import com.ppt.agent.gateway.v1.ChatRequest
import com.ppt.agent.gateway.v1.ChatResponse
import com.ppt.agent.gateway.v1.ModelGatewayGrpc
import io.grpc.ManagedChannel
import io.grpc.Server
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import io.grpc.stub.StreamObserver
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Verifies [GatewayModelClient] forwards `paramOverrides` onto the gRPC
 * `ChatRequest.param_overrides` map on the wire — transport plumbing only,
 * no retry/token logic (that lives in the business layer).
 */
class GatewayModelClientTest {

    private lateinit var server: Server
    private lateinit var channel: ManagedChannel
    private lateinit var capturingService: CapturingGatewayService

    @BeforeEach
    fun setUp() {
        capturingService = CapturingGatewayService()
        val name = InProcessServerBuilder.generateName()
        server = InProcessServerBuilder.forName(name).directExecutor()
            .addService(capturingService).build().start()
        channel = InProcessChannelBuilder.forName(name).directExecutor().build()
    }

    @AfterEach
    fun tearDown() {
        channel.shutdownNow()
        server.shutdownNow()
    }

    @Test
    fun chatForwardsParamOverridesAndCapabilityOnTheWire() {
        val client = GatewayModelClient(ModelGatewayGrpc.newBlockingStub(channel))
        val overrides = mapOf("max_tokens" to "12288", "temperature" to "0.2")

        val response = client.chat(
            messages = listOf(ChatMessage.User("hi")),
            tools = emptyList(),
            model = "deepseek",
            paramOverrides = overrides,
        )

        val received = capturingService.lastRequest!!
        assertEquals("deepseek", received.capability)
        assertEquals(overrides, received.paramOverridesMap)
        assertEquals("ok", response.text)
    }

    @Test
    fun chatSendsAnEmptyParamOverridesMapWhenNoneAreGiven() {
        val client = GatewayModelClient(ModelGatewayGrpc.newBlockingStub(channel))

        client.chat(messages = listOf(ChatMessage.User("hi")), tools = emptyList(), model = "deepseek")

        assertEquals(emptyMap(), capturingService.lastRequest!!.paramOverridesMap)
    }

    private class CapturingGatewayService : ModelGatewayGrpc.ModelGatewayImplBase() {
        @Volatile
        var lastRequest: ChatRequest? = null

        override fun chat(request: ChatRequest, responseObserver: StreamObserver<ChatResponse>) {
            lastRequest = request
            responseObserver.onNext(ChatResponse.newBuilder().setText("ok").build())
            responseObserver.onCompleted()
        }
    }
}
