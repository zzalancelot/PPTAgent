import {
  Alert,
  Badge,
  Button,
  Card,
  Descriptions,
  Empty,
  Flex,
  Skeleton,
  Space,
  Statistic,
  Tag,
  Tooltip,
  Typography,
} from "antd";
import {
  CheckCircleTwoTone,
  CloseCircleTwoTone,
  DownloadOutlined,
  RocketOutlined,
} from "@ant-design/icons";
import type { RunResponse, Stage, PresentationScenario, PipelineError } from "../api";
import ErrorList from "./ErrorList";
import OutlineView from "./OutlineView";
import ContentView from "./ContentView";

const { Text } = Typography;

const STAGE_LABEL: Record<Stage, string> = {
  parse: "解析",
  outline: "大纲",
  content: "内容",
  pptx: "PPTX",
  restyle: "风格切换",
};

const TIMING_LABEL: Record<string, string> = {
  parse: "解析",
  outline: "大纲",
  content: "内容",
  pptx: "PPTX",
  scenarios: "场景分析",
  theme: "配色",
  restyle: "风格切换",
};

function TimingBar({ timingMs }: { timingMs: Record<string, number> }) {
  const entries = Object.entries(timingMs);
  if (!entries.length) return null;
  return (
    <Card size="small" variant="borderless" style={{ background: "rgba(99,102,241,0.06)" }}>
      <Flex gap={32} wrap>
        {entries.map(([stage, ms]) => (
          <Statistic
            key={stage}
            title={TIMING_LABEL[stage] ?? stage}
            value={(ms / 1000).toFixed(ms < 1000 ? 2 : 1)}
            suffix="s"
            valueStyle={{ fontSize: 20 }}
          />
        ))}
      </Flex>
    </Card>
  );
}

function ThemeSwatches({ colors }: { colors: string[] }) {
  return (
    <Flex gap={6} align="center" wrap style={{ marginTop: 8 }}>
      <Text type="secondary" style={{ fontSize: 12 }}>配色：</Text>
      {colors.map((c) => (
        <Tooltip key={c} title={c}>
          <div
            style={{
              width: 24,
              height: 24,
              borderRadius: 4,
              background: c,
              border: "1px solid rgba(0,0,0,0.1)",
              cursor: "default",
            }}
          />
        </Tooltip>
      ))}
    </Flex>
  );
}

interface Props {
  loading: boolean;
  restyling: boolean;
  requestedStage: Stage;
  networkError: string | null;
  result: RunResponse | null;
  restyleErrors: PipelineError[];
  selectedScenarioId: string | null;
  onSelectScenario: (scenarioId: string) => void;
  onRestyle: () => void;
}

