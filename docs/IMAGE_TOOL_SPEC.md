# Image Tool Spec (Gateway-routed, Agent Tool)

> **For coding agent:** Implement an **`image-tool`** module plus **gateway extensions** and an **`image-adapter`** seam. **No hardcoded provider URLs or API keys in `image-tool`.** LLM prompt planning uses the existing **`LlmAdapter` → gateway** path; image generation/stock fetch uses a new **`ImageAdapter` → image gateway client** path.

## Goal

Agent tool: given a deck (and optional outline with `visualHint`), produce **on-disk images** + an **`image-manifest.json`** for the renderer to embed later.

```
deck.json [+ outline.json] → image-tool → assets/*.jpg + image-manifest.json
```

Kyoto / travel decks benefit from real landmark photos (stock) + AI mood images (generate).

---

## Architecture (mirror LLM layering)

```
image-tool
  ├─ planning  → LlmAdapter → gateway-client (chat)     # prompt expansion ONLY
  └─ fetch       → ImageAdapter → image-gateway-client → gateway-server → providers

renderer (future) reads image-manifest.json — out of scope here
```

### Dependency rules

| Module | May depend on |
|--------|----------------|
| `image-tool` | `framework`, `llm-adapter`, `image-adapter` |
| `image-adapter` | `framework`, `image-gateway-client` |
| `image-gateway-client` | `framework`, `gateway-api` |
| `gateway-server` | providers (MiniMax Image, Unsplash, …) |
| `image-tool` | **Must NOT** depend on `gateway-client`, `gateway-api`, `business`, Spring AI, okhttp to providers directly |

LLM calls: **only** through `LlmAdapter`.  
Image/stock calls: **only** through `ImageAdapter`.

Retries, slide selection, and concurrency live in **`image-tool`**, never in adapters.

---

## Phase 1 — Framework contracts

Package: `com.ppt.agent.framework`

### `GatewayImageModel` enum

```kotlin
enum class GatewayImageModel(val id: String) {
    /** MiniMax Image-01 text-to-image (configure in gateway-server YAML). */
    MINIMAX_IMAGE("minimax-image"),

    /** Unsplash search → download URL (configure access key in gateway-server YAML). */
    UNSPLASH("unsplash"),
    ;

    companion object {
        fun fromId(id: String): GatewayImageModel = ...
    }
}
```

### Image DTOs

```kotlin
data class ImageGenerateRequest(
    val prompt: String,
    val width: Int = 1024,
    val height: Int = 768,   // 4:3 good for slide right rail
    val model: GatewayImageModel = GatewayImageModel.MINIMAX_IMAGE,
    val paramOverrides: Map<String, String> = emptyMap(),
)

data class StockImageRequest(
    val query: String,
    val orientation: String = "landscape", // unsplash: landscape|portrait|squarish
    val model: GatewayImageModel = GatewayImageModel.UNSPLASH,
)

data class ImageAsset(
    val bytes: ByteArray,
    val mimeType: String,           // image/jpeg, image/png
    val sourceUrl: String? = null,  // provider CDN URL
    val attribution: String? = null,// "Photo by X on Unsplash" — required for stock
    val resolvedModel: String,      // wire capability id
)
```

### `ImageClient` (framework interface)

```kotlin
interface ImageClient {
    fun generate(request: ImageGenerateRequest): ImageAsset
    fun fetchStock(request: StockImageRequest): ImageAsset
}
```

---

## Phase 2 — Gateway API (proto)

Extend `gateway-api/src/main/proto/ppt/gateway/v1/model_gateway.proto` **or** add `image_gateway.proto` in the same package.

```protobuf
service ImageGateway {
  rpc GenerateImage(GenerateImageRequest) returns (GenerateImageResponse);
  rpc FetchStockImage(FetchStockImageRequest) returns (GenerateImageResponse);
  rpc ListImageCapabilities(ListImageCapabilitiesRequest) returns (ListImageCapabilitiesResponse);
}

message GenerateImageRequest {
  string capability = 1;              // GatewayImageModel id
  string prompt = 2;
  int32 width = 3;
  int32 height = 4;
  map<string, string> param_overrides = 5;
}

message FetchStockImageRequest {
  string capability = 1;            // "unsplash"
  string query = 2;
  string orientation = 3;
}

message GenerateImageResponse {
  bytes image_data = 1;
  string mime_type = 2;
  string source_url = 3;
  string attribution = 4;
  ResolvedCapability resolved = 5;
}
```

Register `ImageGatewayService` on the **same gRPC port** (`9090`) as `ModelGateway`.

---

## Phase 3 — Gateway server

### YAML (`gateway-server/src/main/resources/application.yaml`)

Add alongside `gateway.models`:

```yaml
gateway:
  images:
    minimax-image:
      provider: minimax
      base-url: https://api.minimax.io          # verify against MiniMax Image-01 docs
      model: image-01                           # verify official model id
      api-key: ${ai.keys.minimax:${MINIMAX_API_KEY:}}
      params:
        timeout_seconds: "120"
    unsplash:
      provider: unsplash
      base-url: https://api.unsplash.com
      api-key: ${ai.keys.unsplash:${UNSPLASH_ACCESS_KEY:}}
      params:
        per_page: "1"
```

