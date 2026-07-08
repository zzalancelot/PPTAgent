# DESIGN.md — PPT 一套生成器 决策日志

> 目标：JSON（topic / brief / audience）进 → 一套 25–30 页**风格一致、叙事连贯**的 `.pptx` 出。
> 三个硬考核点：**美观度 · LLM 成本 · 生成速度**。

---

## 1. 架构图 + 数据流

系统按「**领域逻辑 / 模型接入 / 传输**」分层，模型细节只在网关一处收敛，业务层永远只认 `GatewayModel` 枚举。

```
                    ┌─────────────┐
   浏览器 (5173)     │  frontend   │  React + Vite + Ant Design
                    │  输入表单   │  代理 /v1/* → :8080
                    └──────┬──────┘
                           │ POST /v1/ppt/run?stage=pptx
                           ▼
┌──────────────────────────────────────────────────────────┐
│ app (:8080)  PptApiController + PptxExportService          │
│   parse → outline → content → render → 落盘 + 下载链接      │
└───────┬───────────────────────────────────┬──────────────┘
        │ business (领域编排)                 │ renderer (独立工具)
        ▼                                     ▼
┌──────────────────────┐            ┌────────────────────────┐
│ PptGenerationService │            │ PptRenderTool           │
│  ├ PptInputParser    │            │  deck JSON → .pptx      │
│  ├ OutlinePlanner    │            │  PROGRAMMATIC / TEMPLATE│
│  └ SlideContentGen   │            │  (Apache POI, 无 LLM)   │
└─────────┬────────────┘            └────────────────────────┘
          │ 只依赖 LlmAdapter（盲转发）
          ▼
┌──────────────────────┐   gRPC :9090   ┌────────────────────────┐
│ llm-adapter          │ ─────────────► │ gateway-server (:9091)  │
│ gateway-client       │                │  CapabilityRegistry     │
└──────────────────────┘                │  Spring AI (OpenAI 兼容)│
                                         │  → DeepSeek / MiMo / …  │
                                         └────────────────────────┘
```

**数据流（`stage=pptx` 全链路）：**

1. **parse**：`PptInputParser` 校验 topic / brief(≤500) / audience / slide_count(25–30，默认 27)。毫秒级，不调 LLM。
2. **outline**：`OutlinePlanner` 一次 LLM 调用产出**整套大纲 JSON**——叙事线、分节、逐页 `slideType` + `intent` + `bulletHints`，以及全局 `consistency`（关键术语 / 禁用词 / 偏好措辞）。这是「一套而非一堆」的锚点。
3. **content**：`SlideContentGenerator` 按页并行调用 LLM，把大纲每页扩写成最终文案（title / subtitle / bullets / bodyText / speakerNotes）。
4. **render**：`renderer` 把 deck JSON 用 Apache POI 画成 `.pptx`，写到 `build/output/pptx/`。
5. **download**：`GET /v1/ppt/download/{fileName}` 返回文件；前端显示「下载 PPTX」按钮。

**分层铁律**：`business` 永远不 import `gateway-client` / provider SDK；`renderer` 不依赖 `business` / `app`；换模型 provider 只改 `gateway-server` 的 YAML。

---

## 2. 模型选型

所有 provider 都走 **OpenAI 兼容** 的 chat-completions 协议，因此一套 `spring-ai-openai` 客户端 + 一份 YAML 即可覆盖，新增模型零代码改动。

| 用途 | 选用模型 | 理由 |
|------|----------|------|
| **大纲规划**（1 次/套） | **DeepSeek-V4-Pro** | 全局叙事、分节、一致性约束需要更强的结构化与长程一致能力；每套只调一次，贵一点可接受。 |
| **逐页文案**（约 27 次/套） | **DeepSeek-V4-Flash** | 调用量最大，成本 / 速度敏感；单页任务上下文短、约束清晰，flash 足够，且便宜快。 |
| 备选 / 兜底 | **MiMo**、**MiniMax** | 已在网关配置；当某模型逐页失败时按轮转 fallback。 |

**「贵模型管全局，便宜模型管细节」** 是核心取舍：把预算花在决定「整套是否连贯」的那一次大纲调用上，把 27 次高频调用交给 flash。API 层支持 `outlineModel` / `contentModel` 独立指定，方便做美观版 vs Trade-off 版切换。

**比较过 / 未采用**：单一强模型跑完全部 27 页——质量略好但成本和时延都乘以 27，不划算；纯 flash 跑大纲——叙事线容易散、分节不稳，被否。

---

## 3. 风格一致性怎么保的（核心难点）

