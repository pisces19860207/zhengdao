# 终端字体子集化 + 间距舒适档 真机验收（2026-10-10）

> 产出：2026-10-10 晚（DeepSeek Harness）｜被测包：**自建 benchmark 变体**（R8 开启）
> `app\build\outputs\apk\benchmark\zhengdao-2.0.9-benchmark.apk`（**7.82 MB = 8,196,507 B**，applicationId 无后缀，`adb install -r` 覆盖安装后 Debian 环境未丢）
> 对应实施：分支 **`feature/terminal-font-subset-spacing`**（自 `feature/terminal-font` 的 `c56a361` 切出，**不基于** `feature/terminal-color`）
> 设备：Honor **PGT-AN10**（`AD3J023824001723`，Android 16，1312×2848，未 root）＋内嵌 Debian 13.7 / tmux / vim｜字号：App 默认 12dp
> 方法：`adb shell input text` 注入 + `screencap` 截图，再用 Pillow 逐行扫亮度求墨迹区间（本页数值全部是 device px）
> 一句话：**子集化把字体从 17.94 MB 压到 6.46 MB（36%），APK 13.53 MB → 7.82 MB、首次加载 73 ms → 27 ms；2:1 对齐与子集化前逐像素一致（0 px 偏差）；新增「间距」舒适档实测行距 56 → 64 px（1.143 ≈ 预期的 1.15×）。**

---

## 一、结论速览

| 验收项 | 通过标准 | 实测 | 结论 |
|---|---|---|---|
| **2:1 对齐**（子集化后重跑基线） | 与子集化前一致，21 列累计偏差 0 px | 两行长行（10×CJK＋`\|`、20×ASCII＋`\|`）竖线同落 **x 552..556**；两行短行（`中\|`、`ab\|`）末笔同落 **x 106** | ✅ **0 px** |
| 无花屏 / 边框不歪 / 表格不错位 | 目视无异常 | tmux 分屏竖线一条到底；vim 边框/行号/语法正常 | ✅ |
| **APK 体积** | 13.53 MB → 9–10 MB | **14,188,475 B → 8,196,507 B（7.82 MB）** | ✅ 优于目标 |
| **字体首次加载** | 73 ms → < 30 ms | **27 ms**（同口径冷启动日志） | ✅ |
| **行高 1.15×** | 舒适档明显高于旧版 | 舒适 **64 px** / 紧凑 **56 px**（旧版＝56 px），比值 1.143 | ✅ |
| 档位可切换且可达 | 设置页可选、立即生效 | 设置 → 终端外观 → 行距：舒适 / 紧凑，切档后重进终端行距随之变 | ✅ |
| tmux 分屏 | 分隔线不歪不断 | 左右双 pane，竖线全高无断缝 | ✅ |
| vim | 边框 / 行号 / 状态栏 | `:set number` 行号 1..14、`import`/`class`/`def` 多色语法、CJK 注释对齐 | ✅ |
| htop 柱状图 | 真图验证 | **受 proot 无 `/proc/stat` 限制，验不了**；按所有者决定**已卸载 htop、保留 vim** | ⛔ 记录 |
| 覆盖代价 | —— | GB2312 之外约 **1.4 万汉字**回退系统字体 | ⚠️ 记录 |

---

## 二、任务与范围

1. **子集化**：用 `pyftsubset` 把终端正文字体裁到「GB2312 常用汉字 + 终端必备符号 + Nerd Font 图标」，记录前后体积与 SHA-256，**重跑 2:1 基线**，更新 `PROVENANCE.md` 与加载耗时基线。
2. **间距舒适档**：行高 ×1.15、终端左右内边距 8dp、上下 4dp、功能键条 48dp；数值提到 `TerminalPrefs`，默认「舒适」；行高通过 `TerminalSizeResolver` 控制、**不改 `updateSize()` 的像素→行列换算**；内边距加在外层容器、**不碰 `TerminalView` 的 4 个 XML**。
3. **真机验收 + 文档 + 提交**（本页）。

> 裁量前提：源字体 **96.4% 的体积是 `glyf`**，按 Unicode 范围裁到 GBK 只能省 3% —— 「APK 9–10 MB」与「保留 GBK 全量汉字」不可兼得，故请所有者裁决；**选择：GB2312 常用集 6,763 汉字 + NF 全图标**（代价：GB2312 之外的约 1.4 万汉字回退系统字体）。

---

## 三、分支与基线

