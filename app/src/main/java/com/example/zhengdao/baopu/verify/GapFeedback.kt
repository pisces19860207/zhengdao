// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第三百一十三批 · 右相回灌——差距板：合并去重 → 按 attempts 排序 → 回灌；≥3 次交人。
//
// 分工：格式与判据在 [GapItem]／[GapRules]（纯函数）；这一层是**状态与出口**——
//   · record：两家来源（金吾卫 observe ／右相 chancellorGapsOf）产出的 GapItem 合并进板，
//     同项（kind+target 相同）只留一行（判据③；不同来源也不许出现两行——负例③）；
//   · round：回灌一轮。attempts≥3 ⇒ 不回灌、交人（进「未办成单」，判据④）；
//     其余按 [GapRules.orderOf] 排序逐条渲染成回灌行——第 3 次永远轮不到回灌（负例④）；
//   · 回灌文案只从 GapItem 字段渲染（判据②：产出必为 GapItem，没有自由文本批评的口子），
//     行里带价值判断词 ⇒ 不回灌、转交人（判据⑥的运行时面；源级面在 GapFeedbackGateTest）。
//
// 落盘只有一条：交人进「未办成单」（scout.UnfinishedLedger，两落点扇出在那层）。
// 板本身是进程态（跨进程续跑归接线批次——PlanPledgeBoard.seed 同款留白）。
// 时钟一律入参（nowMs），本文件不自取——ChancellorSourceGateTest 的源级门禁照样扫本文件。
package com.example.zhengdao.baopu.verify

import com.example.zhengdao.baopu.scout.UnfinishedItem
import com.example.zhengdao.baopu.scout.UnfinishedLedger
import com.example.zhengdao.baopu.scout.UnfinishedOutcome
import com.example.zhengdao.baopu.scout.UnfinishedReason
import com.example.zhengdao.core.RunLog
import java.io.File

internal object GapFeedback {

    /** 交人线：attempts 到线即交人，不再回灌（派工单「≥3 次交人」）。 */
    const val HAND_OVER_ATTEMPTS = 3

    /** 未办成单落点（装配层注入；空表 ⇒ 交人只出在 Round 返回值里，不落盘——290 同口径）。 */
    @Volatile
    private var dirs: List<File> = emptyList()

    /** 差距板：gapKey → 合并后的 GapItem（进程态）。 */
    private val board = LinkedHashMap<String, GapItem>()

    /** 注入未办成单的落点（生产是「记忆」目录们；不调 ⇒ 交人不落盘）。 */
    fun attach(ledgerDirs: List<File>) {
        dirs = ledgerDirs
    }

    /** 板上在册的差距（快照；测试与装配读数用）。 */
    fun pending(): List<GapItem> = synchronized(board) { board.values.toList() }

    /**
     * 记进板（两家通用；同项合并——判据③）。
     * 同项已交过人（板上没有）⇒ 按新差距重新起账（attempts 从 1 起；人那边由未办成单并账）。
     */
    fun record(items: List<GapItem>) = synchronized(board) {
        items.forEach { item ->
            val key = GapRules.keyOf(item)
            val current = board[key]
            board[key] = if (current == null) item else GapRules.merge(current, item)
        }
    }

    /** 一轮回灌的产出：回灌行（给模型上下文）＋交人的差距（给人）。 */
    data class Round(val feedback: List<String>, val handedOver: List<GapItem>)

    /**
     * 回灌一轮：按 attempts 排序；到线（≥[HAND_OVER_ATTEMPTS]）或行里带价值词 ⇒ 交人，
     * 其余渲染成回灌行。板上的在册项不动（attempts 由 record 累加，不由回灌加）。
     */
    fun round(nowMs: Long): Round = synchronized(board) {
        val feedback = mutableListOf<String>()
        val handedOver = mutableListOf<GapItem>()
        GapRules.orderOf(board.values.toList()).forEach { item ->
            val line = renderLine(item)
            if (item.attempts >= HAND_OVER_ATTEMPTS || line == null) {
                handOver(item, nowMs)
                board.remove(GapRules.keyOf(item))
                handedOver += item
            } else {
                feedback += line
            }
        }
        Round(feedback, handedOver)
    }

