# Outline Agent Spec

> **For coding agent:** Implement in the `business` module. Depends on `PptInput` (input parser) and `LlmAdapter`.

## Goal

Given a validated `PptInput`, call the LLM via `LlmAdapter` to produce a structured `OutlineJson` — a cohesive slide plan with exactly `input.slideCount` slides.

Default model: **`GatewayModel.DEEPSEEK`**.

## Pipeline Position

```
PptInput → OutlinePlanner → OutlineJson (sections carry layoutProfile) → SlideContentGenerator → theme colors → render
```

## Interface

Package: `com.ppt.agent.business.outline`

```kotlin
interface OutlinePlanner {
    fun plan(input: PptInput, model: GatewayModel = GatewayModel.DEEPSEEK): OutlineResult
}

sealed class OutlineResult {
    data class Ok(val outline: OutlineJson) : OutlineResult()
    data class Err(val errors: List<OutlineError>) : OutlineResult()
}

sealed class OutlineError {
    data class LlmFailure(val message: String) : OutlineError()
    data class InvalidJson(val message: String, val attempt: Int) : OutlineError()
    data class TruncatedOutput(val attempt: Int, val maxTokensUsed: Int) : OutlineError()
    data class ValidationFailed(val violations: List<String>, val attempt: Int) : OutlineError()
    data class ExhaustedRetries(val attempts: Int, val lastError: String) : OutlineError()
}
```

Wire into `PptGenerationService`:

```kotlin
fun planOutline(input: PptInput, model: GatewayModel = GatewayModel.DEEPSEEK): OutlineResult
```

## Prompt Files

Load from classpath:

- `business/src/main/resources/prompts/outline_planner_system.txt`
- `business/src/main/resources/prompts/outline_planner_user.txt` — placeholders `{topic}`, `{brief}`, `{audience}`, `{slide_count}`

## Layer Responsibilities (IMPORTANT)

| Layer | Owns | Does NOT own |
|-------|------|--------------|
| **`business`** (`OutlinePlannerImpl`) | Retry loop, token ladder, truncation detection, validation feedback, parse → `OutlineJson` | gRPC, provider params in YAML |
| **`llm-adapter`** | Passthrough `chat()` only | Retry, token bump decisions, prompt rewriting |
| **`gateway-client` / server** | Forward `param_overrides` if provided | Business retry logic |

**Retry orchestration lives entirely in the business module**, not in `llm-adapter`.  
The adapter may accept an optional `paramOverrides` map and forward it blindly — that is transport plumbing, not business logic.

## Retry Policy (business layer only)

Implement in `OutlinePlannerImpl` (or extract `OutlineRetryExecutor` under `com.ppt.agent.business.outline`).

### Baseline

- Gateway default `max_tokens` for deepseek: **8192** (`gateway-server` YAML).
- `OutlinePlannerImpl` starts at **8192** and controls escalation itself.

### Max attempts

**At most 3 LLM calls** per `plan()` (1 initial + up to 2 retries).  
All 3 fail → `OutlineResult.Err(ExhaustedRetries(3, ...))`.

### Token escalation ladder

On **JSON parse failure** or **detected truncation**, business bumps `max_tokens` before the next call:

| Attempt | max_tokens |
|---------|------------|
| 1 | 8192 |
| 2 | 12288 |
| 3 | 16384 |

### Truncation / parse failure detection

Retryable when any of:

1. `framework.Json.parseFirstObject(text)` returns `null`
2. Full JSON parse throws
3. Trimmed text does not end with `}`
4. Detected slide count `< input.slideCount` (optional heuristic)

### Validation failure

- Do **not** bump tokens.
- Append `ChatMessage.User` with violations; retry within the same 3-attempt budget.

### Retry loop (business — `OutlinePlannerImpl`)

```kotlin
// com.ppt.agent.business.outline — NOT in llm-adapter
const val MAX_ATTEMPTS = 3
val tokenLadder = listOf(8192, 12288, 16384)

var messages = initialMessages(input)
var tokenIndex = 0

repeat(MAX_ATTEMPTS) { attempt ->
    val maxTokens = tokenLadder[tokenIndex.coerceAtMost(tokenLadder.lastIndex)]
    val overrides = mapOf("max_tokens" to maxTokens.toString())
    val response = llmAdapter.chat(messages, emptyList(), model, paramOverrides = overrides)
    // ... parse, validate, bump tokenIndex or append feedback — all here in business
}
```

### Transport plumbing (no retry logic)

Extend `LlmAdapter.chat` with an optional passthrough parameter only:

```kotlin
interface LlmAdapter {
    fun chat(
        messages: List<ChatMessage>,
        tools: List<Tool>,
        model: GatewayModel,
        paramOverrides: Map<String, String> = emptyMap(),  // blind forward only
    ): ModelResponse
}
```

