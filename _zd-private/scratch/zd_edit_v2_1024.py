# -*- coding: utf-8 -*-
"""把 2026-10-24 用户拍板＋全案复核结果写进 v2 总案 MD（CRLF 保底回写）。"""
import io, sys

PATH = r"C:\Users\guoli\AndroidStudioProjects\zhengdao\证道设计报告\证道-界面优化与美化总案-v2-2026-10-23.md"

with io.open(PATH, encoding="utf-8") as f:
    text = f.read()  # 默认 newline=None ⇒ \r\n 统一成 \n

EDITS = []

# ── 1. 头部引读：件数改口＋补记指向 ──
EDITS.append((
    "P0×6 / P1×18 / P2×13，共 37 条",
    "P0×6 / P1×17（另 1 条撤项）/ P2×14，共 37 条",
))
EDITS.append((
    "与《证道-全量审计单-2026-10-23》逐条去重；抱朴 53 个文件的专项全扫单列一节，见 §3.4）。",
    "与《证道-全量审计单-2026-10-23》逐条去重；抱朴 53 个文件的专项全扫单列一节，见 §3.4）。"
    "**2026-10-24 补**：用户拍板三项（交通灯／Switch／圆角）＋全案覆盖复核，新增 §七。",
))
EDITS.append((
    "> 基线：HEAD `d3f2618b`，工作树 2 处未提交改动（`values-en/strings.xml`、`TestAssume.kt`）。",
    "> 基线：HEAD `d3f2618b`，工作树 2 处未提交改动（`values-en/strings.xml`、`TestAssume.kt`）。"
    "2026-10-24 复核时 HEAD 未变（`git log --oneline -3` 仍 d3f2618b／9041a88f／cb1d684a）。",
))

# ── 2. §〇 结论：补记块 ──
EDITS.append((
    "**建议动工顺序**：先修文档（半天）",
    "**2026-10-24 补记（用户拍板，标准＝方便手机）**：\n"
    "- **终端三交通灯维持 30dp**——用户原话「现在挺和谐的」。git 实测历史是 30→48→40→**30**（abf7c895 标题居中改版），注释里「48→40dp」是陈旧叙事 ⇒ **只修注释不改尺寸**（延续 2026-10-16「观感优先于 M3 48dp」先例）。\n"
    "- **Switch 关态采 A 案**：关态滑块白→Ink3 `#8A857A`（深色 `DarkInk3`），形状一行不动（137 批纪律）。\n"
    "- **圆角 33 处裁决毕**：21 处零视觉变化换令牌、3 处小幅换令牌、10 处维持定稿 ⇒ 白名单 29→10 处、报红 5→0。全表见 §六-4 与 §七。\n"
    "\n"
    "**建议动工顺序**：先修文档（半天）",
))

# ── 3. §2.2 行 #5 证据升级＋行 #6 挂裁决 ──
EDITS.append((
    "| 5 | 彻底清除移右侧主位朱色（HomeScreen.kt:1380-1397） | 总案自陈 0 行代码；现仍取消侧 ③ | **未修** |",
    "| 5 | 彻底清除移右侧主位朱色（HomeScreen.kt:1380-1397） | 总案自陈 0 行代码；实读 `:1382-1397`：彻底清除在 `dismissButton` 行内（朱色 `zhengdaoErrorText`＋`purgeArmed` 两击），确实不在 `confirmButton` 主位 ②（2026-10-24 由 ③ 升 ②） | **未修** |",
))
EDITS.append((
    "| 6 | 三圆点热区 30→48dp（activity_main.xml:53/64/75） | 三处**仍 30dp**；注释却写「48→40dp」，**注释与代码互相矛盾**（注释说 40、代码是 30、纪律要 48，三方三个数）② | **未修**（附送注释谎言一条） |",
    "| 6 | 三圆点热区 30→48dp（activity_main.xml:53/64/75） | 三处**仍 30dp**；注释却写「48→40dp」，**注释与代码互相矛盾**（注释说 40、代码是 30、纪律要 48，三方三个数）② | **未修**——但 2026-10-24 用户裁决**维持 30dp**，只修注释（真实历史已查明，见 P2-2 与 §7.1） |",
))

