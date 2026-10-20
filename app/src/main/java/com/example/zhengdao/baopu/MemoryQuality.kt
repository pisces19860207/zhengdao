// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单第八十九批 §1（写入校验 / 合并 / 衰减时效 / 检索排序 / 冲突标记 / 迁移）
// 与 §2「明确不做」。**红线**：本文件里没有任何删除动作——衰减只降权、重复只出合并
// 视图、冲突只打标记，删除永远由人来点（§0 原话：「本单只做打分/合并/排序这些无破坏
// 动作，删除永远留给人」）。
package com.example.zhengdao.baopu

/**
 * 记忆质量的**纯判据**（第八十九批 · 进化地基 ⑥）：打分、衰减、同键判据、合并视图、
 * 冲突标记、排序键。无 Android、无文件 IO，JVM 直测（`import` 一条都没有）。
 *
 * ## 为什么单独一个文件而不是塞进 [MemoryRules]
 * [MemoryRules] 判「一条记忆合不合格」（第八批定的），本对象判「一库记忆的**质量**」
 * （本批定的）。两层各自可测：合格性不看时间、质量不看单条格式。混在一起会让第八批
 * 那批用例既钉格式又钉衰减，改哪个都红。
 *
 * ## 三条不可动摇的口径
 * 1. **只降权不删**：分数地板是 [MIN_SCORE] = 1 而不是 0——0 分在检索里等于召不到，
 *    那是「不删之删」，正面踩红线。降权的表现是排得靠后，不是消失。
 * 2. **合并不吃原文**：[merged] 是**读时视图**，被合并的原件一条都不动（文件仍在盘上，
 *    id 全留在 [MemoryEntry.mergedFrom] 里）。所以合并天然可逆：不想要合并视图，
 *    读 [ranked] 的原样清单即可。
 * 3. **冲突不选边**：同键不同值 ⇒ 两边都保留、两边都打 [FLAG_CONFLICT]，
 *    谁对谁错交给人（[Pass.conflicts] 把组并排列出来，不排序裁剪、不比较优劣）。
 *
 * ## 确定性
 * 一切判据都是 `(条目, now)` 的纯函数：同一批记忆、同一个 `now`，两次调用逐字段相同
 * （包括合并视图的 id 与顺序）。这条纪律来自追加四第 2 条——拼进上下文的前缀不许因为
 * 集合迭代顺序而变。
 */
internal object MemoryQuality {

    /** 满分（也是老库迁移后的默认分：没有质量账的记忆按「暂不扣分」处理）。 */
    const val MAX_SCORE = 100

    /**
     * 分数地板。**故意不是 0**：0 分等于「检索召不到」，那是不删之删，与本单红线
     * （不自动删除用户记忆）正面冲突。1 分 = 排最后但还在。
     */
    const val MIN_SCORE = 1

    /** 老数据默认分（v14 迁移里 `score` 列的默认值 = 这个数；见 [BaopuSql.MEMORY_QUALITY_COLUMNS]）。 */
    const val DEFAULT_SCORE = MAX_SCORE

    /** 一天毫秒数（衰减按「天」整档算，不按毫秒连续算——连续函数会让每次读数都换顺序）。 */
    const val DAY_MILLIS = 86_400_000L

    /** 半衰期（天）：空闲满 90 天掉一档（100 → 50 → 25 → …）。 */
    const val HALF_LIFE_DAYS = 90L

    /** 衰减档位上限（超过就到底，不再右移；也防位移溢出）。 */
    private const val MAX_STEPS = 7

    /** 每次使用加分（与 [USE_BONUS_CAP] 一起构成「常用＝有用」的补偿）。 */
    private const val USE_BONUS_PER = 2

    /** 使用加分上限（20 分封顶：用得多能续命，但不能把过期记忆顶回满分）。 */
    private const val USE_BONUS_CAP = 20

    /** 低于这个分打 [FLAG_STALE]（标记不是删除，也不从召回里剔除）。 */
    const val STALE_BELOW = 40

    /** 标记字面值（写进 .md 的 `quality_flags` 与 DB 的 `quality_flags`，同一套字符串）。 */
    const val FLAG_STALE = "STALE"
    const val FLAG_CONFLICT = "CONFLICT"
    const val FLAG_MERGED = "MERGED"

    /** 审计动作字面值（[Audit.action] 只能取这几个；日志与报告按它归类）。 */
    const val ACT_SCORE = "SCORE"
    const val ACT_STALE = "STALE"
    const val ACT_MERGE = "MERGE"
    const val ACT_CONFLICT = "CONFLICT"
    const val ACT_REJECT = "REJECT"

