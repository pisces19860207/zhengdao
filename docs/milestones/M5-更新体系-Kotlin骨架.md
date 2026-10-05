# M5 更新体系 Kotlin 骨架（本里程碑工程约束与验收标准；实现以代码为准，约束与红线以本文档为准）

> 项目：证道 · 依据：v3 文档 §6 分发与更新（双通道、OTA 热更新 + 自动回滚、Golden Image、版本护栏）、§9 Checklist、§11 里程碑
> M5 = App 自更新 + 组件热更新 + RootFS 构建管线（CI）+ ed25519 验签 + 版本护栏
> 核心安全闸：manifest 与所有热更新组件必须 ed25519 验签通过才应用，否则回退出厂版（Golden Image）。

---

## 1. AppUpdater（APK 自更新）

```kotlin
class AppUpdater(
    private val okHttp: OkHttpClient,
    private val downloader: ResumableDownloader,  // M1.1 骨架：断点续传 + SHA256
) {
    // 启动时调用 GET /repos/<org>/<repo>/releases/latest，比对 versionCode
    suspend fun checkForUpdate(): UpdateInfo? {
        // 解析 release 的 tag_name 与 assets；找出 arm64 APK 资产
        // versionCode > 当前 BuildConfig.VERSION_CODE -> 返回 UpdateInfo
    }

    suspend fun downloadAndInstall(apk: UpdateInfo) {
        // 后台下载 APK（断点续传 + SHA256 校验）
        // 弹「发现新版本，点我更新」-> 调起系统安装器
        // 需要 REQUEST_INSTALL_PACKAGES 权限（直发渠道无政策障碍）
    }
}
```

## 2. AgentHelperUpdater（agent-helper 热更新 + Golden Image 回退）

> **v3.9 简化说明**：终端渲染层切换为 Termux TerminalView（原生代码）后，`xterm-bundle` 已取消，**热更新组件只剩 `agent-helper` 一个**。原先为"多组件"设计的 `ComponentUpdater` 泛化层随之去掉，简化为只服务 `agent-helper` 的 `AgentHelperUpdater`——少一层抽象，实现更直白。
> **恢复预案**：将来若 manifest 新增第二个可热更新组件，把本类拆回通用 `ComponentUpdater` 即可，改动量很小（单组件取值换成 `components[...]` 遍历）。

```kotlin
// manifest 中 components["agent-helper"] 的解析结果（单组件，无需 id 字段）
data class AgentHelperComponent(
    val version: String,
    val minAppVersion: Int,      // 版本护栏：不兼容则跳过更新，继续用当前版本
    val urlGithub: String,
    val urlCdn: String,
    val sha256: String,
)

class AgentHelperUpdater(
    private val manifestClient: ManifestClient,   // M3 骨架：双通道拉取 + ed25519 验签
    private val downloader: ResumableDownloader,
    private val bundledDir: File,               // APK 内置出厂版本（Golden Image）
    private val activeDir: File,                // 当前生效版本
) {
    suspend fun update() {
        val manifest = manifestClient.fetch().getOrNull() ?: run {
            // 验签失败 -> 整份拒绝应用，回退出厂版（安全闸）
            rollbackToGoldenImage(); return
        }
        val comp = manifest.components["agent-helper"] ?: return   // manifest 没带就不动

        // 版本护栏：新组件不支持当前 APK -> 跳过，继续用当前版本
        if (comp.minAppVersion > BuildConfig.VERSION_CODE) return

        // 已是最新 -> 不动
        if (readLocalVersion() == comp.version) return

        // 下载 + 校验；失败或校验不过 -> 回退出厂版
        downloadAndVerify(comp)?.let { apply(it) } ?: rollbackToGoldenImage()
    }

    private fun rollbackToGoldenImage() {
        // 删除 activeDir 里的下载物，恢复 APK bundledDir 出厂版本
        // 保证 agent 命令永远可用；终端渲染为原生 TerminalView，随 APK 发版，不在此回退范围
    }
}
```

## 3. ManifestClient（复用 M3，双通道 + 验签）

> 直接复用 M3 的 `ManifestClient`：并发请求 GitHub + CDN，先到且验签通过者胜；双通道都失败回退出厂版。验签对象是原始字节，先验签后解析。

```kotlin
// 见 M3-一键安装-Kotlin骨架.md §1，此处不重复定义。
```

## 4. RootFSBuildPipeline（CI 构建管线 + 版本断言）

```kotlin
// 不在 App 内运行，是 CI 脚本（GitHub Actions）的骨架。
// 构建顺序（v3 §6）：
// apt-get update
//   -> 基础包与依赖（git curl ca-certificates gnupg python3 ripgrep libatomic1
//      ffmpeg tmux procps busybox sqlite3 uv ...）
//   -> NodeSource 官方源安装 nodejs 26（curl -fsSL setup_26.x | bash - 后 apt-get install -y nodejs）
//   -> 清理 apt 缓存 -> 裁掉 /usr/share/doc 与多余 locale
//   -> mkdir -p /var/log/apt /var/log/dpkg
//   -> 写 /etc/profile.d/uv-link-mode.sh：export UV_LINK_MODE=copy
//   -> tar.zst 打包（zstd：解压快数倍、发热更小）
// 版本断言（构建即验收，漂移即失败）：
//   ldd --version          -> glibc 2.41.x（只读不自升）
//   python3 --version      -> 3.13.x（系统自带）
//   node --version         -> v26.x（NodeSource）
// 产物：debian-13.7-base-arm64.tar.zst + manifest.json（含 SHA256、大小、双通道 URL、ed25519 签名）
```

