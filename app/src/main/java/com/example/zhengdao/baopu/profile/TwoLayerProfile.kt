// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百八十批 · 画像两层引擎（派工单 280 · M7；台账 173fy 定稿口径）。
//
// 两层为什么分开（用户 2026-10-28：「全清会把基线也清掉 ⇒ 每两周从零学一次永远学不准」）：
//   - **短期层**（情绪/当下）：原始采样滚动保存，**过了 TTL 硬清**——
//     TTL 可配 7–15 天（默认 14，越界夹回）；过期即删，不进稳定层、不通知用户；
//   - **稳定层**（习性）：只存**聚合权重**（每类一个数），指数衰减、半衰期 30 天。
//     喂样时两处都记（短期层留原始行用于"连续 2 周"举证，稳定层记累计权重），
//     短期层过期删的是明细，稳定层的聚合一个字节不动——**删明细保聚合**。
//
// 黑箱纪律（173fy／173dw）：本文件是"harness 自用"的内部状态，对外只有两个读口——
//   `distribution()`（判据用，Dirichlet α=1 后验）与 `conclusion()`（勘验用，只出计数不出明细）。
// 没有任何函数能把一条原始采样（键/时刻/权重/哈希）递出去。
//
// 弱先验（M7 判据「清短期层后第一周回弱先验 1:1:1」）：清短期层（显式 clear 或 TTL 自然清空）
// 起 7 天内、或稳定层证据总量 <20 时，`distribution()` 一律等分（α=1 的先验形态）。
//
// 采样只许机器键：`kind` 必须是 `[a-z0-9_]`（≤64），文本一律以 8–64 位十六进制哈希＋长度
// 进来——**原文在类型上就进不来**（字符串参数只收哈希，形状闸拒绝一切散文）。
//
// 零 Android / 零 IO / 零时钟：`now` 全部由调用方传入；序列化用 org.json（与账本同房型）。
package com.example.zhengdao.baopu.profile

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import kotlin.math.pow

/** 一条短期层原始采样。**没有原文**：只有机器键、时刻、计入权重、来源与（可选）文本哈希/长度。 */
internal data class ShortTermEntry(
    val kind: String,
    val atMs: Long,
    /** 计入稳定层的权重（用户行为 ×1，模型自评 ×[TwoLayerProfile.MODEL_FACTOR]，喂入时已折算）。 */
    val weight: Double,
    val fromUser: Boolean,
    val textSha: String,
    val textLen: Int,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("k", kind)
        .put("at", atMs)
        .put("w", weight)
        .put("u", fromUser)
        .put("sha", textSha)
        .put("len", textLen)

    companion object {
        fun parse(o: JSONObject): ShortTermEntry? {
            val k = o.optString("k")
            val at = o.optLong("at", 0L)
            if (k.isEmpty() || at <= 0L) return null
            return ShortTermEntry(
                kind = k,
                atMs = at,
                weight = o.optDouble("w", 0.0),
                fromUser = o.optBoolean("u", false),
                textSha = o.optString("sha"),
                textLen = o.optInt("len", 0),
            )
        }
    }
}

/** 一类稳定层的聚合（基数＋记账时刻；有效权重＝base×0.5^(龄/半衰期)，读时现算）。 */
internal data class StableBucket(val base: Double, val atMs: Long)

/**
 * 两层画像引擎（纯状态机；持久化由 [UserProfileBlackbox] 做）。
 *
 * @param shortTermTtlDays 短期层硬清天数（**可配 7–15，默认 14**；越界夹回边界）
 */
internal class TwoLayerProfile(shortTermTtlDays: Int = DEFAULT_SHORT_TERM_DAYS) {

    /** 短期层硬清天数（可配范围 7–15；`ttl` 一律先夹再存）。 */
    var ttlDays: Int = shortTermTtlDays.coerceIn(MIN_TTL_DAYS, MAX_TTL_DAYS)
        private set

    /** 短期层原始采样（滚动 ≤[ROLLING_CAP] 条；过期在 [purge] 删）。 */
    val shortTerm = ArrayList<ShortTermEntry>()

