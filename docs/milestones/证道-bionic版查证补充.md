# bionic 版 OpenCode 查证补充 —— 对 Compose 方案的影响

> **补充时间**：2026-10-06（前一轮方案的疏漏补正）
> **疏漏内容**：前一轮（技术选型裁定 + Compose 设计方案）**只查了 OpenCode 的官方 API 文档与 GitHub issue，没有查 bionic 版本身**。bionic 版是太极 Tab 的实际运行载体，其来源、构建方式、能力边界都可能影响方案。
> **本轮查证结论**：✅ **Compose 方案仍然成立**，但有 **1 个必须补的动作** 与 **1 个值得利用的发现**。

---

## 0. 一句话结论

**bionic 版来自社区打包（`Hope2333/opencode-termux`），不是 OpenCode 官方产物；它把 TUI 渲染库 `libopentui.so` 一起打进包里，而证道当前的释放逻辑很可能只取了 `bin/opencode` 一个文件——这正好解释了 TUI 为何启动即退出。** Compose 方案不受影响，但释放逻辑需要补一处校验。

---

## 1. bionic 版是什么（查证结果）

### 1.1 来源：**社区打包，非官方**

`OcManager.kt:38-39` 确认：
```kotlin
private const val REPO = "Hope2333/opencode-termux"
private const val PKG_NAME = "opencode-$VERSION-1-aarch64.pkg.tar.xz"
```

对应 GitHub 仓库 **`Hope2333/opencode-termux`**，其自述："OpenCode on Termux. AI-powered terminal coding assistant, native bionic runtime, **zero glibc dependencies**"，并明确列出**两代并行**：

| 世代 | 运行时 | 体积 | 状态 |
|---|---|---|---|
| **v2（`opencode`，2.0.x）** | 原生 bionic | ~66MB | **主线**，与项目 `VERSION = "2.0.22"` 对应 |
| v1（`opencode1`，1.18.30–32） | 原生 bionic | ~40MB | 维护中，与 v2 可共存 |

> ⚠️ **重要含义**：`opencode serve` 的 API 行为、`/event` 事件字段，都属于 **OpenCode 上游 v2.0.x 的约定**，而这个包装版做了 **"binary surgery"**（把宿主编译的 module graph 移植到 Android 原生 Bun runner 上）。**API 层未被改动的概率很高**（module graph 是上游代码），但**字段仍须按实际打包版本核实**——不能照抄官方主站文档。

### 1.2 构建方式：**binary surgery**（值得知道的机制）

社区没有用 `bun build --compile`（Bun 官方**明确不支持 Android 交叉编译**，标为 "not planned"），而是：

1. 在宿主机上`bun build --compile` 生成宿主原生二进制
2. 从中**提取 module graph**（Bun standalone 二进制的尾部结构：`\n---- Bun!----` 哨兵 + 8 字节总长度）
3. 把 module graph **移植**到为 Android/aarch64 **从源码交叉编译**的 Bun runner 上

**为此需要交叉编译的东西**（这解释了为什么 TUI 在 Android 上是难题）：
- **Bun 运行时本体**（含 WebKit/JavaScriptCore，需打 `android-support.patch`）
- **ICU 75.1**
- **TinyCC**（`libtcc.a`，供 Bun FFI 用）
- **`libopentui.so`**（TUI 渲染库，需 `opentui/android-libc-link.patch` 才能 dlopen）

### 1.3 ⚠️ 关键发现：包内**含 `.so`**，且不止一个

社区的 zip 安装说明明确列出：
```
mv libtagfix.so libc++_shared.so libopentui.so $PREFIX/lib/
```

**三个 `.so`**：`libtagfix.so`、`libc++_shared.so`、`libopentui.so`。

而上游 issue #12515 里的社区解法也印证了这点——那位 Termux 用户正是**给 `$bunfs` 加 dlopen 拦截**才让 OpenCode 跑起来的，说明 bionic 版内部**确实在做 `dlopen`**（对 `libopentui.so` 的 dlopen）。

---

## 2. 🔴 这解释了 TUI 为何启动即退出——并给出一个必做动作

