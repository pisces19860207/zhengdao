// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第三百三十批 · 开机自检——"打开就自己干活、少烦人"那台机器的判定层。
//
// 用户 2026-10-31（派工单原文）：开机 1–2 秒跑一轮（后台、不弹窗、不等用户），查四个部位——
//   斥候（能力四态）· 勘验（工作区可读／账本可写）· 御医轻量（该接没接）· 审计（最近 N 行有动作无账）
// 三级处置：①可自恢复⇒自己修不打扰 ②可自决⇒左相出自决单、不弹卡、进记事页 ③必须人⇒只留一条未办成单、不弹卡；
//   ④唯一例外：阻断性（账写不了／文件读不了）⇒弹**一次**，说清"我干不了活，因为 X"。
// 另出简报：≤10 行、只写事实，供首条消息前回灌。
//
// 四条取舍（为什么长成这样，不是别的样子）：
// 1. **本文件一个写口都没有**。`InspectReadOnlyGateTest` 从源码层钉着勘验层只查不改，所以修、
//    落自决单、记未办成单、弹那一次全走 [BootSinks] 交出去，由装配层实现（那三本账归 277／290）。
//    开检只做一件事：把四个部位的读数折成**一条结论＋一档处置**。
// 2. **处置长在事实表上，不长在调用方身上**：每种 [BootFinding] 自带部位、自带"这部位还算不算能用"、
//    自带处置。尤其 [BootDisposition.interrupts] 全表只有 [BootDisposition.BLOCKING] 一格为真——
//    判据④"必须人的事弹卡数＝0"是类型层的保证，不是谁的自觉。
// 3. **不可用必须带原因码**（承 290 斥候那句"不带原因的不可用等于不知道"）：部位不可用时结论一定挂着
//    [BootFinding.wire]。负例"不可用的能力仍假装能做"就红在这一格——没探过就是没探过，不许写成"11 项都能用"。
// 4. **只弹一次靠事件键去重**：键是 `boot:<原因码>`，权威在装配层那本账上（[BootSinks.interruptShown]），
//    不在本进程里。第二遍开检的简报照写（事实不许藏），卡不许再弹——这两件事必须分得开。
//
// 与 290 的分工：斥候那一趟探测（读系统、写能力账）在它自己手里，本文件只读它最近一次的报告；
// 与 291 的分工：御医那 14 个部位的普查与取数在它自己手里，本文件只读它给出的结论（"轻量"＝不重跑体检）。
//
// 一处如实（本批不谎报）：本文件是**判定层**，还没有人调它——开机那一点是 331 接线批的活
// （租约只含这三个新文件，`ZhengdaoApp.onCreate` 不在内）。所以判据①的"≤2 秒"这一批是
// 按"一轮判定＋简报渲染"的耗时算的，真机整轮（含四个部位各自的取数）要等接线那批在机上量。
package com.example.zhengdao.baopu.inspect

import com.example.zhengdao.baopu.scout.Scout
import com.example.zhengdao.baopu.scout.ScoutRecord
import com.example.zhengdao.baopu.scout.ScoutState
import com.example.zhengdao.baopu.scout.UnfinishedItem
import com.example.zhengdao.baopu.scout.UnfinishedOutcome
import com.example.zhengdao.baopu.scout.UnfinishedReason

/** 开机一轮的常量（上限只在这一处写，渲染层不再截第二次——两处口径必打架）。 */
internal object BootLimits {

    /** 一轮开检的时间预算（派工单"1–2 秒"取上界 2 秒；超时不是失败，简报照出，但要如实说）。 */
    const val MAX_BOOT_MS = 2_000L

    /** 简报的**总行数**上限，含表头（派工单"≤10 行"）。 */
    const val MAX_BRIEF_LINES = 10

    /** 未办成单里"这件事是开检发现的"的出处前缀（`boot:<原因码>`，点得回同一条结论）。 */
    const val REF_PREFIX = "boot"

    /** 御医那一句里最多列几个部位名（一句事实不是整张体检单）。 */
    const val MAX_ORGAN_NAMES = 3
}

