package com.ppt.agent.business.input

/** Outcome of [PptInputParser.parse] / [PptInputParser.parseFromFile]. */
sealed class ParseResult {
    data class Ok(val input: PptInput) : ParseResult()

    data class Err(val errors: List<PptInputError>) : ParseResult()
}
