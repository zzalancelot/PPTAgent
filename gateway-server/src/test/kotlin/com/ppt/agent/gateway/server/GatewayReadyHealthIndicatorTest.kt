package com.ppt.agent.gateway.server

import com.ppt.agent.gateway.server.health.GatewayReadyHealthIndicator
import com.ppt.agent.gateway.v1.ModelGatewayGrpc
import org.springframework.boot.health.contributor.Status
import kotlin.test.Test
import kotlin.test.assertEquals

class GatewayReadyHealthIndicatorTest {

    private val registry = CapabilityRegistry(
        entries = mapOf(
            "deepseek" to CapabilitySpec("deepseek", "deepseek", "deepseek-chat", emptyMap()),
        ),
        defaultCapability = "deepseek",
    )

    private fun lifecycle() = GrpcServerLifecycle(
        service = object : ModelGatewayGrpc.ModelGatewayImplBase() {},
        port = 0, // ephemeral port for the test
    )

    @Test
    fun `down when grpc server not started`() {
        val indicator = GatewayReadyHealthIndicator(lifecycle(), registry)
        assertEquals(Status.DOWN, indicator.health().status)
    }

    @Test
    fun `up when grpc serving and capabilities present`() {
        val lifecycle = lifecycle()
        lifecycle.start()
        try {
            val indicator = GatewayReadyHealthIndicator(lifecycle, registry)
            val health = indicator.health()
            assertEquals(Status.UP, health.status)
        } finally {
            lifecycle.stop()
        }
    }

    @Test
    fun `down when no capabilities configured`() {
        val emptyRegistry = CapabilityRegistry(emptyMap(), "deepseek")
        val lifecycle = lifecycle()
        lifecycle.start()
        try {
            val indicator = GatewayReadyHealthIndicator(lifecycle, emptyRegistry)
            assertEquals(Status.DOWN, indicator.health().status)
        } finally {
            lifecycle.stop()
        }
    }
}
