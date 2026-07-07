package com.ppt.agent.gateway.server

import org.junit.jupiter.api.assertThrows
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CapabilityRegistryTest {

    private fun registry() = CapabilityRegistry(
        entries = mapOf(
            "fast-chat" to CapabilitySpec("fast-chat", "anthropic", "claude-opus-4-6", mapOf("temperature" to "0.7")),
            "reasoning" to CapabilitySpec("reasoning", "anthropic", "claude-opus-4-6", mapOf("temperature" to "0.4")),
        ),
        defaultCapability = "fast-chat",
    )

    @Test
    fun `resolves a known alias`() {
        val spec = registry().resolve("reasoning")
        assertEquals("reasoning", spec.capability)
        assertEquals("claude-opus-4-6", spec.model)
        assertEquals("0.4", spec.params["temperature"])
    }

    @Test
    fun `null or blank alias falls back to default`() {
        assertEquals("fast-chat", registry().resolve(null).capability)
        assertEquals("fast-chat", registry().resolve("   ").capability)
    }

    @Test
    fun `unknown alias fails fast`() {
        assertThrows<CapabilityRegistry.UnknownCapabilityException> {
            registry().resolve("does-not-exist")
        }
    }

    @Test
    fun `exposes alias table`() {
        val registry = registry()
        assertTrue(registry.contains("fast-chat"))
        assertFalse(registry.contains("missing"))
        assertEquals(setOf("fast-chat", "reasoning"), registry.aliases())
        assertEquals(2, registry.all().size)
    }
}
