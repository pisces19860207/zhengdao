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

## 3. 还没验的：真机

设备当前**未连接**（`adb devices` 列表为空，2026-10-12 12:0x 实测）⇒ 真机复测**未做**。三条各自的验法已定：

- **🔴1**：进终端页，在 guest shell 里执行
  `printf '\033[?69h\033[10;70s\b\033[@'`
  （这串全是可打印字符，`adb shell input text` 打得进去，不需要真按 ESC）。
  修复前这一条会让 App **FATAL**；修复后应当只是光标不动、页面照常。
- **🔴2**：走「冷启动 App → 进终端 → 退出 → 再进终端」，全程 logcat 不得出现 `Os.kill(0)` 引发的异常／FATAL。
  判据本身（`mShellPid == 0` ⇒ `isRunning() == false`）已由 JVM 单测钉死，真机只作回归。
- **🔴3**：复制一段 > 50 KB 的文本（例如整个 `docs/ERRATA.md`）→ 在终端页点粘贴：
  应立刻弹「正在粘贴 N 个字符…」、界面**不冻**，随后弹「已粘贴 N 个字符」。
  剪贴板可用 `adb shell cmd clipboard set-text` 预置（该命令在 MagicOS 上是否可用**待实测**）。

---

## 4. 义务与回滚

- **Apache-2.0 §4(b)**：两个 vendored 文件的修改点已追加进 `PROVENANCE.md`「Apache-2.0 修改通知」表（`TerminalEmulator.java` / `TerminalSession.java` 两行）。
- **教训**：`docs/ERRATA.md` **E-116**（哨兵值语义与判据不自洽；「只看见新测试通过」不等于钉住缺陷）。
- **回滚**：`git revert -m 1 <merge-commit>`；或直接从 `fa09938` 重拉分支。三条互不依赖，也可只回滚其中一条。
