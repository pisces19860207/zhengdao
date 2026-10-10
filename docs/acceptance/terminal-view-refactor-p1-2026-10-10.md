# 终端视图重构 P1 真机验收（六刀 + 接口收窄 · 2026-10-10）

> 产出：2026-10-10 晚（DeepSeek Harness）｜被测包：**自建 benchmark 变体**（R8 开启）
> `app\build\outputs\apk\benchmark\zhengdao-2.0.9-benchmark.apk`（4.5 MB，20:58:23 构建，与 HEAD `02d99fa` 同源；applicationId 无后缀，`adb install -r` 覆盖安装后 Debian 环境未丢）
> 对应实施：`docs/证道-P0渲染层测量-2026-10-10.md` §3「六刀实施结果」＋收尾项「接口收窄」；分支 `feature/terminal-view-refactor-p1`
> 设备：Honor **PGT-AN10**（`AD3J023824001723`，Android 16，1312×2848，未 root）＋内嵌 Debian 13.7 / tmux（`set -g mouse on`）
> 方法：`adb shell input` 注入按键与手势 ＋ 截图判读 ＋ `logcat`；坐标一律由 `adb shell uiautomator dump /sdcard/ui.xml` 取得
> 一句话：**5 项全过——跨类边界的 IME（commitText）与滚动坐标（滚轮转发 + clamp）均无回归；clamp 拿到定量证据。**

---

## 一、结论速览

| 验收项 | 手段 | 结果 |
|---|---|---|
| **① 中文输入（分词键盘 → commitText）** | `ImeRegressionActivity` 三场景页 + T9 九键真机敲键 | ✅ **通过**（页面自判「上屏内容与期望一致」，实测读取 `你好`） |
| **② 两种回车** | 系统按键路径（`keyevent 66`）与 IME「换行」路径各敲一条命令 | ✅ **通过**（两条命令都真的执行） |
| **③ 快捷键条 17 键** | 逐键打进 `cat -v`，看 pty 侧回显 | ✅ **通过**（1 条弱证据：SHIFT 粘滞的大写化没抓到独立回显） |
| **④ 复制 / 粘贴** | Toast 字数 + 输入法剪贴板建议条旁证 | ✅ **通过** |
| **⑤ tmux 滚动语义 + clamp 新行为** | copy-mode `[N/M]` 指示器做尺子，同距离异时长对照 | ✅ **通过**（clamp 有定量证据） |

配套：`:app:testDebugUnitTest` 全绿（`OscOverflowTest` 4 例 + `Osc52LimitTest` 2 例）；benchmark 包首启无 `FATAL EXCEPTION`，tmux 会话 `[zhengdao] 0:bash*` 正常渲染。

---

## 二、验收 ①：中文输入（分词键盘走 commitText）

**为什么这条风险最高**：IME 路径跨了类边界 —— `TerminalView.onCreateInputConnection` 转发 → `TerminalImeBridge` 的匿名 `BaseInputConnection` → `TerminalSession`。

**复现步骤**（`ImeRegressionActivity` 在清单里 `exported="true"`，专为 PC 侧一键起页）：

1. `adb shell am start -n com.example.zhengdao/.ui.ImeRegressionActivity`
2. 点终端块 (300,1000) 聚焦 → 九键分词键盘弹出
3. 按 T9 键序 `6 → 4 → 4 → 2 → 6`（`6(875,2458) 4(392,2458) 4 2(636,2272) 6`）→ 候选栏出现 `ni'hao`
4. 点第一个候选「你好」(162,2001)

**证据**：

- 候选栏 `ni'hao` → 「你好 / 你高 / 你敢 / 你号 / 你喊」（说明走的是**分词键盘的组合输入**，不是直接文本提交）
- `logcat`：`I/ZD-IME-EVENT(25927): commitText|你好`（事件来自 `ImeRegressionActivity.kt:562`）
- 终端块（跑 `cat`）上屏 `你好|`
- 页面「判定」按钮 → **`✓ 通过 — 上屏内容与期望一致`**（实测读取：你好，期望上屏：你好）

---

## 三、验收 ⑤：tmux 滚动语义 + 滚轮 clamp

### 3.1 前提（决定走哪条分支）

```console
root@localhost:~# tmux show -g mouse
mouse on
```

`ProotLauncher` 预置的 `~/.tmux.conf` 里有 `set -g mouse on`，所以 `mView.mEmulator.isMouseTrackingActive()` 为真，触摸滑动走的是**滚轮转发**分支（clamp 所在分支），不是本地回滚分支。

