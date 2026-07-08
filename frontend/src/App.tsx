import { useCallback, useEffect, useState } from "react";
import { App as AntdApp, Badge, Button, Col, Layout, Row, Space, Tooltip, Typography } from "antd";
import { ApiOutlined, ReloadOutlined, ThunderboltOutlined } from "@ant-design/icons";
import {
  fetchHealth,
  pingModel,
  runPipeline,
  type ModelId,
  type RunRequestBody,
  type RunResponse,
  type Stage,
} from "./api";
import DebugModeSwitch, { readDebugMode, writeDebugMode } from "./components/DebugModeSwitch";
import InputForm, { DEFAULT_STAGE } from "./components/InputForm";
import ResultView from "./components/ResultView";

const { Text } = Typography;

type HealthState = "unknown" | "up" | "down";

function errorMessage(err: unknown): string {
  if (typeof err === "object" && err !== null) {
    const anyErr = err as { message?: string; code?: string };
    if (anyErr.code === "ECONNABORTED") return "请求超时（内容生成可能需要更久，可稍后重试）。";
    if (anyErr.message) return anyErr.message;
  }
  return String(err);
}

export default function App() {
  const { message } = AntdApp.useApp();
  const [loading, setLoading] = useState(false);
  const [result, setResult] = useState<RunResponse | null>(null);
  const [networkError, setNetworkError] = useState<string | null>(null);
  const [requestedStage, setRequestedStage] = useState<Stage>(DEFAULT_STAGE);
  const [health, setHealth] = useState<HealthState>("unknown");
  const [pinging, setPinging] = useState(false);
  const [debugMode, setDebugMode] = useState(readDebugMode);

  const handleDebugModeChange = (enabled: boolean) => {
    setDebugMode(enabled);
    writeDebugMode(enabled);
  };

  const refreshHealth = useCallback(async () => {
    try {
      await fetchHealth();
      setHealth("up");
    } catch {
      setHealth("down");
    }
  }, []);

  useEffect(() => {
    void refreshHealth();
  }, [refreshHealth]);

  const handleRun = async (body: RunRequestBody, stage: Stage, model: ModelId) => {
    setLoading(true);
    setNetworkError(null);
    setResult(null);
    setRequestedStage(stage);
    try {
      const data = await runPipeline(body, stage, model);
      setResult(data);
      if (data.status === "ok") message.success(`「${stage}」阶段完成`);
      else message.warning(`「${stage}」阶段返回错误`);
    } catch (err) {
      setNetworkError(errorMessage(err));
      message.error("请求失败");
    } finally {
      setLoading(false);
    }
  };

  const handlePing = async () => {
    setPinging(true);
    try {
      const res = await pingModel("deepseek");
      message.success(`DeepSeek 连通：${res.text ?? "(空响应)"}`);
      setHealth("up");
    } catch (err) {
      message.error(`连通失败：${errorMessage(err)}`);
      setHealth("down");
    } finally {
      setPinging(false);
    }
  };

  const healthBadge = {
    unknown: <Badge status="default" text="检测中" />,
    up: <Badge status="success" text="服务在线" />,
    down: <Badge status="error" text="服务离线" />,
  }[health];

  return (
    <Layout style={{ background: "transparent", minHeight: "100vh" }}>
      <div className="app-shell">
        <header className="app-header">
          <div className="brand">
            <div className="brand__logo">
              <ThunderboltOutlined />
            </div>
            <div>
              <h1 className="brand__title">PPT Agent 演示文稿生成台</h1>
              <p className="brand__subtitle">输入主题 · 生成大纲 · 逐页成稿 · 下载 PPTX</p>
            </div>
          </div>
          <Space size={12} wrap>
            <DebugModeSwitch checked={debugMode} onChange={handleDebugModeChange} />
            <Tooltip title="检查 app 服务健康状态">
              <Space size={8}>
                {healthBadge}
                <Button
                  size="small"
                  type="text"
                  icon={<ReloadOutlined />}
                  onClick={() => void refreshHealth()}
                />
              </Space>
            </Tooltip>
            <Button icon={<ApiOutlined />} loading={pinging} onClick={() => void handlePing()}>
              测试模型连通
            </Button>
          </Space>
        </header>

        <Row gutter={[24, 24]} style={{ marginTop: 8 }}>
          <Col xs={24} lg={9} xl={8}>
            <div className="sticky-col">
              <InputForm loading={loading} debugMode={debugMode} onRun={handleRun} />
              <Text
                type="secondary"
                style={{ display: "block", marginTop: 12, fontSize: 12, textAlign: "center" }}
              >
                默认对接 <code>/v1/ppt</code>（app :8080 → gateway :9090）
              </Text>
            </div>
          </Col>
          <Col xs={24} lg={15} xl={16}>
            <ResultView
              loading={loading}
              requestedStage={requestedStage}
              networkError={networkError}
              result={result}
            />
          </Col>
        </Row>
      </div>
    </Layout>
  );
}
