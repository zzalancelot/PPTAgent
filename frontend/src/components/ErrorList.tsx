import { Alert, Space, Typography } from "antd";
import type { PipelineError } from "../api";

const { Text } = Typography;

/** Human-readable one-liner for a structured pipeline error. */
function describe(err: PipelineError): string {
  const type = err.type ?? "error";
  switch (type) {
    case "invalid_json":
      return `JSON 无法解析：${err.message ?? ""}`;
    case "missing_field":
      return `缺少字段：${err.field}`;
    case "blank_field":
      return `字段为空：${err.field}`;
    case "brief_too_long":
      return `简介过长：${err.length} 字（上限 ${err.max}）`;
    case "invalid_slide_count":
      return `页数不合法：${err.value}（应在 ${err.min}–${err.max}）`;
    case "slide_count_wrong_type":
      return `页数类型错误：${JSON.stringify(err.actual)}`;
    case "llm_failure":
      return `模型调用失败：${err.message ?? ""}`;
    case "truncated_output":
      return `模型输出被截断（第 ${err.attempt} 次，max_tokens=${err.maxTokensUsed}）`;
    case "validation_failed":
      return `校验失败（第 ${err.attempt} 次）：${(err.violations as string[] | undefined)?.join("；") ?? ""}`;
    case "exhausted_retries":
      return `重试用尽（${err.attempts} 次）：${err.lastError ?? ""}`;
    case "slide_failed":
      return `第 ${err.index} 页生成失败（${err.sectionId}）：${err.message ?? ""}`;
    case "partial_failure":
      return `${err.message ?? "部分页面失败"}${
        (err.failedIndices as number[] | undefined)?.length
          ? `（页码：${(err.failedIndices as number[]).join(", ")}）`
          : ""
      }`;
    case "scenario_invalid_json":
      return `场景 JSON 无法解析：${err.message ?? ""}`;
    case "scenario_validation_failed":
      return `场景校验失败：${(err.violations as string[] | undefined)?.join("；") ?? err.message ?? ""}`;
    case "scenario_exhausted_retries":
      return `场景分析重试用尽（${err.attempts ?? "?"} 次）：${err.lastError ?? ""}`;
    case "theme_invalid_json":
      return `配色 JSON 无法解析：${err.message ?? ""}`;
    case "theme_validation_failed":
      return `配色校验失败：${err.message ?? ""}`;
    case "theme_exhausted_retries":
      return `配色重试用尽（${err.attempts ?? "?"} 次）：${err.lastError ?? ""}`;
    case "pptx_render_failed":
      return `PPTX 渲染失败：${err.message ?? ""}`;
    case "unknown_scenario_id":
      return `未知场景 ID：${err.scenarioId ?? err.message ?? ""}`;
    case "slide_count_mismatch":
      return `页数不匹配：期望 ${err.expected ?? "?"}，实际 ${err.actual ?? "?"}`;
    default:
      return err.message ?? JSON.stringify(err);
  }
}

export default function ErrorList({ errors }: { errors: PipelineError[] }) {
  if (!errors.length) return null;
  return (
    <Space direction="vertical" size={10} style={{ width: "100%" }}>
      {errors.map((err, i) => (
        <Alert
          key={i}
          type="error"
          showIcon
          message={<Text strong>{err.type ?? "error"}</Text>}
          description={describe(err)}
        />
      ))}
    </Space>
  );
}
