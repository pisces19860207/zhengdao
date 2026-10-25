// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第二百零五批 · 反思器 v1 的**纯判据层**（房型照 `SkillRules`/`SkillIndexReconcile`：
// 算得清的都在这，IO 壳在 [ReflectionRunner]，本文件不建线程、不碰 Android；
// 磁盘只做**只读探测**——证据「可点开」的判定要真的去摸一下文件在不在）。
//
// 一句话：四源（自动日记／技能变更账／产物台账／失败轨迹）⇒ ≤3 张带证据的候选卡。
// 三条铁律（162 已验证、205 固化成装置）：
//   1. **空源必须降级并明示**——日记空 ⇒ 标 `DIARY_EMPTY` 走兜底三源；四源全空 ⇒ 只记一行日志、不弹卡；
//   2. **无证据不出卡**——每张卡 ≥2 条**可点开**证据（路径＋行号/日期，且生成时文件真在盘上）；
//   3. **确认前零写盘**——本层只读；进化档案的 append 是「出卡」的一部分（决定=待定），
//      技能目录/索引/人设/日记一个字节不动（写盘归 184/192 的确认通道）。
//
// 去重（派工单 §3）：同技能同改法 7 天内不重复提；进化档案里已「否决」的 30 天内不再提。
// 键写死：`target::changeKind`（162 §2 原文「同技能＋同改法」）。
package com.example.zhengdao.baopu

import java.io.File
import java.time.LocalDate
import java.time.ZoneId

// ── 四源可得性（报告 §1 的那张小表；162 那轮的诚实写法固化在这）──────────────────

/** 四源的名字（顺序＝派工单 §1 的表格序：主源在前，兜底三源在后）。 */
internal enum class ReflectionSourceName { DIARY, SKILL_LEDGER, PRODUCT_LEDGER, TRACE }

/** 一源的状态：有货／降级（主源空、已走兜底）／空。 */
internal enum class ReflectionSourceLevel { OK, DEGRADED, EMPTY }

/** 日记空的固定标记（判据原话：日记空时走兜底并**写明 DIARY_EMPTY**）。 */
internal const val DIARY_EMPTY_MARK = "DIARY_EMPTY"

internal data class ReflectionSourceRow(
    val name: ReflectionSourceName,
    val level: ReflectionSourceLevel,
    val note: String,
)

// ── 候选卡（162 §1 的六个字段一个不少）─────────────────────────────────────────

/** 三类形状（162 人工轮验证过、205 沿用的分类）。 */
internal enum class ReflectionShape { REPEAT_LABOR, ACCOUNT_MISMATCH, REPEAT_FAILURE }

/** 证据的来源种类（只为展示与归档压缩，判「可点开」不看它、看盘上有没有）。 */
internal enum class ReflectionEvidenceKind { DIARY, SKILL_LEDGER, PRODUCT_LEDGER, PRODUCT_FILE, INDEX, SKILL_BODY, TRACE }

/** 一条可点开的证据：`display` 给人看，`path`+`anchor`（行号/日期/runId#seq）给「点开原文」。 */
internal data class ReflectionEvidence(
    val display: String,
    val path: String,
    val anchor: String,
    val kind: ReflectionEvidenceKind,
    val openable: Boolean,
)

/** 档案行里的「类型」（162 §1.5 的四个值，一字不改——档案管长期，跨批可读）。 */
internal enum class ReflectionChangeKind(val label: String) {
    NEW_SKILL("新技能"),
    EDIT_SKILL("改技能"),
    EDIT_FLOW("改流程"),
    RETIRE("退役");

    companion object {
        /** 档案行解析用：认不出的脏值 ⇒ null（不冒充任何一种）。 */
        fun of(raw: String): ReflectionChangeKind? = entries.firstOrNull { it.label == raw || it.name == raw }
    }
}

/**
 * 一张候选卡。字段＝派工单 §2 固定六件：类型／一句话主张／≥2 证据／收益／风险／落点。
 *
 * @param target 去重键的「技能」半边（技能名或落点名）；**不许装正文**。
 * @param changeKind 去重键的「改法」半边——`"$target::${changeKind.label}"` 即 7 天去重键。
 * @param suggestedName/suggestedBody 能落成技能时的建议名与正文骨架（展示复用 184 卡用；
 *   落不了技能的形状给 null，UI 不出「记住它」）
 */
