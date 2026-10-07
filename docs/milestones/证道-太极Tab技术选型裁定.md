# 太极 Tab 技术选型裁定：Compose 原生 UI 直连 OpenCode HTTP API

> **裁定时间**：2026-10-06
> **提议**：用 Compose 原生 UI 替代 WebView / TerminalView，直连 `opencode serve` 的 HTTP API，作为太极 Tab 长期方案
> **裁定结论**：✅ **可行且推荐**。但有 **1 个必须先解决的前置问题**（鉴权）与 **3 个必须提前决策的取舍**，否则会在实现中期卡住。
> **依据**：仓库代码实况（`OcManager.kt` / `TaijiScreen.kt` / `build.gradle.kts`）+ OpenCode 官方 API 文档与社区 issue（核验日 2026-10-06）

> ⚠️ **2026-10-07 勘误（本文件写于 10-06，此后代码已演进）**：本文多处引用的 **`LocalProxy.kt` 已被删除**（WebView 旧路径随 v1.1.1 阶段 3 下线，commit `e9997ec`）；**§2 的端点表整体不成立**，真实契约是带 `/api` 前缀的那一套（见 §2 勘误）；**§6.1 要求的 `network_security_config.xml` 实际不需要**（`usesCleartextTraffic="true"` 已放行）。裁定**结论本身不变**（Compose 直连 → 已实现），但下文引用的端点名、文件名、行号请以勘误块与 `docs/milestones/证道-太极Tab-设计API对照表.md` 为准。

---

## 1. 结论先行

**这个方向是对的，而且比现有两条路都好。** 三点理由：

1. **它绕开的是真问题，不只是换个渲染层。** TUI 模式在bionic 版上启动即退出，根因是 OpenTUI 的原生库被 Bun 塞进虚拟文件系统（`$bunfs`），Android linker 无法 `dlopen` 虚拟路径。**这是上游已知问题**（opencode#12515、#8620），社区给出的解法是给 bunfs 做 dlopen 拦截——那是**给别人的运行时打补丁**，成本与不确定性都高。走 HTTP API 则根本不需要 TUI 渲染库。
2. **API 面完全够用，且是官方一等公民。** `opencode serve` 提供完整 REST + SSE，并有 **OpenAPI 3.1 spec** 与**官方生成的 JS SDK**（`@opencode-ai/sdk`，`createOpencodeClient`）。太极 Tab 需要的能力（会话列表、发消息、流式收、权限确认）**全部有端点**，不是私有接口 hack。
3. ~~**仓库里已经有一个可复用的鉴权桥。** `LocalProxy` 已经在跑……这个组件在 Compose 方案里**依然需要**（见 §3）。~~
   → **更正**：`LocalProxy` 已随 `e9997ec` 删除，Compose 方案最终**没有复用它**，而是按本文 §3 的「决策建议」走 OkHttp Interceptor 直连 14000 注入 Basic auth（已实现于 `oc/OcClient.kt`）。**这项理由作废，第 3 点应删。**

**关于你说的"替代 WebView / TerminalView"**：现状是 WebView 已在跑（`TaijiScreen.kt:101-149`），TerminalView 那条是bionic TUI 走不通才回退的。所以实际是 **WebView → Compose**，而 TerminalView 是被 OpenTUI 卡死的、不是被Compose 方案淘汰的。

---

## 2. 你要对接的 API 面（已核实）

来自 OpenCode 官方文档 + OpenAPI spec（核验日 2026-10-06）：

