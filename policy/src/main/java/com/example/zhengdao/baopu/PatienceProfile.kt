// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百三十八批 · 使用者耐心画像（`PatienceProfile`）与熟悉度（`Familiarity`）。
//
// 这个文件只存**统计量**，不存任何一句话：失败/纠正/爱称/攻击的**次数**、是否偏好短答、
// 是否偏好 emoji。没有 String 字段（门禁 `HarmonyInvisibilityTest` 用反射钉死）。
//
// 三道闸（派工单 §3.7.3）：
//   ① 样本闸：观察不足 5 次，`learnedAdjustment` 返回 null，个人化阈值完全不生效；
//   ② 漂移闸：30 天半衰期，置信度衰减到 0.30 以下自动回默认；
//   ③ 保守闸：阈值只能在默认值 ±1 档内浮动，越界夹回边界并可由 `outOfRangeFields()` 记 `familiarity_bad`。
//   ④ 序关系不变量：`loopBudget > patienceBudget`、`angerAt > patienceBudget`；不满足时按额度 +1 生效，
//      用户原值不动，由 `outOfRangeFields()` 报出来。
//
// 手工设置与「学出来的」分开：用户自己改的阈值走 `clamped()/effective()`，立即生效；
// 样本闸只管 `learnedAdjustment()`。两者都不改事实标准、不改权限（本文件没有任何权限 API）。
//
// 零 Android / 零 IO / 零时钟：`now` 由调用方传入，缺省 0 表示「不做时间判断」。
// 第二百九十三批（2026-10-31）：本文件自 :app 搬进 :policy（宰相的白名单匣）。
// 一起搬进来的还有可见性——Kotlin 的 internal 以编译单元为界，:app 看不见 :policy 的
// internal，故顶层声明由 internal 放宽为公开。**只放宽可见性，判据一个字没动**
// （搬前搬后的逐行对照与放宽清单见交付报告）。
package com.example.zhengdao.baopu

import kotlin.math.pow