| 分支 | HEAD | 说明 |
|---|---|---|
| `main` | `7f5b3289f9e0d1fd6e48001e5fc38c916433a327` | 本次实施**未触碰** |
| `feature/terminal-font` | `c56a361` | 字体资产 + 首次加载日志（本次的基线） |
| `feature/terminal-color` | `f2dc143` | Catppuccin 配色（**本次不含**，按决定不单独合并） |
| **`feature/terminal-font-subset-spacing`** | 自 `c56a361` 切出 | 子集化字体 + 间距舒适档 + 设置页入口 + 本页 |

**为什么不直接自 `main` 切**：`main` 上**根本没有字体资产**（字体改动还在未合并的 `feature/terminal-font` 里），自 `main` 切会让「子集字体」变成与 `feature/terminal-font` 同名二进制的 **add/add 冲突**；自 `feature/terminal-font` 切既**不含配色改动**（满足「别从配色分支切」的意图），又让子集产物成为原字体的**修改**，因此 `font → color → subset-spacing` 的合并顺序能干净落地（合并前已做 rebase 干跑，见 §九）。

---

## 四、子集化：工具、参数与结果

**工具**：`fonttools 4.66.1`（`brotli`），脚本 **`tools/subset-terminal-font.py`**（仓库内，可复跑；`--dry-run` / `--source` / `--output`）。

**参数**：`--layout-features=* --notdef-glyph --name-IDs=* --no-recalc-timestamp`（最后一项保证可复现），外加

* `--unicodes=U+0000-00FF,U+0100-024F,U+2000-206F,U+2190-21FF,U+2300-23FF,U+2500-259F,U+25A0-27BF,U+2B00-2BFF,U+3000-303F,U+3400-4DBF,U+F900-FAFF,U+FE30-FE4F,U+FF00-FFEF,U+E000-F8FF,U+F0000-FFFFD`
* `--text-file=<GB2312 解码出的 6,763 个汉字>`

> ⚠️ **踩坑**：`--unicodes` 里**不能写 `U+4E00-9FFF`** —— 写了等于保留上游全部 20,975 个汉字，产物回到 18,248,824 B（只省 3%）；去掉后才是 6,773,564 B。脚本 docstring 已注明。

| 项 | 源（`feature/terminal-font`） | 产物（本分支） |
|---|---|---|
| 体积 | 18,815,220 B（17.94 MB） | **6,773,564 B（6.46 MB）＝ 36.0%** |
| SHA-256 | `a4fc642d821671b1a2937b9a52d398b96cf0b1e1da758846ee1ff38a297b22a5` | `10c8ea51ab6bb2df9a615424df1054ad6b3c08ad60a47e8bb3368039b92b5089` |
| 字形数 | 34,127 | 18,342 |
| cmap 码位 | 33,355 | 17,994 |
| 步进（unitsPerEm 1000） | ASCII 600 / CJK 1,200 | ASCII 600 / CJK 1,200（**2:1 不变**） |
| 可复现 | —— | 两次独立运行 SHA 一致（`--no-recalc-timestamp`） |

**覆盖抽查**：GB2312 边界 `U+9F98` ✗ / `U+9F9F` ✓ / `U+9FA0` ✓ / `U+9F99`（龙）✓ / `U+8C61`（象）✓；`U+E000-F8FF`（BMP 私用区 3,499）与 `U+F0000-FFFFD`（补充私用区 A 6,880）全量保留（Nerd Font 图标即这两段）；方框线 `U+2500-257F` 128/128；保留 `GDEF/GPOS/GSUB/gasp`。**CJK 扩展 A（`U+3400-4DBF`）在上游就一个字形都没有**（㐀 `U+3400`、㐁 `U+3401` 均缺），扩展 B–G 亦为 0 —— 这不是子集化造成的。

`PROVENANCE.md` 已同步：许可表「JetBrains Maple Mono」行标注**已由本项目子集化**（OFL 1.1 允许修改再分发），并新增「### 字体子集化（2026-10-10）」小节记录上表与「升级流程＝取全量 ttf → 跑脚本 → 更新 SHA → 真机验 2:1 与加载耗时」。

---

## 五、间距舒适档：实现

