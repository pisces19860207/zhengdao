# 太极 Tab · Compose 原生 UI 设计方案

> **版本**：v1.0（2026-10-06）>   
> **定位**：OpenCode 的原生客户端。替代 WebView / TerminalView，直连 `opencode serve` 的 HTTP + SSE API。>   
> **前置裁定**：`证道-太极Tab技术选型裁定.md`（为什么做）、`证道-WebView卡慢根因诊断.md`（为什么必须换掉 LocalProxy）>   
> **技术栈**：Kotlin + Jetpack Compose（Material 3）+ OkHttp 4.12（已有）+ kotlinx.serialization>   
> **不引入**：WebView、TerminalView、本地代理层、官方 JS SDK

---

## 1. 设计原则

| 原则         | 具体含义                                                                                               |
| ---------- | -------------------------------------------------------------------------------------------------- |
| **结构化优先**  | SSE 给的是结构化事件（message / part / tool / permission / todo），不是终端画面。**先建模事件，再画 UI**——不要用"拼字符串"的方式模拟 TUI |
| **薄网络层**   | 网络只有一处出入口（`OcClient`），auth 在 Interceptor 注入。所有 UI 拿到的都是 Kotlin 类型，**不出现裸 JSON 字符串**                |
| **状态单一来源** | Compose state 是唯一真相。**不用全局变量、不用单例存会话状态**——进程重建能从serve 恢复                                           |
| **失败可见**   | 任何失败（SSE 断、鉴权错、权限拒绝）都要在 UI 上可读，不能静默。**不用"假装成功"**（与 M2 `trimGuestMemory` 同一原则）                      |
| **可回退**    | 阶段 3 之前 WebView 路径保留，Compose 侧出问题能切回。**但不做功能降级 UI**，只做整体回退                                         |

---

## 1.5 依赖清单

~~**新增 2 个**（其余复用现有）：~~ ⚠️ **更正（2026-10-07）：这 2 个依赖一个都没加。**

```kotlin
// app/build.gradle.kts —— ⚠️ 更正（2026-10-07）：下面「新增」两行从未落地；现状只有 okhttp 4.12.0
dependencies {
    // 已有
    implementation("com.squareup.okhttp3:okhttp:4.12.0")   // ← 现状：这是唯一的网络依赖（app/build.gradle.kts:149）
    implementation(libs.androidx.compose.material3)          // 已有
    implementation(libs.androidx.lifecycle.runtime.ktx)       // 已有

    // ── 新增（⚠️ 2026-10-07 核对：从未加入 app/build.gradle.kts）──
    // ⚠️ okhttp-sse 是独立 artifact，不在 okhttp 里（2026-10-06 核实 Maven Central）
    implementation("com.squareup.okhttp3:okhttp-sse:4.12.0")   // ⛔ 实际未引入

    // DTO 序列化（若不用 kotlinx.serialization 则手写，量不大）
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")   // ⛔ 实际未引入
    // 注意：启用 kotlinx.serialization 需在 kotlin { } 里加 serialization 插件
}
```

**不引入**：WebView（阶段 3 删除）、TerminalView（洞天保留，与本Tab 无关）、任何第三方 SSE 库、官方 JS SDK、任何 UI 框架（M3 够用）。

> 📌 **DTO 手写 vs kotlinx.serialization**：OpenCode 的 part 类型较多（Text/Reasoning/Tool/File/Patch/Step…），序列化能省事。但若不想引插件+ 依赖，**手写 `JsonObject` 映射也可行**（量不大，约 200 行）。阶段 0 验证时顺手定即可。
>
> ⚠️ **更正（2026-10-07）**：实际选的是**手写**——SSE 由 `oc/SseClient.kt` 自写解析（该文件 `:27` 的注释标题即「为什么自己解析而不用 okhttp-sse 的 EventSources」，`:29-31` 说明 okhttp-sse 是独立 artifact、不新增依赖），DTO 用手写 `org.json` 映射；`okhttp-sse` 与 `kotlinx-serialization` **均未引入**（见上）。

## 2. 架构分层

