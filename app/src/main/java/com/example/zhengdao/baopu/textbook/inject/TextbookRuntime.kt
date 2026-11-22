// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第七百九十八批 · 段 B · 生产装配（全 App 唯一一处，参照 778 ReadingPorts 房型）：
// 只读段 A 教材库的 entries.jsonl，按本轮上下文命中后走 738 管道注入。
// 默认未装配（provider 全 null）⇒ 空块 ⇒ 与没有段 B 逐字节一致；detach() 即回滚。
package com.example.zhengdao.baopu.textbook.inject

import com.example.zhengdao.baopu.calibration.CalibrationWiring
import com.example.zhengdao.baopu.textbook.AppendOnlyStore
import com.example.zhengdao.baopu.textbook.Domain
import java.io.File

/** 教材库在 workspace 下的相对落点（段 B 约定；段 A 只 ingest，不规定生产落点）。 */
internal object TextbookRuntime {

    const val LIBRARY_REL = "教材库"

    /** 一次装配：② 教材快照只读库文件；③ 命中关键词由 738 本轮 matchedZones 传入；① 接进管道尾部。 */
    fun install(workspace: () -> File?, domains: () -> List<Domain> = { emptyList<Domain>() }) {
        TextbookTeaching.entriesProvider = entryLoader@ {
            val root = workspace() ?: return@entryLoader emptyList()
            val lib = File(root, LIBRARY_REL)
            if (!lib.isDirectory) emptyList() else runCatching { AppendOnlyStore(lib).all() }.getOrDefault(emptyList())
        }
        TextbookTeaching.domainsProvider = domains
        CalibrationWiring.textbookNotesProvider = { zones -> TextbookTeaching.notes(zones) }
    }

    /** 回滚位：与没有段 B 逐字节一致。 */
    fun detach() {
        CalibrationWiring.textbookNotesProvider = null
        TextbookTeaching.resetForTest()
    }
}
