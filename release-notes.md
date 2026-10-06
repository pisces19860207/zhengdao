# v1.1.1 — 洞天体验打磨 + 清债（2026-10-07）

v1.1 的收尾版：把洞天（终端）从"能用"做到"好用"，并把 v1.1 留下的尾巴清干净。
（v1.1 本体：太极 Tab 会话完整化 / 消息视觉层级 / 输入框体验 / Markdown 渲染，四阶段真机验收通过。）

## ✅ 洞天（终端）

- 分屏 / 收屏改**即时键绑定**（`C-b "` / `C-b x`），不再走有竞态的命令提示符路径
- 首次进终端弹一次**三个圆点的功能说明**（红=关页面 / 绿=分屏 / 黄=收屏）；
  之后**长按任意圆点**可复查；未启用 tmux 时的提示改成一句人话
- 文字选择「更多」菜单新增**清屏**（Ctrl-L，不污染输入）与**重载字号**
- 终端启动横幅新增「两个 OpenCode」说明（仅当你自己在终端装过 opencode 时显示）：
  太极内置版与终端自装版互相隔离、互不影响

## ✅ 修两个"点了没反应"

- 设置页「在终端中清理包缓存」：去掉会话存活门槛，**点击即跳转**终端并执行
- 「环境就绪」卡 / Agent「⋮」/ 太极组件：圆角容器上的**涟漪全部裁成圆角**，不再糊出方印

## ✅ 插件收窄

- 插件管理页**只作用于「太极」的 OpenCode 实例**——终端里自装的 opencode 不归它管，
  页面文案同步说明（详见《故障排查手册》坑 #3）

## 🧹 清债（纯删除）

- 删除旧 WebView 路径：`ui/TaijiScreen.kt`、`oc/LocalProxy.kt`、`TaijiPrefs`、
  `useNativeUi` 回退开关、缓存清理里的 opencode-mem 死路径
- 删除后太极行为不变（Compose 客户端直连 serve，真机「发一句话」复验通过）

---

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
