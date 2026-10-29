// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百八十批 · 冷启动限频（台账 173ge：用户 2026-10-28「冷启动狂跑耗电」负例）。
//
// 三条闸（顺序固定，谁在前谁先拦）：
//   ① **暂停闸**：一键暂停采集（173ge「一键暂停画像采集」）——暂停即全拒，连计数都不涨；
//   ② **可见闸**：只在**屏幕亮且前台**才算（锁屏/后台的采样一律不算数——不是"少记"，
//      是连喂都不收，避免"背地里攒账"）；
//   ③ **日额闸**：装机前 [COLD_START_DAYS] 天每天 ≤[DAILY_CAP] 条，**超限即停**
//      （第 51 条被拒，不是挤到下一天）——过冷启动期后不再设日额（可见闸仍在）。
//
// 重算是另一件事：`allowRecompute` 只认**充电**（用电池不跑重活，173ge 原话），
// 与限频状态无关——不充电时一天一条没喂也照样不重算。
//
// 零 Android / 零时钟：`now` 由调用方传入；"哪一天"用本地时区（与御案日额同一把尺）。
// 状态可序列化（org.json），持久化由 [UserProfileBlackbox] 做。
package com.example.zhengdao.baopu.profile

import org.json.JSONObject

/** 喂样被拒的原因（机器键；只进日志与结论，不给用户看）。 */
internal enum class FeedDeny(val wire: String) {
    PAUSED("paused"),
    NOT_VISIBLE("not_visible"),
    DAY_CAP("day_cap"),
}

/** 一次喂样的裁决。 */
internal sealed class FeedVerdict {
    /** 记上了（含当日第几条）。 */
    data class Accept(val dayCount: Int) : FeedVerdict()

    /** 被拒（原因见 [FeedDeny]；超限即停的那条就在这里）。 */
    data class Deny(val reason: FeedDeny) : FeedVerdict()
}

internal class ColdStartThrottle(
    /** 冷启动期天数（前 14 天限频；写死在本批，不开放配置）。 */
    private val coldStartDays: Int = COLD_START_DAYS,
    /** 冷启动期内的日额。 */
    private val dailyCap: Int = DAILY_CAP,
) {
    /** 装机日（本地时区 yyyy-MM-dd；第一条**被收下**的采样那天起算——锁屏后台的拦截不算装机）。 */
    var installDay: String? = null
        private set

    /** 各本地日已收条数（只留近 [DAY_KEEP] 天，防无界长）。 */
    private val dayCounts = LinkedHashMap<String, Int>()

    /** 一键暂停（true ⇒ 全拒）。 */
    var paused: Boolean = false
        private set

    fun setPaused(p: Boolean) {
        paused = p
    }

    /**
     * 喂样裁决：暂停/不可见/超日额 ⇒ [FeedVerdict.Deny]（**超限即停**，不排队不挤占）；
     * 收下则当日计数 +1。冷启动期外日额闸不生效（可见闸、暂停闸仍在）。
     */
    fun feed(now: Long, screenOn: Boolean, foreground: Boolean): FeedVerdict {
        if (paused) return FeedVerdict.Deny(FeedDeny.PAUSED)
        if (!screenOn || !foreground) return FeedVerdict.Deny(FeedDeny.NOT_VISIBLE)
        val day = TwoLayerProfile.dayOf(now).toString()
        if (installDay == null) installDay = day
        if (inColdStart(now)) {
            val count = dayCounts[day] ?: 0
            if (count >= dailyCap) return FeedVerdict.Deny(FeedDeny.DAY_CAP)
            dayCounts[day] = count + 1
            trimDays(day)
            return FeedVerdict.Accept(count + 1)
        }
        // 冷启动期外：不设日额，但计数照记（勘验结论要用）
        dayCounts[day] = (dayCounts[day] ?: 0) + 1
        trimDays(day)
        return FeedVerdict.Accept(dayCounts[day] ?: 1)
    }

    /** 今天（本地日）已收几条——勘验结论用。 */
    fun todayCount(now: Long): Int = dayCounts[TwoLayerProfile.dayOf(now).toString()] ?: 0

    /** 今天还收不收得出（结论用语；不算账不写状态）。 */
    fun coldStartActive(now: Long): Boolean = inColdStart(now)

    /** 重算闸：**只认充电**。 */
    fun allowRecompute(charging: Boolean): Boolean = charging

    /** 冷启动期：装机日起 [coldStartDays] 天内（第 0..13 天受限，第 14 天起放开）。 */
    private fun inColdStart(now: Long): Boolean {
        val install = installDay ?: return false
        val installDate = runCatching { java.time.LocalDate.parse(install) }.getOrNull() ?: return false
        val today = TwoLayerProfile.dayOf(now)
        return today.isBefore(installDate.plusDays(coldStartDays.toLong()))
    }

    /** 只留近 N 天的计数（防跨年无界长）。 */
    private fun trimDays(today: String) {
        while (dayCounts.size > DAY_KEEP) {
            val oldest = dayCounts.keys.firstOrNull { it != today } ?: break
            dayCounts.remove(oldest)
        }
    }

    // ── 序列化 ──────────────────────────────────────────────────────────────

    fun toJson(): JSONObject = JSONObject()
        .put("v", VERSION)
        .put("install", installDay ?: JSONObject.NULL)
        .put("paused", paused)
        .put("days", JSONObject().also { o -> dayCounts.forEach { (d, c) -> o.put(d, c) } })

    fun loadFrom(o: JSONObject) {
        installDay = if (o.isNull("install")) null else o.optString("install").ifEmpty { null }
        paused = o.optBoolean("paused", false)
        dayCounts.clear()
        runCatching {
            val days = o.optJSONObject("days") ?: JSONObject()
            days.keys().forEach { d -> val c = days.optInt(d, 0); if (c > 0) dayCounts[d] = c }
        }
    }

    companion object {
        const val VERSION = 1

        /** 冷启动期 14 天（台账 173ge「前 14 天每天最多采 50 条」）。 */
        const val COLD_START_DAYS = 14

        /** 冷启动期日额 50 条。 */
        const val DAILY_CAP = 50

        /** 计数表最多留几天的账。 */
        const val DAY_KEEP = 30
    }
}
