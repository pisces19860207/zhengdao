# 发版收尾清单（RELEASE-CHECKLIST）

> **这份清单只做一件事**：把「vX.Y.Z 从合并到销账」的全过程钉成一串可勾的框，
> 每一步都写清**判据**（怎么算通过）与**停下来等确认的位置**（不许跳）。
>
> **怎么用**
> - DSH 里输入 `/release`（若该命令已注册，见文末「怎么把它变成 /release」）；
> - 没注册就直接说：「**按 `docs/RELEASE-CHECKLIST.md` 走**」。
> - 阶段 3 是**你（用户）在真机上执行**、我只负责记录读数与截图结论 —— 不要替跑、不要替猜。
>
> **本文档按六段组织**：阶段 0 前置检查 → 阶段 1 合并与打 tag → 阶段 2 CI 产物核对 →
> 阶段 3 真机验证 → 阶段 4 收尾留痕 → 异常处理（贯穿全程）。
> **阶段 0 / 1 / 2 / 4 末尾的「停下来等用户确认」是硬闸：不得跳过、不得自行放行。**

版本占位符约定：`X.Y.Z` = 本次版本号，`N` = 本次 `versionCode`，`#NN` = 本次要新写的台账序号。

---

## 阶段 0 · 发版前置检查（只读）

> 目标：在动 `main`、动 tag 之前，先把「这一版配不配发」验完。
> 这一段**只读不改**；发现不合规就停下来报，不顺手改。

- [ ] **0.1 版本号 bump** —— `app/build.gradle.kts` 里 `appVersionName = "X.Y.Z"` 且 `versionCode = N`（比上一版 +1）。
- [ ] **0.2 release note 已建** —— 文件 `.github/release-notes/vX.Y.Z.md` 存在且**正文 > 400 字**（不是占位）。
      *判据来源*：`app/src/test/java/com/example/zhengdao/ReleaseSigningGateTest.kt` 第 84–86 行；`build.yml` 的 release job 按 tag 找 `.github/release-notes/<tag>.md`，找不到只打 `::warning::` 不失败（E-068）。
- [ ] **0.3 README 三处一致** —— 三处必须同时指向 `vX.Y.Z` / `versionCode N`：
      - [ ] `README.md`：含「当前版本：vX.Y.Z」与「`versionCode N`」
      - [ ] `README.en.md`：含「Current release: vX.Y.Z」与「`versionCode N`」
      - [ ] `app/build.gradle.kts`：`appVersionName` / `versionCode` 与上面两处一致
      *判据来源*：`ReleaseSigningGateTest.kt:69-87`（`版本号三处一致_且该版本有 release note`）。
- [ ] **0.4 单测全绿** —— `.\gradlew.bat --offline testDebugUnitTest`
      （`JAVA_HOME=D:\Program Files\Android\Android Studio\jbr`）；读数记下 **suite 数 / 例数 / 0 失败 / 0 错误 / skipped 数**。
      *上一版（v2.0.18）**实际**基准*：86 suite / 690 例 / 0 失败 0 错误 / 1 skipped（含 Qoder 三条修复后的读数：`13d18ff` 带进 2 个测试类 5 例，改前为 84/685）。
- [ ] **0.5 开工前置（本仓铁律）** —— `python tools\zd.py preflight -k <本次关键词> -p <本次要动的文件>` 退出码 **0**；
      仓库奇怪时补跑 `python tools\zd.py doctor`。落后 origin 就不许继续（`git rev-list --left-right --count origin/main...HEAD` 左侧必须为 **0**）。
- [ ] **0.6 `assembleRelease` 成功** —— 四个环境变量齐备：`ZHENGDAO_KEYSTORE_FILE` / `ZHENGDAO_KEYSTORE_PASSWORD` / `ZHENGDAO_KEY_ALIAS` / `ZHENGDAO_KEY_PASSWORD`；产物 = `app\build\outputs\apk\release\zhengdao-X.Y.Z-release.apk`。
- [ ] **0.7 签名断言通过（正例 + 负例）** ——
      - [ ] 负例：**不设** `ZHENGDAO_KEYSTORE_FILE` 跑 release 任务 ⇒ 配置期**必须失败**（`GradleException`）
      - [ ] 正例：设齐四个变量 ⇒ 通过；显式绕过 `-PallowDebugSignedRelease` 在本地仅在明知故为时用