> ## ⛔ §2 勘误（2026-10-07）：下面这张表**整张都不对**，端点路径与动词均有偏差
>
> 下表来自**上游文档 / OpenAPI spec 的命名**，而项目实际打包的 OpenCode `2.0.22` 暴露的是**带 `/api` 前缀的另一套**。以 `oc/OcRepository.kt` 为准：
>
> | 能力 | **真实端点（以 `oc/OcRepository.kt` 为准）** | 备注 |
> |---|---|---|
> | 会话列表 | `GET /api/session` | |
> | 新建会话 | `POST /api/session` | |
> | 读消息 | `GET /api/session/{id}/message` | |
> | 发消息 | `POST /api/session/{id}/prompt` | **不是** `prompt_async` |
> | 中止 | `POST /api/session/{id}/interrupt` | **不是** `abort` |
> | 权限应答 | `POST /api/session/{id}/permission/{permissionId}/reply` | 另有 `GET /api/permission/request` |
> | 模型列表 | `GET /api/model?location[directory]=` | |
> | **事件流（SSE）** | `GET /api/event` | **不是** `/event`；无 `/global/health` |
>
> **完整、逐条核验过的端点表见** `docs/milestones/证道-太极Tab-设计API对照表.md:194-201`（§四），**以那张表为准，不要再引用本节下表**。
>
> 下表仅作「当时据以判断 API 面够用」的历史凭据保留。

| 能力 | 端点（**已过期，见上方勘误**） | 说明 |
|---|---|---|
| 健康检查 | `GET /global/health` | 带版本号 |
| 会话列表 | `GET /session` | |
| 新建会话 | `POST /session` | |
| 发消息（同步） | `POST /session/{id}/message` | 等完整回复 |
| 发消息（异步） | `POST /session/{id}/prompt_async` | **推荐**，立即返回 |
| 中止 | `POST /session/{id}/abort` | |
| 读消息 | `GET /session/{id}/message` | 初始拉取 |
| 读 todo | `GET /session/{id}/todo` | |
| 查改动 | `GET /session/{id}/diff` | 可做"改了哪些文件"面板 |
| **事件流（SSE）** | `GET /event`（全局）/ `GET /session/{id}/event`（单会话） | **实时更新主通道**，30s 心跳 |
| 权限批准 | `POST /session/{id}/permissions/{permissionID}` | body `{response: "allow", remember: true}` → **实际是** `{"decision":"once"|"always"|"reject"}` |
| 文件读/搜 | `GET /file/content`、`GET /find` | 拉上下文 |

**事件类型**（SSE 推来的）：`message.updated`、`message.part.updated`（**带 `delta` 增量，用于流式打字机效果**）、`session.updated`、`session.idle`、`permission.asked`、`todo.updated`、`lsp.updated`。

> 📌 **这比 TUI 的信息更丰富**：TUI 是"把终端画面画出来"，而 SSE 给的是**结构化事件**。做 Compose UI 反而能做出 TUI 给不了的东西——比如工具调用折叠、todo 清单内嵌、diff 高亮。这是这个方案被低估的收益。

---

## 3. 🔴 前置问题 #1：鉴权（必须先解决，否则第一版就卡死）

**现状**：`OcManager.kt` 已确认 serve 走 **HTTP Basic 鉴权**，密码从 `serve.log` 解析（`OcManager.kt:76-79`）。~~现有 `LocalProxy` 的存在理由（`LocalProxy.kt:18-24`）写得很清楚：~~（**`LocalProxy.kt` 已删除**，下列引文仅作历史记录）

> Android WebView 的 fetch/XHR 收到 401 **不会**触发 `onReceivedHttpAuthRequest`（真机实测），SPA 拿不到凭据就卡死在启动页。

**关键判断**：**这个坑 Compose 方案同样会踩，而且踩得更狠。**

- WebView 侧至少有 `onReceivedHttpAuthRequest` 这个（实测不触发的）回调可用；
- **Compose 方案是纯 OkHttp 调用，没有任何浏览器回调兜底**——密码必须由 App 主动加到每个请求的`Authorization` 头上。

**好消息**：~~`LocalProxy` **就是为此而写**，且注释明确写了"透传是字节级的：SSE、chunked、POST body 全部原样通过"。**所以 Compose 方案直接复用 `LocalProxy` 即可**，不需要自己写鉴权注入。~~
→ **更正（2026-10-07）**：`LocalProxy` 已随 `e9997ec` 删除，**没有被复用**。实际做法就是 §3 下面那条「决策建议」——OkHttp `Interceptor` 注入 Basic auth 直连 14000，**没有本地代理**。

