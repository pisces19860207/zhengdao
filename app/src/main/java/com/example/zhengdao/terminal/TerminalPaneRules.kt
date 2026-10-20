// 独立开发声明：本文件为本项目从零编写。
package com.example.zhengdao.terminal

/**
 * tmux 分屏块数的客户端计数规则（纯逻辑，可单测）。
 *
 * ## 为什么是"客户端自己数"而不是问 tmux
 * 分屏走即时键绑定 `C-b "`，没有回执；`C-b :` 命令提示符那条路有固定延迟的竞态
 * （第二十二批已改掉，本文件不改回）。宁可计数保守，也不要为一条计数去发一条
 * 可能命中竞态、把按键打进 pane 前台程序 stdin 的命令。
 *
 * `-1` = **未知**：attach 到旧会话时服务端有几块我们问不到（见 `TerminalActivity.paneCount`
 * 的 KDoc）。未知时的取舍照搬既有行为：
 * - 分屏：按"开出来就是两块"记（`-1`/`0` 都进 [nextPaneCountAfterSplit] 的 else 分支）；
 * - 关闭：至少还留一块（`1` 及以下都收敛到 `1`）。
 *
 * 最坏情况是未知态下偶发分出 3 块，用户点黄点就能收回——好过为计数冒竞态。
 */

/** 点绿点分屏之后的新块数：已知 n（≥1）→ n+1；未知（≤0）→ 2。 */
internal fun nextPaneCountAfterSplit(current: Int): Int =
    if (current > 0) current + 1 else 2

/** 点黄点关掉当前分屏之后的块数：n（≥2）→ n-1；否则收敛到 1。 */
internal fun nextPaneCountAfterClose(current: Int): Int =
    if (current > 1) current - 1 else 1
