# Section Style Planner Spec (layout profile per outline chapter)

> **For coding agent:** Create a **new Gradle module** `section-style`. Given **one outline section (chapter)** plus its slides and deck context, call the LLM once to produce a structured **`SectionStyle`** JSON that controls how that section should be **typeset** in the renderer (layout density, alignment, column strategy, per-slideType overrides, decorative motifs). **No renderer / app / HTTP changes in this task** — models, planner, validator, prompts, tests only.

## Problem

Today every deck uses the same programmatic layout: left title, standard bullets, fixed margins. **Theme colors** (Morandi palette) already vary per deck, but **layout and typography rhythm** still feel like one template.

We need **per-section layout personality**: a Kyoto itinerary “Day 1” chapter should not look like a Python “variables” chapter, even inside the same deck.

**Scope of this module:** decide the **layout/style contract** for one `OutlineSection`. Applying it inside `PptxWriter` is a **follow-up task**.

---

## Pipeline position

```
OutlineJson
  └─ for each OutlineSection:
        SectionContext(section + slides in range + deck meta + optional themeColors)
              → SectionStylePlanner (LLM, 1 call / section)
              → SectionStyle (validated JSON)
              → (future) renderer reads style when rendering slides in that section
```

Deck-level flow (future orchestration in `business`):

```
pickThemeColors(outline)           // existing, deck-wide colors
planSectionStyles(outline)         // new, Map<sectionId, SectionStyle>
generateContent(...)
render with colors + per-section styles
```

---

## New module: `section-style`

Add to `settings.gradle.kts`:

```kotlin
include(..., "section-style")
```

### Dependencies (`section-style/build.gradle.kts`)

```kotlin
dependencies {
    implementation(project(":framework"))
    implementation(project(":llm-adapter"))
    // Must NOT depend on :business, :app, :renderer, :gateway-client, :gateway-api
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
}
```

**Rationale:** Same layering as `business` content/theme packages, but isolated so layout-style logic can evolve without bloating `business`. `business` will depend on `:section-style` and wire beans.

---

## Input: `SectionContext`

Package: `com.ppt.agent.sectionstyle`

One **chapter** = one `OutlineSection` + all `OutlineSlide` entries whose `sectionId` matches, sorted by `index`.

```kotlin
/**
 * Everything the planner needs to style ONE section/chapter.
 * Built by the orchestrator from [OutlineJson]; not produced by the LLM.
 */
data class SectionContext(
    /** Deck-level meta (topic, audience, tone, narrativeArc, language, oneLiner). */
    val deckMeta: DeckMetaContext,
    /** The chapter being styled. */
    val section: SectionInput,
    /** Slides belonging to this section only (from outline.slides filtered by sectionId). */
    val slides: List<SlideInput>,
    /** Optional Morandi palette already picked for the deck (5 × #RRGGBB). */
    val themeColors: List<String>? = null,
)

data class DeckMetaContext(
    val topic: String,
    val audience: String,
    val tone: String,
    val narrativeArc: String,
    val language: String,
    val oneLiner: String,
)

/** Mirrors outline section fields the LLM needs. */
data class SectionInput(
    val id: String,
    val title: String,
    val purpose: String,
    val slideRange: List<Int>, // [start, end] 1-based inclusive
)

/** Compact per-slide outline slice — no full copy text. */
data class SlideInput(
    val index: Int,
    val slideType: String,
    val title: String,
    val intent: String,
    val visualHint: String?,
)
```

### Building context (orchestrator helper)

```kotlin
object SectionContextFactory {
    fun fromOutline(outline: OutlineJson, sectionId: String, themeColors: List<String>? = null): SectionContext?
}
```

Return `null` if `sectionId` not found.

---

## Output: `SectionStyle`

The LLM returns a single JSON object. This is the **layout/typesetting contract** for every slide in the section.