internal data class ReflectionCandidate(
    val shape: ReflectionShape,
    val target: String,
    val changeKind: ReflectionChangeKind,
    val claim: String,
    val evidences: List<ReflectionEvidence>,
    val benefit: String,
    val risk: String,
    val landing: String,
    val suggestedName: String? = null,
    val suggestedBody: String? = null,
    val createdAt: Long,
) {
    /** 去重键写死（派工单 §3：同技能同改法 7 天内不重复提）。 */
    val dedupKey: String get() = "$target::${changeKind.label}"

    /** 出卡闸：证据 ≥2 且条条可点开（判据 §4 负例 1/2 的执行点）。 */
    val presentable: Boolean get() = evidences.size >= 2 && evidences.all { it.openable }
}

// ── 四源装配好之后的输入（IO 由 [ReflectionRunner] 做，这层只吃数据）────────────

/** 技能变更账的一行（`技能/_变更.jsonl`，带行号——证据要能点开到那一行）。 */
internal data class SkillAuditLine(
    val file: File,
    val lineNo: Int,
    val ts: Long,
    val scope: String,
    val name: String,
    val action: String,
    val reason: String,
    val indexUpdated: Boolean,
)

/** 失败轨迹的一行（run_trace 优先；无表时 run_log 兜底，`kind` 退化为错误摘要）。 */
internal data class ReflectionFailureRow(
    val kind: String,
    val atMs: Long,
    val runId: String,
    val seq: Int,
    val fromTrace: Boolean,
)

/** 索引行引用（哪份索引文件的第几行——「账实不符」的证据要能点开到那行）。 */
internal data class IndexLineRef(
    val file: File,
    val lineNo: Int,
    val line: SkillRules.IndexLine,
)

/** 一条技能在盘上的读数（两层目录扫出来；`bodyFile` null＝目录里没有 SKILL.md 本名）。 */
internal data class SkillDirRef(
    val dir: SkillIndexReconcile.SkillDir,
    val scopeTag: String,
    val bodyFile: File?,
    val bodyStatus: String?,
    val bodyUses: Int,
    val hasHeader: Boolean,
    /** 该技能在两层索引里的行（失联＝空表）。 */
    val indexRefs: List<IndexLineRef>,
    /** 变更账里最后一次「写/改」那一行（悬空技能「谁写的」证据）。 */
    val lastWriteLine: SkillAuditLine?,
)

internal data class ReflectionInputs(
    val nowMs: Long,
    val wsRoot: File,
    val globalRoot: File,
    val diary: List<DiaryEntry>,
    val diaryFiles: Map<String, File>,
    val diaryRootExists: Boolean,
    val skillLedger: List<SkillAuditLine>,
    val productLedgerFile: File?,
    val productLedgerText: String?,
    val productFiles: List<File>,
    val failures: List<ReflectionFailureRow>,
    val skillDirs: List<SkillDirRef>,
)

/** 一次扫描的结果：可得性表＋候选（已去重、≤3）＋「本轮无候选」的一行日志文案。 */
internal data class ReflectionScan(
    val availability: List<ReflectionSourceRow>,
    val diaryDegraded: Boolean,
    val allEmpty: Boolean,
    val candidates: List<ReflectionCandidate>,
    val logLine: String,
) {
    /** 四源可得性小表（报告与日志共用一份拼法）。 */
    fun availabilityText(): String =
        availability.joinToString("；") { "${it.name.name}=${it.level.name}" + if (it.note.isNotBlank()) "（${it.note}）" else "" }
}

// ── 证据可点开（负例 2 的闸门在这：文件不在 ⇒ 证据不可点开 ⇒ 卡出不来）──────────

/**
 * 解析证据路径并探一下盘：工作区相对路径（`记忆/…`、`技能/…`、`产物/…`）优先按工作区根
 * 解析，`技能/…` 再按全局根试一次（两层同名各算各的）。文件不在 ⇒ openable=false——
 * 「可点开」是**生成时点上的事实**，不是承诺。
 */
internal fun evidenceOpenable(path: String, anchor: String, wsRoot: File, globalRoot: File): Boolean {
    if (path.isBlank() || anchor.isBlank()) return false
    if (File(path).isAbsolute) return File(path).isFile
    if (File(wsRoot, path).isFile) return true
    return File(globalRoot, path).isFile
}

internal fun ReflectionEvidence.recheck(wsRoot: File, globalRoot: File): ReflectionEvidence =
    // 轨迹证据不是盘上文件（path 形如 run_trace:<runId>），可点开＝那条 run 真在输入里
    // （构造时已判）——不能再拿 isFile 去探它。
    if (kind == ReflectionEvidenceKind.TRACE) this
    else copy(openable = evidenceOpenable(path, anchor, wsRoot, globalRoot))

