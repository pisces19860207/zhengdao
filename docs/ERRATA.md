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
- `app/src/main/AndroidManifest.xml:20-21`：`MANAGE_EXTERNAL_STORAGE` 已声明，升为主路径。
  （原文写 `:15`，行号已漂移。）
- `app/src/main/java/.../terminal/ProotLauncher.kt:482`：`args.addAll(arrayOf("-b", "$shared:$shared", "-b", "/sdcard:/sdcard"))`
  已在，主路径不变。（原文写 `:373` 且字面写作 `--bind /sdcard:/sdcard`——
  该字面全库不存在：proot 参数用的是短选项 `-b`，且 `/sdcard` 是与 `$shared` 一起加的。）
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

## E-011 · 2026-10-07 · v1.2 E1（打包排除桌面端原生库）已落盘，但提交归属错、信息未提

**事实**：`app/build.gradle.kts` 的 `packaging.resources.excludes += {win/** , darwin/** , freebsd/**}`
（v1.2 E1）**不在**任何 v1.2 提交里，而是随 `5601bb4`（zcode 的
「test(bench): 解压基线先行」）一起进了远端 `origin/main`。

**归因**：并行 agent 在同一工作树上提交时，`build.gradle.kts` 被整文件提交——
E1 的 `packaging` 块与 zcode 的 `androidTestImplementation(commons-compress)` 行
落在同一次提交里。该提交信息只写了「androidTest 源集补 commons-compress 依赖」，
**没有提 packaging 排除**。是提交粒度的疏漏，不是代码错：E1 的改动本身正确且已生效。

**为什么不改历史**：`5601bb4` 已推到 `origin/main`，且有并行 agent 在基于它工作，
改写共享历史代价大于收益 → 记勘误而非 rebase。

**收益口径修正（避免后人照抄错误数字）**：
- 未压缩体积：这 5 个文件（3 个 `.dll` + 2 个 `.dylib`）合计 **4.58 MB**；
- **APK 实际只小了 469 KB**（41,271,658 → 40,791,258 字节）——
  deflate 对这些原生库压缩率很高，未压缩体积不能直接当安装包收益。
- 结论：E1 值得做（白拿的），但**别拿 4.58MB 当卖点**。

**教训**：多 agent 共树上，`build.gradle.kts` / `AndroidManifest.xml` 这类
"人人都要碰" 的文件最容易互相卷进对方的提交。提交前用
`git diff --stat` 逐文件确认，必要时 `git commit -o -- <path>` 精确圈定路径。

## E-012 · 2026-10-07 · 16KB 页对齐漏配让 Rust 路径静默失效；R1 收编为 libzhengdao_core.so

**一句话**：Rust 解压链路"代码已实装、APK 里也有 `.so`"，但在 16KB 页设备上**一次都没跑过**——
NDK r27 默认按 4KB 页对齐，`libextract.so` 会被 loader 直接拒绝加载；而规范 #2 的回退纪律
让这次失败**不崩溃、不报错、无日志**，只是永远走 Java 路径。

> **状态（2026-10-07）**：本条的**修法尚未进 `main`**。上面的分析对 `main` **依然成立**——
> `main` 现在仍在打包 4KB 对齐的 `app/src/main/jniLibs/arm64-v8a/libextract.so`（803,856 B），
> 16KB 页设备上它照样加载不了、照样静默落回 Java 路径。
> 修复（`rust/core` 收编 + `libzhengdao_core.so` + `.cargo/config.toml` 两个 page-size flag）
> 落在 `feat/v2.0-r1-rust-core-16kb` 分支的 `008d554`，随 v2.0 R1 合并才生效。

### 1. 现象：一个"跑得挺好"的假象

- 调试包（`zhengdao-1.2.0-debug.apk`，18:57 构建）lib/ 下**有** `libextract.so`，
  测试机（Honor Magic 5 Pro / Android 16）是 **4KB 页**设备 → 加载正常，R2 对拍 3/3 通过。
- 于是「Rust 解压已实装」看起来成立。但实际上**没有任何一次验收检查过"这个 .so 能不能被加载"**，
  它只是"在恰好是 4KB 页的机器上能加载"。

