// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第三百一十五批 · 紧急闸接线——276 写好的法条（L21 紧急卡＋L30b 三时钟）第一次接进生产。
//
// 出处：第三百一十五批派工单（紧急卡必人批／止损四件 0 秒＋即时上报／24h 追认逾期降级／
// 三时钟落账）；租约＝law/EmergencyGate.kt、law/EmergencyClocks.kt、本文件（＋测试）。
// 不许碰 policy/（298 在飞）、law/ 其它文件、GuardPipeline.kt——所以自决路径（agent/OpinionWiring
// →SelfRulingLedger）这批接不进代码，改由源级门禁 `EmergencyWiringGateTest` 钉住：
// 自决放行点谁想绕开 [selfRulingRejection] 另起炉灶，门禁当场红。
//
// ## 派工单判据 → 本文件的落点
//   ① 紧急卡走自决 ⇒ 红：[Opinion.requiresApproval] 恒 true ＋ [selfRulingRejection] 一律拒，
//      拒因唯一出自 [EmergencyGate.selfRulingRejection]——本文件不写第二个拒因（不许"特殊情况"）。
//   ② 止损四件不必等人：[EmergencyWiring.executeStopLoss] 的签名里**没有**审批参数——
//      API 形状上就没有"等人"的口子，调了即执行（0 秒，[EmergencyClocks.STOP_LOSS_MS]）。
//   ③ 超 24h ⇒ 报逾期并降级：[EmergencyWiring.sweepOverdue] 把到期未追认的处置降级
//      （授权收回，回"先停下"）并进「未办成单」。
//   ④ 执行 3 秒内账可查：执行与落账在**同一次同步调用**里完成（追加写＋RunLog），
//      截止线由 [EmergencyClocks.reportDueAtMs] 出——账行本身带三时钟读数（判据④落账）。
//   ⑤ 生产调用点：本文件是 app/src/main 生产源码，对两个法条对象的点号调用被
//      `LawSourceWiringGateTest` 名册逐字钉住（少一处＝接线被删）。
//
// ## 为什么止损的"真动作"只有一个记账壳
// 停机／隔离／回滚／冻结写入的机器效应分别住在引擎与存储层（agent/**、DB 迁移区——都是禁区）。
// 本批交的是**授权与账**：执行＝处置入账生效（[EmergencyWiring.stopLossActiveActions] 可查），
// 真效应由效应层认账行执行；先有账后有效应，顺序不能反——没有账的止损等于黑箱动手。
package com.example.zhengdao.baopu.inspect

import com.example.zhengdao.baopu.DecisionLedger
import com.example.zhengdao.baopu.law.EmergencyClocks
import com.example.zhengdao.baopu.law.EmergencyGate
import com.example.zhengdao.baopu.scout.LedgerFanout
import com.example.zhengdao.baopu.scout.UnfinishedItem
import com.example.zhengdao.baopu.scout.UnfinishedLedger
import com.example.zhengdao.baopu.scout.UnfinishedOutcome
import com.example.zhengdao.baopu.scout.UnfinishedReason
import com.example.zhengdao.core.RunLog
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** 紧急卡的证据键：进 [Opinion.evidence]（形状照 `risk:`／`tool:` 那套稳定机器键，文案会改键不许改）。 */
const val EMERGENCY_MARKER = "marker:EMERGENCY"

/** 是不是紧急卡（机器键进 evidence；三布尔投影给 [EmergencyGate]，本层不算第二遍）。 */
internal val Opinion.isEmergencyCard: Boolean
    get() = EmergencyGate.isEmergencyCard(
        hasMarker = evidence.contains(EMERGENCY_MARKER),
        severityIsCritical = severity == Severity.CRITICAL,
        lawRefIsL21 = lawRef == LawRef.L21,
    )

/** 派工单①：Opinion 标 requiresApproval——认成紧急卡 ⇒ 恒 true（判据唯一出自 [EmergencyGate]）。 */
internal val Opinion.requiresApproval: Boolean
    get() = EmergencyGate.requiresApproval(isEmergencyCard)