/** 四个部位（闭集；顺序＝简报行序。改顺序＝改简报，所以写死在这里）。 */
internal enum class BootPart(val wire: String, val cn: String) {
    SCOUT("scout", "斥候"),
    INSPECTOR("inspector", "勘验"),
    PHYSICIAN("physician", "御医"),
    AUDITOR("auditor", "审计"),
}

/**
 * 处置档（派工单三级＋那个唯一例外）。[interrupts] 是"这一档能不能弹卡"的唯一依据。
 *
 * 四档为假、只有 [BLOCKING] 为真——`BootCheckTest` 有一条用例按"全表恰有一格 interrupts 为真"钉它，
 * 多一格、少一格都红（判据④的机器依据就是这一格，不是谁的自觉）。
 */
internal enum class BootDisposition(val wire: String, val cn: String, val interrupts: Boolean) {
    /** 这个部位这次没问题。 */
    NONE("none", "不处置", false),

    /** ①可自恢复：App 自己修，修完不提（只在简报里留一行事实）。 */
    SELF_HEAL("self_heal", "自己修", false),

    /** ②可自决：落一条自决单（"这次没打扰你，因为…"），不弹卡。 */
    SELF_RULING("self_ruling", "自决单", false),

    /** ③必须人：只留一行未办成单，不弹卡。 */
    NEEDS_HUMAN("needs_human", "未办成单", false),

    /** ④唯一例外：活干不下去（账写不了／文件读不了）⇒ 弹一次，说清为什么。 */
    BLOCKING("blocking", "阻断·弹一次", true),
    ;

    /** 同一部位多条事实时取最重的那条作结论（BLOCKING 压过一切）。 */
    val rank: Int get() = ordinal
}

/**
 * 开检查到的一件事（闭集：每条自带部位、处置，以及"这个部位这次还算不算能用"）。
 *
 * @param partUsable false ⇒ 这个部位**给不出可信结论**（判据②：这种一定带原因码，原因码就是 [wire]）
 */
internal enum class BootFinding(
    val wire: String,
    val cn: String,
    val part: BootPart,
    val disposition: BootDisposition,
    val partUsable: Boolean,
) {
    // ── 斥候（能力四态） ────────────────────────────────────────────────────
    SCOUT_NOT_PROBED("scout_not_probed", "还没探过", BootPart.SCOUT, BootDisposition.SELF_RULING, false),
    SCOUT_SHEET_NOT_SAVED("scout_sheet_not_saved", "能力清单没落住", BootPart.SCOUT, BootDisposition.SELF_HEAL, true),
    SCOUT_OVER_BUDGET("scout_over_budget", "探测超预算", BootPart.SCOUT, BootDisposition.SELF_RULING, true),
    SCOUT_PERMISSION_MISSING("scout_permission_missing", "有权限没开", BootPart.SCOUT, BootDisposition.NEEDS_HUMAN, true),
    SCOUT_CAPABILITY_UNAVAILABLE(
        "scout_capability_unavailable", "本机做不了", BootPart.SCOUT, BootDisposition.SELF_RULING, true
    ),
    SCOUT_MIRROR_NOT_SAVED("scout_mirror_not_saved", "账只有私有本", BootPart.SCOUT, BootDisposition.SELF_RULING, true),

    // ── 勘验（工作区可读／账本可写） ────────────────────────────────────────
    WORKSPACE_UNREADABLE("workspace_unreadable", "工作区读不了", BootPart.INSPECTOR, BootDisposition.BLOCKING, false),
    LEDGER_UNWRITABLE("ledger_unwritable", "账本写不了", BootPart.INSPECTOR, BootDisposition.BLOCKING, false),
    LEDGER_DIR_MISSING("ledger_dir_missing", "台账目录不在", BootPart.INSPECTOR, BootDisposition.SELF_HEAL, true),
    LEDGER_UNTESTED("ledger_untested", "落点没测过", BootPart.INSPECTOR, BootDisposition.SELF_RULING, false),

    // ── 御医轻量（该接没接） ────────────────────────────────────────────────
    PHYSICIAN_NOT_RUN("physician_not_run", "御医没给结论", BootPart.PHYSICIAN, BootDisposition.SELF_RULING, false),
    ORGAN_NOT_WIRED("organ_not_wired", "该接没接", BootPart.PHYSICIAN, BootDisposition.SELF_RULING, true),

    // ── 审计（最近 N 行有动作无账） ─────────────────────────────────────────
    AUDIT_NO_DATA("audit_no_data", "无从比对", BootPart.AUDITOR, BootDisposition.SELF_RULING, false),
    AUDIT_GAP("audit_gap", "有动作无账", BootPart.AUDITOR, BootDisposition.SELF_RULING, true),
    ;

    /**
     * 这条要不要写进未办成单的"能不能补／怎么补"两列。
     *
     * 只有 ③ 那一档会走到这里（必须人＋自修失败的升格）。**不可补的照实说不可补**——
     * 派工单负例点名的"骗人说再试一次就行"就是这一格填错时的样子。
     */
    fun fix(): Pair<Boolean, String> = when (this) {
        SCOUT_PERMISSION_MISSING -> true to "去系统设置里把那条权限打开"
        SCOUT_SHEET_NOT_SAVED -> true to "重探一遍，能力清单会重写"
        LEDGER_DIR_MISSING -> true to "重开 App 会自建台账目录"
        else -> false to ""
    }
}