/** 使用者耐心画像：全是计数/布尔，**没有一句话**。 */
data class PatienceProfile(
    val patienceBudget: Int = DEFAULT_PATIENCE_BUDGET,
    val angerAt: Int = DEFAULT_ANGER_AT,
    val loopBudget: Int = DEFAULT_LOOP_BUDGET,
    val askTolerance: Int = DEFAULT_ASK_TOLERANCE,
    val prefersEmoji: Boolean = true,
    val prefersShort: Boolean = true,
    val enabled: Boolean = true,
    val observations: Int = 0,
    val updatedAt: Long = 0L,
) {
    /**
     * 保守闸：每项只能在默认值 ±1 档内；`loopBudget` 还要比 `patienceBudget` 至少多 1。
     * `angerAt` 另要晚于 `patienceBudget`（换挡点≠发火点）：不满足时按 `pb + 1` 生效，
     * **用户原值不动**——原值仍留在对象里，由 `outOfRangeFields()` 报出来。
     */
    fun clamped(): PatienceProfile {
        val pb = patienceBudget.coerceIn(MIN_PATIENCE_BUDGET, MAX_PATIENCE_BUDGET)
        val anger = angerAt.coerceIn(MIN_ANGER_AT, MAX_ANGER_AT)
        return copy(
            patienceBudget = pb,
            angerAt = if (angerTooEarly(pb, anger)) pb + 1 else anger,
            loopBudget = loopBudget.coerceIn(maxOf(pb + 1, MIN_LOOP_BUDGET), MAX_LOOP_BUDGET),
            askTolerance = askTolerance.coerceIn(MIN_ASK_TOLERANCE, MAX_ASK_TOLERANCE),
        )
    }

    /** 越界字段清单：非空 ⇒ 调用方记一条 `familiarity_bad`（只记字段名，不记数值）。 */
    fun outOfRangeFields(): List<String> = buildList {
        if (patienceBudget !in MIN_PATIENCE_BUDGET..MAX_PATIENCE_BUDGET) add("patienceBudget")
        if (angerAt !in MIN_ANGER_AT..MAX_ANGER_AT || angerTooEarly(patienceBudget, angerAt)) add("angerAt")
        if (loopBudget !in MIN_LOOP_BUDGET..MAX_LOOP_BUDGET || loopBudget <= patienceBudget) add("loopBudget")
        if (askTolerance !in MIN_ASK_TOLERANCE..MAX_ASK_TOLERANCE) add("askTolerance")
    }

    /**
     * 换挡点不晚于发火点 ⇒ 两档并成一档，语义打架。
     * 钳位与留痕必须共用这一条；两处各写一套就会出现「夹了不报」或「报了没夹」。
     */
    private fun angerTooEarly(patienceBudget: Int, angerAt: Int): Boolean = angerAt <= patienceBudget

    /** 样本量换算的置信度（已含半衰期漂移）。 */
    fun familiarity(now: Long = 0L): Familiarity {
        val days = if (updatedAt <= 0L || now <= updatedAt) 0.0
        else (now - updatedAt).toDouble() / DAY_MILLIS
        return Familiarity.of(observations, days)
    }

    /**
     * 学出来的调整：样本不足 / 漂移衰减后置信度不足 ⇒ null（不使用个人化）。
     * 0.30–0.70 之间留一次余量（各阈值提前一档）；≥0.70 用画像值。结果仍过保守闸。
     */
    fun learnedAdjustment(now: Long = 0L): PatienceProfile? {
        if (!enabled) return null
        val f = familiarity(now)
        if (f.confidence < CONFIDENCE_MIN_PERSONAL) return null
        val base = clamped()
        if (f.confidence >= CONFIDENCE_FULL_PERSONAL) return base
        return base.copy(
            patienceBudget = base.patienceBudget - 1,
            angerAt = base.angerAt - 1,
            loopBudget = base.loopBudget - 1,
        ).clamped()
    }

    /** 调和器实际读到的阈值：关机 ⇒ 默认；否则学出来的优先，其次手工值（都过保守闸）。 */
    fun effective(now: Long = 0L): PatienceProfile =
        if (!enabled) PatienceProfile(enabled = false)
        else (learnedAdjustment(now) ?: clamped())

    /** 记一次观察（只在失败时调用；关机不写盘、不计数）。 */
    fun recordObservation(now: Long = 0L): PatienceProfile =
        if (!enabled) this else copy(observations = observations + 1, updatedAt = now)

    /** 设置页「清空」：统计与阈值回默认，开关保持原样。 */
    fun cleared(): PatienceProfile = PatienceProfile(enabled = enabled)

    companion object {
        const val DEFAULT_PATIENCE_BUDGET = 2
        const val DEFAULT_ANGER_AT = 3
        const val DEFAULT_LOOP_BUDGET = 4
        const val DEFAULT_ASK_TOLERANCE = 2

        const val MIN_PATIENCE_BUDGET = 1
        const val MAX_PATIENCE_BUDGET = 3
        const val MIN_ANGER_AT = 2
        const val MAX_ANGER_AT = 4
        const val MIN_LOOP_BUDGET = 3
        const val MAX_LOOP_BUDGET = 5
        const val MIN_ASK_TOLERANCE = 1
        const val MAX_ASK_TOLERANCE = 3

        const val CONFIDENCE_MIN_PERSONAL = 0.30
        const val CONFIDENCE_FULL_PERSONAL = 0.70

        private const val DAY_MILLIS = 86_400_000.0
    }
}

