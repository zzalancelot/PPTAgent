package com.ppt.agent.business.scenario

/**
 * One plausible presentation scenario for the same topic — different purpose,
 * voice, and visual mood.
 */
data class PresentationScenario(
    /** Stable id, snake_case, e.g. "client_pitch", "team_report". */
    val id: String,
    /** Short UI label, e.g. "向客户推介方案". */
    val label: String,
    /** One sentence: when/why you'd use this version. */
    val description: String,
    /** Implied primary audience for this scenario. */
    val audienceFrame: String,
    /** Drives Morandi hue family. See [ALLOWED_COLOR_MOODS]. */
    val colorMood: String,
    /** Drives slide copy voice. Free text + mapped narrativeArc. */
    val voiceTone: String,
    /** Maps to existing OutlineMeta.narrativeArc allowed set. */
    val narrativeArc: String,
    /** 0.0–1.0 confidence this scenario fits the user's brief. */
    val confidence: Double,
)

/** LLM output envelope. */
data class ScenarioBrief(
    val scenarios: List<PresentationScenario>,
    val recommendedScenarioId: String,
    /** One sentence explaining why recommended fits brief best. */
    val recommendationRationale: String,
)

/** Active presentation stance for theme + content generation. */
data class DeckStance(
    val scenarioId: String,
    val label: String,
    val colorMood: String,
    val voiceTone: String,
    val narrativeArc: String,
    val audienceFrame: String,
)

object DeckStanceResolver {
    fun fromScenario(s: PresentationScenario): DeckStance = DeckStance(
        scenarioId = s.id,
        label = s.label,
        colorMood = s.colorMood,
        voiceTone = s.voiceTone,
        narrativeArc = s.narrativeArc,
        audienceFrame = s.audienceFrame,
    )

    fun resolve(brief: ScenarioBrief, scenarioId: String? = null): DeckStance {
        val id = scenarioId ?: brief.recommendedScenarioId
        val scenario = brief.scenarios.find { it.id == id }
            ?: brief.scenarios.first { it.id == brief.recommendedScenarioId }
        return fromScenario(scenario)
    }
}

val ALLOWED_COLOR_MOODS: Set<String> = setOf(
    "warm_earth", "cool_slate", "soft_green", "dusty_rose",
    "neutral_stone", "ocean_mist", "sunset_amber",
)

private val SCENARIO_ID_REGEX = Regex("^[a-z][a-z0-9_]{2,40}$")

/** Allowed narrativeArc values (imported from outline). */
private val ALLOWED_NARRATIVE_ARCS: Set<String> = setOf(
    "teaching", "personal_story", "decision_framework", "persuasion", "itinerary", "general",
)
