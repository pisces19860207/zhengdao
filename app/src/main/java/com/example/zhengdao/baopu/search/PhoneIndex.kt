// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百九十八批（派工单 §1①③④）：`search.db` 的**入库与检索薄壳**。
//
// ## 这个文件里为什么没有一句新 SQL
// 全部语句都取自 [SearchSql]（`UPSERT_DOC`／`SELECT_DOC_ID`／`UPSERT_TOK`／`DELETE_DOC_BY_ID`／
// `SELECT_DOCS_OF_ROOT`／`UPSERT_WATERMARK`／`SELECT_WATERMARK`／`SELECT_ACTIVE_ROOTS`／
// `selectSearch(…)`——其中十条在 S2 交付时是「形状照单、无人调用」的那一批，本批是它们的第一个调用方）。
// 列名与绑定顺序一旦在这儿重写一遍，`SearchSqlTest` 那条「列名逐字对账」就管不到这里了；
// 所以这里只允许 `args` 的**顺序**照语句走，顺序对不对由 `PhoneIndexArgsTest` 用
// 「一条语句的占位符个数 == 参数个数」＋「逐位取出的字面量」钉（JVM 里跑得起来的那一半）。
//
// ## 一片＝一个事务（派工单 §1①「边扫边入库」的落点）
// [putShard] 把整片（doc 行 ＋ 它的全部 tok 行）交给本文件的 [transact]：
// 刷盘的单位是片，不是文件。中途失败 ⇒ 整片回滚，盘上还是上一篇的样子；
// 扫描器把这一片记进 `failedShards` 后继续走下一片——**不许**因为一片失败就把整根的水位
// 报成「扫全了」（[PhoneScanRules.rollUpMark] 的 `completed` 参数就是为这件事留的）。
//
// ## doc_id 为什么回头查一次
// `UPSERT_DOC` 是 `ON CONFLICT(root_id, rel_path) DO UPDATE`（不是 `INSERT OR REPLACE`——
// 后者会先删行，顺 `ON DELETE CASCADE` 把这一篇的 tok 全清掉，见 [SearchSql] 里 scope 那条同样的理由），
// 而 `execSQL` 拿不回 rowid ⇒ 用 [SearchSql.SELECT_DOC_ID] 查回来。多一次查询换两件事：
// doc_id 稳定（同一篇文件重扫不换身份）、tok 不会被级联误删。这一条在 `docs` 的 UNIQUE 索引上走，
// 不是全表扫（报告 §4 的写入性能数字包含这一次查询）。
//
// ## 隐私线（本文件最要紧的一条纪律）
// `tok.token` 里存的是**文件内容**切出来的词，查询词也一样只作为绑定参数进 `selectSearch` 的
// `IN (?, ?…)`。所以这片代码里的失败**一律不写异常文本**（`SQLiteException` 会把绑定参数抄进
// message），只写「第几片、多少行」这种计数；`run_log` 与主库里因此不会留下任何一句用户查过的词。
// 这条由 `PhoneSearchPrivacyGateTest.负例_异常文本不进日志` 用源码文本钉住。
package com.example.zhengdao.baopu.search

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.example.zhengdao.core.RunLog

/** 一个还有效的授予根（[SearchSql.SELECT_ACTIVE_ROOTS] 的三列）。 */
internal data class ActiveRoot(val rootId: Long, val absRoot: String, val kind: ScopeKind)

/**
 * 一篇已经读完、切好词的文档（`doc` 行 ＋ 它的倒排行）。
 *
 * [counts] 为空 ⇔ 这篇不认领（`TOO_BIG`／`UNCLAIMED`）或解不出编码：建行、不建词。
 */
internal data class IndexedDoc(
    val relPath: String,
    val kind: String,
    val size: Long,
    val mtime: Long,
    val title: String,
    val sha256Head: String,
    val status: DocStatus,
    val counts: Map<String, Int>,
)

/**
 * `search.db` 的入库／检索壳（薄：只有游标读写与参数顺序，判据全在纯函数那一层）。
 *
 * 与 [SqlSearchScopeStore] 同一个吞错口径：**读不出就当没有**（fail closed ⇒ 搜索范围只会变小）；
 * 写不出就返回 false，由扫描器计数，不静默当成功。
 */
internal class PhoneIndex(private val db: SearchDb) {