`PassthroughLlmAdapter` forwards `paramOverrides` → `GatewayModelClient` → gRPC `ChatRequest.param_overrides`.  
**No branching, no retries, no token ladder inside adapter.**

## OutlineJson Schema

Package: `com.ppt.agent.business.outline`

```kotlin
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

data class OutlineSection(
    val id: String,
    val title: String,
    val purpose: String,
    val slideRange: List<Int>,
    /** Coarse per-chapter layout/typesetting seed for renderer diversity. */
    val layoutProfile: String,
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
```

### slideType enum

`title`, `agenda`, `section_divider`, `content`, `comparison`, `timeline`, `framework`, `case_study`, `code_or_demo`, `quote`, `summary`, `call_to_action`, `qa`

### layoutProfile enum (per section)

Each `OutlineSection` carries a **basic style seed** chosen at outline time. The renderer (or a future refiner) uses this to vary typesetting per chapter without a separate LLM call.

| Value | Typical use |
|-------|-------------|
| `tutorial_friendly` | Warm teaching, approachable spacing |
| `editorial_left` | Left-aligned editorial bullets |
| `centered_impact` | Big centered headlines, opening chapters |
| `dense_reference` | Compact rhythm, more info per slide |
| `split_narrative` | Two-column comparisons |
| `timeline_flow` | Sequential / itinerary / step-by-step |
| `pitch_bold` | Persuasion, accent-forward closing |

```kotlin
object LayoutProfiles {
    const val TUTORIAL_FRIENDLY = "tutorial_friendly"
    const val EDITORIAL_LEFT = "editorial_left"
    const val CENTERED_IMPACT = "centered_impact"
    const val DENSE_REFERENCE = "dense_reference"
    const val SPLIT_NARRATIVE = "split_narrative"
    const val TIMELINE_FLOW = "timeline_flow"
    const val PITCH_BOLD = "pitch_bold"
    val ALL: Set<String> = setOf(...)
}
```

**Diversity intent:** when planning sections, the LLM should vary `layoutProfile` based on section purpose and dominant `slideType`s — not assign one profile to the entire deck.

**Future:** a optional `SectionStyleRefiner` may expand `layoutProfile` into full typography/spacing overrides after theme colors are picked. See `docs/SECTION_STYLE_SPEC.md` (deferred).

## Validation (`OutlineValidator`)

Pure Kotlin. Collect all violations:

1. `slides.size == meta.slideCount == input.slideCount`
2. `index` values are `1..N` unique and consecutive
3. Each `section.slideRange` covers slides without gaps/overlaps
4. Required types: `title`×1, `agenda`×1, `section_divider`≥2, `summary`×1
5. `narrativeArc` in allowed set
6. `content`-like slides: `bulletHints` size 2–5
7. No more than 3 consecutive identical `slideType`
8. `keyTerms` non-empty
9. Every section has `layoutProfile` in `LayoutProfiles.ALL`
10. Layout diversity: ≥3 sections → ≥2 distinct profiles; ≥5 sections → ≥3 distinct profiles
11. Coherence: timeline-heavy sections (≥2 `timeline` slides) → `timeline_flow` or `split_narrative`; comparison-heavy (≥2 `comparison`) → `split_narrative` or `editorial_left`

## Tests

### `OutlineValidatorTest`

Hand-crafted valid outline + invalid cases. No LLM.

### `OutlinePlannerTest`

Fake `LlmAdapter` with scripted responses:

1. First call returns truncated JSON → second call returns valid fixture → `Ok`
2. Verify second call passes higher `max_tokens` in `paramOverrides` (business decided the value)
3. Three parse failures → `ExhaustedRetries` or `TruncatedOutput` on attempt 3
4. Valid JSON but validation fails twice → third attempt gets violation feedback message

### `PassthroughLlmAdapterTest` / `GatewayModelClientTest`

Verify `param_overrides` is forwarded on the wire (transport only; no retry logic in adapter).

## Module Constraints

- **All retry / token-ladder / truncation logic in `business` only**
- `llm-adapter`: passthrough `paramOverrides` only (optional plumbing)
- Default model `GatewayModel.DEEPSEEK`
- No streaming for outline

## Acceptance Criteria

- [ ] `OutlineJson` + `OutlineValidator` + `OutlinePlannerImpl`
- [ ] `paramOverrides` passthrough adapter → gRPC (no retry in adapter)
- [ ] Business retry: max 3 attempts, token ladder 8192 → 12288 → 16384 on parse/truncation
- [ ] Validation feedback retry within same attempt budget
- [ ] `PptGenerationService.planOutline` wired
- [ ] Unit tests cover retry + token escalation (fake adapter)
- [ ] `./gradlew build` green

## Non-Goals

- Slide content, `.pptx` rendering
- Streaming outline generation
