# 勘误记录（ERRATA）

本文件记录项目历史中关于第三方组件、架构前提或实现方式的**已更正表述**。
保留历史 commit 原样，仅在此追加勘误，避免后来者被过时描述误导。

---

## E-001 · 2026-10-05 · `5a0fd2e` 中 Termux 终端组件的许可证表述有误

**受影响提交**
`5a0fd2e feat: 终端架构升级方案 A——聚合 Termux terminal-emulator+terminal-view（v0.119.0-beta.3，GPL-3.0 源码级聚合，用户钉版本）……`

**原表述（有误）**
> 聚合 Termux `terminal-emulator` + `terminal-view`（**GPL-3.0** 源码级聚合）

**更正为**
> 聚合 Termux `terminal-emulator` + `terminal-view`（**Apache-2.0** 源码级聚合）

**依据**
上游 `termux/termux-app` 仓库根 `LICENSE.md` 原文：

```
The `termux/termux-app` repository is released under GPLv3 only license.

### Exceptions

- [Terminal Emulator for Android](https://github.com/jackpal/Android-Terminal-Emulator)
  code is used which is released under [Apache 2.0](https://www.apache.org/licenses/LICENSE-2.0)
  license. Check `terminal-view` and `terminal-emulator` libraries.
- Check `termux-shared/LICENSE.md` for `termux-shared` library related exceptions.
```

即：**仓库整体为 GPLv3 only，但 `terminal-view` 与 `terminal-emulator` 两个库属于明确列出的例外，为 Apache-2.0**（源自 jackpal/Android-Terminal-Emulator）。
只有 `termux-app` **主应用本体**才是 GPL-3.0，而本项目**并未聚合主应用**。

**为什么必须更正**
- GPL-3.0 会触发**传染**：若误判，会让人以为整个 App 必须开放第一方源码。
- Apache-2.0 **不会**传染：第一方 Kotlin/C 代码可合法闭源，义务仅为保留第三方 LICENSE 与 NOTICE，并履行书面要约（见 `PROVENANCE.md`）。
- 这条直接决定"核心代码能否闭源"，属于不可出错项。

**不影响的部分**
- proot（Termux fork 发行版）**确为** GPL-2.0-or-later，且必须以**独立可执行文件**方式 `exec` 调用——该结论不变（见 `PROVENANCE.md` 红线一）。
- targetSdk 28 钉死、单会话模型等架构前提——均不受影响。

---

## E-002 · 2026-10-05 · proot 分发方式：jniLibs 方案已废弃

**曾有表述**
> proot 二进制改名 `libproot.so` 放入 `jniLibs/arm64-v8a/`，运行时从 `nativeLibraryDir` 调用。

**现行为（以代码实现为准）**
> proot 用 **Termux 官方发行二进制**（proot **5.1.107.96** aarch64 官方构建产物），
> 以 **assets 内置**（`app/src/main/assets/proot/`）分发，首启释放到 `files/termux-proot/`
> 并补执行位，随后 **execve 执行**；每个文件带 SHA256 固定清单校验。

**为什么可行**
targetSdk 28 下 App 的 `files/` 目录**允许 exec**——这正是 targetSdk 28 的意义所在。
**若将来 targetSdk 抬高，这条路径会失效**，故 targetSdk 28 是硬前提。

**历史方案为何废弃**
① 自编译上游 proot 缺少 Android 适配，在 App 域内加载 guest 时**静默退出 255**（本机实测 2026-10-04），
而带 Android 补丁的 Termux 官方发行二进制正常；
② `.so` 命名容易被后来者误当作动态库 `loadLibrary`，是 GPL 传染隐患。

**红线不变**
**无论叫什么名字、放在哪个目录，proot 都只能 `exec`，绝不能 `dlopen` / `loadLibrary`。**
详见 `PROVENANCE.md` 第 1 节红线一。

---

## E-003 · 2026-10-05 · 终端滚动手势曾经完全失效（已修）

**现象**：终端里单指上下滑动没有反应。

**根因**：tmux 采用「定位光标 + 重绘整屏」的方式输出，不产生行滚动，
导致本地回滚缓冲恒为空（实测 `getActiveTranscriptRows() == 0`）；
而上游 `TerminalView.onScroll` 只在「事件来自鼠标源」时才转发滚轮事件，
**手机上触摸永远不是鼠标源** → 滑动被彻底丢弃。

