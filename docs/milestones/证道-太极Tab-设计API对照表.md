# 太极 Tab：设计 ↔ 真实 API 对照表

> **取证方式**：荣耀 Magic 5 Pro（AD3J023824001723）真机，`adb forward tcp:14000` +
> 手动拉起 `opencode serve`（2.0.22，与 `OcManager.VERSION` 一致）+
> `GET /api/openapi.json`（256KB，OpenAPI 3.1.0）+ 真实 SSE 事件流抓取 + 二进制字符串取证。
> 取证时间 2026-10-06。**本文所有结论均为实测，非文档推断。**

---

## 零、先说结论：骨架里有 6 处是"跑在幻想的数据上"

| # | 骨架写法 | 真实情况 | 后果 |
|---|---|---|---|
| 1 | 端点 `/session`、`/event`、`/session/{id}/prompt_async` | 全部是 **`/api/`** 前缀；`prompt_async` 不存在 | 所有请求 404 |
| 2 | `x-opencode-directory` header | spec 里**没有这个 header**；参数是 query `directory` / `location` | 目录绑定完全失效 |
| 3 | SSE 解析优先读 `event:` 行 | 实测**没有 `event:` 行**，事件名在 data 的 `type` 字段 | 事件全部落进 Unknown |
| 4 | 归约按 `part.id` 定位 delta | 真实 delta 事件**没有 partId**，用 `assistantMessageID + ordinal` | 流式文本无处落地 |
| 5 | 权限回复 `{"response":"allow"}` | 真实是 `{"decision":"once"\|"always"\|"reject"}` | 授权提交必然失败 |
| 6 | ~~TodoPanel~~ | ~~**2.0.22 根本没有 todo**（见 §3）~~ → **本条结论作废，见 §3 更正**：`todo.updated` 确实存在且已实现 | ~~元素无数据源，须删~~ → **保留 TodoPanel** |

---

## 一、鉴权与连接（实测）

| 验证项 | 实测结果 | 结论 |
|---|---|---|
| `GET /`（根路径） | `200` **text/html** | SPA catch-all 外壳 |
| `GET /global/health` | `200` **text/html** | **不是 API**——设计文档契约里的这个端点实测不存在，会被外壳假通过 |
| `GET /session` | `200` **text/html** | 同上 |
| `GET /api/session` 无鉴权 | `401` + `WWW-Authenticate: Basic realm="Secure Area"`（application/json） | **`/api/*` 一律要鉴权** |
| `GET /api/session` 带正确密码 | `200` application/json | ✅ |
| 判活 /「已连接」判定 | 必须打 `/api/*` **并校验 content-type** | 打 SPA 路径恒 200 → 假通过 |

> **⚠️ 更正本表初版**：初版记"`/api/session` 无鉴权 200"是**误记**（当时打的是 `/session`，没有 `/api` 前缀）。
> 真实结论：**静态外壳公开、`/api/*` 才鉴权**。统一注入 AuthInterceptor 依然安全（带鉴权访问所有端点均通过）。
> 来源：PoC #3（zcode 探测 + 本次复核），见 `docs/太极-PoC3-鉴权发现.md`。

密码来源：serve 启动日志 `server password <pw>`（与 `OcManager.parseServePassword` 一致）。

**⚠️ 待验证**：App 内由 `OcManager.startServe` 拉起时密码是否同样解析得到（本次是手动拉起）。
PoC：装 debug 包 → 进太极 Tab → 若顶栏显示"已连接"即通过；停在"启动失败"看错误码是否 401。

---

## 二、SSE 事件（真实抓取）

**传输形态**：`GET /api/event`，`text/event-stream`。
**关键差异：没有 `event:` 行**，每条是：

```
data: {"id":"evt_...","type":"server.connected","data":{}}

: heartbeat
```

→ 解析必须读 `data` 里的 `type`（骨架的正则 fallback 恰好是对的，但优先级应反过来）。
`data` 是 `V2EventEncoded`（spec 中定义为 JSON 字符串，类型未展开），故**事件全集只能靠实测**。

