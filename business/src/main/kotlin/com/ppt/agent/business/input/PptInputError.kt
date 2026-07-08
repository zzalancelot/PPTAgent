package com.ppt.agent.business.input

import com.ppt.agent.business.input.PptInput.Companion.MAX_SLIDE_COUNT
import com.ppt.agent.business.input.PptInput.Companion.MIN_SLIDE_COUNT

/** A single validation failure found while parsing a [PptInput] JSON payload. */
sealed class PptInputError {
    data class InvalidJson(val message: String) : PptInputError()

    data class MissingField(val field: String) : PptInputError()

    data class BlankField(val field: String) : PptInputError()

    data class BriefTooLong(val length: Int, val max: Int = 500) : PptInputError()

    data class InvalidSlideCount(
        val value: Int,
        val min: Int = MIN_SLIDE_COUNT,
        val max: Int = MAX_SLIDE_COUNT,
    ) : PptInputError()

    data class SlideCountWrongType(val actual: String) : PptInputError()
}
