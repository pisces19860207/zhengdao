# 玄览第①②③刀 · 真机验收（2026-10-14）

> 验收对象 = `feat/xuanlan-skeleton` 的 ①②③刀合并进 `main` 之后的构建（合并提交 `3092aae`）。
> 验收人 = 蓝总管；设备 = HONOR PGT-AN10 / Android 16 / SDK 36 / 安全补丁 2026-08-01（serial `AD3J023824001723`）。
> 方法 = 同签名 release 包覆盖安装 + `uiautomator dump` 读界面 + `dumpsys` 读状态 + 终端截图读像素 + guest 内探针脚本。
> 本文只写**真机上真看到的**；未验的一律进第八节，不混进结论。

---

## 一、装包与签名

| 项 | 读数 |
| --- | --- |
| 包 | `app/build/outputs/apk/release/zhengdao-2.1.1-release.apk` |
| 尺寸 | **8,730,820 B** |
| 本机 sha256 | `76DB433E5528E0B65E3C36020B9ADB49E50B3E47B59AB95E5465DEE289548ED2` |
| 签名 | `apksigner verify --print-certs` exit 0，`CN=Zhengdao, O=Zhengdao, C=CN`，证书 SHA-256 `b93169f211c6b3d9fdae36b2c614c23bef28eae3582e4d8bca0657c27ac86ec2`（与 v2.0.13~v2.1.1 同一把发布钥匙） |
| 安装 | `adb -s AD3J023824001723 install -r` ⇒ `Success`（未卸载） |
| 版本 | `versionCode=36` / `versionName=2.1.1` |
| 数据 | `firstInstallTime=2026-10-11 18:00:12` **未变**、`lastUpdateTime=2026-10-14 10:15:33` ⇒ 覆盖升级、数据保留 |
| App 自检 | 日志逐字「启动自检：安装包签名 = 官方 ✓」 |

## 二、四 Tab 改名（真机成立）

底部四个 Tab 逐字为 **抱朴 / 逍遥 / 怡然 / 玄览**（坐标分别 ≈ `(153,2810)` / `(488,2810)` / `(823,2810)` / `(1158,2810)`）。

两处**刻意不改**的文案，与 `app/src/main/res/values/strings.xml` 里写死的口径一致：
- 首页提示条仍是「安装与启动均在**「终端」**内进行；会话由 tmux 保持…」—— `strings.xml:45` 记着「改的只是 Tab 名；各处『终端里…』类**功能指代**文案不在改名范围（终端=功能，逍遥=Tab 名）」。
- 终端页标题仍是「证道 - Debian 13.7」—— `strings.xml:7-8` 明写豁免。
- 终端 banner 里「[提示] OpenCode 在**「抱朴」**Tab 里」✓（这条是随 Tab 名改的）。

## 三、玄览页（空态）

- 地址栏 hint「输入网址或搜索」，右侧「前往」按钮；EditText bounds `[42,169][1039,365]`。
- 空态文案：「在上方输入网址，或从终端里点一条链接进来」（= `browser_empty_hint`）。
- 第 4 个 Tab 在 `MainActivity` 里接通（`onTabClicked(3)` / `3 -> BrowserScreen()`）✓。

## 四、阶段 0 里要写代码的三条

| 项 | 真机读数 | 代码对位 |
| --- | --- | --- |
| Safe Browsing 回调 | ✅ 地址栏输入 `https://testsafebrowsing.appspot.com/s/malware.html` ⇒ 页面被拦，界面逐字「**这个网址有安全风险，已被拦下**」 | `browser/WebViewEngine.kt:161-169` `onSafeBrowsingHit` → `delegate?.onSafeBrowsingHit(…)` **且** `callback?.showInterstitial(true)`（两路都触发） |
| 空 WebView 内存基线 | ✅ 日志 `[10-14 10:16:05] XuanLanMem before-webview pss=114405KB rss=156596KB` → `after-webview pss=145395KB rss=195900KB` ⇒ 建一个空 WebView ≈ **+31 MB PSS / +39 MB RSS** | `browser/BrowserMemoryProbe` |
| `shouldInterceptRequest` 拦非白名单 scheme | ⚠️ **未真机验**（未构造 `intent://` 样本） | `browser/WebViewEngine.kt`（非 http/https 返回空响应） |

![真机拦截现场：地址栏 `https://testsafebrowsing.appspot.com/s/malware.html`，页面被拦、界面逐字「这个网址有安全风险，已被拦下」](assets/xuanlan-slice123-2026-10-14/02-safe-browsing-blocked.png)

