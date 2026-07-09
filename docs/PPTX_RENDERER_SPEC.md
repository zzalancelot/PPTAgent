# PPTX Renderer Spec (Segment 5)

> **For coding agent:** Implement a new `renderer` Gradle module + wire into `business` and `app`. **No LLM calls** — consumes `SlideDeckContent` only.

## Goal

Turn validated `SlideDeckContent` (27 slides of final copy) into a **real `.pptx`** file that opens in PowerPoint / Keynote. v1 uses **programmatic layouts** (Apache POI XSLF) and a fixed **deck theme** — no external `.pptx` template file yet.

## Pipeline Position

```
PptInput → OutlineJson → SlideDeckContent → PptxRenderer → .pptx file
```

## New Module: `renderer`

Add to `settings.gradle.kts`:

```kotlin
include("framework", ..., "renderer", "app")
```

### Dependencies (`renderer/build.gradle.kts`)

```kotlin
plugins {
    kotlin("jvm")
}

dependencies {
    implementation(project(":framework"))   // only if needed; prefer zero framework dep
    implementation(project(":business"))      // for SlideDeckContent types — OR duplicate-free: move shared DTOs to framework (do NOT refactor; depend on :business for SlideDeckContent)
    implementation("org.apache.poi:poi-ooxml:5.4.1")
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
}
```

**Module rule:** `renderer` depends on `business` (for `SlideDeckContent` / `SlideContent`). `business` depends on `renderer` (for `PptxRenderer` interface usage). This creates a cycle — **resolve as follows:**

- Put the **interface** `PptxRenderer` + `RenderResult` in `business` package `com.ppt.agent.business.render`
- Put the **implementation** `PptxRendererImpl` in `renderer` module
- `business/build.gradle.kts`: `compileOnly` or no dep on renderer; `BusinessConfiguration` does NOT `@Bean` the impl
- `app` module: depends on both `business` and `renderer`; `@Import(RendererConfiguration::class)` wires the bean

```
app → business (interface + orchestration)
app → renderer (POI implementation)
renderer → business (SlideDeckContent types)
```

## Interface (business layer)

Package: `com.ppt.agent.business.render`

```kotlin
interface PptxRenderer {
    fun render(deck: SlideDeckContent, outputPath: Path): RenderResult
}

sealed class RenderResult {
    data class Ok(val path: Path, val slideCount: Int) : RenderResult()
    data class Err(val errors: List<RenderError>) : RenderResult()
}

sealed class RenderError {
    data class ValidationFailed(val violations: List<String>) : RenderError()
    data class IoFailure(val message: String) : RenderError()
    data class UnsupportedSlideType(val index: Int, val slideType: String) : RenderError()
}
```

Wire into `PptGenerationService`:

```kotlin
fun renderPptx(deck: SlideDeckContent, outputPath: Path): RenderResult
```

`PptGenerationServiceImpl` delegates to injected `PptxRenderer`.

## Layout mapping (no LLM)

Package: `com.ppt.agent.business.render` (pure Kotlin, unit-testable)

```kotlin
enum class SlideLayoutKind {
    TITLE,
    AGENDA,
    SECTION_DIVIDER,
    BULLETS,
    TWO_COLUMN,
    BODY_TEXT,
    CLOSING,
}

object SlideLayoutMapper {
    /** Maps outline/content [SlideContent.slideType] to a layout kind. */
    fun map(slideType: String): SlideLayoutKind
}
```

### `slideType` → layout table

| `slideType` | `SlideLayoutKind` | On-slide fields used |
|-------------|-------------------|----------------------|
| `title` | `TITLE` | `title`, `subtitle` |
| `agenda` | `AGENDA` | `title`, `bullets` (numbered) |
| `section_divider` | `SECTION_DIVIDER` | `title`, `subtitle` |
| `content`, `case_study`, `framework`, `timeline` | `BULLETS` | `title`, `subtitle?`, `bullets` |
| `comparison` | `TWO_COLUMN` | `title`, `bullets` split left/right |
| `code_or_demo` | `BODY_TEXT` | `title`, `subtitle?`, `bullets` or `bodyText` (monospace block) |
| `quote` | `BODY_TEXT` | `title`, `bodyText` or first bullet (centered, italic) |
| `summary` | `BULLETS` | `title`, `bullets` |
| `call_to_action` | `CLOSING` | `title`, `subtitle?`, `bullets` |
| `qa` | `TITLE` | `title` (= "Q&A" style), `subtitle?` |

Unknown `slideType` → `RenderError.UnsupportedSlideType` (fail fast, no silent default).

## Theme tokens (v1 — programmatic)

Package: `com.ppt.agent.renderer.theme` (implementation module)

```kotlin
data class DeckTheme(
    val slideWidthInches: Double = 13.333,
    val slideHeightInches: Double = 7.5,
    val backgroundRgb: IntArray,       // e.g. dark navy
    val titleRgb: IntArray,
    val bodyRgb: IntArray,
    val accentRgb: IntArray,
    val titleFontPt: Double = 36.0,
    val subtitleFontPt: Double = 20.0,
    val bodyFontPt: Double = 18.0,
    val bulletFontPt: Double = 16.0,
    val codeFontPt: Double = 14.0,
    /** CJK-friendly; fall back gracefully if missing on host OS. */
    val fontFamily: String = "PingFang SC",
    val codeFontFamily: String = "Menlo",
)

object DefaultThemes {
    val DARK: DeckTheme = ...   // primary v1 theme: dark bg, light text, teal accent
}
```