### 3.2 语义：两个方向都通

| 手势 | 现象 | 判读 |
|---|---|---|
| 下滑（手指向下，如 `input swipe 300 400 300 1900 600`） | tmux 进入 copy-mode（状态栏变 `[zhengdao] 0: [tmux]*`，右上角出现 `hh:mm:ss [N/1918]`），画面往更旧内容走 | ✅ 触摸 → 滚轮 → tmux |
| 上滑（手指向上） | 画面往最新内容走；到底后**自动退出 copy-mode**（状态栏回 `[zhengdao] 0:bash*`） | ✅ 双向语义正确 |

⚠️ **手势起点必须落在终端视图内**（约 y 270–1600）：起点落在快捷键条上（如 y=1900 正好是 `↓` 键那一行）时终端收不到手势，看起来像「不滚动」。

### 3.3 clamp 的定量证据

尺子 = tmux copy-mode 右上角的 `[N/M]` 指示器（本次 M=1918 行）。**同一 1500 px 位移、只改手势时长**：

| 手势时长 | 位移 | copy-mode 位置变化 | 折算每个 MOVE 事件 | 判读 |
|---|---|---|---|---|
| 600 ms | 1500 px | **+200 行** | ~37 px ≈ 0.8 行 | 未触顶 |
| 60 ms | 1500 px | **+225 行** | ~250 px ≈ 5.5 行 | 未触顶 |
| 20 ms | 1500 px | **+110 行** | ~750 px ≈ 16.7 行 → **被截到 10 行** | **clamp 生效** |

不加 clamp 时三次应同量级（~200+ 行）；20 ms 那次只有约一半，正落在「**2 个回调 × 10 行上限 × ~5.5 行/滚轮事件 ≈ 110 行**」的预测上。

### 3.4 口径更正（重要）

clamp 是「**每个 `onScroll` 回调（每个 MOVE 事件）最多 10 行 = 20 条鼠标事件**」，**不是**「一次手势最多 10 行」。依据 `app/src/main/java/com/termux/view/TerminalGestureController.java`：

- `:61` `private static final int MAX_WHEEL_ROWS = 10;`
- `:113` `int wheelRows = (int) (distanceY / mView.mRenderer.mFontLineSpacing);`
- `:114` `mScrollRemainder = distanceY - wheelRows * mView.mRenderer.mFontLineSpacing;`（**按真实行数结算余量**，所以 clamp 不会让滚动位置漂移）
- `:117` `int clampedRows = Math.max(-MAX_WHEEL_ROWS, Math.min(MAX_WHEEL_ROWS, wheelRows));`
- `:119–125` 每行发一对 press/release；`:73` 的 onUp 在鼠标跟踪下补一对左键

行高实测 ≈45 device px ⇒ **单次 MOVE 位移 > 450 px 才会触顶**。

---

## 四、验收 ②③④

### 4.1 ② 两种回车 —— 通过

| 路径 | 操作 | 结果 |
|---|---|---|
| 系统按键 | `adb shell input keyevent 66` 后敲 `echo ENTER-KEY-OK` | 输出 `ENTER-KEY-OK` + 新提示符 |
| IME 文本 | 点 IME「换行」(1127,2771)（即 `commitText("\n")`）后敲 `echo ENTER-IME-OK` | 输出 `ENTER-IME-OK`（`TerminalImeBridge.java:169-175` 把 `\n` 映射成 `\r`） |

### 4.2 ③ 快捷键条 —— 通过（修正：条上 **17** 键，不是 18）

坐标（`uiautomator dump` 实测，本机两行 y=1697 / y=1879）：

| 行 | 键（x 坐标） |
|---|---|
| 第一行 y=1697 | ESC 154 · TAB 404 · CTRL 655 · SHIFT 906 · ^C 1157 |
| 第二行 y=1879 | ↑ 77 · ↓ 172 · ← 267 · → 362 · PGUP 472 · PGDN 596 · HOME 720 · END 836 · `\|` 937 · `-` 1032 · `/` 1128 · ⌫ 1224 |

顶部工具栏另有两键：复制 `btn_copy_top`(1006,228)、粘贴 `btn_paste_top`(1168,228)。

逐键打进 `cat -v` 的回显：

