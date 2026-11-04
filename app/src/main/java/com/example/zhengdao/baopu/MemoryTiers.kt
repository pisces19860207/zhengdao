// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：用户 2026-11-04 记忆分层裁定（三类分开／生命周期四态／三条判据，第四百三十八批）；
// AGENTS.md「稳定层与易变层」（外部事实必带 source＋asOf、默认 90 天过期）——本文件把
// 同一条时效纪律从模型清单推广到记忆库。与 [MemoryRules]（一条合不合格）、
// [MemoryQuality]（一库质量）的分工：这里判「这条记忆属于哪一类、处在生命周期哪一态」。
package com.example.zhengdao.baopu

import com.example.zhengdao.core.TimeFormats

/**
 * 记忆三类（用户 2026-11-04，**三类分开，判据互不相同**）：
 * - **偏好** [MemoryTier.PREFERENCE]：用户的口味与习惯。**可被一句话覆盖**——
 *   同一条偏好后说的算（[MemoryTiers.overridable]），旧条留盘不删（红线：不删用户记忆）；
 * - **规矩** [MemoryTier.RULE]：用户亲手立的规矩。**只能人改**——机器不覆盖、不选边、
 *   不过期；每一条都必须能追溯到用户原句＋人工确认，机器只当抄写员；
 * - **事实** [MemoryTier.FACT]：关于世界的陈述。**必带 source＋asOf**（source 那半是
 *   [MemoryRules.validate] 的既有判据），90 天过期（[MemoryTiers.FACT_TTL_DAYS]）。
 */
enum class MemoryTier { PREFERENCE, RULE, FACT }

/**
 * 生命周期四态（用户 2026-11-04）：候选 → 复核 → 有效 → 过期。
 *
 * 与 [MemoryStatus] 的对法（[MemoryTiers.lifecycleOf] 一处说了算）：
 * CANDIDATE＝候选（**不可用于判断**）、PENDING＝复核、CONFIRMED＝有效（**可作提示**）、
 * 过期＝事实类读时判（不落盘改状态——与质量环同一房型：读时算、零写入、可逆）。
 * REJECTED 不在四态里（人不肯记＝没有这条）。
 */
enum class MemoryLifecycle { CANDIDATE, REVIEW, ACTIVE, EXPIRED }

/**
 * 记忆分层的**纯判据**（第四百三十八批）。无 Android、无文件 IO，JVM 直测。
 *
 * ## 三条判据（用户原话，逐条落码）
 * 1. **事实无 source＋asOf 不收**：[acceptance] 在落盘闸上拒（source 由
 *    [MemoryRules.validate] 的既有判据管，这里管 asOf 那一半）；
 * 2. **超期降「仅供参考」且不参与判定**：[isExpired] 读时判；过期条目照样可进上下文
 *    （AGENTS.md 口径「不许默默藏掉」——标注而不隐藏），但 [renderForContext] 把它
 *    降格成「仅供参考」，判定的部分（三态结论）从来不吃记忆（MemoryTriStateGateTest 钉）；
 * 3. **记忆永不参与三态**：三态结论模块（右相验收 PASS/FAIL/UNVERIFIABLE、技能入库闸、
 *    漂移三态等）一个都不许引用记忆系统——文本不变量由 MemoryTriStateGateTest 源级扫。
 *
 * ## 确定性
 * 一切判据都是 `(条目, now)` 的纯函数；[collapseOverrides] 的平手键是
 * 创建时间新 ⇒ id 升序，与 [MemoryQuality.primaryOf] 同房型——同输入同输出，
 * 不依赖集合迭代顺序（拼进上下文的前缀不许抖）。
 */
internal object MemoryTiers {

    /** 事实的保质期：90 天（与 AGENTS.md「外部事实默认 90 天过期」同一口径）。 */
    const val FACT_TTL_DAYS = 90L

    /** 保质期毫秒数（衰减按「天」整档算，复用 [MemoryQuality.DAY_MILLIS] 那一个数）。 */
    const val FACT_TTL_MILLIS = FACT_TTL_DAYS * MemoryQuality.DAY_MILLIS

    // ── 三类映射（唯一真相） ────────────────────────────────────────────────

