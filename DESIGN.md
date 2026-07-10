# DESIGN.md — PPT 一套生成器 决策日志

> 目标：JSON（topic / brief / audience）进 → 一套 25–30 页**风格一致、叙事连贯**的 `.pptx` 出。
> 三个硬考核点：**美观度 · LLM 成本 · 生成速度**。

**实测状态（2026-07-10）**：已按招聘题面公开开发集完成 **5 主题 × 2 版本 = 10 套** 端到端生成；产物见 `docs/demos/`。原始计时见 `docs/benchmark-results.jsonl`，复现命令：`./scripts/exam-benchmark.sh all`。

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

**分层逻辑**：`业务层` 与 `模型层` 解耦。上层业务无需关系底层的模型究竟是什么。便于任意时候，调用任何一个可以被使用的模型能力。</br> provider SDK；`renderer` 等不需要依赖模型能力的工具，独立出来。</br>`app`；换模型 provider 只改 `gateway-server` 的 YAML。

---

## 2. 模型选型

所有 provider 都走 **OpenAI 兼容** 的 chat-completions 协议，因此一套 `spring-ai-openai` 客户端 + 一份 YAML 即可覆盖，新增模型零代码改动。

| 用途             | 选用模型                                    | 理由 |
|----------------|-----------------------------------------|------|
| **大纲规划**（1 次/套） | **推理能力比较强**的模型  <br/>例DeepSeek-V4-Pro        | 全局叙事、分节、一致性约束需要更强的结构化与长程一致能力；每套只调一次，贵一点可接受。 |
| **逐页文案**（1次/页） | **生成速度比较快的模型**  <br/>例DeepSeek-V4-Flash | 调用量最大，成本 / 速度敏感；单页任务上下文短、约束清晰，flash 足够，且便宜快。 |

**「能力强但是比较贵的模型负责全局，便宜的小模型负责细节」** 是核心取舍：把预算花在决定「整套是否连贯」的那一次大纲调用上，把 27 次高频调用交给便宜的小模型。API 层支持 `outlineModel` / `contentModel` 独立指定，方便做美观版 vs Trade-off 版切换。

**比较过但是未采用的方案**：单一强模型跑完全部 27 页——质量略好但成本和时延都乘以 27，不划算；纯 flash 跑大纲——叙事线容易散、分节不稳，也被pass。

---

## 3. 风格一致性怎么保的

一致性不是靠「同一个模板填空」，而是**先立一份全局契约**，在每一次交给小模型做的时候，通过一致并且足量的prompt来交给小模型工作。再让每页服从它。

1. **单一大纲作为唯一来源**。一次 pro 调用产出：
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

## 4. 多样性怎么实现

一致 ≠ 千篇一律。多样性在多个维度上被显式引入：

- **叙事弧随输入变**：`narrativeArc` 由 topic + audience 推断——Python 教学是 `teaching`、Rust 说服 CEO 是 `persuasion`、京都行程是 `itinerary`，语气与结构随之不同。
- **13 种 `slideType`**：title / agenda / section_divider / content / comparison / timeline / framework / case_study / code_or_demo / quote / summary / call_to_action / qa；大纲规则要求「连续不超过 3 个同类型」。
- **版式随类型映射**：`SlideLayoutMapper` 把 slideType 映射到 TITLE / BULLETS / TWO_COLUMN / BODY_TEXT 等 7 种版式，comparison 走双栏、code_or_demo 走等宽正文，视觉上不重样。
- **分节分配不同模型**：`ModelAssignmentPolicy` 按节轮转分配模型，天然引入措辞风格的细微差异（同时也分摊压力）。
- **内容由 intent + bulletHints 扩写**：每页 prompt 要求「在 hint 基础上加一层（定义 / 例子 / 为什么重要 / 常见坑）」，而非照抄 hint，避免模板腔。

---

## 5. 成本与时延实测（5 套 demo × 2 版）

### 5.1 测试方法（严格对齐题面）

| 题面要求 | 本仓库实现 |
|---------|-----------|
| 输入：公开开发集 5 个 JSON（`topic` / `brief` / `audience`） | `docs/exam-fixtures/01-*.json` … `05-*.json`，与题面逐字一致 |
| 输出：25–30 页标准 `.pptx` | 全部 **27 页**，PowerPoint 可打开（Apache POI 渲染） |
| 单条命令 JSON → pptx | `POST /v1/ppt/run?stage=pptx`（`scripts/exam-benchmark.sh` 封装） |
| **最大化美观度版**：单份 < $10、< 30 min | **beauty** profile，见下表；全部达标 |
| **Trade-off 版**：自行权衡美观/成本/速度 | **tradeoff** profile，见下表 |
| 提交 5 套 demo（两版各 5 个） | `docs/demos/tradeoff/` + `docs/demos/beauty/` 各 5 个 `.pptx` |