    /** 稳定层聚合：kind → 桶。 */
    private val stable = LinkedHashMap<String, StableBucket>()

    /** 「连续 2 周 ≥60%」的起算时刻（kind → 时刻；份额跌破即撤销重算）。 */
    val stableSince = HashMap<String, Long>()

    /** 短期层最近一次被清空的时刻（弱先验计时起点；0＝还没清过）。 */
    var lastEmptiedAt: Long = 0L
        private set

    /** 改 TTL（越界夹回；返回夹后的值）。 */
    fun retuneTtl(days: Int): Int {
        ttlDays = days.coerceIn(MIN_TTL_DAYS, MAX_TTL_DAYS)
        return ttlDays
    }

    // ── 写侧 ────────────────────────────────────────────────────────────────

    /**
     * 喂一条采样：短期层留原始行，稳定层累计权重（先衰减后累加）。
     *
     * @param kind 机器键（形状闸：`[a-z0-9_]{1,64}`，散文进不来）
     * @param weight 原始权重（1–100；用户手动纠正按台账口径传 5）
     * @param fromUser false＝模型自评（×[MODEL_FACTOR]；**行为信号权重高于模型自评**）
     * @param textSha 采样文本的哈希（8–64 位十六进制；空＝无文本）；**原文参数不存在**
     * @return true＝记上了；false＝被形状闸拒绝（IllegalArgumentException 见 [require]）
     */
    fun feed(
        kind: String,
        weight: Int,
        fromUser: Boolean,
        atMs: Long,
        textSha: String = "",
        textLen: Int = 0,
    ): Boolean {
        require(KIND_SHAPE.matches(kind)) { "画像 kind 必须是机器键（[a-z0-9_] ≤64）：$kind" }
        require(weight in 1..MAX_WEIGHT) { "画像权重越界（1–100）：$weight" }
        require(textSha.isEmpty() || SHA_SHAPE.matches(textSha)) { "画像文本哈希形状不对：$textSha" }
        require(textLen >= 0) { "画像文本长度不合法：$textLen" }
        require(atMs > 0L) { "画像时刻不合法：$atMs" }
        val w = weight.toDouble() * (if (fromUser) 1.0 else MODEL_FACTOR)
        shortTerm.add(ShortTermEntry(kind, atMs, w, fromUser, textSha, textLen))
        if (shortTerm.size > ROLLING_CAP) shortTerm.removeAt(0)
        // 稳定层：先把旧基数衰减到此刻，再累加（同一把半衰期尺）
        val old = effectiveOf(kind, atMs)
        stable[kind] = StableBucket(base = old + w, atMs = atMs)
        return true
    }

    /**
     * 硬清：删掉过期的短期层明细（**稳定层聚合一个字节不动**）。
     *
     * @return 本次删掉几条；删完短期层从"有"变"空"时记 [lastEmptiedAt]（弱先验起算）
     */
    fun purge(now: Long): Int {
        val before = shortTerm.size
        if (before == 0) return 0
        val cutoff = now - ttlDays * DAY_MS
        shortTerm.removeAll { it.atMs < cutoff }
        val removed = before - shortTerm.size
        if (shortTerm.isEmpty() && removed > 0) lastEmptiedAt = now
        return removed
    }

    /**
     * 显式清短期层（M7 判据「清短期层后第一周回弱先验」的入口）：明细全删、聚合保留、
     * 弱先验从现在起算。返回删掉的条数。
     */
    fun clearShortTerm(now: Long): Int {
        val removed = shortTerm.size
        shortTerm.clear()
        stableSince.clear()
        lastEmptiedAt = now
        return removed
    }