// ── 进化档案（162 §1.5 的行格式；append-only，状态变化追加新行）────────────────

internal data class ReflectionArchiveLine(
    val date: LocalDate,
    val target: String,
    val changeKind: ReflectionChangeKind,
    val claim: String,
    val evidences: String,
    val decision: String,
) {
    val key: String get() = "$target::${changeKind.label}"
}

internal object ReflectionArchive {

    /** 档案文件名（放 `<工作区>/产物/`，与台账同屋）。 */
    const val FILE_NAME = "进化档案.md"

    /** 决定的四个值（档案行最后一列；「待定」是出卡时写的，其余是用户点按钮后追加的）。 */
    const val DECISION_PENDING = "待定"
    const val DECISION_ADOPTED = "采纳"
    const val DECISION_REJECTED = "否决"
    const val DECISION_SHELVED = "搁置"

    /** 新建档案时的表头（只有第一次 append 才写；历史行此后一个字节不改）。 */
    fun header(): String = listOf(
        "# 进化档案（append-only：只在状态变化时追加新行，历史行不改）",
        "| 日期 | 类型 | 技能/落点 | 一句话主张 | 证据 | 决定 |",
        "|---|---|---|---|---|---|",
    ).joinToString("\n")

    /** 一张候选 ⇒ 一行（决定默认「待定」；同候选改状态时**追加**新行，不改旧行）。 */
    fun renderLine(c: ReflectionCandidate, date: LocalDate, decision: String = DECISION_PENDING): String {
        val ev = c.evidences.joinToString("；") { "${it.path}:${it.anchor}" }
        return "| ${date} | ${c.changeKind.label} | ${c.target} | ${c.claim} | $ev | $decision |"
    }

    /** 档案全文 ⇒ 行（坏行跳过；空文本 ⇒ 空表）。 */
    fun parse(text: String?): List<ReflectionArchiveLine> {
        if (text.isNullOrBlank()) return emptyList()
        val out = mutableListOf<ReflectionArchiveLine>()
        text.lines().forEach { raw ->
            val line = raw.trim()
            if (!line.startsWith("|")) return@forEach
            val fields = line.split("|").map { it.trim() }
            // | 日期 | 类型 | 技能 | 主张 | 证据 | 决定 | ⇒ split 首尾是空串，实字段从 1 起
            if (fields.size < 7) return@forEach
            val date = runCatching { LocalDate.parse(fields[1]) }.getOrNull() ?: return@forEach
            val kind = ReflectionChangeKind.of(fields[2]) ?: return@forEach
            val target = fields[3].takeIf { it.isNotEmpty() } ?: return@forEach
            out += ReflectionArchiveLine(
                date = date,
                target = target,
                changeKind = kind,
                claim = fields[4],
                evidences = fields[5],
                decision = fields[6].ifBlank { DECISION_PENDING },
            )
        }
        return out
    }
}

// ── 去重（派工单 §3：7 天同键不重提；否决 30 天不再提）─────────────────────────

internal object ReflectionDedup {

    const val REPEAT_WINDOW_DAYS = 7L
    const val REJECT_WINDOW_DAYS = 30L

    /** 今天（档案行只有日期粒度，去重窗也按日算，不装精确到秒的假象）。 */
    fun today(nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate =
        LocalDate.ofInstant(java.time.Instant.ofEpochMilli(nowMs), zone)

    /**
     * 同键 7 天内提过（任何决定）⇒ 压；30 天内**否决**过 ⇒ 压。
     * 「提过」以档案行为准——出卡时写「待定」行，所以 7 天窗天然含本轮之前每一次出卡。
     */
    fun suppressed(c: ReflectionCandidate, archive: List<ReflectionArchiveLine>, today: LocalDate): Boolean {
        val mine = archive.filter { it.key == c.dedupKey }
        if (mine.isEmpty()) return false
        if (mine.any { daysBetween(it.date, today) <= REPEAT_WINDOW_DAYS }) return true
        if (mine.any { it.decision == ReflectionArchive.DECISION_REJECTED && daysBetween(it.date, today) <= REJECT_WINDOW_DAYS }) return true
        return false
    }

    /** 按 LocalDate 算天数差（非负；解析不了的行在 parse 里已经被丢掉）。 */
    private fun daysBetween(a: LocalDate, b: LocalDate): Long = java.time.temporal.ChronoUnit.DAYS.between(a, b).let { if (it < 0) -it else it }

    /** 过完闸再取前 [ReflectionRunner.MAX_CARDS] 张（宁少不滥：不足 3 就少出）。 */
    fun <T> pickTop(candidates: List<T>, limit: Int): List<T> = candidates.take(limit)
}

// ── 三个形状的提炼（每个形状自带证据构造；证据不可点开 ⇒ 该候选不出）────────────

internal object ReflectionExtract {

