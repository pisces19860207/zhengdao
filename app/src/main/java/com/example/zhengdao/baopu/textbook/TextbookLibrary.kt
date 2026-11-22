// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 798 段 A 的唯一对外命令面（internal）：一条命令投一个目录。
// 段 B 的注入、段 C 的改参数都不在这里，也不许从这里转发。
package com.example.zhengdao.baopu.textbook

import java.io.File
import java.time.LocalDate

internal object TextbookLibrary {
    /** 一条命令：把 dropDir 里所有 *.json / *.jsonl 投进 libraryDir。 */
    fun ingest(dropDir: File, libraryDir: File): IngestReport = AppendOnlyStore(libraryDir).ingest(dropDir)

    /** 90 天未复核的条目（只读查询，不改条目）。 */
    fun reviewDue(libraryDir: File, today: LocalDate): List<ReviewView> =
        AppendOnlyStore(libraryDir).due(today)

    /** 把待复核读数落成派生文件，便于人工核对。 */
    fun writeDueReport(libraryDir: File, today: LocalDate): File =
        AppendOnlyStore(libraryDir).writeDueReport(today)
}
