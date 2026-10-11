# 验收：iOS 化 UI 皮肤（`feat/ui-ios-skin` 合并）

- 日期：2026-10-11
- 设备：AD3J023824001723（Android 16 / arm64-v8a / Adreno 740），**保数据覆盖安装**（同签名 `adb install -r`，未卸载、proot 环境与 tmux 会话原样保留）
- 支线 tip：`52c1ea4`（8 commit）→ 合并提交 **`159c044`**（`--no-ff`），基线 `391f8b6`（v2.0.11 / versionCode 27）
- 改动规模：**16 文件 / +323 / −43**（与交接文档自述一致，`git diff --shortstat 391f8b6 HEAD` 复核）
- 安装包：`app/build/outputs/apk/debug/zhengdao-2.0.11-debug.apk` 43,451,744 B（:app:assembleDebug BUILD SUCCESSFUL）

## 1. 这次改了什么

| 层 | 文件 | 内容 |
| --- | --- | --- |
| 三 Tab 底栏 | `app/src/main/java/com/example/zhengdao/MainActivity.kt` | `NavigationBar(modifier = Modifier.height(56.dp))`、`indicatorColor = Color.Transparent`（去胶囊）、标签改局部 `TextStyle(fontSize = 10.sp, fontWeight = Medium, letterSpacing = 0.12.sp)`；新增 `tabIconScale()`（spring `dampingRatio 0.55 / stiffness 420`，选中 1f / 未选中 0.86f）经 `graphicsLayer` 作用到 `TaijiIcon` / `CaveIcon` / `DingIcon` |
| 终端页 chrome | `app/src/main/res/layout/activity_main.xml`、`app/src/main/res/values/themes.xml` | 工具条 42dp→44dp、标题 13sp→17sp、标题色 → `#FF1C1C1E`、复制/粘贴 → `#FF007AFF`；主题补 `statusBarColor=transparent` + `windowDrawsSystemBarBackgrounds` + `windowLightStatusBar` + `windowSoftInputMode=adjustResize` + `android:alertDialogTheme=@style/Theme.ZhengdaoDialog` |
| 键条深色 | `app/src/main/res/layout/term_keys.xml`、`app/src/main/res/layout-w600dp/term_keys.xml`、`app/src/main/res/drawable/key_button_bg.xml`、`app/src/main/res/values/themes.xml` | 键条底 `#FFFFFFFF → #FF1C1C1E`；按键常态 `#FFF2F2F2 → #FF2C2C2E`、按下 `#FFD8D8DD → #FF48484A`、圆角 6dp→8dp、描边 1dp→0.5dp `#FF3A3A3C`；`TermKeyFlex` 字色 `#FF222222 → #FFE5E5EA`。**17 个按键 id 与顺序零改动** |
| 按压反馈 | 新增 `app/src/main/java/com/example/zhengdao/ui/util/Press.kt` | `Modifier.pressClickable(...)`：按下 scale **0.97** + `spring(DampingRatioNoBouncy, StiffnessMedium)` + `HapticFeedbackType.LongPress` + `ripple()`；13 处 `clickable → pressClickable` |
| 圆角令牌 / 开关 | 新增 `app/src/main/java/com/example/zhengdao/ui/theme/Radius.kt`、`app/src/main/java/com/example/zhengdao/ui/theme/SwitchTokens.kt`、`app/src/main/res/drawable/dialog_bg_ios.xml`；`app/src/main/java/com/example/zhengdao/ui/theme/Color.kt` | 8 个圆角令牌（rSwatch 4 / rBadge 6 / rChip 8 / rPill 10 / rButton 12 / rDialog 14 / rCard 16 / rSheet 24 dp）；`iosSwitchColors()`（`#34C759` 轨道）；对话框底 14dp + 0.5dp `#33000000` 描边 + `#F2F2F7`；新增 `IOSBlueText = #0058B0`（对比度 4.02:1 → 5.07:1） |
| 对话框 | `ui/HomeScreen.kt`、`ui/PluginsScreen.kt`、`ui/SettingsScreen.kt`（Compose）+ 主题 `Theme.ZhengdaoDialog`（框架） | Compose `AlertDialog(shape = RoundedCornerShape(14.dp))`；框架 `android.app.AlertDialog.Builder`（5 处：`MainActivity.kt:90/146/224`、`TerminalActivity.kt:334/1005`）靠主题一处生效 |

## 2. 真机回归（交接 §8 清单）

