package com.ppt.agent.business.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** No Spring context: exercises [PptInputParserImpl] directly. */
class PptInputParserTest {

    private val parser: PptInputParser = PptInputParserImpl()

    private fun fixture(name: String): String {
        val stream = requireNotNull(javaClass.getResourceAsStream("/fixtures/$name")) {
            "fixture not found on classpath: $name"
        }
        return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    @Test
    fun eachPublicDevSetFixtureParsesOkWithDefaultSlideCount() {
        val fixtures = listOf(
            "01-python-intro.json",
            "02-year-review.json",
            "03-coffee-beans.json",
            "04-rust-rewrite.json",
            "05-kyoto-weekend.json",
        )

        for (name in fixtures) {
            val result = parser.parse(fixture(name))
            val ok = assertIs<ParseResult.Ok>(result, "fixture $name should parse OK, was $result")
            assertEquals(
                PptInput.DEFAULT_SLIDE_COUNT,
                ok.input.slideCount,
                "fixture $name should default slideCount to ${PptInput.DEFAULT_SLIDE_COUNT}",
            )
            assertTrue(ok.input.topic.isNotBlank(), "fixture $name topic should be non-blank")
            assertTrue(ok.input.brief.isNotBlank(), "fixture $name brief should be non-blank")
            assertTrue(ok.input.audience.isNotBlank(), "fixture $name audience should be non-blank")
        }
    }

    @Test
    fun missingTopicIsReported() {
        val json = """{"brief": "b", "audience": "a"}"""

        val err = assertIs<ParseResult.Err>(parser.parse(json))

        assertEquals(listOf(PptInputError.MissingField("topic")), err.errors)
    }

    @Test
    fun blankAudienceWhitespaceOnlyIsReported() {
        val json = """{"topic": "t", "brief": "b", "audience": "   "}"""

        val err = assertIs<ParseResult.Err>(parser.parse(json))

        assertEquals(listOf(PptInputError.BlankField("audience")), err.errors)
    }

    @Test
    fun briefOver500CharsIsReported() {
        val longBrief = "a".repeat(501)
        val json = """{"topic": "t", "brief": "$longBrief", "audience": "a"}"""

        val err = assertIs<ParseResult.Err>(parser.parse(json))

        assertEquals(listOf(PptInputError.BriefTooLong(501)), err.errors)
    }

    @Test
    fun malformedJsonIsInvalidJson() {
        val err = assertIs<ParseResult.Err>(parser.parse("{"))

        assertEquals(1, err.errors.size)
        assertIs<PptInputError.InvalidJson>(err.errors.single())
    }

    @Test
    fun extraKeysAreIgnored() {
        val json = """{"topic": "t", "brief": "b", "audience": "a", "extra": "ignored", "another": 123}"""

        val ok = assertIs<ParseResult.Ok>(parser.parse(json))

        assertEquals(PptInput(topic = "t", brief = "b", audience = "a"), ok.input)
    }

    @Test
    fun multipleErrorsInOnePayloadAreAllReported() {
        val json = """{"audience": "   "}"""

        val err = assertIs<ParseResult.Err>(parser.parse(json))

        assertEquals(
            listOf(
                PptInputError.MissingField("topic"),
                PptInputError.MissingField("brief"),
                PptInputError.BlankField("audience"),
            ),
            err.errors,
        )
    }

    @Test
    fun slideCount25IsOk() {
        val json = """{"topic": "t", "brief": "b", "audience": "a", "slide_count": 25}"""

        val ok = assertIs<ParseResult.Ok>(parser.parse(json))

        assertEquals(25, ok.input.slideCount)
    }

    @Test
    fun slideCount30IsOk() {
        val json = """{"topic": "t", "brief": "b", "audience": "a", "slide_count": 30}"""

        val ok = assertIs<ParseResult.Ok>(parser.parse(json))

        assertEquals(30, ok.input.slideCount)
    }

    @Test
    fun slideCount24IsInvalidSlideCount() {
        val json = """{"topic": "t", "brief": "b", "audience": "a", "slide_count": 24}"""

        val err = assertIs<ParseResult.Err>(parser.parse(json))

        assertEquals(listOf(PptInputError.InvalidSlideCount(24)), err.errors)
    }

    @Test
    fun slideCount31IsInvalidSlideCount() {
        val json = """{"topic": "t", "brief": "b", "audience": "a", "slide_count": 31}"""

        val err = assertIs<ParseResult.Err>(parser.parse(json))

        assertEquals(listOf(PptInputError.InvalidSlideCount(31)), err.errors)
    }

    @Test
    fun slideCountAsStringIsWrongType() {
        val json = """{"topic": "t", "brief": "b", "audience": "a", "slide_count": "27"}"""

        val err = assertIs<ParseResult.Err>(parser.parse(json))

        assertEquals(listOf(PptInputError.SlideCountWrongType("String")), err.errors)
    }

    @Test
    fun slideCountNullWithValidOtherFieldsDefaultsTo27() {
        val json = """{"topic": "t", "brief": "b", "audience": "a", "slide_count": null}"""

        val ok = assertIs<ParseResult.Ok>(parser.parse(json))

        assertEquals(PptInput.DEFAULT_SLIDE_COUNT, ok.input.slideCount)
    }
}
