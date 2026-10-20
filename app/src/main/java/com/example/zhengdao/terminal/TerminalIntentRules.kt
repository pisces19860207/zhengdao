// 独立开发声明：本文件为本项目从零编写。
package com.example.zhengdao.terminal

/**
 * 一次「进终端请求」：要执行的命令 ＋ 它点名要进的 agent。
 *
 * @property cmd 要注入终端执行的命令（Intent extra `autocmd`）；null = 只是"带我去看看终端"。
 * @property agentId 命令点名要进的 agent（extra `agent_id`）；**只在有命令时**才可能非 null。
 */
internal data class TerminalRequest(
    val cmd: String?,
    val agentId: String?,
)

/**
 * 从 Intent extras 判出「这一次进终端要干什么」（纯逻辑，可单测）。
 *
 * 两条规则都是 2026-10-07 起在 [com.example.zhengdao.TerminalActivity.readRequestFromIntent]
 * 里长出来的既有口径，本次只是**等值搬移**，行为零变化：
 *
 * 1. `autocmd` 空白 ⇒ 整次请求无效（`cmd` 与 `agentId` 都 null）：空白命令注入终端
 *    等于替用户敲了一次回车。
 * 2. `agent_id` **只在带着命令时才算数**：安装中的「已装好，去看看」按钮（HomeScreen）
 *    会传 `agent_id` 但不带命令——那只是"带我去看看正在跑的安装"，不该触发任何会话切换。
 *    若把单有 agent_id 的 Intent 当成一次切换请求，第二次点「安装」就会被带到错的 agent。
 *
 * @param autocmdRaw Intent 里的 `autocmd` 原值（可空、可空白）。
 * @param agentIdRaw Intent 里的 `agent_id` 原值（可空、可空白）。
 */
internal fun deriveTerminalRequest(autocmdRaw: String?, agentIdRaw: String?): TerminalRequest {
    val cmd = autocmdRaw?.takeIf { it.isNotBlank() }
    return TerminalRequest(
        cmd = cmd,
        agentId = if (cmd == null) null else agentIdRaw?.takeIf { it.isNotBlank() },
    )
}
