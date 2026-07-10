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
- **多种 `slideType`**：大纲规则要求「连续不超过 3 个同类型」。
- **版式随类型映射**：`SlideLayoutMapper` 把 slideType 映射到 TITLE / BULLETS / TWO_COLUMN / BODY_TEXT 等 7 种版式，comparison 走双栏、code_or_demo 走等宽正文，视觉上不重样。
- **分节分配不同模型**：`ModelAssignmentPolicy` 按节轮转分配模型，天然引入措辞风格的细微差异（同时也分摊压力）。
- **内容由 intent + bulletHints 扩写**：每页 prompt 要求「在 hint 基础上加一层（定义 / 例子 / 为什么重要 / 常见坑）」，而非照抄 hint，避免模板腔。

---

## 5. 成本与时延实测（5 套 demo × 2 版）

>此处使用DeepSeek-v4进行测试，调试+整体开发总共消耗￥8.29CNY

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

| 遇到的问题 / 权衡                       | 处理                                                                                        |
|----------------------------------|-------------------------------------------------------------------------------------------|
| **大纲一次性生成，单次JSO过长导致token被截断**    | 加 `TOKEN_LADDER`（8192→12288→16384）+ 截断检测（结尾非 `}` / 页数 < 请求数）。在一次请求后检查是否被截断，如果被截断，则自动升档重试。 |
| **单页偶发格式错 / 校验不过**               | 分层重试：主模型 3 次（升 token），再换 1 个备选模型 2 次；校验失败把违规项回灌 prompt 重试。                                |
| **scenario / theme 小 JSON 偶发截断** | 大纲链路已加 token 阶梯；scenario、theme 仍仅 3 次重试且无升档，benchmark 中 4/10 首次失败、重试后 10/10 成功。后续应对齐 outline 的截断检测 + token ladder。 |
| **renderer 与 business 循环依赖风险**   | 把 renderer 做成**零业务依赖的独立工具模块**（仅 framework + POI），DTO 用容忍式可空字段解析，`app` 负责组装。               |
| **美观版未接 TEMPLATE 母版**            | `PptxExportService` 仍固定 `PROGRAMMATIC`；美观版差异目前来自 **pro 全链路文案**，而非设计母版。TEMPLATE 模式已在 renderer 模块就绪，待 app 层切换。 |

---

## 7. AI 协作复盘

协作模式可以概括成：**人定方向与验收标准 → 写成英文 spec / prompt 交给编码 Agent 分段落地 → 人用代码、测试、实跑验收，必要时推翻或回滚**。AI 加速实现，但不替代判断。

### 人提出、与 AI 讨论后采纳的
- **分层网关架构**（`framework` / gateway / `business` 隔离，`GatewayModel` 枚举选模型）：人定边界，AI 补齐实现细节；换 provider 只改 YAML，业务层不碰 SDK。
- **「一套而非一堆」靠大纲契约**：先一次 LLM 产出整套大纲 + `consistency`，再并行扩写逐页；人坚持叙事线优先于「每页各自好看」。
- **风格切换 = 全链路重生成**：早期 AI 倾向「只换主题色 / 保留旧大纲」；人明确要求 restyle 必须按新 stance 重规划大纲 → content → theme → pptx，并写成 `docs/SCENARIO_FULL_REGEN_PROMPT.md` 交给 Agent 改。
- **交付物按题面验收**：公开开发集 5 主题 × 两版、实测表进 `DESIGN.md`、demo pptx 入库；人要求「不编造未测数据」，AI 负责跑 benchmark 与填表。

### AI 提的、照做了
- **双档模型策略**（pro 管大纲、flash 管逐页）：符合成本 / 时延直觉，落地为 Trade-off 版默认配置。
- **renderer 双模式**（PROGRAMMATIC + TEMPLATE）：兼顾「快出图」与「可换母版」；当前导出仍以 PROGRAMMATIC 为主。
- **并行 + 信号量限流**、**outline 的 token 阶梯重试**：工程细节直接落地，显著压低 content 阶段 wall time。
- **前端 bug 清单 → 英文修复 prompt**：人先让 AI 审计前端问题，再整理成 `docs/FRONTEND_BUGFIX_PROMPT.md` 交给编码 Agent，避免口头需求漂移。

### AI 提的、被推翻 / 修正
- **renderer 想直接挂进 business**：会造成循环依赖；推翻为独立工具模块，由 `app` 组装。
- **模板只生成在 build 目录、不入库**：推翻「运行时隐式生成」；要求脚本 + 二进制可复现。
- **风格切换只改配色 / 保留旧 outline**：产品上不够；推翻为 stance 驱动的全链路 regen（见上）。

