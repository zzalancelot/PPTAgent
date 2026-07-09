# Slide Content Density Spec (Richer On-Slide Copy)

> **For coding agent:** Increase per-slide content richness in the **content generation** layer (`business` module): prompts + validator + tests. Optionally adjust `renderer` `PptxWriter` to display new fields. **No LLM architecture changes.**

## Problem

Current slides feel sparse because:

1. `slide_content_system.txt` asks for **2–4 tight bullet phrases** and discourages sentences/paragraphs.
2. `SlideContentValidator` **rejects** decks with fewer than 2 or more than 4 bullets on content-like slides.
3. `subtitle` and `bodyText` are underused; most pages are title + 3 short bullets only.

Goal: each slide should **expand around its theme** (`outline.intent` + `bulletHints`) with enough on-slide material that the page feels substantive — not a bare outline card.

**Not the goal:** turn slides into essay walls; still presentation-ready (scannable, not a blog post).

---

## Content model (per slide type)

### Field roles

| Field | Role |
|-------|------|
| `title` | Slide headline (unchanged) |
| `subtitle` | **One sentence** framing why this page matters / angle on the theme |
| `bullets` | Main on-slide points — **complete short thoughts**, not telegram stubs |
| `bodyText` | Optional **1–3 sentences** bridging context, analogy, or “so what” (when bullets alone are thin) |
| `speakerNotes` | **4–6 sentences** for the presenter: example, demo cue, transition (not duplicated verbatim on slide) |

### Density rules by `slideType`

| `slideType` | `subtitle` | `bullets` | `bodyText` | `speakerNotes` |
|-------------|--------------|-----------|------------|----------------|
| `title` | required (tagline) | empty OK | null | optional 1–2 sentences |
| `section_divider` | required | empty OK | null | optional |
| `agenda` | null | **5–7** items | null | 2–3 sentences |
| `content`, `framework`, `case_study`, `timeline` | **required** (theme angle) | **4–6** | optional 1–3 sentences | **required** 4–6 sentences |
| `comparison` | recommended | **4–6** (split left/right OK) | optional | required 4–6 sentences |
| `code_or_demo` | recommended (what we’re running) | **3–5** steps/lines | optional setup line | required (walkthrough) |
| `summary` | optional | **5–6** recap bullets | optional 1 sentence wrap-up | required |
| `call_to_action` | recommended | **3–5** | optional | required |
| `quote` | attribution in subtitle | 0–1 | quote text in `bodyText` | optional |
| `qa` | optional | empty OK | null | optional |

### Bullet writing style (prompt instruction)

Each bullet should:

- Relate directly to `outline.intent` and at least one `bulletHints` entry (expand, don’t ignore hints)
- Be a **readable phrase or short sentence** (Chinese ~12–40 chars; English ~8–20 words)
- Add **one layer** beyond the hint: definition, example, “why it matters”, or common pitfall
- **Not** copy `bulletHints` verbatim

Example (hint → bullet):

- Hint: `"变量是贴了标签的盒子"`
- Bad: `"变量 = 盒子"` (too thin)
- Good: `"变量像贴了名字的盒子，用来存放年龄、姓名等数据，之后可随时取用"`

---

## Prompt changes

### `business/src/main/resources/prompts/slide_content_system.txt`

Replace sparse rules with:

- Remove “2–4 tight bullets” and “NOT full sentences”
- Add the density table above (condensed)
- Instruct: **expand the slide’s theme** using `intent` + `bulletHints` from `slide_json`
- `subtitle` = one-sentence “why listen to this slide”
- Prefer **4–6 bullets** for content-like types; use `bodyText` when a short paragraph clarifies the concept
- `speakerNotes` = 4–6 spoken sentences with example/demo/transition
- Still: no forbidden terms; stay consistent with deck tone; return JSON only

### `business/src/main/resources/prompts/slide_content_user.txt`

Add explicit instruction block:

```
Expand this slide's theme for the audience. Use outline intent and bulletHints as seeds —
each bullet should teach one incremental idea. Do not leave the slide with only 2–3 thin lines.
```