    /** 日记「同一诉求」的归一：去空白标点取前 16 字（再长就不是同一句诉求了）。 */
    private const val TITLE_NORM_CHARS = 16

    /** 变更账「反复写」：同名同理由 ≥2 次 ⇒ 重复劳动；或同名累计 ≥3 次写入。 */
    private const val LEDGER_SAME_REASON_MIN = 2
    private const val LEDGER_SAME_NAME_MIN = 3

    /** 产物「同题材」：同一赛道 ≥3 篇且跨 ≥2 天 ⇒ 该流程值得记成技能。 */
    private const val PRODUCT_TRACK_MIN = 3
    private const val PRODUCT_TRACK_DAYS_MIN = 2

    /** 失败「反复踩」：同类失败 ≥3 次且跨 ≥2 天（都在回看窗内）。 */
    const val FAILURE_KIND_MIN = 3
    const val FAILURE_DAYS_MIN = 2

    /** 失败回看窗（14 天——再远的坑不追，反思器不是考古队）。 */
    const val FAILURE_WINDOW_MS = 14L * 24 * 60 * 60 * 1000

    private fun normTitle(t: String): String = t.filter { it.isLetterOrDigit() }.take(TITLE_NORM_CHARS)

    private fun ev(
        display: String,
        path: String,
        anchor: String,
        kind: ReflectionEvidenceKind,
        inputs: ReflectionInputs,
    ): ReflectionEvidence =
        ReflectionEvidence(display, path, anchor, kind, evidenceOpenable(path, anchor, inputs.wsRoot, inputs.globalRoot))

    private fun relUnder(wsRoot: File, f: File): String =
        f.relativeToOrNull(wsRoot)?.path?.replace('\\', '/') ?: f.path.replace('\\', '/')

    // ── 形状①：重复劳动（反复手写／反复写技能）──────────────────────────────

    /**
     * 变更账同内容多次：同名同理由 ≥2 次，或同名累计写入 ≥3 次
     * ⇒「改了 N 次还没固化」的候选（改法=改技能；该技能已存在）。
     */
    fun repeatWriteCandidates(inputs: ReflectionInputs): List<ReflectionCandidate> {
        val writes = inputs.skillLedger.filter { it.action == "write" || it.action == "update" }
        if (writes.isEmpty()) return emptyList()
        val out = mutableListOf<ReflectionCandidate>()
        val byName = writes.groupBy { it.name }
        for ((name, lines) in byName) {
            val sameReason = lines.groupBy { it.reason }.filter { it.value.size >= LEDGER_SAME_REASON_MIN }
            val enough = sameReason.isNotEmpty() || lines.size >= LEDGER_SAME_NAME_MIN
            if (!enough) continue
            val first = lines.minBy { it.ts }
            val last = lines.maxBy { it.ts }
            val evidences = listOf(
                ev("${relUnder(inputs.wsRoot, first.file)} 第 ${first.lineNo} 行（${first.action}）", relUnder(inputs.wsRoot, first.file), "L${first.lineNo}", ReflectionEvidenceKind.SKILL_LEDGER, inputs),
                ev("${relUnder(inputs.wsRoot, last.file)} 第 ${last.lineNo} 行（${last.action}）", relUnder(inputs.wsRoot, last.file), "L${last.lineNo}", ReflectionEvidenceKind.SKILL_LEDGER, inputs),
            )
            out += ReflectionCandidate(
                shape = ReflectionShape.REPEAT_LABOR,
                target = name,
                changeKind = ReflectionChangeKind.EDIT_SKILL,
                claim = "「$name」${lines.size} 次改写仍是手改——把这次的做法固化进正文",
                evidences = evidences,
                benefit = "同一诉求少手改 ${lines.size - 1} 次；模型直接照技能做",
                risk = "只改这条技能的正文；不放宽任何确认",
                landing = "项目技能「$name」正文",
                suggestedName = name,
                suggestedBody = null,
                createdAt = inputs.nowMs,
            )
        }
        return out
    }

