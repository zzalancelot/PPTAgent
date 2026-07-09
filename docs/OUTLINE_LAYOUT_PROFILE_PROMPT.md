# Outline Layout Profile — Implementation Spec

> **For coding agent:** Add **`layoutProfile`** to each `OutlineSection` in the outline pipeline. The LLM chooses a per-chapter **basic layout/style seed** at outline-planning time so different decks and different chapters within a deck can produce visually diverse PPT typesetting later. **Implement models, validator, tests, and verify prompt files. Do NOT implement renderer changes in this task.**

---

## 1. Background

Today every deck uses the same programmatic layout in the renderer (left title, standard bullets, fixed margins). **Theme colors** (`ThemeColorPicker`) already vary per deck, but **layout rhythm and chapter personality** still feel like one template.

**Design decision (agreed):** assign a coarse **`layoutProfile`** on each outline **section (chapter)** when the outline is planned — **not** via a separate LLM module per section. Planning a chapter already requires deciding its purpose and slide mix; layout personality belongs in that same step.

**Future (out of scope here):** the renderer (or an optional `SectionStyleRefiner`) may expand `layoutProfile` into fine-grained typography/spacing overrides. This task only plants the seed in `OutlineJson`.

---

## 2. Pipeline position

```
PptInput
  → OutlinePlanner (DEEPSEEK_PRO, existing retry loop)
  → OutlineJson
       sections[].layoutProfile   ← NEW
  → SlideContentGenerator
  → ThemeColorPicker
  → (future) Renderer reads layoutProfile per slide's section
```

No new Gradle module. All changes live in **`business`** (+ optional frontend type display).

---

## 3. JSON contract change

### Before

```json
{
  "id": "basics",
  "title": "Variables & Types",
  "purpose": "Cover core data storage",
  "slideRange": [9, 13]
}
```

### After

```json
{
  "id": "basics",
  "title": "Variables & Types",
  "purpose": "Cover core data storage",
  "slideRange": [9, 13],
  "layoutProfile": "editorial_left"
}
```

`layoutProfile` is **required** on every entry in `sections[]`.

---

## 4. `layoutProfile` enum

| Value | When to use | Typical chapters |
|-------|-------------|------------------|
| `tutorial_friendly` | Warm teaching, approachable spacing, left titles | onboarding, basics, demos |
| `editorial_left` | Magazine-like left-aligned titles, standard bullets | general explanation |
| `centered_impact` | Big centered headlines, breathing room | opening hook, key message |
| `dense_reference` | Compact rhythm, more information per slide | cheat-sheets, reference |
| `split_narrative` | Two-column comparisons | pros/cons, before/after |
| `timeline_flow` | Sequential steps, itinerary, day-by-day | timeline slides, travel days |
| `pitch_bold` | Persuasion, accent-forward dividers | CEO pitch, closing CTA |

### Diversity intent

The LLM must **vary** `layoutProfile` across sections when purposes differ. Do **not** assign one profile to the entire deck unless the deck truly has a single structural chapter type.

Enforced by validator (see §6).

---

## 5. Kotlin model changes

**File:** `business/src/main/kotlin/com/ppt/agent/business/outline/OutlineJson.kt`

```kotlin
data class OutlineSection(
    val id: String,
    val title: String,
    val purpose: String,
    val slideRange: List<Int>,
    /** Coarse per-chapter layout/typesetting seed. See outline_planner_system.txt. */
    val layoutProfile: String,
)
```

Add enum holder (same file or adjacent):

```kotlin
object LayoutProfiles {
    const val TUTORIAL_FRIENDLY = "tutorial_friendly"
    const val EDITORIAL_LEFT = "editorial_left"
    const val CENTERED_IMPACT = "centered_impact"
    const val DENSE_REFERENCE = "dense_reference"
    const val SPLIT_NARRATIVE = "split_narrative"
    const val TIMELINE_FLOW = "timeline_flow"
    const val PITCH_BOLD = "pitch_bold"

    val ALL: Set<String> = setOf(
        TUTORIAL_FRIENDLY, EDITORIAL_LEFT, CENTERED_IMPACT, DENSE_REFERENCE,
        SPLIT_NARRATIVE, TIMELINE_FLOW, PITCH_BOLD,
    )
}
```

**Downstream compile fixes:** any `OutlineSection(...)` constructor call in tests must pass `layoutProfile`. Use `LayoutProfiles.EDITORIAL_LEFT` as a generic default in synthetic test outlines unless the test is layout-specific.

Known call sites:
- `business/src/test/kotlin/com/ppt/agent/business/content/SlideContentValidatorTest.kt`
- `business/src/test/kotlin/com/ppt/agent/business/content/ModelAssignmentPolicyTest.kt`

---

## 6. Validator changes

**File:** `business/src/main/kotlin/com/ppt/agent/business/outline/OutlineValidator.kt`

Add `checkLayoutProfiles(outline, violations)` and call it from `validate()`.

### Rule 9 — valid enum

Every `section.layoutProfile` must be in `LayoutProfiles.ALL`.

### Rule 10 — deck-level diversity

| Section count | Minimum distinct `layoutProfile` values |
|---------------|---------------------------------------|
| 1–2 | 1 (no diversity rule) |
| 3–4 | 2 |
| 5+ | 3 |

Violation examples:
- `"deck with 7 sections must use at least 3 different layoutProfile values (found 1)"`

### Rule 11 — coherence with slide types

For each section, inspect slides whose `index` falls in `section.slideRange` (exclude `section_divider` and `agenda` when counting content slides).

| Condition | Required `layoutProfile` |
|-----------|--------------------------|
| ≥2 `timeline` slides in section | `timeline_flow` or `split_narrative` |
| ≥2 `comparison` slides in section | `split_narrative` or `editorial_left` |