Add `ai.keys.unsplash` to `ai-keys.yaml.example` (gitignored example file).

### Server components

| Class | Role |
|-------|------|
| `ImageCapabilityRegistry` | maps capability id → provider config (like `CapabilityRegistry`) |
| `MinimaxImageProvider` | calls MiniMax Image-01 REST API |
| `UnsplashImageProvider` | `GET /search/photos?query=...`, download `urls.regular` |
| `ImageGatewayService` | gRPC impl; metrics `gateway.image.requests`, `gateway.image.latency` |

**No Spring AI for image** unless it fits; direct REST is fine **inside gateway-server only**.

Expose ops: `GET /health/image-capabilities` (like `/health/capabilities`).

---

## Phase 4 — `image-gateway-client` module

New Gradle module (or extend `gateway-client` with a separate `ImageClient` bean — prefer **separate `image-gateway-client`** to keep chat client unchanged).

```kotlin
class GatewayImageClient(
    private val stub: ImageGatewayGrpc.ImageGatewayBlockingStub,
) : ImageClient {
    override fun generate(request: ImageGenerateRequest): ImageAsset = ...
    override fun fetchStock(request: StockImageRequest): ImageAsset = ...
}
```

`ImageGatewayClientConfiguration` — same host/port as `gateway.client.*`.

Add to `settings.gradle.kts`:

```kotlin
include(..., "image-gateway-client", "image-adapter", "image-tool")
```

---

## Phase 5 — `image-adapter` module

Mirror `llm-adapter`:

```kotlin
interface ImageAdapter {
    fun generate(request: ImageGenerateRequest): ImageAsset
    fun fetchStock(request: StockImageRequest): ImageAsset
}

class PassthroughImageAdapter(
    private val imageClient: ImageClient,
) : ImageAdapter {
    override fun generate(request: ImageGenerateRequest) = imageClient.generate(request)
    override fun fetchStock(request: StockImageRequest) = imageClient.fetchStock(request)
}
```

`ImageAdapterConfiguration` for Spring apps; `image-tool` CLI can also construct `PassthroughImageAdapter(GatewayImageClient(...))` manually if not using Spring in CLI.

---

## Phase 6 — `image-tool` module

### Tool contract

```kotlin
enum class ImageSourceStrategy {
    /** Rules + outline visualHint; landmarks → stock, mood → AI. */
    AUTO,

    /** All selected slides via ImageAdapter.generate (AI). */
    AI_ONLY,

    /** All selected slides via ImageAdapter.fetchStock. */
    STOCK_ONLY,
}

interface DeckImageTool {
    fun resolve(
        deckJson: Path,
        outlineJson: Path?,
        outputDir: Path,
        options: DeckImageOptions,
    ): DeckImageResult
}

data class DeckImageOptions(
    val strategy: ImageSourceStrategy = ImageSourceStrategy.AUTO,
    val maxImages: Int = 10,
    val llmModel: GatewayModel = GatewayModel.DEEPSEEK_FLASH,  // prompt planning via LlmAdapter
    val aiImageModel: GatewayImageModel = GatewayImageModel.MINIMAX_IMAGE,
    val stockModel: GatewayImageModel = GatewayImageModel.UNSPLASH,
    val planPromptsWithLlm: Boolean = true,
)
```

### Input files

1. **`deck.json`** — `SlideDeckContent` (same as renderer). Supports wrapped `"content"` root.
2. **`outline.json`** (optional) — `OutlineJson` with per-slide `visualHint`. Supports wrapped `"outline"` root from full API response.

If outline missing: plan from `slide.title` + `slideType` only.

### Slide selection (business rules in `image-tool`)

Select up to `maxImages` slides, priority order:

1. `slideType == title`
2. `slideType == section_divider`
3. slides with non-blank `visualHint` (from outline, matched by `index`)
4. `slideType in (content, case_study)` with travel/place keywords in title (Kyoto fixture)

Never image every slide in v1.

### Source routing (`AUTO` strategy)

| Signal | Source |
|--------|--------|
| `visualHint` or title contains landmark/place patterns (寺, 神社, 打卡, 景点, Kyoto, 清水寺, …) | **STOCK** (`fetchStock`) |
| `slideType == title` or `section_divider` | **AI generate** (mood/hero) |
| `code_or_demo`, `agenda` | **SKIP** |

Implement `ImageSourceRouter` as pure Kotlin (unit-tested).

### Prompt planning (via `LlmAdapter`, NOT hardcoded LLM)

When `planPromptsWithLlm == true` and source is AI:

1. Build messages from classpath `prompts/image_prompt_system.txt` + user template with `{title}`, `{visualHint}`, `{topic}`, `{slideType}`.
2. `llmAdapter.chat(messages, emptyList(), options.llmModel, paramOverrides = mapOf("max_tokens" to "512"))`
3. Parse JSON: `{ "prompt": "...", "negativePrompt": null }` (use `Json.extractFirstJsonObject`)
4. On parse failure: fallback to rule-based prompt = `"$topic, $visualHint, presentation slide illustration, no text"` (no extra LLM retry in v1 beyond one attempt)