```kotlin
interface SectionStylePlanner {
    fun plan(context: SectionContext, model: GatewayModel = GatewayModel.DEEPSEEK_FLASH): SectionStyleResult
}

sealed class SectionStyleResult {
    data class Ok(val style: SectionStyle) : SectionStyleResult()
    data class Err(val errors: List<SectionStyleError>) : SectionStyleResult()
}

data class SectionStyle(
    val sectionId: String,
    /** Short snake_case label summarizing the look, e.g. "warm_tutorial_spacious". */
    val styleName: String,
    /** High-level layout personality — drives renderer defaults for this section. */
    val layoutProfile: String,
    val typography: TypographyStyle,
    val spacing: SpacingStyle,
    /** Optional per-slideType layout overrides within this section. */
    val slideTypeOverrides: Map<String, SlideTypeOverride>,
    /** Decorative cues the renderer may draw (accent bar, timeline spine, etc.). */
    val decorations: List<String>,
    /** One sentence: why this style fits the section (for debugging / diversity audits). */
    val rationale: String,
)

data class TypographyStyle(
    /** Title font size tier: "xl" | "large" | "medium". */
    val titleScale: String,
    /** Body/bullet size tier: "large" | "medium" | "compact". */
    val bodyScale: String,
    /** Title alignment on content slides: "left" | "center". */
    val titleAlign: String,
)

data class SpacingStyle(
    /** Overall vertical rhythm: "airy" | "standard" | "dense". */
    val density: String,
    /** Content area top offset tier: "high" | "standard" | "low" (more room for title/subtitle). */
    val contentTop: String,
)

data class SlideTypeOverride(
    /** Target renderer layout family: "title" | "agenda" | "section_divider" | "bullets" | "two_column" | "body_text" | "closing". */
    val layoutKind: String,
    /** Bullet presentation when applicable: "disc" | "number" | "none". */
    val bulletStyle: String?,
    /** 1 or 2 — hint for comparison/framework slides. */
    val columns: Int?,
)
```

### Allowed enum values (validator must enforce)

| Field | Allowed values |
|-------|----------------|
| `layoutProfile` | `editorial_left`, `centered_impact`, `dense_reference`, `split_narrative`, `timeline_flow`, `pitch_bold`, `tutorial_friendly` |
| `typography.titleScale` | `xl`, `large`, `medium` |
| `typography.bodyScale` | `large`, `medium`, `compact` |
| `typography.titleAlign` | `left`, `center` |
| `spacing.density` | `airy`, `standard`, `dense` |
| `spacing.contentTop` | `high`, `standard`, `low` |
| `slideTypeOverrides.*.layoutKind` | `title`, `agenda`, `section_divider`, `bullets`, `two_column`, `body_text`, `closing` |
| `slideTypeOverrides.*.bulletStyle` | `disc`, `number`, `none`, null |
| `slideTypeOverrides.*.columns` | `1`, `2`, null |
| `decorations` (each entry) | `left_accent_bar`, `top_accent_rule`, `surface_card`, `timeline_spine`, `section_icon_placeholder`, `none` |

`styleName`: `^[a-z][a-z0-9_]{2,40}$`

`rationale`: non-blank, 20–200 characters.

`sectionId` in output **must equal** `context.section.id`.

---

## Layout profile semantics (for the LLM)

| `layoutProfile` | When to use | Typical sections |
|-----------------|-------------|------------------|
| `tutorial_friendly` | Warm teaching, approachable spacing | Python basics, onboarding |
| `editorial_left` | Left-aligned editorial, readable bullets | General content chapters |
| `centered_impact` | Big centered headlines, few bullets | Opening, key message |
| `dense_reference` | More text per slide, compact | Cheat-sheets, summaries |
| `split_narrative` | Prefer two-column comparisons | pros/cons, before/after |
| `timeline_flow` | Sequential steps / itinerary | timeline slides, travel days |
| `pitch_bold` | Persuasion, accent-heavy | CEO pitch, call to action |

**Diversity rule:** style should reflect **section purpose + dominant slideTypes + narrativeArc**, not only deck topic. Two sections in the same deck **should usually differ** in `layoutProfile` unless they serve the same structural role.

---

## Model & retry

- **Model:** `DEEPSEEK_FLASH` (one short structured JSON per section).
- **Retries:** mirror `ThemeColorPickerImpl` / `OutlinePlannerImpl`:
  - Up to **3** attempts.
  - Parse / truncated failure → retry.
  - Validation failure → append violations as `User` message, retry **without** bumping tokens.