/** 账本落点的四种探针读数（闭集；"没测过"自成一格，不许混进"写不了"）。 */
internal enum class LedgerProbe(val wire: String, val cn: String) {
    WRITABLE("writable", "写得进"),
    DIR_MISSING("dir_missing", "台账目录不在"),
    NOT_WRITABLE("not_writable", "写不进"),

    /** 没探 ⇒ 结论只能是"不知道"，不能替盘说话。 */
    UNTESTED("untested", "没测过"),
}

/** 勘验那一格的读数（工作区可读＋账本落点探针）。 */
internal data class InspectorReading(
    val workspaceReadable: Boolean = false,
    val ledger: LedgerProbe = LedgerProbe.UNTESTED,
)

/**
 * 四个部位交进来的读数（缺的那格一律用 null／[LedgerProbe.UNTESTED] 表达，
 * **不许拿"看起来正常"的默认值顶替**——承 291「取不到数就回 null，绝不拿 0 顶替」）。
 */
internal data class BootReadings(
    /** 斥候：最近一次探测报告（null ⇒ 这一台还没探过／没装配）。 */
    val scout: Scout.Report? = null,
    /** 勘验：工作区与账本落点的探针读数。 */
    val inspector: InspectorReading = InspectorReading(),
    /** 御医轻量：只带它已经给出的部位结论（本批不重跑体检）。 */
    val physician: List<OrganVerdict> = emptyList(),
    /** 审计：最近 N 行有动作无账的那条勘验结论（null ⇒ 没跑过）。 */
    val audit: InspectFinding? = null,
)

/** 一条部位结论（判据②：四部位各一条，不可用必带原因码）。 */
internal data class PartVerdict(
    val part: BootPart,
    /** null ⇒ 这个部位这次没问题。 */
    val finding: BootFinding? = null,
    /** 一句事实（进简报；只写量出来的数，不写意见、不写保证）。 */
    val fact: String,
    /**
     * ①没修成的那一条为真 ⇒ 升格成 [BootDisposition.NEEDS_HUMAN]。
     * 负例③"自修失败静默"就是它没升格时的样子。
     */
    val escalated: Boolean = false,
) {
    init {
        require(fact.isNotBlank()) { "部位结论必须有一句事实：${part.wire}" }
        require(finding == null || finding.part == part) {
            "结论挂错部位：${part.wire} 上挂了 ${finding?.wire}"
        }
        require(!escalated || finding?.disposition == BootDisposition.SELF_HEAL) {
            "只有①自己修那一档谈得上修不成：${finding?.wire}"
        }
    }

    /** 原因码（没问题时是 `ok`；有问题时一定是闭集里的键——判据②要的就是这一格）。 */
    val code: String get() = finding?.wire ?: CODE_OK

    /** 这个部位这次还能不能用。 */
    val usable: Boolean get() = finding?.partUsable != false

    /** 这一条走哪档处置。 */
    val disposition: BootDisposition
        get() = when {
            finding == null -> BootDisposition.NONE
            escalated -> BootDisposition.NEEDS_HUMAN
            else -> finding.disposition
        }

    companion object {
        /** 没问题时的原因码（不留空串：空串在账里读起来等于"没记"）。 */
        const val CODE_OK = "ok"
    }
}

