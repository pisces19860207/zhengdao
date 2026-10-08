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

> **状态（2026-10-07，本条更新过两次）**
> - **当时（写这条时）**：本条的**修法尚未进 `main`**。上面的分析对 `main` **依然成立**——
>   `main` 那时仍在打包 4KB 对齐的 `app/src/main/jniLibs/arm64-v8a/libextract.so`（803,856 B），
>   16KB 页设备上它照样加载不了、照样静默落回 Java 路径。
>   修复（`rust/core` 收编 + `libzhengdao_core.so` + `.cargo/config.toml` 两个 page-size flag）
>   当时落在 `feat/v2.0-r1-rust-core-16kb` 分支的 `008d554`。
> - **✅ 已收尾（2026-10-07 22:41）**：用户拍板后，该提交已 **cherry-pick 进 `main`（`af37010`）**——
>   只挑这一颗；分支上另外三颗（`dd75713` 终端页唯一化、`5697943` ERRATA E-013、`8484d46` CI
>   加 assembleRelease）在 `main` 上都已有等价物，**直接 merge 会引入重复提交并让
>   `build.yml`/`ERRATA.md` 冲突**，故不采用 merge（见 `文档核对报告-2026-10-07.md` §10）。
>   现状：`main` 的 `jniLibs/arm64-v8a/` 只剩 `libzhengdao_core.so`（807,712 B，四个 LOAD 段
>   p_align 实测**全 `0x4000`**）+ `libzstd-jni-1.5.6-4.so`；真机 8/8 通过（extract 3 + sha256 5，
>   设备 `AD3J023824001723`）。**下面第 1 节描述的 `libextract.so` 现象自本条起属历史记录。**

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

> ⚠️ **2026-10-07 补正**：上面这句在本项目**被实测推翻**。
> `app/src/main/cpp/CMakeLists.txt:18` 只给了 `-Wl,-z,max-page-size=16384`，
> 而它产出的 `libtermux.so` 三个 LOAD 段 p_align 实测**全 `0x4000`** ⇒
> 决定 PT_LOAD `p_align` 的是 `max-page-size`，`common-page-size` 只是冗余保险。
> 两个都给当然更稳（Rust 侧就是这么配的），但**不要把它写成"缺一个就等于没对齐"**——
> 那会让后来人误判一个其实已经对齐的产物。验收永远以 `llvm-readelf -l` 的实测为准。
> 另见 E-016 §5。

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
- **勘误：仓库里从来没有"纯 Java zstd 实现"**。2026-10-07 全库文档核对发现，多份文档
  （`证道-Rust迁移模块评估.md` 的 §迁移候选、`证道-Rust改造可行性裁定.md` §6 末句、
  `证道-性能演进路线图.md` §5.6 等，前后共 4 处）把解压描述成"commons-compress 的
  **纯 Java zstd**"，并据此预测"native zstd 快 3~5 倍"。**这是错的**：
  `app/build.gradle.kts:154` 引入的 `commons-compress` 走
  `ZstdCompressorInputStream`，它本身就是 **zstd-jni 的包装**（JNI → C 的 libzstd，
  `libzstd-jni-1.5.6-4.so` 一直在 `jniLibs` 里）。所以本仓库的 zstd **从来就是 native**，
  "纯 Java zstd 是 CPU 热点、快 3~5 倍"这个前提不成立——R2 对拍实测
  **Rust 3611ms vs Java 3668ms**（`rust/extract/README.md`）已经把它证伪。
  凡是看到"原生 zstd 能提速"的推论，先回这里。**"纯 Java zstd"这个说法应从所有文档中清除。**

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
   > ⚠️ 行号更正：写下这句时 `rust/core/README.md` **在 main 上并不存在**，它只存在于
   > `feat/v2.0-r1-rust-core-16kb` 分支（R1 收编把 `rust/extract` + `rust/sha256poc`
   > 合并成 `rust/core` 的产物）；main 上当时该读 `rust/extract/README.md`。
   > ✅ **2026-10-07 22:41 起已成立**：R1 收编 cherry-pick 进 main（`af37010`）后，
   > `rust/core/README.md` 已存在于 main，`rust/extract/` 与 `rust/sha256poc/` 已删除，
   > 上面这句指向不再落空。
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

### 7. ⚠️ 2026-10-07 深夜补记：**那道关卡验的是替身，不是产物 —— E-014 并没真修好**

secret 配好、run `37641936875`（@`682dceb`）16 步全绿之后，把 `latest` 上刚发布的
`zhengdao-1.3.0-release.apk`（4,162,930 B，2026-10-07T15:20:04Z）下下来，用
`apksigner verify --print-certs` 直接读**产物**的证书：

| 对象 | 证书 SHA-256 | 结论 |
|---|---|---|
| CI 第 9 步恢复进去的 keystore（日志亲证） | `44e2fe86…a3be18` | ✅ 文件是对的 |
| **这一跑打出来的那个 APK** | `18e5268a…0eff4f` | ❌ **不是这把** |
| 本机所有构建产物 + 手机上装着的那个包 | `44e2fe86…a3be18` | ✅ |
| `C:\Users\guoli\Downloads\zhengdao-debug.apk`（10-04 从 CI 下的） | `aef9e220…` | ❌ 第三把 |

⇒ **CI 依旧每次跑一把新 key**：`latest` 的包与任何存量安装仍然互不兼容，
实测 `adb install -r` 报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE: Existing package
com.example.zhengdao signatures do not match newer version`。
第 9 步只证明了"钥匙文件放对了地方、文件里的证书是这个指纹"，
**没有证明"打出来的包是用它签的"**；`:app:validateSigningRelease` 属于同一类替身检查。

已核实 / 已排除（都不是原因）：

- CI 日志里 `:app:packageRelease`（15:09:41）**真跑了**，全程**没有** `FROM-CACHE` /
  build cache 字样 ⇒ 不是复用旧产物（Gradle build cache 未启用）。
- `app/build.gradle.kts` **整份文件没有任何 `signingConfigs {}` 块**，全仓 `git grep storeFile` 零命中
  ⇒ release 走的就是 AGP 默认的 debug keystore（`:82` `signingConfigs.getByName("debug")`）。
- `$HOME/.android/debug.keystore` 的路径与内容都正确（第 9 步自证）。
- 剩余怀疑：AGP 在 runner 上解析到的是**另一个候选目录**（`ANDROID_USER_HOME` /
  `ANDROID_SDK_HOME` / `ANDROID_PREFS_ROOT` 下的 `.android`）。CI 日志里**没有** env dump，
  当场无法定论 —— 所以修法里必须先补上 dump。

**教训（与第 4 节第 1 条同族，但要再往前一步）**：
"验钥匙文件"和"验产物"是两件事。**签名这种最终属性，只能对着最终产物验。**
新关卡改成两步：① 把 keystore 写进所有候选目录，并在日志里 dump
`env | grep -iE '^(ANDROID|HOME)'` 自证路径；② `assembleRelease` 之后**立刻**用
`apksigner verify --print-certs` 验刚打出来的那个 APK，指纹 ≠ `44E2FE86…` 就让 run 红 ——
而且这一步排在「发布到 Releases」之前，红了就发不出去（宁可不发，也不发一个装不上的包）。

### 8. ✅ 2026-10-07 更深一夜：真凶仍**未定论**，但已不必知道它 —— 把签名钥匙改成显式输入

新关卡第一次生效就抓了个正着：run `37645467811`（@`741eb5d`）**第 12 步 failure**：

- 被验产物：`app/build/outputs/apk/release/zhengdao-1.3.0-release.apk`（4,174,862 B）
- 产物证书 SHA-256 = `9409433d…a1888af` —— **CI 第三次不同的指纹**（`18e5268a…` → `9409433d…`）
- 报错原文：`::error::打出来的包不是存量那把 key 签的（期望 44E2FE86…BE18，实际 9409433D…88AF）`
- 步骤 13–17 全部 `skipped` ⇒ 这一跑**没有**再往 `latest` 扔装不上的包，关卡按设计生效。

同一跑第 9 步新加的 env dump 把嫌疑范围收窄到「路径」：

- runner 上**只有** `ANDROID_HOME` / `ANDROID_SDK_ROOT` / `ANDROID_NDK*`；
  **没有** `ANDROID_USER_HOME`、`ANDROID_PREFS_ROOT`、`ANDROID_SDK_HOME`；`HOME=/home/runner`。
- `已写入: /home/runner/.android/debug.keystore (2618 字节)`，`keystore 证书 SHA-256 = 44E2FE86…BE18` ✅

⇒ 按 AGP 的取值顺序本该落到 `$HOME/.android/debug.keystore`，**而钥匙文件确实就在那儿、内容也对**，
产物却仍是别的 key。**所以 AGP 在 runner 上读的不是这个文件**；具体读到哪儿，本机无法复现，
**仍未定论**（`:app:signingReport` + `find / -name debug.keystore` 已作为常驻诊断进 CI 日志，下次一眼定案）。

**修法：不再赌 AGP 的目录推断，改成显式输入。**

- `app/build.gradle.kts` 顶部读环境变量 `ZHENGDAO_KEYSTORE_FILE`；设了就把 AGP 内建「debug」签名
  配置的 `storeFile` 覆盖成该路径（debug / release / benchmark 三变体本来就都指这一份，覆盖一处即三处生效；
  `:82`、`:95` 一行未改）。**本地不设该变量 ⇒ 行为一字不变**。
- `.github/workflows/build.yml` 第 9 步把 keystore 的**权威副本**写到 `$GITHUB_WORKSPACE/.ci-debug.keystore`
  （指纹仍逐字比对，不符照样 `exit 1`），第 10/11 步用 `env: ZHENGDAO_KEYSTORE_FILE` 指过来；
  新增一步常驻诊断 `./gradlew :app:signingReport` + `find / -name 'debug.keystore'`（`continue-on-error`）。
- `.gitignore` 加 `.ci-debug.keystore`（密钥，绝不许入库）。

**本机实证（可复现）**：用 `keytool -genkeypair` 造一把"诱饵" keystore
（`%TEMP%\zd-decoy\debug.keystore`，证书 SHA-256 `384db91c…6db28a`），然后

```powershell
$env:ZHENGDAO_KEYSTORE_FILE = "$env:TEMP\zd-decoy\debug.keystore"
.\gradlew.bat :app:signingReport :app:assembleRelease --console=plain
```

- `signingReport` 里 debug / release / benchmark 三个变体的 `Store:` **全部指向诱饵路径**；
- 产出的 release APK 用 `apksigner verify --print-certs` 读出来正是 `384db91c…`（≠ `44e2fe86…`）

⇒ 环境变量确实**完全接管**了签名。CI 把它指向还原进去的真钥匙，产物就会是 `44E2FE86…`。

**教训（第 5 节的补充）**：当一个「本机好好的」属性在别的机器上不对、而所有「检查替身」的手段
全绿时，正确做法不是继续加检查，而是**把这个属性改成显式输入** —— 让不确定性没有藏身之处。
`signingReport` 这类「让工具自己说话」的诊断，也该**常驻**在流水线里，而不是出事后临时加。

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

---

## E-016 · 2026-10-07 · CI 一直在构建并发布一个 App 根本不用的 proot

### 1. 现象

`latest` 滚动版清理掉 debug 包后只剩三个资产：rootfs 包 + `.sha256` + **`proot-arm64`（186,296 B）**。
其中 `proot-arm64` 来自 `rootfs/build-proot.sh`——它从上游 `proot-me/proot` master 自编译
（`:133` 链接、`:137` 拷成 `proot-arm64`），由 `.github/workflows/build.yml` 的
「构建 proot」步骤产出，再随 `rootfs-files/` 一起传上 Releases 页。

而**这个文件在应用代码里 0 引用**：`git grep proot-arm64` 只命中 `rootfs/build-proot.sh:137/139`
与 `build.yml:10`（产物说明），没有一行 Kotlin 读它。

### 2. 根因：两条路线并存，旧的被取代却没删

- **旧路线（已废弃）**：自编译 proot → 改名 `libproot.so` 放进 `jniLibs/arm64-v8a/` →
  从 `nativeLibraryDir` exec。`app/src/main/java/com/example/zhengdao/terminal/ProotLauncher.kt:156-159`
  已注明该方案**废弃删除**，理由是"本机实测它在 App 域内加载 guest 静默退出 255"，
  且该 `.so` 有 GPL 传染隐患。
- **现行路线**：`app/src/main/assets/proot/` 下 4 个 **Termux 官方发行二进制**
  （proot **5.1.107.96**、`loader`、`libtalloc.so`、`libandroid-shmem.so`），
  由 `ProotLauncher.kt:164-169` 按 SHA256 钉死后释放到 `files/termux-proot/`。
  设计文档与 `PROVENANCE.md` 都写明「用 Termux 官方产物，不要退回自编译上游版」。

`rootfs/build-proot.sh` 属于旧路线，但 CI 一直在跑它、还把它发到用户可见的下载页。
换句话说：**这条红线在代码里从未被执行过。**

### 3. 为什么没被发现

- 它不崩、不影响任何功能，只是白占一份资产和约 2 分钟 CI 时间；
- 名字听起来完全合理——"proot" 就是 App 在用的那个东西，
  只有把它和 `assets/proot/` 对照，才会发现是两个不同的 proot；
- 与 E-013 同类：**CI 做的事和 App 实际需要的东西之间，没有任何一步做对账。**

### 4. 修法（2026-10-07 已实施）

1. `.github/workflows/build.yml`：删掉「构建 proot」步骤；头部产物说明同步删掉 `proot-arm64` 行，
   并写明为何不再构建（指向本条）。
2. `rootfs/build-proot.sh` 整份删除（140 行）——留档在 git 历史里，要用时 `git show`。
3. Releases 页 `latest` 里的 `proot-arm64` 资产删除。
4. 文档同步：`docs/milestones/M1.1-开发任务书.md` 的 ⚠️ 块由「可整条移除」改为「已移除」。

### 5. 顺带纠正两处文档错值

- `rootfs/build-proot.sh:2710` 这个行号是**错的**——该脚本只有 140 行，
  `-Wl,-z,max-page-size=16384` 在 **`:133`**。
- 「r27 必须同时给 `max-page-size` 与 `common-page-size`，漏第二个部分段仍会对齐到 4KB」
  这句话在本项目**被实测推翻**：只给 `max-page-size` 时 `libtermux.so` 三个 LOAD 段
  p_align 已是 `0x4000`（`app/src/main/cpp/CMakeLists.txt:18` 就只有这一个 flag）。
  官方对 r27 的建议是两个都给，作为冗余保险没错，但不该被写成"缺一个就是缺口"。

### 6. 教训

1. **"这个文件是干什么的"要问应用代码，不能问文件名。** 两个都叫 proot 的东西可以只有一个真被用；
   `git grep <产物名>` 是唯一便宜的裁决手段。
2. **CI 的产物清单必须和 App 的读取点对账。** E-013（发了 debug 包）与本条（发了没人用的产物）
   是同一个洞的两种表现：流水线缺少"发出去的东西是否有人用"这一关。
3. **红线写在设计文档里 ≠ 被执行。** M1.1 早就写了"不要退回自编译上游版"，
   而 CI 一直在做这件事——文档与实践的差距要靠 `git grep` 定期找，不能靠读文档。

## E-017 · 2026-10-07 · 拍板删掉的功能被「让 CI 转绿」带回来，留下半残状态（已删）+ 三道防复发闸

### 1. 现象：同一个功能删了又加，加回来的是半份

- `e882050`（2026-10-06）`refactor(security): 移除 API Key 管理功能（用户决定：凭据类信息不落 App）`，
  改了 6 个文件 **+10/−185**：`oc/OcManager.kt`(−9)、`settings/ApiKeyStore.kt`(−93，整份删除)、
  `terminal/ProotLauncher.kt`(−15)、`ui/HomeScreen.kt`、`ui/SettingsScreen.kt`(−72)、`ui/TaijiScreen.kt`。
- `fba9185`（2026-10-07）`fix(ci): 补回 merge 漏带的 settings/ApiKeyStore.kt 及其 import（12bc499 红叉根因）`，
  只改了 2 个文件 **+94**：`ApiKeyStore.kt` 整份加回、`oc/OcManager.kt` 加回 import。
- 结果 `origin/main` 上是**半残**：存储类在、`oc/OcManager.kt:9` 的 import 在、
  `:278-279` 的 `ApiKeyStore.PROVIDERS.forEach { … ApiKeyStore.get(ctx, id)?.let { env[envName] = it } }` 是活的调用；
  而 `ui/SettingsScreen.kt` 里那 −72 行的配置入口**没有恢复** —— 用户在 App 里没有任何地方能填 key，
  但 App 依然会去读一个永远不会被写入的密钥库。

### 2. 根因：把「CI 红叉」当成了最高优先级

`fba9185` 的判断链是「CI 红了 → 报错说缺文件 → 把文件补回来让 CI 绿」。
这条链里**没有一步在问「这个文件本来该存在吗」**。而它在 8 小时前刚被用户拍板删除。
两个 agent 各自都"解决了自己的问题"，合起来把用户的决定吃掉了。

> 这是 E-013（CI 发的"正式版"其实是 debug 包）、E-016（CI 一直构建 App 根本不用的 proot）
> 的同族缺陷：**流水线的绿，从来没有和"用户要什么"对过账。**

### 3. 处理（2026-10-07）

按用户再次确认「按原决定删掉」执行，等价于 revert `fba9185` 的那两处：

1. 删除 `app/src/main/java/com/example/zhengdao/settings/ApiKeyStore.kt`（93 行，`settings/` 包随之整个消失）。
2. `oc/OcManager.kt`：删掉 `import com.example.zhengdao.settings.ApiKeyStore`，
   删掉那段 `runCatching { ApiKeyStore.PROVIDERS.forEach … }`，原位置留一行注释说明"故意不注入任何 API Key"。
3. `git grep -i "ApiKeyStore\|zhengdao-apikeys\|apikey" -- app/src` **零命中**；
   `:app:testDebugUnitTest :app:assembleDebug` → **BUILD SUCCESSFUL**。

### 4. 防复发：三道闸（本次同时落地）

光写文档没用——用户的原话是「也不核对就开始了」。所以做成机器拦得住：

| 闸 | 位置 | 拦什么 |
|---|---|---|
| 开工前核对 | `tools/agent-preflight.ps1` | 落后 origin/main / 关键词在历史里已实现过 / 要新增的文件被删过；退出码 1 = 有阻塞项 |
| 提交硬闸 | `tools/hooks/pre-commit`（用 `tools/install-hooks.ps1` 装进共享 `.git`） | ①在 `main` 上直接提交 ②你改的文件在未同步的 `origin/main` 提交里也改过 ③要新增的文件历史上被删过 |
| 功能台账 | `docs/FEATURE-LEDGER.md` | §2 在用/半残/已移除清单；§3 已被拍板删除的功能（复活前必须问用户）；§4 反复回归事件实锤 |

绕过硬闸的唯一方式：`ZHENGDAO_HOOK_BYPASS=1 git commit ...`，并在提交信息里写理由。
给 agent 的入口说明写在仓库根 `AGENTS.md`。

### 5. 教训

1. **「让 CI 转绿」不是需求。** 它是手段。当手段和用户的决定冲突时，先问用户，不要先让流水线闭嘴。
2. **删除也是一种有意的状态，必须被记住。** git 历史里"删过一次"是弱信号，
   台账 §3 把它升成硬信号，hook 在提交时强制查。
3. **靠自觉读文档的约定一定会被绕过。** 多个 agent 各自乐观地开工时，
   只有 pre-commit 这种"你过不去"的东西才有效。

---

## E-018 · 2026-10-07 · 「杀 App 再开，tmux 会把会话留住」这个前提不成立

**原表述（有误）**

> 期望：切后台 30 分钟回来 → 还是 agy；杀 App 再开 → 还是 agy（**tmux 兜底**）

**更正为**

> tmux 只能兜住「**进程还活着**」这一种情况——切后台、关页面、断线重连都算。
> **App 进程被杀时 tmux 一起死**：proot 与 tmux server 都是 App 进程的子进程，
> Android 按进程组 / cgroup 回收，没有任何外部托管者。
> `tmux new-session -A` 的 "断线重连不丢现场"，前提是**那个 server 还在**。

**真机证据（2026-10-07 · PGT-AN10 / Android 16）**

```
杀掉前：
  19157  com.example.zhengdao
  19954  └─ proot
  19960  │   └─ tmux            （客户端）
  19967  └─(ppid=1) tmux        （server，已 reparent 但仍在本 App 的 cgroup 里）
  19968      └─ -bash           （pane 里的 login shell）
  19977          └─ bash        （正在跑的 Agent）
执行 adb shell am force-stop com.example.zhengdao 后：
  ps -A | grep -E "zhengdao|proot|tmux"  →  0
```

**因此「回来还是它」是"重放启动命令"，不是 attach**

`TerminalActivity.restoreLastAgentIfAny()`：把当前 Agent 的 id 落盘
（`zhengdao-session` prefs 的 `current_agent`，进程被杀时它照样留着），
新会话起来、且本次请求**不带命令**时，重新执行该 Agent 的 `launchCmd`。
用户侧观感与"接回来"一致，**但不能指望 Agent 的对话上下文还在** ——
那是进程内的东西，随进程一起没了。

对照：切后台 30 分钟那种情况进程还活着，走的是**真正的 attach**
（同一个 pty、同一块 tmux 画面），现场原样保留。两种情况必须分开表述，
否则会写出"tmux 能扛住杀进程"这种经不起 force-stop 一句话检验的结论。

## E-019 · 2026-10-07 · hermes uv 包装器把自己覆盖成了 `uv.real`（无限 self-exec）

**原实现（有误）** —— AgentInstaller 注入的 guest 侧命令：

```sh
for d in /root/.hermes/tools/uv-*; do
  if [ -f "$d/uv" ] && [ ! -f "$d/uv.real" ]; then mv "$d/uv" "$d/uv.real"; fi
done
```

`mv` 之前**没有检查 `uv` 到底是不是真的 ELF 二进制**。
上一轮若已经把**包装器脚本**写在了 `uv` 这个路径上，下一轮就会把"包装器自己"
挪成"真身" `uv.real`；紧接着再写一份新包装器到 `uv` ⇒
包装器末尾的 `exec "$R"`（R = uv.real）执行到的还是包装器自己 ⇒
**无限自我 exec**（进程挂死、CPU 打满）。现场症状只是「安装卡住不动」，
完全看不出是包装器自噬。

**真机证据（2026-10-07）**

```
sha256(uv)      = 3b5fad6bcc337263e712b0c24b7fd764dd408748da1bab9d5da41fa2edd5b175
sha256(uv.real) = 3b5fad6bcc337263e712b0c24b7fd764dd408748da1bab9d5da41fa2edd5b175
两个文件各 1273 字节、内容逐字节相同（都是包装器，不是真身）
```

**更正为**

1. 包装器的落盘改到**宿主侧**：`EnvSelfHeal.ensureHermesUvWrappers`。
   proot 用 `-b $D/home:/root` 把 `filesDir/home` 绑成 guest 的 `/root`，
   两边是同一份存储，宿主写文件 == guest 里写文件。
   附带修掉一个一直没人提但很扎眼的毛病：那段 1.5KB 的 base64 原先写在
   注入命令里，bash 会把整行**回显**出来 —— 点一次「安装」先糊满一屏 base64，
   用户看不到任何进度。（用户 2026-10-07 报的"hermes 安装有问题"里最直观的一条。）
2. 该函数**只在 `uv` 是 ELF（魔数 `\x7FELF`）时才挪成 `uv.real`**，从根上堵住自噬。
   安装路径额外传 `ensurePinnedDir = true`：install.sh 的 `ensure_uv` 见路径上
   已有可执行文件就跳过下载，预置包装器即接管（这一条与旧实现同义，未改行为）。
3. 现场修复：删掉那个假的 `uv.real`。包装器会按设计自愈 ——
   `if [ ! -x "$R" ]` 分支从 GitHub（失败换 hermes 官方镜像）拉 pinned
   uv 0.12.3 并做 SHA256 校验后落位。

**教训**

`mv` / `rm` / `ln` 这类**就地改名**的操作，判据必须是**内容**
（ELF 魔数、sha256、文件大小量级），不能是**路径存在性**。
"路径上有东西"只说明有个东西在那儿，不说明**那是它**。
本项目已经因为同一类错误吃过一次亏（`run-as` 探针代表 App 真身 → E-005），
这是第二次：**"存在"不等于"是"**。

---

## E-020 · 2026-10-07 · 配好签名密钥后仍然发不出包：secret 编码与 `cancel-in-progress` 两处坑

> **编号说明**：本条最初写为 E-018；同一时段另一个工作树（`zhengdao-wt-session`）
> 也往这份 ERRATA 追加了两条并占用了 E-018 / E-019，合并进 main 时本条让号，改成 **E-020**。
> E-018 是「杀 App 再开，tmux 会把会话留住这个前提不成立」、E-019 是「hermes uv 包装器把自己
> 覆盖成了 `uv.real`」，**都不是本条**。

### 1. 现象 A：配了 secret，main 的 build 反而**整个 run 都起不来**

`8011702` 推上 main 后，`build` workflow 立刻 `startup_failure`，**0 个 job**（两次 rerun 同样）；
而 22 分钟前的 `6a59169` 是 success，两次的 `build.yml` blob 完全相同
（`a9b2690ab0dd3cf8ca75a65b7564176eac252f6a`）⇒ 不是 workflow 内容问题，也不是偶发。

### 2. 现象 A 的根因：secret 里存的是 keystore 的**二进制**，不是它的 **base64 文本**

`build.yml:104` 是 `KS_B64: ${{ secrets.DEBUG_KEYSTORE_B64 }}`，脚本体内对它做 `base64 -d`
（步骤名见 `:95`「还原 debug 签名密钥」）⇒ **secret 值必须是那段 base64 文本本身**。
本次配置时误写成 `SealedBox(pk).encrypt(base64.b64decode(value))`，把 2618 字节二进制传了上去。
二进制不是合法 UTF-8，GitHub 在 workflow **启动阶段**就拒绝整个 run：

> The secret DEBUG_KEYSTORE_B64 referenced by this workflow is not properly UTF-8 encoded and cannot be used by GitHub Actions.

**定位手法（关键，REST API 查不到）**：`startup_failure` 的原因**不在** run / jobs 接口里，
在网页的一个局部端点上：

```
https://github.com/pisces19860207/zhengdao/actions/runs/<run_id>/annotations_partial
```

用 `Invoke-WebRequest -UseBasicParsing` + 自定义 `User-Agent` 抓 HTML、剥掉标签即可读到上面那句话。
（`web_fetch` 打不开 github.com —— 报 `URL hostname "github.com" resolves to a non-public IP address`。）

修法：加密 **`value.encode("utf-8")`**（`value` = 文件里那段 3492 字符的纯 ASCII base64），
并在脚本里断言 `value.isascii()` 与 `len(base64.b64decode(value)) == 2618`。
重配后 rerun，**步骤 9「还原 debug 签名密钥」首次 success**，步骤 10/11（Debug / Release APK）也 success。

### 3. 现象 B：签名修好了，包还是没发出去 —— 被自己的**下一次推送**掐死

`build.yml:42-44`：

```yaml
concurrency:
  group: build-${{ github.ref }}
  cancel-in-progress: true
