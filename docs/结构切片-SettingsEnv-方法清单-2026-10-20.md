# 结构切片 · SettingsEnv 环境与更新页方法清单（第八十批 · 2026-10-20）

> 派工单：`docs/派工单-2026-10-20-第八十批-SettingsEnv切片.md`（单在私有副本 `_zd-private/docs/`）。
> 本清单是「先出方法清单，再动手」这一步的产物。行号按**开工时** `origin/main`（`df1caf7f`）实测。
> 交付前按 pre-commit 闸门 rebase 到 `dbfe733c`（上游又走了 40 余笔：78/73/74/76/75 各批合并＋立账）；
> 上游这些笔**没有碰** `SettingsEnvActivity.kt`（只碰了 `util/BareThreadGuardTest.kt` 与本单无关的文档），
> 所以上表行号在 rebase 后依然成立。
> ⚠️ 派工单行数口径：单上写「Env 页 691 行」——实测**全文件 880 行**（`SettingsEnvPage` 函数体 :125–:879，755 行）。
> 差 189 行来自 §B 80 审计之后的既有改动（65 批安装体验 +84 行是其中一笔），非本批。

## 〇、前置条件核对（派工单要求「等 65 合入 main」）

| 项 | 读数 | 证据 |
|---|---|---|
| 65 分支 | `feat/install-progress`，tip `f75c1737` | `git log` |
| 65 是否已在 main | **是**（`git merge-base --is-ancestor f75c1737 origin/main` → YES） | `29fb8a40 docs(账): 立账 65/72 两批验收（321 套件/2933 例全绿、lint 719 带内）` |
| 开工基线 | `df1caf7f`（`origin/main` tip），落后 0 / 领先 0 | `python tools/zd.py preflight` |
| 65 在 Env 页的改动 | +48 行，4 个 hunk：`:596–:623`（修复线程）、`:765–:770`（增量下载回调）、`:785–:792`（全量校验）、`:802–:808`（收尾） | `git diff origin/main...f75c1737 -- <path>` |

> 注：开工那一刻（14:44）65 尚未合入，`origin/main` 还是 `c83cc763`、Env 页 832 行；14:47 再 fetch 时已被合入并立账。
> 本批**全部改动都落在 65 未触碰的行段**（见下表「与 65 重叠」列），所以即使重排也零冲突，但依派工单仍等合入后才动手。

## 一、`ui/SettingsEnvActivity.kt`（880 行全文件逐块过秤）

| 块 | 行数 | 位置 | 依赖 | 可抽性 |
|---|---|---|---|---|
| `SettingsEnvActivity` 类 ＋ `onCreate` | 13 | `:106`–`:118` | ComponentActivity、setContent | 不可抽（Android 入口） |
| `SettingsEnvPage(onBack)` 整体 | 755 | `:125`–`:879` | Compose、Context、State、coroutines | 编排留驻；内部片段抽出 |
| `openLogFromSnackbar()` | 3 | `:158`–`:160` | snackbarHostState、Intent | 不可抽 |
| `toastOnMain()` | 5 | `:162`–`:166` | Handler、Toast | 不可抽（Android 线程） |
| 「修复环境」卡 | 15 | `:171`–`:185` | Compose、`InstallFlow.isRunning()` | 视觉留驻；可点性已是既有 helper |
| 「Hermes 依赖环境」卡 | 67 | `:187`–`:253` | HermesEnv、裸 `Thread`、Toast | 不可抽（`BareThreadGuardTest` 盯防对象） |
| 「检查环境更新」卡 | 247 | `:254`–`:500` | 网络、RootfsIndex、RootfsCache、Context | **本批主战场**（见下表） |
| 「运行内存上限」卡 | 47 | `:501`–`:547` | Settings.prefs、FlowRow、Toast | 不可抽（读写偏好 + 视觉） |
| 修复确认弹窗（含本地包挑选） | 100 | `:556`–`:655` | AlertDialog、File、InstallFlow、RootfsInstaller | 挑选条件抽出（片段④），执行留驻 |
| 环境更新弹窗（下载/增量/全量） | 172 | `:657`–`:828` | AlertDialog、网络、RootfsDelta、RootfsInstaller | 两条进度回调的节流抽出（片段⑤），执行留驻 |
| `SettingsLogDialog` | 2 | `:878` | 既有组件 | 不可抽 |