```
feature/taiji/
├── data/                          # 网络与数据层（不含 UI）
│   ├── OcClient.kt                # OkHttp 封装：auth Interceptor + 端点方法
│   ├── dto.kt                     # 序列化 DTO（对应 OpenAPI schema）
│   ├── SseClient.kt               # SSE 长连接：重连退避 + 事件分发
│   ├── OcRepository.kt            # 单一真相源：本地状态 + SSE 归约 + API 写操作
│   └── ConnectionState.kt         # 连接状态机（ sealed class）
├── ui/                            # Compose UI（纯函数式，输入 state 输出 UI）
│   ├── TaijiScreen.kt             # 屏幕骨架 + 状态机分发
│   ├── TaijiViewModel.kt          # 状态宿主（不依赖 Android 框架，除 ViewModel）
│   ├── components/
│   │   ├── MessageList.kt# 消息列表（LazyColumn）
│   │   ├── MessageBubble.kt       # 单条消息（user / assistant）
│   │   ├── PartRow.kt             # part 渲染分发：text / tool / reasoning / file
│   │   ├── ToolCallCard.kt        # 工具调用卡片（可折叠 + 状态）
│   │   ├── PermissionSheet.kt     # 权限批准底部抽屉 ★ 不可省
│   │   ├── TodoPanel.kt           # Agent 任务清单
│   │   ├── ComposerBar.kt         # 输入框 + 发送 + 中止
│   │   ├── SessionBar.kt          # 顶部栏：会话标题 + 状态 + 菜单
│   │   └── ConnectionBanner.kt    # 连接状态横幅（重连中/已断）
│   └── theme/TaijiTheme.kt        # 局部主题（可选）
└── TaijiNavHost.kt                # 若需要多会话路由则在此
```

**依赖方向**：`ui → data`（单向）。`data` 层不引用任何 Compose 类型（除 `StateFlow`）。

---

## 3. 数据层设计

### 3.1 `OcClient` —— 网络唯一出入口

```kotlin
/**
 * OpenCode HTTP 客户端。
 *
 * 为什么不用 LocalProxy（2026-10-06 诊断）：
 *   旧路径为绕开 WebView 的 401 回调缺失，自建字节级 HTTP 转发层，代价是
 *   ① readHeaderBlock 逐字节读 + O(N²) 拷贝 ② 强制 Connection: close
 *   ③ SSE 占线程 —— 表现为用户实测的「卡 / 慢 / 进不了对话框」。
 *   OkHttp 自带缓冲解析 + 连接池 + keep-alive，Interceptor 天然解决 auth 注入，
 *   上述三条代价一次性消失。
 *
 * 依据：docs/milestones/证道-WebView卡慢根因诊断.md
 */
class OcClient(
    baseUrl: String = "http://127.0.0.1:${OcManager.PORT}",  // 14000
    private val passwordProvider: () -> String?,
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)      // SSE 需要长读超时
        .retryOnConnectionFailure(true)
        .addInterceptor(AuthInterceptor(passwordProvider))  // ★ auth 在此统一注入
        .build()

    val base: HttpUrl = baseUrl.toHttpUrl()

    // ── 端点（对应 OpenAPI，全部经 OkHttp；字段名以实际打包版本的 spec 为准）──
    suspend fun health(): Health?                  // ⛔ GET /global/health —— 该端点不存在（2026-10-07 核对：无真实调用，catch-all 只回 HTML）
    suspend fun listSessions(): List<Session>       // GET /api/session
    suspend fun createSession(title: String?): Session  // POST /api/session
    suspend fun messages(sessionId: String): List<Message>  // GET /api/session/{id}/message
    suspend fun prompt(sessionId: String, text: String)    // POST /api/session/{id}/prompt（不是 prompt_async）
    suspend fun abort(sessionId: String)            // POST /api/session/{id}/interrupt（不是 abort，且无请求体）
    suspend fun todos(sessionId: String): List<Todo> // ⛔ GET /api/session/{id}/todo —— 未实现（todos 只来自 SSE 事件 todo.updated：oc/SseClient.kt:179 → oc/OcRepository.kt:379）
    suspend fun respondPermission(
        sessionId: String, permissionId: String, allow: Boolean, remember: Boolean,
    )                                              // POST /api/session/{id}/permission/{permissionId}/reply
}

/**
 * 统一注入 HTTP Basic auth。
 *
 * 为什么这一层就能解决鉴权（旧路径必须靠 LocalProxy）：
 *   WebView 的 fetch/XHR 收到 401 **不会**触发 onReceivedHttpAuthRequest（真机实测），
 *   SPA 拿不到凭据就卡死；App 自己是客户端时，直接在请求头带上凭据即可。
 */
private class AuthInterceptor(
    private val passwordProvider: () -> String?,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val pw = passwordProvider() ?: return chain.proceed(chain.request())
        val creds = Base64.encodeToString(
            "opencode:$pw".toByteArray(), Base64.NO_WRAP
        )
        return chain.proceed(
            chain.request().newBuilder()
                .header("Authorization", "Basic $creds")
                .header("x-opencode-directory", WORKSPACE_DIR)   // ⚠️ 更正（2026-10-07）：仅 **REST** 客户端保留兼容；**SSE 客户端不得带**（见 §3.4 更正）
                .build()
        )
    }
}
```

