package com.ppt.agent.gateway.server

import com.ppt.agent.config.SpringAiModelClient
import com.ppt.agent.gateway.server.health.ModelHealthProbe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ModelHealthProbeTest {

    private fun registry() = CapabilityRegistry(
        entries = mapOf(
            "fast-chat" to CapabilitySpec("fast-chat", "anthropic", "claude-opus-4-6", emptyMap()),
            "reasoning" to CapabilitySpec("reasoning", "anthropic", "claude-opus-4-6", emptyMap()),
            "extraction" to CapabilitySpec("extraction", "anthropic", "claude-opus-4-6", emptyMap()),
        ),
        defaultCapability = "fast-chat",
    )

    private fun health(mode: String = "real") = GatewayCapabilitiesProperties.Health().apply {
        probeMode = mode
    }

    private fun providers(chatModel: FakeChatModel, registry: SimpleMeterRegistry): ProviderChatModels =
        ProviderChatModels(
            clients = mapOf("anthropic" to SpringAiModelClient(chatModel)),
            chatModels = mapOf("anthropic" to chatModel),
            meterRegistry = registry,
        )

    @Test
    fun `429 marks every entry down and probes are deduplicated per model`() {
        val fake = FakeChatModel.failing { RuntimeException("HTTP 429 Too Many Requests") }
        val probe = ModelHealthProbe(registry(), providers(fake, SimpleMeterRegistry()), health())

        val results = probe.probeAll()

        assertEquals(3, results.size)
        assertTrue(results.all { it.status == ModelHealthProbe.ModelStatus.DOWN })
        assertTrue(results.all { it.detail == "rate limited (429)" })
        // Three aliases share one model -> exactly one live probe.
        assertEquals(1, fake.callCount.get())
    }

    @Test
    fun `healthy model reports up`() {
        val fake = FakeChatModel.replying("pong")
        val probe = ModelHealthProbe(registry(), providers(fake, SimpleMeterRegistry()), health())

        val results = probe.probeAll()

        assertTrue(results.all { it.status == ModelHealthProbe.ModelStatus.UP })
        assertEquals(1, fake.callCount.get())
    }

    @Test
    fun `non-real probe mode is disabled without calling the model`() {
        val fake = FakeChatModel.replying("pong")
        val probe = ModelHealthProbe(registry(), providers(fake, SimpleMeterRegistry()), health(mode = "off"))

        val results = probe.probeAll()

        assertTrue(results.all { it.status == ModelHealthProbe.ModelStatus.DISABLED })
        assertEquals(0, fake.callCount.get())
    }
}
