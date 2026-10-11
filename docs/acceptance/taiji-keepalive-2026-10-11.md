# 太极连接保活 —— 切 Tab / 返回不再重连（2026-10-11）

> **用户报（原话）**：「能不能保持太极页的网络在APP打开时一直连着的啊？每次重进去都会重新连接，虽然快，但是感觉不太好。」
>
> 分支 `fix/taiji-keepalive`；基线 `c73bcd6`（main）；真机 `AD3J023824001723`（Android 16 / arm64-v8a，debug 包 `com.example.zhengdao`）。

## 一、原先为什么"每进一次连一次"

三层叠在一起，缺一层都还能忍，三层凑齐就成了"每进一次重连一次"：

| 层 | 原先实现 | 后果 |
|---|---|---|
| 网络实例的寿命 | `app/src/main/java/com/example/zhengdao/ui/taiji/TaijiScreen.kt` 里 `remember { OcClient(...); OcRepository(...) }` | 切 Tab 即销毁 —— `app/src/main/java/com/example/zhengdao/MainActivity.kt:654` 的 `when (tab)` 让**切走的页面直接退出 Composition** |
| 轮询 / SSE 任务的寿命 | 跑在 `rememberCoroutineScope()` 上 | 跟着页面一起被取消（`OcRepository.open()` 里 `startPolling(scope,id)` / `connectSse(scope,id)` 用的就是这个 scope） |
| 重进页面的动作 | `LaunchedEffect` 每次调 `repo.open(OcRuntime.scope, savedSession)` | 无条件重新握手 + 重新全量拉取 |

## 二、改法（4 处 + 1 个新单测）

1. **新增 `app/src/main/java/com/example/zhengdao/oc/OcConnectionPolicy.kt`** —— 把"这次还要不要重开连接"抽成**纯函数** `shouldReopenConnection(requested, current, pollAlive, sseAlive, isFailed)`，判据按顺序先命中先返回：

   | # | 判据 | 结果 |
   |---|---|---|
   | 1 | 停在 `TaijiPhase.Failed` | 重开（`FailedPane` 的「重试」靠这条） |
   | 2 | 当前还没有会话 id（空 / null） | 开 |
   | 3 | 轮询或 SSE 任一没在跑 | 重开（真断线，或进程重建后 Job 已死） |
   | 4 | 明确要求切到**别的**会话 | 开 |
   | 5 | 其余（同会话 + 两条 Job 都活） | **不重开，直接复用** |

   抽成纯函数是为了能在 JVM 上把组合钉死 —— Job 的存活与否没法在真机上反复复现（同一套教训见 `TaijiSessionGrouping` / `MessageCopy` / `AgentInstallPrep`）。

2. **`app/src/main/java/com/example/zhengdao/oc/OcRepository.kt` 的 `open()` 顶部加守卫**：命中"复用"分支时记一行 `ocLog("open 复用现有连接：会话 …（切 Tab / 返回不重连）")` 后直接 `return`，不碰 `latestSessionId()` / `loadAll()` / `startPolling()` / `connectSse()`。

3. **新增 `app/src/main/java/com/example/zhengdao/oc/OcRuntime.kt`** —— 进程级持有者：`val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)` + `repository()`（双检锁懒建）+ `shutdown()`（关闭并丢引用）。KDoc 记了三条边界：**不自动起 serve**（`opencode serve` 仍由首次进太极时 `OcManager.startServe()` 拉起）、单例只持一个 repository（与单会话模型一致）、旁路 REST 任务（如知识库摘要）仍自己 new 一套。
   - ⚠️ 它本来叫 `OcSession`，和 `oc/OcDto.kt:26` 的 `data class OcSession`（服务端会话 DTO）重名 —— 实测直接把 DTO 顶掉，编译报 `Unresolved reference 'id' / 'title' / 'timeUpdated' / 'timeCreated'`（`OcRepository.kt:709/728`）与 `:861:12 Unresolved reference 'OcSession'`。**改名 `OcRuntime`** 后通过。

4. **`app/src/main/java/com/example/zhengdao/ui/taiji/TaijiScreen.kt` 接线**：实例改从 `OcRuntime.repository()` 取；`open` / `switchSession` / `startNewSession` 全部改用 `OcRuntime.scope`（进程级，**不能**再用页面自己的 `scope`）；`onCloseSession`（「关闭太极并退出」）里 `repo.close(); OcRuntime.shutdown()` 成对调用。

5. **新增单测** `app/src/test/java/com/example/zhengdao/oc/OcConnectionPolicyTest.kt`（10 条：同会话双活=false、`requested=null` 复用、切别的会话=true、点当前条目=false、轮询停=true、SSE 停=true、两 Job 都不在=true、失败态=true、无会话 id=true、空串 id=true）。

