# M3 一键安装 Kotlin 骨架（本里程碑工程约束与验收标准；实现以代码为准，约束与红线以本文档为准）

> 📌 **存储结论已反转（E-005，2026-10-06）**：App 真身下 `/sdcard` 读写均可用，SAF 镜像同步降级为**备用方案**。本文件不含存储方案约定；存储权威说明见 `docs/ERRATA.md` 与 `已知限制.md`。
> 📌 **执行总览**：M1-M5 与 P1-P8 的整合路线图见 `证道-执行路线图.md`（本文档为功能骨架，整体顺序与优先级以路线图为准）。
>
> 项目：证道 · 依据：v3 文档 §6（manifest/验签）、§7（命令抽屉/agent-helper）、§8（快照重置）
> M3 = manifest（ed25519 验签）+ Agent 卡片 + 一键安装 + 快照重置

---

## 1. ManifestClient（拉取 + ed25519 验签 + 解析）

```kotlin
// 安全闸：manifest 被篡改 = 用户环境 RCE。验签不过 -> 整份拒绝应用、回退出厂版。
class ManifestClient(
    private val publicKey: Ed25519PublicKey,   // 固化在 APK 里的公钥（构建期断言非占位符）
    private val okHttp: OkHttpClient,
) {
    // 双通道拉取（GitHub + CDN 各存一份 manifest + .sig），谁快用谁
    suspend fun fetch(): Result<Manifest> {
        // 1. 并发请求两个通道的 manifest.json 与 manifest.json.sig
        // 2. 取先到且验签通过的一份（通道本身不可信，签名才是信任根）
        // 3. 验签失败 -> 丢弃该通道结果，等另一通道；双通道都失败 -> 回退出厂版
        // 4. 校验通过 -> 解析为 Manifest（见 v3 §6 schema v5）
    }

    fun verifySignature(body: ByteArray, sig: ByteArray): Boolean {
        // Ed25519.verify(publicKey, body, sig)
        // 注意：验签对象是「原始字节」，不是解析后的 JSON——先验签、后解析
    }
}
```

## 2. AgentRepository（卡片数据 + 安装状态）

```kotlin
data class AgentInfo(
    val id: String,              // "claude-code"
    val name: String,            // "Claude Code"
    val desc: String,
    val install: String,         // 官方安装命令（manifest 提供）
    val upgrade: String,
    val uninstall: String,
    val launch: String,          // 启动命令
    val launchFallback: String?, // 快捷命令兜底（如 python3 -m hermes_cli.main --tui）
    val check: String,           // "command -v claude"
    val testedOn: List<String>,  // 必须覆盖 debian-13.7
)

enum class InstallState { NotInstalled, Installing, Installed, Launchable, Error }

// v3.9 补：卡片需显示已装版本 + 更新标记，故状态里必须带版本号
data class InstalledInfo(
    val state: InstallState,
    val installedVersion: String?,  // 已安装版本（null = 未安装）；由 agent-helper 登记写入
    val installPath: String?,       // 真实安装路径（agent-helper 登记，避免全局 find）
    val latestVersion: String?,     // manifest 侧最新可用版本（可为空：非全部 Agent 都暴露版本号）
    val hasUpdate: Boolean = false, // installedVersion != latestVersion 时置 true -> 卡片显示「可更新」
)

class AgentRepository(private val manifestClient: ManifestClient) {
    // DataStore 持久化：每个 agent 的 InstalledInfo（state + 版本号 + 安装路径，agent-helper 登记）
    suspend fun listAgents(): List<AgentInfo> = manifestClient.fetch().getOrNull()?.agents ?: emptyList()

    suspend fun stateOf(agentId: String): InstalledInfo { /* 读本地状态文件 */ }

    // 「添加自定义 Agent」：填名字 + 启动命令，command -v 存在才允许保存（v3 §7）
    suspend fun registerCustom(name: String, launch: String): Boolean
}
```

## 3. AgentInstaller（一键安装执行器）

