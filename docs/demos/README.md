# 招聘题面 Demo（5 主题 × 2 版本）

公开开发集输入见 [`../exam-fixtures/`](../exam-fixtures/)。以下为实测产出的 10 个 `.pptx`（均为 27 页）。

## Trade-off 版

`outlineModel=deepseek-pro`，`contentModel=deepseek-flash`（全局 pro + 逐页 flash，偏成本/速度）。

| # | 主题 | 文件 |
|---|------|------|
| 1 | Python 入门 30 分钟 | `tradeoff/01-python-入门-30-分钟-20260710-103240.pptx` |
| 2 | 2025 我的年度复盘 | `tradeoff/02-2025-我的年度复盘-20260710-103547.pptx` |
| 3 | 如何挑选一款适合自己的咖啡豆 | `tradeoff/03-如何挑选一款适合自己的咖啡豆-20260710-113400.pptx` |
| 4 | Rust 重写订单系统 | `tradeoff/04-给老板讲清楚为什么我们应该用-rust-重写订单系统-20260710-104531.pptx` |
| 5 | 周末两天玩遍京都 | `tradeoff/05-周末两天玩遍京都-20260710-105304.pptx` |

## 美观最大化版

全链路 `deepseek-pro`（偏文案质量；单份时延/成本仍满足题面 < 30 min、< $10）。

| # | 主题 | 文件 |
|---|------|------|
| 1 | Python 入门 30 分钟 | `beauty/01-python-入门-30-分钟-20260710-115906.pptx` |
| 2 | 2025 我的年度复盘 | `beauty/02-2025-我的年度复盘-20260710-110228.pptx` |
| 3 | 如何挑选一款适合自己的咖啡豆 | `beauty/03-如何挑选一款适合自己的咖啡豆-20260710-110840.pptx` |
| 4 | Rust 重写订单系统 | `beauty/04-给老板讲清楚为什么我们应该用-rust-重写订单系统-20260710-115152.pptx` |
| 5 | 周末两天玩遍京都 | `beauty/05-周末两天玩遍京都-20260710-112012.pptx` |

实测时延与估算成本见根目录 [`DESIGN.md`](../../DESIGN.md) §5；原始 JSON 行见 [`../benchmark-results.jsonl`](../benchmark-results.jsonl)。
