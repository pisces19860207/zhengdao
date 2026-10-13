# 终端滑动「一顿一顿、不跟手」—— 真机测量、定案与修复（2026-10-12）

> 用户症状（逐字，2026-10-12）：**「你刚刚输入的那些字，我滑动了一下屏幕，一顿一顿的，还不跟手。」**
> 界面＝**证道 App 的终端页**（里面跑 Debian / tmux 的那个黑底终端；不是 DSH 聊天页、不是 WebView）。
> 设备：PGT-AN10（MagicOS / Android 16，adb 序列号 `AD3J023824001723`），App = v2.0.17 / versionCode 33。

---

## 1. 先否掉的两条假设

| 假设 | 出处 | 结论 |
|---|---|---|
| **PTY 读缓冲只有 4 KiB ⇒ 大输出洪水 ⇒ 渲染挨饿** | 另一个 DSH 会话的诊断（引 Termux [issue #4976](https://github.com/termux/termux-app/issues/4976) / [PR #4977](https://github.com/termux/termux-app/pull/4977)） | **引用为真、结论层不成立**。本仓库**从 v2.0.0 起就是 64 KiB**：`app/src/main/java/com/termux/terminal/TerminalSession.java:50` `new ByteQueue(64 * 1024)`、`:360` `new byte[64 * 1024]`（backport 提交 `4bb1f0d`，`git tag --contains 4bb1f0d` = v2.0.0…v2.0.17 全覆盖）。且这条因果链本项目**已实测并正式撤回**（E-088：`seq 1 2000000` ≈14 MB / `real 1.702s` 吃干净；200 MB base64 洪水下 52 fps、janky 0；单次 64 KB `append` 3–4 ms） |
| **App 把滚动按「整行」量化 ⇒ 亚行位移不重画** | 我自己的第一轮测量（帧数 ∝ 距离、不 ∝ 时长） | **机制描述对、但不是用户这条路径**。真机探针证明：该手势**走的是滚轮分支**，我改的本地回滚路径一次都没被调用（见 §3） |

## 2. 量具与口径（可复用）

- 帧数：`adb shell dumpsys gfxinfo com.example.zhengdao reset` → `input swipe` → `dumpsys gfxinfo`，取 `Total frames rendered` / `Janky frames` / `50th`/`90th`/`95th`/`99th percentile`；
- **拖动必须用慢速、不带惯性**（`input swipe x y1 x y2 900` 以上；`300` ms 会触发 fling，探针里的 `wheelRows` 会飙到 180–270）；
- 坐标系：终端区 x=656、y≈400–1500（**别落在 y≈1879 的方向键行上**）；
- **量「一次拖动走多少行」的唯一量具 = tmux 状态栏的位置指示** `[位置/总行数]`（进 copy-mode 后状态栏会多出 `[tmux]` 标记）；
- 静止满屏的 fps 不可信：只有 600 ms 一次的光标闪烁帧在驱动重绘（3 秒静止 ≈ 6 帧）。

## 3. 关键证据：临时探针（已删）

在 `app/src/main/java/com/termux/view/TerminalGestureController.java` 的 `onUp` 里打一行日志，真机读回：

```
ZDSCROLL: up: mouseTracking=true scrollCalls=0 px=0.0 wheelRows=9 localBlocked=0 transcriptRows=0 topRow=0 offset=0.0
```

- `mouseTracking=true` ⇒ 当前会话开了鼠标跟踪（tmux），手势进**滚轮分支**；
- `scrollCalls=0`、`px=0.0` ⇒ **新写的本地像素级滚动一次都没被调用**；
- `transcriptRows=0` ⇒ tmux 用**备用屏**，App 的本地回滚缓冲恒为空 ⇒ 本地平滑**结构上帮不上忙**。

## 4. 真凶：tmux 默认每次滚 5 行

两次 600 px 慢拖（各 9 个滚轮事件）后，状态栏位置：

| 动作 | 位置指示 | 位移 |
|---|---|---|
| 起点（在底部） | `[0/1992]` | — |
| 第一次 600 px 慢拖（9 事件） | `[55/1992]` | **+55 行** |
| 第二次 600 px 慢拖（9 事件） | `[115/1992]` | **+60 行** |

⇒ **≈6 行 / 滚轮事件**（tmux 默认 `WheelUpPane` 一次滚 5 行，带惯性时更多）。
手指走 11 行（600 px ÷ 54 px/行），内容跳 55–60 行 ⇒ **不跟手 = 5~6 倍速**；**一顿一顿 = 每次跳 5 行**。
（这也解释了第一轮测量「帧数 ∝ 距离、斜率 ≈54 px/帧 = 一行」：App 每收一个滚轮事件才重画一次，而事件的产生只跟手指走过的行数有关，与时长无关。）

## 5. 修法（改在源头，客户端一行没改）

`app/src/main/java/com/example/zhengdao/terminal/ProotLauncher.kt` 的 `tmuxConfAppend()` 幂等追加四条绑定：

```
bind -T copy-mode    WheelUpPane   send -N1 -X scroll-up
bind -T copy-mode    WheelDownPane send -N1 -X scroll-down
bind -T copy-mode-vi WheelUpPane   send -N1 -X scroll-up
bind -T copy-mode-vi WheelDownPane send -N1 -X scroll-down
```

- 判重规则：**同一表（`copy-mode` / `copy-mode-vi`）同一键（`WheelUpPane` / `WheelDownPane`）上用户已经写过绑定（`bind` 或 `bind-key`）就让位**，缺哪条加哪条 ⇒ 幂等、不覆盖用户配置；
- 生效范围：主会话每次启动都 `tmux kill-server` ⇒ 一进终端即生效；已在跑的旧 server 要重启后生效；
- 客户端保持「手指走一行 ⇒ 发一个滚轮事件」，于是**手指一行 = 内容一行**，与本地回滚口径一致。
- 单测：`app/src/test/java/com/example/zhengdao/terminal/TmuxConfAppendTest.kt`（空配置补齐 / 幂等 / 用户已写则让位 / 文本门禁：客户端只发一次 `C-b x`、不再盲发 `y`）。

## 6. 复测

| 项 | 修前 | 修后 |
|---|---|---|
| 一次 600 px 慢拖走过的行数 | **55–60 行** | **约 9 行**（两次共 `[17/1990]`） |
| 与手指位移比 | 5~6 倍 | **≈1:1**（手指 11 行） |
| 单次拖动帧数（含静止基线） | 9–14 | **18**（其中内容驱动的约 9–11 帧，其余是光标闪烁） |

单测：`suites=78 tests=660 skipped=1 failures=0 errors=0`。

## 7. 同时撤回的一版（记录在案，避免重复劳动）

按「整行量化」假设做的**像素级平滑滚动**已 `git checkout --` 还原，**不进本笔**：
新 `TerminalScrollMath.java` ＋ `TerminalView.java`（亚行偏移 `mTopRowPixelOffset`／`scrollByPixels`／`applyTopRowAndOffset`，`onDraw` 平移画布，命中测试带偏移）＋ `TerminalRenderer.java`（`endRow` 多画一行）＋ `TerminalGestureController.java`（拖动改像素）＋ 3 个测试文件（`TerminalScrollMathTest` 10 条、`TerminalSmoothScrollGateTest` 7 条）。

- **撤回理由**：它在当前 App 的 tmux 会话里**不会被调用**（§3），却要动常态渲染（多画一行 + `canvas.save/translate`）并让 `TERMINAL 三个上游文件`多背一份修改通知；
- **备份**：`%TEMP%\zd-smooth-scroll-20261012\`（`smooth-scroll.diff` 31,958 B ＋ `TerminalScrollMath.java` / `TerminalScrollMathTest.kt` / `TerminalSmoothScrollGateTest.kt` 三份副本）；
- **什么时候可以取回来**：出现「非 tmux 回落路径」的实际需求时 —— `app/src/main/java/com/example/zhengdao/terminal/ProotLauncher.kt` 里「rootfs 未带 tmux，降级为裸 bash 会话」那条分支，此时 App 自带回滚缓冲（`transcriptRows > 0`）才有内容可平滑。

## 8. 义务与回滚

- 本笔**没有改动任何 Termux 上游文件**（`app/src/main/java/com/termux/` 下无改动）⇒ `PROVENANCE.md` 的 Apache-2.0 §4(b) 修改通知表**无需增行**；
- 回滚：删掉 guest 的 `~/.tmux.conf` 里那四条绑定（或整体 `rm ~/.tmux.conf && tmux kill-server`），App 侧回退 `ProotLauncher.tmuxConfAppend()` 里的新代码块即可；
- 复现步骤：装包 → 进终端 → `seq 1 2000` → 慢速（≥900 ms）从 y=500 拖到 y=1100 ×2 → 读状态栏位置指示；若两次位移 ≈55 行/次说明绑定没生效（旧 server 没重启）。