> 这一项读了两遍（10:16 一次、重装后二次复核一次），两次结果一致；二次复核时**空态文案与拦截提示同时在场** ⇒ 空态判据只是「有没有当前 URL」，被拦的加载从未产生 URL。

## 五、B 路径（终端里点裸 URL）—— 真机端到端 ✅

探针（guest 内 `printf`，脚本见第九节）：同一行同时含 `AAA-OSC8-AAA`（OSC 8 包裹）与 `BBB-BARE https://example.com/bare-B`（裸 URL）。

| 动作 | `topResumedActivity` | 结果 |
| --- | --- | --- |
| `input tap 800 380`（点在裸 URL 上） | `com.example.zhengdao/.MainActivity` | ✅ 玄览页起来，地址栏逐字 `https://example.com/bare-B`（`uiautomator` 读回）⇒ 终端 → 玄览整条链通 |

链路对位：`TerminalActivity.kt:866-881 onSingleTapUp` → `findBareUrlAt` → `TerminalLinkScanner.findLinkCovering` → `:890-918 onLinkOpen` = `TerminalLinkPolicy.classify` → `OPEN_IN_APP` 时 `BrowserHolder.pendingUrl = uri` + `requestBrowserTab()` + `finish()` → `MainActivity` `ON_RESUME` 消费 `consumeTabRequest()` 切 Tab＝玄览 ⇒ 真机行为与设计一致。

**已知缺口（见 §B 顺序 30）**：`TerminalLinkScanner` 的排除集不含 ASCII `,` —— 真机输出 `see https://example.com/trailing-test, and stop.` 时，点开的地址栏**带上尾逗号**。

![同行 A/B 判别现场（终端像素 ×3 最近邻）：第一行 `AAA-OSC8-AAA`（OSC 8 包裹）**没有下划线**，同一行后半 `BBB-BARE https://example.com/…`（裸 URL）可点；点 x=800 落在裸 URL 上 ⇒ 打开成功](assets/xuanlan-slice123-2026-10-14/01-terminal-ab-row.png)

## 六、A 路径（OSC 8 超链接）—— 真机 ❌，根因 = tmux 没声明 `hyperlinks`

| 动作 | `topResumedActivity` | 结果 |
| --- | --- | --- |
| `input tap 200 380`（点同一行的 OSC 8 文本） | `com.example.zhengdao/.TerminalActivity` | ❌ 什么都没发生；屏幕上也不画下划线 |
| 收键盘后再点（`input keyevent 4` → tap） | 键盘重新弹出 | ⇒ 证明 `onSingleTapUp` **确实进了**、只是 `findBareUrlAt` 没命中 ⇒ 不是「点击没送达」 |

排除 App 侧实现：`TerminalEmulator.java:2101-2118 handleOsc8Hyperlink`（解析 `ESC ] 8 ; params ; URI ST`）→ `TerminalBuffer.setLink/getLinkAt` → `TerminalRow` 链接区间 → `TerminalRenderer.java:134 insideLink`（下划线分支）→ `TerminalGestureController.java:103-108`（`getLinkAt` → `onLinkOpen`）逐段核过，无低级错误。

![运行时加了 `hyperlinks` 能力 + `refresh-client` 之后重放同一探针：`OSC8-FIXED-TEST` / `FIXED-LINK-CLICK-ME`（OSC 8 包裹）仍**不画下划线**，而下一行的裸 URL 照常可点 ⇒ 运行时改能力无效](assets/xuanlan-slice123-2026-10-14/03-osc8-after-runtime-feature-flag.png)

**根因判据（guest 实查）**：

```
tmux -V                              ⇒ tmux 3.5a
tmux display-message -p '#{client_termname}'      ⇒ xterm-256color
tmux display-message -p '#{client_termfeatures}'  ⇒ bpaste,ccolour,clipboard,cstyle,focus,margins,title
tmux show -g terminal-features       ⇒ xterm*:clipboard:ccolour:cstyle:focus:title / screen*:title / rxvt*:ignorefkeys
```

- App 侧设的 `TERM=xterm-256color`（`terminal/ProotLauncher.kt:418` / `:775`）；
- **`client_termfeatures` 里没有 `hyperlinks`** ⇒ tmux ≥3.4 只在客户端声明该能力时才把 OSC 8 透传给外层终端；
- 于是 **App 里那套 OSC 8 处理在 tmux 里等于白做** —— 与 `ProotLauncher.kt:666-672` 记的 OSC 52 经验同型（tmux 默认不转发 ⇒ 处理白做 ⇒ 必须显式开）。

