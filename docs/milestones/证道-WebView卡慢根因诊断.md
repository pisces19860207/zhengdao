# WebView 版太极 Tab「卡 / 慢 / 进不了对话框」根因诊断

> ## ⚠️ 状态更正（2026-10-07）：本文所诊断的文件已删除，本文转为**历史诊断**
>
> - 本文依据的 `app/src/main/java/com/example/zhengdao/oc/LocalProxy.kt` **已随 commit `e9997ec` 删除**，仓库现状已不含该文件。本文所引代码经 `git show e9997ec^:` 复核**属实**，故**下文结论仍然成立**，保留作删除前的历史诊断记录。
> - 同 commit 一并删除的还有 **WebView 版 `ui/TaijiScreen.kt`**；现存 `app/src/main/java/com/example/zhengdao/ui/taiji/TaijiScreen.kt` 是**重写后的 Compose 原生客户端**（468 行），与本文所指的 WebView 版**不是同一个文件**，不要据"引用了 TaijiScreen.kt"来判断本文过时。
> - §6.3「迁移期间不要删 `LocalProxy`」的**过渡期已结束**（见文末 §6.3 更正）。

> **诊断时间**：2026-10-06
> **现象**（用户反馈）：WebView 版太极 Tab 不好用；改了几次仍然卡、慢、**进不了对话框**。
> **结论**：✅ **不是 UI 层的问题，是 `LocalProxy` 的三处结构性缺陷**。换 Compose UI 之所以能解决，是因为它**把这层代理整个绕开了**——不是恰好修好了渲染。
> **依据**：~~`app/src/main/java/com/example/zhengdao/oc/LocalProxy.kt` 代码实况（逐行核对）~~ ⚠️ 更正（2026-10-07）：该文件**已随 commit `e9997ec` 删除**；本文是其**删除前**的逐行实况，所引代码经 `git show e9997ec^:` 复核属实，结论保留有效。

---

## 0. 一句话结论

**`LocalProxy` 为了绕开 WebView 的 401 回调缺失，代价是给每个请求开了新TCP 连接 + 逐字节解析 HTTP 头+ 强制 `Connection: close`——这三件事叠加，正好产生你感受到的"卡、慢、进不去"。** Compose 方案让 App 自己就是 HTTP 客户端，**这三条代价一次性全部消失**。

---

## 1. 缺陷一：`readHeaderBlock` 逐字节读，且每次都做 O(n²) 拷贝

**代码实况**（`LocalProxy.kt`）：
```kotlin
private fun readHeaderBlock(cin: InputStream): ByteArray? {
    val buf = java.io.ByteArrayOutputStream()
    val one = ByteArray(1)                       // ① 每次只读 1 字节
    while (buf.size() < 65536) {
        val r = cin.read(one)
        if (r < 0) return null
        buf.write(one, 0, r)
        val b = buf.toByteArray()                // ② 每写 1 字节就整份拷贝一次
        if (b.size >= 4 && b[b.size-4]=='\r' && ...) return b
    }
    return null
}
```

**两个问题叠加**：
- **逐字节 `read()`**：一个典型 HTTP 请求头约 500–2000 字节（Cookie、Accept、User-Agent 都可能很长），意味着**每个请求要做上千次系统调用**。`InputStream.read()` 在 `Socket` 上是同步阻塞的。
- **每字节一次 `toByteArray()`**：`ByteArrayOutputStream.toByteArray()` 是**完整拷贝**。读 N 字节的头 → **O(N²) 的内存拷贝**。2000 字节的头 ≈ 200 万次字节拷贝，**每个请求都重来一遍**。

**为什么这会导致"进不了对话框"**：
SPA 首屏会并发打一批请求（配置、模型列表、会话列表、provider 列表……）。每个请求都要走一遍"上千次read + 200 万次拷贝"，而**这些请求是串行等待的**（浏览器有并发上限，但每个都要排在代理后面）。首屏直接卡在"看起来什么都没发生"的状态——**用户看到的正是"进不了对话框"**。

>对比：正确写法是一次 `BufferedInputStream.read(buf, 0, 8192)`，最多两三次系统调用就拿到整个头。

---

## 2. 缺陷二：强制 `Connection: close`，彻底废掉 HTTP keep-alive

**代码实况**（注释写得很坦白）：
> 请求头强制 Connection: close，让浏览器为每个新请求开新连接（每请求都经过注入；keep-alive 复用会让后续请求裸奔 401，真机实测 ERR_HTTP_RESPONSE_CODE_FAILURE）

**代价**：
- **每个请求都要三次 TCP 握手**（本地环回，约 0.1–1ms，可接受）+ **新建Socket + 新建线程**（`thread(name = "oc-proxy-conn")`）。
- **每个请求都要重新解析一遍 HTTP 头**——也就是**把缺陷一的代价乘以请求数**。
- 线程创建/销毁高频发生。首屏并发十几个请求 = 十几个线程各跑一遍 O(N²) 拷贝。

**为什么会"卡、慢"**：SPA 的资源加载是**请求密集型**的（几十个小请求很常见）。HTTP/1.1 的 keep-alive 本来就是为了这个场景，现在被完全禁用了。

> 这条是为解决鉴权而做的**正确取舍**——但它不该由代理层长期承担，而应该由客户端层（Interceptor）天然解决。

---

## 3. 缺陷三：SSE 长连接与单请求线程模型冲突

**代码实况**：
```kotlin
val up = thread(name = "oc-proxy-up") { pump(cin, uout) }
pump(upstream.getInputStream(), cout)     // ← 当前线程被 SSE 阻塞占用
up.join(3000)
```

