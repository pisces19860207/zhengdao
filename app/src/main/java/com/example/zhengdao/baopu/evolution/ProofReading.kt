// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第三百八十批 · 进化闭环的**真验证臂**：四条验证读数不再由接线层硬编"全没跑"，
// 而是从读数收件箱（`进化读数.jsonl`）里读 CI 真跑出来的数——428 交付时点名的唯一开口
// （"接 CI 读数换的是 proofRunner 这一个参数，链不动"）。
//
// 分工红线（用户两条）：**不许重造第二套进化闸**——本文件只采集读数与新表，判过没过
// 仍归 376 的 [EvolutionLoop.verify] 与 310 的 [CriteriaSnapshot] 构造闸：四个子对象与
// 新表都直接在 runCatching 里调**它们自己的构造器**（`ran=true` 却 0 例、撞不可进化清单
// 的表，根本建不出来）——建不出来就是坏行，本文件自己零判法；**门拒绝不许静默**——
// 每一行被弃的读数按层落账（见下"弃读三层"）。
//
// 收件箱房型（与 进化提案/进化尝试 同一棵账根）：CI 在电脑上跑完四条，把读数写一行 JSON，
// 人手送进 app 私有目录（debug 构建经 run-as；release 没有推送口——体检单上有一格写明，
// 免得用户把"链停在准入等读数"看成了"进化总在失败"）。行 schema（v1，缺任一必填＝坏行）：
//   {"v":1,"proposal":"<提案id>","ref":"<CI run id>","at":<ms>,
//    "unit":{"ran":b,"total":n,"failed":n},"regression":{...同款},
//    "anchor":{"total":n,"mismatched":n},"detekt":{"before":n,"after":n},
//    "snapshot":{"epochMs":<ms>,"seq":n,"criteria":[{"id","description","target","admittedInEpoch"}]}}
// —— `snapshot` 必带：四条读数是给"那张新表"验的，没有新表的读数在链上没有用途（v1 收紧）。
//
// **弃读三层**（判据⑤：坏行与重放分开，各有一条测试）：
//   · 结构坏行（parse 失败／必填缺失／构造器拒）⇒ 只 RunLog，**不消耗 ref**——人手写的
//     文件难免错一次，别让一次笔误烧掉一个 CI run；修正后同 ref 重推还收；
//   · 时刻不符（早于提案开出-容差）⇒ 只 RunLog，不消耗 ref；
//   · **时钟可疑**（at 晚于时刻+容差）⇒ RunLog ＋**落一行进 ref 账**（理由写进账，
//     判据②：别静默弃——设备时钟偏了这件事，重放检查与事后对账都要看得见）。
//
// ref 账 `进化读数-已用.refs`（追加-only，kinds 闭集）：`USED|ref|atMs` 与
//   `CLOCK_SUSPECT|ref|理由|atMs`。**ref 落盘是防重放的根**（用户判据①：只存内存，
//   重启就能重放一票）——本文件零内存态，每次评估都从盘上现读，进程重启挡不住它。
// 消耗时机＝verify 真正拿读数那一刻（[Reading.runner] 被链调用时）：准入拒／换表拒／
//   没有新表，链都到不了 verify ⇒ ref 不许被烧。
//
// 零 Android：`root` 由 [DecisionLedger.installedRoot] 给，`nowMs` 由调用方给。
package com.example.zhengdao.baopu.evolution

import com.example.zhengdao.baopu.DecisionLedger
import com.example.zhengdao.core.RunLog
import com.example.zhengdao.baopu.policy.AnchorHold
import com.example.zhengdao.baopu.policy.CriteriaSnapshot
import com.example.zhengdao.baopu.policy.CriterionEntry
import com.example.zhengdao.baopu.policy.DetektDebt
import com.example.zhengdao.baopu.policy.EpochId
import com.example.zhengdao.baopu.policy.EvolutionProof
import com.example.zhengdao.baopu.policy.GateRun
import java.io.File
import org.json.JSONObject

