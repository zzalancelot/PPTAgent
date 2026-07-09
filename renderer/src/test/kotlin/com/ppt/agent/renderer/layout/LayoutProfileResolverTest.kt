package com.ppt.agent.renderer.layout

import com.ppt.agent.renderer.model.DeckMeta
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LayoutProfileResolverTest {

    @Test
    fun editorialLeftHasBaselineScales() {
        val t = LayoutProfileResolver.resolve("editorial_left")
        assertEquals("editorial_left", t.profileId)
        assertEquals(1.0, t.titleScale)
        assertEquals(1.0, t.bodyScale)
        assertEquals(TitleAlign.LEFT, t.titleAlign)
        assertEquals(Density.STANDARD, t.density)
        assertEquals(1.0, t.contentTopScale)
        assertFalse(t.leftAccentBar)
        assertFalse(t.topAccentRule)
    }

    @Test
    fun centeredImpactHasLargerTitleAndCenter() {
        val t = LayoutProfileResolver.resolve("centered_impact")
        assertEquals(1.20, t.titleScale)
        assertEquals(0.95, t.bodyScale)
        assertEquals(TitleAlign.CENTER, t.titleAlign)
        assertEquals(Density.AIRY, t.density)
        assertEquals(1.15, t.contentTopScale)
        assertFalse(t.leftAccentBar)
        assertTrue(t.topAccentRule)
    }

    @Test
    fun denseReferenceIsDense() {
        val t = LayoutProfileResolver.resolve("dense_reference")
        assertEquals(0.90, t.titleScale)
        assertEquals(0.90, t.bodyScale)
        assertEquals(Density.DENSE, t.density)
        assertEquals(0.90, t.contentTopScale)
    }

    @Test
    fun tutorialFriendlyHasAccentBar() {
        val t = LayoutProfileResolver.resolve("tutorial_friendly")
        assertTrue(t.leftAccentBar)
        assertFalse(t.topAccentRule)
        assertEquals(Density.AIRY, t.density)
        assertEquals(1.05, t.bodyScale)
    }

    @Test
    fun pitchBoldHasBothAccents() {
        val t = LayoutProfileResolver.resolve("pitch_bold")
        assertTrue(t.leftAccentBar)
        assertTrue(t.topAccentRule)
        assertEquals(1.15, t.titleScale)
    }

    @Test
    fun timelineFlowHasAccentBarAndSlightlyLargerTitle() {
        val t = LayoutProfileResolver.resolve("timeline_flow")
        assertTrue(t.leftAccentBar)
        assertFalse(t.topAccentRule)
        assertEquals(1.05, t.titleScale)
    }

    @Test
    fun unknownProfileDefaultsToEditorialLeft() {
        val t = LayoutProfileResolver.resolve("neon_future")
        assertEquals("editorial_left", t.profileId)
    }

    @Test
    fun nullProfileDefaultsToEditorialLeft() {
        val t = LayoutProfileResolver.resolve(null)
        assertEquals("editorial_left", t.profileId)
    }

    @Test
    fun blankProfileDefaultsToEditorialLeft() {
        val t = LayoutProfileResolver.resolve("  ")
        assertEquals("editorial_left", t.profileId)
    }

    @Test
    fun forSlideReadsSectionLayouts() {
        val meta = DeckMeta(
            topic = "t", slideCount = 2, language = "en",
            sectionLayouts = mapOf("opening" to "centered_impact", "closing" to "pitch_bold"),
        )
        val t = LayoutProfileResolver.forSlide(meta, "opening")
        assertEquals("centered_impact", t.profileId)
    }

    @Test
    fun forSlideMissingKeyDefaultsToEditorialLeft() {
        val meta = DeckMeta(
            topic = "t", slideCount = 2, language = "en",
            sectionLayouts = mapOf("opening" to "centered_impact"),
        )
        val t = LayoutProfileResolver.forSlide(meta, "unknown_section")
        assertEquals("editorial_left", t.profileId)
    }

    @Test
    fun forSlideNullMetaDefaultsToEditorialLeft() {
        val t = LayoutProfileResolver.forSlide(null, "opening")
        assertEquals("editorial_left", t.profileId)
    }

    @Test
    fun forSlideNullSectionIdDefaultsToEditorialLeft() {
        val meta = DeckMeta(
            topic = "t", slideCount = 2, language = "en",
            sectionLayouts = mapOf("opening" to "centered_impact"),
        )
        val t = LayoutProfileResolver.forSlide(meta, null)
        assertEquals("editorial_left", t.profileId)
    }

    @Test
    fun forSlideEmptyMapDefaultsToEditorialLeft() {
        val meta = DeckMeta(topic = "t", slideCount = 2, language = "en", sectionLayouts = emptyMap())
        val t = LayoutProfileResolver.forSlide(meta, "opening")
        assertEquals("editorial_left", t.profileId)
    }
}