- Draw shapes/text boxes with POI `XSLFTextShape` / `XSLFTextBox`
- Section divider: large accent bar + big title
- Title slide: centered title + subtitle
- Bullets: left-aligned title bar + bullet list (`•` or `1.` for agenda)
- `TWO_COLUMN`: split bullets — first half left, second half right (if odd count, extra bullet goes left)
- `BODY_TEXT`: monospace block for code; wrap long lines

## Speaker notes

For every slide, if `speakerNotes` is non-null/non-blank, write to POI slide notes:

```kotlin
slide.notes?.createTextBody()?.setText(speakerNotes)
```

## Pre-render validation

`SlideDeckValidator` in `business` (or `renderer`):

| Rule | Error |
|------|-------|
| `deck.slides.size == deck.meta.slideCount` | violation message |
| Indices `1..slideCount` contiguous, no duplicates | violation |
| Every slide `title` non-blank | violation per index |
| `BULLETS` / `AGENDA` layouts: at least 1 bullet OR allow empty only for `title` / `section_divider` | violation |

Collect **all** violations before failing (same pattern as input parser).

## Output path

```kotlin
object RenderOutputConfig {
    const val OUTPUT_DIR = "build/output"
}
```

- Create `build/output/` if missing
- Filename: slugified `deck.meta.topic` + timestamp, e.g. `python-intro-30min-20260708-120000.pptx`
- Slug: lowercase, replace non-alphanumeric with `-`, max 40 chars

`PptxRendererImpl.render(deck, outputPath)` writes to the given path (parent dirs created).

## Implementation (`PptxRendererImpl`)

Package: `com.ppt.agent.renderer`

1. Validate deck
2. `XMLSlideShow()` with theme slide dimensions
3. For each `SlideContent` in index order:
   - `layoutKind = SlideLayoutMapper.map(slide.slideType)`
   - Create blank slide (no reliance on bundled PPT master — draw from scratch)
   - Apply layout renderer strategy per kind
   - Write speaker notes
4. `Files.createDirectories(outputPath.parent)`
5. `show.write(OutputStream)` → `RenderResult.Ok`

**Non-goals v1:** images, icons, charts, animations, master-template `.pptx`, custom fonts embedding.

## App wiring

`renderer/src/main/kotlin/com/ppt/agent/renderer/RendererConfiguration.kt`:

```kotlin
@Configuration(proxyBeanMethods = false)
class RendererConfiguration {
    @Bean
    fun pptxRenderer(): PptxRenderer = PptxRendererImpl(theme = DefaultThemes.DARK)
}
```

`PptAgentApplication.kt` — add `@Import(RendererConfiguration::class)`.

`app/build.gradle.kts` — `implementation(project(":renderer"))`.

## HTTP API extension

Extend `POST /v1/ppt/run`:

| `stage` | Behavior |
|---------|----------|
| `pptx` | `parse` → `outline` → `content` → `renderPptx` → return JSON + file path |

Query params (inherit from segment 4):

- `outlineModel` (default `deepseek-pro`)
- `contentModel` (default `deepseek-flash`)

Response additions on success:

```json
{
  "stage": "pptx",
  "status": "ok",
  "pptxPath": "/abs/path/build/output/python-intro-....pptx",
  "pptxSlideCount": 27,
  ...
}
```

Optional: `download=true` query param → return `application/vnd.openxmlformats-officedocument.presentationml.presentation` as attachment instead of JSON (implement if straightforward; otherwise path-only is acceptable for v1).

Update `GET /v1/ppt/health` stages list to include `pptx`.

## Tests (no live API)

### 1. `SlideLayoutMapperTest` (business)

- Every `SlideTypes.ALL` value maps without throwing
- Unknown type throws or returns explicit error

### 2. `SlideDeckValidatorTest` (business)

- Valid fixture passes
- Wrong count / gap in indices fails with multiple violations

### 3. `PptxRendererTest` (renderer)

Use fixture: `business/src/test/resources/content/valid-deck.json`  
(copy from `docs/content-test-python-intro-deck.json` — commit a trimmed or full 27-slide version)

```kotlin
@Test
fun rendersFixtureDeckToPptx() {
    val deck = loadFixture("content/valid-deck.json")
    val out = tempDir.resolve("test.pptx")
    val result = PptxRendererImpl().render(deck, out)
    assertTrue(result is RenderResult.Ok)
    assertTrue(Files.exists(out))
    XMLSlideShow(out.inputStream()).use { show ->
        assertEquals(27, show.slides.size)
        assertTrue(show.slides[0].slideLayout != null) // or check title text in shapes
    }
}
```

Assert **slide 1 title text** contains `"Python"` (from fixture).

### 4. `PptGenerationServiceTest` (business)

- Fake `PptxRenderer` — `renderPptx` delegates with same deck + path

## Acceptance Criteria

- [ ] `renderer` module with Apache POI, no LLM
- [ ] `SlideLayoutMapper` + `SlideDeckValidator`
- [ ] `PptxRendererImpl` produces openable `.pptx` with 27 slides from fixture
- [ ] Speaker notes written when present
- [ ] `PptGenerationService.renderPptx` wired via `app` configuration
- [ ] `POST /v1/ppt/run?stage=pptx` runs full pipeline, returns `pptxPath`
- [ ] `./gradlew :renderer:test :business:test` green
- [ ] `./gradlew build` green

## Non-Goals

- LLM calls, layout AI, image generation
- Gamma / Tome / external SaaS
- Template `.pptx` on classpath (future segment)
- Partial deck on render failure

## Manual smoke test

```bash
# gateway + app running
curl -s -X POST "http://localhost:8080/v1/ppt/run?stage=pptx&outlineModel=deepseek-pro&contentModel=deepseek-flash" \
  -H 'Content-Type: application/json' \
  -d @business/src/test/resources/fixtures/python-intro-user-input.json
```

Open returned `pptxPath` in PowerPoint / Keynote.
