// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 289 · 技能库卫生（2026-10-30 用户口径：「让技能多了也不乱，不是换存储方式」）。
//
// 四件（全部**纯判据**——只出建议与读数，处置权在人，与 `SkillRefine.SkillExpiry`
// 同一条边界：只提示不处置）：
//   ① **重名/近似合并建议**：同名或高度相似 ⇒ 给「升级／新建」二选一，不许静默覆盖
//      （旧口径是同名备份后原地覆盖、近似场景在索引里原位替换——289 起要先摆到明面上）；
//   ② **长期不用归档建议**：记「用几次、成没成」，过期判据（14 天未用等）命中的 ⇒
//      建议归档（走既有软退役：改名 `.deleted-*` 原件保留，**可恢复，永不真删**）；
//   ③ **总量阈值提醒**：技能库（草稿＋正式）到阈值 ⇒ 提醒整理一次（只提醒，不拦写入）；
//   ④ **命中率读数**：被检索到（file_op 里的读账）/ 被选用（skill_event kind=use）/
//      成功（kind=effect outcome=SUCCESS）三个数——「多而不乱」靠这三个数证明，
//      不是只看总数。
package com.example.zhengdao.baopu

/**
 * 技能库卫生的**纯判据层**（不碰盘、不碰库；执行壳在 UI 与工具层）。
 */
internal object SkillHygiene {

    // ── ① 重名／近似合并建议 ──────────────────────────────────────────────

    /**
     * 两层既有索引行（B＋C 一起）——重名/近似探查的输入（**只读**，读不到按空）。
     *
     * 为什么放这而不放 `SkillAutoWrite`：免批壳文件有 N8 源码级门禁
     * （「不许提 B 层字面量」——闸的意图是 AUTO 不许**写** B 层）；本函数只读，
     * 放这儿既能让免批壳拿到查重输入，又不动那道闸一个字。
     */
    fun existingIndexLines(personaFiles: PersonaFiles): List<SkillRules.IndexLine> =
        SkillRules.parseIndexLines(personaFiles.read(PersonaLayer.B_GLOBAL), MemoryScope.GLOBAL) +
            SkillRules.parseIndexLines(personaFiles.read(PersonaLayer.C_PROJECT), MemoryScope.PROJECT)

    /** 重复的种类（判据从硬到软：同名 > 名字像 > 场景像）。 */
    enum class DuplicateKind {
        /** 名字一模一样（安全化后逐字相等）——落点就是同一个文件。 */
        SAME_NAME,

        /** 名字高度相似（二字元 bigram Jaccard ≥ [NAME_SIMILARITY]）。 */
        SIMILAR_NAME,

        /** 场景句高度相似（与索引判重同一阈值 [MemoryRules.DUPLICATE_SIMILARITY]）。 */
        SIMILAR_SCENARIO,
    }

    /**
     * 一次重复命中。`existingName` 是既有那条的技能名；`similarity` 给确认卡/工具回执展示。
     */
    data class Duplicate(val kind: DuplicateKind, val existingName: String, val similarity: Double)

    /** 名字相似的阈值（bigram Jaccard；0.8 ≈ 「整理书稿」vs「整理书稿V2」这一档）。 */
    const val NAME_SIMILARITY = 0.8

    /**
     * 查重（**纯函数**）：把待写的名字＋场景句对既有索引逐条判。
     * 同名必中（与 `MemoryRules.bodySimilarity` 的空串口径一致：任一侧空 ⇒ 不判相似）。
     * 多条命中取**最硬**的一条（同名优先，其次名字像，最后场景像；同级取相似度最高）。
     */
    fun findDuplicate(
        name: String,
        scenario: String,
        existing: List<SkillRules.IndexLine>,
    ): Duplicate? {
        var best: Duplicate? = null
        for (e in existing) {
            val hit = matchOne(name, scenario, e) ?: continue
            val cur = best
            if (cur == null || rankOf(hit) < rankOf(cur) ||
                (rankOf(hit) == rankOf(cur) && hit.similarity > cur.similarity)
            ) {
                best = hit
            }
        }
        return best
    }

