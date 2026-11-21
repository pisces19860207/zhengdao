// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第七百七十八批 · 读数**投影器**（:app 侧；零新增生产、零算法改动、零写口）。
//
// 职责只有一件：把四类**已存在的读数产物**只读投影成 policy 侧的 [ReadingFacts]。
//   · 753 环读数账本：现成落盘产物（716 真机轮写过 `产物/环读数.jsonl`），本文件只调
//     [LoopReadingsLedger.filesOf]/[read] 读回，从行里派生"卡在哪一环/同参重试/RPE 连负同因"；
//   · 722 三行、747 越界：生产侧本批之前未装配（背景事实），因此走**可选只读注入位**
//     （函数参数），不注入＝未装配＝视为无——绝不为了"让规则命中"去凭空造读数。
//
// 为什么投影器在 :app 而不在 :policy：753 的 [LoopReadingRow] 是 internal 且带 org.json 解析，
// `:policy` 白名单够不着（PolicyBoundaryGateTest 钉死）；投影是"把 app 的形状翻译成 policy 的
// 纯数据"，天然属于装配侧。翻译只做**读取与归类**，不做判定——判定在 ReadingCodex，决定在
// MediatorDecide（判定权不迁）。
//
// 本文件零写口：没有 appendText／writeText／File 写入／DecisionLedger.record（判据④源码断言钉）。
package com.example.zhengdao.baopu

import com.example.zhengdao.baopu.loops.Loop
import com.example.zhengdao.baopu.loops.LoopReadingsLedger
import com.example.zhengdao.baopu.loops.LoopReadingRow
import java.io.File

object ReadingFactsAdapter {

    /**
     * 一次只读投影。
     *
     * @param workspace 工作区根（从 BaopuRuntime.workspaceRoot 取），只读 753 账本；
     *   null＝没有工作区（早期启动）⇒ 753 一路视为无。
     * @param readbackPresent 722 三行是否装配（未装配传 false）；本批无装配点 ⇒ 恒 false。
     * @param crossedWithoutEvidence 747 越界无证据快照（未装配传 false）；本批无装配点 ⇒ 恒 false。
     */
    fun project(
        workspace: File?,
        readbackPresent: () -> Boolean = { false },
        crossedWithoutEvidence: () -> Boolean = { false },
    ): ReadingFacts {
        val rows = workspace?.let { readLoopRows(it) } ?: emptyList()
        val plan = stuckFacts(rows)
        val rpe = rpeFacts(rows)
        return ReadingFacts(
            readbackPresent = safe(readbackPresent),
            stuckLoop = plan.first,
            previousStuckLoop = plan.second,
            sameParamRetries = plan.third,
            rpeNegativeStreak = rpe.first,
            rpeCauseSame = rpe.second,
            crossedWithoutEvidence = safe(crossedWithoutEvidence),
        )
    }

    /** 只读读回 753 账本的全部合法行（坏行由 ledger 自己跳过）；不做任何写入。 */
    private fun readLoopRows(workspace: File): List<LoopReadingRow> =
        LoopReadingsLedger.filesOf(workspace).flatMap { LoopReadingsLedger.read(it) }

    /** 内置注入位抛错一律吞成"无"——消费读数不许把弹卡链带崩。 */
    private fun safe(provider: () -> Boolean): Boolean = runCatching { provider() }.getOrDefault(false)

    /**
     * 753 卡步事实：取最近一条 plan 行的 stuckAt 作为"本次卡步"，前一条作为"上次卡步"，
     * 两者相等即"卡步相同"；同参重试＝最近连续 N 条 plan 行 stuckAt 相同且 ref 相同。
     * 返回 Triple(本次卡步, 上次卡步, 同参重试次数)。只读，不改账本。
     */
    private fun stuckFacts(rows: List<LoopReadingRow>): Triple<String?, String?, Int> {
        val plans = rows.filter { it.loop == Loop.PLAN }
        if (plans.isEmpty()) return Triple(null, null, 0)
        val latest = plans.last()
        val step = asStep(latest)
        val previous = plans.dropLast(1).lastOrNull()?.let(::asStep)
        val retries = if (step == null) 0 else {
            plans.asReversed().takeWhile { asStep(it) == step && it.ref == latest.ref }.count()
        }
        return Triple(step, previous, retries)
    }

    /**
     * 取一行 plan 的卡步键：**只认 stuckAt**（该字段非空才表示"卡在第几步"）。
     * 不用 stepRef 退化——stepRef 是每次归因都带的步号，拿它当卡步会把正常的 plan 行
     * 也算成"卡住"，规则①就会误触发（判不了就不猜）。
     */
    private fun asStep(row: LoopReadingRow): String? = row.reading["stuckAt"]?.toString()

    /**
     * 756/RPE 事实：取 rpe 环里最近一段连续 rpe=-1 的行；连击数＝段长，原因相同＝note 全等。
     * 返回 Pair(连续负次数, 原因是否同类)。
     */
    private fun rpeFacts(rows: List<LoopReadingRow>): Pair<Int, Boolean> {
        val rpes = rows.filter { it.loop == Loop.RPE }
        val streak = rpes.asReversed().takeWhile { asRpe(it) == -1 }
        if (streak.isEmpty()) return 0 to false
        val notes = streak.map { it.reading["note"]?.toString().orEmpty() }.toSet()
        return streak.size to (notes.size == 1)
    }

    /** 取一行 rpe 的差读数：-1/0/1；非整数或越界返回 null（不参与连击）。 */
    private fun asRpe(row: LoopReadingRow): Int? = when (val v = row.reading["rpe"]) {
        is Int -> v.takeIf { it in -1..1 }
        is Long -> v.toInt().takeIf { it in -1..1 }
        else -> null
    }
}
