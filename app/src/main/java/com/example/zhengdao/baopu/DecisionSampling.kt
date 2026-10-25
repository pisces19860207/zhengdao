// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百零八批 §2.7（2026-10-26 补）：「**待抽检**」组——自动做的事不能没人看，
// 但也**不许把你变成审稿员**：真删 100%、正式退役 100%，其余每周最多 5 条配额。
//
// 三条规则：
//   ① 抽检命中：`diary_purge`／`skill_retire` 全抽；`skill_draft_retire`／`diary_maintain`
//      按**自然周**配额（[WEEKLY_QUOTA]）抽，超了排队下周；
//   ② 每条给两个结论：「确认无误」／「判错了」——后者转成**回归样本**（带动作摘要＋
//      `matrixVersion`＋`reasonCode`），落 `<抱朴记忆>/回归样本.jsonl`，供 219 加测试读；
//   ③ 判过的（不论对错）离开待抽检组，但**仍留在配额里**（不然删一条就能无限抽）。
//
// 纪律：零 Android、零 Compose、零 IO（落盘只在 [DecisionRegression]）；JVM 直测。
package com.example.zhengdao.baopu

import org.json.JSONObject
import java.io.File
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

internal object DecisionSampling {

    /** 每周配额（§2.7 原文：每周自动抽检 ≤5 条；真删类不受限）。 */
    const val WEEKLY_QUOTA = 5

    /** 本周起点（**本地**周一 00:00）。配额按这个桶算。 */
    fun weekStartMs(atMs: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
        Instant.ofEpochMilli(atMs)
            .atZone(zone)
            .toLocalDate()
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            .atStartOfDay(zone)
            .toInstant()
            .toEpochMilli()

    /**
     * 这一刻**抽中**的记录 id（配额在抽中那一层消耗，判过的不退还）。
     *
     * - [DecisionAudit.ALWAYS]：全中；
     * - [DecisionAudit.WEEKLY]：按自然周分组，每组按时间**从旧到新**取前 [WEEKLY_QUOTA] 条；
     * - [DecisionAudit.NONE]：不抽。
     */
    fun sampledIds(records: List<DecisionRecord>): Set<String> {
        val out = mutableSetOf<String>()
        records.filter { it.kind.audit == DecisionAudit.ALWAYS }.forEach { out += it.id }
        records.filter { it.kind.audit == DecisionAudit.WEEKLY }
            .groupBy { weekStartMs(it.atMs) }
            .forEach { (_, rows) -> rows.sortedBy { it.atMs }.take(WEEKLY_QUOTA).forEach { out += it.id } }
        return out
    }

    /** 待抽检：抽中且**还没判**的（新→旧）。 */
    fun pending(records: List<DecisionRecord>, judged: Set<String> = emptySet()): List<DecisionRecord> {
        val sampled = sampledIds(records)
        return records
            .filter { it.id in sampled && it.id !in judged }
            .sortedByDescending { it.atMs }
    }

    /** 待抽检 id 集合（给分组层用）。 */
    fun pendingIds(records: List<DecisionRecord>, judged: Set<String> = emptySet()): Set<String> =
        pending(records, judged).map { it.id }.toSet()
}

/**
 * 抽检结论（「确认无误」／「判错了」）——**判错的那些就是回归样本**，供 219 加测试读。
 *
 * 落点 `<抱朴记忆>/抽检结论.jsonl`，一行一条、只追加：判了又反悔＝再写一行，
 * 读侧按 [recordId] 取最后一条（历史不覆盖，说得出「当时判的是什么」）。
 *
 * 为什么不另起一个「回归样本.jsonl」：那会是同一件事的第二份账——
 * 判错这件事本身就是样本，字段一次写全（动作摘要＋`matrixVersion`＋`reasonCode`），
 * 219 读 [regressionSamples] 就等于读回归样本集。
 */
internal object DecisionReview {

    const val FILE_NAME = "抽检结论.jsonl"

    /**
     * 风险矩阵版本（219 落地后由它注入；没注入就是 [UNVERSIONED]——
     * **写死一个假的版本号比不写更糟**，那时样本无法定位到具体矩阵）。
     */
    @Volatile
    var matrixVersion: String = UNVERSIONED

