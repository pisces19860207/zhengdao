# v2.0.17 验收 · hermes 扩展一律保留 ＋ 提版发版（2026-10-11）

## 0. 用户交办（逐字）

> 这两个做了就对账、清账、销账。今天就彻底结束了。

（上文「这两个」= ① 把 `cua-driver` 也划入「保留」；② 提版发 v2.0.17。）

前一条交办（同源，逐字）：

> hermes 自己下载的那个浏览器工具也是要保留的，虽然没有也可以用，但是有的话hermes是可以用的，我试过了。

## 1. 本次改了什么

| # | 改动 | 文件 | 判据 |
| --- | --- | --- | --- |
| 1 | `cua-driver` 划入 `PINNED_TOOL_NAMES`，`OPTIONAL_TOOL_NAMES` 收窄为**空表** | `app/src/main/java/com/example/zhengdao/terminal/StorageAudit.kt` | `removable = kind == ToolKind.OPTIONAL` ⇒ 空表下没有任何工具目录可清 |
| 2 | 清理入口注释改写：空表 ⇒ `optMb` 恒 0 ⇒ `if (optMb > 0)` 这一入口不渲染（代码与弹窗文案保留） | `app/src/main/java/com/example/zhengdao/ui/SettingsStorageActivity.kt` | 真机 dump 里「清理可选工具」字符串不存在 |
| 3 | 四条用例按「`~/.hermes/tools` 里的东西默认一个都不许删」重写 | `app/src/test/java/com/example/zhengdao/terminal/StorageAuditTest.kt` | `assertTrue(OPTIONAL_TOOL_NAMES.isEmpty())`、`assertEquals(0, items.count { it.removable })`、空表下清理 `count==0 / freed==0L` 且七个目录全在 |
| 4 | 提版：`appVersionName = "2.0.17"`、`versionCode = 33` | `app/build.gradle.kts:166` / `:199` | `dumpsys package` = `versionName=2.0.17` / `versionCode=33` |
| 5 | 新建公开正文 | `.github/release-notes/v2.0.17.md` | `build.yml` 的 release job 取 `<tag>.md`，缺了会打 `::warning::`（E-068） |
| 6 | 台账 / 错误台账 / README 口径 | `docs/FEATURE-LEDGER.md` §A #55、`docs/ERRATA.md` E-115 补记、`README.md:130`、`README.en.md:201-204` | 口径一致：工具组无可清项 |

## 2. 真机走查（PGT-AN10 / Android 16 / MagicOS）

装机：`adb -s AD3J023824001723 install -r app\build\outputs\apk\release\zhengdao-2.0.17-release.apk`
= `Success`（**未卸载**，`files/rootfs`、`files/home`、prefs 全保留）。

| 页面 | 读数 |
| --- | --- |
| `dumpsys package com.example.zhengdao` | `versionName=2.0.17` / `versionCode=33` / `minSdk=36 targetSdk=28` / `lastUpdateTime=2026-10-11 23:33:54` |
| 设置 → **关于** | `版本 2.0.17 (versionCode 33)`、`作者 小信多多`、`版本来源 官方发布 ✓`、`许可证 保留所有权利（第一方代码）`、`开源许可 随包正文` |
| 设置 → **存储与缓存** | 存储占用 `rootfs 772 MB` / `home 3530 MB` / `cache 0 MB`；`Agent 包缓存 → 太极缓存 52 MB · 可清`（缓存组）；`hermes 工具` 组：`chromium-1208` 600 MB · **保留**、`ffmpeg-9.0.1-linux-arm64` 328 MB · 保留、`python-3.14.7…` 247 MB · 保留、`node-26.7.0…` 211 MB · 保留、**`cua-driver-0.21.0-linux-arm64` 80 MB · 保留（本版新改）**、`uv-0.12.3…` 45 MB · 保留、`npm-12.0.2…` 11 MB · 保留、`agent-browser-0.26.0-linux-arm64` 9 MB · 保留 |

**入口消失的判定**：整页 `uiautomator dump` 里含「可清」的字符串**只有** `太极缓存 52 MB · 可清` 一条，
`清理可选工具` 完全不存在 ⇒ 空表隐藏入口的行为与设计一致。

### 没验的（如实记录）

- **没点**「清理可选工具」（本版已无此入口）与设置 → 环境与更新里的「清理更新标记」「在终端里修复」
  —— 点下去会真动用户 guest 状态（删标记文件 / 删工具目录），按铁律只读走查，改由单测钉住。
- **没验**日后把 `OPTIONAL_TOOL_NAMES` 放开时入口能否正常回来（代码留着，但没做实验）。
- 其余 10 项环境体检本次未逐项复核（本版没动它们；HOME 卡的 11/11 已在 #53 那版验过）。

## 3. 构建 / 单测 / 签名

| 项 | 结果 |
| --- | --- |
| `python tools\check-native-so.py` | `[通过]`，退出 0 |
| `.\gradlew.bat --offline testDebugUnitTest`（`JAVA_HOME=D:\Program Files\Android\Android Studio\jbr`） | BUILD SUCCESSFUL in 32s；**68 suite / 622 例 / 0 失败 / 0 错误 / 1 skipped** |
| `.\gradlew.bat --offline assembleRelease`（四个 `ZHENGDAO_KEY*` 齐备） | BUILD SUCCESSFUL in 2m 6s ⇒ `zhengdao-2.0.17-release.apk` **8,318,196 B**（2026-10-11 23:33:34） |
| `apksigner verify --print-certs` | 退出 0；`CN=Zhengdao, O=Zhengdao, C=CN`；SHA-256 `b93169f211c6b3d9fdae36b2c614c23bef28eae3582e4d8bca0657c27ac86ec2` |
| 本机 APK 文件 SHA-256 | `26aa8894b898fc153cb19ad5c99705508ef17085b279d2afe4c535188f06d4`（CI 会另建同版本包，字节差 ~12 B 属 E-081） |

> ⚠️ **签名坑（本次踩实）**：漏设 `ZHENGDAO_KEYSTORE_FILE` / `_PASSWORD` / `_KEY_ALIAS` / `_KEY_PASSWORD`
> 时 `assembleRelease` **照样 BUILD SUCCESSFUL**，但产物是 **debug 签名**
> （`apksigner` 报 `CN=Android Debug`、digest `44e2fe86…`），装机会被系统拒。
> **装 Release 包前先 `apksigner verify --print-certs` 核证书。**

## 4. 发版与余账

- tag `v2.0.17` 推送后由 `build.yml` 的 release job（`if: startsWith(github.ref, 'refs/tags/v')`）
  建正式 Release；滚动 `latest` 会因推 `main` 提前刷新（**滚动通道 ≠ 发版**，E-081 口径）。
- 待补：Release 6 项资产的公开 `digest` 入 `PROVENANCE.md`「官方发布资产摘要」节与
  `THIRD-PARTY-LICENSES.md`（按 v2.0.15 先例；rootfs 五项应与前几版逐字节相同）。
- 余账：今日收工的对账 / 清账 / 销账记在 `docs/FEATURE-LEDGER.md` §B 的
  「2026-10-11 第四轮（收工）对账 · 清账 · 销账」引用块。
