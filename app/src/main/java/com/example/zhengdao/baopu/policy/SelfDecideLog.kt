// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第三百三十一批 · **自决账**：左相每自决一笔，就落一张单；每张单都能被推翻。
//
// 出处：第三百三十一批派工单 §②「自决单字段：时间｜动作｜依据｜可否回退｜回退方式；
// 收工一行摘要 ≤1 行，数字要对得上账」、§③「三读数进体检」。
//
// ## 三件必须做到的事（每件都有对应的负例测试）
// 1. **自决不落单**：[record] 是唯一入口，每调一次恰好落一行——自决落 [SelfSlip]，
//    该问的落 [AskRecord]。「判了却没留痕」在本层没有 API 可走；空依据也落不下来。
// 2. **摘要数字对得上账**：[summaryLine] 与 [reconcile] 出自同一个 [counts]，
//    并且 [auditSummary] 能把一份**外来**的摘要逐格与账对平——所以「摘要说 3、账里 2」
//    这种账会被抓出来，而不是靠人眼。
// 3. **误自决可被推翻**：[overturn] 追加一行 [OverturnRecord]，**不改写原单**——
//    原单永远在（可查），推翻本身也是一笔可查的账。重复推翻要拒（否则误自决率会算重）。
//
// ## 三读数的分母（派工单 §③：分母全是外部事实，不取意见条数）
// - 自决率 ＝ 自决单数 ÷ 决策总笔数（＝自决单数＋必须问条数，账里的**行数**）；
// - 打扰比 ＝ 必须问条数 ÷ 决策总笔数；
// - 误自决率 ＝ 被推翻的单数 ÷ 自决单数。
// 分母一律是**行数事实**，不是 `Opinion` 的条数：意见是判据自己的产物，
// 拿它当分母就是自己量自己（本文件因此一个 `Opinion` 都不引用，见 `SelfGateTest`）。
// 另有一道跨账核对：[readings] 收 208 账本行数，自决笔数比它多 ⇒ 账实不符（有自决没进 208）。
//
// 本文件零 IO、零时钟、零 Android（`PolicySourceGateTest.policy包除资产壳外零Android零IO零网络` 钉着）：
// 落盘由装配层做（一行一条 [encode] 的文本），读盘由 `inspect/HealthSources` 做（只读）。
//
// ## 与 277 那本「追认队列」的关系（两本账都叫自决单，必须说清谁是权威）
// 基线里已有 `agent/SelfRulingLedger`（第二百七十七批，落 `追认队列.jsonl`）：那是 L33 的**最小版**——
// 只记「为什么判为不必打扰」＋指向一条 Opinion，形状是 id/at/round/tool/gist/law/why/reason/op，
// 没有「可否回退」「回退方式」，也没有推翻与三读数。本批这本是**五字段版**（派工单 §②）：
// 时间｜动作｜依据｜可否回退｜回退方式 ＋ 推翻行 ＋ 三读数。
// 两本**本批不合并**（合并要动 `agent/SelfRulingLedger` 与 L33 门禁，都在租约外）：
// 现在的分工是「277 那本管紧急卡那一路的追认，331 这本管左相打扰闸的自决」；
// 合并与「谁是唯一真相」留一张单，风险写进交付报告，不在这里偷偷定。
package com.example.zhengdao.baopu.policy

/** 账里一行是什么（闭集三个；改字面量＝老账对不上）。 */
internal enum class SelfRowKind(val wire: String) {
    /** 自决单。 */
    SLIP("slip"),

    /** 必须问（打扰了人，也要有账）。 */
    ASK("ask"),

    /** 推翻（指到某张自决单）。 */
    OVERTURN("overturn"),
}

/** 账里一行的公共部分（[id] 是主键，不是自决单的第五个字段）。 */
internal sealed class SelfRow {
    abstract val id: String
    abstract val atMs: Long
    abstract val kind: SelfRowKind
}

/**
 * 一张**自决单**：时间｜动作｜依据｜可否回退｜回退方式（五个字段，一个不多）。
 *
 * [id] 是主键（推翻要指得到它），不算字段——所以 [columns] 恰好五列。
 */
