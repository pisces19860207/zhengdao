// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百九十五批 · `logistics.db` 的连接壳（薄壳；形状与判据全在 [LogisticsSql]）。
//
// 房型照 `baopu/search/SearchDb.kt`（本仓库既有的「独立小库」三原则），三条对得上理由也对得上：
// 1. **不起线程**：`BareThreadGuardTest` 按 file:line 钉着全仓每一个裸 `Thread(...)`，
//    这里只有「导入那一下」和「查一句话」，都是调用方线程上的低频活；
// 2. **不动主库**：`BaopuDb` 的版本号一个字节都不碰（派工单 §2 红线），这张库自己带版本、
//    结构不认识就整包重建——它是**可重建缓存**，源表还在 `产物/物流/inbox/`；
// 3. **坏库不猜**：不做「打不开就删文件」。删库不可逆，而库里存着她勾过的状态
//    （`status_user`），那是人的判断、不是能重新导出来的东西。
package com.example.zhengdao.baopu.logistics

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.example.zhengdao.rootfs.RunLog
import java.io.File

/**
 * `logistics.db` 的连接壳。
 *
 * 路径计算是纯函数 [LogisticsPaths.dbPathUnder]（可 JVM 单测）：落点错了查询就永远查不到，
 * 而响起来像「表里没有」——那是最难查的一种安静错，所以这一行必须能被断言。
 * `SQLiteOpenHelper` 对绝对路径的解析依赖 `Context.getDatabasePath` 实现，本机 JVM 跑不了 ⇒
 * 真机行为留给验收（失败形态是首开库就抛，响、可定位）。
 */
internal class LogisticsDb(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    dbPathUnder(context.filesDir).absolutePath,
    null,
    LogisticsSql.VERSION,
) {

    init {
        // 绝对路径名不走 getDatabaseDir 的自动父目录创建 ⇒ 显式建（照 SearchDb.kt:44 的处置）
        runCatching { dbPathUnder(context.filesDir).parentFile?.mkdirs() }
        // WAL：导入写的时候面板还能读（她一边让人导表一边问「北京装了没」是真会发生的事）
        setWriteAheadLoggingEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        LogisticsSql.DDL.forEach { db.execSQL(it) }
    }

    /**
     * 老库少列 ⇒ 补列，不删库（里面有用户勾选）。
     *
     * 补完之后还剩「认不出的多余列」这种事：SQLite 加列不影响读写，多余列放着不管，
     * 删列才是不可逆动作，而这张库的唯一真相来源是 inbox 里的导出表。
     */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        val have = columnsOf(db)
        if (have.isEmpty()) {
            // 连表都查不到 ⇒ 结构不认识。整包重建（可重建是这张库的立库前提）
            LogisticsSql.DROP_ALL.forEach { runCatching { db.execSQL(it) } }
            onCreate(db)
            return
        }
        LogisticsSql.alterFor(have).forEach { sql ->
            runCatching { db.execSQL(sql) }.onFailure { RunLog.log("logistics.db 补列失败：${sql.substringBefore("DEFAULT")}（$it）") }
        }
        onCreate(db)
    }

    /** 读现有列名（`PRAGMA table_info`）。表不存在时返回空集 ⇒ 调用方按「整包重建」处理。 */
    private fun columnsOf(db: SQLiteDatabase): Set<String> = runCatching {
        db.rawQuery("PRAGMA table_info(${LogisticsSql.TABLE})", null).use { c ->
            val name = c.getColumnIndexOrThrow("name")
            val out = HashSet<String>()
            while (c.moveToNext()) out += c.getString(name)
            out
        }
    }.getOrDefault(emptySet())

    /** 只读连接：不做 runCatching，吞异常的位置只有一处（[LogisticsStore]），壳里再吞就查不出是谁挡的。 */
    fun readable(): SQLiteDatabase = readableDatabase

    /** 写连接（导入与勾选状态）。 */
    fun writable(): SQLiteDatabase = writableDatabase

    companion object {
        fun dbPathUnder(filesDir: File): File = LogisticsPaths.dbPathUnder(filesDir)
    }
}
