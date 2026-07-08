# Business + LLM Adapter Layer Spec

> **For coding agent:** Read this file and implement. Builds on the existing gateway modules already in this repo.

## Goal

Add two modules between `app` and `gateway-client`:

1. **`business`** — PPT domain logic (placeholder for now)
2. **`llm-adapter`** — seam between business and gateway; **pass-through today**, extension point for future model-capability compensation (extra prompts, multi-step flows)

Business must **never** depend on `gateway-client` or `gateway-api` directly.

## Dependency Graph

```
app
 ├── business          (domain services)
 ├── llm-adapter       (adapter beans + config)
 └── gateway-client    (gRPC client wiring — app layer only)

business     → llm-adapter → framework
llm-adapter  → gateway-client → framework
```

`framework` rule unchanged: no spring-web, no spring-ai.

## Module: `llm-adapter`

Package: `com.ppt.agent.llm.adapter`

### Interface `LlmAdapter`

Single seam for all business LLM calls. Mirrors existing gateway turn API but lives above transport.

```kotlin
interface LlmAdapter {
    fun chat(messages: List<ChatMessage>, tools: List<Tool>, model: GatewayModel): ModelResponse
    fun chatStream(messages: List<ChatMessage>, tools: List<Tool>, model: GatewayModel): Flux<ModelStreamEvent>
}
```

- Reuse `ChatMessage`, `Tool`, `ModelResponse`, `ModelStreamEvent`, `GatewayModel` from `framework`.
- One model turn per call; tool execution stays on caller (same as `ModelClient`).

### Default impl: `PassthroughLlmAdapter`

```kotlin
class PassthroughLlmAdapter(
    private val modelClient: ModelClient,
    private val streamingModelClient: StreamingModelClient,
) : LlmAdapter {
    override fun chat(...) = modelClient.chat(messages, tools, model.id)
    override fun chatStream(...) = streamingModelClient.chatStream(messages, tools, model.id)
}
```

No extra prompts, no retries, no message rewriting — direct delegate.

### Spring wiring: `LlmAdapterConfiguration`

```kotlin
@Configuration
class LlmAdapterConfiguration {
    @Bean
    fun llmAdapter(
        modelClient: ModelClient,
        streamingModelClient: StreamingModelClient,
    ): LlmAdapter = PassthroughLlmAdapter(modelClient, streamingModelClient)
}
```

- `llm-adapter/build.gradle.kts`: depends on `framework`, `gateway-client` (for types only — adapter receives injected clients, does not open gRPC itself).
- No Spring Web. No Spring AI.

### Future extension (document only, do NOT implement now)

`LlmAdapter` is the hook for when models differ in capability, e.g.:

- inject extra system prompts per `GatewayModel`
- multi-step fallback (outline → validate → retry)
- structured-output repair loops

Keep `PassthroughLlmAdapter` as default; future variants can be strategy beans selected by config.

---

## Module: `business`

Package: `com.ppt.agent.business`

PPT domain layer. **Placeholder only** — no outline agent, no `.pptx` yet.

### Interface `PptGenerationService` (stub)

```kotlin
interface PptGenerationService {
    /** Placeholder entry point for future JSON-in → pptx-out pipeline. */
    fun pingLlm(model: GatewayModel): String
}
```

### Impl: `PptGenerationServiceImpl`

- Injects `LlmAdapter` (NOT `ModelClient`)
- `pingLlm`: sends one user message `"ping"`, returns response text or error message
- Proves business → adapter → gateway chain works

### Spring: `BusinessConfiguration`

```kotlin
@Configuration
class BusinessConfiguration {
    @Bean
    fun pptGenerationService(adapter: LlmAdapter): PptGenerationService =
        PptGenerationServiceImpl(adapter)
}
```

- `business/build.gradle.kts`: depends on `framework`, `llm-adapter` only. **No** `gateway-client`.

---

## Update `app` module

Wire the new layers:

```kotlin
@Import(
    GatewayClientConfiguration::class,
    LlmAdapterConfiguration::class,
    BusinessConfiguration::class,
)
@SpringBootApplication
class PptAgentApplication
```

`app/build.gradle.kts` add:
```kotlin
implementation(project(":business"))
implementation(project(":llm-adapter"))
```

### Update `SmokeTestRunner`

- Inject `PptGenerationService` instead of (or in addition to) raw `ModelClient`
- Call `pptGenerationService.pingLlm(GatewayModel.DEEPSEEK)` and log result
- Keep existing direct `ModelClient` smoke optional, or remove to enforce adapter-only path

**Preferred:** smoke test goes **only** through `PptGenerationService` → `LlmAdapter` to validate the new layering.

---

## `settings.gradle.kts`

```kotlin
include("framework", "config", "gateway-api", "gateway-server", "gateway-client", "llm-adapter", "business", "app")
```

---

## Tests

1. **`PassthroughLlmAdapterTest`** (in `llm-adapter`, no Spring)
   - Fake `ModelClient` / `StreamingModelClient`
   - Verify adapter delegates with same args and returns same response

2. **`PptGenerationServiceTest`** (in `business`, no Spring)
   - Fake `LlmAdapter`
   - Verify service calls adapter, not gateway

3. **`app` context-load test** — still passes

No live API key required for unit tests.

---

## Docs

Update `README.md` with one line on layer stack:
`business → llm-adapter → gateway-client → gateway-server`

---

## Acceptance Criteria

- [ ] `llm-adapter` and `business` modules exist and compile
- [ ] `business` has zero dependency on `gateway-client` / `gateway-api`
- [ ] `LlmAdapter` + `PassthroughLlmAdapter` implemented
- [ ] `PptGenerationService` stub calls `LlmAdapter`
- [ ] `app` smoke test runs through business → adapter → gateway
- [ ] Unit tests for passthrough adapter and business service
- [ ] `./gradlew build` green (JDK 21)

## Non-Goals

- Outline agent, slide content, `.pptx` rendering
- Model-specific prompt compensation logic
- Changes to `gateway-server` or proto contract
