package com.ppt.agent.business.content

import com.ppt.agent.business.input.PptInput
import com.ppt.agent.business.outline.OutlineJson
import com.ppt.agent.framework.GatewayModel

/**
 * Expands an [OutlineJson] into final [SlideDeckContent] by making **one LLM
 * call per slide**, executed in parallel with a concurrency cap. Model choice is
 * fixed per outline section (see [ModelAssignmentPolicy]). All orchestration —
 * parallelism, retry, token escalation and cross-model fallback — lives in the
 * implementation (business layer); the `LlmAdapter` is a blind passthrough.
 */
interface SlideContentGenerator {
    fun generate(
        input: PptInput,
        outline: OutlineJson,
        modelPool: List<GatewayModel> = ModelPool.DEFAULT,
    ): ContentResult
}

sealed class ContentResult {
    data class Ok(val deck: SlideDeckContent) : ContentResult()
    data class Err(val errors: List<ContentError>) : ContentResult()
}

sealed class ContentError {
    /** A single slide that could not be generated after all retries + fallback. */
    data class SlideFailed(val index: Int, val sectionId: String, val message: String) : ContentError()

    /** Summary of a run where one or more slides failed (v1 fails the whole job). */
    data class PartialFailure(val failedIndices: List<Int>, val message: String) : ContentError()
}

/** The default set of models slide content generation may round-robin across. */
object ModelPool {
    val DEFAULT: List<GatewayModel> = listOf(GatewayModel.DEEPSEEK, GatewayModel.MIMO, GatewayModel.MINIMAX)
}

object ContentGenerationConfig {
    /** Max in-flight LLM calls for slide content generation. Tune here. */
    const val MAX_PARALLEL_SLIDES = 8
}
