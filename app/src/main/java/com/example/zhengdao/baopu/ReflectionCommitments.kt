// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第二百三十六批 · 进化档案的**闭合**层（派工单 §3；纯判据，零 IO、零时钟、零 Android）。
//
// 要解决的唯一问题：**反思能不能连着上一次**。205 已经把"这次说了一句什么"记进
// `产物/进化档案.md`，但那是**候选卡的流水**——它没有"我答应要改 → 到底改没改 → 改完有没有变好"
// 这一段账。缺这段，每次反思都只能从头感慨一遍（派工单 §3 原话），而"从头感慨"正是
// "人替它反思"的典型症状：模型不记得自己欠着什么，就得由人来提醒。
//
// 为什么不加列复用 `进化档案.md`：205 的 `ReflectionArchive.parse` 按**位置**取字段（1..6），
// 且对 `fields.size < 7` 的行直接跳过。加第 7 列不会报错，会被**静默读错位**——
// 档案错位比没档案更坏（它开始说假话）。所以本层另开一个文件，各记各的。
//
// 四条硬判据（每条都对上一条派工单负例）：
// · **可执行**：一条承诺必须同时有落点、动作、验法；只有感想 ⇒ 拒收，不写进档案（负例⑤）。
// · **必跟进**：下一轮反思**先读**未闭合的承诺；只出新东西、旧账一条不接 ⇒ 判违规（负例⑥）。
// · **闭合三问**：承诺 → 改了没 → 有没有变好，三问不齐的键挂在"欠账"里，不消失。
// · **没批 ≠ 不同意**：人没应答的 B 档静默等着，同键只留一行、不再重摆；
//   只有明确"否决"才进冷档（负例⑦——把两态合成一态才会反复骚扰）。
package com.example.zhengdao.baopu

import com.example.zhengdao.core.RunLog
import java.io.File
import java.time.LocalDate
import java.time.temporal.ChronoUnit

// ── 四段（一行只填一段；三问闭合＝同一键出现前三段）───────────────────────────

/** 档案行的段位（闭集；线值就是盘上第三列）。 */
internal enum class CommitmentStage(val wire: String) {
    /** 我说要改什么。 */
    PROMISE("承诺"),

    /** 改了没。 */
    RESULT("改了没"),

    /** 有没有变好（206 对拍或人抽查的结论）。 */
    OUTCOME("有没有变好"),

    /** 问过人了没人应答——B 档的**静默**标记（负例⑦靠它把"没批"和"不同意"分开）。 */
    ASKED("问过人了"),
}

/** 「改了没」的结论（闭集）。 */
internal enum class CommitmentResult(val wire: String) {
    /** 落盘了（A 档自改成功、或 B 档人批后执行）。 */
    DONE("改成"),

    /** 没落盘，还欠着（下一步接着改）。 */
    NOT_DONE("没改成"),

    /** 不打算改了——**必须带理由**（派工单 §3：要么接着改，要么写清为什么放弃）。 */
    ABANDONED("放弃"),

    /** 想做但做不了（没通道/没额度/前置件没到）；和"放弃"不同，它下一轮还要回来。 */
    BLOCKED("卡住"),
}

/** 「有没有变好」的结论（闭集）。 */
internal enum class CommitmentOutcome(val wire: String) {
    /** 206 判 ADOPT，或人抽查说确实好了。 */
    BETTER("变好"),

    /** 跑了对拍，没见好（DEFER）——不算坏，但账没闭合。 */
    SAME("没变好"),

    /** 206 判 REJECT 或结果列退化 ⇒ A 档必须已回滚。 */
    WORSE("变差"),

    /** 压根没验（"自治 ≠ 免检"的那半：这一态出现即欠验，不能算闭合）。 */
    UNCHECKED("没验"),
}

/** 「问过人了」的应答态（闭集；**UNANSWERED 与 DISAGREED 绝不能合成一态**）。 */
internal enum class CommitmentAsk(val wire: String) {
    /** 摆了卡，没人点（静默等待；同键只留一行，不再重摆）。 */
    UNANSWERED("没人应答"),

    /** 人点了同意。 */
    AGREED("批了"),

    /** 人点了不同意（这才进冷档，同键一段时间不再提）。 */
    DISAGREED("否决"),
}

