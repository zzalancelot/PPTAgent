package com.ppt.agent.app

import com.ppt.agent.framework.ChatMessage
import com.ppt.agent.framework.GatewayModel
import com.ppt.agent.framework.ModelClient
import com.ppt.agent.framework.ModelStreamEvent
import com.ppt.agent.framework.StreamingModelClient
import com.ppt.agent.framework.chat
import com.ppt.agent.framework.chatStream
import org.slf4j.LoggerFactory
import org.springframework.boot.CommandLineRunner
import org.springframework.stereotype.Component

/**
 * Selects a target model via the [GatewayModel] enum, calls the gateway, and
 * logs the response. Failures (e.g. gateway-server not running) are logged, not
 * fatal, so the context still loads in tests.
 */
@Component
class SmokeTestRunner(
    private val modelClient: ModelClient,
    private val streamingModelClient: StreamingModelClient,
) : CommandLineRunner {

    private val log = LoggerFactory.getLogger(SmokeTestRunner::class.java)

    override fun run(vararg args: String) {
        val model = GatewayModel.DEEPSEEK
        val messages = listOf(ChatMessage.User("Say hello in one sentence."))
        try {
            val response = modelClient.chat(messages, emptyList(), model)
            log.info("[smoke] {} response: {}", model.id, response.text)
        } catch (e: Exception) {
            log.warn("[smoke] chat failed (is gateway-server running on :9090?): {}", e.message)
        }

        try {
            val builder = StringBuilder()
            var chunks = 0
            streamingModelClient.chatStream(messages, emptyList(), model)
                .doOnNext { event ->
                    if (event is ModelStreamEvent.TextDelta) {
                        chunks++
                        builder.append(event.content)
                    }
                }
                .blockLast()
            log.info("[smoke] streaming received {} text chunk(s): {}", chunks, builder.toString())
        } catch (e: Exception) {
            log.warn("[smoke] streaming failed (is gateway-server running on :9090?): {}", e.message)
        }
    }
}