```

同一 ref 上的新 run 会**立刻取消**正在跑的那个。在它跑到第 12 步
「构建 Debian 13.7 RootFS（qemu 交叉构建，约 30–90 分钟）」时推了 `682dceb`，
旧 run（`37639534259`，attempt 3）被 `cancelled`，第 13–16 步全部 `skipped` ——
**第 16 步「发布到 Releases」（`build.yml:174`）根本没执行**，所以 `latest` 上仍然是旧包。

### 4. 教训

1. **发布步骤排在整条流水线的最后**（在 RootFS 之后），所以「构建成功」和「包发出去了」是两件事。
   判断发布是否真的发生，**必须看第 16 步「发布到 Releases」的结论**，不能只看 run 是不是绿的。
2. **main 上有构建在跑时，不要往 main 推任何东西** —— 哪怕只改文档。
   `group: build-${{ github.ref }}` + `cancel-in-progress: true` 意味着「推一次 = 掐一次」。
   要推文档就等构建跑完；或者先推分支（`v1.3` 不触发 build —— `build.yml:37-39` 只监听 `main` 与 `v*` tag）。
3. **遇到 `startup_failure`，先去 `annotations_partial` 看报错原文**，不要在 workflow 内容上反复怀疑自己。

---

## E-021 · 2026-10-08 · 「自己下的包，自己认不出」——下载缓存与自动安装检查各认一个文件夹

### 1. 现象（用户原话）

> 环境的包应该是下载到和 opencode bionic 一起的文件里……这样就可以不用重复下载了，之前就做了这个功能的，不知道为什么 zhengdao 文件夹里没有，而且我说的是**检测到的话就自动安装的，没有的话才需要下载**。

实测（设备 `AD3J023824001723` / PGT-AN10）：

| 位置 | 内容 |
|---|---|
| `/sdcard/Download/zhengdao/cache/`（**拉丁名**） | 只有一枚 `debian-13.7-base-arm64.tar.zst.part`（**99,352,567 B**，中断的残片） |
| `/sdcard/Download/证道/`（**中文名**） | `.zhengdao/scripts/*`、`opencode/opencode-2.0.22-1-aarch64.pkg.tar.xz`（68,606,212 B）—— **没有 debian 包** |

⇒ App 下载的包落进 A 目录，自动安装只查 B 目录，**两个文件夹互不相通**：
点过「开始下载」、包也真下过，重开 App 依然"检测不到"，只能再下 326MB。
用户要的那个功能**其实一直都在**（`6e16f16`/v0.6.0 就做了），是被这个不一致架空的。

### 2. 根因：两个文件夹从引入那天起就不一致

- `51cd425`（第三批）引入公共缓存：`rootfs/RootfsCache.kt` 的 `publicDir()` = `/storage/emulated/0/Download/zhengdao/cache`（拉丁名）。
- `6e16f16`（v0.6.0）引入「本地有安装包就免下载直装」：`TerminalActivity.findLocalArchive()` 只认 `/storage/emulated/0/Download/证道/debian-13.7-base-arm64.tar.zst`（**中文名 + 根目录 + 固定文件名 + >100MB**）。
- 两条路径各自演化，**没有任何一处同时看两个目录**。
  对照：opencode 那套从一开始就写对了 —— `oc/OcManager.kt:123` 注释「下载缓存（用户共享存储：Download/证道/opencode/，卸载重装不丢）」，重装 App 后是从缓存**重新解包、不重新下载**。用户说的"和 opencode 一起"就是这个意思。

附带缺陷：`RootfsCache.cleanupNonCurrent()` 按「后缀是 `.tar.zst`/`.tar.gz`」删文件。
把缓存目录**挪进用户共享工作区** `Download/证道` 之后，这个判据会误删用户/Agent 自己放进来的归档
⇒ 必须先把「哪些是我们的包」收紧到文件名以 `debian-` 开头。

### 3. 修法（2026-10-08，分支 `fix/rootfs-cache-shared-with-opencode`）

1. **缓存目录搬到与 opencode 同处**：公共目录改为 `/storage/emulated/0/Download/证道/rootfs/`（`SHARED_DIR_PATH` + `CACHE_SUBDIR`）。
2. **旧目录自动搬家**：`migrateLegacy()` 把 `Download/zhengdao/cache` 里的 `debian-*`（**含 `.part` 续传残片**）rename 过来，搬空则删旧目录；只跑一次（`legacyMigrated`），搬了写 `RunLog`。
3. **认包只认一种名字**：`isOurs(name) = name.startsWith("debian-")`，`isArchiveName()` 在其上再加后缀判据；`listArchives()` / `cleanupNonCurrent()` 一律用它 ⇒ 共享目录里的用户文件不被误删。
4. **「检测到就自动安装」收敛成一个入口**：新增 `RootfsCache.findLocalArchive(ctx, minBytes, preferredName)`，顺序 =
   `Download/证道/<文件名>`（兼容老位置）→ `Download/证道/rootfs/<文件名>`（新缓存）→ `listArchives()`（任意版本的完整包）。
   `TerminalActivity.findLocalArchive()` 改为委托它 ⇒ **自己下过的包、别处放着的包、旧版本回滚包，全都认**。
5. **文案与测试同步**：`ui/SettingsScreen.kt` 的缓存路径说明、「修复环境」的候选表；两个真机测试（`ExtractBaselineTest:29`、`CoreNativeExtractInstrumentedTest:27`）的候选路径加上新目录 —— 否则搬家后测试会找不到包并**静默跳过**（测试"通过"却什么都没验）。

> 附带修掉一个"假好消息"：`OcManager.checkUpdate()` 把「网络不通/接口报错」与「已是最新」都返回 `null`，设置页于是显示「OpenCode 已是最新」。现已拆出 `checkUpdateDetailed()`（`Available` / `UpToDate` / `Failed(reason)`），设置页与太极抽屉（新增入口）都如实显示失败原因。**「查不到」和「没有」不是一回事。**

### 4. 顺带清掉的一枚已失效残片

`latest` 的 rootfs 资产在签名修复那一跑里重新生成过：**326,580,168 B → 326,613,604 B**
⇒ 手机上那枚 99,352,567 B 的 `.part` 对应的是**旧资产**，续传必然 SHA 不匹配，只能删。
已用 `adb push` 把新包（sha256 `2f1406af1939f7f263b8191abdec6743ff99e8a87e47f2235adc3a6a3c3b1146`，与 `.sha256` 边车一致）
放到 `/sdcard/Download/证道/debian-13.7-base-arm64.tar.zst`（**根目录**：手机上那版 App 只认这里），并删掉旧 `.part`。

### 5. 教训

1. **同一个东西有两处"应该放哪"的定义，早晚会分叉。** 路径常量只能有一个来源；本次把「包放哪」收敛到 `RootfsCache` 一处，安装检查、修复环境、测试全部改成问它。
2. **缓存目录挪进用户工作区时，清理逻辑的判据必须同时收紧**：从"私有目录按后缀删"变成"用户目录按前缀删"，否则第一个受害者是用户自己的文件。
3. **失败与"没有"必须分开表达**（见上"假好消息"）：把"查不到"说成"已是最新"，用户就会以为功能不存在。

---

## E-022 · 2026-10-08 · R8 把 JNI 回调改名 —— release 包一装环境就 SIGABRT

### 1. 现象（用户原话）

> 还是不行的，要安装运行环境

用户 00:21 起连续点「安装运行环境」三次，每次都**直接退回主页**，Debian 环境永远装不上；
`assembleDebug` 的包一切正常，只有 `assembleRelease` 出来的包会崩。

### 2. 证据（`adb logcat -b crash -d -v time`，四次完全相同）

00:21:58 / 00:22:12 / 00:22:29（用户三次）/ 00:25:14（复现）：

```
F/libc: Fatal signal 6 (SIGABRT), code -1 (SI_QUEUE) in tid 23916 (Thread-6), pid 23782 (xample.zhengdao)
Abort message: 'No pending exception expected: java.lang.NoSuchMethodError: no static method
"Lcom/example/zhengdao/rust/CoreNative;.onProgress(Ljava/lang/String;Ljava/lang/String;)V"
...
#04 art::Thread::AssertNoPendingException() const
#10 pc 0000000000039dec  .../lib/arm64/libzhengdao_core.so (Java_com_example_zhengdao_rust_CoreNative_nativeExtract+1232)
```

### 3. 根因

- `nativeExtract` 本身没问题（JNI 静态绑定 OK），**是它反向调用的回调没了**：
  `rust/core/src/jni_bridge.rs` 里 `env.call_static_method(cls, "onProgress", "(Ljava/lang/String;Ljava/lang/String;)V", …)`。
- `app/proguard-rules.pro` 当时只有：
  ```
  -keep class com.example.zhengdao.rust.CoreNative { native <methods>; }
  ```
  —— 只保住了 `native <methods>`（Java→native 那一半），**没保被 native 按名字查找的普通方法**。
  `app/src/main/java/com/example/zhengdao/rust/CoreNative.kt:49-52` 的 `@JvmStatic fun onProgress(entries: String, name: String)`
  在 Java 侧没有任何调用点，R8 看不见 JNI 的调用点 ⇒ 判定"可安全改名/删除"。
- `dexdump` 对照（`classes.dex` 里 `Lcom/example/zhengdao/rust/CoreNative;` 的方法名）：

| 构建 | 方法列表 |
|---|---|
| 修复前（00:16 release） | `<clinit>`、`nativeExtract`、`nativeSha256Hex`、`a`（= 被改名的 `onProgress`） |
| 修复后（00:30 release） | `<clinit>`、`nativeExtract`、`nativeSha256Hex`、**`onProgress`**、`a` |

  （`app/build/outputs/mapping/release/mapping.txt` 里也查不到 `CoreNative.onProgress`，只有其它类的同名方法 —— 它被改了名。）
- 崩溃链：查找失败 → `NoSuchMethodError` 挂在当前线程 → Rust 侧 `let _ = (|| … )()` 吞掉了错误却**没有 `exception_clear()`**
  → `nativeExtract` 随后继续调 JNI（`new_string`）→ 命中 ART 的 `AssertNoPendingException` → `abort()` → 进程闪退回主页。

### 4. 修法（2026-10-08）

1. `app/proguard-rules.pro` 把被按名查找的成员一并 keep：
   ```
   -keep class com.example.zhengdao.rust.CoreNative {
       native <methods>;
       public static void onProgress(java.lang.String, java.lang.String);
   }
   ```
2. `rust/core/src/jni_bridge.rs` 的 `report_progress()` 兜底：失败一律 `env.exception_clear()`，
   并用 `PROGRESS_DISABLED`（`AtomicBool`）闩锁关掉这条旁路回调 —— 进度显示失败不该升级成进程崩溃。
3. 真机验收（release 包，`AD3J023824001723`）：点「安装运行环境」→ `I/CoreNative: 解压进度: …` 正常打点，
   `I/RootfsInstaller: Rust 解压完成: 20041 条目 973MB sha=2f1406af1939` → `RootFS 安装完成（Rust 路径）`，
   pid 不变（没崩）、零下载（直接用 `Download/证道/rootfs/` 里缓存好的包），终端进 `root@localhost:~#`。

### 5. 教训

1. **JNI 是双向的**：`native <methods>` 只保住了 Java→native 那一半；凡是 native 会**按名字查找**的成员（回调、构造函数、字段）都必须显式 keep —— R8 看不见 JNI 的调用点。
2. **同一份代码，debug 绿 ≠ release 绿**：这个 bug 在 debug 包上永远复现不了（不混淆）。凡是 native/反射/JNI 参与的路径，验收必须用 `assembleRelease` 的产物在真机走一遍。
3. **JNI 里"吞掉错误"必须连异常一起吞**：留下 pending exception 比抛异常更致命 —— 它会把下一个无关的 JNI 调用变成 `abort()`。
4. **CI 全绿只保证"编得出来、签得对名"，不保证"跑得起来"**：这一跑（`47d8066`）18 步全绿，release 包却一装环境就闪退；发布前的真机冒烟不能省。

## E-023 · 2026-10-08 · 终端「左边缘右滑返回」从来没真正生效过（两处静默失效）

### 1. 现象（用户原话）

> 还有就是在终端页屏幕右滑不能返回是为什么啊？

终端页（`TerminalActivity`）从最左边起手向右滑，**什么都不会发生**；而代码里明明有这段逻辑，
2026-10-07 的注释还写着"真机实测"。

### 2. 证据（真机 `AD3J023824001723`，release 1.3.0）

- 代码：`app/src/main/java/com/example/zhengdao/TerminalActivity.kt` 的 `installEdgeSwipeToClose()`，
  挂在 `R.id.terminal_native`（TerminalView）与 `R.id.terminal_root`（根 FrameLayout）两处；
  常量 `EDGE_SWIPE_WIDTH_DP = 32f`、`EDGE_SWIPE_TRIGGER_DP = 56f`；屏 1312×2848、density 3.5
  ⇒ 识别带 112px、阈值 196px。logcat `D/EdgeSwipe` 证明两个监听器都挂上了。
- `adb shell dumpsys window`（TerminalActivity 为焦点窗口）：

  ```
  mSystemGestureExclusion=SkRegion((53,141,112,313)(53,313,133,1291)(0,1291,133,1314)(0,1314,35,1991))
  ```

  —— **不是 `Rect(0,0,112,height)` 的整矩形，而是残缺碎块**：`view.post { … view.height.coerceAtLeast(1) }`
  执行时高度常为 0，被截成 1px 高。
- 注入滑动（`adb shell input swipe`，y=1400 扫到 x=700，200ms）逐点复现：

| 起点 x | 结果 | 归属 |
|---|---|---|
| 6px | 回主页 ✓ | 本页识别带（0–112px） |
| 90px | 回主页 ✓ | 本页识别带 |
| 130px | 回主页 ✓ | 系统边缘返回（排除区只到 ~133px，之外系统自己接） |
| 170px | **毫无反应 ✗** | 谁也不管的死区 |
| 6px 起手、只滑到 156px（dx=150 < 196px 阈值） | **毫无反应 ✗** | 阈值太严 |
| 6px→306px，700ms（慢速 dx=300） | 回主页 ✓ | 本页识别带 |

⇒ 真人拇指落点常在 40–55dp（140–190px），**正好落在死区里**；而"快速一甩"又常不到 196px。
两个**独立**缺陷叠加 = "怎么滑都不返回"。

### 3. 根因

1. **排除区在 `view.post` 里按 `view.height` 设置**，那一刻高度常为 0 ⇒ 变成 1px 高的废矩形（静默失效）。
2. **识别带 32dp 比真人落点窄**，而这 32dp 又被排除区从系统返回手势手里要走了
   ⇒ 识别带外、系统感应带内的 140–190px 成了"两边都不管"的死区。
3. **阈值 56dp 且只在 MOVE 判定**：快速一甩（实测 dx=43dp）与 MOVE 丢失的手势完全没有兜底。

### 4. 修法（2026-10-08）

- 手势判定改到 **Activity 层 `dispatchTouchEvent` 里"旁观"**（不消费任何事件），命中才 `finish()`
  —— 终端自己的点击、长按选词、纵向滚动一律照旧；旧实现用 `OnTouchListener` 吞掉按下，
  条带内的终端手势会一起被吃掉。
- 排除区挂在**窗口根 View**（`window.decorView`）上，布局完成后按真实高度设置、尺寸变化时重设。
- 识别带 32dp → **56dp**；阈值 56dp → **40dp**；纵向容差 40dp 且要求"横向占优"（`dx > dy`）；
  MOVE 与 **UP 双判定**（快甩兜底）。
- 命中时打一行 `D/EdgeSwipe`（`MOVE` / `UP 兜底` + dx/dy/startX），以后有争议直接看 logcat。

### 5. 教训

1. **"设置系统手势排除区"没生效是静默的**：`view.post` + `view.height` 是经典坑；验收要看
   `dumpsys window` 里的 `mSystemGestureExclusion` 是不是整矩形，而不是"代码写了就算做了"。
2. **手感阈值必须按真人落点回归**：注入 `input swipe` 从 x=6 起手永远成功，掩盖了 140–190px 的死区
   —— 用注入测手势时，起手点要覆盖真人可能的落点（含 40–60dp 这一段）。
3. **一次投诉里常常叠着两个以上独立缺陷**（这里是"排除区失效" + "阈值太严"）；只修一个，用户照样说不行。

## E-024 · 2026-10-08 · 「太极发送按钮点了没用」是误判 —— 但失败确实不可见（顺手补上）

### 1. 现象（用户原话）

> 太极对话框的发送按钮点了也没用啊

### 2. 实测结论：**发送是通的**

- 真机（release 1.3.0）：点输入框 → `adb shell input text "hello"` → 点发送键
  （uiautomator 给的真实 bounds `[1102,1795][1270,1963]`）⇒ 输入框清空、按钮变 ■（`isStreaming=true`）；
  `uiautomator dump` 里出现用户气泡 `hello`、`🧠 思考过程`、助手回复正文，
  会话标题也从「新会话」变成 `hello` ⇒ POST 被服务端接受（HTTP 2xx）。
- 用户"看着没反应"的两个原因：那段对话区**正好被他自己的画中画短剧小窗盖住**（覆盖约 y∈[419,1578]）；
  以及**第一次点在 (1189,1915) 没命中按钮**（偏了 36px），第二次点在按钮中心才生效。

### 3. 但我们确实做错了：失败不可见

`OcRepository.prompt()` 的失败只走 `ocLog()`，而 `ocLog` 写的是 **App 私有
`cacheDir/runlog/zhengdao-log.txt`**（`adb` 读不到、logcat 里也没有）—— 真失败时用户看到的
就是"点了没反应"。`ConnectionBanner` 又只在 `state.connection != Connected` 时出现，
连上以后发送失败没有任何提示。

### 4. 修法（2026-10-08）

`app/src/main/java/com/example/zhengdao/ui/taiji/TaijiScreen.kt` 的 `onSend` 改为
`repo.prompt(text).onFailure { Toast.makeText(ctx, "发送失败：…", LENGTH_LONG).show() }`。

### 5. 教训

1. **"点了没反应"先查"是不是真没反应"**：注入点击 + `uiautomator dump` 比看截图可靠
   （截图会被画中画小窗、软键盘遮挡误导）。
2. **关键失败路径不能只写进 App 私有日志**：用户界面必须有出口，否则"没坏"也会被当成"坏了"。

## E-025 · 2026-10-08 · 搬家包恢复后 hermes 必崩 —— 记录指向没随包搬来的依赖代

### 1. 现象（用户原话）

> 我安装好hermes agent后，让hermes把手机里的搬家文件里的东西搬过来，然后就出问题额了，
> 再重新打开APP用命令进入hermes后就出现这个难题了。

终端里敲 `hermes` 只得到这几行（逐字）：

```
hermes: source-update completion failed: [Errno 2] No such file or directory:
        '/root/.hermes/hermes-agent/uv.lock'; running with the previous dependencies
        - run `hermes update` to finish it
hermes: repairing the recorded dependency environment...
hermes: dependency repair failed: venv: recorded dependency lock is missing;
        refusing to drop plugins — retry, or run `hermes pm doctor`; run `hermes pm repair`
hermes: dependency environment is missing or outside this install:
        /root/.hermes/installs/8a4017c4cabfe15f/environments/3c17878dccdc4a2c89c75675056b0cb5/venv;
        run `hermes pm repair`
```

### 2. 根因（两条叠加；不是 App 的锅，但 App 侧确实少一层自愈）

**① 安装状态记录被跨机照搬。** 搬家包 `搬迁说明.txt` 明确写了 `installs/*/environments`（345M）
**有意不带**，但包里仍带着旧机的 `installs/8a4017c4cabfe15f/facts.json`，而它记录的依赖环境是旧机上的
`.../environments/3c17878dccdc4a2c89c75675056b0cb5/venv` ⇒ 新机上这个目录不存在。

- `hermes_bootstrap.py:582-620`：`_pm_repair = command_argv(sys.argv[1:])[:2] == ["pm","repair"]`；
  非 `pm repair` 时先 `prepare_launch()`（就是那句 source-update completion failed），再
  `recover_if_needed()` + `activate_dependencies()`，抛错即 `hermes: {exc}; run \`hermes pm repair\`` 退出 1。
- `pm/environments.py:106` `runtime_facts_path(project_root) = install_state_dir(project_root)/"facts.json"`；
  `:297-318` `_recorded_venv()` 要求 `packages.venv.environment` 位于 `install_state_dir/environments`
  之下且含 `pyvenv.cfg`，否则 `RuntimeError("dependency environment is missing or outside this install: …")`。
- `pm/packages.py:397-455` `apply(..., repair=True)`：`repair` 时先读 `prior`，若 prior 记了
  environment/resolved_lock，就要求 `recorded == previous/"workspace"/"uv.lock"` 且该文件存在，否则
  `InstallError("recorded dependency lock is missing; refusing to drop plugins")` —— 所以记录指向
  不存在的代时 **`pm repair` 会拒绝修**，必须先清掉（或改写）那条记录。

**② 一次被中断的自我更新删掉了 `uv.lock`。** 本机 04:31–04:40 那次更新删了受 git 管理的
`hermes-agent/uv.lock`（+`flake.lock`）却没重建，留下 `.hermes-update-in-progress.lock`(04:40) 与
`.repair-incomplete` ⇒ 每次启动都先报 source-update completion failed。**残酷处**：非 `pm` 子命令
全都在 bootstrap 阶段退出，`hermes update`（连 `--help`）也救不了 —— 能用的只有
`hermes pm doctor` / `hermes pm repair`。

### 3. 修法（已真机实测通过）

```bash
cd /root/.hermes/hermes-agent && git checkout -- uv.lock flake.lock   # ① 恢复被删的锁
# ② 清掉 facts.json 里指向不存在代的那条记录（或改写为存在的代），并删陈旧标记
rm -f ~/.hermes/installs/*/.recovery.lock ~/.hermes/installs/*/.repair-incomplete \
      ~/.hermes/.hermes-update-in-progress.lock
hermes pm repair                                                     # ③ 重建并登记依赖环境
```

实测：`pm repair` 建出 `environments/34aa9d1ad79c46e19c8222a032404c59/`（359M）并写回 facts.json；
随后 `hermes --version` = `Hermes Agent v0.21.5+9110.g8a33891 · Python 3.14.7 · Up to date`，
`hermes --help` / `hermes doctor` 全部正常。**身份数据一件没丢**，与 `打包基线.txt` 逐项吻合：
sessions **65** / messages **7057** / `MEMORY.md` 4482 B / `USER.md` 3180 B（`SKILL.md` 126 个，
比基线的 79 多，方向是"多"不是"丢"）。顺手删掉孤儿旧代 `98a9a76854de40bc92aaec0d97848d78`（353M）。

### 4. 搬家包侧已补上（2026-10-08）

`/sdcard/Download/Hermes搬家-20261005-113244/`：

- `一键恢复.sh` 追加「依赖环境自愈」段（解包后自动恢复锁 → 清失效记录 → 需要时跑 `pm repair`），
  原文件备份为 `一键恢复.sh.orig-20261005`；
- 新增独立脚本 `修复依赖环境.sh`（可随时单独跑，每步都有输出）。

### 5. 教训

1. **"安装状态记录"是不能跨机照搬的状态**：带了 `facts.json` 却没带它指向的环境目录，新机启动即崩。
   打包要么连环境一起带（345M），要么恢复后立刻重建 —— 两者都不做就是这次的事故。
2. **恢复脚本每次都会把坏记录写回去**：所以"再跑一次恢复"不是解药，修法必须与恢复脚本绑在一起（见第 4 节）。
3. **CLI 挂掉时要绕过它去读源码**：这类故障下只有 `pm` 子命令活着，定位全靠直接读
   `hermes_bootstrap.py` / `pm/*.py`（本文行号都来自真机里那份固定版本源码）。
4. **在 guest 里驱动命令要防编码坑**：往 `/sdcard` 推脚本**别用** Windows PowerShell 的
   `Set-Content -Encoding UTF8`（写 BOM ⇒ guest 里报 `/sdcard/x.sh: 1: #!/bin/sh: not found`）；
   用无 BOM 的 LF 文本写好后 `adb push`，终端里拼命令时空格要单独 `input keyevent 62`
   （`input text "a b"` 只送出空格前那段）。