### 2.1 根因链条

```
TUI 模式启动
  → OpenCode 调 bun:ffi dlopen(".../libopentui.so")
  → 该路径位于 $bunfs 虚拟文件系统（模块被打进二进制，没有真实文件）
  → Android linker 无法 dlopen 虚拟路径
  → 启动即退出
```

**这与你观察到的现象完全吻合**，且**与 serve 模式无关**（serve 不 dlopen TUI 渲染库）——和你的判断一致。

### 2.2 ⚠️ 但还有一个**更靠前的可能**：`.so` 可能根本没被释放

**代码实况**（`OcManager.kt` 释放逻辑，约 218-234 行）：

```kotlin
var entry: TarArchiveEntry? = tar.nextTarEntry
while (entry != null) {
    val name = entry.name
    // ...
    if (rel.startsWith(".")) { entry = tar.nextTarEntry; continue }   // 跳过
    target.parentFile?.mkdirs()
    target.outputStream().use { out -> tar.copyTo(out) }
    entry = tar.nextTarEntry
}
```

而 `installed()` 的判据是：
```kotlin
binaryFile(ctx).let { it.isFile && it.length() > 100L * 1024 * 1024 }   // > 100MB
```

**审查发现**：代码里**搜不到任何对 `.so` 的显式处理**（无 `System.loadLibrary`、无按 `lib/` 路径释放的分支、无 `LD_LIBRARY_PATH` 设置）。这意味着：

| 情形 | 后果 |
|---|---|
| 若释放逻辑**只取 `bin/opencode`** | `.so` 从未被落盘 → TUI 的 dlopen **必然失败**（缺文件，不是虚拟路径问题） |
| 若释放逻辑**遍历了全部 entry** | `.so` 已落盘，但 dlopen 仍会因 `$bunfs` 虚拟路径而失败 |

> 🔺 **两种情形都导致 TUI 不可用，但修法完全不同**：前者要补释放逻辑（把 `.so` 放对位置并设 `LD_LIBRARY_PATH`），后者只能靠社区的 dlopen 拦截或干脆走 serve。
>
> **这也解释了为什么 serve 模式正常**——serve 根本不碰 TUI 渲染库，所以两种情形都不影响它。

### 2.3 ✅ 必做动作（新增到阶段 0）

**在阶段 0 加一条 0-5：核实 `.so` 的实际落盘情况。**

```bash
# 在真机或模拟器上，App 数据目录内检查：
ls -la <filesDir>/oc/usr/bin/opencode
ls -la <filesDir>/oc/usr/lib/          # ← .so 在不在这里？
# 以及 App 启动时的环境（serve 进程）：
echo $LD_LIBRARY_PATH
```

**判定与对策**：

| 检查结果 | 结论 | 处置 |
|---|---|---|
| `.so` **没落盘** | TUI 失败的第一原因是缺文件，**不是 bunfs** | 补释放逻辑：把 `.so` 释放到 `filesDir/oc/usr/lib/`，并在 `ProcessBuilder` 环境里设 `LD_LIBRARY_PATH=/data/data/证道/files/oc/usr/lib`。**这可能让 TUI 直接可用**，是零成本的意外收益 |
| `.so` **已落盘但 TUI 仍退出** | 确认是 `$bunfs` 虚拟路径 dlopen 失败 | 走 serve + Compose 方案（**本方案的主线**）；或评估社区的 dlopen 拦截（要打补丁进 App，成本高，**不建议**） |

> 📌 **这条检查的价值**：如果落到第一种情况，你能**顺带把 TUI 救活**——那对 zcode 的调试体验是个大利好（用户说"zcode 不理解"说明调试路径在 TUI 上）。但**主方案仍是 Compose + serve**，因为它同时解决了 IME 和内存问题。

---

## 3. Compose 方案是否受影响：逐项复核

