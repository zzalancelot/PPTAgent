package com.ppt.agent.business

import com.ppt.agent.framework.ChatMessage
import com.ppt.agent.framework.GatewayModel
import com.ppt.agent.llm.adapter.LlmAdapter

/**
 * Stub implementation proving the business -> llm-adapter -> gateway chain
 * works end to end: sends one `"ping"` user message and returns the response
 * text (or an error message), never touching `gateway-client` directly.
 */
class PptGenerationServiceImpl(
    private val llmAdapter: LlmAdapter,
) : PptGenerationService {

    override fun pingLlm(model: GatewayModel): String =
        try {
            val response = llmAdapter.chat(listOf(ChatMessage.User("ping")), emptyList(), model)
            response.text ?: "(empty response)"
        } catch (e: Exception) {
            "error: ${e.message}"
        }
}