// ── 一行（盘上事实的内存形状）────────────────────────────────────────────────

/**
 * 一行承诺档案。
 *
 * @param key 承诺键——**与 205 的去重键同形**（`落点::改法`），这样"档案里的账"和
 *   "反思器不再重复提的那条"是同一个键；两套键迟早对不上，所以不提供第二种拼法。
 * @param verdict 段位对应的结论线值（认不认得由 [CommitmentArchive.parse] 把关，认不出整行丢）。
 */
internal data class CommitmentLine(
    val date: LocalDate,
    val key: String,
    val stage: CommitmentStage,
    val verdict: String,
    /** 依据：文件路径/对拍行/抽查记录。**「放弃」这一段非空不可**。 */
    val evidence: String = "",
    /** 给人看的一句话。 */
    val note: String = "",
    /** 这条走的哪档（A/B；记账与档位无关，但闭合样例要能说明"谁写的"）。 */
    val tier: String = "",
)

// ── 读写（append-only；历史行一个字节不改）─────────────────────────────────────

internal object CommitmentArchive {

    /** 档案文件名（放 `<工作区>/产物/`，与 205 的 `进化档案.md` 同屋不同件）。 */
    const val FILE_NAME = "进化档案-承诺.md"

    /** 新建时的表头（只有第一次 append 才写）。 */
    fun header(): String = listOf(
        "# 进化档案·承诺（append-only：只在承诺推进时追加新行，历史行不改）",
        "| 日期 | 承诺键 | 段 | 结论 | 档位 | 依据 | 一句话 |",
        "|---|---|---|---|---|---|---|",
    ).joinToString("\n")

    /** 一行 ⇒ 盘上文本（列里的 `|` 换成 `／`，不然表格被撑开、下一轮读不成）。 */
    fun renderLine(line: CommitmentLine): String {
        val cell = listOf(
            line.date.toString(),
            line.key,
            line.stage.wire,
            line.verdict,
            line.tier,
            line.evidence,
            line.note,
        ).joinToString(" | ") { it.replace("|", "／").trim() }
        return "| $cell |"
    }

    /** 全文 ⇒ 行（坏行跳过，**不猜**：段名/结论认不出就整行丢，丢的行由 [unreadableCount] 暴露）。 */
    fun parse(text: String?): List<CommitmentLine> {
        if (text.isNullOrBlank()) return emptyList()
        val out = mutableListOf<CommitmentLine>()
        text.lines().forEach { raw ->
            val line = raw.trim()
            if (!line.startsWith("|")) return@forEach
            val f = line.split("|").map { it.trim() }
            // | 日期 | 键 | 段 | 结论 | 档位 | 依据 | 一句话 | ⇒ 首尾空串，实字段 1..7
            if (f.size < 8) return@forEach
            val date = runCatching { LocalDate.parse(f[1]) }.getOrNull() ?: return@forEach
            val key = f[2].takeIf { it.isNotEmpty() } ?: return@forEach
            val stage = CommitmentStage.entries.firstOrNull { it.wire == f[3] } ?: return@forEach
            if (f[4].isEmpty()) return@forEach
            out += CommitmentLine(
                date = date,
                key = key,
                stage = stage,
                verdict = f[4],
                tier = f[5],
                evidence = f[6],
                note = f[7],
            )
        }
        return out
    }

    /** 认得出的表体行数——"到底写进去几行"就数这个。 */
    fun rowCount(text: String?): Int = parse(text).size

    /** 表体行数（内部用：认得出的＋认不出的）。 */
    private fun bodyRows(text: String?): Int =
        text.orEmpty().lines().count {
            val t = it.trim()
            // 只认表头那一行（连"承诺键"一起对），别拿"含日期"当排除条件——
            // 手改坏的数据行里也可能带"日期"两个字，那样它就躲过计数、坏行永远露不出来。
            t.startsWith("|") && !t.contains("日期 | 承诺键") && !t.replace(" ", "").startsWith("|---")
        }

    fun hasHeader(text: String?): Boolean = text.orEmpty().contains("| 日期 | 承诺键 |")