一致性不是靠「同一个模板填空」，而是**先立一份全局契约，再让每页服从它**。

1. **单一大纲作为唯一事实源**。一次 pro 调用产出：
   - `storyline`（hook / promise / opening·core·closing beats）——全篇叙事骨架；
   - `consistency`：`keyTerms`（术语统一）、`forbiddenTerms`（禁用词）、`preferredPhrases`（偏好措辞）、`avoidPatterns`、`differentiationNote`；
   - `narrativeArc`（teaching / persuasion / itinerary…）——决定整套语气。
2. **逐页生成时强制注入上下文**（`SlideContentGeneratorImpl.buildMessages`）：
   - 把 `oneLiner / hook / promise / 禁用词 / 偏好措辞 / 差异化说明` 拼成 `consistencyNote` 塞进每页 prompt；
   - 附上**前一页 / 后一页的 title + intent** 作为 neighbor hint，要求「承上启下、不重复」；
   - 传入全局 `keyTerms`，要求术语一致、不逐字照抄。
3. **校验兜底**（`SlideContentValidator`）：每页产出后按 `slideType` 校验 bullet 数量 / subtitle / speakerNotes 密度；不合规就把违规项作为 user feedback 追加、重试。
4. **渲染层统一主题**：`renderer` 用一套固定配色 / 字体 / 版式令牌（PROGRAMMATIC 模式），保证视觉一致；TEMPLATE 模式则统一套用一份母版。

---

## 4. 多样性怎么保的

一致 ≠ 千篇一律。多样性在多个维度上被显式引入：

- **叙事弧随输入变**：`narrativeArc` 由 topic + audience 推断——Python 教学是 `teaching`、Rust 说服 CEO 是 `persuasion`、京都行程是 `itinerary`，语气与结构随之不同。
- **13 种 `slideType`**：title / agenda / section_divider / content / comparison / timeline / framework / case_study / code_or_demo / quote / summary / call_to_action / qa；大纲规则要求「连续不超过 3 个同类型」。
- **版式随类型映射**：`SlideLayoutMapper` 把 slideType 映射到 TITLE / BULLETS / TWO_COLUMN / BODY_TEXT 等 7 种版式，comparison 走双栏、code_or_demo 走等宽正文，视觉上不重样。
- **分节分配不同模型**：`ModelAssignmentPolicy` 按节轮转分配模型，天然引入措辞风格的细微差异（同时也分摊压力）。
- **内容由 intent + bulletHints 扩写**：每页 prompt 要求「在 hint 基础上加一层（定义 / 例子 / 为什么重要 / 常见坑）」，而非照抄 hint，避免模板腔。

---

## 5. 成本与时延实测（5 套 demo）

> **诚实说明：** 截至本文档初版，仅 **demo #1（Python 入门）** 有端到端实测（27 页全成功）。其余 4 套的实测数据将在补跑后填入；下方**不编造**未测数据。

### 已测（demo #1，DeepSeek pro 大纲 + flash 逐页）

| 阶段 | 模型 | 耗时 | 说明 |
|------|------|------|------|
| parse | — | <1s | 纯校验 |
| outline | deepseek-pro | ~182s | 1 次调用，27 页大纲 |
| content | deepseek-flash | ~34s | 27 页并行（≤8 in-flight） |
| render | —（POI） | ~1–2s | 100 KB pptx，27 页 |
| **合计** | | **~3.7 min** | 远低于 30 min 上限 |

### 待补（两版 × 5 套）

| # | 主题 | 版本 | outline 耗时 | content 耗时 | 总时延 | 估算成本 (USD) |
|---|------|------|------|------|------|------|
| 1 | Python 入门 | Trade-off | 182s | 34s | ~3.7min | 待测 |
| 1 | Python 入门 | 美观最大化 | 待测 | 待测 | 待测 | 待测 |
| 2 | 年度复盘 | 两版 | 待测 | 待测 | 待测 | 待测 |
| 3 | 咖啡豆 | 两版 | 待测 | 待测 | 待测 | 待测 |
| 4 | Rust 重写 | 两版 | 待测 | 待测 | 待测 | 待测 |
| 5 | 京都两日 | 两版 | 待测 | 待测 | 待测 | 待测 |

> 成本口径：outline 1 次 pro 调用 + content ≈27 次 flash 调用，按各 provider 单价 × 实际 token 计。两版差异主要来自 content 模型档位与 token 上限。

---

## 6. 踩坑和取舍

