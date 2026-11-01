// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百九十一批 · 御医体检的**判据层**：给 harness 的每个部位判一条"在不在干活"，
// 给每个工具判一行"用得对不对"，再按六类缺陷信号与七类卡点各筛一遍。
//
// 五条纪律（派工单 291 逐条落在这里，不靠自觉）：
// 1. **只读**：本文件零 IO、零时钟、零 Android、零随机；不弹卡、不写账、不改业务状态。
//    体检的输出只有 [HealthSheet]（一份文本），递给人看，不自己递到御案。
// 2. **只报有证据的**：每条结论构造期就要求 [OrganVerdict.evidence] 非空（L14 同款）——
//    没有证据行就不许构造，连"我觉得它没干活"都写不进来。
// 3. **"没发现信号" ≠ "没有 bug"**：六类信号与七类卡点各自有三态
//    （[ScanOutcome.HIT]／[ScanOutcome.NO_SIGNAL]／[ScanOutcome.NO_DATA]）。
//    `NO_SIGNAL` 的文案固定是"未发现信号"，`NO_DATA` 固定是"未取样"——
//    **两者都不许渲染成"正常/健康"**（负例见 `HealthCheckTest`）。
// 4. **零产出必须写原因**：`WIRED_NO_OUTPUT` 的 [NoOutputCause] 只有三种，且
//    "没人派活"（[NoOutputCause.NEVER_DISPATCHED]）与"派了没效果"
//    （[NoOutputCause.DISPATCHED_NO_EFFECT]）**必须分开**——把前者记成后者，
//    就是派工单点名的"把没人派活记成它坏了"。
// 5. **四态是闭集**：[OrganState] 只有四个值；测不到产出时**不编第五态**，
//    而是 `state = null`（[OrganVerdict.state]）并标 [NoOutputCause.UNMEASURABLE]——
//    "查不了"与"查过没问题"是两件事（239 的 `InspectVerdict.NO_DATA` 同一口径）。
package com.example.zhengdao.baopu.inspect

import com.example.zhengdao.core.TimeFormats

/** 体检的阈值与形制上限（一处一个口径；改这里等于改判据，要连着门禁一起改）。 */
internal object HealthLimits {

    /** 体检窗（天）：与 239／281 的读数窗同口径，不另立一套。 */
    const val SPAN_DAYS = 30

    const val DAY_MS = 86_400_000L

    /** 失败率判定的最小样本（<10 只算"样本不够"，不许报失败率异常）。 */
    const val MIN_SAMPLE = 10

    /** 成功率低于它且样本够 ⇒ 疑似逻辑错（派工单：失败率 <60%）。 */
    const val LOW_SUCCESS_PCT = 60

    /** 权限卡／路径卡的次数门槛（派工单：≥3 次）。 */
    const val DENIED_MIN = 3

    /** 判据卡：同一类动作被反复确认的次数门槛（派工单：≥10 次）。 */
    const val REASK_MIN = 10

    /** 性能卡：P90 超这个数即报（毫秒）。 */
    const val P90_BUDGET_MS = 8_000L

    /** 体检单 ≤1 页：正文行数上限（超了截断并留一句"还有 N 行"）。 */
    const val SHEET_MAX_LINES = 40

    /** 一条结论最多带几条证据行（多了没人看，明细在账本里）。 */
    const val MAX_EVIDENCE = 3

    /** 取数起点。 */
    fun fromOf(nowMs: Long): Long = nowMs - SPAN_DAYS * DAY_MS
}

/**
 * 部位四态（闭集；`wire` 是体检单与账本的稳定键，改字面量＝老体检单对不上）。
 *
 * 判据（派工单 291 §状态机，逐字落成可执行）：
 * - [ACTIVE]：有生产调用点 **且** 窗内有 ≥1 条产出；
 * - [WIRED_NO_OUTPUT]：有调用点，窗内零产出（原因见 [NoOutputCause]）；
 * - [NOT_WIRED]：**无生产调用点**（当日进缺陷台账，除非是裁定关着的）；
 * - [UNUSED_OR_DEAD]：连续两次 `WIRED_NO_OUTPUT`，或全仓零引用 ⇒ 合并/撤掉。
 */
internal enum class OrganState(val wire: String, val cn: String) {
    ACTIVE("active", "在干活"),
    WIRED_NO_OUTPUT("wired_no_output", "接了没产出"),
    NOT_WIRED("not_wired", "没接线"),
    UNUSED_OR_DEAD("unused_or_dead", "该合并/撤掉"),
    ;

