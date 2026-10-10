# 给 AI agent 的开工须知（证道 zhengdao）

> 这个仓库同时有多个 agent 在干活（WorkBuddy / Zcode / DSH 桌面版）。**细则看 [`docs/协作规约.md`](docs/协作规约.md)。**
> 历史上因为「不核对就开工」，同一个功能被反复实现、删了又加回来（实锤见 `docs/FEATURE-LEDGER.md` §4）。
> **先花 30 秒核对，能省掉半天重做。**
> 开工前**先读 [docs/FEATURE-LEDGER.md](docs/FEATURE-LEDGER.md) 的「结账 · 现状总表」** —— 那里写着已经做完并有证据的、待做的、以及明确判定不做的。

## 三条铁律

1. **先同步，再动手。** `git fetch origin` 之后确认
   `git rev-list --left-right --count origin/main...HEAD` 的**左侧是 0**。
   落后就别写代码 —— 你看到的代码不是大家看到的代码。
2. **先查重，再动手。** 用功能关键词在全部历史里搜一遍，并读 `docs/FEATURE-LEDGER.md` §2 / §3。
   **已经被拍板删除的功能不许复活**，除非用户再次明确要求。
3. **不要在主工作树上直接改。** `AndroidStudioProjects/zhengdao` 是集成用的主工作树；
   一个 agent 一个分支 / 一个工作树（`git worktree add`），做完再合。
   *例外*：**集成本来就该在 main 上做** —— `merge --no-ff` 是允许的（钩子对合并提交放行）。

## 开工前跑这条命令（唯一入口，纯 Python 标准库）

```powershell
cd C:\Users\guoli\AndroidStudioProjects\zhengdao
python tools\zd.py preflight -k 终端,单会话 `
       -p app\src\main\java\com\example\zhengdao\terminal\你要新增的文件.kt
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

`.git/hooks/pre-commit`（源文件 `tools/hooks/pre-commit`，用 `python tools\zd.py install-hooks` 装到共享 `.git`）
会在三种情况下**拒绝提交**：

| 拦什么 | 为什么 |
|---|---|
| 在 `main` 上直接提交（**合并提交除外**） | 主工作树是集成用的，直接改会让别的 agent 把你的在途改动冲掉 |
| 你改的文件，`origin/main` 上你还没同步的提交也改过 | 你在旧版本上改，提交上去会覆盖别人的改动 |
| 你要新增的文件，历史上被删除过 | 复活检测：被拍板删掉的功能不许悄悄加回来 |

确实需要绕过：`ZHENGDAO_HOOK_BYPASS=1 git commit ...`，**并在提交信息里写明为什么**。

## 该读哪个文件

| 想知道 | 读 |
|---|---|
| 多主体怎么协作、边界在哪、工具怎么用 | `docs/协作规约.md` |
| **已经做完 / 待做的结账总表（新会话先读这个）**、现在有哪些功能、哪些半残、哪些被拍板删过 | `docs/FEATURE-LEDGER.md` 的「结账 · 现状总表」+ §2 / §3 |
| 踩过的坑与更正（E-xxx，按编号查） | `docs/ERRATA.md` |
| 终端 / rootfs 出问题怎么排查 | `docs/milestones/证道-故障排查手册.md` |
| 终端为什么只有一个会话（改动重灾区） | `docs/milestones/证道-终端单会话模型.md` |
| 版本计划、里程碑、验收 | `docs/milestones/`、`docs/acceptance/` |