| # | 项目 | 判据 / 实测 | 结论 |
| --- | --- | --- | --- |
| 1 | 底栏 56dp + 10sp 标签 | `uiautomator` 量底栏容器 `[0,2652][1312,2848]` = **196 px**；本机 1312 px / 375 dp = 3.5 px/dp ⇒ **56.0 dp** ✓；三个标签节点高 46 px，完整落在 栏内（无裁切） | ✅ |
| 2 | 选中态去胶囊 | 改动前（`00-before`）选中 Tab 有浅蓝胶囊；改动后（`01-after`）胶囊消失，只剩 iOS 蓝图标 + 标签 | ✅ |
| 3 | 终端页 chrome（键条深色化） | 工具条实测 **154 px = 44.0 dp**；标题**深色** `#FF1C1C1E`（工具条本身仍是浅色底）、复制/粘贴 `#007AFF`；画布仍是 Catppuccin Mocha（见 `02-terminal-chrome-dark`） | ✅ |
| 4 | **红线**：终端快捷键 17 键 id/顺序不变 | `uiautomator` 逐条列出第一行 `key_esc` / `key_tab` / `key_ctrl` / `key_shift` / `key_interrupt`，第二行 `key_up` / `key_down` / `key_left` / `key_right` / `key_pgup` / `key_pgdn` / `key_home` / `key_end` / `key_pipe` / `key_minus` / `key_slash` / `key_backspace` = **17 个**，id 与顺序与改动前一字不差；单键高 **168 px = 48.0 dp** | ✅ |
| 5 | **红线**：终端页红点仍关闭终端 | 点红点（`btn_close`）后 `dumpsys activity activities` 的 `topResumedActivity` 从 `TerminalActivity` 回到 `com.example.zhengdao/.MainActivity`；重进终端 **tmux 会话仍在**（Hermes Agent v0.21.6 横幅原样），即 `finish()` 不杀会话 | ✅ |
| 6 | Compose 对话框 14dp 圆角 | 「资料库说明」对话框（`SettingsScreen.kt` 的 `资料库说明`）左上角圆弧可见（`04-dialog-compose-14dp`） | ✅ |
| 7 | 框架对话框 14dp 圆角 | 长按任一个圆点 → 「顶部三个圆点」对话框：`#F2F2F7` 底 + 圆角 + 0.5dp 描边（`05-dialog-framework-14dp`） | ✅ |
| 8 | *（附加，不在原清单）* `pressClickable` 的 0.97 缩放 | 按住「环境就绪」卡时抓屏，量卡宽：**1196 px → 1160 px = 0.9699 ≈ 0.97**（y=560 处 1180 → 1142 = 0.968）—— 与代码里的 0.97 吻合（`06-press-normal` / `07-press-holding-097`） | ✅ |

## 3. 这次**没验到**的（据实记）

1. **触感反馈**（`HapticFeedbackType.LongPress`）与弹簧观感 —— 截图无法表征，需要人手感；本轮未验。
2. **iOS 绿开关的颜色** —— 设置页两个开关都在屏幕最右侧（x ≈ 1018–1200），被系统**画中画小窗**遮住（`03-keybar-dark-17keys` 同期截图可见），未拍到。代码层面 `iosSwitchColors()` 共替换 4 处（`ui/SettingsScreen.kt` 2 处、`ui/PluginsScreen.kt` 2 处）。
3. **Tab 图标弹动**（0.86 → 1.0 spring）在静止截图里看不出，需要录屏。

## 4. 顺手发现的四条（2026-10-11 当天处理完毕）

