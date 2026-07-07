package com.ppt.agent.app

import com.ppt.agent.gateway.client.GatewayClientConfiguration
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Import

/**
 * Smoke-test runner. Depends only on the framework contracts and the gateway
 * client — never on Spring AI or Anthropic directly.
 */
@Import(GatewayClientConfiguration::class)
@SpringBootApplication
class PptAgentApplication

fun main(args: Array<String>) {
    runApplication<PptAgentApplication>(*args)
}
