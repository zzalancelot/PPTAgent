# PPT Render Dual-Mode Spec (Programmatic + Template)

> **For coding agent:** Extend the existing **`renderer`** module to support **two render modes**, selected by a **`RenderMode` enum** on the tool API and CLI. Keep backward compatibility: default mode stays **programmatic** (current behavior).

## Context

The `renderer` module already exists with:

- `PptRenderTool` / `PptRenderToolImpl`
- `PptxWriter` — mode **A** (Apache POI, draw from scratch)
- CLI: `./gradlew :renderer:run --args="--input ... --output ..."`
- Fixture: `docs/content-test-python-intro-deck.json`

**Your task:** add mode **B** (template fill) and route by enum. Do **not** wire into `business` / `app` / HTTP.

---

## Render modes

```kotlin
package com.ppt.agent.renderer

/**
 * How deck JSON is turned into a .pptx file.
 *
 * PROGRAMMATIC — draw slides with POI text boxes (existing PptxWriter).
 * TEMPLATE     — open a bundled/custom .pptx template and fill placeholders.
 */
enum class RenderMode {
    /** Mode A: code-drawn slides (current default). */
    PROGRAMMATIC,

    /** Mode B: fill an on-disk / classpath master template. */
    TEMPLATE,
}
```

| Mode | Enum | Implementation class | When to use |
|------|------|----------------------|-------------|
| A | `PROGRAMMATIC` | existing `PptxWriter` | fast, no template file, v1 default |
| B | `TEMPLATE` | new `TemplatePptxWriter` | better visuals from a designed master |

---

## Updated public API

```kotlin
interface PptRenderTool {
    /**
     * @param mode render strategy; defaults to [RenderMode.PROGRAMMATIC] for backward compat.
     * @param templatePath required when [mode] is [RenderMode.TEMPLATE] unless the bundled
     *        default template is used. Ignored for [RenderMode.PROGRAMMATIC].
     */
    fun render(
        inputJson: Path,
        outputPptx: Path,
        mode: RenderMode = RenderMode.PROGRAMMATIC,
        templatePath: Path? = null,
    ): RenderToolResult
}
```

Introduce a small strategy interface (same module):

```kotlin
internal interface DeckRenderer {
    fun write(deck: DeckDocument, output: Path)
}

// PptRenderToolImpl picks DeckRenderer based on RenderMode:
//   PROGRAMMATIC → PptxWriter
//   TEMPLATE     → TemplatePptxWriter(templatePath)
```

Add errors:

```kotlin
sealed class RenderToolError {
    // ... existing ...
    data class TemplateNotFound(val path: String) : RenderToolError()
    data class TemplateLayoutMissing(val layoutKind: String) : RenderToolError()
}
```

---

## CLI

Extend `RenderPptxMain`:

```
Usage: render-pptx --input <deck.json> --output <deck.pptx> [--mode programmatic|template] [--template <path.pptx>]
```

| Flag | Default | Notes |
|------|---------|-------|
| `--mode` / `-m` | `programmatic` | Case-insensitive; map to `RenderMode` |
| `--template` / `-t` | bundled resource | Only used when `--mode template` |

Examples:

```bash
# Mode A (unchanged behavior)
./gradlew :renderer:run --args="--input docs/content-test-python-intro-deck.json --output build/output/a.pptx"

# Mode B — bundled template
./gradlew :renderer:run --args="--mode template --input docs/content-test-python-intro-deck.json --output build/output/b.pptx"

# Mode B — custom template path
./gradlew :renderer:run --args="--mode template --template path/to/my-template.pptx --input docs/content-test-python-intro-deck.json --output build/output/c.pptx"
```

Invalid `--mode` → exit 1 with clear stderr message.

---

## Mode A — PROGRAMMATIC (keep as-is)

- **Do not regress** existing `PptxWriter` behavior or tests.
- Refactor only if needed to implement `DeckRenderer` interface.

---

## Mode B — TEMPLATE (new)

### Bundled template file

Add a real `.pptx` template to the repo:

```
renderer/src/main/resources/templates/deck-template.pptx
```

**You must create this file** (commit it). Options (pick one):

1. **Recommended:** Use POI in a one-off `TemplateGenerator` main or test (`@Disabled` / `manual`) to generate `deck-template.pptx` with a slide master containing **7 slide layouts**, then commit the binary; or
2. Build it manually in PowerPoint and commit — layouts must match the index table below.

Default template path when `templatePath == null`:

```kotlin
javaClass.getResource("/templates/deck-template.pptx")
```

### Template layout contract

The template slide master must expose **7 layouts** in a fixed order (index 0–6 on the first slide master):