5. **脚本里"要删的东西"必须逐条明确**：本次我写了 `rm -fv "$FACTS" …`，把刚写好的状态记录也删了
   （幸而 `pm repair` 在没有记录时会退回"从源码 `uv.lock` 重建"，反而修好了）。
6. **证道侧的缺口（已补，见第 6 节）**：`app/src/main/java/com/example/zhengdao/terminal/EnvSelfHeal.kt` 的启动自愈
   已覆盖 DNS / hosts / 时区 / uv 包装（`ensureHermesUvWrappers`），但**没有**覆盖"依赖环境记录失效"
   这一层 —— 事故当天已按下面第 6 节补上（宿主侧纯文件检测 + 首页体检 / 设置页入口）。

### 6. 证道侧已补上（2026-10-08 当日实施）

事故当天就补了这层检查，理由是"修好了这一次，下一次恢复搬家包还会崩"：

- 新增 `app/src/main/java/com/example/zhengdao/terminal/HermesEnv.kt`：宿主侧**只读**判定，
  读 `installs/*/facts.json` 的 `packages.venv.environment|resolved_lock`，按与
  `pm/environments.py:297-318` **同条件**判可用（环境目录在且含 `pyvenv.cfg`；记了锁则锁也得在）。
  宿主 `filesDir/home` 与 guest `/root` 是同一个 bind，所以这里改 `facts.json` 等于在 guest 里改。
- 修复分两层（**修得上就当场修，修不上绝不装作能修**）：
  - 记录指向不存在的代、盘上还有**完整**代（`venv/pyvenv.cfg` + `workspace/uv.lock` 都在）⇒
    宿主侧把指针改到 `maxByOrNull { lastModified() }` 那代，先备份 `facts.json.bak-证道<时间戳>`；
    一代都没有 ⇒ **删掉这条失效记录**（留着会让 `pm repair` 以 "recorded dependency lock is missing" 拒绝重建）；
  - 要重建 Python 环境 / 要 `git checkout -- uv.lock flake.lock` ⇒ 脚本落盘
    `Workspace.hostDir/.zhengdao/scripts/hermes-env-repair.sh`（`res/raw/hermes_env_repair.sh`），
    在终端里跑 `bash /workspace/.zhengdao/scripts/hermes-env-repair.sh`，过程用户看得见。
- 入口两处：`EnvHealth.inspect` 新增 `hermes-deps` 项（`Check` 增加 `terminalCmd` 字段表达
  "需要进 guest 的修复"），首页状态卡点「修复」即可；设置页新增 `SectionCard("Hermes 依赖环境")`
  （「一键修正记录」/「在终端里修复」+ 状态说明）。没装 Hermes 时直接跳过、不报警。
- 单测 `app/src/test/java/com/example/zhengdao/terminal/HermesEnvTest.kt` 锁住三件事：
  修得上要修（指针改指完整代、备份在、`facts.json` 不丢）、修不上要如实说（一代都没有 / 缺源码锁 /
  没记录 ⇒ `canRepairOnHost=false`）、坏 `facts.json` 不抛异常也不乱改文件。

## E-026 · 2026-10-08 · 会话退出回调不认"是哪条会话"——换 Agent 时新旧会话互相误杀（已修）

**一句话**：`app/src/main/java/com/example/zhengdao/terminal/SessionManager.kt` 的退出清理只看
「当前会话跑没跑」，不看「结束的是不是当前这条」；而换 Agent 走的是"先 finish 旧会话、立刻装上
新会话"，旧会话的 `onSessionFinished` 是引擎读线程**稍后**回来的，于是这条陈旧回调有机会把
**刚建好、还没 spawn** 的新会话当成"刚结束的旧会话"清掉。

### 1. 现场（代码时序）

- 旧写法（WorkBuddy 单会话改动那版）：

  ```kotlin
  override fun onSessionFinished(finishedSession: TerminalSession) {
      val code = runCatching { finishedSession.getExitStatus() }.getOrDefault(-1)
      onFinished(code)                       // ← 只传退出码，不传"是哪条会话"
  }

  private fun onFinished(code: Int) {
      mainHandler.post {
          if (session?.isRunning == false) { // ← 判的是**字段现在那条**，不是 finishedSession
              session = null; startedAtMs = 0L
              setCurrentAgent(null)          // 抹掉 Agent 记录
              …; SessionService.stop(it)     // 停前台服务
              onSessionDied?.invoke(code)    // 视图据此关页
          }
      }
  }
  ```

- 触发路径：`start()` → `killInternal(context, clearAgent = false)` → `s.finishIfRunning()`（旧会话）
  → 紧接着 `session = TerminalSession(...)`（新会话）。新会话**首次 `updateSize` 之前 `isRunning` 也是 false**
  ——这一点本文件 `:112-119`（`hasSession` 的注释）自己写明了，是为"onNewIntent 复用实例"专门加的宽判。
  两个"false"一条时序上撞在一起，就成了误杀。
- 后果按严重度递增：Agent 记录被抹（下次进终端"接不回来"，用户验收第 5 条直接失效）→
  前台服务被停（退到后台可能被系统杀，长任务断）→ 视图收到 `onSessionDied` 关页。
- 触发条件是时序，不是必现：`mainHandler.post` 的 runnable 要等当前主线程消息跑完才执行，
  而 attach + 布局 + `updateSize`（spawn）常常就在同一条消息里 ⇒ 多数时候新会话已经 spawn、
  `isRunning == true`，误杀不发生。**它是一颗"竞态地雷"，不是每次必炸**——这也是它没在真机验收里
  被抓到的原因。

### 2. 修法（`app/src/main/java/com/example/zhengdao/terminal/SessionManager.kt:192-222`）

只做一件事：**清理必须比对会话身份**，陈旧回调一律丢弃。

```kotlin
override fun onSessionFinished(finishedSession: TerminalSession) { …
    onFinished(finishedSession, code) }

private fun onFinished(finished: TerminalSession, code: Int) {
    mainHandler.post {
        if (session !== finished) return@post   // ← 结束的不是当前这条：丢掉
        if (!finished.isRunning) { …原有清理… }
    }
}
```

行为差异只在"旧会话的回调迟到"这一种情形：旧写法误清新会话，新写法直接返回（此时
`killInternal` 早已做完它该做的清理）。手动 kill 的路径两种写法等效——`killInternal` 把
`session` 置 null，旧写法的 `session?.isRunning == false` 求值为 `null == false` = false，
本来也不进清理分支。

### 3. 验证

- `.\gradlew.bat :app:testDebugUnitTest :app:assembleRelease` = `BUILD SUCCESSFUL in 1m 25s`，
  单测 **58 例 0 失败**；产物 `app/build/outputs/apk/release/zhengdao-1.3.0-release.apk`。
- 真机：`adb install -r` 后进终端，shell 正常、`tmux ls` 仍只有一条 `zhengdao:`（单会话模型的硬约束）。
- ⚠️ **诚实说明**：这个竞态是**读代码读出来的**，不是真机复现出来的（时序窗口窄、且被
  "post 要等主线程消息跑完"天然掩护）。上面的真机验证证明的是"没改出新问题"，
  不等于"复现过并修好了"。要真正复现，得在主线程里人为插一段延迟把 attach 推后一条消息
  ——那属于构造性测试，本次没做。

### 4. 教训

1. **回调里判"是不是我这条"要用身份（`===`），不要判"字段现在是什么"**：字段随时会被下一条会话
   改写，而回调天生是异步迟到者。凡 `handler.post` + 共享可变字段的组合，都要先问
   "这条消息执行时，字段还会是当初那个值吗"。
2. **"spawn 前 `isRunning == false`"这个宽判是双刃剑**：它救了 `onNewIntent` 那条路
   （见 `:112-119`），同时给所有"看 `isRunning` 判死"的地方埋了同一个坑。用它的地方都要复查。
3. **单会话模型的清理动作都很重**（抹记录 + 停服务 + 关页），误杀的代价远大于漏杀 ——
   宁可漏清（下次进终端再清），不可错杀。

## E-027 · 2026-10-08 · 「`cargo test` 缺 host C 编译器」其实是 PATH 问题；Rust 单测此前**没有任何自动化入口**

**一句话**：`rust/README.md:70-75` 把 `cargo test` 失败记成"本机没有 host C 编译器"，
但这台机器上 **w64devkit 一直装着**，只是没在 `PATH` 里；把它挂上后 `cargo test` 8/8 全绿。
同一次核对还发现：**Rust 逻辑层的 8 个单测既不在本机日常流程里，也不在 CI 里**（`ci.yml` 只有
`:app:testDebugUnitTest`，`build.yml` 里没有任何 `cargo` 步骤）⇒ 已加进 `ci.yml`。

### 1. 现场

- 报错原文（本机重跑，与 README 记载一致）：

  ```
  cargo:warning=Compiler family detection failed due to error: ToolNotFound:
    failed to find tool "gcc.exe": program not found
  error occurred in cc-rs: failed to find tool "gcc.exe": program not found
  ```

- 但 `rust/.cargo/config.toml` 的 host 段**自己写着** w64devkit 的路径
  （`[target.x86_64-pc-windows-gnu] rustflags = ["-C","dlltool=C:/Users/guoli/w64devkit/w64devkit/bin/dlltool.exe"]`）
  ⇒ 顺着这条线索查：`C:\Users\guoli\w64devkit\w64devkit\bin\gcc.exe`（1,772,032 B，GCC **15.2.0**）在盘上。
- 同一个坑还有第二层：rustup 自带的 `…\toolchains\stable-x86_64-pc-windows-gnu\lib\rustlib\x86_64-pc-windows-gnu\bin\self-contained\x86_64-w64-mingw32-gcc.exe`
  **能跑、不能编**——它只是个链接驱动，没有 `cc1`：

  ```
  x86_64-w64-mingw32-gcc.exe: fatal error: cannot execute 'cc1': CreateProcess: No such file or directory
  ```

  所以"把 rustup 那个 gcc 挂上 PATH"是**错的解法**（会以另一种报错失败，比"找不到 gcc"更难查）。

### 2. 解法

```powershell
$devkit = 'C:\Users\guoli\w64devkit\w64devkit\bin'
$env:PATH = "$devkit;$env:PATH"
$env:CC   = "$devkit\gcc.exe"
Set-Location rust; cargo test -p zhengdao_core --release
```

实测：`test result: ok. 8 passed; 0 failed`（sha256 4 例 + extract 4 例），冷编 41.69s、热跑 0.09s，
**退出码 0**。注意 PowerShell 会把 stderr 上的 `warning: unused …` 当成 `NativeCommandError`
（exit 1 假失败），判成败要看 `test result:` 那行。

### 3. 补上的自动化（`.github/workflows/ci.yml`）

在「全量单元测试」之后加两步：Rust 逻辑层单测 + cargo 缓存（key 用已入库的 `rust/Cargo.lock`）。

```yaml
      - name: Rust 逻辑层单测（PC 层纯逻辑；ubuntu runner 自带 gcc，无需本机那套 w64devkit）
        run: cd rust && cargo test -p zhengdao_core --release
```

理由：`rust/core/src/tests.rs` 那 8 例（路径穿越拒绝、SHA 不匹配不落盘、zstd 端到端文件树、
gzip 兜底壳…）是解压主路径的安全网，此前**只在人手动跑**；而 host C 编译器的前置在 Windows 上
还依赖 w64devkit 在不在 PATH —— 放到 ubuntu runner（自带 gcc）反而最省事。

### 4. 教训

1. **"缺工具"的报错，先找工具，再装工具**：`Get-ChildItem ~ -Recurse -Filter gcc.exe` 之前，
   别相信"这台机器没有 C 编译器"。本仓库的 `.cargo/config.toml` 里就留着工具位置。
2. **同名工具可能只是个壳**：rustup 的 `x86_64-w64-mingw32-gcc.exe` 有 `--version` 但没 `cc1`，
   拿它当 `CC` 会得到"cannot execute 'cc1'"，比"找不到 gcc"更难定位。
3. **"本机能跑"不等于"有人跑"**：一个测试只要有"环境前置"，就一定会退化成"只在某台机器上手动跑过"。
   把 RS 层单测放进 CI，比在 README 里写"记得先装 gcc"可靠得多。

> 本节贴的 `run: cd rust && cargo test …` 是**改造前**的单行版本；首次真跑红了之后，
> 这一步已被 E-028 加强为"失败时把日志要点抛成 annotation"（本仓库 Actions 日志未登录读不到）。

## E-028 · 2026-10-08 · Rust 单测接进 CI 的**第一个产出**：测试自己的平台盲区（symlink 的 linkname 写成根相对）+ 7 个字面 NUL 字节

**一句话**：给 `ci.yml` 加「Rust 逻辑层单测」后第一次真跑（`37719284097 ci @20b0082`）就红了，
但**红的不是产品代码，是测试夹具自己写错了**：`make_archive()` 给 symlink 的 linkname 写成
`data/hello.txt`（根相对），而 tar 规范里 symlink 的 linkname 是**相对链接所在目录**的路径；
提取器照磁盘原样落盘（与 Kotlin 版 `RootfsInstaller.kt` 对拍一致 = **正确**），于是 unix 那条
"读穿内容"的断言读不到文件而 panic。这条断言在 Windows 上被 `#[cfg(unix)]` 整块 cfg 掉 ——
**它从来没在开发机上跑过**，是 E-027"只在某台机器上手动跑过"的直接后果。

### 1. 现场

- `37719284097 ci @20b0082`：唯一红的是新加的 `Rust 逻辑层单测（PC 层纯逻辑，失败即红）`，
  其余全绿（编译 Debug APK ✓ / 全量单元测试 ✓ / Android Lint ✓）；后两步「R8 冒烟」
  「上传 release APK」被 skipped。
- check-run annotations（匿名可读）只有一行：
  `.github :: Process completed with exit code 101.` —— 101 是 **cargo 自己的错误码**
  （编译错误或测试失败都会是它，光看码分不出来）。**Actions 日志匿名读不到**：
  `GET /repos/{owner}/{repo}/actions/jobs/{id}/logs` = `403 Must have admin rights to Repository.`，
  网页端 `…/actions/runs/<id>/job/<jobid>` 显示 "Sign in to view logs" ⇒ 这次是**读代码**定位的。

### 2. 根因

- `rust/core/src/tests.rs` 的 `make_archive()`：

  ```rust
  hdr_link.set_link_name(format!("{base}/hello.txt"))   // base = "data"
  ```

  写进 tar 的 linkname 是 `data/hello.txt`（**根相对**）。tar 里 symlink 的 linkname 是
  **相对链接所在目录**的路径 —— 正确写法是 `hello.txt`。
- `rust/core/src/extract.rs:149-166` 把 linkname **原样落盘**（GNU tar 也照磁盘原样记录；
  Kotlin 版同语义）⇒ `out/data/link` 实际指向 `out/data/data/hello.txt`（不存在）。
- 只有 unix 会走到读穿内容那句：`fs::read(out.join("data/link")).unwrap()`
  → `No such file or directory` → panic → 测试失败 → cargo 退出码 101。
- Windows host 上 symlink 走 `#[cfg(not(unix))]` 的"空文件占位"分支，**整段 unix 断言被 cfg 掉**
  ⇒ 一直是绿的。

### 3. 修法

- 夹具按规范写：`hdr_link.set_link_name("hello.txt").unwrap();`
- unix 断言由"读得到内容"加强成两条：
  `assert_eq!(fs::read_link(out.join("data/link")).unwrap(), Path::new("hello.txt"))`
  \+ 读穿内容与 `data/hello.txt` 相等。
- 补 Windows 侧断言：`#[cfg(not(unix))]` 下
  `fs::metadata(out.join("data/link")).unwrap().len() == 0`（占位文件必须是 0 字节）。
- 顺手清 warning：`hdr_hard.set_link_name(...)` 的 `io::Result` 补 `.unwrap()`（原来 `unused Result`）；
  删掉未用的 `use std::io::Write;` 与 `let src = fs::File::open(&tar_path).unwrap();`；
  `extract.rs` 里 `let link = …` 挪进 `#[cfg(unix)]` 块内（非 unix 上是未使用变量）。
- 结果：本机 `cargo test -p zhengdao_core --release` = **8 passed / 0 failed、0 warning**
  （改动前 6 条 warning），编译 12.98s / 热跑 0.04s。
- 顺带修掉一个埋了很久的坑：`tests.rs` 里有 **7 个字面 NUL 字节**，让 git 把这个纯文本文件
  当成二进制（`git diff` 只显示 `Bin 8054 -> 8967 bytes`）；改成 `\0` 转义后它恢复成文本文件，
  以后能正常出可读 diff。

### 4. 让 CI 的失败看得见（同一轮改造的步骤）

本仓库 Actions 日志未登录读不到，但 **check-run annotations 匿名可读** ⇒ cargo 步骤改成失败时抛 annotation：

```yaml
      - name: Rust 逻辑层单测（PC 层纯逻辑，失败即红）
        run: |
          cd rust
          set +e
          cargo test -p zhengdao_core --release 2>&1 | tee /tmp/cargo-test.log
          code=${PIPESTATUS[0]}
          if [ "$code" -ne 0 ]; then
            grep -E '(^error|^warning: unused|FAILED|panicked|assertion|^ *-->|left:|right:)' /tmp/cargo-test.log \
              | tail -n 8 | while IFS= read -r line; do
                  echo "::error title=cargo test 失败::${line}"
                done
            echo "::error title=cargo test::退出码 ${code}（完整输出见本步骤日志）"
          fi
          exit "$code"
```

### 5. 教训

1. **`#[cfg(unix)]` 里的断言在 Windows 上等于不存在**："本机全绿"完全不能代表它跑过。
   平台分支的测试必须跑在**对应平台的 runner** 上 —— 这也是把 Rust 单测放 ubuntu runner 的额外收益。
2. **夹具要符合格式规范，别让实现迁就夹具**：如果为了"让测试过"去 rebase linkname，
   就会与 Kotlin 版提取器的语义分叉，把一条**正确**的行为改坏。对拍纪律优先于"测试先绿"。
3. **cargo 的退出码 101 分不出编译错误与测试失败**：CI 上要么把日志要点抛成 annotation，
   要么本地按平台复现；只盯着 "exit code 101" 会一直在错误的方向上找。
4. **一个"从没跑过"的测试，等于没写**：这次红是好事 —— 它证明新加的入口真的在跑。

---

## E-029 · 2026-10-08 · 「发布到 Releases」排在 30–90 分钟的 RootFS 后面 ⇒ 任何人一推送，发布就永远走不到（E-020 §3 的结构性修法）

### 1. 现场

用户侧可见的现象只有一个：**下载页停在旧包**。

| 事实 | 证据 |
| --- | --- |
| 滚动版 `latest`（「证道 · 最新构建」）的 APK 停在 10-07 | `zhengdao-1.3.0-release.apk` = 4,179,922 B，`updated_at` = 2026-10-07T17:25:57Z |
| RootFS 包同样停在 10-07 | `debian-13.7-base-arm64.tar.zst` = 326,606,222 B，`updated_at` = 2026-10-07T17:26:07Z |
| 2026-10-08 一整天的修复一个都没进下载页 | E-021（缓存目录与自动安装）、E-025/E-026（依赖环境与会话自愈）、体检第 10 项 —— 全都不在 `latest` 里 |
| 当天最后一次能观察到的 run | `build @c142389`（run `37720101957`）11:14:36 = `cancelled` |

那个被取消的 run 的步骤列表（`GET /repos/{owner}/{repo}/actions/runs/{id}/jobs`，匿名可读）：

```
编译 Debug APK（开发者用）                              success
编译 Release APK（最终用户用；R8 + 资源收缩 + lintVital）  success
校验 release 包的签名真的是存量那把 key                   success
构建 Debian 13.7 RootFS（qemu 交叉构建，约 30–90 分钟）    cancelled   ← 掐死在这里
修正产物目录属主 / 收集产物 / 上传产物 / 发布到 Releases     skipped
```

⇒ **APK 早早就编好了、签名也验过了，但"发出去"这一步一次都没轮到。**

### 2. 根因

**两条叠加，致命的是顺序。**

1. **发布排在长步骤后面**：`.github/workflows/build.yml` 里「发布到 Releases」是第 16 步，
   紧跟在「构建 Debian 13.7 RootFS（qemu 交叉构建，约 30–90 分钟）」之后。
2. **长步骤之后就是死区**：同一文件的

   ```yaml
   concurrency:
     group: build-${{ github.ref }}
     cancel-in-progress: true
   ```

   让同一 ref 上只保留最新一次 run —— 任何新推送都会**立刻取消**正在跑的那个。
   掐死点本身是随机的，但只要落在 RootFS 那 30–90 分钟里，**后面的发布步骤 100% 走不到**。

⇒ 组合效果反直觉：**推得越勤，发布越不可能完成**。这解释了"有时候能发、有时候一直不发"。

放大因素：RootFS 每次推送都重建（哪怕 `rootfs/` 一个字节都没改），于是那 30–90 分钟的"死区"
几乎每次推送都在，安全窗口被压到接近于零。

### 3. 与 E-020 §3 的关系（这不是新坑）

`E-020`（2026-10-07）的 §3「现象 B：签名修好了，包还是没发出去 —— 被自己的**下一次推送**掐死」
记的就是同一件事，当时的收尾是**人工纪律**：

> **main 上有构建在跑时，不要往 main 推任何东西** —— 哪怕只改文档。

那条纪律第二天就失效了：10-08 用户自己在网页端合并了 PR #7（`cce6dea`，只是加了一个 `LICENSE`），
照旧把在跑的 run 掐死在 RootFS 上。**要求人记住一条与"发布"无关的纪律，是设计问题，不是纪律问题。**

### 4. 修法（`.github/workflows/build.yml`）

1. **把发布排到长步骤之前**。新顺序：

   ```
   编译 Debug/Release APK → 校验产物签名（E-014 的硬关卡）→ 收集 APK →
   **发布 APK 到 Releases** → 构建 RootFS → 收集/发布 RootFS 包
   ```

   发布仍在签名校验之后（把关不放松），但不再位于任何可能被取消的长步骤之后。
2. **RootFS 改成按输入变化触发**（不再按事件触发）：`actions/checkout` 加 `fetch-depth: 0`，
   新增步骤「判断本次推送要不要重建 RootFS」：

   | 条件 | 结论 |
   | --- | --- |
   | `github.event_name == 'workflow_dispatch'` | 重建 |
   | `git diff --name-only $before $sha` 命中 `^rootfs/` | 重建 |
   | 拿不到 `github.event.before`（全零 / tag 推送 / 强制推送 / 首次） | 保守重建 |
   | 其余（纯 App 代码、文档改动） | **跳过**，省 30–90 分钟 |

   「安装 RootFS 构建依赖」「构建 RootFS」「收集 RootFS 产物」「发布 RootFS 包」四步挂 `if`；
   tag 推送不重建（正式 Release 只发 APK，RootFS 包由滚动版 `latest` 提供）。
3. 步骤拆分：`收集产物` → `收集 APK 产物` + `收集 RootFS 产物`；
   `发布到 Releases` → `发布 APK 到 Releases` + `发布 RootFS 包到 Releases`。
   `release` job（tag 正式版）用的产物名 `zhengdao-build-${run_number}` 不变。
4. **保留 `cancel-in-progress: true`**：新顺序下被掐死最多损失一次 RootFS 重建，
   不再影响发布；保留它可以避免连推时排队堆积。

定盘提交 `fa8d467`（分支 `fix/ci-release-before-rootfs`）。

### 5. 验证

- `yaml.safe_load()` 解析通过（PyYAML 6.0.3）；步骤表 20 步，顺序断言
  `校验签名(#13) < 发布APK(#15) < RootFS(#16)` = `True`。
  ⚠️ 但**"yaml 能解析"不等于"GitHub 认"** —— 真正的验证是下一次 run 能起来。
- 上线后应观察到：推送后**几分钟内**（而不是几十分钟后）`latest` 的 APK `updated_at`
  变成本次 run 的时间；`rootfs-files/` 的 zst 保持不变；run 的步骤列表里 RootFS 四步显示 `skipped`。
- 观测结果按惯例补记在 `docs/FEATURE-LEDGER.md` 的当日块里。

### 6. 教训

1. **"收尾动作"绝不要排在可能被取消的长步骤后面**。发布、上传、通知这类"最后一公里"
   一旦排在长步骤之后，就会变成永远跑不到的死代码 —— 而且它不报错，只会**静默地什么都不做**。
2. **重活要按输入变化触发，不要按事件触发**。`rootfs/` 没变时重建 30–90 分钟纯属浪费，
   还顺带把发布窗口压到几乎为零。
3. **"CI 是绿的"不等于"东西发出去了"**。这次前面的步骤全绿，红/取消只发生在最后；
   要盯的是 **Releases 资产的 `updated_at`**，不是 run 的结论。
4. **用人工纪律兜住设计缺陷，第二天就会失效**（E-020 §3 → 本条目）。
   一条需要"记住在构建跑着的时候别推 main"的纪律，迟早会被一次"只是合个 LICENSE"打破。
5. **可读的证据源决定排查速度**：Actions 日志未登录读不到（E-028 已记），
   但 run 的步骤结论 + Releases API 的资产时间戳**匿名可读**，靠这两样就定了案。

---

## E-030 · 2026-10-08 · 「发布」前面还站着 154 秒的诊断与 54 秒的调试包：把发布路径排干

**一句话**：E-029 把发布提到了 RootFS 之前，但发布**前面**仍排着三个只为开发者/排查服务的步骤
（keystore 诊断 154 秒、Debug APK 编译 54 秒、为 RootFS 腾盘的 38 秒）——它们不该挡在"用户能下载"之前。

