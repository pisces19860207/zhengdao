// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百三十九批 I5 · 反复失败未归档。
//
// 查什么（派工单 §1 原行）：同一技能／同一能力近 7 天失败 ≥3 次，
// 但**既没进"待学完"也没退役** ⇒ 交叉 208 × 技能目录，命中就报"**该收没收拾**"。
//
// 两个必须写实的口径，不然这一项要么永远绿、要么永远红：
// 1. **"待学完"目前只在设计文档里**（台账 173ci/238 那段：`技能/待学完/`、
//    `complete` vs `draft_incomplete` 都只是提案，`app/src` 里 grep 不到一个字符）。
//    所以本项现在只能核"退役"这半边 ⇒ 判据留了一个 [archivedNames] 的口，
//    将来 `技能/待学完/` 落地就把那批名字喂进来，判据一个字不用改。
//    **这不降低命中质量**：今天没有任何技能能进待学完，所以每条 HIT 都是实打实没收拾。
// 2. **失败从 `skill_event` 取，不从技能正文取**：`kind='use' AND ok=0` 才是"用了一次没成"；
//    正文里的 `uses=` 只涨不跌，拿它当失败数会永远为 0。
//
// 「已收拾」的三个可观察证据（任一即算，全在账本/文件系统上）：
//   ① 208 里有 `skill_retire` / `skill_draft_retire` 行（`evidence = "skill:<层>/<名>"`，
//      房型逐字取自 `SkillLibraryScreen.kt:252`／`:447`）；
//   ② 正文文件已软删成 `SKILL.md.deleted-*`（`SkillStore.archive` 的唯一出口，本仓无硬删）；
//   ③ 将来：待学完目录里的名字。
package com.example.zhengdao.baopu.inspect

import com.example.zhengdao.core.TimeFormats

/** 一次技能失败（`skill_event` 里 `kind='use' AND ok=0` 的一行）。 */
internal data class SkillFailure(val name: String, val atMs: Long)

/** 一个名字的失败合计（[latest] 用于指到最近那一次）。 */
internal data class FailureCluster(val name: String, val hits: Int, val latest: Long)

internal object UnarchivedFailureCheck {

    /** 窗口（天）——派工单写"近 7 天"。 */
    const val WINDOW_DAYS = InspectLimits.FAILURE_WINDOW_DAYS

    /** 阈值——派工单写"≥3 次"。 */
    const val MIN_HITS = InspectLimits.FAILURE_MIN_HITS

    /** 窗口毫秒。 */
    fun windowMs(days: Int = WINDOW_DAYS): Long = days * 24L * 3600_000L

    /** 按名字合计窗口内的失败（新→旧里的最近一次留作指针）。 */
    fun cluster(
        failures: List<SkillFailure>,
        nowMs: Long,
        days: Int = WINDOW_DAYS,
    ): List<FailureCluster> {
        val from = nowMs - windowMs(days)
        return failures.filter { it.atMs >= from }
            .groupBy { it.name }
            .map { (name, rows) -> FailureCluster(name, rows.size, rows.maxOf { it.atMs }) }
            .sortedByDescending { it.hits }
    }

    /** 够次数、又没有任何"已收拾"证据 ⇒ 命中。 */
    fun unarchived(
        clusters: List<FailureCluster>,
        archivedNames: Set<String>,
        minHits: Int = MIN_HITS,
    ): List<FailureCluster> = clusters.filter { it.hits >= minHits && it.name !in archivedNames }

    private fun evidenceOf(c: FailureCluster): String =
        "「${c.name}」${WINDOW_DAYS} 天内失败 ${c.hits} 次" +
            "（最近 ${TimeFormats.monthDay(c.latest)} ${TimeFormats.hourMinute(c.latest)}），" +
            "208 无退役行、正文也未软删 ⇒ 该收没收拾"

    fun check(
        failures: List<SkillFailure>,
        archivedNames: Set<String>,
        nowMs: Long,
        days: Int = WINDOW_DAYS,
        minHits: Int = MIN_HITS,
    ): InspectFinding {
        val clusters = cluster(failures, nowMs, days)
        val hits = unarchived(clusters, archivedNames, minHits)
        val overLimit = clusters.count { it.hits >= minHits }
        val evidence = hits.take(InspectDetailLimits.MAX_EVIDENCE_PER_FINDING).map(::evidenceOf)
        return when {
            failures.isEmpty() -> InspectFinding(
                check = InspectCheck.UNARCHIVED_FAILURE,
                verdict = InspectVerdict.NO_DATA,
                oneLine = InspectText.line("反复失败：窗口内没有任何技能失败记录"),
                suggestion = "跑过技能再巡（零失败可能是零使用，别当通过）",
                count = 0,
            )
            hits.isNotEmpty() -> InspectFinding(
                check = InspectCheck.UNARCHIVED_FAILURE,
                verdict = InspectVerdict.HIT,
                oneLine = InspectText.line("反复失败未归档 ${hits.size} 条：「${hits.first().name}」失败 ${hits.first().hits} 次"),
                suggestion = "要么退役要么进待学完，别让它继续被自动选中（退役是可回滚的那条路）",
                evidence = evidence,
                count = hits.size,
            )
            overLimit > 0 -> InspectFinding(
                check = InspectCheck.UNARCHIVED_FAILURE,
                verdict = InspectVerdict.CLEAR,
                oneLine = InspectText.line("反复失败未归档 0 条（$overLimit 条已收拾）"),
                suggestion = "无需处理",
                evidence = clusters.filter { it.hits >= minHits }
                    .map { "「${it.name}」失败 ${it.hits} 次，已有退役/归档证据" },
                count = 0,
            )
            else -> InspectFinding(
                check = InspectCheck.UNARCHIVED_FAILURE,
                verdict = InspectVerdict.CLEAR,
                oneLine = InspectText.line("反复失败未归档 0 条（最多 ${clusters.maxOf { it.hits }} 次/未达 $minHits）"),
                suggestion = "无需处理",
                count = 0,
            )
        }
    }
}