    // ── 自然键（「同一条记忆的另一个说法」的判据） ──────────────────────────

    /**
     * 自然键 = `scope/kind/标题slug`。
     *
     * 为什么用 slug 不用标题原文：标题的手写差异（多一个空格、多一个「！」）本来就该算
     * 同一条；[MemoryRules.slugOf] 已经是「文件名安全片段」这层归一（第八批定的），复用
     * 它而不是再造一份归一规则——再造就是第二份真相。
     * 为什么含 scope/kind：全局的「偏好」与项目的「事实」不可能是同一条记忆，让它们
     * 互相判冲突等于把两码事搅成一团（判据与 [MemoryRules.isDuplicate] 同房型）。
     */
    fun naturalKey(e: MemoryEntry): String =
        e.scope.name.lowercase() + "/" + e.kind.name.lowercase() + "/" + MemoryRules.slugOf(e.title)

    /** 同键（[naturalKey] 相等）——本批「同 key」的全部所指。 */
    fun sameKey(a: MemoryEntry, b: MemoryEntry): Boolean = naturalKey(a) == naturalKey(b)

    // ── 衰减打分 ──────────────────────────────────────────────────────────

    /** 最后一次「有动静」的时刻：创建 / 改写 / 被用上，三者取最晚（都没动过才按创建算）。 */
    fun activityAt(e: MemoryEntry): Long = maxOf(e.createdAt, e.updatedAt, e.lastUsedAt)

    /** 空闲了多少整天（负数按 0——时钟倒走不许凭空变出满分）。 */
    fun idleDays(e: MemoryEntry, now: Long): Long =
        ((now - activityAt(e)).coerceAtLeast(0L)) / DAY_MILLIS

    /** 衰减档位：每满 [HALF_LIFE_DAYS] 天加一档，上限 [MAX_STEPS]。 */
    fun decayStep(e: MemoryEntry, now: Long): Int =
        (idleDays(e, now) / HALF_LIFE_DAYS).coerceAtMost(MAX_STEPS.toLong()).toInt()

    /** 「常用＝有用」的补偿分（每次 [USE_BONUS_PER] 分，[USE_BONUS_CAP] 封顶）。 */
    fun useBonus(e: MemoryEntry): Int = (e.useCount * USE_BONUS_PER).coerceAtMost(USE_BONUS_CAP)

    /**
     * 质量分（[MIN_SCORE]..[MAX_SCORE] 整数）：满分按半衰期整档右移，再加使用补偿。
     *
     * 用位移不用浮点幂函数：`100 → 50 → 25 → 12 → 6 → 3 → 1`（整档台阶）。浮点会让
     * 每次读数都产生新分数，注入顺序跟着抖——前缀缓存与「同输入同输出」都保不住。
     * 分数只会因**跨过 90 天边界**而变，一天里读一百次也是同一个序。
     */
    fun scoreOf(e: MemoryEntry, now: Long): Int =
        ((MAX_SCORE shr decayStep(e, now)) + useBonus(e)).coerceIn(MIN_SCORE, MAX_SCORE)

    /** 时间能产生的标记（只有 [FLAG_STALE]；[FLAG_CONFLICT]／[FLAG_MERGED] 是分组事实）。 */
    fun timeFlags(score: Int): List<String> = if (score < STALE_BELOW) listOf(FLAG_STALE) else emptyList()

    /**
     * 给一条记忆算出当前质量分与标记（**只写 `qualityScore`/`qualityFlags`/`scoredAt`
     * 三个账位，正文、标题、来源、id 一个字都不动**）。
     *
     * 已有的 [FLAG_CONFLICT]／[FLAG_MERGED] 保留：分组阶段先打的事实标记，再过一次
     * 打分不许把它抹掉（抹掉＝冲突看不见＝变相替用户选边）。
     */
    fun marked(e: MemoryEntry, now: Long): MemoryEntry {
        val s = scoreOf(e, now)
        val kept = e.qualityFlags.filter { it == FLAG_CONFLICT || it == FLAG_MERGED }
        return e.copy(qualityScore = s, qualityFlags = (timeFlags(s) + kept).distinct(), scoredAt = now)
    }