    /**
     * 产物同题材多次：文件名 `YYYYMMDD_<赛道>_<序号>…` 同赛道 ≥3 篇、跨 ≥2 天
     * ⇒「这个流程手写了 N 次」的候选（改法=新技能）。
     * 证据点开到台账行（台账没有的落到产物文件本身）。
     */
    fun repeatProductCandidates(inputs: ReflectionInputs): List<ReflectionCandidate> {
        data class Parsed(val file: File, val date: String, val track: String)

        val parsed = inputs.productFiles.mapNotNull { f ->
            val m = Regex("""^(\d{8})_(.+?)_\d+""").find(f.name) ?: return@mapNotNull null
            Parsed(f, m.groupValues[1], m.groupValues[2])
        }
        if (parsed.isEmpty()) return emptyList()
        val out = mutableListOf<ReflectionCandidate>()
        for ((track, group) in parsed.groupBy { it.track }) {
            if (group.size < PRODUCT_TRACK_MIN) continue
            val days = group.map { it.date }.distinct().sorted()
            if (days.size < PRODUCT_TRACK_DAYS_MIN) continue
            val twoNewest = group.sortedBy { it.date }.takeLast(2)
            val ledgerLine = { f: File -> ledgerLineOf(inputs.productLedgerText, inputs.productLedgerFile, f.name) }
            val evidences = twoNewest.map { p ->
                val ref = ledgerLine(p.file)
                if (ref != null) {
                    ev(
                        "${relUnder(inputs.wsRoot, ref.first)} 第 ${ref.second} 行（${p.file.name}）",
                        relUnder(inputs.wsRoot, ref.first),
                        "L${ref.second}",
                        ReflectionEvidenceKind.PRODUCT_LEDGER,
                        inputs,
                    )
                } else {
                    ev(p.file.name, relUnder(inputs.wsRoot, p.file), "全文", ReflectionEvidenceKind.PRODUCT_FILE, inputs)
                }
            }
            out += ReflectionCandidate(
                shape = ReflectionShape.REPEAT_LABOR,
                target = track,
                changeKind = ReflectionChangeKind.NEW_SKILL,
                claim = "「$track」这类产物 ${group.size} 篇、跨 ${days.size} 天——值得记成一条技能",
                evidences = evidences,
                benefit = "下一次同题材少走弯路；落盘与记账一步到位",
                risk = "新技能为草稿（试用期），用满 ${SkillStore.PROMOTE_USES} 次才转正",
                landing = "项目技能（草稿）",
                suggestedName = track,
                suggestedBody = "# $track 流程\n<!-- 由反思器起稿：把 ${days.first()}–${days.last()} 这几篇的公共做法补全 -->\n",
                createdAt = inputs.nowMs,
            )
        }
        return out
    }

    /** 台账文本里点到该文件名的那一行（文件名在台账里成对出现：`☐/✔ <名>（N 字）`）。 */
    private fun ledgerLineOf(text: String?, ledger: File?, fileName: String): Pair<File, Int>? {
        if (text == null || ledger == null) return null
        text.lines().forEachIndexed { i, line ->
            if (line.contains(fileName)) return ledger to (i + 1)
        }
        return null
    }

    /**
     * 日记同一诉求 ≥2 天：标题归一后相同、日期不同
     * ⇒「这件事隔天又来一遍」的候选。日记是主源，条目天然带日期（anchor=日期）。
     */
    fun repeatDiaryCandidates(inputs: ReflectionInputs): List<ReflectionCandidate> {
        if (inputs.diary.isEmpty()) return emptyList()
        val out = mutableListOf<ReflectionCandidate>()
        for ((norm, group) in inputs.diary.groupBy { normTitle(it.title) }) {
            if (norm.isBlank()) continue
            val days = group.map { it.date }.distinct()
            if (days.size < 2) continue
            val twoNewest = group.sortedBy { it.date }.takeLast(2)
            val evidences = twoNewest.map { e ->
                val path = inputs.diaryFiles[e.date]?.let { relUnder(inputs.wsRoot, it) } ?: "记忆/日记/${e.date}.md"
                ev("日记 ${e.date}「${e.title}」", path, e.date, ReflectionEvidenceKind.DIARY, inputs)
            }
            out += ReflectionCandidate(
                shape = ReflectionShape.REPEAT_LABOR,
                target = twoNewest.first().title.take(16),
                changeKind = ReflectionChangeKind.NEW_SKILL,
                claim = "「${twoNewest.first().title.take(16)}」${days.size} 天里各来了一遍——记成技能省第二遍",
                evidences = evidences,
                benefit = "第二遍起照技能直做，少一次摸索",
                risk = "新技能为草稿（试用期）；日记本身不动",
                landing = "项目技能（草稿）",
                suggestedName = twoNewest.first().title.take(SkillStore.NAME_MAX_CHARS),
                suggestedBody = "# ${twoNewest.first().title.take(24)}\n<!-- 由反思器起稿：按 ${days.joinToString("/")} 两天的日记补全做法 -->\n",
                createdAt = inputs.nowMs,
            )
        }
        return out
    }

