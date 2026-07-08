package com.ppt.agent.business.input

/**
 * Validated PPT generation input: the first step of the business pipeline,
 * before outline/LLM work. Fields are trimmed; [slideCount] defaults to
 * [DEFAULT_SLIDE_COUNT] when the input JSON omits `slide_count`.
 */
data class PptInput(
    val topic: String,
    val brief: String,
    val audience: String,
    val slideCount: Int = DEFAULT_SLIDE_COUNT,
) {
    companion object {
        const val DEFAULT_SLIDE_COUNT = 27
        const val MIN_SLIDE_COUNT = 25
        const val MAX_SLIDE_COUNT = 30
    }
}
