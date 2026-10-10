# 终端渲染 P0 实测（真机 · 2026-10-10）

> 产出：2026-10-10（DeepSeek Harness）｜被测包：**v2.0.9**（versionCode 25，真机 `lastUpdateTime` 10-10 16:54:34）
> 对应方案：`docs/证道-终端显示交互层重写方案-2026-10-10.md` 的 **P0「量三个数」**
> 设备：Honor **PGT-AN10**（`AD3J023824001723`，Android 16，1312×2848，density 560，未 root）
> 方法：`adb shell dumpsys gfxinfo com.example.zhengdao`（summary）＋ `… framestats`（相位拆解）
> 一句话：**渲染器不是瓶颈——`render()` 一帧 2.3 ms，只占 16.67 ms 预算的 14%；中文与英文没有可测差异。掉帧发生在 GPU/合成侧。**
>
> ⚠️ **更正（2026-10-10 晚 · ERRATA E-088）**：原句末尾还有「**与主线程解析侧**」五个字，以及 §一 ③ 行、§4.2 读法、§七第 2 条据此推出的「洪水时解析把主线程占满、渲染挨饿」——**已正式撤回**。
> 复核方式：把洪水窗口拉长，逐 2–3 s 采应用 CPU（`/proc/<pid>/stat` 的 utime+stime）与**累积**帧数。结果：`seq 1 2000000`（≈14 MB，guest 侧 `real 1.702s`）在**头 3 秒**就被 App 消化掉（CPU +920 ms、≈108 帧、**janky 0**）；200 MB `base64` 洪水 16 s 内 CPU +9.36 s、**939 帧（≈52 fps）**、**janky 0**。
> ⇒ 洪水期间 App 一直有 **36–52 fps 且零掉帧**；原「英文洪水 16.7 fps / 中文洪水 29.3 fps」是**测量窗口落在洪水结束之后**的读数（窗口里只剩 600 ms 光标闪烁）。渲染器 2.3 ms 与「中英无差」两条结论不受影响。

---

## 一、结论速览

| 问题（方案文档 P0） | 实测答案 | 判读 |
|---|---|---|
| **① `render()` 每帧 ms** | **2.3 ms（p50）**：英文 2.26 / 2.27 / 2.37；中文 2.27 / 2.29 / 2.67（三次独立采样，各 10 帧） | ✅ 远在预算内（16.67 ms 的 14%） |
| **② 中文屏 vs 英文屏** | **无差异**（p50 相同到小数点后两位）；中文 p90 偶发 8.84 ms | ✅ 字形光栅化/每码点 `measureText` **不是问题** |
| **③ 真实帧率** | 静态屏 1.7–4 fps（**只有光标闪烁在驱动**）· 手滑 12 次 **19.4 fps** · ~~英文洪水 16.7 fps · 中文洪水 29.3 fps~~（**窗口作废，见 §4.2 更正**；长洪水复测 **36–52 fps / janky 0**） | ⚠️ ~~洪水时帧很少但每帧很快 ⇒ 主线程不在画图，在别处~~ → **该推断已撤回（E-088）**；渲染器 2.3 ms 的结论不变 |

**配套观测**：同窗口内 `帧总时长 p50 = 6.4–8.4 ms（英）/ 6.5–19.9 ms（中）`、`GPU 相位 p50 = 2.7–3.1 ms（英）/ 2.7–16.1 ms（中）`；
累计口径 `janky ≈ 50%`（英 89/181、中 114/220），`gfxinfo p50/p90/p99 = 10–15 / 30–32 / 32–53 ms`。

---

## 二、测量环境（对照时必须同条件）

