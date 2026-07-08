package com.ppt.agent.gateway.server

import com.ppt.agent.config.SpringAiModelClient
import com.ppt.agent.gateway.server.health.ModelHealthProbe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ModelHealthProbeTest {

    // Three model ids, but two of them point at the same provider+model, so a
    // single sweep should probe only two distinct (provider, model) pairs.
    private fun registry() = CapabilityRegistry(
        entries = mapOf(
            "deepseek" to CapabilitySpec("deepseek", "deepseek", "deepseek-chat", emptyMap()),
            "deepseek-alt" to CapabilitySpec("deepseek-alt", "deepseek", "deepseek-chat", emptyMap()),
            "mimo" to CapabilitySpec("mimo", "mimo", "mimo-v2.5-pro", emptyMap()),
        ),
        defaultCapability = "deepseek",
    )

    private fun health(mode: String = "real") = GatewayCapabilitiesProperties.Health().apply {
        probeMode = mode
    }

    // Both providers share the SAME FakeChatModel so callCount aggregates.
    private fun providers(fake: FakeChatModel): ProviderChatModels = ProviderChatModels(
        clients = mapOf("deepseek" to SpringAiModelClient(fake), "mimo" to SpringAiModelClient(fake)),
        chatModels = mapOf("deepseek" to fake, "mimo" to fake),
        meterRegistry = SimpleMeterRegistry(),
    )

    @Test
    fun `429 marks every entry down and probes are deduplicated per model`() {
        val fake = FakeChatModel.failing { RuntimeException("HTTP 429 Too Many Requests") }
        val probe = ModelHealthProbe(registry(), providers(fake), health())

        val results = probe.probeAll()

        assertEquals(3, results.size)
        assertTrue(results.all { it.status == ModelHealthProbe.ModelStatus.DOWN })
        assertTrue(results.all { it.detail == "rate limited (429)" })
        // deepseek + deepseek-alt collapse to one probe; mimo is the other.
        assertEquals(2, fake.callCount.get())
    }

    @Test
    fun `healthy models report up`() {
        val fake = FakeChatModel.replying("pong")
        val probe = ModelHealthProbe(registry(), providers(fake), health())

        val results = probe.probeAll()

        assertTrue(results.all { it.status == ModelHealthProbe.ModelStatus.UP })
        assertEquals(2, fake.callCount.get())
    }

    @Test
    fun `non-real probe mode is disabled without calling the model`() {
        val fake = FakeChatModel.replying("pong")
        val probe = ModelHealthProbe(registry(), providers(fake), health(mode = "off"))

        val results = probe.probeAll()

        assertTrue(results.all { it.status == ModelHealthProbe.ModelStatus.DISABLED })
        assertEquals(0, fake.callCount.get())
    }
}