Violation examples:
- `"section 'day2' has 3 timeline slides but layoutProfile 'tutorial_friendly' (prefer timeline_flow or split_narrative)"`

Collect **all** violations (existing validator pattern — do not fail fast).

---

## 7. Prompt files

Classpath:
- `business/src/main/resources/prompts/outline_planner_system.txt`
- `business/src/main/resources/prompts/outline_planner_user.txt`

**These files may already contain partial `layoutProfile` edits.** Your job is to ensure they match the verbatim text below exactly.

### `outline_planner_system.txt` — required content

The JSON `sections[]` entry must include `layoutProfile` in the schema block:

```
{ "id": string, "title": string, "purpose": string, "slideRange": [startIndex, endIndex], "layoutProfile": "tutorial_friendly" | "editorial_left" | "centered_impact" | "dense_reference" | "split_narrative" | "timeline_flow" | "pitch_bold" }
```

Rules section must include (in addition to existing rules 1–7):

```
8. Every section MUST include layoutProfile — the basic visual/layout personality for that chapter (see below).
9. Vary layoutProfile across sections when purposes differ; do NOT assign the same profile to every section unless the deck truly has only one structural chapter type.
10. JSON only.
```

Add a **Section layoutProfile** block with the table and diversity rules from §4 of this document (copy from the existing prompt file if already present).

### `outline_planner_user.txt` — required constraint line

Add to the Constraints list:

```
- Each section must have a layoutProfile that fits its purpose and slide mix; vary profiles across chapters for visual diversity
```

No new placeholders. Existing: `{topic}`, `{brief}`, `{audience}`, `{slide_count}`.

---

## 8. Test plan

### 8.1 Update fixture

**File:** `business/src/test/resources/outline/valid-outline.json`

Add `layoutProfile` to all 7 sections. Suggested values (diverse, passes rules 10–11):

| Section id | layoutProfile |
|------------|---------------|
| `opening` | `centered_impact` |
| `setup` | `tutorial_friendly` |
| `basics` | `editorial_left` |
| `control` | `dense_reference` |
| `functions` | `tutorial_friendly` |
| `project` | `split_narrative` |
| `closing` | `pitch_bold` |

Distinct count = 6 (≥3 required for 7 sections). ✓

### 8.2 `OutlineValidatorTest` — add cases

| Test | Expect |
|------|--------|
| `valid outline loads` | still passes (after fixture update) |
| `rejectsUnknownLayoutProfile` | `layoutProfile = "neon_future"` → violation |
| `rejectsUniformLayoutProfilesWhenManySections` | all sections `editorial_left` on 7-section deck → violation |

### 8.3 `OutlinePlannerTest`

No new cases required unless a fake response fixture omits `layoutProfile` — ensure existing valid fixture includes the field after §8.1.

### 8.4 Run

```bash
./gradlew :business:test
```

All business tests must pass.

---

## 9. Optional frontend (low priority)

If time permits, surface `layoutProfile` in the outline preview:

| File | Change |
|------|--------|
| `frontend/src/api.ts` | `OutlineSection.layoutProfile: string` |
| `frontend/src/components/OutlineView.tsx` | show a tag next to each section |

**Not required for acceptance** if backend tests are green.

---

## 10. Sync `docs/OUTLINE_AGENT_SPEC.md`

Ensure `OUTLINE_AGENT_SPEC.md` documents:
- `OutlineSection.layoutProfile` in schema
- `LayoutProfiles` enum
- Validator rules 9–11
- Pipeline note that sections carry `layoutProfile`

(That file may already be partially updated — align it with this spec.)

---

## 11. Non-goals

- **No renderer changes** (`PptxWriter`, `SlideLayoutMapper`, theme application per section)
- **No new `section-style` Gradle module** (deferred — see `docs/SECTION_STYLE_SPEC.md`)
- **No HTTP API contract change** — `layoutProfile` flows through existing `outline` JSON in API responses automatically once the model parses it
- **No changes** to `SlideContentGenerator` prompts or validation

---

## 12. Acceptance criteria

- [ ] `OutlineSection` has required `layoutProfile: String`
- [ ] `LayoutProfiles` object with `ALL` set
- [ ] `OutlineValidator` enforces rules 9, 10, 11
- [ ] `outline_planner_system.txt` and `outline_planner_user.txt` match §7
- [ ] `valid-outline.json` updated with diverse profiles
- [ ] All `OutlineSection(...)` test constructors updated
- [ ] New validator tests added
- [ ] `./gradlew :business:test` green
- [ ] Renderer untouched

---

## 13. Example outline section output (reference)

```json
"sections": [
  {
    "id": "opening",
    "title": "Opening & Goals",
    "purpose": "Build trust, set expectations, spark interest",
    "slideRange": [1, 4],
    "layoutProfile": "centered_impact"
  },
  {
    "id": "day1_kyoto",
    "title": "Day 1 — Kyoto Temples",
    "purpose": "Walk through the day's itinerary in order",
    "slideRange": [5, 10],
    "layoutProfile": "timeline_flow"
  },
  {
    "id": "closing",
    "title": "Summary & Next Steps",
    "purpose": "Recap value and drive action",
    "slideRange": [26, 27],
    "layoutProfile": "pitch_bold"
  }
]
```

---

## 14. Mental model for the LLM

When planning each section, ask:

1. **What is this chapter trying to do?** (teach, compare, persuade, sequence steps…)
2. **What slideTypes dominate in `slideRange`?** (timeline → `timeline_flow`; comparison → `split_narrative`)
3. **How should this chapter *feel* different from the previous one?** Pick a different `layoutProfile` when the structural role differs.

The seed is intentionally **coarse** — one enum per chapter — to keep outline JSON compact and avoid extra LLM calls.
