## 证道 — 正式版

在 Android 上运行 AI Agent 的轻量环境——免 root、零命令、一键安装。

> ⚠️ **这份是通用正文**（本版没有专属正文时才会用到）。发版前请写一份
> `.github/release-notes/<tag>.md`（例如 `.github/release-notes/v2.0.6.md`），
> CI 会优先用它；找不到才退回本文件 —— 见 `docs/ERRATA.md` E-068。

### 支持情况

- ✅ 完整支持：OpenCode（App 内置版，开箱即用）
- ✅ 走官方脚本：Claude Code
- ⚠️ 受限支持：Hermes（安装时要联网取它自己 pin 的 Python 工具链；依赖环境损坏可在首页一键修复）

### 系统要求

- Android 16（API 36）及以上
- arm64-v8a 架构

### 下载

在下方 Assets 区域下载 `zhengdao-<版本>-release.apk`。

> 带 `-release` 的是正式包（R8 混淆 + 资源收缩）——请认准这个后缀。
> 带 `-debug` 的是调试包，体积大一倍且可被调试，仅供开发者使用。