internal data class SelfSlip(
    override val id: String,
    override val atMs: Long,
    /** 动作原文。 */
    val action: String,
    /** 依据（判词＋调用方补的事实；不许为空）。 */
    val basis: String,
    /** 可否回退（有回退手段才是「是」）。 */
    val rollbackable: Boolean,
    /** 回退方式（可回退 ⇒ 怎么退；不可回退 ⇒ [NO_ROLLBACK]）。 */
    val rollbackHow: String,
) : SelfRow() {

    override val kind: SelfRowKind get() = SelfRowKind.SLIP

    /** 五个字段（时间／动作／依据／可否回退／回退方式），顺序与 [SelfDecideLog.encode] 一致。 */
    fun columns(): List<String> = listOf(
        atMs.toString(),
        action,
        basis,
        if (rollbackable) "是" else "否",
        rollbackHow,
    )

    companion object {
        /** 不可回退时「回退方式」一栏的固定词（留空会被读成「忘了填」）。 */
        const val NO_ROLLBACK = "—"
    }
}

/** 一条「必须问」的账（打扰了人，也得能查：问了什么、按哪一类问的、凭什么）。 */
internal data class AskRecord(
    override val id: String,
    override val atMs: Long,
    val action: String,
    val askClass: AskClass,
    val why: String,
) : SelfRow() {
    override val kind: SelfRowKind get() = SelfRowKind.ASK
}

/** 一条推翻（只追加，不改写原单）。 */
internal data class OverturnRecord(
    override val id: String,
    override val atMs: Long,
    /** 被推翻的自决单 id。 */
    val slipId: String,
    val why: String,
) : SelfRow() {
    override val kind: SelfRowKind get() = SelfRowKind.OVERTURN
}

/** 账里各格的计数（[summaryLine]、[reconcile]、[readings] 的唯一来源）。 */
internal data class SelfCounts(
    val total: Int,
    val slips: Int,
    val asks: Int,
    val overturns: Int,
    val rollbackable: Int,
    val unrollbackable: Int,
    val byClass: Map<AskClass, Int>,
    /** 被推翻过的**单数**（按 id 去重：同一条单被指两次也只算一笔）。 */
    val overturned: Int,
)

/** 对账结论（[ok] 为真才算「摘要数字对得上账」）。 */
internal data class SelfReconcile(val counts: SelfCounts, val problems: List<String>) {

    val ok: Boolean get() = problems.isEmpty()

    /** 一行结论（相符时说清几行几笔，不符时把每一处不符都列出来）。 */
    fun text(): String = if (ok) {
        "账实相符：${counts.total} 行（自决 ${counts.slips}／必须问 ${counts.asks}／推翻 ${counts.overturns}）"
    } else {
        problems.joinToString("；")
    }
}

/**
 * 一条率的读数（分子分母**都给**，与 `inspect` 的 `Ratio` 同一口径）。
 *
 * 三态：分母 0 ⇒「无数据」；分母 <[MIN_DENOM] ⇒「样本不足」；否则印整数百分比。
 * **算不出就不印 %**——印 0% 会被读成「一次都没有」。
 */
internal data class SelfReading(val cn: String, val num: Int, val denom: Int) {

    /** 分母够不够算。 */
    val known: Boolean get() = denom >= MIN_DENOM

    /** 一行字。 */
    fun text(): String {
        val keys = "分子 $num／分母 $denom"
        return when {
            denom == 0 -> "$cn 无数据（$keys：这件事还没发生过）"
            !known -> "$cn 样本不足（$keys，<$MIN_DENOM），算不出率"
            else -> "$cn ${num * 100 / denom}%（$keys）"
        }
    }

    companion object {
        /** 最小分母（与 `inspect.Metrics.MIN_DENOM` 同口径，`SelfDecideLogTest` 钉着两边相等）。 */
        const val MIN_DENOM = 10
    }
}

/** 三读数（自决率／误自决率／打扰比）＋ 跨账核对出来的缺口。 */
internal data class SelfReadings(
    val selfRate: SelfReading,
    val misSelfRate: SelfReading,
    val disturbRate: SelfReading,
    /** 账实对不上的地方（空＝对得上）。 */
    val gaps: List<String>,
) {
    /** 给体检单的三行（缺口另起行；缺一条都不许静默）。 */
    fun lines(): List<String> = listOf(selfRate.text(), misSelfRate.text(), disturbRate.text()) + gaps
}