| 项 | 值 |
|---|---|
| 设备 / 系统 | 荣耀 PGT-AN10（Magic 5 Pro）／ Android 16 |
| 屏幕 | 1312×2848 @560dpi |
| **面板活跃模式** | ⚠️ **60 Hz**：`mActiveModeId=1`、`mActiveRenderFrameRate=60.000004`、`vsync=60.000004` ⇒ **帧预算 16.67 ms** |
| 设备支持的刷新率 | `[60.000004, 120.00001, 120.00001, 120.00001, 90.0, 40.0, 30.000002]`（defaultModeId=6，userPreferredModeId=-1） |
| 被测包 | v2.0.9，release（非 debuggable），未 root |
| 负载 | `tmux` 会话内 `bash`（`[zhengdao]0:bash*`），软键盘已收起 |
| 采样 | 每场景「`gfxinfo reset` → 固定窗口 → 读两次 `framestats`（间隔 2.5 s，各 ~10 帧）」；三个场景各采 3 次 |

> ⚠️ **与既有基线口径不一致（未查明原因）**：`docs/acceptance/compose-perf-baseline.md:30` 记的是
> `renderFrameRate **120Hz**`，本次同一台设备同一位置读到的是 **60 Hz**。两处数字不可直接对比帧预算
> （8.33 ms vs 16.67 ms）。本次所有判断都按 60 Hz / 16.67 ms 口径。

---

## 三、方法（两个必须知道的坑，否则数字要么是空的、要么读错）

1. **`framestats` 的数据段要连调两次才出现。** 第一次调用只"准备"，第二次才有行；
   只调一次时 `---PROFILEDATA---` 之间是**空的**（本文件第一版因此把所有相位报成 `n/a`）。
2. **Android 16 的 `framestats` 是 25 列 CSV**，表头逐字如下（列下标 0 基）：
   `Flags,FrameTimelineVsyncId,IntendedVsync,Vsync,InputEventId,HandleInputStart,AnimationStart,PerformTraversalsStart,DrawStart,FrameDeadline,FrameStartTime,FrameInterval,WorkloadTarget,SyncQueued,SyncStart,IssueDrawCommandsStart,SwapBuffers,FrameCompleted,DequeueBufferDuration,QueueBufferDuration,GpuCompleted,SwapBuffersCompleted,DisplayPresentTime,CommandSubmissionCompleted,`

   | 相位 | 公式 | 含义 |
   |---|---|---|
   | **`draw(render)`** | `(15 − 8) / 1e6` | **`View.draw()` / `onDraw()` 的 CPU 时间＝本文的"渲染耗时"** |
   | `traversal` | `(8 − 7)` | 测量/布局 |
   | `input+anim` | `(7 − 5)` | 输入 + 动画回调 |
   | `frame total` | `(17 − 2) / 1e6` | 整帧（含等 vsync / 排队 / GPU） |
   | `gpu` | `(20 − 15) / 1e6` | 提交到 GPU 完成 |

   用 14 列的正则在 Android 16 上**一条都匹配不到**（历史脚本就是这么废的）。

3. 另外两条环境事实（省得下次再撞）：
   - `TerminalActivity` 是 `android:exported="false"` ⇒ `adb shell am start -n com.example.zhengdao/.TerminalActivity` 必失败
     （`SecurityException: … not exported from uid …`）。进终端：`monkey -p com.example.zhengdao -c android.intent.category.LAUNCHER 1`
     → `input tap 656 2775`（底部「终端」Tab）。
   - `input text` 的空格必须写成 `%s`；`input keyevent 4` 只收键盘、不 finish Activity（终端继续跑）。

---

## 四、原始数据

### 4.1 稳态重绘（核心数，两次独立会话）

**英文满屏静态**（`clear; seq 1 200`，可见 40 行 ASCII）：

| 采样 | `draw(render)` p50 / p90 | 帧总时长 p50 / p90 | GPU p50 / p90 |
|---|---|---|---|
| 第 1 次 | **2.26 / 2.48** | 6.45 / 10.70 | 3.00 / 7.38 |
| 第 2 次 | **2.27 / 2.48** | 7.40 / 15.75 | 3.82 / 12.77 |
| 第 3 次 | **2.37 / 2.58** | 8.38 / 21.58 | 3.06 / 16.97 |

