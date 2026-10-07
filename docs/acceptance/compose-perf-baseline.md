# Compose 性能基线报告

> 产出时间：2026-10-07 ｜ 基线版本：v1.2.0（versionCode 14）
> 模块：`:macrobenchmark`（`com.android.test`，androidx.benchmark-macro-junit4 **1.5.0** + UiAutomator **2.4.0**）
> 一句话：**这是后续任何 UI 改动的对照原点——改动前后各跑一次，同场景同分位对比，恶化超阈值即判回归。**

---

## 一、结论速览

| 场景 | 指标 | 中位数 | 尾部（P99） | 判读 |
|---|---|---|---|---|
| 冷启动 | timeToInitialDisplayMs | **287.8 ms** | max 294.9 | 优（<500ms 即体感"秒开"） |
| 太极页加载 | frameDurationCpuMs | P50 **3.76 ms** | P99 63.6 ms | 常态流畅；少数长帧（serve 建连 + 首屏） |
| 终端页切换 | frameDurationCpuMs | P50 **2.91 ms** | P99 28.6 ms | ⚠️ P90 起掉帧，四场景中最重 |
| 设置页滚动 | frameDurationCpuMs | P50 **8.93 ms** | P99 11.4 ms | ✅ 无掉帧（overrun 全负） |

**整体结论：UI 主干健康。唯一的红旗是「终端页切换」，P90 起超帧（+44ms）——与
TerminalView 初始化 + proot 拉起有关，属架构固有成本而非 Compose 层问题，暂不动；
但它是将来终端 UI 改动时最该盯的一个数。**

---

## 二、测量环境（对照时必须同条件）

| 项 | 值 |
|---|---|
| 设备 | 荣耀 **Magic 5 Pro**（PGT-AN10，MagicOS / Android 16，SoC kalama） |
| 系统 | Android 16 |
| 屏幕 | 1312×2848 @560dpi，renderFrameRate **120Hz** |
| **构建类型** | `benchmark` 变体 ＝ `initWith(release)`：R8 混淆 + 资源收缩 + **非 debuggable** + **profileable**，debug 签名（可覆盖安装不丢数据） |
| 迭代次数 | 每场景 **5 轮**，取中位数 |
| 数据落盘 | `/storage/emulated/0/Android/media/com.example.zhengdao.macrobenchmark/*.perfetto-trace` |

### 口径说明（读数字前必看）

- **冷启动只报 `timeToInitialDisplay`**（首帧上屏）＝「首帧渲染时间」。
  没有 `timeToFullDisplay`——那需要 App 主动调 `reportFullyDrawn()`，目前没接（见 §七）。
- **`frameOverrunMs`**＝帧实际耗时超出系统帧截止时间的量，**负数＝没超，正数＝掉帧**。
- 太极/终端两场景的 `frameCount` 波动大（太极 35→64，终端 9→27），因为窗口长度取决于
  serve 建连 / proot 启动这些非 UI 因素。**对比时看 P50/P99 耗时，不要比帧数。**
- 本机未 root，Macrobenchmark 的 shader 缓存清理在冷启动路径上**必然失效**（§五·坑①），
  冷启动数字是"shader 缓存偏热"口径——**每次都用同一口径，对照依然成立**。

---

## 三、四场景原始数据

### 1. coldStartup（冷启动，进程被杀后从零拉起）

```
time_to_initial_display_millis_min     = 241.83
time_to_initial_display_millis_median  = 287.83
time_to_initial_display_millis_max     = 294.92
time_to_initial_display_millis_stddev  = 21.69
```

σ≈22ms（约 8%）——**单次对比无意义，须两次复跑取一致趋势**。

### 2. taijiTabLoad（丹房 → 太极 切页 + 首屏稳态）

```
frame_count_median              = 36      (min 35 / max 64, σ 12.7)
frame_duration_cpu_millis_p50   = 3.76
frame_duration_cpu_millis_p90   = 19.64
frame_duration_cpu_millis_p95   = 20.15
frame_duration_cpu_millis_p99   = 63.62
frame_overrun_millis_p50        = -11.57
frame_overrun_millis_p90        = +4.78
frame_overrun_millis_p95        = +7.02
frame_overrun_millis_p99        = +49.63
```