    // ── 形状②：账实不符（204 对账结果＋从未被用）────────────────────────────

    /**
     * 账实不符（证据＝204 的只读对账）：
     * - 索引悬空／正文失联／同场景双行 ⇒ 建议跑一次对账修正（改法=改流程）；
     * - 有状态头且 uses=0 的正式技能 ⇒ 「从未被用」（改法=退役或改场景句）。
     * 三类合成**一张**卡（162 的 cand-162-2 房型）；证据点开到索引行与盘上实件。
     */
    fun mismatchCandidates(inputs: ReflectionInputs): List<ReflectionCandidate> {
        val evidences = mutableListOf<ReflectionEvidence>()
        val problems = mutableListOf<String>()

        for (dir in inputs.skillDirs) {
            val refs = dir.indexRefs
            val dangling = refs.isNotEmpty() && dir.bodyFile == null
            val orphan = refs.isEmpty() && dir.bodyFile != null
            if (dangling) {
                problems += "悬空 ${dir.dir.name}"
                refs.first().let { r ->
                    evidences += ev(
                        "索引行 ${relUnder(inputs.wsRoot, r.file)}:${r.lineNo}（${dir.dir.name} 无正文）",
                        relUnder(inputs.wsRoot, r.file),
                        "L${r.lineNo}",
                        ReflectionEvidenceKind.INDEX,
                        inputs,
                    )
                }
                // 悬空的第二手证据：变更账里写它的那一行（谁写的、什么时候）
                dir.lastWriteLine?.let { w ->
                    evidences += ev(
                        "${relUnder(inputs.wsRoot, w.file)} 第 ${w.lineNo} 行（${w.action}）",
                        relUnder(inputs.wsRoot, w.file),
                        "L${w.lineNo}",
                        ReflectionEvidenceKind.SKILL_LEDGER,
                        inputs,
                    )
                }
            }
            if (orphan) {
                problems += "失联 ${dir.dir.name}"
                dir.bodyFile?.let { f ->
                    evidences += ev(
                        "有正文无索引 ${relUnder(inputs.wsRoot, f)}",
                        relUnder(inputs.wsRoot, f),
                        "L1",
                        ReflectionEvidenceKind.SKILL_BODY,
                        inputs,
                    )
                }
            }
            if (dir.hasHeader && dir.bodyStatus == "active" && dir.bodyUses == 0 && dir.bodyFile != null) {
                problems += "未用 ${dir.dir.name}"
                dir.bodyFile?.let { f ->
                    evidences += ev(
                        "${relUnder(inputs.wsRoot, f)}（status=active uses=0）",
                        relUnder(inputs.wsRoot, f),
                        "L1",
                        ReflectionEvidenceKind.SKILL_BODY,
                        inputs,
                    )
                }
                refs.firstOrNull()?.let { r ->
                    evidences += ev(
                        "索引行 ${relUnder(inputs.wsRoot, r.file)}:${r.lineNo}",
                        relUnder(inputs.wsRoot, r.file),
                        "L${r.lineNo}",
                        ReflectionEvidenceKind.INDEX,
                        inputs,
                    )
                }
            }
        }
        if (problems.isEmpty()) return emptyList()
        val deduped = evidences.distinctBy { it.path + it.anchor }
        if (deduped.size < 2) return emptyList() // 点不开两只手 ⇒ 这张卡不出（负例 2 的闸）
        return listOf(
            ReflectionCandidate(
                shape = ReflectionShape.ACCOUNT_MISMATCH,
                target = "技能索引",
                changeKind = ReflectionChangeKind.EDIT_FLOW,
                claim = "账实不符 ${problems.size} 处（${problems.take(3).joinToString("、")}）——按 204 对账结果修一轮",
                evidences = deduped.take(4),
                benefit = "模型看得见的索引与盘上正文一致；从未被用的技能显式处置",
                risk = "只报不改：修索引走既有确认通道；退役是软删可恢复",
                landing = "技能索引（悬空去行/失联补行）与未用技能的处置",
                createdAt = inputs.nowMs,
            ),
        )
    }

    // ── 形状③：反复踩坑（同类失败连续出现）─────────────────────────────────

