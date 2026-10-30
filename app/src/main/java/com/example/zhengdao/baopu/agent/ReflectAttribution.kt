// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百八十三批 · 反思给归因（派工单 §1 第 1 件）：**判错要能回答"为什么判错"，且带证据行**。
//
// 265 把反思条目落进了 `<工作区>/产物/进化档案.md`（出卡一行「待定」，用户点按钮后**追加**一行
// 决定——append-only）。本文件把那本档案当输入，只回答一件事：**机器提了、用户否了的那一条，
// 错在哪**。归因不许写成"我这次表现还行"式的自嗨——每条归因都必须指着档案里**能点开的行**：
//   · 提出那一行（哪天、以什么主张提的）；
//   · 否决那一行（哪天、用户说的"不要"）；
//   · 此前如有同键否决，也要带上——提卡之前账上就写着"不要"，这是最该脸红的一种判错。
//
// L17（271 门禁同一条法，同一个口径）：**无证据不落盘**——证据 ≥2 条且条条可点开
// （一条说不出「反复」；归因解释的是判错模式，不是流水账）。差证据的归因只记一行日志，
// 一个字节不进账。
//
// 落点＝判决经验账（[JudgmentExperienceStore]，**280 预留的持久化口**：280 合入前是内存桩）。
// 归因行进账后，学习器（[LearnWiring]）读的是同一本账——"学习器↔反思器"这一对就此接通。
//
// 三条纪律（照 205/277 的房型）：
//   1. **只读不写源**：进化档案一个字节不改（归因是它的下游读者，不是它的编辑）；
//   2. **append-only**：进账的归因行不改不撤（当时怎么归的因，30 天后还得是原样）；
//   3. **判据与 IO 分离**：[ReflectionMisjudgeJudge] 纯函数零 IO 零时钟，[ReflectionAttributor] 只管读盘装数。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.ReflectionArchive
import com.example.zhengdao.baopu.ReflectionArchiveLine
import com.example.zhengdao.baopu.ReflectionChangeKind
import com.example.zhengdao.baopu.ReflectionDedup
import com.example.zhengdao.baopu.ReflectionEvidence
import com.example.zhengdao.baopu.ReflectionEvidenceKind
import com.example.zhengdao.baopu.ReflectionRunner
import com.example.zhengdao.core.RunLog
import java.io.File

/** 判错的"为什么"（稳定机器键；文案会改，键不许改——照 `OpinionWhy` 的纪律）。 */
internal enum class MisjudgeWhy(val wire: String, val cn: String) {
    /** 否决 30 天窗内同键又被提出：去重窗该拦的没拦住。 */
    REPROPOSE_IN_REJECT_WINDOW("repropose_in_reject_window", "否决窗内重提"),

    /** 此前已否决过同键（窗外），仍然提出：账上写着"不要"没读。 */
    REJECT_PATTERN_IGNORED("reject_pattern_ignored", "有前科仍提"),

    /** 提出时同键无否决前科：证据模式成立但用户判定不值得——留档供学习器调"何时问"。 */
    FIRST_REJECT("first_reject", "首驳"),
    ;

