# Unit Test Supplement Spec (Segments 1–4)

> **For coding agent:** 只补全/修复单元测试，不实现新业务，不调真实 LLM。仓库：`/Users/zhaozian/code/LearnCode/PPTAgent`

## 目标

补全前四段 unit test，使 `./gradlew build` 全绿（JDK 21），所有测试不依赖外网/API Key。

## 对照 Spec

| 段 | 文档 |
|----|------|
| 1 | `docs/LLM_GATEWAY_SPEC.md`、`docs/BUSINESS_ADAPTER_SPEC.md` |
| 2 | `docs/INPUT_PARSER_SPEC.md` |
| 3 | `docs/OUTLINE_AGENT_SPEC.md` |
| 4 | `docs/SLIDE_CONTENT_SPEC.md` |

---

## 0. 先修编译错误（必须）

`llm-adapter/.../PassthroughLlmAdapterTest.kt` ~L90：

```kotlin
Flux.just<ModelStreamEvent>(ModelStreamEvent.Done(fullText = "done"))
```

---

## 1. Gateway + Adapter

**已有：** `CapabilityRegistryTest`、`GatewayReadyHealthIndicatorTest`、`ModelHealthProbeTest`、`GatewayModelClientTest`、`PassthroughLlmAdapterTest`、`PptAgentApplicationTests`

**补全：**
- `PassthroughLlmAdapterTest`：`chat` 透传 `paramOverrides`
- 新建 `GatewayStreamingModelClientTest`（fake/in-process gRPC，测 `TextDelta`→`Done`）
- （可选）`framework.Json` 基础测试

---

## 2. Input Parser

**已有：** `PptInputParserTest`（较全）

**补全：**
- `parseFromFile`：临时文件成功 + 文件不存在/非法 JSON
- 边界：顶层非 object、`slide_count: 27.5`、`brief` 500/501 字
- `PptGenerationServiceTest`：用真实 `PptInputParserImpl` 解析 `01-python-intro.json`（无 Spring）

---

## 3. Outline Agent

**已有：** `OutlineValidatorTest`、`OutlinePlannerTest`（6 例）、`planOutline` 委托测试、`valid-outline.json`

**补全：**
- `OutlineValidatorTest`：spec 8 条规则每条至少 1 个失败用例（缺的补上）
- `OutlinePlannerTest`：`slides.size < slideCount` 截断 → 升 token

---

## 4. Slide Content

**已有代码：** `ModelAssignmentPolicy`、`SlideContentValidator`、`SlideContentGenerator` 接口；`SlideContentGeneratorImpl` 可能尚未存在

**必须新建：**
- `ModelAssignmentPolicyTest`：7 section × 3 model round-robin；单模型 pool；空 pool 抛错
- `SlideContentValidatorTest`：`validateSlide` + `validateDeck`（用 hand-crafted 数据 + `valid-outline.json`）
- Fixture：`business/src/test/resources/content/single-slide-response.json`

**若 `SlideContentGeneratorImpl` 已存在，另加 `SlideContentGeneratorTest`：**
- 27 页全成功 → `Ok`
- 并发 ≤ `ContentGenerationConfig.MAX_PARALLEL_SLIDES`（8）
- 单页 3 次失败 → 换模型 fallback 成功
- 不可恢复 → `Err(SlideFailed)`
- Fake 模式参考 `OutlinePlannerTest.ScriptedLlmAdapter`

**若 Impl 不存在：** 只写 Policy + Validator 测试，Generator 测试待后续。

**若已 wire `generateContent`：** `PptGenerationServiceTest` 加委托测试。

---

## 规范

- 不调真实 LLM，不 `bootRun`
- 风格对齐现有测试（`kotlin.test` + JUnit 5）
- 不新增无关依赖；并发测试可加 `kotlinx-coroutines-test`（test scope）
- 不为通过测试改业务语义；发现 bug 小修可以，大改不要

---

## 验收

- [ ] `./gradlew build` 全绿
- [ ] 段 1：`PassthroughLlmAdapterTest` 编译通过 + `paramOverrides` 测试
- [ ] 段 2：`parseFromFile` + 边界用例
- [ ] 段 3：Validator 规则全覆盖
- [ ] 段 4：`ModelAssignmentPolicyTest` + `SlideContentValidatorTest` + fixture
- [ ] 段 4：Impl 存在时 `SlideContentGeneratorTest` 存在

## 禁止

- PPTX 渲染、真实 API 集成测试、改 `ai-keys.yaml`、大规模重构生产代码
