# 太极 PoC #3（App 内鉴权闭环）探测发现

> **性质**：Zcode 于 2026-10-06 晚在真机（Honor Magic 5 Pro / Android 16 / MagicOS）上的
> 独立探测记录，**交接给 Compose UI agent** 供拼接时核对。探测使用当前已提交代码
> （e882050 构建的 WebView 版太极 + `OcManager.startServe`），未涉及 Compose 新代码。
> **状态：探测在 401 复现后按用户指示暂停**，未做修复——修复方案留待拼接时定。

---

## 发现 1：这版 opencode serve 的 API 在 `/api/*` 前缀下

**实测（adb forward tcp:18080 → 设备 14000，curl 直打）**：

| 请求 | 结果 |
|---|---|
| `GET /global/health`（无/有 Basic auth） | 200，**text/html**（SPA catch-all，非 API） |
| `GET /session`（无/有 Basic auth） | 200，**text/html**（SPA catch-all，非 API） |
| `GET /doc` | 200，text/html（同上） |
| `GET /api/session`（无 auth） | **401** + `WWW-Authenticate: Basic realm="Secure Area"`（application/json） |
| `GET /api/session`（带 serve.log 密码） | **401**（见发现 2） |

**结论**：2.0.22 构建的 serve 把无前缀路径全部喂给 Web UI；真正的 REST API 挂在
`/api/*` 下。**设计文档（`证道-太极Tab-Compose设计方案.md` §OcClient 契约）里的
`GET /global/health`、`GET /session` 等端点表与实测不符**——拼接前请逐条核对
（阶段 0-1 的验收判据"`GET /global/health` 返回 200"在字面上会被 SPA catch-all
**假通过**：200 但 content-type 是 html。判据应加 `content-type: application/json`）。

> 旁证：WebView 版太极（LocalProxy 注入 auth 头）能正常工作，其转发目标是
> serve 原端口——说明 serve 内部路由与代理无关，是这版 serve 的真实路由形态。

## 发现 2：401 情形已复现，根因候选 = `parseServePassword` 取错密码行

**证据链**：

1. `files/oc/serve.log` 尾部（tail -5 实录）：

   ```
   server password cQ4a4UMo…      ← 旧一场
   server listening on http://127.0.0.1:14000
   server password N65iwlSG…      ← 旧一场
   server listening on http://127.0.0.1:14000
   server password PduH4VBR…      ← 最后一场：**没有对应的 "server listening" 行**
   ```

2. `OcManager.parseServePassword` 取 `lastOrNull { it.contains("server password") }`
   → 拿到 **PduH4…**。
3. 带该密码请求 `/api/*` → **401**。

**推断**：最后一场 serve 启动时打了密码、但**端口被存活进程占用没绑上**
（启动失败或孤儿进程场景），真正在监听的进程用的是前一场（N65iw…）的密码。
`lastOrNull` 在"最后一次启动失败"时必然取错。这正对应 PoC #3 预设的
「停在启动失败、错误码 401 → startServe 没解析到密码」分支。

**修复方向（拼接时定，未实施）**：
- 取密码后用一次 `/api/*` 探测验活，401 则回溯前一场密码；
- 或启动前检测端口占用并杀孤儿 / 复用孤儿；
- 或 serve 启动改为「每场独立 log 文件」，密码与监听配对读取。

## 发现 3：静态外壳公开、API 才鉴权（401 判定要打对位置）

无前缀的 SPA 资源（`/`、`/assets/*`、catch-all HTML）**不带鉴权即可 200**，
`/api/*` 才要求 Basic auth。两个推论：

1. Compose UI 的「已连接」判定**必须打 `/api/*` 端点**（打 SPA 路径永远 200，
   会把未鉴权误判成已连接）；
2. 「顶栏显示已连接 / 启动失败 + 错误码」的错误码应读取 `/api/*` 响应——
   WebView 版本次的表现即「外壳加载成功（logo 出现）但 API 全 401 → 页面空白」，
   Compose 版按设计文档的「失败可见」原则应直接把 401 亮出来。

---

## 环境快照（复现用）

- APK：e882050（debug，0.9.1），手机上现装版本
- serve：太极 Tab「启动 OpenCode」拉起，`files/oc/serve.log` 可经
  `adb shell run-as com.example.zhengdao cat files/oc/serve.log` 读取
- 端口转发：`adb forward tcp:18080 tcp:14000` 后本机 curl 即可复打
- 密码解析实现：`OcManager.kt` `parseServePassword`（`lastOrNull` 语义）