/** ④那一格要弹的一条阻断（带事件键：同一次阻断跨两遍开检只弹一次）。 */
internal data class BootInterrupt(val key: String, val text: String)

/**
 * 开检的四个出口。本文件只调、不实现；实现归装配层，落点归 277（自决单）／290（未办成单）那两本账。
 *
 * 返回值一律是"落没落成"，落成与否**不改变结论**：账写失败自有勘验那一格去说，
 * 开检不许因为记账没成就把这条事实从简报里删掉（照 DriftStore「记账失败不挡事，写失败只 log」）。
 */
internal interface BootSinks {

    /** ①自己修（自建台账目录、重写能力清单）。假 ⇒ 升格成"必须人"，不许静默。 */
    fun repair(finding: BootFinding): Boolean

    /** ②自决单：一句"这次没打扰你，因为…"。 */
    fun noteSelfRuling(finding: BootFinding, reason: String): Boolean

    /** ③未办成单：一行事实（想做的事／结果／原因／能不能补）。 */
    fun recordUnfinished(item: UnfinishedItem): Boolean

    /** 这个事件键是不是已经弹过了（去重的权威在账上，不在本进程里）。 */
    fun interruptShown(key: String): Boolean

    /** 弹那一次；[key] 是去重键。 */
    fun emitInterrupt(key: String, text: String): Boolean
}

/** 一轮开检的产出。 */
internal data class BootReport(
    val atMs: Long,
    /** 本轮耗时（由调用方量——本文件不读时钟）。 */
    val elapsedMs: Long,
    /** 恒四条，按 [BootPart] 的顺序。 */
    val verdicts: List<PartVerdict>,
    /** 简报（≤[BootLimits.MAX_BRIEF_LINES] 行、只写事实；首条消息前回灌的就是它）。 */
    val brief: List<String>,
    /** ①修成了哪几件（不打扰，只在简报里留一行）。 */
    val healed: List<BootFinding>,
    /**
     * ①没修成的哪几件。**这一格不许为空着就当没事**——部位结论被更重的一条占走时
     * （比如"工作区读不了"压住"台账目录没建成"），升格不会发生，但这一格仍然留得住事实。
     */
    val healFailures: List<BootFinding>,
    /** ②落了自决单的哪几件。 */
    val selfRulings: List<BootFinding>,
    /** ③留下的未办成单。 */
    val unfinished: List<UnfinishedItem>,
    /** ④要弹的那一次（常态为空）。 */
    val interrupts: List<BootInterrupt>,
) {
    /** 在 2 秒预算内（判据①；超时不是失败，简报照出）。 */
    val withinBudget: Boolean get() = elapsedMs <= BootLimits.MAX_BOOT_MS

    /** 弹卡次数（判据④断言它是 0，判据⑤断言阻断那一格它是 1）。 */
    val cardCount: Int get() = interrupts.size
}

/**
 * 开检引擎（纯判定：不读时钟、不碰盘、不起线程）。
 */
internal object BootCheck {

