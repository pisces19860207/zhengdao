// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 798 段 A · 90 天复核闸。以 asOf 为"复核基准日"：距今天≥90 天自动标待复核。
// 伪造一个旧日期即可触发（测试判据⑤）；只产状态，不改条目本身（只增不改）。
package com.example.zhengdao.baopu.textbook

import java.time.LocalDate
import java.time.temporal.ChronoUnit

internal enum class ReviewStatus { 正常, 待复核 }

internal data class ReviewView(val entry: TextbookEntry, val status: ReviewStatus, val ageDays: Long)

internal object ReviewExpiry {
    const val REVIEW_DAYS: Long = 90

    fun of(entry: TextbookEntry, today: LocalDate): ReviewView {
        val age = ChronoUnit.DAYS.between(LocalDate.parse(entry.asOf), today)
        return ReviewView(entry, if (age >= REVIEW_DAYS) ReviewStatus.待复核 else ReviewStatus.正常, age)
    }

    fun due(entries: Collection<TextbookEntry>, today: LocalDate): List<ReviewView> =
        entries.map { of(it, today) }.filter { it.status == ReviewStatus.待复核 }
}