- [ ] **0.8 产物签名真的是正式钥匙（不是 debug 钥匙）** —— `apksigner verify --print-certs <apk>`，证书主体与上一版一致（`CN=Zhengdao, O=Zhengdao, C=CN`）。
      ⚠️ **E-099 的坑**：漏设 keystore 变量时 `assembleRelease` **照样 BUILD SUCCESSFUL**，但产物是 `CN=Android Debug` 签的，装机会被系统拒（老用户必须卸载重装、Debian 环境全没）。**装 Release 包之前先核证书。**
- [ ] **0.9 许可证正文门禁通过** —— `python tools\check-licenses.py`（不给路径则取 `app/build/outputs/apk/` 下最新的包）；
      覆盖 `assets/licenses/` 与仓库逐字节一致 ＋ `libzstd-jni-*.so` ⇒ BSD 正文在 ＋ `publicsuffixes.gz` ⇒ `NOTICES.txt` 必须提到它（依据 E-105）。
- [ ] **0.10 `.so` 门禁通过** —— `python tools\check-native-so.py`（16KB 页对齐 + JNI 入口符号），退出码 0。
- [ ] **0.11 分支与 origin 同步** —— 发版分支已推远端；`git fetch origin` 后本地分支与 `origin/<分支>` 一致，`main` 侧左侧计数为 0。
- [ ] **0.12 这一版改了哪些东西，已能一眼说清** —— 差异清单（文件 + 行数）与「为什么」已备好，阶段 1 写 `§A #NN` 与 release note 时直接引用。

> **🛑 阶段 0 收尾：停下来等用户确认** —— 报告 0.1–0.12 的读数（单测数、APK 大小与 SHA256、证书指纹、门禁退出码），**等用户说「可以」**再进阶段 1。
> 任何一项红：停在这里，报用户，**不要**自行绕过。

---

## 阶段 1 · 合并与打 tag

> 目标：把 `fix/batch-vX.Y.Z` 合进 `main`，记完账，打 tag，并确认 tag job 用的是正式签名路径。

- [ ] **1.1 确认待合并分支的基线** —— `fix/batch-vX.Y.Z` 已推远端、与本地一致；先看清楚它相对 `main` 的提交数与文件数。
- [ ] **1.2 切到 main 并拉齐** —— `git checkout main` → `git fetch origin` → `git pull --ff-only`（左侧计数必须 0）。
- [ ] **1.3 合并（merge 提交，禁止 force）** —— `git merge --no-ff fix/batch-vX.Y.Z`，冲突必须人工解，**不许** `push --force`、**不许** `reset --hard`。
      *钩子口径*：`main` 上**直接提交**会被 `.git/hooks/pre-commit` 拒（源文件 `tools/hooks/pre-commit`，装法 `python tools\zd.py install-hooks`）；**合并提交走 `pre-merge-commit`，放行**（见 `docs/ERRATA.md` E-103）⇒ 集成本来就该用 `merge --no-ff`。
      确实要绕过钩子时：`ZHENGDAO_HOOK_BYPASS=1`，**并在提交信息里写明为什么**。
- [ ] **1.4 记合并提交号** —— 记下合并提交 SHA（阶段 4 的 `§A #NN` 要写它）。
- [ ] **1.5 写 `§A #NN`（台账）** —— `docs/FEATURE-LEDGER.md` §A 表格追加一行，表头是 `| # | 做了什么 | 提交 | 证据 / 验证方式 |`（第 18 行）：
      - [ ] 「做了什么」写清本版内容与用户逐字交办（若用户给了原话，照录）
      - [ ] 「提交」写分支 → 合并提交（如 `fix/batch-vX.Y.Z` → 合并 `xxxxxxx`）
      - [ ] 「证据 / 验证方式」写单测读数、门禁退出码、真机结论（阶段 3 完成后再补读数为准）
      - [ ] 顺带收口 §B：本版做完的项标「已销账」并指向本行 `§A #NN`