**但有个必须先验证的点**：

> ⚠️ **`LocalProxy` 当前的实现是为 WebView 优化的……**（**`LocalProxy.kt` 已删除，以下整段为历史分析与最终结论**）
> 现在的模式是"WebView 加载 `http://127.0.0.1:14001`，代理注入 auth 转发到 14000"——**由浏览器发起请求**。
> Compose 方案下，**App 自己就是 HTTP 客户端**，可以直接在 OkHttp 的 `Interceptor` 里加 `Authorization: Basic base64(user:password)`，**根本不需要本地代理**（少一跳、少一个线程、少一个失败点）。
>
> 决策建议：**Compose 方案走 OkHttp Interceptor 直连 14000 + 密码注入**，~~`LocalProxy` 保留给 WebView 旧路径做过渡（等 WebView 路径下线后再删）~~ → **已按此执行并完成**：WebView 路径随 v1.1.1 阶段 3 下线，`LocalProxy` 已删除（`e9997ec`）。**先验证 OkHttp 直连能否过 Basic 鉴权**——这是第 0 号任务（**已验证通过**）。

---

## 4. 🟡 必须提前决策的 3 个取舍

### 4.1 SSE 长连接在 Android 上的可靠性

SSE 是单向流，Android 上用 OkHttp 实现没有原生坑，但有 3 个必须处理的点：

| 问题 | 处理方式 |
|---|---|
| **App 切后台被 LMK 杀** | SSE 断了。恢复策略：`TaijiScreen` 已有 2 秒轮询 `serveRunning()` 的机制，**复用它**：SSE 断开 → 轮询兜底 → 重连后先 `GET /session/{id}/message` 补齐断线期间的消息 |
| **30s 心跳被中间设备掐断** | OkHttp 自带 `readTimeout(0)` + `retryOnConnectionFailure`，再加应用层重连退避（1s→2s→4s→8s 封顶） |
| **流式增量 vs 全量刷新** | `message.part.updated` 带 `delta`，**用 delta 增量追加**；重连时切回全量拉取。注意二者不要混，否则会重复追加文本 |

**决策点**：太极 Tab 在前台时才需要 SSE；切后台是否要维持连接？建议**维持但降级为长轮询**——Agent 跑着的时候用户切回来看结果，这个体验值回成本。

### 4.2 `directory` 参数：这个方案的最大隐性风险

OpenCode 的所有会话/文件接口都带 `directory` 参数（~~SDK 里通过 `x-opencode-directory` header 传递~~ → **更正（2026-10-07）**：实际是 **query 参数** `directory` / `location[directory]`，如 `GET /api/model?location[directory]=`；`oc/OcClient.kt:104-129` 只在 **REST** 上注入 `x-opencode-directory` header，**SSE 已刻意去掉**该 header），**它决定"这个会话在哪个项目上下文里"**。

**风险**：Compose UI 如果让用户自由切目录 /起多个会话，可能同时出现多个 `directory` 上下文。而证道的 workspace 是 `/workspace`（= 手机 `Download/证道`），**和 OpenCode 自己的 XDG 体系是两套**。

**决策点（必须现在就定）**：
- 太极 Tab 是否**只允许一个固定 directory**（建议：只绑 `/workspace`，不做目录选择器）？
- 是否需要多会话列表？建议**先只做单会话**（与证道 v3.6 的单会话定调一致），会话列表留到 v2。

> 这条不定清楚，实现到一半会返工——因为会话/文件/权限三类接口全都得带这个参数。

### 4.3 权限批准（`permission.asked`）：Agent 要写文件时必须能批准