    /**
     * 把质量账清零（回到「没算过分」的原样）。**这就是可逆性的实现**：质量层只往
     * `qualityScore`/`qualityFlags`/`mergedFrom`/`scoredAt` 四个账位写，清掉它
     * 就等于本批从没发生过——正文与来源本来就没被碰过。
     */
    fun unmarked(e: MemoryEntry): MemoryEntry = e.copy(
        qualityScore = DEFAULT_SCORE,
        qualityFlags = emptyList(),
        mergedFrom = emptyList(),
        scoredAt = 0L,
    )

    // ── 同键判据：重复 vs 冲突 ────────────────────────────────────────────

    /** 同键两条的关系：[DuplicateBody] 走合并，[Conflict] 走标记，[DifferentKey] 不相干。 */
    sealed interface KeyVerdict
    object DifferentKey : KeyVerdict
    data class DuplicateBody(val similarity: Double) : KeyVerdict
    data class Conflict(val similarity: Double) : KeyVerdict

    /**
     * 判据（本批的核心分岔）：
     * - 不同键 ⇒ 不相干；
     * - 同键且正文相似度 ≥ [MemoryRules.DUPLICATE_SIMILARITY] ⇒ 重复（合并）；
     * - 同键但正文不一样 ⇒ **冲突**（两边都留、都标记，等人来判）。
     *
     * 阈值复用第八批的 [MemoryRules.DUPLICATE_SIMILARITY]：一把尺子量两头，
     * 不然「写入时算重复、读时算冲突」就是两份真相。
     */
    fun verdict(a: MemoryEntry, b: MemoryEntry): KeyVerdict {
        if (!sameKey(a, b)) return DifferentKey
        val sim = MemoryRules.bodySimilarity(a.body, b.body)
        return if (sim >= MemoryRules.DUPLICATE_SIMILARITY) DuplicateBody(sim) else Conflict(sim)
    }

    // ── 合并（读时视图，不吃原文） ────────────────────────────────────────

    /**
     * 主条目：组里挑「最早立账」那条做主体（id 与来源跟着它走）。
     * 判据 = 创建时间升序 ⇒ id 升序。选最早而不是选最新，是为了让合并视图的 id
     * **与读数时刻无关**（选最新的话，每次改写都把合并视图的 id 换掉，缓存与日志全乱）。
     */
    fun primaryOf(group: List<MemoryEntry>): MemoryEntry =
        group.sortedWith(compareBy({ it.createdAt }, { it.id })).first()

    /**
     * 正文取「信息量最大」的一条：词集合（[MemoryRules.tokensOf]）大的赢；
     * 平手按更新时间新 ⇒ id 升序。相似度已过阈值的两条本来就近乎同文，取词多的
     * 等于取「不丢内容」的那一半——另一半分毫未动，仍在盘上，id 记在 mergedFrom。
     */
    private fun richestBody(group: List<MemoryEntry>): MemoryEntry =
        group.sortedWith(
            compareByDescending<MemoryEntry> { MemoryRules.tokensOf(it.body).size }
                .thenByDescending { it.updatedAt }
                .thenBy { it.id },
        ).first()

    /**
     * 把一组同键重复合并成**一条视图**（不写盘、不删原件、不碰任何一条原文）。
     *
     * 保留清单（判据：合并必须不丢东西）：
     * - id／来源 = 主条目（[primaryOf]）的；
     * - [MemoryEntry.mergedFrom] = 组内全部 id（**含主条目自己**、按 id 升序）⇒ 每条都能顺着 id 回原文；
     *   为什么排序：合并视图要在「输入顺序打乱」后逐字段相同（判据：确定性），
     *   并集保序挡不住调用方传序不同——按 id 升序才是唯一形态。
     * - 标签 = 组内并集（主条目优先，去重保序）；
     * - 正文 = 信息量最大那条；
     * - 时间／用量 = 创建取最早、更新取最晚、使用数求和、最后使用取最晚；
     * - 分数 = 组内最高分（合并后不许比任何一条原件更难看）。
     */
    fun merged(group: List<MemoryEntry>, now: Long): MemoryEntry {
        require(group.size >= 2) { "合并视图至少要有两条记忆（现在 ${group.size} 条）" }
        val p = primaryOf(group)
        val flags = (p.qualityFlags + group.flatMap { it.qualityFlags } + FLAG_MERGED).distinct()
        return p.copy(
            body = richestBody(group).body,
            tags = (p.tags + group.flatMap { it.tags }).distinct(),
            qualityScore = group.maxOf { it.qualityScore },
            qualityFlags = flags,
            mergedFrom = group.map { it.id }.distinct().sorted(),
            scoredAt = now,
            createdAt = group.minOf { it.createdAt },
            updatedAt = group.maxOf { it.updatedAt },
            useCount = group.sumOf { it.useCount },
            lastUsedAt = group.maxOf { it.lastUsedAt },
        )
    }

