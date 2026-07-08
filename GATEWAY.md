# Gateway Architecture

## Overview

The gateway isolates the rest of the system from any specific model provider or
SDK. Business code depends only on the `framework` module and selects a target
model with the **`GatewayModel` enum**; the concrete provider endpoint + model id
mapping lives in exactly one place: `gateway-server`'s `application.yaml`.

```
app / business
   │  (framework contracts: ModelClient, StreamingModelClient, GatewayModel)
   ▼
gateway-client ──[gRPC :9090]──► gateway-server ──► config/SpringAiModelClient ──► Spring AI (OpenAI-compatible) ──► provider
   ▲                                   ▲
framework                          gateway-api (proto)
```

## Design rules

1. **Enum selects the model.** Callers pass `GatewayModel.DEEPSEEK` / `MIMO` /
   `MINIMAX`. They never see or send a provider base-url or raw model id. The
   enum's `id` is the wire identifier (`deepseek` / `mimo` / `minimax`).
2. **Single source of model truth.** Concrete base-urls, model ids, params and
   key references only exist in `gateway-server` YAML (`gateway.models.*`). No
   endpoints or model ids leak into `framework`, `gateway-client`, or `app`.
3. **One model turn per request.** The gateway performs exactly one call to the
   model and returns the result — including any tool-call *requests* — verbatim.
   The **caller owns the tool-execution loop**.
4. **Tools cross the wire as JSON.** Tool schemas (`parameters_schema_json`) and
   tool-call arguments (`args_json`) are plain JSON strings in the proto.
5. **Plain gRPC.** The server runs an `io.grpc.Server` driven by a Spring
   `SmartLifecycle` (`GrpcServerLifecycle`) — no gRPC Spring Boot starter.

## Providers & models

All three providers speak the **OpenAI-compatible** chat-completions protocol, so
a single client library (`spring-ai-openai`) covers them. `ChatModelFactory`
builds one `OpenAiChatModel` per `gateway.models` entry; only `base-url`,
`api-key` and `model` differ.

| `GatewayModel` id | Default base-url | Default model | Key |
|-------------------|------------------|---------------|-----|
| `deepseek` (default) | `https://api.deepseek.com` | `deepseek-chat` | `ai.keys.deepseek` |
| `mimo` | `https://api.xiaomimimo.com` | `mimo-v2.5-pro` | `ai.keys.mimo` |
| `minimax` | `https://api.minimax.io` | `MiniMax-M2` | `ai.keys.minimax` |

> `base-url` must NOT include a trailing `/v1` — the OpenAI client appends the
> completions path. Verify model ids and base URLs against each provider's docs.

`CapabilityRegistry` resolves a model id to a `CapabilitySpec` (provider + model +
params). Unknown ids fail fast (`INVALID_ARGUMENT` on the wire). A blank/absent id
falls back to `gateway.default-model`. Per-request `param_overrides` are merged
over the entry defaults.

## Request flow

**Unary (`Chat`)**

1. `gateway-client` maps framework `ChatMessage`/`Tool` → proto and calls `Chat`
   with the selected model id as `capability`.
2. `ModelGatewayService` resolves the model, merges `param_overrides`, and calls
   `ProviderChatModels.chat`.
3. `ProviderChatModels` routes to the matching `SpringAiModelClient`, which builds
   a Spring AI `Prompt` with `ToolCallingChatOptions` (model + params + tool
   *definitions*) and calls `ChatModel.call`. No `ToolCallingManager` is wired, so
   Spring AI never executes tools — it returns tool-call requests as-is.
4. The `ChatResponse` (text + tool calls + resolved model) is returned.

**Streaming (`ChatStream`)**

- `ProviderChatModels.stream` uses `ChatModel.stream()` and maps each chunk to
  `ModelStreamEvent`s, emitting one or more `TextDelta`s, any `ToolCallRequest`s,
  and finally a `Done(fullText)`. The client re-exposes these as a Reactor `Flux`.

## Streaming events (proto `ChatEvent` oneof)

| Event | Meaning |
|-------|---------|
| `TextDelta` | An incremental chunk of assistant text. |
| `ToolCallEvent` | The model requested a tool call (not executed by the gateway). |
| `Done` | Terminal success; carries the aggregated `full_text`. |
| `ErrorEvent` | Terminal failure; carries `message` + `code`. |

## Error model

- **Unary:** exceptions map to gRPC `Status`. Rate limits (HTTP 429) →
  `RESOURCE_EXHAUSTED`; unknown model → `INVALID_ARGUMENT`; everything else →
  `INTERNAL`.
- **Streaming:** failures are delivered **in-band** as a terminal `ErrorEvent`
  (`code = RATE_LIMITED` for 429, else `PROVIDER_ERROR`) followed by stream
  completion, so subscribers always get a clean terminal signal.

Rate-limit detection lives in `RateLimit` (matches 429 / "rate limit" / "too many
requests" across the exception cause chain).

## Health & observability

- `GET /health` — overall serve-ability (200/503), backed by
  `GatewayReadyHealthIndicator` (gRPC serving **and** ≥1 model).
- `GET /health/models` — per-model live probe via `ModelHealthProbe`. Always
  returns 200 (diagnostic). Probes are **deduplicated per provider/model** within
  a sweep and cached for `gateway.health.cache-ttl`. A 429 marks the affected
  entries `DOWN` without failing overall serve-ability. Set
  `gateway.health.probe-mode` to anything other than `real` to short-circuit to
  `DISABLED` (no live calls).
- `GET /health/capabilities` — the model table (id → provider/model/params).
- Actuator: `health` (+ liveness/readiness probes), `info`, `prometheus`.
- Metrics: `gateway.provider.requests` (counter) and `gateway.provider.latency`
  (timer), each tagged `provider`, `capability` (the model id), `outcome`
  (`success` / `rate_limited` / `error`).

## Ports

| Port | Purpose |
|------|---------|
| `9090` | gRPC `ModelGateway` service (`gateway.grpc.port`). |
| `9091` | HTTP ops + actuator (`server.port`). |

The `app` module runs no HTTP server (`spring.main.web-application-type=none`); it
is a pure gRPC client. Configure its target via `gateway.client.host` /
`gateway.client.port` (defaults `localhost:9090`).

## Adding a provider

1. Add a value to `GatewayModel` in `framework`.
2. Add a matching entry under `gateway.models` in the server YAML (base-url,
   model, api-key, params) and a key field in `AiKeysProperties`.

No other code changes are required as long as the provider is OpenAI-compatible.