OpenCode 的工具调用会触发 `permission.asked` 事件，**App 必须能响应**（~~`POST /session/{id}/permissions/{permissionID}`，body `{response:"allow"|"deny", remember}`~~ → **更正（2026-10-07）**：真实端点是 `POST /api/session/{id}/permission/{permissionId}/reply`，**body 是 `{"decision":"once"|"always"|"reject"}`**，可选 `GET /api/permission/request` 拉待批准列表）。

**这不是可选项**——不实现的话，Agent 一旦要执行命令或改文件就会**卡住等批准**，用户看到的是"卡死了"。

**决策点**：批准弹窗用 Compose `AlertDialog` 还是底部抽屉？建议**底部抽屉**（单手可达、能看到上下文）。且必须显示**工具名 + 目标文件/命令**，否则就是盲批。

---

## 5. ✅ 这个方案顺带解决 / 规避的问题

| 原来的问题 | 在新方案下|
|---|---|
| **WebView 吃内存**（Android WebView 每个实例几十 MB，且 `RootfsDownloader.kt:46` 明确把它排除在内存治理外） | ✅ 消除。这是本次最大的实际收益——太极 Tab 是常驻 Tab，WebView 常驻的内存代价很实在 |
| **P0-1 cleartext 问题**（曾担心 `targetSdk 28` 下 WebView 访问 `http://localhost` 被系统拦） | ✅ **本项目实际已配 `usesCleartextTraffic="true"`（真实位置 `app/src/main/AndroidManifest.xml:33`，**不是**原文写的 `Manifest:27`），从未被拦**。Compose 方案同样不受影响。见 §6.1 |
| **WebView auth 回调不触发**（`LocalProxy` 注释里的实测坑） | ✅ 绕开（Interceptor 直接注入） |
| **IME 中文输入**（v3 §3 把 CJK 输入列为"高风险持续投入项"，Hermes 桌面端为此专门修过IME） | ✅ **大幅缓解**。Compose `TextField` 走系统标准输入通道，比 TerminalView 的 `EditText` 路径可控得多。**这可能是被低估的第二大收益** |
| **终端渲染卡顿**（v3.9 换掉 xterm.js 的初衷） | ✅ 不再渲染终端，只渲染结构化消息 |
| 保留 TerminalView 给真终端用（洞天） | ✅ 不动，职责分离干净 |

---

## 6. ⚠️ 必须提醒的三个坑

### 6.1 cleartext 不会因为不用 WebView 就消失

⚠️ **这是最容易误判的一点**：`network_security_config.xml` / `usesCleartextTraffic` 管的是**所有明文 TCP 流量**，不只是 WebView。

`targetSdk 28` + OkHttp 访问 `http://127.0.0.1:14000` —— **OkHttp 同样受 cleartext 策略约束**。

~~**必须在 `AndroidManifest.xml` 加**~~ → **更正（2026-10-07）：不需要加。** 项目 `app/src/main/AndroidManifest.xml:33` 已有 **`android:usesCleartextTraffic="true"`**，它对整个 App 的所有明文 TCP 流量放行，`network_security_config.xml` 属于更细粒度的替代方案，两者**不必并存**。下面这段仅作历史留档：

```xml
<application android:networkSecurityConfig="@xml/network_security_config" ...>
```
```xml
<!-- res/xml/network_security_config.xml -->
<network-security-config>
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="false">127.0.0.1</domain>
        <domain includeSubdomains="false">localhost</domain>
    </domain-config>
    <base-config cleartextTrafficPermitted="false" />
</network-security-config>
```
**注意**（若将来真要用该 XML）：`127.0.0.1` 与 `localhost` 在 `<domain>` 里是**两条，不通配**。

> **原自问已核实（2026-10-07）**：项目里**没有** `res/xml/network_security_config.xml`（该目录只有 `backup_rules.xml`、`data_extraction_rules.xml`），而且**不需要新建**——`AndroidManifest.xml:33` 的 `usesCleartextTraffic="true"` 已放行。**原文「如果没有，这是第 1 号任务」作废**，第 0 号任务就是鉴权验证。
> 补充（结论仍成立）：这条策略管的是**所有**明文 TCP 流量，走 `LocalProxy`（也是 HTTP 明文）时同样受它约束——所以这不是"绕过 WebView 专属问题"，是"整个 localhost HTTP 链路"的问题。只是**解法是已有的 `usesCleartextTraffic`，不是新建 XML**。