**运行时改不动**（实测）：

```
tmux set -as terminal-features 'xterm*:hyperlinks'   # 表里加上了
tmux refresh-client                                  # 无效
tmux display-message -p '#{client_termfeatures}'     # 仍无 hyperlinks；OSC 8 依旧无下划线且点不动
```

⇒ 能力必须在**客户端 attach 之前**就位。

**建议修法（未实施）**：在 `terminal/ProotLauncher.kt:692-707 tmuxConfAppend()` 里按同一条纪律（`(?m)` 行首判重、幂等、不覆盖用户自有配置）追加 `set -as terminal-features 'xterm*:hyperlinks'`。主会话启动前会 `tmux kill-server`（同文件 `buildLaunchPlan`）⇒ 一进终端的新客户端即吃到（副会话 attach 旧 server 不吃，与既有注释一致）。

**复验要点**：改完必须走一次 attach 周期，再按第五/六节的探针重放，并**同时确认下划线出现**。

## 七、另外两条发现

1. **地址栏把 `http://` 一律升格成 `https://` ⇒ 环回明文服务打不开（待用户拍板）** —— `ui/browser/BrowserScreen.kt:278-291 normalizeInput()`：`http://` → `https://`（注释理由「外部明文被网络策略拦，升级避免加载失败」）、无 scheme 一律补 `https://`、无点无 scheme 走 Bing 搜索。真机：`http://127.0.0.1:14000/`（App 自己 OpenCode 的环回端口）被改成 `https://127.0.0.1:14000/` ⇒ 「页面加载出了点问题」。环回/内网是否允许保留明文，需用户拍板（见 §B 顺序 31）。
2. **`shouldGuide` 等旧账不属本次范围**，不在此页。

## 八、本轮未验（照实记）

- `shouldInterceptRequest` 拦 `intent://`（代码已核，未构造样本）。
- SSL 错误一律 cancel（未构造自签样本）。
- JS 弹窗 cancel、`evaluateJavascript` 的脱敏与 64 KB 上限（只有 JVM 单测）。
- 地址栏搜索路径（Bing）未点。
- OSC 8 修完后的**复验**（要一次 attach 周期）。
- 阶段 2 三条 TODO（`dataDirectorySuffix` 静态 / `thirdPartyCookies` / `addJavascriptInterface`）未开工。
- **切 Tab 返回玄览不恢复上一页** —— 二次复核时从终端切回玄览，地址栏已清空、显示空态（上次加载的 `https://example.com/bare-B` 不再显示）。WebView 实例在 `BrowserHolder` 里，但「切回来要不要恢复上一页」是设计取向，**本次不下结论**，留给用户拍板/交回处理。

## 九、探针（可复用）

host `%TEMP%` 里的脚本推到共享目录 `/sdcard/Download/证道/`（guest 内即 `/opt/zhengdao/`），在终端里敲 `sh /opt/zhengdao/<名>.sh`：

| 脚本 | 作用 |
| --- | --- |
| `zd-links.sh` | 三组链接样本（裸 URL / OSC 8 / 带尾标点） |
| `link-final.sh` | A/B 同行判别样本：`AAA-OSC8-AAA`（OSC 8）＋ `BBB-BARE https://example.com/bare-B`（裸 URL） |
| `tv.sh` | tmux 版本 / `client_termname` / `client_termfeatures` / `terminal-features` 落盘 |
| `osc8.sh` / `osc8b.sh` | 运行时加 `hyperlinks` 能力 + 刷新，验证「改了也无效」 |

**操作坑（都踩过）**：
- `adb shell input text 'a;b'` 的 `;` 会被**设备侧 sh** 拆行、PowerShell 还会吃掉内嵌双引号 ⇒ 别在 `input text` 里带 `;` / `>` / 引号；一律「推脚本 + 敲 `sh /opt/zhengdao/x.sh`」。
- 软键盘弹起时底部导航栏被顶掉，点到的是键盘 ⇒ 先 `input keyevent 4` 收键盘再切 Tab。
- `uiautomator` 读不到 `TerminalView` 的文本 ⇒ 终端画面只能截图；**坐标即设备像素**。
- 终端字符宽 ≈ 25–26 px、行高 ≈ 52 px，文本区左起 ≈ x=32；点必须落在**链接文字本身**上（点到标签/表头一律不命中）。
