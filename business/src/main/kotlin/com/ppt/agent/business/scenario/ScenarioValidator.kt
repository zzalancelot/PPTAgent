package com.ppt.agent.business.scenario

/**
 * Pure-Kotlin structural validation of a [ScenarioBrief]. Collects all
 * violations (does not fail fast) so the retry loop can feed them back.
 */
class ScenarioValidator {

    private val SCENARIO_ID_REGEX = Regex("^[a-z][a-z0-9_]{2,40}$")

    private val ALLOWED_NARRATIVE_ARCS: Set<String> = setOf(
        "teaching", "personal_story", "decision_framework", "persuasion", "itinerary", "general",
    )

    /** Returns a list of human-readable violation messages; empty means valid. */
    fun validate(brief: ScenarioBrief): List<String> {
        val v = mutableListOf<String>()

        // Size 3–5
        if (brief.scenarios.size !in 3..5) {
            v += "scenarios.size must be 3–5 (found ${brief.scenarios.size})"
        }

        // Unique ids
        val ids = brief.scenarios.map { it.id }
        if (ids.toSet().size != ids.size) {
            v += "scenario ids must be unique (found duplicates in $ids)"
        }

        // Per-scenario validation
        for (s in brief.scenarios) {
            if (!SCENARIO_ID_REGEX.matches(s.id)) {
                v += "scenario id '${s.id}' must match ^[a-z][a-z0-9_]{2,40}$"
            }
            if (s.colorMood !in ALLOWED_COLOR_MOODS) {
                v += "scenario '${s.id}' has invalid colorMood '${s.colorMood}'"
            }
            if (s.narrativeArc !in ALLOWED_NARRATIVE_ARCS) {
                v += "scenario '${s.id}' has invalid narrativeArc '${s.narrativeArc}'"
            }
            if (s.confidence !in 0.0..1.0) {
                v += "scenario '${s.id}' confidence ${s.confidence} must be 0.0–1.0"
            }
        }

        // recommendedScenarioId must match
        if (brief.scenarios.none { it.id == brief.recommendedScenarioId }) {
            v += "recommendedScenarioId '${brief.recommendedScenarioId}' not found in scenarios"
        }

        // Recommended should have highest confidence (warning-level, still a violation)
        val recommended = brief.scenarios.find { it.id == brief.recommendedScenarioId }
        if (recommended != null) {
            val maxConfidence = brief.scenarios.maxOf { it.confidence }
            if (recommended.confidence < maxConfidence) {
                v += "recommended scenario '${brief.recommendedScenarioId}' confidence ${recommended.confidence} is not the highest (max ${maxConfidence})"
            }
        }

        return v
    }
}