**两版模型策略：**

| 版本 | outline | content | theme | 设计意图 |
|------|---------|---------|-------|---------|
| **Trade-off** | `deepseek-pro` | `deepseek-flash` | `deepseek-flash` | 大纲 1 次 pro 保全局叙事；27 次 flash 并行压时延与成本 |
| **美观最大化** | `deepseek-pro` | `deepseek-pro` | `deepseek-pro` | 全链路最强模型，文案密度与措辞质量更高 |

**测试环境：** 本机 `./scripts/dev-up.sh`（gateway `:9091` + app `:8080`），DeepSeek API key 已配置。每套为冷启动单次 `stage=pptx` 全链路（parse → outline ∥ scenarios → content → theme → render）。

**成本口径：** 网关暂未回传 token usage，下表「估算成本」按典型 token 量级 × [DeepSeek 公开单价](https://api-docs.deepseek.com/) 粗算（pro 输入 $0.55/M、输出 $2.19/M；flash 输入 $0.14/M、输出 $0.55/M）。实际账单以 provider 为准；粗算均 **≪ $10/套**。

### 5.2 Trade-off 版（5/5 成功）

| # | 主题 | 页数 | outline | content | theme | render | **总时延** | 估算成本 | 产物 |
|---|------|------|---------|---------|-------|--------|-----------|---------|------|
| 1 | Python 入门 | 27 | 88s | 66s | 5s | <1s | **2.6 min** | ~$0.06 | `docs/demos/tradeoff/01-python-入门-30-分钟-20260710-103240.pptx` |
| 2 | 年度复盘 | 27 | 94s | 87s | 5s | <1s | **3.1 min** | ~$0.06 | `docs/demos/tradeoff/02-2025-我的年度复盘-20260710-103547.pptx` |
| 3 | 咖啡豆 | 27 | 345s | 71s | 5s | <1s | **7.0 min** | ~$0.06 | `docs/demos/tradeoff/03-如何挑选一款适合自己的咖啡豆-20260710-113400.pptx` ※ |
| 4 | Rust 重写 | 27 | 91s | 65s | 13s | <1s | **2.8 min** | ~$0.06 | `docs/demos/tradeoff/04-给老板讲清楚为什么我们应该用-rust-重写订单系统-20260710-104531.pptx` |
| 5 | 京都两日 | 27 | 374s | 73s | 5s | <1s | **7.5 min** | ~$0.06 | `docs/demos/tradeoff/05-周末两天玩遍京都-20260710-105304.pptx` |

※ #3 首次跑在 `theme` 阶段 JSON 截断失败（422），重试 1 次成功。

### 5.3 美观最大化版（5/5 成功）

| # | 主题 | 页数 | outline | content | theme | render | **总时延** | 估算成本 | 产物 |
|---|------|------|---------|---------|-------|--------|-----------|---------|------|
| 1 | Python 入门 | 27 | 301s | 105s | 12s | <1s | **7.0 min** | ~$0.12 | `docs/demos/beauty/01-python-入门-30-分钟-20260710-115906.pptx` ※※ |
| 2 | 年度复盘 | 27 | 209s | 129s | 30s | <1s | **6.1 min** | ~$0.12 | `docs/demos/beauty/02-2025-我的年度复盘-20260710-110228.pptx` |
| 3 | 咖啡豆 | 27 | 256s | 106s | 9s | <1s | **6.2 min** | ~$0.12 | `docs/demos/beauty/03-如何挑选一款适合自己的咖啡豆-20260710-110840.pptx` |
| 4 | Rust 重写 | 27 | 511s | 164s | 42s | <1s | **11.9 min** | ~$0.12 | `docs/demos/beauty/04-给老板讲清楚为什么我们应该用-rust-重写订单系统-20260710-115152.pptx` ※ |
| 5 | 京都两日 | 27 | 226s | 179s | 23s | <1s | **7.1 min** | ~$0.12 | `docs/demos/beauty/05-周末两天玩遍京都-20260710-112012.pptx` |

※ #4 首次跑 `theme` 截断失败，重试 1 次成功（717s）。  
※※ #1 前 2 次分别在 `scenario` / `theme` 阶段失败，第 3 次成功。

### 5.4 题面约束核对

| 约束 | Trade-off | 美观最大化 |
|------|-----------|-----------|
| 单份时延 < 30 min | ✅ 最长 7.5 min | ✅ 最长 11.9 min |
| 单份成本 < $10（美观版） | — | ✅ 粗算 ~$0.12 |
| 25–30 页 | ✅ 全部 27 页 | ✅ 全部 27 页 |
| 叙事连贯（一套非一堆） | 大纲一次 pro + consistency 契约 + 邻页 hint | 同左，且 pro 逐页文案更饱满 |

**瓶颈观察：** `outline` 与 `scenarios` 在 `pptx` 阶段并行，计时相同；大纲单次 LLM 占 wall time 50–90%。京都、咖啡豆、Rust（beauty）等主题大纲耗时更长，与题材复杂度正相关。偶发失败集中在 **scenario / theme** 等小 JSON 输出阶段（非主链路），重试可恢复。

---

## 6. 遇到的问题和取舍

| 遇到的问题 / 权衡                                 | 处理                                                                                        |
|--------------------------------------------|-------------------------------------------------------------------------------------------|
| **大纲一次生成 27 页 → token被截断**                 | 加 `TOKEN_LADDER`（8192→12288→16384）+ 截断检测（结尾非 `}` / 页数 < 请求数）。在一次请求后检查是否被截断，如果被截断，则自动升档重试。 |
| **逐页串行太慢**                                 | 改并行，`Semaphore` 限流 8 路（`MAX_PARALLEL_SLIDES`），content 从数分钟降到 ~34s。                        |
| **单页偶发格式错 / 校验不过**                         | 分层重试：主模型 3 次（升 token），再换 1 个备选模型 2 次；校验失败把违规项回灌 prompt 重试。                                |
| **scenario / theme 小 JSON 偶发截断** | 大纲链路已加 token 阶梯；scenario、theme 仍仅 3 次重试且无升档，benchmark 中 4/10 首次失败、重试后 10/10 成功。后续应对齐 outline 的截断检测 + token ladder。 |
| **renderer 与 business 循环依赖风险**             | 把 renderer 做成**零业务依赖的独立工具模块**（仅 framework + POI），DTO 用容忍式可空字段解析，`app` 负责组装。               |
| **美观版未接 TEMPLATE 母版** | `PptxExportService` 仍固定 `PROGRAMMATIC`；美观版差异目前来自 **pro 全链路文案**，而非设计母版。TEMPLATE 模式已在 renderer 模块就绪，待 app 层切换。 |

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
- **招聘题面 10 套 benchmark**：`scripts/exam-benchmark.sh` 对 `docs/exam-fixtures/` 全量跑通，失败用例自动/手动重试直至 10/10，结果写入 `docs/benchmark-results.jsonl` 与 `docs/demos/`。
- **对数据保持怀疑**：区分「实测」与「待测」；成本在 token 未回传前标注为粗算。

---

## 8. 本轮开发与测试总结（至 2026-07-10）

### 8.1 系统能力概览

已完成题面核心链路：**JSON → 27 页叙事连贯 pptx**，含大纲规划、场景推断、逐页文案、主题色、程序化渲染；前端可表单输入 / 预览 / 下载；支持按场景标签 **全链路 restyle 重生成**。

### 8.2 关键工程修复（测试前完成）

| 问题 | 修复 |
|------|------|
| `/v1/ppt/restyle` Jackson 无法反序列化 `OutlineJson` | Controller 改 Gson 手工解析 |
| restyle 与首跑模型池不一致（mimo/minimax 404） | 主流程统一 `deepseek` 系列 |
| 大纲 `truncated_output` / `bulletHints` 超标 / 连续 content | `OutlinePlannerImpl` 截断检测、hint 裁剪、大 deck 加 attempts |
| 风格切换应重塑叙事而非只换色 | restyle = 新 stance → 新 outline → content → theme → pptx |

### 8.3 测试结论（对齐招聘题面）

- **交付物完整性**：代码 + README（待与题面 README 要求对齐）+ 本 `DESIGN.md` + **10 个 demo pptx** ✅
- **速度**：Trade-off 均值 ~4.6 min/套，美观版 ~7.7 min/套，均远低于 30 min 上限 ✅
- **成本**：粗算 Trade-off ~$0.06、美观 ~$0.12/套，远低于 $10 上限 ✅
- **美观度**：程序化渲染 + LLM 主题色 + 13 种 slideType 版式映射；美观版 pro 文案明显更饱满（未上 TEMPLATE 母版，仍有提升空间）
- **稳定性**：首次批量 7/10 成功，3 例为小 JSON 阶段偶发截断，重试后 **10/10**；生产环境需加固 scenario/theme 重试策略

### 8.4 复现命令

```bash
./scripts/dev-up.sh
./scripts/exam-benchmark.sh all          # 约 50–90 min，写 docs/benchmark-results.jsonl
# 单版：./scripts/exam-benchmark.sh tradeoff
# 单条：
curl -s -X POST 'http://127.0.0.1:8080/v1/ppt/run?stage=pptx&outlineModel=deepseek-pro&contentModel=deepseek-flash' \
  -H 'Content-Type: application/json' \
  --data-binary @docs/exam-fixtures/01-python-intro.json
```
