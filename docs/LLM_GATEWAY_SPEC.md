# LLM Gateway Bootstrap Spec

> **For coding agent:** Implement everything in this document. No external reference repos. Work in repo root `/Users/zhaozian/code/LearnCode/PPTAgent`.

## Goal

Refactor the existing single-module Spring Boot scaffold into a **multi-module LLM gateway** (Opus 4.6 primary). Upper layers call only `ModelClient` / `StreamingModelClient` via **capability aliases** — never Spring AI, never raw model IDs.

**In scope:** infrastructure only.  
**Out of scope:** PPT outline/content/`.pptx`, web REST for PPT, tool execution inside gateway, Gamma/Tome/Beautiful.ai.

## Current State

- Single module: `src/main/kotlin/com/ppt/agent/pptagent/PptAgentApplication.kt`
- Kotlin 2.3.21, Spring Boot 4.1.0, Spring AI 2.0.0, JDK 21, `group = com.ppt.agent`
- Remove: `com.netflix.dgs.codegen`, Lombok, root-level Spring AI starters
- Delete root `src/` after migrating to `app/`

## Target Modules

```
framework/        # pure interfaces + DTOs (no spring-web, no spring-ai)
config/           # SpringAiModelClient, AiKeysProperties
gateway-api/      # proto + generated gRPC stubs
gateway-server/   # standalone Boot: gRPC :9090 + HTTP ops :9091
gateway-client/   # ModelClient/StreamingModelClient over gRPC
app/              # smoke-test runner (from PptAgentApplication)
```

`settings.gradle.kts`:

```kotlin
rootProject.name = "PPTAgent"
include("framework", "config", "gateway-api", "gateway-server", "gateway-client", "app")
```

Root `build.gradle.kts`: aggregator only — plugins `apply false`, `extra["springBootVersion"]="4.1.0"`, `extra["springAiVersion"]="2.0.0"`.

## Architecture Rules

1. Business sends **capability aliases** (`fast-chat`, `reasoning`, `extraction`), not model IDs.
2. Concrete `provider + model` live **only** in `gateway-server` YAML.
3. Gateway = **one model turn** per request; caller owns tool-execution loop.
4. Tool schemas/args cross gRPC as **JSON strings**.
5. gRPC via plain `io.grpc.Server` + `SmartLifecycle` (no gRPC starter).

```
app → gateway-client → [gRPC] → gateway-server → config/Spring AI → Anthropic
         ↑                              ↑
    framework                      gateway-api (proto)
```

## Framework API (`com.ppt.agent.framework`)

```kotlin
interface ModelClient {
    fun chat(messages: List<ChatMessage>, tools: List<Tool>, model: String): ModelResponse
}
interface StreamingModelClient {
    fun chatStream(messages: List<ChatMessage>, tools: List<Tool>, capability: String): Flux<ModelStreamEvent>
}

sealed class ChatMessage {
    data class System(val text: String) : ChatMessage()
    data class User(val text: String) : ChatMessage()
    data class Assistant(val text: String?, val toolCalls: List<ToolCall>) : ChatMessage()
    data class ToolResults(val items: List<ToolResultItem>) : ChatMessage()
}
data class ToolCall(val id: String, val name: String, val argsJson: String)
data class ToolResultItem(val id: String, val name: String, val content: String)
data class ModelResponse(val text: String?, val toolCalls: List<ToolCall>)

sealed class ModelStreamEvent {
    data class TextDelta(val content: String) : ModelStreamEvent()
    data class ToolCallRequest(val call: ToolCall) : ModelStreamEvent()
    data class Done(val fullText: String?, val toolCalls: List<ToolCall> = emptyList()) : ModelStreamEvent()
    data class Failed(val message: String, val code: String? = null) : ModelStreamEvent()
}

interface Tool {
    fun name(): String; fun description(): String
    fun parametersSchema(): Map<String, Any>
    fun execute(argsJson: String): ToolResult
}
data class ToolResult(val content: String)
```

Also add `object Json` (Gson + fallback) for tool schema serialization.

## Proto (`gateway-api/src/main/proto/ppt/gateway/v1/model_gateway.proto`)

- `java_package = "com.ppt.agent.gateway.v1"`
- Service: `Chat`, `ChatStream`, `ListCapabilities`
- `ChatRequest`: capability, messages, tools, param_overrides, request_id
- Roles: SYSTEM, USER, ASSISTANT, TOOL_RESULTS
- `ChatEvent` oneof: TextDelta, ToolCallEvent, Done, ErrorEvent
- `ChatResponse`: text, tool_calls, resolved, usage

