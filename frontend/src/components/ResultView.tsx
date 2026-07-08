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
  Typography,
} from "antd";
import {
  CheckCircleTwoTone,
  CloseCircleTwoTone,
  DownloadOutlined,
  RocketOutlined,
} from "@ant-design/icons";
import type { RunResponse, Stage } from "../api";
import ErrorList from "./ErrorList";
import OutlineView from "./OutlineView";
import ContentView from "./ContentView";

const { Text } = Typography;

const STAGE_LABEL: Record<Stage, string> = {
  parse: "解析",
  outline: "大纲",
  content: "内容",
  pptx: "PPTX",
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
            title={STAGE_LABEL[stage as Stage] ?? stage}
            value={(ms / 1000).toFixed(ms < 1000 ? 2 : 1)}
            suffix="s"
            valueStyle={{ fontSize: 20 }}
          />
        ))}
      </Flex>
    </Card>
  );
}

interface Props {
  loading: boolean;
  requestedStage: Stage;
  networkError: string | null;
  result: RunResponse | null;
}

export default function ResultView({ loading, requestedStage, networkError, result }: Props) {
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
            >
              下载 PPTX
            </Button>
          </Flex>
        </Card>
      ) : null}

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