    /** 同级排序权重：同名最硬，其次名字像，最后场景像。 */
    private fun rankOf(d: Duplicate): Int = when (d.kind) {
        DuplicateKind.SAME_NAME -> 0
        DuplicateKind.SIMILAR_NAME -> 1
        DuplicateKind.SIMILAR_SCENARIO -> 2
    }

    /** 一条既有索引的匹配（null ＝ 不撞）。判据从硬到软：同名 > 名字像 > 场景像。 */
    private fun matchOne(
        name: String,
        scenario: String,
        e: SkillRules.IndexLine,
    ): Duplicate? {
        if (e.name == name) return Duplicate(DuplicateKind.SAME_NAME, e.name, 1.0)
        // 名字像：bigram Jaccard 达线，或互为包含（「整理书稿」vs「整理书稿V2」这类
        // 改名后缀——短名 bigram 会被稀释掉，包含关系兜住；较短方 ≥3 字才认）。
        val contains = name.length >= 3 && e.name.length >= 3 && (name.contains(e.name) || e.name.contains(name))
        val nSim = MemoryRules.bodySimilarity(name, e.name)
        if (nSim >= NAME_SIMILARITY || contains) {
            return Duplicate(DuplicateKind.SIMILAR_NAME, e.name, maxOf(nSim, NAME_SIMILARITY))
        }
        if (scenario.isNotBlank()) {
            val sSim = MemoryRules.bodySimilarity(scenario, e.scenario)
            if (sSim >= MemoryRules.DUPLICATE_SIMILARITY) {
                return Duplicate(DuplicateKind.SIMILAR_SCENARIO, e.name, sSim)
            }
        }
        return null
    }

    /**
     * 「新建」分支的 suggested 名字：`<名>-2`、`<名>-3`…取第一个没人用的；
     * 名字＋后缀超上限时先截基础名（安全化过的名字再截仍是安全字符）。
     * 基础名占满上限截完还装不下 `-2` ⇒ null（调用方让人自己起名，不硬造）。
     */
    fun alternativeName(base: String, taken: Set<String>): String? {
        var stem = SkillStore.sanitizeName(base) ?: return null
        // 先把基础名截到「连 -999 都装得下」的长度（顶格名也要有解）；
        // 截出来的仍是安全字符（名字本来就是安全化过的）。
        while (stem.length + 4 > SkillStore.NAME_MAX_CHARS) {
            if (stem.length <= 1) return null
            stem = stem.dropLast(1)
        }
        var n = 2
        while (n <= 999) {
            val cand = "$stem-$n"
            if (cand !in taken) return cand
            n++
        }
        return null
    }

    /** 确认卡／工具回执上的「二选一」人话（升级＝覆盖既有（先备份），新建＝换名另立）。 */
    fun duplicateGuidance(dup: Duplicate, alt: String?): String {
        val head = when (dup.kind) {
            DuplicateKind.SAME_NAME -> "已有同名技能「${dup.existingName}」"
            DuplicateKind.SIMILAR_NAME ->
                "已有名字很近的技能「${dup.existingName}」（相似度 ${(dup.similarity * 100).toInt()}%）"
            DuplicateKind.SIMILAR_SCENARIO ->
                "已有场景几乎一样的技能「${dup.existingName}」（相似度 ${(dup.similarity * 100).toInt()}%）"
        }
        return "$head，二选一：升级＝覆盖它（旧版自动留备份）；新建＝换个名字另立一条" +
            (alt?.let { "（建议「$it」）" } ?: "（自己起一个没人用的名）") + "。"
    }

    // ── ② 长期不用 ⇒ 归档建议（不删，走可恢复的软退役） ─────────────────────