/**
 * 自决账（追加式；[record]／[overturn] 都回一份新账，不改旧账）。
 *
 * 不可变是刻意的：账一旦落就不能被就地改写——推翻只能是**追加一行**。
 */
internal class SelfDecideLog private constructor(private val rows: List<SelfRow>) {

    /** 行数。 */
    val size: Int get() = rows.size

    val slips: List<SelfSlip> get() = rows.filterIsInstance<SelfSlip>()

    val asks: List<AskRecord> get() = rows.filterIsInstance<AskRecord>()

    val overturns: List<OverturnRecord> get() = rows.filterIsInstance<OverturnRecord>()

    /** 按 id 取一张单（「每笔可查」的取用口）。 */
    fun slip(id: String): SelfSlip? = slips.firstOrNull { it.id == id }

    /**
     * 落一笔（**唯一入口**：判了就一定留痕）。
     *
     * @param basis 自决单的「依据」；默认用判词，调用方可以补事实，但**不许为空**
     * @throws IllegalArgumentException 依据为空（不落空单）、时间为 0
     */
    fun record(ruling: SelfJudgement, atMs: Long, basis: String = ruling.why): SelfDecideLog {
        require(atMs > 0L) { "自决单要有时间：atMs 不许为 0" }
        require(basis.isNotBlank()) { "自决单缺依据：宁可多问一句，也不许落空单" }
        val asked = ruling.askClass
        val row: SelfRow = if (asked != null) {
            AskRecord(idOf(SelfRowKind.ASK, atMs, rows.size + 1), atMs, ruling.request.action, asked, basis)
        } else {
            val rollback = ruling.request.rollback
            SelfSlip(
                id = idOf(SelfRowKind.SLIP, atMs, rows.size + 1),
                atMs = atMs,
                action = ruling.request.action,
                basis = basis,
                rollbackable = rollback is Rollback.Ready,
                rollbackHow = (rollback as? Rollback.Ready)?.how ?: SelfSlip.NO_ROLLBACK,
            )
        }
        return SelfDecideLog(rows + row)
    }

    /**
     * 推翻一笔自决（追加一行，原单保留）。
     *
     * 三条拒绝：理由为空（推翻不可查）、指不到真实单（推翻不存在的账）、重复推翻
     * （会把误自决率算重）。
     */
    fun overturn(slipId: String, why: String, atMs: Long): SelfDecideLog {
        require(why.isNotBlank()) { "推翻要写理由：没有理由的推翻不可查，也不能拿来改判据" }
        require(atMs > 0L) { "推翻要有时间：atMs 不许为 0" }
        require(slips.any { it.id == slipId }) { "推翻必须指到一张真实存在的自决单：$slipId" }
        require(overturns.none { it.slipId == slipId }) {
            "这张自决单已经被推翻过：$slipId（重复推翻会把误自决率算重）"
        }
        return SelfDecideLog(
            rows + OverturnRecord(idOf(SelfRowKind.OVERTURN, atMs, rows.size + 1), atMs, slipId, why),
        )
    }

    /** 各格计数（摘要、对账、三读数的唯一来源）。 */
    fun counts(): SelfCounts {
        val slipRows = slips
        val askRows = asks
        val overturnedIds = overturns.map { it.slipId }.distinct()
        return SelfCounts(
            total = rows.size,
            slips = slipRows.size,
            asks = askRows.size,
            overturns = overturns.size,
            rollbackable = slipRows.count { it.rollbackable },
            unrollbackable = slipRows.count { !it.rollbackable },
            byClass = AskClass.entries.associateWith { k -> askRows.count { it.askClass == k } },
            overturned = overturnedIds.size,
        )
    }

    /** 收工摘要（**一行**；数字与 [counts] 同源）。 */
    fun summaryLine(): String {
        val c = counts()
        return "[自决] 收工：自决 ${c.slips} 笔（可回退 ${c.rollbackable}／不可回退 ${c.unrollbackable}）｜" +
            "必须问 ${c.asks} 笔（${classCells(c)}）｜推翻 ${c.overturned} 笔｜账 ${c.total} 行"
    }