## 5. 安全闸：离线签发 + 公钥固化（v3.4）

```kotlin
// 私钥管理（离线签发，红线）：
//   1. ed25519 私钥在完全离线的可信机器上生成，0600 权限本地保存
//   2. 仅用于手动签署发布 manifest——不入仓库、不进 APK 源码、不进 CI
//   3. CI 只做构建与校验，不做签名
// APK 侧：
//   公钥固化在 APK 源码，构建期加断言（publicKey 非占位符）
//   验签不过 -> 整份拒绝应用，回退出厂版
// 密钥轮换预案：
//   新公钥随新版 APK 下发，旧私钥作废；用户升级 APK 后完成信任迁移
```

**离线签发实操脚本（v3.9 补）** —— `scripts/sign-manifest.sh`，**只在离线机器执行**：

```bash
#!/bin/bash
# 离线签发 manifest —— 私钥永不入仓库、永不进 CI
# 前置（仅首次）：openssl genpkey -algorithm ed25519 -out ~/.zhengdao/private.key
#                 chmod 600 ~/.zhengdao/private.key
set -euo pipefail
KEY="${HOME}/.zhengdao/private.key"
MANIFEST="${1:?用法: ./sign-manifest.sh manifest.json}"

# ed25519 签名（openssl 原生支持，-rawin 表示直接签原始字节而非先做 digest）
openssl pkeyutl -sign -rawin -inkey "$KEY" -in "$MANIFEST" -out "${MANIFEST}.sig"

# 自检：用公钥验一遍，确保签出来的东西 APK 侧能验过
openssl pkeyutl -verify -rawin -pubin -inkey "$HOME/.zhengdao/public.key" \
  -in "$MANIFEST" -sigfile "${MANIFEST}.sig"

echo "已生成 ${MANIFEST}.sig —— 把 manifest.json 与 .sig 一并上传 Release"
```

> 注意：`-rawin` 是 ed25519 必需的（ed25519 自身完成摘要，openssl 默认会再做一次 digest 导致验签失败）。公钥 `public.key` 从私钥导出后固化进 APK 源码并加构建期断言。

## 6. 网络就绪（zcode 实现前必读）

> 用户环境：依赖代理 App（Clash/v2rayNG）的 VpnService 系统 VPN。证道**不需要内置代理**，但要确保流量进入 VPN 隧道。

```kotlin
// 【红线】App 网络栈保持系统默认路由：
// - OkHttp / 下载器：不设 Proxy.NO_PROXY，不自定义 ProxySelector 强制直连
// - 默认路由 -> 流量自动进入用户代理 App 的 VpnService TUN -> GitHub/npm/claude.ai 全部可达
// - 一旦设了 NO_PROXY -> 全部海外源连不上 = "证道不走科学上网"
```

**软件源策略（构建期定案）**：rootfs `sources.list` 用 **Debian 官方源**，npm 用官方 registry（npmjs.org），pip 用官方 PyPI。**不预置国内镜像源**（镜像滞后破坏可复现性、开代理访问国内源反而绕路）。

**更新失败引导**：`AppUpdater.checkForUpdate()` / `AgentHelperUpdater.update()`（原 `ComponentUpdater`）/ manifest 拉取失败时，**必须接入 v3 §6 连接失败智能引导**（弹图文：确认代理已开 VPN 模式 → 检查分应用代理是否勾选证道 → 手动代理设置），不允许静默失败。

## 验收要点（对应 v3 §9）

- [ ] APK 自更新：release 新版本 -> 启动检测到 -> 下载校验 -> 调起系统安装器安装
- [ ] 组件热更新：新 agent-helper 静默下载替换，agent 命令仍可用（终端渲染为原生 TerminalView，不设热更新通道，渲染修复随 APK 发版）
- [ ] 验签演练：篡改 manifest / 换签名 / 篡改组件包，必须拒绝应用并回退出厂版
- [ ] Golden Image：出厂 agent-helper 离线可用；下载失败/校验不过自动回退，agent 命令打得开
- [ ] 版本护栏：`min_app_version` 高于当前 APK 的组件被跳过，不崩溃
- [ ] 断网 / 弱网：下载中断续传，双通道自动切换
- [ ] CI：人为制造 Node/Python/glibc 版本漂移，构建必须失败
- [ ] RootFS 构建产物在真机可解压、proot 可启动、bash 交互式存活

## 红线（v3 实测结论）

- **manifest 先验签后解析**；公钥固化 APK 并加构建期断言（非占位符）
- 私钥离线签发、不入 CI；**CI 永不签名**
- 组件版本必须声明 `min_app_version`，防止新组件下发到老 APK 崩溃
- 大文件（rootfs）只走 GitHub Releases + Cloudflare R2/自定义域名；jsDelivr 仅用于小文件（不代理 Releases 资产、50MB 上限）