### 1. 现场（E-029 上线后第一次跑：`build #147 @7d4953a`）

run 起于 `03:30:15Z`，`发布 APK 到 Releases` 完成于 `03:37:50Z` ⇒
**从 run 开始到用户能下载 = 7 分 35 秒**（发布这次**成功了**：`latest` 的
`zhengdao-1.3.0-release.apk` = 4,190,625 B、`updated_at = 2026-10-08T03:37:50Z`，
"下载页的包不是最新"就此了结）。但步骤耗时表里，发布前站着这些：

| 步骤 | 耗时 | 服务对象 |
|---|---|---|
| 诊断（常驻）：AGP 到底认哪把 keystore | **154s** | 排查（E-014 §8 的常驻诊断） |
| 编译 Debug APK（开发者用） | **54s** | 开发者从 Actions 页下载 |
| 释放磁盘空间（约 10GB） | **38s** | 只有 RootFS 构建需要 |
| 安装 NDK r27.2 | 28s | 构建本身，必须 |
| 缓存 Gradle | 9s | 构建本身，必须 |
| 编译 Release APK | 119s | 构建本身，必须 |

对照：`ci #37 @7d4953a` = success、5.4 分钟；其中 `R8 冒烟` 120s 与 `build.yml` 的
`assembleRelease` 在同一 commit 上**完全重复**（那边还多一道对着产物验签）。

### 2. 改法（4 处，都是"让路"，不是"减活"）

1. **keystore 诊断 → 仅失败/手动触发时跑，并挪到发布之后**：
   `if: failure() || github.event_name == 'workflow_dispatch'`。它的价值是"出了事一眼定案"，
   而**真正的把关**是「校验 release 包的签名」那步对着**产物**验（E-014 §7）——
   日常构建不需要每次多付一次 Gradle 调用。
2. **Debug APK 编译 → 挪到发布之后**，并加一步「收集 Debug APK 产物」把它补进 artifact。
3. **释放磁盘空间 → `if: steps.rootfs_needed.outputs.rootfs == 'true'`**（那 10GB 是给 RootFS 腾的）。
4. **`ci.yml` 的 R8 冒烟（含其 artifact 上传）→ 只在 `pull_request` / `workflow_dispatch` 跑**：
   main 上 `build.yml` 每次都编 release 且多一道验签，重复劳动没有意义；PR 上保留快反馈。

**刻意没做**的两件：合并 `ci`/`build` 两个 workflow 去重（省不了几分钟，却把"快反馈"与"发布"
两条职责搅在一起）；给 RootFS 做缓存复用（30–90 分钟 → 几分钟的诱惑很大，但 qemu 交叉构建的
半成品缓存有正确性风险，而 rootfs 极少变——真嫌慢的正道是本地构建后手动上传）。

### 3. 验证

- 解析 + 顺序断言（PyYAML 6.0.3）：`build.yml` 21 步，
  `编译 Release APK(#10) < 校验签名(#11) < 收集 APK(#12) < 发布 APK(#13) < 编译 Debug APK(#14)
  < 收集 Debug APK(#15) < RootFS(#16) < 发布 RootFS 包(#20) < 诊断(#21)` = `True`；
  `编译 Debug APK` 全文件仅出现 1 次；`诊断` 保留 `continue-on-error: true`。
- 预期：同一类推送（纯 App 代码）的"推送 → 用户能下载"从 7 分 35 秒压到 **约 3.5 分钟**
  ——省下的正是 154s + 54s + 38s ≈ 4.1 分钟。实际观测按惯例补记进 `docs/FEATURE-LEDGER.md`。

### 4. 教训

1. **缩短关键路径要按贡献排序，不能按直觉**：一个诊断步骤（154 秒）比要发布的那个包自己的
   编译（119 秒）还贵；先量再改。
2. **排查期的临时步骤会长住**：E-014 写"常驻诊断"时是无奈之举（根因未定论），但它此后
   每次构建都白付 2 分半。临时步骤要有"什么时候可以撤"的约定——正确的收尾是"只在需要时跑"，
   不是"留着反正不要钱"。
3. **免费的东西也要算账**：本仓库是 public（`private=false`），Actions 分钟数免费不限量
   ⇒ 省机器时间本身**没有意义**，有意义的只有"用户多久能下载到"与"红叉是否可信"。
   这次的账恰好都落在前者，所以才值得动手。

---

## E-031 · 2026-10-08 · 环境包 326.6 MB 里有一半是 ffmpeg 拖来的一棵树；顺带否掉「压缩参数调优」与「zstd 差分」两条捷径

**一句话**：线上那个 326.6 MB 的环境包，**什么都不删、只把压缩级别从默认 3 提到 19 就白省 24.8%**；
再往下省必须动内容，而内容里最大的一块（ffmpeg 及其 201 个私有依赖，安装体积 397.8 MB）**从
2026-10-06 的审核报告起就标着"保留理由待复核"**；至于"差分更新省流量"这条最有诱惑力的路，
zstd 的 `--patch-from` 在这份包上**实测无效**（解码窗口覆盖不了基线）。

### 1. 现场（全部为实测；取法与工具见方案文档 §6）

线上包 = rolling `latest` 的 `debian-13.7-base-arm64.tar.zst`，**326,606,222 B（326.6 MB）**，
sha256 `ae27ddfe…a934f5`（与 `.sha256` 边车一致）；解开后 tar `1,031,792,640 B`：

| 内容 | 数值 |
|---|---|
| tar 成员 | 20,042 = 普通文件 **16,008**（内容合计 1,017,093,953 B）+ 目录 2,392 + 软/硬链接 1,634 + 其他 8 |
| 已装 dpkg 包 | 350 个，Installed-Size 合计 1,002,942 KB（979.4 MB） |
| 最大单文件 / 最大包 | `usr/bin/node` 150.2 MB / nodejs 241.9 MB（24.1% of Installed-Size） |

**压缩参数（同一份内容，只改参数）**：

| 配置 | 原包内容 | 瘦身包内容 |
|---|---|---|
| zstd 默认（级别 3，现网 `build-rootfs.sh:251`） | 326,606,222 B | 147,325,507 B |
| **zstd -19** | **245,658,431 B（−24.8%）** | **106,557,407 B（−27.6%）** |
| zstd -19 + `window_log 27` + LDM | 239,651,672 B（只再省 2.4%） | （同量级） |
| zstd -22 + `window_log 27` + LDM | — | 102,015,852 B（比 -19 再省 4.3%） |
| xz -9e（端侧不支持） | — | 90,439,092 B（比 -19 再省 15%） |

**内容侧（逐项单独剔除 → 按 zstd-3 重打包）**：

| 杠杆 | 重打包后 | 相对基线 |
|---|---|---|
| 只删 **ffmpeg 闭包**（201 个包） | **158,350,130 B** | **−51.5%** |
| 只裁 locale 到 4 种（zh_CN/zh_TW/en/en_GB） | 307,814,330 B | −5.8% |
| 只删构建残留（qemu-aarch64-static / `__pycache__` / gitweb / debconf） | 322,838,288 B | −1.2% |
| 只删 node 头（`usr/include/node`，3,428 个成员，原始 63.5 MB） | 325,843,200 B | **−0.2%** |
| 全叠加（瘦身包：480.3 MB tar） | 147,325,507 B | −54.9% |
| 全叠加 + zstd -19 | **106,557,407 B** | **−67.4%** |

### 2. 关键结论与根因

1. **ffmpeg 不是"数十 MB"，它是半个包**：Debian 13 上 `ffmpeg` 会拖进
   `libLLVM.so.19.1`（123 MB）、mesa/libgallium（34 MB）、libflite1（27 MB）、libz3（26 MB）、
   libcodec2/avcodec/avfilter/x265/placebo 以及一整套 X11/GL/cairo/pango/SDL2/音频/字体
   ⇒ **397.8 MB 安装体积（40.6%）/ 158.4 MB 包体积（51.5%）**。
   依赖闭包分析：保留集合（Essential/required/important 60 个 ∪ `build-rootfs.sh:99-105` 的显式清单）
   **没有任何包反向依赖这 201 个包** ⇒ 它是**一整棵可以整棵摘掉的叶子树**。
   文档侧本来就是待办：`docs/milestones/安卓AgentApp-设计方案-v3.md:232` 与
   `docs/milestones/文档审核报告-2026-10-06.md:143`（P2-10）都写着"ffmpeg 保留理由待复核"。
2. **压缩级别是"免费的那一半"**：−24.8% 只需把 `tar --zstd` 换成 `zstd -19`
   （内容、格式、端侧解码器都不用动；`zstd-jni 1.5.6-4` 与 Rust `zstd 0.13` 都支持 -19）。
3. **`--long` / `window_log 27` 不值得**：只再省 2.4%（原包）～4.3%（瘦身包），
   代价是端侧解压必须分配 **128 MB 窗口**内存——低端机上"崩一次"比"多下 4 MB"贵得多。
4. **zstd 差分（`--patch-from`）算法有效，但端侧用不了**（用官方 zstd CLI v1.5.7 实测）：
   - 32 MB 基线、新包与基线**内容完全一致** → 补丁 **5,298 B**（同一份内容普通 `-19` = 5,260,466 B）；
     128 MB 基线同条件 → **31,104 B** ⇒ **算法本身没问题（差三个数量级）**；
   - 但补丁**不自包含**：解码必须让**同一份基线原文**参与
     （不加基线直接解 → `Decoding error (36) : Data corruption detected`），
     且**解码窗口必须开到基线大小**：128 MB 基线的补丁帧声明窗口 128 MB，
     `--memory=64MB` 报 `Window size larger than maximum : 134217728 > 67108864`，
     放宽到 256 MB 才解出、产物与原文件逐字节一致 ✓；
   - ⇒ 端侧代价 = 基线原文（480 MB，且必须是**上一版未压缩的 tar**；App 手里只有已安装的 rootfs，
     uid/gid/mtime/成员顺序都变了，端上重建逐字节相同的 tar 不可靠）+ 512 MB 解码窗口 ⇒
     **结构性不可行**，不是"参数没调对"。
   ⇒ 差分要走**文件级增量**（清单 `sha256 \t size \t path` + 只含变更文件的补丁包 + 回退全量）：
   清单开销实测 **10,399 行 / zstd-19 后 441,331 B（0.44 MB）**，且只按内容哈希判定 ⇒ mtime 扰动免疫。
   ⚠️ **一条自我更正**：我最初拿 python-zstandard 的 `dict_data`（raw-content dictionary）当
   `--patch-from` 的等价物，得到"零变化也压不出小补丁"（480 MB 基线 147,326,517 B ≈ 无字典
   147,325,507 B），并据此写了"窗口覆盖不了基线"的结论——**那是错的**：python 绑定与 CLI 语义不等价
   （32 MB 切片上给到 `window_log 25` + LDM 也只省 3%，CLI 同条件省 99.98%）。判据以 CLI 为准。
5. **反直觉：原始字节 ≠ 包体积**。`usr/include/node` 原始 63.5 MB 只值 **0.8 MB** 包体积
   （3,428 个纯文本头文件，彼此高度相似、压缩率极高）⇒ 删它没有收益，只伤害 `npm` 原生编译。
6. **换解压器不省一个字节**：包体积只由压缩端（算法 + 参数）决定；解压器只管速度与容错。

### 3. 落地

- 方案文档（含三阶段 A/B/C、与 App 侧兼容性、以及"往 Rust 方向进化"的排序）：
  `docs/milestones/证道-环境包瘦身与压缩方案-2026-10-08.md`
- **阶段 A 已上线（2026-10-08）**：`rootfs/build-rootfs.sh` 两处改动（§2.9 清构建残留
  `qemu-aarch64-static`/`gitweb`/`debconf` 缓存/`var/log/*`；§[4/4] 打包改 `ZSTD_CLEVEL=19
  tar --use-compress-program="zstd -19"` + 新增 280 MB 包体积门禁），提交 `b1ebe6a` → main `a7a1d22`。
  **实测结果**：`build #152 @a7a1d22` = success（24 分钟），
  `latest` 的 `debian-13.7-base-arm64.tar.zst` = **326,606,222 B → 243,428,459 B（−25.5%）**，
  边车 sha256 同步刷新，端侧零改动（Rust `zstd 0.13` / Java `zstd-jni 1.5.6-4` 本来就能解 -19 帧）。
- **阶段 B1（删 ffmpeg）已被用户否掉**（2026-10-08 原话要点：「ffmpeg 是 hermes agent 要用的，
  不然 hermes 会自己下载的，更拖慢整个进度，本来 hermes 下载就慢了」）⇒ 预装清单与
  `build-rootfs.sh:204` 的断言都**保留**；方案的 B1 一节改写为"如果将来又要删"的施工图。
  阶段 B2（裁 locale，−18.8 MB）用户未答。
- **`zstd -19` 的解压侧兼容性不必验证**：帧窗口 8 MB，两端解码器都支持；
  门禁值 280 MB 留了约 35 MB 余量（基线 243.4 MB）。
- 工具（临时目录，可复用）：`zd_measure.py`（decompress/budget/files/pkg/closure/purge/compress/xz/decode）、
  `zd_check.py`（瘦身包完整性门禁：缺失 0 项、漏删 0 项）、`zd_manifest.py`（清单体积与内容去重，输出 10,399 行 / zstd-19 后 441,331 B）、`zd_delta.py`（retouch/delta）。

### 4. 教训

1. **没量过的数字会在设计文档里躺两天**："体积数十 MB"与实际 397.8 MB 差了十倍，
   而这条从审核报告起就写着"待复核"——**"待复核"要配一个复核的动作与期限**，否则等于没写。
2. **先做便宜的那一半**：内容瘦身要重新构建、要拍板、有功能风险；压缩参数只花 CI 时间。
   同一批实测里，免费的那步（−24.8%）比最贵的那步（−51.5%）性价比高得多。
3. **差分不是算法问题，是内存问题**：判断"能不能差分"先问"窗口/内存要多大"，
   再问算法；基线越大，patch-from 越不可行。
4. **按字节占比做取舍会做错**：63.5 MB 的文本头文件只值 0.8 MB 包体积；
   "删大头"必须按**重打包后的体积**判断。
5. **别拿语言绑定当 CLI 语义的等价物**：同一个"字典"概念，python-zstandard 的 `dict_data`
   与 CLI 的 `--patch-from` 在同样条件下差三个数量级（3% vs 99.98%）——凡是"某能力不可行"的
   结论，都要用**用户真正会用的那个工具**复现一次；同时，"零变化 → 补丁 ≈ 全量"这种**几乎免费的
   对照实验**仍然是判死一条路线的最快办法（正是它暴露了绑定与 CLI 的差异）。

---

## E-032 · 2026-10-08 · 那 70 MB 语言包，App 从来没读过；设计文档写了"裁掉多余 locale"，构建脚本从没做

### 1. 现场（实测 + 代码取证）

| 项 | 实测值 |
|---|---|
| `usr/share/locale/*` 原始字节 | 70.5 MB（另有 `usr/share/i18n` 15.7 MB 的 locale 生成源码） |
| 只裁 locale 到 4 种语言（zh_CN/zh_TW/en/en_GB） | 包 326,606,222 → 307,814,330 B（−18.8 MB） |
| **只留 zh_CN + en（本次采用）** | 包 → **301,895,492 B（−24.7 MB，−7.6%）**；tar 1,031.8 → 902.7 MB |
| 翻译全删（只留 `locale.alias`） | 包 → 301,164,218 B（比"只留中英"只再多省 0.7 MB） |

- 环境实际是按英文/C 跑的：`app/src/main/java/com/example/zhengdao/terminal/ProotLauncher.kt:433`
  与 `:617` 注入 `LANG=C.UTF-8`。
- 镜像里也**没有生成 zh_CN 语言**：`rootfs/build-rootfs.sh:107-111`（§2.3）只做
  `locale -a | grep -qi '^C\.utf8' || locale-gen`——即"只保证 C.UTF-8 存在"。
  ⇒ 在 `LANG=C.UTF-8` 下 gettext 根本不会去读 `zh_CN/LC_MESSAGES/*.mo`，这 70 MB **从来没被读过**。
- 文档/实现漂移：`docs/milestones/安卓AgentApp-设计方案-v3.md:225` 与
  `docs/milestones/M5-更新体系-Kotlin骨架.md:134` 都写着"裁掉 `/usr/share/doc` 与多余 locale"，
  而构建脚本只做了 `/usr/share/doc`，**locale 那半句一直没落地**（`rm -rf /usr/share/locale/*` 从未出现）。

### 2. 修法（`rootfs/build-rootfs.sh:217-226`，§2.9 清理段）

```bash
find /usr/share/locale -mindepth 1 -maxdepth 1 \
  ! -name 'zh_CN' ! -name 'en' ! -name 'locale.alias' -exec rm -rf {} +
rm -rf /usr/share/i18n
```

- 保留 `zh_CN`/`en` 的翻译目录 + `locale.alias`（合计不到 1 MB），
  比"全删"只多 0.7 MB，却保住了"哪天要开中文提示就能开"的可能（缺的只是 locale 生成本身）。
- `usr/share/i18n` 是 `locale-gen` 的源码（608 个成员、15.7 MB 落盘），删掉后环境里不能再生成新语言。

### 3. 验证要点

- 体积：包体积门禁（280 MB）应通过；线上包应从 243.4 MB 降到 **≈ 217 MB**（重建后按实际字节回填）。
- 功能：`LANG=C.UTF-8` 下 `ls`/`apt`/`git` 的提示文本本来就是英文 ⇒ 用户侧无可感差异；
  需确认环境里 `env`、`locale`、`locale -a` 仍正常（`C.UTF-8` 是 glibc 内置，不依赖 `/usr/share/i18n`）。

### 4. 教训

1. **"预生成了 4 种语言"这种话，先查它到底做了什么**：脚本里 `locale-gen` 无参数时生成的是
   `/etc/locale.gen` 里**没被注释**的那些——本次实测就是"只有 C.UTF-8"，与"按 4 种语言预生成"的
   直觉完全不同。**别把"包里有 `locales` 包"当成"有这些语言"**。
2. **文档写了 ≠ 代码做了**：设计文档与 Kotlin 骨架文档都写着"裁掉多余 locale"，
   实际脚本一行都没做。这类"文档/实现漂移"只能用"文档里每条清理项 → `grep` 脚本"的方式抓。
3. **删内容前先看"谁在读它"**：决定要不要留 zh_CN，靠的不是"用户是中国人"，
   而是 `LANG=` 到底等于什么（一个 grep 就能定案）。
4. **工具坑（差点得出错误结论）**：PowerShell 调原生命令时**空字符串参数会被吞掉**，
   于是 `purge src dst "" extra keep` 里的 `""` 消失、`keep` 顶到 `extra` 位、
   locale 保留列表退回默认的 4 种 ⇒ 两个变体量出**完全相同的字节数**。
   若不核对 `额外前缀 … 命中 N 个成员` 那行输出，就会得出"留几种都一样"的错论。
   改用不存在的包名（`zz-none`）占位后复测才拿到真数字。

## E-033 · 2026-10-08 · 每次更新重下 228 MB 的账：改「内容指纹 + 清单 + 文件级差分包」；并记下"删除类方案必须在线上压缩级别下测算"

### 1. 现场（这账是怎么攒出来的）

| 项 | 实测值 |
|---|---|
| 线上环境包（zstd -19 后） | `debian-13.7-base-arm64.tar.zst` = **228,789,461 B** |
| 真机下载全量包 | 311 MB 落盘 ≈ **4 分钟**（≈1.3 MB/s，95% 时间在 GitHub 资产直链） |
| 检查更新为什么永远说"已是最新" | `SettingsScreen.kt` 拿 manifest 的 `version` 与本地比，而 `version` 恒为发行版号 `13.7`——装过几版它都不变 ⇒ **更新通道等于没有**（真更新只能靠重装/清缓存） |
| `rootfs/manifest.json` 的 `sha256` | **空串**，且 CI 从不更新该文件 ⇒ 空值即跳过校验 |

⇒ 两笔账：①更新检测失效；②即便检测出来，每次都要重下整个包。

### 2. 方案（`docs/milestones/证道-环境包增量下发协议.md`）

- **`env` = 内容指纹**：清单正文（去掉 `#` 注释行）按 LF 连接 + 末尾 LF 的 `sha256[:16]`。
  版本号不再参与判断，"内容变了"就是 env 变了。同一棵树无论打包多少次、mtime 怎么变，env 恒定。
- **三件套**（同一个 Release，恒定资产名）：
  - `rootfs-manifest.txt`：每行 `type\tmode(%04o)\tsize\t\tdigest\tpath`（软链 digest = 链接目标；
    按 path **字节序**排序；FIFO/socket/设备节点不进清单）。实测 10,399 文件 / 1,240,434 B，
    zstd-19 后 **441,331 B**。
  - `rootfs-patch-<base>-to-<new>.tar.zst`：只装变更文件（未压缩 tar + 首成员元数据）。
  - `rootfs-index.json`：**App 端唯一事实来源**——`schema/distro/version/env/builtAt/url/sha256/size/
    manifestUrl/patch{from,url,sha256,size}`。
- **端侧（`RootfsDelta`）**：①本地 env ≠ 补丁 `from` ⇒ **动任何文件之前**抛 `BaseMismatch`；
  ②整树克隆到 `rootfs.tmp`（**同内容优先硬链接**）；③解补丁（跳过 `.zhengdao-patch-info`）；
  ④按 `deletes` 递归删；⑤写新标记；⑥`swapIntoPlace` 原子替换。**任何一步失败 ⇒ 回退全量下载**。
- **刻意不做**全树逐文件校验：保留用户 `apt upgrade` 造成的漂移（协议 §6）。
- `rootfs/manifest.json` 降级为**无人维护**的兼容路径（索引取不到时的兜底提示），
  其"本地版本 == 清单版本 ⇒ 已是最新"的旧判断本次一并改成不再下这个结论。

### 3. CI（`rootfs/build-rootfs.sh` §5 `:288-346` + `.github/workflows/build.yml`）

- 构建脚本末尾产清单 → 写 `.sha256` → 取 `NEW_ENV`；`BASE_MANIFEST` 存在且 env 不同 ⇒ 产补丁。
  **缺 python3/工具 ⇒ 跳过（不产假清单）；工具一旦跑起来报错 ⇒ 直接失败**——宁可 CI 红，
  也不发一份与实际内容对不上的清单。
- 新增步骤「取上一版 rootfs 清单」（`curl -fsSL --retry 3` 取 `latest` 的清单 + `.sha256`，
  `sha256sum -c` 不过就删掉并 `::warning::`）；RootFS 步骤多一个 `BASE_MANIFEST` 环境变量。
- `release` job（tag）新增「补挂该版本的 RootFS 资产」：整包 + 清单 + 索引从 `latest` 搬到本 tag，
  **刻意不搬补丁**（tag 是历史快照，补丁是"上一版→本版"的路径，搬过去只会让基线关系错乱）。

### 4. 验证（四层，全部实跑）

1. **bash harness**（抽 `build-rootfs.sh:288-346` 真代码跑，真 bash 5.3.15 + python3 3.11.9 + zstd 1.5.7）：
   无基线 ⇒ 产清单+索引（`patch: null`）；基线 env 相同 ⇒ "不产补丁"；改树 ⇒ 产补丁
   （`rootfs-patch-41db7529bd459ca7-to-ba50655bf622304d.tar.zst`，215 B）、首成员元数据正确、
   两侧车 `sha256sum -c` 通过 → **PASS，失败 0 项**。
2. **Python 自测** `tools/rootfs-delta-selftest.py`：45 断言通过 / 0 失败 / 2 跳过（真实软链、
   含制表符文件名——本机无权限）；含"参考 Applier 应用后清单与 new **逐字节相同**"。
3. **Kotlin**：`:app:testDebugUnitTest --tests com.example.zhengdao.rootfs.*` 46 用例 0 失败；
   **真机 PGT-AN10 / Android 16** 仪器测试 2 用例 0 失败（含"基线不匹配 ⇒ 动手前抛错、原树不变"，
   覆盖 mode/软链/删除/水印不落地）。全量 JVM 单测 **211 用例 / 0 失败**。
4. 端到端真数据：等下一次 CI 重建（首次无基线 ⇒ 不产补丁，只验清单/索引与线上包一致）。

### 5. 教训

1. **"版本号"不是"内容版本"**：一个恒定的发行版号（13.7）撑不起更新判断，
   于是 UI 永远显示"已是最新"——**能自证"没有更新"的通道，比没有通道更危险**。
   改用内容指纹（env）后，判断依据与"实际装了什么"是同一份数据。
2. **删除类瘦身方案必须在线上压缩级别下测算**：B2 删语言包在 **zstd -3** 下测出 −24.7 MB，
   线上 **zstd -19** 实测只省 **14.6 MB**（243,428,459 → 228,789,461 B）。原因是删掉的
   79.9 MB 几乎全是高冗余文本（`.mo`/charmap），级别越高压得越狠（-3 ≈ 1/3.4、-19 ≈ 1/5.5）。
   ⇒ 任何"删内容"的收益预测，都要在**与线上一致的压缩参数**下量，否则必然高估。
3. **信任锚要落在"校验通过的那份 sha"上**：索引由 CI 更新，CI 半途失败/上传错序都可能让
   `index.sha256` 指向别的包。规则是"**实际校验通过的 sha == 索引 sha** 才写 `env=`，
   否则照装但不记版本"⇒ 下次自然走全量。**宁可多下一次，不可错走增量**。
4. **硬链接克隆的树上，"先删再写"是硬性要求**：增量把旧树硬链接进 tmp 省掉 GB 级拷贝，
   于是 `outputStream()` 直接写就会改穿到正在被 proot 使用的**旧环境**——原子性就没了。
   `RootfsInstaller.extractEntry` 的每个分支（普通文件/硬链/软链）都先 `delete()` 再写。
