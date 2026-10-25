// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第二百零五批 · 追加件③：**草稿技能自动治理**的纯规则层（只判不写——执行壳在
// `ui/baopu` 的 `governDrafts`，写盘全部走既有口）。
//
// 四条规则（派工单原文，**只动草稿**、全部留痕可回退）：
//   1. 草稿 30 天未用（uses 不涨、从未被选中）⇒ 自动**软退役**（.deleted-*＋索引去行）；
//   2. 草稿 > 20 条 ⇒ 按「最久未用」软退役到 20；
//   3. uses ≥ 3 ⇒ 自动转正（进决策中心，action=promote reason=满3次）；
//   4. 两条草稿 scenario 归一后相同 ⇒ **不自动删**，只报「疑似重复」给技能页标出来。
//
// 三条红线（负例逐条红，见 `SkillDraftGovernorTest`）：
//   · 只软退役，**永不真删**正文（执行壳走 `SkillStore.archive`，本层连 delete 的字面都没有）；
//   · **正式技能（status=active）自动规则一个不碰**——本层入口只收草稿；
//   · 每条自动动作都要在决策中心留 `skill_draft_retire` / `promote`（执行壳经
//     `SkillAudit.record`／`commitSkillDraft` 落账，缺账即红）。
package com.example.zhengdao.baopu

import java.io.File
import org.json.JSONObject

/** 一条草稿的治理读数（两层目录扫出来；`uses` 只认数据层）。 */
internal data class DraftStat(
    val name: String,
    val scope: SkillScope,
    val file: File,
    val body: String,
    val uses: Int,
    /** 最近一次活动（skill_event 最后使用与变更账最后写入取大；没有 ⇒ 首次写入时刻）。 */
    val lastActivityMs: Long,
    /** 索引行的场景句（失联草稿给空串——重复判据对空归一不判）。 */
    val scenario: String,
)

/** 一次治理的计划（纯判据的输出；执行壳照单执行，缺一条都不许）。 */
internal data class GovernancePlan(
    /** 规则①：30 天未用 ⇒ 软退役。 */
    val idleRetire: List<DraftStat> = emptyList(),
    /** 规则②：超出上限 ⇒ 最久未用的补刀（已进 idleRetire 的不重复列）。 */
    val overflowRetire: List<DraftStat> = emptyList(),
    /** 规则③：uses 满 3 ⇒ 自动转正（走 207 的口，进决策中心）。 */
    val promoteDue: List<DraftStat> = emptyList(),
    /** 规则④：疑似重复对（只标不删；A/B 均为草稿）。 */
    val duplicatePairs: List<Pair<DraftStat, DraftStat>> = emptyList(),
) {
    val isEmpty: Boolean
        get() = idleRetire.isEmpty() && overflowRetire.isEmpty() && promoteDue.isEmpty() && duplicatePairs.isEmpty()
}

internal object SkillDraftGovernor {

    /** 规则②的上限：草稿最多 20 条（派工单原话「> 20 条 ⇒ 软退役到 20」）。 */
    const val MAX_DRAFTS = 20

    /** 规则①的闲置窗：30 天。 */
    const val IDLE_MS = 30L * 24 * 60 * 60 * 1000

