// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百三十一批 · 子智能体 v2 ②「工具白名单（收窄兑现）」的**判据层**：
// 每子任务一张名单（`SubTask.tools` 读 + `SubTask.writes` 父显式授予的写），
// 名单外的调用一律拒；`run_subtasks` 永远不在名单里（禁递归）。
//
// 为什么判定放在这里而不是装配层：装配层的 lambda 测不到（要 Context＋注册表），
// 而「谁的调用该被拒」是纯判据——放这里，JVM 单测喂两条数据就能钉住。
package com.example.zhengdao.baopu.agent.multiagent

internal object SubAgentWhitelist {

    /**
     * 子任务永远不许碰的工具（v2 禁递归：子子孙孙不许再拆）。
     *
     * 597 加 `portrait_appearance`：它的 describe 是**全仓唯一**把图片字节发出去的动作、
     * merge 是改卡——两条都必须**人在场**（CONFIRM 卡/467 租约在主管线）；而子 Agent 的
     * 执行器是直调（不经过弹卡），一旦进名单就是无人在场外发。`portrait_look` 只读，可留。
     * 610 加 `novel_readthrough_run`：一次调用会连烧几十上百次模型请求＋写盘——同样必须
     * 人在场（只读预检的 `novel_readthrough` 可留）。
     */
    val FORBIDDEN: Set<String> = setOf(
        SubTaskTool.NAME,
        "portrait_appearance",
        "novel_readthrough_run",
    )

    /** 工具名上限（防一条 grant_writes 塞长文；正常工具名远短于此）。 */
    const val MAX_NAME_CHARS = 60

    /**
     * 生效的**写类**授权 ＝ 父显式给的 `grant_writes` ∩ 父会话可用 ∩ 真写类。
     *
     * 为什么不收进 [SubAgentRules.effectiveWhitelist]：读名单的口径是「只能收窄」，
     * 写名单的口径是「不显式给就一件都没有」——两条口径混在一个函数里，
     * 「模型顺手在 tools 里写了 write_file」就会变成默许。分开放，混不进来。
     *
     * @param isWrite 真写类判定（生产由注册表 `ToolSpec.readOnly` 反推；未知工具按写处理）
     */
    fun effectiveWrites(
        granted: List<String>?,
        available: Set<String>,
        isWrite: (String) -> Boolean,
    ): List<String> {
        if (granted.isNullOrEmpty()) return emptyList()
        val out = mutableListOf<String>()
        granted.forEach { raw ->
            val name = raw.trim().take(MAX_NAME_CHARS)
            if (name.isEmpty() || name in FORBIDDEN) return@forEach
            if (name !in available) return@forEach
            if (!isWrite(name)) return@forEach // 读类走 tools 那张表，不在写名单里重复
            if (name !in out) out += name
        }
        return out
    }

    /** 这条子任务总共能用哪些工具（读＋写；顺序稳定：先读后写）。 */
    fun allowed(task: SubTask): Set<String> = (task.tools + task.writes).toSet()

    /** 一次调用的放行判定。 */
    sealed interface Decision {
        /** 放行。 */
        object Allow : Decision

        /** 拒（带回灌给人话理由；调用点同时落一条 REJECT 账）。 */
        data class Reject(val reason: String) : Decision
    }

    /**
     * 该不该放行这次调用。
     *
     * 禁递归排在最前：即使某天有人把 `run_subtasks` 塞进可用集或写名单，
     * 这条判定也不放行——「子任务不能再拆」是 v2 的硬口径，不靠名单维护。
     *
     * 名单**空**（读名单与写名单都没解析出东西）时不在这里拦：那是 v1 的
     * 「用户把读工具全关了 / 纯凭 input 作答」形状，执行层仍会按空名单拒
     * （[com.example.zhengdao.baopu.BaopuRuntime] 那一层，v1 既有口径）。本层
     * 只负责「有名单时名单说了算」——把两种拦截混在一起，会计出两笔账。
     */
    fun decide(toolName: String, task: SubTask): Decision = when {
        toolName in FORBIDDEN -> Decision.Reject(
            "$toolName 在子任务里不可用（v2 禁递归）：子任务不能再拆，自己把这件事做完。" +
                "确实要拆，由父任务拿到结论后另行决定。",
        )

        allowed(task).isEmpty() -> Decision.Allow

        toolName !in allowed(task) -> Decision.Reject(
            "$toolName 不在这条子任务的白名单里（可用：${allowed(task).joinToString("、")}）",
        )

        else -> Decision.Allow
    }
}
