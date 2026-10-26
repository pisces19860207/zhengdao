// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百九十八批（派工单 §1 六条的总装）：**全手机搜索的门面**——把纯判据层
// （[PhoneScanRules]／[PhoneText]／[PhoneRank]／[PhoneJump]）接到真的 `listFiles()` 与
// [PhoneIndex] 上。本文件是 Android 壳，JVM 里跑不起来，所以它**只做四件事**：
// 取数（stat／读字节）、排队（目录队列）、调用纯函数、写库。任何一条判据想往这里长，
// 都该先回纯函数那一层——否则派工单 §2 点名的「JVM 表驱动测……不许只写真机验」就没东西可测。
//
// ## 一次扫描的形状（派工单 §1①「每片 2000、边扫边入库、片间可取消」）
// 1. 账本由调用方先同步（[syncNow]；⑥① 的裁定见下面「入口」一节）；
// 2. 有效根只从 [PhoneIndex.activeRoots] 来 ⇒ 撤销过的根、没入账的目录**在这里物理上扫不到**；
// 3. 每个根两段：**核对段**（[sweepLedger]，只在 `verifyDocs` 时跑）按账上的行逐批 stat ⇒
//    没了的删行（`tok` 顺 `ON DELETE CASCADE`）、size+mtime 变了的重索引 —— 这一半管**改内容**；
//    **遍历段**（[walkRoot]）走目录队列 ⇒ 新增的文件进索引 —— 这一半管**新增**（POSIX 下
//    新增/删除必然改目录 mtime，所以 [PhoneScanRules.suspectDir] 只在这两件事上可信）；
// 4. 遍历段一片＝「一个目录的一段 ≤2000 条」（[PhoneScanRules.shardPages]），
//    每片一个事务（[PhoneIndex.putShard]）；片间检查 [PhoneScanRules.stopReason]
//    （取消 → 命中够 → 超时）；
// 5. 每根扫完才 [PhoneScanRules.rollUpMark]，`completed = (这一根真把队列走完了)`——
//    中途停只刷 `last_scan_at`、不刷 `last_full_at`（谎报扫全了＝永久漏索引）。
//
// ## 为什么不用 `LibraryPaths.scanTree`
// 它到上限时 `collected.take(MAX_SCAN)`（LibraryPaths.kt:189）——**尾巴直接丢**。
// 本批要的是「一次看一点但一根不漏」，所以递归交给目录队列，每层只用
// `listTopLevelFiles`（不递归、无上限、按名排序 ⇒ 切片是精确的，没有尾巴）。
//
// ## 内存上限为什么敢写死
// 全程同时活着的大对象只有四样：一批账上的行（≤[PhoneScanRules.CHUNK_FILES] 行）、
// 一片的条目表（≤2000 条）、一篇的字节（≤[PhoneText.MAX_FILE_BYTES]，另有
// [READ_HARD_CAP] 兜住「stat 之后又长胖」那一刻）、一篇的词表（≤[PhoneRank.MAX_DOC_TOKENS] 项）。
// 根上有多少文件都不改变这四个数 ⇒ 真机冷扫的峰值内存是个能复现的数字（派工单 §3 死卡的那条）。
//
// ## 入口（⑥① 的裁定，钉在 `PhoneSearchEntryGateTest`）
// 读侧账本只在**用户明确动手**的两个地方变：设置页那个开关（S2 已有）、搜索卡上的
// 「扫描本机／重建索引」。装配层（`BaopuRuntime`／`MainActivity`／`HomeScreen`）**没有**自动同步，
// 所以「谁在什么时候能改读侧账本」的答案是两句可核对的话，不是一个运行时意外。
//
// ## 隐私线（本文件一句查询词都不写）
// [scan] 接的 `terms` 只用来数命中（[PhoneScanRules.stopReason] 里 `topK * HIT_RATIO` 那把尺子），
// 一个字不进日志；所有 `RunLog.log` 只有计数、状态名与根路径。异常文本一律不进日志
// （`SQLiteException` 会把绑定参数抄进 message，而这里绑的正是内容切出来的 token）。
package com.example.zhengdao.baopu.search