| 文件 | 改动 |
|---|---|
| `app/src/main/java/com/termux/view/TerminalRenderer.java` | 新增三参构造 `TerminalRenderer(int textSize, Typeface typeface, float lineHeightMultiplier)`（两参构造委托 1.0f）；核心行 `mFontLineSpacing = (int) Math.ceil(mTextPaint.getFontSpacing() * multiplier);`。**只放大行盒，`mFontWidth` 不动 ⇒ 列宽与 2:1 不受影响**；光标块与选区矩形按 `mFontLineSpacing` 算，随之变高 |
| `app/src/main/java/com/termux/view/TerminalSizeResolver.java` | 新增 `private float mLineHeightMultiplier = 1.0f;` 与 `void setLineHeightMultiplier(float)`（同值早退；渲染器未建时只记值，否则重建渲染器 + `updateSize()` + `invalidate()`）；**`updateSize()` 的像素→行列换算一字未改**（行变高 ⇒ 行数自然变少） |
| `app/src/main/java/com/termux/view/TerminalView.java` | 转发 `public void setLineHeightMultiplier(float multiplier)` |
| `app/src/main/java/com/example/zhengdao/terminal/TerminalPrefs.kt` | `KEY_SPACING = "terminal_spacing"`；`enum class Spacing(id, label, lineHeightMultiplier, paddingVerticalDp, keyHeightDp)`：`COMFORTABLE("comfortable","舒适",1.15f,4,48)`、`COMPACT("compact","紧凑",1.0f,0,48)`，`DEFAULT = COMFORTABLE`；`spacing()` / `saveSpacing()`；`applyTo()` 里 `canvasHost.setPadding(insetPx, vInsetPx, insetPx, vInsetPx)`（左右＝画布留白，上下＝档位 4dp）并在 `setTextSize` 前 `termView.setLineHeightMultiplier(...)`；新增 `applyKeyBarHeight(keyBar, ctx)` 把子 `TextView` 高度设为 48dp（**不动** `key_bar_container`：窄屏两行键压成 48dp 会裁掉第二行） |
| `app/src/main/java/com/example/zhengdao/TerminalActivity.kt` | `wireKeyBar()` 开头调用 `TerminalPrefs.applyKeyBarHeight(...)` |
| `app/src/main/java/com/example/zhengdao/ui/SettingsScreen.kt` | 「终端外观」段在「画布留白」与「配色」之间新增**行距**芯片行（舒适 / 紧凑）+ 说明文案，选中态沿用 `FilterChip2` |
| `app/src/test/java/com/example/zhengdao/terminal/TerminalSpacingTest.kt`（新增） | 4 例：默认档是舒适 / 舒适档 1.15f＋4dp＋48dp / 紧凑档 1.0f＋0＋48 / 未知 id 回落默认且 `"compact"` 仍认得出 —— 全绿（`:app:testDebugUnitTest` BUILD SUCCESSFUL，套件 65 个全过） |

---

## 六、真机验收：逐条对照

### 6.1 行距（A/B 同机对照）

`seq%s1%s20` 注入后截图，Pillow 扫 x 15..559、阈值 40 求行带顶边：

| 档位 | 行带（前 5 行） | 行距 |
|---|---|---|
| 紧凑（＝旧版行为） | 331 / 387 / 443 / 499 / 555 | **56 px** |
| 舒适（新默认） | 353 / 417 / 481 / 545 / 609 | **64 px** |

`64 / 56 = 1.143`，与 `ceil(getFontSpacing() × 1.15)` 的取整预期一致（`S ∈ (55,56] ⇒ ceil(1.15S) = 64`）。此 A/B 同时证明**设置页芯片 → `saveSpacing` → `applyTo()` → `TerminalRenderer` 全链路在真机生效**。

### 6.2 2:1 对齐（子集化后重跑，硬要求）

`bash /sdcard/zd-font-check.sh`（`中文×10+|`、`abcdefghijklmnopqrst+|`、`中|`、`ab|`）注入后截图，逐行扫墨迹 + 列探针：

* 两行长行（21 列）末端的 `|` 都落在 **x 552..556**
* 两行短行（）的末笔都落在 **x 106**

⇒ **21 列累计偏差 0 px**，与 `docs/acceptance/terminal-font-2026-10-10.md` 的子集化前基线一致（列宽 ASCII 25.0 px / 中文 50.0 px）。

### 6.3 体积与加载耗时

| 指标 | 子集化前 | 本分支 |
|---|---|---|
| benchmark APK | 14,188,475 B（13.53 MB） | **8,196,507 B（7.82 MB）** |
| 首次加载（`createFromAsset`，冷启动日志） | 72 / 73 / 73 ms（3 样本） | **27 ms**（日志 `I/TerminalPrefs: 字体首次加载：fonts/JetBrainsMapleMono-NF-Regular.ttf，createFromAsset 耗时 27ms`） |

### 6.4 tmux 分屏 / vim

* `tmux split-window -h`：左右双 pane，**分隔竖线全高一条到底**，无断缝、无抖动。
* vim：`vim /sdcard/zd-test.py` → 进入后 `:set number` ⇒ 行号 1..14、`import`/`class`/`def` 关键字与字符串/数字/注释多色、CJK 注释（"证道终端验收：vim 语法着色 / 行号 / 状态栏 / 边框"）正常显示并与 ASCII 折行对齐。