/**
 * 派工单①的门：任何自决路径在放行前必须过这里——紧急卡 ⇒ 拒（拒因唯一出自 law/）。
 *
 * 非紧急 ⇒ null（按各路径原判走，本文件不替预算层判）。谁在自决路径上绕开本函数
 * 直接放行，`EmergencyWiringGateTest` 的自决放行点名册当场红。
 */
internal fun Opinion.selfRulingRejection(): String? =
    EmergencyGate.selfRulingRejection(isEmergencyCard = requiresApproval)

/** 止损四件（派工单点名：停机／隔离／回滚／冻结写入；`wire` 是账行稳定键）。 */
internal enum class StopLossAction(val wire: String, val cn: String) {
    STOP_MACHINE("stop_machine", "停机"),
    QUARANTINE("quarantine", "隔离"),
    ROLLBACK("rollback", "回滚"),
    FREEZE_WRITES("freeze_writes", "冻结写入"),
    ;

    companion object {
        fun ofWire(wire: String): StopLossAction? = entries.firstOrNull { it.wire == wire }
    }
}

/** 处置状态（闭集）：已执行待追认 ／ 人已追认 ／ 逾期降级（授权收回）。 */
internal enum class EmergencyStatus(val wire: String) {
    PENDING_RATIFY("pending_ratify"),
    RATIFIED("ratified"),
    OVERDUE_DOWNGRADED("overdue_downgraded"),
    ;