import android.content.Context
import com.example.zhengdao.baopu.BaopuWorkspace
import com.example.zhengdao.baopu.library.LibraryPaths
import com.example.zhengdao.core.RunLog
import java.io.File

/** 一条检索命中（rootId 已翻成绝对根，UI 不用再查库）。 */
internal data class HitRow(
    val rootId: Long,
    val absRoot: String,
    val relPath: String,
    val score: Int,
    val rootIsWorkspace: Boolean,
)

/** 一次检索的读数（[termsTruncated] 为真 ⇒ 界面必须明说「只按前 N 个词查的」）。 */
internal data class QueryResult(
    val hits: List<HitRow>,
    val termCount: Int,
    val termsTruncated: Boolean,
)

/** 一次扫描期间的累加器（跨根、跨批共用；只装计数，不装内容——装内容就是内存债）。 */
internal class ScanCounters {
    var roots = 0
    var shards = 0
    var docsWritten = 0
    var reused = 0
    var goneDeleted = 0
    var unclaimed = 0
    var cappedDocs = 0
    var skipped = 0
    var seen = 0
    var bytesRead = 0L
    var failedShards = 0
    val skipTags = mutableMapOf<String, Int>()

    fun tag(name: String, times: Int = 1) {
        skipTags[name] = (skipTags[name] ?: 0) + times
    }
}

/** 「根自己被排除清单命中」这一笔的计数名（不属于 [Exclusion] 的任何一条：那是文件层的判据）。 */
internal const val TAG_ROOT_EXCLUDED = "root-excluded"

/** 一次扫描的全部对账数字（派工单 §2「截断与不认领必须可见」——每个数都要能在界面上说话）。 */
internal data class ScanReport(
    val roots: Int,
    val shards: Int,
    val docsWritten: Int,
    val reused: Int,
    val goneDeleted: Int,
    val unclaimed: Int,
    val cappedDocs: Int,
    val skipped: Int,
    val bytesRead: Long,
    val scanned: Int,
    val approxTotal: Int?,
    val stopped: StopReason,
    val failedShards: Int,
    val elapsedMs: Long,
    val skipTags: Map<String, Int>,
    /** ⑥③：扫之前 `search.db` 这个文件不存在 ⇒ 日志里写明「本次是首次建库」。 */
    val dbFirstBuild: Boolean,
) {
    val isPartial: Boolean get() = stopped != StopReason.DONE
}

/** 索引现状（设置页进来先读这个，不触发任何扫描、不建库）。 */
internal data class IndexSnapshot(
    val access: PhoneAccess,
    val roots: List<ActiveRoot>,
    /** 每个根的水位；**value 可为 null** = 这个根从没扫过（UI 要显示「从没扫过」而不是「扫了 0 个」）。 */
    val marks: Map<Long, MarkRow?>,
    val dbExists: Boolean,
)

/** 全手机搜索的门面（读侧扫描／检索的唯一入口，Context 到这一层为止）。 */
internal object PhoneSearch {

    /** 一次查询给界面的默认条数（与 [SearchSql.SEARCH_LIMIT] 同一个数）。 */
    const val DEFAULT_TOP_K = SearchSql.SEARCH_LIMIT

    /** stat 与实读之间文件长胖的硬上限：超过就当没读到（宁可不索引，不许把内存撑爆）。 */
    const val READ_HARD_CAP = 8L * 1024L * 1024L

    /** 库文件在不在（⑥③：「首次建库」要能在真机日志里看见，先要能回答「之前在不在」）。 */
    fun dbExists(ctx: Context): Boolean = SearchDb.dbPathUnder(ctx.filesDir).isFile

    /**
     * 把读侧账本拨到与现状一致（⑥①：**手动入口**，只有两处——设置页开关、搜索卡的扫描按钮）。
     *
     * 没有第三处，也没有「App 启动时自动同步」。加自动同步等于让「谁在什么时候能改读侧账本」
     * 变成运行时的随手一刻，而那张账本是读侧唯一的授权真相（派工单 §1⑤ 那条线）。
     */
    fun syncNow(ctx: Context): SyncReport = SearchScope.sync(ctx)

