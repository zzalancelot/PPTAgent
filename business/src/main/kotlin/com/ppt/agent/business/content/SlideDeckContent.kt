package com.ppt.agent.business.content

/**
 * Final on-slide copy for a whole deck, produced by [SlideContentGenerator] by
 * expanding each `OutlineSlide` into presentation-ready content. Field names on
 * [SlideContent] that the model supplies (`index`, `title`, `subtitle`,
 * `bullets`, `speakerNotes`, `bodyText`) match the per-slide JSON contract in
 * `prompts/slide_content_system.txt`; `slideType` / `sectionId` are copied from
 * the outline after parsing (never rewritten by the LLM).
 */
data class SlideDeckContent(
    val meta: ContentMeta,
    val slides: List<SlideContent>,
)

data class ContentMeta(
    val topic: String,
    val slideCount: Int,
    val language: String,
    /** sectionId → resolved model id used for that section's slides. */
    val modelsUsed: Map<String, String>,
)

data class SlideContent(
    val index: Int,
    val sectionId: String,
    val slideType: String,
    val title: String,
    val subtitle: String?,
    val bullets: List<String>,
    val speakerNotes: String?,
    val bodyText: String?,
)

/**
 * The raw per-slide payload the LLM returns. Deserialized from the model JSON,
 * then merged with `slideType` / `sectionId` from the outline into a
 * [SlideContent]. All fields nullable so a missing/omitted field surfaces as a
 * mapping failure (retryable) rather than a hard crash.
 */
internal data class LlmSlidePayload(
    val index: Int?,
    val title: String?,
    val subtitle: String?,
    val bullets: List<String>?,
    val speakerNotes: String?,
    val bodyText: String?,
)