### 实测到的完整事件表（两次真实会话：纯对话 + 工具调用）

| 事件 type | data 载荷 | UI 用途 |
|---|---|---|
| `server.connected` | `{}` | 连接就绪 |
| `session.created` | session 对象 | 会话列表刷新 |
| `project.updated` | — | — |
| `session.inbox.enqueued` / `.delivered` | — | 消息已投递 |
| `session.execution.started` | `{sessionID}` | **开始转圈** |
| `session.execution.succeeded` | `{sessionID}` | **结束转圈** |
| `session.instructions.updated` | — | — |
| `session.step.started` | `{sessionID, agent, model, assistantMessageID, started}` | 含**当前模型**，可显示 |
| `session.step.streamed` / `.ended` | — | — |
| `session.reasoning.started` | `{sessionID, assistantMessageID, ordinal}` | 思考块开始 |
| `session.reasoning.delta` | `{…, ordinal, delta}` | **思考流式追加** |
| `session.reasoning.ended` | `{…, ordinal, text}` | 思考定稿 |
| `session.text.started` | `{…, ordinal}` | 正文块开始 |
| `session.text.delta` | `{…, ordinal, delta}` | **正文流式追加** |
| `session.text.ended` | `{…, ordinal, text}` | 正文定稿（全量覆盖） |
| `session.tool.input.started` | `{…, id:"functions.shell:0", name:"shell"}` | 工具卡片建立，`name`=工具名 |
| `session.tool.input.ended` | `{…, id, text:"{\"command\":…}"}` | 入参（JSON 字符串） |
| `session.tool.called` | `{…, id, input, executed}` | 状态→执行中 |
| `session.tool.progress` | `{…, id, metadata}` | 进度（shellID 等） |
| `session.tool.success` | `{…, id, content:[{type,text}]}` | 状态→完成 + 结果 |
| `session.usage.updated` | `{sessionID, cost, tokens:{input,output,…}}` | **token / 费用统计** |
| `shell.created` / `shell.exited` | — | shell 工具专用 |
| `permission.asked` / `permission.replied` | 二进制确认存在（17/14 次命中），**载荷未实测到** | 权限抽屉 |

**尚未实测到的事件**（触发条件未满足，标⚠️待验证）：`permission.*` 载荷、`session.*error/failed`、
`session.compacted`、`tool` 失败态事件。

---

## 三、三个点名问题的答案

### 3.1 是否有独立的 todo / thinking / tool call 事件？

| 能力 | 结论 | 证据 |
|---|---|---|
| **todo** | ✅ **存在，保留 `TodoPanel`**（⚠️ 本节原结论为"不存在，必须删掉"，**已于 2026-10-07 更正**，详见下方更正块） | `oc/SseClient.kt:179` 解析事件名 `"todo.updated"`；DTO `oc/OcDto.kt:117`；归约 `oc/OcRepository.kt:892`、`oc/TaijiState.kt:69`；渲染 `ui/taiji/TaijiComponents.kt:749 fun TodoPanel`（`:336` 已接线） |
| **thinking** | ✅ 有独立事件流 | `session.reasoning.started / .delta / .ended`，且消息里持久化在 `content[].type=="reasoning"` |
| **tool call** | ✅ 有独立事件流 | `session.tool.input.started/ended`、`tool.called`、`tool.progress`、`tool.success` |