Pinned: protobuf `4.28.3`, grpc-java `1.68.1`, grpc-kotlin `1.4.1`.

## Gateway Server

**Deps:** gateway-api, framework, config, `spring-ai-starter-model-anthropic`, actuator, prometheus, grpc-netty-shaded.

**Beans to implement:**

| Class | Role |
|-------|------|
| `CapabilityRegistry` | alias → provider/model/params; pure Kotlin, unit-testable |
| `GatewayCapabilitiesProperties` | `@ConfigurationProperties("gateway")` |
| `ProviderChatModels` | unary via `SpringAiModelClient`; streaming via `ChatModel.stream()` |
| `ModelGatewayService` | gRPC impl; map 429/errors → `ErrorEvent` |
| `GrpcServerLifecycle` | start/stop gRPC on `gateway.grpc.port` |
| `GatewayConfig` | wire providers + beans |
| Health | `GatewayReadyHealthIndicator`, `GrpcServerHealthIndicator`, `ModelHealthProbe`, `OpsHealthController` |

**Metrics:** `gateway.provider.requests`, `gateway.provider.latency` (tags: provider, capability, outcome).

**Ops endpoints (HTTP :9091 only):**

- `GET /health` — 200/503 serve-ability
- `GET /health/models` — per-capability probe (always 200)
- `GET /health/capabilities` — alias table
- Actuator: liveness, readiness, prometheus, info

### Config (`gateway-server/src/main/resources/application.yaml`)

```yaml
spring:
  application.name: ppt-model-gateway
  config.import:
    - optional:classpath:ai-keys.yaml
    - optional:file:./ai-keys.yaml
  ai.anthropic:
    api-key: ${ai.keys.anthropic:${ANTHROPIC_API_KEY:}}
    chat.options.model: claude-opus-4-6   # verify against Anthropic docs

server.port: 9091

gateway:
  grpc.port: 9090
  default-capability: fast-chat
  health: { probe-mode: real, timeout: 8s, cache-ttl: 15s, probe-prompt: ping }
  capabilities:
    fast-chat:   { provider: anthropic, model: claude-opus-4-6, params: { temperature: "0.7",  max_tokens: "4096" } }
    reasoning:   { provider: anthropic, model: claude-opus-4-6, params: { temperature: "0.4",  max_tokens: "8192" } }
    extraction:  { provider: anthropic, model: claude-opus-4-6, params: { temperature: "0.2",  max_tokens: "2048" } }
```

Keys: `ai-keys.yaml.example` (committed), `ai-keys.yaml` (gitignored). Env: `AI_KEYS_ANTHROPIC`.

## Gateway Client

- `GatewayModelClient` / `GatewayStreamingModelClient` — proto ↔ framework mapping
- `GatewayClientConfiguration` — `ManagedChannel`, stub, both client beans
- Config: `gateway.client.host` (default localhost), `gateway.client.port` (default 9090)

## App Module (smoke test)

```kotlin
@Import(GatewayClientConfiguration::class)
@SpringBootApplication
class PptAgentApplication
```

`CommandLineRunner`: inject `ModelClient`, call `fast-chat` with `"Say hello in one sentence."`, log response. Optional streaming demo.

**App must not import Spring AI or Anthropic.**

## Tests (no live API key)

- `CapabilityRegistryTest`
- `GatewayReadyHealthIndicatorTest`
- `ModelHealthProbeTest` (429 → DOWN per-entry, dedup probes)
- `app` context-load test

Use `FakeChatModel` for unit tests.

## Docs to Commit

- `README.md` — setup keys, two-process run, health curls
- `GATEWAY.md` — architecture, alias convention, streaming/errors
- `ai-keys.yaml.example`

## Run

```bash
./gradlew :gateway-server:bootRun   # gRPC 9090, ops 9091
./gradlew :app:bootRun              # smoke test
curl localhost:9091/health/capabilities
```

## Acceptance Criteria

- [ ] `./gradlew build` green (JDK 21)
- [ ] 6 submodules; root `src/` gone; DGS + Lombok removed
- [ ] gRPC 9090 + HTTP ops 9091 up
- [ ] `/health/capabilities` → 3 aliases → anthropic/claude-opus-4-6
- [ ] With valid key, `:app:bootRun` prints chat response
- [ ] Streaming emits multiple TextDelta chunks
- [ ] No API keys in git; model IDs only in gateway-server YAML
- [ ] `framework` has no spring-web / spring-ai deps
