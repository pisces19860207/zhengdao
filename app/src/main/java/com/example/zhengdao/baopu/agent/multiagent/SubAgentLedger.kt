// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百三十一批 · 子智能体 v2 ③「账归属」：子 Agent 的**每条账行**都带
// `agent_id`（子任务 id）＋`parent_id`（分解 id）——能按子任务分组看「谁干了什么、
// 哪次调用被拒、撤回了什么」。
//
// 为什么不再往 `run_log` 里塞列：run_log 是**父会话**的步账（一步一行、由引擎
// 单点写），子 Agent 的一步不是父的一步（并发、重试、多条并行），混进去会让
// 「第几步」出现两种答法。这里单开一张只记子 Agent 事实的表（v23），分组天然。
package com.example.zhengdao.baopu.agent.multiagent

/** 账行种类（闭集五格：模型轮/工具调用/被拒/写前入库/撤回）。 */
internal enum class SubAgentLedgerKind {
    /** 一轮模型回复（ok＝这一轮没失败）。 */
    ROUND,

    /** 一次被允许的工具调用（ok＝工具返回 Ok）。 */
    TOOL,

    /** 一次被**拒**的调用（白名单外/禁递归/越界路径）——拒也要落账，不是静默。 */
    REJECT,

    /** 写类动作前的原件入库（470 保险箱）。 */
    WRITE,

    /** 子任务未成功 ⇒ 撤回它的写（回滚结果）。 */
    ROLLBACK,
    ;
}

/**
 * 一条子 Agent 账行。
 *
 * [agentId] 是子任务 id（`sg-…-<序号>`），[parentId] 是分解 id；两列都在行上，
 * 不靠 join 推算——账要能单表分组（派工单③的原话）。
 * [detail] 只存摘要（截断 [SubAgentLedger.MAX_DETAIL]），不存正文。
 */
internal data class SubAgentLedgerRow(
    val agentId: String,
    val parentId: String,
    val kind: SubAgentLedgerKind,
    val tool: String = "",
    val ok: Boolean = true,
    val detail: String = "",
    val atMs: Long = 0L,
)

/** 落账出口（生产＝SQLite；JVM 单测＝内存假件）。写失败只 log，不拦执行。 */
internal fun interface SubAgentLedgerSink {
    fun append(row: SubAgentLedgerRow)
}

/** 账的查询口（分组/按分解取）。 */
internal interface SubAgentLedgerStore : SubAgentLedgerSink {
    fun rowsOf(parentId: String): List<SubAgentLedgerRow>
}

/** 内存账（JVM 单测与「库没装好」时的兜底：不丢事实，只是不持久）。 */
internal class MemorySubAgentLedger : SubAgentLedgerStore {
    private val rows = mutableListOf<SubAgentLedgerRow>()

    override fun append(row: SubAgentLedgerRow) {
        synchronized(rows) { rows += row }
    }

    override fun rowsOf(parentId: String): List<SubAgentLedgerRow> =
        synchronized(rows) { rows.filter { it.parentId == parentId } }
}

internal object SubAgentLedger {

    /** 行内摘要上限（账不是日志：够认出「哪次、什么原因」就够）。 */
    const val MAX_DETAIL = 160

    /** 造一行（统一截断；kind/tool/ok 由调用点给）。 */
    fun row(
        task: SubTask,
        kind: SubAgentLedgerKind,
        tool: String = "",
        ok: Boolean = true,
        detail: String = "",
        nowMs: Long = System.currentTimeMillis(),
    ): SubAgentLedgerRow = SubAgentLedgerRow(
        agentId = task.id,
        parentId = task.parentId,
        kind = kind,
        tool = tool,
        ok = ok,
        detail = SubAgentRules.truncate(detail, MAX_DETAIL),
        atMs = nowMs,
    )

    /** 按子任务分组（判据③「可按子任务分组」的纯函数形态；组内保持落账顺序）。 */
    fun groupByAgent(rows: List<SubAgentLedgerRow>): Map<String, List<SubAgentLedgerRow>> =
        rows.groupBy { it.agentId }

    /** 一笔分解里被拒的条数（回执/体检用；不参与放行判定）。 */
    fun rejectedCount(rows: List<SubAgentLedgerRow>): Int =
        rows.count { it.kind == SubAgentLedgerKind.REJECT }

    /** 一行人话（交付/日志用）：`分解 X：3 条子任务 / 12 行账 / 2 次被拒`。 */
    fun summaryLine(rows: List<SubAgentLedgerRow>): String {
        val grouped = groupByAgent(rows)
        return "子 Agent 账：${grouped.size} 条子任务 / ${rows.size} 行 / " +
            "${rejectedCount(rows)} 次被拒"
    }
}
