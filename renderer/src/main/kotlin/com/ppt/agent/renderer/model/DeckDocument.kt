package com.ppt.agent.renderer.model

/**
 * JSON DTOs mirroring `SlideDeckContent` (segment 4 output). All fields are
 * nullable so malformed / partial input surfaces as a validation violation
 * rather than a deserialization crash. Field names match the on-wire JSON
 * (camelCase), so Gson maps them directly.
 */
data class DeckDocument(
    val meta: DeckMeta?,
    val slides: List<SlideDocument>?,
)

data class DeckMeta(
    val topic: String?,
    val slideCount: Int?,
    val language: String?,
    val modelsUsed: Map<String, String>? = null,
    /** Optional Morandi palette from ThemeColorPicker: [background, surface, accent, accentMuted, textPrimary]. */
    val themeColors: List<String>? = null,
    /** sectionId → layoutProfile (from OutlineSection). Optional. */
    val sectionLayouts: Map<String, String>? = null,
)

data class SlideDocument(
    val index: Int?,
    val sectionId: String?,
    val slideType: String?,
    val title: String?,
    val subtitle: String?,
    val bullets: List<String>?,
    val speakerNotes: String?,
    val bodyText: String?,
)

/**
 * Tolerant top-level view of the input file. A raw `SlideDeckContent` has
 * `meta` + `slides` at the root; a full pipeline API response nests the deck
 * under a top-level `content` object. [toDeck] resolves either shape.
 */
data class RootDocument(
    val content: DeckDocument?,
    val meta: DeckMeta?,
    val slides: List<SlideDocument>?,
) {
    /** Unwraps to the deck: prefer a top-level `content` object, else treat the root as the deck. */
    fun toDeck(): DeckDocument =
        if (content != null && (content.meta != null || content.slides != null)) {
            content
        } else {
            DeckDocument(meta, slides)
        }
}
