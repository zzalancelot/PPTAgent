package com.ppt.agent.app

import com.ppt.agent.business.PptGenerationService
import com.ppt.agent.business.input.ParseResult
import com.ppt.agent.framework.GatewayModel
import org.slf4j.LoggerFactory
import org.springframework.boot.CommandLineRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path

/**
 * Validates the full `business -> llm-adapter -> gateway-client` chain by
 * calling [PptGenerationService.pingLlm] — never the raw `ModelClient`
 * directly, so this exercises the new layering end to end. Failures (e.g.
 * gateway-server not running) are logged, not fatal, so the context still
 * loads in tests.
 *
 * If the first CLI arg is a `.json` file path, instead parses + validates it
 * via [PptGenerationService.parseInput] and logs the resulting [PptInput] (or
 * validation errors) — see INPUT_PARSER_SPEC.md. Otherwise runs the pingLlm
 * smoke as before.
 */
@Component
@ConditionalOnProperty(name = ["ppt.smoke.enabled"], havingValue = "true")
class SmokeTestRunner(
    private val pptGenerationService: PptGenerationService,
) : CommandLineRunner {

    private val log = LoggerFactory.getLogger(SmokeTestRunner::class.java)

    override fun run(vararg args: String) {
        val jsonPath = args.firstOrNull { it.endsWith(".json") }
        if (jsonPath != null) {
            runParseInputSmoke(jsonPath)
        } else {
            runPingLlmSmoke()
        }
    }

    private fun runParseInputSmoke(jsonPath: String) {
        try {
            val json = Files.readString(Path.of(jsonPath))
            when (val result = pptGenerationService.parseInput(json)) {
                is ParseResult.Ok -> log.info(
                    "[smoke] parseInput({}) -> Ok: topic='{}' audience='{}' slideCount={}",
                    jsonPath,
                    result.input.topic,
                    result.input.audience,
                    result.input.slideCount,
                )
                is ParseResult.Err -> log.warn("[smoke] parseInput({}) -> Err: {}", jsonPath, result.errors)
            }
        } catch (e: Exception) {
            log.warn("[smoke] failed to read/parse {}: {}", jsonPath, e.message)
        }
    }

    private fun runPingLlmSmoke() {
        val model = GatewayModel.DEEPSEEK
        val result = pptGenerationService.pingLlm(model)
        log.info("[smoke] business -> llm-adapter -> gateway ({}) pingLlm result: {}", model.id, result)
    }
}