| Index | `SlideLayoutKind` | Placeholders to fill |
|-------|-------------------|----------------------|
| 0 | `TITLE` | title (large), subtitle (optional) |
| 1 | `AGENDA` | title, body (numbered bullets) |
| 2 | `SECTION_DIVIDER` | title, subtitle (optional) |
| 3 | `BULLETS` | title, body (bullets) |
| 4 | `TWO_COLUMN` | title, left body, right body |
| 5 | `BODY_TEXT` | title, body (monospace-friendly for code) |
| 6 | `CLOSING` | title, subtitle (optional), body (bullets) |

`TemplatePptxWriter` flow:

1. Open template `XMLSlideShow(InputStream)`
2. **Remove** any sample slides shipped in the template (start from 0 content slides)
3. For each `SlideDocument` (sorted by `index`):
   - `kind = SlideLayoutMapper.map(slideType)`
   - `layout = master.getLayoutLayout(kind.toLayoutIndex())`
   - `slide = ppt.createSlide(layout)`
   - Fill placeholders from `title`, `subtitle`, `bullets`, `bodyText` (same field rules as mode A)
   - Attach `speakerNotes` when present (same best-effort as `PptxWriter`)
4. Write to `outputPptx`

Helper:

```kotlin
fun SlideLayoutKind.toLayoutIndex(): Int = when (this) {
    SlideLayoutKind.TITLE -> 0
    SlideLayoutKind.AGENDA -> 1
    // ...
}
```

### Placeholder filling rules (mirror mode A semantics)

| Layout | Fields |
|--------|--------|
| TITLE / SECTION_DIVIDER / QA→TITLE | `title`, `subtitle` |
| AGENDA / BULLETS / SUMMARY / CLOSING | `title`, `bullets` (bullet paragraphs) |
| TWO_COLUMN | `title`, bullets split left/right (same split as `PptxWriter`) |
| BODY_TEXT | `title`, `bodyText` or joined `bullets` (code slides use monospace if template allows) |

Use POI `XSLFSlide.placeholders` / `Placeholder.TITLE`, `Placeholder.BODY`, etc. If a expected placeholder is missing, return `TemplateLayoutMissing`.

### Template visual quality (minimum bar)

- Widescreen 16:9
- Coherent dark or light theme (your choice, but must look **intentionally designed**, not blank white)
- Distinct section-divider layout vs content layout
- Template file size reasonable (< 500 KB)

---

## Shared logic (both modes)

Keep using:

- `DeckValidator`
- `SlideLayoutMapper`
- `RootDocument.toDeck()` unwrap of `"content"`

Both writers must produce **the same slide count** as `meta.slideCount` for the same fixture.

---

## Tests

Extend / add tests in `renderer/src/test`:

### 1. `PptRenderToolTest`

| Test | Mode | Assert |
|------|------|--------|
| `programmaticModeStillDefault` | default / explicit `PROGRAMMATIC` | 27 slides, first title contains `Python` |
| `templateModeWithBundledTemplate` | `TEMPLATE`, `templatePath=null` | 27 slides, file exists, size > 0 |
| `templateModeWithExplicitTemplatePath` | `TEMPLATE`, path to bundled resource copied to temp | same |
| `templateModeMissingFile` | `TEMPLATE`, bad path | `Err(TemplateNotFound)` |
| `cliAcceptsModeFlag` | CLI `--mode template` | exit 0 |

### 2. `TemplatePptxWriterTest` (new)

- Load fixture deck, render template mode, reopen pptx
- Assert slide count == 27
- Assert at least one slide has non-empty title text matching fixture

### 3. Do not break existing tests

`./gradlew :renderer:test` must stay green.

---

## Acceptance criteria

- [ ] `RenderMode` enum (`PROGRAMMATIC`, `TEMPLATE`)
- [ ] `PptRenderTool.render(..., mode, templatePath?)` with **default `PROGRAMMATIC`**
- [ ] CLI `--mode` and optional `--template`
- [ ] `TemplatePptxWriter` + committed `templates/deck-template.pptx`
- [ ] Mode A behavior unchanged
- [ ] Both modes render `docs/content-test-python-intro-deck.json` → valid 27-slide `.pptx`
- [ ] `./gradlew :renderer:test` and `./gradlew build` green
- [ ] Still **no** dependency on `business` / `app` / gateway

## Non-goals

- HTTP API / `PptGenerationService` integration
- LLM calls
- Images, charts, icons beyond what the template background provides
- Auto-selecting mode from JSON (mode is **tool/CLI enum param only** in this task)

## Manual verification

```bash
./gradlew :renderer:run --args="--mode programmatic --input docs/content-test-python-intro-deck.json --output build/output/prog.pptx"
./gradlew :renderer:run --args="--mode template --input docs/content-test-python-intro-deck.json --output build/output/tmpl.pptx"
open build/output/prog.pptx
open build/output/tmpl.pptx
```

Compare visual difference between the two files.