    /** 一条簇要看的样子：单条原样（不贴 MERGED 标记、不写 mergedFrom），多条才出合并视图。 */
    private fun viewOf(cluster: List<MemoryEntry>, now: Long): MemoryEntry =
        if (cluster.size > 1) merged(cluster, now) else cluster.first()

    // ── 排序（读取顺序 = 质量顺序） ───────────────────────────────────────

    /**
     * 排序键：分数降序 ⇒ 创建时间升序 ⇒ id 升序。
     *
     * 后两档就是第八批那对「显式稳定排序」键，**分数全平时结果与旧序逐位相同**
     * （老库默认满分 ⇒ 升级当天注入顺序一个字都不动）。
     * 为什么第二键不用更新时间：注入前缀要「越早立的账越靠前」才稳——一次改写就把一条
     * 记忆甩到队尾，等于每改一次重排一遍前缀（缓存与「同输入同输出」都保不住）。
     */
    fun rankComparator(): Comparator<MemoryEntry> =
        compareByDescending<MemoryEntry> { it.qualityScore }
            .thenBy { it.createdAt }
            .thenBy { it.id }

    /** 按 [rankComparator] 排序（不裁剪、不改变条数）。 */
    fun ranked(entries: List<MemoryEntry>): List<MemoryEntry> = entries.sortedWith(rankComparator())

    // ── 一次质量扫描（打分 + 分组 + 合并视图 + 审计） ──────────────────────

    /** 一条审计：动作、对象、说明、时刻。正文不进审计（审计要能贴日志，记忆内容不能）。 */
    data class Audit(val action: String, val target: String, val detail: String, val atMillis: Long)

    /**
     * 一次质量扫描的结果。
     *
     * @param scored 全部记忆逐条打分后的**原样清单**（条数与输入相同——[Pass] 不会少一条）
     * @param rankedList [scored] 按 [rankComparator] 排好序（原样清单的读取顺序）
     * @param merged 合并视图（同键重复塌成一条；冲突的两侧都留着）——召回用这份
     * @param conflicts 每组冲突的 id（并排呈现，不选边）
     * @param audits 本次扫描做的每个动作（可审计；不含记忆正文）
     */
    data class Pass(
        val scored: List<MemoryEntry>,
        val rankedList: List<MemoryEntry>,
        val merged: List<MemoryEntry>,
        val conflicts: List<List<String>>,
        val audits: List<Audit>,
    )

    /**
     * 全库质量扫描（**纯函数，零写入**）：打分 → 按自然键分组 → 组内按相似度聚簇 →
     * 每簇出视图；一簇以上同键的组记为冲突（两侧都在视图里）。
     *
     * 为什么读时算而不是写时落库：落库就要改写用户已有的 .md，那既是 §2 禁止的
     * 「覆盖用户记忆」，又把「回滚」变成一次数据修复。读时算的回滚＝不读这个视图，
     * 一个字节都不用动。
     *
     * 分组按「键的字典序」处理、簇按 [ranked] 顺序生成 ⇒ 结果与输入集合形态无关。
     */
    fun pass(entries: List<MemoryEntry>, now: Long): Pass {
        val scored = entries.map { marked(it, now) }
        val audits = mutableListOf<Audit>()
        val conflicts = mutableListOf<List<String>>()
        val view = mutableListOf<MemoryEntry>()

        for ((key, group) in scored.groupBy { naturalKey(it) }.toSortedMap()) {
            val clusters = mutableListOf<MutableList<MemoryEntry>>()
            for (e in ranked(group)) {
                val hit = clusters.firstOrNull { verdict(it.first(), e) is DuplicateBody }
                if (hit != null) hit += e else clusters += mutableListOf(e)
            }
            if (clusters.size == 1) {
                val cluster = clusters[0]
                val v = viewOf(cluster, now)
                view += v
                if (cluster.size > 1) {
                    audits += Audit(
                        ACT_MERGE, key,
                        "同键重复 ${cluster.size} 条并成 1 条视图：${cluster.joinToString(",") { it.id }} → ${v.id}" +
                            "（分数 ${v.qualityScore}；原件全留，可逐条追回）",
                        now,
                    )
                }
            } else {
                val ids = clusters.flatMap { c -> c.map { it.id } }.sorted()
                conflicts += ids
                for (c in clusters) {
                    val v = viewOf(c, now)
                    view += v.copy(qualityFlags = (v.qualityFlags + FLAG_CONFLICT).distinct())
                }
                audits += Audit(
                    ACT_CONFLICT, key,
                    "同键不同值 ${clusters.size} 组：${ids.joinToString(",")} ⇒ 只标记，不选边、不覆盖、不删除",
                    now,
                )
            }
        }
        // 降权审计：只讲分数与档位，满分且无标记的不出声（免得账被噪声淹没）。
        // 过期出 ACT_STALE、其余分数变化出 ACT_SCORE——两类账分开记，检索侧一眼能分。
        for (e in ranked(scored)) {
            if (e.qualityScore < DEFAULT_SCORE || e.qualityFlags.isNotEmpty()) {
                val stale = e.qualityFlags.contains(FLAG_STALE)
                val why = if (stale) {
                    "空闲 ${idleDays(e, now)} 天（第 ${decayStep(e, now)} 档）"
                } else {
                    "带标记 ${e.qualityFlags.joinToString("/")}"
                }
                val action = if (stale) ACT_STALE else ACT_SCORE
                audits += Audit(action, e.id, "$why ⇒ 分数 ${e.qualityScore}（最低 $MIN_SCORE 分也不删）", now)
            }
        }
        return Pass(
            scored = scored,
            rankedList = ranked(scored),
            merged = ranked(view),
            conflicts = conflicts,
            audits = audits.sortedWith(compareBy({ it.atMillis }, { it.action }, { it.target }, { it.detail })),
        )
    }