    /** 现状读数（不扫盘；库还没建就直接给空表，不因为「看一眼」而创建文件）。 */
    fun snapshot(ctx: Context): IndexSnapshot {
        val st = SearchScope.status(ctx)
        if (!dbExists(ctx)) return IndexSnapshot(st.access, emptyList(), emptyMap(), false)
        val db = SearchDb(ctx)
        return try {
            val index = PhoneIndex(db)
            val roots = index.activeRoots()
            IndexSnapshot(st.access, roots, roots.associate { it.rootId to index.markOf(it.rootId) }, true)
        } finally {
            runCatching { db.close() }
        }
    }

    /**
     * 扫一遍（派工单 §1①②③④ 的总装）。**必须在 IO 线程调用**：UI 侧
     * `withContext(Dispatchers.IO)`，房型照 196 批产物查看器与存储页那三处盘 IO。
     *
     * @param terms 用户正在等的词（空表＝不带查询的纯重建）；非空时用于「可达即停」。
     * @param topK 界面准备显示几条 ⇒ 「可达即停」的命中预算 = `topK × HIT_RATIO`（[PhoneScanRules.stopReason]）。
     * @param cancelled 片间检查的取消闸门（UI 的「停止」按钮置位；本函数一片一查，不杀线程）。
     * @param forceFull 用户点「重建索引」⇒ 忽略水位，整根重扫（水位仍只在扫完时刷 `last_full_at`）。
     */
    fun scan(
        ctx: Context,
        terms: List<String> = emptyList(),
        topK: Int = DEFAULT_TOP_K,
        cancelled: () -> Boolean = { false },
        forceFull: Boolean = false,
    ): ScanReport {
        val startedAt = System.currentTimeMillis()
        // ⑥③：「首次建库」这件事由调用方那行 `syncLine` 先说（[syncNow] 一旦跑起来就会把库建出来）。
        // 这里再问一次，回答的是「**扫**之前库在不在」——走搜索卡那条链时它恒为「在」，不是假话，
        // 只是那一句轮不到它说；直接从别处调 scan 的情况下它仍然有效。
        val firstBuild = !dbExists(ctx)
        val c = ScanCounters()
        var stop = StopReason.DONE
        var approx: Int? = null
        val db = SearchDb(ctx)
        try {
            val index = PhoneIndex(db)
            val active = index.activeRoots()
            c.roots = active.size
            for (root in active) {
                val rootFile = File(root.absRoot)
                // 根自己被排除清单命中 ⇒ 整根不扫（失效方向是「少搜」，绝不是「多读」）
                if (!PhoneScanRules.rootAccepted(rootFile)) {
                    c.tag(TAG_ROOT_EXCLUDED)
                    continue
                }
                val mark = index.markOf(root.rootId)
                val plan = PhoneScanRules.planRoot(scopeRowOf(root, mark), mark, forceFull)
                val seenBefore = c.seen
                val skippedBefore = c.skipped
                val bytesBefore = c.bytesRead
                if (plan.verifyDocs) stop = sweepLedger(index, rootFile, root.rootId, terms, cancelled, startedAt, c)
                if (stop == StopReason.DONE) stop = walkRoot(index, root.rootId, rootFile, mark, plan.full, !forceFull, terms, topK, cancelled, startedAt, c)
                val completed = stop == StopReason.DONE
                val mw = PhoneScanRules.rollUpMark(
                    rootId = plan.rootId,
                    old = mark,
                    seenThisPass = c.seen - seenBefore,
                    skippedThisPass = c.skipped - skippedBefore,
                    bytesReadThisPass = c.bytesRead - bytesBefore,
                    now = System.currentTimeMillis(),
                    completed = completed,
                )
                if (!index.putMark(mw)) {
                    RunLog.log("搜索水位没写成（根 ${root.kind.tag}，本次扫到 ${mw.filesSeen} 个文件）：下一次按冷扫补")
                }
                approx = PhoneScanRules.approxTotal(if (completed) markOfWrite(mw) else mark)
                if (!completed) break
            }
        } finally {
            runCatching { db.close() }
        }
        val report = ScanReport(
            roots = c.roots,
            shards = c.shards,
            docsWritten = c.docsWritten,
            reused = c.reused,
            goneDeleted = c.goneDeleted,
            unclaimed = c.unclaimed,
            cappedDocs = c.cappedDocs,
            skipped = c.skipped,
            bytesRead = c.bytesRead,
            scanned = c.seen,
            approxTotal = approx,
            stopped = stop,
            failedShards = c.failedShards,
            elapsedMs = System.currentTimeMillis() - startedAt,
            skipTags = c.skipTags.toMap(),
            dbFirstBuild = firstBuild,
        )
        RunLog.log(
            "全手机搜索扫描：${report.roots} 个根、${report.shards} 片、入库 ${report.docsWritten} 篇、" +
                "复用 ${report.reused} 篇、删掉 ${report.goneDeleted} 篇、不认领 ${report.unclaimed} 篇、" +
                "长文截断 ${report.cappedDocs} 篇、跳过 ${report.skipped} 个、读了 ${report.bytesRead / 1024} KB、" +
                "用时 ${report.elapsedMs} ms、停因 ${report.stopped.name}" +
                if (report.dbFirstBuild) "（首次建库：扫之前 search.db 这个文件不存在）" else "",
        )
        if (report.failedShards > 0) {
            RunLog.log("扫描里有 ${report.failedShards} 片入库失败（整片回滚，一片都没落）：这些文件下一次还会重扫")
        }
        if (report.isPartial) {
            RunLog.log("扫描提前停下（${report.stopped.name}）：已入库的部分照常可搜，未扫的下一次补")
        }
        return report
    }