    companion object {
        fun ofWire(wire: String): EmergencyStatus? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * 一条止损处置的账行（一行＝一次执行；追认／降级**追加新行**不改旧行——208/未办成单同一纪律）。
 *
 * 三时钟落账（派工单判据④）：[stopLossMs]／[reportDueAtMs]／[ratifyDueAtMs] 三列在写入时
 * 从 [EmergencyClocks] 算死在行上——回放时不重算，"当时用的是哪只钟"看行就知道。
 */
internal data class EmergencyReceipt(
    val id: String,
    val action: StopLossAction,
    val executedAtMs: Long,
    val ratifyDueAtMs: Long,
    val status: EmergencyStatus,
    /** 追认时刻（0＝还没人追认）。 */
    val ratifiedAtMs: Long = 0L,
    /** 追认人（空串＝还没人追认）。 */
    val ratifiedBy: String = "",
    /** 三时钟落账：止损时钟（0）。 */
    val stopLossMs: Long = EmergencyClocks.STOP_LOSS_MS,
    /** 三时钟落账：上报线（3 秒）。 */
    val informMs: Long = EmergencyClocks.INFORM_MS,
    /** 触发止损的证据引用（evidence 机器键，照 Opinion 的形状）。 */
    val evidence: List<String> = emptyList(),
) {
    /** 追认逾期没有（照 law/ 的判定，本层不算第二遍）。 */
    fun ratifyOverdue(nowMs: Long): Boolean = EmergencyClocks.ratifyOverdue(executedAtMs, nowMs)

    fun toJson(): JSONObject = JSONObject()
        .put("v", VERSION)
        .put("id", id)
        .put("act", action.wire)
        .put("at", executedAtMs)
        .put("rdue", ratifyDueAtMs)
        .put("st", status.wire)
        .put("rat", ratifiedAtMs)
        .put("by", ratifiedBy)
        .put("sl", stopLossMs)
        .put("inf", informMs)
        .put("ev", JSONArray(evidence))

    companion object {
        /** 行格式版本（改形状才 +1；加字段不算）。 */
        const val VERSION = 1

        fun parse(raw: String): EmergencyReceipt? {
            val o = runCatching { JSONObject(raw) }.getOrNull() ?: return null
            val id = o.optString("id")
            val action = StopLossAction.ofWire(o.optString("act")) ?: return null
            val status = EmergencyStatus.ofWire(o.optString("st")) ?: return null
            return runCatching {
                EmergencyReceipt(
                    id = id,
                    action = action,
                    executedAtMs = o.optLong("at", 0L),
                    ratifyDueAtMs = o.optLong("rdue", 0L),
                    status = status,
                    ratifiedAtMs = o.optLong("rat", 0L),
                    ratifiedBy = o.optString("by"),
                    stopLossMs = o.optLong("sl", 0L),
                    informMs = o.optLong("inf", 0L),
                    evidence = (o.optJSONArray("ev") ?: JSONArray()).let { a ->
                        (0 until a.length()).mapNotNull { i -> a.optString(i).ifEmpty { null } }
                    },
                )
            }.getOrNull()
        }
    }
}

/**
 * 紧急闸的接线层（进程内单例）：止损执行入账、追认到场、逾期降级，一本账管到底。
 *
 * 落点＝208 已装配的那棵目录（`抱朴记忆/`，与 意见判定.jsonl / 追认队列.jsonl 同一棵）；
 * 没装配（纯 JVM 单测）⇒ 只进内存，回放照样查得到。
 */
internal object EmergencyWiring {

    /** 账本文件名（与 意见判定.jsonl 同一棵目录）。 */
    const val FILE_NAME = "紧急处置.jsonl"

    /** 内存里最多留多少行（回放以盘为准；这条只是"没装配落点"时的兜底）。 */
    private const val MEMORY_CAP = 200

    private val memory = ArrayList<EmergencyReceipt>()
    private var seq = 0

    /** 未办成单的落点（生产装配注入；没装 ⇒ 降级照旧、单不落，只留一行日志——290 同口径）。 */
    @Volatile
    private var unfinishedDirs: () -> List<File> = { emptyList() }

    /** 注入「未办成单」的落点（生产：`ScoutDirs.ledgerDirs(ctx)` 那一套）。 */
    fun installUnfinishedDirs(provider: () -> List<File>) {
        unfinishedDirs = provider
    }

    /**
     * 派工单②：止损四件，0 秒执行，不等人。
     *
     * 签名里没有审批参数——"等人批"的口子在形状上就不存在（负例②的本体）。
     * 执行与落账在同一次同步调用里完成：返回时 [EmergencyWiring.replay] 已经查得到（判据④）。
     */
    @Synchronized
    fun executeStopLoss(
        action: StopLossAction,
        nowMs: Long = System.currentTimeMillis(),
        evidence: List<String> = emptyList(),
    ): EmergencyReceipt {
        seq += 1
        val receipt = EmergencyReceipt(
            id = "em:$nowMs:$seq",
            action = action,
            executedAtMs = nowMs,
            ratifyDueAtMs = EmergencyClocks.ratifyDueAtMs(nowMs),
            status = EmergencyStatus.PENDING_RATIFY,
            evidence = evidence,
        )
        record(receipt)
        RunLog.log(
            "抱朴紧急：止损${EmergencyClocks.STOP_LOSS_MS}s执行＝${action.cn}｜id=${receipt.id}" +
                "｜上报线=${receipt.informMs}ms｜追认截止=${receipt.ratifyDueAtMs}",
        )
        return receipt
    }

    /**
     * 派工单③的另一面：24h 内有人到场确认。
     *
     * 只认「待追认」的处置；到线（含）之后才来的追认收不下——那时处置已属逾期，走 [sweepOverdue]。
     * 追认必须署名（[by] 空白 ⇒ 拒）：没有主的人批等于没批（88 决策口"每条决策记操作者"同口径）。
     */
    @Synchronized
    fun ratify(receiptId: String, nowMs: Long, by: String): EmergencyReceipt? {
        val latest = replay().firstOrNull { it.id == receiptId } ?: return null
        val who = by.trim()
        if (who.isEmpty() || latest.status != EmergencyStatus.PENDING_RATIFY ||
            EmergencyClocks.ratifyOverdue(latest.executedAtMs, nowMs)
        ) {
            RunLog.log("抱朴紧急：追没收下（id=$receiptId｜st=${latest.status.wire}｜到线=${latest.ratifyOverdue(nowMs)}）")
            return null
        }
        val ratified = latest.copy(
            status = EmergencyStatus.RATIFIED,
            ratifiedAtMs = nowMs,
            ratifiedBy = who,
        )
        record(ratified)
        RunLog.log("抱朴紧急：追认到场＝${latest.action.cn}｜id=$receiptId｜by=$who")
        return ratified
    }

    /**
     * 派工单③：逾期自动降级并进「未办成单」。
     *
     * 降级＝处置授权收回（[stopLossActiveActions] 不再含它，回"先停下"）＋未办成单落一行
     * （追认这件事没办成，可点回处置账行）。返回本轮降级的处置。
     */
    @Synchronized
    fun sweepOverdue(nowMs: Long = System.currentTimeMillis()): List<EmergencyReceipt> {
        val downgraded = replay()
            .filter { it.status == EmergencyStatus.PENDING_RATIFY && it.ratifyOverdue(nowMs) }
            .map { it.copy(status = EmergencyStatus.OVERDUE_DOWNGRADED) }
        downgraded.forEach { receipt ->
            record(receipt)
            RunLog.log(
                "抱朴紧急：追认逾期已降级＝${receipt.action.cn}｜id=${receipt.id}" +
                    "｜处置授权收回，进未办成单",
            )
            recordUnfinished(receipt, nowMs)
        }
        return downgraded
    }

    /** 现在身上带着的止损处置（已执行且没被降级——降级＝授权收回，回"先停下"）。 */
    @Synchronized
    fun stopLossActiveActions(): List<StopLossAction> =
        replay()
            .filter { it.status != EmergencyStatus.OVERDUE_DOWNGRADED }
            .map { it.action }
            .distinct()

    /** 回放（不设时间窗：逾期降级的处置 30 天后照样查得到）。 */
    @Synchronized
    fun replay(limit: Int = 0): List<EmergencyReceipt> {
        val fromFile = DecisionLedger.installedRoot()?.let { root ->
            runCatching {
                val f = fileIn(root)
                if (!f.isFile) emptyList() else f.readLines(Charsets.UTF_8).mapNotNull(EmergencyReceipt::parse)
            }.getOrDefault(emptyList())
        }.orEmpty()
        // 追加式的"最新一行"语义：同一 id 后写的行覆盖先写的（追认／降级都是追加，不改旧行）。
        val latest = LinkedHashMap<String, EmergencyReceipt>()
        (fromFile + memory).filter { it.id.isNotEmpty() }.forEach { latest[it.id] = it }
        val rows = latest.values.sortedByDescending { it.executedAtMs }
        return if (limit > 0) rows.take(limit) else rows
    }

    /** 落点文件（与 208 同一棵目录；没装配 ⇒ 只进内存）。 */
    fun fileIn(globalRoot: File): File = File(globalRoot, FILE_NAME)

    /** 清空（测试专用：单例状态会跨用例污染，谁用谁还）。 */
    @Synchronized
    fun resetForTest() {
        memory.clear()
        seq = 0
        unfinishedDirs = { emptyList() }
    }

    /**
     * 追加一行（内存照记，落盘走 scout 的 [LedgerFanout]——建目录与容错住在那一层；
     * 勘验层自身不带写口，239 §5① 的门禁 `InspectReadOnlyGateTest` 盯着）。
     */
    private fun record(receipt: EmergencyReceipt) {
        memory.add(receipt)
        if (memory.size > MEMORY_CAP) memory.removeAt(0)
        val root = DecisionLedger.installedRoot() ?: return
        val ok = runCatching {
            LedgerFanout(listOf(root), FILE_NAME).appendLine(receipt.toJson().toString())
        }.getOrDefault(false)
        if (!ok) RunLog.log("抱朴紧急：账没落上（${receipt.id}）")
    }

    /** 降级 ⇒ 未办成单一行（账写失败只 log，不挡事——290 同一口径）。 */
    private fun recordUnfinished(receipt: EmergencyReceipt, nowMs: Long) {
        val dirs = unfinishedDirs()
        if (dirs.isEmpty()) {
            RunLog.log("抱朴紧急：未办成单没装配落点，逾期单只在账上（${receipt.id}）")
            return
        }
        val item = UnfinishedItem(
            intent = "追认逾期：${receipt.action.cn}的紧急处置没有人在 24 小时内确认",
            outcome = UnfinishedOutcome.NOT_DONE,
            reason = UnfinishedReason.OTHER,
            reasonDetail = "处置于 ${receipt.executedAtMs} 执行，追认截止 ${receipt.ratifyDueAtMs}",
            reasonRef = "emergency:${receipt.id}",
            fixable = true,
            fixHint = "到决策中心追认或撤销这次处置",
            atMs = nowMs,
        )
        runCatching { UnfinishedLedger(dirs).record(item) }
            .onFailure { RunLog.log("抱朴紧急：未办成单写失败（${receipt.id}）——${it.javaClass.simpleName}") }
    }
}