- [ ] **1.6 打 tag（打在合并提交上）** —— `git tag vX.Y.Z`；确认 `git show --stat vX.Y.Z` 指向的是 **1.4 那个合并提交**，不是分支上的旧提交。
- [ ] **1.7 推 main（先）** —— `git push origin main`（滚动 `latest` 会因此刷新；**滚动通道 ≠ 发版**，E-081 口径）。
- [ ] **1.8 推 tag（后）** —— `git push origin vX.Y.Z`，触发 `.github/workflows/build.yml` 的 `release` job（`if: startsWith(github.ref, 'refs/tags/v')`，`build.yml:483-484`）。
- [ ] **1.9 tag job 必须真实签名的两道绿（硬失败，红了就是发不了）** ——
      - [ ] 「把关：没有签名密钥就不许发正式版」绿（`build.yml:490-500`；缺 secret `RELEASE_KEYSTORE_B64` 直接 `exit 1`，E-014 / E-099）
      - [ ] 「校验 release 包的签名真的是那把正式 release key」绿（`build.yml:273`）
- [ ] **1.10 tag job 的构建日志里**不得出现 `-PallowDebugSignedRelease`**（逐个 run 步骤核对）** ——
      - [ ] `build` job 的编译步是 `./gradlew :app:assembleRelease --console=plain`（`build.yml:263`），env 注入 `ZHENGDAO_KEYSTORE_FILE=${{ github.workspace }}/.ci-release.keystore`（`build.yml:259`）
      - [ ] 该参数**只允许**出现在 `.github/workflows/ci.yml:141` 的 R8 冒烟（PR 验证用；产线不许带）
      - [ ] 若 tag job 日志里出现了它 ⇒ **停**，说明发出去的包是 debug 签名的（E-099），报用户
- [ ] **1.11 顺手确认 CI 侧两道门禁也在跑** —— `.github/workflows/ci.yml`：`:64` `.so` 门禁、`:75-76` `tools/check-licenses.py`、`:78-79` 全量单测（`ReleaseSigningGateTest.kt:53-67` 已把「CI 必须跑许可证门禁」钉成断言）。

> **🛑 阶段 1 收尾：停下来等用户确认** —— 报告合并提交号、tag 名、tag job run 链接与 1.9/1.10 的核对结果，**等用户说「继续」**再进阶段 2。

---

## 阶段 2 · CI 产物核对

> 目标：Release 页上的东西**真的齐、真的对得上**。
> ⚠️ **本阶段的必要原因**：搬运 RootFS 资产那一步是 `continue-on-error: true`（`build.yml:567`）——
> **缺件不会让 CI 变红**，只会打 `::warning::`。所以必须人工核。

- [ ] **2.1 tag job 全绿** —— `release` job 与它的 `needs: build` 都 success；若有 `::warning::`（如 E-068 的正文回退）逐条记录。
- [ ] **2.2 Release 必核资产 5 件齐全**（缺一件都算没发完）——
      - [ ] APK：`zhengdao-X.Y.Z-release.apk`
      - [ ] rootfs 整包：`debian-13.7-base-arm64.tar.zst`
      - [ ] rootfs 校验：`debian-13.7-base-arm64.tar.zst.sha256`
      - [ ] 版权归档：`debian-13.7-copyright.tar.zst`（E-097 收口）
      - [ ] 版权归档校验：`debian-13.7-copyright.tar.zst.sha256`
      - [ ] **APK 没有 `.sha256` 边车，别去找** —— APK 摘要以 `assets[].digest` 回读为准（GitHub 自己算的 `sha256:`）。
        依据：`build.yml` 里 APK 只被 `cp` 成 `apk-release/zhengdao-*-release.apk` 一个文件（`build.yml:303-313`），
        tag 的 release job 也只上传这一个文件（`build.yml:546-553`）；**实测** v2.0.17 / v2.0.18 两个 Release 的
        `assets[]` 都是 **8 件、无任何 APK 边车**，`.sha256` 边车**只有** rootfs 整包与版权归档才有。
