package com.ppt.agent.app

import com.ppt.agent.business.PptGenerationService
import com.ppt.agent.framework.GatewayModel
import org.slf4j.LoggerFactory
import org.springframework.boot.CommandLineRunner
import org.springframework.stereotype.Component

/**
 * Validates the full `business -> llm-adapter -> gateway-client` chain by
 * calling [PptGenerationService.pingLlm] — never the raw `ModelClient`
 * directly, so this exercises the new layering end to end. Failures (e.g.
 * gateway-server not running) are logged, not fatal, so the context still
 * loads in tests.
 */
@Component
class SmokeTestRunner(
    private val pptGenerationService: PptGenerationService,
) : CommandLineRunner {

    private val log = LoggerFactory.getLogger(SmokeTestRunner::class.java)

    override fun run(vararg args: String) {
        val model = GatewayModel.DEEPSEEK
        val result = pptGenerationService.pingLlm(model)
        log.info("[smoke] business -> llm-adapter -> gateway ({}) pingLlm result: {}", model.id, result)
    }
}
