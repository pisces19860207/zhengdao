// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：492 工单 B 段②「新增 index_browse(路径)；index_search 复用既有搜索」。本文件是
// 浏览面的**组合层**（房型照 [PhoneSearch]：Context 到这里为止、判据在纯函数、语句在
// [SearchSql]）：把 [PhoneIndex] 的账行读数折成「给模型看」的卡与目录；渲染函数是纯函数
// （JVM 直测），Context 侧只有三口（状态／单篇卡／目录清单）。
//
// ## 为什么没有「现读兜底」
// `index_browse` 是索引的**视图**，不是第二个 read_file：账上没有的路径如实说「未入索引」
// 并指去搜索卡扫描／read_file。现读兜底等于在浏览层再长一遍读文解码（「不许新写扫描器」
// 的隔壁就是「不许第二遍读文」），模型要全文本来就有 read_file。
package com.example.zhengdao.baopu.search

import android.content.Context
import com.example.zhengdao.core.PathGuard
import com.example.zhengdao.core.TimeFormats
import java.io.File

/** 「模型能不能看懂工作区」的组合层（读侧扫描／检索之外的第二张脸，仍是 [PhoneIndex] 一张库）。 */
internal object IndexBrowse {

    /** 目录浏览一次最多翻出的账行数（写死；浏览是「看懂结构」，不是全量导出）。 */
    const val DIR_CAP = 500

    // ── 读数 ────────────────────────────────────────────────────────────────

    /** 索引现状（「部分索引 N/M」的出处；库不在 ⇒ 一个数都没有）。 */
    data class Status(val dbExists: Boolean, val summaries: Int, val homeDocs: Int) {
        val partial: Boolean get() = dbExists && summaries < homeDocs

        /** 状态行（工单 B 判据「超限标『部分索引 N/M』」的唯一写法，渲染层不许另造）。 */
        fun line(): String = when {
            !dbExists -> "索引未建立（搜索卡「扫描本机」建好后可用）"
            partial -> "部分索引 $summaries/$homeDocs（已达摘要行数上限 ${DocSummaryRules.MAX_ROWS} 之外的差距都会在这里现形）"
            else -> "索引摘要 $summaries/$homeDocs"
        }
    }

    fun status(ctx: Context): Status {
        if (!PhoneSearch.dbExists(ctx)) return Status(false, 0, 0)
        val db = SearchDb(ctx)
        return try {
            val index = PhoneIndex(db)
            Status(true, index.summaryCount(), index.homeTextDocCount())
        } finally {
            runCatching { db.close() }
        }
    }

    /** 单篇卡：账行 ＋ 摘要（都在 ⇒ 完整卡；账行没有 ⇒ found=false，由渲染层说实话）。 */
    data class FileCard(
        val absPath: String,
        val relPath: String,
        val doc: DocCardRow?,
        val summary: DocSummaryRules.SummaryText?,
    )

    /** 单篇卡（absFile 不在任何有效根内 ⇒ relPath 空串、doc null）。 */
    fun fileCard(ctx: Context, absFile: File): FileCard {
        if (!PhoneSearch.dbExists(ctx)) return FileCard(absFile.absolutePath, "", null, null)
        val db = SearchDb(ctx)
        return try {
            val index = PhoneIndex(db)
            val hit = rootOf(index, absFile)
            if (hit == null) {
                FileCard(absFile.absolutePath, "", null, null)
            } else {
                val rel = SearchAdmission.relativePath(File(hit.absRoot), absFile) ?: ""
                val doc = if (rel.isEmpty()) null else index.docCard(hit.rootId, rel)
                FileCard(absFile.absolutePath, rel, doc, doc?.let { index.summaryOf(it.docId) })
            }
        } finally {
            runCatching { db.close() }
        }
    }

    /** 目录清单：直接子文件（带标题）＋ 子目录名单 ＋ 子树深处的计数。 */
    data class DirBrowse(
        val absDir: String,
        val relDir: String,
        val directFiles: List<DirDocRow>,
        val dirNames: List<String>,
        val deeperFiles: Int,
        val truncated: Boolean,
    )

    /** 目录清单（路径不在任何有效根内 ⇒ null，工具层回「不在索引范围」）。 */
    fun dirBrowse(ctx: Context, absDir: File, cap: Int = DIR_CAP): DirBrowse? {
        if (!PhoneSearch.dbExists(ctx)) return null
        val db = SearchDb(ctx)
        return try {
            val index = PhoneIndex(db)
            val hit = rootOf(index, absDir) ?: return null
            val rel = SearchAdmission.relativePath(File(hit.absRoot), absDir) ?: return null
            val rows = index.docsUnderDir(hit.rootId, likePattern(rel), cap)
            val p = partition(rel, rows)
            DirBrowse(
                absDir = absDir.absolutePath,
                relDir = rel,
                directFiles = p.directFiles,
                dirNames = p.dirNames,
                deeperFiles = p.deeperFiles,
                truncated = rows.size >= cap,
            )
        } finally {
            runCatching { db.close() }
        }
    }

