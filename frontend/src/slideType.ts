/** Chinese label + tag color for each outline/content slide type. */
const MAP: Record<string, { label: string; color: string }> = {
  title: { label: "标题页", color: "magenta" },
  agenda: { label: "议程", color: "geekblue" },
  section_divider: { label: "章节分隔", color: "purple" },
  content: { label: "内容", color: "blue" },
  comparison: { label: "对比", color: "cyan" },
  timeline: { label: "时间线", color: "gold" },
  framework: { label: "框架", color: "volcano" },
  case_study: { label: "案例", color: "orange" },
  code_or_demo: { label: "代码/演示", color: "green" },
  quote: { label: "引用", color: "lime" },
  summary: { label: "总结", color: "geekblue" },
  call_to_action: { label: "行动号召", color: "red" },
  qa: { label: "问答", color: "default" },
};

export function slideTypeLabel(type: string): string {
  return MAP[type]?.label ?? type;
}

export function slideTypeColor(type: string): string {
  return MAP[type]?.color ?? "default";
}

/** Deterministic color for a model id (used in tags/legends). */
export function modelColor(model: string): string {
  switch (model) {
    case "deepseek":
    case "deepseek-pro":
      return "blue";
    case "deepseek-flash":
      return "geekblue";
    case "mimo":
      return "purple";
    case "minimax":
      return "volcano";
    default:
      return "default";
  }
}