    /**
     * 同类失败 ≥[FAILURE_KIND_MIN] 次、跨 ≥[FAILURE_DAYS_MIN] 天（回看窗 [FAILURE_WINDOW_MS]）
     * ⇒「把规避写进技能/工具规则」的候选。证据点开到两次失败的轨迹行（runId#seq）。
     */
    fun repeatFailureCandidates(inputs: ReflectionInputs): List<ReflectionCandidate> {
        val windowStart = inputs.nowMs - FAILURE_WINDOW_MS
        val recent = inputs.failures.filter { it.atMs in windowStart..inputs.nowMs }
        if (recent.isEmpty()) return emptyList()
        val out = mutableListOf<ReflectionCandidate>()
        for ((kind, group) in recent.groupBy { it.kind }) {
            if (kind.isBlank()) continue
            val days = group.map { LocalDate.ofInstant(java.time.Instant.ofEpochMilli(it.atMs), ZoneId.systemDefault()).toString() }.distinct()
            if (group.size < FAILURE_KIND_MIN || days.size < FAILURE_DAYS_MIN) continue
            val twoNewest = group.sortedBy { it.atMs }.takeLast(2)
            val evidences = twoNewest.map { r ->
                ReflectionEvidence(
                    "${if (r.fromTrace) "run_trace" else "run_log"} ${r.runId}#${r.seq}（$kind，${LocalDate.ofInstant(java.time.Instant.ofEpochMilli(r.atMs), ZoneId.systemDefault())}）",
                    if (r.fromTrace) "run_trace:${r.runId}" else "run_log:${r.runId}",
                    "#${r.seq}",
                    ReflectionEvidenceKind.TRACE,
                    // 轨迹证据的可点开＝这条 runId 真的在输入里（库里有这次运行，不是编的）
                    openable = inputs.failures.any { it.runId == r.runId },
                )
            }
            out += ReflectionCandidate(
                shape = ReflectionShape.REPEAT_FAILURE,
                target = kind,
                changeKind = ReflectionChangeKind.NEW_SKILL,
                claim = "同类失败「$kind」${group.size} 次、跨 ${days.size} 天——把规避写进技能",
                evidences = evidences,
                benefit = "同类失败少重试；下次直接绕开已知坑",
                risk = "只加规则不改工具；不放宽任何确认",
                landing = "项目技能（草稿）或工具规则",
                suggestedName = "规避${kind.take(10)}",
                suggestedBody = "# 规避 $kind\n<!-- 由反思器起稿：${days.joinToString("/")} 共 ${group.size} 次同类失败，把触发条件与绕法补全 -->\n",
                createdAt = inputs.nowMs,
            )
        }
        return out
    }
}

// ── 扫描主流程（可得性表＋降级＋三形状＋去重＋≤3）──────────────────────────────

internal object ReflectionScanRules {

    /** 四源可得性（顺序固定＝派工单 §1 表格序）。 */
    fun availability(inputs: ReflectionInputs): List<ReflectionSourceRow> {
        val diaryDays = inputs.diary.map { it.date }.distinct().size
        val diaryLevel = when {
            inputs.diary.isEmpty() -> ReflectionSourceLevel.DEGRADED
            else -> ReflectionSourceLevel.OK
        }
        val diaryNote = when {
            inputs.diary.isEmpty() && !inputs.diaryRootExists -> "$DIARY_EMPTY_MARK：日记目录不存在，走兜底三源"
            inputs.diary.isEmpty() -> "$DIARY_EMPTY_MARK：日记无条目，走兜底三源"
            else -> "${inputs.diary.size} 条／$diaryDays 天"
        }
        val ledgerWrites = inputs.skillLedger.size
        val productCount = inputs.productFiles.size + (if (inputs.productLedgerText.isNullOrBlank()) 0 else 1)
        val traceFrom = inputs.failures.firstOrNull()?.fromTrace
        return listOf(
            ReflectionSourceRow(ReflectionSourceName.DIARY, diaryLevel, diaryNote),
            ReflectionSourceRow(
                ReflectionSourceName.SKILL_LEDGER,
                if (ledgerWrites == 0) ReflectionSourceLevel.EMPTY else ReflectionSourceLevel.OK,
                if (ledgerWrites == 0) "无变更账（没写过技能或文件不存在）" else "$ledgerWrites 行",
            ),
            ReflectionSourceRow(
                ReflectionSourceName.PRODUCT_LEDGER,
                if (productCount == 0) ReflectionSourceLevel.EMPTY else ReflectionSourceLevel.OK,
                if (productCount == 0) "无台账无产物" else "台账${if (inputs.productLedgerText.isNullOrBlank()) "无" else "有"}＋产物 ${inputs.productFiles.size} 件",
            ),
            ReflectionSourceRow(
                ReflectionSourceName.TRACE,
                if (inputs.failures.isEmpty()) ReflectionSourceLevel.EMPTY else ReflectionSourceLevel.OK,
                when {
                    inputs.failures.isEmpty() -> "无失败轨迹（run_trace/run_log 都没有）"
                    traceFrom == true -> "run_trace ${inputs.failures.size} 条失败"
                    else -> "run_trace 无表，run_log 兜底 ${inputs.failures.size} 条"
                },
            ),
        )
    }

