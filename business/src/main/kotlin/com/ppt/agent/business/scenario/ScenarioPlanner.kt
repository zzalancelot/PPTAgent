package com.ppt.agent.business.scenario

import com.ppt.agent.business.input.PptInput
import com.ppt.agent.framework.GatewayModel

interface ScenarioPlanner {
    fun infer(input: PptInput, model: GatewayModel = GatewayModel.DEEPSEEK): ScenarioResult
}

sealed class ScenarioResult {
    data class Ok(val brief: ScenarioBrief) : ScenarioResult()
    data class Err(val errors: List<ScenarioError>) : ScenarioResult()
}

sealed class ScenarioError {
    data class LlmFailure(val message: String) : ScenarioError()
    data class InvalidJson(val message: String, val attempt: Int) : ScenarioError()
    data class ValidationFailed(val violations: List<String>, val attempt: Int) : ScenarioError()
    data class ExhaustedRetries(val attempts: Int, val lastError: String) : ScenarioError()
}
