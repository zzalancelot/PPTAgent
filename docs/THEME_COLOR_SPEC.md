# Theme Color Picker Spec (Morandi palette from outline)

> **For coding agent:** Implement in the **`business`** module. One LLM call per deck, **after** `OutlineJson` is ready, **before** render. No renderer changes in this task — only produce and validate a 5-color palette the renderer can consume later.

## Goal

Today every deck looks like it came from the same mold because `PptxWriter` uses hard-coded dark blues. Fix the **business** side first: given a validated `OutlineJson`, ask the LLM to pick **5 Morandi-style colors** that match the deck's topic, audience, tone, and narrative arc.

Output: a JSON object with a **`colors` array of length exactly 5** — each entry a `#RRGGBB` hex string.

## Pipeline position

```
PptInput → OutlineJson → ThemeColorPicker → colors[5] → (future) PptxWriter / TemplatePptxWriter
```

Wire into `PptGenerationService` (method name suggestion: `pickThemeColors`). HTTP `stage=pptx` can call this after content generation and pass colors into the renderer in a follow-up task.

## Interface (sketch)

Package: `com.ppt.agent.business.theme`

```kotlin
interface ThemeColorPicker {
    fun pick(outline: OutlineJson, model: GatewayModel = GatewayModel.DEEPSEEK_FLASH): ThemeColorResult
}

sealed class ThemeColorResult {
    data class Ok(val palette: ThemePalette) : ThemeColorResult()
    data class Err(val errors: List<ThemeColorError>) : ThemeColorResult()
}

/** Five Morandi colors, index semantics fixed (see below). */
data class ThemePalette(
    val colors: List<String>, // size == 5, each #RRGGBB
)

sealed class ThemeColorError {
    data class LlmFailure(val message: String) : ThemeColorError()
    data class InvalidJson(val message: String, val attempt: Int) : ThemeColorError()
    data class ValidationFailed(val violations: List<String>, val attempt: Int) : ThemeColorError()
    data class ExhaustedRetries(val attempts: Int, val lastError: String) : ThemeColorError()
}
```

### Index semantics (document in code comments)

| Index | Role | Usage (future renderer) |
|-------|------|------------------------|
| 0 | `background` | Slide background fill |
| 1 | `surface` | Cards, secondary panels, agenda blocks |
| 2 | `accent` | Titles, section dividers, key highlights |
| 3 | `accentMuted` | Subtitles, borders, decorative shapes |
| 4 | `textPrimary` | Body bullets and main readable text on background |

Colors must stay **Morandi**: muted, low-to-medium saturation, gray or dusty undertones — harmonious as a set, not five unrelated brights.

## Model & retry

- **Model:** `DEEPSEEK_FLASH` (single short call; cheap, fast).
- **Retries:** mirror `OutlinePlannerImpl` — up to 3 attempts; on parse failure retry; on validation failure append user feedback and retry **without** bumping tokens.
- **`max_tokens`:** 512 is enough.
- **No parallel calls** — one palette per deck.

## Prompt files

Classpath:

- `business/src/main/resources/prompts/theme_color_system.txt`
- `business/src/main/resources/prompts/theme_color_user.txt`

Placeholders in user prompt:

- `{topic}`, `{audience}`, `{tone}`, `{narrative_arc}`, `{one_liner}`, `{language}`
- `{storyline_json}` — compact JSON of `storyline` (hook, promise, beats, audienceMotivation)
- `{sections_json}` — array of `{id, title, purpose}` only (no per-slide spam)

---

## `theme_color_system.txt` (copy verbatim)