| 踩的坑 / 权衡 | 处理 |
|--------------|------|
| **大纲一次生成 27 页 → token 截断** | 加 `TOKEN_LADDER`（8192→12288→16384）+ 截断检测（结尾非 `}` / 页数 < 请求数）自动升档重试。 |
| **网关 HTTP 读超时 60s，pro 大纲跑不完就断** | `ChatModelFactory` 默认超时提到 **600s**。 |
| **Spring `ObjectMapper` 注入失败导致 `/run` 500** | 控制器改为 `@RequestBody json: String`，业务层自己解析，去掉隐式依赖。 |
| **逐页串行太慢** | 改并行，`Semaphore` 限流 8 路（`MAX_PARALLEL_SLIDES`），content 从数分钟降到 ~34s。 |
| **单页偶发格式错 / 校验不过** | 分层重试：主模型 3 次（升 token），再换 1 个备选模型 2 次；校验失败把违规项回灌 prompt 重试。 |
| **renderer 与 business 循环依赖风险** | 把 renderer 做成**零业务依赖的独立工具模块**（仅 framework + POI），DTO 用容忍式可空字段解析，`app` 负责组装。 |
| **模板模式缺母版文件** | 提供 `renderer/tools/generate_template.py` 生成并入库 `deck-template.pptx`（7 版式，16:9）。 |
| **密度升级后旧 fixture 无法验证新规则** | 明确区分「旧 deck JSON 仅测 renderer」与「需重跑 content 才验证密度」，避免用过期数据自证。 |

**主动取舍**：v1 不做「部分成功」——任一页最终失败即整套失败，保证交付的永远是完整 25–30 页，而非残缺的一堆。图片 / 配图模块（`IMAGE_TOOL_SPEC`）已写 spec 但**暂缓**，优先保证「文字连贯 + 能下载」这条主干先跑通。

---

## 7. AI 协作复盘

本项目由「人定方案 + 多个编码 Agent 分段实现」的模式推进，人始终握着架构与验收权。

### AI 提的、照做了
- **分层网关架构**（framework / gateway / business 隔离，枚举选模型）：AI 提出，判断合理（换模型零改动、职责清晰），采纳。
- **双档模型策略**（pro 管大纲、flash 管逐页）：AI 建议，符合成本直觉，采纳。
- **renderer 双模式**（PROGRAMMATIC + TEMPLATE）：AI 提出，兼顾「快」与「可换母版美化」，采纳。
- **并行 + 信号量限流**、**token 阶梯重试**：AI 提出的工程细节，直接落地。

### AI 提的、被推翻 / 修正
- **renderer 一开始想直接接进 business**（造成循环依赖）：推翻，改为**独立工具模块**，DTO 容错解析，由 app 组装。
- **模板模式最初没把 `deck-template.pptx` 入库**（只在 build 目录）：推翻「运行时生成」的隐式做法，要求生成脚本 + 二进制入库，验收时明确检查。
- **validator 的边缘页型覆盖不全**（title/section_divider subtitle、call_to_action bullet 未强校验）：识别为缺口，标注为后续补齐，而非当成「已完成」放过。

### AI 跑偏、被拽回来的具体例子
1. **密度升级用旧数据自证**：Agent 声称「内容更丰富了」，但拿来展示的 `content-test-python-intro-deck.json` 是**升级前**生成的（多是 3 条短 bullet）。识破后明确：旧 JSON 只能测 renderer，必须**重跑 stage=content** 才算验证密度，避免被「看起来完成了」骗过。
2. **服务「启动成功」但进程被回收**：Agent 报告 dev stack 起来了、health 全绿，但那是在 Agent 终端里跑的，会话一结束进程即被回收。拽回来：脚本改用 `nohup`+`disown` 脱离，并明确告知用户需在**本机终端**执行才能常驻。
3. **脚本带 CRLF 直接报 `env: bash\r`**、**误用 macOS 没有的 `setsid`**：AI 生成的脚本在本机跑不起来，逐个定位换行符 / 命令兼容性问题后修复。

### 对 AI 输出做的核验 / 兜底
- **每段验收对照 spec 勾选 acceptance criteria**，不看「Agent 说完成了」，看代码 + 测试 + 实跑。
- **跑测试**：`:renderer:test` / `:business:test` / `:app:test` + `./gradlew build` 必须全绿。
- **CLI / API 实跑冒烟**：两种渲染模式各生成一份 pptx 对比；`stage=pptx` 落盘 + 下载链路实测。
- **对数据保持怀疑**：区分「实测」与「待测」，本文档成本表**只填真实测过的**，其余明确标注待补。

---

*本文档为初版，与代码同步演进；成本/时延表待 5 套 demo 两版跑齐后补全。*