    /**
     * 重算（**只在充电时由上层调用**，本函数不管闸）：把稳定层基数归一到 `now`（防漂移）、
     * 清一遍过期明细、顺带复评稳定偏好。返回是否有变化。
     */
    fun recompute(now: Long): Boolean {
        var changed = false
        for ((kind, bucket) in stable.entries.toList()) {
            val eff = effectiveOf(kind, now)
            if (eff < STABLE_FLOOR) {
                stable.remove(kind)
                stableSince.remove(kind)
                changed = true
            } else {
                stable[kind] = StableBucket(base = eff, atMs = now)
                if (bucket.base != eff) changed = true
            }
        }
        changed = purge(now) > 0 || changed
        reevaluateStableShares(now)
        return changed
    }

    // ── 读侧（只有这两个出口；没有任何函数递出原始行） ──────────────────────

    /**
     * harness 自用判据：给定候选类上的分布（Dirichlet α=1 后验）。
     *
     * 弱先验（清短期层后 7 天内、或稳定层证据总量 <[STABLE_MIN_SAMPLES]）⇒ **等分 1:1:…:1**。
     * `kinds` 为空 ⇒ 空表。不抛、不要求已 purge。
     */
    fun distribution(now: Long, kinds: Collection<String>): Map<String, Double> {
        if (kinds.isEmpty()) return emptyMap()
        val distinct = kinds.distinct()
        if (weakPriorActive(now)) {
            return distinct.associateWith { 1.0 / distinct.size }
        }
        val eff = distinct.associateWith { effectiveOf(it, now) }
        val total = eff.values.sum()
        val alpha = 1.0
        val denom = distinct.size * alpha + total
        reevaluateStableShares(now)
        return distinct.associateWith { (alpha + (eff[it] ?: 0.0)) / denom }
    }

    /** 弱先验是否在生效（判据口径：清短期层后第一周，或证据不足）。 */
    fun weakPriorActive(now: Long): Boolean {
        if (lastEmptiedAt > 0L && now - lastEmptiedAt < WEAK_PRIOR_DAYS * DAY_MS) return true
        if (lastEmptiedAt > now) return true // 时钟回拨：按保守侧处理（当弱先验）
        return totalEffective(now) < STABLE_MIN_SAMPLES
    }

    /** 某类是否算"稳定偏好"（份额 ≥60% 且连续 ≥2 周；份额跌破即不算）。 */
    fun isStableKind(kind: String, now: Long): Boolean {
        reevaluateStableShares(now)
        val since = stableSince[kind] ?: return false
        return now - since >= STABLE_CONFIRM_DAYS * DAY_MS
    }

    /**
     * 勘验出口：**只出结论，不出明细**。全是计数与布尔，没有 kind 列表、没有权重、没有哈希。
     */
    fun conclusion(now: Long): String = buildString {
        val expired = shortTerm.count { it.atMs < now - ttlDays * DAY_MS }
        append("画像结论：短期层在档 ${shortTerm.size} 条")
        append("（TTL=${ttlDays}天，已过期待清=$expired）")
        append("；稳定层 ${stable.size} 类，证据总量=${"%.1f".format(totalEffective(now))}")
        append("；弱先验=${if (weakPriorActive(now)) "是" else "否"}")
        append("；稳定偏好 ${stableSince.size} 类")
    }

    // ── 内部数学 ────────────────────────────────────────────────────────────

    private fun effectiveOf(kind: String, now: Long): Double {
        val b = stable[kind] ?: return 0.0
        val ageDays = (now - b.atMs).coerceAtLeast(0L).toDouble() / DAY_MS
        return b.base * 0.5.pow(ageDays / HALF_LIFE_DAYS)
    }

    private fun totalEffective(now: Long): Double = stable.keys.sumOf { effectiveOf(it, now) }

    /** 复评「连续 2 周 ≥60%」：份额达标即起算（不重置），跌破即撤销。 */
    private fun reevaluateStableShares(now: Long) {
        val total = totalEffective(now)
        if (total < STABLE_MIN_SAMPLES) { // 证据不足时谈不上稳定偏好
            stableSince.clear()
            return
        }
        for (kind in stable.keys.toList()) {
            val share = effectiveOf(kind, now) / total
            if (share >= STABLE_SHARE) {
                if (stableSince[kind] == null) stableSince[kind] = now
            } else {
                stableSince.remove(kind)
            }
        }
    }

