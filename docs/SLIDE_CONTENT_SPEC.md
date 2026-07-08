# Slide Content Generator Spec (Segment 4)

> **For coding agent:** Implement in the `business` module. Depends on `PptInput`, `OutlineJson`, and `LlmAdapter`.

## Goal

Expand each slide in `OutlineJson` into final on-slide copy (`SlideDeckContent`) via **one LLM call per slide**, executed **in parallel** with a concurrency cap. Different **sections** may use different `GatewayModel` instances; model assignment is fixed per section.

Default pool: all configured `GatewayModel` entries (`DEEPSEEK`, `MIMO`, `MINIMAX`).

## Pipeline Position

```
PptInput + OutlineJson → SlideContentGenerator → SlideDeckContent → (future) layout / PPTX
```

## Interface

Package: `com.ppt.agent.business.content`

```kotlin
interface SlideContentGenerator {
    fun generate(
        input: PptInput,
        outline: OutlineJson,
        modelPool: List<GatewayModel> = ModelPool.DEFAULT,
    ): ContentResult
}

sealed class ContentResult {
    data class Ok(val deck: SlideDeckContent) : ContentResult()
    data class Err(val errors: List<ContentError>) : ContentResult()
}

sealed class ContentError {
    data class SlideFailed(val index: Int, val sectionId: String, val message: String) : ContentError()
    data class PartialFailure(val failedIndices: List<Int>, val message: String) : ContentError()
}
```

Wire into `PptGenerationService`:

```kotlin
fun generateContent(input: PptInput, outline: OutlineJson): ContentResult
```

## Concurrency (business layer)

```kotlin
object ContentGenerationConfig {
    /** Max in-flight LLM calls for slide content generation. Tune here. */
    const val MAX_PARALLEL_SLIDES = 8
}
```

Use a `Semaphore(MAX_PARALLEL_SLIDES)` (or equivalent) around each per-slide LLM call. All 27 tasks are submitted; at most 8 run concurrently.

## Model assignment — fixed per section

Package: `com.ppt.agent.business.content`

```kotlin
object ModelAssignmentPolicy {
    /**
     * Assigns one model per outline section (stable for the whole section).
     * Round-robin across [pool] in section order (outline.sections iteration order).
     * Example: 7 sections, pool [DEEPSEEK, MIMO, MINIMAX] →
     *   opening→DEEPSEEK, setup→MIMO, basics→MINIMAX, control→DEEPSEEK, ...
     */
    fun assignBySection(outline: OutlineJson, pool: List<GatewayModel>): Map<String, GatewayModel>
}
```

- Build `sectionId → GatewayModel` once per `generate()` call.
- Every slide in that section uses the same model.
- If `pool` has one model, all sections use it.
- If a model is removed from pool (future health check), fall back to next in pool.

**Do NOT assign a random model per slide** — section-fixed only.

## Per-slide LLM call

### One call = one slide

For each `OutlineSlide`:

1. Resolve `model = sectionAssignments[slide.sectionId]`
2. Build messages from prompt templates + context (below)
3. `llmAdapter.chat(messages, emptyList(), model, paramOverrides = ...)`
4. Parse JSON → `SlideContent`
5. Validate; on failure → retry policy

### Prompt files

Classpath:

- `business/src/main/resources/prompts/slide_content_system.txt`
- `business/src/main/resources/prompts/slide_content_user.txt`

Placeholders: `{topic}`, `{audience}`, `{tone}`, `{narrative_arc}`, `{key_terms_json}`, `{slide_json}`, `{prev_slide_hint}`, `{next_slide_hint}`, `{consistency_note}`

Each slide prompt MUST include global context:

- `outline.meta` (tone, oneLiner, language)
- `outline.storyline` (hook, promise — short)
- `outline.consistency` (keyTerms, forbiddenTerms, preferredPhrases)
- Current `OutlineSlide` (full)
- Optional: previous/next slide **outline** title + intent only (not generated content)

## Output schema