    /**
     * 两层技能目录 ⇒ 草稿读数（**只读**）。
     *
     * - 只收 `status=draft`（正式技能在这里就进不来——「自动规则不碰正式组」的第一道闸）；
     * - `lastActivityMs` ＝ max(变更账最后写入 ts, 调用方给的 lastUseMs)——uses 不涨且没人写
     *   ⇒ 这个值就停在最后一次活动上，30 天窗由此起算；
     * - `scenario` ＝ 索引行场景（按名字精确对上；失联草稿空串）。
     */
    fun draftStats(
        projectRoot: File,
        globalRoot: File,
        lastUseMs: Map<String, Long> = emptyMap(),
        scenarios: Map<String, String> = emptyMap(),
    ): List<DraftStat> {
        val auditLastTs = mutableMapOf<String, Long>()
        for (dir in listOf(SkillRules.projectSkillDir(projectRoot), SkillRules.globalSkillDir(globalRoot))) {
            val f = SkillAudit.fileIn(dir)
            if (!f.isFile) continue
            val text = runCatching { f.readText(Charsets.UTF_8) }.getOrNull() ?: continue
            text.lines().forEach { raw ->
                val o = runCatching { JSONObject(raw) }.getOrNull() ?: return@forEach
                val name = o.optString("name")
                if (name.isEmpty()) return@forEach
                val ts = o.optLong("ts", 0L)
                if (ts > auditLastTs.getOrDefault(name, 0L)) auditLastTs[name] = ts
            }
        }
        val out = mutableListOf<DraftStat>()
        for ((scope, dir) in listOf(
            SkillScope.PROJECT to SkillRules.projectSkillDir(projectRoot),
            SkillScope.GLOBAL to SkillRules.globalSkillDir(globalRoot),
        )) {
            val subs = dir.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }.orEmpty()
            subs.forEach { sub ->
                val f = File(sub, SkillRules.SKILL_FILE)
                if (!f.isFile) return@forEach
                val body = runCatching { f.readText(Charsets.UTF_8) }.getOrNull() ?: return@forEach
                if (SkillStore.statusOf(body) != "draft") return@forEach
                out += DraftStat(
                    name = sub.name,
                    scope = scope,
                    file = f,
                    body = body,
                    uses = SkillStore.usesOf(body),
                    lastActivityMs = maxOf(auditLastTs[sub.name] ?: 0L, lastUseMs[sub.name] ?: 0L),
                    scenario = scenarios[sub.name].orEmpty(),
                )
            }
        }
        return out
    }

    /** 四条规则一次算清（纯函数；执行壳照单执行）。 */
    fun plan(stats: List<DraftStat>, nowMs: Long): GovernancePlan {
        if (stats.isEmpty()) return GovernancePlan()

        // ③ 满 3 次 ⇒ 自动转正（不弹卡，进决策中心）
        val promoteDue = stats.filter { it.uses >= SkillStore.PROMOTE_USES }

        // ① 30 天未用（uses 不涨、从未被选中 ⇒ lastActivity 停在 30 天前）
        val idleRetire = stats.filter { it.uses < SkillStore.PROMOTE_USES && nowMs - it.lastActivityMs > IDLE_MS }
            .sortedWith(compareBy({ it.lastActivityMs }, { it.name }))

        // ② 超出 20 条 ⇒ 最久未用的补刀（已进①的不重复；恰好 20 条不动）
        val overflowRetire = if (stats.size > MAX_DRAFTS) {
            val doomed = idleRetire.toSet()
            stats.filter { it !in doomed && it.uses < SkillStore.PROMOTE_USES }
                .sortedWith(compareBy({ it.lastActivityMs }, { it.name }))
                .take(stats.size - MAX_DRAFTS)
        } else {
            emptyList()
        }

        // ④ 疑似重复：场景归一后相同（复用 204 的归一口径）；归一为空不判；只标不删
        val duplicates = mutableListOf<Pair<DraftStat, DraftStat>>()
        val byNorm = stats.groupBy { SkillIndexReconcile.normalizeScenario(it.scenario) }
        for ((norm, group) in byNorm) {
            if (norm.isEmpty() || group.size < 2) continue
            val sorted = group.sortedBy { it.name }
            for (i in sorted.indices) for (j in i + 1 until sorted.size) {
                duplicates += sorted[i] to sorted[j]
            }
        }
        return GovernancePlan(
            idleRetire = idleRetire,
            overflowRetire = overflowRetire,
            promoteDue = promoteDue,
            duplicatePairs = duplicates,
        )
    }
}