    /** absFile 属于哪个有效根（嵌套根取**最长**的那个——更具体者说了算；没有 ⇒ null）。 */
    private fun rootOf(index: PhoneIndex, absFile: File): ActiveRoot? = index.activeRoots()
        .filter { PathGuard.isInside(File(it.absRoot), absFile) }
        .maxByOrNull { it.absRoot.length }

    // ── 纯函数（JVM 直测） ──────────────────────────────────────────────────

    /** LIKE 模式（[SearchSql.SELECT_DOCS_UNDER_DIR] 的 ESCAPE 口径）：`%`/`_`/`\` 转义；根目录给 `%`。 */
    fun likePattern(relDir: String): String {
        val d = relDir.trim('/')
        if (d.isEmpty()) return "%"
        val esc = d.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        return "$esc/%"
    }

    /** 分拣结果（[partition] 的出口）。 */
    data class Partition(val directFiles: List<DirDocRow>, val dirNames: List<String>, val deeperFiles: Int)

    /**
     * 把「目录（含子目录）下的账行」分拣成直接子文件／子目录名／更深处计数（**纯函数**）。
     * 行序就是 SQL 给的 relPath 序，这里不重排——渲染层保持库序（稳定、可断言）。
     */
    fun partition(relDir: String, rows: List<DirDocRow>): Partition {
        val base = relDir.trim('/')
        val prefix = if (base.isEmpty()) "" else "$base/"
        val direct = mutableListOf<DirDocRow>()
        val dirs = linkedSetOf<String>()
        var deeper = 0
        rows.forEach { r ->
            val rest = r.relPath.removePrefix(prefix)
            val slash = rest.indexOf('/')
            when {
                slash < 0 -> direct += r
                else -> {
                    dirs += rest.substring(0, slash)
                    deeper++
                }
            }
        }
        return Partition(direct.toList(), dirs.toList(), deeper)
    }

    /** 时间戳给人看的那一行（账行要有时刻，模型才知道摘要是几点的账；格式走 :core 的 TimeFormats）。 */
    private fun at(ms: Long): String = TimeFormats.logStamp(ms)

    /** 单篇卡渲染（**纯函数**）：账上没有／有账无摘要／完整卡三种都说实话。 */
    fun renderFile(card: FileCard, statusLine: String): String = when {
        card.doc == null -> buildString {
            append("「${card.absPath.ifEmpty { card.relPath }}」不在索引里")
            append("（索引只收录扫过的文件；先在搜索卡「扫描本机」，或用 read_file 直接读全文）。")
            append('\n').append(statusLine)
        }

        card.summary == null -> buildString {
            append("账上有这篇，但没有摘要：${card.relPath}（${card.doc.status}）")
            append("——全盘档与顶满的根不建摘要，非文本不建摘要；全文用 read_file 读。")
            append('\n').append(statusLine)
        }

        else -> buildString {
            append("[索引摘要卡] ${card.relPath}")
            append("（${card.doc.status}，索引于 ${at(card.doc.indexedAt)}）")
            append('\n').append("标题：${card.doc.title.ifEmpty { "（无标题）" }}")
            val lines = DocSummaryRules.firstLineList(card.summary.firstLines)
            if (lines.isEmpty()) {
                append("\n前 ${DocSummaryRules.FIRST_LINES} 行：（空）")
            } else {
                append('\n').append("前 ${DocSummaryRules.FIRST_LINES} 行：")
                lines.forEachIndexed { i, l -> append("\n  ${i + 1}| ").append(l) }
            }
            val blocks = DocSummaryRules.blockList(card.summary.blocks)
            if (blocks.isEmpty()) {
                append("\n结构块：（没有 Markdown 标题）")
            } else {
                append("\n结构块：")
                blocks.forEach { (no, name) -> append("\n  ${card.relPath}:$no  ").append(name) }
            }
            append('\n').append(statusLine)
        }
    }

    /** 目录清单渲染（**纯函数**）：子目录在前（与 list_dir 同序原则），触顶明说。 */
    fun renderDir(browse: DirBrowse, statusLine: String, cap: Int): String {
        if (browse.directFiles.isEmpty() && browse.dirNames.isEmpty() && !browse.truncated) {
            return "「${browse.absDir}」在索引里是空的（账上没有它下面的文件；先在搜索卡「扫描本机」）。\n$statusLine"
        }
        return buildString {
            append("[索引目录] ${browse.relDir.ifEmpty { "<根>" }}")
            append("（文件 ${browse.directFiles.size}，子目录 ${browse.dirNames.size}）")
            if (browse.dirNames.isNotEmpty()) {
                append("\n子目录：")
                browse.dirNames.forEach { append("\n  ").append(it).append('/') }
            }
            if (browse.directFiles.isNotEmpty()) {
                append("\n文件：")
                browse.directFiles.forEach { f ->
                    append("\n  ").append(File(f.relPath).name)
                    if (f.title.isNotEmpty()) append(" —— ").append(f.title)
                    if (f.status != "OK") append("（${f.status}）")
                }
            }
            if (browse.deeperFiles > 0) {
                append("\n（更深处还有 ${browse.deeperFiles} 篇账行；把 path 指到子目录逐层看）")
            }
            if (browse.truncated) {
                append("\n（已到 $cap 行上限，可能没翻完；缩小目录或提 limit 再看）")
            }
            append('\n').append(statusLine)
        }
    }
}