| # | 发现 | 处置 | 证据 |
| --- | --- | --- | --- |
| 1 | 「资料库」卡里两个开关颜色不统一：「让终端 AI 自动读取」是 iOS 绿，「提问时自动附上资料线索」还是 M3 主色 | ✅ **已修**：`app/src/main/java/com/example/zhengdao/ui/SettingsScreen.kt` 第二处补 `colors = iosSwitchColors()`（一行） | 把该开关**打开**后抓屏，探针在 **x 1018–1220** 判定出第二条 iOS 绿轨（**y ≈ 2320–2439**，2993 px），与第一条同色；验完已按回默认「关」——见 `08-switch-two-green` / `10-switch-restored-off` |
| 2 | 两套对话框按钮色不同：Compose 走 iOS 蓝，框架 `android.app.AlertDialog` 跟系统 accent（偏绿） | ✅ **已修**：`app/src/main/res/values/themes.xml` 的 `Theme.ZhengdaoDialog` 加 `android:colorAccent = #FF007AFF` —— 改这一处，5 个框架对话框全跟 | 长按圆点弹对话框后抓屏，按钮区饱和主色 = **hue 218°**（`#007AFF` 系；改前是绿）——见 `09-dialog-framework-iosblue` |
| 3 | 「终端页状态栏浅底深字 ⇒ 深色工具条上方一条亮带」 | ❌ **不是缺陷，是我判断错了**（已更正：`docs/ERRATA.md` **E-091**）。像素实测：状态栏 `(250,250,250)`、工具条 `(255,255,255)` —— **工具条本来就是浅色**（只是标题字色深 `#FF1C1C1E`），根本不存在"深色工具条"；浅色顶栏 + 深色画布/键条是交接文档写明**有意保留**的 PAPER 取舍 | `02-terminal-chrome-dark` 顶部逐行取色 |
| 4 | 交接文档措辞与代码不符：写「分隔线改 0.5dp 发丝线」，实际只改了颜色 | ✅ **已修**：`docs/archive/证道-UI美化动画-交接-2026-10-11.md` 该行改为「分隔线**只改颜色** `#E0E0E0`→`#C6C6C8`（`height` 仍 1dp）」 | 文档改动本身 |

## 5. 复现命令（PC 侧）

```powershell
# 构建（JDK 在 D 盘！写 C 盘会报 JAVA_HOME invalid）
$env:JAVA_HOME = "D:\Program Files\Android\Android Studio\jbr"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
& "C:\Users\guoli\AndroidStudioProjects\zhengdao\gradlew.bat" -p C:\Users\guoli\AndroidStudioProjects\zhengdao :app:assembleDebug --console=plain

# 覆盖安装（同签名 ⇒ 保数据；签名不一致会安全失败，不会清数据）
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb install -r C:\Users\guoli\AndroidStudioProjects\zhengdao\app\build\outputs\apk\debug\zhengdao-2.0.11-debug.apk

# 量底栏 / 键条：dump 后按 bounds 换算（1312 px = 375 dp ⇒ 3.5 px/dp）
& $adb shell uiautomator dump /sdcard/ui.xml

# 量按压缩放：按住卡片的同时抓屏，再比卡宽（脚本 %TEMP%\zd-card-edge.py，Pillow 找白/着色连续段）
Start-Process -FilePath $adb -ArgumentList 'shell','input','swipe','656','486','656','486','2600' -NoNewWindow
Start-Sleep -Milliseconds 1300
& $adb shell screencap -p /sdcard/press.png
```

## 6. 图片资产

`docs/acceptance/assets/ui-ios-skin-2026-10-11/`（Pillow q90 WebP，全部来自真机截图）

| 文件 | 说明 |
| --- | --- |
| `00-before-tabbar.webp` | **改动前**底栏：选中 Tab 有浅蓝胶囊、标签更大 |
| `01-after-tabbar-56dp.webp` | **改动后**底栏：胶囊消失、10sp 标签、整栏 196 px = 56dp |
| `02-terminal-chrome-dark.webp` | 终端页深色 chrome：工具条 44dp、复制/粘贴 iOS 蓝；顶部亮带＝透明状态栏 + 浅色图标（问题 3） |
| `03-keybar-dark-17keys.webp` | 键条深色化后的完整两行（17 键），`^C` 保持红底语义色 |
| `04-dialog-compose-14dp.webp` | Compose 对话框：「资料库说明」，左上 14dp 圆角 |
| `05-dialog-framework-14dp.webp` | 框架对话框：长按圆点弹出的「顶部三个圆点」，14dp 圆角 + `#F2F2F7` 底 |
| `06-press-normal.webp` | 常态：「环境就绪」卡宽 1196 px |
| `07-press-holding-097.webp` | 按住中：同卡 1160 px（= 0.97），且整卡被按下态着色 |
| `08-switch-two-green.webp` | §4-1 修复后：把第二个开关**打开**，「资料库」卡里两条轨道同为 iOS 绿 |
| `09-dialog-framework-iosblue.webp` | §4-2 修复后：框架对话框按钮变 iOS 蓝（饱和主色 hue 218°，改前偏绿） |
| `10-switch-restored-off.webp` | 验证完把开关按回默认「关」：两条轨道同为灰，没在用户设置里留下改动 |