**修法（两处配合）**
1. `TerminalView.onScroll`：`isMouseTrackingActive()` 时，把触摸滑动按行高换算成滚轮事件转发给应用。
2. `ProotLauncher`：预置 `~/.tmux.conf` 写 `set -g mouse on`（tmux 不开 mouse 收不到滚轮）。

**验收**：上滑后内容由 `76-100` 变为 `18-43`，tmux 状态栏出现 `[58/78]` 位置指示。

---

## E-004 · 2026-10-05 · hermes uv 包装器生成出字面 `$\@`（安装自检失败，已修）

**现象**：真机安装 hermes 报 `✗ pinned uv staged but does not run on this host`；
`bash -x` 跟踪显示包装器末行是 `exec "$R" "$\@"`——真 uv 收到字面量参数
`$\@`，报 `unrecognized subcommand` 退出非零。

**根因**：包装器脚本文本当时嵌在 **bash 双引号包裹的 python 内联代码**里生成：
bash 把 `\"`→`"`、`\$`→`$` 都正确转换了，但 `\@` 不是 bash 的合法转义，
**反斜杠原样保留**，`"$@"` 就成了 `"$\@"`。且首验时只 `head -8` 对比开头，
没查到尾部损坏。

**修法**：
1. 脚本内容先写成本地文件（`build/hermes-uv-wrapper.sh`），再从**文件字节**做
   base64 编码嵌入 Kotlin——彻底绕开 shell 转义层；
2. 注入逻辑改为每次**无条件重写**包装器（自愈：历史坏版本与新逻辑都被覆盖）。

**教训**：跨多层字符串转义（bash → python → shell 脚本）时每一层都要验证；
凡走 base64 投递的内容，必须解码后与原文**全文比对**，不能抽查开头。

---

## E-005 · 2026-10-06 · "传统存储视图已死"结论错误——run-as 探针不能代表 App 真身（重大勘误）

**现象与反转**：10-05 诊断（commit d414dca）断言"安卓16 上 targetSdk 28 的传统
存储视图已失效，/sdcard 读写全拒，SAF 是唯一通路"，并据此建了 SAF 镜像同步。
10-06 凌晨用户在 guest 真身直接测 `/sdcard`：**列目录、读、写全部成功**，且文件
在手机文件管理器可见。原结论被证伪。

**根因（方法论错误）**：整套"全拒"证据来自 `run-as` 宿主侧探针。`run-as sh`
的 SELinux 域是 `u:r:runas_app:...`（`id` 输出可见），**不是** App 真身运行时的
`untrusted_app` 域——FUSE/MediaProvider 对 runas_app 域拒绝共享存储访问，与
App 实际权限状态无关。用 run-as 当"App 身份"测存储，测出来的是 SELinux 对
调试域的策略，不是产品行为。MANAGE appops 强开后的探针"仍拒"同样无效。

**真实机制（修正后）**：
- **写** /sdcard：一直可用（WRITE 运行时授权就位；此前无人从 guest 测过，
  Download/证道 为空只是因为 hermes 把文件写去了 /workspace）
- **读** /sdcard：10-05 确实失败——根因是 manifest `READ_EXTERNAL_STORAGE`
  带 `maxSdkVersion=32` 帽子，Android 13+ 上 READ 永远无法持有 → hermes
  "只能写不能读"是字面事实
- **修复** = d414dca 中的帽子摘除 + READ 运行时授权（该 commit 的权限修复部分
  正确且是真正的功臣；同 commit 里"传统存储视图已死"的诊断叙事错误）
- MANAGE_EXTERNAL_STORAGE 与本问题无关（appops 强开与否，真身读写都通）

**教训**：
1. **存储类探针禁止用 run-as 结论替代真身验证**——SELinux 域不同，行为可能
   完全不同；要么在真机 UI/终端里以真身实测，要么明确标注"runas 域结果，
   仅作线索不作结论"。
2. **推断链不要跨过未实测的一环**：guest 侧 /sdcard 从头到尾没直接测过，
   "全拒"是从 run-as 外推的——恰好外推错了。
3. 用户真机一分钟的裸测，胜过一小时基于间接探针的推理链。

**现状**：/sdcard 直连可用（真身验证：readdir/读/写 ✓，文件管理器可见 ✓）；
SAF 镜像同步降级为**备用方案**（个别 ROM 兜底），代码保留、设置页标注"备用"。