# ── 4. §3.2 / §3.3 小标题件数 ──
EDITS.append((
    "### 3.2 P1 · 结构 / 数据 / 可达性（12 件）",
    "### 3.2 P1 · 结构 / 数据 / 可达性（17 件，另 P1-2 已撤项）",
))
EDITS.append((
    "### 3.3 P2 · 热区 / 收编 / 死代码（8 件）",
    "### 3.3 P2 · 热区 / 收编 / 死代码（14 件）",
))

# ── 5. P1-11 拍板 ──
EDITS.append((
    "| 低视力用户分不出开关是开是关 | 二选一，待拍板（同圆角白名单纪律）：**A（推荐，只换色不动形状）** 关态滑块白→`#8A857A`（Ink3，对纸底 3.34:1 过 3:1）；B 关态轨道加 1dp `#8A857A` 边（动形状，需用户裁决）。`ContrastGuardTest` 无此判定，不会红 |",
    "| 低视力用户分不出开关是开是关 | **已拍板（2026-10-24 星衡，标准＝方便手机）：A 案**——`uncheckedThumbColor` 白→`if (dark) DarkInk3 else Ink3`（`Color.kt:35/76` 两值早已在，与同文件轨道两态写法一致）。实算对比度：关态滑块 vs 轨道 1.44:1 → **2.56:1**（浅色）／**3.21:1**（深色）；vs 纸底 3.34:1，过非文字 3:1 ①。**不动的**：形状一行不动（137 批「iOS 形状语言保留」——圆滑块改灰仍是圆，左右位置＋黛青轨道仍是主状态线索）；B 案（关态轨道加 1dp `#8A857A` 边）否——动形状与 137 批纪律自相矛盾。`ContrastGuardTest` 无此判定、全仓无测试钉 thumb 色（grep `uncheckedThumb|thumbColor` 零命中）⇒ 不会红 |",
))

# ── 6. P2-1 修法补实数 ──
EDITS.append((
    "| 复制/粘贴两钮 `minWidth` 提到 48dp（宽度不够就只保高度 48dp，标题让位——终稿顶栏已重排，冲突时按总案 22:30 尺寸表） |",
    "| 复制/粘贴两钮**高度**补到 48dp（`paddingTop/Bottom` 12→14dp；宽度仍 36dp 起，标题让位——冲突时按总案 22:30 尺寸表）。**注释同时修**：112-116 行自称「约 40dp」「受 42dp 工具条高度所限」两句都陈旧——实测约 36×42dp、工具条已是 48dp（`:26-27`）② |",
))

# ── 7. P2-2 改裁决 ──
EDITS.append((
    "| P2-2 | 终端三圆点 30dp（批一 #6 未修） | `activity_main.xml:53/64/75` ② | 扩 48dp；**同步删/改 38-50 与 93-97 两段谎言注释**（声称 48→40 实际 30） |",
    "| P2-2 | 终端三圆点 30dp（批一 #6 未修） | `activity_main.xml:53/64/75`；点击处理已定位：`TerminalActivity.kt:217-249`（红＝关页／黄＝收分屏／绿＝分屏，长按＝帮助 `:423-430`）；「左边缘右滑等价物」属实（`:205` `installEdgeSwipeToClose`，帮助文案 `:427` 也这么写）② | 原案：扩 48dp | **已裁决（2026-10-24 用户观感）**：「终端的那三个交通灯现在挺和谐的」⇒ **维持 30dp 不动**（延续 2026-10-16「观感优先于 M3 48dp」先例；abf7c895 正是把尺寸回 30dp 换标题居中的既定结果）。**只修注释谎言**：38-50 行注释以「48→40dp」为现值，git 实测历史为 30→48（46ea98d1）→40（846f9a6e）→**30（abf7c895，2026-10-18 标题真正居中：热区回 30dp＋标题右侧补 `paddingEnd=32dp`）** ⇒ 三段改写成一条时间线；93-97 行「B-补-20 后圆点热区 104 → 158dp」改实数（3×30＋2＋2＋6＝100dp）；「红点是整页唯一的关闭出口」改「按钮位唯一的关闭出口（左边缘右滑等价）」 |",
))

