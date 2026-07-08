# PPT Input JSON Parser Spec

> **For coding agent:** Implement in the `business` module. Builds on existing `PptGenerationService` stub.

## Goal

Parse and validate the PPT generation input JSON (`topic`, `brief`, `audience`, optional `slide_count`) — the first step of the business pipeline before outline/LLM work.

## Input Contract

```json
{
  "topic": "主题",
  "brief": "简介（≤500 字）",
  "audience": "目标受众",
  "slide_count": 27
}
```

| Field | Type | Rules |
|-------|------|-------|
| `topic` | string | required, non-blank after trim |
| `brief` | string | required, non-blank after trim, max **500 characters** (`String.length`) |
| `audience` | string | required, non-blank after trim |
| `slide_count` | int | **optional**, default **27** when absent; must be in **25–30** (inclusive) |

Extra JSON keys: **ignore** (forward-compatible).

`slide_count` is passed downstream to the outline planner — the planner must produce exactly that many slides.

## Domain Model

Package: `com.ppt.agent.business.input`

```kotlin
companion object {
    const val DEFAULT_SLIDE_COUNT = 27
    const val MIN_SLIDE_COUNT = 25
    const val MAX_SLIDE_COUNT = 30
}

data class PptInput(
    val topic: String,
    val brief: String,
    val audience: String,
    val slideCount: Int = DEFAULT_SLIDE_COUNT,
)

sealed class PptInputError {
    data class InvalidJson(val message: String) : PptInputError()
    data class MissingField(val field: String) : PptInputError()
    data class BlankField(val field: String) : PptInputError()
    data class BriefTooLong(val length: Int, val max: Int = 500) : PptInputError()
    data class InvalidSlideCount(val value: Int, val min: Int = MIN_SLIDE_COUNT, val max: Int = MAX_SLIDE_COUNT) : PptInputError()
    data class SlideCountWrongType(val actual: String) : PptInputError()
}

sealed class ParseResult {
    data class Ok(val input: PptInput) : ParseResult()
    data class Err(val errors: List<PptInputError>) : ParseResult()
}
```

Use trimmed strings in `PptInput`.

## Parser Interface

```kotlin
interface PptInputParser {
    fun parse(json: String): ParseResult
    fun parseFromFile(path: java.nio.file.Path): ParseResult
}
```

`parseFromFile`: read UTF-8 text, delegate to `parse`. Map `IOException` → `ParseResult.Err(listOf(InvalidJson(...)))`.

## Implementation: `PptInputParserImpl`

- Use `com.ppt.agent.framework.Json.parse()` to parse JSON object
- Expect top-level `Map<*, *>`; else `InvalidJson`
- Extract `topic`, `brief`, `audience`, `slide_count`; validate all fields, collect **all** errors (don't fail fast)
- `slide_count` parsing:
  - absent / `null` → use `DEFAULT_SLIDE_COUNT` (27)
  - number → validate 25–30
  - non-integer (e.g. `"27"`, `27.5`) → `SlideCountWrongType`
  - out of range → `InvalidSlideCount`
- Return `ParseResult.Ok` only when zero errors

No Spring AI, no gateway, no LLM.

## Wire into `PptGenerationService`

Extend the existing interface:

```kotlin
interface PptGenerationService {
    fun pingLlm(model: GatewayModel): String
    fun parseInput(json: String): ParseResult
}
```

`PptGenerationServiceImpl` delegates `parseInput` to `PptInputParser`.

Register `PptInputParser` bean in `BusinessConfiguration`.

## Fixture Files

Add `business/src/test/resources/fixtures/` with 5 JSON files matching the public dev set:

| File | topic |
|------|-------|
| `01-python-intro.json` | Python 入门 30 分钟 |
| `02-year-review.json` | 2025 我的年度复盘 |
| `03-coffee-beans.json` | 如何挑选一款适合自己的咖啡豆 |
| `04-rust-rewrite.json` | 给老板讲清楚为什么我们应该用 Rust 重写订单系统 |
| `05-kyoto-weekend.json` | 周末两天玩遍京都 |

Use exact `topic`, `brief`, `audience` values from the exam doc. **Omit `slide_count`** in all 5 fixtures (tests default to 27).

Add optional fixture `06-custom-slide-count.json` with `"slide_count": 25` for explicit override testing.

## Tests (`business` module, no Spring)

### `PptInputParserTest`

1. Each of 5 fixtures → `ParseResult.Ok` with `slideCount == 27`
2. Missing `topic` → `MissingField("topic")`
3. Blank `audience` (whitespace only) → `BlankField("audience")`
4. `brief` with 501 chars → `BriefTooLong(501)`
5. Invalid JSON `{` → `InvalidJson`
6. Extra keys → still `Ok`
7. Multiple errors in one payload → `Err` contains all errors
8. `"slide_count": 25` → `Ok`, `slideCount == 25`
9. `"slide_count": 30` → `Ok`, `slideCount == 30`
10. `"slide_count": 24` → `InvalidSlideCount(24)`
11. `"slide_count": 31` → `InvalidSlideCount(31)`
12. `"slide_count": "27"` → `SlideCountWrongType`
13. `"slide_count": null` with valid other fields → `Ok`, `slideCount == 27`

### `PptGenerationServiceTest`

- Verify `parseInput` delegates to parser (fake parser or real impl)

## App Smoke (optional)

Update `SmokeTestRunner`: if first CLI arg is a `.json` file path, call `pptGenerationService.parseInput(...)` and log `PptInput` (including `slideCount`). Otherwise keep existing `pingLlm` smoke.

```
./gradlew :app:bootRun --args="business/src/test/resources/fixtures/01-python-intro.json"
```

## Module Constraints

- All code in `business` module
- Dependencies unchanged: `framework`, `llm-adapter` only
- Reuse `framework.Json` — do not add Jackson dependency to business

## Acceptance Criteria

- [ ] `PptInput`, `PptInputParser`, `PptInputParserImpl` implemented
- [ ] Validation rules enforced (required, non-blank, brief ≤ 500, slide_count 25–30 or default 27)
- [ ] 5 fixture JSON files + optional `06-custom-slide-count.json` committed
- [ ] Unit tests cover fixtures + slide_count cases
- [ ] `PptGenerationService.parseInput` wired
- [ ] `./gradlew :business:test` and `./gradlew build` green

## Non-Goals

- Outline generation, LLM calls, `.pptx` output
- CLI framework (just optional arg in SmokeTestRunner)
- JSON Schema file / OpenAPI
