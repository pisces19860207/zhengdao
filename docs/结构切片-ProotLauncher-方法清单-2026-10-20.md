# 结构切片 · ProotLauncher 方法清单（第七十五批 · 2026-10-20）

> 派工单：`docs/派工单-2026-10-20-第七十五批-ProotLauncher切片.md`（改单版，目标 `terminal/ProotLauncher.kt`）。
> 本清单是「先出方法清单，再动手」这一步的产物；行号按开工时 `origin/main`（`4771de6e`）实测。

## 一、顶层成员（843 行全文件）

| 成员 | 行数 | 位置 | 依赖 | 可抽性 |
|---|---|---|---|---|
| `buildLaunchPlanLocked()` | 575 | `:112`–`:686` | Context、filesDir、File 写、EnvSelfHeal、IssueCenter、Workspace、AppPrefs、RunLog、RootfsInstaller（锁） | 编排留驻；内部 5 个纯片段抽出（见下表） |
| rootfs 预置写盘块 | ~207 | `:240`–`:446` | DNS/时区/skel/hermes/zzclean/npm/PATH/git/资料库/tmux conf 全写盘 | 不可抽（全是副作用，原位保留） |
| env 变量表 | 49 | `:451`–`:499` | 纯字符串拼装（值来自 prootTmp/rtDir/nativeDir/loaderBin 路径） | **抽**（片段①） |
| args 组装区 | 129 | `:501`–`:629` | 混排：固定段纯、存储 bind 纯 + 软链/mkdirs 副作用、cache bind 副作用、mem 读 AppPrefs、收尾命令纯 | **抽**（片段②，副作用编排留驻） |
| 启动横幅块 | 46 | `:633`–`:678` | 写盘（profile.d + pending 文件） | 不可抽 |
| pinned 资产校验块 | 61 | `:152`–`:212` | sha256Of、IssueCenter、戳记读写 | 不可抽（IO + 报告） |
| `tmuxConfAppend()` | 29 | `:742`–`:770` | **已纯**（`TmuxConfAppendTest` 覆盖） | 已是样板，不搬 |
| `sharedCacheBindings()` | 14 | `:794`–`:807` | **已纯**（`SharedCacheBindTest` 覆盖） | 已是样板，不搬 |
| `fallbackPlan()` | 13 | `:831`–`:843` | File 路径 → `LaunchPlan`（路径拼装，无 IO） | **抽**（片段④） |
| `bindSharedCache()` | 21 | `:809`–`:829` | File mkdirs + RunLog | 不可抽 |
| `storageGranted()` | 5 | `:56`–`:60` | Context/Android 权限 | 查询留驻，判定抽出（片段③） |
| `buildLaunchPlan()` | 9 | `:81`–`:89` | `require` + 持锁调用 | 原位保留（持锁不动） |
| `isLegalSessionName()` | 3 | `:97`–`:99` | **已纯** | 已是样板，不搬 |
| `sha256Of()` | 12 | `:63`–`:74` | File IO | 不可抽 |
| `appendWheelBindIfMissing()` | 14 | `:779`–`:792` | 纯小函数 | 随 tmuxConfAppend 留驻 |

## 二、本单抽出的 5 个纯片段（落 `terminal/ProotLaunchRules.kt`）

| # | 片段 | 原位置 | 纯函数入口（输入 → 输出） | 留驻的副作用 |
|---|---|---|---|---|
| ① | env 变量表 | `:451`–`:499` | `envEntries(tzName, prootTmp, rtDir, nativeDir, loaderBin) → List<String>` | `tzName` 的 zoneinfo 文件查询留在调用点 |
| ② | proot 参数串 | `:501`–`:629` | `baseArgs(proot, rootfs, home)`；`sharedStorageBindArgs()`；`guestSymlinkTargetFor(hostPath)`；`workspaceBindArg(wsHost)`；`storeRootBindArg(storeRoot, guestRoot)`；`memLimitPrefix(mb)`；`parseMemLimitMb(raw)`；`guestCommandArgs(session, memPrefix, hasTmux)` | 软链刷新、mkdirs、cache bind、AppPrefs 读、tmux 文件查询、RunLog 全留驻 |
| ③ | 存储/权限判定 | `:515` / `:573` 两处重复 | `sharedStorageAllowed(granted, wsShared) → Boolean` | `storageGranted(context)`、`Workspace.isShared(context)` 查询留驻 |
| ④ | 回退计划 | `:831`–`:843` | `fallbackPlan(filesDir, cacheDir) → LaunchPlan` | 两个早返点（`:149`/`:234`）与 IssueCenter 报告留驻 |
| ⑤ | 启动就绪/回落判定 | `:219` | `rootfsReady(prootIsFile, prootExec, loaderIsFile, markerIsFile) → Boolean` | 四个文件查询与 `if (installMarker.isFile)` 报告留驻 |

## 三、本单明确不动的

- 写盘、`RunLog` 日志点、`buildLaunchPlan` 对 `RootfsInstaller.withPrepareLock` 的持锁调用；
- `RootfsInstaller` / `RootfsDownloader`（65 在飞）、`TerminalActivity`（74）、`RunEngine`（64）、`BaopuChatController`（72）；
- `ProotBinaries` / `PinnedAssets` / `Workspace` / `Store` / `EnvSelfHeal` 只读协作类；
- 参数顺序/内容、启动结果、行为、文案：零变化。