    /** 没装配矩阵版本时写这个字面量（读的人一眼知道「这条样本没版本」）。 */
    const val UNVERSIONED = "m0"

    /** 一条抽检结论（键名固定，测试逐个钉）。 */
    data class Entry(
        val recordId: String,
        val kind: String,
        /** [DecisionRecord.VERDICT_CONFIRMED]／[DecisionRecord.VERDICT_WRONG]。 */
        val verdict: String,
        /** 动作摘要（人读的一行：谁对什么做了什么）——判错时它就是回归样本的输入。 */
        val actionSummary: String,
        val matrixVersion: String,
        /** 原因码（稳定键＝kind 的线值；「判错了」这件事由 [verdict] 表达）。 */
        val reasonCode: String,
        val evidence: String,
        val atMs: Long,
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("record_id", recordId)
            .put("kind", kind)
            .put("verdict", verdict)
            .put("action_summary", actionSummary)
            .put("matrix_version", matrixVersion)
            .put("reason_code", reasonCode)
            .put("evidence", evidence)
            .put("at", atMs)
    }

    /** 由一条记录造结论（纯函数；[matrixVersion] 缺省取现装配的）。 */
    fun entryOf(
        r: DecisionRecord,
        verdict: String,
        atMs: Long,
        matrixVersion: String = DecisionReview.matrixVersion,
    ): Entry = Entry(
        recordId = r.id,
        kind = r.kind.wire,
        verdict = verdict,
        actionSummary = "${r.actor.wire}：${r.reason}（${r.before}→${r.after}）",
        matrixVersion = matrixVersion,
        reasonCode = r.kind.wire,
        evidence = r.evidence,
        atMs = atMs,
    )

    /** 追加一条（父目录不存在就建；失败返回 false，由调用方 log——结论丢一条不算事故）。 */
    @Synchronized
    fun append(globalRoot: File, entry: Entry): Boolean = runCatching {
        val f = File(globalRoot, FILE_NAME)
        f.parentFile?.mkdirs()
        f.appendText(entry.toJson().toString() + "\n", Charsets.UTF_8)
        true
    }.getOrDefault(false)

    /** 现读（坏行跳过；按记录 id 取**最后**一条＝当前结论）。 */
    fun read(globalRoot: File): List<Entry> = runCatching {
        val f = File(globalRoot, FILE_NAME)
        if (!f.isFile) return@runCatching emptyList()
        f.readLines(Charsets.UTF_8).mapNotNull { raw ->
            val o = runCatching { JSONObject(raw) }.getOrNull() ?: return@mapNotNull null
            val id = o.optString("record_id")
            val verdict = o.optString("verdict")
            if (id.isEmpty() || verdict !in VERDICTS) return@mapNotNull null
            Entry(
                recordId = id,
                kind = o.optString("kind"),
                verdict = verdict,
                actionSummary = o.optString("action_summary"),
                matrixVersion = o.optString("matrix_version"),
                reasonCode = o.optString("reason_code"),
                evidence = o.optString("evidence"),
                atMs = o.optLong("at", 0L),
            )
        }
    }.getOrDefault(emptyList())

    /** 每个记录 id 的**当前**结论（后写覆盖前写）。 */
    fun latestPerRecord(entries: List<Entry>): Map<String, Entry> {
        val out = LinkedHashMap<String, Entry>()
        entries.forEach { e ->
            val prev = out[e.recordId]
            if (prev == null || e.atMs >= prev.atMs) out[e.recordId] = e
        }
        return out
    }

    /** 判过的记录 id（待抽检组要排除它们）。 */
    fun judgedIds(entries: List<Entry>): Set<String> = latestPerRecord(entries).keys

    /** 回归样本集（判错的那些；219 的测试读这个）。 */
    fun regressionSamples(entries: List<Entry>): List<Entry> =
        latestPerRecord(entries).values.filter { it.verdict == DecisionRecord.VERDICT_WRONG }

    /** 合法结论值（别的字面量一律当坏行）。 */
    val VERDICTS = setOf(DecisionRecord.VERDICT_CONFIRMED, DecisionRecord.VERDICT_WRONG)
}
