package com.ppt.agent.renderer.layout

/** The visual layout family a slide is rendered with. Pure mapping, no LLM. */
enum class SlideLayoutKind {
    TITLE,
    AGENDA,
    SECTION_DIVIDER,
    BULLETS,
    TWO_COLUMN,
    BODY_TEXT,
    CLOSING,
}

/**
 * Maps an outline/content `slideType` string to a [SlideLayoutKind]. Unknown
 * types have no layout — callers use [mapOrNull] to surface
 * `UnsupportedSlideType`, or [map] to fail fast.
 */
object SlideLayoutMapper {

    private val BY_TYPE: Map<String, SlideLayoutKind> = mapOf(
        "title" to SlideLayoutKind.TITLE,
        "agenda" to SlideLayoutKind.AGENDA,
        "section_divider" to SlideLayoutKind.SECTION_DIVIDER,
        "content" to SlideLayoutKind.BULLETS,
        "case_study" to SlideLayoutKind.BULLETS,
        "framework" to SlideLayoutKind.BULLETS,
        "timeline" to SlideLayoutKind.BULLETS,
        "summary" to SlideLayoutKind.BULLETS,
        "comparison" to SlideLayoutKind.TWO_COLUMN,
        "code_or_demo" to SlideLayoutKind.BODY_TEXT,
        "quote" to SlideLayoutKind.BODY_TEXT,
        "call_to_action" to SlideLayoutKind.CLOSING,
        "qa" to SlideLayoutKind.TITLE,
    )

    /** Returns the layout for [slideType], or null if the type is unknown. */
    fun mapOrNull(slideType: String): SlideLayoutKind? = BY_TYPE[slideType]

    /** Returns the layout for [slideType]; throws [IllegalArgumentException] if unknown. */
    fun map(slideType: String): SlideLayoutKind =
        mapOrNull(slideType) ?: throw IllegalArgumentException("Unsupported slideType: '$slideType'")
}

/**
 * Fixed layout index into the template slide master (0-6), matching the layout
 * contract in `PPTX_RENDER_MODE_SPEC.md`. Used by `TemplatePptxWriter`.
 */
fun SlideLayoutKind.toLayoutIndex(): Int = when (this) {
    SlideLayoutKind.TITLE -> 0
    SlideLayoutKind.AGENDA -> 1
    SlideLayoutKind.SECTION_DIVIDER -> 2
    SlideLayoutKind.BULLETS -> 3
    SlideLayoutKind.TWO_COLUMN -> 4
    SlideLayoutKind.BODY_TEXT -> 5
    SlideLayoutKind.CLOSING -> 6
}