# ── 8. P2-6 附裁决结果 ──
EDITS.append((
    "| 逐个换令牌（24→rSheet、20→rSheet 或 rDialog 视语义、8→rChip、6→rBadge）或进白名单并写清理由；收编后门禁须回 rc=0 |",
    "| 逐个换令牌（24→rSheet、8→rChip、6→rBadge）或进白名单并写清理由；收编后门禁须回 rc=0。**2026-10-24 拍板**：ChannelSheet:275→rSheet24、PlanArea:453→rChip8、ProcessBar:566→rBadge6（均零视觉变化）；McpSheet:44/NovelSheet:54 两个**小对话框** 20dp→rCard16（Δ4dp 近乎不可感知，不添新白名单）——全表见 §六-4 |",
))

# ── 9. P2 表新增 P2-14 ──
EDITS.append((
    "| P2-13 | **15 处浮层/区块态非 Saveable**（抱朴专项新扫，总案批一 #10 的精确面）",
    "| P2-14 | 复制/粘贴注释陈旧数据（2026-10-24 复核新发现） | `activity_main.xml:112-116`：「约 40dp」「受 42dp 工具条高度所限」——实测约 36×42dp、工具条 48dp ② | 施工方照注释里的假数排布，会把「高度还能不能再提」误判成不能 | 随 P2-1 同一笔修（见 P2-1 行）；单列一行是为了让「注释谎言」在清单里可数、可验收 |\n"
    "| P2-13 | **15 处浮层/区块态非 Saveable**（抱朴专项新扫，总案批一 #10 的精确面）",
))

# ── 10. §四 批二：P1-11 去「待拍板」 ──
EDITS.append((
    "P1-11 Switch 关态（待拍板 A/B）",
    "P1-11 Switch 关态（已拍板 A 案，§七）",
))

# ── 11. §五 门禁表：圆角行 ──
EDITS.append((
    "| `tools/check-radius-tokens.py`（现 rc=1，5 红） | P2-6 收编后必须 rc=0；换令牌会改视觉值的，先白名单登记＋写明理由，等拍板 |",
    "| `tools/check-radius-tokens.py`（现 rc=1，5 红） | 按 §六-4 拍板施工：34 处（29 白名单＋5 报红）裁毕 ⇒ rc=0、白名单 18 行→8 行（放行处数 29→10）；`tools/radius-whitelist.txt` 同笔更新（删 10 行、改 8 行理由），过期条目门禁会打「过期」⇒ 当场删 |",
))

# ── 12. §六 #4 圆角：待拍板 → 已拍板全表 ──
EDITS.append((
    "4. **圆角白名单 12 处待用户拍板**（门禁已放行，属「换令牌会改视觉值」类）：① 弹窗层 Surface 用 14（rDialog）还是 16（rCard）——ArtifactShelf:339、SkillCard:250/839/949、SkillCategory:342、TrashBin:412 五处同此问题；② 大浮层 28dp 要不要立新档——BackupScreen:130、ExtractSheet:127；③ 图片缩略格 8dp 认 rChip 还是新档——MessageList:788/808/822（派工单第 30 批写明 8dp）；④ 计划卡内产物条 12dp 算卡层还是控件层——MessageList:356；⑤ ArtifactViewer:146 的 20dp 介于 rCard 与 rSheet 之间。",
    "4. **圆角 33 处 · 已拍板（2026-10-24 星衡，标准＝方便手机）**：门禁现状＝白名单放行 29 处＋报红 5 处（rc=1）。裁决总原则＝**「值撞 8 档的一律换令牌（零视觉变化优先）；自定义值按浮层尺寸分档；玄览壳 guard 钉死不动」**。施工后白名单 29→10 处、报红 5→0（rc=0）。\n"
    "   - **A·换令牌·零视觉变化（21 处）**：Dialog 内 Surface 16dp×6（`BaopuArtifactShelf.kt:339`、`BaopuSkillCard.kt:250/839/949`、`BaopuSkillCategory.kt:342`、`BaopuTrashBin.kt:412`）→ rCard；`HomeScreen.kt:506/508/906/944/1029` 16dp→rCard、`:1319/1407` 14dp→rDialog、`:1438` 8dp→rChip（68 批 `ab3d7089` 已入 HEAD，「在飞」理由失效；白名单理由里的旧行号 437/439/824/862/942 已漂移，按门禁当前输出逐行换）；`BaopuMessageList.kt:788/808/822` 图片缩略格 8dp→rChip（`Radius.kt` 定义补「缩略图/媒体格」）、`:356` 产物条 12dp→rButton（定义补「可点小条目」）；报红 3 处 `BaopuChannelSheet.kt:275` 24dp→rSheet24、`BaopuPlanArea.kt:453` 8dp→rChip8、`BaopuProcessBar.kt:566` 6dp→rBadge6。\n"
    "   - **B·换令牌·有小幅视觉变化（3 处）**：`HomeScreen.kt:872` 系统信息块 10dp→rCard16（Δ6dp——该行注释自称「与卡片同圆角语系」，代码却是 10dp，此次让代码兑现注释）；`BaopuMcpSheet.kt:44`／`BaopuNovelSheet.kt:54` 两个**小对话框** 20dp→rCard16（Δ4dp，小框上近乎不可感知；消 2 处报红且不添新白名单）。\n"
    "   - **C·维持定稿·只改白名单理由（10 处）**：`BaopuBackupScreen.kt:130`／`BaopuExtractSheet.kt:127` 28dp（24dp 内边距的中型对话框，既有视觉不动，不并 rSheet24）；`BaopuArtifactViewer.kt:146` 20dp（fillMaxHeight 0.88f 全屏大查看器的中间档）；`WelcomeScreen.kt:74` 14dp（一次性欢迎页三大卡）；玄览壳 6 处（`BrowserBottomBar.kt:194/249/391`、`BrowserPreviewBar.kt:149`、`BrowserTabStrip.kt:93`、`BrowserStartPage.kt:111`——`BrowserBottomBarGuardTest` 以源码字面量钉死，动它＝同时改守卫，属争议面）。\n"
    "   - **配套动作**：`tools/radius-whitelist.txt` 同笔删 10 行、改 8 行理由；`Radius.kt` 两处定义补语；门禁复跑须 rc=0；换令牌后 `ContrastGuardTest` 无连带（它只管色不圆角）。",
))

