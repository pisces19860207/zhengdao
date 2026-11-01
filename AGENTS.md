# 给 AI agent 的开工须知（证道 zhengdao）

> **两仓分工（2026-10-27 起）**：终端（PTY/rootfs/proot/终端 UI）已整仓拆到
> [zhengdao-term](https://github.com/pisces19860207/zhengdao-term)，**主仓不含终端代码、不维护终端**；
> 终端相关的活、排查与文档去 zhengdao-term（或私有侧历史文档）。
>
> 这个仓库同时有多个 agent 在干活（WorkBuddy / Zcode / DSH 桌面版）。**细则看 [`docs/协作规约.md`](docs/协作规约.md)。**
> 历史上因为「不核对就开工」，同一个功能被反复实现、删了又加回来（实锤见全量台账 §4——公开仓 `docs/FEATURE-LEDGER.md` 已是占位，全文在私有侧 `..\_zd-private\docs\FEATURE-LEDGER-全量-*.md`；2026-10-28 注）。
> **先花 30 秒核对，能省掉半天重做。**
> 开工前**先读台账**：公开仓 `docs/FEATURE-LEDGER.md` 只是占位，**全量在私有侧** `..\_zd-private\docs\FEATURE-LEDGER-全量-*.md`（公开摘要见 `docs/台账-公开摘要.md`） —— 那里写着已经做完并有证据的、待做的、以及明确判定不做的。

## 四条铁律

1. **先同步，再动手。** `git fetch origin` 之后确认
   `git rev-list --left-right --count origin/main...HEAD` 的**左侧是 0**。
   落后就别写代码 —— 你看到的代码不是大家看到的代码。
2. **先查重，再动手。** 用功能关键词在全部历史里搜一遍，并读私有侧全量台账的 §2 / §3（公开仓只有摘要）。
   **已经被拍板删除的功能不许复活**，除非用户再次明确要求。
3. **不要在主工作树上直接改。** `AndroidStudioProjects/zhengdao` 是集成用的主工作树；
   一个 agent 一个分支 / 一个工作树（`git worktree add`），做完再合。
   *例外*：**集成本来就该在 main 上做** —— `merge --no-ff` 是允许的（钩子对合并提交放行）。
4. **先核最新，再动手。** 凡是涉及第三方 API / SDK / 模型 / 平台政策 / 安全边界的活，开工前先搜**官方文档/changelog**（用当前日期，别拿训练语料当依据），把来源链接＋版本/日期写进交付报告；没有官方来源就标“未核最新”。报告缺这一节，验收不销账。
   - 同时先查**现成方案/官方库**：能复用就不重造（FTS5、WorkManager、MediaStore、Markdown 渲染、JSON Schema、MCP SDK 等）；但云服务/重运行时（Temporal/LangGraph/Playwright/React）不硬搬，先写“为什么不用”。

## 开工前跑这条命令（唯一入口，纯 Python 标准库）

```powershell
cd C:\Users\guoli\AndroidStudioProjects\zhengdao
# （2026-10-28 注：示例里的 `-k 终端,单会话 -p …\terminal\…` 是终端还在本仓时的旧例，
#   终端已拆出到 zhengdao-term——关键词与 -p 路径请换成你本单实际要动的文件。）
python tools\zd.py preflight -k <功能关键词> `
       -p app\src\main\java\com\example\zhengdao\你要新增的文件.kt
```

退出码 **0 = 可以开工**；**1 = 有阻塞项**（落后 / 关键词命中 / 复活风险），先看它列出来的东西。

其它两条常用命令：

| 命令 | 什么时候用 |
|---|---|
| `python tools\zd.py doctor` | 环境/仓库奇怪的时候（钩子、keystore 指纹、build.yml、关键文档一次全查） |
| `python tools\zd.py install-hooks` | 新克隆、或 `hooks-status` 报「与库里不一致」时 |

> `tools/agent-preflight.ps1`、`tools/install-hooks.ps1` 仍可用，但**只是 `zd.py` 的 Windows 壳**。
> 本机 ExecutionPolicy 是 Restricted，跑 `.ps1` 记得 `powershell -ExecutionPolicy Bypass -File …`。

## 硬闸：提不上去，说明你做错了

`.git/hooks/pre-commit`（源文件 `tools/hooks/pre-commit`，用 `python tools\zd.py install-hooks` 装到共享 `.git`；**合并提交**走 `pre-merge-commit`，它转交给同一个脚本 —— 见 `..\_zd-private\docs\ERRATA.md` E-103；`docs/ERRATA.md` 已于 2026-10-20 移出公开仓）
会在三种情况下**拒绝提交**：

| 拦什么 | 为什么 |
|---|---|
| 在 `main` 上直接提交（**合并提交除外**） | 主工作树是集成用的，直接改会让别的 agent 把你的在途改动冲掉 |
| 你改的文件，`origin/main` 上你还没同步的提交也改过 | 你在旧版本上改，提交上去会覆盖别人的改动 |
| 你要新增的文件，历史上被删除过 | 复活检测：被拍板删掉的功能不许悄悄加回来 |

确实需要绕过：`ZHENGDAO_HOOK_BYPASS=1 git commit ...`，**并在提交信息里写明为什么**。

## 该读哪个文件

> **工程文档已移出公开仓（2026-10-20）**：勘误、派工单、设计/验收/交接这批「机制与踩坑」文档不在本仓，私有副本在工作区根 `..\_zd-private\docs\`（本机）。本仓 `docs/` 只留用户向文档、在飞表与台账/规约。

| 想知道 | 读 |
|---|---|
| 多主体怎么协作、边界在哪、工具怎么用 | `docs/协作规约.md`（全文在 `..\_zd-private\docs\协作规约.md`） |
| **已经做完 / 待做的结账总表（新会话先读这个）**、现在有哪些功能、哪些半残、哪些被拍板删过 | 私有侧全量台账 `..\_zd-private\docs\FEATURE-LEDGER-全量-*.md` 的「结账 · 现状总表」＋ §2 / §3（公开仓 `docs/FEATURE-LEDGER.md` 2026-10-27 起只是占位，不含条目） |
| **抱朴现行架构/刀0–刀5对应**（旧方案只作历史） | `docs/抱朴-现行架构-2026-10-24.md` |
| **谁在改哪些文件（文件租约）** | 私有侧 `..\_zd-private\docs\在飞.md`（公开仓 `docs/在飞.md` 只是占位；`tools/check-inflight.py` 已优先读私有侧） |
| 踩过的坑与更正（E-xxx，按编号查） | `..\_zd-private\docs\ERRATA.md` |
| 终端 / rootfs 出问题怎么排查 | **终端已搬出** ⇒ 去 `zhengdao-term` 仓；历史排查记录在 `..\_zd-private\docs\milestones\证道-故障排查手册.md` |
| 终端为什么只有一个会话 | 同上，属**新仓**话题；历史分析在 `..\_zd-private\docs\milestones\证道-终端单会话模型.md` |
| 版本计划、里程碑、验收 | `..\_zd-private\docs\milestones\`、`..\_zd-private\docs\acceptance\` |

## 工具去工具房拿（2026-11-01）
本机所有 agent 共用一个工具房：`..\_zd-toolroom\`（索引 `_index.md` 同目录 `_索引.md`）。
· 要工具先来这儿拿，**别在本仓再造一套**；
· 本仓 `tools/` 里的脚本**跟代码走**（要过 review），不搬进工具房；
· 一次性脚本用完归档，不要散落在项目根目录；
· 构建锁：`powershell -ExecutionPolicy Bypass -File ..\_zd-toolroom\02-build\build-lock.ps1 -Run ":app:testDebugUnitTest"`。

## 参考范例：2026-11-01 的一次"顺"是怎么做到的（新会话照着做）
> 用户点评：「今天执行得很好，记一笔，其他 agent 开工会读。」——**这不是表扬，是可复制的做法。**

当天状态：**10 单并行、零租约相撞、CI 两仓全绿、磁盘没涨**。做到的四件事：
1. **先冻结派单**：要验收的活没验完，不派新单（避免"新旧混着改"）；
2. **租约写窄**：只列"我要动的文件"，不占目录；发现别人租约过宽就**提请收窄**（当天 341 就是这么让出 `agent/tools/` 的）；
3. **每单带判据＋军令状**：交付时逐项 DONE(证据)/ABANDONED(原因)，**缺项＝未完成**（不是扣分）；
4. **收工交清洁回执**：临时脚本删、截图删/归档、分支工作树删、锁释放、记磁盘前后——**不干净不算完成**。

**两条配套（当天验证有效）**
- **构建排队**：同一时刻只跑一个 Gradle 构建（`_zd-toolroom/02-build/build-lock.ps1`）；不排队就出现 12 个 java 抢内存；
- **别让 Gradle 自己下 JDK**：本机已装 Temurin 25（`~/.jdks/jdk-25.0.5+8`），构建前指 `JAVA_HOME` 过去。

**一句话**：**顺不是运气，是"先冻结、租约窄、判据硬、收工净"这四件同时做到。**

## 清洁分工三层（2026-11-01 定，别越界）
| 层 | 谁 | 清什么 | 不清什么 |
|---|---|---|---|
| **①工位** | **承接方自己** | 自己的一次性脚本、临时截图、日志、占用的锁/daemon | **分支、工作树、在飞行**（那是账，不是垃圾） |
| **②账** | **合入方**（验收/整合会话） | 合并后删分支（远程删需用户点头）、删工作树、在飞表销行 | 别人正在干的工作区 |
| **③走廊** | **尚宫局**（清 C 盘的会话） | 公共 temp、根目录散落物、已合并且空闲 >60 分钟的 build 产物 | 正在构建的树；一单未合的产物 |
**两条硬规矩**：① **不干净不算完成**（收工交清洁回执：七项各一行）；② **尚宫局只清"公共的/废弃的"，不替人打扫工位**。
**为什么分支不能自删**：还没合并就删＝丢工作；删分支是合入动作的一部分，不是清洁动作。

## detekt 预算：以"合并后的实测值"为准，**自己不准往上加**（2026-11-01 定）
1. **以降低后的数为准**：每批降债合并后预算 = 当批实测值（341 交完记 ≈406，之后按实测继续降）；
2. **没降就是没降**：拆不动或没拆，如实写"未降"，不许把预算说成"预估值"糊过去；
3. **降了就是降了**：合并后立即重记两处（`config/detekt/issue-budget.json` 的 total/zones ＋ `config/detekt/detekt.yml` 的 `# maxIssues`）；
4. **任何人不得自行抬高预算**：你的新增必须同批清掉等价条数（净零）；确实需要上调 ⇒ **必须用户批准**并在 `raised_reason` 写清"口径修正/为什么"，否则视为违规（门禁会红）。
**判据**：合并后 `total` == 当批实测 XML 条数（差 1 都不行）。
