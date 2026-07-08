# PPT Agent Frontend

美化的输入/输出界面，用于驱动 PPTAgent 的 `parse → outline → content` 流水线。

- **框架**：React 18 + TypeScript + Vite
- **组件库**：Ant Design 5
- **HTTP**：axios，通过 Vite 代理转发到后端 `app` 模块

## 前置

后端需要先启动（在仓库根目录）：

```bash
# 1) 网关（gRPC :9090 / 运维 HTTP :9091）
./gradlew :gateway-server:bootRun

# 2) 应用 API（HTTP :8080，提供 /v1/ppt/*）
./gradlew :app:bootRun
```

## 开发

```bash
cd frontend
npm install
npm run dev
```

打开 http://localhost:5173 。开发服务器会把 `/v1/*` 代理到 `http://localhost:8080`
（可用环境变量 `VITE_API_TARGET` 覆盖后端地址）。

## 构建

```bash
npm run build      # 类型检查 + 产物打包到 dist/
npm run preview    # 本地预览 dist/
```

## 对接的接口

| 方法 | 路径 | 说明 |
|------|------|------|
| `POST` | `/v1/ppt/run?stage=&model=` | 执行 `parse` / `outline` / `content` 阶段 |
| `GET`  | `/v1/ppt/ping?model=` | 单次模型连通性测试 |
| `GET`  | `/v1/ppt/health` | 健康检查 |

请求体（与 fixtures 相同）：

```json
{
  "topic": "Python 入门 30 分钟",
  "brief": "……",
  "audience": "……",
  "slide_count": 27
}
```