    // ── 序列化（文件里同样没有原文：键/时刻/权重/哈希） ─────────────────────

    fun toJson(): JSONObject = JSONObject()
        .put("v", VERSION)
        .put("ttl", ttlDays)
        .put("short", JSONArray().also { a -> shortTerm.forEach { a.put(it.toJson()) } })
        .put("stable", JSONObject().also { o ->
            stable.forEach { (k, b) -> o.put(k, JSONObject().put("b", b.base).put("at", b.atMs)) }
        })
        .put("ssince", JSONObject().also { o -> stableSince.forEach { (k, at) -> o.put(k, at) } })
        .put("emptied", lastEmptiedAt)

    /** 坏行/缺键 ⇒ null（调用方丢弃换默认，不抛）。 */
    fun loadFrom(o: JSONObject): Boolean {
        val ttl = o.optInt("ttl", DEFAULT_SHORT_TERM_DAYS)
        ttlDays = ttl.coerceIn(MIN_TTL_DAYS, MAX_TTL_DAYS)
        shortTerm.clear(); stable.clear(); stableSince.clear()
        runCatching {
            val arr = o.optJSONArray("short") ?: JSONArray()
            for (i in 0 until arr.length()) {
                ShortTermEntry.parse(arr.getJSONObject(i))?.let { shortTerm.add(it) }
            }
            val st = o.optJSONObject("stable") ?: JSONObject()
            st.keys().forEach { k ->
                val b = st.optJSONObject(k) ?: return@forEach
                val base = b.optDouble("b", 0.0)
                val at = b.optLong("at", 0L)
                if (base > 0.0 && at > 0L) stable[k] = StableBucket(base, at)
            }
            val ss = o.optJSONObject("ssince") ?: JSONObject()
            ss.keys().forEach { k -> val at = ss.optLong(k, 0L); if (at > 0L) stableSince[k] = at }
        }.onFailure { return false }
        lastEmptiedAt = o.optLong("emptied", 0L)
        return true
    }

    companion object {
        /** 行格式版本（改形状才 +1）。 */
        const val VERSION = 1

        /** 短期层 TTL 默认 14 天；可配 7–15。 */
        const val DEFAULT_SHORT_TERM_DAYS = 14
        const val MIN_TTL_DAYS = 7
        const val MAX_TTL_DAYS = 15

        /** 稳定层半衰期 30 天（台账 173fy）。 */
        const val HALF_LIFE_DAYS = 30L

        /** 弱先验窗口：清短期层后第一周。 */
        const val WEAK_PRIOR_DAYS = 7L

        /** 样本 ≥20 才回显（低于即弱先验）。 */
        const val STABLE_MIN_SAMPLES = 20

        /** 稳定偏好份额线（≥60%）。 */
        const val STABLE_SHARE = 0.6

        /** 连续 2 周才算稳定偏好。 */
        const val STABLE_CONFIRM_DAYS = 14L

        /** 稳定层衰减地板（低于即整类清出，防僵尸桶）。 */
        const val STABLE_FLOOR = 0.05

        /** 滚动窗口 200 条（台账 173fy）。 */
        const val ROLLING_CAP = 200

        /** 模型自评权重系数（行为信号 ×5 于模型自评 ⇒ 自评 ×0.2）。 */
        const val MODEL_FACTOR = 0.2

        const val MAX_WEIGHT = 100

        const val DAY_MS = 24L * 60L * 60L * 1000L

        /** 机器键形状：小写字母/数字/下划线，≤64。 */
        val KIND_SHAPE = Regex("[a-z0-9_]{1,64}")

        /** 文本哈希形状：8–64 位十六进制（只收哈希，不收原文）。 */
        val SHA_SHAPE = Regex("[0-9a-f]{8,64}")

        /** 本地时区的"哪一天"（与御案日额同一把尺）。 */
        fun dayOf(now: Long): java.time.LocalDate =
            Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()
    }
}