### 2. 根因：门禁存在，但没进构建步骤

- 本项目自己的判据早已写明：`docs/milestones/M1.1-开发任务书.md:36`
  「每个 LOAD 段的 align 都需 ≥ `0x4000`（16384）」（`cpp/CMakeLists.txt` 就是照此配的）。
- 但 Rust 侧 `.cargo/config.toml` 的 `[target.aarch64-linux-android]` **只配了 linker**，
  没有任何 page-size flag → clang 按默认 4KB 对齐。
- `llvm-readelf -l` 实测（NDK 27.2）：
  - `libextract.so` 四个 LOAD 段 p_align **全是 `0x1000`** ❌
  - `libsha256poc.so` 同样**全是 `0x1000`** ❌
  - `libzstd-jni-1.5.6-4.so` 是 `0x10000` ✅（第三方预编译库自己做了）
- 为什么以前没暴露：`libtermux.so` / `libzstd-jni.so` 走 CMake 与预编译路径，只有**自研 Rust 库**
  落在了门禁之外；而唯一的测试机是 4KB 页。

### 3. 修法

`rust/.cargo/config.toml` 补两个 flag（r27 及以下必须**两个都给**，r28+ 才默认对齐）：

```toml
[target.aarch64-linux-android]
rustflags = [
  "-C", "link-arg=-Wl,-z,max-page-size=16384",
  "-C", "link-arg=-Wl,-z,common-page-size=16384",
]
```

只加 `max-page-size` 不给 `common-page-size`，在 r27 下**部分段仍会对齐到 4KB**——
这正是"改了参数却以为修好了"的陷阱。

### 4. 验收（不能拿"App 没崩"当验收）

正因回退纪律让失败静默，必须独立取证：

```bash
llvm-readelf -l app/src/main/jniLibs/arm64-v8a/libzhengdao_core.so | grep LOAD
```

四个 LOAD 段 p_align 全 `0x4000` ✅ 才算过（本次已实测：四条全 `0x4000`）。

release APK 内**全部** `.so` 的 LOAD 段对齐（本次实测，已无遗漏）：

| 库 | p_align |
|---|---|
| `libzhengdao_core.so`（自研） | `0x4000,0x4000,0x4000,0x4000` ✅ |
| `libtermux.so`（自研 CMake） | `0x4000 ×3` ✅ |
| `libandroidx.graphics.path.so`（第三方） | `0x4000 ×3` ✅ |
| `libzstd-jni-1.5.6-4.so`（第三方预编译） | `0x10000 ×2` ✅ |

另外补一条运行时检查点：`CoreNativeSha256InstrumentedTest.rust链路可用` /
`CoreNativeExtractInstrumentedTest.rust链路可用` 的 `isRustAvailable()` 断言——
在 16KB 页真机上这是唯一能主动报警的地方（readelf 只在构建机上能跑）。

### 5. 顺带：R1 收编（演化，不并存）

按架构文档 §2.3 把 `sha256poc` + `extract` 合并为单一 `rust/core` → `libzhengdao_core.so`：

| | 收编前 | 收编后 |
|---|---|---|
| `.so` | `libsha256poc.so` 325,208 B + `libextract.so` 803,856 B = **1,129,064 B** | `libzhengdao_core.so` **807,712 B** |
| 加载次数 | 2 次 `loadLibrary` | 1 次 |
| Kotlin 桥 | `Sha256Native` + `ExtractNative` | 单 `CoreNative` |
| 依赖 | 两份 sha2/zstd 静态链接 | 共享一份 |

**省 321,352 B（-28.5%）**，且共享了同一份 sha2/zstd。
⚠️ 口径提醒（同 E-011）：这是**未压缩**体积差；**debug APK 实际只小了 36,917 B**
（38,790,203 → 38,753,286）——两个已 strip 的 `.so` 本来就压得很好，别拿 321KB 当卖点。
真正的收益是"一次 `loadLibrary` + 一份依赖"，体积只是顺带。
JNI 符号随之改名：`Java_com_example_zhengdao_rust_CoreNative_nativeSha256Hex` /
`..._nativeExtract`；proguard keep 同步从 `Sha256Native` 改为 `CoreNative`。
PC 层 `cargo test` 8/8（sha256 4 + extract 4）。