export default function ResultView({
  loading,
  restyling,
  requestedStage,
  networkError,
  result,
  restyleErrors,
  selectedScenarioId,
  onSelectScenario,
  onRestyle,
}: Props) {
  if (loading) {
    return (
      <Card variant="borderless">
        <Space style={{ marginBottom: 16 }}>
          <RocketOutlined spin style={{ color: "#6366f1" }} />
          <Text type="secondary">
            正在执行「{STAGE_LABEL[requestedStage]}」阶段
            {requestedStage === "content" || requestedStage === "pptx"
              ? "，逐页生成可能需要数分钟…"
              : "…"}
          </Text>
        </Space>
        <Skeleton active paragraph={{ rows: 6 }} />
        <Skeleton active paragraph={{ rows: 4 }} style={{ marginTop: 20 }} />
      </Card>
    );
  }

  if (networkError) {
    return (
      <Alert
        type="error"
        showIcon
        message="请求失败"
        description={
          <Space direction="vertical">
            <span>{networkError}</span>
            <Text type="secondary">
              请确认 app 服务已在 :8080 运行，且 gateway-server 已在 :9090 就绪。
            </Text>
          </Space>
        }
      />
    );
  }

  if (!result) {
    return (
      <div className="result-empty">
        <Empty
          image={Empty.PRESENTED_IMAGE_SIMPLE}
          description={<Text type="secondary">填写左侧表单并点击「开始生成」查看结果</Text>}
        />
      </div>
    );
  }

  const ok = result.status === "ok";

  return (
    <Space direction="vertical" size={16} style={{ width: "100%" }}>
      <Flex align="center" justify="space-between" wrap gap={12}>
        <Space size={10}>
          {ok ? (
            <CheckCircleTwoTone twoToneColor="#10b981" style={{ fontSize: 20 }} />
          ) : (
            <CloseCircleTwoTone twoToneColor="#ef4444" style={{ fontSize: 20 }} />
          )}
          <Badge
            status={ok ? "success" : "error"}
            text={
              <Text strong>
                {STAGE_LABEL[result.stage] ?? result.stage} · {ok ? "成功" : "失败"}
              </Text>
            }
          />
        </Space>
        <Tag color={ok ? "success" : "error"}>{result.status.toUpperCase()}</Tag>
      </Flex>

      <TimingBar timingMs={result.timingMs} />

      {result.pptx ? (
        <Card variant="borderless" style={{ background: "rgba(16,185,129,0.08)" }}>
          <Flex align="center" justify="space-between" wrap gap={12}>
            <Space direction="vertical" size={4}>
              <Text strong>演示文稿已生成</Text>
              <Text type="secondary" style={{ fontSize: 12 }}>
                {result.pptx.fileName} · {result.pptx.slideCount} 页 · 保存在{" "}
                <code>build/output/pptx/</code>
              </Text>
            </Space>
            <Button
              type="primary"
              size="large"
              icon={<DownloadOutlined />}
              href={result.pptx.downloadUrl}
              download={result.pptx.fileName}
              disabled={restyling}
            >
              {restyling ? "切换中…" : "下载 PPTX"}
            </Button>
          </Flex>
          {result.themeColors?.length ? <ThemeSwatches colors={result.themeColors} /> : null}
        </Card>
      ) : null}

      {result.pptx && result.scenarios?.length ? (
        <Card variant="borderless" title="演示风格">
          <Text type="secondary" style={{ display: "block", marginBottom: 12, fontSize: 12 }}>
            选择风格后重新生成将重新规划大纲、文案、配色与 PPTX，约需数分钟
          </Text>
          <Space wrap>
            {result.scenarios.map((s: PresentationScenario) => {
              const isApplied = result.deckStance?.scenarioId === s.id;
              const isSelected = selectedScenarioId === s.id;
              return (
                <Tooltip
                  key={s.id}
                  title={
                    <span>
                      {s.description}
                      <br />
                      受众：{s.audienceFrame} · 语气：{s.voiceTone}
                    </span>
                  }
                >
                  <Tag
                    color={isApplied ? "success" : isSelected ? "processing" : "default"}
                    style={{
                      cursor: restyling ? "not-allowed" : "pointer",
                      fontSize: 13,
                      padding: "4px 12px",
                    }}
                    onClick={() => {
                      if (!restyling) onSelectScenario(s.id);
                    }}
                  >
                    {s.label}
                    {isApplied ? "（当前）" : null}
                  </Tag>
                </Tooltip>
              );
            })}
          </Space>
          <Flex align="center" justify="space-between" wrap gap={12} style={{ marginTop: 12 }}>
            {result.deckStance ? (
              <Text type="secondary" style={{ fontSize: 12 }}>
                已应用：{result.deckStance.label} · {result.deckStance.colorMood} ·{" "}
                {result.deckStance.voiceTone}
              </Text>
            ) : (
              <span />
            )}
            <Button
              type="primary"
              icon={<RocketOutlined />}
              loading={restyling}
              disabled={
                restyling ||
                !selectedScenarioId ||
                selectedScenarioId === result.deckStance?.scenarioId
              }
              onClick={onRestyle}
            >
              {restyling ? "重新生成中…" : "按此风格重新生成"}
            </Button>
          </Flex>
          {restyling ? (
            <Text type="secondary" style={{ display: "block", marginTop: 8 }}>
              <RocketOutlined spin /> 正在重新规划大纲并生成内容…
            </Text>
          ) : null}
        </Card>
      ) : null}

      {restyleErrors.length ? <ErrorList errors={restyleErrors} /> : null}
      {result.errors?.length ? <ErrorList errors={result.errors} /> : null}

      {result.input ? (
        <Card variant="borderless" title="已校验的输入">
          <Descriptions size="small" column={1} bordered>
            <Descriptions.Item label="主题">{result.input.topic}</Descriptions.Item>
            <Descriptions.Item label="受众">{result.input.audience}</Descriptions.Item>
            <Descriptions.Item label="页数">{result.input.slideCount}</Descriptions.Item>
            <Descriptions.Item label="简介">{result.input.brief}</Descriptions.Item>
          </Descriptions>
        </Card>
      ) : null}

      {result.outline ? <OutlineView outline={result.outline} /> : null}
      {result.content ? <ContentView deck={result.content} /> : null}
    </Space>
  );
}
