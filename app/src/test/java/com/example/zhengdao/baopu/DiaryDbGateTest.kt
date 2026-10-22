// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.baopu

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 147 阶段① · v17 迁移与索引的**源码级门禁**（本仓无 Robolectric ⇒ SQLite 进不了
 * JVM 单测，源码即唯一真源——与 EvolutionShadowGateTest / MemoryQualityGateTest 同惯例）。
 *
 * 覆盖（判据 19 的可测半边）：VERSION=17、迁移链两处、`diary_index` DDL 存在、
 * **DDL 在裸线程冻结点之下（零位移纪律）**、[DiaryIndexStore] 的重建/读写口齐全。
 * 真机迁移路径（旧库升级）留仪器验收（P1-1 CI 模拟器挂账延续）。
 */
class DiaryDbGateTest {

    private val src: String by lazy {
        val cands = listOf(
            File("app/src/main/java/com/example/zhengdao/baopu/BaopuDb.kt"),
            File("../app/src/main/java/com/example/zhengdao/baopu/BaopuDb.kt"),
        )
        cands.firstOrNull { it.isFile }?.readText()
            ?: throw AssertionError("找不到 BaopuDb.kt")
    }

    /** 剥整行 `//` 注释（防注释里的反例词误伤，房规）。 */
    private val code: String by lazy { src.replace(Regex("(?m)^[ \\t]*//.*$"), "") }

    private fun lineOf(needle: String): Int =
        code.lines().indexOfFirst { it.contains(needle) } + 1

    @Test
    fun `门禁_VERSION17与迁移链两处`() {
        assertTrue("VERSION 应为 17", code.contains("const val VERSION = 17"))
        val chainHits = Regex("applyV17Migration\\(db\\)").findAll(code).count()
        assertTrue(
            "迁移链应有两处调用（onCreate 全量链 ＋ oldVersion 升级链），实测 $chainHits",
            chainHits >= 2,
        )
        assertTrue("应有迁移体定义", code.contains("private fun applyV17Migration"))
        assertTrue("应有 diary_index 建表", code.contains("CREATE TABLE IF NOT EXISTS diary_index"))
    }

    @Test
    fun `门禁_DDL在裸线程冻结点之下_零位移`() {
        val freeze = lineOf("zd-baopu-db")
        val ddl = lineOf("CREATE TABLE IF NOT EXISTS diary_index")
        assertTrue("冻结点应存在（对账单锚）", freeze > 0)
        assertTrue(
            "diary_index DDL（:$ddl）必须在 zd-baopu-db 创建点（:$freeze）之下——" +
                "BaopuSql（文件头）在冻结点之上，往那里加行会平移对账单",
            ddl > freeze,
        )
    }

    @Test
    fun `门禁_索引读写口齐全_文件是真相`() {
        for (needle in listOf(
            "internal class DiaryIndexStore",
            "fun replace(e: DiaryEntry)",
            "fun deleteById(id: String)",
            "fun rebuild(entries: List<DiaryEntry>)",
            "DELETE FROM diary_index",
        )) {
            assertTrue("缺 $needle", code.contains(needle))
        }
        // 索引不存正文与产物清单——那些以日记文件为真相（方案 §2）。
        // ⚠️ 负例断言必须**只查 diary_index 的 DDL 块**：别表（compression/sub_task）本来就有
        // 自己的 summary 列，扫全文件会把别人的合法列当缺陷（137 批同款教训）。
        val diaryDdl = code.substringAfter("CREATE TABLE IF NOT EXISTS diary_index(")
            .substringBefore(")")
        assertFalse("diary_index 不得存 summary", diaryDdl.contains("summary"))
        assertFalse("diary_index 不得存产物清单", diaryDdl.contains("artifacts"))
        assertFalse("diary_index 不得存正文", diaryDdl.contains("body"))
    }
}