- [ ] **2.3 另三件 rootfs 资产也要在**（tag job 从滚动版 `latest` 搬，`.github/workflows/build.yml:575-577` 的 glob）——
      - [ ] `rootfs-manifest.txt` 与 `rootfs-manifest.txt.sha256`
      - [ ] `rootfs-index.json`
      - [ ] 说明：2.2 ＋ 2.3 **合计 8 件**（= v2.0.17 / v2.0.18 实测件数）；这三样**故意不搬** `rootfs-patch-*`（补丁是一次性产物、App 走索引里的 URL，`build.yml:562-564`）
- [ ] **2.4 用 `assets[].digest` 回读比对（不看自记，从公开地址回读）** ——
      - [ ] GitHub REST `https://api.github.com/repos/pisces19860207/zhengdao/releases/tags/vX.Y.Z` 取 `assets[].name` / `size` / `digest`（`sha256:` 前缀，GitHub 自己算的）
      - [ ] 逐件下载后本地复算 `sha256sum`，与 `digest` **逐字节一致**
      - [ ] 真机上若已下载过 rootfs，端侧 `sha256sum` 与线上 `digest` 也要一致（离线包 = 线上同一串字节）
      - [ ] 口径依据：§A #46（v2.0.14 / v2.0.15 先例）与 §A #65（版权归档五版 `digest` 相同）
- [ ] **2.5 摘要入册** —— 把 2.4 的 `name / size / digest` 写进 `PROVENANCE.md` 的「官方发布资产摘要」节（按 v2.0.15 先例）；
      **滚动版 `latest` 明确不入册**（每次构建刷新，同版本号两次构建 sha 不同，E-081）。
- [ ] **2.6 资产名与 App 的读取路径对得上** —— App 侧读 `rootfs-manifest.txt` / `rootfs-index.json`；索引是 Ed25519 签名的（`rootfs/RootfsIndex.kt`），**索引验签失败即视为没发**。

> **🛑 阶段 2 收尾：停下来等用户确认** —— 报告 6 件资产的 `digest` 表与三样附带资产的结论，**等用户说「开始验机」**再进阶段 3。
> 缺件：**停报用户，不自己补、不自己重跑**（见「异常处理」）。

---

## 阶段 3 · 真机验证（用户执行，我记录）

> **这一段的读数是用户给的**：我不代跑、不代猜、不改口径。
> 每次先记 **机型 / Android 版本 / ROM**，再逐项记读数；失败的项记「机型 + 现象」原文。

- 本次机型 / 系统：`__________`（上一版口径示例：PGT-AN10 / Android 16 / MagicOS）

- [ ] **3.1 升级安装（E-099）** —— `adb install -r app\build\outputs\apk\release\zhengdao-X.Y.Z-release.apk` = `Success`，**未卸载**，`files/rootfs`、`files/home`、prefs 全保留。
      - [ ] 若从 **v2.0.12 或更早**升级：必须**先卸载**（那些版本是旧 debug 钥匙签的，Android 不允许覆盖），卸载会清掉 App 私有目录（Debian 环境约 200 MB 需重新下载；手机存储里的工作区 / 资料库 / 日志不受影响）
- [ ] **3.2 首启权限页（§B-15；实现见 §A #32）** —— **清应用数据后连续冷启动 5 次**：不得自动弹权限框 / 通知框（§B-15 的判据）；
      权限页一屏一卡四行（存储读写＝必需 / 所有文件访问＝可选 / 通知 / 网络访问）；未授权点整行弹系统框、已授权点行进应用详情；底部按钮文案 `完成，进入证道` / `稍后再说`，**两者都不拦人**。
- [ ] **3.3 rootfs 下载与校验** —— 下载完成提示、镜像内 `/usr/share/zhengdao-copyright.tar.zst` 存在（**348 个包**的 `copyright` 正文，4.78 MiB 原文 → 约 0.49 MiB 归档）。
- [ ] **3.4 proot 四件套 SHA256** —— 四个 asset 的端侧实算值与钉住值一致（固化在 `PROVENANCE.md:106-131` / `terminal/PinnedAssets.kt`）。
- [ ] **3.5 serve 鉴权（E-093）** —— `adb forward tcp:14000 tcp:14000` 后从 PC：
      - [ ] 无鉴权 `GET /api/session` = **401**
      - [ ] 无鉴权 `GET /`（SPA 外壳）= **200**
      - [ ] serve 日志逐字：`server listening on http://127.0.0.1:14000`（**只听环回**）
      - [ ] 口径依据：§A #29、`docs/太极-PoC3-鉴权发现.md:61-67`、`docs/milestones/证道-太极Tab-设计API对照表.md:34-35`
