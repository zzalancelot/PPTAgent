package com.ppt.agent.gateway.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChatModelFactoryTest {

    private fun entry(
        baseUrl: String = "https://api.xiaomimimo.com",
        params: Map<String, String> = emptyMap(),
    ) = GatewayCapabilitiesProperties.ModelEntry().apply {
        this.baseUrl = baseUrl
        apiKey = "test-key"
        model = "mimo-v2.5-pro"
        this.params = params
    }

    @Test
    fun `normalizeBaseUrl appends api_root when missing`() {
        assertEquals(
            "https://api.xiaomimimo.com/v1",
            ChatModelFactory.normalizeBaseUrl(entry(params = mapOf("api_root" to "v1"))),
        )
    }

    @Test
    fun `normalizeBaseUrl leaves deepseek host unchanged without api_root`() {
        assertEquals(
            "https://api.deepseek.com",
            ChatModelFactory.normalizeBaseUrl(entry(baseUrl = "https://api.deepseek.com")),
        )
    }

    @Test
    fun `createBundle uses max completion token configurer for mimo`() {
        val mimo = ChatModelFactory.createBundle(
            entry(
                params = mapOf(
                    "api_root" to "v1",
                    "use_max_completion_tokens" to "true",
                    "thinking" to "disabled",
                ),
            ),
        )
        val deepseek = ChatModelFactory.createBundle(entry(baseUrl = "https://api.deepseek.com"))
        assertTrue(mimo.tokenLimitConfigurer !== deepseek.tokenLimitConfigurer)
    }

    @Test
    fun `thinkingExtraBody maps disabled`() {
        assertEquals(
            mapOf("thinking" to mapOf("type" to "disabled")),
            ChatModelFactory.thinkingExtraBody("disabled"),
        )
    }
}