    /**
     * 检索（只读；UI 在 IO 线程调）。词只到 [PhoneRank.termsOf] 为止，之后全是 token。
     *
     * 未授权目录在这儿根本搜不到：命中只来自 `doc`／`tok`，而这两张表只由 [scan] 按
     * [PhoneIndex.activeRoots]（`revoked_at IS NULL` 的那几行）写出来；撤销一个根时
     * [SqlSearchScopeStore.revokeAbsentFrom] 已经把它的 doc 行连带 tok 删干净。
     */
    fun query(ctx: Context, text: String, topK: Int = DEFAULT_TOP_K): QueryResult {
        val terms = PhoneRank.termsOf(text)
        val limit = topK.coerceIn(1, SearchSql.MAX_LIMIT)
        if (terms.isEmpty || !dbExists(ctx)) return QueryResult(emptyList(), terms.tokens.size, terms.truncated)
        val db = SearchDb(ctx)
        return try {
            val index = PhoneIndex(db)
            val roots = index.activeRoots().associateBy { it.rootId }
            val workspaceKey = SearchScopeRules.rootKey(BaopuWorkspace.hostDir(ctx))
            val hits = PhoneRank.rank(index.search(terms.tokens, limit), limit).mapNotNull { h ->
                val r = roots[h.rootId] ?: return@mapNotNull null // 账上没这个根 ⇒ 命中不显示（宁少不错）
                HitRow(
                    rootId = h.rootId,
                    absRoot = r.absRoot,
                    relPath = h.relPath,
                    score = h.score,
                    rootIsWorkspace = PhoneJump.isWorkspaceRoot(r.absRoot, workspaceKey),
                )
            }
            QueryResult(hits, terms.tokens.size, terms.truncated)
        } finally {
            runCatching { db.close() }
        }
    }

    // ── 内部：两段遍历 ────────────────────────────────────────────────────────

