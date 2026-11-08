// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第四百六十七批 · 租约的**账内建议**（自进化只出"默认值建议"，不许自动生成永久租约）。
//
// 三条红线：
//   1. 本文件**不持有 [LeaseStore]**，一个 `grant` 都不调——统计只产出一句建议，改不改由人；
//   2. 样本不足就说"样本不足"，**不编数字**（分母 0 回 no_sample 的同一条纪律）；
//   3. 只在体检行被请求时算一次（定点 U5：不自动跑），窗口近 [WINDOW_DAYS] 天，只读本机账。
package com.example.zhengdao.baopu.authz

import java.util.concurrent.TimeUnit
import org.json.JSONObject

/** 一个类目的建议（一句话 ＋ 两个数）。 */
internal data class LeaseAdvice(
    val cls: String,
    val grants: Int,
    val avgUsed: Double,
    val avgHours: Double,
    val suggestCap: Int,
    val suggestTtlMs: Long,
)

internal object LeaseAdviceRules {

    /** 窗口（定点 U5：近 30 天）。 */
    const val WINDOW_DAYS = 30

    /** 样本下限：少于这么多次授予就说"样本不足"（不拿两条样本编一个默认值）。 */
    const val MIN_SAMPLE = 10

    private val DAY_MS = TimeUnit.DAYS.toMillis(1)

    /**
     * 算建议（读本机账；只读，不写任何租约）。
     *
     * 口径：窗口内每一类目的 `grant` 行 → 授予次数、平均用掉几次、平均活了多久。
     * 建议值只是"下一次默认值"的一个候选：次数取向上取整的均值（至少 1），
     * 时窗取均值的 1.5 倍（至少 1 小时），**两个都夹在天花板内**。
     */
    fun suggest(nowMs: Long): List<LeaseAdvice> {
        val from = nowMs - TimeUnit.DAYS.toMillis(WINDOW_DAYS.toLong())
        val grants = LeaseLedger.replay().filter { it.optString("kind") == LeaseKinds.GRANT && it.optLong("at") >= from }
        if (grants.isEmpty()) return emptyList()
        return grants.groupBy { it.optString("cls") }
            .map { (cls, rows) -> adviceOf(cls, rows, nowMs) }
            .filter { it.grants >= MIN_SAMPLE }
            .sortedByDescending { it.grants }
    }

    /** 落一行 `advice`（按需调；返回落了几行）。 */
    fun note(nowMs: Long = System.currentTimeMillis()): Int {
        val rows = suggest(nowMs)
        rows.forEach { a ->
            LeaseLedger.append(
                JSONObject()
                    .put("kind", LeaseKinds.ADVICE)
                    .put("id", "advice-${a.cls}")
                    .put("at", nowMs)
                    .put("cls", a.cls)
                    .put("grants", a.grants)
                    .put("cap", a.suggestCap)
                    .put("ttl", a.suggestTtlMs)
                    .put("result", line(a)),
            )
        }
        return rows.size
    }

    /** 一句人话（体检行/账上都用它，两处不会漂）。 */
    fun line(a: LeaseAdvice): String {
        val hours = (a.suggestTtlMs / TimeUnit.HOURS.toMillis(1)).coerceAtLeast(1L)
        return "近 ${WINDOW_DAYS} 天「${a.cls}」被授予 ${a.grants} 次，" +
            "平均用 ${"%.1f".format(a.avgUsed)} 次、平均 ${"%.1f".format(a.avgHours)} 小时 ⇒ " +
            "建议默认改成 ≤${a.suggestCap} 次/≤$hours 小时（现 ${LeaseLimits.DEFAULT.cap} 次/" +
            "${LeaseLimits.DEFAULT.ttlMs / TimeUnit.HOURS.toMillis(1)} 小时）"
    }

    private fun adviceOf(cls: String, rows: List<JSONObject>, nowMs: Long): LeaseAdvice {
        val n = rows.size
        val used = rows.map { it.optInt("used", 0) }.average()
        val hours = rows.map { ((nowMs - it.optLong("at", nowMs)) / 3_600_000.0).coerceAtLeast(0.0) }.average()
        val cap = (Math.ceil(used).toInt() + 1).coerceIn(1, LeaseLimits.MAX_CAP)
        val ttl = (hours * 1.5 * 3_600_000.0).toLong().coerceIn(3_600_000L, LeaseLimits.MAX_TTL_MS)
        return LeaseAdvice(cls, n, used, hours, cap, ttl)
    }
}