    /** 把一份**外来**摘要逐格与账对平（摘要与账不符的负例就是靠它抓的）。 */
    fun auditSummary(line: String): List<String> {
        val problems = mutableListOf<String>()
        if (line.contains('\n') || line.contains('\r')) {
            problems += "收工摘要必须是一行（换行会把一条账劈成两条）"
        }
        val m = SUMMARY_RE.find(line.trim())
            ?: return problems + "摘要格式不认识：$line"
        val got = m.groupValues.drop(1).mapNotNull { it.toIntOrNull() }
        val want = summaryNumbers(counts())
        if (got.size != want.size) return problems + "摘要列数 ${got.size} ≠ 账的列数 ${want.size}"
        val labels = summaryLabels()
        labels.indices.forEach { i ->
            if (got[i] != want[i]) problems += "摘要与账不符：${labels[i]} 摘要写 ${got[i]}，账里是 ${want[i]}"
        }
        return problems
    }

    /** 对账（行数、分类和、id 唯一、推翻指得到）。 */
    fun reconcile(): SelfReconcile {
        val c = counts()
        val problems = mutableListOf<String>()
        if (c.slips + c.asks + c.overturns != c.total) {
            problems += "行数对不上：自决 ${c.slips}＋必须问 ${c.asks}＋推翻 ${c.overturns} ≠ 总行 ${c.total}"
        }
        if (c.byClass.values.sum() != c.asks) {
            problems += "必须问分类合不上：分类和 ${c.byClass.values.sum()} ≠ 必须问 ${c.asks}"
        }
        if (slips.map { it.id }.distinct().size != slips.size) problems += "自决单 id 有重复"
        overturns.forEach { o ->
            if (slips.none { it.id == o.slipId }) problems += "推翻指到不存在的单：${o.slipId}"
        }
        return SelfReconcile(c, problems)
    }

    /**
     * 三读数。
     *
     * @param ledgerTotal 208 决策账的行数（外部事实；null ＝ 读不动，那时只印「未取样」）
     */
    fun readings(ledgerTotal: Int? = null): SelfReadings {
        val c = counts()
        val routed = c.slips + c.asks
        return SelfReadings(
            selfRate = SelfReading("自决率", c.slips, routed),
            misSelfRate = SelfReading("误自决率", c.overturned, c.slips),
            disturbRate = SelfReading("打扰比", c.asks, routed),
            gaps = gapsOf(c, ledgerTotal),
        )
    }

    /** 落盘文本（一行一条；列序见 [encodeRow]）。 */
    fun encode(): List<String> = rows.map(::encodeRow)

    /**
     * 跨账核对：自决笔数与 208 账行数对不对得上。
     *
     * 对不上就**单独说**，不许悄悄印一个率——自决是「真做了事」，208 里必然有账；
     * 自决比 208 还多，只能是有一笔没进账。
     */
    private fun gapsOf(c: SelfCounts, ledgerTotal: Int?): List<String> = buildList {
        when {
            ledgerTotal == null -> add("账实未取样：208 决策账读不动，自决笔数没得对")
            c.slips > ledgerTotal -> add("账实不符：自决 ${c.slips} 笔 > 208 账 $ledgerTotal 行（有自决没进账）")
            ledgerTotal > 0 && c.slips + c.asks == 0 -> add("自决账没跟上：208 有 $ledgerTotal 行，本账 0 笔")
        }
    }

