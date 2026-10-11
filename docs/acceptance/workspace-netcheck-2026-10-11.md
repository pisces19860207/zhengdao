# B-补-1「仅私有模式」整条分支不可达 + B-补-2 网络预检往会话里打字 —— 真机验收（2026-10-11）

**分支**：`fix/workspace-netcheck`（基线 `d0015bf`）
**真机**：`AD3J023824001723`（Android 16 / arm64-v8a），装的是 debug 包 `zhengdao-2.0.12-debug.apk`（43,458,008 B / 14:53 构建）
**构建**：`gradlew.bat --offline :app:testDebugUnitTest :app:assembleDebug` = **BUILD SUCCESSFUL in 1m 57s**（含新增 `WorkspacePathTest` 6 例）；`adb install -r` = Success

> 两条都来自 2026-10-11 的「还有没有紧急 BUG」盘点（台账 §B-补-1 / §B-补-2），用户当场拍板「两条都修」。判据、证据链与未验项同时写进 `docs/ERRATA.md` 的 **E-094 / E-095**。

---

## 1. 改了什么

| 编号 | 改动 | 文件 |
|---|---|---|
| **B-补-1** | `isShared()` 的判据从「路径前缀 `startsWith("/storage/emulated/0")`」改成「宿主目录 ≠ App 外部目录」，并抽成纯函数 `isSharedPath(hostPath, privatePath)`；`isPrivate()` 保持 `= !isShared()`（现在才第一次可能为真） | `app/src/main/java/com/example/zhengdao/terminal/Workspace.kt`（`:67-76`） |
| **B-补-1**（测试） | 新增 6 例：仅私有档判非共享（回归正身）、默认档判共享、自定义档判共享、无存储权限回落私有判非共享、尾部斜杠不敏感、把 App 外部目录的**子目录**选成自定义档仍判共享（已知边界） | 新增 `app/src/test/java/com/example/zhengdao/terminal/WorkspacePathTest.kt` |
| **B-补-1**（附带） | 仅私有档下，交付给终端 Agent 的「文件地图」不再写「/sdcard —— 共享存储整体可读可写」与「先 ls /workspace 和 /sdcard/Download」，改成明说本档没有挂载共享存储。**只改与模式相关的两行**，其余文案一字未动 | `app/src/main/java/com/example/zhengdao/terminal/KnowledgeBase.kt`（`dispatchAgentsText()`） |
| **B-补-2** | 网络预检失败不再 `SessionManager.write("echo …")` 往会话里打字，改成终端页横幅 `showBanner(...)`（并加「安装进行中不刷横幅」守卫） | `app/src/main/java/com/example/zhengdao/TerminalActivity.kt`（`:593-613`） |

---

## 2. 真机验收（逐条）

| 项 | 断言 | 结果 | 证据 |
|---|---|---|---|
| **B-补-1**（仅私有档） | 路径与文案都按「应用专属目录、随卸载删除」走 | ✅ | 把 `shared_prefs/zhengdao-settings.xml` 的 `workspace_mode` 临时改成 `private` 后重启 App ⇒ 设置页「工作区」卡：路径 `/storage/emulated/0/Android/data/com.example.zhengdao/files/workspace`，文案 **「应用专属目录：卸载证道时随之删除（在意痕迹时用）。」**（截图 `01-workspace-private-mode.webp`；`uiautomator` 文本节点逐字核过） |
| **B-补-1**（共享档·反向） | 默认/自定义档的行为没被改坏 | ✅ | 还原 `custom` 后同一张卡：路径 `/storage/emulated/0/Download/男性`，文案 **「手机文件管理器直接可见、可自由删除；卸载证道后此文件夹仍会保留——产出不丢。」**（截图 `02-workspace-shared-mode.webp`）—— **用户的设置已还原**（`workspace_path` / `workspace_mode` 与改动前逐字一致） |
| **B-补-1**（单测） | 四种档位判据都对 | ✅ | `WorkspacePathTest` 6 例全绿；整体 `:app:testDebugUnitTest` BUILD SUCCESSFUL |
| **B-补-2** | 不再往会话里写字节；失败时横幅**真的**会弹 | ✅ **真机（2026-10-11 补验）** | `TerminalActivity.kt:593-613` 已无 `SessionManager.write`；**断网复现**（`svc wifi disable` + `svc data disable`，设备侧 `ping registry.npmjs.org` 变 `unknown host` → `am force-stop` → `am start … --ez open_terminal true`）：终端页顶部黄色横幅逐字 **「网络不通：请确认代理已开启，并在代理 App 的「分应用代理」里勾选「证道」」**（截图 `03-net-banner-network-down.webp`），同一屏会话区只有三行启动提示 + `root@localhost:~#` ⇒ **零字节注入**（旧行为会多出 `echo '[网络] ⚠️ 检测失败…'`）；点一下横幅 ⇒ 收起（`04-net-banner-dismissed.webp`）；`svc wifi/data enable` 后 `ping` 立刻恢复。日志逐字 `网络预检：registry.npmjs.org 不通 —— 终端页横幅提示（不往会话里打字）`（15:59:35，一次冷启动 3 行 —— 观察项，非缺陷） |
| **B-补-1**（仅私有档·会话内） | 重开会话后 guest 里不再 bind `/sdcard`、资料库不预置骨架 | ✅ **2026-10-11 补验** | guest 内 `mount` 只剩 `…/files/workspace on /workspace` + `Download/证道 on /opt/zhengdao`（**没有** `/sdcard`、`/storage/emulated/0`、pip/npm 缓存）⇒ `/sdcard` 目录在但**空**，`ls /storage/emulated/0` 无输出；`/workspace` 只有 `.ignore`（**没有**「资料库」骨架）；会话日志逐字 `工作区: /workspace <- …/files/workspace（仅私有，随卸载删除）`；切回 `custom` 后重新 bind + 文案全回（日志 `（共享存储，卸载保留）`） |