    /**
     * 核对段（派工单 §1④ 的「改内容／没了」那一半）：账上的行**一批 2000** 流式读，
     * 逐个 stat ⇒ [PhoneScanRules.planIncrement] 判 gone / reindex。
     *
     * `gone` 按批删（`tok` 顺级联）；`reindex` 当场重读重索引（一批最多 2000 个，
     * 一篇一个事务——变更是少数事件，不值得为它攒表）。**不**在这里数 `reused`：
     * 没变的那些文件随后会被遍历段按账对上，由那一侧统一计数（两处都记就是同一个文件算两遍）。
     */
    private fun sweepLedger(
        index: PhoneIndex,
        rootFile: File,
        rootId: Long,
        terms: List<String>,
        cancelled: () -> Boolean,
        startedAt: Long,
        c: ScanCounters,
    ): StopReason {
        var stop = StopReason.DONE
        index.eachDoc(rootId, PhoneScanRules.CHUNK_FILES) { batch ->
            if (stop != StopReason.DONE) return@eachDoc
            val disk = HashMap<String, SizeMtime>(batch.size * 2)
            batch.forEach { row ->
                val f = File(rootFile, row.relPath)
                if (f.isFile) disk[row.relPath] = SizeMtime(f.length(), f.lastModified())
            }
            val inc = PhoneScanRules.planIncrement(batch, disk)
            if (inc.gone.isNotEmpty()) {
                val ids = inc.gone.map { it.docId }
                if (index.deleteDocs(ids)) c.goneDeleted += ids.size else c.failedShards++
            }
            inc.reindex.forEach { ref ->
                val f = File(rootFile, ref.relPath)
                val stat = disk[ref.relPath]
                // 入库前同样只过 [SearchAdmission.call] 这一扇门（与 planDocRows 同一把判据）
                if (stat != null && SearchAdmission.call(rootFile, f) == Call.IN_SCOPE) {
                    // 内容变了（size/mtime 对不上）⇒ 当场重读重索引；一个字节都没变的那些不在这里，
                    // 由遍历段按账对上后计入 `reused`（两处都记就是同一个文件算两遍）。
                    val doc = readDoc(f, ref.relPath, LibraryPaths.classify(f.name).first.name, stat.size, stat.mtime, terms, c)
                    val ok = if (doc != null) {
                        val wrote = index.putShard(rootId, listOf(doc), System.currentTimeMillis())
                        if (wrote) c.docsWritten++
                        wrote
                    } else {
                        // 原来是能搜的文本、现在读不出来了（改成二进制／清空了）⇒ **撤下这一行**，
                        // 连着它的 tok 一起走。留着旧行＝旧内容还能被搜到，那是份假账。
                        val deleted = index.deleteDocs(listOf(ref.docId))
                        if (deleted) c.goneDeleted++
                        deleted
                    }
                    if (!ok) c.failedShards++
                }
            }
            stop = PhoneScanRules.stopReason(0, 0, System.currentTimeMillis() - startedAt, cancelled())
        }
        return stop
    }

    /**
     * 遍历段（派工单 §1①② 的「每片 2000／不跟随软链／排除清单」那一半）：目录队列。
     *
     * 返回停止原因（`DONE` = 队列走空）。`full = false` 时只把**可疑目录**里的文件送去入库，
     * 但队列仍然下沉——父目录不可疑不代表子目录不可疑（可疑性按每个目录自己的 mtime 判）。
     */
    private fun walkRoot(
        index: PhoneIndex,
        rootId: Long,
        rootFile: File,
        mark: MarkRow?,
        full: Boolean,
        reuse: Boolean,
        terms: List<String>,
        topK: Int,
        cancelled: () -> Boolean,
        startedAt: Long,
        c: ScanCounters,
    ): StopReason {
        val queue = ArrayDeque<File>()
        queue.addLast(rootFile)
        var hits = 0
        while (queue.isNotEmpty()) {
            val dir = queue.removeFirst()
            val children = dir.listFiles() // 一次 O(一层)；子目录进队，文件按下面的切片处理
            if (children != null) {
                children.forEach { child ->
                    if (child.isDirectory && PhoneScanRules.enqueueDir(child)) queue.addLast(child)
                }
            }
            val suspect = full || PhoneScanRules.suspectDir(runCatching { dir.lastModified() }.getOrDefault(0L), mark)
            if (!suspect) continue
            val relDir = SearchAdmission.relativePath(rootFile, dir) ?: ""
            val entries = LibraryPaths.listTopLevelFiles(dir)
            for (page in PhoneScanRules.shardPages(entries.size)) {
                c.shards++
                // `shardPages` 给的是 `IntRange`（**last 含端点**）⇒ 取片必须用闭区间的 `slice`。
                // 写成 `subList(page.first, page.last)` 每片会悄悄丢掉最后一个文件：2001 条丢第 2001 条，
                // 单文件目录（`0 until 1`）直接整个目录不入库。`PhoneScanTest.负例_片是闭区间_薄壳取片不许丢末位` 钉这条。
                hits += indexPage(index, rootFile, rootId, relDir, entries.slice(page), terms, reuse, c)
                val stop = PhoneScanRules.stopReason(hits, topK, System.currentTimeMillis() - startedAt, cancelled())
                if (stop != StopReason.DONE) return stop
            }
        }
        return StopReason.DONE
    }