### 3. terminalSwitch（点「洞天」拉起 TerminalActivity）

```
frame_count_median              = 11      (min 9 / max 27, σ 7.5)
frame_duration_cpu_millis_p50   = 2.91
frame_duration_cpu_millis_p90   = 25.82
frame_duration_cpu_millis_p95   = 27.41
frame_duration_cpu_millis_p99   = 28.64
frame_overrun_millis_p50        = -6.12
frame_overrun_millis_p90        = +44.10
frame_overrun_millis_p95        = +47.30
frame_overrun_millis_p99        = +52.74
```

### 4. settingsScroll（设置页连续 3 段滑动）

```
frame_count_median              = 150     (min 150 / max 151, σ 0.55)  ← 极稳
frame_duration_cpu_millis_p50   = 8.93
frame_duration_cpu_millis_p90   = 10.13
frame_duration_cpu_millis_p95   = 10.61
frame_duration_cpu_millis_p99   = 11.44
frame_overrun_millis_p50        = -6.27
frame_overrun_millis_p90        = -4.95   ← 全部为负
frame_overrun_millis_p95        = -4.46
frame_overrun_millis_p99        = -3.59
```

### 稳定性验证（设置页滚动，同一份代码多次运行）

| 运行 | P50 (ms) | P99 (ms) |
|---|---|---|
| 第 1 次 | 8.33 | 10.96 |
| 第 2 次 | 9.00 | 11.40 |
| 第 3 次 | 8.98 | 11.56 |
| 第 4 次 | 8.98 | 11.25 |

极差约 **8%**，无趋势漂移 → **判定稳定，可作基线**。

---

## 四、怎么复跑（原样复制即可）

```bash
cd /c/Users/guoli/AndroidStudioProjects/zhengdao
export JAVA_HOME="/d/Program Files/Android/Android Studio/jbr"
export PATH="/c/Users/guoli/AppData/Local/Android/Sdk/platform-tools:$PATH"

./gradlew :app:assembleBenchmark :macrobenchmark:assembleBenchmark
adb install -r app/build/outputs/apk/benchmark/zhengdao-1.2.0-benchmark.apk
adb install -r macrobenchmark/build/outputs/apk/benchmark/macrobenchmark-benchmark.apk

# ⚠️ 必须先启动一次 App 再跑（§五·坑①），否则冷启动测试直接失败
adb shell am start -n com.example.zhengdao/.MainActivity && sleep 4

adb shell input keyevent 3
adb shell am instrument -w \
  -e androidx.benchmark.dropShaders.throwOnFailure false \
  -e class com.example.zhengdao.macrobenchmark.ZhengdaoBenchmark \
  com.example.zhengdao.macrobenchmark/androidx.test.runner.AndroidJUnitRunner
```

单场景：`-e class ...ZhengdaoBenchmark#settingsScroll`（方法名：`coldStartup` /
`taijiTabLoad` / `terminalSwitch` / `settingsScroll`）。

**对照流程**：改 UI 前跑一次 → 改完跑一次 → 同场景同分位对比。

### 回归判定线（建议）

- P50 恶化 **>30%** 且 P99 恶化 **>50%**，两次复跑都复现 → 判回归，必须修或写明理由；
- 冷启动中位数变化在 **±25ms**（一个 σ）内 → 视为噪声；
- 设置页滚动只要 `frame_overrun_millis_p90` 由负转正 → 判回归（它现在是零掉帧）。

---

## 五、踩过的坑（复跑前必读，全部真机实测坐实）

1. **stopped state 吞广播**——`adb install -r` 之后包处于停止态，manifest 声明的
   `ProfileInstallReceiver` 收不到广播（`am broadcast` 返回 `result=0`，启动过一次后才是
   `result=14`）。所以装完必须先启动一次 App。
   更糟的是：Macrobenchmark 冷启动路径是**先 force-stop 再发广播**，而 force-stop 会把包
   踢回停止态 → 未 root 设备上这一步**必然失败**。必须加
   `-e androidx.benchmark.dropShaders.throwOnFailure false` 降级为"记日志不抛错"。