## 三、真机验收（证据 = `logs/zhengdao-log.txt`，App 自己写的运行日志）

*截图刻意不入库*：太极页的截图必然带上用户的会话内容（消息与模型回复），而本仓库的验收页是要留在版本库里的 —— 这条改动的决定性证据本来就是**日志行**，比截图更硬。

**改前**（旧包，`zhengdao-log.20261011-112828.txt`，11:11:50 每次进太极）：

```
[10-11 11:11:50] 太极: 复用服务端最近会话 id=ses_ed96dd271ffenwH9qf3udxBlgR
[10-11 11:11:50] 太极: 全量加载 1 条消息
[10-11 11:11:50] 太极: SSE 信号通道就绪（HTTP 200）；数据以 REST 轮询为准
```

→ 每次进页面都重新挑会话、重新全量拉、重新连 SSE。

**改后**（新包，`zhengdao-log.txt`；① 先进太极连上 → ② 切到「终端」Tab（`TerminalActivity`）→ ③ 左缘右滑退回 → ④ 再进太极）：

```
[10-11 11:32:52] 太极: 未知 SSE 事件 type=plugin.updated，已忽略但保留原文（同类后续仅每 100 次汇总）
[10-11 11:33:20] 太极: session.step.started model=opencode/mimo-v2.6-flash-free
[10-11 11:33:22] 太极: 未知 SSE 事件 type=session.execution.succeeded，已忽略但保留原文（同类后续仅每 100 次汇总）
[10-11 11:33:26] 太极: open 复用现有连接：会话 ses_ed96dd271ffenwH9qf3udxBlgR（切 Tab / 返回不重连）
```

判读：

- 第 4 行是**重新进太极**时打的 —— 走的是"复用"分支，**没有** `复用服务端最近会话` / `全量加载` / `SSE 信号通道就绪`，且会话 id 与切走前**完全一致**（`ses_ed96dd271ffenwH9qf3udxBlgR`）。
- 11:32:52 与 11:33:20-22 的 SSE 行落在**切走期间**（切 Tab 在 ~11:33:0x、回到太极在 ~11:33:15）—— 连接在页面不在的时候**照样活着**、事件照收（连"会话正在跑"都收到了）。这正是用户要的"一直连着"。

## 四、未验 / 边界（写清理由，不糊过去）

| 项 | 状态 | 理由 |
|---|---|---|
| **切到另一个会话仍会重连** | 未做真机复现 | 抽屉里当前只有一个会话；造一个会话＝往用户的服务端会话列表里塞东西。判据由 `OcConnectionPolicyTest` 的第 3 条钉住（`requested != current` → true） |
| **进程被杀后重开** | 设计上仍重连一次 | 进程都没了，连接没法活；`OcRuntime` 全部重建。这是**有意**的边界，不是缺陷 |
| 「关闭太极并退出」后再进 | 未单独复现 | 代码路径明确（`repo.close()` + `OcRuntime.shutdown()` 把单例置 null），且与"失败态重开"共用同一套判据 |

## 五、构建与单测

- `JAVA_HOME=D:\Program Files\Android\Android Studio\jbr`（本机 java 不在 PATH）
- `gradlew.bat --offline :app:testDebugUnitTest :app:assembleDebug --console=plain` → **BUILD SUCCESSFUL in 24s**（含 `OcConnectionPolicyTest` 10 条）
- 真机安装：`adb install -r app/build/outputs/apk/debug/zhengdao-2.0.12-debug.apk` → `Success`

## 六、现场附带发现（已另记 ERRATA E-093）

`adb install -r` 换包后**第一次**进太极，真机报过一次：

```
[10-11 11:30:44] 太极: 创建会话失败：HTTP 401: {"_tag":"UnauthorizedError","message":"Authentication required"}
```

退出再进一次就好了。定位到 `OcManager.startServe()` 的**孤儿 serve 分支**：这条路上原本**没有**"等 password 落盘"的轮询（只有"新起一场 serve"那条路有），而 serve 的日志顺序是 `server listening` 在前、`server password` 在后 —— 恰好卡在两次落盘之间解析就会拿到 `null`，客户端于是不带 `Authorization`，`/api/*` 全程 401。已补一段**有界等待**（最多 3 秒 / 每 150ms 重解析一次），仍拿不到才照旧返回 null。**这条竞态是否就是本次 401 的唯一成因未实测**（另一种可能是取到过期场次的密码），但"孤儿分支缺轮询"是实打实的缺口。