| 方案要素 | 是否受影响 | 说明 |
|---|---|---|
| 直连 `127.0.0.1:14000` HTTP + SSE | ❌ 不受影响 | serve 不 dlopen TUI 渲染库，API 层是上游 module graph（未改动） |
| OkHttp + `okhttp-sse` | ❌ 不受影响 | 与bionic 无关 |
| `OcManager.startServe()` 复用 | ❌ 不受影响 | 已验证可跑（用户实测 14000 端口可用） |
| XDG 独立四目录 | ❌ 不受影响 | 已有 |
| **权限批准 / todo / diff 等端点** | ⚠️ **须核实** | v2.0.22 是否完整暴露这些端点，取决于上游 API 面 + 包装版是否裁剪。**阶段 0 必须逐个探** |
| **SSE 事件字段名** | ⚠️ **须核实** | 官方文档横跨 v1/v2/v3，且 v2 命名空间下事件名不同（`EventSessionCreated` 等）。**以实际打包版本的 `openapi.json` 为准** |
| 依赖 `libopentui.so` | ✅ **完全无关** | Compose 方案**不用 TUI 渲染库**——这正是它的价值所在 |

**结论**：✅ **方案成立**。所有与 `.so`/TUI 相关的问题都被绕过了。需要核实的只有 **API 覆盖面与事件字段名**，而这本来就该在阶段 0 做。

---

## 4. 修正后阶段 0 清单（原 4 项 → 5 项）

| # | 任务 | 通过标准 | 状态 |
|---|---|---|---|
| 0-1 | OkHttp + AuthInterceptor 直连 `127.0.0.1:14000` | `GET /global/health` 200 | 🔴 **不过则方案重设计** |
| 0-2 | 建立 SSE `/event` 连接 | 收到 `server.connected` 及后续事件 | 必须 |
| 0-3 | **对比测量**（OkHttp 直连 vs LocalProxy） | 20 请求总耗时 + SSE 首事件时间 | 量化收益 |
| 0-4 | **核实 API 覆盖面与事件字段名** | 按**实际打包版本**（`VERSION = "2.0.22"`）的 spec 逐字段核对；**并逐个探测 `/session`、`/session/{id}/todo`、`/session/{id}/permissions/*`、`/session/{id}/diff` 是否可用** | **本轮新增强调** |
| 0-5 | **核实 `.so` 落盘情况**（§2.3） | 判断 TUI 失败是"缺文件"还是"虚拟路径"；前者可顺带救活 TUI | **本轮新增** |

> ⚠️ **0-4 的理由被本轮查证强化了**：bionic 版是**社区用 binary surgery 移植的**，其 API 暴露面**理论上与上游一致但不保证**——尤其 `/session/{id}/permissions/*` 这类较新的端点。**不要假定"serve 能跑 = 所有端点都在"。**

---

## 5. 顺带修正：前一轮方案里的一处不准确表述

前一轮 Compose 设计方案的 §9 写「`OcManager.kt` **保留并复用**」，这个结论仍成立，但需要补充一条认知：

> **它复用的是 serve 的生命周期管理（拉起/判活/密码/XDG），而对 TUI 渲染链路的支撑能力此前未被核实。**
> 现在明确了：TUI 失败在 `$bunfs` dlopen（+ 可能的 `.so` 未落盘），**这是 OpenCode 自身在 Android 上的限制，不是证道的实现缺陷**。因此"TUI 走不通"不该被当成"证道哪里做错了"——这也能缓解 zcode 那边的困惑。

---

## 6. 一句话总结

**bionic 版是社区用 binary surgery 移植的，包内含三个 `.so`；TUI 失败大概率是 `$bunfs` dlopen（可能叠加 `.so` 未落盘）。Compose + serve 方案完全绕过这个问题，因此成立。** 但阶段 0 要加两条：**核实 `.so` 落盘情况**（可能顺带救活 TUI，对 zcode 调试有利）与**逐个探测 API 覆盖面**（社区包装版不保证端点齐全）。

---

*本补充查证基于 `Hope2333/opencode-termux` 仓库说明、opencode-termux 构建管线文档、opencode#12515（bunfs dlopen 问题）、以及 `OcManager.kt` / `TaijiScreen.kt` 代码实况（核验日 2026-10-06）。未修改任何代码。*