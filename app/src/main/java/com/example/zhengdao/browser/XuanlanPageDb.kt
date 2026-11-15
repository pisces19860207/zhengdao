// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。

package com.example.zhengdao.browser

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.example.zhengdao.core.RunLog
import java.io.File

/**
 * `xuanlan_page.db` 的连接壳（651）。形状照 `SearchDb`（197 批）：**薄壳**——算路径、开连接、
 * 执行语句；判据全在 [PageMemoryPolicy] / [PageMemorySql]（纯文本，JVM 直测）。
 *
 * 三处刻意：
 * 1. **不动 `BaopuDb`**（连版本号都不碰）：页面记忆是"看过的页"这种便利账，不值得为它去改
 *    主库版本、连带 6 份别的批次的迁移门禁。
 * 2. **不删库**：结构真不认识时只 `DROP TABLE xuanlan_page` 再建（[onUpgrade]），
 *    别的表（如果将来有）一根手指都不碰。删库是不可逆动作，壳里不许有。
 * 3. **失败只记日志不抛**：记忆是增强，不是主链路——写不进去时玄览照常用，
 *    只是"最近 20 页"少一条（`PageMemoryStore` 侧同样吞成空列表，fail closed）。
 *
 * 落点：`<filesDir>/baopu/xuanlan_page.db`（App 私有 ⇒ 别的 App 够不着、不上云）。
 */
internal class XuanlanPageDb(context: Context) : SQLiteOpenHelper(
    context,
    dbPathUnder(context.filesDir).absolutePath,
    null,
    VERSION,
) {

    init {
        // 绝对路径名不走 getDatabaseDir 的自动父目录创建 ⇒ 显式建（照 SearchDb / BaopuDb 的处置）。
        runCatching { dbPathUnder(context.filesDir).parentFile?.mkdirs() }
        // WAL：写一条页记录不该把界面那侧的读卡住。
        setWriteAheadLoggingEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        PageMemorySql.DDL.forEach { db.execSQL(it) }
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // 记忆不是可重建的缓存，但结构不认识时只能重来：只清这一张表，并如实留一行日志，
        // 让"为什么我的最近 20 页空了"在日志里查得到（不许静默）。
        RunLog.log("玄览 页记忆：库结构 $oldVersion → $newVersion，清空 xuanlan_page 重建（其余表不动）")
        runCatching { db.execSQL(PageMemorySql.DROP_TABLE) }
        onCreate(db)
    }

    /** 写侧句柄（房型照 `BaopuDb.write`：给句柄，不包事务——事务由调用方自己挂）。 */
    fun <T> write(block: (SQLiteDatabase) -> T): T = block(writableDatabase)

    /** 只读句柄。 */
    fun read(): SQLiteDatabase = readableDatabase

    companion object {
        const val DIR_BAOPU = "baopu"
        const val DB_NAME = "xuanlan_page.db"

        /**
         * 壳自己的版本：没有迁移阶梯，只有"结构不认识就清表重建"。
         * 结构变了才加一（[PageMemorySql.DDL] 与 [PageMemoryPolicy.COLUMNS] 一起改）。
         */
        const val VERSION = 1

        /** 库的绝对落点（抽成纯函数：落点错＝记到别人看不见的地方，必须能被断言）。 */
        fun dbPathUnder(filesDir: File): File = File(File(filesDir, DIR_BAOPU), DB_NAME)
    }
}