### 2.1 没验的（连理由一起记）

| 项 | 为什么没验 |
|---|---|
| ~~B-补-2 的横幅**真的**会弹~~ | **2026-10-11 已验完**（见第 2 节 B-补-2 行）：断网 → `force-stop` → `--ez open_terminal true` 复现；横幅逐字出现、会话**零字节注入**、点一下可收起、恢复网络后不再报。代价：`force-stop` 会把当时在跑的会话 / Agent 进程一起结束（会话历史在盘上，重开即可），网络与工作区档位已在同一轮还原 |
| 「无存储权限 → 自动回落仅私有」这条 | 需要撤销「所有文件访问」（系统设置里操作），本轮没动系统权限；只有单测覆盖 |

> 已知限制（不是缺陷）：仅私有档的「文件地图」文案（`KnowledgeBase.dispatchAgentsText()` 里改掉的那两行）**只对新装 / 清空 home 生效** —— `terminal/KnowledgeBase.kt:267` 用 `writeOrAppend(File(home, F_AGENTS), …)`（缺 marker 才追加一次，不是 `writeIfChanged`），所以老安装的 guest `/root/AGENTS.md` 不会回填（本轮真机上核到它仍是共享档文案）。要让它更新就删掉 home 里 `AGENTS.md` 的 zhengdao 块，或重装。

### 2.2 改档手法（可复现，也记录了"怎么改回去"）

```powershell
# 读（先备份）
adb exec-out run-as com.example.zhengdao cat shared_prefs/zhengdao-settings.xml > backup.xml
# 改档：private ↔ custom（文件里 "custom"/"private" 各只出现一次）
adb shell am force-stop com.example.zhengdao
adb shell run-as com.example.zhengdao sed -i s/custom/private/ shared_prefs/zhengdao-settings.xml
# 改回去
adb shell run-as com.example.zhengdao sed -i s/private/custom/ shared_prefs/zhengdao-settings.xml
```

改档后必须**重启 App**（`am force-stop` 再拉起）才生效 —— 与界面上的「已切换仅私有，**下次启动会话生效**」是两件事：界面文案立刻变，**挂载**要等下次会话。

---

## 3. 资产

| 文件 | 内容 |
|---|---|
| `assets/workspace-netcheck-2026-10-11/01-workspace-private-mode.webp` | 仅私有档：「工作区」卡路径 = 应用外部目录 + 「应用专属目录：卸载证道时随之删除（在意痕迹时用）。」 |
| `assets/workspace-netcheck-2026-10-11/02-workspace-shared-mode.webp` | 还原成自定义档后：「工作区」卡路径 = `/storage/emulated/0/Download/男性` + 「手机文件管理器直接可见…卸载证道后此文件夹仍会保留——产出不丢。」 |
| `assets/workspace-netcheck-2026-10-11/03-net-banner-network-down.webp` | 断网 + 冷启动进终端：顶部黄色横幅逐字「网络不通：请确认代理已开启，并在代理 App 的「分应用代理」里勾选「证道」」，会话区只有启动提示 + 提示符（**零字节注入**） |
| `assets/workspace-netcheck-2026-10-11/04-net-banner-dismissed.webp` | 点一下横幅之后：横幅消失，会话内容一字未变 |

---

## 4. 收尾

- 两条都不是新设计：B-补-1 让实现回到它自己的 KDoc（0.6 提交 `2b60a44` 写的用户定稿语义），B-补-2 回到 2026-10-05 提交 `ca8825c` 的原始意图（横幅，不注入）。
- 台账同步：`docs/FEATURE-LEDGER.md` §A 新增 #27、§B-补-1 / §B-补-2 两行销账；`docs/ERRATA.md` 新增 E-094 / E-095。
- 没有触碰的东西：用户的 `workspace_path`（`Download/男性`）与 `workspace_mode`（`custom`）已逐字还原；设备上正在跑的 Hermes 会话没被打断；本轮没有升级版本号、没有打 tag（等用户说发版再发）。