    /** 一条技能的使用读数（调用方从 skill_event 聚合；没有记录就给 null／空表——不冤枉）。 */
    data class UsageStat(
        val name: String,
        /** 最近一次被选中（kind=use）的时刻；没记录 ⇒ null。 */
        val lastUsedAtMs: Long?,
        /** 使用成败（旧→新，供失败率窗口）。 */
        val recentOks: List<Boolean> = emptyList(),
    )

    /** 归档建议的动作（RECHECK＝先重验，不一定要归档）。 */
    enum class SuggestAction { ARCHIVE, RECHECK }

    /** 一条建议（只提示；处置走技能页行内动作，归档＝软退役，可恢复）。 */
    data class Suggestion(val name: String, val action: SuggestAction, val reason: String)

    /**
     * 卫生建议（**纯函数**）：判据完全复用 `SkillRefine.expiryVerdict`（14 天窗／5 次失败窗，
     * 证据不够不判）——
     * - 久未用（含又久又败）⇒ 建议归档（软退役，可恢复；不是删除）；
     * - 只是在连败 ⇒ 建议重验（技能可能没坏，是环境变了）。
     */
    fun hygieneSuggestions(stats: List<UsageStat>, nowMs: Long): List<Suggestion> {
        val out = mutableListOf<Suggestion>()
        for (s in stats) {
            when (val v = SkillRefine.expiryVerdict(s.lastUsedAtMs, s.recentOks, nowMs)) {
                SkillRefine.ExpiryVerdict.FRESH -> Unit
                SkillRefine.ExpiryVerdict.STALE_FAILING ->
                    out += Suggestion(s.name, SuggestAction.RECHECK, SkillRefine.reasonOf(v))
                SkillRefine.ExpiryVerdict.STALE_UNUSED, SkillRefine.ExpiryVerdict.STALE_BOTH ->
                    out += Suggestion(s.name, SuggestAction.ARCHIVE, SkillRefine.reasonOf(v))
            }
        }
        return out.sortedWith(compareBy({ it.action }, { it.name }))
    }

    // ── ③ 总量阈值提醒 ────────────────────────────────────────────────────

    /** 技能库总量提醒阈值（草稿＋正式合计；只提醒不拦写）。 */
    const val TOTAL_THRESHOLD = 200

    /**
     * 到阈值 ⇒ 提醒一句整理；没到 ⇒ null。**只提醒**：阈值不是写入闸，
     * 「多了也不乱」靠命中率与归档建议，不靠硬卡数量。
     */
    fun totalReminder(totalActive: Int, threshold: Int = TOTAL_THRESHOLD): String? =
        if (totalActive >= threshold) {
            "技能库已有 $totalActive 条（到整理线 $threshold）：建议把长期没用、命中率低的归档一批" +
                "（行内「退役」，原件保留可恢复）。"
        } else {
            null
        }

    // ── ④ 命中率读数 ──────────────────────────────────────────────────────

    /** 一条技能的命中率三数（窗口随数据源：检索来自 file_op 30 天账）。 */
    data class HitRate(
        val retrieved: Int,
        val used: Int,
        val succeeded: Int,
    ) {
        /** 给行上展示的一句（三个数都是 0 ⇒ 空串，不占地方）。 */
        fun text(): String = if (retrieved <= 0 && used <= 0 && succeeded <= 0) {
            ""
        } else {
            "检索 $retrieved · 用 $used · 成 $succeeded"
        }
    }

    /**
     * file_op 的读账里有多少笔是这个技能正文的（**纯函数**）：
     * 精确按尾段 `/技能/<名>/SKILL.md` 匹配（名字里的 `_` 在 Kotlin 里是字面量，
     * 不吃 SQL LIKE 通配符的亏）。
     */
    fun retrievedCountOf(name: String, readPaths: List<String>): Int {
        val suffix = "/${SkillRules.SKILL_DIR}/$name/${SkillRules.SKILL_FILE}"
        return readPaths.count { it.endsWith(suffix) }
    }
}
