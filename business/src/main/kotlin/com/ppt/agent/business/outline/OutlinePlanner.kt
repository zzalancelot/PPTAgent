package com.ppt.agent.business.outline

import com.ppt.agent.business.input.PptInput
import com.ppt.agent.business.scenario.DeckStance
import com.ppt.agent.framework.GatewayModel

/**
 * Plans a cohesive slide-deck [OutlineJson] for a validated [PptInput] by
 * calling the LLM through the `LlmAdapter`. All retry / token-escalation /
 * truncation-detection logic lives in the implementation (business layer),
 * never in the adapter.
 */
interface OutlinePlanner {
    fun plan(input: PptInput, stance: DeckStance? = null, model: GatewayModel = GatewayModel.DEEPSEEK): OutlineResult
}

sealed class OutlineResult {
    data class Ok(val outline: OutlineJson) : OutlineResult()
    data class Err(val errors: List<OutlineError>) : OutlineResult()
}

sealed class OutlineError {
    data class LlmFailure(val message: String) : OutlineError()
    data class InvalidJson(val message: String, val attempt: Int) : OutlineError()
    data class TruncatedOutput(val attempt: Int, val maxTokensUsed: Int) : OutlineError()
    data class ValidationFailed(val violations: List<String>, val attempt: Int) : OutlineError()
    data class ExhaustedRetries(val attempts: Int, val lastError: String) : OutlineError()
}