**问题**：一条 SSE 连接会**长期占住一个线程**（`pump` 直到断开才返回）。而 `handle()` 本身也是每连接一线程。

**后果**：
- 前端开几条 SSE（全局事件流 + 会话事件流是**两个独立端点**：`GET /event` 与 `GET /session/{id}/event`），就**长期占住两条连接 + 两个线程**。
- `ServerSocket(PROXY_PORT, 64, ...)` backlog 只有 64，且**每条 SSE 都在accept 循环里占着**。并发连接一多，新请求排队甚至被拒。
- 更麻烦的是：**SSE 是这个 SPA 的实时更新主通道**。事件流一慢，UI 上的"正在思考…/token 逐字出现"就变成"卡住不动"——**这就是"慢"的直接体感**。

---

## 4. 为什么换 Compose 就好了——它把这一层整个绕开

| 原缺陷 | Compose 方案下|
|---|---|
| 逐字节读头 + O(N²) 拷贝 | **不存在**——OkHttp 自己有高效的 `BufferedSource`，且 App 是客户端，不需要代理层解析头再转发 |
| 强制 `Connection: close`、每请求新建连接 | **不存在**——OkHttp 走连接池 + keep-alive，一个 `RealConnection` 复用多次 |
| SSE 占线程 + backlog 压力 | **大幅缓解**——OkHttp 单个 SSE 流只占一个连接，不需要为它额外开线程再转发 |
| 401 回调不触发 → 逼出代理层 | **Interceptor 注入 auth header**，天然对所有请求生效，不需要每次重写头 |

**所以 Compose 方案的收益不只是"UI 更好看"，而是把一个自制的、字节级的 HTTP 转发层换成成熟的 HTTP 客户端栈。**这才是"卡、慢"的真正解法。

---

## 5. 关于"改了几次还是不行"——这不是你们的问题

从代码看，这个代理层的设计动机是明确且正确的（绕过 WebView 的 401 回调缺失，且注释里记录了真机实测：`ERR_HTTP_RESPONSE_CODE_FAILURE`）。**但它选择了字节级透传 + 自建 HTTP 解析**这条路，而这条路在"高频小请求 + 长连接流"这个 WebView SPA 的典型负载下，天然就是低效的。

**可以推断改的方向一直在打补丁**（比如调超时、重试、改 UI 渲染），**而瓶颈在传输层，UI 层怎么改都不会好**。这解释了"改了几次还是卡"。

---

## 6. 建议的行动

### 6.1 不要在 WebView 路径上继续投入

⚠️ **这是个明确的止损建议**。理由：
- 缺陷在**自建 HTTP 转发层**，不是 UI 层；改 UI 收益极低。
- 缺陷 2（`Connection: close`）是设计取舍，不是 bug——**只要"WebView 需要注入 auth header"这个前提还在，就必须付这个代价**。
- 唯一能彻底消除它的办法就是**客户端层注入 auth**（即 Compose 方案）。

### 6.2 阶段 0 的验证价值因此更高

原计划阶段 0 验证的是"OkHttp 直连 + Interceptor 注入 auth 能否过鉴权"。本诊断把这个验证的**收益预期提高了**：

> 这一步不仅验证"能连上"，而是**同时验证"能绕开上面三处缺陷"**。
> 建议在阶段 0 里加一条**对比测量**（半小时能做完，能直接量化收益）：
>
> ```
> 1. 用 OkHttp + Interceptor（复用连接池、keep-alive）连续打 20 个 /global/health 与 /session
>    → 记录总耗时与平均单请求耗时
> 2. 对照：同样 20 个请求经现有 LocalProxy
>    → 记录总耗时
> 3. 对比 SSE：分别用两种方式建立 /event 连接，测首事件到达时间
> ```
>
> **预期**：OkHttp 直连在"高频小请求"场景下应有数量级优势。这个数字会让我们对"换 Compose 到底值多少"有实据，而不是靠感觉。

### 6.3 ~~迁移期间不要删 `LocalProxy`~~ ⚠️ 更正（2026-10-07）：过渡期已结束，`LocalProxy` 已下线

~~它还在给现有 WebView 路径供着。**Compose 方案阶段 0 验证通过后**，再按裁定报告的阶段 3 下线。~~

**实况**：`app/src/main/java/com/example/zhengdao/oc/LocalProxy.kt` 与 WebView 版 `ui/TaijiScreen.kt` **已随 commit `e9997ec` 删除**，阶段 3 下线已完成（证据：仓库中已无 `LocalProxy.kt`；现存 `app/src/main/java/com/example/zhengdao/ui/taiji/TaijiScreen.kt` 为重写后的 Compose 原生客户端）。本节的"必须等到阶段 0 验证通过再删"已不再是有效约束。

---

## 7. 一句话回答"为什么 Web UI 这么难用"

**因为它在你的架构里承担了一个它不该承担的角色**——本该由客户端网络层（OkHttp Interceptor）做的鉴权注入，被迫由一个自建的字节级 HTTP 代理来做，而代价是"每请求新连接 + 逐字节解析 + 禁 keep-alive + SSE 占线程"。**WebView 只是那个被架在上面的受害者。**

Compose 直连之后，WebView 和这个代理一起退休，问题从根上消失。

---

*本诊断基于 `LocalProxy.kt` 代码逐行核对，未做任何代码修改。所列缺陷均可复核。*

> ⚠️ 更正（2026-10-07）：上句中的 `LocalProxy.kt` 已随 commit `e9997ec` 删除；复核方式改为 `git show e9997ec^:app/src/main/java/com/example/zhengdao/oc/LocalProxy.kt`。本文其余内容不变。