5. **补丁元数据必须能自证完整**：首成员 `.zhengdao-patch-info` 声明 `deletes=` 行数，
   实到行数少于声明数 ⇒ 判定"补丁被截断"并拒绝（下载截断最常见的形态）。
   元数据行解析**不允许未知行**（宁可不认，也不半信半疑地装）。
6. **"没有基线"必须是正常路径**：首次上线时 `latest` 里没有上一版清单 ⇒ 不产补丁、只产全量包 +
   清单 + 索引，App 照样全量安装；差分包要等下一次环境真正变化才出现。
7. **（自查追加）校验脚本必须匹配边车的格式**：CI 里本来写的是
   `(cd rootfs-base && sha256sum -c rootfs-manifest.txt.sha256)`。但本项目 `.sha256` 的既有约定是
   **只有 64 位摘要、不带文件名**（`sha256sum … | awk '{print $1}'`；线上
   `debian-13.7-base-arm64.tar.zst.sha256` 实测就是 65 字节的裸摘要）。`sha256sum -c` 要求
   `<hash>  <文件名>` 的标准格式，碰到裸摘要直接报 `no properly formatted checksum lines found`
   ⇒ **每一次都"校验失败"** ⇒ 基线被丢掉 ⇒ **差分包永远不产生，日志里只有一句 warning，没有红灯**。
   改法：取第一个字段自己比（`EXPECTED=$(awk 'NR==1{print $1}' …)` 对 `sha256sum … | awk '{print $1}'`），
   同时对标准格式保持兼容。
   本地用真 shell + 真 curl 对四种边车形态逐一确认：裸摘要 ⇒ 通过 / 哈希不符 ⇒ 警告并丢弃 /
   资产缺失 ⇒ 警告且不失败 / 标准格式 ⇒ 通过。
   教训：**"校验"本身也会静默失效**——它失败的样子和"确实没有基线"一模一样。
   凡是"不通过就降级"的分支，都要有一条能证明"正常路径真的走得通"的用例。

---

## E-034 · 2026-10-08 · 「网络自检」的绿灯骗人：它只 ping 国内镜像，而下载与更新全在 GitHub 家族（已修）

### 1. 现场（真机取证，PGT-AN10 / Android 16 / 5G + VPN）

增量下发上线后，在真机上点「检查环境更新」，logcat（`-s RootfsDownloader RootfsIndexFetcher …`）拿到完整失败链：

```
W/RootfsDownloader: 抓取文本失败：https://github.com/pisces19860207/zhengdao/releases/download/latest/rootfs-index.json
W/RootfsDownloader: 抓取文本失败：https://gh-proxy.com/https://github.com/…/rootfs-index.json
W/RootfsIndexFetcher: 索引抓取失败（已试全部源），调用方按老路径降级
W/RootfsDownloader: 抓取文本失败：https://raw.githubusercontent.com/pisces19860207/zhengdao/main/rootfs/manifest.json
```

「立即刷新 Agent 清单」同样三条通道全灭（`api.github.com` / `raw.githubusercontent.com` / `cdn.jsdelivr.net`），
保出厂版清单、不崩。**但设置里的「网络自检」是绿色的**：`✅ 网络可用（285ms）`——因为它探的是
`https://registry.npmmirror.com/-/ping`（国内主机）。

**同一台手机、同一个 URL，shell 里却通**：`adb shell curl` 到 `https://github.com` ⇒ 200 / 1.43s，
到 gh-proxy ⇒ 200 / 469 B / 1.05s。App 的 uid 不通、shell 通 ⇒ 网络环境差异（最可能是 VPN 的
**分应用代理没勾选证道**；设置页自己那句提示就写着"若走代理：请在代理 App 的「分应用代理」里勾选证道"）
⇒ **这不是代码缺陷**，App 的降级行为（落老路径、保住出厂清单）都是对的。

坏的是**诊断本身**：用户看到绿灯，会以为网络没问题，然后被"下载一动不动 / 环境更新失败"绕进去。

### 2. 修法

新增 `app/src/main/java/com/example/zhengdao/ui/NetSelfCheck.kt`：

- `probeCn()`：国内基线（历史行为不变，5 秒超时）。
- `probeIndex()`：**直接复用 `RootfsIndexFetcher.fetch()`**（含 gh-proxy 镜像回退），
  于是自检问的就是「检查环境更新」问的那个问题，两处结论不会各说各话；
  用 `FutureTask` + 25s 预算（daemon 线程），手动触发的自检不能因为一条卡死的连接把界面挂住。
- `summary(cn, update)`：纯函数，四档文案。关键是**国内通 + 更新源不通**这一档不再只报绿灯：

  > ✅ 网络可用（285ms）· ⚠️ 更新源不可达：下载环境包与「检查环境更新」都会失败。若在代理下，请到代理 App 的「分应用代理」里勾选证道

`SettingsScreen.kt` 的自检 `onClick` 从 20 行内联探测缩成两句探针 + `summary`。

### 3. 验证

- 新增 `app/src/test/java/com/example/zhengdao/ui/NetSelfCheckTest.kt`（5 用例：四档组合各一条，
  外加"错误描述缺省回落成超时"）⇒ 全过。
- 全量 JVM 单测：**27 suites / 216 tests / failures=0 / errors=0 / skipped=0**（改前 26 / 211）。
- 真机复验（`build #157 @415c858` 的 release APK 4,208,009 B，`adb install -r` 同签名覆盖升级）：
  点「网络自检」实测显示

  > ✅ 网络可用（291ms）· ⚠️ 更新源不可达：下载环境包与「检查环境更新」都会失败。若在代理下，请到代理 App 的「分应用代理」里勾选证道

  ⇒ 修复在同一台机器、同一网络状态下生效（旧版此刻只会打绿灯）。

### 4. 教训

1. **诊断工具必须探"真正依赖的那条路"**：绿灯比红灯更坏——红灯会让人去查，绿灯让人以为没事。
   凡是有多源/多主机依赖的 App，"网络可用"这个词必须限定在**它自己用的那几个源**上。
2. **手动触发的自检要有时间预算**：探针走的是全局共享 OkHttp（connect 20s / read 60s），
   不加 `FutureTask` 上限，断网时能把"检测中…"挂几分钟。
3. **同一个 URL 在 shell 通、在 App 里不通，先怀疑代理的"分应用"名单**，别急着改代码——
   App 与 `adb shell` 是两个 uid，走的联网路径本来就可以不同。

---

## E-035 · 2026-10-08 · 「重建一次环境包，内容指纹就会变吗」——拿两个真实构建对拍，只差 6 个文件（共 41 KB），差分包吸得住

### 1. 为什么值得测

文件级增量下发（E-033）判断"要不要更新"靠的是 **env**（清单正文 sha256[:16]，只看
type/mode/size/内容 sha256，**不含 mtime**）。如果 env 会被构建噪声污染，那么"环境没变"这个
前提就不成立：维护者只是重建了一次，所有用户都会被提示"有更新"。

疑点在打包命令上：`rootfs/build-rootfs.sh:274`

```sh
ZSTD_CLEVEL=19 tar --use-compress-program="zstd -19" -cf "$OUT_DIR/$ASSET" \
    --numeric-owner -C "$ROOTFS_DIR" .
```

**没有任何 mtime 归一化**（全脚本唯一的 `touch` 在 `:97`：
`touch /var/log/apt/history.log /var/log/apt/term.log`）。当时点名的可疑文件是
`./var/cache/ldconfig/aux-cache` 与 `./var/cache/fontconfig/*-le64.cache-9`——缓存类文件
经常把源文件的 mtime 嵌进内容。

### 2. 怎么测（不需要下载 228 MB）

主机到 `release-assets.githubusercontent.com` 被限速到 **~50 KB/s**（90 秒只下 4.5 MB），
当前包下不动；于是反过来用**手机缓存里那份旧包**：`adb pull`（36.3 MB/s）得到
`C:\Users\guoli\AppData\Local\Temp\zd-real\old326.tar.zst`
= 326,613,604 B / sha256 `2f1406af1939f7f263b8191abdec6743ff99e8a87e47f2235adc3a6a3c3b1146`
（手机侧 mtime 2026-10-08 00:21 本地 = **10-07 16:21Z**，B2 瘦身后的线上包是 06:49Z/10-08）。

与它对比的是**线上当前清单**（build #155，`env=e6059c6bd3ee309f`，17,991 条目），
逐成员比内容 sha256。脚本：`C:\Users\guoli\AppData\Local\Temp\zd-real\compare_two_builds.py`
（`zstd.exe -dc` 管道 → `tarfile` 流模式 `r|` → 每个常规文件算 sha256）。

### 3. 结果

旧包：**16,010 文件 / 2,391 目录 / 1,632 软链**。

| 分类 | 数量 |
| --- | --- |
| 两边都有、内容**逐字节相同** | **14,301** |
| 两边都有、内容**不同** | **6** |
| 只在旧包里 | 1,703 |
| 只在当前包里 | **0** |

"只在旧包里"的 1,703 个 = locale 1,077 + i18n 605（B2 有意删的）＋更早的清理
（`proot`/`qemu-aarch64-static`、`/var/log/*`、`/usr/share/gitweb`、`/var/cache/debconf`）。

**内容不同的只有这 6 个**：

| 路径 | 大小 | 为什么会变 |
| --- | --- | --- |
| `./etc/shadow` | 474 B | 第二字段的**日字段 = 构建当天的 UTC 日**：旧包 `20733`（1970-01-01 + 20733 天 = **2026-10-07**，正是该包 10-07 16:21Z 的构建日）。文件长度不变，只有那个数字变 |
| `./var/cache/ldconfig/aux-cache` | 20,200 B | 缓存里嵌了被索引文件的 mtime |
| `./var/cache/fontconfig/3830d5c3ddfd5cd38a049b759396e72e-le64.cache-9` | 144 B | 同上（fontconfig 缓存） |
| `./var/cache/fontconfig/4c599c202bc5c08e2d34565a40eac3b2-le64.cache-9` | 104 B | 同上 |
| `./var/cache/fontconfig/7ef2298fde41cc6eeb7af42e48b7d293-le64.cache-9` | 160 B | 同上 |
| `./var/cache/fontconfig/d589a48862398ed80a3d6066f4f56f4c-le64.cache-9` | 21,080 B | 同上 |

合计 **42,162 B ≈ 41 KB**（zstd 后只有几 KB）。

### 4. 结论与处置

1. **树本身是确定性的**：这两个包跨了一次脚本改动（B2 删 locale）、跨 6.5 小时、跨一个
   UTC 日重建，仍有 **99.96% 的文件逐字节相同** ⇒ 「内容指纹 + 清单 + 差分包」的地基成立。
2. **env 在任何一次重建后都会变**（ldconfig/fontconfig 缓存必变；跨 UTC 日再多一个
   `/etc/shadow`）。维护者重建后用户收到一次"有更新"是**正确**的——资产确实变了——
   代价 = 一个几 KB 的差分包。
3. **有意不做的硬化**：删 `/var/cache/ldconfig/aux-cache` 与 `/var/cache/fontconfig/*`
   （首次使用时自动重建），或在打包前把 `/etc/shadow` 的日字段 sed 成常量。收益只是
   "无改动重建也不提示更新"，而 CI 只在 `rootfs/` 有改动时才重建（`rootfs_needed`），
   这个场景本来就不存在；改系统状态的风险大于收益。真要做，按 E-033 的四层流程走一遍。
4. 记账：`docs/milestones/证道-环境包增量下发协议.md` 补「内容指纹的稳定性实测」一节。

### 5. 教训

1. **"内容指纹会不会变"要用两个真实构建对拍，别靠推理**：mtime 到底进不进内容，猜不出来——
   实测只 6 个文件，而且全是可解释的（缓存 + 构建日期）。
2. **下不动就换数据源**：这次是主机到 `release-assets` 被限速 50 KB/s，而**同一份包就在手机上**
   （`adb pull` 36 MB/s）。数据在哪就从哪拿，别死磕一条链路。
3. **流式 tar 比对有两个坑**：`r|` 模式下 `extractfile` 对链接会抛
   `StreamError: cannot extract (sym)link as file object`——软链要先 `issym()` 拦掉，硬链要按
   `linkname` 复用目标已算出的 digest（顺序不定，得先记 pending 再补解）。
4. **`/etc/shadow` 的日字段就是"构建日期"**：凡是谈"构建可复现"，都得先把这类系统状态文件
   排除掉再谈。

---

## E-036 · 2026-10-08 · 「自动清理」是文档里的功能（两个函数零调用点）；同一轮查出缓存面板漏报 378 MB、安装提示只有 2 秒 Toast

### 1. 起因（用户原话）

> 「还有运行日志，错误日志，下载的东西都放到 download 证道文件夹里，包括终端里下载的
> agent 的安装主程序，重新装 APP 的话也要像装环境一样的，自己就瞬间装好了，今天的体验就很爽，
> 但是那个提示时间有点短，终端里也没有显示，我以为要重新下载呢，还跑到环境检测那里点了很多次下载，
> 这算是个小误会，后来我在终端等了一下终端就刷新了」

一句"提示太短"往下查，查出**三个互相独立**的问题，全都在同一个流程上叠着。

### 2. 三处勘误

**(1) 安装提示是"2 秒 Toast + 私有日志"的组合，等于没有反馈。**

- `app/src/main/java/com/example/zhengdao/TerminalActivity.kt:919`：检测到本地包时只有一句
  `Toast.makeText(this, "检测到本地安装包，直接安装", Toast.LENGTH_SHORT).show()`。
- 同文件 `installStatus(text)`（`:964-967`）= `RunLog.log(text)` + `Toast.LENGTH_SHORT`：
  **每一条里程碑都只是 2 秒 Toast**（"复制本地安装包到缓存（约 1 分钟）…"`:1008`、
  "安装完成！安装包已保留在缓存（重装免下载）"`:989`）。
- 日志写进私有 `cacheDir/runlog/`——用户用文件管理器打不开，等于看不见。
- 于是那天的事实是：**真的没有重新联网下载**（用的是 `Download/证道/rootfs/` 里那份
  312 MB 的本地包），但屏幕上没有任何一行字告诉用户这件事。用户只能猜，猜的结果是跑去
  「检查环境更新」连点了几次下载。

**(2) 缓存面板漏报 378 MB。**

`app/src/main/java/com/example/zhengdao/terminal/CacheCleaner.kt` 的 `probePaths` 里
"安装包缓存"量的是**私有兜底** `File(cacheDir, "rootfs-cache")`，而真身在公共区：

| 位置 | 真机实测 |
| --- | --- |
| `Download/证道/rootfs/debian-13.7-base-arm64.tar.zst` | 326,613,604 B（312 MB） |
| `Download/证道/opencode/opencode-2.0.22-1-aarch64.pkg.tar.xz` | 68,606,212 B（66 MB） |

真机（PGT-AN10 / Android 16）设置页实测：**npm 0 / uv 0 / apt 0 / 安装包缓存 0 /
临时文件 0 MB**，而存储占用 973 MB。**面板 0 vs 实际 378 MB**。

**(3) 「自动清理」从未接线。**

- `CacheCleaner.autoCleanNeeded()` 与 `maybeNotify()` —— **全仓库零调用点**
  （grep `.kt/.xml/.gradle/.md`，只命中定义行 `CacheCleaner.kt:210`）。
- `app/src/main/java/com/example/zhengdao/terminal/SessionService.kt:57-62` 的 `monitorTick`
  每 30 秒只调 `updateNotification()`（`:71 handler.postDelayed(monitorTick, 30_000L)`）；
  全仓库没有 WorkManager / AlarmManager / Timer。
- 而 `docs/milestones/证道-执行路线图.md:284` 写着「自动清理：启动时 > 500MB 才清，
  检测安装进程跳过」——**文档漂移**，这条从未生效。用户问"自动缓存清理可以有吗"时，
  正确答案是"代码里有、但没人调用；文档里已经宣称做了"。
- 真正自动的只有三处：`RunLog` 启动自清理、`RootfsInstaller.cleanupPartial`
  （`TerminalActivity.kt:95`）、`RootfsCache.pruneKeep(keep = 2)`（装完保留 2 个包）。

### 3. 处置（同一轮落地）

| 项 | 落点 |
| --- | --- |
| 公共区唯一真相源 | `app/src/main/java/com/example/zhengdao/terminal/Store.kt`（`logs/` `cache/` `agents/`；**锚定 `Download/证道`**，不跟随工作区——见下方 §6） |
| 日志 | `RunLog` → `Download/证道/logs/`（无权限回落私有；`zhengdao-log.txt` + `errors.log` 跨轮错误汇总 + `.prev`；新增 `@Volatile settled` 保证同一进程只善后一次） |
| 包缓存 | proot 额外 `-b`：`cache/npm`→`/root/.npm`、`cache/uv`→`/root/.hermes/cache/uv` 与 `/root/.cache/uv`、`cache/pip`→`/root/.cache/pip` |
| Agent 脚本 / 账本 | `agents/scripts/`、`agents/installed.json`（`AgentLedger`）+ 主页「恢复全部（N 个）」 |
| 安装可见性 | `InstallProgress`（单一状态）+ `InstallNotifier`（`NotificationChannels.INSTALL`）+ `activity_main.xml` 里那个**代码零引用的死控件** `status_banner` + 里程碑写 `rootfs/tmp/.zhengdao-banner-pending` |
| 自动清理 | 二档（宿主侧临时残留：白名单 + 24h + 有会话跳过）接到 `ZhengdaoApp.onCreate` |
| 面板计量 | `CacheCleaner.measure` 改量真身（公共区 + 私有候选并存，见下方"为什么不是删掉私有路径"） |

### 4. 三条约束（都是查出来的，不是设计的）

1. **`/sdcard` 是 noexec 挂载** ⇒ 公共区只放**不需要执行权限**的东西（安装脚本、npm/uv/pip
   缓存）。Agent 的可执行文件（`~/.local/bin`，agy 实测 201 MB）**不能搬**，否则 `Permission denied`。
2. **hermes 会剥离 `UV_*` 环境变量**（见 E-026 一系的 hermes 环境处理）⇒ 想让 uv 缓存落在
   公共区，**bind 是唯一可靠注入点**，设 `UV_CACHE_DIR` 会被它清掉。
3. **凭据不进公共目录**（用户拍板）：`~/.claude`、`~/.hermes` 里的 API key / 登录态留在私有
   home。公共目录任何有存储权限的 App 都能读，也可能被云备份带走。
   `AgentLedger` 的 `Entry` 只有 `id/name/at/state` 四个字段，单测锁死"账本里不写凭据类字段"。

### 5. 教训

1. **"文档写了" ≠ "已实现"**：判断一个自动行为存不存在，要 grep **调用点**
   （`autoCleanNeeded(` 而不是 `自动清理`）。本仓库此前已经栽过一次同类（E-032：设计文档写了
   "裁掉多余 locale"，构建脚本从没做）——这是第二次，说明"文档描述功能"必须带**代码锚点**。
2. **面板数字和磁盘实物对不上时，先怀疑"量的位置"**：`measure()` 的算术一直是对的，
   错的是它量了一个空目录。
3. **用户说"提示太短"，根因是可见性架构，不是时长**：把 2 秒 Toast 改成 5 秒仍然是错的。
   要的是"常驻 + 可回看 + 结论落盘"：横幅（看得见）、系统通知（离开页面也在）、
   日志文件（事后能查）、以及**免下载时明说"不联网下载"**——用户当时的怀疑正是从
   "不知道有没有在下载"来的。
4. **终端里想"显示一行"必须走文件通道**：终端是原生 `TerminalView`，不能往里注入文本；
   `/etc/profile.d/zz-banner.sh` + `rootfs/tmp/.zhengdao-banner-pending` 是既有的、
   已验证可用的唯一通道（tmux 里每个新 pane 打一次并删文件）。
5. **搬家的默认策略必须是"不覆盖、搬不动就留着"**：私有 `files/` 与公共 `/sdcard` 可能不是
   同一文件系统（`renameTo` 会失败），所以 `Store.adoptDir/adoptFile` 逐条目搬、
   目标同名则跳过、失败退回复制后删源、源目录没搬空就保留。幂等 ⇒ 每次启动无脑调用。
   这条全部锁进了 `StoreTest`。

### 6. 真机验证后的修正：公共区**不跟随工作区**（同一轮）

上面第一版实现里，`Store.root(ctx)` = `Workspace.hostDir(ctx)`——理由是"下载物放哪"与
"产出放哪"永远不漂成两处。**真机一验就散架**：

- 用户的工作区早被设成了自定义目录 `Download/男性`（他的小说工程，见
  `shared_prefs/zhengdao-settings.xml` 的 `workspace_mode=custom` / `workspace_path`），
  于是日志、缓存、Agent 脚本与账本全落进了 `Download/男性/{logs,cache,agents}`——
  和他自己写作内容混在一起，而安装包（`rootfs/`、`opencode/`）还在 `Download/证道/`，
  东西反而**更分散**了。用户原话要的就是「都放到 download 证道 文件夹里」。
- 更实际的两个后果：换工作区就丢缓存（要重下几百 MB）、丢账本（"装过什么"没了，
  正好毁掉"重装 App 秒装好"最需要的那份数据）。

修正（用户拍板"固定放 `Download/证道`"）：

| 项 | 改成 |
| --- | --- |
| `Store.root` | 恒为 `Store.PUBLIC_ROOT` = `/storage/emulated/0/Download/证道`；没存储权限 / 仅私有模式 → `filesDir/store`（私有兜底，功能不断） |
| `Store.isPublic` | 判 `root` 是否在 `/storage/emulated/0` 下（不再问 `Workspace.isShared`） |
| guest 侧公共区 | 新增一条 `-b <Store.root>:/opt/zhengdao`（挂载点在 rootfs 里预建 `opt/zhengdao`）；`Store.GUEST_SCRIPTS_DIR` 从 `/workspace/agents/scripts` 改为 `/opt/zhengdao/agents/scripts`——**不能再挂在工作区下面**，否则脚本路径会随用户的内容目录漂 |
| 老脚本迁移 | `AgentInstaller` 两处都收：`Workspace.hostDir/.zhengdao/scripts`（老落点）与 `Store.root/.zhengdao/scripts`（第一版的落点） |
| 面板计量 | `CacheCleaner.measure` 改用新的 `Store.cacheDirPath`（只算路径不建目录）——否则"打开面板看一眼 / App 每次启动"都会在用户存储里凭空建出 `cache/{npm,uv,pip}` 三个空目录 |

教训（补一条）：**"统一到一个根"是个好直觉，但先得问清那个根是不是用户的内容目录**。
工作区是"Agent 干活的地方"，可以是用户自己的文件夹；App 自己的日志 / 缓存 / 账本是
**程序数据**，锚在程序自己的目录（`Download/证道`，与安装包同处）才不会被用户的下一个
选择带偏。用户说"都放到 X 文件夹"时，X 就是答案，别用"更优雅的一致性"去替换它。

### 7. 同轮补漏：设置页还有三条"只有 Toast"的长流程（真机复验时才翻出来）

修完 §3 之后上真机复验横幅，顺手翻设置页——**同一条安装逻辑有两个入口，只修了终端那一个**。
`app/src/main/java/com/example/zhengdao/ui/SettingsScreen.kt` 里三条同样几分钟的路径仍是旧世界：

| 路径 | 原实现 | 症状 |
| --- | --- | --- |
| 「修复环境（30 秒）」 | 原 `:1019-1043`：拷包 → `RootfsInstaller.install(ctx, archive) { }` → 一句"修复完成"Toast | 真机实测解压只要 ~8 秒（Rust 快路径），但那 8 秒里屏幕上没有任何动静 |
| 「回退环境版本」 | 原 `:803-819`，对话框自己写着"约几分钟" | 只有一条结束 Toast |
| 「检查环境更新 → 下载并安装」 | `:1095-1180`：`if (done * 100 / total % 20 == 0L) toastOnMain("下载中 X%")` | 每 20% 闪一条 2 秒 Toast——**用户"提示时间有点短……我以为要重新下载呢"的原样复现** |

处置：新增 `app/src/main/java/com/example/zhengdao/ui/InstallFlow.kt`（`object InstallFlow`），
把四个可见性落点（`InstallProgress` 的 Compose 状态 + `InstallNotifier` 的通知栏 + `RunLog` 落
`Download/证道/logs/` + 成功结论写 `files/install-notice.txt`）收成**一份实现**，
API = `start(ctx, text, fromLocal)` / `update(ctx, text, percent)` / `finish(ctx, text)` /
`fail(ctx, text)` / `isRunning()` / `writeTerminalNotice(ctx, text)` + `@Composable StatusLine()`。

- `TerminalActivity` 三处安装改为委托它（原来四个落点是散着调的）；
- 设置页三条路径各 `InstallFlow.start(...)`，按钮下面摊一行 `StatusLine()`（图标跟阶段走
  ⏳ / ✅ / ⚠️——第一版 Done 态仍显示 ⏳，真机看过才觉得别扭）；
- 「检查环境更新」的下载回调从"每 20% 一条 Toast"改成"每 10% 更新通知栏百分比 + 状态行"，
  增量补丁成功后补一句"本次只下了 X，没有重下完整包"——直接对着用户那次误会写。

一条查出来的硬约束：`RootfsInstaller.install(context, archive, env, onEntry)`（`rootfs/RootfsInstaller.kt:109`）
的 `onEntry` 是"每条目回调一次、节流由调用方负责"，但 **Rust 快路径下一次都不回调**
（`:118-133` 的 `CoreNative.extract` 是整包跨一次边界），所以解压阶段只能给**不确定进度**
（通知栏转圈 + 文案里明说"没有细粒度进度"），不能假装有百分比。