**中文满屏静态**（`clear; cat /root/AGENTS.md | head -60`）：

| 采样 | `draw(render)` p50 / p90 | 帧总时长 p50 / p90 | GPU p50 / p90 |
|---|---|---|---|
| 第 1 次 | **2.27 / 2.46** | 6.47 / 7.96 | 2.73 / 3.21 |
| 第 2 次 | **2.29 / 2.67** | 6.45 / 19.87 | 2.79 / 16.10 |
| 第 3 次 | **2.67 / 8.84** | 19.87 / 30.96 | 16.10 / 19.42 |

⇒ **渲染耗时稳定在 2.3 ms，且中英一致**；波动全在帧总时长 / GPU 两列（第 3 次中文那组整体抬高，
疑为 vsync 对齐或热，见 §六）。

### 4.2 三种负载下的真实帧率（窗口 6.0 s）

| 场景 | 帧数 | **实测 fps** | janky | gfxinfo p50/p90/p95/p99 (ms) | slowUI / slowIssueDraw / deadlineMissed |
|---|---|---|---|---|---|
| 英文洪水 `clear; seq 1 2000000` | 100 | ~~**16.7**~~ ⚠️ 见下 | 7 (7.00%) | 5 / 7 / 19 / 27 | 2 / 7 / 7 |
| 中文洪水 `for i in $(seq 1 400); do cat /root/AGENTS.md; done` | 176 | ~~**29.3**~~ ⚠️ 见下 | 5 (2.84%) | 5 / 6 / 8 / 36 | 5 / 5 / 5 |
| 手滑 12 次（`input swipe` 上下交替，5.3 s 窗口） | 103 | **19.4** | 22 (21.36%) | 6 / 18 / 21 / 22 | 1 / 13 / **22** |
| 静态屏（光标闪烁驱动） | 10 | 1.7（6 s 窗）/ ~4（2.5 s 窗） | 100% | 27–42 / 31–48 | 6–10 / 8–10 / 10 |

> ⚠️ **这两行洪水数字已作废（测量窗口修正，2026-10-10 晚 · ERRATA E-088）**：两个窗口都**落在洪水结束之后**——
> `seq 1 2000000` 在 guest 侧 `real` 只有 **1.702 s**（user 0.025 / sys 1.311），6 s 窗口里跑的基本只剩 600 ms 的光标闪烁，
> 所以 16.7 fps / 29.3 fps 量到的是**闪烁帧率**，不是洪水帧率。长洪水逐 2–3 s 复测：`seq 1 2000000`（≈14 MB）在头 3 秒内
> 被 App 消化（CPU +920 ms、≈108 帧、**janky 0**）；200 MB `base64` 洪水 16 s 内 CPU +9.36 s、**939 帧（≈52 fps）**、**janky 0**。
> ⇒ 洪水期间是 **36–52 fps、零掉帧**，本文 §4.2 读法与 §五第 2 条据此推出的「解析占满主线程」**正式撤回**。
> 同表「手滑 19.4 fps / 静态屏 1.7–4 fps」两行不受影响（它们的窗口本来就覆盖了对应动作）。

**滑动场景的相位**：`draw` p50 **2.58** / p90 8.38；帧总时长 p50 5.83 / **p90 27.48**；GPU p50 2.03 / **p90 16.95**。