    /**
     * 回灌一行（判据②：只从 GapItem 字段渲染，没有第二个文本来源）。
     * 行里带价值判断词 ⇒ null（调方转交人——判据⑥的运行时面）。
     */
    internal fun renderLine(item: GapItem): String? {
        val line = "差距[${item.kind.wire}] ${item.target}（第 ${item.attempts} 次）" +
            "证据：${item.evidence.first()}｜满足条件：${item.minimalFix}"
        val clean = GapItem.BANNED_VALUE_WORDS.none { line.contains(it) }
        return if (clean) line else null
    }

    /** 交人：进「未办成单」（账写失败只 log，不挡事——290 同口径）。 */
    private fun handOver(item: GapItem, nowMs: Long) {
        if (dirs.isEmpty()) return
        val ok = runCatching {
            UnfinishedLedger(dirs).record(
                UnfinishedItem(
                    intent = "差距未消（第 ${item.attempts} 次）：${item.target}",
                    outcome = UnfinishedOutcome.FAILED,
                    reason = UnfinishedReason.MODEL_MISJUDGED,
                    reasonDetail = "回灌未消，转人工处理",
                    reasonRef = "gap:${item.kind.wire}|${item.target}",
                    fixable = true,
                    fixHint = item.minimalFix,
                    atMs = nowMs,
                ),
            )
        }.getOrDefault(false)
        if (!ok) RunLog.log("差距交人落单失败：${item.target}")
    }

    // ── 右相验收发现 → GapItem（同格式入板；本批的第二家来源） ────────────────

    /**
     * 右相报告 → 差距。只有 **FAIL 的确定发现**回灌；UNVERIFIABLE 不回灌
     * （「判不了」≠「确定差距」——302 的 NO_SAMPLES 挂起口径同一条理）。
     */
    fun chancellorGapsOf(report: ChancellorReport): List<GapItem> =
        report.findings.mapNotNull { gapFromChancellor(it) }

    /** 一条发现 → 一条差距（判据⇒六类的映射表写死在这；note 是散文，不进差距）。 */
    internal fun gapFromChancellor(f: ChancellorFinding): GapItem? {
        val kind = when {
            f.criterion == ChancellorGate.C0_URGENT -> GapKind.TOOL_MISUSE
            f.criterion == ChancellorGate.C1_USABLE -> GapKind.MISSING_EVIDENCE
            f.criterion == ChancellorGate.C2_LEDGER -> GapKind.NO_LEDGER
            f.criterion == ChancellorGate.C3_CONTRACT -> GapKind.SCHEMA_VIOLATION
            f.criterion == ChancellorGate.C4_LEARNING && f.note.startsWith("③") -> GapKind.GOAL_DRIFT
            f.criterion == ChancellorGate.C4_LEARNING -> GapKind.SCHEMA_VIOLATION
            f.criterion == ChancellorGate.C5_OPINION -> GapKind.GOAL_DRIFT
            else -> return null
        }
        return GapItem(
            source = GapSource.RIGHT,
            kind = kind,
            target = f.evidence.trim(),
            evidence = listOf("验收判据:${f.criterion}", "对象:${f.evidence.trim()}"),
            minimalFix = fixFor(kind),
        )
    }

    /** 右相侧各类的最小满足条件（按六类写死一句；判据出自 302 的判据面，不自造）。 */
    private fun fixFor(kind: GapKind): String = when (kind) {
        GapKind.TOOL_MISUSE -> "紧急卡走人批入口，误用的动作收回重做"
        GapKind.SCHEMA_VIOLATION -> "补齐契约段（appliesWhen/success 各至少一条）"
        GapKind.MISSING_EVIDENCE -> "补出可用交付物（存在、非 0 字节、路径可解析）"
        GapKind.HASH_MISMATCH -> "重新产出交付物并对上哈希"
        GapKind.NO_LEDGER -> "补对应写/移/拷账行后重验"
        GapKind.GOAL_DRIFT -> "先消掉在册异议（撞法条的成果换版本）再交验"
    }

    /** 直测收尾（生产不调：进程态）。 */
    fun resetForTest() {
        synchronized(board) { board.clear() }
        dirs = emptyList()
    }
}
