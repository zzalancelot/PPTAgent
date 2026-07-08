import { Space, Switch, Tooltip, Typography } from "antd";
import { BugOutlined } from "@ant-design/icons";

const { Text } = Typography;

const STORAGE_KEY = "ppt-agent-debug-mode";

export function readDebugMode(): boolean {
  try {
    return localStorage.getItem(STORAGE_KEY) === "1";
  } catch {
    return false;
  }
}

export function writeDebugMode(enabled: boolean): void {
  try {
    localStorage.setItem(STORAGE_KEY, enabled ? "1" : "0");
  } catch {
    // ignore quota / private browsing
  }
}

interface Props {
  checked: boolean;
  onChange: (enabled: boolean) => void;
}

/** Toggles advanced controls (stage / model selection). State is persisted in localStorage. */
export default function DebugModeSwitch({ checked, onChange }: Props) {
  return (
    <Tooltip title="开启后可选择执行阶段与模型">
      <Space size={8}>
        <BugOutlined style={{ color: checked ? "#6366f1" : "#9ca3af" }} />
        <Text type="secondary" style={{ fontSize: 13 }}>
          调试模式
        </Text>
        <Switch size="small" checked={checked} onChange={onChange} />
      </Space>
    </Tooltip>
  );
}
