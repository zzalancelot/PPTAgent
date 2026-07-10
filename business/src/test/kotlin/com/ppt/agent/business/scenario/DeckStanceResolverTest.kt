package com.ppt.agent.business.scenario

import kotlin.test.Test
import kotlin.test.assertEquals

class DeckStanceResolverTest {

    private val scenarios = listOf(
        PresentationScenario(
            id = "ceo_report",
            label = "CEO Report",
            description = "d",
            audienceFrame = "CEO",
            colorMood = "cool_slate",
            voiceTone = "concise",
            narrativeArc = "teaching",
            confidence = 0.9,
        ),
        PresentationScenario(
            id = "client_pitch",
            label = "Client Pitch",
            description = "d",
            audienceFrame = "clients",
            colorMood = "neutral_stone",
            voiceTone = "persuasive",
            narrativeArc = "persuasion",
            confidence = 0.7,
        ),
        PresentationScenario(
            id = "team_guide",
            label = "Team Guide",
            description = "d",
            audienceFrame = "team",
            colorMood = "warm_earth",
            voiceTone = "friendly",
            narrativeArc = "general",
            confidence = 0.5,
        ),
    )

    private val brief = ScenarioBrief(
        scenarios = scenarios,
        recommendedScenarioId = "ceo_report",
        recommendationRationale = "matches brief",
    )

    @Test
    fun resolveByRecommended() {
        val stance = DeckStanceResolver.resolve(brief)
        assertEquals("ceo_report", stance.scenarioId)
        assertEquals("CEO Report", stance.label)
        assertEquals("cool_slate", stance.colorMood)
        assertEquals("concise", stance.voiceTone)
        assertEquals("teaching", stance.narrativeArc)
        assertEquals("CEO", stance.audienceFrame)
    }

    @Test
    fun resolveByExplicitScenarioId() {
        val stance = DeckStanceResolver.resolve(brief, "client_pitch")
        assertEquals("client_pitch", stance.scenarioId)
        assertEquals("Client Pitch", stance.label)
        assertEquals("neutral_stone", stance.colorMood)
        assertEquals("persuasive", stance.voiceTone)
        assertEquals("persuasion", stance.narrativeArc)
    }

    @Test
    fun unknownIdFallsBackToRecommended() {
        val stance = DeckStanceResolver.resolve(brief, "nonexistent_id")
        assertEquals("ceo_report", stance.scenarioId)
        assertEquals("CEO Report", stance.label)
    }

    @Test
    fun fromScenarioMapsAllFields() {
        val scenario = scenarios[1]
        val stance = DeckStanceResolver.fromScenario(scenario)
        assertEquals(scenario.id, stance.scenarioId)
        assertEquals(scenario.label, stance.label)
        assertEquals(scenario.colorMood, stance.colorMood)
        assertEquals(scenario.voiceTone, stance.voiceTone)
        assertEquals(scenario.narrativeArc, stance.narrativeArc)
        assertEquals(scenario.audienceFrame, stance.audienceFrame)
    }
}
