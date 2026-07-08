package com.ppt.agent.business.outline

/**
 * Structured slide-deck outline produced by the [OutlinePlanner] — a cohesive
 * narrative plan (not full slide copy). Field names match the JSON contract in
 * `prompts/outline_planner_system.txt`, so the raw model JSON maps here directly.
 */
data class OutlineJson(
    val meta: OutlineMeta,
    val storyline: Storyline,
    val sections: List<OutlineSection>,
    val slides: List<OutlineSlide>,
    val consistency: ConsistencyRules,
)

data class OutlineMeta(
    val topic: String,
    val audience: String,
    val slideCount: Int,
    val language: String,
    val narrativeArc: String,
    val tone: String,
    val oneLiner: String,
)

data class Storyline(
    val hook: String,
    val promise: String,
    val openingBeats: List<String>,
    val coreBeats: List<String>,
    val closingBeats: List<String>,
    val audienceMotivation: String,
)

/** A contiguous run of slides. [slideRange] is `[startIndex, endIndex]`, 1-based inclusive. */
data class OutlineSection(
    val id: String,
    val title: String,
    val purpose: String,
    val slideRange: List<Int>,
)

data class OutlineSlide(
    val index: Int,
    val sectionId: String,
    val slideType: String,
    val title: String,
    val subtitleHint: String?,
    val intent: String,
    val bulletHints: List<String>,
    val visualHint: String?,
    val transition: String?,
)

data class ConsistencyRules(
    val keyTerms: List<KeyTerm>,
    val forbiddenTerms: List<String>,
    val preferredPhrases: List<String>,
    val avoidPatterns: List<String>,
    val differentiationNote: String,
)

data class KeyTerm(val term: String, val definitionHint: String)

/** The `slideType` values the planner may emit. */
object SlideTypes {
    const val TITLE = "title"
    const val AGENDA = "agenda"
    const val SECTION_DIVIDER = "section_divider"
    const val CONTENT = "content"
    const val COMPARISON = "comparison"
    const val TIMELINE = "timeline"
    const val FRAMEWORK = "framework"
    const val CASE_STUDY = "case_study"
    const val CODE_OR_DEMO = "code_or_demo"
    const val QUOTE = "quote"
    const val SUMMARY = "summary"
    const val CALL_TO_ACTION = "call_to_action"
    const val QA = "qa"

    val ALL: Set<String> = setOf(
        TITLE, AGENDA, SECTION_DIVIDER, CONTENT, COMPARISON, TIMELINE, FRAMEWORK,
        CASE_STUDY, CODE_OR_DEMO, QUOTE, SUMMARY, CALL_TO_ACTION, QA,
    )

    /** Slide types that must carry 2–5 bullet hints. */
    val CONTENT_LIKE: Set<String> = setOf(
        CONTENT, COMPARISON, TIMELINE, FRAMEWORK, CASE_STUDY, CODE_OR_DEMO,
    )
}

/** Allowed values for [OutlineMeta.narrativeArc]. */
val ALLOWED_NARRATIVE_ARCS: Set<String> = setOf(
    "teaching", "personal_story", "decision_framework", "persuasion", "itinerary", "general",
)
