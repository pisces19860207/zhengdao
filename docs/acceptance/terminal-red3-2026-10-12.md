# 验收页 · 终端三条 🔴（2026-10-12）

**范围**：`docs/FEATURE-LEDGER.md` §B 顺序 13「终端缺陷审计 18 条」里**最危险的三条**（其余 15 条仍在 §B）。
**用户交办**（2026-10-12 逐字）：「最紧要的做了吧，要开手机开发者模式就说一声」。
**分支**：`fix/terminal-red3`（从 `fa09938` 起）。

---

## 1. 改了什么

| # | 缺陷 | 文件 | 改动 |
|---|---|---|---|
| 🔴1 | 退格把光标列写成 **−1** ⇒ ICH 拿 −1 去 `TerminalBuffer.blockCopy` 撞断言 ⇒ 异常从 **pty 读线程**冒顶、整页 FATAL | `app/src/main/java/com/termux/terminal/TerminalEmulator.java` | BS 判据 `mLeftMargin == mCursorCol` → **`mCursorCol <= mLeftMargin`**；`setCursorCol()` 钳到 `[0, mColumns - 1]` |
| 🔴2 | 未 spawn 的会话被当成「在跑」⇒ `finishIfRunning()` 发 **`Os.kill(0, SIGKILL)`**（pid 0 = 调用者自己的进程组 ＝ 自杀） | `app/src/main/java/com/termux/terminal/TerminalSession.java` | `isRunning()`：`mShellPid != -1` → **`mShellPid > 0`** |
| 🔴3 | 粘贴大文本在**主线程**写 pty（`ByteQueue` 50 ms 轮询反复阻塞）⇒ ANR | `app/src/main/java/com/example/zhengdao/TerminalActivity.kt` ＋ 新建 `app/src/main/java/com/example/zhengdao/terminal/TerminalPasteWriter.kt` | 粘贴改走单线程 daemon executor、按 UTF-8 **4 KiB** 分块、每块写之前查会话存活、结果回主线程报进度与失败进度；顺带给 `SessionManager.session` 补 `@Volatile` |

`git diff --stat`：4 个被跟踪文件（`+114 −11`）＋ **4 个新文件**（1 个第一方类 `TerminalPasteWriter.kt` ＋ 3 个测试）。

---

## 2. 证据

| 项 | 命令 | 结果 |
|---|---|---|
| 编译 | `.\gradlew.bat --offline :app:assembleDebug` | **BUILD SUCCESSFUL in 21s** |
| 全量单测 | `$env:JAVA_HOME='D:\Program Files\Android\Android Studio\jbr'; .\gradlew.bat --offline testDebugUnitTest` | **BUILD SUCCESSFUL in 20s**；**71 suites / 638 tests / 0 failures / 0 errors / 1 skipped**（v2.0.17 时是 68 / 622） |
| 新增用例 | 读 `app/build/test-results/testDebugUnitTest/*.xml` | `com.termux.terminal.TerminalEmulatorTest` 4 · `com.termux.terminal.TerminalSessionTest` 2 · `com.example.zhengdao.terminal.TerminalPasteWriterTest` 10 —— **16 条全绿** |
| **测试有效性反证** | `git stash push -- app/src/main/java/com/termux/terminal/TerminalEmulator.java app/src/main/java/com/termux/terminal/TerminalSession.java` → `.\gradlew.bat --offline testDebugUnitTest --tests "com.termux.terminal.*"` → `git stash pop` | **`6 tests completed, 4 failed`**（两条崩溃复现 ＋ 两条存活判据全红）⇒ 测试钉的是**缺陷本身**，不是在描述现状 |

---

## 3. 真机复测（2026-10-12 17:0x · PGT-AN10 / MagicOS / Android 16 · `AD3J023824001723`）

**装的是哪只包**：本次修复的**正式签名**构建 `app/build/outputs/apk/release/zhengdao-2.0.17-release.apk`（8,320,032 B／本机 sha256 `d4cd560c…66eab`；`apksigner` 证书 `CN=Zhengdao`／`b93169f2…86ec2`，与线上 v2.0.17 **同一张证书** ⇒ `adb install -r` 保数据）。
装后 `dumpsys package com.example.zhengdao`：`versionName=2.0.17` / `versionCode=33` / `lastUpdateTime=2026-10-12 17:04:54`。
**包内容自证**：把 `classes.dex` 解出来查字符串池，能找到新线程名 `zd-paste` ⇒ 装进去的确实是本次改动（类名被 R8 混淆，线程名是常量字符串、留得住）。

