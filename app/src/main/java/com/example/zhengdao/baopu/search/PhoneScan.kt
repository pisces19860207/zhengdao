// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百九十八批（派工单 §1①④）：**分片扫描与增量的算式层**——把「一次一片、每片 2000、
// 边扫边入库、片间可取消、可达即停、增量按 size+mtime、重启按 watermark 补扫」这七句
// 拆成能 JVM 直测的纯函数。
//
// ## 为什么内核吃 String 而不是 File
// `doc.rel_path` 的真假账就长在这几个字符串拼接上：每片扫出来的是**该片目录内**的名字
// （`LibraryPaths.listTopLevelFiles` 的 relPath 就是 `f.name`，:220），而库里存的必须是
// **相对授予根**的路径。这层换算如果散在扫描器里，换一次分片口径就漂一次；
// 收进 [relToRoot] 一个函数，表驱动测直接打它（派工单 §2 那句「JVM 表驱动测……
// 不许只写真机验」里能直测的部分就是这几个函数）。
//
// ## 为什么不接 `scanTree`（本批最重要的一个口径裁定）
// `LibraryPaths.scanTree`（:145）到上限时是 `collected.take(MAX_SCAN)`（:189）——**尾巴是丢掉的，
// 不是留到下一次的**。它服务的是「模型一次看一批文件」那种用法（看不全就明说），
// 而索引要的是「一次看一点，但一根都不许漏」。所以本批走**目录队列**：
// 一片＝一个目录的顶层（[shardPages] 把它切成每片 ≤2000 条），子目录进队当下一片。
// 遍历由队列完成，`scanTree` 的递归与它的 `take` 都不参与 ⇒ 天然无尾巴，
// 「静默截断」那条负例从结构上就没有发生的余地（派工单 §2 点名的负例）。
// 队列里允许重叠（同一个目录被补扫两次）：`doc` 靠 `UNIQUE(root_id, rel_path)` UPSERT、
// `tok` 靠 `PRIMARY KEY(token, doc_id)` INSERT OR REPLACE，重复入库只是刷新同一行。
// **重叠无害、缺失有害**——所以 [suspectDir] 在没水位时一律判「可疑」（宁可多扫）。
//
// ## 不在这儿做的事
// 读盘、切词、入库全在 [PhoneSearch]／[PhoneIndex]（薄壳）；排除清单与「在不在授予根内」
// 只有 [SearchAdmission] 那一扇门（本文件一片都不判范围，只判「这片要不要切」「这层要不要进队」）。
package com.example.zhengdao.baopu.search

import java.io.File
import java.nio.file.Files

/** `watermark` 的一行读数（列与 [SearchSql.SELECT_WATERMARK] 逐字同序）。 */
internal data class MarkRow(
    val lastScanAt: Long,
    val lastFullAt: Long,
    val filesSeen: Int,
    val filesSkipped: Int,
    val bytesRead: Long,
)

/** 一个根本次该怎么扫。 */
internal data class RootPlan(
    val rootId: Long,
    val absRoot: String,
    /** `false` = 这个根上次全扫过 ⇒ 只补「可疑目录」＋核对账上的行（派工单 §1④）。 */
    val full: Boolean,
    /** 要不要把账上每一行都拿去 stat 一遍（增量的一半：文件没了要删行）。 */
    val verifyDocs: Boolean,
)

/** 为什么停（`DONE` = 队列自己走完了，这是唯一「扫全了」的情形）。 */
internal enum class StopReason {
    DONE,

    /** 用户点了停止（片间检查，不是杀线程）。 */
    CANCELLED,

    /** 命中够了（`topK * HIT_RATIO`）——派工单 §1③「可达即停」。 */
    HIT_BUDGET,

    /** 预算时间到。 */
    TIMEOUT,
}

/** 增量的核对结果（派工单 §1④：size+mtime 变⇒重索引；没了⇒删 doc＋其 tok）。 */
internal data class IncrementPlan(
    /** 需要重扫的那几行（size 或 mtime 对不上）。带 `docId`：重扫后**读不出内容**时要能撤下这一行。 */
    val reindex: List<DocRowRef>,
    /** 盘上已经没有、要删 doc 行的那些（`tok` 顺 `ON DELETE CASCADE` 一起走）。 */
    val gone: List<DocRowRef>,
    /** 两个数都对上 ⇒ 一个字节都不重读（三条出口必须分全：`gone + reindex + unchanged == rows.size`）。 */
    val unchanged: Int,
)

/** 一条要删的 `doc` 行。 */
internal data class DocRowRef(val docId: Long, val relPath: String)

/** `doc` 表里的一行，只带增量核对要的四个数（列序照 [SearchSql.SELECT_DOCS_OF_ROOT]）。 */
internal data class DocLedgerRow(val docId: Long, val relPath: String, val size: Long, val mtime: Long)

/** 盘上的一次 stat：增量的判据就是这两个数（派工单 §1④「size+mtime 变」）。 */
internal data class SizeMtime(val size: Long, val mtime: Long)

