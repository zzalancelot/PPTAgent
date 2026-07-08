package com.ppt.agent.app

import com.ppt.agent.business.BusinessConfiguration
import com.ppt.agent.gateway.client.GatewayClientConfiguration
import com.ppt.agent.llm.adapter.LlmAdapterConfiguration
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Import

/**
 * Smoke-test runner. Wires gateway-client -> llm-adapter -> business and
 * validates the full chain via `PptGenerationService` — never Spring AI or
 * Anthropic directly.
 */
@Import(
    GatewayClientConfiguration::class,
    LlmAdapterConfiguration::class,
    BusinessConfiguration::class,
)
@SpringBootApplication
class PptAgentApplication

fun main(args: Array<String>) {
    runApplication<PptAgentApplication>(*args)
}