    /**
     * kind → 三类。六个 kind 全部落进三类，**不留第四类**：
     * 偏好/画像 → 偏好；规矩/教训 → 规矩（教训是用户原句的纠正——人立的规矩）；
     * 事实/术语 → 事实（术语定义来自资料，是有出处的陈述）。
     */
    fun tierOf(kind: MemoryKind): MemoryTier = when (kind) {
        MemoryKind.PREFERENCE, MemoryKind.PROFILE -> MemoryTier.PREFERENCE
        MemoryKind.RULE, MemoryKind.LESSON -> MemoryTier.RULE
        MemoryKind.FACT, MemoryKind.TERM -> MemoryTier.FACT
    }

    /** 三类的中文标签（与 [MemoryRules.kindLabel] 同房型；界面与说明用）。 */
    fun tierLabel(tier: MemoryTier): String = when (tier) {
        MemoryTier.PREFERENCE -> "偏好"
        MemoryTier.RULE -> "规矩"
        MemoryTier.FACT -> "事实"
    }

    /** 生命周期四态的中文标签（「过期」带去向说明，别让读者以为是删了）。 */
    fun lifecycleLabel(lifecycle: MemoryLifecycle): String = when (lifecycle) {
        MemoryLifecycle.CANDIDATE -> "候选"
        MemoryLifecycle.REVIEW -> "复核"
        MemoryLifecycle.ACTIVE -> "有效"
        MemoryLifecycle.EXPIRED -> "过期（仅供参考）"
    }

    // ── 事实的时效 ────────────────────────────────────────────────────────

    /**
     * 事实的「核实时刻」：asOf 缺失（老文件读回 0）按 createdAt 兜底——
     * **不发明时间线，也不追溯拒收**：老条目是旧判据收进来的，过期线照算，
     * 收录判据只对「新落的」 bite（[acceptance]）。
     */
    fun asOfOrFallback(entry: MemoryEntry): Long = if (entry.asOf > 0L) entry.asOf else entry.createdAt

    /**
     * 过期线（毫秒）：核实时刻 ＋ 90 天。非事实类没有过期线 ⇒ 0（偏好与规矩
     * 不过期——「只能人改」的另一面是「机器不许替人淡忘」）。
     * 核实时刻都拿不到（asOf 与 createdAt 都是 0）⇒ 同样 0：没有时间线就不判过期。
     */
    fun expiresAt(entry: MemoryEntry): Long {
        if (tierOf(entry.kind) != MemoryTier.FACT) return 0L
        val asOf = asOfOrFallback(entry)
        return if (asOf <= 0L) 0L else asOf + FACT_TTL_MILLIS
    }

    /**
     * 过期判据：**满 90 天整才过期**（与半衰期「满 90 天掉一档」同一房型）。
     * `now = 0`（调用方没给时刻）恒为不过期——[ContextAssembler] 的旧口径调用
     * 不传时刻，行为与 438 之前逐字相同。
     */
    fun isExpired(entry: MemoryEntry, now: Long): Boolean {
        val at = expiresAt(entry)
        return at > 0L && now >= at
    }

    // ── 收录判据（判据 1：事实无 source＋asOf 不收） ──────────────────────

    /** 收录结论。[Ok] 原样放行；[Bad] 带给人看的理由（不收，也不替人补）。 */
    sealed interface Acceptance {
        data class Ok(val entry: MemoryEntry) : Acceptance
        data class Bad(val reason: String) : Acceptance
    }

    /**
     * 收录判据（[MemoryStore.write] 的闸之一，排在合格性校验之后、质量闸门之前）：
     * 事实类必须带 asOf。**不自动补**——补一个「核实时刻」就是替人编造证据时刻，
     * 该什么时刻核实就写什么时刻；补不上就别收。
     */
    fun acceptance(entry: MemoryEntry): Acceptance = when (tierOf(entry.kind)) {
        MemoryTier.FACT ->
            if (entry.asOf > 0L) Acceptance.Ok(entry)
            else Acceptance.Bad("事实必须带 asOf（哪天核实的）——没有不收（438 判据）；补上核实时刻再记")
        else -> Acceptance.Ok(entry)
    }

    // ── 生命周期（判据 2 的读时部分） ──────────────────────────────────────

    /**
     * 这条记忆处在生命周期哪一态（**读时判、零写入、可逆**）。
     * REJECTED ⇒ null：四态之外，任何使用路径都必须当「没有这条」处理——
     * 用 null 不用第五个枚举值，是诚实的做法（四态就是四态，拒绝的不在里面）。
     */
    fun lifecycleOf(entry: MemoryEntry, now: Long): MemoryLifecycle? = when (entry.status) {
        MemoryStatus.CANDIDATE -> MemoryLifecycle.CANDIDATE
        MemoryStatus.PENDING -> MemoryLifecycle.REVIEW
        MemoryStatus.CONFIRMED -> if (isExpired(entry, now)) MemoryLifecycle.EXPIRED else MemoryLifecycle.ACTIVE
        MemoryStatus.REJECTED -> null
    }