> ### ⚠️ 更正（2026-10-07）：todo 的结论是错的，是**过度推断**
>
> 原文断言「todo：❌ 不存在，必须删掉 TodoPanel」，并列了四条证据。**四条证据全部不成立**：
>
> | 原文证据 | 为什么不成立 |
> |---|---|
> | ① spec 中 schema 名含 `todo` 的一个都没有 | spec 是**精简过**的，事件名不等于 schema 名；`todo.updated` 是**事件类型字符串**，不体现在 schema 枚举里 |
> | ② 两次真实会话的事件流中无 todo 事件 | 那两次会话**本来就没有产生 todo**（没触发 TodoWrite），"没观测到"不等于"不存在" |
> | ③ 二进制中 `todo.updated`/`todo.list`/`session.todo`/`TodoList` 全部 0 命中 | grep 的目标字符串选错了（真实字面是事件类型常量，且分布在 Kotlin 侧解析代码而不是二进制字符串表）；实际 4 处命中见上表 |
> | ④ `Session.Message.Info` 的 anyOf 无 Todo 类型 | todo **不走 Message.Info**，走独立事件 `todo.updated`，所以这条从一开始问错了地方 |
>
> **反向佐证（同仓自相矛盾）**：`docs/milestones/证道-太极Tab-Compose设计方案.md:240` 写
> `is TodoUpdated -> s.copy(todos = ev.todos)`——同一批文档里，Compose 方案已经按"有 todo"在写归约了。
>
> **处置**：保留并渲染 `TodoPanel`。同族的其余 4 处错误结论（§0 表格第 6 行、§3.1「设计动作」、
> §四、§五）**已一并更正**——只改一处会让文档自相矛盾。
>
> **教训**：**"我没看到"要被当成"我没看到"，不能写成"不存在"。** 本条把两个样本的观测结果
> 直接升格成了协议事实，还据此排了删除动作（P0）。凡是靠"grep 0 命中 / 样本里没有"得出的否定结论，
> 落笔前必须再找一条**独立的**肯定性证据去证伪。

> **~~原设计动作~~（已作废）**：~~删 `TodoPanel` 与 `OcTodo`。若将来要"任务清单"，只能用 `session.step.*` 近似，语义不同，不建议。~~
> **现行设计动作**：保留 `TodoPanel`；`Todo` 数据来自独立事件 `todo.updated`（`oc/SseClient.kt:179` → `oc/TaijiState.kt:69` → `ui/taiji/TaijiComponents.kt:749`）。

### 3.2 `/api/permission/request` 载荷 & approve/deny 怎么传

```
GET /api/permission/request?location=…   →  {location, data: [Permission.Request]}

Permission.Request = {
  id, sessionID,
  action:    string,        // "bash" / "edit" …
  resources: string[],      // ★ 具体目标（命令 / 文件路径）—— 不能盲批的依据
  save:      string[],      // ★ 可"记住"的选项值
  metadata:  object,
  source:    Permission.Source,
  message:   string
}
```

**回复**（骨架写错了）：

```
POST /api/session/{sessionID}/permission/{requestID}/reply
body = { "decision": "once" | "always" | "reject", "message": string? }
required: decision
```

`Permission.Reply` 在 spec 里就是 `enum["once","always","reject"]`——**三态，不是布尔**。

补充：还有一个**主动判定**端点
`POST /api/session/{sessionID}/permission` body `{action, resources[], save[], metadata, source, agent}`
→ 实测返回 `{"data":{"id":"per_…","effect":"allow"}}`（规则直接放行时不产生挂起请求）。

### 3.3 `/api/permission/saved` 的语义：一次性还是永久

| 决策 | 语义 | 落点 |
|---|---|---|
| `once` | **一次性**，仅本次生效 | 不写 saved |
| `always` | **永久**，写入 saved 规则 | `GET /api/permission/saved` 可见 |
| `reject` | 拒绝 | — |

```
GET /api/permission/saved → { data: [PermissionSaved.Info] }
PermissionSaved.Info = { id, projectID, action, resource, time:{created,updated} }
DELETE /api/permission/saved/{id}        // 撤销某条永久规则
```

注意 saved 是**按 project 维度、单条 resource**，`Permission.Request.save[]` 列出本次可保存的候选值。
→ UI 的"记住这个选择"就是二选一：`once` / `always`，**不能再用布尔 `remember`**。

### 3.4 `/api/session/{id}/message` 是否含工具调用记录

✅ **含**。