Stock queries: rule-based from title + visualHint (e.g. `"Fushimi Inari Kyoto"`), optional LLM polish behind same flag.

### Output

```
build/assets/kyoto-weekend/
  slide-01.jpg
  slide-03.jpg
  ...
  image-manifest.json
```

```json
{
  "topic": "周末两天玩遍京都",
  "images": [
    {
      "slideIndex": 1,
      "path": "slide-01.jpg",
      "source": "ai",
      "capability": "minimax-image",
      "prompt": "...",
      "attribution": null
    },
    {
      "slideIndex": 8,
      "path": "slide-08.jpg",
      "source": "stock",
      "capability": "unsplash",
      "query": "Fushimi Inari Kyoto",
      "attribution": "Photo by ..."
    }
  ]
}
```

### Concurrency

```kotlin
object ImageToolConfig {
    const val MAX_PARALLEL_FETCHES = 4
}
```

Semaphore around each `ImageAdapter` call. Failures for one slide **do not** abort the whole manifest (record error entry); v1 continues.

### CLI

```bash
./gradlew :image-tool:run --args="\
  --deck docs/content-kyoto-deck.json \
  --outline docs/content-kyoto-outline.json \
  --output-dir build/assets/kyoto \
  --strategy auto \
  --max-images 10 \
  --llm-model deepseek-flash \
  --image-model minimax-image"
```

Flags: `--deck`, `--outline` (optional), `--output-dir`, `--strategy`, `--max-images`, `--llm-model`, `--image-model`, `--no-llm-plan` (rule-only prompts).

`application` plugin; `workingDir = rootProject.projectDir` (same as renderer).

---

## Prompt files

```
image-tool/src/main/resources/prompts/
  image_prompt_system.txt
  image_prompt_user.txt
```

System prompt instructs: English or bilingual image prompt, no text in image, landscape 4:3, style suitable for travel deck, safe for presentation.

---

## Tests

### Unit (no network)

| Test | Module |
|------|--------|
| `ImageSourceRouterTest` | image-tool — Kyoto landmarks → stock |
| `SlideImageSelectorTest` | image-tool — max 10, priority |
| `DeckImageToolTest` | image-tool — fake `LlmAdapter` + fake `ImageAdapter` |
| `PassthroughImageAdapterTest` | image-adapter |
| `ImageCapabilityRegistryTest` | gateway-server |

Fakes return 1×1 PNG bytes.

### Integration (optional, `@Disabled`)

Live call to gateway with keys — manual only.

---

## Wiring for CLI (no Spring required in v1)

`image-tool` main:

1. Build gRPC channel to `localhost:9090` (read env `GATEWAY_HOST` / `GATEWAY_PORT` or defaults)
2. `PassthroughImageAdapter(GatewayImageClient(stub))`
3. `PassthroughLlmAdapter(GatewayModelClient(...), ...)` — reuse client wiring pattern from `gateway-client` tests or small `ImageToolBootstrap` helper in `image-tool`

**Do not** duplicate provider HTTP in `image-tool`.

---

## Acceptance criteria

- [ ] `GatewayImageModel`, `ImageClient`, request/response DTOs in `framework`
- [ ] Proto + `ImageGatewayService` on gRPC `:9090`
- [ ] `gateway-server` YAML entries for `minimax-image` + `unsplash`
- [ ] `image-gateway-client` + `image-adapter` passthrough
- [ ] `image-tool` CLI: deck (+ optional outline) → manifest + files
- [ ] LLM prompt planning via **`LlmAdapter` only**
- [ ] Image fetch via **`ImageAdapter` only**
- [ ] `./gradlew :image-tool:test :image-adapter:test :gateway-server:test` green
- [ ] `./gradlew build` green
- [ ] Manual: gateway running + keys → run against Kyoto fixture paths (document in README snippet)

## Non-goals

- Embed images into `.pptx` (renderer follow-up reads manifest)
- Hardcode MiniMax/Unsplash HTTP in `image-tool`
- Call `ModelClient` / `gateway-client` directly from `image-tool` (use `LlmAdapter`)
- Generate images for all 27 slides
- Midjourney or Gamma/Tome

## Fixtures

Add or reference:

- `business/src/test/resources/fixtures/05-kyoto-weekend.json` (input)
- After content run: `docs/content-kyoto-deck.json` (placeholder OK — tests use minimal fake deck + outline with `visualHint`)

---

## Manual smoke test

```bash
# terminal 1
./gradlew :gateway-server:bootRun

# terminal 2 — after deck exists
./gradlew :image-tool:run --args="--deck docs/content-test-python-intro-deck.json --output-dir build/assets/test --max-images 3 --strategy ai_only"
```

For Kyoto, use deck + outline with `visualHint` and `--strategy auto`.