真机全链路（PGT-AN10 / Android 16；用 `Download/证道/rootfs/debian-13.7-base-arm64.tar.zst`
311 MB 本地包，全程未联网）：

1. 点「修复环境（30 秒）」→ 确认框"将重新解压 Debian 系统层（约 30 秒 + Agent 重装时间）…"；
2. 卡片里立刻出现 `⏳ 正在解压系统层（没有细粒度进度，约 30 秒～几分钟）…`；
3. 通知栏出现常驻进度 `证道 · 正在准备运行环境` + 同一行正文 + 不确定进度条；
4. 8 秒后（`16:41:49` → `16:41:57`）卡片变 `✅ 修复完成：环境已重置，登录态与工作区保留（本次未联网下载）`，
   通知变成可划掉的 `证道 · 环境已就绪`；
5. `Download/证道/logs/zhengdao-log.txt` 逐行落地：
   `修复环境：用本地安装包（311 MB）重新解压系统层，不联网下载` →
   `正在准备安装包：debian-13.7-base-arm64.tar.zst` → `正在解压系统层（没有细粒度进度，约 30 秒～几分钟）…` →
   `修复完成：环境已重置，登录态与工作区保留（本次未联网下载）`；
6. `files/install-notice.txt` 被写入该结论，待下次开会话时由启动横幅消费一次。

教训（补两条）：

1. **修可见性要按"入口"清点，不能按"流程"清点**：终端与设置页是两个入口，只修一个等于没修。
   `grep -rn "Toast" app/src/main/java | grep -v "^.*//"` 比 `grep "安装"` 更容易逮住这类漏点。
2. **"30 秒"这类文案是估的，真机测出来是 8 秒**：文案要写成"约 30 秒～几分钟"这种区间，
   别把估算值写成承诺。

---

## E-037 · 2026-10-08 · 「运行日志」的善后其实是**把每轮正常运行的记录删掉**——与"日志留着给 Agent 查问题"直接冲突（已改成归档式保留）

**现场**：用户 2026-10-08 原话：

> 「那些日志都是方便给你们这些 agent 看查哪里有问题的，所以要留着」

而当时的实现（`app/src/main/java/com/example/zhengdao/rootfs/RunLog.kt` 旧 `:89-109`
`cleanupIfClean()`）是**反过来的**：

```kotlin
val hasError = current.readLines().any { line -> ERROR_MARKERS.any { line.contains(it, true) } }
if (hasError) { File(d, PREV_NAME).delete(); current.renameTo(File(d, PREV_NAME)) }
else { current.delete() }        // ← 没错误标记 ⇒ 整轮日志直接删掉
```

三处后果（都是"排查时最需要的东西恰好被删掉"）：

1. **正常那轮被删**：一次干净启动/一次成功的环境更新，日志在下次启动时消失——
   而"正常的时候长什么样"正是判断"哪里开始不对"的对照组；
2. **只留一代，且会被下一秒覆盖**：`.prev` 只有一份，下一次出错就把它顶掉，
   于是"第一次出错时的现场"永远留不住；
3. **启动路径上做整文件 IO**：`init()` 在 `ZhengdaoApp` / `MainActivity` / `TerminalActivity`
   三处被调用，旧实现每次都要 `readLines()` 整个文件（上限 512 KB）来判错，
   而这个判断只在设置页才需要。

**修法（新语义：只轮转，不删除）**——`app/src/main/java/com/example/zhengdao/rootfs/RunLog.kt`：

- `archivePrevious(d)` 取代 `cleanupIfClean(d)`：启动时把上一轮的 `zhengdao-log.txt`
  **整份归档**成 `zhengdao-log.<yyyyMMdd-HHmmss>.txt`（时间戳取上一轮最后写入时间
  `lastModified()`，不是归档时刻）；空文件仍删（没有信息量却占一个历史位）；
  `renameTo` 失败时退回复制（跨文件系统/被占用也别丢日志）。
- 保留策略 `pruneArchivesIn(d, keep = KEEP_ARCHIVES = 20, maxBytes = ARCHIVE_TOTAL_MAX = 20 MB)`：
  先按份数砍 `drop(keep)`，再按总量在剩下的里**从最旧的**继续删；**最新那一份永不删**
  （否则单文件超上限时会退化成"一份都不剩"）。
- 单文件上限 `MAX_BYTES` 512 KB → **2 MB**（`errors.log` 单独 1 MB），超限仍保留后半段。
- `lastRunHadErrors(ctx)` 改读**最新归档**（老用户只剩 `.prev.txt` 时也认它），
  并且**只在设置页的 IO 协程里调用**——启动路径不再读整份日志。
- 设置页（`app/src/main/java/com/example/zhengdao/ui/SettingsScreen.kt` 运行日志卡片）：
  文案改成"留最近 20 份 / 最多 20 MB，从最旧的开始轮转；不会因为「这轮没出错」就删"，
  显示"已有 N 份历史日志"，把原来那个把唯一一代删掉的「删除」按钮换成
  **「只留最近 3 份」**（用户主动清理，后台不偷偷删）。

**验证**：

- 新增单测 `app/src/test/java/com/example/zhengdao/rootfs/RunLogArchiveTest.kt`（4 例）：
  归档名判定（**本轮日志 / `errors.log` / 旧 `.prev.txt` / `zhengdao-log..txt` 都不算归档**）、
  份数超限从最旧的删、总量超限也从最旧删且不碰本轮与 `errors.log`、没有归档时纯空操作。
- 全量 `.\gradlew.bat :app:testDebugUnitTest :app:installDebug` = `Installed on 1 device.` +
  `BUILD SUCCESSFUL`；测试报告合计 **30 suites / 232 tests / failures=0 / errors=0 / skipped=0**。
- 顺带确认：全仓库只有 `RunLog` 会动 `Download/证道/logs/`（`Store.logsDir` 只被它使用），
  二档缓存清理的白名单在 `rootfs/tmp`，**不会**碰日志。

**教训**：

1. **"自动清理"要先问"这东西的读者是谁"**：日志的读者是**下一轮的排查者**（人或 Agent），
   不是本轮的用户。按"本轮用户不需要它"来清理，等于把排查材料当垃圾。
2. **"只留一代错误日志"看着节约，实际删掉的是对照组**：没有错误标记的那些轮次
   恰恰定义了"正常"，一次性删掉之后就再也说不清"从哪次开始坏的"。
3. **启动路径不该做能推迟的 IO**：判错只需要在设置页做，而旧实现把它塞进了三处 `init()`。

---

## E-038 · 2026-10-08 · 环境里的 GPU 软件渲染栈（mesa + LLVM，152 MB）：终端里没有任何入口用它，但"删掉"要先绕过 apt 的**假依赖** —— 最终只切掉 `libllvm19`（包 −25.9 MiB，见 E-042）

**现场**：用户 2026-10-08 原话：

> 「GPU软件渲染栈要是在终端确实没用或者某些人也用不到的话就删吧」

**先取证（真机只读探针，不猜）**——脚本由宿主机 `adb push` 进 `Download/证道/`（= guest 内 `/opt/zhengdao/`，
靠已有的 `-b <Store.root>:/opt/zhengdao` bind 双向可见），在 guest 里只敲一条短命令，输出写回文件再由 adb 读回
（绕开 `adb shell input text` 对引号/`$` 的破坏）。四组硬数据：

1. **反向依赖扫描**（`awk RS="\n\n"` 扫 `/var/lib/dpkg/status`）：
   `mesa-libgallium` 的父包只有 `libgbm1` 与 `libglx-mesa0`；`libgbm1` 的父包是 `libgl1-mesa-dri` 与
   `libsdl2-2.0-0`；`libsdl2-2.0-0` 的父包是 `ffmpeg` 与 `libavdevice61`；`libllvm19` 的唯一父包是
   `mesa-libgallium`。⇒ 这条链**唯一**的入口是 `ffmpeg` 包（经 SDL2 → GBM → mesa → LLVM）。
2. **`ldd /usr/bin/ffmpeg`（真机逐条）**：NEEDED 里有 `libdrm.so.2`、`libGL.so.1`、`libSDL2-2.0.so.0`、
   `libplacebo.so.349`、`libgbm.so.1`、`libwayland-egl.so.1`、`libvulkan.so.1` …，
   **没有 `libgallium-*.so`、也没有 `libLLVM.so.19.1`**（`ffprobe`/`ffplay` 同样）⇒ mesa 与 LLVM 对 ffmpeg
   只是 **apt 声明上的依赖**（运行时 dlopen），不是加载期依赖。**这一条是整个改动的支点**。
3. **已装体积**（`dpkg-query -W -f='${Package}\t${Installed-Size}'`）：`libllvm19` **120,416 KB**、
   `mesa-libgallium` **34,238 KB**（`du` 落盘：`libLLVM.so.19.1` 118 MB、`libgallium-25.0.7-2+deb13u1.so` 34 MB）；
   而 `libplacebo349` 8,449 KB 与 `libvulkan1` 611 KB 是 ffmpeg 的**真**依赖（`libplacebo → libvulkan1`），必须留。
4. **没有入口**：环境里 `/dev/dri` 不存在（`ls` 报 `Permission denied`）、`/usr/share/vulkan/icd.d` 不存在、
   更没有 X/Wayland display ⇒ mesa 的驱动后端**没有任何被拉起的路径**，`ffplay`（SDL2/GBM 输出）本来就不可能用；
   `app/` 全仓 grep `libgallium|mesa|libgbm|SDL2|vulkan` **0 命中**，客户端不碰。基线 `du -smx /` = **1021 MB**。

**修法**（`rootfs/build-rootfs.sh` 新增 §2.8，排在版本断言之前、清理之前）：

- 先**重打包 `libgbm1`**、摘掉它对 `mesa-libgallium` 的声明依赖：
  `apt-get download libgbm1` → `dpkg-deb -R` → `sed` 掉 `DEBIAN/control` 里的 `mesa-libgallium` →
  `dpkg-deb -b` → `dpkg -i`（并断言重打包后 control 里不再出现 `mesa-libgallium`）。
  理由：`libgbm1` **必须留**（ffmpeg/ffprobe NEEDED `libgbm.so.1`），不摘这条，apt 就会顺着
  `ffmpeg → libsdl2 → libgbm1 → mesa-libgallium → libllvm19` 把 ffmpeg 整串带走。
- 再 `apt-get -s -y purge` **模拟一遍**（`mesa-libgallium libllvm19 libglx-mesa0 libgl1-mesa-dri`），
  断言模拟输出的 `Remv/Purg` 名单里**不含** `ffmpeg|ffprobe|libavdevice61|libsdl2-2.0-0|libgbm1|libplacebo349|libvulkan1|libgl1|libglx0|libglvnd0|nodejs|python3|git|tmux|busybox|ripgrep|coreutils`，
  通过了才真卸。
- **刻意不跑 `apt-get autoremove`**：ffmpeg 链接的 `libGL.so.1`（`libgl1`）**没有被任何包声明成依赖**，
  autoremove 会把它当垃圾清掉，ffmpeg 随即起不来——"看起来是垃圾"和"实际是承重墙"之间隔着一层声明。

**修正（2026-10-08 晚，又踩了两轮 CI 之后；详见 E-041 / E-042）**：上面这版"重打包 `libgbm1` + 一次 purge 四个包"
**没有成功过一次**。Run 163 暴露出 `sed` 字符类里的 `)` 与版本约束撞车（E-041，纯语法问题）；修好之后
Run 164 又证明"只绕一条边不够"——`apt` 的 purge 会顺着主链
`mesa-libgallium ← libglx-mesa0 ← libglx0 ← libgl1 ← ffmpeg` 把 ffmpeg 一起带走（E-042）。
**最终做法：不动 mesa 本体，只切 `libllvm19`** —— 重打包 `mesa-libgallium` 摘掉它对 `libllvm19` 的
**声明**依赖，再 `apt-get -y purge libllvm19`（黑名单断言里补上 `mesa-libgallium`，它必须活着）。
放弃 mesa 本体的理由：要保住 ffmpeg，得再重打包 `libglx0` 与 `libgl1` 两个包，而多拿到的只有 34 MB，不划算。

**断言（同一次构建里验收，§2.9）**：`dpkg -s libllvm19` 必须**失败**，而 `dpkg -s mesa-libgallium` 必须**成功**
（本轮只切 LLVM，它还在）；
`ffprobe` 存在；`ldd /usr/bin/{ffmpeg,ffprobe,ffplay}` 不得出现 `not found`；`ffmpeg -f lavfi -i testsrc=size=64x64:rate=1 -frames:v 1 -f null -`
转码冒烟必须成功；`dpkg --audit` 无输出且 `apt-get check` 通过（依赖图没破）；
`node python3 git tmux rg busybox sqlite3 curl zstd uv` 逐个 `command -v`。

**收益（实测；build Run 165，`0b47899`，2026-10-08 19:01 完成，RootFS 步骤 success）**：
`latest` 上的环境包 **228,790,151 B → 201,630,773 B = −27,159,378 B（−25.9 MiB，−11.9%）**；
新索引 `builtAt=2026-10-08T11:00:36Z`、`env=51e1cc0c32f099aa`、
`sha256=d12cd1d37e0c4767e6730fd709eb796f5fe996b27e94e40b480bdb96afec9e27`、`size=201630773`。
落盘少 118 MB（`libLLVM.so.19.1`），但它在 zstd-19 下压缩比很高，所以**线上字节只降 25.9 MiB** ——
这正是 E-033 那条"删除类方案必须在线上压缩级别下测算"的又一次验证。
（本行原先写"落盘 −152 MB / 预期 −35～45 MB"，是按"mesa + LLVM 全删"估的；实际只切了 LLVM，原因见 E-042。）

**教训**：

1. **"声明依赖"与"加载依赖"是两回事**：`ldd` 说 ffmpeg 不吃 mesa，dpkg 说 ffmpeg 需要 libsdl2、libsdl2 需要
   libgbm1、libgbm1 需要 mesa —— 两条链各自都对。删东西之前必须把两条都拉出来：只看 apt 会得出"删不掉"
   （子代理第一版结论），只看 ldd 会得出"直接 rm 就行"（会把 libgbm1 一起删掉、ffmpeg 立刻起不来）。
2. **不许用 `autoremove` 收尾**：它按"有没有被声明依赖"判断去留，而 ffmpeg 真正 NEEDED 的 `libGL.so.1`
   恰好没有任何包声明它。清理工具的依据是**声明**，不是**真实使用**。
3. **数字要三份对齐再动手**：外部包页说 34.2 MB / 120.4 MB、真机 `dpkg-query` 说 34,238 KB / 120,416 KB、
   `du` 说 34 MB / 118 MB —— 三份一致才敢改构建脚本；体积收益也必须按线上压缩级别（zstd-19）测算。

---

## E-039 · 2026-10-08 · 构建失败时"外面什么都看不到"：Actions 日志对未登录用户不可见，得让脚本自己发 `::error::` 注解

**现场**：`efd68a7`（E-038 那次剔除 GPU 栈）推上去后，build **Run 162** 整轮显示**绿色**，
但 `latest` 上的 `rootfs-index.json` 还是上一版（`size: 228790151`、`builtAt: 2026-10-08T06:49:30Z`），
新包根本没产出。翻到 job 页面才看到一条注解：

```
构建 Debian 13.7 RootFS（qemu 交叉构建，约 30–90 分钟；失败不阻塞 APK 上传）
Process completed with exit code 2.
```

**为什么外面看不到日志**：Actions 的 job 页面匿名只渲染一句 `Sign in to view logs`；
`https://github.com/<owner>/<repo>/actions/runs/<run_id>/logs` 匿名下载直接 **HTTP 404**；
`web_fetch` 走 `api.github.com` 在本机被策略拒（`non-public IP address`），`gh` CLI 也不在 PATH。
于是"唯一的匿名可见通道"就是**注解（annotation）**——可它当时只告诉我们"退出码 2"。
再叠加 `build.yml` 的 rootfs job 是 `continue-on-error: true`、发布步骤有 `compgen -G "rootfs-files/*"` 守卫，
失败就被完全吞掉了：CI 绿着、`latest` 不动、没人知道死在哪一行。

**修法（`rootfs/build-rootfs.sh` 的"失败自述"）**：

1. `annot()` = `printf '::error::%s\n' "$(printf '%s' "$1" | tr '\n' '|' | cut -c1-1500)"`
   —— 注解必须单行，所以把内部换行压成 `|`、并截断到 1500 字符。
2. 外层脚本与 chroot 内 CONF 脚本**各挂一个 `trap ... ERR`**，打印
   `[当前小节] 第 N 行 \`命令\`，退出码 R`；小节用显式变量跟踪（外层 `STEP_OUTER`、内层 `STEP`），
   在 `[1/4] [2/4] [2.5/4] [3/4] [4/4] [5/5]` 与 `---- 2.1 … ---- 2.11` 每段前赋值。
3. §2.8 里每条外部命令（`apt-get download` / `dpkg-deb -R` / `dpkg-deb -b` / `dpkg -i` /
   `apt-get -y purge`）都单独抓 `2>&1`，失败时把**它自己的 stderr** 塞进注解。
4. §2.9 与包体积门禁里 20 处 `echo "[断言失败] …"` 改成 `annot "[断言失败] …"`；
   外层 5 处 `echo "[错误] …"` 同样处理；`tar` / `manifest` / `patch` / `index` 四处也各自抓输出。

**教训**：

1. **"日志看不到"是一个必须正面解决的真问题，不是环境噪音**：失败信息只存在于日志里，
   就等于这个构建**没有可观测性**。能让匿名用户看到的通道只有注解，所以关键失败点必须自己发注解。
2. **`exit 1` 不触发 `ERR` trap**。脚本里手写的 `echo "[断言失败] …"; exit 1` 属于**显式退出**，
   trap 不会补一条注解——这类分支必须自己调 `annot`，否则"加了 trap"会给人错误的安全感。
3. **注解里必须带"哪一小节"**：只给行号，一旦脚本再改几行就对不上了；`[2.8 剔除 GPU 栈]` 这种标签
   才是人（和 Agent）能直接对上号的定位信息。
4. **`continue-on-error: true` 的 job 必须配"可见的失败"**：它保证了 `latest` 不会被空目录覆盖（好事），
   但也让失败隐身（坏事）。两者要一起设计：守卫 + 自述。

**补记（同日晚，build Run 163，`ee766e7`）**：这套注解上线**第一轮**就把真因带了回来 ——
`[2.8] dpkg-deb -b 重打包 libgbm1 失败：… 'Depends' field, syntax error after reference to package
'libwayland-server0'`。上一轮外面只能看到 `exit code 2`，这一轮"读一行原始 stderr 就能改代码"。
真因与修法见 **E-041**。

---

## E-040 · 2026-10-08 · 「装过」不等于「装得回来」：AGY 的恢复入口按用户拍板关掉

**现场**：

- AGY（Antigravity）第一次安装失败（终端原文 `Fatal: Could not connect to the release server to
  download the manifest. Please check your internet connection or firewall settings.`）；
  账本 `Download/证道/agents/installed.json` 里因此留下一条 `{"id":"antigravity",…,"state":"installing"}`，
  主页随之常驻一颗蓝色「**恢复全部（1 个）**」——点下去必然再失败一次。
- 真机网络探针（2026-10-08 17:19，结果落在 `Download/证道/net-probe.txt`）：环境里
  `registry.npmmirror.com -> 200`，而 `github.com` / `raw.githubusercontent.com` /
  `antigravity.google` / `registry.npmjs.org` / `www.google.com` **全部 `000`**；
  `env` 里只有 `no_proxy=localhost,127.0.0.1,::1`，**没有任何 `http_proxy` / `https_proxy`**。
  ⇒ 不是"代理没开"这么简单：Google 那条路要终端整体出境（用户的话："除非把终端的 IP、地址
  什么的都改成国外才行"）。
- 用户原话：「那就不管AGY了，这玩意儿用的人少。清掉AGY的恢复功能吧，谷歌对地域限制太严了，
  除非把终端的IP、地址什么的都改成国外才行」；在给出的选项里选了
  **「只做恢复侧：AGY 不再进恢复候选（丹房保留 AGY 安装卡片）」**。

**改法**：

1. `AgentInfo` 新增 `restorable: Boolean = true`（`app/src/main/java/com/example/zhengdao/ui/AppState.kt`），
   字段注释里直接留下判据（网络探针的逐项结果）与用户原话；
2. 出厂清单里的 AGY 条目 `restorable = false`——**安装卡片、探测路径、卸载能力全部保留**，
   只是不再参与"恢复全部"（境外网络下用户仍可自己点装）；
3. `AgentLedger.restoreCandidates` 抽出纯函数
   `pickRestoreCandidates(ledgerIds, agents)`，筛选条件多一条 `it.restorable`
   （`app/src/main/java/com/example/zhengdao/ui/AgentLedger.kt`）；
4. 单测补两条（`app/src/test/java/com/example/zhengdao/ui/AgentLedgerTest.kt`）：
   候选只收「账本里有 + 现在探测不到 + 有安装命令 + `restorable`」，且**顺序跟清单走**。

**教训**：

1. **"装过"≠"装得回来"**：账本记的是历史事实，恢复能力取决于**当下网络能否到达发行方**。
   把两者混为一谈，主页就会递给用户一个点了必错的按钮。
2. **失败的尝试不该留下"待恢复"的假象**：`markStarted` 先把 `installing` 写进账本是对的
   （那轮真在装），但"要不要给恢复入口"的判据里必须带上"这条路现在走不走得通"。
3. **地域限制是产品约束，不是环境噪音**：凡是"官方安装器只从单一境外域名拉包"的 Agent，
   都该默认假定在受限网络下不可恢复 —— `restorable` 就是给这类条目准备的开关
   （将来别的条目遇到同类问题，改一个布尔值即可，不用动恢复逻辑）。

**同轮补做（用户 2026-10-08 拍板）**：恢复横幅文案改了。旧文案写死
「这些 Agent 的程序已随上次卸载消失」，而账本里更常见的其实是"上次装到一半失败"
（`state=installing`，本轮 AGY 就是）⇒ 改成
「这些 Agent 没装完（或程序已不在本地），但安装脚本与包缓存还在 …」
（`app/src/main/java/com/example/zhengdao/ui/HomeScreen.kt`，横幅段的注释里也留了这次改动的由来）。

---

## E-041 · 2026-10-08 · `sed` 的字符类里放了数据里也会出现的字符（`)`），于是重打包的 `.deb` 少了一个括号：Run 162 那个 `exit 2` 的真因

**现场**：E-039 那套"失败自述"上线后的**第一轮**（build **Run 163**，`ee766e7`）就把真因带回来了。
匿名可见的 build job 注解里原文是：

```
[2.8] dpkg-deb -b 重打包 libgbm1 失败：dpkg-deb: error: parsing file '/tmp/tmp.CUna1R4a8h/gbm/DEBIAN/control' near line 7 package 'libgbm1':| 'Depends' field, syntax error after reference to package 'libwayland-server0'
```

（同一 job 的另一条注解只有 `Process completed with exit code 1.` ⇒ 小节标签 + 原始 stderr 那条才是有效信息。
整轮仍显示 `success`，因为 rootfs 那步是 `continue-on-error: true`；`latest` 这次也**没更新**。）

**真因**：§2.8 要摘掉 `libgbm1` 对 `mesa-libgallium` 的声明依赖，原来的写法是

```bash
sed -i -E 's/, *mesa-libgallium[^,)]*//g' "$GPU_TMPDIR/gbm/DEBIAN/control"
```

字符类 `[^,)]` 里塞了一个 `)`，而依赖项的**版本约束自己就含括号**。真实 control（`https://deb.debian.org/debian/pool/main/m/mesa/libgbm1_25.0.7-2+deb13u1_arm64.deb`，
44,144 B 的 ar 包，内含 `control.tar.xz` 1,444 B）的第 7 行是：

```
Depends: libc6 (>= 2.38), libdrm2 (>= 2.4.121), libexpat1 (>= 2.0.1), libwayland-server0 (>= 1.15.0), mesa-libgallium (= 25.0.7-2+deb13u1)
```

于是 `[^,)]*` 只吃到 `(= 25.0.7-2+deb13u1`（在右括号**之前**停下），把那个孤零零的 `)` 留在原地：

```
… libwayland-server0 (>= 1.15.0), )
```

`dpkg-deb -b` 随即报 `'Depends' field, syntax error after reference to package 'libwayland-server0'`。
Run 162 的 `exit 2` 就是它（当时外面只能看到"退出码 2"，没有注解 ⇒ 定位不了）。

**修法**（`rootfs/build-rootfs.sh` §2.8）：以**逗号**为界吃掉整条版本约束，再逐项收尾空项：

```bash
sed -i -E \
  -e 's/(,[[:space:]]*)?mesa-libgallium[^,]*//g' \
  -e 's/,[[:space:]]*,/,/g' \
  -e 's/,[[:space:]]*$//' \
  -e 's/:[[:space:]]*,[[:space:]]*/: /' \
  -e 's/[[:space:]]+$//' \
  -e '/^(Depends|Pre-Depends|Recommends|Suggests|Breaks|Conflicts|Provides|Replaces|Enhances):[[:space:]]*$/d' \
  "$GPU_TMPDIR/gbm/DEBIAN/control"
```

并在 `dpkg-deb -b` **之前**加一道自查：依赖字段里不许出现 `, ,` / 行尾逗号 / `: ,` / 空括号
（`grep -nE '^(Depends|Pre-Depends|Recommends):' … | grep -qE ',[[:space:]]*,|,[[:space:]]*$|:[[:space:]]*,|\([[:space:]]*\)'`），
命中就把字段原文塞进 `annot` 注解再退出 —— 自己报错比等 `dpkg-deb` 报错更直白。

**证据（本地，改完先验，不必等 CI）**：