- **`max_tokens`:** **1024** (richer than theme colors, still small).
- **Parallelism:** orchestrator may call planner per section in parallel (out of scope here; unit tests use fakes).

### JSON parsing

Reuse `Json.parseFirstObject(text)` — **do not** require `text.trim().endsWith("}")` (see theme-color bugfix). If `parseFirstObject` returns an object, treat as parseable.

---

## Prompt files

Classpath:

- `section-style/src/main/resources/prompts/section_style_system.txt`
- `section-style/src/main/resources/prompts/section_style_user.txt`

### Placeholders (user prompt)

| Placeholder | Source |
|-------------|--------|
| `{topic}` | `deckMeta.topic` |
| `{audience}` | `deckMeta.audience` |
| `{tone}` | `deckMeta.tone` |
| `{narrative_arc}` | `deckMeta.narrativeArc` |
| `{language}` | `deckMeta.language` |
| `{section_json}` | JSON of `section` |
| `{slides_json}` | JSON array of `slides` (compact) |
| `{theme_colors_json}` | JSON array or `"null"` |

---

## `section_style_system.txt` (copy verbatim)

```
You are a presentation layout director. Given ONE chapter (section) of a slide deck outline, define a cohesive visual/layout style for that chapter only.

## Task
Output a JSON object that tells the renderer how to typeset every slide in this section: density, title alignment, typography scale, per-slideType layout overrides, and light decorative motifs.

The style must feel tailored to THIS section's purpose and slide mix — not a generic template applied to every deck.

## Input you receive
- Deck meta: topic, audience, tone, narrative arc, language
- One section: id, title, purpose, slide range
- Slides in this section only: index, slideType, title, intent, visualHint
- Optional deck theme colors (5 Morandi hex colors) — layout choices should harmonize with a warm/cool/muted palette when provided, but do NOT output colors in this JSON

## Layout profiles (pick exactly one for layoutProfile)
- tutorial_friendly — warm teaching rhythm, approachable spacing, left titles
- editorial_left — magazine-like left-aligned titles, standard bullets
- centered_impact — big centered headlines, breathing room, fewer visual elements
- dense_reference — compact body scale, more information per slide
- split_narrative — prefer two-column treatment for comparison-style content
- timeline_flow — sequential / itinerary / step-by-step chapters
- pitch_bold — persuasion chapters, accent-forward section dividers

## Diversity
- Let section purpose + dominant slideTypes drive the choice (e.g. itinerary section → timeline_flow; comparison-heavy → split_narrative).
- Do NOT pick the same layoutProfile for every section in a deck unless the slides truly share the same structural role.
- slideTypeOverrides should only cover slideTypes that actually appear in slides_json.

## slideTypeOverrides keys
Use only slideTypes present in the input slides. Values:
- layoutKind: title | agenda | section_divider | bullets | two_column | body_text | closing
- bulletStyle: disc | number | none (null if not applicable)
- columns: 1 | 2 | null

## decorations (0–3 items)
Pick from: left_accent_bar, top_accent_rule, surface_card, timeline_spine, section_icon_placeholder, none
Use sparingly — 0–2 decorations is typical.

## Output
Return ONLY valid JSON (no markdown fences, no commentary):

{
  "sectionId": "string",
  "styleName": "snake_case_label",
  "layoutProfile": "one_of_the_profiles_above",
  "typography": {
    "titleScale": "xl" | "large" | "medium",
    "bodyScale": "large" | "medium" | "compact",
    "titleAlign": "left" | "center"
  },
  "spacing": {
    "density": "airy" | "standard" | "dense",
    "contentTop": "high" | "standard" | "low"
  },
  "slideTypeOverrides": {
    "content": {
      "layoutKind": "bullets",
      "bulletStyle": "disc",
      "columns": 1
    }
  },
  "decorations": ["left_accent_bar"],
  "rationale": "One sentence explaining why this style fits the section."
}

Rules:
- sectionId must match the input section id exactly.
- styleName: lowercase snake_case, 3–41 chars.
- rationale: 20–200 characters.
- slideTypeOverrides may be {} if defaults are enough.
```

