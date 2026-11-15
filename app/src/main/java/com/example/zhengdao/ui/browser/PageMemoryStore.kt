// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。

package com.example.zhengdao.ui.browser

import android.content.Context
import com.example.zhengdao.browser.BrowserSnapshotPolicy
import com.example.zhengdao.browser.PageMemoryPolicy
import com.example.zhengdao.browser.PageMemorySql
import com.example.zhengdao.browser.SensitiveRedactor
import com.example.zhengdao.browser.XuanlanPageDb
import com.example.zhengdao.core.RunLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 会话级页面记忆的**唯一读写口**（651）。
 *
 * 判据（用户 2026-11-15）：「每会话最近 20 页（有序），切换/重启后保留；**只记 URL 与标题**；
 * 可单会话清空/全清」——四条全在这一层落地，别处只调用：
 * - **只记 URL 与标题**：入口 [record] 只有 `url` / `title` 两个字符串参数，写入前各过一道既有判据
 *   （URL → [BrowserSnapshotPolicy.sanitizeUrlForStore]；标题 → [SensitiveRedactor.redactTabTitle]）。
 *   页面正文从来不经过本类（**结构上就没有这个参数**），所以无从落盘。
 * - **有序**：顺序由表里的 `id` 承担（[PageMemorySql] 注释里写了为什么不用时间戳）。
 * - **重启保留**：落 SQLite 文件（[XuanlanPageDb]），不是内存表。
 * - **清空**：单会话 [clearSession] / 全部 [clearAll]。
 *
 * 线程纪律（照 `BrowserTabSnapshotStore` 的三条）：
 * 1. 写入一律在 IO 线程（[scope]），**绝不在主线程碰 SQLite**；
 * 2. 失败只 `RunLog`，不抛、不弹——记忆是增强，不是主链路；
 * 3. 不持 `Context` 字段（`StaticFieldLeak` 的教训），Context 由调用方每次传；
 *    缓存的只有 [XuanlanPageDb] 实例（房型照 `BaopuRuntime.db`）。
 *
 * 会话桶：`sessionId` 由装配层注入（[BrowserTabManager.pageMemorySessionOf]，界面里接的是
 * `BaopuChatController.sessionId`）；没有会话时为 0（「还没开会话」那一桶，
 * 同一条「单会话清空／全清」照样管得到）。
 */
internal object PageMemoryStore {

    /**
     * 写侧作用域：用共享的 IO 池，**不起裸线程**（本仓有一条"谁起了线程"的对账单）。
     * Supervisor：一次写失败不该带走后续所有写。
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 一个实例够用（同 `BrowserTabSnapshotStore`：每次落库都 new 一次是白花钱）。 */
    private val redactor = SensitiveRedactor()

    /** 只缓存壳（不是 Context）：类型不是 Context 子类，`StaticFieldLeak` 不会盯它。 */
    @Volatile
    private var dbInstance: XuanlanPageDb? = null

    private fun db(context: Context): XuanlanPageDb =
        dbInstance ?: synchronized(this) {
            dbInstance ?: XuanlanPageDb(context.applicationContext).also { dbInstance = it }
        }

    /**
     * 记一次「页面加载完成」。
     *
     * **不阻塞调用方**（onPageFinished 在 UI 线程）：整段丢进 IO 池。
     * 不该记的（空地址 / 预览假地址 / about:blank）⇒ 直接返回，一条不给。
     */
    fun record(context: Context, sessionId: Long, url: String, title: String, isPreview: Boolean) {
        val page = PageMemoryPolicy.normalize(url, title, isPreview) ?: return
        val safeUrl = BrowserSnapshotPolicy.sanitizeUrlForStore(page.url) ?: return
        val safeTitle = redactor.redactTabTitle(page.title)
        val appCtx = context.applicationContext
        val now = System.currentTimeMillis()
        scope.launch {
            runCatching {
                db(appCtx).write { d ->
                    d.beginTransaction()
                    try {
                        // 重复访问不叠加：摘旧行 → 插新行（新 id ⇒ 落到末尾）
                        d.execSQL(PageMemorySql.DELETE_SAME_URL, arrayOf<Any?>(sessionId, safeUrl))
                        d.execSQL(
                            PageMemorySql.INSERT,
                            arrayOf<Any?>(sessionId, safeUrl, safeTitle, now),
                        )
                        d.execSQL(
                            PageMemorySql.PRUNE_OVERFLOW,
                            arrayOf<Any?>(sessionId, sessionId, PageMemoryPolicy.MAX_PAGES),
                        )
                        d.setTransactionSuccessful()
                    } finally {
                        d.endTransaction()
                    }
                }
            }.onFailure { RunLog.log("玄览 页记忆：写入失败（会话#$sessionId）：${it.message}") }
        }
    }

    /** 读某会话最近若干页（oldest → newest）。内部切 IO，可直接从界面协程调。 */
    suspend fun list(
        context: Context,
        sessionId: Long,
        limit: Int = PageMemoryPolicy.MAX_PAGES,
    ): List<PageMemoryPolicy.Page> = withContext(Dispatchers.IO) {
        runCatching {
            db(context.applicationContext).read().rawQuery(
                PageMemorySql.SELECT_SESSION,
                arrayOf(sessionId.toString(), limit.toString()),
            ).use { c ->
                buildList {
                    while (c.moveToNext()) {
                        add(PageMemoryPolicy.Page(url = c.getString(0).orEmpty(), title = c.getString(1).orEmpty()))
                    }
                }
            }
        }.onFailure { RunLog.log("玄览 页记忆：读取失败（会话#$sessionId）：${it.message}") }
            .getOrDefault(emptyList())
    }

    /** 清空某一个会话的账（「清空本会话」）。返回是否真的清成功（调用方据此给提示）。 */
    suspend fun clearSession(context: Context, sessionId: Long): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            db(context.applicationContext).write { d ->
                d.execSQL(PageMemorySql.DELETE_SESSION, arrayOf<Any?>(sessionId))
            }
            RunLog.log("玄览 页记忆：清空会话#$sessionId")
            true
        }.onFailure { RunLog.log("玄览 页记忆：清空会话#$sessionId 失败：${it.message}") }
            .getOrDefault(false)
    }

    /** 全清（「全部清空」）。返回清掉了几个会话的账（0 ＝ 本来就空）。 */
    suspend fun clearAll(context: Context): Int = withContext(Dispatchers.IO) {
        runCatching {
            val d = db(context.applicationContext)
            val sessions = d.read().rawQuery(PageMemorySql.COUNT_SESSIONS, null).use { c ->
                if (c.moveToNext()) c.getInt(0) else 0
            }
            d.write { w -> w.execSQL(PageMemorySql.DELETE_ALL) }
            RunLog.log("玄览 页记忆：全清 $sessions 个会话的账")
            sessions
        }.onFailure { RunLog.log("玄览 页记忆：全清失败：${it.message}") }
            .getOrDefault(0)
    }

    /** 仅单测用：丢掉缓存的壳（object 单例跨用例串味）。JVM 单测里从不建真库，这里只是清引用。 */
    internal fun resetForTest() {
        dbInstance = null
    }
}