> ⚠️ **更正（2026-10-07）——本段端点注释**：代码块里的 `// GET /global/health`、`// GET  /session`、`// POST /session`、`// GET /session/{id}/message`、`// POST /session/{id}/prompt_async`、`// POST /session/{id}/abort`、`// GET /session/{id}/todo`、`// POST /session/{id}/permissions/{id}` 是**骨架的设想**，实测端点**一律带 `/api` 前缀**（以 `oc/OcRepository.kt` 为准）：`POST /api/session`（`oc/OcRepository.kt:99`）、`GET /api/session`（`:115`）、`GET /api/session/{id}/message`（`:128`/`:211`）、`POST /api/session/{id}/prompt`（`:539`，**不是 `prompt_async`**）、`POST /api/session/{id}/interrupt`（`:551`，**不是 `abort`**，且无请求体——`:547` 的 `suspend fun abort()` 只是自己起的名字，`:550` 注释已注明）、`POST /api/session/{id}/permission/{permissionId}/reply`（`:571`）、`GET /api/permission/request`（`:269`）、`GET /api/model?location[directory]=`（`:485`）、SSE `GET /api/event`（`:336-337`）。**`/global/health` 不存在**（`oc/OcManager.kt:133` 的注释说明 `/`、`/global/health`、`/session`、`/doc` 是 catch-all、"统统返回 200 text/html"，不是 JSON 健康接口）；`GET /api/session/{id}/todo` **未实现**（todos 只来自 SSE 事件 `todo.updated`：`oc/SseClient.kt:179` → `oc/OcRepository.kt:379`，解析在 `:894`）。
> ⚠️ **更正（2026-10-07）——`x-opencode-directory` 头**：`oc/OcClient.kt:106` 的注释原文判定「该头**真实 spec 里并不存在**，是骨架的幻想产物」；现状是**只在 REST 请求上注入**（`oc/OcClient.kt:128`；REST/SSE 两个 client 分别在 `:53`/`:69`），SSE 请求**刻意不注入**（`oc/OcClient.kt:121` 的「🔬 诊断实验：SSE 请求不注入 x-opencode-directory」——曾怀疑它让 `/api/event` 立刻返回空流）。真正的 `directory` 参数在 `/api/model` 上是 query `location[directory]`（`oc/OcRepository.kt:485`）。

> ⚠️ **`readTimeout(0)` 是必须的**：SSE 是长连接，设了读超时会被中途掐断。但**这个 0 只应给SSE 连接**，普通请求应给合理超时（建议 30s）。实现上建议**两个 OkHttpClient**（普通 + SSE）而不是共用一个，避免普通请求也永久等待。

### 3.2 `SseClient` —— 事件长连接

````kotlin
/**
 * SSE 客户端。负责：连接、解析 event、重连退避、事件分发。
 *
 * 不用 WebView 的 EventSource，也不用第三方 SSE 库。

> ⚠️ **依赖注意（2026-10-06 核实Maven Central）**：`okhttp-sse` 是**独立 artifact**，**不包含在 `com.squareup.okhttp3:okhttp` 里**。只引okhttp 找不到 `okhttp3.sse.EventSources` 工厂类，须显式加一行（版本与 okhttp 对齐）：
> ```kotlin
> implementation("com.squareup.okhttp3:okhttp:4.12.0")   // 已有
> implementation("com.squareup.okhttp3:okhttp-sse:4.12.0") // ← 需新增
> ```
 */