---

## Validator changes

File: `SlideContentValidator.kt`

Introduce configurable ranges (constants at top):

```kotlin
object ContentDensityRules {
    const val CONTENT_BULLETS_MIN = 4
    const val CONTENT_BULLETS_MAX = 6
    const val AGENDA_BULLETS_MIN = 5
    const val AGENDA_BULLETS_MAX = 7
    const val SUMMARY_BULLETS_MIN = 5
    const val SUMMARY_BULLETS_MAX = 6
    const val CODE_BULLETS_MIN = 3
    const val CODE_BULLETS_MAX = 5
    const val SPEAKER_NOTES_MIN_CHARS = 80   // ~4 short Chinese sentences
    const val SUBTITLE_MIN_CHARS = 8
}
```

Update `validateSlide`:

| Check | Types |
|-------|-------|
| bullets in `4..6` | `CONTENT_LIKE` (content, comparison, timeline, framework, case_study) |
| bullets in `3..5` | `code_or_demo` |
| bullets in `5..7` | `agenda` |
| bullets in `5..6` | `summary` |
| `subtitle` non-blank, len ≥ 8 | content, framework, case_study, timeline, comparison |
| `speakerNotes` non-blank, len ≥ 80 | content-like + summary + call_to_action + code_or_demo |
| `bullets` empty OK | title, section_divider, qa |
| max bullets cap | raise to **8** for agenda/summary (was 6 global) |

Keep index/title checks unchanged.

**Retry behavior:** validation failures already append user feedback in `SlideContentGeneratorImpl` — no change needed.

---

## Renderer display (same PR if small)

File: `renderer/.../PptxWriter.kt` (and `TemplatePptxWriter` if dual-mode exists)

Ensure richer JSON **shows on slide**, not only in notes:

1. **Always render `subtitle`** under title on BULLETS / BODY_TEXT / AGENDA layouts (already partial — verify all layouts)
2. **Render `bodyText`** between title/subtitle and bullets when non-blank (muted smaller font)
3. If bullet count > 5, use **slightly smaller bullet font** (e.g. 17pt → 15pt) to reduce overflow
4. Do **not** truncate bullets silently — prefer smaller font first

If `renderer` dual-mode task is in flight, apply the same rules to `TemplatePptxWriter`.

---

## Tests

### `SlideContentValidatorTest`

- Update existing 2–4 bullet tests to 4–6 ranges
- Add: content slide with 3 bullets → fails
- Add: content slide without subtitle → fails
- Add: content slide without speakerNotes → fails
- Add: valid rich slide with subtitle + 5 bullets + notes → passes

### `SlideContentGeneratorTest`

- Update fixture `single-slide-response.json` to match new density (5 bullets, subtitle, longer notes)
- If fake adapter returns thin content, retry loop should still be tested

### Optional integration

Re-run content generation on `python-intro-user-input.json` is **manual** (costs LLM); unit tests use fakes only.

---

## Acceptance criteria

- [ ] `slide_content_system.txt` + `slide_content_user.txt` updated for richer copy
- [ ] `SlideContentValidator` enforces new density rules
- [ ] `SlideContentValidatorTest` updated and green
- [ ] `SlideContentGeneratorTest` fixture updated and green
- [ ] `PptxWriter` shows `subtitle` + `bodyText` on slide when present (if touched)
- [ ] `./gradlew :business:test :renderer:test` green

## Non-goals

- Change outline planner hint counts (optional follow-up)
- Change slide count or LLM retry/token logic
- Auto-summarize long text with a second LLM pass

## Manual check (after deploy)

```bash
curl -s -X POST "http://localhost:8080/v1/ppt/run?stage=content&outlineModel=deepseek-pro&contentModel=deepseek-flash" \
  -H 'Content-Type: application/json' \
  -d @business/src/test/resources/fixtures/python-intro-user-input.json \
  | jq '.content.slides[3] | {title, subtitle, bullets: (.bullets|length), bodyText, speakerNotes}'
```

Expect slide 4 (first real content): subtitle present, **≥4 bullets**, speakerNotes several sentences.
