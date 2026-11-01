# 给 Claude / 通用 agent 的入口（与 AGENTS.md 同源）

> 本仓的开工须知只有**一份**：见 [`AGENTS.md`](AGENTS.md)。本文件只是入口，**不要在这里写第二套规矩**（两套必漂移）。

必须知道的三句：
1. **先同步再动手**：`git fetch origin` 后 `git rev-list --left-right --count origin/main...HEAD` 左侧必须是 0；
2. **不要在主工作树直接改**：一个 agent 一个分支/工作树；`merge --no-ff` 允许；
3. **工具去工具房拿**：`..\_zd-toolroom\`（索引 `_索引.md`），别在本仓再造一套。

细则：`docs/协作规约.md`（多主体协作、租约、构建锁、验收五步）。