    /**
     * 一片的入库（认领判定 → 只给认领件读内容 → 切词 → 一次事务）。返回这片贡献的命中数。
     *
     * 队列顺序＝`listTopLevelFiles` 的名序（它内部按 `relPath` 排过 ⇒ 同一目录两次切片完全一致）。
     *
     * [reuse] 是「账上 size+mtime 对上就不重读」这条快路开不开（派工单 §1④）。
     * 用户点「重建索引」时必须关掉它，否则快路会把重建变成空转：**切词口径改了也扫不出变化**
     * （三个 `StopReason` 之外最容易漏的一件事，`PhoneSearchWiringTest.结构_重建索引不看账` 钉住）。
     */
    private fun indexPage(
        index: PhoneIndex,
        rootFile: File,
        rootId: Long,
        relDir: String,
        slice: List<LibraryPaths.ScannedFile>,
        terms: List<String>,
        reuse: Boolean,
        c: ScanCounters,
    ): Int {
        val now = System.currentTimeMillis()
        val docs = ArrayList<IndexedDoc>(slice.size)
        slice.forEach { e ->
            c.seen++
            val rel = PhoneScanRules.relToRoot(relDir, e.relPath)
            val candidate = File(rootFile, rel)
            val ledger = if (reuse) index.docStat(rootId, rel) else null
            if (ledger != null && ledger.size == e.size && ledger.mtime == e.mtime) {
                c.reused++
                return@forEach // 账对上 ⇒ 一个字节都不重读（这一片这一篇一行都不写，库里那行仍然有效）
            }
            val doc = readDoc(candidate, rel, e.kind.name, e.size, e.mtime, terms, c) ?: return@forEach
            docs += doc
        }
        if (docs.isEmpty()) return 0
        // 入库前只过 [SearchAdmission.planDocRows] 这一扇门（S2 钉着的唯一一扇）：
        // 被排除／越界的那些 ⇒ **一行都不建**，只在 skipped 与 skipTags 上留数。
        val probes = docs.map { Probe(File(rootFile, it.relPath), it.kind, it.size, it.mtime, it.status) }
        val plan = SearchAdmission.planDocRows(rootId, rootFile, probes, now)
        plan.skipTags.forEach { (k, v) -> c.tag(k, v) }
        c.skipped += plan.skipped
        val keep = plan.rows.map { it.relPath }.toSet()
        val rows = docs.filter { it.relPath in keep } // 探针没进 rows ⇒ 对应的词表也一起丢（两份账不许对不上）
        if (rows.isEmpty()) return 0
        if (!index.putShard(rootId, rows, now)) {
            c.failedShards++
            return 0 // 这一片没进库 ⇒ 一个命中都不算（算了就是「够一屏」提前停，屏幕上却是空的）
        }
        c.docsWritten += rows.size
        // 命中只按「真进了库、且带着用户要的那些词」的行数：没入库的行搜不到，不配参与「可达即停」。
        return if (terms.isEmpty()) 0 else rows.count { r -> r.counts.keys.any { it in terms } }
    }