---

### E-005 修订（2026-10-06 晚）· 存储产品决策反转

**背景**：同日 E-005 定案后，用户在 P1-P8 执行清单中决定调整存储产品策略，
要求把 `MANAGE_EXTERNAL_STORAGE` 升为正式引导主路径，并在跑通完整链路后删除 SAF 镜像同步。

**新决策（用户 2026-10-06 晚，P1）**：
1. **`MANAGE_EXTERNAL_STORAGE` 升为主路径**：首启 / 存储检测失败时主动引导用户到
   `ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION`（带包名），失败回退
   `ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION`；未授权不阻塞，降级私有工作区。
2. **删除 SAF 镜像同步（PhoneMirror 引擎 + FD 代理）**：跑通 MANAGE + `--bind` 完整链路后，
   SAF 镜像同步与 FD 代理全部作废、代码移除（P1.5）。

**与 E-005 技术结论的关系（务必区分，不得误记）**：
- E-005 的**方法论教训不变**：`run-as` 探针不能替代 App 真身验证（SELinux 域不同）。
- E-005 的技术事实"`MANAGE_EXTERNAL_STORAGE` 与基础 `/sdcard` 访问无关（真身读写都通）"
  **仍然成立**——MANAGE 升主路径是**产品决策**（为获取更广存储访问面、统一授权入口），
  **不是修复"存储不通"的 bug**。基础 `/sdcard` 通路本就可用，靠的是
  READ 帽子摘除（`d414dca`）+ READ/WRITE 运行时授权 + `--bind`。
- **已告知并接受的代价**：对非技术用户弹出"允许访问所有文件"授权页；删除 SAF 后
  失去个别 ROM 的兜底（E-005 原保留的 ROM 兜底不再存在）。

**代码影响**：
- `app/src/main/AndroidManifest.xml:15`：`MANAGE_EXTERNAL_STORAGE` 已声明，升为主路径。
- `app/src/main/java/.../terminal/ProotLauncher.kt:373`：`--bind /sdcard:/sdcard` 已在，主路径不变。
- `app/src/main/java/.../mirror/PhoneMirror.kt`：删除（SAF 引擎作废，P1.5）。
- `app/src/main/java/.../ui/SettingsScreen.kt`：SAF 镜像入口删除；MANAGE 入口升为显式引导。
- `AgentInstaller.kt` / FD 代理：移除（P1.5）。

> ✅ **P1.5 执行完毕（2026-10-06 晚）**：删除前 P1 完整验收通过（guest 内
> `/sdcard/Download` 写读 + 宿主复核三方一致）→ PhoneMirror 引擎、设置页
> SAF 镜像入口、冷启动自动同步、`/mnt/phone` bind 与人设行、documentfile
> 依赖、设备 prefs 残留键全部移除 → 删后防回归四项通过（启动/设置页布局/
> 终端 /sdcard 读写/本地安装包路径零改动）。`TerminalActivity` 的
> ACTION_OPEN_DOCUMENT（手动选 rootfs 安装包）为独立用途，**保留不在本范围**。

**修订纪律**：后续任何人改存储相关代码，仍以本 ERRATA 的"技术事实"为准；
产品决策（MANAGE 主路径 / 删 SAF）可随用户新指令再调整，但不得改写上方技术事实与方法论。

---

## E-006 · 2026-10-06 · 太极接 OpenCode Web 的三个坑（batch 3 实装实录）