/** 读数收件箱的一行（`进化读数.jsonl` 一行一条；CI 写、本件只读，不回写不涂改）。 */
internal data class ProofRow(
    val proposal: String,
    val ref: String,
    val atMs: Long,
    val proof: EvolutionProof,
    val snapshot: CriteriaSnapshot,
) {
    companion object {
        /** parse＋构造一体：任何一层建不出来＝null（坏行），不抛——调用方只分好/坏两态。 */
        fun parse(raw: String): ProofRow? = runCatching {
            val o = JSONObject(raw)
            val proposal = o.optString("proposal")
            val ref = o.optString("ref")
            val at = o.optLong("at", -1L)
            if (proposal.isBlank() || ref.isBlank() || at < 0L) return null
            val unit = o.optJSONObject("unit") ?: return null
            val regression = o.optJSONObject("regression") ?: return null
            val anchor = o.optJSONObject("anchor") ?: return null
            val detekt = o.optJSONObject("detekt") ?: return null
            val snap = o.optJSONObject("snapshot") ?: return null
            val epochMs = snap.optLong("epochMs", -1L)
            if (epochMs < 0L) return null
            val criteriaJson = snap.optJSONArray("criteria") ?: return null
            if (criteriaJson.length() == 0) return null
            // 376 的构造期拦截在这里生效：ran=true 却 0 例 ⇒ GateRun 建不出来 ⇒ 坏行。
            val proof = EvolutionProof(
                unitTests = GateRun(unit.optBoolean("ran"), unit.optInt("total"), unit.optInt("failed")),
                regressionSet = GateRun(
                    regression.optBoolean("ran"),
                    regression.optInt("total"),
                    regression.optInt("failed"),
                ),
                anchorSet = AnchorHold(anchor.optInt("total"), anchor.optInt("mismatched")),
                detekt = DetektDebt(detekt.optInt("before"), detekt.optInt("after")),
            )
            // 310 的构造期拦截在这里生效：撞不可进化清单的表 ／ 判据 id 重复 ⇒ 建不出来 ⇒ 坏行。
            val snapshot = CriteriaSnapshot.of(
                epoch = EpochId.of(epochMs),
                seq = snap.optLong("seq", 0L),
                criteria = (0 until criteriaJson.length()).map { i ->
                    val c = criteriaJson.getJSONObject(i)
                    CriterionEntry(
                        id = c.optString("id"),
                        description = c.optString("description"),
                        target = c.optString("target"),
                        admittedInEpoch = c.optLong("admittedInEpoch", 0L),
                    )
                },
            )
            ProofRow(proposal, ref, at, proof, snapshot)
        }.getOrNull()
    }
}

/** 读数收件箱（380 真验证臂的取数件；判定仍在 376 的闸里，本件零判法）。 */
internal object ProofReading {

    internal const val READINGS_FILE = "进化读数.jsonl"
    internal const val USED_REFS_FILE = "进化读数-已用.refs"

    /** 时钟容差（判据②：设备时钟可能偏，±5 分钟内不算账钟不对）。 */
    internal const val CLOCK_TOLERANCE_MS = 5L * 60_000L

    private const val KIND_USED = "USED"
    private const val KIND_CLOCK_SUSPECT = "CLOCK_SUSPECT"

    /** ref 账的一行（kinds 闭集；竖线由落盘时替换兜底，列分隔符不许被内容撑破）。 */
    private fun refLine(vararg cols: String): String = cols.joinToString("|") { it.replace("|", "／") }

    /**
     * 给一场进化选一行合格读数（**不消耗 ref**——消耗在 [Reading.runner] 被链调用那一刻）。
     *
     * 返回 null ＝ 本场没有能用的读数：链拿着 next=null 停在准入之后（不自动改判据），
     * 提案状态由链自己写明白，本件只把弃因逐行说给 RunLog。
     */
    fun pick(proposalId: String, openedAtMs: Long, nowMs: Long): Reading? {
        val root = DecisionLedger.installedRoot()
        if (root == null) {
            RunLog.log("抱朴读数臂：账根没挂——读数通道不可用（链只能停在准入后）")
            return null
        }
        val rows = readRows(root)
        if (rows.isEmpty()) {
            RunLog.log("抱朴读数臂：收件箱没有合格行（$READINGS_FILE 缺失或全坏）——链停在准入后等读数与新表")
            return null
        }
        val mine = rows.filter { it.proposal == proposalId }
        if (mine.isEmpty()) {
            RunLog.log("抱朴读数臂：收件箱有 ${rows.size} 行，没有一行是本场提案（$proposalId）的——链停在准入后")
            return null
        }
        val used = usedRefs(root)
        val suspected = suspectedRefs(root)
        val usable = ArrayList<ProofRow>()
        for (row in mine.sortedByDescending { it.atMs }) {
            when {
                row.ref in used ->
                    RunLog.log("抱朴读数臂：读数被拒（ref=${row.ref}）——一票一用，已用过（防重放）")
                row.ref in suspected ->
                    RunLog.log("抱朴读数臂：读数被拒（ref=${row.ref}）——此前已判时钟可疑，不再采用")
                row.atMs > nowMs + CLOCK_TOLERANCE_MS -> {
                    RunLog.log(
                        "抱朴读数臂：时钟可疑（ref=${row.ref}）——读数时刻 ${row.atMs} 晚于此刻 $nowMs 超容差，" +
                            "弃读数；理由已落 ref 账",
                    )
                    markClockSuspect(root, row, nowMs)
                }
                row.atMs < openedAtMs - CLOCK_TOLERANCE_MS ->
                    RunLog.log(
                        "抱朴读数臂：读数早于提案（ref=${row.ref}）——时刻 ${row.atMs} 早于提案开出 " +
                            "$openedAtMs 减容差，弃读数（证据不能先于它要辩护的提议）",
                    )
                else -> usable += row
            }
        }
        val chosen = usable.firstOrNull()
        if (chosen == null) {
            RunLog.log("抱朴读数臂：本场提案（$proposalId）没有一条合格读数——链停在准入后")
            return null
        }
        return Reading(chosen, root)
    }