    companion object {

        /** 空账。 */
        fun empty(): SelfDecideLog = SelfDecideLog(emptyList())

        /**
         * 从落盘文本读回（坏行按 208 同款丢弃——读侧另有行数可比对，差多少就是坏多少）。
         */
        fun parse(lines: List<String>): SelfDecideLog =
            SelfDecideLog(lines.mapNotNull(::parseRow))

        /** 一行一条：`slip|id|at|动作|依据|是/否|回退方式`，`ask|id|at|动作|类别|判词`，
         *  `overturn|id|at|被推翻的单|理由`。 */
        private fun encodeRow(row: SelfRow): String = when (row) {
            is SelfSlip -> listOf(
                row.kind.wire, row.id, row.atMs.toString(), esc(row.action), esc(row.basis),
                if (row.rollbackable) "是" else "否", esc(row.rollbackHow),
            ).joinToString(SEP)

            is AskRecord -> listOf(
                row.kind.wire, row.id, row.atMs.toString(), esc(row.action), row.askClass.wire, esc(row.why),
            ).joinToString(SEP)

            is OverturnRecord -> listOf(
                row.kind.wire, row.id, row.atMs.toString(), row.slipId, esc(row.why),
            ).joinToString(SEP)
        }

        private fun parseRow(line: String): SelfRow? {
            val cells = splitEscaped(line)
            if (cells.size < 2) return null
            val kind = SelfRowKind.entries.firstOrNull { it.wire == cells[0] } ?: return null
            val atMs = cells.getOrNull(2)?.toLongOrNull() ?: return null
            return when (kind) {
                SelfRowKind.SLIP -> if (cells.size != 7) null else SelfSlip(
                    id = cells[1], atMs = atMs, action = cells[3], basis = cells[4],
                    rollbackable = cells[5] == "是", rollbackHow = cells[6],
                )
                SelfRowKind.ASK -> {
                    val ask = AskClass.entries.firstOrNull { it.wire == cells.getOrNull(4) } ?: return null
                    if (cells.size != 6) null else AskRecord(cells[1], atMs, cells[3], ask, cells[5])
                }
                SelfRowKind.OVERTURN -> if (cells.size != 5) null else OverturnRecord(
                    id = cells[1], atMs = atMs, slipId = cells[3], why = cells[4],
                )
            }
        }

        /** 主键：`<kind>:<时刻>#<第几行>`（同毫秒的两条也分得开）。 */
        private fun idOf(kind: SelfRowKind, atMs: Long, seq: Int): String = "${kind.wire}:$atMs#$seq"

        /** 摘要里各格的顺序（[summaryLine] 与 [SUMMARY_RE] 都按这一份走）。 */
        private fun summaryLabels(): List<String> =
            listOf("自决", "可回退", "不可回退", "必须问") +
                AskClass.entries.map { it.cn } + listOf("推翻", "账")

        private fun summaryNumbers(c: SelfCounts): List<Int> =
            listOf(c.slips, c.rollbackable, c.unrollbackable, c.asks) +
                AskClass.entries.map { c.byClass.getValue(it) } + listOf(c.overturned, c.total)

        private fun classCells(c: SelfCounts): String =
            AskClass.entries.joinToString("／") { "${it.cn} ${c.byClass.getValue(it)}" }

        /** 列分隔符（`joinToString` 要字符串，`when` 分支要比字符，两处同源）。 */
        private const val SEP = "|"

        private const val SEP_CHAR = '|'

        /** 摘要的正则：按 [summaryLabels] 的顺序、由同一个枚举拼出来（不手抄一份）。 */
        private val SUMMARY_RE: Regex = Regex(
            """^\[自决\] 收工：自决 (\d+) 笔（可回退 (\d+)／不可回退 (\d+)）｜必须问 (\d+) 笔（""" +
                AskClass.entries.joinToString("／") { Regex.escape(it.cn) + """ (\d+)""" } +
                """）｜推翻 (\d+) 笔｜账 (\d+) 行$""",
        )

        /** 转义：`\` 与 `|` 都要躲开，换行压平（一行一条）。 */
        private fun esc(v: String): String = buildString {
            v.forEach { c ->
                when (c) {
                    '\\' -> append("\\\\")
                    SEP_CHAR -> append("\\|")
                    '\n', '\r' -> append(' ')
                    else -> append(c)
                }
            }
        }

        /** 按未转义的分隔符切（手写扫描：正则切不动 `\|`）。 */
        private fun splitEscaped(line: String): List<String> {
            val out = ArrayList<String>()
            val cell = StringBuilder()
            var i = 0
            while (i < line.length) {
                val c = line[i]
                when {
                    c == '\\' && i + 1 < line.length -> {
                        cell.append(line[i + 1])
                        i += 2
                    }
                    c == SEP_CHAR -> {
                        out += cell.toString()
                        cell.setLength(0)
                        i++
                    }
                    else -> {
                        cell.append(c)
                        i++
                    }
                }
            }
            out += cell.toString()
            return out
        }
    }
}