    /**
     * 一轮开检。
     *
     * @param readings 四部位的读数（缺哪格就在简报里如实说哪格没数）
     * @param sinks ①修／②自决单／③未办成单／④弹一次 的出口
     * @param elapsedMs 本轮已经花掉的毫秒数（调用方量的）
     */
    fun run(
        readings: BootReadings,
        sinks: BootSinks,
        nowMs: Long,
        elapsedMs: Long,
    ): BootReport {
        val healed = ArrayList<BootFinding>()
        val healFailures = ArrayList<BootFinding>()
        val verdicts = BootPart.entries.map { part ->
            verdictOf(part, readings, candidatesOf(part, readings), sinks, healed, healFailures)
        }
        val rulings = verdicts.filter { it.disposition == BootDisposition.SELF_RULING }
        val needHuman = verdicts.filter { it.disposition == BootDisposition.NEEDS_HUMAN }
        val blocking = verdicts.filter { it.disposition == BootDisposition.BLOCKING }

        rulings.forEach { sinks.noteSelfRuling(it.finding!!, it.fact) }
        val unfinished = needHuman.map { unfinishedOf(it, readings, nowMs) }
            .filter { sinks.recordUnfinished(it) }
        // 一轮最多一枚：两个部位同时卡死也只说一次"我干不了活"，其余留在简报里。
        // 选哪一枚按原因码在闭集里的顺序定（同轮同输入 ⇒ 同一枚，去重键才稳定）。
        val once = blocking.minByOrNull { it.finding!!.ordinal }
        val interrupts = listOfNotNull(once)
            .map { BootInterrupt(keyOf(it.finding!!), interruptText(it)) }
            .filter { !sinks.interruptShown(it.key) && sinks.emitInterrupt(it.key, it.text) }

        return BootReport(
            atMs = nowMs,
            elapsedMs = elapsedMs,
            verdicts = verdicts,
            brief = briefOf(
                verdicts, healed.size, healFailures.size, rulings.size, unfinished.size,
                blocking.size, interrupts.size, elapsedMs
            ),
            healed = healed,
            healFailures = healFailures,
            selfRulings = rulings.map { it.finding!! },
            unfinished = unfinished,
            interrupts = interrupts,
        )
    }

    /** 阻断的事件键（同一件事跨两遍开检只弹一次，靠它去重）。 */
    fun keyOf(finding: BootFinding): String = "${BootLimits.REF_PREFIX}:${finding.wire}"

    // ── 每个部位的候选事实 ──────────────────────────────────────────────────

    private fun candidatesOf(part: BootPart, r: BootReadings): List<BootFinding> = when (part) {
        BootPart.SCOUT -> scoutFindings(r.scout)
        BootPart.INSPECTOR -> inspectorFindings(r.inspector)
        BootPart.PHYSICIAN -> physicianFindings(r.physician)
        BootPart.AUDITOR -> auditFindings(r.audit)
    }

    /** 斥候：四态里有没有否定态，加它自己的两件（清单落没落、超没超预算、账是不是只剩私有本）。 */
    private fun scoutFindings(report: Scout.Report?): List<BootFinding> {
        if (report == null) return listOf(BootFinding.SCOUT_NOT_PROBED)
        val out = ArrayList<BootFinding>()
        if (!report.sheetSaved) out += BootFinding.SCOUT_SHEET_NOT_SAVED
        if (!report.withinBudget) out += BootFinding.SCOUT_OVER_BUDGET
        if (report.privateFallback) out += BootFinding.SCOUT_MIRROR_NOT_SAVED
        if (report.records.any { it.state != ScoutState.UNAVAILABLE && it.blocked }) {
            out += BootFinding.SCOUT_PERMISSION_MISSING
        }
        if (report.records.any { it.state == ScoutState.UNAVAILABLE }) {
            out += BootFinding.SCOUT_CAPABILITY_UNAVAILABLE
        }
        return out
    }

    private fun inspectorFindings(r: InspectorReading): List<BootFinding> {
        val out = ArrayList<BootFinding>(2)
        if (!r.workspaceReadable) out += BootFinding.WORKSPACE_UNREADABLE
        when (r.ledger) {
            LedgerProbe.WRITABLE -> Unit
            LedgerProbe.DIR_MISSING -> out += BootFinding.LEDGER_DIR_MISSING
            LedgerProbe.NOT_WRITABLE -> out += BootFinding.LEDGER_UNWRITABLE
            LedgerProbe.UNTESTED -> out += BootFinding.LEDGER_UNTESTED
        }
        return out
    }

    /** 御医轻量：只数它已经判成"该接没接"且当日进缺陷台账的那几个（裁定关着的不算缺陷）。 */
    private fun physicianFindings(verdicts: List<OrganVerdict>): List<BootFinding> = when {
        verdicts.isEmpty() -> listOf(BootFinding.PHYSICIAN_NOT_RUN)
        notWired(verdicts).isEmpty() -> emptyList()
        else -> listOf(BootFinding.ORGAN_NOT_WIRED)
    }

