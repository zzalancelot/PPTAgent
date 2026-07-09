# Renderer Layout Profile — Implementation Spec

> **For coding agent:** Make the **programmatic** PPTX renderer apply each slide’s chapter **`layoutProfile`** (seeded on `OutlineSection` at outline time). Pass profile hints from the app export JSON into `DeckDocument`, resolve a per-slide layout recipe, and change typography / spacing / alignment / light decorations in `PptxWriter`. **No LLM calls. TEMPLATE mode can ignore profiles in v1. Do not change outline prompts or ThemeColorPicker.**

---

## 1. Background

Outline planning now assigns a required `layoutProfile` on every `OutlineSection` (see `docs/OUTLINE_LAYOUT_PROFILE_PROMPT.md`). That seed currently stops at outline JSON — **`PptxWriter` still uses one hard-coded rhythm** (left titles, fixed margins, fixed font sizes).

Theme colors already vary per deck via `meta.themeColors`. This task adds the parallel path for **layout diversity per chapter**.

```
OutlineSection.layoutProfile
        │
        ▼
PptxExportService embeds sectionLayouts (or per-slide layoutProfile)
        │
        ▼
DeckDocument → LayoutProfileTokens.resolve(profile)
        │
        ▼
PptxWriter draws with tokens (PROGRAMMATIC only)
```

---

## 2. Scope

| In scope | Out of scope |
|----------|--------------|
| `renderer` token + apply in `PptxWriter` | `TemplatePptxWriter` profile support |
| Deck JSON wiring from `app` export | New Gradle module / SectionStyle LLM refiner |
| Unit tests for tokens + writer differences | Changing outline / content / theme LLM prompts |
| Fallback when profile missing / unknown | Frontend redesign (optional tag OK) |

---

## 3. Data flow — how profile reaches the renderer

### Problem

`SlideDeckContent` / `SlideDocument` has `sectionId` but **not** `layoutProfile`. Section profiles live on `OutlineJson.sections`. The renderer module must **not** depend on `business.outline` types (keep renderer independent of outline models).

### Solution

Embed a compact map on deck `meta` when exporting:

```json
{
  "meta": {
    "topic": "...",
    "slideCount": 27,
    "language": "zh-CN",
    "modelsUsed": { ... },
    "themeColors": ["#...", "..."],
    "sectionLayouts": {
      "opening": "centered_impact",
      "setup": "tutorial_friendly",
      "basics": "editorial_left"
    }
  },
  "slides": [
    { "index": 1, "sectionId": "opening", "slideType": "title", ... }
  ]
}
```

**Resolution per slide:**

```
layoutProfile =
  meta.sectionLayouts[slide.sectionId]
  ?: DEFAULT_PROFILE   // "editorial_left"
```

If `sectionId` is null/blank → `DEFAULT_PROFILE`.  
If `sectionLayouts` is absent (`null` / `{}`) → all slides use `DEFAULT_PROFILE` (backward compatible with old deck JSON fixtures).

### Alternative (also acceptable)

Stamp `layoutProfile` onto each `SlideDocument` at export time. Prefer **`sectionLayouts` map** to avoid duplicating the same string on every slide and to keep content DTOs unchanged.

---

## 4. App wiring

### 4.1 `PptxExportService`

**File:** `app/src/main/kotlin/com/ppt/agent/app/output/PptxExportService.kt`

Change signature to accept section layouts:

```kotlin
fun render(
    deck: SlideDeckContent,
    topic: String,
    themeColors: List<String>,
    sectionLayouts: Map<String, String> = emptyMap(),
): PptxExportResult
```

In `deckJson(...)`, add:

```kotlin
"sectionLayouts" to sectionLayouts,
```

(omit when empty **or** always include — either OK; prefer always include map for predictable tests.)

### 4.2 `PptApiController` (stage `pptx`)

When outline is available, build:

```kotlin
val sectionLayouts = outline.sections.associate { it.id to it.layoutProfile }
pptxExportService.render(deck, input.topic, themeResult.palette.colors, sectionLayouts)
```

### 4.3 CLI / tests that call export without outline

Pass `emptyMap()` → renderer falls back to default profile (legacy behavior).

---

## 5. Renderer model

**File:** `renderer/src/main/kotlin/com/ppt/agent/renderer/model/DeckDocument.kt`

