package com.ppt.agent.business.input

import com.ppt.agent.framework.Json
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * Parses + validates the PPT generation input JSON contract using
 * [com.ppt.agent.framework.Json] only (no Jackson, no gateway, no LLM).
 * Collects **all** validation errors rather than failing fast.
 */
class PptInputParserImpl : PptInputParser {

    override fun parse(json: String): ParseResult {
        val root = try {
            Json.parse(json)
        } catch (e: Exception) {
            return ParseResult.Err(listOf(PptInputError.InvalidJson(e.message ?: "malformed JSON")))
        }

        if (root !is Map<*, *>) {
            return ParseResult.Err(listOf(PptInputError.InvalidJson("expected a top-level JSON object")))
        }

        @Suppress("UNCHECKED_CAST")
        val fields = root as Map<String, Any?>

        val errors = mutableListOf<PptInputError>()

        val topic = requireNonBlankString(fields, "topic", errors)
        val brief = requireNonBlankString(fields, "brief", errors)?.also { trimmed ->
            if (trimmed.length > MAX_BRIEF_LENGTH) {
                errors += PptInputError.BriefTooLong(trimmed.length, MAX_BRIEF_LENGTH)
            }
        }
        val audience = requireNonBlankString(fields, "audience", errors)
        val slideCount = parseSlideCount(fields, errors)

        return if (errors.isEmpty()) {
            ParseResult.Ok(
                PptInput(
                    topic = topic!!,
                    brief = brief!!,
                    audience = audience!!,
                    slideCount = slideCount!!,
                ),
            )
        } else {
            ParseResult.Err(errors)
        }
    }

    override fun parseFromFile(path: Path): ParseResult = try {
        parse(Files.readString(path, StandardCharsets.UTF_8))
    } catch (e: IOException) {
        ParseResult.Err(listOf(PptInputError.InvalidJson(e.message ?: "failed to read file: $path")))
    }

    private fun requireNonBlankString(
        fields: Map<String, Any?>,
        field: String,
        errors: MutableList<PptInputError>,
    ): String? {
        val raw = fields[field]
        if (raw == null) {
            errors += PptInputError.MissingField(field)
            return null
        }
        val text = (raw as? String)?.trim() ?: raw.toString().trim()
        if (text.isBlank()) {
            errors += PptInputError.BlankField(field)
            return null
        }
        return text
    }

    private fun parseSlideCount(fields: Map<String, Any?>, errors: MutableList<PptInputError>): Int? {
        if (!fields.containsKey(SLIDE_COUNT_FIELD) || fields[SLIDE_COUNT_FIELD] == null) {
            return PptInput.DEFAULT_SLIDE_COUNT
        }

        val raw = fields[SLIDE_COUNT_FIELD]!!
        val value = asWholeNumber(raw)
        if (value == null) {
            errors += PptInputError.SlideCountWrongType(describeType(raw))
            return null
        }
        if (value < PptInput.MIN_SLIDE_COUNT || value > PptInput.MAX_SLIDE_COUNT) {
            errors += PptInputError.InvalidSlideCount(value)
            return null
        }
        return value
    }

    /** Returns [raw] as an [Int] only if it is a number with no fractional part; else null. */
    private fun asWholeNumber(raw: Any): Int? = when (raw) {
        is Int -> raw
        is Long -> raw.toInt()
        is Double -> raw.takeIf { it == Math.floor(it) && !it.isInfinite() }?.toInt()
        is Float -> raw.takeIf { it == Math.floor(it.toDouble()).toFloat() }?.toInt()
        else -> null
    }

    private fun describeType(value: Any): String = when (value) {
        is String -> "String"
        is Boolean -> "Boolean"
        is List<*> -> "Array"
        is Map<*, *> -> "Object"
        else -> value::class.simpleName ?: "Unknown"
    }

    private companion object {
        const val SLIDE_COUNT_FIELD = "slide_count"
        const val MAX_BRIEF_LENGTH = 500
    }
}