    /** 表体里认不出的行数（档案被手改坏时唯一的可见处；正常应为 0）。 */
    fun unreadableCount(text: String?): Int = bodyRows(text) - parse(text).size
}

// ── 承诺该不该被记下来（负例⑤：只有感想 ⇒ 不收）────────────────────────────────

/** 一条承诺的"可执行"形状。 */
internal data class PromiseShape(
    val key: String,
    val target: String,
    /** 验法：怎么算"变好"（对拍用例名／要看的产物／抽查的一句判据）。空 ⇒ 只是感想。 */
    val verifyBy: String,
    /** 动作句（"补一条示例"这种；空或只有感想词 ⇒ 不可执行）。 */
    val action: String,
)

internal object ClosureRules {

    /** 泛泛感想的开头词（派工单 §3 点名的症状："要注意细节"这类——没有落点，也就没有下一步）。 */
    private val VAGUE_WORDS = listOf("要注意", "注意", "应该更好", "优化一下", "提升", "改进", "加强", "完善")

    /** 未闭合的承诺挂多少天就该正面处理一次（见 [overdue]）。 */
    const val OVERDUE_DAYS = 7L

    /** 「人明确否决」的冷档天数：**沿用 205 的 30 天**，不另立第二个数（两个数迟早分叉）。 */
    const val DISAGREE_COOLDOWN_DAYS: Long = ReflectionDedup.REJECT_WINDOW_DAYS

    /**
     * 这条承诺收不收（负例⑤）。
     *
     * 四样齐才收：**键、落点、动作、验法**。缺验法是最常见的假承诺——
     * "以后注意细节"有动作没验法，永远判不了"有没有变好"，于是档案里的账永远不闭合，
     * 而人会以为它改过。所以宁可不记，也不要记一条没法还的账。
     */
    fun promiseFindings(p: PromiseShape): List<String> {
        val out = mutableListOf<String>()
        if (p.key.isBlank()) out += "没有承诺键（落点::改法）：下一轮没法对上账"
        if (p.target.isBlank()) out += "没有落点：说不上要动哪个文件/哪条技能"
        if (p.action.isBlank()) out += "没有动作：只有状态，没有下一步做什么"
        else if (isVague(p.action)) out += "只有感想（「${p.action.take(16)}」）：换成一件今天就能做的事再记"
        if (p.verifyBy.isBlank()) out += "没有验法：这条承诺以后判不了「有没有变好」"
        return out
    }

    /** 泛泛感想判据（纯词表；命中≠一定没价值，只是**不能当承诺入账**）。 */
    fun isVague(action: String): Boolean {
        val t = action.trim()
        if (t.length < 4) return true
        val startsVague = VAGUE_WORDS.any { t.startsWith(it) }
        // 有具体对象的样子：带路径/带引号里的技能名/带文件名——感想的形状是"只有形容词"
        val hasObject = t.contains('：') || t.contains('/') || t.contains('「') || t.contains('.')
        return startsVague && !hasObject
    }

    // ── 账（按键聚合成"欠什么"）────────────────────────────────────────────

    /** 一个键的账（三问 + B 档应答态）。 */
    internal data class Book(
        val key: String,
        val promised: Boolean,
        /** 最新一条「改了没」（null = 只承诺了，还没结果）。 */
        val result: CommitmentResult?,
        /** 最新一条「有没有变好」。 */
        val outcome: CommitmentOutcome?,
        /** 最新一条应答态。 */
        val ask: CommitmentAsk?,
        /** 这一键最早的一行日期（欠账的"挂了多久"按它算）。 */
        val since: LocalDate,
        /** 「放弃」有没有写理由（取最新一条 RESULT 的依据）。 */
        val reason: String,
        /** 最近一次摆卡/应答的日期（冷档窗从它算，不从承诺日算）。 */
        val askedAt: LocalDate?,
    )