class SseClient(
    private val client: OkHttpClient,
    private val scope: CoroutineScope,
) {
    sealed interface Event {
        data class MessageUpdated(val info: Message) : Event
        /** ⚠️ 带 delta —— 流式增量追加的关键 */
        data class PartUpdated(val part: Part, val delta: String?) : Event
        data class PartRemoved(val sessionId: String, val partId: String) : Event
        data class SessionIdle(val sessionId: String) : Event
        data class PermissionAsked(val sessionId: String, val permissionId: String,
                                   val title: String, val detail: String?) : Event
        data class TodoUpdated(val sessionId: String, val todos: List<Todo>) : Event
        data class Unknown(val type: String, val raw: String) : Event   // ★ 见下
    }

    fun connect(url: HttpUrl): Flow<Event> = flow {
        var attempt = 0
        while (currentCoroutineContext().isActive) {
            try {
                client.newCall(Request.Builder().url(url)
                    .header("Accept", "text/event-stream").build())
                    .execute().use { resp ->
                    resp.body!!.source().buffer().source().let { parseSse(it) { emit(it) } }
                }
                attempt = 0                       // 连上了就重置退避
            } catch (e: IOException) {
                delay(BACKOFF_MS[minOf(attempt++, BACKOFF_MS.size - 1)])
            }
        }
    }
    private val BACKOFF_MS = longArrayOf(1_000, 2_000, 4_000, 8_000, 15_000)
}
````

**关键设计点**：

| 点                    | 做法                                                    | 理由                                                |
| -------------------- | ----------------------------------------------------- | ------------------------------------------------- |
| **`Unknown` 分支必须保留** | 收到不认识的事件类型时归为 `Unknown(raw)` 而不是丢弃                    | 上游 OpenCode 会持续加新事件；**丢了会导致 UI 静默不同步**（比多显示一条更安全） |
| **重连后必须全量补齐**        | 重连成功后调 `messages(sessionId)` 拉全量                      | 断线期间的消息只在补齐里拿得到                                   |
| **delta 与全量不可混用**    | 连接正常时用 `delta` 增量追加；重连后切全量并**重建列表**                   | 否则文本会重复追加（§3.3 的核心陷阱）                             |
| **切后台降级**            | `Lifecycle` 转后台 → 停SSE、启轮询；回前台 → 停轮询、恢复 SSE 并全量补齐     | 复用现有 `serveRunning()` 轮询机制                        |
| **心跳**               | SSE 每 30s 有心跳；OkHttp 层靠 `retryOnConnectionFailure` 兜底 | 不需要自己发明心跳                                         |

### 3.3 `OcRepository` —— 状态归约（最容易写错的地方）

```kotlin
class OcRepository(private val client: OcClient, private val sse: SseClient) {
    private val _state = MutableStateFlow(TaijiState())
    val state: StateFlow<TaijiState> = _state.asStateFlow()

    suspend fun open(sessionId: String) {
        // ① 初始全量
        _state.update { it.copy(messages = client.messages(sessionId).toUiModel()) }
        // ② SSE 增量
        sse.connect(...).collect { ev -> _state.update { reduce(it, ev) } }
    }

    /** ⚠️ delta 与全量的互斥由这里保证 */
    private fun reduce(s: TaijiState, ev: SseClient.Event): TaijiState = when (ev) {
        is PartUpdated -> {
            val existing = s.parts[ev.part.id]
            val text = when {
                existing == null -> ev.delta ?: ev.part.text.orEmpty()   // 新建
                ev.delta != null -> existing.text + ev.delta            // ★ 增量追加
                else -> ev.part.text.orEmpty()                           // 全量覆盖
            }
            s.copy(parts = s.parts + (ev.part.id to text.toPart()))
        }
        is MessageUpdated -> s.copy(messages = s.messages.upsert(ev.info))
        is PermissionAsked -> s.copy(pendingPermission = ev.toUi())
        is TodoUpdated -> s.copy(todos = ev.todos)
        is PartRemoved -> s.copy(parts = s.parts - ev.partId)
        is SessionIdle -> s.copy(isStreaming = false, pendingPermission = null)
        is Unknown -> s.copy(lastUnknownEvent = ev.type)   // 记日志，不崩
        else -> s
    }
}
```

> 🔺 **这段是整个方案最容易出错的地方。** `PartUpdated` 同时可能带 `delta`（增量）和不带 `delta`（全量）。判据：**有 `delta` 就追加，没有就用 `part.text` 覆盖**。混用会导致文字重复或闪烁。

### 3.4 `directory` 参数 —— 定死不做目录选择器