### 6.2 别用官方 JS SDK，用 OpenAPI spec 生成 Kotlin 客户端

官方 `@opencode-ai/sdk` 是 **JS/TS 的**（Bun/Node 环境）。项目是纯 Kotlin Android，**引入它需要塞一个 JS 运行时**——那就本末倒置了。

**推荐做法**：拿 `packages/sdk/openapi.json`（OpenAPI 3.1）用 openapi-generator 生成 **Kotlin client**（OkHttp 适配器，项目已有 OkHttp 4.12）。这样类型与 API 契约与上游同步，上游改 API 只需重新生成。

**成本**：生成器要吃 JSON schema → 工程一次性投入。**收益**：比手撸 12 个端点可靠得多，且事件类型不用猜。

>备选（更快起步）：先手写 5 个核心端点（~~health / session list / message / prompt_async / event SSE~~ → 按真实端点写：**`GET /api/session`（列表）/ `POST /api/session`（新建）/ `GET /api/session/{id}/message` / `POST /api/session/{id}/prompt` / `GET /api/event`（SSE）**；**没有 `health` 端点**），验证通了再决定是否上生成器。

### 6.3 SSE 事件类型要按 v2 的字段名对齐

`packages/sdk/js/src/v2/gen/types.gen.ts` 里的事件类型是 **`v2` 命名空间**（`EventSessionCreated`、`EventMessagePartUpdated` 等），且 `message.part.updated` 的负载是 `{ part, delta? }`。

项目当前用的是 **OpenCode `VERSION = "2.0.22"`**（`OcManager.kt:34`）。**必须对着你实际打包的那个版本的 openapi.json 核实字段**，不要照抄文档里的示例——文档横跨 v1/v2/v3，字段名会变。

---

## 7. 建议的实施路径

**阶段 0（前置验证，0.5 天，不写UI）**
1. ~~确认/新建 `res/xml/network_security_config.xml`（§6.1）~~ → **无需处理**：`AndroidManifest.xml:33` 已有 `usesCleartextTraffic="true"`，且项目内没有、也不需要该 XML（见 §6.1 勘误）
2. 用 OkHttp 直连 `http://127.0.0.1:14000`，加 Basic auth header，验证 ~~`GET /global/health` 返回 200~~ → **`GET /api/session` 返回 200**（该版本**没有 health 端点**）
   —— **这一步决定方案可行性**，若鉴权过不去则整个方案要重新设计（**已通过**）
3. 验证 ~~`GET /event`~~ → **`GET /api/event`** 能收到 `server.connected` 及后续事件
4. 确认 `package` 里的 `openapi.json` 版本字段（§6.3）

**阶段 1（最小可用，消息收发）**
5. OkHttp `Interceptor` 统一注入 auth；~~`LocalProxy` 保留给旧 WebView 路径~~ → **已完成，且 `LocalProxy` 已随 WebView 路径一起删除**（`e9997ec`）
6. 会话：列表 → 单会话（固定 directory，§4.2）→ 发消息（~~`prompt_async`~~ → `POST /api/session/{id}/prompt`）
7. 收消息：SSE 增量（`delta` 追加）+ 重连全量补齐（§4.1）
8. UI：消息列表（user/assistant 分气泡）+ 输入框

**阶段 2（Agent 完整）**
9. **权限批准弹窗**（§4.3）—— 不可省
10. 工具调用展示（tool part折叠）、todo 列表、diff 面板（API 现成，§2）
11. 中止按钮（~~`POST /session/{id}/abort`~~ → **`POST /api/session/{id}/interrupt`**）