    /**
     * 读**一篇**并切成待入库的 [IndexedDoc]（[indexPage] 与 [sweepLedger] 共用这一份代码——
     * 「两处各写一遍」就是将来漂的开始，所以全仓只有这一处读内容）。
     *
     * 三条出口，各有计数（派工单 §2「截断与不认领必须可见」）：
     * - 不认领／超大 ⇒ **建行、零字节都不读**（`counts` 空 ⇒ 没有 tok 行；冷扫里绝大多数文件走这条）；
     * - 二进制／解不出编码／空正文 ⇒ **不建行**（`null`），只留 skipTag 那一笔；
     * - 认领且读得出 ⇒ 建行 ＋ 切词。
     */
    private fun readDoc(
        file: File,
        rel: String,
        kindTag: String,
        size: Long,
        mtime: Long,
        terms: List<String>,
        c: ScanCounters,
    ): IndexedDoc? {
        val pre = PhoneText.preCall(size, kindTag)
        if (pre != ReadCall.PARSE_TEXT && pre != ReadCall.PARSE_DOCX) {
            val status = PhoneText.docStatusOf(pre, "") ?: DocStatus.UNCLAIMED
            if (status == DocStatus.UNCLAIMED) c.unclaimed++
            return IndexedDoc(rel, kindTag, size, mtime, "", "", status, emptyMap())
        }
        val head = readHead(file)
        c.bytesRead += head.size
        val call = PhoneText.headCall(pre, head)
        if (call == ReadCall.SKIP_BINARY) {
            c.tag(PhoneText.TAG_BINARY)
            c.skipped++
            return null // 前 8KB 有 NUL ⇒ 连 doc 行都不建（派工单 §1②「跳过」）
        }
        val all = if (head.size.toLong() == size) head else readAll(file)
        c.bytesRead += all.size
        val body = PhoneText.bodyOf(call, file, all)
        val status = PhoneText.docStatusOf(body.call, body.text)
        if (status == null) {
            PhoneText.skipTagOf(body.call)?.let { c.tag(it) }
            c.skipped++
            return null // 空正文／解不出编码 ⇒ 不建行，只在计数里留一笔
        }
        val title = PhoneText.titleOf(body.text)
        val counts = PhoneRank.tokenCounts(title, body.text)
        if (counts.truncated) c.cappedDocs++
        return IndexedDoc(rel, kindTag, size, mtime, title, PhoneText.sha256Head(PhoneText.headOf(head)), status, counts.counts)
    }

    /** 前 [PhoneText.HEAD_BYTES]（读不满就给读到的那些；失败给空表 ⇒ 判成「解不出编码」，不猜内容）。 */
    private fun readHead(f: File): ByteArray = runCatching {
        f.inputStream().use { input ->
            val buf = ByteArray(PhoneText.HEAD_BYTES)
            var got = 0
            while (got < buf.size) {
                val n = input.read(buf, got, buf.size - got)
                if (n < 0) break
                got += n
            }
            if (got == buf.size) buf else buf.copyOf(got.coerceAtLeast(0))
        }
    }.getOrDefault(ByteArray(0))

    /** 全文（大小已由 [PhoneText.preCall] 卡在 2 MiB 内；这一刻长胖过 [READ_HARD_CAP] 就当没读到）。 */
    private fun readAll(f: File): ByteArray = runCatching {
        f.inputStream().use { input ->
            val b = input.readBytes()
            if (b.size.toLong() <= READ_HARD_CAP) b else ByteArray(0)
        }
    }.getOrDefault(ByteArray(0))

    /** `ScopeRow` 的拼装（水位与账本两半合给 [PhoneScanRules.planRoot]，本批不碰 store 实现细节）。 */
    private fun scopeRowOf(root: ActiveRoot, mark: MarkRow?): ScopeRow = ScopeRow(
        rootId = root.rootId,
        absRoot = root.absRoot,
        kind = root.kind,
        grantedAt = mark?.lastScanAt ?: 0L,
        revokedAt = null, // 只从 activeRoots() 来 ⇒ 一定没撤销过
    )

    /** [MarkWrite] 折回 [MarkRow]（只为让 [PhoneScanRules.approxTotal] 吃同一个形状）。 */
    private fun markOfWrite(w: MarkWrite): MarkRow =
        MarkRow(w.lastScanAt, w.lastFullAt, w.filesSeen, w.filesSkipped, w.bytesRead)
}
