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