# ── 13. §六 #1 真机未验清单随裁决更新 ──
EDITS.append((
    "1. **真机未验 7 项**：圆点热区、Switch 关态、读屏标签、深色孤岛、安装恢复、导出 catch、计划胶囊——都需人在 Magic5 Pro 上点。按协作纪律，需要现场配合的活不默认硬推，等用户排期。",
    "1. **真机未验 6 项**：读屏标签、深色孤岛、安装恢复、导出 catch、计划胶囊、Switch 关态（A 案上机效果）——都需人在 Magic5 Pro 上点。按协作纪律，需要现场配合的活不默认硬推，等用户排期。圆点热区 2026-10-24 已裁决**维持 30dp**，从「待决」移出（转「施工后无新验」）。",
))

# ── 14. §3.4 抱朴大表：圆角各处挂裁决 ──
EDITS.append((
    "| 通道浮层 | 24dp 圆角报红（`:275`）｜initialStep（`:95/142`）｜公司行加徽标 | P2/P1 | P2-6＋旧批二 #3/#15 |",
    "| 通道浮层 | 24dp 圆角报红（`:275`，已拍板→rSheet24）｜initialStep（`:95/142`）｜公司行加徽标 | P2/P1 | P2-6＋旧批二 #3/#15 |",
))
EDITS.append((
    "| 计划区 | `PlanPalette` 深色 3 值 off-token（`:147`）｜8dp 圆角报红（`:453`）｜",
    "| 计划区 | `PlanPalette` 深色 3 值 off-token（`:147`）｜8dp 圆角报红（`:453`，已拍板→rChip8）｜",
))
EDITS.append((
    "| 过程条 | 6dp 圆角报红（`:566`）｜",
    "| 过程条 | 6dp 圆角报红（`:566`，已拍板→rBadge6）｜",
))
EDITS.append((
    "| 产物架 | 两击确认**已修**（不用动）｜16dp 圆角白名单（`:339`，待「弹窗层 14 还是 16」拍板） | — | §2.2 已修 |",
    "| 产物架 | 两击确认**已修**（不用动）｜16dp 圆角白名单（`:339`，已拍板→rCard） | — | §2.2 已修 |",
))
EDITS.append((
    "| 回收站 | 两击确认齐全（`BaopuTrashBin.kt:524-543`，**已修**）｜列目录主线程 IO（`:311`）｜16dp 圆角白名单（`:412`）｜空态仍可点（cosmetic） | P1/— | P1-16＋§一改口 2 |",
    "| 回收站 | 两击确认齐全（`BaopuTrashBin.kt:524-543`，**已修**）｜列目录主线程 IO（`:311`）｜16dp 圆角白名单（`:412`，已拍板→rCard）｜空态仍可点（cosmetic） | P1/— | P1-16＋§一改口 2 |",
))
EDITS.append((
    "| 提取浮层 | 28dp 圆角白名单（`BaopuExtractSheet.kt:127`，等「大浮层」口径）｜",
    "| 提取浮层 | 28dp 圆角白名单（`BaopuExtractSheet.kt:127`，已拍板维持 28dp 定稿）｜",
))
EDITS.append((
    "| MCP 浮层 | 20dp 圆角报红（`BaopuMcpSheet.kt:44`） | P2 | P2-6 |",
    "| MCP 浮层 | 20dp 圆角报红（`BaopuMcpSheet.kt:44`，已拍板→rCard16） | P2 | P2-6 |",
))
EDITS.append((
    "| 小说浮层 | 20dp 圆角报红（`:54`）｜主线程读全项目（`:51`） | P2/P1 | P2-6＋P1-7 |",
    "| 小说浮层 | 20dp 圆角报红（`:54`，已拍板→rCard16）｜主线程读全项目（`:51`） | P2/P1 | P2-6＋P1-7 |",
))
EDITS.append((
    "| 备份页 | 28dp 圆角白名单（`BaopuBackupScreen.kt:130`） | P2 | P2-6 族 |",
    "| 备份页 | 28dp 圆角白名单（`BaopuBackupScreen.kt:130`，已拍板维持 28dp 定稿） | P2 | P2-6 族 |",
))
EDITS.append((
    "｜12/8dp 圆角白名单（`:356/788/808/822`）｜",
    "｜12/8dp 圆角白名单（`:356`→rButton、`:788/808/822`→rChip，已拍板）｜",
))
EDITS.append((
    "｜16dp 圆角白名单×4｜",
    "｜16dp 圆角白名单×4（已拍板→rCard）｜",
))
EDITS.append((
    "ArtifactViewer 20dp 白名单（`:146`） | — | 本轮新扫（干净） |",
    "ArtifactViewer 20dp 白名单（`:146`，已拍板维持 20dp 定稿） | — | 本轮新扫（干净） |",
))

