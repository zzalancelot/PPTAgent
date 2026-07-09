package com.ppt.agent.renderer.layout

import com.ppt.agent.renderer.model.DeckMeta

/**
 * Resolved typesetting knobs for one layoutProfile.
 * Pure data — no POI.
 */
data class LayoutProfileTokens(
    val profileId: String,
    /** Multiplier on default title font size (e.g. 1.25 = larger titles). */
    val titleScale: Double,
    /** Multiplier on default body/bullet font size. */
    val bodyScale: Double,
    val titleAlign: TitleAlign,
    /** Multiplier on vertical spacing / content top offset. */
    val density: Density,
    /** Multiplier applied to CONTENT_TOP (and related Y positions). */
    val contentTopScale: Double,
    /** Left vertical accent bar for content-like layouts. */
    val leftAccentBar: Boolean,
    /** Thin top rule under title region. */
    val topAccentRule: Boolean,
    /**
     * Soft layout preference override for BULLETS-family slides.
     * null = keep SlideLayoutMapper result unchanged.
     */
    val preferTwoColumnForComparison: Boolean = false,
)

enum class TitleAlign { LEFT, CENTER }

enum class Density { AIRY, STANDARD, DENSE }

object LayoutProfileResolver {
    const val DEFAULT_PROFILE = "editorial_left"

    private val TABLE: Map<String, LayoutProfileTokens> = mapOf(
        "editorial_left" to LayoutProfileTokens(
            profileId = "editorial_left",
            titleScale = 1.00, bodyScale = 1.00,
            titleAlign = TitleAlign.LEFT, density = Density.STANDARD,
            contentTopScale = 1.00, leftAccentBar = false, topAccentRule = false,
        ),
        "tutorial_friendly" to LayoutProfileTokens(
            profileId = "tutorial_friendly",
            titleScale = 1.00, bodyScale = 1.05,
            titleAlign = TitleAlign.LEFT, density = Density.AIRY,
            contentTopScale = 1.05, leftAccentBar = true, topAccentRule = false,
        ),
        "centered_impact" to LayoutProfileTokens(
            profileId = "centered_impact",
            titleScale = 1.20, bodyScale = 0.95,
            titleAlign = TitleAlign.CENTER, density = Density.AIRY,
            contentTopScale = 1.15, leftAccentBar = false, topAccentRule = true,
        ),
        "dense_reference" to LayoutProfileTokens(
            profileId = "dense_reference",
            titleScale = 0.90, bodyScale = 0.90,
            titleAlign = TitleAlign.LEFT, density = Density.DENSE,
            contentTopScale = 0.90, leftAccentBar = false, topAccentRule = false,
        ),
        "split_narrative" to LayoutProfileTokens(
            profileId = "split_narrative",
            titleScale = 1.00, bodyScale = 1.00,
            titleAlign = TitleAlign.LEFT, density = Density.STANDARD,
            contentTopScale = 1.00, leftAccentBar = false, topAccentRule = false,
        ),
        "timeline_flow" to LayoutProfileTokens(
            profileId = "timeline_flow",
            titleScale = 1.05, bodyScale = 1.00,
            titleAlign = TitleAlign.LEFT, density = Density.STANDARD,
            contentTopScale = 1.00, leftAccentBar = true, topAccentRule = false,
        ),
        "pitch_bold" to LayoutProfileTokens(
            profileId = "pitch_bold",
            titleScale = 1.15, bodyScale = 1.00,
            titleAlign = TitleAlign.LEFT, density = Density.STANDARD,
            contentTopScale = 1.05, leftAccentBar = true, topAccentRule = true,
        ),
    )

    fun resolve(profileId: String?): LayoutProfileTokens {
        if (profileId.isNullOrBlank()) return TABLE.getValue(DEFAULT_PROFILE)
        return TABLE[profileId] ?: TABLE.getValue(DEFAULT_PROFILE)
    }

    /** Resolve for one slide using deck meta + slide.sectionId. */
    fun forSlide(meta: DeckMeta?, sectionId: String?): LayoutProfileTokens {
        val key = sectionId?.takeIf { it.isNotBlank() }
        val fromMap = key?.let { meta?.sectionLayouts?.get(it) }
        return resolve(fromMap)
    }
}