### 6. Rust 化结论（值不值得，用数字说话）

- **解压不是性能任务**：基线真机解 311MB 归档（20,042 条目 → 969MB）只要 **2.07 秒**；
  R2 对拍 **Rust 3611ms vs Java 3668ms**——同一量级。它的价值是**架构验证**
  （"Rust 处理文件树 + native zstd + 进度回调"这套模式能不能跑通），**别拿它当性能卖点**。
- **JNI 边界搬大块数据永远亏**：sha256 两轮实测（10MB × 10 轮均值）——
  软件实现 5ms vs 27ms，开了 ARMv8 asm 后 6ms vs 33ms。**开不开 asm 都慢 5 倍**，
  说明瓶颈不在哈希算法，在"10MB 数组拷进 native + hex 字符串封回 JVM"。
  结论：划算的形态是**数据常驻 native、边界只跨一次**（解压正是这种），
  而不是逐个函数跨边界调大块数据。
- 实装状态留档：tag `v1.2.0` 的两个资产（`zhengdao-1.2.0-release.apk` 15:33、
  `app-release.apk` 15:42）**都不含** `libextract.so`——`.so` 是 18:25/18:41 才提交的，
  两个资产早约 3 小时构建完；`latest` 滚动版（19:13，HEAD `725ebef` 之后）才带上。
  即**代码层实装 ≠ 已发布的包里有**。

### 7. 两个"看着像 bug、其实不是"（免得后人白查一遍）

1. **Rust 路径不回调 `onEntry` 进度** —— 不是漏实现。`RootfsInstaller.install()` 的
   **6 个调用点全部传空 lambda**（`TerminalActivity.kt:809/850/903`、
   `ui/SettingsScreen.kt:714/924/1010`），UI 侧本来就不消费逐条回调（Rust 侧另有限频的
   `CoreNative.onProgress`），所以 Java 路径调了也没人听。
2. **"0 条目也写 MARKER"** —— 不是 Rust 引入的回归。`RootfsInstaller.kt:128` 无条件写
   `.zhengdao-rootfs-ok`，且 Java 路径**同样不校验最小条目数**；`extract_pipeline` 也没有
   条目数下限。两条路径语义一致，属于**继承来的**行为，要改就两条一起改。

### 8. 教训

1. **有回退的链路，失败是静默的**。"功能没崩"永远不能当作"功能可用"——必须有一个
   独立于主流程的取证点（这里是 `llvm-readelf` + 真机 `isRustAvailable()` 断言）。
   回退纪律（规范 #2）本身是对的，但它会把"没生效"伪装成"一切正常"。
2. **门禁要写进"重新生成产物"的步骤里**。16KB 判据 10 月 5 日就写在任务书里了，
   但 Rust 的 `.cargo/config.toml` 是独立新增的构建入口，没继承这条门禁。
   凡是"另一条构建链"，都要逐条对照既有门禁重查一遍。
3. **`.so` 入库 + CI 免装 Rust = CI 永远不会重编、也就永远发现不了这类问题**。
   二进制产物的正确性只能靠人写进文档的验收命令守住（已写进 `rust/README.md` 与
   `rust/core/README.md`）。
   > ⚠️ 行号更正：`rust/core/README.md` **在 main 上不存在**，它只存在于
   > `feat/v2.0-r1-rust-core-16kb` 分支（R1 收编把 `rust/extract` + `rust/sha256poc`
   > 合并成 `rust/core` 的产物）。在 main 上应读 `rust/extract/README.md`。
4. **"开个 feature 就好了"要验证**：sha256 的 asm feature 开了之后反而更慢（27ms → 33ms），
   因为它根本没有改变瓶颈所在。优化前先量出瓶颈在哪一段，别猜。

---

## E-013 · 2026-10-07 · CI 发布的「正式版」其实是 debug 包——所有 Release 资产都是 37MB 调试件