**阶段 3（收尾）**
12. WebView 路径下线 → 删除 `LocalProxy` 与 WebView 代码 —— **✅ 已完成**（`e9997ec`，v1.1.1 阶段 3）
13. TerminalView 在太极 Tab 不再使用（洞天继续用）—— ✅ 已完成；太极 Tab 现为 `ui/taiji/TaijiScreen.kt` 的 Compose 原生实现（`MainActivity.kt:490-494` 的 `0 -> TaijiScreen()`，并注明「旧 WebView + LocalProxy 回退路径已删」）

---

## 7.5 为什么不该在 WebView 路径上继续投入

用户实测反馈：WebView 版太极 Tab「**卡、慢、进不了对话框**」，改了几次无改善。**已定位为`LocalProxy` 的三处结构性缺陷，不是 UI 层问题**——完整诊断见 `证道-WebView卡慢根因诊断.md`。

> **注（2026-10-07）**：`LocalProxy.kt` 已随 WebView 路径下线而删除（`e9997ec`）。下表**行号/文件引用已失效**，仅保留缺陷描述作为「为什么要换 Compose」的历史依据。

| 缺陷 | 代码位置 | 后果 |
|---|---|---|
| `readHeaderBlock` **逐字节读 + 每字节 O(N²) 拷贝** | `LocalProxy.kt` | 每个请求上千次系统调用 + 百万次字节拷贝；SPA 首屏并发请求**串行排队** → "进不了对话框" |
| 强制 `Connection: close`（为绕过 keep-alive 裸奔 401） | `LocalProxy.kt` | **每个请求新建连接 + 新建线程**，且要重新解析一遍头 → 请求密集场景极慢 |
| SSE 占用线程 + `backlog=64` | `LocalProxy.kt` | 全局事件流与会话事件流各占一连接两线程；事件流一慢，UI 的"正在思考/token 逐字"就变成卡住 → "慢"的直接体感 |

**关键判断**：缺陷 2 是**设计取舍而非 bug**——只要"WebView 侧需要注入 auth header"这个前提还在，就必须付这个代价。而唯一能消除它的方式是**客户端层注入（OkHttp Interceptor）**，也就是 Compose 方案。

> **因此本裁定的重心从"换个更好的 UI"转为"移除自建 HTTP 转发层"。** Compose 直连后，WebView 与 `LocalProxy` 一并退休，上述三处缺陷一次性消失。
>
> **给阶段 0 加一条对比测量**（§7步骤 2-3）：用OkHttp 直连 vs 经`LocalProxy` 各打 20 个请求测总耗时，并对比 SSE 首事件到达时间。**预期数量级差异**——这个数字能让我们对"换 Compose 值多少"有实据而非感觉。

## 8. 一句话裁定

**可行，推荐做。**（**执行结果（2026-10-07）：已全部落地**——Compose 原生直连已上线，WebView 与 `LocalProxy` 已删除。）除了解掉内存、cleartext、IME 三个既有问题，更关键的是**移除 `LocalProxy` 这个自建 HTTP 转发层**——用户实测的"卡、慢、进不了对话框"已定位为其三处结构性缺陷（逐字节解析 + 禁 keep-alive + SSE 占线程），Compose 直连后一次性消失。此外还额外获得结构化事件流（比 TUI 信息更丰富）。**唯一的前置门槛是第 0 号任务——OkHttp 直连能否过 Basic 鉴权**——这个不通过，方案要重新设计；其余都是工程量问题。建议**先做阶段 0 的验证，再动 UI**。

---

*本裁定基于代码实况与 OpenCode 官方文档（核验日 2026-10-06），未修改任何代码。外部引用：opencode#12515（bunfs dlopen 问题与社区解法）、#8620（OpenTUI 在 PRoot 下 `/proc/self/fd` 权限问题）、官方 server 文档与 SDK 文档。相关文档：`证道-WebView卡慢根因诊断.md`（WebView 路径卡慢的传输层根因）。*