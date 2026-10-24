// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
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
    fun `门禁_VERSION18与v17迁移链两处`() {
        // 157 补充把版本推到 18（workspace_grant/workspace_audit）；本条钉「版本号就是 18」
        // ＋「v17 迁移体还挂在链上两处」——语义要动就一起动，不许只改一半。
        assertTrue("VERSION 应为 19（v19 = 172 L4）", code.contains("const val VERSION = 19"))
        assertTrue("v18 迁移体必须在链上", code.contains("applyV18Migration(db)"))
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

    private val storeSrc: String by lazy {
        val cands = listOf(
            File("app/src/main/java/com/example/zhengdao/baopu/DiaryStore.kt"),
            File("../app/src/main/java/com/example/zhengdao/baopu/DiaryStore.kt"),
        )
        cands.firstOrNull { it.isFile }?.readText()
            ?: throw AssertionError("找不到 DiaryStore.kt")
    }

    private val storeCode: String by lazy { storeSrc.replace(Regex("(?m)^[ \\t]*//.*$"), "") }

    @Test
    fun `门禁_索引读写口齐全_文件是真相`() {
        for (needle in listOf(
            "internal class DiaryIndexStore",
            "fun replace(e: DiaryEntry)",
            "fun deleteById(id: String): Int",
            "fun rebuild(entries: List<DiaryEntry>)",
            "d.delete(\"diary_index\"",
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

    /**
     * 阶段③ · 真删的索引半边接线（判据 18 的 DB 半边）：
     * 本仓无 Robolectric ⇒ 索引删除**功能**验不了，钉源码接线——
     * [DiaryStore.realDelete] 必须真的去调 [DiaryIndexStore.deleteById]，
     * 且**只删 diary_index**（不得顺手碰别的表）。
     */
    @Test
    fun `门禁_真删接线索引删除_只碰diary_index`() {
        assertTrue("realDelete 应存在", storeCode.contains("fun realDelete(plan: DiaryDeletionPlan)"))
        assertTrue(
            "realDelete 必须调 DiaryIndexStore(db).deleteById（四处全消的索引半边）",
            storeCode.contains("DiaryIndexStore(d)") && storeCode.contains("idx.deleteById(it)"),
        )
        // DiaryIndexStore.deleteById 的删除面：只许 diary_index（真删红线）
        val idxBody = code.substringAfter("fun deleteById(id: String): Int")
            .substringBefore("fun count()")
        val tables = Regex("(?:DELETE FROM|d\\.delete\\(\\s*\")(\\w+)").findAll(idxBody)
            .map { it.groupValues[1] }.toList()
        assertTrue("索引删除只许指向 diary_index，实测 $tables", tables.all { it == "diary_index" })
        assertTrue("索引删除应至少指向 diary_index", tables.isNotEmpty())
    }
}