    /**
     * 「四源全空」的口径：四个点名源全空，**且**技能目录与索引没有任何账实信号
     * （形状②的原料——悬空/失联/未用也算「有东西可反思」，不许被「四源全空」提前短路）。
     */
    private fun allEmpty(inputs: ReflectionInputs): Boolean =
        inputs.diary.isEmpty() &&
            inputs.skillLedger.isEmpty() &&
            inputs.productFiles.isEmpty() &&
            inputs.productLedgerText.isNullOrBlank() &&
            inputs.failures.isEmpty() &&
            inputs.skillDirs.none { it.indexRefs.isNotEmpty() || it.bodyFile != null }

    /**
     * 扫描（**只读**）：
     * - 四源全空 ⇒ 候选空＋一行「本轮无候选」日志文案（调用方只记日志、不弹卡、不写档案）；
     * - 有源 ⇒ 三形状各出各的，过 7/30 天去重，取前 [limit] 张；
     * - 不可点开证据的候选在 [ReflectionCandidate.presentable] 闸上被丢——**不带证据的卡一张不出**。
     */
    fun scan(inputs: ReflectionInputs, archiveText: String?, limit: Int = 3): ReflectionScan {
        val availability = availability(inputs)
        val empty = allEmpty(inputs)
        if (empty) {
            return ReflectionScan(
                availability = availability,
                diaryDegraded = true,
                allEmpty = true,
                candidates = emptyList(),
                logLine = "反思器：本轮无候选——四源全空（${availability.joinToString("；") { "${it.name.name}=${it.level.name}" }}）",
            )
        }
        val archive = ReflectionArchive.parse(archiveText)
        val today = ReflectionDedup.today(inputs.nowMs)
        val all = buildList {
            addAll(ReflectionExtract.repeatDiaryCandidates(inputs))
            addAll(ReflectionExtract.repeatWriteCandidates(inputs))
            addAll(ReflectionExtract.repeatProductCandidates(inputs))
            addAll(ReflectionExtract.mismatchCandidates(inputs))
            addAll(ReflectionExtract.repeatFailureCandidates(inputs))
        }
        val presentable = all
            .map { c -> c.copy(evidences = c.evidences.map { it.recheck(inputs.wsRoot, inputs.globalRoot) }) }
            .filter { it.presentable }
            .filterNot { ReflectionDedup.suppressed(it, archive, today) }
            .sortedWith(compareByDescending<ReflectionCandidate> { it.evidences.size }.thenBy { it.dedupKey })
        val picked = ReflectionDedup.pickTop(presentable, limit)
        val logLine = if (picked.isEmpty()) {
            "反思器：本轮无候选（有源；压制 ${all.size - presentable.size} 条，${availability.joinToString("；") { "${it.name.name}=${it.level.name}" }}）"
        } else {
            "反思器：出 ${picked.size} 张候选卡（${picked.joinToString("、") { it.target }}）"
        }
        return ReflectionScan(
            availability = availability,
            diaryDegraded = availability[0].level == ReflectionSourceLevel.DEGRADED,
            allEmpty = false,
            candidates = picked,
            logLine = logLine,
        )
    }
}

// ── 触发（派工单 §3：①每天一次「进抱朴时若今天没跑过」；②用户说「复盘一下」）────

internal object ReflectionTrigger {

    /** 用户要复盘的原话标记（派工单 §3 ②＋追加件的「现在复盘一次」同族）。 */
    val REVIEW_MARKERS = listOf(
        "复盘一下", "复盘一次", "最近有什么该改的", "有什么该改的",
    )

    /** 「今天还没跑过」＝上次运行不在今天（本地时区按日算）。 */
    fun isReviewRequest(userText: String): Boolean =
        userText.isNotBlank() && REVIEW_MARKERS.any { userText.contains(it) }

    fun dueToday(lastRunMs: Long, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): Boolean {
        if (lastRunMs <= 0L) return true
        return LocalDate.ofInstant(java.time.Instant.ofEpochMilli(lastRunMs), zone) !=
            LocalDate.ofInstant(java.time.Instant.ofEpochMilli(nowMs), zone)
    }
}