```kotlin
class AgentInstaller(
    private val sessionManager: SessionManager,   // M2 骨架里的 tmux 会话管理
    private val helperPath: String,                // rootfs 内 /usr/local/bin/agent
) {
    // 安装统一在 tmux 会话里执行，输出实时渲染到终端（进度条 + 滚动日志）
    fun install(agent: AgentInfo, terminal: TerminalView): Flow<InstallProgress> = flow {
        // 1. 确保 rootfs 已就绪、DNS 已落盘、会话已建立
        // 2. 构造安装命令（agent-helper 规范，附录 A）：
        //    不直接 curl|bash：先 curl --fail --retry 3 -C - 下载到临时文件再执行
        //    Python 包走 uv pip install --system（PEP 668 / RECORD 卸载错双免）
        //    安装后执行 check；失败 -> 按 launchFallback 生成 /usr/local/bin/<id> 包装脚本
        //    记录真实安装路径到状态文件（不用全局 find）
//    【v3.8】Hermes 保持官方安装方式（install/upgrade 即 manifest 里的官方
//    curl | bash），不注入任何版本环境变量；运行时稳定性由 proot 复现
//    Termux v0.119.0-beta.3 的体验保证（§4），与 Agent 安装方式无关。
        // 3. 进度事件：开始 -> 下载中 -> 执行中(日志流) -> 校验 -> 完成/失败(可重试)
    }

    fun upgrade(agent: AgentInfo) { /* 同 install，命令换 upgrade */ }
    fun uninstall(agent: AgentInfo) { /* 执行 uninstall + 清状态 */ }
}

sealed class InstallProgress {
    data class Downloading(val percent: Int) : InstallProgress()
    data class Executing(val line: String) : InstallProgress()   // 日志流
    data class Verifying(val cmd: String) : InstallProgress()
    data object Done : InstallProgress()
    data class Failed(val message: String, val log: String) : InstallProgress()  // 日志可复制反馈
}
```

## 4. AgentCard UI（Compose）

```kotlin
@Composable
fun AgentCard(agent: AgentInfo, info: InstalledInfo, onInstall: () -> Unit, onLaunch: () -> Unit) {
    // 状态驱动（取 info.state；版本号与更新标记取 info.installedVersion / info.hasUpdate）：
    // NotInstalled -> 大按钮「安装」
    // Installing   -> 进度条 + 滚动日志（可收起）
    // Installed    -> 「启动」+ 更多菜单（升级/卸载/重新安装）+ 已装版本行
    //              -> info.hasUpdate == true 时额外显示「更新」按钮
    // Launchable   -> 「启动」（已手动装好，command -v 通过）
    // Error        -> 「重试」+ 日志复制按钮（永远不让用户面对裸报错）
    //
    // 为什么传 InstalledInfo 而不是 InstallState：卡片要显示「已装版本 + 更新标记」，
    // 这两个值不在 InstallState 里。InstallState 仍是 InstalledInfo 的 state 字段，
    // 一一对应，未引入第二套状态定义。
}
```

## 5. 快照重置（home 分离 + 重解压 base）

```kotlin
class EnvironmentRepair(
    private val extractor: TarZstExtractor,     // M1.1 骨架
    private val pristineBase: File,              // 本地缓存的 tar.zst（不重下）
    private val systemDir: File,                 // rootfs 系统层
    private val homeDir: File,                   // /root 独立目录（§8，不动）
) {
    // 用户点「修复环境」：
    // 1. 停止所有 tmux 会话（kill-session，不动 homeDir）
    // 2. 重解压 pristineBase 到 systemDir（原子性：tmp + 标记 + rename）
    // 3. 询问「是否顺带重装已选的 Agent」（默认是）-> 按 manifest 重跑 install
    // 4. 全程 30 秒 + 重装时间，不重下 RootFS、不丢登录态
    suspend fun repair(reinstallAgents: Boolean = true): Result<Unit>
}
```

## 验收要点（对应 v3 §9 Checklist）

- [ ] **验签演练**：篡改 manifest 内容 / 换签名两种场景 -> App 必须拒绝应用并回退出厂版
- [ ] 一键安装 Claude Code / Hermes 成功，进度 + 日志正常，失败可重试、日志可复制
- [ ] `agent install` 与手动 `curl|bash` 两条路装出的 Agent 都能登记为一键启动卡片
- [ ] 修复环境后 Agent 登录态与配置完好（home 分离生效）
- [ ] 新 Agent 上架 manifest 前实测附录 A #4–#8，且 `tested_on` 覆盖 debian-13.7（CI 强制）
- [ ] API Key（Keystore 加密）在重装/重置后仍在（§7 双保险）

## 红线（v3 实测结论）

- **PROOT_NO_SECCOMP=1 禁止设置**（本机实测反而致命）
- proot 必须用 Termux fork（GPL，聚合分发登记 PROVENANCE）
- **manifest 先验签后解析**；公钥固化 APK 并加构建期断言（非占位符）