2. **测试 APK 不会自动签名**——`com.android.test` 的非 debuggable 变体不自动补 debug
   签名，装不上（`INSTALL_PARSE_FAILED_NO_CERTIFICATES`）。已在
   `macrobenchmark/build.gradle.kts` 显式 `signingConfig = signingConfigs.getByName("debug")`。
3. **`FLAG_ACTIVITY_CLEAR_TASK` 清不掉终端页**——终端页压在栈顶时，仅清栈启动后栈顶仍是
   `TerminalActivity`（dumpsys 实证）；必须先 `force-stop` 再启动（任务栈 t4792→t4793 整个
   重建）。基准里 `launchHome()` 的 `killProcess()` 不能删。
4. **`last_route` 污染冷启动**——App 记忆上次页面，上个测试停在「设置」会让冷启动直接落在
   设置页，底栏锚点（太极/证道）不存在 → 必败。已用 `@Before parkOnHome()` 归位。
5. **复位逻辑：从「无障碍树找文字节点」改为「系统返回键」**——界面明明有「‹ 返回」，
   `findObject(By.text("返回"))` 仍偶发返回 null（失败诊断输出可见标记=[]，原因未明）。
   二级页复位改按 **BACK 键**（系统级动作，不依赖无障碍树）。改完后设置页滚动连续
   通过，问题未再复现。
6. **AGP 9 的两处适配**——`com.android.test` 插件自带 kotlin 扩展，**不要再 apply
   `org.jetbrains.kotlin.android`**（报 `Cannot add extension with name 'kotlin'`）；
   脚本里 `create()` 出来的 buildType 不生成 `benchmarkImplementation` 访问器，要用
   `add("benchmarkImplementation", ...)`。
7. **`<profileable>` 必须显式加**——AGP 9 不自动注入，而 FrameTimingMetric 采帧必需
   （核对过 benchmark 变体合并清单确认）。用 `isProfileable = true`。

---

## 六、边界与归属说明

- **release 产物零影响**：`androidx.profileinstaller` 只挂 benchmark 变体
  （`add("benchmarkImplementation", ...)`），release 不会背上这个依赖。
- **架构冻结红线未触碰**：本任务只新增 `:macrobenchmark` 模块与构建配置，未动 PRoot 启动 /
  SSE·REST 客户端 / tmux / Agent 安装器 / `OcClient`·`OcRepository`。
- ⚠️ **归属勘误（同 E-011 情形，不改写已推送历史）**：`app/build.gradle.kts` 里 benchmark
  变体与 profileinstaller 的 24 行改动实际由本任务引入，但被并行提交 `4526b8f`
  （test(rootfs): 第一阶段测试覆盖）一并卷入仓库。代码本身正确，此处记录事实，避免将来
  考古时误判出处。
- 工作区根部的 `.kotlin/` 是 Kotlin 构建会话缓存，**未提交**，也暂不动共享的
  `.gitignore`（避免与并行工作冲突）。
- 基线采集收尾时真机上的 App 被并行工作卸载（Rust 解压实装验证），第 5、6 次稳定性
  复跑中止；已完成的 4 次成功运行足以支撑稳定性结论（见 §三·4）。

---

## 七、后续可做（不阻塞，记入待办池）

- [ ] 接 `reportFullyDrawn()`，让冷启动同时产出 `timeToFullDisplay`（真正的"内容可用"时点）。
- [ ] 太极页 P99 63.6ms 的长帧：定位是 serve 建连还是首屏 Compose 组合，属可优化项。
- [ ] 终端页切换 P90 +44ms：拆分「TerminalView 首帧」与「proot 启动」两段，后者不该算进 UI 指标。
- [ ] 加 `CompilationMode.Partial(baselineProfile = ...)`，为将来上 Baseline Profile 留对照。