```
GET /api/session/{sessionID}/message → SessionMessagesResponse
  = { data: [Session.Message.Info], cursor:{previous,next} }

Session.Message.Info = anyOf[
  User | Assistant | System | Skill | Shell |
  AgentSelected | ModelSelected | LocationSwitched | Synthetic | Compaction | Idle
]

Session.Message.Assistant = {
  id:"msg_…", type:"assistant", agent, model:Model.Ref,
  time:{created,streamed,completed},
  content: [ Text | Reasoning | Tool ],     // ★ 字段名是 content 不是 parts
  snapshot:{start,end,files[]}, finish
}

Session.Message.Assistant.Tool = {
  type:"tool", id, name, executed,
  state: ToolState.Streaming|Running|Completed|Error,
  time:{created,ran,completed}
}
ToolState.Completed = { status:"completed", input:{}, content:[Tool.Content], metadata:{} }
```

实测样本（含工具调用的那条）确认 `content[]` 里同时有 `reasoning` 与 `text`；`tool` 类型由 schema 保证。

---

## 四、设计 ↔ API 对照表

状态：**✅ 已确认**（本次真机实测）/ **⚠️ 待 PoC**（端点存在但未取到真实数据）

| UI 元素 | 数据来源 | 状态 | 若待验证，PoC 怎么做 |
|---|---|---|---|
| **连接状态（已连接/重连/断开）** | `server.connected` 事件；断线由 socket EOF 判定 | ✅ | — |
| **转圈（流式指示）** | `session.execution.started` → `session.execution.succeeded` | ✅ | — |
| **正文流式文本** | `session.text.delta` → 追加；`session.text.ended` → 定稿覆盖 | ✅ | — |
| **思考过程折叠块** | `session.reasoning.delta/ended`；历史从 `content[].type=="reasoning"` | ✅ | — |
| **工具卡片（名称/状态/入参/结果）** | `session.tool.input.started`（`name`）、`tool.called`、`tool.progress`、`tool.success`（`content[]`） | ✅ | — |
| **消息历史** | `GET /api/session/{id}/message` | ✅ | — |
| **发送消息** | `POST /api/session/{id}/prompt` body `{text, files[], agents[], skills[]}` | ✅ | — |
| **中断按钮**（新增） | `POST /api/session/{id}/interrupt`（无 body）→ 实测 `{"interrupted":false}` HTTP 200 | ✅ | PoC：流式输出中点一次，看是否返回 `interrupted:true` 并出现 `session.execution.*` 收尾 |
| **权限抽屉** | `permission.asked` 事件 或 轮询 `GET /api/permission/request` | ⚠️ 事件载荷未实测 | PoC：① 让 Agent 执行一次需批准的操作（如 `edit` 工作区外文件）；② 同时抓 SSE，确认 `permission.asked` 的 data 是否就是 `Permission.Request`；③ 若事件不可靠，退化为进入页面时轮询 `/api/permission/request` |
| **权限回复 once/always/reject** | `POST …/permission/{requestID}/reply` `{decision}` | ✅（schema+端点确认） | PoC：接上条，提交 `once` 后确认请求从 `/api/permission/request` 消失 |
| **"记住这个选择"** | `always` 写入 `/api/permission/saved` | ✅（语义确认） | PoC：提交 `always` 后 `GET /api/permission/saved` 应出现该条；再 `DELETE` 验证撤销 |
| **顶栏模型选择器**（新增） | `GET /api/model?location[directory]=<path>` → `[Model.Info]`；`POST /api/session/{id}/model` `{model:{id,providerID}}` | ✅ 列表取到真实数据 | PoC：切换模型后发一条 prompt，看 `session.step.started.model` 是否变成新模型 |
| **顶栏会话列表入口**（新增） | `GET /api/session?directory=<path>`；`POST /api/session`；`GET /api/session/active` | ✅ | PoC：建两个会话，确认列表返回且 `directory` 过滤生效 |
| **工具卡片的文件变更入口**（新增） | `GET /api/session/{id}/diff` → `[FileDiff.Info{file,patch,additions,deletions,status}]`；另有 `/api/vcs/status`、`/api/vcs/diff` | ⚠️ 本次 diff 返回空数组 | PoC：让 Agent 真的改一个文件，再取 diff，确认有条目且 `patch` 可直接渲染 |
| **token / 费用显示**（可选新增） | `session.usage.updated` `{cost, tokens}` | ✅ | — |
| **TodoPanel** | ✅ **存在**（`oc/SseClient.kt:179` / `oc/OcDto.kt:117` / `oc/TaijiState.kt:69` / `ui/taiji/TaijiComponents.kt:749`） | ~~❌ 不存在，删除~~ → **保留并渲染** | 已实现，无需 PoC。⚠️ 原"三重否定"证据不成立，见 §3.1 更正块 |
| **会话 id 显示** | `POST /api/session` 返回 `id`（`ses_` 前缀） | ✅ | — |
| **发送附件** | `POST prompt` 的 `files[]`（`{uri,name,description,mention}`） | ⚠️ 未实测 | PoC：阶段 2 再评估，本期不实现 |

