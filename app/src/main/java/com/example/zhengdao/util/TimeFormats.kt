package com.example.zhengdao.util

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 时间戳格式化的**唯一真源**（B-补-24，2026-10-16）。
 *
 * 为什么要有它：全仓原先有 10 处 `SimpleDateFormat("…", Locale).format(Date(ts))`，
 * 每次调用都新建一个 `SimpleDateFormat` —— 它一建就要解析图案、建 `Calendar`、
 * 查 `Locale` 数据，而这些调用点大多在**日志/时间戳热路径**（运行日志每次落盘一行、
 * 会话存档每次渲染一行）上，纯属白扔。更麻烦的是 `SimpleDateFormat` **不是线程安全的**：
 * 谁哪天把它提成 `val` 复用，就会踩「两个线程同时 format ⇒ 结果错乱/抛异常」这个经典坑。
 *
 * 改法：`java.time` 的 `DateTimeFormatter` **不可变、线程安全**，可以在 `object` 里
 * 建一次、处处复用；`Instant.ofEpochMilli` 也省掉一个 `Date` 对象。`minSdk = 36`
 * ⇒ `java.time` 是平台自带，不需要脱糖。
 *
 * 语义必须与旧写法**逐字一致**（旧写法用的是默认时区）：
 * 这里所有 formatter 都显式 `withZone(系统默认时区)`，输出与 `SimpleDateFormat` 相同。
 * 时区/默认 Locale 在进程启动后固定 —— 与旧写法「每次取当时值」的差别只在
 * 「运行中改系统时区」这一种情形，此时旧写法会立刻跟着变、这里要等进程重启；
 * 应用内所有时间戳都出自同一进程，保持一致反而更对（运行日志里不会出现半程换时区）。
 */
object TimeFormats {

    /** 进程启动时取一次系统默认时区（见 KDoc 里的取舍说明）。 */
    private val zone: ZoneId = ZoneId.systemDefault()

    /** `MM-dd HH:mm:ss` —— 运行日志每行前缀、保活归档的时间列。 */
    private val logStampFmt: DateTimeFormatter =
        DateTimeFormatter.ofPattern("MM-dd HH:mm:ss", Locale.US).withZone(zone)

    /** `yyyyMMdd-HHmmss` —— 归档/备份文件名（精确到秒）。 */
    private val fileStampFmt: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.US).withZone(zone)

    /** `yyyyMMdd-HHmm` —— 归档文件名（精确到分，用于同分钟内允许重名合并的场景）。 */
    private val fileStampMinuteFmt: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmm", Locale.US).withZone(zone)

    /** `yyyy-MM-dd HH:mm:ss` —— 导出物里的「导出时间」。 */
    private val isoSecondsFmt: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US).withZone(zone)

    /** `yyyy-MM-dd HH:mm` —— 知识库里的中文习惯时间（沿用旧写法的 `Locale.CHINA`）。 */
    private val minuteStampFmt: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.CHINA).withZone(zone)

    /** `HH:mm:ss.SSS` —— IME 事件日志（要毫秒）。 */
    private val clockMillisFmt: DateTimeFormatter =
        DateTimeFormatter.ofPattern("HH:mm:ss.SSS", Locale.US).withZone(zone)

    /** `HH:mm` —— 会话列表里的「今天这条是几点」。数字型图案，locale 只影响数字字形。 */
    private val hourMinuteFmt: DateTimeFormatter =
        DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault()).withZone(zone)

    /** `MM-dd` —— 「哪一天」（抱朴计划面板的组头：昨天的写「昨天 22:10」，更早的写 `10-18`）。 */
    private val monthDayFmt: DateTimeFormatter =
        DateTimeFormatter.ofPattern("MM-dd", Locale.US).withZone(zone)

    private fun at(millis: Long): Instant = Instant.ofEpochMilli(millis)

    fun logStamp(millis: Long): String = logStampFmt.format(at(millis))

    fun fileStamp(millis: Long = System.currentTimeMillis()): String = fileStampFmt.format(at(millis))

    fun fileStampMinute(millis: Long = System.currentTimeMillis()): String =
        fileStampMinuteFmt.format(at(millis))

    fun isoSeconds(millis: Long = System.currentTimeMillis()): String =
        isoSecondsFmt.format(at(millis))

    fun minuteStamp(millis: Long = System.currentTimeMillis()): String =
        minuteStampFmt.format(at(millis))

    fun clockMillis(millis: Long = System.currentTimeMillis()): String =
        clockMillisFmt.format(at(millis))

    fun hourMinute(millis: Long): String = hourMinuteFmt.format(at(millis))

    /** `MM-dd`（第一百零六批：抱朴计划面板组头用）。 */
    fun monthDay(millis: Long): String = monthDayFmt.format(at(millis))
}