**一句话**：`.github/workflows/build.yml` 从来没有跑过 `assembleRelease`。它只编 debug APK，
推 tag 时把这个 debug APK **改名成 `app-release.apk`** 传到 Releases。于是所有用户
（含滚动版 `latest`）下载到的都是 debug 构建——体积大 9.7 倍、`debuggable=true`、没过 R8。

### 1. 现象：名字和内容对不上

tag `v1.2.0` 上并排躺着两个资产：

| 资产名 | 大小 | 真身 |
|---|---|---|
| `zhengdao-1.2.0-release.apk` | 3,936,733 B | 真的 release 构建（= 本机 `:app:assembleRelease` 的字节数，手工传的） |
| `app-release.apk` | 38,155,528 B | **debug 构建**（CI 改名传的） |

滚动版 `latest`：`zhengdao-1.2.0-debug.apk` = 38,583,773 B —— 这个名字反倒是诚实的。

体积差 **9.7×**。而 App 内没有任何自更新下载器（`ui/SettingsScreen.kt:884/894/978` 只是
`Intent.ACTION_VIEW` 跳 GitHub 页面），用户是**自己挑资产下载**的——他会看到两个都叫 "release"。

### 2. 根因：三处，逐条对应行号

> ⚠️ **本节所有 `build.yml:` 行号都是修复前（`fc74cd5^`）的旧编号。**
> 修好之后该文件从 154 行长到 254 行，同样的行号现在指向完全不同的内容。
> 要按行号查证请用 `git show fc74cd5^:.github/workflows/build.yml`。

- `build.yml:77-78` —— 整条流水线唯一的编译步骤：
  ```yaml
  - name: 编译 Debug APK
    run: ./gradlew :app:assembleDebug --console=plain
  ```
  没有 release，一行都没有。
- `build.yml:97` —— 收集产物时只拷 debug 目录：
  ```bash
  cp app/build/outputs/apk/debug/zhengdao-*.apk artifacts/
  ```
- `build.yml:140-145` —— release job 把 debug 包**改名**：
  ```bash
  cp artifacts/zhengdao-*-debug.apk artifacts/app-release.apk 2>/dev/null || \
  cp artifacts/zhengdao-*.apk artifacts/app-release.apk
  ```
- `build.yml:147-153` —— `softprops/action-gh-release` 把这个文件作为正式版发布。

### 3. 为什么一直没被发现（这才是最值钱的部分）

1. **release buildType 用的就是 debug 签名**（`app/build.gradle.kts:77`
   `signingConfig = signingConfigs.getByName("debug")`，注释写明是"个人分发渠道"策略；
   benchmark 变体同款在 `:90`。注意 `:69` 不是这行，`fc74cd5^` 时代就写错过一次）。
   所以 debug 包**能正常安装、能正常跑**——功能上看不出任何区别，只有体积和 `debuggable` 不同。
2. 滚动版文件名带 `-debug`，作者自己一眼看得懂，就不觉得是错。
3. **没人拿"资产字节数"对过账**。本机 release 产物 3.9MB，CI 资产 38MB，一对比就露馅。

### 4. 影响（按严重度排）

1. **可调试**：`android:debuggable="true"`，任何人都能 attach 调试器、读 App 私有数据。
   对一个"内置 Debian 环境 + Agent + 用户凭据"的 App，这是实打实的暴露面，不只是体积问题。
2. **无 R8**：没有收缩/优化，体积、启动、内存全面吃亏。
3. **验收口径失效**：`lintVital` 与 R8 只在 release 变体上跑——**"CI 绿了"从来不等于
   "release 能构建"**。本次 R1 就是活证：改完之后 `:app:assembleRelease` 跑了 2m41s
   才第一次被真正验证（顺带说明它**是能构建的**，CI 只是没跑）。
4. 用户下载量/流量：38MB vs 3.9MB。

### 5. 修法（**已实施**，见本节末「实施记录」）