/** 写 `watermark` 的那一行（数值全在这里算完，薄壳只负责绑参数）。 */
internal data class MarkWrite(
    val rootId: Long,
    val lastScanAt: Long,
    val lastFullAt: Long,
    val filesSeen: Int,
    val filesSkipped: Int,
    val bytesRead: Long,
)

/**
 * 分片与增量的判据（**纯函数，无 Android、无 SQL**）。
 *
 * 一次扫描的全部「什么时候切、什么时候停、什么算变过」都在这张表里，JVM 直接打；
 * [PhoneSearch] 只把这些判据接到真的 `listFiles()` 与 `SQLiteDatabase` 上。
 */
internal object PhoneScanRules {

    /** 一片的文件数上限（派工单 §1①「每片 2000 文件」；与 `LibraryPaths.MAX_SCAN`（:56）同一个数）。 */
    const val CHUNK_FILES = 2000

    /** 命中达到 `topK * HIT_RATIO` 就停（派工单 §1③「可达即停」）。 */
    const val HIT_RATIO = 20

    /** 一次手动扫描的时间预算（毫秒）——超时是「可达即停」的另一半，不是错误。 */
    const val SCAN_TIMEOUT_MS = 90_000L

    /** 估算全盘规模时，`约 M` 最多报多少（超过这个数，M 就没有「约」的意义了）。 */
    const val MAX_APPROX = 200_000

    /**
     * 一个目录的顶层切成哪几片（派工单 §1①「每片 2000」的算式本尊）。
     *
     * 返回的是**左闭右开**的下标区间，覆盖 `[0, total)` 全体 ⇒ 「尾巴丢了」这条负例
     * 在函数形状上就不成立（`scanTree` 的 `take(MAX_SCAN)` 是同一件事的反面写法）。
     * `total = 0` 给空表：没文件也「扫全了」，不是出错。
     */
    fun shardPages(total: Int, chunk: Int = CHUNK_FILES): List<IntRange> {
        require(total >= 0) { "total 不能为负，实传 $total" }
        require(chunk > 0) { "chunk 必须为正，实传 $chunk" }
        if (total == 0) return emptyList()
        val out = mutableListOf<IntRange>()
        var from = 0
        while (from < total) {
            val to = (from + chunk).coerceAtMost(total)
            out += from until to
            from = to
        }
        return out.toList()
    }

    /**
     * 片内相对路径 → 根内相对路径（`doc.rel_path` 的写法）。
     *
     * `relDir` 为空（根自己）时直接返回；两边都不许多一个 `/`——多一个前导斜杠，
     * [SearchAdmission.relativePath] 算的那一份就对不上，增量核对会把同一个文件当成两个。
     */
    fun relToRoot(relDir: String, relInShard: String): String {
        val d = relDir.trim('/')
        val f = relInShard.trimStart('/')
        require(f.isNotEmpty()) { "片内相对路径不能是空串（空串拼出来的是目录，不是文件）" }
        return if (d.isEmpty()) f else "$d/$f"
    }

    /**
     * 这一层要不要下沉：命中排除清单的目录**连进队列都不许**（派工单 §1②：
     * 不进 `Android/`、不进 `.` 开头、点名排除 `rootfs/` 与 `.baopu-spill/`）。
     *
     * 判据还是 [SearchAdmission.exclusion] 那一把——排除清单只有一个地方说话。
     */
    fun acceptDir(dir: File): Boolean = SearchAdmission.exclusion(dir) == null

    /**
     * 软链接不跟随（派工单 §1②）。`LibraryPaths.isSymlink`（:278）是 private，本批不改它的可见性
     * （动 `LibraryPaths.kt` 不在本批范围内），所以这里是**同一类谨慎的第二个消费者**——
     * 房型照 [SearchAdmission] 对 `isJunkName` 的处理：本地实现一份，
     * 由 `PhoneSearchWiringTest` 用**源码文本**把两边钉在一起，一边改了另一边不改就红。
     */
    fun isSymlink(f: File): Boolean =
        runCatching { Files.isSymbolicLink(f.toPath()) }.getOrDefault(false)

    /** 一个子目录能不能进队：既不是软链、也没命中排除清单（两条判据的顺序不影响结论，只影响读起来）。 */
    fun enqueueDir(dir: File): Boolean = !isSymlink(dir) && acceptDir(dir)

    /** 根目录自己也要过一遍门（授予根若本身被排除，整个根一行都不扫——失效方向是「少搜」）。 */
    fun rootAccepted(root: File): Boolean = acceptDir(root)

    /** 片间检查：现在该不该停（顺序＝取消 → 命中够 → 超时；取消永远最优先）。 */
    fun stopReason(hits: Int, topK: Int, elapsedMs: Long, cancelled: Boolean): StopReason = when {
        cancelled -> StopReason.CANCELLED
        topK > 0 && hits >= topK * HIT_RATIO -> StopReason.HIT_BUDGET
        elapsedMs >= SCAN_TIMEOUT_MS -> StopReason.TIMEOUT
        else -> StopReason.DONE
    }

