// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.ui

import android.content.Context

/**
 * Agent 安装状态仓库（M3 骨架 §2 简化版）：
 * - 「已安装」仍以真实文件探测为准（AppState.agents）——安装命令可能由用户在终端
 *   手动执行，文件是唯一可信事实；
 * - 本仓库补充「安装中」瞬态（点 [安装] 时登记）：卡片据此禁用按钮并轮询，
 *   装好（文件出现）自动转 [启动]；**15 分钟超时兜底**转「未完成」，防卡片卡死。
 */
object AgentRepository {

    private const val PREFS = "zhengdao-agent-state"
    private const val INSTALLING_TTL_MS = 15 * 60 * 1000L

    enum class State { NotInstalled, Installing, Installed, Failed }

    fun stateOf(ctx: Context, agent: AppState.AgentInfo): State {
        if (agent.installed) {
            clearInstalling(ctx, agent.id) // 幂等清理：装好后残留的安装中标记
            return State.Installed
        }
        val started = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong("installing_${agent.id}", 0L)
        return when {
            started <= 0L -> State.NotInstalled
            System.currentTimeMillis() - started < INSTALLING_TTL_MS -> State.Installing
            else -> State.Failed // 超时且文件未出现：上次安装未完成
        }
    }

    /** 点 [安装] 时登记（TerminalActivity 在 autocmd 派发时调用）。 */
    fun markInstalling(ctx: Context, agentId: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putLong("installing_$agentId", System.currentTimeMillis()).apply()
    }

    fun clearInstalling(ctx: Context, agentId: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove("installing_$agentId").apply()
    }
}
