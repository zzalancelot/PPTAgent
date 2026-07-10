import { useCallback, useEffect, useRef, useState } from "react";
import { App as AntdApp, Badge, Button, Col, Layout, Row, Space, Tooltip, Typography } from "antd";
import { ApiOutlined, ReloadOutlined, ThunderboltOutlined } from "@ant-design/icons";
import {
  fetchHealth,
  pingModel,
  restylePipeline,
  runPipeline,
  type ModelId,
  type RestyleRequestBody,
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
    const anyErr = err as { message?: string; code?: string; response?: { data?: { message?: string } } };
    if (anyErr.code === "ECONNABORTED") return "请求超时（内容生成可能需要更久，可稍后重试）。";
    // Extract message from axios error response body when available
    const dataMsg = anyErr.response?.data?.message;
    if (dataMsg) return dataMsg;
    if (anyErr.message) return anyErr.message;
  }
  return String(err);
}

export default function App() {
  const { message } = AntdApp.useApp();
  const genRef = useRef(0);
  const [loading, setLoading] = useState(false);
  const [restyling, setRestyling] = useState(false);
  const [result, setResult] = useState<RunResponse | null>(null);
  const [restyleErrors, setRestyleErrors] = useState<RunResponse["errors"]>([]);
  const [networkError, setNetworkError] = useState<string | null>(null);
  const [requestedStage, setRequestedStage] = useState<Stage>(DEFAULT_STAGE);
  const [health, setHealth] = useState<HealthState>("unknown");
  const [pinging, setPinging] = useState(false);
  const [debugMode, setDebugMode] = useState(readDebugMode);
  const [selectedScenarioId, setSelectedScenarioId] = useState<string | null>(null);

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

  useEffect(() => {
    setSelectedScenarioId(result?.deckStance?.scenarioId ?? null);
  }, [result?.deckStance?.scenarioId, result?.pptx?.fileName]);

  const handleRun = async (body: RunRequestBody, stage: Stage, model: ModelId) => {
    const gen = ++genRef.current;
    setLoading(true);
    setNetworkError(null);
    setResult(null);
    setRestyleErrors([]);
    setRequestedStage(stage);
    try {
      const data = await runPipeline(body, stage, model);
      if (gen !== genRef.current) return;
      setResult(data);
      if (data.status === "ok") message.success(`「${stage}」阶段完成`);
      else message.warning(`「${stage}」阶段返回错误`);
    } catch (err) {
      if (gen !== genRef.current) return;
      setNetworkError(errorMessage(err));
      message.error("请求失败");
    } finally {
      if (gen === genRef.current) setLoading(false);
    }
  };

  const handleRestyle = async () => {
    if (!selectedScenarioId) return;
    if (!result?.input || !result?.scenarios) {
      message.warning("请先完成完整 PPTX 生成");
      return;
    }
    if (selectedScenarioId === result.deckStance?.scenarioId) {
      message.info("当前已是该演示风格");
      return;
    }
    const gen = ++genRef.current;
    setRestyling(true);
    setRestyleErrors([]);
    try {
      const body: RestyleRequestBody = {
        topic: result.input.topic,
        brief: result.input.brief,
        audience: result.input.audience,
        slideCount: result.input.slideCount,
        scenarioId: selectedScenarioId,
        scenarios: result.scenarios,
      };
      const updated = await restylePipeline(body);
      if (gen !== genRef.current) return;
      if (updated.status === "error") {
        setRestyleErrors(updated.errors ?? []);
        message.error("风格切换失败，请重试");
        return;
      }
      // Use server outline (new stance-aware outline), keep scenarios from response or previous
      const merged: RunResponse = {
        ...updated,
        scenarios: updated.scenarios ?? result.scenarios,
      };
      setResult(merged);
      setRestyleErrors([]);
      message.success("风格切换完成");
    } catch (err) {
      if (gen !== genRef.current) return;
      message.error(`风格切换请求失败：${errorMessage(err)}`);
    } finally {
      if (gen === genRef.current) setRestyling(false);
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
              <InputForm loading={loading || restyling} debugMode={debugMode} onRun={handleRun} />
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
              restyling={restyling}
              requestedStage={requestedStage}
              networkError={networkError}
              result={result}
              restyleErrors={restyleErrors}
              selectedScenarioId={selectedScenarioId}
              onSelectScenario={setSelectedScenarioId}
              onRestyle={() => void handleRestyle()}
            />
          </Col>
        </Row>
      </div>
    </Layout>
  );
}