> 注入踩坑（复现时注意）：`adb shell input text` 里的 `;` 会被设备 `sh` 吃掉（`clear; bash …` 报 `/system/bin/sh: bash%s…: inaccessible or not found`），须拆成多条 `input text` + `keyevent 66`；`vim -c set number f` 会被解析成「`set` 一个命令 + 两个待编辑文件」，正确做法是不带 `-c`，进入后在 vim 内输入 `:set number`；PowerShell 写出的 vimrc 是 CRLF ⇒ vim 报 `E488: Trailing characters`。

---

## 七、证据清单

`docs/acceptance/assets/terminal-font-subset-spacing-2026-10-10/`（Pillow q90 WebP，均取自真机截图）

| 文件 | 内容 |
|---|---|
| `00-before-lineheight.webp` | 改动前 `seq 1 …` 行距基线（56 px） |
| `01-lineheight-compact-1.0.webp` | 紧凑档 56 px |
| `02-lineheight-comfortable-1.15.webp` | 舒适档 64 px |
| `03-align-cjk-ascii-2to1.webp` | 2:1 对齐（10×中文＋`\|` 与 20×ASCII＋`\|` 同列） |
| `04-tmux-split.webp` | tmux 左右分屏，分隔线全高 |
| `05-vim-cjk-syntax.webp` | vim 行号 + 多色语法 + CJK 注释 |
| `06-settings-line-spacing.webp` | 设置 → 终端外观：字号 / 画布留白 / **行距（舒适·紧凑）** / 配色 |

---

## 八、已知限制

1. **覆盖代价**：GB2312（6,763 汉字）之外的约 **1.4 万汉字**（含 GBK 扩展字、生僻字）**回退系统字体**渲染 —— 字宽仍由终端按 2:1 计算，但字形来自系统字体，可能与正文风格不一致。
2. **CJK 扩展 A/B–G 全缺**：上游 `1.2304.79` 的 `Regular` 就不含这些字形（本次子集化未删除任何上游字形）。
3. **htop 无法在本环境验证**：proot 下没有 `/proc/stat` 等真实 procfs 内容，htop 画不出柱状图；按所有者决定**已从 rootfs 卸载 htop**（`apt-get remove -y htop` → `Removing htop (3.4.1-5) ... rc=0`，释放 475 kB），**vim 保留**（`/usr/bin/vim` 仍在，`dpkg -l` 只剩 `vim / vim-common / vim-runtime 2:9.1.1230-2`）。
4. **Activity 销毁观察**：终端页在后台被系统回收后重新进入时，观察到过一次会话/视图重建的时序现象（不影响 tmux 会话本身，重进即回到现场）；记入已知限制，**不阻塞本次工作**。
5. **设置档位只能在 App 内切换**：guest 侧看不到 `/data/data`（无 SharedPreferences），无法从 proot 内改档。
6. **量测方法误差**：本页行距取「墨迹顶边」之差，含 ±2 px 噪声；1.143 与理论 1.15 的差别即来自取整与量法，非实现偏差。

---

## 九、复现步骤

```powershell
# 1) 子集化（幂等：源 SHA 不符会 WARNING，避免对已子集化资产二次裁剪）
python tools/subset-terminal-font.py            # 输出 6,773,564 B / SHA 10c8ea51…

# 2) 构建 + 单测
.\gradlew :app:testDebugUnitTest :app:assembleBenchmark
#   → app/build/outputs/apk/benchmark/zhengdao-2.0.9-benchmark.apk（8,196,507 B）

# 3) 安装 + 冷启动取加载耗时
adb install -r app/build/outputs/apk/benchmark/zhengdao-2.0.9-benchmark.apk
adb shell am force-stop com.example.zhengdao
adb shell monkey -p com.example.zhengdao -c android.intent.category.LAUNCHER 1
adb logcat -d | Select-String "字体首次加载"        # 期望 27ms 上下

# 4) 2:1 与行距（终端页内注入；每次注入后截图再用 Pillow 量）
adb shell input text "clear"                 ; adb shell input keyevent 66
adb shell input text "bash%s/sdcard/zd-font-check.sh" ; adb shell input keyevent 66
adb shell input text "seq%s1%s20"            ; adb shell input keyevent 66
```

**合并顺序（待所有者放行）**：`feature/terminal-font` → `feature/terminal-color` → `feature/terminal-font-subset-spacing`；三分支 rebase 干跑已确认可干净合并。本分支**不合并 `main`**。
