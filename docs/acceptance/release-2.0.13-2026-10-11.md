# 验收 · v2.0.13 发版 + 换正式签名钥匙 + 手机换包（2026-10-11）

用户当日交办（原话）：「你手机上的包要现在换。线上 v2.0.12 那个 release 的正文没写"先卸载" ，写。
那边的会话弄完了，你接手一下，然后发。把以前的正式版删掉。」

四项逐条：① 手机换包 ② v2.0.12 正文补「先卸载」 ③ 接手另一会话的 `fix/term-title` 并发版 ④ 删掉以前的正式版。

---

## ① 手机换包（AD3J023824001723 / PGT-AN10 / Android 16）

**为什么必须卸载**：正式包从此用独立 release keystore 签名（`docs/ERRATA.md` E-099），
与手机里旧包的 debug 钥匙不同名 ⇒ 直接覆盖会 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`。

**做法与代价控制**（App 私有目录会被清空，先备份）：

| 步骤 | 命令 / 事实 |
|---|---|
| 备份 | `run-as com.example.zhengdao tar -cf files/proot-tmp/zd-appdata.tar -C files home oc` ⇒ **2,759,037,440 B**；`adb exec-out run-as … cat` 取回本机（PowerShell 的 `>` 会按文本重编码，必须走 `cmd /c` 或 `exec-out`） |
| 卸载 | `adb uninstall com.example.zhengdao`（旧包 versionName=2.0.12、debug 钥匙） |
| 安装 | `adb install zhengdao-2.0.13-release.apk` ⇒ Success（versionCode 29 / versionName 2.0.13） |
| 恢复 | ⚠️ release 包**不是 debuggable** ⇒ `run-as` 不可用（`run-as: package not debuggable`）。改用**桥接包**：把 `assembleDebug` 产物 `zipalign -f -p 4` 后用正式 keystore 重新签名（`apksigner sign --ks ~/.zhengdao-keys/zhengdao-release.jks --ks-key-alias zhengdao`）⇒ `adb install -r` 覆盖安装（同钥匙同 versionCode，数据保留）⇒ `run-as` 可用 |
| 灌数据 | `adb push` 到 `/data/local/tmp` → `cat … \| run-as … sh -c 'cat > files/proot-tmp/zd-appdata.tar'` → `tar -xf … -C files` ⇒ `files/home` 2251 MB、`files/oc` 512 MB（与卸载前一致，tar 只报一条无害的 `home/工作区 bad symlink`，该软链由 App 在会话启动时重建） |
| 工作区设置 | 直接写 `shared_prefs/zhengdao-settings.xml`：`workspace_mode=custom` + `workspace_path=/storage/emulated/0/Download/男性` + `workspace_migrated_v2=true`（prefs 在 `/data/data` 里，卸载时随包清掉，tar 里没有它） |
| 收尾 | 再用**正式 release APK** `install -r` 覆盖桥接包（同钥匙同 versionCode）⇒ `/data/data` 数据保留，`run-as` 重新变成不可用（正确状态） |

**装后取证**（日志逐字）：

```
[10-11 17:02:10] 启动自检：安装包签名 = 官方 ✓
[10-11 17:02:10] 工作区: /workspace <- /storage/emulated/0/Download/男性（共享存储，卸载保留）
[10-11 17:02:10] 会话启动（Termux 引擎）tmux=zhengdao agent=- isFallback=false usesTmux=true
```

- `files/rootfs/etc/zhengdao-rootfs.info` ⇒ `distro=debian-13.7` / `arch=arm64` / `glibc=2.41` / `python=3.13` / `node_major=26`（环境完好，**没有重新下载**：rootfs 用的是共享存储里的本地归档 `/sdcard/Download/证道/rootfs/debian-13.7-base-arm64.tar.zst`，sha256 边车 `d80639e7…4f02`）。
- 太极 Tab：`ps -A` 见 `opencode`（pid 12089，RSS 444 MB），日志持续收到 SSE 事件（`type=project.updated` 等）⇒ 恢复的 `files/oc` 可直接用。
- 终端页顶栏（见资产 01）：**「证道 - Debian 13.7」**——完整显示、无省略号，右侧「复制 / 粘贴」不再被挤掉。

**没验的**：这次换包没有覆盖「从旧版直接覆盖安装会失败」这一条（没有为验证去制造失败安装），
依据是签名不同名的既有事实（E-099）与 Android 的签名校验规则。

## ② v2.0.12 正文补「先卸载」

`PATCH /repos/pisces19860207/zhengdao/releases/409388741`，正文由 2737 → **3284 字符**，
首行起逐字：

```
> ⚠️ **装这一版之前必须先卸载旧版**（2026-10-11 起）
> 证道从 **v2.0.13** 起改用**独立的正式签名钥匙**。在此之前，正式包与开发包共用同一把 debug 钥匙
> （背景与代价见 `docs/ERRATA.md` **E-099**）。因此：
> - 已经装过旧版（v2.0.12 及更早，或本仓库 CI 出的任一版本）的手机，**装 v2.0.13 及以后版本前必须先卸载旧版**，
>   否则系统会以「签名不一致」拒绝安装（`INSTALL_FAILED_UPDATE_INCOMPATIBLE`）。
> - 装**这一版（v2.0.12）本身**仍是旧钥匙，可以直接覆盖安装，不要求卸载。
```

## ③ 接手 `fix/term-title` 并发版

- 复核：另一会话的 `231eca9`（2 文件 +23 −3）——`TerminalActivity.kt:486-492` / `:507-513` 顶栏标题收短为 `证道 - Debian 13.7`；`res/layout/activity_main.xml:76-104` 标题改 `maxLines=1` + `autoSizeTextType=uniform`（12–17sp，步进 1sp）。
- 本地重跑：`gradlew.bat :app:testDebugUnitTest :app:assembleRelease` ⇒ **BUILD SUCCESSFUL in 2m 42s**，**66 suite / 602 用例 / 0 失败 / 0 错误 / 1 跳过**。
- 合并：`b3a1b11`（merge `fix/term-title`）→ 提版 `c8919b6`（`appVersionName` 2.0.12→**2.0.13**、`versionCode` 28→**29**、release-notes 补第 ⑥ 条）→ merge `d8647e1` → push。
- 本机 APK 验签（新钥匙）：`app/build/outputs/apk/release/zhengdao-2.0.13-release.apk`（8,212,499 B）⇒ `apksigner verify --print-certs`：`CN=Zhengdao, O=Zhengdao, C=CN`、`SHA-256 b93169f2…86ec2`。
- CI：`d8647e1` 的 ci / build 双绿；滚动版 `latest` 出现 `zhengdao-2.0.13-release.apk`（8,212,319 B，09:02:35Z）。
- 发版：`git tag -a v2.0.13` + push ⇒ release job 绿 ⇒ **「证道 v2.0.13 — 正式版」**（published 2026-10-11T09:10:55Z），6 个资产：APK 8,212,319 B + rootfs 四项（正文取自 `.github/release-notes/v2.0.13.md`）。

## ④ 删掉以前的正式版

`DELETE /repos/…/releases/{id}` 逐个删（**19 个**，全部 HTTP 204）：
v0.9.0 / v0.9.1 / v1.0.0 / v1.1.1 / v1.2.0 / v1.3.0 / v2.0.0 … v2.0.12。

- **保留**：`v2.0.13`（本次正式版）与滚动版 `latest`（App 内置的 rootfs / 索引下载地址就是
  `releases/download/latest/...`，见 `rootfs/RootfsIndex.kt:113`、`terminal/ProotLauncher.kt:23` ⇒ 绝不能删）。
- **git tag 未删**（`v2.0.12` 这些 tag 还在仓库里，历史与源码可追溯）；如要连 tag 一起清，另说。
- 滚动版 `latest` 里仍堆着 CI 攒下的历史 APK（`zhengdao-1.3.0` … `2.0.12-release.apk`，`--clobber` 只覆盖同名所以越攒越多）——本次未动，待确认是否清。

---

## 资产

| 文件 | 说明 |
|---|---|
| `01-title-2.0.13.webp` | 换包后终端页顶栏：`证道 - Debian 13.7` 完整显示，右侧「复制 / 粘贴」可见 |
| `02-taiji-after-restore.webp` | 恢复数据后的太极 Tab（opencode 环境可直接用） |