---

## `section_style_user.txt` (copy verbatim)

```
Define the layout/style for this ONE presentation section (chapter).

## Deck context
topic: {topic}
audience: {audience}
tone: {tone}
narrative_arc: {narrative_arc}
language: {language}

## Optional deck theme colors (Morandi palette, may be null)
{theme_colors_json}

## Section (chapter)
{section_json}

## Slides in this section only
{slides_json}

Return ONLY the SectionStyle JSON object.
```

---

## Validator (`SectionStyleValidator`)

Pure Kotlin. Collect **all** violations.

| Check | Rule |
|-------|------|
| `sectionId` | equals `context.section.id` |
| `styleName` | matches `^[a-z][a-z0-9_]{2,40}$` |
| `layoutProfile` | in allowed set |
| `typography.*` | each field in allowed set |
| `spacing.*` | each field in allowed set |
| `rationale` | length 20–200 |
| `decorations` | size 0–3, each in allowed set, no duplicates |
| `slideTypeOverrides` keys | each key is a known `slideType` AND appears in `context.slides` |
| `slideTypeOverrides` values | `layoutKind` allowed; `bulletStyle` / `columns` optional but valid if present |
| Coherence | if `layoutProfile` is `timeline_flow`, overrides should include `timeline` with `layoutKind` bullets or body_text if timeline slides exist |
| Coherence | if `layoutProfile` is `split_narrative` and `comparison` slides exist, overrides should prefer `two_column` for comparison |

Coherence checks are **warnings promoted to violations** in v1 (strict).

---

## Wiring (minimal, in `business`)

After module exists:

1. `business/build.gradle.kts` → `implementation(project(":section-style"))`
2. `SectionStyleConfiguration` → `@Bean fun sectionStylePlanner(adapter: LlmAdapter): SectionStylePlanner`
3. `PptGenerationService.planSectionStyle(context)` and `planSectionStyles(outline, themeColors?)` returning `Map<String, SectionStyleResult>`

**Do not** call from HTTP `stage=pptx` in this task (follow-up).

---

## Tests

### `SectionStyleValidatorTest`

- valid fixture passes
- wrong `sectionId` fails
- unknown `layoutProfile` fails
- override key for slideType not in section fails
- invalid `styleName` / short `rationale` fails

### `SectionStylePlannerTest` (fake `LlmAdapter`)

- valid JSON → `Ok`
- markdown-wrapped JSON → `Ok` (if `parseFirstObject` handles it)
- invalid JSON → retries then `ExhaustedRetries`
- validation failure on attempt 1, fixed on attempt 2 → `Ok`

### Fixture

`section-style/src/test/resources/fixtures/python-basics-section-context.json`

---

## Acceptance criteria

- [ ] New `:section-style` Gradle module compiles
- [ ] `SectionContext`, `SectionStyle`, planner, validator implemented
- [ ] English prompt files committed (as above)
- [ ] `SectionStyleValidatorTest` + `SectionStylePlannerTest` green
- [ ] `SectionContextFactory.fromOutline` helper
- [ ] `business` depends on module and exposes `PptGenerationService.planSectionStyles` (thin delegate)
- [ ] `./gradlew :section-style:test :business:test` green
- [ ] **No** `renderer` / `app` HTTP / frontend changes

## Non-goals (this task)

- Applying `SectionStyle` in `PptxWriter` / `TemplatePptxWriter`
- Image / icon generation
- Per-slide style (section-level only in v1)
- Auto-parallel orchestration benchmarks

## Future renderer mapping (reference only)

| `SectionStyle` field | Future `PptxWriter` behavior |
|----------------------|------------------------------|
| `spacing.density` | margin / line spacing multipliers |
| `typography.titleScale` | title font pt |
| `typography.titleAlign` | `TextAlign` |
| `slideTypeOverrides[].layoutKind` | override `SlideLayoutMapper` for this section |
| `decorations` | draw accent bar / timeline spine via POI shapes |

---

## Manual smoke (after wire-up)

```bash
./gradlew :section-style:test
./gradlew :business:test --tests "*SectionStyle*"
```