    private fun notWired(verdicts: List<OrganVerdict>): List<OrganVerdict> = verdicts.filter {
        it.state == OrganState.NOT_WIRED && it.disposition == Disposition.DEFECT
    }

    private fun auditFindings(finding: InspectFinding?): List<BootFinding> = when {
        finding == null -> listOf(BootFinding.AUDIT_NO_DATA)
        finding.verdict == InspectVerdict.HIT -> listOf(BootFinding.AUDIT_GAP)
        finding.verdict == InspectVerdict.NO_DATA -> listOf(BootFinding.AUDIT_NO_DATA)
        else -> emptyList()
    }

    // ── 一条结论（①的修与"修不成要说话"都在这里） ──────────────────────────

    private fun verdictOf(
        part: BootPart,
        r: BootReadings,
        candidates: List<BootFinding>,
        sinks: BootSinks,
        healed: MutableList<BootFinding>,
        healFailures: MutableList<BootFinding>,
    ): PartVerdict {
        val fact = factOf(part, r)
        if (candidates.isEmpty()) return PartVerdict(part, null, fact)
        val heals = candidates.filter { it.disposition == BootDisposition.SELF_HEAL }
        val failed = heals.filter { !sinks.repair(it) }
        healed += heals.filter { it !in failed }
        healFailures += failed
        val rest = candidates.filter { it !in heals }
        val chosen = (rest + failed).maxByOrNull { it.disposition.rank }
            // 只剩"修成了"的那几条 ⇒ 这个部位本轮不留问题（①的定义就是不打扰）。
            ?: return PartVerdict(part, null, fact)
        return PartVerdict(part, chosen, fact, escalated = chosen in failed)
    }

    // ── 那句事实（只写数出来的东西；四部位各一句） ──────────────────────────

    private fun factOf(part: BootPart, r: BootReadings): String = when (part) {
        BootPart.SCOUT -> scoutFact(r.scout)
        BootPart.INSPECTOR -> "工作区可读=${yes(r.inspector.workspaceReadable)}·" +
            "账本${r.inspector.ledger.cn}"
        BootPart.PHYSICIAN -> physicianFact(r.physician)
        BootPart.AUDITOR -> r.audit?.oneLine ?: "审计这一轮没跑，没有比对结论"
    }

    /**
     * 斥候那一句：四态各数一遍（能用／曾收回／被驳回／本机做不了）。
     *
     * 没探过时**不许写成"11 项都能用"**——负例"不可用的能力仍假装能做"就红在这一句写虚。
     */
    private fun scoutFact(report: Scout.Report?): String {
        if (report == null) return "还没探过：能力四态一个数都没有"
        val n = report.records
        fun count(state: ScoutState): Int = n.count { it.state == state }
        return "${n.size} 项能力：能用 ${count(ScoutState.GRANTED)}、曾收回 ${count(ScoutState.REVOKED)}、" +
            "被驳回 ${count(ScoutState.DENIED)}、本机做不了 ${count(ScoutState.UNAVAILABLE)}"
    }

    private fun physicianFact(verdicts: List<OrganVerdict>): String {
        if (verdicts.isEmpty()) return "御医没给结论：这一轮没有体检读数"
        val dead = notWired(verdicts)
        val names = dead.take(BootLimits.MAX_ORGAN_NAMES).joinToString("、") { it.name }
        val more = if (dead.size > BootLimits.MAX_ORGAN_NAMES) "等" else ""
        return "体检结论 ${verdicts.size} 条：该接没接 ${dead.size} 个" +
            (if (names.isEmpty()) "" else "（$names$more）")
    }

    // ── ③未办成单 / ④阻断那一次 ─────────────────────────────────────────────