```kotlin
companion object {
    /** 太极 Tab 只绑这一个目录。不做目录选择器（与会话/文件/权限三类接口全部相关）。 */
    const val WORKSPACE_DIR = "/workspace"
}
```

**理由**（裁定报告 §4.2）：所有会话/文件/权限接口都带 `directory`，它决定"这个会话在哪个项目上下文里"。太极 Tab 是**单会话 + 固定目录**（与证道 v3.6 单会话定调一致）。多会话/多目录留 v2。

> ⚠️ **更正（2026-10-07）**：实际落地的 `WORKSPACE_DIR` 语义与本节不同——**没有**走"所有会话/文件/权限接口都带 `directory` 参数"这条路，而是：① REST 请求统一加 `x-opencode-directory: /workspace` 头（`oc/OcClient.kt:128`，该文件 `:106` 的注释判定此头"真实 spec 里并不存在"）；② **SSE 请求刻意不加**（`oc/OcClient.kt:121`）；③ 真正的 `directory` 只出现在 `GET /api/model?location[directory]=`（`oc/OcRepository.kt:485`）。"单会话 + 固定目录"的结论不变。


## 5. UI 设计

### 5.1 屏幕骨架

```
┌─────────────────────────────────┐
│  ← 会话标题 · ● 就绪      ⋮     │  SessionBar
├─────────────────────────────────┤
│ ⟨连接横幅：已断开，正在重连…⟩    │  ConnectionBanner（仅非Connected 时显示）
├─────────────────────────────────┤
│                                 │
│  用户：帮我看看这个 bug          │  MessageList (LazyColumn，倒序)
│  ┌───────────────────────────┐  │
│  │ 助手：…                   │  │
│  │ ▸ 读取 App.kt        ✓    │  │  ToolCallCard（可折叠）
│  │ ▸ 编辑 Main.kt      ✓    │  │
│  └───────────────────────────┘  │
│  ⟨待办清单：3 项⟩               │  TodoPanel（折叠）
│                                 │
├─────────────────────────────────┤
│  上下文已满 ▾                    │
│  输入你的消息…            [中止] │  ComposerBar
└─────────────────────────────────┘
     ↑ 权限批准时从底部升起
┌─────────────────────────────────┐
│ ⚠️ 工具需要授权                  │
│ 修改 Main.kt（写入 12 行）       │  PermissionSheet ★ 不可省
│                    [拒绝] [允许] │
└─────────────────────────────────┘
```

### 5.2 关键组件要点

**`ComposerBar`** —— IME 是重点（v3 §3 把 CJK 输入列为"高风险持续投入项"）

- `TextField` 用普通文本类型，**绝不设 `TYPE_TEXT_VARIATION_PASSWORD`**（§7 第2条红线的延伸——安全键盘会让输入不可用）
- 关闭自动建议/自动纠错：不要干扰代码输入
- **多行输入**（Agent 场景常用长指令），发送键固定在右下
- `isStreaming` 时发送键变为「中止」（调 `abort`），不要让用户干等
- ⚠️ **键盘弹出不得顶掉输入框**：`imePadding()` + `navigationBarsPadding()`，`windowSoftInputMode` 确认是 `adjustResize`

**`PermissionSheet`** —— **不可省，且不能盲批**

- 显示**工具名 + 具体目标**（文件路径 / 将执行的命令），不是"允许执行吗？"
- 两个按钮：`拒绝` / `允许`；可选 `记住这个选择`（对应 API 的 `remember` 参数）
- 用 `ModalBottomSheet`（Material 3），单手可达
- **必须有超时兜底**：Agent 卡在等待批准会让用户以为死了。挂个"3 分钟未响应自动拒绝"或明确的"正在等待授权"提示

**`ToolCallCard`** —— 结构化事件带来的红利

- 折叠态：一行摘要（工具名 + 状态图标）
- 展开态：入参摘要 + 结果摘要（超长截断）
- 状态：`running` / `success` / `error` 三态视觉区分

**`MessageList`**

- `LazyColumn` + `reverseLayout`（新消息在底部，符合聊天习惯）
- 列表项**高度不定**（代码块会长）→ 不要用固定高度 item
- 自动滚到底部仅在用户已在底部时；用户上滑查看历史时**不要抢滚动**

### 5.3 主题

沿用项目现有 Material 3 主题（`Theme.Zhengdao`）。太极 Tab 可加一点自己的色彩标识（沿用底部 Tab 的太极图标语汇），但**不要引入新设计系统**——项目已有 Compose + M3。

