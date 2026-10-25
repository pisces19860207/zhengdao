// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：派工单-2026-10-24-第一百九十七批-读侧授权阶梯 §1①（读侧账本落**新库**
// `filesDir/baopu/search.db`，不动 `BaopuDb`）。表形状与全部 SQL 在 [SearchSql]（纯文本、JVM 直测），
// 本文件只是**薄壳**：把路径算出来、把连接开起来、把语句交给 SQLiteDatabase 执行。
//
// ## 与 `BaopuDb` 的三处刻意不同
// 1. **没有 `writer` 单线程执行器**：`BaopuDb.kt:1383` 那条 `zd-baopu-db` 线程被
//    `BareThreadGuardTest` 按文件行号钉着，全仓「谁起了线程」是一份对账单。本批只有
//    设置页那一次低频写入（用户拨开关），起一条常驻线程是为一件没发生的事付钱；
//    S3 的分片扫描真要并发时，**由扫描器自己串行化**（设计 §6.3「一次一片」），不在这里预埋。
// 2. **没有版本号、没有 migrate 阶梯**：主库的 v1..v19 是「用户数据不能丢」的迁移；
//    `search.db` 是**可整包丢弃重建的缓存**（设计 §6.4 原话「文件是真相，索引只是加速」），
//    全 DDL 都是 `CREATE TABLE IF NOT EXISTS` ⇒ 新装与升级同一条路径，缺表就建，不需要版本阶梯。
//    派工单点名「不动 BaopuDb 版本号」，这条库连版本号都没有，天然无从误升。
// 3. **坏库不猜**：[SearchDb] 不做「打不开就删文件重建」。删库是不可逆动作，而本批唯一的
//    写入方是设置页；真在机上打不开时，[SearchScopeStore] 的每个方法都吞成「没有授予/没有行」
//    （fail closed ⇒ 搜索范围退化，不会静默多读），错误经 `onFailure` 进 RunLog 给人看见。
package com.example.zhengdao.baopu.search

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.example.zhengdao.rootfs.RunLog
import java.io.File

/**
 * `search.db` 的连接壳（第一百九十七批）。
 *
 * 路径计算抽成纯函数 [dbPathUnder]（可 JVM 单测，房型照 `BaopuDb.dbPathUnder`）：
 * 落点错＝多读/少读，比写错更安静，所以这一行必须能被断言。
 * `SQLiteOpenHelper` 对绝对路径 name 的解析依赖 `Context.getDatabasePath` 的实现，
 * 本机无 Android 运行时 ⇒ 真机行为标③留给验收（失败形态是首开库时抛异常，响、可定位）。
 */
internal class SearchDb(context: Context) : SQLiteOpenHelper(
    context,
    dbPathUnder(context.filesDir).absolutePath,
    null,
    VERSION,
) {

    init {
        // 绝对路径名不走 getDatabaseDir 的自动父目录创建 ⇒ 显式建（照 BaopuDb.kt:1391 的处置）。
        runCatching { dbPathUnder(context.filesDir).parentFile?.mkdirs() }
        // WAL：读不挡写、写不挡读（S3 边扫边查时，检索不该被入库卡住）。
        setWriteAheadLoggingEnabled(true)
    }

    override fun onConfigure(db: SQLiteDatabase) {
        db.execSQL(SearchSql.PRAGMA_FK_ON)
    }

    override fun onCreate(db: SQLiteDatabase) {
        SearchSql.DDL.forEach { db.execSQL(it) }
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // 缓存库不迁移：老结构不认识就整包丢弃、按 onCreate 重建（可重建是这张库的立库前提）。
        runCatching { DROP_ALL.forEach { db.execSQL(it) } }.onFailure {
            RunLog.log("search.db 重建前的清理失败（$it）；下一次建表仍会 IF NOT EXISTS")
        }
        onCreate(db)
    }

    /** 写一条（记账/撤销）。返回是否执行成功——调用方按没写成处理，不许静默当成功。 */
    fun exec(sql: String, args: Array<Any?> = emptyArray()): Boolean = runCatching {
        writableDatabase.execSQL(sql, args)
        true
    }.getOrDefault(false)

    /**
     * 写侧句柄（房型照 `BaopuDb.write`：**给句柄，不包事务**）。事务三件套由调用方挂在自己的
     * lambda 里写——仓里已有九处这个形状（RunEngine／PlanStore／BaopuChatController 的
     * `db.write { d -> d.beginTransaction() … }`），[PhoneIndex.transact] 是第十处。
     *
     * 为什么不提成「带 `SQLiteDatabase` 形参的私有方法」：lint 的 `UseKtx` 只在那种形状下点
     * `beginTransaction`（2026-10-25 两轮现数：方法形参形状红、lambda 形状全绿）。它建议的
     * `SQLiteDatabase.transaction` 属 `androidx.core.database.sqlite.SQLiteDatabaseKt`（不是
     * `sqlite-ktx`——第一轮找错了 artifact），而本仓解析的 core-ktx 1.19.1 的 classes.jar 只有
     * 183 字节（unzip 现数）＝那个类不在这个版本里 ⇒ 建议用不了。不压制、不改基线。
     */
    fun <T> write(block: (SQLiteDatabase) -> T): T = block(writableDatabase)
    /**
     * 只读连接（查询面）。**不做 runCatching**：拿不到连接要在调用方那里被吞成
     * 「没有授予/没有行」，吞的位置只有一处（[SqlSearchScopeStore]），壳里再吞一层就查不到是谁放行的。
     */
    fun readable(): SQLiteDatabase = readableDatabase

    companion object {
        const val DIR_BAOPU = "baopu"
        const val DB_NAME = "search.db"

        /**
         * 壳自己的版本：`search.db` 里没有迁移阶梯（见类注释第 2 条），这个数只用来让
         * `SQLiteOpenHelper` 认表结构的存在与否——结构变了就**加一版**，走 [onUpgrade] 整包重建。
         */
        const val VERSION = 1

        /** 整包重建时先摘掉的四张表（顺序：叶子先走，级联不会被自己的外键挡住）。 */
        val DROP_ALL: List<String> = listOf(
            "DROP TABLE IF EXISTS tok",
            "DROP TABLE IF EXISTS doc",
            "DROP TABLE IF EXISTS watermark",
            "DROP TABLE IF EXISTS scope",
        )

        /** 库的绝对落点：`<filesDir>/baopu/search.db`（App 私有 ⇒ 不上云、别的 App 够不着）。 */
        fun dbPathUnder(filesDir: File): File = File(File(filesDir, DIR_BAOPU), DB_NAME)
    }
}