- build job 增加一行 `./gradlew :app:assembleRelease --console=plain`。
  > ⚠️ **原文此处写"release 走 debug 签名，CI 上不需要任何 secret，可以直接编"——这句已被 E-014 推翻。**
  > E-014 查明：CI 从不还原 `~/.android/debug.keystore`，AGP 会每次随机生成一把新 key。
  > 所以 `assembleRelease` 一旦进 CI，**必须**先配 repo secret `DEBUG_KEYSTORE_B64`
  > 把本机那把 keystore 还原回去（见 E-014 §4）；照本行原文去配 CI 会发出装不上的正式版。
- **产物命名规范化**，重点是把 `app-release.apk` 这个会撒谎的名字去掉：
  - release job 直接传 `zhengdao-<versionName>-release.apk`（AGP 默认产物名就是这样）；
  - 若某处真需要固定名 `app-release.apk`，改成 `zhengdao-release.apk` 之类，
    并同步改下游引用——**当前仓库内没有这样的下游**（`ProotLauncher.kt:22` 下的是
    rootfs `debian-13.7-base-arm64.tar.zst`，不是 APK）。
- ⚠️ 改的时候注意 `gh release upload latest artifacts/*` 会把**整个目录**推上滚动版：
  如果同时把 debug 与 release 两个 APK 都塞进同一个目录，滚动版会**同时挂两个包**，
  反而更乱。所以产物拆成三个目录——`apk-debug/`（只进 Actions 页面，给开发者）、
  `apk-release/`（唯一进 Releases 页的 APK）、`rootfs-files/`（RootFS + proot，也进 Releases 页）。
- 验收：推一个 tag 跑完整流程，确认新发布资产的 APK **字节数量级 ≈ 4MB**（不是 38MB），
  且 `aapt dump badging` 里**没有** `application-debuggable`。

**实施记录（2026-10-07）**

- `.github/workflows/build.yml`：
  - build job 新增 `编译 Release APK` 步骤（紧跟 debug 之后）。**刻意不加
    `continue-on-error`**：release 编不出来就让 job 变红——宁可 CI 红，也不要再悄悄发调试包。
  - `收集产物` 改为 `apk-debug/` `apk-release/` `rootfs-files/` 三个目录，
    `upload-artifact` 的 `path:` 同时列这三个目录。
  - 滚动版 `latest` 只上传 `apk-release/*` 与 `rootfs-files/*`。RootFS 步骤是
    `continue-on-error`，失败时 `rootfs-files/` 会是空的——未展开的 glob 会被 `gh`
    当成一个不存在的文件名而让这一步失败，所以先用 `compgen -G` 探测，空则只发警告。
  - release job 下载到 `dist/`，取 `dist/apk-release/zhengdao-*-release.apk`，
    以**它自己的真实文件名**上传（不再 `cp` 成 `app-release.apk`）；找不到就 `exit 1`。
- `.github/release-notes.md`：下载指引由 `app-release.apk` 改为
  `zhengdao-<版本号>-release.apk`，并说明 `-debug` 后缀是什么。
- 本机可验证的部分：`:app:assembleDebug :app:assembleDebugAndroidTest` 16s 绿；
  `:app:assembleRelease`（含 R8 + lintVital）2m41s 绿，产物 `zhengdao-1.2.0-release.apk` 4,164,289 B。
- ⚠️ **尚未验证的部分**：workflow 只在推 main 或打 `v*` tag 时才真正执行，
  本次改动所在的分支不在 `on:` 的触发条件里，所以这段 CI 逻辑当时**一次都还没在 GitHub 上跑过**。
  > ✅ **后续（2026-10-07 晚，`3788eac` 进 main 后）**：构建腿已在真实 runner 上跑通。
  > Run 130（`actions/runs/37627650755`）里步骤「还原 debug 签名密钥」「编译 Debug APK」
  > 「编译 Release APK」**全部 success**。**只有 tag 触发的 release 腿仍未跑过**——
  > 它依赖 repo secret `DEBUG_KEYSTORE_B64`（见 E-014），配好之前不许打 tag。

### 6. 教训

1. **"CI 绿了"只证明 CI 跑的那条命令绿了。** 这条流水线从 workflow 名到产物名都在说
   "release / 正式版"，实际只跑 debug。**命名撒谎比配置错误更难发现**——配置错误会报错，
   撒谎的命名不会。