---

## 五、骨架必须改的清单（按优先级）

| 优先级 | 改动 | 说明 |
|---|---|---|
| P0 | 所有端点加 `/api` 前缀；`prompt_async` → `/api/session/{id}/prompt` | 否则全部 404 |
| P0 | SSE 解析以 `data.type` 为准（无 `event:` 行），事件名全表替换 | 否则全落 Unknown |
| P0 | 归约键从 `part.id` 改为 `assistantMessageID + ordinal` | 否则流式无落地 |
| P0 | 权限回复改 `{decision:"once"\|"always"\|"reject"}` | 否则授权必失败 |
| ~~P0~~ **已撤销** | ~~删除 `TodoPanel` / `OcTodo`~~ | **撤销理由**：`todo.updated` 确实存在且已实现并接线，**数据源存在**。见 §3.1 更正块。**保留待办面板，不做删除。** |
| P1 | `x-opencode-directory` header → query `directory`（`/api/session`）或 `location[directory]`（其余） | 否则目录绑定失效 |
| P1 | 消息解析 `parts` → `content`；工具名 `tool` → `name`；工具结果 `state.content[]` | 字段名全错 |
| P1 | 模型选择器、会话列表入口、中断按钮、文件变更入口 四个新元素接入 | 用户要求，数据源均已确认 |
| P2 | `session.usage.updated` → token/cost 显示（可选） | 数据现成 |

---

## 六、PoC 进展

### PoC #3（App 内鉴权闭环）—— ✅ 已跑，结论：失败 + 根因已定位 + 已修

记录见 `docs/太极-PoC3-鉴权发现.md`（zcode 探测），本次做了复核验证。

- **现象**：App 拉起 serve 后打 `/api/*` 全部 **401**
- **根因**：`parseServePassword` 用 `lastOrNull { contains("server password") }` 取日志**最后一行**，
  而最后一场 serve 因端口被占用**没绑上端口**（无 listening 行），真正在监听的进程用的是更早一场的密码
- **复核证据**：日志末行 `PduH4…`（无 listening）→ **401**；当前真在监听那场的密码 → **200**；无鉴权 → 401
- **叠加剧因**：`serveRunning()` 打 `http://127.0.0.1:14000/` 根路径 → SPA catch-all 恒返回 200
  → **判活恒真** → 所有"已在运行就跳过启动"的分支全错

**已修（分支 `feat/taiji-compose-ui`，未合主线）**：

| 修复 | 改法 |
|---|---|
| `OcManager.serveRunning()` | 改打 `/api/session`，校验 `content-type: application/json`；401 也算存活（证明 serve 在监听且要鉴权） |
| `OcManager.parseServePassword()` | 改为「password 行后**有对应 listening 行**才有效」，取最后一个有效密码；拿不到返回 null |

⚠️ 这两处是 **WebView 版与 Compose 版共用**的，修完对现有可用路径同样生效——
**现有 WebView 版其实一直带这两个 bug**（判活恒真 + 可能取错密码），只是 LocalProxy 掩盖了部分表现。

### PoC #1（权限事件载荷）—— ✅ 已验证，权限链路完全打通