# ── 15. 新增 §七 ＋ 页脚更新 ──
SEC7 = """
---

## 七、2026-10-24 用户拍板与全案复核记录

> 触发：用户三句话——「终端的那三个交通灯现在挺和谐的」（观感异议）；「拍板的事你觉得哪种方便手机就按你的来」（Switch A/B 与圆角五组交权，标准＝方便手机）；「你最好都搜一遍再看看方案有没有需要修改的地方」（全案复核）。以下每条都带回查证据，结论已同步写进 P1-11／P2-2／§六-4／§3.4。

### 7.1 三项拍板（结论与理由）

| 事项 | 裁决 | 理由（回查实录） |
|---|---|---|
| 终端三交通灯 | **维持 30dp**，只修注释 | 用户观感「挺和谐的」。git 历史实测：30→48（46ea98d1）→40（846f9a6e）→**30（abf7c895，2026-10-18「标题真正居中」：热区回 30dp＋标题补 `paddingEnd=32dp`）**。当前 30dp 是既定设计结果、不是漏改；M3 48dp 与用户观感冲突时按 2026-10-16 先例「观感优先」 |
| Switch 关态 | **A 案**（关态滑块白→Ink3，形状不动） | 「方便手机」＝每天要拨的开关，只换色、零形状风险；B 案（轨道加 1dp 边）与 137 批「形状一行不动」冲突。深色态用 `DarkInk3`（`Color.kt:76` 已有），不必新色 |
| 圆角 33 处 | **令牌优先／定稿保留／玄览不动**（§六-4 全表） | 21 处零视觉变化清债；3 处小幅变化（Δ4~6dp）清报红；10 处自定义值按「浮层尺寸分档」保留（全屏大查看器 20dp／中对话框 28dp／欢迎页 14dp／玄览壳 guard 钉死）。裁完全部门禁 rc=0 |

### 7.2 覆盖复核（「都搜一遍」的账）

把总案与 v2 清单**没有逐点核过**的界面全部补扫：

| 界面 | 复核结论 |
|---|---|
| `PermissionSetupScreen.kt`（552 行，全读） | **干净**。单一判定源（`storageGranted` 委托 `ProotLauncher`）、ON_RESUME＋launcher 回执双重重读、`commit` 同步写盘、返回键也写引导标记——设计良好。唯一残留：`:302-304` `permLauncher` 无调用点（历史契约，注释已自陈理由，不列问题） |
| `KeepaliveLogActivity.kt`（304 行，全读） | **干净**。ZhengdaoTheme、读写全走 `Dispatchers.IO`、清空有 AlertDialog 确认（红字带条数）、顶栏 `defaultMinSize 48dp`。与 ImeRegression 的「主题破例」对比写在 KDoc，有据 |
| `ImeRegressionActivity.kt`（597 行，结构扫＋精读前 220 行） | **开发者自查工具页**：裸 `MaterialTheme`（默认紫）是 `KeepaliveLogActivity` KDoc 记录的**有意破例**；全文硬编码中文、日志 10sp 属开发工具口径。不列用户问题；若哪天要从设置页开放给用户，须先换 ZhengdaoTheme＋strings 收编（另立专项） |
| `WelcomeScreen.kt`（138 行，全读） | 只有 14dp 圆角一处（已入 §六-4 裁决＝维持定稿）；三步卡、主按钮 56dp、跳过次级按钮均合规 |
| 底栏 4 Tab（`MainActivity.kt:889-962`） | **干净**。56dp 高、发丝线分隔、10sp 标签在字阶（34/22/17/15/13/10）上、双击滚顶走 300ms 窗口判定；选中态＝**颜色＋图标缩放**双线索（`tabIconScale`，`:1152`）⇒ 不是「选中仅靠颜色」，不用补 P2-10 |
| 批一 #5「彻底清除移主位」 | 证据级 ③→**②**：`HomeScreen.kt:1382-1397` 实读——彻底清除在 `dismissButton` 行内（朱色 `zhengdaoErrorText`＋`purgeArmed` 两击），确实不在 `confirmButton` 主位，「未修」判定成立 |
| 对话框按钮相邻无间距（初判「新问题」） | **撤项**：`HomeScreen.kt:1383`／`KeepaliveLogActivity.kt:246` 等 5 处 `Row { TextButton… }` 无 `spacedBy`——但 M3 TextButton 自带内容内边距，间距由 padding 出，这正是 AlertDialog 官方排法；不是缺陷 |

### 7.3 复核中改掉的文档错（不影响施工方向）

1. **§3.2/§3.3 小标题件数陈旧**：写「P1 12 件／P2 8 件」，实际表里是 P1 17 件（另 P1-2 撤项）／P2 13 件——P1-13~18 与 P2-9~13 是后插入的，标题没跟上。本档已改；P2 另补 1 条新发现（P2-14）⇒ P0×6 / P1×17 / P2×14，合计仍 37。
2. **「注释谎言」从一句带过升级为可施工规格**：两条假注释的真实历史（git 提交号＋现行实数）写进了 P2-1/P2-2，施工方不用再考古。
3. **§五 圆角门禁行目标值更新**：收编后 rc=0、白名单 18 行→8 行（放行处数 29→10）。
4. **§六 #1 真机未验清单**：圆点热区（已裁决维持）移出「待决」，Switch 关态转「A 案上机效果」。
"""
EDITS.append((
    "\n---\n\n*证据三级说明：①＝真跑/门禁实跑",
    SEC7 + "\n---\n\n*证据三级说明：①＝真跑/门禁实跑",
))
EDITS.append((
    "抱朴 53 文件专项全扫（含 27 项子代理发现的自核）见 §3.4。*",
    "抱朴 53 文件专项全扫（含 27 项子代理发现的自核）见 §3.4。2026-10-24 用户拍板与全案覆盖复核见 §七。*",
))

# ── 执行 ──
fails = []
for i, (old, new) in enumerate(EDITS, 1):
    n = text.count(old)
    if n != 1:
        fails.append((i, n, old[:60]))
        continue
    text = text.replace(old, new)

if fails:
    for i, n, frag in fails:
        print("FAIL #%d count=%d :: %s" % (i, n, frag))
    sys.exit(1)

with io.open(PATH, "w", encoding="utf-8", newline="\r\n") as f:
    f.write(text)
print("OK: %d edits applied" % len(EDITS))