---

## 6. 实施路径

### 阶段 0：可行性验证（0.5 天，**不写 UI**）

| #   | 任务                                            | 通过标准                                                              |
| --- | --------------------------------------------- | ----------------------------------------------------------------- |
| 0-1 | OkHttp + AuthInterceptor 直连 `127.0.0.1:14000` | ~~`GET /global/health` 返回 200~~ ⚠️ 见下 ⁰                                       |
| 0-2 | 建立 SSE `/event` 连接                            | ~~收到 `server.connected` 及后续事件~~ ⚠️ 见下 ⁰                                       |
| 0-3 | **对比测量**（把收益变实据）                              | OkHttp 直连 vs 经 LocalProxy 各打 20 请求的总耗时；两者 SSE 首事件到达时间             |
| 0-4 | 核实 DTO 字段名                                    | 对**实际打包版本**（`OcManager.VERSION = "2.0.22"`）的 `openapi.json` 逐字段核对 |

> ⁰ ⚠️ **更正（2026-10-07）**：`/global/health` **不存在**（`app/src/main` 全库 0 次真实调用；`oc/OcClient.kt:4` 与 `oc/OcManager.kt:133` 只是把它当 catch-all 路径提到，后者明说这些路径"统统返回 200 text/html"，不是 JSON 健康接口）。0-1 的通过标准应改为 **`GET /api/session` 返回 200**（`oc/OcRepository.kt:115`）。0-2 的端点应为 **`GET /api/event`**（`oc/OcRepository.kt:336-337`），且 `docs/ERRATA.md` E-008 已判定它"连上后 0.1–1.4 s 即被服务端关闭"（上游 Issue #38458）——SSE 现在只当信号通道、事件数据走 REST 轮询（`oc/SseClient.kt:20-25`）。

> 🔴 **0-1 不通过则整个方案要重新设计**，不要往下做。

### 阶段 1：消息收发（最小可用）

1. `OcClient` + DTO + AuthInterceptor
2. `OcRepository` 初始全量加载
3. `SseClient` 增量（**先只处理 `PartUpdated` + `MessageUpdated`**）
4. UI：`MessageList` + `MessageBubble` + `ComposerBar`
5. 验收：发一句话，能看到逐字流式回复；杀进程重进能恢复会话

### 阶段 2：Agent 完整能力

1. **`PermissionSheet`（★ 里程碑门禁：不做就不完整）**
2. `ToolCallCard`（running/success/error）
3. `TodoPanel`
4. `abort` 按钮
5. SSE 重连 + 全量补齐（用阶段 0-3 的数据验证提升）
6. 切后台降级为轮询、回前台恢复

### 阶段 3：收尾

1. 移除 WebView 代码路径
2. 移除 `LocalProxy`
3. `TuiScreen` 从 Tab 中下线（洞天的 TerminalView **不动**）

---

## 7. 不可违反的约束（继承项目红线）

| # | 约束                                                                                   | 来源                         |
| - | ------------------------------------------------------------------------------------ | -------------------------- |
| 1 | **所有输入框禁用密码类型**（`TYPE_TEXT_VARIATION_PASSWORD`）——国产 ROM 会弹安全键盘，无 ESC/CTRL，终端与输入直接不可用 | v3 §7 第 2 条                |
| 2 | 不设 `Proxy.NO_PROXY`、不自定义 `ProxySelector` 强制直连——必须让流量进用户代理 App 的 VpnService           | v3.7 红线 / M5 §6            |
| 3 | **targetSdk 28 钉死**，不得以"顺手升级"为由改动                                                    | `已知限制.md` §2               |
| 4 | 太极 Tab 只用 **arm64 单架构**                                                              | 2026-10-06 定案              |
| 5 | XDG 隔离由 `OcManager` 的独立四目录承担，不在 UI 层另设路径                                             | 执行路线图 P3                   |
| 6 | 失败必须可见，不许"假装成功"                                                                      | 与 M2 `trimGuestMemory` 同原则 |
| 7 | 不引入官方 JS SDK（`@opencode-ai/sdk` 是 JS/TS 的，塞 JS 运行时本末倒置）                              | 裁定报告 §6.2                  |

---

## 8. 风险与对策