    /** 全文 ⇒ 每个键的最新账（**行序即时间序**，同段取最后一条；乱序写入不猜）。 */
    fun books(lines: List<CommitmentLine>): Map<String, Book> {
        val out = LinkedHashMap<String, Book>()
        for (l in lines) {
            val cur = out[l.key]
            val base = cur ?: Book(
                key = l.key,
                promised = false,
                result = null,
                outcome = null,
                ask = null,
                since = l.date,
                reason = "",
                askedAt = null,
            )
            val aged = if (cur == null) base else base.copy(since = minOf(base.since, l.date))
            out[l.key] = when (l.stage) {
                CommitmentStage.PROMISE -> aged.copy(promised = true, reason = l.evidence.ifBlank { aged.reason })
                CommitmentStage.RESULT -> aged.copy(
                    result = CommitmentResult.entries.firstOrNull { it.wire == l.verdict },
                    reason = l.evidence,
                )

                CommitmentStage.OUTCOME -> aged.copy(
                    outcome = CommitmentOutcome.entries.firstOrNull { it.wire == l.verdict },
                )

                CommitmentStage.ASKED -> aged.copy(
                    ask = CommitmentAsk.entries.firstOrNull { it.wire == l.verdict } ?: aged.ask,
                    askedAt = l.date,
                )
            }
        }
        return out
    }

    /** 闭合＝结果齐＋不是"没改成/卡住"＋有验法结论（放弃算闭合，但必须带理由）。 */
    fun closed(b: Book): Boolean {
        val r = b.result ?: return false
        return when (r) {
            CommitmentResult.NOT_DONE, CommitmentResult.BLOCKED -> false
            CommitmentResult.ABANDONED -> b.reason.isNotBlank()
            CommitmentResult.DONE -> b.outcome != null && b.outcome != CommitmentOutcome.UNCHECKED
        }
    }

    /** 没闭合的键（有承诺却还欠着）。这就是"欠账"——它会消失，才是本层要防的事。 */
    fun openBooks(lines: List<CommitmentLine>): List<Book> =
        books(lines).values.filter { it.promised && !closed(it) }.sortedBy { it.since }

    /** 下一轮反思**必须先读**的账（负例⑥钉这个：第二次反思不读第一次 ⇒ 红）。 */
    fun mustFollowUp(lines: List<CommitmentLine>): List<Book> = openBooks(lines)

    /** 挂了太久、该正面回答"接着改还是放弃"的键。 */
    fun overdue(lines: List<CommitmentLine>, today: LocalDate, days: Long = OVERDUE_DAYS): List<Book> =
        openBooks(lines).filter { ChronoUnit.DAYS.between(it.since, today) >= days }

    /** 写了"放弃"却没写为什么的键（空着等于没放弃，账照旧挂着）。 */
    fun abandonedWithoutReason(lines: List<CommitmentLine>): List<String> =
        books(lines).values.filter { it.result == CommitmentResult.ABANDONED && it.reason.isBlank() }
            .map { it.key }.sorted()

    /**
     * 这一轮的输出有没有接住上次的账（**返回违规，不返回建议**）。
     *
     * @param keptKeys 这一轮真的提到的键（新增候选的键 ∪ 追加了跟进行的键）
     * @param abandonedKeys 这一轮明确写了"放弃＋理由"的键
     * @return 每条没接住的账一句话（空 ⇒ 闭合做得对）
     */
    fun followUpFindings(prior: List<CommitmentLine>, keptKeys: Set<String>, abandonedKeys: Set<String>): List<String> {
        val out = mutableListOf<String>()
        for (b in mustFollowUp(prior)) {
            if (b.key in keptKeys || b.key in abandonedKeys) continue
            out += "上次承诺「${b.key}」（${b.since}）这一轮没接：要么接着改，要么写清为什么放弃"
        }
        return out
    }

    // ── 骚扰闸（负例⑦：没批 ≠ 不同意）──────────────────────────────────────

    /** 这一条还要不要再摆卡。 */
    fun shouldAskAgain(b: Book, today: LocalDate): Boolean = when (b.ask) {
        // 没人应答：不重摆。这是"静默等待"，不是"以后都不许"——人哪天点了就照办。
        CommitmentAsk.UNANSWERED -> false
        // 明确否决：进冷档（沿用 205 的 30 天窗），过期后再提。
        CommitmentAsk.DISAGREED ->
            ChronoUnit.DAYS.between(b.askedAt ?: b.since, today) >= DISAGREE_COOLDOWN_DAYS
        // 批了 / 还没问过：可以摆（批了走执行，不再算"求批"）
        CommitmentAsk.AGREED, null -> true
    }