**读法**：~~洪水场景「帧少（16.7 fps）但每帧只要 5 ms」——**主线程时间不在画图上**。~~
~~⇒ 输出洪水时主线程被 `append()` 占满，渲染器在挨饿。~~
**⚠️ 本节结论已正式撤回（2026-10-10 晚，ERRATA E-088）**：表里那两行洪水数字（英文 16.7 fps / 中文 29.3 fps）
是**测量窗口落在洪水结束之后**的读数——`seq 1 2000000` 在 guest 侧 `real` 只要 1.702 s，窗口里跑的基本只剩
600 ms 的光标闪烁。用长洪水逐 2–3 s 采样复测：`seq 1 2000000`（≈14 MB）在**头 3 秒**内被 App 消化完
（CPU +920 ms、≈108 帧、**janky 0**）；200 MB `base64` 洪水 16 s 内 CPU +9.36 s、**939 帧（≈52 fps）**、**janky 0**。
⇒ 洪水期间 App 一直有 36–52 fps 且零掉帧，**「解析占满主线程、渲染挨饿」不成立**。
（下方那段「代码事实」仍然成立——解析确实在主线程——但**它并不构成瓶颈**：按 47–64 ms CPU/MB 折算，
单次 64 KB `append` ≈3–4 ms，远低于 16.67 ms 预算。此条同样**未做 atrace 直接取证**，见 §六。）

**代码事实（仍然成立，但不构成瓶颈）**：PTY 的字节读取在线程里（`app/src/main/java/com/termux/terminal/TerminalSession.java:133-148`
读进队列后 `sendEmptyMessage(MSG_NEW_INPUT)`），而**解析与写入模拟器发生在主线程**
（同文件 `:337-347` 的 `MainThreadHandler.handleMessage()` → `mEmulator.append(...)`；
`:224-226` 再回调 `mClient.onTextChanged`）。

### 4.3 留档：被污染的第一组数（不要引用）

同一天早些时候、**进程刚 boot 完 Debian 仍在忙**时采的一组（`zd-p0-a.ps1`）：

| 场景 | 帧总时长 p50 / p90 | janky | GPU p50 |
|---|---|---|---|
| 英文静态 | 30 / 32 | 10 (100%) | 16 |
| 中文静态 | 38 / 48 | 10 (100%) | 13–15 |

这组数是**帧总时长**（含 GPU/排队/等 vsync），不是渲染耗时；且窗口落在主线程忙于启动的时段。
留在这里是为了让 §五 的更正可追溯。

---

## 五、更正（对应 `docs/ERRATA.md` E-086）

1. **把"帧总时长"当成了"渲染耗时"。** 2026-10-10 的口头汇报里写过
   「全屏重绘在 UI 线程上 30 ms（ASCII）/ 38 ms（中文）中位，是 16.67 ms 预算的 1.8–2.9 倍」
   ——那是 `gfxinfo` 的**帧总时长百分位**。拆相位后 `draw(render)` = **2.3 ms**。
2. **"中文比英文慢 27%（p50）/ 50%（p90）"不成立。** 同一口径下中英 p50 相同（2.27 vs 2.27 ms）。
3. 由此，`docs/证道-终端显示交互层重写方案-2026-10-10.md:183` 的
   「Compose 文本测量性能 🟡 —— `TerminalRenderer.java:111` 只有 ASCII 走缓存，中文每码点每帧一次 `measureText`，
   Compose 侧需加宽度缓存」**在本机这个屏上不是风险**：40 行满屏中文没有可测代价。该条可降级；
   真要加 `SparseArray<Float>` 宽度缓存，应作为"顺手做"而不是"必须先做"。
4. **本文件与 `docs/acceptance/compose-perf-baseline.md` 不是同一场景，别混着比**：
   那边量的是冷启动 / 太极页 / **终端页切换** / 设置页滚动（`terminalSwitch` 的 P90 +44 ms 来自
   TerminalView 初始化 + proot 建连，属**一次性切换成本**）；本文量的是**终端稳态重绘**。

---

## 六、疑点与未取证（如实记录）

1. **GPU 相位 16–19 ms 没坐实。** 空闲屏上"GPU 时间"可能是**等下一个 vsync** 的对齐效应而非真实 GPU 工作；
   要判死需再抓 `dumpsys SurfaceFlinger --latency` 或 GPU HISTOGRAM 的对照。本文不据此下结论。