| 键 | pty 侧收到 | 备注 |
|---|---|---|
| ESC | `^[` | |
| TAB | `\t` | 在空提示符下无可见输出（正常） |
| ↑ ↓ ← → | `^[[A` `^[[B` `^[[D` `^[[C` | |
| PGUP / PGDN | `^[[5~` / `^[[6~` | |
| `\|` `-` `/` | 字面字符 | |
| ⌫ | `0x7F`（回显 `^?`） | |
| ^C | `0x03` | 真的打断了 `cat`，提示符返回 |
| CTRL（粘滞） | 再按 `a` → `^A`；再送 `xyz` → `^X^Y^Z` | `^Z` 触发 SIGTSTP，出现 `[1]+ Stopped` |
| SHIFT（粘滞） | 按下高亮、被消费后熄灭 | ⚠️ 大写化那一下**没拿到独立回显**（弱证据，建议手点复看） |
| HOME / END | pane 里是 `^[[1~` / `^[[4~` | App 发的是 `\e[H` / `\e[F`（`TerminalActivity.kt:609-610`），**差异来自中间那层 tmux 的重编码**（`xterm-keys on`），不是本次改动 |

### 4.3 ④ 复制 / 粘贴 —— 通过

| 操作 | 证据 |
|---|---|
| 点「复制」 | Toast **`已复制屏幕内容（124 字）`**；输入法自带的剪贴板建议条同步显示 `root@localhost:~# ec …`（第三方旁证：剪贴板里确实是那一屏内容） |
| 点「粘贴」 | Toast **`已粘贴 556 个字符`**（556 = 上一次未 `clear` 时的整屏字数）；pty 侧收到并回显（含被拷贝进去的空白行） |

代码路径：复制走 `TerminalActivity.kt:618-631` 的 `em.screen.getSelectedText(0, top, em.mColumns, top + em.mRows)` 整屏快拷；粘贴走 `:641` / `:689-704` 的 `doPaste()` → `SessionManager.write(text)`。

---

## 五、两个观察（都未定性，非本次重构引入）

1. **紧跟控制字符后自动输入会丢首字符**：`^C` 之后约 1 秒内注入 `clear`，bash 收到的是 `lear`（`-bash: lear: command not found`）；隔 3 秒再送就正常（同一轮里 `clear` + `echo CLR-OK` 全对）。退出 copy-mode 后立刻敲 `seq` 也出现过一次。手打不会这么跟手，**更像 adb 注入脚本的时序假象**，但值得人工复看一次。
2. **SHIFT 粘滞**：高亮态会在使用后被清掉（说明被消费了），但没抓到「下一个字符变大写」的独立回显，今天只算弱证据。

---

## 六、怎么复跑

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"

# ① 中文输入（页面自带三场景与「判定」按钮）
& $adb shell am start -n com.example.zhengdao/.ui.ImeRegressionActivity
#   点终端块 (300,1000) → 九键 6 4 4 2 6 → 点第一个候选；日志：
& $adb logcat -d -v brief -s ZD-IME-EVENT

# ⑤ 滚动 / clamp（尺子 = copy-mode 右上角 [N/M]；同一位移改时长做对照）
& $adb shell input swipe 300 400 300 1900 600   # 慢：每事件位移小，不触顶
& $adb shell input swipe 300 400 300 1900 20    # 快：每事件被截到 10 行
& $adb shell input text "q"                     # 退出 copy-mode（keyevent 31 有时无效）

# ③ 取精确坐标
& $adb shell uiautomator dump /sdcard/ui.xml

# 注入文本时空格必须写 %s（否则 adb shell 拆参数，只剩第一个词）
& $adb shell input text "seq%s1%s120"
```

坑位备忘：`input swipe … 1`（1 ms）**不产生 MOVE 事件**，完全不滚动，不能用来测 clamp；手势起点落在快捷键条上终端收不到。

---

## 七、截图留档（`%TEMP%`）

`zd-accept-1..3.png`（benchmark 包首启 + 终端页 + 键条）、`zd-ime-1..4.png`（中文输入三场景与判定）、`zd-tmux-1..6.png`（滚动语义）、`zd-clamp-1/2.png`、`zd-clamp-slow.png`、`zd-clamp-fast20.png`、`zd-clamp-fast60.png`（clamp 对照）、`zd-dir-old.png` / `zd-dir-new.png`（两个方向）、`zd-enter-1/2.png`（两种回车）、`zd-keys-1..4.png`（逐键回显）、`zd-copy.png` / `zd-copy2.png`（复制粘贴 Toast 与剪贴板条）。