| 项 | 做法 | 结果 |
|---|---|---|
| 冒烟 | 冷启动 → 进「终端」Tab（焦点 `com.example.zhengdao/.TerminalActivity`） | pid 存活、bash/tmux 会话正常、无 FATAL |
| 🔴3 新粘贴路径 | 点「复制」（整屏快拷）→ 终端里起 `cat > /sdcard/zd_p3.txt` → 点「粘贴」 | 立刻弹 **「正在粘贴 736 个字符…」**（另一次 204 个字符），字节确实进了 pty（bash 把粘进去的行当命令执行、回 `command not found`）⇒ **界面不冻、无 ANR、无崩溃** |
| ANR 复核 | 粘贴前后 `logcat` 过滤 `ANR in` / `Not responding` / `FATAL EXCEPTION` | 空；`pidof` 前后一致（30787） |

**三条各自的真机结论 —— 哪些「验过」、哪些「验不了」：**

- **🔴1：真机复现不了 —— 被 tmux 挡住。** 设备会话跑在 tmux 里（终端里 `echo $TMUX` → `/tmp/tmux-0/default,28851,0`，屏幕上也有 tmux 状态行）。pty 的唯一子进程是 tmux，**所有进到模拟器的输出都先过 tmux 的屏幕模型**，DECSLRM/BS 这类序列会被它按自己的模型重画、不再原样透传 ⇒ 从 shell 里打这串**到不了 `TerminalEmulator`**。当场连打三次（含一次把引号喂对的），App 全程没崩、logcat 无 `TerminalBuffer` / `IllegalArgumentException`。
  ⇒ 缺陷**存在性**与修复**有效性**由 JVM 单测钉死（`TerminalEmulatorTest`：修前两条红、修后四条绿，见 §2）；真机这一趟只证明「改了之后终端照常用」。
  ⇒ 顺带一条判断（记进 ERRATA）：只要会话在 tmux 里，「往模拟器喂裸转义序列」这条路走不通 —— 这条缺陷的**用户可见面比审计报告的口径小**，但代码里的雷仍然是真的（非 tmux 会话、或 tmux 自己发这类序列时会踩）。
- **🔴2：真机复现不了 —— 触发窗口抓不到。** 崩的前提是「会话对象已建、spawn 还没发生」的那一瞬间去 kill 它；App 里没有导出的入口能从 adb 打进这个窗口（`TerminalActivity` 未导出，通知栏「停止会话」是 PendingIntent，抓不到毫秒级时序）。⇒ 同样由 JVM 单测钉（`getPid()==0` ⇒ `isRunning()==false`；`finishIfRunning()` 不得发信号）。**注意**：单测里 `Os.kill` 若被调到会走 android.jar 桩抛异常，所以「不抛」等价于「没调」—— 但这一条是**推断**，我没有单独断言过。
- **🔴3：新路径真机验过（见上表）；「修复前一定 ANR」这一半没有在真机上反证。** 原因：① `adb shell cmd clipboard set-text` 在 MagicOS 上不可用（回 `No shell command implementation`），没法从外部预置 > 4 KB 的**单行**文本；② 会话在 tmux 里时 tmux 会先吃掉粘贴的字节，pty 写入不一定阻塞。⇒ 「旧代码会 ANR」仍只有代码链论证（`ByteQueue.wait()` 阻塞调用线程 ＋ 主线程调用）＋ 单测（`TerminalPasteWriterTest` 10 条：分块、跨块 round-trip、会话中途结束、写失败、线程归属、`submit` 立即返回）。

**踩到的坑（都记进 ERRATA）**
- `adb shell input text` 打带引号/分号/反斜杠的命令时：adb 那层 shell 会把引号当**语法**吃掉，喂进终端的正文里就**没有引号**了 ⇒ `\033` 被降级成 `033`、`;` 把命令切断（真机屏幕上亲手见：`-bash: 70sb033[@: command not found`）。要送引号得用 `'\''` 这类写法把它当**数据**送进去。
- `adb exec-out screencap -p > x.png` 在 PowerShell 里会把二进制写坏（`read_image` 报 malformed）；改成设备内 `screencap -p /sdcard/x.png` 再 `adb pull`。
- `adb shell cmd clipboard` 在 MagicOS 上不可用。

---

## 4. 义务与回滚

- **Apache-2.0 §4(b)**：两个 vendored 文件的修改点已追加进 `PROVENANCE.md`「Apache-2.0 修改通知」表（`TerminalEmulator.java` / `TerminalSession.java` 两行）。
- **教训**：`docs/ERRATA.md` **E-116**（哨兵值语义与判据不自洽；「只看见新测试通过」不等于钉住缺陷）。
- **回滚**：`git revert -m 1 <merge-commit>`；或直接从 `fa09938` 重拉分支。三条互不依赖，也可只回滚其中一条。
