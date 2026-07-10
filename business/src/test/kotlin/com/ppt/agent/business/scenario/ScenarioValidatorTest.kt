package com.ppt.agent.business.scenario

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScenarioValidatorTest {

    private val validator = ScenarioValidator()

    private fun scenario(
        id: String = "ceo_report",
        colorMood: String = "cool_slate",
        narrativeArc: String = "teaching",
        confidence: Double = 0.9,
    ) = PresentationScenario(
        id = id,
        label = "Label",
        description = "desc",
        audienceFrame = "CEO",
        colorMood = colorMood,
        voiceTone = "concise",
        narrativeArc = narrativeArc,
        confidence = confidence,
    )

    private fun validBrief() = ScenarioBrief(
        scenarios = listOf(
            scenario("ceo_report", confidence = 0.9),
            scenario("client_pitch", colorMood = "neutral_stone", confidence = 0.7),
            scenario("team_guide", colorMood = "warm_earth", confidence = 0.5),
            scenario("travel_marketing", colorMood = "sunset_amber", confidence = 0.4),
        ),
        recommendedScenarioId = "ceo_report",
        recommendationRationale = "matches brief",
    )

    @Test
    fun validFourScenarioBriefPasses() {
        val violations = validator.validate(validBrief())
        assertTrue(violations.isEmpty(), "expected no violations, got: $violations")
    }

    @Test
    fun twoScenariosFailsMinThree() {
        val brief = ScenarioBrief(
            scenarios = listOf(
                scenario("aaa"),
                scenario("bbb"),
            ),
            recommendedScenarioId = "aaa",
            recommendationRationale = "r",
        )
        val violations = validator.validate(brief)
        assertTrue(violations.any { "3–5" in it }, "expected size violation, got: $violations")
    }

    @Test
    fun duplicateIdsFails() {
        val brief = ScenarioBrief(
            scenarios = listOf(
                scenario("same_id", confidence = 0.9),
                scenario("same_id", confidence = 0.8),
                scenario("other_id", confidence = 0.7),
            ),
            recommendedScenarioId = "same_id",
            recommendationRationale = "r",
        )
        val violations = validator.validate(brief)
        assertTrue(violations.any { "unique" in it }, "expected duplicate violation, got: $violations")
    }

    @Test
    fun invalidColorMoodFails() {
        val brief = ScenarioBrief(
            scenarios = listOf(
                scenario("aaa", colorMood = "neon_pink", confidence = 0.9),
                scenario("bbb", confidence = 0.7),
                scenario("ccc", confidence = 0.5),
            ),
            recommendedScenarioId = "aaa",
            recommendationRationale = "r",
        )
        val violations = validator.validate(brief)
        assertTrue(violations.any { "colorMood" in it }, "expected colorMood violation, got: $violations")
    }

    @Test
    fun invalidNarrativeArcFails() {
        val brief = ScenarioBrief(
            scenarios = listOf(
                scenario("aaa", narrativeArc = "adventure", confidence = 0.9),
                scenario("bbb", confidence = 0.7),
                scenario("ccc", confidence = 0.5),
            ),
            recommendedScenarioId = "aaa",
            recommendationRationale = "r",
        )
        val violations = validator.validate(brief)
        assertTrue(violations.any { "narrativeArc" in it }, "expected narrativeArc violation, got: $violations")
    }

    @Test
    fun recommendedScenarioIdNotInListFails() {
        val brief = ScenarioBrief(
            scenarios = listOf(
                scenario("aaa", confidence = 0.9),
                scenario("bbb", confidence = 0.7),
                scenario("ccc", confidence = 0.5),
            ),
            recommendedScenarioId = "nonexistent",
            recommendationRationale = "r",
        )
        val violations = validator.validate(brief)
        assertTrue(violations.any { "recommendedScenarioId" in it }, "expected recommended violation, got: $violations")
    }

    @Test
    fun recommendedNotHighestConfidenceIsViolation() {
        val brief = ScenarioBrief(
            scenarios = listOf(
                scenario("aaa", confidence = 0.5),
                scenario("bbb", confidence = 0.9),
                scenario("ccc", confidence = 0.3),
            ),
            recommendedScenarioId = "aaa",
            recommendationRationale = "r",
        )
        val violations = validator.validate(brief)
        assertTrue(violations.any { "highest" in it }, "expected confidence violation, got: $violations")
    }
}
