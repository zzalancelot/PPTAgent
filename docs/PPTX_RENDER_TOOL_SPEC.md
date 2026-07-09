# PPT Render Tool Spec (Standalone Module)

> **For coding agent:** Create a new **`renderer`** Gradle module that acts as an **agent tool**: **deck JSON file in → `.pptx` file out**. No LLM, no Spring, no dependency on `business` / `app` / `gateway-*`.

## Tool Contract (agent-facing)

```
Input:  path to a JSON file (SlideDeckContent)
Output: path to a `.pptx` file
```

This is intentionally narrow — the same shape as a single-purpose tool in an agent runtime:

| | |
|---|---|
| **Tool name** | `render_pptx` |
| **Input** | `deck.json` — final slide copy for the whole deck |
| **Output** | `deck.pptx` — standard Office Open XML presentation |
| **Side effects** | Writes file to disk only |
| **No** | LLM calls, network, gateway, outline/content generation |

## Input JSON Schema

Primary input is **`SlideDeckContent`** — the artifact produced by segment 4 (content generator).

**Canonical fixture:** `docs/content-test-python-intro-deck.json`

```json
{
  "meta": {
    "topic": "Python 入门 30 分钟",
    "slideCount": 27,
    "language": "zh-CN",
    "modelsUsed": { "opening": "deepseek-flash", ... }
  },
  "slides": [
    {
      "index": 1,
      "sectionId": "opening",
      "slideType": "title",
      "title": "Python 入门 30 分钟",
      "subtitle": "零基础也能学会的编程第一课",
      "bullets": [],
      "speakerNotes": null,
      "bodyText": null
    }
  ]
}
```

### Wrapper tolerance (optional but recommended)

If the input file is a **full pipeline API response** (e.g. `docs/content-test-python-intro.json`), detect a top-level `"content"` object and use that as the deck. If top-level already has `meta` + `slides`, treat the whole file as `SlideDeckContent`.

Do **not** require outline / input fields for rendering.

## Output

- Valid `.pptx` openable in PowerPoint / Keynote
- Slide count must equal `meta.slideCount`
- Speaker notes preserved when `speakerNotes` is non-blank

## Module Layout

Add to `settings.gradle.kts`:

```kotlin
include(..., "renderer")
```

```
renderer/
├── build.gradle.kts
└── src/
    ├── main/kotlin/com/ppt/agent/renderer/
    │   ├── PptRenderTool.kt              # public tool interface
    │   ├── PptRenderToolImpl.kt          # orchestrates parse → validate → render
    │   ├── cli/RenderPptxMain.kt         # CLI entry point
    │   ├── model/DeckDocument.kt         # JSON DTOs (mirror SlideDeckContent)
    │   ├── validate/DeckValidator.kt
    │   ├── layout/SlideLayoutMapper.kt
    │   └── poi/PptxWriter.kt             # Apache POI XSLF implementation
    └── test/
        ├── kotlin/.../PptRenderToolTest.kt
        └── resources/fixtures/valid-deck.json   # copy from docs/content-test-python-intro-deck.json
```

### Dependency rules

| Module | May depend on |
|--------|----------------|
| `renderer` | `framework` (for `Json` helper only), Apache POI, Kotlin stdlib |
| `renderer` | **Must NOT** depend on `business`, `app`, `llm-adapter`, `gateway-*` |

**Do not** create a `business` ↔ `renderer` Gradle cycle. Integration with `PptGenerationService` / HTTP API is **out of scope** for this task (follow-up segment).

### `renderer/build.gradle.kts` (sketch)

```kotlin
plugins {
    kotlin("jvm")
    application
}

application {
    mainClass = "com.ppt.agent.renderer.cli.RenderPptxMainKt"
}

dependencies {
    implementation(project(":framework"))
    implementation("org.apache.poi:poi-ooxml:5.4.1")
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
}
```

Register a Gradle `run` task or use `application` plugin so:

```bash
./gradlew :renderer:run --args="--input docs/content-test-python-intro-deck.json --output build/output/python-intro.pptx"
```

## Public API

Package: `com.ppt.agent.renderer`