    /**
     * 能不能进上下文当提示：**只有 CONFIRMED（有效）能**。候选与复核中的
     * 不可用于判断（生命周期判据），被拒的更不行。过期事实**仍在其中**——
     * 以「仅供参考」形态进（[renderForContext]），标注而不隐藏。
     */
    fun injectable(entry: MemoryEntry): Boolean = entry.status == MemoryStatus.CONFIRMED

    // ── 偏好的一句话覆盖 ──────────────────────────────────────────────────

    /**
     * 一句话覆盖管不管用：**只对偏好成立**（用户 2026-11-04 原话的括号注）。
     * 规矩不管用（只能人改——同键冲突两侧保留等人裁）；事实也不管用
     * （世界的事实不因谁说了句话就变，要变得带新的 source＋asOf 另立一条）。
     */
    fun overridable(tier: MemoryTier): Boolean = tier == MemoryTier.PREFERENCE

    /**
     * 同键偏好的**最新覆盖视图**：同一自然键（[MemoryQuality.naturalKey]）的偏好条目
     * 多于一条时，只留最新那条（创建时间新 ⇒ id 升序平手键），其余从**视图**里塌掉。
     *
     * 两条红线不破：
     * 1. **只塌视图不碰盘**——被塌掉的条目原文件一个字节不动（与质量环合并视图同房型，
     *    回滚＝不读这个视图）；
     * 2. **只塌偏好**——规矩与事实的同键多条原样保留（冲突等人裁，不自动选边）。
     *
     * 用在注入侧（[ContextAssembler]）：模型的上下文里同一条偏好只出现最新说法，
     * 旧说法不再顶在前面打架。输入顺序保持（被塌的抽掉，其余原位原序）。
     */
    fun collapseOverrides(entries: List<MemoryEntry>): List<MemoryEntry> {
        if (entries.size < 2) return entries
        val newest = mutableMapOf<String, MemoryEntry>()
        for ((key, group) in entries.groupBy { MemoryQuality.naturalKey(it) }) {
            if (group.size < 2 || !overridable(tierOf(group.first().kind))) continue
            newest[key] = group
                .sortedWith(compareByDescending<MemoryEntry> { it.createdAt }.thenBy { it.id })
                .first()
        }
        if (newest.isEmpty()) return entries
        return entries.filter { e ->
            val key = MemoryQuality.naturalKey(e)
            key !in newest || newest.getValue(key).id == e.id
        }
    }

    // ── 注入渲染（判据 2 的「仅供参考」形态） ──────────────────────────────

    /**
     * 一条记忆进上下文的渲染：未过期与老口径逐字相同（[MemoryRules.renderForContext]）；
     * **过期事实**降格「仅供参考」——类型标签带后缀，正文之后追一行去向说明，
     * 说清哪天核实、超了多少天、要继续用得人工复核（模型与人都看得见这条降格，
     * 不许默默藏掉，也不许它冒充现行事实）。
     *
     * @param nowMillis 当前时刻（毫秒）；0＝调用方没给 ⇒ 按不过期渲染（旧口径不变）。
     */
    fun renderForContext(entry: MemoryEntry, nowMillis: Long): String {
        if (!isExpired(entry, nowMillis)) return MemoryRules.renderForContext(entry)
        return buildString {
            append("【").append(MemoryRules.kindLabel(entry.kind)).append("·仅供参考】").append(entry.title)
            if (entry.tags.isNotEmpty()) append("（标签：").append(entry.tags.joinToString("、")).append("）")
            append('\n').append(entry.body)
            append('\n').append(expiryNote(entry, nowMillis))
        }
    }

    /** 过期说明（给人与模型看的去向行；天数按「天」整档算，与衰减同房型）。 */
    private fun expiryNote(entry: MemoryEntry, now: Long): String {
        val asOf = asOfOrFallback(entry)
        val idleDays = ((now - asOf).coerceAtLeast(0L)) / MemoryQuality.DAY_MILLIS
        return "（本条 ${TimeFormats.dayStamp(asOf)} 核实，已 $idleDays 天没复核，过了 $FACT_TTL_DAYS 天保质期——" +
            "只当背景参考，别当现行事实用；要继续用请人工复核后更新 as_of）"
    }
}