    // ── 写入闸门（合格之外再看「同键重复 / 同键冲突」） ────────────────────

    /**
     * 落盘前的质量判据。[WriteGate.Reject] = 不入库（重复，原因给人看）；
     * [WriteGate.Ok] = 可以落盘，[WriteGate.Ok.entry] 就是打好分与标记的那条
     * （**调用方落盘落的必须是它**，分数才有账可查）。
     */
    sealed interface WriteGate {
        data class Ok(val entry: MemoryEntry, val note: String, val audits: List<Audit>) : WriteGate
        data class Reject(val reason: String, val audit: Audit) : WriteGate
    }

    /**
     * 写入校验（第八十九批 §1 第 1、5 条）：
     * 1. **空／超长**由 [MemoryRules.validate] 判（第八批既有判据，这里不重造）；
     * 2. **明显重复**（同键且正文近似，或同 scope＋同 kind 高相似）⇒ 拒（不重复入库）；
     * 3. **同键不同值** ⇒ 放行，但给新条目打 [FLAG_CONFLICT]——**不许覆盖已有那条**
     *    （覆盖＝自动改掉用户记忆，红线），两条并排留在库里等人判。
     */
    fun gateWrite(entry: MemoryEntry, existing: List<MemoryEntry>, now: Long): WriteGate {
        val sameKeyDup = existing.firstOrNull { sameKey(entry, it) && verdict(entry, it) is DuplicateBody }
        if (sameKeyDup != null) {
            val reason = "与既有记忆同键且正文近似（${sameKeyDup.id}），不重复入库；要改原文请走「改」，或先把那条删了再记"
            return WriteGate.Reject(reason, Audit(ACT_REJECT, naturalKey(entry), reason, now))
        }
        val dupAnywhere = existing.firstOrNull { MemoryRules.isDuplicate(entry, it) }
        if (dupAnywhere != null) {
            val reason = "与既有记忆重复（同 scope ＋ 同 kind ＋ 正文近似：${dupAnywhere.id}），不重复入库"
            return WriteGate.Reject(reason, Audit(ACT_REJECT, dupAnywhere.id, reason, now))
        }
        val conflict = existing.firstOrNull { sameKey(entry, it) }
        val scored = marked(entry, now)
        if (conflict == null) {
            return WriteGate.Ok(scored, "", listOf(Audit(ACT_SCORE, scored.id, "入库分数 ${scored.qualityScore}", now)))
        }
        return WriteGate.Ok(
            scored.copy(qualityFlags = (scored.qualityFlags + FLAG_CONFLICT).distinct()),
            "同键已有另一份说法（${conflict.id}）：两条都留着并标记冲突，不覆盖、不删除、不替你判哪条对",
            listOf(Audit(ACT_CONFLICT, naturalKey(entry), "新条目 ${scored.id} 与既有 ${conflict.id} 同键不同值 ⇒ 双方标记", now)),
        )
    }
}