/** 熟悉度：只由样本量与距上次观察的天数换算，不含任何内容。 */
data class Familiarity(
    val observations: Int,
    val confidence: Double,
    val halfLifeDays: Int = DEFAULT_HALF_LIFE_DAYS,
) {
    companion object {
        const val DEFAULT_HALF_LIFE_DAYS = 30

        /**
         * 分段线性：<5 次不超过 0.24；5–20 次落在 0.30–0.70；>20 次从 0.70 往上。
         * 再乘半衰期衰减（默认 30 天减半）。这是「熟悉就是判据」的度量，不是人格评价。
         */
        fun of(
            observations: Int,
            daysSinceLast: Double,
            halfLifeDays: Int = DEFAULT_HALF_LIFE_DAYS,
        ): Familiarity {
            val n = observations.coerceAtLeast(0)
            val base = when {
                n <= 0 -> 0.0
                n < 5 -> n * 0.06
                n <= 20 -> 0.30 + (n - 5) / 15.0 * 0.40
                else -> 0.70 + (n - 20) / 30.0 * 0.30
            }.coerceIn(0.0, 1.0)
            val hl = if (halfLifeDays <= 0) DEFAULT_HALF_LIFE_DAYS else halfLifeDays
            val decay = 0.5.pow(daysSinceLast.coerceAtLeast(0.0) / hl)
            return Familiarity(n, (base * decay).coerceIn(0.0, 1.0), hl)
        }
    }
}

/**
 * 画像的落盘格式（纯字符串，零 IO）。
 *
 * 只有数字与布尔；没有版本以外的自由文本。坏数据回默认，
 * 不抛异常、不把上一份画像「猜」回来。
 */
object PatienceProfileCodec {
    private const val VERSION = "p1"

    fun encode(profile: PatienceProfile): String = listOf(
        VERSION,
        "pb=${profile.patienceBudget}",
        "ag=${profile.angerAt}",
        "lb=${profile.loopBudget}",
        "at=${profile.askTolerance}",
        "pe=${if (profile.prefersEmoji) 1 else 0}",
        "ps=${if (profile.prefersShort) 1 else 0}",
        "en=${if (profile.enabled) 1 else 0}",
        "obs=${profile.observations}",
        "upd=${profile.updatedAt}",
    ).joinToString("\n")

    fun decode(raw: String?): PatienceProfile {
        if (raw.isNullOrBlank()) return PatienceProfile()
        val lines = raw.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lines.firstOrNull() != VERSION) return PatienceProfile()
        val map = lines.drop(1).mapNotNull { line ->
            val i = line.indexOf('=')
            if (i <= 0) null else line.substring(0, i) to line.substring(i + 1)
        }.toMap()

        fun int(key: String, def: Int): Int? {
            val value = map[key] ?: return def
            return value.toIntOrNull()
        }

        fun bool(key: String, def: Boolean): Boolean? = when (map[key]) {
            null -> def
            "1" -> true
            "0" -> false
            else -> null
        }

        val pb = int("pb", DEFAULT_PATIENCE_BUDGET) ?: return PatienceProfile()
        val ag = int("ag", DEFAULT_ANGER_AT) ?: return PatienceProfile()
        val lb = int("lb", DEFAULT_LOOP_BUDGET) ?: return PatienceProfile()
        val at = int("at", DEFAULT_ASK_TOLERANCE) ?: return PatienceProfile()
        val pe = bool("pe", true) ?: return PatienceProfile()
        val ps = bool("ps", true) ?: return PatienceProfile()
        val en = bool("en", true) ?: return PatienceProfile()
        val obs = int("obs", 0) ?: return PatienceProfile()
        val upd = map["upd"]?.toLongOrNull() ?: 0L

        return PatienceProfile(
            patienceBudget = pb,
            angerAt = ag,
            loopBudget = lb,
            askTolerance = at,
            prefersEmoji = pe,
            prefersShort = ps,
            enabled = en,
            observations = obs,
            updatedAt = upd,
        )
    }

    private const val DEFAULT_PATIENCE_BUDGET = PatienceProfile.DEFAULT_PATIENCE_BUDGET
    private const val DEFAULT_ANGER_AT = PatienceProfile.DEFAULT_ANGER_AT
    private const val DEFAULT_LOOP_BUDGET = PatienceProfile.DEFAULT_LOOP_BUDGET
    private const val DEFAULT_ASK_TOLERANCE = PatienceProfile.DEFAULT_ASK_TOLERANCE
}
