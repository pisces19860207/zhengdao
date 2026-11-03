// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第三百八十八单 · 离场值守的**环境暂停规则**（388 派工单：电量<20%／过热／无网 ⇒
// 暂停＋普通通知）。纯规则、零 IO：读数由服务每拍喂进来，本文件只算"进哪几格、退哪几格"。
//
// 三条纪律：
// 1. **读不到的数不许装健康**：电量/温度读不到（null）⇒ 不据此判"低电/过热"（不假装出事），
//    也**不许据 null 复工**（已经暂停的那格，读不到数就一直停着——fail-closed 的复工侧）。
//    网络探针相反：探针本身异常（没有网络服务／读不到）按"无网"处理，照斥候的
//    fail-closed 口径（HealthScheduler「读不到＝当没充电」同一条法）。
// 2. **迟滞带**：电量 20% 停、25% 才复工；温度 43.0℃ 停、40.0℃ 以下才复工。
//    没有迟滞带，电量在 20% 边缘晃一下就是一条暂停/复工的振荡账（375 批刚立过振荡判据）。
// 3. **充电不豁免**：派工单字面是"电量<20% ⇒ 暂停"，没有"插着电除外"的例外；
//    本文件不自造口径，字面执行。
package com.example.zhengdao.unattended

/** 环境暂停的理由（闭集三格；派工单点名的三样，一个不自造）。 */
internal enum class UnattendedEnvReason(val wire: String, val cn: String) {
    LOW_BATTERY("low_battery", "电量低"),
    OVERHEAT("overheat", "机身过热"),
    NO_NETWORK("no_network", "无网络"),
    ;

    companion object {
        /** 认不出就 null（账本自造值不静默当别的格，照本仓闭集解析纪律）。 */
        fun ofWire(wire: String): UnattendedEnvReason? = entries.firstOrNull { it.wire == wire }
    }
}

/** 阈值（改数＝改制度，只许在这里改，不许调用处各写一把）。 */
internal object UnattendedEnvLimits {

    /** 电量低于这个百分数 ⇒ 暂停（388 派工单：电量<20%）。 */
    const val PAUSE_BATTERY_PCT = 20

    /** 电量回到这个百分数及以上 ⇒ 复工（迟滞带上沿，与下沿隔 5 个点防振荡）。 */
    const val RESUME_BATTERY_PCT = 25

    /** 电池温度达到这个值（摄氏×10）⇒ 暂停（43.0℃：电池高温告警的常见下沿）。 */
    const val PAUSE_TEMP_TENTHS = 430

    /** 电池温度回落到这个值及以下（摄氏×10）⇒ 复工（迟滞带 3℃）。 */
    const val RESUME_TEMP_TENTHS = 400

    /** 空调区间之外一律维持现状（电量/温度读数不许为负数；负数按"读不到"处理）。 */
    fun saneBatteryPct(pct: Int?): Boolean = pct != null && pct in 0..100

    fun saneTempTenths(t: Int?): Boolean = t != null && t in 0..800
}

/**
 * 一拍环境读数（服务从 BatteryManager／ConnectivityManager 采来；本层不摸 Android）。
 *
 * @param batteryPct 电量百分数 0..100；读不到或越界 ⇒ null
 * @param batteryTempTenths 电池温度（摄氏×10）；读不到或越界 ⇒ null
 * @param networkOk 现在能不能用网（探针异常 ⇒ false，fail-closed）
 */
internal data class UnattendedEnvSample(
    val batteryPct: Int? = null,
    val batteryTempTenths: Int? = null,
    val networkOk: Boolean = true,
)

/**
 * 一拍迟滞转移：**当前在停的格**加上这一拍的读数 ⇒ 下一拍该停哪几格。
 *
 * 进入侧（读到坏数就进）与退出侧（读到足够好的数才退；读不到不许退）都只看这一拍的
 * 读数与当前状态，所以同一段读数序列回放 ⇒ 同一段暂停/复工账（可回放，不许随机）。
 */
internal fun nextPauseReasons(
    sample: UnattendedEnvSample,
    active: Set<UnattendedEnvReason>,
): Set<UnattendedEnvReason> = buildSet {
    val batteryOk = UnattendedEnvLimits.saneBatteryPct(sample.batteryPct)
    val tempOk = UnattendedEnvLimits.saneTempTenths(sample.batteryTempTenths)
    if (batteryOk && sample.batteryPct!! < UnattendedEnvLimits.PAUSE_BATTERY_PCT) {
        add(UnattendedEnvReason.LOW_BATTERY)
    }
    if (tempOk && sample.batteryTempTenths!! >= UnattendedEnvLimits.PAUSE_TEMP_TENTHS) {
        add(UnattendedEnvReason.OVERHEAT)
    }
    if (!sample.networkOk) add(UnattendedEnvReason.NO_NETWORK)
    // 维持侧：已在停的格，读数不够好（或读不到）就继续停——不许据 null 复工。
    // 无网一格没有阈值，进与出同一把尺（!networkOk），不需要迟滞。
    if (UnattendedEnvReason.LOW_BATTERY in active &&
        (!batteryOk || sample.batteryPct!! < UnattendedEnvLimits.RESUME_BATTERY_PCT)
    ) {
        add(UnattendedEnvReason.LOW_BATTERY)
    }
    if (UnattendedEnvReason.OVERHEAT in active &&
        (!tempOk || sample.batteryTempTenths!! > UnattendedEnvLimits.RESUME_TEMP_TENTHS)
    ) {
        add(UnattendedEnvReason.OVERHEAT)
    }
}
