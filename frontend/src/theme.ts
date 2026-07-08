import type { ThemeConfig } from "antd";

/** A calm, modern indigo/violet theme shared across the app. */
export const theme: ThemeConfig = {
  token: {
    colorPrimary: "#6366f1",
    colorInfo: "#6366f1",
    colorSuccess: "#10b981",
    colorWarning: "#f59e0b",
    colorError: "#ef4444",
    borderRadius: 10,
    fontSize: 14,
    fontFamily:
      '"PingFang SC", "Microsoft YaHei", -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif',
    colorBgLayout: "#f5f6fb",
  },
  components: {
    Card: {
      boxShadowTertiary: "0 6px 24px rgba(17, 17, 26, 0.06)",
    },
    Layout: {
      headerBg: "transparent",
      bodyBg: "transparent",
    },
  },
};
