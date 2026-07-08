# PPTAgent — LLM Gateway

A multi-module Kotlin/Spring Boot **LLM gateway**. Upper layers call only the
`ModelClient` / `StreamingModelClient` contracts and pick a target model with the
**`GatewayModel` enum** (`DEEPSEEK`, `MIMO`, `MINIMAX`) — never Spring AI, never
raw provider model ids.

> This repository contains **infrastructure only**. PPT outline/content/`.pptx`
> generation, tool execution, and PPT web APIs are out of scope.

## Modules

| Module | Responsibility |
|--------|----------------|
| `framework` | Pure interfaces + DTOs (`ModelClient`, `Tool`, `ChatMessage`, `GatewayModel`, …). No spring-web / spring-ai. |
| `config` | `SpringAiModelClient` (bridges the framework onto Spring AI `ChatModel`), `AiKeysProperties`. |
| `gateway-api` | Protobuf contract + generated gRPC stubs. |
| `gateway-server` | Standalone Boot app: gRPC on `:9090`, HTTP ops on `:9091`. |
| `gateway-client` | `ModelClient` / `StreamingModelClient` implemented over gRPC. |
| `llm-adapter` | `LlmAdapter` seam above transport; `PassthroughLlmAdapter` delegates straight to `gateway-client` today, and is the hook for future model-capability compensation. |
| `business` | PPT domain layer (`PptGenerationService`, placeholder only). Depends on `llm-adapter`, never on `gateway-client` / `gateway-api` directly. |
| `app` | Smoke-test runner. Wires all layers and validates `business → llm-adapter → gateway-client`. |

```
app → gateway-client → [gRPC] → gateway-server → config/Spring AI → provider
         ↑                              ↑
    framework                      gateway-api (proto)
```

Business layering (new): `business → llm-adapter → gateway-client → gateway-server`.
`business` never depends on `gateway-client` / `gateway-api`; it only ever calls `LlmAdapter`.

## Providers

All three providers are reached through **OpenAI-compatible** chat-completions
endpoints. Concrete endpoints + model ids live only in
`gateway-server/src/main/resources/application.yaml` under `gateway.models`.

| `GatewayModel` | Default base-url | Default model | Key property |
|----------------|------------------|---------------|--------------|
| `DEEPSEEK` (default) | `https://api.deepseek.com` | `deepseek-chat` | `ai.keys.deepseek` |
| `MIMO` | `https://api.xiaomimimo.com` | `mimo-v2.5-pro` | `ai.keys.mimo` |
| `MINIMAX` | `https://api.minimax.io` | `MiniMax-M2` | `ai.keys.minimax` |

> Verify each `base-url` and `model` against the provider's current docs. MiniMax
> also offers an Anthropic-compatible endpoint; if the OpenAI-compatible path does
> not fit your account, adjust `gateway.models.minimax` accordingly.

See [`GATEWAY.md`](GATEWAY.md) for architecture, the enum-routing convention, and
the streaming / error model.

## Prerequisites

- JDK 21
- At least one provider API key (the gateway boots and serves
  `/health/capabilities` without any key)

## Configure keys

Keys never live in source control. Pick one of:

1. **File (recommended for local):** copy the example and edit it.

   ```bash
   cp ai-keys.yaml.example ai-keys.yaml   # ai-keys.yaml is gitignored
   ```

   ```yaml
   # ai-keys.yaml
   ai:
     keys:
       deepseek: "sk-..."
       mimo: "sk-..."
       minimax: "..."
   ```

   `gateway-server` imports this automatically via `spring.config.import`.

2. **Environment variables:** `DEEPSEEK_API_KEY`, `MIMO_API_KEY`, `MINIMAX_API_KEY`.

## Selecting a model (caller side)

```kotlin
import com.ppt.agent.framework.GatewayModel
import com.ppt.agent.framework.chat

val response = modelClient.chat(messages, tools = emptyList(), model = GatewayModel.MIMO)
```

## Run (two processes)

Start the gateway server (gRPC `:9090` + ops HTTP `:9091`):

```bash
./gradlew :gateway-server:bootRun
```

In another terminal, run the smoke-test app:

```bash
./gradlew :app:bootRun
```

The app calls `PptGenerationService.pingLlm(GatewayModel.DEEPSEEK)` — a `"ping"`
message routed through `business → llm-adapter → gateway-client` — and logs the
result.

## Health checks

```bash
# 200 when serving, 503 otherwise
curl -s -w "\nHTTP %{http_code}\n" localhost:9091/health

# per-model live probe (always 200; diagnostic only)
curl -s localhost:9091/health/models

# the model table: id -> provider/model/params
curl -s localhost:9091/health/capabilities

# actuator (liveness / readiness / prometheus / info)
curl -s localhost:9091/actuator/health
curl -s localhost:9091/actuator/prometheus
```

## Build & test

```bash
./gradlew build      # compiles all 8 modules and runs unit tests (no live key needed)
```

Tests use an in-memory `FakeChatModel`; none require network or an API key.