- 把真实 `.deb` 的 control 抽出来跑一遍新 sed，得到
  `Depends: libc6 (>= 2.38), libdrm2 (>= 2.4.121), libexpat1 (>= 2.0.1), libwayland-server0 (>= 1.15.0)`；
  `grep mesa` 无命中、无逗号残留，且 `diff`（去掉 `Depends` 行）显示**整份 control 一字未动**（`Description` 续行也没碰）。
- 6 种排布回归（mesa 在末尾 / 中间 / 最前 / 唯一一项（整行删）/ 无版本约束 / `Recommends`+`Suggests` 也含 mesa）：
  全部通过，空掉的依赖字段整行删除、不留 `Depends:` 空字段。
- `bash -n` 自检：外层 `OUTER_SYNTAX_OK`，抽出 CONF 内层（255 行）`INNER_SYNTAX_OK`。

**教训**：

1. **排除字符必须是分隔符，不能是内容**：当时的 `[^,)]` 是"顺手"想把右括号一起吃掉，可版本约束
   `(= 1.2.3)` 自己就带括号 —— 排除集里放内容，就会在"内容里恰好也有它"的地方悄悄切错。
   这类错误 `bash -n` 永远看不出来（语法合法），本地不真跑一遍就只能等 CI 用整轮构建来告诉你。
2. **"失败自述"上线第一轮就自证了价值**：上一轮（Run 162）外面只有 `exit code 2`，靠猜；
   这一轮注解把 `dpkg-deb` 的原始 stderr 带了回来，定位从"翻日志（还看不到）"变成"读一行"。
   可观测性不是锦上添花，它决定一次修复是 5 分钟还是 5 轮 CI。
3. **拿真实输入做回归，别拿自己想象的输入**：一开始只用"单行、mesa 在末尾"的样例试，
   结论是"通过"；直到把 deb.debian.org 上那个真包下回来跑，才算真的验证过。
   涉及外部数据格式的改动，**样本要从真实来源取一份**。

---

## E-042 · 2026-10-08 · 剔 GPU 栈的第三层：`apt` 的 purge 解算会顺着 `mesa-libgallium ← libglx-mesa0 ← libglx0 ← libgl1 ← ffmpeg` 把 ffmpeg 一起带走 —— 改成只切 `libllvm19`（−118 MB）

**现场**：E-041 的 sed 修好之后，build **Run 164**（`57757a2`，runId `37761000072`）的 RootFS 步骤**又**失败
（整轮仍显示成功，因为 `continue-on-error: true` + 发布步骤的空目录守卫），自述注解这次把 apt 的模拟
卸载名单原样带了出来（外面匿名可见）：

```
[2.8] 卸载 mesa 会连带移除关键包，已中止：Purg ffmpeg [7:7.1.5-0+deb13u1]|Purg libavdevice61 [7:7.1.5-0+deb13u1]|
Purg libgl1 [1.7.0-1+b2]|Purg libglx0 [1.7.0-1+b2]|Purg libglx-mesa0 [25.0.7-2+deb13u1]|
Purg libgl1-mesa-dri [25.0.7-2+deb13u1]|Purg mesa-libgallium [25.0.7-2+deb13u1]|Purg libllvm19 [1:19.1.7-3+b1]
```

**真因**：上一轮只摘掉了 `libgbm1 → mesa-libgallium` 这条**支线**，而要点掉 mesa 本体，链路上还有
别的边。这次 Purg 名单摊开的顺序是

```
mesa-libgallium ← libglx-mesa0 ← libglx0 ← libgl1 ← ffmpeg
```

（`mesa-libgallium ← libglx-mesa0` 这一段由真机反向依赖扫描确认过；`libglx0 ← libgl1 ← ffmpeg`
这两段是这次 Purg 名单反推的 —— 即 ffmpeg 一侧声明了 `libgl1`，逐级回到 mesa）
⇒ 一条 `apt-get purge mesa-libgallium libllvm19 libglx-mesa0 libgl1-mesa-dri` 就会把 ffmpeg 与
libavdevice61 一起带走。**上一轮"绕过 apt 的假依赖"这个判断本身没错，错在只绕了一条边。**

**决策**：不再动 mesa 本体，**只切 `libllvm19`**（落盘 118 MB，构建 Top20 里第二大）。理由：

- 要动 mesa 本体，就得再重打包 **两个**包（`libglx0`、`libgl1`）才能把 ffmpeg 从链上摘下来，
  而多拿到的只有 `libgallium-25.0.7-2+deb13u1.so` 的 34 MB ⇒ 风险/改动量不划算；
- `libllvm19` 是这条链上**最干净的一条边**：真机反向依赖扫描显示它的父包**只有** `mesa-libgallium`
  一个 ⇒ 在 `mesa-libgallium` 的 `Depends` 里摘掉 `libllvm19 (>= 1:19.1.0)`，purge 就只带走它自己；
- 用户在终端里对 LLVM 同样没有任何入口（E-038 的三条取证：`ldd /usr/bin/ffmpeg` 的 NEEDED 闭包里
  既没有 `libgallium-*.so` 也没有 `libLLVM.so.19.1`；proot 里没有 `/dev/dri`、没有 X/Wayland display；
  全仓 `app/` 对 `libgallium|mesa|libgbm|SDL2|vulkan` 零命中）。

**修法**（`rootfs/build-rootfs.sh` §2.8，整段重写）：
`apt-get download mesa-libgallium` → `dpkg-deb -R` → 多表达式 `sed` 摘掉 `libllvm19` 一条
（`-e 's/(,[[:space:]]*)?libllvm19[^,]*//g'` + 空项/尾逗号/`: ,`/空依赖字段逐项收尾）→ 自查
（无残留 + 依赖字段无 `, ,`/尾逗号/空括号）→ `dpkg-deb -b` → `dpkg -i`；然后
`apt-get -s -y purge libllvm19` 打印 `^(Remv|Purg) ` 名单并断言黑名单（这次把 `mesa-libgallium` 也加了
进去 —— 它必须活着）→ `apt-get -y purge libllvm19`。**旧的 libgbm1 重打包段整段删除**（它绕的那条边
现在不再需要）。§2.9 的断言反过来：`libllvm19` 必须没了，`mesa-libgallium` **必须还在**（少它说明
重打包或卸载跑偏了）。每条外部命令仍各自把 stderr 塞进 `annot`（E-039 的机制，这两轮都靠它定位）。

**证据（本地，改完先验）**：把真包 `mesa-libgallium_25.0.7-2+deb13u1_arm64.deb`（deb.debian.org 下回来，
8,032,536 B，成员 `debian-binary`/`control.tar.xz`/`data.tar.xz`）的 control 抽出来（903 字符）跑新 sed：

- 原 `Depends` 第 7 行共 19 项，其中 `libllvm19 (>= 1:19.1.0)` **夹在中间**（`libgcc-s1` 与 `libsensors5` 之间）；
- 改后 18 项：`libllvm19` 整条连同它的括号版本约束干净消失，其余 18 项**逐字未动**；
- 除依赖字段外整份 control `diff` 为空（`Description` 续行、`Provides: libglapi-mesa` 都原样）；
- 回归脚本 `C:\Users\guoli\AppData\Local\Temp\zd-watch\mesa-check.sh` = `RESULT=PASS`；
- `bash -n` 内外层自检（外层 + 抽出的 CONF 内层 266 行）= `OUTER_SYNTAX_OK` / `INNER_SYNTAX_OK`。

**教训**：

1. **"绕过 apt 的假依赖"要绕过"整条链"，不是"一条边"**：依赖图上每一条边都得各自重打包一个包，
   所以选目标时先看**这条边上游有几个包**（`libllvm19` 只有 1 个父包；mesa 本体至少有 2 个）。
   边际收益 34 MB、边际成本 2 个包 ⇒ 明确放弃，并把"为什么放弃"写进脚本注释，免得下一轮又有人试。
2. **断言要写"这个包自己的事实"，别抄隔壁包的**：我给回归脚本写"该留的依赖"清单时，顺手抄了上一轮
   `libgbm1` 的 `libwayland-server0`，结果它根本不在 `mesa-libgallium` 的 `Depends` 里，脚本报了个
   假失败（`reasons: lost-libwayland-server0`）。改成逐项点名 + **结构性断言**（"依赖项数必须恰好少 1"）
   之后，这个绿灯才是可信的。
3. **每一轮失败都要把"新事实"写回脚本**：这次失败注解里的 Purg 名单本身成了下一轮的判据
   （黑名单里补上 `mesa-libgallium`）。CI 失败的价值不在"红了"，而在它把依赖图的下一层摊开给你看。

## E-043 · 2026-10-08 · 「终端里复制不出东西」的真因是 tmux 的 `set-clipboard` 默认 `external` —— termux 的 OSC 52 backport 因此在真机上一直是空转

**现场**：2026-10-08 20:35，termux 单点 backport（合并提交 `b3622f5`，含 OSC 52 累积上限从 8192
抬到 `100*1024+10` 的修复）进入 main、同签名 release APK 装机之后，按"发一条 >8 KiB 的 OSC 52，
看 `TerminalActivity.kt:809-814` 的 Toast「已复制 N 个字符」"做真机验收：脚本确实跑了（屏上打出
`OSC52-9000-SENT`），但**一条 Toast 都没有**，随后点终端工具栏「粘贴」也什么都粘不出来。

**排查**（三步，缺任何一步都会误判成"App 的 OSC 52 解析坏了"）：

1. **BEL 终止是死路**：tmux 3.5a 只认 ST（`ESC \`）终止，用 `\x07` 结尾的 OSC 52 会被 tmux 当普通
   文本透到屏上（截图里能看到裸 base64 `]52;c;U1NT…`）⇒ 序列根本没到 App，这时"没有 Toast"说明不了任何事。
2. **对照实验切链路**：`tmux set-buffer -w "ZD-CLIP-PROBE-A"` 之后点「粘贴」，剪贴板里粘出了这句话
   （屏上可见，剪贴板预览条也同步显示）⇒ **tmux→App 与 App→剪贴板这两段都是通的**，坏只坏在
   pane 内应用 → tmux 这一段。
3. **tmux 侧真机读数**：`tmux 3.5a`；`set-clipboard external`（默认值）；`#{client_termfeatures}` 里
   其实**有** `clipboard`；会话内 `$TERM=tmux-256color`；`infocmp` 里**没有 `Ms`**；
   `~/.tmux.conf` 只有 16 字节（内容 `set -g mouse on`，由 `ProotLauncher` 预置）；`/etc/tmux.conf` 不存在。

**真因**：`set-clipboard external` 的语义是"只接受**外层终端**下发的剪贴板写入，不把 pane 里的 OSC 52
转发出去"；只有 `on` 才既存进 tmux buffer、又透传给外层终端。改成 `on` 之后三种负载立刻全部打通：
100 字节 → Toast「已复制 100 个字符」、9000 字节 → 「已复制 9000 个字符」、12345 字节 →
「已复制 12345 个字符」，剪贴板预览条分别是 `SSSSSSSS…` / `BBBBBBBB…` / `CCCCCCCC…`
⇒ **100 KiB 上限那条修复本身是好的，它只是从来没有机会被触发。**

**修法**：`app/src/main/java/com/example/zhengdao/terminal/ProotLauncher.kt` 里那段"幂等补
`~/.tmux.conf`"的代码（原本只补 `set -g mouse on`）加第二条：匹配
`(?m)^\s*set(-option)?\s+-g\s+set-clipboard\b`，缺失就追加 `set -g set-clipboard on`，并
`RunLog.log("tmux 配置已补：set -g set-clipboard on（终端内 OSC 52 才能写进手机剪贴板）")`；
有改动才写文件，不覆盖用户自有配置。提交 `87b7c10`（分支 `fix/tmux-set-clipboard`）→
merge **`28e733d`**，已推 origin/main。

**证据（配置驱动，不手工改运行时选项）**：先 `tmux set-option -g set-clipboard external` 把运行时复原，
`adb install -r` 新包（`lastUpdateTime=2026-10-08 20:47:14`）、`am force-stop` 冷启 → 点底部「终端」
→ `ProotLauncher` 重跑：`~/.tmux.conf` 变成 `set -g mouse on` + `set -g set-clipboard on`，日志原文
`[10-08 20:48:01] tmux 配置已补：set -g set-clipboard on（终端内 OSC 52 才能写进手机剪贴板）`，
**新起的 tmux server**（`pid=4785`，`created Thu Oct 8 20:48:01 2026`）直接报 `set-clipboard on`；
同一条 9000 字节脚本在收起软键盘后弹出完整 Toast「**已复制 9000 个字符**」。

**教训**：

1. **backport 正确 ≠ 功能可用**：转义序列类功能要连着"谁有可能吞掉它"一起验。`TerminalEmulator` 的
   单测只能证明解析器收得下 100 KiB；真机上决定这条序列能否抵达解析器的是 pty 与 tmux 之间的配置。
2. **终端页的屏幕是唯一真相**：终端是 canvas，a11y 树里没有任何节点 ⇒ 验收一律走"push 脚本 →
   guest 里 `bash /workspace/x.sh` → 结果写文件再 `adb pull` 当纯文本读"，或者截图；
   另外 `adb shell input text` 里**不能带 `;`**（设备侧 shell 会把它当命令分隔符，报
   `/system/bin/sh: %stmux%s: inaccessible or not found`）——这也是一开始命令打不进终端的真因。
3. **Toast 是这类事件的唯一出口，且只活 2 秒**：`logcat` 里没有 Toast 文本，只能用
   `dumpsys window windows | grep -c 'u0 Toast'` 轮询、命中即截图；「已复制 N 个字符」这类只在
   Toast 里出现的信息，不截图就等于没验。
4. **先做对照实验再归因**：`set-buffer -w` 那一发把"三段链路"切成两段，一步就把嫌疑锁死在 tmux 配置上；
   否则很容易误判成自家 OSC 52 解析或渲染层的锅。

## E-044 · 2026-10-08 · 「修复环境」把环境指纹抹掉：标记文件只记 distro/env，重装时无从证明「本地包 == 当前环境」 ⇒ 补 `archive-sha256` 一行，只有同源才回填 env

**现象（真机时间线，Honor PGT-AN10 / Android 16）**：

- 21:26 设置页「检查环境更新」→ 结果行「**已是最新版本（13.7，环境 51e1cc0c32f099aa）**」。
- 21:27:54 点「修复环境（30 秒）」→ 8 秒后「修复完成：环境已重置，登录态与工作区保留（本次未联网下载）」。
- 21:56 再点「检查环境更新」→ 结果行变成「**本地已安装 13.7，但缺少环境指纹记录（env）：本次将用全量包（约 192 MB）重装一次以补上指纹，重装后「检查环境更新」才能正确判断新旧。**」

也就是说：**用户点一次「修复环境」，就把自己的增量基线抹掉了**，下次更新只能全量下 192 MB。这不是文案问题，是标记文件真的少了一行。

**根因**：标记（`<filesDir>/rootfs/.zhengdao-rootfs-ok`）里只记 `distro` / `env` / `installed-at` 三行，而"重装的是同一个包吗"这件事从来没被记下来。于是：

- `ui/SettingsScreen.kt` 的修复环境路径调 `RootfsInstaller.install(ctx, archive) { }`（env 留空、包 sha 留空）；
- `rootfs/RootfsInstaller.kt` 里两处收尾都写 `RootfsMarker.write(tmpDir, DEFAULT_DISTRO, env, archiveSha256 = archiveSha256)` ⇒ `env=` 行被整行跳过；
- 而"缺少指纹"和"环境真的变了"在标记里长得一模一样 ⇒ 检查更新只能保守地要求全量。

**修法（`48d5569` → merge `d8fb5d6`）**：

1. `rootfs/RootfsMarker.kt`：`Data` 增 `archiveSha256: String?`；标记新增一行 `archive-sha256=<64 位 hex>`（写在 `env=` 之后、`installed-by` 之前），解析时用 `SHA_RE = Regex("^[0-9a-fA-F]{64}$")` 校验形态，**形态不合 = 没记过**（旧标记没有这一行 ⇒ 一律视为"不确定"，行为与修复前完全一致，向后兼容）。新增 `installedArchiveSha256(rootfsDir)`。
2. `rootfs/RootfsInstaller.kt`：新增 `envForReinstall(rootfsDir, archiveSha256): String?` —— **只有**标记里记的 sha 与"这次真正要装的那个包"的 sha 相等（忽略大小写）时才把原 `env` 原样传回，否则回 `null`（照装，但**绝不凭空造一个 env**）。`install(...)` 换签名带上 `archiveSha256`，Rust 快路径与 Java 路径都写。
3. `rootfs/RootfsDownloader.kt`：把 `verifySha256` 里的流式哈希抽成 `sha256Of(file): String` 复用（128 KB 缓冲、小写 hex）。
4. 三条调用点各自带 sha：修复环境 `ui/SettingsScreen.kt:1231`（`repairSha` → `envForReinstall`）、回退版本 `:941`、全量更新 `:1397`；`TerminalActivity.kt:1101`（本地缓存包）/`:1167`（联网下载）带已知 sha；`:1050`（用户用 SAF 自选的文件）**故意不带** —— 说不清来源就不写，宁可下次全量。两条路径都把「同源→保留环境指纹 …」或「无法确认同源→不写（下次全量）」写进 RunLog，真机验收一眼能看。
5. 单测：`rootfs/RootfsEnvTrustTest.kt` +4（同源写回、大小写/空白容错、对不上/没记 sha/形态不合一律 null、不会凭空造 env、`sha256Of` 跨 128 KB 缓冲边界）、`rootfs/RootfsMarkerTest.kt` +3（往返与行序、空值不写行、形态不合视同没记过）。

**教训**：

