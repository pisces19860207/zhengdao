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