| 风险                        | 概率 | 影响        | 对策                                                             |
| ------------------------- | -- | --------- | -------------------------------------------------------------- |
| **鉴权过不去**（阶段 0-1失败）       | 中  | 方案重设计     | 阶段 0 前置验证，**不通过就停**                                            |
| **SSE 在部分 ROM 上被掐**（省电策略） | 中高 | 实时更新失效    | 切后台降级轮询；重连退避上限 15s；**必须有全量补齐兜底**                               |
| **`directory` 参数语义理解错**   | 中  | 会话/文件操作错乱 | 阶段 0-4 核实 spec；先只绑 `/workspace`                                |
| **DTO 字段名与实际版本不符**        | 中  | 静默丢数据     | 保留 `Unknown` 事件分支 + 记日志；上线后看日志调                                |
| **`delta` 与全量混用致文本重复**    | 中  | UI 显示错乱   | 归约逻辑集中在一处（§3.3），便于单测                                           |
| **中文 IME 输入出问题**          | 中  | 不可用       | Compose 标准输入通道比 TerminalView 可控；**阶段 1 就要专门测中文输入法**（v3 §3 的老坑） |
| **流量成本**（OpenCode 事件量大）   | 低  | 卡顿/耗电     | SSE 只订阅需要的端点；`Unknown` 不渲染 UI                                  |
| 用户在 Agent 运行时切走           | 高  | 体验割裂      | 会话由 serve 持有（本来就在宿主进程），App 回来重新attach 即可                       |

---

## 9. 与既有架构的关系

| 组件                                | 处置                                           |
| --------------------------------- | -------------------------------------------- |
| `TaijiScreen.kt`（WebView 版，329 行） | ~~阶段 3 删除，由本方案取代~~ ✅ 已于 `e9997ec` 删除                               |
| `LocalProxy.kt`（164 行）            | ~~阶段 3 删除（Compose 直连不需要）~~ ✅ 已于 `e9997ec` 删除                       |
| `OcManager.kt`                    | **保留并复用**：`serve` 拉起、密码解析、XDG 隔离、进程判活都已实现    |
| `MainActivity` 的太极 Tab            | 改为承载新的 `TaijiScreen`（Compose，与另两个 Tab 同级）    |
| TerminalView / `TerminalActivity` | **完全不动**（洞天继续用）——职责分离：太极 = Agent UI，洞天 = 真终端 |
| `CacheCleaner.kt`（工作树新增）          | 若在清理 SSE 相关资源，注意别与 SSE 连接管理冲突                |

---

> ⚠️ **更正（2026-10-07）——§9 表格第 1/2 行**：`TaijiScreen.kt`（WebView 版）与 `oc/LocalProxy.kt` 都已随 commit **`e9997ec`** 删除——阶段 3 是**已完成**，不是"待删除"。现存 `app/src/main/java/com/example/zhengdao/ui/taiji/TaijiScreen.kt` 是**重写后的 Compose 原生客户端**（468 行）：它**不是**被删的那个 WebView 文件，而正是本方案的产物；同批删除的还有 `oc/TaijiPrefs.kt` 与 `MainActivity` 的 `useNativeUi` 回退开关。

## 10. 一句话总结

**先做阶段 0 的半天验证（鉴权 + 对比测量），通过后按阶段 1→2→3 推进；其中 `PermissionSheet` 是里程碑门禁——不做 Agent 就会卡在等待授权。** 架构上最重要的决定是：**用结构化事件建模，而不是想办法把终端画面搬进 Compose。**

---

*本方案基于代码实况（`TaijiScreen.kt` / `LocalProxy.kt` / `OcManager.kt` / `MainActivity.kt` / `build.gradle.kts`）与 OpenCode 官方 API 文档撰写。DTO 字段名须以实际打包版本的 `openapi.json` 为准——官方文档横跨 v1/v2/v3，字段会变。*

> ⚠️ **更正（2026-10-07）**：上句列出的 `TaijiScreen.kt`（WebView 版）与 `LocalProxy.kt` **均已随 commit `e9997ec` 删除**——撰写时（2026-10-06）的"代码实况"对这两个文件已不适用；`OcManager.kt` / `MainActivity.kt` / `build.gradle.kts` 仍在。另：本方案 §1.5 所列的两个新增依赖（`okhttp-sse`、`kotlinx-serialization-json`）**最终都没有引入**，实现改用手写解析 + 手写 `org.json`（见 §1.5 更正）。