**触发方法（关键）**：agent `build` 的权限表里 `*` = `allow`，但
**`external_directory` = `ask`**。所以「让 Agent 写工作区内的文件」不会触发权限，
必须让它**写工作区外的路径**（本次用 `/data/local/tmp/`）。

**实测 `permission.asked` 载荷**（与 spec 的 `Permission.Request` 一致，实测无 `metadata`/`message`）：

```json
{"id":"per_110e9be7a0017q5zyOrs0q0w54","sessionID":"ses_…",
 "action":"external_directory",
 "resources":["/data/local/tmp/*"],
 "save":["/data/local/tmp/*"],
 "source":{"type":"tool","messageID":"msg_…","id":"functions.write:2"}}
```

| 验证项 | 结果 |
|---|---|
| 不响应会怎样 | ✅ 事件流停在 `permission.asked`，**没有任何后续事件** —— 卡死属实 |
| `GET /api/permission/request` 轮询 | ✅ 同时可见同一条（事件 + 轮询双通道） |
| `reply {"decision":"once"}` | ✅ 204，请求清空，`/api/permission/saved` **不变**（一次性） |
| `reply {"decision":"always"}` | ✅ 204，写入 `psv_…`：`{id, projectID, action, resource, time}`（永久，可 DELETE 撤销） |

→ 权限抽屉的数据源、三态语义、"记住这个选择"全部确认。

### PoC #2（diff 非空）—— ✅ 已验证，结论是**不能用**（数据源需换）

- Agent 确实用 `write` 工具创建了文件（`poc_write_test.txt`，9 字节，真机确认存在）
- 但 `GET /api/session/{id}/diff` → `[]`
- `GET /api/vcs` → `{"branch":{}}`，`GET /api/vcs/status` → `[]` → **当前工作区不是 git 仓库**

**结论：`/api/session/{id}/diff` 依赖 git 工作区；非 git 目录恒返回空数组。**
太极的工作区若是普通目录（如 `/workspace`），这个端点拿不到任何数据。

**设计调整**：工具卡片的"文件变更入口"**不要接 diff 端点**，改用已确认可用的
`session.tool.*` 事件——`tool.called.input.path` + `tool.success.content[]`
（本次实测 `input:{path,content}`、`content:[{type,text}]` 均取到真实值）。
若将来强制工作在 git 仓库下，再考虑接 diff 做补丁视图。

---

## 七、附：本次取证的可复现命令

```bash
# 1. 转发端口
adb forward tcp:14000 tcp:14000

# 2. 手动拉起 serve（与 OcManager.startServe 同一套环境）
adb shell "run-as com.example.zhengdao sh -c 'HOME=/data/data/com.example.zhengdao/files/oc/home \
  XDG_DATA_HOME=<...>/files/taiji/data XDG_CACHE_HOME=<...>/files/taiji/cache \
  XDG_CONFIG_HOME=<...>/files/taiji/config XDG_STATE_HOME=<...>/files/taiji/state \
  PATH=/system/bin nohup <...>/files/oc/usr/bin/opencode serve --port=14000 </dev/null \
  >/data/data/com.example.zhengdao/files/oc/serve_manual.log 2>&1 &'"

# 3. 取密码 + 拉 spec
PW=$(adb shell run-as com.example.zhengdao cat files/oc/serve_manual.log | awk '/server password/{print $3}')
curl -u "opencode:$PW" http://127.0.0.1:14000/openapi.json -o openapi.json

# 4. 抓事件（后台）+ 触发交互
curl -s -N -u "opencode:$PW" http://127.0.0.1:14000/api/event > sse.log &
curl -u "opencode:$PW" -X POST -H "Content-Type: application/json" -d '{"text":"..."}' \
  http://127.0.0.1:14000/api/session/<sid>/prompt

# 5. 模型列表（location 必须是对象形式）
curl -u "opencode:$PW" "http://127.0.0.1:14000/api/model?location%5Bdirectory%5D=/data/user/0/com.example.zhengdao"
```