    /**
     * 根计划：从没全扫过 ⇒ 从根起一整片队列；扫全过 ⇒ 只补可疑目录 + 核对账上的行。
     *
     * `forceFull` 是用户点「重建索引」那一条（[SearchSql] 的索引可整包丢弃重建，UI 侧入口在
     * `BaopuPhoneSearchCard` 的「重建索引」）。
     */
    fun planRoot(row: ScopeRow, mark: MarkRow?, forceFull: Boolean): RootPlan {
        if (row.revokedAt != null) {
            // 已撤销的根：一行都不该扫（账本说撤了，扫描器还去读＝本批最坏的那种失效）。
            return RootPlan(row.rootId, row.absRoot, full = false, verifyDocs = false)
        }
        val neverFull = mark == null || mark.lastFullAt <= 0L
        return RootPlan(
            rootId = row.rootId,
            absRoot = row.absRoot,
            full = forceFull || neverFull,
            verifyDocs = !forceFull && !neverFull,
        )
    }

    /**
     * 这个目录是不是「可疑目录」（增量补扫的入口）：目录本身的 mtime 在 `lastScanAt` 之后才动过
     * ⇒ 里面有新增/删除（POSIX 语义：改内容不碰目录 mtime，所以这条只抓新增与删除）；
     * **改内容**那一半由 [planIncrement] 按 size+mtime 逐行核对兜住，两半合起来才是完整的增量。
     */
    fun suspectDir(dirMtime: Long, mark: MarkRow?): Boolean {
        if (mark == null || mark.lastScanAt <= 0L) return true // 没水位 ⇒ 当可疑（宁可多扫，不许漏）
        return dirMtime > mark.lastScanAt
    }

    /**
     * 增量核对（纯函数）：账上的行 vs 盘上的读数。
     *
     * 三条口径：
     * 1. 盘上没了 ⇒ `gone`（删 doc，`tok` 顺级联走，不写第二条 DELETE）；
     * 2. `size` 或 `mtime` 对不上 ⇒ `reindex`（派工单 §1④ 原话）；
     * 3. 两个数都对上 ⇒ `unchanged`（**不读文件内容**——一次 stat 换一遍全根，这是冷扫之后
     *    每次刷新还能跑得动的原因）。
     */
    fun planIncrement(rows: List<DocLedgerRow>, disk: Map<String, SizeMtime>): IncrementPlan {
        val reindex = mutableListOf<DocRowRef>()
        val gone = mutableListOf<DocRowRef>()
        var unchanged = 0
        rows.forEach { r ->
            val stat = disk[r.relPath]
            when {
                stat == null -> gone += DocRowRef(r.docId, r.relPath)
                stat.size != r.size || stat.mtime != r.mtime -> reindex += DocRowRef(r.docId, r.relPath)
                else -> unchanged++
            }
        }
        return IncrementPlan(reindex.toList(), gone.toList(), unchanged)
    }

    /**
     * 水位怎么落（每根一行；派工单 §1④「重启按 watermark 只补可疑目录」的数据源就是这里写的）。
     *
     * `lastFullAt` **只在扫全了的时候刷新**：中途取消/超时/命中即停都只更新 `lastScanAt`，
     * 于是下一次仍然按全量口径补——水位谎报「扫全了」＝永久漏索引，比慢十秒严重得多。
     */
    fun rollUpMark(
        rootId: Long,
        old: MarkRow?,
        seenThisPass: Int,
        skippedThisPass: Int,
        bytesReadThisPass: Long,
        now: Long,
        completed: Boolean,
    ): MarkWrite {
        require(seenThisPass >= 0 && skippedThisPass >= 0 && bytesReadThisPass >= 0) {
            "水位三个计数不能为负：seen=$seenThisPass skipped=$skippedThisPass bytes=$bytesReadThisPass"
        }
        return if (completed) {
            MarkWrite(rootId, now, now, seenThisPass, skippedThisPass, bytesReadThisPass)
        } else {
            val keepFull = old?.lastFullAt ?: 0L
            MarkWrite(rootId, now, keepFull, seenThisPass, skippedThisPass, bytesReadThisPass)
        }
    }

    /**
     * 「已扫 N／约 M」里的 M（派工单 §1③ 要求截断**可见**）。
     *
     * 只有上一次真扫全过的数才配当「约 M」；没扫全过时返回 null——UI 此时只显示 N，
     * 不许拿「本片扫到的数」冒充总数（那是个必然偏小的假数）。
     */
    fun approxTotal(mark: MarkRow?): Int? {
        if (mark == null || mark.lastFullAt <= 0L || mark.filesSeen <= 0) return null
        return mark.filesSeen.coerceAtMost(MAX_APPROX)
    }
}