2. **产物名必须能被当成事实。** 一个真身是 debug 的包叫 `app-release.apk`，比老老实实叫
   `-debug` 危险得多（后者至少诚实）。
3. 与 E-012 是**同一类错误**：E-012 是 Rust 的 `.cargo/config.toml` 作为"另一条构建链"
   没继承 16KB 门禁；E-013 是 CI 作为"另一条构建链"没继承 release 构建。
   凡是新增的构建入口，都要逐条对照既有门禁重查一遍。
4. 顺带修正 E-012 §6 的表述：那里说"tag `v1.2.0` 两个资产都不含 `libextract.so`"——
   其中 `app-release.apk` 之所以不含，除了构建时间早，还因为**它根本就是个 debug 包**。

## E-014 · 2026-10-07 · CI 每次构建都换一把签名密钥——CI 产物互相装不上，也盖不了正式版

**一句话**：`build.yml` 只缓存 `~/.gradle/*`，**既不缓存也不还原 `~/.android/debug.keystore`**。
GitHub 每次跑在全新 runner 上，AGP 找不到 keystore 就**随机生成一把新的**，于是每个 CI 产物的
签名证书都不同 → 交叉升级一律 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`，只能卸载重装，
而卸载会把 `filesDir` 一起删掉 = 整个 Debian 环境重下（4 分钟 + 流量）。

### 1. 现象：四把 key，两两都不兼容

取 APK 尾部的 APK Signing Block（v2 方案，block id `0x7109871a`）、解析出签名证书后比对：

| 包 | 构建者 | 证书 SHA-256 |
|---|---|---|
| `zhengdao-1.2.0-release.apk`（tag v1.2.0，3,936,733 B） | 本机 | `44e2fe86b1f62a9fdb2e86805fe0a4dae7cad0c3024dbf6af84d0c45b5a3be18` |
| 本机 `:app:assembleDebug` / `:app:assembleRelease` 产物 | 本机 | `44e2fe86…a3be18`（同一把） |
| latest `zhengdao-1.1.1-debug.apk`（40,080,028 B） | CI | `a42ddaa55eb22d5313a8d2122dfc7bb1c80cbce7eade18381639c1cdb7e80e84` |
| latest `zhengdao-1.2.0-debug.apk`（38,583,773 B） | CI | `2c94143a68eb4c5f2ef4d74997edf7a569dab70cca86e3db0aa0348ac9f0d973` |

**四把 key。** 本机两把一致（同一份 `C:\Users\guoli\.android\debug.keystore`，2,618 B，2026-10-03 生成），
两个 CI 产物**彼此不同**、也**都不同于正式版**。

（复现方法：不需要下整包——对 release 资产发 HTTP Range 只取文件末尾约 700 KB，
找到 EOCD → 中央目录 → 紧邻其前的 `APK Sig Block 42` → 取 v2 块里的证书 DER。全程 <1 MB。）

### 2. 根因：keystore 落在缓存之外

- `build.yml` 的 cache 只覆盖 `~/.gradle/{caches,wrapper,jdks}`，**没有 `~/.android`**。
- AGP 的 debug 签名指向 `$HOME/.android/debug.keystore`；**文件不存在时会自动生成**，
  其中的 RSA 私钥是随机的。
- 所以"签名一致"这件事**只在本机成立**：本机那份 keystore 生成一次后一直复用，
  而 CI 每次全新 runner 都会重建。

### 3. 为什么它比 E-013 更严重

E-013 是"发出去的包不好"，本条是"**发出去的包装不上**"：

1. `latest` 上累积的 7 个 debug APK **互相之间无法覆盖安装**——每升一次就得卸载一次。
2. 从正式版切到 `latest` 的包也不行（key 不同），反过来也一样。
3. 卸载 = `filesDir` 全删 = `rootfs/`、已装 Agent、`~/.local/bin` 全套重来。
   对一个"装环境要 4 分钟"的 App，这是最贵的一种失败。
4. ⚠️ **E-013 的修法会把伤害从 debug 通道扩大到正式通道**：`assembleRelease` 一旦进 CI，
   发布出去的正式 APK 就是 CI 的随机 key，**老用户从 v1.2.0 升 v1.3 必然签名冲突**。
   两条修复**必须同时上线**。

### 4. 修法

- 把本机那把 keystore（证书 `44e2fe86…a3be18`，即**所有存量用户已经装上的那把 key**）
  存成 repo secret `DEBUG_KEYSTORE_B64`，CI 在**编译之前**还原到 `$HOME/.android/debug.keystore`。
- **不要**走"另建一把 release keystore"的路子：那把 key 与存量用户无关，
  一样逼所有人卸载重装。必须复用**同一把**。
- 还原步骤**不能硬失败**（否则 main 的日常构建会一直红），
  但 **tag 发布那一步必须硬失败**——没有 secret 就不许发正式版，
  绝不能让随机 key 的包以"正式版"的名义流出去。
- 验收：取**两次不同 run** 的 CI 产物，确认证书 SHA-256 相同，且等于 `44e2fe86…a3be18`。

### 5. 教训

1. **"构建可复现"不等于"签名可复现"。** 代码、依赖、工具链都能锁版本，
   而签名密钥是一个**藏在缓存之外的状态文件**。流水线里凡是"东西不在仓库里"的环节，
   都要单独点一遍。
2. **缓存策略有反向代价。** 为了"每次干净"而不缓存 `~/.android/`，代价是每次换 key。
   正解不是把它加进缓存（缓存一被淘汰 key 又变），而是**从 secret 确定性还原**。
3. 与 E-012 / E-013 同属一族：**"本机能跑"和"流水线能跑"是两套环境。**
   凡是"在某台机器上恰好存在"的东西（keystore、page-size 默认值、release 构建步骤），
   到了 CI 就是另一回事。

### 6. 实施记录（2026-10-07）

- `.github/workflows/build.yml:89-120` 新增步骤「还原 debug 签名密钥（让 CI 产物与存量用户同签名，可覆盖安装）」，
  位置在 Gradle 缓存之后、编译 Debug APK 之前：
  - `env: KS_B64: ${{ secrets.DEBUG_KEYSTORE_B64 }}`、`EXPECTED_SHA256: 44E2FE86B1F62A9FDB2E86805FE0A4DAE7CAD0C3024DBF6AF84D0C45B5A3BE18`。
  - 无 secret → `::warning::` + `exit 0`（**不硬失败**，避免 main 日常构建一直红）；
    有 → `base64 -d` 还原到 `$HOME/.android/debug.keystore`，再用 keytool 提指纹比对，
    **不符则 `::error::` + `exit 1`**。
- `:169-195` 「发布到 Releases」步骤加 `KS_READY: ${{ secrets.DEBUG_KEYSTORE_B64 != '' }}`：
  为 `true` 才 `gh release upload latest apk-release/*`；为 `false` 只打警告并跳过 APK 上传
  （滚动版宁可不更新 APK，也不发一把随机 key 的包）。
- `:202-219` release job 第一步新增「把关：没有签名密钥就不许发正式版」——**硬失败**
  （`::error::` + `exit 1`）。tag 发布会因此被拦住，而不是悄悄发出去。
- **指纹提取命令（踩过的坑，必须这样写）**：
  ```bash
  keytool -list -v -keystore "$HOME/.android/debug.keystore" -storepass android -alias androiddebugkey 2>/dev/null \
    | grep -m1 'SHA256:' | sed 's/.*SHA256: *//' | tr -d ':' | tr 'a-f' 'A-F'
  ```
  ⚠️ **不能用 `awk -F': *' '/SHA256:/{print $2}'`**——冒号后的 hex 会被 `-F` 逐段切开，
  只能拿到第一段 `44`，比对会永远失败。
- **本机验证**：用 `D:\Program Files\Android\Android Studio\jbr\bin\keytool.exe` 对同一份 keystore
  跑上面的命令，得到的 SHA256 与写死的 `EXPECTED_SHA256` **逐字符吻合** ⇒ 同时证明
  "这把 keystore 就是存量用户那把"与"CI 里的提取命令没写错"。
- **待用户执行的最后一步**：把 `C:\Users\guoli\.zhengdao-keys\DEBUG_KEYSTORE_B64.txt`
  （3492 字符、单行、无 BOM）配成 repo secret **`DEBUG_KEYSTORE_B64`**。
  在此之前 tag 发布会硬失败、滚动版不发 APK。
- 验收（未完成）：配好 secret 后打一个 tag，确认 CI 产物证书 SHA-256 = `44e2fe86…a3be18`，
  且能在装着 v1.2.0 的机器上直接覆盖安装（**不卸载、不丢 `filesDir`**）。

---

## E-015 · 2026-10-07 · 「方案一」废弃：rootfs 主下载源不迁往对象存储

**决定**：用户于 2026-10-07 拍板——**方案一（把 rootfs 主下载源从 GitHub Releases 迁到对象存储，
Cloudflare R2 / 阿里云 OSS）废弃，不再推进。**

> 用户原话（2026-10-07）：「方案一那个已经废弃了。」
> **本条不代为编纂废弃理由**——用户未给出书面理由，任何人不得在下游文档里替这次决定补编动机。
> 可核实的客观事实只有一条：截止落档时，rootfs 仍由 GitHub Releases 直链 + `gh-proxy.com` 兜底提供，实测可用。

### 1. 方案一原本要做什么

把"下载 Debian 环境包"从 GitHub Releases 换成对象存储做主源，目标是把装环境从约 4 分钟压到 1 分钟以内。
方案与清单见 `docs/milestones/证道-rootfs下载优化方案.md`、`docs/milestones/证道-方案一执行清单.md`。

### 2. 为什么必须落档（本条存在的唯一理由）

**这次废弃此前在全库（含本文件）没有任何一处正面记录**，只隐含在两份待归档的文档里。
不落档的后果是：将来有人翻到那份 21 个待办、一个勾都没有的执行清单，会把它当成"还没开始做的计划"
重新排期——而真正的原因是**它已经被决定不做了**。

### 3. 落档时必须一并纠正的一处事实错误

执行清单 `:18` / `:61` / `:63` 把"新增 `withObjectStorage(url)`"列为待办。
**这个函数早在 `4526b8f` 就已实现，而且是两参数版本**：

```kotlin
// app/src/main/java/com/example/zhengdao/rootfs/RootfsDownloader.kt:72
fun withObjectStorage(url: String, objectStoreBase: String): List<String>
```

单测覆盖在 `app/src/test/java/com/example/zhengdao/rootfs/UrlTransformTest.kt`（23 例）。
照清单原样执行会让后来人**重复实现一个单参数版本**。

同时确认：该函数在生产代码里**零调用**（`git grep` 只命中定义与单测）；真实生效的兜底是
`app/src/main/java/com/example/zhengdao/rootfs/RootfsDownloader.kt:59` 的 `withMirrorFallback` → `gh-proxy.com`。

### 4. 处置（顺序不能反）

1. **先**记本条 ERRATA——决定落档；否则后面两步做完就查不到原因了。
2. **再**改 `app/src/main/java/com/example/zhengdao/rootfs/RootfsDownloader.kt:63-71` 的 KDoc：
   删掉"接入时机与回退演练见执行清单阶段一 3.x"，改为"方案一已于 2026-10-07 废弃；此变换仅供试验与单测，不要接入默认源列表"。
3. **再**给 `docs/milestones/证道-rootfs下载优化方案.md` 加废弃横幅、`:36` 标题改「方案一（已废弃，仅存档）」。
4. **最后**整篇归档 `docs/milestones/证道-方案一执行清单.md`——**不留勾选框**。
   只加横幅而留着 `- [ ]`，等于把它继续摆在待办队列里。

### 5. 教训

1. **"决定不做"和"还没做"在仓库里长得一模一样。** 待办清单只记录"要做的事"，不记录"决定不做的事"；
   后者一旦不落档，几个月后就会被重新捡起来。
2. **废弃一个方案时，最先该清理的是它的可执行清单**，不是它的方案描述：方案描述是历史，清单是行动指令。
3. **列待办前先 `git grep` 一遍函数名。** 本次清单要求"新增"的函数已经存在了近一周。