```
You are a presentation color designer specializing in Morandi palettes.

## Task
Given a deck outline (topic, audience, tone, narrative arc, storyline), choose exactly FIVE colors that feel tailored to THIS deck — not a generic corporate template.

## Morandi style (required)
- Muted, desaturated, soft — colors with gray or dusty undertones.
- Low-to-medium saturation; avoid neon, pure primaries (#FF0000, #00FF00), and hyper-saturated blues.
- The five colors must harmonize as one cohesive palette (analogous or closely related hues), not five unrelated accents.
- Each deck should feel visually distinct: a Kyoto travel deck ≠ a Rust rewrite pitch ≠ a Python tutorial.

## Color roles (array order is fixed)
Return colors in this exact order:
0. background — main slide background (darkest or lightest anchor of the set)
1. surface — secondary panels / cards (slightly different from background)
2. accent — titles, section dividers, key highlights
3. accentMuted — subtitles, borders, decorative elements
4. textPrimary — body text and bullets; MUST be readable on `background` (aim for WCAG-ish contrast; if background is dark, textPrimary should be light, and vice versa)

## Selection guidance
- Let topic + narrativeArc drive the hue family (e.g. warm earth for coffee / travel, cool blue-gray for tech, soft green-gray for nature, dusty rose for personal story).
- Respect `tone` and `audience` (CEO persuasion → restrained and authoritative; beginner tutorial → warm and approachable).
- Do NOT default to the same blue-gray stack for every deck.

## Output
Return ONLY valid JSON (no markdown, no commentary):

{
  "colors": ["#RRGGBB", "#RRGGBB", "#RRGGBB", "#RRGGBB", "#RRGGBB"]
}

Rules:
- Exactly 5 strings.
- Each string must match `#` + 6 hexadecimal digits (uppercase or lowercase OK).
- No other keys.
```

---

## `theme_color_user.txt` (copy verbatim)

```
Pick a Morandi palette for this presentation outline.

topic: {topic}
audience: {audience}
tone: {tone}
narrative_arc: {narrative_arc}
one_liner: {one_liner}
language: {language}

## Storyline
{storyline_json}

## Sections (structure only)
{sections_json}

Return ONLY the JSON object with a `colors` array of length 5.
```

---

## Validator (`ThemeColorValidator`)

Pure Kotlin, no LLM. Collect all violations.

| Check | Rule |
|-------|------|
| Array length | `colors.size == 5` |
| Hex format | each matches `^#[0-9A-Fa-f]{6}$` |
| Not identical | not all 5 hex values the same |
| Not generic default | reject if colors are exactly the current renderer defaults `#1E1E2E,#1E1E2E,#89B4FA,#A6ADC8,#CDD6F4` (optional guard) |
| Contrast hint | if `background` (index 0) luminance < 0.35, `textPrimary` (index 4) luminance should be > 0.55; if background luminance > 0.65, textPrimary luminance should be < 0.45 (simple relative luminance helper is enough) |
| Morandi heuristic | average saturation across the 5 colors should be ≤ 0.45 in HSL (reject neon stacks) |

On validation failure → retry with feedback listing violations (same pattern as `OutlinePlannerImpl.validationFeedback`).

## Tests

`ThemeColorValidatorTest`:

- valid 5-color palette passes
- 4 or 6 colors fails
- bad hex (`#GGG`, `1E1E2E`, `#12345`) fails
- neon all-high-saturation set fails heuristic

`ThemeColorPickerTest` with fake `LlmAdapter`:

- returns parsed palette on valid JSON
- retries once when first response has 4 colors, second has 5

## Acceptance criteria

- [ ] `theme_color_system.txt` + `theme_color_user.txt` committed (English, as above)
- [ ] `ThemeColorPicker` + `ThemeColorValidator` in `business`
- [ ] `PptGenerationService.pickThemeColors(outline)` wired
- [ ] Unit tests green; `./gradlew :business:test`
- [ ] **No** renderer / frontend changes in this task

## Non-goals

- Applying colors in `PptxWriter` (follow-up)
- Image or font pairing
- Per-slide color variation

## Manual smoke (after wire-up)

```bash
# After outline exists, call picker in a test main or integration test with valid-outline.json fixture
./gradlew :business:test --tests "*ThemeColorPicker*"
```
