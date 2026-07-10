# PPTAgent — JSON 进，一套连贯 PPT 出

输入 `topic` / `brief` / `audience` JSON，输出 **25–30 页、风格一致、叙事连贯** 的 `.pptx`。

- **决策日志**：[`DESIGN.md`](DESIGN.md)（架构、模型选型、一致性、实测数据、AI 协作复盘）
- **招聘题面 demo**：[`docs/demos/`](docs/demos/)（5 主题 × Trade-off / 美观最大化 两版，共 10 个 pptx）
- **公开开发集**：[`docs/exam-fixtures/`](docs/exam-fixtures/)

## 前置条件

- JDK 21
- Node.js 18+（仅前端）
- DeepSeek API Key（主流程；MiMo / MiniMax 可选）

## 配置 API Key

密钥不入库。任选其一：

```bash
cp ai-keys.yaml.example ai-keys.yaml   # 已 gitignore
```

```yaml
ai:
  keys:
    deepseek: "sk-..."
```

或环境变量：`DEEPSEEK_API_KEY`。

## 本地一键启动

```bash
./scripts/dev-up.sh      # gateway :9091 + app :8080 + frontend :5173
./scripts/dev-status.sh  # 健康检查
./scripts/dev-down.sh    # 停止
```

浏览器打开 http://127.0.0.1:5173 ，填表单生成；或直接用 API / CLI。

## 跑一个 Demo（单条命令 JSON → pptx）

**Trade-off 版**（推荐日常）：pro 大纲 + flash 逐页

```bash
curl -s -X POST \
  'http://127.0.0.1:8080/v1/ppt/run?stage=pptx&outlineModel=deepseek-pro&contentModel=deepseek-flash' \
  -H 'Content-Type: application/json' \
  --data-binary @docs/exam-fixtures/01-python-intro.json
```

成功响应含 `pptx.fileName`；文件落在 `build/output/pptx/`，也可：

```bash
curl -OJ "http://127.0.0.1:8080/v1/ppt/download/<fileName>"
```

**美观最大化版**：全链路 pro

```bash
curl -s -X POST \
  'http://127.0.0.1:8080/v1/ppt/run?stage=pptx&outlineModel=deepseek-pro&contentModel=deepseek-pro' \
  -H 'Content-Type: application/json' \
  --data-binary @docs/exam-fixtures/01-python-intro.json
```

**批量跑题面 5 套 × 2 版**（约 1–2 小时，写 `docs/benchmark-results.jsonl`）：

```bash
./scripts/exam-benchmark.sh all
```

## 构建与测试

```bash
./gradlew build
./gradlew :business:test :app:test :renderer:test
```

单元测试使用 Fake LLM，无需网络与 API Key。

## 模块结构

| 模块 | 职责 |
|------|------|
| `framework` | 接口与 DTO（`GatewayModel`、`ModelClient` 等） |
| `gateway-server` | gRPC `:9090` + HTTP ops `:9091`，对接 DeepSeek / MiMo / MiniMax |
| `gateway-client` / `llm-adapter` | 业务层与网关之间的传输与适配 |
| `business` | 大纲、场景、逐页文案、主题色等领域逻辑 |
| `renderer` | deck JSON → `.pptx`（Apache POI，无 LLM） |
| `app` | REST API（`/v1/ppt/run`、`/restyle`、下载） |
| `frontend` | React 表单 + 结果预览 |

```
frontend → app → business → llm-adapter → gateway-client → gateway-server → provider
                    └→ renderer (pptx)
```

网关细节见 [`GATEWAY.md`](GATEWAY.md)。