### AI 跑偏、被拽回来的具体例子
1. **用旧数据自证「内容更丰富了」**：Agent 拿升级前的 `content-test-*.json` 当证据。拽回来：旧 JSON 只能测 renderer，密度必须以**重跑 `stage=content`** 为准。
2. **「服务启动成功」但进程已死**：Agent 终端里 `bootRun` / health 全绿，会话结束进程被回收；或 `dev-up` 与后续 curl 拆成两次会话，中间服务已 DOWN。拽回来：`nohup`+`disown`、长任务与启动放同一会话、并提醒用户在本机终端常驻。
3. **脚本 CRLF / 误用 `setsid`**：本机直接跑挂；人定位换行符与 macOS 兼容性后修。
4. **restyle 反序列化翻车**：Jackson 无法构造 `OutlineJson`，前端表现为「风格切换失败」。人要求先定位根因再改：Controller 改 Gson 手工解析，并补解析单测。
5. **提交前审查与「面试官视角」冒烟**：人把代码拉到新路径 `TestCode/PPTAgent`、只配 DeepSeek key，要求按 README 跑京都冒烟；AI 按「clone 后第一次跑」路径验收，而不是在开发机上假设环境永远在线。

### 对 AI 输出做的核验 / 兜底
- **对照 spec / acceptance criteria**，不看「Agent 说完成了」。
- **单元测试必绿**：`:renderer:test` / `:business:test` / `:app:test`（必要时 `./gradlew build`）。
- **端到端实跑**：`stage=pptx` 落盘；题面 5×2 benchmark（`scripts/exam-benchmark.sh`）失败则重试直至 10/10，产物进 `docs/demos/`。
- **成本与时延只填实测**：token 未回传则标注粗算，不编造。
- **改坏就回滚**：MiMo 通路整段 `git restore` + 删临时产物，避免半成品污染主分支。
- **交作业前再扫一遍已知缺口**（不读本文件时的独立 review）：UI 默认 `deepseek` 与 README 的 pro+flash 不一致、scenario/theme 无 token 阶梯、restyle 硬编码模型等——记入风险，不假装已修。

---

## 8. 交付与测试结论（至 2026-07-10）

### 8.1 系统能力

题面核心链路已通：**JSON → 27 页叙事连贯 `.pptx`**（parse → outline ∥ scenarios → content → theme → render）；前端可输入 / 预览 / 下载；支持按场景 **全链路 restyle**。主流程实测以 **DeepSeek pro + flash（Trade-off）** 与 **全 pro（美观版）** 为准。

### 8.2 关键工程修复（benchmark 前）

| 问题 | 处理 |
|------|------|
| `/v1/ppt/restyle` Jackson 反序列化失败 | Controller 改 Gson 手工解析 |
| restyle / 首跑模型池不一致（mimo/minimax 404） | 主流程收敛到 DeepSeek 系列 |
| 大纲截断 / `bulletHints` 超标 / 连续同类型 | `OutlinePlannerImpl` 升档重试、hint 裁剪、大 deck 加 attempts |
| 风格切换应重塑叙事 | restyle = stance → 新 outline → content → theme → pptx |
| MiMo 通路尝试后效果差 | **整段回滚**，不纳入交付默认路径 |

### 8.3 对齐题面的验收结论

| 维度 | 结论 |
|------|------|
| 交付物 | 代码 + README + 本决策日志 + `docs/demos/` 两版各 5 套 pptx |
| 速度 | Trade-off 最长 ~7.5 min，美观版最长 ~11.9 min，均 < 30 min |
| 成本 | 粗算 ~$0.06 / ~$0.12 每套，均 ≪ $10 |
| 稳定性 | 批量首次约 7/10，scenario/theme 偶发截断，重试后 10/10；已知缺口见 §6 |
| 面试官视角冒烟 | 新 clone + 仅配 DeepSeek key，京都 `stage=pptx`（pro+flash）HTTP 200、27 页落盘 |

### 8.4 怎么复现（优先前端）

**推荐路径：一键起服务 → 浏览器填表生成。**

```bash
cp ai-keys.yaml.example ai-keys.yaml   # 填入 DeepSeek key
./scripts/dev-up.sh                    # gateway + app + 前端
./scripts/dev-status.sh                # 可选：确认三端 UP
```

浏览器打开 http://127.0.0.1:5173 ：

1. 填入 `topic` / `brief` / `audience`（可参考 `docs/exam-fixtures/` 五个公开题）
2. 默认阶段为 **pptx**，点生成，等待大纲 → 文案 → 下载按钮出现
3. 需要换风格时：选场景标签 → 「按此风格重新生成」
4. 停止：`./scripts/dev-down.sh`

已生成的两版 demo 也可直接打开：`docs/demos/tradeoff/`、`docs/demos/beauty/`。

**可选：命令行（无 UI / 批跑 / 对齐 README 的 pro+flash 时）**

```bash
# 单条 Trade-off（pro 大纲 + flash 逐页）
curl -s -X POST \
  'http://127.0.0.1:8080/v1/ppt/run?stage=pptx&outlineModel=deepseek-pro&contentModel=deepseek-flash' \
  -H 'Content-Type: application/json' \
  --data-binary @docs/exam-fixtures/05-kyoto-weekend.json

# 批量 5 主题 × 2 版（约 1–2 小时）
./scripts/exam-benchmark.sh all
```

> 说明：前端默认传单一 `model=deepseek`；若要与 benchmark / README 完全一致的 pro+flash，请用上面的 curl，或在 debug 模式里指定模型（若界面已开放）。
