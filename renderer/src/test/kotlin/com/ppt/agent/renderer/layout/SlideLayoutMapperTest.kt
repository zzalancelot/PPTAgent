package com.ppt.agent.renderer.layout

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFailsWith

class SlideLayoutMapperTest {

    @Test
    fun allKnownSlideTypesMap() {
        val expected = mapOf(
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
        expected.forEach { (type, kind) ->
            assertEquals(kind, SlideLayoutMapper.map(type), "slideType '$type'")
        }
    }

    @Test
    fun unknownTypeMapsToNull() {
        assertNull(SlideLayoutMapper.mapOrNull("hologram"))
    }

    @Test
    fun unknownTypeThrowsFromStrictMap() {
        assertFailsWith<IllegalArgumentException> { SlideLayoutMapper.map("hologram") }
    }
}