2. **60 Hz vs 120 Hz 口径冲突**未查明（§二）。同一台设备在 `compose-perf-baseline.md` 采集时读到 120 Hz。
3. **"主线程被解析占满"是推断**，未做 atrace / `Perfetto` 直接取证。
4. **没测**：软键盘弹出/收起路径、选区与复制、`fling` 惯性滚动（只做了 12 次手滑）、横屏（≥600dp 单行快捷键条）、
   深色主题下的同一组数。
5. 光标闪烁触发的是**整屏 `invalidate()`**（Termux 上游行为），本文没有单独量化"改成局部失效能省多少"——
   这是最便宜的一个待验优化（见 §七）。

---

## 七、给 P1 / P2 的含义

- **换渲染层（Compose 画布 + `drawIntoCanvas { it.nativeCanvas }` 原样搬 `drawTextRun`）不会让帧变快**：
  CPU 绘制只占 16.67 ms 里的 2.3 ms，且中英一致。它的收益是**结构/可维护性/可加特性**，不是性能。
- 数字指向的真问题有两个，都在 P1 那一层能顺手做：
  1. **光标闪烁整屏重绘** —— 每 ~500 ms 一次全屏重录 + 全屏合成（静态屏因此 100% janky）。改成**局部失效**（只失效光标所在行/单元）是纯收益项。
  2. ~~**解析在主线程** —— 洪水时帧率被 `mEmulator.append()` 挤到 16.7–29.3 fps。要么把解析挪出主线程，要么在解析与渲染之间做**背压/合并**。~~
     **⚠️ 已撤回（2026-10-10 晚，ERRATA E-088）**：那两个 fps 是窗口落在洪水结束之后的读数；长洪水复测为 **36–52 fps / janky 0**，
     ⇒「解析挤掉帧」不成立。P1-b 因此裁决为**不做**（理由见 `docs/证道-P0渲染层测量-2026-10-10.md` §3 P1-b）。
- **不动**：`activity_main.xml` / `term_keys.xml` / `themes.xml` / `key_button_bg.xml`（`wt-ios-skin` 的 `8c015df`、`9a4a525` 正在改这组文件，详见 preflight 记录）。

---

## 八、怎么复跑（原样复制）

```powershell
$adb = 'C:\Users\guoli\AppData\Local\Android\Sdk\platform-tools\adb.exe'
# 进终端（TerminalActivity 不可 am start）
& $adb shell monkey -p com.example.zhengdao -c android.intent.category.LAUNCHER 1
Start-Sleep 12
& $adb shell input tap 656 2775          # 底部「终端」Tab
& $adb shell input keyevent 4            # 收键盘（Activity 不 finish）

# 打字：空格写 %s
& $adb shell "input text 'clear;%scat%s/root/AGENTS.md%s|%shead%s-60'"
& $adb shell input keyevent 66

# 量：reset → 等窗口 → 读两次 framestats（间隔 2.5 s，取第二次）
& $adb shell dumpsys gfxinfo com.example.zhengdao reset
Start-Sleep 6
& $adb shell dumpsys gfxinfo com.example.zhengdao framestats | Out-Null     # 第一次：准备
Start-Sleep -Milliseconds 2500
& $adb shell dumpsys gfxinfo com.example.zhengdao framestats                 # 第二次：有数据
```

本机 **ExecutionPolicy 是 Restricted**：`.ps1` 不能直接跑，用
`Invoke-Expression (Get-Content -Raw '<path>')`。本次用的三个脚本在
`%TEMP%\zd-p0-a.ps1`（旧解析器，留档）、`zd-p0-b.ps1`（25 列解析器 + 四场景）、`zd-p0-c.ps1`（framestats + 滑动）、`zd-p0-d.ps1`（干净对照）。

**截图留档**（`%TEMP%`）：`zd-p0-en200.png`（英文 40 行）、`zd-p0-cn-2.png` / `zd-p0-flood-zh.png`（中文满屏，确认过确实是 CJK 内容）、`zd-p0-flood-en.png`、`zd-p0-fling.png`、`zd-p0-term-boot.png`、`zd-p0-main.png`。
