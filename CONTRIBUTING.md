# 贡献指南（CONTRIBUTING）

感谢愿意花时间。这个仓库目前**只有一位版权人**（见 [`LICENSE`](LICENSE) 与
[`docs/闭源切换清单-2026-10-11.md`](docs/闭源切换清单-2026-10-11.md) 第七节），
所以规则很短，但有几条是硬的。

## 一、贡献前必须知道的两件事

1. **第一方代码自 2026-10-11 起是「保留所有权利」**（≤ v2.0.15 的版本仍是 GPL-3.0）。
   提交代码**不等于**你获得任何再分发或商用权利；最终许可由项目所有者决定。
2. **外部贡献必须签 [`CLA.md`](CLA.md)**，把该贡献的版权与**再许可权**授予项目所有者。
   - 在 PR 描述里写一行：`I have read and agree to CLA.md` ＋ 你的姓名 / 邮箱 / GitHub 用户名。
   - 未签 CLA 的 PR 不会被合入（这是为了将来换许可、双许可或闭源时不存在"找不到人签字"的贡献）。
   - ⚠️ **CLA 必须在第一个外部 PR 合入之前签**：一旦合入，撤销该贡献需要贡献者本人同意。

## 二、提交与分支

- 本仓库装了钩子（`tools/hooks/`）：
  - `pre-commit` 有三道闸（**在 `main` 上直接 `git commit` 会被拦下**）——
    请开分支提交，再用 `git merge --no-ff` 合回 `main`；
  - `pre-merge-commit` 是前向转发器，合并时会带 `ZHENGDAO_IN_MERGE=1`（见 `docs/ERRATA.md` E-103）。
- 提交信息用中文，说清「改了什么 / 为什么」；一次提交只做一件事。

## 三、构建与自检（本机口径）

```powershell
$env:JAVA_HOME = 'D:\Program Files\Android\Android Studio\jbr'   # PATH 里没有 java
.\gradlew.bat --offline testDebugUnitTest
.\gradlew.bat --offline :app:assembleRelease
python tools\check-native-so.py      # 必须退出 0
```

- **Gradle 一律离线**（`--offline`），依赖已在本机缓存。
- PR 前请贴出单测结果（当前基线见 `docs/FEATURE-LEDGER.md` 最近一行 §A 记录）。
- ⚠️ **绝不要跑 `connectedDebugAndroidTest`，也不要 `adb uninstall`**：会清掉真机上的
  `files/rootfs`、`files/home` 与偏好设置（`docs/ERRATA.md` E-085）。装包只走 `adb install -r`。

## 四、代码约定

- 新文件第一行写独立开发声明（见任一 `app/src/main/java/.../*.kt` 的文件头）：
  本文件为本项目从零编写、未参考第三方同类应用代码；参考资料清单在 `PROVENANCE.md`。
- 注释写中文，先说结论再说原因；`TODO` / `FIXME` 请带上 issue 或 ERRATA 编号。
- Compose 刷新惯用法：`storageTick` ＋ `LaunchedEffect(storageTick)` ＋ `remember`；
  阻塞 IO 走 `scope.launch { withContext(Dispatchers.IO) { … } }`。
- **不要**改动 `licenses/` 下的许可证正文（逐字节可核，`licenses/README.md` 记着 SHA256），
  也不要改字体 `assets/fonts/OFL.txt` —— 要动先问。
- 不要新增第三方依赖而不说明许可证；新增随包组件必须同时补正文与 `THIRD-PARTY-LICENSES.md`。

## 五、报告问题

开 [Issue](https://github.com/pisces19860207/zhengdao/issues) 时请带：设备型号与 Android 版本、
App 版本（设置 → 关于）、复现步骤、日志（设置 → 关于 → 保活记录，或终端里的输出）。
安全相关的问题请私下发邮件到 putaopanpan@gmail.com，不要开公开 Issue。

## 六、语言

文档与提交信息用中文；`README.en.md` 是英文镜像，两边的**许可与义务口径必须一致** ——
改了一边，请同步另一边。