```kotlin
/**
 * Agent tool: deck JSON file → pptx file.
 * Stateless; safe to call from CLI, tests, or a future orchestrator.
 */
interface PptRenderTool {
    fun render(inputJson: Path, outputPptx: Path): RenderToolResult
}

sealed class RenderToolResult {
    data class Ok(
        val outputPath: Path,
        val slideCount: Int,
    ) : RenderToolResult()

    data class Err(
        val errors: List<RenderToolError>,
    ) : RenderToolResult()
}

sealed class RenderToolError {
    data class InvalidJson(val message: String) : RenderToolError()
    data class ValidationFailed(val violations: List<String>) : RenderToolError()
    data class UnsupportedSlideType(val index: Int, val slideType: String) : RenderToolError()
    data class IoFailure(val message: String) : RenderToolError()
}
```

`PptRenderToolImpl` flow:

1. Read `inputJson` as UTF-8 text
2. Parse JSON → `DeckDocument` (unwrap `.content` if present)
3. `DeckValidator.validate(deck)` — collect all violations
4. `PptxWriter.write(deck, outputPptx)` via Apache POI
5. Return `Ok` or `Err`

## Layout mapping (pure rules, no LLM)

Package: `com.ppt.agent.renderer.layout`

```kotlin
enum class SlideLayoutKind {
    TITLE, AGENDA, SECTION_DIVIDER, BULLETS, TWO_COLUMN, BODY_TEXT, CLOSING,
}

object SlideLayoutMapper {
    fun map(slideType: String): SlideLayoutKind
}
```

| `slideType` | Layout |
|-------------|--------|
| `title` | `TITLE` |
| `agenda` | `AGENDA` |
| `section_divider` | `SECTION_DIVIDER` |
| `content`, `case_study`, `framework`, `timeline`, `summary` | `BULLETS` |
| `comparison` | `TWO_COLUMN` |
| `code_or_demo`, `quote` | `BODY_TEXT` |
| `call_to_action` | `CLOSING` |
| `qa` | `TITLE` |

Unknown type → `RenderToolError.UnsupportedSlideType`.

## Visual theme (v1 — programmatic)

- Widescreen 16:9 (13.333 × 7.5 in)
- Dark background, light text, one accent color
- CJK font: `PingFang SC` with fallback `Microsoft YaHei`
- Code blocks: `Menlo` / `Consolas`
- Draw slides from scratch with POI `XMLSlideShow` + text boxes (no external `.pptx` template in v1)

## Validation rules

| Rule | |
|------|---|
| `slides.size == meta.slideCount` | required |
| Indices `1..slideCount` contiguous, unique | required |
| Each `title` non-blank | required |
| `meta.topic` non-blank | required |

## CLI

`RenderPptxMain`:

```
Usage: render-pptx --input <deck.json> --output <deck.pptx>

Exit code 0 on success, 1 on error (print errors to stderr as JSON or plain text).
```

Example:

```bash
./gradlew :renderer:run --args="--input docs/content-test-python-intro-deck.json --output build/output/python-intro.pptx"
open build/output/python-intro.pptx
```

## Tests (no network)

1. **`DeckValidatorTest`** — valid fixture passes; bad index/count fails
2. **`SlideLayoutMapperTest`** — all known `slideType` values map
3. **`PptRenderToolTest`** — end-to-end: fixture JSON → temp `.pptx` → reopen with POI, assert `slides.size == 27` and first slide title contains `"Python"`

Copy fixture: `docs/content-test-python-intro-deck.json` → `renderer/src/test/resources/fixtures/valid-deck.json`

## Acceptance Criteria

- [ ] New `renderer` module, included in `settings.gradle.kts`
- [ ] `PptRenderTool` + `PptRenderToolImpl` + CLI main
- [ ] Input: `docs/content-test-python-intro-deck.json` → Output: valid `.pptx`
- [ ] Accepts wrapped JSON with top-level `"content"` field
- [ ] No dependency on `business` / `app` / gateway / LLM
- [ ] `./gradlew :renderer:test` green
- [ ] `./gradlew build` still green (do not break existing modules)

## Non-Goals (this task)

- Wire into `PptGenerationService` or `POST /v1/ppt/run?stage=pptx`
- LLM, outline, content generation
- External template `.pptx` on classpath
- Images / icons / charts

## Manual verification

```bash
./gradlew :renderer:run --args="--input docs/content-test-python-intro-deck.json --output build/output/python-intro.pptx"
```

Open `build/output/python-intro.pptx` in PowerPoint or Keynote.