1. **"记了没记"和"记的值是空"必须能区分**：标记文件里少一行与写一个空值，后续逻辑必须能分开判 —— 这里的做法是"旧格式没有这一行 ⇒ 视为不确定"，而不是"空 = 未知"，否则老用户的标记会被新逻辑解释成"确定没记过"，凭空多一次全量。
2. **只在能证明同源时才补写信任信息**：`envForReinstall` 宁可回 `null`（放弃保留）也不猜；猜错一次就是把两个不同环境包的内容混进同一个指纹，比"多下一次 192 MB"贵得多。
3. **统计用例数要固定口径**：此前记的「32 suites / 81 tests」是抓 `test-results` XML 时抓错节点得到的假数字；按"每个 XML 首两行的 `tests="N" skipped=… failures=… errors=…"`"重数 —— 改前 **264**、改后 **271**（+7 正是本轮新增）。以后一律走这个数法。

## E-045 · 2026-10-08 · 向 Rust 进化的第一刀：安装完整性锚挪进 Rust 核心（撤销一段**从未启用**的死代码、192 MB 包少读一遍），顺带把入库的 `.so` strip 掉 278 KB —— 并记住「16 KB 对齐漏了不是崩溃而是静默降级」

**发现（读代码读出来的，不是线上事故）**：

- `rust/core/src/extract.rs` 的 `extract_pipeline(archive_path, target_dir, expected_sha256: Option<&str>)` **本来就会在解压途中流式算归档 sha 并对账**（失配走 `ExtractError::ShaMismatch`，文案 `SHA256 不匹配: 期望 {expected} 实际 {actual}`）。
- 但 `rootfs/RootfsInstaller.kt` 一直这样调：`CoreNative.extract(p, tmp, null)`，旁边注释写着「SHA 已在调用方校验过」⇒ 那段 Rust 校验**从来没被启用过**；代价是同一个 192 MB 包被读两遍（Java 先哈希一遍校验，Rust 再解压读一遍）。

**改动（分支 `feat/rust-install-integrity`）**：

1. `RootfsInstaller.kt`：Rust 快路径改成 `CoreNative.extract(archive.canonicalPath, tmpDir.canonicalPath, archiveSha256)` —— 信任锚落在"恰好被解压的那些字节"上，一次读盘。失败分流：`err.message` 命中 `SHA_MISMATCH_MARK = "SHA256 不匹配"` ⇒ `throw InstallFailed("安装包完整性校验失败（Rust 核心）：…")`，**不回退 Java**（Java 路径根本不校验 sha256，回退等于把校验降级成没校验）；其余失败仍 `Log.w("Rust 解压失败，回退 Java 路径")`。
2. Rust 侧新增文件摘要：`rust/core/src/sha256.rs` 的 `sha256_file_hex(path) -> io::Result<String>`（128 KB `BufReader` 分块喂流式 `Sha256Stream`）、`jni_bridge.rs` 的 `Java_com_example_zhengdao_rust_CoreNative_nativeSha256File`（成功回 hex、失败回 **null**，绝不 panic 跨 FFI）。
3. `rust/CoreNative.kt`：`sha256File(file): String?`（`rustAvailable` 守卫 + `runCatching`）；`rootfs/RootfsDownloader.kt` 的 `sha256Of` 优先走 Rust，回落平台 `MessageDigest`，并留两行日志「**sha256Of 走 Rust 核心** / **走平台回退**」——这是 release 包里 R8 万一改了 native 方法名时**唯一能分辨的出口**（AGP 默认规则 `-keepclasseswithmembernames class * { native <methods>; }` 理论上是安全的，但 E-022 已经栽过一次"名字被 R8 改掉"）。

**验证（都在本机做完）**：

- host `cargo test -p zhengdao_core --release` ⇒ **10 passed / 0 failed**（含新增的「文件摘要与一次性一致_跨缓冲边界」「空文件摘要等于空串摘要」）。
- `cargo build --release --target aarch64-linux-android -p zhengdao_core` ⇒ 未 strip **1,099,248 B**。
- 入库前 `llvm-strip.exe --strip-unneeded` ⇒ **811,592 B**（原入库版 1,095,744 B，**−284,152 B ≈ −278 KB**）。注意这 278 KB **只是仓库/检出的体积，APK 不因此变小**：AGP 打 release 时本来就会替你把 native 库 strip 一遍 —— 实测 main 那个 APK 里 `libzhengdao_core.so` 是 **808,704 B**（对应仓库里 1,095,744 B 的未 strip 原件），本轮 APK 因为多了新增的 Rust 代码反而比 main 大 1,092 B（**4,309,349 vs 4,308,257 B**）。strip 后 sha256 `EC78CADC…13E649`、四个 LOAD 段 `p_align` 全 `0x4000`、`--dyn-syms` 里 `nativeExtract` / `nativeSha256File` / `nativeSha256Hex` 三个 JNI 入口都在。

**教训**：

1. **"参数存在"不等于"路径启用"**：`expected_sha256: Option<&str>` 一直都在，但调用方永远传 `null` ⇒ 一段看起来在跑的校验其实是死的。看到「已在调用方校验过」这类注释，要**顺着看调用方到底校验了什么**（这里：校验了完整性，却没把结论交给唯一能把它和"被解压的字节"绑在一起的执行者）。
2. **人工入库的 `.so` 必须有固定工序**：构建 → `llvm-strip --strip-unneeded` → 两道校验（16 KB 页对齐 `p_align=0x4000`、JNI 符号仍在 `--dyn-syms`）。理由是 `.cargo/config.toml` 只给 `aarch64-linux-android` 加了 `-Wl,-z,max-page-size=16384`，而**漏掉它不会崩溃，只会静默降级**（16 KB 页设备 `loadLibrary` 失败 → `isRustAvailable()=false` → 全部悄悄退回 Java 路径）。
3. **仓库里没有任何 workflow 构建这个 `.so`**（`.github/workflows/ci.yml` 只在 ubuntu 上 `cargo test`）⇒ 源码与产物之间没有自动一致性检查，改 Rust 就必须本机重建并入库，别指望 CI 拦。
4. **本机跑 host `cargo test` 要把 w64devkit 放进 PATH**（`C:\Users\guoli\.cargo\bin;C:\Users\guoli\w64devkit\w64devkit\bin;`），否则 zstd-sys 的 `cc-rs` 报 `failed to find tool "gcc.exe"`、`exit=101`（E-027）。
5. **别把 `Get-ChildItem -Recurse` / `glob` 指向 `rust/target`**：一万多个构建产物（incremental `.o`、`libzstd.a`、host `.dll`/`.exe`），一次调用就能烧掉约 4 万 token 的上下文。
6. **"仓库里的文件大" ≠ "APK 大"**：本轮最初估"strip 一下 APK 能省 278 KB"，实际是**零** —— AGP 打 release 时自动 strip native 库（main 的 APK 里那个 `.so` 808,704 B，仓库里却是 1,095,744 B）。所以要判断"瘦身能不能省用户流量"，必须**拆开 APK 看 `lib/` 条目的 uncompressed/compressed 尺寸**，不能看仓库里那份文件的大小。

## E-046 · 2026-10-08 · `feat/v2.0-r1-rust-core-16kb` 的活已经全在 main 里了 —— 别再合并它（合并会回拉 7 个早就修掉的坑）

**结论：该分支 superseded，本地与远端一并删除。内容在 main 里逐字节可查，删了不丢东西。**

**对拍证据（只读核对，未合并）**：分支 head `8484d46`、merge-base `725ebef`，4 提交 / 30 文件 +675/−684。它做的三件事：

1. `008d554` Rust 收编：两个 crate 两个 `.so`（1,129,064 B）→ 单 crate `rust/core` + `libzhengdao_core.so` 807,712 B（4 个 LOAD 段 `p_align` 全 `0x4000`）。
2. `dd75713` 终端页唯一化（`CLEAR_TOP|SINGLE_TOP`）。
3. `5697943` + `8484d46` CI 加 `:app:assembleRelease`。

三件事**全部**已被 main 覆盖，而且**每条都能当场复核**（下面都是本轮实跑过的命令）：

- **证据 1（内容逐字节）**：`git diff --stat 008d554 af37010 -- rust app/src/main/jniLibs app/proguard-rules.pro app/src/main/java/com/example/zhengdao/rust app/src/main/cpp` ⇒ **输出为空**，即分支的 Rust 收编结果与 main 的 `af37010` 在这些路径上一字不差（两个提交的提交信息不同、patch-id 也不同，只有对拍才知道是同一件事）。
- **证据 2（patch-id 相同）**：分支 `dd75713`（终端页唯一化）与 main `b221603` 的 `git patch-id --stable` **同为 `f1c39c8336149320e7c45bce2e034987d59de39e`**。
- **证据 3（被更严格的门禁取代）**：CI 出正式包（分支 `5697943` + `8484d46`）在 main 侧由 `fc74cd5` + E-014 取代 —— 分支版本自称"不需要任何密钥"，而 E-014 要求 `DEBUG_KEYSTORE_B64` 与 `EXPECTED_SHA256=44E2FE86…`。

**合并面**：`git merge-tree --write-tree main <branch>` → **9 路径 / 20 个冲突块** + 3 处 rename/delete + 3 处 add/add。注意 `merge-tree` 写出的树**本身就带冲突标记**（`<<<<<<< main` / `>>>>>>> feat/v2.0-r1-rust-core-16kb`），所以 `git diff --shortstat main <tree>` 里那一百多行 insertions 只是标记文本 —— 它的含义是「把每个冲突块都取 main 侧之后，结果与 main 一致」。**结论：今天这个分支只能以"全部丢弃"的方式合并；"取分支侧"会踩下面 7 个早就修掉的坑（本轮逐条实测复核，括号里是复核方式）**：

1. `.github/workflows/build.yml:106` 仍是 `run: bash rootfs/build-proot.sh "$GITHUB_WORKSPACE/rootfs-out"`，而 main 已按 E-016 删掉这个脚本（`git grep build-proot.sh <branch>` 命中 build.yml 与脚本本身）。
2. `rust/core/src/jni_bridge.rs` 无 `PROGRESS_DISABLED`（0 次命中）、无 `exception_clear`（0 次）⇒ 退回 E-022 事故版。
3. `app/proguard-rules.pro` 只有类级 `-keep class com.example.zhengdao.rust.CoreNative {`，**缺** `public static void onProgress(java.lang.String, java.lang.String);`（main 有）⇒ R8 改名后 Rust 反向回调在 release 上直接 `NoSuchMethodError … onProgress(...)V` / SIGABRT。
4. `rust/core/src/tests.rs` 含 **7 个字面 NUL 字节**（实测正则计数），是 E-028 之前的版本（`link_name()` 未进 `#[cfg(unix)]`）。
5. `app/src/main/jniLibs/arm64-v8a/libzhengdao_core.so` = 807,712 B，而 main 现在是 **811,592 B**（E-045 为 Rust 完整性锚重建并 strip 后的入库版）。
6. `docs/ERRATA.md` 停在 E-013 时代（分支约 500 行，main 已 2900+ 行）⇒ 整段搬回来会把 E-012/E-013 的旧结论重新带进文档（E-014 已推翻它们）。
7. `app/src/main/java/com/example/zhengdao/TerminalActivity.kt` 里 `routed` 0 次、`pendingAgentId` 0 次 ⇒ 缺 main 的 `onNewIntent` 闸与 Agent 入口。

**教训**：

1. **里程碑分支合过一次、内容进了 main 之后，要主动标 superseded 并删掉**：留着它最大的风险不是占地方，而是有人（或某个自动化）真的去 merge 它，用"取分支侧解冲突"的方式把 7 个已修问题一次性搬回来。
2. **判"是否已被覆盖"要用 patch-id / blob sha，不是看提交信息**：这个分支的提交信息与 main 的对应提交不同（各自独立写的），但 `git patch-id` 一模一样 ⇒ 只有对拍才能确认。
3. **`merge-tree` 的冲突数不代表合并结果的大小**：这里 20 个冲突块，全部取 main 侧后结果是 no-op —— 先看 `git diff --shortstat main <merged-tree>` 再决定要不要花力气解冲突。

## E-047 · 2026-10-08 · 真机上"按钮点不动"先别改代码：第三方悬浮窗与 IME 的**隐形触摸带**会把点击吃掉

**症状**：真机验收时「检查环境更新」按钮在 `y=2223` 与 `y=1254` 两处、`input tap` 与"滑动到位再点"都不响应；`uiautomator dump` 里按钮明明在（bounds 正常、`clickable=true`），点下去毫无反应，RunLog 也没有任何新行。同一台机器上别的按钮点得动。

**根因（实测）**：番茄小说（`com.phoenix.read`）的第三方悬浮窗在该机型上是一块**活的、会移动的** `touchableRegion`。`adb shell am force-stop com.phoenix.read` 之后，**同一个坐标、同一次 `input tap` 立刻生效**（当场弹出「可以重装环境」AlertDialog）。旁证：本机 IME 窗口 `mInputShown=false` 而 `mIsInputViewShown=true`、`touchableRegion=(0,1850 - 1312,2707)` —— 一条**看不见、却在吃点击**的底带；`x=656` 的滑动起点落在这条带里时整页不动。

**教训 / 以后这么干**：

1. **真机"点不动"先怀疑不是 App**：`adb shell am force-stop <悬浮窗包名>` 后拿**同一坐标**重试；立刻生效 ⇒ 与 App 代码无关，别去改 UI 代码（用户投诉的第 4 条「第三方悬浮窗吃掉点按」就是这一类）。
2. **坐标必须现算**：每次 `uiautomator dump` 取**当前** bounds 的中心再点，不要用上一轮记住的固定坐标 —— 键盘弹出、浮窗移形、列表重排都会让固定坐标失效（K2 验收时曾因固定坐标把 4 条消息连成一个输入框里没发出去）。
3. **滑动起点避开悬浮窗与 IME 的触摸带**（这台机器上 `x=656` 常落在带里，改 `x=400` 就稳）。
4. **`uiautomator dump` 看不到第三方浮窗**（它在另一个 window）⇒ "dump 里有这个按钮"**不代表**"点得到"。排查时先 `dumpsys window windows | Select-String -Pattern 'touchableRegion|mAttrs'` 看有没有第三方 window 压在目标上。

## E-048 · 2026-10-08 · 把 E-045 教训 3 的缺口补成门禁：入库 `.so` 的 16KB 对齐与 JNI 入口符号进 CI（`tools/check-native-so.py`）

**缺口**：E-045 里记了两件事 —— ① `app/src/main/jniLibs/arm64-v8a/libzhengdao_core.so` 是**人工** `cargo build --release --target aarch64-linux-android` + `llvm-strip --strip-unneeded` 后拷进来的，`ci.yml` / `build.yml` 里**没有任何一步**构建或校验它；② 16KB 页对齐漏掉时不是崩溃而是**静默降级**（16KB 页设备 `loadLibrary` 失败 → `CoreNative.isRustAvailable()=false` → 整条解压路径悄悄退回 Java，日志里只有一行"走平台回退"）。两件凑一起＝「Rust 核心到底有没有在跑」在 CI 上零证据，而下一次谁改 Rust 忘了重建/忘了对齐，谁都不会红。

**做法**：新增 `tools/check-native-so.py`（纯标准库，自己解析 ELF 头与段表），`ci.yml` 与 `build.yml` 在"编译 APK"之前各跑一步「入库 .so 门禁（16KB 页对齐 + JNI 入口符号）」：

1. `app/src/main/jniLibs/<abi>/*.so` 的**每个** `PT_LOAD` 段 `p_align >= 0x4000`（16KB 页设备可加载）；
2. `libzhengdao_core.so` 必须导出 `CoreNative.kt` 里**每个** `external fun` 对应的 JNI 符号 —— 期望符号**现读** `CoreNative.kt` 推导（`Java_com_example_zhengdao_rust_CoreNative_<方法名>`），将来加 native 方法**自动**纳入，不用维护第二份清单；
3. `.symtab` 是否存在只打一行信息（影响入库体积、**不影响 APK** —— 见 E-045 教训 6 的更正）。

失败按本仓约定抛 `::error title=…`（Actions 日志匿名读不到、注解读得到，见 E-028/E-039）。

**实测**（`python tools/check-native-so.py`，v1.3.0 `0d2320a`）：

- `libzhengdao_core.so` 811,592 B：4 个 `PT_LOAD` 全 `p_align=0x4000`；3 个 JNI 入口（`nativeSha256Hex` / `nativeSha256File` / `nativeExtract`）全在；已 strip（无 `.symtab`）
- `libzstd-jni-1.5.6-4.so` 600,064 B：2 个 `PT_LOAD` 全 `p_align=0x10000`（第三方，≥16KB 同样合格）
- 负例：把期望符号前缀临时改成 `…_NOPE` → 退出码 1，三行缺失符号各一条，符合预期

**教训**：

1. **"人工工序 + 静默失败"必然要做成门禁**：凡是"没人自动跑、跑漏了也不红"的工序，都会在某次赶工里漏掉，而漏掉的后果偏偏长得像"一切正常"。
2. **门禁的期望值要现读源码**（这里读 `CoreNative.kt` 的 `external fun`），别在脚本里再抄一份清单 —— 抄的那份一定会过期。
3. **Windows 的 GBK 控制台会把门禁脚本自己搞崩**：消息里出现 `⇒` 这类字符时 `print` 抛 `UnicodeEncodeError`，于是"脚本报错而死"和"检查失败而死"混在一起。修法是 `sys.stdout.reconfigure(errors="replace")` + 消息里用 ASCII 箭头。

## E-049 · 2026-10-08 · "补指纹"白下了 192 MB：本地缓存里躺着的就是索引那个整包，检查更新却照旧走下载

**症状**（真机 Honor `AD3J023824001723`，v1.3.0 `0c43978`）：标记里缺 `env=`（上一轮「修复环境」抹掉的，见 E-044）时，设置页「检查环境更新」给出「本地已安装 13.7，但缺少环境指纹记录（env）：本次将用全量包（约 192 MB）重装一次以补上指纹」，点「下载并安装」后**走的是联网下载**：`22:31:22 检查环境更新：开始下载新版本环境包` → `环境包下载中 N%（M/192 MB，可离开本页）` → `22:42:46 100%（192/192 MB）`，约 11 分钟、20 MB/分钟。而 `/sdcard/Download/证道/rootfs/debian-13.7-base-arm64.tar.zst`（201,632,517 B）**当时就躺在缓存里，内容正是索引那一版**（sha256 与 `rootfs-index.json` 的 `sha256`/`size` 完全一致）。

**根因**：检查线程在 `localEnv == null` 分支里**无条件**把 `pendingUrl = idx.url; pendingSha = idx.sha256` 交给弹窗，而弹窗只认 `pendingUpdateUrl`（`ui/SettingsScreen.kt:1290` 的 `updateMsg?.takeIf { pendingUpdateUrl != null }`）⇒「缺指纹」这种**根本不需要新内容**的场景被当成了"要下载新版本"。真正会翻本地缓存的那条路（`修复环境`，`SettingsScreen.kt:1183-1260`）是另一处实现，**检查 → 安装**这条链上从来没有"先看看本地有没有"这一步。

**修法**（`feat/local-cache-no-download`）：

1. `rootfs/RootfsCache.kt` 新增 `localCandidateFor(ctx, url, size)` 与纯函数内核 `pickLocalCandidate(preferred, cached, size)`：先认"按 URL 猜到的那个文件"（名字对上＝最可能），再翻 `listArchives` 按**字节数**找；`size <= 0` 或都不符 ⇒ `null`（宁可下载，也不拿大小对不上的包去重装）。
2. `ui/SettingsScreen.kt` 的检查线程在 `localEnv == null` 与 `else`（有新版本）两条分支里**都**先算 `cached`：文案改成「本地已有该版本的安装包（…），将直接用它安装，无需下载」，并把 `pendingLocalArchive` 随 url/sha/index/patch 一起交给弹窗。**本地整包排在增量补丁之前**（0 下载优于几十 MB）。
3. 弹窗确认线程**最前面**加"本地优先"块：先 `RootfsDownloader.sha256Of(本地包)` 与索引 sha 比对 —— 一致才走本地重解压（`InstallFlow.start(..., fromLocal = true)`），不一致就打一行 RunLog（`本地缓存包 sha（…）与索引不一致（…），改为下载`）退回原来的下载路径。确认按钮文案随之变「用本地包安装」。
4. **信任锚不变**：大小只用来**挑候选**，能不能写 `env=` 仍然由 `RootfsInstaller.envForMarker(index.env, index.sha256, actualSha)` 决定（`actualSha` 是安装线程当场算出来的那道 sha）—— 大小相同 ≠ 内容相同。

**实测**：

- 单测：`33 suites / 277 tests / 0 failures / 0 errors / 0 skipped`（新增 `app/src/test/java/com/example/zhengdao/rootfs/RootfsLocalCandidateTest.kt` 6 例：名字优先、退回列表、半截包被拒、谁都不符返回 null、非正数大小返回 null、目录不算候选）。
- 真机（debug 包覆盖安装以便 `run-as` 读标记）：`adb shell run-as com.example.zhengdao sed -i '/^env=/d' files/rootfs/.zhengdao-rootfs-ok` 构造"缺指纹"后点「检查环境更新」⇒ 弹窗「可以重装环境 / 本地已安装 13.7，但缺少环境指纹记录（env）：本地已有该版本的安装包（约 192 MB），将直接用它重装补指纹，无需下载。」+ 按钮「**用本地包安装**」。
- 确认后的 RunLog：`23:03:44 检查环境更新：本地已有同版本安装包（192 MB），直接重解压，不下载` → `23:03:44 环境更新：用本地安装包（sha=d80639e7dc5c）重解压，本次未联网下载` → `23:03:49 环境更新完成（用本地安装包，本次未联网下载），重进终端生效` —— **5 秒**（对比联网那次的 11 分钟）。
- 收尾：标记里 `env=368b59b30b10712c` 回填、`archive-sha256=d80639e7…` 保持；再点「检查环境更新」⇒ Toast「已是最新版本（13.7，环境 368b59b30b10712c）」，不再弹窗。

**教训**：

1. **"有新版本"和"要下载"是两件事**：检查线程一旦把 URL 塞进 pending 状态，后面所有分支都会下载。缺指纹、重装、换机这类场景要先问一句"这份内容我是不是已经有了"。
2. **大小只能用来挑候选，不能当证据**：先按大小把 192 MB 的候选挑出来是为了快，**能不能据此写指纹**仍然要逐字节验 sha（E-044 立的规矩）。
3. **本地整包要排在增量补丁前面**：补丁只是"比全量小"，本地包才是"不用下载"—— 顺序写反就白白花掉几十 MB 流量。
4. **验收要能"制造"目标状态**：debug 包（同签名、无 applicationIdSuffix）覆盖安装 + `run-as` 删掉标记里的一行 `env=`，就能复现"缺指纹"，比清 App 数据/换机便宜得多。真机点按前记得 `adb shell am force-stop com.phoenix.read`（E-047）。

## E-050 · 2026-10-08 · 补丁解压一直留在 Java 侧：Rust 流水线只认 zstd/gzip，也不能"跳过成员"，于是增量与全量长期分叉

**缺口**：`RootfsInstaller.extractArchiveJava` 支持 zstd / gzip / **纯 tar**（未知魔数按 tar 处理，见 `formatOf`），而 Rust 的 `extract_pipeline`（`rust/core/src/extract.rs`）只认 zstd 与 gzip，第三种魔数直接 `BadArchive("无法识别的压缩格式: …")`；"跳过指定成员"更是只有 Java 侧有（`openTar(skipNames)`）。因此 `rootfs/RootfsDelta.kt` 的 `applyTo` 只能调 `extractArchiveJava(..., skipNames = setOf(PATCH_INFO_NAME))` —— **全量走 Rust、补丁走 Java** 的分叉：同一台设备上，全量安装有"恰好被解压的那些字节"的 sha 对账（E-045）与 native 进度回调，补丁这条什么都没有。

**为什么现在必须收口**：补丁包（增量下发协议 §4）是 **未压缩 tar**（`app/src/androidTest/java/com/example/zhengdao/rootfs/RootfsDeltaInstrumentedTest.kt` 用 commons-compress 直写），且第一个成员固定是元数据 `.zhengdao-patch-info`，不落盘靠的就是 `skipNames`；Rust 不接这两样，增量路径就永远停在 Java 侧。

**修法**（`feat/rust-patch-extract`）：

1. `rust/core/src/extract.rs`：魔数匹配的第三支由"报错"改成 `_ => Box::new(file)`（纯 tar，与 Kotlin `openTar` 对齐）；新增 `pub fn extract_pipeline_skip(archive, target_dir, expected_sha256, skip_names: &[String], on_progress)`，入口循环里 `if skip_names.iter().any(|s| s == &name) { skipped += 1; continue; }`，`ExtractReport` 增加 `pub skipped: u64`；原 `extract_pipeline` 变成传 `&[]` 的薄包装（既有调用点零改动）。
2. `rust/core/src/jni_bridge.rs`：**新增**符号 `Java_com_example_zhengdao_rust_CoreNative_nativeExtractSkip(archivePath, targetDir, expectedSha256, skipNamesJoined)`，跳过清单以 `\n` 连接（不引 `JObjectArray`，jni crate 的数组 API 版本间签名不稳）；`nativeExtract` 与新入口共用 `extract_impl`，JSON 增加 `"skipped":N`。
3. `app/src/main/java/com/example/zhengdao/rust/CoreNative.kt`：`extract(..., skipNames: List<String> = emptyList())`，空清单仍走 `nativeExtract`（不多绕一次 JNI）；JSON 解析抽成 `parseExtractReport`。
4. `app/src/main/java/com/example/zhengdao/rootfs/RootfsInstaller.kt`：新增 `extractArchive(archive, destDir, skipNames, expectedSha256, onEntry): Boolean` —— Rust 优先、**sha 不匹配 ⇒ `InstallFailed` 且不回退**、其它失败回退 `extractArchiveJava`；`install()` 也改走它（日志仍按走没走 Rust 分别给"RootFS 安装完成（Rust 路径）"）；`RootfsDelta.applyTo/apply` 换用它并新增可选 `expectedSha256`；`ui/SettingsScreen.kt` 的增量分支把 `patchRef.sha256` 一起传下去（下载校验一次 + 解压时对同一期望值再对一次账）。

**实测**：

- host：`cargo test -p zhengdao_core --release` = **12 passed / 0 failed**（新增 `纯tar包_无压缩壳_直接解压`、`跳过成员_不落盘且计入skipped`）。
- `python tools/check-native-so.py`：`libzhengdao_core.so` 816,040 B、4 个 `PT_LOAD` 全 `p_align=0x4000`、`CoreNative.kt` 的 **4** 个 `external fun` 全部命中（含新符号 `nativeExtractSkip`）。
- JVM：`33 suites / 277 tests / 0 failures / 0 errors / 0 skipped`。
- 真机（Honor PGT-AN10 / Android 16）：`:app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.example.zhengdao.rootfs.RootfsDeltaInstrumentedTest` ⇒ `tests="3" failures="0" errors="0" skipped="0"`；新增用例 `补丁sha不匹配时硬失败不退回Java`（故意给 `expectedSha256 = "0"×64`）断言 `InstallFailed` 且原树逐项未动。这条用例同时是"Rust 真的接管了补丁解压"的证据：走 Java 时没人校验 sha，它必然失败；它**没被 `assumeTrue` 跳过**，说明这台设备上 Rust 核心可用。

**教训**：

1. **"参数存在"不等于"路径启用"**（E-045 的教训第二次应验）：`expected_sha256`、`skipNames` 这类能力，只有**入口真的会调**才算有。
2. **分叉比缺失更危险**：全量与增量各走一套实现时，修一条永远只修一半 —— 收口到同一入口（`extractArchive`）比在两条路上各打补丁便宜。
3. **门禁只校验"符号存在"，校验不了"签名对不对"**：`tools/check-native-so.py` 是拿 `CoreNative.kt` 里的 `external fun` 名字比对 .so 导出符号，所以**改已有符号的签名**它能放行；本条因此选择**新增**符号（旧 .so + 新 Kotlin 时只有补丁路径 JNI 查找失败 → 回退 Java），把不兼容限制在一条路径里。
4. **Rust 侧也要能吃"最土"的输入**：生产上补丁就是未压缩 tar，Kotlin 早就支持，Rust 却把它当非法格式 —— 能力对齐要按**调用方实际会传的形状**做，不是按"生产压缩格式"做。

---

## E-051 · 2026-10-08 · 清单验签的信任根只活在 App 进程里：把 Ed25519 验签搬进 Rust 核心，并让两边**对拍**（不一致＝拒绝）

**缺口**：全仓唯一的签名验证入口是 `app/src/main/java/com/example/zhengdao/ui/AgentManifest.kt` 的 `verify()`，Ed25519 计算完全在 Kotlin 里（私有 `object Ed25519`，为绕开 Android `KeyFactory("Ed25519")` 默认路由到 AndroidKeystore 而从零实现 RFC 8032 §5.1），只在 App 进程可用：终端/Agent、脚本、将来任何"非 App 的校验方"都拿不到同一份信任根；而"向 Rust 方向进化"的判据之一就是**信任根这类纯计算不该被 Android 运行时钉死**。

**修法**（`feat/rust-ed25519`）：

1. `rust/core/src/ed25519.rs`（新）：`pub fn verify(pub_key: &[u8], sig: &[u8], msg: &[u8]) -> bool` —— 长度非法或验不过一律 `false`、绝不 panic 跨 FFI；曲线常量 `P/D/D2/L/SQRT_M1/基点B` 用 `std::sync::OnceLock` 惰性初始化（`D` 用费马小定理求逆，省掉 num-integer）；SHA-512 用 `sha2::Sha512`；`Point{x,y,z,t}` 扩展坐标（`t` 由 `X·Y` 直接算出，省掉 Kotlin 那套 nullable `T`）；`decode_point` 按 §5.1.3（含奇偶修正）、`pt_add` 按 §5.1.4、`verify` 判 `S·B == R + h·A` 且拒 `S ≥ L`。
2. `rust/core/src/jni_bridge.rs`：**新增** `Java_com_example_zhengdao_rust_CoreNative_nativeVerifyEd25519(pub, sig, msg) -> jboolean`（新增而非改旧符号签名，理由＝E-050 教训 3：门禁只校验符号存在、校验不了签名）。
3. `app/src/main/java/com/example/zhengdao/rust/CoreNative.kt`：`verifyEd25519(pubKey, sig, msg): Boolean?`（核心不可用/例外 ⇒ `null`，调用方回退）+ `private external fun nativeVerifyEd25519`。
4. `AgentManifest.verify()` 改成**对拍**形态：平台先算 → Rust 再算 → Rust 为 `null` 记「验签走平台回退（Rust 核心不可用）：$platform」并返回平台结论；两边不一致记 `Log.w` 并**拒绝**；一致则记「验签走 Rust 核心（与平台对拍一致）：$rust」并返回 Rust 结论。`rust/core/Cargo.toml` 加 `num-bigint`/`num-traits`（**通用大整数库、非密码学库**，理由是公式能与 `java.math.BigInteger` 逐行对照）。

**实测**：

- host：`cargo test -p zhengdao_core --release` = **18 passed / 0 failed**，其中 ed25519 6 例：RFC 8032 §7.1 TEST1/2/3 + `真实清单_验签通过与三类篡改拒绝`（读仓库 `rootfs/agents.json` 1379 B 与 `agents.json.sig`，用固化公钥验签通过；篡改正文/错公钥/签名翻位三类拒绝）+ `全零退化输入_朴素判定式通过_已知边界` + `长度非法_一律false不panic`。
- `python tools/check-native-so.py`：`libzhengdao_core.so` **948,392 B**（未 strip 1,268,448 B；比 E-050 那版 816,040 B 大 132 KB，全是 num-bigint + ed25519 的代价）、4 个 `PT_LOAD` 全 `p_align=0x4000`、`CoreNative.kt` 的 **5** 个 `external fun` 全部命中。
- JVM：`33 suites / 277 tests / 0 failures / 0 errors / 0 skipped`。
- 真机（Honor PGT-AN10 / Android 16）：`Ed25519VerifyInstrumentedTest` ⇒ `tests="4" failures="0" errors="0" skipped="0"`（没被 `assumeTrue` 跳过 ⇒ arm64 上这份 .so 真的在算）；`AgentManifestVerifyInstrumentedTest`（真实 `agents.json` + `.sig` 走 App 的验签入口）⇒ `tests="1" failures="0"`，logcat：`I AgentManifest: 验签走 Rust 核心（与平台对拍一致）：true`。

**教训**：

1. **对拍要带"失败侧取严"**：两边不一致时按拒绝处理 —— 不一致本身就是待修的 bug，放行等于把风险留给用户。
2. **"朴素判定式"的边界要写成用例**：RFC 8032 §5.1.7 允许不做小阶点拒绝，全零公钥/签名（阶 4）在这种实现下**会判通过**（我第一版单测就把它写成"必 false"而挂掉）。本项目公钥固化在 APK、攻击者只能控 R/S ⇒ 不可达；把它写成注释 + 显式用例（`全零退化输入…已知边界`），行为就被锁死，换实现不会静默漂移。
3. **验收要能证明"是新路径在跑"**：`verify(...) == true` 区分不了 Rust 与平台实现；`验签走 Rust 核心（与平台对拍一致）：true` 这一行才是凭据（"路径日志 + 断言"两件套，E-045 起固定用法）。
4. **依赖选型要写理由**：`num-bigint`/`num-traits` 引入的是通用大整数运算、不是密码学库；理由（可逐行对照 Kotlin 实现）与体积代价（+132 KB）都该落进 `Cargo.toml` 注释与 ERRATA，否则下一个人只会看到"多了个依赖"。

