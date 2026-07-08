package com.ppt.agent.business.input

import java.nio.file.Path

/** Parses and validates the PPT generation input JSON contract. */
interface PptInputParser {
    fun parse(json: String): ParseResult

    /** Reads [path] as UTF-8 text and delegates to [parse]. */
    fun parseFromFile(path: Path): ParseResult
}