    /** 同键的「没人应答」行是不是已经有了（有了就**不再追加**——追加一次＝重复骚扰一次）。 */
    fun shouldAppendAskRow(lines: List<CommitmentLine>, key: String): Boolean {
        val last = lines.lastOrNull { it.key == key && it.stage == CommitmentStage.ASKED } ?: return true
        return CommitmentAsk.entries.firstOrNull { it.wire == last.verdict } != CommitmentAsk.UNANSWERED
    }

    /**
     * 「人没批」到底算不算不同意——只有人**明确否决**才算（负例⑦唯一一句判据）。
     *
     * 为什么要有这个函数而不是靠注释：混态的 bug 长得像"我在尊重人"。做成可断言的纯函数，
     * 负例⑦才有地方钉。
     */
    fun treatUnansweredAsDisagreement(b: Book): Boolean = b.ask == CommitmentAsk.DISAGREED

    /** 冷档期内不该再提的键（否决后 30 天内）。 */
    fun cooledOut(lines: List<CommitmentLine>, today: LocalDate): List<String> =
        books(lines).values
            .filter { it.ask == CommitmentAsk.DISAGREED }
            .filter { ChronoUnit.DAYS.between(it.askedAt ?: it.since, today) < DISAGREE_COOLDOWN_DAYS }
            .map { it.key }
            .sorted()
}

// ── 落盘壳（薄 IO；判据全在上面，这里只负责"写一行"与"读回来"）────────────────

/**
 * `产物/进化档案-承诺.md` 的读写口（房型照 [ReflectionRunner]：只追加、不覆盖历史行）。
 *
 * 三条：
 * · **不收感想**：[promise] 先过 [ClosureRules.promiseFindings]，没过 ⇒ 一个字节不写（负例⑤）；
 * · **不重复骚扰**：[noteAsk] 遇到同键已有「没人应答」就返回 false（负例⑦）；
 * · **每次现读**：不缓存——上一行刚写进去，下一轮反思就要看得见（闭合的"先读上次承诺"靠这个）。
 */