    companion object {
        fun ofWire(wire: String): OrganState? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * 零产出的三种原因（**必须分开**，否则"没人派活"会被写成"它坏了"）。
 */
internal enum class NoOutputCause(val wire: String, val cn: String) {

    /** 调用点在，但窗内没有任何一次调用到达它 ⇒ 没人派活（不是坏了）。 */
    NEVER_DISPATCHED("never_dispatched", "没人派活"),

    /** 有调用到达，但窗内零产出（曾经有过产出、这窗没有）⇒ 才可能是坏了。 */
    DISPATCHED_NO_EFFECT("dispatched_no_effect", "派了活没效果"),

    /** 有调用点却没有产出落点 ⇒ 接线半截（产出无处可去）。 */
    NO_SINK("no_sink", "没有落点"),

    /** 取不到产出数（数据源不可用／设备没连）⇒ **不许当成零产出**。 */
    UNMEASURABLE("unmeasurable", "测不出来"),
}

/** 这条结论要不要当日进缺陷台账。 */
internal enum class Disposition(val wire: String, val cn: String) {

    /** 该接没接／接线半截 ⇒ 当日进缺陷台账。 */
    DEFECT("defect", "进缺陷台账"),

    /** 按裁定关着、或本批之外还没开工 ⇒ 只记录，不进缺陷台账。 */
    BY_DESIGN("by_design", "裁定/未开工"),

    /** 正常（在干活、或只是这窗没数）。 */
    NONE("none", "无"),
}

/** 缺陷信号六类（闭集；派工单 291 §第三段）。 */
internal enum class DefectSignal(val wire: String, val cn: String) {
    CRASH_ANR("crash_anr", "崩溃/ANR"),
    SWALLOWED("swallowed", "异常被吞"),
    LOW_SUCCESS("low_success", "失败率异常"),
    LEDGER_MISMATCH("ledger_mismatch", "行为与账不符"),
    TEST_RED("test_red", "测试/门禁红"),
    REPEAT_ASK("repeat_ask", "用户重复问"),
}

/** 卡点七类（闭集；派工单 291 §卡点）。 */
internal enum class Blocker(val wire: String, val cn: String) {
    PERMISSION("permission", "权限卡"),
    PATH("path", "路径卡"),
    CRITERIA("criteria", "判据卡"),
    PERFORMANCE("performance", "性能卡"),
    WIRING("wiring", "接线卡"),

    /** 工具存在但模型零选择 ⇒ **改描述，不删工具**（派工单原话）。 */
    UNDERSTANDING("understanding", "理解卡"),
    LEDGER("ledger", "账卡"),
}

/** 一类信号/卡点的筛结果：命中／未发现信号／未取样，三者不许互相冒充。 */
internal enum class ScanOutcome(val wire: String, val cn: String) {
    HIT("hit", "命中"),
    NO_SIGNAL("no_signal", "未发现信号"),
    NO_DATA("no_data", "未取样"),
}

/**
 * 一个部位的**盘上事实**（谁给：源码普查 [OrganCensus] + 只读取数 [HealthSources]）。
 *
 * @param entry 干活入口的符号（人读；门禁按它重扫源码核数）
 * @param declaredIn 该符号的定义文件（相对 `app/src/main/`；算"生产调用点"时排除它）
 * @param callSites 生产调用点数（`declaredIn` 之外的引用数；0 ⇒ 没接线）
 * @param present 代码在不在（不在 ⇒ 未实现，不是死代码）
 * @param sink 产出落点（账本/文件/表名）；null ⇒ 没有落点
 * @param outputs 窗内产出条数；**null ⇒ 测不出来**（不是 0）
 * @param lastOutputMs 最近一次产出的时刻（null ⇒ 从没有过）
 * @param previous 上一次体检的态（连续两次判"该撤掉"用）
 * @param orphan 全仓零引用（死件）
 * @param byDesign 非空 ⇒ 这个"没接线"是裁定的/未开工的，不是缺陷
 */
internal data class OrganProbe(
    val name: String,
    val entry: String,
    val declaredIn: String,
    val callSites: Int,
    val present: Boolean = true,
    val sink: String? = null,
    val outputs: Int? = null,
    val lastOutputMs: Long? = null,
    val previous: OrganState? = null,
    val orphan: Boolean = false,
    val byDesign: String? = null,
    val evidence: List<String> = emptyList(),
)

/** 一个部位的体检结论（[state] 为 null ＝ 未取样，不是第五态）。 */
internal data class OrganVerdict(
    val name: String,
    val state: OrganState?,
    val cause: NoOutputCause?,
    val disposition: Disposition,
    val reason: String,
    val evidence: List<String>,
    /** 要补什么才测得出（未取样时非空）——"补数据源要什么"那一列。 */
    val missingToMeasure: String? = null,
)

/**
 * 一个工具的盘上事实（谁给：[HealthSources] 的只读取数；空表 ＝ 未取样）。
 *
 * @param calls 窗内调用次数；null ⇒ 测不出来
 * @param ok 成功次数（`run_trace.status = OK`）
 * @param rejected 摆过确认卡后**被用户拒**的次数（`status = REJECTED`）
 * @param permissionDenied 因缺权限失败次数；**null ⇒ 测不出来**（轨迹里没有这个键）
 * @param pathDenied 因路径不在工作区失败次数；null ⇒ 测不出来
 * @param reasked 同一类动作被反复确认的次数（判据卡用）；null ⇒ 测不出来
 */
internal data class ToolProbe(
    val name: String,
    val group: String,
    val visibleByDefault: Boolean,
    val calls: Int? = null,
    val ok: Int = 0,
    val rejected: Int = 0,
    val permissionDenied: Int? = null,
    val pathDenied: Int? = null,
    val reasked: Int? = null,
    val p50Ms: Long = 0L,
    val p90Ms: Long = 0L,
    val skipReason: String? = null,
)

/** 机能画像的一行（派工单第二段的五列，一列不少；null 一律读作"未取样"）。 */
internal data class ToolRow(
    val name: String,
    val calls: Int?,
    val successRatePct: Int?,
    val p90Ms: Long,
    val rejected: Int,
    val skipReason: String?,
    val permissionDenied: Int?,
    val pathDenied: Int?,
    val reasked: Int?,
    val blockers: List<Blocker>,
    val signal: DefectSignal?,
    val note: String,
)

/** 一处空 catch（静态扫描的原料；[hasReason] 为 false 才算"真吞"）。 */
internal data class SwallowNote(val where: String, val hasReason: Boolean)

/** 六类信号的原料（**每一路都带"取没取到"的旗**，缺数不许冒充"没问题"）。 */
internal data class SignalInput(
    val crashSamples: List<String> = emptyList(),
    val crashSampled: Boolean = false,
    val swallowNotes: List<SwallowNote> = emptyList(),
    val swallowSampled: Boolean = false,
    val toolRows: List<ToolRow> = emptyList(),
    val ledgerGaps: List<String> = emptyList(),
    val ledgerSampled: Boolean = false,
    /** 读数层有没有人调（false ⇒ 账在、没人算）；null ⇒ 不知道。 */
    val ledgerReaderWired: Boolean? = null,
    val redGates: List<String> = emptyList(),
    val gatesRan: Boolean = false,
    val repeatAsks: List<String> = emptyList(),
    val repeatsSampled: Boolean = false,
)

/** 一类信号的筛结果。 */
internal data class SignalVerdict(
    val signal: DefectSignal,
    val outcome: ScanOutcome,
    val evidence: List<String>,
    val note: String,
)

/** 一处卡点。 */
internal data class BlockerVerdict(
    val blocker: Blocker,
    val outcome: ScanOutcome,
    val where: String,
    val evidence: List<String>,
    val advice: String,
    val fixableNow: Boolean,
)

/** 一份体检单（[lines] 已按 [HealthLimits.SHEET_MAX_LINES] 截好，可直接给人看）。 */
internal data class HealthSheet(
    val atMs: Long,
    val organs: List<OrganVerdict>,
    val rows: List<ToolRow>,
    val signals: List<SignalVerdict>,
    val blockers: List<BlockerVerdict>,
    val lines: List<String>,
) {
    fun count(state: OrganState): Int = organs.count { it.state == state }
    fun unmeasured(): Int = organs.count { it.state == null }
    fun outcomeOf(s: DefectSignal): ScanOutcome =
        signals.first { it.signal == s }.outcome

    fun outcomeOf(b: Blocker): ScanOutcome =
        blockers.first { it.blocker == b }.outcome
}

/** 判据层：全是纯函数，输入是盘上事实，输出是体检单。 */
internal object HealthCheck {

    /** 给"未发现信号"用的固定尾注——**不许**写成"没有 bug"。 */
    const val SCREEN_NOTE: String = "未发现信号 ≠ 没有 bug：体检是筛子，不是证明。"

    // ── 部位四态 ────────────────────────────────────────────────────────────

    fun organ(p: OrganProbe): OrganVerdict {
        require(p.evidence.isNotEmpty()) { "L14：没有证据行的部位不许进体检单（${p.name}）" }
        return when {
            p.orphan -> verdict(p, OrganState.UNUSED_OR_DEAD, null, Disposition.DEFECT, "全仓零引用（孤儿件）")
            !p.present -> verdict(p, OrganState.NOT_WIRED, NoOutputCause.NEVER_DISPATCHED, Disposition.BY_DESIGN, p.byDesign ?: "尚未实现")
            p.callSites <= 0 -> notWired(p)
            p.sink == null -> verdict(p, OrganState.WIRED_NO_OUTPUT, NoOutputCause.NO_SINK, Disposition.DEFECT, "有调用点但没有产出落点（接线半截）")
            else -> measured(p)
        }
    }

    private fun notWired(p: OrganProbe): OrganVerdict {
        val byDesign = p.byDesign
        return if (byDesign != null) {
            verdict(p, OrganState.NOT_WIRED, NoOutputCause.NEVER_DISPATCHED, Disposition.BY_DESIGN, byDesign)
        } else {
            verdict(p, OrganState.NOT_WIRED, NoOutputCause.NEVER_DISPATCHED, Disposition.DEFECT, "无生产调用点（该接没接）")
        }
    }

    /** 有调用点、有落点：只在**拿得到产出数**时才判四态，否则如实"未取样"。 */
    private fun measured(p: OrganProbe): OrganVerdict {
        val outputs = p.outputs
            ?: return OrganVerdict(
                name = p.name,
                state = null,
                cause = NoOutputCause.UNMEASURABLE,
                disposition = Disposition.NONE,
                reason = "取不到产出数（数据源不可用）",
                evidence = p.evidence,
                missingToMeasure = p.sink,
            )
        if (outputs > 0) return verdict(p, OrganState.ACTIVE, null, Disposition.NONE, "窗内产出 $outputs 条")
        if (p.previous == OrganState.WIRED_NO_OUTPUT) {
            return verdict(p, OrganState.UNUSED_OR_DEAD, null, Disposition.DEFECT, "连续两次零产出 ⇒ 合并/撤掉")
        }
        val never = p.lastOutputMs == null
        val cause = if (never) NoOutputCause.NEVER_DISPATCHED else NoOutputCause.DISPATCHED_NO_EFFECT
        val why = if (never) "调用点在，但窗内没人派活" else "派过活，窗内零产出"
        return verdict(p, OrganState.WIRED_NO_OUTPUT, cause, Disposition.NONE, why)
    }

    private fun verdict(
        p: OrganProbe,
        state: OrganState,
        cause: NoOutputCause?,
        disposition: Disposition,
        reason: String,
    ) = OrganVerdict(
        name = p.name,
        state = state,
        cause = cause,
        disposition = disposition,
        reason = reason.ifBlank { "（零产出必须写原因，派工单 291 §负例②）" },
        evidence = p.evidence.take(HealthLimits.MAX_EVIDENCE),
    )

    // ── 机能画像 ────────────────────────────────────────────────────────────

    fun tool(t: ToolProbe): ToolRow {
        val calls = t.calls
        val rate = if (calls == null || calls <= 0) null else t.ok * 100 / calls
        val signal = if (rate != null && calls!! >= HealthLimits.MIN_SAMPLE && rate < HealthLimits.LOW_SUCCESS_PCT) {
            DefectSignal.LOW_SUCCESS
        } else {
            null
        }
        return ToolRow(
            name = t.name,
            calls = calls,
            successRatePct = rate,
            p90Ms = t.p90Ms,
            rejected = t.rejected,
            skipReason = t.skipReason,
            permissionDenied = t.permissionDenied,
            pathDenied = t.pathDenied,
            reasked = t.reasked,
            blockers = toolBlockers(t, calls),
            signal = signal,
            note = toolNote(t, calls),
        )
    }

    private fun toolBlockers(t: ToolProbe, calls: Int?): List<Blocker> = buildList {
        if (calls == null) return@buildList
        if (calls == 0 && t.visibleByDefault) add(Blocker.UNDERSTANDING)
        if ((t.permissionDenied ?: 0) >= HealthLimits.DENIED_MIN) add(Blocker.PERMISSION)
        if ((t.pathDenied ?: 0) >= HealthLimits.DENIED_MIN) add(Blocker.PATH)
        if ((t.reasked ?: 0) >= HealthLimits.REASK_MIN) add(Blocker.CRITERIA)
        if (calls > 0 && t.p90Ms > HealthLimits.P90_BUDGET_MS) add(Blocker.PERFORMANCE)
    }

    private fun toolNote(t: ToolProbe, calls: Int?): String = when {
        calls == null -> "未取样（取数层没拿到这个工具的轨迹）"
        calls == 0 && t.visibleByDefault -> "可见但窗内零选择 ⇒ 改描述，不删工具"
        calls == 0 -> "默认不可见（要 capabilities 解锁）⇒ 零选择不是描述问题"
        t.skipReason != null -> "被跳过：${t.skipReason}"
        else -> ""
    }

    // ── 缺陷信号六类 ────────────────────────────────────────────────────────

    fun signals(i: SignalInput): List<SignalVerdict> = listOf(
        crashSignal(i),
        swallowSignal(i),
        lowSuccessSignal(i),
        ledgerSignal(i),
        testSignal(i),
        repeatSignal(i),
    )

    private fun crashSignal(i: SignalInput): SignalVerdict = when {
        !i.crashSampled -> SignalVerdict(DefectSignal.CRASH_ANR, ScanOutcome.NO_DATA, emptyList(), "没取到 crash buffer/logcat（设备没连）")
        i.crashSamples.isEmpty() -> SignalVerdict(DefectSignal.CRASH_ANR, ScanOutcome.NO_SIGNAL, emptyList(), "窗内没有 FATAL/ANR 记录")
        else -> SignalVerdict(DefectSignal.CRASH_ANR, ScanOutcome.HIT, i.crashSamples.take(HealthLimits.MAX_EVIDENCE), "窗内有崩溃/ANR")
    }

    private fun swallowSignal(i: SignalInput): SignalVerdict {
        if (!i.swallowSampled) {
            return SignalVerdict(DefectSignal.SWALLOWED, ScanOutcome.NO_DATA, emptyList(), "这一趟没扫空 catch")
        }
        val silent = i.swallowNotes.filterNot { it.hasReason }
        return when {
            silent.isNotEmpty() -> SignalVerdict(DefectSignal.SWALLOWED, ScanOutcome.HIT, silent.map { it.where }.take(HealthLimits.MAX_EVIDENCE), "空 catch 且没写理由")
            i.swallowNotes.isNotEmpty() -> SignalVerdict(
                DefectSignal.SWALLOWED,
                ScanOutcome.NO_SIGNAL,
                i.swallowNotes.map { it.where }.take(HealthLimits.MAX_EVIDENCE),
                "${i.swallowNotes.size} 处空 catch 都写了理由（有意为之）",
            )
            else -> SignalVerdict(DefectSignal.SWALLOWED, ScanOutcome.NO_SIGNAL, emptyList(), "静态扫描 0 处空 catch")
        }
    }

    private fun lowSuccessSignal(i: SignalInput): SignalVerdict {
        val sampled = i.toolRows.filter { it.calls != null && it.calls >= HealthLimits.MIN_SAMPLE }
        if (sampled.isEmpty()) {
            return SignalVerdict(DefectSignal.LOW_SUCCESS, ScanOutcome.NO_DATA, emptyList(), "没有一个工具样本 ≥${HealthLimits.MIN_SAMPLE}")
        }
        val bad = sampled.filter { it.signal == DefectSignal.LOW_SUCCESS }
        return if (bad.isEmpty()) {
            SignalVerdict(DefectSignal.LOW_SUCCESS, ScanOutcome.NO_SIGNAL, emptyList(), "${sampled.size} 个够样本的工具都在 60% 以上")
        } else {
            SignalVerdict(DefectSignal.LOW_SUCCESS, ScanOutcome.HIT, bad.map { "${it.name}=${it.successRatePct}%" }.take(HealthLimits.MAX_EVIDENCE), "成功率 <60% 且样本够")
        }
    }

    private fun ledgerSignal(i: SignalInput): SignalVerdict = when {
        !i.ledgerSampled -> SignalVerdict(DefectSignal.LEDGER_MISMATCH, ScanOutcome.NO_DATA, emptyList(), "没取到账本与轨迹（账实对不上对不出来）")
        i.ledgerGaps.isEmpty() -> SignalVerdict(DefectSignal.LEDGER_MISMATCH, ScanOutcome.NO_SIGNAL, emptyList(), "窗内动作与账行对得上")
        else -> SignalVerdict(DefectSignal.LEDGER_MISMATCH, ScanOutcome.HIT, i.ledgerGaps.take(HealthLimits.MAX_EVIDENCE), "有动作无账行／有账行无动作")
    }

    private fun testSignal(i: SignalInput): SignalVerdict = when {
        !i.gatesRan -> SignalVerdict(DefectSignal.TEST_RED, ScanOutcome.NO_DATA, emptyList(), "这一趟没跑全量/lint")
        i.redGates.isEmpty() -> SignalVerdict(DefectSignal.TEST_RED, ScanOutcome.NO_SIGNAL, emptyList(), "全量与 lint 都绿")
        else -> SignalVerdict(DefectSignal.TEST_RED, ScanOutcome.HIT, i.redGates.take(HealthLimits.MAX_EVIDENCE), "有红：${i.redGates.take(2).joinToString("；")}")
    }

    private fun repeatSignal(i: SignalInput): SignalVerdict = when {
        !i.repeatsSampled -> SignalVerdict(DefectSignal.REPEAT_ASK, ScanOutcome.NO_DATA, emptyList(), "没取到对话史（同一意图问几次判不了）")
        i.repeatAsks.isEmpty() -> SignalVerdict(DefectSignal.REPEAT_ASK, ScanOutcome.NO_SIGNAL, emptyList(), "窗内没有「同一意图 ≥3 次」的记录")
        else -> SignalVerdict(DefectSignal.REPEAT_ASK, ScanOutcome.HIT, i.repeatAsks.take(HealthLimits.MAX_EVIDENCE), "同一意图反复问且答案不一致")
    }

    // ── 卡点七类 ────────────────────────────────────────────────────────────

    fun blockers(organs: List<OrganVerdict>, rows: List<ToolRow>, i: SignalInput): List<BlockerVerdict> = listOf(
        countBlocker(Blocker.PERMISSION, rows) { it.permissionDenied },
        countBlocker(Blocker.PATH, rows) { it.pathDenied },
        criteriaBlocker(rows),
        perfBlocker(rows),
        wiringBlocker(organs),
        understandingBlocker(rows),
        ledgerBlocker(i),
    )

    private fun countBlocker(
        b: Blocker,
        rows: List<ToolRow>,
        denied: (ToolRow) -> Int?,
    ): BlockerVerdict {
        val measured = rows.filter { it.calls != null }
        if (measured.isEmpty()) return BlockerVerdict(b, ScanOutcome.NO_DATA, "全部工具", emptyList(), "先补取数", false)
        val known = measured.filter { denied(it) != null }
        val hit = known.filter { (denied(it) ?: 0) >= HealthLimits.DENIED_MIN }
        if (hit.isEmpty()) {
            val advice = if (b == Blocker.PERMISSION) "不需要放开" else "不需要改落点"
            return if (known.size < measured.size) {
                BlockerVerdict(b, ScanOutcome.NO_DATA, "部分工具", emptyList(), "有 ${measured.size - known.size} 个工具没这个读数，判不了", false)
            } else {
                BlockerVerdict(b, ScanOutcome.NO_SIGNAL, "全部工具", emptyList(), advice, false)
            }
        }
        return BlockerVerdict(
            b,
            ScanOutcome.HIT,
            hit.joinToString("、") { it.name },
            hit.map { "${it.name} 被拒 ${denied(it)} 次" }.take(HealthLimits.MAX_EVIDENCE),
            if (b == Blocker.PERMISSION) "查权限授予与斥候四态" else "查工作区解析与默认落点",
            true,
        )
    }

    private fun criteriaBlocker(rows: List<ToolRow>): BlockerVerdict {
        val measured = rows.filter { it.calls != null }
        if (measured.isEmpty()) return BlockerVerdict(Blocker.CRITERIA, ScanOutcome.NO_DATA, "全部工具", emptyList(), "先补取数", false)
        val known = measured.filter { it.reasked != null }
        val hit = known.filter { (it.reasked ?: 0) >= HealthLimits.REASK_MIN }
        if (hit.isEmpty()) {
            return if (known.size < measured.size) {
                BlockerVerdict(Blocker.CRITERIA, ScanOutcome.NO_DATA, "部分工具", emptyList(), "计簿里没有「同类反复确认」的键，判不了", false)
            } else {
                BlockerVerdict(Blocker.CRITERIA, ScanOutcome.NO_SIGNAL, "全部工具", emptyList(), "不需要改判据", false)
            }
        }
        return BlockerVerdict(
            Blocker.CRITERIA,
            ScanOutcome.HIT,
            hit.joinToString("、") { it.name },
            hit.map { "${it.name} 反复确认" }.take(HealthLimits.MAX_EVIDENCE),
            "分类器过严 ⇒ 放宽该档；过松则收紧",
            true,
        )
    }

    private fun perfBlocker(rows: List<ToolRow>): BlockerVerdict {
        val measured = rows.filter { it.calls != null && it.calls > 0 }
        if (measured.isEmpty()) return BlockerVerdict(Blocker.PERFORMANCE, ScanOutcome.NO_DATA, "全部工具", emptyList(), "先补取数", false)
        val hit = measured.filter { it.p90Ms > HealthLimits.P90_BUDGET_MS }
        if (hit.isEmpty()) return BlockerVerdict(Blocker.PERFORMANCE, ScanOutcome.NO_SIGNAL, "全部工具", emptyList(), "不需要加预算", false)
        return BlockerVerdict(
            Blocker.PERFORMANCE,
            ScanOutcome.HIT,
            hit.joinToString("、") { it.name },
            hit.map { "${it.name} P90=${it.p90Ms}ms" }.take(HealthLimits.MAX_EVIDENCE),
            "查主线程 IO／超时预算",
            false,
        )
    }

    private fun wiringBlocker(organs: List<OrganVerdict>): BlockerVerdict {
        val hit = organs.filter { it.state == OrganState.NOT_WIRED && it.disposition == Disposition.DEFECT }
        if (hit.isEmpty()) {
            return BlockerVerdict(Blocker.WIRING, ScanOutcome.NO_SIGNAL, "全部部位", emptyList(), "没有该接没接的", false)
        }
        return BlockerVerdict(
            Blocker.WIRING,
            ScanOutcome.HIT,
            hit.joinToString("、") { it.name },
            hit.flatMap { it.evidence }.distinct().take(HealthLimits.MAX_EVIDENCE),
            "补生产调用点（每处一行）",
            true,
        )
    }

    private fun understandingBlocker(rows: List<ToolRow>): BlockerVerdict {
        val measured = rows.filter { it.calls != null }
        if (measured.isEmpty()) return BlockerVerdict(Blocker.UNDERSTANDING, ScanOutcome.NO_DATA, "全部工具", emptyList(), "先补取数", false)
        val hit = measured.filter { it.blockers.contains(Blocker.UNDERSTANDING) }
        if (hit.isEmpty()) return BlockerVerdict(Blocker.UNDERSTANDING, ScanOutcome.NO_SIGNAL, "全部工具", emptyList(), "不需要改描述", false)
        return BlockerVerdict(
            Blocker.UNDERSTANDING,
            ScanOutcome.HIT,
            hit.joinToString("、") { it.name },
            hit.map { "${it.name}：可见但零选择" }.take(HealthLimits.MAX_EVIDENCE),
            "改工具描述/名字，**不许删工具**",
            true,
        )
    }

    private fun ledgerBlocker(i: SignalInput): BlockerVerdict = when {
        i.ledgerReaderWired == false -> BlockerVerdict(
            Blocker.LEDGER,
            ScanOutcome.HIT,
            "计簿",
            listOf("读数层零调用点 ⇒ 账在、没人算"),
            "把读数层接进界面/周报（281 已合但没人调）",
            true,
        )
        !i.ledgerSampled -> BlockerVerdict(Blocker.LEDGER, ScanOutcome.NO_DATA, "计簿", emptyList(), "先补账本取数", false)
        i.ledgerGaps.isEmpty() -> BlockerVerdict(Blocker.LEDGER, ScanOutcome.NO_SIGNAL, "计簿", emptyList(), "入账没发现缺口", false)
        else -> BlockerVerdict(Blocker.LEDGER, ScanOutcome.HIT, "计簿", i.ledgerGaps.take(HealthLimits.MAX_EVIDENCE), "补入账／对平", true)
    }

    // ── 体检单（≤1 页，只出文本，不弹卡）────────────────────────────────────

    fun sheet(
        atMs: Long,
        organs: List<OrganVerdict>,
        rows: List<ToolRow>,
        signals: List<SignalVerdict>,
        blockers: List<BlockerVerdict>,
        /** 读数层的一行人话（348 接线；判据层只排版，不碰取数与算法）。 */
        readings: List<String> = emptyList(),
    ): HealthSheet {
        val body = buildList {
            add("御医体检 · ${TimeFormats.dayStamp(atMs)} · 窗 ${HealthLimits.SPAN_DAYS} 天 · 只读（不弹卡、不改业务状态）")
            add(organSummary(organs))
            // 读数紧跟在部位总览后面：它属于"读数层"那个部位，且不许被后面的明细挤出页面。
            readings.forEach { add("  $it") }
            addAll(organUnmeasured(organs))
            addAll(organDetail(organs))
            add(rowSummary(rows))
            addAll(rowDetail(rows))
            add(signalSummary(signals))
            addAll(signalDetail(signals))
            add(blockerSummary(blockers))
            addAll(blockerDetail(blockers))
        }
        // 尾注必须留：截断只截明细，不截"未发现信号 ≠ 没有 bug"这一句。
        val capped = cap(body, HealthLimits.SHEET_MAX_LINES - 1)
        return HealthSheet(atMs, organs, rows, signals, blockers, capped + SCREEN_NOTE)
    }

    private fun organSummary(organs: List<OrganVerdict>): String {
        val byState = OrganState.entries.joinToString("｜") { "${it.cn} ${organs.count { o -> o.state == it }}" }
        val unmeasured = organs.count { it.state == null }
        return "一、部位四态（共 ${organs.size}）：$byState｜未取样 $unmeasured"
    }

    /** 未取样也要点名：只写"未取样 7"而不说是哪 7 个，等于让人自己去猜（负例⑤的反面）。 */
    private fun organUnmeasured(organs: List<OrganVerdict>): List<String> {
        val names = organs.filter { it.state == null }.map { it.name }
        if (names.isEmpty()) return emptyList()
        return listOf("  未取样（有调用点、取不到产出数）：" + names.joinToString("、"))
    }

    private fun organDetail(organs: List<OrganVerdict>): List<String> {
        val bad = organs.filter { it.state == OrganState.NOT_WIRED || it.state == OrganState.UNUSED_OR_DEAD }
        if (bad.isEmpty()) return listOf("  四态里没有 NOT_WIRED／UNUSED_OR_DEAD")
        return bad.map { o ->
            val tag = if (o.disposition == Disposition.DEFECT) "进缺陷台账" else "裁定/未开工"
            "  ${o.state!!.cn}｜${o.name}：${o.reason}（$tag）"
        }
    }

    private fun rowSummary(rows: List<ToolRow>): String {
        val measured = rows.count { it.calls != null }
        val zero = rows.count { it.calls == 0 }
        val zeroVisible = rows.count { it.calls == 0 && it.blockers.contains(Blocker.UNDERSTANDING) }
        return "二、机能画像：工具 ${rows.size}｜已测 $measured｜未取样 ${rows.size - measured}｜已测里零调用 $zero（默认可见 $zeroVisible）"
    }

    private fun rowDetail(rows: List<ToolRow>): List<String> {
        val flagged = rows.filter { it.blockers.isNotEmpty() || it.signal != null }
        if (flagged.isEmpty()) {
            val measured = rows.count { it.calls != null }
            return listOf("  已测 $measured 个工具里没有卡点；未取样 ${rows.size - measured} 个不参与判断")
        }
        return flagged.take(8).map { r ->
            val why = r.blockers.joinToString("、") { it.cn } + (r.signal?.let { "＋${it.cn}" } ?: "")
            "  ${r.name}：$why"
        }
    }

    private fun signalSummary(signals: List<SignalVerdict>): String {
        val cells = signals.joinToString("｜") { "${it.signal.cn} ${it.outcome.cn}" }
        return "三、缺陷信号（六类）：$cells"
    }

    private fun signalDetail(signals: List<SignalVerdict>): List<String> =
        signals.filter { it.outcome != ScanOutcome.NO_SIGNAL }.map { "  ${it.signal.cn}：${it.note}" }

    private fun blockerSummary(blockers: List<BlockerVerdict>): String {
        val cells = blockers.joinToString("｜") { "${it.blocker.cn} ${it.outcome.cn}" }
        return "四、卡点（七类）：$cells"
    }

    private fun blockerDetail(blockers: List<BlockerVerdict>): List<String> =
        blockers.filter { it.outcome == ScanOutcome.HIT }.map { "  ${it.blocker.cn}：${it.where} ⇒ ${it.advice}" }

    /** ≤1 页：超了截断并留一句"还有 N 行"（不静默丢）。 */
    private fun cap(body: List<String>, max: Int): List<String> {
        if (body.size <= max) return body
        val keep = body.take(max - 1)
        return keep + "…（还有 ${body.size - keep.size} 行，明细在证据清单）"
    }
}