```kotlin
data class DeckMeta(
    val topic: String?,
    val slideCount: Int?,
    val language: String?,
    val modelsUsed: Map<String, String>? = null,
    val themeColors: List<String>? = null,
    /** sectionId → layoutProfile (from OutlineSection). Optional. */
    val sectionLayouts: Map<String, String>? = null,
)
```

No change required on `SlideDocument` if using the map approach.

---

## 6. Layout profile tokens

**New file:** `renderer/src/main/kotlin/com/ppt/agent/renderer/layout/LayoutProfileTokens.kt`

```kotlin
/**
 * Resolved typesetting knobs for one layoutProfile.
 * Pure data — no POI.
 */
data class LayoutProfileTokens(
    val profileId: String,
    /** Multiplier on default title font size (e.g. 1.25 = larger titles). */
    val titleScale: Double,
    /** Multiplier on default body/bullet font size. */
    val bodyScale: Double,
    val titleAlign: TitleAlign,
    /** Multiplier on vertical spacing / content top offset. */
    val density: Density,
    /** Multiplier applied to CONTENT_TOP (and related Y positions). */
    val contentTopScale: Double,
    /** Left vertical accent bar for content-like layouts. */
    val leftAccentBar: Boolean,
    /** Thin top rule under title region. */
    val topAccentRule: Boolean,
    /**
     * Soft layout preference override for BULLETS-family slides.
     * null = keep SlideLayoutMapper result unchanged.
     */
    val preferTwoColumnForComparison: Boolean = false,
)

enum class TitleAlign { LEFT, CENTER }

enum class Density { AIRY, STANDARD, DENSE }
```

### Resolve API

```kotlin
object LayoutProfileResolver {
    const val DEFAULT_PROFILE = "editorial_left"

    fun resolve(profileId: String?): LayoutProfileTokens

    /** Resolve for one slide using deck meta + slide.sectionId. */
    fun forSlide(meta: DeckMeta?, sectionId: String?): LayoutProfileTokens {
        val key = sectionId?.takeIf { it.isNotBlank() }
        val fromMap = key?.let { meta?.sectionLayouts?.get(it) }
        return resolve(fromMap)
    }
}
```

Unknown / blank profile → `resolve(DEFAULT_PROFILE)` (do **not** throw).

### Token table (v1 — implement exactly)

Baseline sizes live in `PptxWriter` today: title ≈ 30pt (content), centered title 44 / 40, bullets 20 / 17. Tokens are **multipliers** on those defaults.

| `layoutProfile` | `titleScale` | `bodyScale` | `titleAlign` | `density` | `contentTopScale` | `leftAccentBar` | `topAccentRule` |
|-----------------|--------------|-------------|--------------|-----------|-------------------|-----------------|-----------------|
| `editorial_left` | 1.00 | 1.00 | LEFT | STANDARD | 1.00 | false | false |
| `tutorial_friendly` | 1.00 | 1.05 | LEFT | AIRY | 1.05 | true | false |
| `centered_impact` | 1.20 | 0.95 | CENTER | AIRY | 1.15 | false | true |
| `dense_reference` | 0.90 | 0.90 | LEFT | DENSE | 0.90 | false | false |
| `split_narrative` | 1.00 | 1.00 | LEFT | STANDARD | 1.00 | false | false |
| `timeline_flow` | 1.05 | 1.00 | LEFT | STANDARD | 1.00 | true | false |
| `pitch_bold` | 1.15 | 1.00 | LEFT | STANDARD | 1.05 | true | true |

Notes:
- `split_narrative`: when `slideType == "comparison"`, layout is already `TWO_COLUMN` — keep that; optionally slightly widen column gap (+8px equivalent) if easy.
- `timeline_flow`: still use `BULLETS` for timeline slides in v1; visual difference comes from **accent bar + slightly larger title**, not a new timeline spine shape (timeline spine = optional stretch goal).
- `centered_impact`: for **TITLE / SECTION_DIVIDER / QA** kinds, favor large centered titles (already centered) with **larger scale**; for **BULLETS / AGENDA / CLOSING**, use **centered title** + more top padding (do not center every bullet line — keep bullets left-aligned under a centered title).

---

## 7. Apply tokens in `PptxWriter`

**File:** `renderer/src/main/kotlin/com/ppt/agent/renderer/poi/PptxWriter.kt`

### 7.1 Resolve per slide

In `write` / `renderSlide`:

```kotlin
val tokens = LayoutProfileResolver.forSlide(deck.meta, slide.sectionId)
```

Pass `tokens` into each `render*` path alongside `theme`.