*本记录只含实测与证据链，未改任何代码；修复决策归 Compose 拼接批次。*

---

## 追记（2026-10-06 晚 · 二轮排查）：「无法创建会话」的真根因——`data` 信封

**前置**：调试污染（多场 serve 的密码日志混写）已按裁定清理；clean 路径下 serve 生产
启动、密码配对（listening 在前/在后均兼容）全部正常——SSE `/api/event` 200 实证。

**真根因**（PC 端经 adb forward 直打实测）：

- `POST /api/session`（Basic auth + `{}`）→ **200**，但响应体是 **`{"data":{...}}`
  信封**，`id` 在 `data.id`：
  `{"data":{"id":"ses_…","projectID":…,"location":{"directory":"/storage/emulated/0/Download/男性"}}}`
- 分支 `createSession` 的解析 `JSONObject(it).optString("id")` 取顶层 `id` → **空串**
  → `takeIf { isNotEmpty() }` → 静默 null → UI「无法创建会话」。
  `runCatching` 无异常可捕 → `onFailure` 不触发 → **零日志**（排查时最迷惑的一点）。

**信封是全局形态，不止一处**（均带 auth 实测）：

| 端点 | 真实响应形态 |
|---|---|
| `POST /api/session` | `{"data":{…session}}` |
| `GET /api/session` | `{"data":[…sessions]}`（**信封数组**，不是裸数组） |
| `GET /api/session/{id}/message` | `{"data":[…],"cursor":{"previous":…,"next":…}}` |
| `GET /api/config` | 裸数组（**例外**，无信封） |

**影响面**：`fetchJsonArray`（`JSONArray(text)`）对信封数组会直接抛
JSONException——createSession 修好后，`loadAll` 拉消息必然跟着炸（这次没炸只是因为
createSession 先挡住了）。**建议统一剥信封**：`JSONObject(text).opt("data") ?: 解析原
文`，message 端点另取 `cursor`。SSE 事件是否信封化未验，接消息流时核。

**附带验证（好消息）**：`DELETE /api/session/{id}` 带 auth → **204 No Content**——
「会话历史删除」功能的服务端路径可用。测试中创建并删除了一个空会话；设备上另有一个
更早的既有会话（`ses_eef00a76…`，非本次产生），未动。

**Android 端密码行顺序**：与正文一致，clean 路径 `listening` 在前、`password` 在后，
分支 4cc808f 的双向兼容已实测生效（SSE 鉴权成功）。

---

## 追记二（2026-10-06 晚 · 三轮）：信封修复验证通过，剩 SSE 断连循环

**信封修复（d87fa67）实测通过**：
- clean 4cc808f+ 信封修复装机后，「启动失败」消失，UI 进入会话界面（输入框就位）。
- 服务端会话列表实测 7 个会话，最新 `ses_eeef55c82ffe` 创建于 19:47:52（本次运行，
  UI 标题「ses_eeef」即其前缀）——**createSession 全链路成功**。
- 注意：这台 serve 实例的请求日志**盲**（`files/oc/xdg/.../opencode.log` 停在 19:47:52
  探活行，连外部 curl 的请求都不落盘；bun 日志缓冲或写路径异常，另行立册）。
  **不能再用"服务端日志没有"推断"请求没到达"**。

**剩余问题：SSE「已断开，正在重连」无限循环（实测第 10 次）**：
- UI 状态机工作正常（失败可见 ✓），但 SSE 每次连接后被断开、自动重连永不到达"已连接"。
- 关键排除证据：`SseClient` 的 `catch(Exception) → ocLog("SSE 连接异常…")` **从未触发**
  （RunLog 无任何 SSE 条目）——断开走的不是普通异常路径。
- 头号嫌疑：`SseClient.kt:122` 的 `catch (e: CancellationException)` **静默吞掉**——
  若重试循环带 `withTimeout` 或取消旧协程，超时/取消都会无痕迹死亡。建议给该分支
  加日志后再复测一次，断连原因即现形。
- 次要嫌疑：客户端 watchdog（连接成功但无事件即判断开）与 opencode 服务端的 SSE
  fiber InterruptError（taiji 日志 11:10/11:12 两段 span≈45-50s 的中断模式吻合）。
- 附带观察：每次进太极都会新建一个会话（服务端已累积 7 个）——单会话模型下
  可能需要"复用最近会话"策略，归产品裁量。