**1. WebView fetch/XHR 的 401 不触发 onReceivedHttpAuthRequest**
OpenCode v2 serve 的 API 强制 HTTP Basic（静态资源 200、/api/* 401 +
WWW-Authenticate: Basic）。WebView 对 fetch/XHR 的 401 不回调认证接口
（只有主 frame 导航会）——SPA 卡死在 logo。**解法：App 内本地透传代理**
（LocalProxy：127.0.0.1:14001 → 14000，TCP 字节级注入 Authorization，
SSE/chunked/POST body 全兼容）。

**2. 代理单连接语义**：第一版只注入每个连接的第一个请求，浏览器 keep-alive
复用连接的后续请求裸奔 401 → 主帧导航报 ERR_HTTP_RESPONSE_CODE_FAILURE。
**解法：单请求单连接语义**——每请求注入 + 请求/响应头强制 Connection: close
（loopback 握手成本可忽略；SSE 长流不受影响，EventSource 关闭才断）。

**3. 孤儿 serve 与代理线程生命周期**：App 被 force-stop 后 serve 子进程存活
（监听 14000），重启 App 后 startServe 探到"已在运行"直接返回——但代理线程
已随旧进程死亡，没人重启 → 界面卡死。**解法**：startServe 入口幂等重启代理；
stopServe 扫 /proc 按 cmdline 同 uid kill 孤儿；servePassword 从 serve.log
解析（密码行在 listening 之后打印，需要重试读取）。

**附带发现**：HttpURLConnection 对本地代理端口 14001 的存活探测恒失败
（curl 同端口 200）——判活改用原始 socket HEAD。

**验证**：太极 Tab 一键 → serve 拉起 → OpenCode Web 全界面渲染（会话列表/
对话/主题切换）→ 真机截图通过。

---

## E-007 · 2026-10-06 · Kotlin 块注释里不能写 `/*`；serve 判活与密码解析的两处更正

### 1. 🔴 Kotlin 注释里的 `/*` 会开启嵌套注释（极隐蔽，报错完全跑偏）

**现象**
在 `OcManager.kt` 的 KDoc 里写 `` 必须打 `/api/*` 并校验 content-type ``，
一次编译报 **18 个错**：

```
e: OcManager.kt:48:29  Unresolved reference 'Settings2'
e: OcManager.kt:54:80  Syntax error: Missing '}'
e: OcManager.kt:336:1  Syntax error: Unclosed comment
e: ui/TaijiScreen.kt:82:67  Unresolved reference 'serveRunning'
e: ui/TaijiScreen.kt:202:57 Unresolved reference 'startServe'
…（共 18 条）
```

**根因**
Kotlin **支持嵌套块注释**。注释里的 `/*` 会开启新的一层，后面的 `*/`
只关闭嵌套层，**外层注释始终不闭合** → 编译器把后面整段代码当成注释吞掉，
于是报出一批看似毫不相干的 `Unresolved reference` / `Missing '}'`。

**真正有用的信号只有一条**：`Unclosed comment`（且行号在文件靠后位置）。
前面那些 `Unresolved reference` 全是噪声——**不要从第一条错误开始修**。

**规避**
注释里写路径一律避免 `/*` 字面序列：

| ❌ 不要写 | ✅ 写成 |
|---|---|
| `` `/api/*` `` | `` `/api/…` `` 或 "`/api` 前缀下的端点" |
| `路径通配 /*` | `路径通配 /…` |

**通用排查法**：出现大批 `Unresolved reference` 且行号集中在某个文件里，
先 `grep -n '/\*' 该文件` 看注释里有没有多余的 `/*`，再看 `Unclosed comment` 的行号。

### 2. 更正 E-006：「密码行在 listening 之后打印」——实测相反

E-006 第 3 点写"servePassword 从 serve.log 解析（**密码行在 listening 之后**
打印，需要重试读取）"。真机 `files/oc/serve.log` 实录是**密码行在前**：

```
server password <pw1>
server listening on http://127.0.0.1:14000
server password <pw2>
server listening on http://127.0.0.1:14000
server password <pw3>          ← 最后一场没绑上端口，无 listening
```

这个顺序直接决定了解析算法：原实现 `lastOrNull { contains("server password") }`
在"最后一场没绑上端口"时必然取到那场失败进程的密码 → 打 `/api/…` 全部 401
（PoC #3 已复现）。**正确做法是取"password 行后有 listening 行"的最后一个**。

### 3. 更正 E-006：判活不能打根路径

E-006 提到"HttpURLConnection 对本地代理端口 14001 的存活探测恒失败"，
但**打 14000 根路径同样不可用**——这版 serve 把无前缀路径全部喂给 Web UI 的
SPA catch-all，`/` 、`/global/health`、`/session` 一律返回 `200 text/html`。
判活必须打 `/api/…` 并校验 `content-type: application/json`
（401 也算存活：证明 serve 在监听且鉴权生效）。

**验证**：三项均在真机（Magic 5 Pro / 2.0.22）实测，修复已落在
`feat/taiji-compose-ui` 分支（`OcManager.serveRunning` / `parseServePassword`），
`assembleDebug` 通过。

---

## E-008 · 2026-10-06 · OpenCode `/api/event` 连上即关（服务端缺陷 Issue #38458）；数据改走 REST 轮询

**现象（真机 + 双端复现）**
太极 Tab 的 SSE 长连接表现为「每秒一轮」：`SSE 连接建立 HTTP 200 | transfer=chunked` 之后
立即 `读到 0 行 / 0 字节` 断开，UI 在「已连接 / 正在重连」之间闪烁，而 REST 端点全部正常。

**定性：服务端缺陷，非本项目客户端 bug**
OpenCode 的 `/api/event` 在**每次 flush 后约 0.1–1.4 秒关闭连接**（上游 Issue #38458），
普通单连接客户端只收到第一波数据然后静默。四路取证全部指向服务端：

| 取证 | 结果 |
|---|---|
| PC `curl /api/event`（`adb forward`，正确密码） | 200 + `server.connected` + 心跳，但同样很快被 FIN |
| **设备自身** `/system/bin/curl` `/api/event` | 与 App 同一 loopback 路径，同样出流后即断 |
| 复刻 OkHttp 全头（UA=`okhttp/4.12.0`、`Connection: Keep-Alive`、`Accept-Encoding: identity`） | 同样表现 ⇒ 与请求头无关 |
| `ss -tn` 查 App→14000 连接 | 停在 `CLOSE-WAIT` = **服务端主动 FIN** |

**排除项**（均已实测，勿再浪费时间）：`x-opencode-directory` 头、`Accept-Encoding: identity`、
readTimeout / callTimeout、PC 代理工具、设备 VPN（`http_proxy=null`，无活动 VPN 接口）。

**策略（用户定稿）：REST 轮询为主，SSE 仅作「信号通道」**
1. **SSE 当信号**：连上（TCP + HTTP 200）即视为「就绪」，`emit(Reconnected)` 一次；
   不指望它持续推事件。
2. **REST 轮询为数据源**：`GET /api/session/{id}/message` 全量重建（天然幂等），
   自适应节奏——有变化 1s、无变化 3–5s、失败指数退避（1–15s）。
3. **SSE 退避**：因连上即关，**不能「连上就重置退避」**（否则永远 1s 刷屏）；
   改为「只有真正读到过事件才重置」，指数退避 1→2→4→8→15s 封顶。
4. **SSE 断开不改连接状态**（避免 UI 每秒闪烁）；连接健康由 REST 轮询驱动。

**代码落点**
- `oc/SseClient.kt`：信号通道语义 + `defectStreak` 退避 + 静音刷屏日志。
- `oc/OcRepository.kt`：新增 `startPolling` / `pollOnce` / `fetchJsonArrayOrThrow`；
  `handle()` 中 `Connected/Reconnected` 只置「就绪」、`Disconnected` 不动状态。

**何时可以回退**：上游修复 Issue #38458（`/api/event` 保持长连接）后，可把数据源切回纯 SSE；
`SseClient` 的事件解析（`PartUpdated` / `MessageUpdated` / …）仍完整保留，届时无需重写。

**验证**：`assembleDebug` 通过；真机跑一轮确认消息经 REST 轮询正常显示。

---

## E-009 · 2026-10-06 · 「回复只有思考过程、没有正文」根因 = opencode 输出 token 硬封顶 32K（已修）

**现象**：太极 Tab 收到回复时**只有「思考过程」（reasoning）卡片、没有最终正文**；推理越长越必现。

**根因（第三方行为，非本项目 bug）**
opencode 对**每一次补全**硬性封顶 **32000 输出 token（含思考/reasoning）**，与模型自身上限无关。
推理模型会把这 32K 预算**全部花在 thinking 上** → 补全以 `reason: length` 结束、**不产出任何正文**。
社区文档原文印证：*"opencode caps every completion at 32 000 output tokens — thinking included…
the completion ends with reason: length and no text"*。

**修复**：在 serve 冷启动时注入环境变量（`OcManager.startServe` 的 `env`）。
```text
OPENCODE_EXPERIMENTAL_OUTPUT_TOKEN_MAX=64000   # 官方文档确认的变量名（opencode 会再按模型上限夹一次，安全）
OPENCODE_EXPERIMENTAL_LENGTH_NUDGE=true        # 未见于官方文档；设未知 env 无副作用，一并设置覆盖用户方案
OPENCODE_EXPERIMENTAL_LENGTH_NUDGE_MAX=3
```
⚠️ 环境变量**只在 serve 冷启动时生效**：已在运行的 serve 不会重读，必须让它重启
（App `force-stop` 会连带杀掉 serve 子进程；重启 App 即冷启动）。

**验证（真机 2.0.22）**
- 注入确认：`/proc/<servePid>/environ` 含上述三项。
- 功能确认：同一条「Write a 400 word essay」提示，修复前只产 `reasoning`；
  **修复后产出 `reasoning(3117) + text(2739)`**，UI 正常显示正文。
- 变量名核实：`OPENCODE_EXPERIMENTAL_OUTPUT_TOKEN_MAX` 见官方 `https://opencode.ai/docs/cli`
  （Experimental 段，"Max output tokens for LLM responses"）；`LENGTH_NUDGE*` 未在官方文档中找到。

**附带发现（待处理）**：UI 把最终正文渲染在了「思考过程」标题之下（疑似 part 标签/分组问题）——
正文确实出现了，但标签可能不准确。

---

## E-010 · 2026-10-06 · 权限确认接通（V2 语法 / 环境变量不生效）+「思考过程」标签错位（已修）

### 1. 权限「静默放行」的根因与接通（安全项）

**现象**：Agent 执行 shell / 改文件**从不弹确认** —— 等于默认授予最高权限。

**根因（两层）**
1. 服务端 OpenCode 默认多数权限为 `allow`（只有 `doom_loop` / `external_directory` 默认 `ask`）。
2. 客户端从未正确接到 `permission.asked`：`parsePermission` 按旧名读
   `permissionID` / `title` / `description`，而真实载荷是
   `{ id(^per), sessionID(^ses), action, resources[], save[], source }` —— 全读空。

**服务端策略怎么设（关键坑）**
- ❌ 环境变量 `OPENCODE_PERMISSION`（官方 CLI 文档列出的名字）**本版 bionic 2.0.22 不生效**：
  设了之后 shell 工具仍被静默放行（真机实测）。
- ✅ **必须写配置文件** `XDG_CONFIG_HOME/opencode/opencode.json` 才生效。
- ⚠️ **V2 换了字段与 action 名**（官方 V2 权限文档）：顶层是 `permissions`（v1 是 `permission`），
  跑 shell 命令的 action 是 **`shell`**（v1 是 `bash`），文件修改是 `edit`（覆盖 write / patch）。
  **写成 v1 的 `bash` 不会匹配 → 依旧放行。**
- 规则 = `[{action, resource, effect}]`，effect ∈ allow|deny|ask，resource 支持 `*` / `?` 通配。
- 落点：`OcManager.ensurePermissionPolicy()`（每次冷启动无条件重写）。

**SSE 事件带信封（这条导致回执 400）**
`permission.asked` 的 data 是 `{ id(evt_), created, type, location, data:{ …Permission.Request } }`
—— **真正的请求在 `data` 里**。不剥这层会把**事件 id `evt_…`** 当作 requestID →
回执必然 HTTP 400。`parsePermission` 现按 `data → properties → 自身` 三级兜底。

**回执**：`POST /api/session/{sid}/permission/{rid}/reply`，body `{"decision":"once"|"always"|"reject"}`
- `once` → 204，请求消失且**不**写 saved
- `always` → 写入 `/api/permission/saved`（`psv_…`）
- `reject` → 拒绝

**兜底**：SSE 空闲会断（见 E-008），故额外轮询 `GET /api/permission/request`（权限绝不能漏）。

**验证（真机）**：抽屉显示「工具：shell / 目标：hostname」；点「允许」(once) →
`/api/permission/request` 变空、`saved` 无新增；此前选 always → `saved` 出现
`{"action":"shell","resource":"date *"}`。

### 2. 正文与「思考过程」标签错位（渲染，修 E-009 附带发现）

**现象**：最终正文看起来被渲染在「思考过程」标题之下。
**归因**：**dispatch 本身没错**（严格按 `part.type`：`reasoning`→Reasoning、`text`→Text）；
错在 `MessageBubble` 把所有 part 塞进**同一个无间距 Column** —— 折叠的「思考过程」标题与正文紧贴，
视觉上连成一体，像是正文属于思考过程。
**修法**：reasoning 抽成独立的 `ReasoningBlock`（💭 头部 + 首行摘要 + 展开/收起），
并给 `MessageBubble` 的 Column 加 `Arrangement.spacedBy(8.dp)`。
