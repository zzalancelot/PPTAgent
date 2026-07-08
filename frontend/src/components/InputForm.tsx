import {
  Button,
  Card,
  Divider,
  Flex,
  Form,
  Input,
  InputNumber,
  Segmented,
  Select,
  Space,
  Tooltip,
  Typography,
} from "antd";
import {
  ExperimentOutlined,
  FileTextOutlined,
  ProfileOutlined,
  ThunderboltOutlined,
} from "@ant-design/icons";
import type { ModelId, RunRequestBody, Stage } from "../api";

const { TextArea } = Input;
const { Text } = Typography;

export interface FormValues {
  topic: string;
  audience: string;
  brief: string;
  slideCount?: number | null;
  stage: Stage;
  model: ModelId;
}

/** Defaults used when debug mode is off (stage / model UI hidden). */
export const DEFAULT_STAGE: Stage = "pptx";
export const DEFAULT_MODEL: ModelId = "deepseek";

const EXAMPLE: FormValues = {
  topic: "Python 入门 30 分钟",
  audience: "没有编程经验、想快速了解 Python 能做什么的职场新人",
  brief:
    "面向零基础学员的 30 分钟 Python 快速入门讲座，涵盖安装环境、变量与数据类型、条件与循环、函数定义，以及一个可以运行的小例子，帮助听众建立继续自学的信心和路线图。",
  slideCount: null,
  stage: DEFAULT_STAGE,
  model: DEFAULT_MODEL,
};

const STAGE_HINTS: Record<Stage, string> = {
  parse: "仅校验输入（毫秒级）",
  outline: "解析 + 一次大纲 LLM 调用（约 1–2 分钟）",
  content: "解析 + 大纲 + 逐页生成（27 页约数分钟）",
  pptx: "完整流水线 + 导出可下载的 .pptx",
};

interface Props {
  loading: boolean;
  debugMode: boolean;
  onRun: (body: RunRequestBody, stage: Stage, model: ModelId) => void;
}

export default function InputForm({ loading, debugMode, onRun }: Props) {
  const [form] = Form.useForm<FormValues>();
  const stage = Form.useWatch("stage", form) ?? DEFAULT_STAGE;
  const effectiveStage = debugMode ? stage : DEFAULT_STAGE;
  const stageHint = STAGE_HINTS[effectiveStage];

  const handleFinish = (values: FormValues) => {
    const body: RunRequestBody = {
      topic: values.topic.trim(),
      brief: values.brief.trim(),
      audience: values.audience.trim(),
    };
    if (values.slideCount != null) body.slide_count = values.slideCount;
    const runStage = debugMode ? values.stage : DEFAULT_STAGE;
    const runModel = debugMode ? values.model : DEFAULT_MODEL;
    onRun(body, runStage, runModel);
  };

  return (
    <Card
      variant="borderless"
      title={
        <Space>
          <ProfileOutlined style={{ color: "#6366f1" }} />
          <span>输入</span>
        </Space>
      }
      extra={
        <Button type="link" size="small" onClick={() => form.setFieldsValue(EXAMPLE)}>
          填充示例
        </Button>
      }
    >
      <Form
        form={form}
        layout="vertical"
        initialValues={{ stage: DEFAULT_STAGE, model: DEFAULT_MODEL, slideCount: null }}
        requiredMark="optional"
        onFinish={handleFinish}
        disabled={loading}
      >
        <Form.Item
          name="topic"
          label="主题 Topic"
          rules={[{ required: true, message: "请输入演示主题" }]}
        >
          <Input placeholder="例如：Python 入门 30 分钟" allowClear />
        </Form.Item>

        <Form.Item
          name="audience"
          label="受众 Audience"
          rules={[{ required: true, message: "请描述你的听众" }]}
        >
          <Input placeholder="例如：没有编程经验的职场新人" allowClear />
        </Form.Item>

        <Form.Item
          name="brief"
          label="简介 Brief"
          rules={[
            { required: true, message: "请输入简介" },
            { max: 500, message: "简介最多 500 字" },
          ]}
        >
          <TextArea
            placeholder="用几句话描述这次演示要讲什么、达到什么目的…"
            autoSize={{ minRows: 4, maxRows: 8 }}
            showCount
            maxLength={500}
          />
        </Form.Item>

        <Form.Item
          name="slideCount"
          label={
            <Space size={4}>
              <span>页数 Slide count</span>
              <Text type="secondary" style={{ fontSize: 12 }}>
                (可选，25–30，留空默认 27)
              </Text>
            </Space>
          }
        >
          <InputNumber min={25} max={30} placeholder="27" style={{ width: "100%" }} />
        </Form.Item>

        {debugMode ? (
          <>
            <Divider style={{ margin: "8px 0 16px" }} />

            <Form.Item name="stage" label="执行阶段 Stage">
              <Segmented
                block
                options={[
                  { label: "解析", value: "parse", icon: <FileTextOutlined /> },
                  { label: "大纲", value: "outline", icon: <ProfileOutlined /> },
                  { label: "内容", value: "content", icon: <ExperimentOutlined /> },
                  { label: "PPTX", value: "pptx", icon: <ThunderboltOutlined /> },
                ]}
              />
            </Form.Item>
            <Text type="secondary" style={{ display: "block", marginTop: -8, marginBottom: 16, fontSize: 12 }}>
              {stageHint}
            </Text>

            <Form.Item name="model" label="模型 Model">
              <Select
                options={[
                  { label: "DeepSeek", value: "deepseek" },
                  { label: "MiMo", value: "mimo" },
                  { label: "MiniMax", value: "minimax" },
                ]}
              />
            </Form.Item>
          </>
        ) : null}

        <Form.Item style={{ marginBottom: 0, marginTop: 8 }}>
          <Flex gap={12}>
            <Tooltip title={debugMode ? stageHint : "完整流水线：解析 → 大纲 → 成稿 → 导出 PPTX"}>
              <Button
                type="primary"
                htmlType="submit"
                size="large"
                block
                loading={loading}
                icon={<ThunderboltOutlined />}
              >
                {loading ? "生成中…" : "开始生成"}
              </Button>
            </Tooltip>
          </Flex>
        </Form.Item>
      </Form>
    </Card>
  );
}