    companion object {
        /** 认不出就 null（自造值不渲染，也不静默当别的类——照 208 的解析纪律）。 */
        fun ofWire(wire: String): MisjudgeWhy? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * 一条归因（判错的"为什么"）——进账前的纯数据。
 *
 * @param reason 一句人话（账上"为什么判错"那一列；空 ⇒ 不落账——L33 同一口径）
 * @param evidence 档案行引用（≥2 条才许落账，见 [AttributionGate]）
 */
internal data class ReflectionMisjudge(
    val id: String,
    val atMs: Long,
    val dedupKey: String,
    val target: String,
    val changeKind: ReflectionChangeKind,
    val why: MisjudgeWhy,
    val reason: String,
    val evidence: List<ReflectionEvidence>,
) {
    /** 反思卡的"类"（学习器按它分组调"何时问"；与 [LearnWiring.onUserSignal] 的 cardClass 同一拼法）。 */
    val cardClass: String get() = "反思:$dedupKey"

    /** 账上 evidence 列的拼法（路径#行锚——回放时照着点开）。 */
    val evidenceRefs: List<String> get() = evidence.map { "${it.path}#${it.anchor}" }
}

/** L17 门禁（271 同一条法）：证据 ≥2 且条条可点开，理由不许空白。 */
internal object AttributionGate {
    fun passes(evidence: List<ReflectionEvidence>, reason: String): Boolean =
        reason.isNotBlank() && evidence.size >= 2 && evidence.all { it.openable }
}

/** 归因判据（纯函数）：一段档案文本 ⇒ 每一处"提出→否决"配对的归因。 */
internal object ReflectionMisjudgeJudge {

    /** 一行档案＋它物理所在的行号（证据要能点开到那一行）。 */
    data class ArchiveRow(val lineNo: Int, val row: ReflectionArchiveLine)

    /** 判一条"提出→否决"配对。
     *
     * @param history 这条提出之前的全部档案行（同键查前科用）
     * @param archivePath 档案文件路径（证据引用的 path 半边；判据自己不摸盘）
     * @param isOpenable IO 壳盖的章：档案文件在不在、行号超没超界（判据自己不摸盘）
     */
    fun judge(
        proposal: ArchiveRow,
        reject: ArchiveRow,
        history: List<ArchiveRow>,
        archivePath: String,
        isOpenable: Boolean,
        nowMs: Long,
        id: String,
    ): ReflectionMisjudge {
        val priorRejects = history.filter {
            it.row.key == proposal.row.key && it.row.decision == ReflectionArchive.DECISION_REJECTED
        }
        val windowReject = priorRejects.lastOrNull {
            it.row.date > proposal.row.date.minusDays(ReflectionDedup.REJECT_WINDOW_DAYS)
        }
        val why = when {
            windowReject != null -> MisjudgeWhy.REPROPOSE_IN_REJECT_WINDOW
            priorRejects.isNotEmpty() -> MisjudgeWhy.REJECT_PATTERN_IGNORED
            else -> MisjudgeWhy.FIRST_REJECT
        }
        val evidence = buildList {
            add(rowEvidence(archivePath, proposal, "提出"))
            add(rowEvidence(archivePath, reject, "用户否决"))
            for (r in priorRejects) add(rowEvidence(archivePath, r, "此前否决"))
        }.map { it.copy(openable = isOpenable) }
        val reason = when (why) {
            MisjudgeWhy.REPROPOSE_IN_REJECT_WINDOW ->
                "同键在否决 ${ReflectionDedup.REJECT_WINDOW_DAYS} 天窗内又被提出" +
                    "（${windowReject!!.row.date} 否决 → ${proposal.row.date} 再提）：去重窗形同虚设"
            MisjudgeWhy.REJECT_PATTERN_IGNORED ->
                "同键此前已于 ${priorRejects.maxOf { it.row.date }} 被否决，仍再提：账上的「不要」没有读"
            MisjudgeWhy.FIRST_REJECT ->
                "提出时同键无否决前科：机器按证据模式提卡，用户判定不值得——归因留档供学习器调「何时问」"
        }
        return ReflectionMisjudge(
            id = id,
            atMs = nowMs,
            dedupKey = proposal.row.key,
            target = proposal.row.target,
            changeKind = proposal.row.changeKind,
            why = why,
            reason = reason,
            evidence = evidence,
        )
    }

    /** 档案行 ⇒ 一条证据引用（可点开与否由 IO 壳统一盖章，这里先置 false 占位）。 */
    private fun rowEvidence(archivePath: String, row: ArchiveRow, what: String): ReflectionEvidence =
        ReflectionEvidence(
            display = "档案第${row.lineNo}行：$what（${row.row.date}）",
            path = archivePath,
            anchor = "L${row.lineNo}",
            kind = ReflectionEvidenceKind.PRODUCT_LEDGER,
            openable = false,
        )
}

/** 归因结果：落了账的与因 L17 闸没过的（负例要能点名）。 */
internal data class AttributionResult(
    val persisted: List<ReflectionMisjudge>,
    val skipped: List<SkippedMisjudge>,
)

internal data class SkippedMisjudge(val dedupKey: String, val lineNo: Int, val reason: String)

/**
 * 归因的 IO 壳：读 265 落的进化档案（**只读**），产出归因行进判决经验账。
 *
 * 没装配工作区／档案不存在／读失败 ⇒ 空结果（归因是下游消费，源坏了不许把调用方带死）。
 */
internal class ReflectionAttributor(
    private val wsRoot: File,
    private val store: JudgmentExperienceStore,
    private val log: (String) -> Unit = { RunLog.log(it) },
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    private var seq = 0

    fun attribute(): AttributionResult {
        val file = ReflectionRunner.archiveFile(wsRoot)
        if (!file.isFile) return AttributionResult(emptyList(), emptyList())
        val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return AttributionResult(emptyList(), emptyList())
        val rows = rowsWithLineNo(text)
        val openable = rows.isNotEmpty()
        val persisted = mutableListOf<ReflectionMisjudge>()
        val skipped = mutableListOf<SkippedMisjudge>()
        rows.forEachIndexed { idx, reject ->
            if (reject.row.decision != ReflectionArchive.DECISION_REJECTED) return@forEachIndexed
            val proposal = rows.subList(0, idx)
                .lastOrNull { it.row.key == reject.row.key && it.row.decision != ReflectionArchive.DECISION_REJECTED }
                ?: return@forEachIndexed
            val now = clock()
            val misjudge = ReflectionMisjudgeJudge.judge(
                proposal = proposal,
                reject = reject,
                history = rows.subList(0, rows.indexOf(proposal)),
                archivePath = file.path,
                isOpenable = openable,
                nowMs = now,
                id = newId(now),
            )
            if (!AttributionGate.passes(misjudge.evidence, misjudge.reason)) {
                skipped += SkippedMisjudge(misjudge.dedupKey, reject.lineNo, "证据不足或点不开（L17：无证据不落盘）")
                log("抱朴归因：缺证据，不落盘（L17）${misjudge.dedupKey}｜档案 L${reject.lineNo}")
                return@forEachIndexed
            }
            store.append(
                ExperienceRow(
                    id = misjudge.id,
                    atMs = misjudge.atMs,
                    kind = ExperienceKind.ATTRIBUTION,
                    cardClass = misjudge.cardClass,
                    key = "attribution:${misjudge.why.wire}",
                    reason = misjudge.reason,
                    evidence = misjudge.evidenceRefs,
                ),
            )
            log("抱朴归因：${misjudge.why.cn}（${misjudge.why.wire}）${misjudge.dedupKey}｜${misjudge.reason}")
            persisted += misjudge
        }
        return AttributionResult(persisted, skipped)
    }

    /** 档案文本 ⇒ 带行号的档案行（坏行跳过：回放是读历史，不许一行脏数据整本读不出）。 */
    private fun rowsWithLineNo(text: String): List<ReflectionMisjudgeJudge.ArchiveRow> {
        val out = mutableListOf<ReflectionMisjudgeJudge.ArchiveRow>()
        text.lines().forEachIndexed { i, raw ->
            val line = raw.trim()
            if (!line.startsWith("|")) return@forEachIndexed
            val row = ReflectionArchive.parse(line).firstOrNull() ?: return@forEachIndexed
            out += ReflectionMisjudgeJudge.ArchiveRow(i + 1, row)
        }
        return out
    }

    private fun newId(now: Long): String {
        seq += 1
        return "att:$now:$seq"
    }
}