    /** 账上现在还有效的根（扫描器只认这一张表 ⇒ 撤销过的根在这儿查不到，物理上就扫不到）。 */
    fun activeRoots(): List<ActiveRoot> = runCatching {
        db.readable().rawQuery(SearchSql.SELECT_ACTIVE_ROOTS, null).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val kind = ScopeKind.fromTag(c.string(2))
                    // kind 读不懂 ⇒ 整行丢掉（同 SqlSearchScopeStore：猜一档等于给自己发授权）
                    if (kind != null) add(ActiveRoot(c.long(0), c.string(1), kind))
                }
            }
        }
    }.getOrElse {
        RunLog.log("读侧根清单没读出来：本次扫描按「没有授予的根」处理（一行都不扫）")
        emptyList()
    }

    /** 一个根的旧水位（没扫过 ⇒ null，[PhoneScanRules.planRoot] 会据此判「这次是冷扫」）。 */
    fun markOf(rootId: Long): MarkRow? = runCatching {
        db.readable().rawQuery(SearchSql.SELECT_WATERMARK, arrayOf(rootId.toString())).use { c ->
            if (!c.moveToNext()) null else MarkRow(
                lastScanAt = c.long(0),
                lastFullAt = c.long(1),
                filesSeen = c.long(2).toInt(),
                filesSkipped = c.long(3).toInt(),
                bytesRead = c.long(4),
            )
        }
    }.getOrDefault(null)

    /**
     * 账上某**一篇**的 size+mtime（派工单 §1④「变则重索引」的左半边读数）。
     *
     * 三种情况一律给 `null`，让调用方按「没对上报」走完整读取流程：账上没这行（新增文件）、
     * 读不出这行（库忙）。这里不能返回一个假的 `SizeMtime(0, 0)`——那等于告诉扫描器
     * 「账上是空的，磁盘也是空的，这个文件不用重读」，一次读失败就变成永久漏索引。
     * 失效方向必须是「多读一遍」，绝不是「少索引」。
     */
    fun docStat(rootId: Long, rel: String): SizeMtime? = runCatching {
        db.readable().rawQuery(SearchSql.SELECT_DOC_STAT, arrayOf(rootId.toString(), rel)).use { c ->
            if (!c.moveToNext()) null else SizeMtime(c.long(0), c.long(1))
        }
    }.getOrDefault(null)

    /**
     * 流式翻一个根的账（增量核对的左半边）：一批 ≤[batchSize] 行，回调返回后那批就可以被回收。
     *
     * ## 为什么不是 `docsOf(rootId): List<…>`
     * 一次返回整根 = 几万个文件就是几万行常驻内存，而派工单 §3 死卡「峰值内存必须是实测数字」——
     * 常驻量必须与根的大小**无关**，这个数才谈得上复现。所以这里一次只留一批（[PhoneScanRules.CHUNK_FILES] 行）。
     *
     * ## 回调里可以写库（WAL 下读游标与写在两条连接上）
     * [SearchDb] 开了 WAL ⇒ 本方法的游标走只读连接，回调里的 [putShard]／[deleteDocs] 走
     * [SearchDb.write]，两条连接互不阻塞（这正是开 WAL 要买的那件事）。
     * 游标可能返回已被本轮删掉的行：那些行 `disk` 里没有（文件已经没了）⇒ 只会再删一次，
     * `DELETE … WHERE doc_id = ?` 对不存在的行是空操作 ⇒ 幂等，不会删错别人。
     */
    fun eachDoc(rootId: Long, batchSize: Int, onBatch: (List<DocLedgerRow>) -> Unit) {
        val cap = batchSize.coerceIn(1, PhoneScanRules.CHUNK_FILES)
        runCatching {
            db.readable().rawQuery(SearchSql.SELECT_DOCS_OF_ROOT, arrayOf(rootId.toString())).use { c ->
                var buf = ArrayList<DocLedgerRow>(cap)
                while (c.moveToNext()) {
                    buf += DocLedgerRow(docId = c.long(0), relPath = c.string(1), size = c.long(2), mtime = c.long(3))
                    if (buf.size >= cap) {
                        onBatch(buf.toList())
                        buf = ArrayList(cap)
                    }
                }
                if (buf.isNotEmpty()) onBatch(buf.toList())
            }
        }.onFailure {
            // 账本没翻完 ⇒ 当「账上没有」处理：这一次这些文件全部走重扫（多读一遍），
            // 而不是跳过核对段（跳过就是「文件删了但 doc 行还留着」的那种残留）。
            RunLog.log("增量核对的账本没翻完（根 $rootId）：本次按「账上没有」处理，全部重扫")
        }
    }

    /**
     * 一片（或一批删除）一个事务——**刷盘的单位是片，不是篇**。
     *
     * 为什么必须有它：[SearchDb.exec] 一条一提交 ⇒ 一片 2000 篇 ×（1 条 doc ＋ N 条 tok）就是
     * 几万次 fsync，冷扫的耗时全花在等盘上（派工单 §1①「边扫边入库」的那个「入库」就是这片一次
     * 提交，不是每篇一次）。
     *
     * 为什么住在**这里**而不是 [SearchDb]：全仓的写法是「谁写谁开事务」——`BaopuDb.write { d -> … }`
     * 的调用方（RunEngine／PlanStore／BaopuChatController…）都在调用方那一侧包，OpenHelper 本体
     * 只管给句柄。lint 的 `UseKtx` 只在「三件套收进带 `SQLiteDatabase` 形参的方法」那个形状下点名
     * `beginTransaction`（2026-10-25 两轮现数：方法形参形状红、lambda 形状全绿），所以三件套必须
     * 挂在 `db.write` 的 lambda 里、不能提成私有方法。它建议的 `SQLiteDatabase.transaction` 属
     * `androidx.core.database.sqlite.SQLiteDatabaseKt`，而本仓解析的 core-ktx 1.19.1 的 classes.jar
     * 只有 183 字节（unzip 现数）＝那个类不在 ⇒ 建议用不了。不压制、不改基线。
     *
     * **失败不记异常文本**（`getOrDefault(false)`，连 `RunLog` 都不写）：Android 的
     * `SQLiteException` 文本会把**绑定参数**抄进 message，而这里绑的正是文件内容切出来的 token
     * —— 把 `$it` 写进日志等于把内容片段抄进 `run_log`。失败理由由调用方按「第几片、多少行」说话
     * （派工单 §2「截断与失败必须可见」的那半）。
     */
    private fun transact(block: (SQLiteDatabase) -> Unit): Boolean = runCatching {
        db.write { d ->
            d.beginTransaction()
            try {
                block(d)
                d.setTransactionSuccessful()
            } finally {
                d.endTransaction()
            }
        }
        true
    }.getOrDefault(false)

    /**
     * 写一片（一次事务）。返回是否整片写成——false 时这片**一行都没落**（回滚），
     * 由调用方记进 `failedShards` 并继续；不许把「失败」折算成「扫过了」。
     */
    fun putShard(rootId: Long, docs: List<IndexedDoc>, now: Long): Boolean {
        if (docs.isEmpty()) return true
        return transact { d ->
            docs.forEach { doc ->
                d.execSQL(
                    SearchSql.UPSERT_DOC,
                    arrayOf<Any?>(
                        rootId, doc.relPath, doc.kind, doc.size, doc.mtime,
                        doc.title, doc.sha256Head, now, doc.status.tag,
                    ),
                )
                val docId = d.rawQuery(
                    SearchSql.SELECT_DOC_ID,
                    arrayOf(rootId.toString(), doc.relPath),
                ).use { c -> if (c.moveToNext()) c.long(0) else 0L }
                // doc 行没建成（外键挡住／relPath 空）⇒ 0，此时一篇词都不该写，否则会留下没有归属的倒排行
                if (docId > 0L) {
                    doc.counts.forEach { (token, cnt) ->
                        d.execSQL(SearchSql.UPSERT_TOK, arrayOf<Any?>(token, docId, cnt))
                    }
                }
            }
        }
    }

    /** 删掉盘上已经没有的那几篇（`tok` 顺 `ON DELETE CASCADE` 一起走，不写第二条 DELETE）。 */
    fun deleteDocs(docIds: List<Long>): Boolean {
        if (docIds.isEmpty()) return true
        return transact { d ->
            docIds.forEach { id -> d.execSQL(SearchSql.DELETE_DOC_BY_ID, arrayOf<Any?>(id)) }
        }
    }

    /** 落一个根的水位（数值已由 [PhoneScanRules.rollUpMark] 算好，这里只绑参）。 */
    fun putMark(w: MarkWrite): Boolean = db.exec(
        SearchSql.UPSERT_WATERMARK,
        arrayOf<Any?>(w.rootId, w.lastScanAt, w.lastFullAt, w.filesSeen, w.filesSkipped, w.bytesRead),
    )

    /**
     * 检索（只读）。词**只**走绑定参数：语句文本来自 [SearchSql.selectSearch]，那里只有 `?`。
     *
     * 空词表 ⇒ 空结果（不发语句）：一条 `IN ()` 是语法错，而「用户只打了标点符号」不是错误，
     * 是一次没内容的查询，不该在日志里留下一条失败。
     */
    fun search(tokens: List<String>, limit: Int = SearchSql.SEARCH_LIMIT): List<TermHit> {
        if (tokens.isEmpty()) return emptyList()
        val n = tokens.size.coerceAtMost(SearchSql.MAX_TOKENS)
        val args = tokens.take(n).toTypedArray()
        return runCatching {
            db.readable().rawQuery(SearchSql.selectSearch(n, limit), args).use { c ->
                buildList {
                    while (c.moveToNext()) {
                        add(TermHit(rootId = c.long(0), relPath = c.string(1), score = c.long(2).toInt()))
                    }
                }
            }
        }.getOrElse {
            // 注意：**不带 $it**（理由见文件头「隐私线」——异常文本会抄绑定参数，也就是用户查过的词）
            RunLog.log("检索没跑成（词表 $n 个）：本次按「没有结果」处理")
            emptyList()
        }
    }
}

/** 游标取值的小工具（列下标写死在调用处，读错列就是测试红，不靠运行时异常）。 */
private fun Cursor.string(i: Int): String = getString(i) ?: ""
private fun Cursor.long(i: Int): Long = if (isNull(i)) 0L else getLong(i)