### 「检查环境更新」卡内部再拆

| 块 | 行数 | 位置 | 与 65 重叠 |
|---|---|---|---|
| 按钮可点性 ＋ 索引拉取 ＋ 8 口径 when 树 | 81 | `:270`–`:362` | 否 |
| 老 manifest 降级解析 | 36 | `:363`–`:398` | 否 |
| catch → HumanizeError ＋ 落 RunLog | 7 | `:399`–`:405` | 否 |
| 回主线程落 pending* ＋ 无目标时 Toast | 16 | `:406`–`:421` | 否 |
| 安装状态行（图标/颜色/文案/收尾） | 45 | `:428`–`:472` | 否 |
| AgentManifest 版本行 ＋ 刷新 | 20 | `:473`–`:493` | 否 |

## 二、圈定的 5 个纯逻辑片段（3–5 个的**上界**，全部输入普通值、输出普通值/数据类）

| # | 片段 | 原位置（改前） | 行数 | 依赖 | 落点 | 与 65 重叠 |
|---|---|---|---|---|---|---|
| ① | 更新判定 8 口径：版本号展示、是否最新、缓存包选择、增量适用 | `:282`–`:362` | 81 | 纯值；读盘用取值闭包注入 | `EnvRules.displayVersion` / `isUpToDate` / `planUpdate` | 否 |
| ② | 老 manifest 的 version/url/sha256 解析 | `:368`–`:370` | 3 | 纯（三条正则） | `EnvRules.parseLegacyManifest` | 否 |
| ③ | 安装状态 → 图标 / 错误色 / 收尾行 / 重试入口 | `:434`–`:472` | 39 | 只取 `InstallProgress.Phase` 枚举 | `EnvRules.phaseView` | 否 |
| ④ | 修复环境的本地包合格条件（严格 > 100MB） | `:578` | 1 | 纯 | `EnvRules.isRepairCandidate` | 否 |
| ⑤ | 下载进度 10% 一档的节流（增量＋全量两处） | `:744`–`:745`、`:806`–`:807` | 4 | 纯 | `EnvRules.progressPercent` | 否 |

## 三、明确**不抽**的块（写下来是为了下一批别再问一遍）

| 块 | 为什么不抽 |
|---|---|
| 索引拉取与整条降级链路（`:282`–`:398` 的网络部分） | 副作用编排（HTTP、镜像兜底、RunLog），抽出去等于把 IO 搬进 helper |
| 两条下载/安装执行流（`:687`–`:819`） | 全是 IO 与 InstallFlow 状态机，65 批刚动过，本批零接触 |
| `InstallFlow.isRunning()` 的六处 `enabled` | 可点性判据本身已在 `InstallFlow` / `EnvInstallGate`，再包一层是同义反复 |
| 裸 `Thread {}` 两处 | `BareThreadGuardTest` 的冻结点，动它要另开单 |
| 所有 Compose 视觉树 | 派工单红线：视觉/布局零变化 |

## 四、片段划分的边界判据（供验收核对）

1. **值进值出**：5 个片段的入参只有 `String?`/`String`/`Long`/`Boolean`/枚举/两个取值闭包；出参只有 `String`/`Int?`/`Boolean`/数据类。
2. **不碰执行**：没有一个片段能触发安装、下载、写盘、Toast、导航。
3. **文案不搬**：`ctx.getString` / `stringResource` 全留页面，helper 只回「哪一句」的枚举。
4. **读盘次数守恒**：`RootfsCache.currentVersion` 与 `localCandidateFor` 原本只在需要它的分支里读，改后仍是（靠取值闭包，不提前算值）——这一条有单测钉住（`判定_指纹一致是最新_且一个盘都不读`）。