```kotlin
data class SlideDeckContent(
    val meta: ContentMeta,
    val slides: List<SlideContent>,
)

data class ContentMeta(
    val topic: String,
    val slideCount: Int,
    val language: String,
    val modelsUsed: Map<String, String>,  // sectionId → model id
)

data class SlideContent(
    val index: Int,
    val sectionId: String,
    val slideType: String,
    val title: String,
    val subtitle: String?,
    val bullets: List<String>,
    val speakerNotes: String?,
    val bodyText: String?,
)
```

### Per-slide JSON (LLM returns)

```json
{
  "index": 5,
  "title": "...",
  "subtitle": "...",
  "bullets": ["...", "..."],
  "speakerNotes": "...",
  "bodyText": null
}
```

`slideType` / `sectionId` come from outline (not rewritten by LLM); merge into `SlideContent` after parse.

## Validation (`SlideContentValidator`)

Per slide:

1. `index` matches outline slide
2. `title` non-blank
3. `slideType` from outline: bullets count rules:
   - `title`, `section_divider`, `qa`: bullets may be empty
   - `content`, `comparison`, etc.: 2–4 bullets
4. `speakerNotes` optional but recommended for `content` / `code_or_demo`

Deck-level after all slides complete:

5. `slides.size == outline.slides.size`
6. indices `1..N` complete (no missing slide)

## Retry policy (business layer ONLY)

Per slide, independent of other slides.

### Attempt budget

Up to **3 attempts** on the **assigned section model** (token ladder on parse/truncation).

If all 3 fail → **model fallback**: try **one other model** from pool (next in round-robin, not the same model), up to **2 attempts** with token ladder.

If still failing → mark slide failed; after all tasks complete, return `ContentResult.Err` (do not return partial deck in v1).

### Token ladder (per attempt, same as outline)

| Attempt | max_tokens |
|---------|------------|
| 1 | 4096 |
| 2 | 6144 |
| 3 | 8192 |

(Single slide needs less than full outline; start lower than outline's 8192.)

### Truncation / parse failure

Same heuristics as outline: parse fails, `parseFirstObject` null, text not ending with `}`.

### Validation failure

Append `User` message with violations; retry without bumping model (same attempt budget).

## Parallel execution sketch

```kotlin
val assignments = ModelAssignmentPolicy.assignBySection(outline, modelPool)
val semaphore = Semaphore(ContentGenerationConfig.MAX_PARALLEL_SLIDES)

val futures = outline.slides.map { slide ->
    async {
        semaphore.withPermit {
            generateOneSlide(input, outline, slide, assignments.getValue(slide.sectionId))
        }
    }
}
val results = futures.awaitAll()
// any Err → ContentResult.Err; else sort by index → SlideDeckContent
```

Use `kotlinx-coroutines` or `ExecutorService` — keep dependency minimal; coroutines preferred if already on classpath in business tests.

## Layer responsibilities

| Layer | Owns |
|-------|------|
| `business` | Parallelism, semaphore, section→model map, per-slide retry + fallback, merge |
| `llm-adapter` | Passthrough `chat` + `paramOverrides` only |
| `gateway` | Route per `GatewayModel` id |

## Tests (no live API)

1. `ModelAssignmentPolicyTest` — 7 sections, 3 models → round-robin mapping
2. `SlideContentValidatorTest` — bullet rules, index match
3. `SlideContentGeneratorTest` — fake `LlmAdapter`:
   - 27 slides complete → `Ok`
   - Semaphore: verify never more than 8 concurrent (use counter in fake)
   - Slide 5 fails 3 times on DEEPSEEK → succeeds on MIMO fallback
   - Unrecoverable failure → `Err` with `SlideFailed(5, ...)`
4. Fixture: `src/test/resources/content/single-slide-response.json`

## Acceptance Criteria

- [ ] `SlideContentGenerator` + parallel execution with `MAX_PARALLEL_SLIDES = 8`
- [ ] Section-fixed model assignment
- [ ] Per-slide retry (3) + cross-model fallback (2 attempts on alternate model)
- [ ] Prompt templates on classpath
- [ ] `PptGenerationService.generateContent` wired
- [ ] Unit tests with fake adapter
- [ ] `./gradlew :business:test` green

## Non-Goals

- PPTX rendering, layout, images
- Adapter per-model prompt compensation (future)
- Partial deck return on failure (v1 fails whole job)