    private fun unfinishedOf(v: PartVerdict, r: BootReadings, nowMs: Long): UnfinishedItem {
        val finding = v.finding!!
        val cap = if (finding == BootFinding.SCOUT_PERMISSION_MISSING) missingPermission(r.scout) else null
        val intent = if (cap != null) {
            "开机自检：打开「${cap.capability.cn}」权限"
        } else {
            "开机自检：${v.part.cn}——${finding.cn}"
        }
        val (fixable, hint) = finding.fix()
        return UnfinishedItem(
            intent = intent,
            outcome = UnfinishedOutcome.NOT_DONE,
            reason = reasonOf(finding, v.escalated),
            reasonDetail = if (cap != null) "${cap.capability.cn}：${cap.reason.ifBlank { "现在没开" }}" else v.fact,
            reasonRef = "${BootLimits.REF_PREFIX}:${finding.wire}",
            fixable = fixable,
            fixHint = hint,
            atMs = nowMs,
        )
    }

    /**
     * 未办成单的原因档。
     *
     * 自修失败（升格的那几条）一律记 `other`＋那句事实：**不许写成"缺权限"**，
     * 因为用户照"去开权限"办也办不通——这一格认错，未办成单就从"能补"变成"骗人"。
     */
    private fun reasonOf(finding: BootFinding, escalated: Boolean): UnfinishedReason = when {
        escalated -> UnfinishedReason.OTHER
        finding == BootFinding.SCOUT_PERMISSION_MISSING -> UnfinishedReason.MISSING_PERMISSION
        finding == BootFinding.WORKSPACE_UNREADABLE || finding == BootFinding.LEDGER_UNWRITABLE ->
            UnfinishedReason.PATH_NOT_FOUND
        else -> UnfinishedReason.OTHER
    }

    /** 哪一项能力是"人没开"（不是"本机没有"）——未办成单要点名到具体能力，所以只取第一条。 */
    private fun missingPermission(report: Scout.Report?): ScoutRecord? =
        report?.records?.firstOrNull { it.state != ScoutState.UNAVAILABLE && it.blocked }

    /** ④那一句（派工单原话："我干不了活，因为 X"）。 */
    private fun interruptText(v: PartVerdict): String {
        val finding = v.finding!!
        return "我干不了活，因为${finding.cn}（${v.part.cn}·原因码 ${finding.wire}）：${v.fact}"
    }

    // ── 简报 ────────────────────────────────────────────────────────────────

    /**
     * ≤10 行、只写事实：表头 1 行＋四部位各 1 行＋①②③④各至多 1 行 ＝ 结构上界 9 行。
     *
     * 所以这里**没有**"超了就截断"这一步：行数超了只可能是有人加了第五类行，
     * 那种事该红，不该被 `take(10)` 藏掉——[check] 就是给它红的地方，判据由测试按上限钉。
     */
    private fun briefOf(
        verdicts: List<PartVerdict>,
        healed: Int,
        healFailed: Int,
        rulings: Int,
        unfinished: Int,
        blocking: Int,
        cards: Int,
        elapsedMs: Long,
    ): List<String> {
        val usable = verdicts.count { it.usable }
        val budget = if (elapsedMs <= BootLimits.MAX_BOOT_MS) "预算内" else "超预算"
        val lines = ArrayList<String>(BootLimits.MAX_BRIEF_LINES)
        lines += "开机自检：四部位 $usable/${verdicts.size} 可用｜耗时 ${elapsedMs}ms｜$budget"
        verdicts.forEach { v -> lines += "${v.part.cn}：${v.fact}（${v.code}）" }
        if (healed + healFailed > 0) {
            lines += "①自修 ${healed + healFailed} 项：成 $healed、没修成 $healFailed（后者一律要人看）"
        }
        if (rulings > 0) lines += "②自决 $rulings 项：没打扰你，理由落自决单（不弹卡）"
        if (unfinished > 0) lines += "③要你办 $unfinished 项：只记了一行未办成单（不弹卡）"
        // 事实不许因为"已经弹过了"就从简报里消失：这一行按阻断条数写，弹几次另说。
        if (blocking > 0) lines += "④阻断 $blocking 项：本轮弹 $cards 次（弹过的不重复弹）"
        check(lines.size <= BootLimits.MAX_BRIEF_LINES) {
            "简报 ${lines.size} 行，超了派工单的 ${BootLimits.MAX_BRIEF_LINES} 行上限"
        }
        return lines
    }

    private fun yes(ok: Boolean): String = if (ok) "是" else "否"
}