### 7.2 Concrete behaviors

| Area | How to apply |
|------|--------------|
| Title font size | `baseTitleSize * tokens.titleScale` |
| Bullet / body font size | `baseBodySize * tokens.bodyScale` |
| Title alignment | `TextAlign.CENTER` when `tokens.titleAlign == CENTER` for title boxes drawn via `addTitle` / content titles; keep bullets LEFT |
| Content top Y | `CONTENT_TOP * tokens.contentTopScale` (and subtitle Y scaled similarly) |
| Density AIRY | increase bullet `spaceBefore` (e.g. 6 → 10) |
| Density DENSE | decrease bullet `spaceBefore` (e.g. 6 → 3) and allow slightly smaller min box heights |
| `leftAccentBar` | draw a thin rectangle on the left (~6–8px wide, height ≈ slide − 2×margin) filled with `theme.accent` (or `accentMuted` for softer look) |
| `topAccentRule` | draw a 2px-tall horizontal bar under the title region, color `theme.accent`, width ≈ content width |

### 7.3 Mapping override (minimal)

Keep `SlideLayoutMapper` as the primary layout chooser. Do **not** invent new `SlideLayoutKind` values in this task.

Optional coherence:
- If `tokens.profileId == "split_narrative"` and `slideType == "content"` with ≥6 bullets, you may render as two columns — **optional**, skip if complex.

### 7.4 TEMPLATE mode

`TemplatePptxWriter`: **no changes required**. Profiles are ignored until a later task.

---

## 8. Tests

### 8.1 `LayoutProfileResolverTest`

- Known profiles → expected scales / flags from the table
- Unknown / null → defaults to `editorial_left` tokens
- `forSlide` reads `sectionLayouts` by `sectionId`
- Missing map / missing key → default

### 8.2 `PptxWriterLayoutProfileTest` (or extend `PptxWriterThemeTest`)

- Two decks identical content/theme, different `sectionLayouts` → both write successfully; assert output files differ in size **or** (preferred) spy/inspect that accent shapes / midpoints differ without opening PowerPoint
- Minimal approach: call writer twice (default vs `centered_impact` / `dense_reference`) and assert both `Ok` / files non-empty and **byte lengths unequal**

Use a tiny fixture deck (2–3 slides) with `sectionId`s.

### 8.3 App / export (optional)

- Unit test `deckJson` includes `sectionLayouts` when provided — only if easy without Spring context. Skip if costly.

### 8.4 Run

```bash
./gradlew :renderer:test :app:test
# or at least
./gradlew :renderer:test
```

If app tests fail due to new `render(...)` parameter, update call sites with default / explicit map.

---

## 9. Acceptance criteria

- [ ] `DeckMeta.sectionLayouts` field added
- [ ] `LayoutProfileTokens` + `LayoutProfileResolver` implemented with the exact table in §6
- [ ] `PptxWriter` applies titleScale, bodyScale, titleAlign, contentTopScale, density spacing, leftAccentBar, topAccentRule
- [ ] `PptxExportService.render` accepts and embeds `sectionLayouts`
- [ ] `PptApiController` stage `pptx` passes `outline.sections.associate { it.id to it.layoutProfile }`
- [ ] Missing / unknown profile → silent default (`editorial_left`)
- [ ] TEMPLATE mode unchanged
- [ ] No business outline / content / theme prompt changes
- [ ] `./gradlew :renderer:test` green (and app compiles)

---

## 10. Non-goals

- Per-slide LLM style refinement (`SECTION_STYLE_SPEC.md`)
- True timeline spine / iconography
- Template / master slide rewriting
- Changing Morandi color picker rules
- Frontend mandatory UI (optional TypeScript field on outline already optional from prior task)

---

## 11. Implementation order (recommended)

1. `LayoutProfileTokens` + `LayoutProfileResolver` + unit tests
2. `DeckMeta.sectionLayouts`
3. Thread tokens through `PptxWriter` + accent shapes
4. Writer difference test
5. `PptxExportService` + `PptApiController` wiring
6. Fix compile breakages at call sites
7. Run renderer (and app) tests

---

## 12. Design intent (for reviewers)

Users should be able to open two chapters of the same deck and **feel** a difference: opening (`centered_impact`) breathes more with larger centered titles; dense chapters pack tighter; teaching chapters get a soft accent bar; pitch closings look slightly bolder. Colors still come only from theme palette — layout profiles must **not** invent new colors outside `RenderTheme`.