    /** 体检单上的一格（判据④）：读数通道在什么构建下可用，release 的不可用要显出来。 */
    internal fun channelLine(debuggable: Boolean): String =
        if (debuggable) {
            "进化读数通道：可用（debug：读数与新表可经 run-as 送进 抱朴记忆/）"
        } else {
            "进化读数通道：不可用（release）：私有目录无推送口——御批后链停在准入等读数，不是进化总在失败"
        }

    /** 一场进化的选中读数：新表给链当 `next`，读数在 verify 那一刻经 [runner] 进闸。 */
    internal class Reading internal constructor(private val row: ProofRow, private val root: File) {

        internal val snapshot: CriteriaSnapshot get() = row.snapshot

        /**
         * verify 真正拿读数那一刻才会被链调用（准入/换表拒都到不了 verify）——调了才消耗 ref。
         * 落盘失败只 RunLog 不抛：账写不上要在日志里喊出来（重启后可能重放，得有人补账）。
         */
        internal val runner: (CriteriaSnapshot) -> EvolutionProof = { _ ->
            if (row.ref in usedRefs(root)) {
                RunLog.log("抱朴读数臂：ref=${row.ref} 已在账上（防重放兜底），本次不重复落")
            } else {
                appendRef(
                    root,
                    refLine(KIND_USED, row.ref, "${row.atMs}"),
                    "已用 ref ${row.ref}",
                )
            }
            row.proof
        }
    }

    // ── 读侧（每次评估都从盘上现读；零内存态 ⇒ 进程重启不构成绕过） ────────────

    internal fun usedRefs(root: File): Set<String> = refsOfKind(root, KIND_USED)

    internal fun suspectedRefs(root: File): Set<String> = refsOfKind(root, KIND_CLOCK_SUSPECT)

    private fun refsOfKind(root: File, kind: String): Set<String> {
        val f = File(root, USED_REFS_FILE)
        if (!f.isFile) return emptySet()
        return f.readLines(Charsets.UTF_8)
            .filter { it.startsWith("$kind|") }
            .mapNotNull { it.split('|').getOrNull(1) }
            .filter { it.isNotBlank() }
            .toSet()
    }

    private fun readRows(root: File): List<ProofRow> {
        val f = File(root, READINGS_FILE)
        if (!f.isFile) return emptyList()
        return f.readLines(Charsets.UTF_8)
            .filter { it.isNotBlank() }
            .mapIndexedNotNull { idx, raw ->
                ProofRow.parse(raw) ?: run {
                    RunLog.log("抱朴读数臂：坏行跳过（$READINGS_FILE 第 ${idx + 1} 行，parse/构造器拒）——不消耗 ref")
                    null
                }
            }
    }

    // ── 写侧（追加-only；只写 ref 账这一本，收件箱本体是 CI 的，一字不碰） ──────

    private fun markClockSuspect(root: File, row: ProofRow, nowMs: Long) {
        appendRef(
            root,
            refLine(
                KIND_CLOCK_SUSPECT,
                row.ref,
                "读数时刻晚于此刻超容差（${row.atMs} > $nowMs+${CLOCK_TOLERANCE_MS}）",
                "$nowMs",
            ),
            "时钟可疑 ref ${row.ref}",
        )
    }

    private fun appendRef(root: File, line: String, what: String) {
        runCatching {
            File(root, USED_REFS_FILE).appendText(line + "\n", Charsets.UTF_8)
        }.onFailure {
            RunLog.log("抱朴读数臂：$what 没落上——${it.javaClass.simpleName}: ${it.message}（重启后可能重放，账要补）")
        }
    }
}
