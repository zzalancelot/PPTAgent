package com.ppt.agent.gateway.server

import com.ppt.agent.config.SpringAiModelClient
import com.ppt.agent.gateway.v1.ChatEvent
import com.ppt.agent.gateway.v1.ChatRequest
import com.ppt.agent.gateway.v1.ListCapabilitiesRequest
import com.ppt.agent.gateway.v1.Message
import com.ppt.agent.gateway.v1.ModelGatewayGrpc
import com.ppt.agent.gateway.v1.Role
import io.grpc.ManagedChannel
import io.grpc.Server
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import io.grpc.stub.StreamObserver
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end exercise of the external gRPC interface using an in-process server
 * and a [FakeChatModel]. No network and no API key required.
 */
class ModelGatewayServiceGrpcIT {

    private lateinit var server: Server
    private lateinit var channel: ManagedChannel

    private val registry = CapabilityRegistry(
        entries = mapOf(
            "deepseek" to CapabilitySpec("deepseek", "deepseek", "deepseek-chat", mapOf("temperature" to "0.7")),
            "mimo" to CapabilitySpec("mimo", "mimo", "mimo-v2.5-pro", emptyMap()),
        ),
        defaultCapability = "deepseek",
    )

    @BeforeEach
    fun setUp() {
        val fake = FakeChatModel.replying("Hello from the gateway!")
        val providers = ProviderChatModels(
            clients = mapOf("deepseek" to SpringAiModelClient(fake), "mimo" to SpringAiModelClient(fake)),
            chatModels = mapOf("deepseek" to fake, "mimo" to fake),
            meterRegistry = SimpleMeterRegistry(),
        )
        val service = ModelGatewayService(registry, providers)

        val name = InProcessServerBuilder.generateName()
        server = InProcessServerBuilder.forName(name).directExecutor().addService(service).build().start()
        channel = InProcessChannelBuilder.forName(name).directExecutor().build()
    }

    @AfterEach
    fun tearDown() {
        channel.shutdownNow()
        server.shutdownNow()
    }

    @Test
    fun `ListCapabilities returns the model table`() {
        val stub = ModelGatewayGrpc.newBlockingStub(channel)

        val response = stub.listCapabilities(ListCapabilitiesRequest.getDefaultInstance())

        assertEquals("deepseek", response.defaultCapability)
        val ids = response.capabilitiesList.map { it.alias }.toSet()
        assertEquals(setOf("deepseek", "mimo"), ids)
    }

    @Test
    fun `Chat returns the model reply and resolved model`() {
        val stub = ModelGatewayGrpc.newBlockingStub(channel)
        val request = ChatRequest.newBuilder()
            .setCapability("mimo")
            .addMessages(Message.newBuilder().setRole(Role.USER).setText("Say hi").build())
            .build()

        val response = stub.chat(request)

        assertEquals("Hello from the gateway!", response.text)
        assertEquals("mimo", response.resolved.capability)
        assertEquals("mimo-v2.5-pro", response.resolved.model)
    }

    @Test
    fun `ChatStream emits a text delta and a terminal done`() {
        val stub = ModelGatewayGrpc.newStub(channel)
        val request = ChatRequest.newBuilder()
            .setCapability("deepseek")
            .addMessages(Message.newBuilder().setRole(Role.USER).setText("Say hi").build())
            .build()

        val events = mutableListOf<ChatEvent>()
        val done = CountDownLatch(1)
        stub.chatStream(
            request,
            object : StreamObserver<ChatEvent> {
                override fun onNext(value: ChatEvent) { events.add(value) }
                override fun onError(t: Throwable) { done.countDown() }
                override fun onCompleted() { done.countDown() }
            },
        )

        assertTrue(done.await(5, TimeUnit.SECONDS), "stream did not complete in time")
        assertTrue(events.any { it.eventCase == ChatEvent.EventCase.TEXT_DELTA }, "expected a TextDelta")
        assertTrue(events.any { it.eventCase == ChatEvent.EventCase.DONE }, "expected a terminal Done")
    }
}