- [ ] **3.6 guest `apt-get update`（NSC 明文策略有没有误伤 guest）** —— 终端里跑 `apt-get update` 正常从 `deb.debian.org` 下载。
      口径：`network_security_config` 的明文策略只在平台网络栈生效，guest 是 glibc + GnuTLS 原生程序、不受拦（§A #64）。
- [ ] **3.7 环回明文** —— App ↔ 内置服务（`127.0.0.1` / `localhost`）通信正常；全局 `usesCleartextTraffic` 已撤、白名单里只有环回。
- [ ] **3.8 资料库（E-089）** —— 扫描到 2000 个上限时，清单**自己写一行**「只列了前 2000 个」，设置页状态带后缀；不静默截断。
      （触发需要 `资料库\原始\` 下 > 2000 个文件；设备上没这个量级时，如实记「未验，由代码复核 + 单测覆盖」。）
- [ ] **3.9 保活切 Tab** —— 切前后台 / 切 Tab 后会话与安装状态不断（保活记录页读数与预期一致）。
- [ ] **3.10 英文 + 深色（§A #66）** —— 设置 → Language → English：设置页 / 二级页 / 首页 / 太极页 / 欢迎与首启权限页全英文（终端页里 App 自绘部分、首页「待处理」、Agents 简介、开源许可页**刻意仍是中文**，属已知）；
      设置 → Appearance：深色在设置页与太极页正常（对比度对卡片底 ≥ 4.5:1）。
- [ ] **3.11 开源许可页** —— 设置 → 关于 →「开源许可 / 随包正文」能离线打开列表 + 详情（正文来自身 APK 的 `assets/licenses/`，§A #41 / #49）。
- [ ] **3.12 缓存清理（E-115）** —— 存储页核对：hermes 工具组一律**只报不删**（`chromium` / `agent-browser` / `cua-driver` 保留），`OPTIONAL_TOOL_NAMES` 为空表 ⇒ 可清项为 **0**、页面上**不得出现**「清理可选工具」入口；可清的只有缓存组（如太极缓存）。
      ⚠️ 真机走查默认**只读**：点「清理」会真动 guest 状态 —— 要么用户明确授权点，要么记「未点，由单测钉住」。
- [ ] **3.13 版本显示** —— 设置 → 关于 = `版本 X.Y.Z (versionCode N)`；可选补 `adb shell dumpsys package com.example.zhengdao` 看 `versionName` / `versionCode` / `lastUpdateTime`。
- [ ] **3.14 本版点名的新功能 / 改动逐条过一遍** —— 按 release note 的「本次更新」逐条给读数（这一项防的是「改了但没人验」）。

> **🛑 阶段 3 收尾：停下来等用户确认** —— 等用户把 3.1–3.14 的读数贴回来（含失败项的机型 + 现象）我才往下走；
> **我不自行改口径、不把「没验」写成「通过」**。

---

## 阶段 4 · 收尾留痕

- [ ] **4.1 §A 补 `#NN` 的完整读数** —— 把阶段 3 的结论补回 `docs/FEATURE-LEDGER.md` §A 那一行，**至少含四样**：
      - [ ] **tag**：`vX.Y.Z`
      - [ ] **APK SHA256**（GitHub `assets[].digest` 的值；本机包与 CI 包字节可能差 ~12 B，属 E-081，别混用两个摘要）
      - [ ] **rootfs SHA256**（`debian-13.7-base-arm64.tar.zst` 的 `digest`）
      - [ ] **验证结果**：单测读数、门禁退出码、真机 3.1–3.14 结论（失败的照实写）
