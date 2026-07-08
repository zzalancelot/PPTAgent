package com.ppt.agent.gateway.server

import org.junit.jupiter.api.assertThrows
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CapabilityRegistryTest {

    private fun registry() = CapabilityRegistry(
        entries = mapOf(
            "deepseek" to CapabilitySpec("deepseek", "deepseek", "deepseek-chat", mapOf("temperature" to "0.7")),
            "mimo" to CapabilitySpec("mimo", "mimo", "mimo-v2.5-pro", mapOf("temperature" to "0.4")),
        ),
        defaultCapability = "deepseek",
    )

    @Test
    fun `resolves a known model id`() {
        val spec = registry().resolve("mimo")
        assertEquals("mimo", spec.capability)
        assertEquals("mimo-v2.5-pro", spec.model)
        assertEquals("0.4", spec.params["temperature"])
    }

    @Test
    fun `null or blank id falls back to default`() {
        assertEquals("deepseek", registry().resolve(null).capability)
        assertEquals("deepseek", registry().resolve("   ").capability)
    }

    @Test
    fun `unknown id fails fast`() {
        assertThrows<CapabilityRegistry.UnknownCapabilityException> {
            registry().resolve("does-not-exist")
        }
    }

    @Test
    fun `exposes the model table`() {
        val registry = registry()
        assertTrue(registry.contains("deepseek"))
        assertFalse(registry.contains("missing"))
        assertEquals(setOf("deepseek", "mimo"), registry.aliases())
        assertEquals(2, registry.all().size)
    }
}
