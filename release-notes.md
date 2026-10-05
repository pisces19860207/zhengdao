# v0.9.1 — 完整支持 OpenCode + Hermes

证道第一个完整跑通两个主流 Agent 的版本（荣耀 Magic 5 Pro · Android 16 真机验收）。

## ✅ Hermes Agent 完整支持

- 一键安装 → 启动 → TUI 全链路真机通过；`hermes --version` / `hermes update` 可用
- 攻克老大难的 `failed to hardlink file`：hermes 安装器**不用系统 PATH 上的 uv**、
  全局 `UV_NO_CONFIG=1` 忽略一切 uv 配置、pm 还会剥离 `UV_*` 环境变量——
  证道改为**包装它自装的 pinned uv 二进制**（真身挪为 `uv.real`，exec 前强制
  `UV_LINK_MODE=copy`），环境变量剥不到二进制内部
- `hermes` 命令在**新旧所有终端窗口**可用（`/usr/local/bin` 软链 + profile.d PATH）
- `hermes update` 韧性：日后拉到新版 pinned uv，App 下次启动自动接管包装
- 设置页保存的 API Key 经环境变量注入，hermes 启动即识别（真机实测 deepseek 直连）

## ✅ OpenCode 完整支持

- `--allow-scripts` 修复 npm 11 拦截 postinstall 导致的二进制缺位
- 国内镜像加速（安装 4 分钟 → 约 45 秒）

## 其他

- 终端时钟与手机同步（guest 时区校准 Asia/Shanghai + TZ 环境变量）
- 启动横幅回归（文件投递方案：提示符上方两行纯提示，无命令回显）
- 文字选择「更多」菜单：浏览器搜索选中文字 / 重置终端
- Claude Code 与 AGY CLI（Antigravity）走各自官方安装脚本

## 已知限制

- Hermes uv 若在未来版本升 pin，那一次 `hermes update` 可能仍报硬链接错误，
  重启证道后再执行一次即可（启动巡检会接管新目录）
- 不执行 `apt upgrade`（系统层只读策略，依赖请走 pip / npm）