- [ ] **4.2 ERRATA 该立的立** —— 本版踩到的新坑立 `E-1xx`（标题写清「错在哪 / 怎么发现的 / 怎么修 / 判据」），并把号写回 §A 那一行。
- [ ] **4.3 ERRATA 该关的关** —— 本版修掉的老条目加「补记（日期）」并标已收口（例：E-097 的收口写法）；**没真修完的不许关**。
- [ ] **4.4 README 版本号与 tag 一致（最终复核）** —— `README.md` / `README.en.md` / `app/build.gradle.kts` 三处与 tag `vX.Y.Z` 一致，`.github/release-notes/vX.Y.Z.md` 在；跑一遍 `ReleaseSigningGateTest` 应全绿。
- [ ] **4.5 台账对账收口** —— §B 里本版做完的项已销账（指向 §A `#NN`）；本版没做的不许顺手删，留在 §B 里。
- [ ] **4.6 工作树清理** ——
      - [ ] `git status` 干净（无未提交残留）
      - [ ] 已合并的临时分支 / 临时工作树出「**待删除清单**」；**删除前给用户确认**，**远程分支删除必须用户确认**
      - [ ] `git worktree prune` / `git remote prune` 后无残留
- [ ] **4.7 收尾简报** —— 一句话结论：合并提交号、tag、APK/rootfs 摘要、真机结论、还剩哪些账未关。

> **🛑 阶段 4 收尾：停下来等用户确认** —— 报告 4.1–4.7，**等用户点头**才算这一版结束。
> 提交这些留痕改动时注意：`main` 上直接提交会被 `pre-commit` 拒 —— 要么在被放行的合并提交里带上，要么用分支 + `merge --no-ff`（E-103）。

---

## 异常处理（贯穿全程）

- [ ] **CI 红** ⇒ **停下来报用户**，把失败步骤 + 报错原文贴出来；**不自己重试、不自己绕过**
      （尤其：不许为了变绿而加 `-PallowDebugSignedRelease` —— 那是 E-099 的雷）。
- [ ] **Release 缺件** ⇒ **停下来报用户**，列出缺哪件、`continue-on-error: true` 那一步的日志说了什么；
      **不自己补挂、不自己重跑 tag**（重跑 = 重新发布，属于重大操作）。
- [ ] **真机验证失败** ⇒ 记下**机型 + Android 版本 + 现象（原文/截图）**，报用户；**不自行改口径**、
      不把「未验」写成「通过」、不擅自降级成 issue 就算了。
- [ ] **需要 force push / 删远程分支 / reset --hard / 重写公共历史** ⇒ 一律**先问用户**。
- [ ] **任何删除** ⇒ 先出「待删除清单」，用户确认后再动；**不自动删用户文件**。

---

## 维护约定

- **每次发版，发现清单漏了一项，就往这份清单里补一条**（补在对应阶段，带判据与依据出处）。
- 判据尽量写成「可复算的东西」：文件路径 + 行号、命令 + 退出码、`digest` 值、真机读数 —— 不写「应该没问题」。
- 踩到新坑：清单补一条 ＋ `docs/ERRATA.md` 立一个 `E-1xx`，两处互指。
- 这份清单**只增不删**；某条被证明天然不适用时，改成「不适用（理由 + 日期）」而不是删掉。

---

## 怎么用这份清单

**不注册自定义命令。** 每次发版直接说：

> 按 `docs/RELEASE-CHECKLIST.md` 走 `vX.Y.Z` 收尾。

DSH 会读本文件、按阶段执行、每阶段末尾停下来等确认。若将来命令注册机制稳定了再考虑封装成 `/release`，当前不引入插件依赖。

> 本文件由 DSH（蓝总管）于 2026-10-13 建立；内容依据：`app/src/test/java/com/example/zhengdao/ReleaseSigningGateTest.kt`、
> `.github/workflows/build.yml`、`.github/workflows/ci.yml`、`docs/FEATURE-LEDGER.md` §A #29 / #32 / #46 / #64 / #65 / #66 / #67、
> `docs/ERRATA.md` E-068 / E-081 / E-089 / E-093 / E-097 / E-099 / E-103 / E-105 / E-115 / E-121、
> `docs/acceptance/release-2.0.17-2026-10-11.md`。