internal class CommitmentStore(
    private val file: File,
    private val log: (String) -> Unit = { RunLog.log(it) },
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    fun text(): String? = runCatching { if (file.isFile) file.readText(Charsets.UTF_8) else null }.getOrNull()

    fun read(): List<CommitmentLine> = CommitmentArchive.parse(text())

    fun today(): LocalDate = ReflectionDedup.today(clock())

    /** 追加一行（文件不存在先写表头）。失败只记日志，不抛——档案写不进不该弄丢这一轮的判断。 */
    fun append(line: CommitmentLine): Boolean {
        val written = runCatching {
            val parent = file.parentFile
            if (parent != null && !parent.isDirectory) parent.mkdirs()
            val header = if (file.isFile) "" else CommitmentArchive.header() + "\n"
            file.appendText(header + CommitmentArchive.renderLine(line) + "\n", Charsets.UTF_8)
            true
        }.getOrElse {
            log("承诺档案追加失败（${it.javaClass.simpleName}：${it.message}）")
            false
        }
        val bad = CommitmentArchive.unreadableCount(text())
        if (written && bad > 0) log("承诺档案里有 $bad 行读不成（手改过表格？新行照旧追加）")
        return written
    }

    /**
     * 记一条承诺。返回**拒收原因**（空 ⇒ 写成了）。
     *
     * 为什么返回原因而不是布尔：拒收必须能原话回灌给模型（"这条没入账，因为缺验法"），
     * 否则它会以为已经记上了，下一轮照样不跟进——那正是本层要治的病。
     */
    fun promise(shape: PromiseShape, tier: String): List<String> {
        val findings = ClosureRules.promiseFindings(shape)
        if (findings.isNotEmpty()) return findings
        val ok = append(
            CommitmentLine(
                date = today(),
                key = shape.key,
                stage = CommitmentStage.PROMISE,
                verdict = shape.action,
                evidence = shape.verifyBy,
                note = "落点：${shape.target}",
                tier = tier,
            ),
        )
        return if (ok) emptyList() else listOf("承诺没写进档案（落盘失败）")
    }

    /** 记「改了没」（放弃必须带理由，这里再挡一次——空依据的放弃行不落）。 */
    fun noteResult(key: String, result: CommitmentResult, evidence: String, tier: String): List<String> {
        if (result == CommitmentResult.ABANDONED && evidence.isBlank()) {
            return listOf("放弃要写为什么（派工单 §3）：没写就还不算放弃，这条账继续挂着")
        }
        return if (append(CommitmentLine(today(), key, CommitmentStage.RESULT, result.wire, evidence, tier = tier))) {
            emptyList()
        } else {
            listOf("改了没没写进档案（落盘失败）")
        }
    }

    /** 记「有没有变好」（206 结论或人抽查；`UNCHECKED` 也照记——不记就会假装闭合）。 */
    fun noteOutcome(key: String, outcome: CommitmentOutcome, evidence: String): Boolean =
        append(CommitmentLine(today(), key, CommitmentStage.OUTCOME, outcome.wire, evidence))

    /**
     * 记「问过人了」（负例⑦的唯一落盘口）。
     *
     * 同键已经有「没人应答」⇒ **不再追加**、返回 false：静默等待，页面上就那一行。
     * 闸只管 `UNANSWERED` 这一态：人真点了按钮（批／否决）是**对人的应答的记录**，
     * 不是又摆一次卡——把闸套到应答上，等于把人的回答也拦掉了。
     */
    fun noteAsk(key: String, ask: CommitmentAsk): Boolean {
        if (ask == CommitmentAsk.UNANSWERED && !ClosureRules.shouldAppendAskRow(read(), key)) return false
        return append(CommitmentLine(today(), key, CommitmentStage.ASKED, ask.wire))
    }

    /** 欠账（下一轮反思**必须先读**的就是它；[ClosureRules.followUpFindings] 的数据源）。 */
    fun openBooks(): List<ClosureRules.Book> = ClosureRules.mustFollowUp(read())

    /** 这一轮有没有接住上次的账（负例⑥的判据；返回违规一句话）。 */
    fun followUpFindings(keptKeys: Set<String>, abandonedKeys: Set<String>): List<String> =
        ClosureRules.followUpFindings(read(), keptKeys, abandonedKeys)

    companion object {
        /** 落点：`<工作区>/产物/进化档案-承诺.md`（与 205 的档案同屋不同件）。 */
        fun fileOf(wsRoot: File): File = File(MemoryLayout.productsDir(wsRoot), CommitmentArchive.FILE_NAME)

        fun of(wsRoot: File, log: (String) -> Unit = { RunLog.log(it) }): CommitmentStore =
            CommitmentStore(fileOf(wsRoot), log)
    }
}

// ── 一句话文案（卡面/日志/208 共用一份，不各拼一套）────────────────────────────

internal object ClosureTell {

    /** 欠账的一行（设置页与反思卡的"上次说到这次"栏都用它）。 */
    fun debtLine(b: ClosureRules.Book): String {
        val miss = when {
            !b.promised -> "没有承诺行"
            b.result == null -> "只承诺了，还没写改了没"
            b.result == CommitmentResult.ABANDONED && b.reason.isBlank() -> "写了放弃，没写为什么"
            b.result == CommitmentResult.NOT_DONE -> "上轮说没改成，这轮还没接"
            b.result == CommitmentResult.BLOCKED -> "上轮卡住，条件有没有齐还不知道"
            b.outcome == null -> "改了，没写有没有变好"
            else -> "改了，但没验（自治≠免检）"
        }
        return "${b.key}（${b.since}）欠：$miss"
    }

    /** 三问齐没齐（档案闭合样例直接断言这个串）。 */
    fun closureState(b: ClosureRules.Book): String {
        val r = b.result
        val o = b.outcome
        return when {
            !b.promised -> "没有承诺"
            r == null -> "只承诺，没结果"
            r == CommitmentResult.ABANDONED -> if (b.reason.isBlank()) "放弃了但没写为什么" else "放弃（已闭合）"
            r == CommitmentResult.NOT_DONE || r == CommitmentResult.BLOCKED -> "还没做成，继续欠着"
            o == null || o == CommitmentOutcome.UNCHECKED -> "改了，但没验（自治≠免检）"
            else -> "闭合：${r.wire}＋${o.wire}"
        }
    }
}
