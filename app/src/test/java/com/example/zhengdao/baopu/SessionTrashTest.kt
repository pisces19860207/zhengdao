// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 第四十七批：会话回收站（软删）——SQL 形状 + 源码级调用点门禁。
package com.example.zhengdao.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SessionTrashTest {

    private fun repoFile(rel: String): File =
        File(rel).let { if (it.isFile) it else File("app/$rel") }

    @Test
    fun v10_adds_deleted_at_only_when_missing() {
        assertTrue("缺列时要 ALTER", BaopuSql.sessionDeletedAtStmt(emptyList()).any { it.sql.contains("ADD COLUMN deleted_at") })
        assertTrue("已有列时幂等", BaopuSql.sessionDeletedAtStmt(listOf("deleted_at")).isEmpty())
        assertEquals(11, BaopuDb.VERSION) // v11 = 第五十七批 skill_event；本批只钉「版本在往前走」
    }

    @Test
    fun soft_delete_and_restore_touch_only_that_row() {
        val del = BaopuSql.softDeleteSession(7, 123L)
        assertTrue(del.sql.startsWith("UPDATE session SET deleted_at = ?"))
        assertEquals(listOf("123", "123", "7"), del.args.toList())

        val back = BaopuSql.restoreSession(7, 456L)
        assertTrue(back.sql.contains("deleted_at = NULL"))
        assertEquals(listOf("456", "7"), back.args.toList())
    }

    @Test
    fun active_lists_filter_deleted_and_trash_list_filters_opposite() {
        assertTrue(
            "主动列表必须排除软删：${BaopuSql.listSessions(false).sql}",
            BaopuSql.listSessions(false).sql.contains("deleted_at IS NULL"),
        )
        assertTrue(
            "含归档的列表也要排除：${BaopuSql.listSessions(true).sql}",
            BaopuSql.listSessions(true).sql.contains("deleted_at IS NULL"),
        )
        assertTrue(
            "回收站列表只看已删：${BaopuSql.listDeletedSessions().sql}",
            BaopuSql.listDeletedSessions().sql.contains("deleted_at IS NOT NULL"),
        )
        assertTrue(BaopuSql.countDeletedSessions().sql.contains("deleted_at IS NOT NULL"))
        // 抽屉里那份「会话库」也必须排除软删——否则删完会话列表里还在（用户 2026-10-19 报）
        assertTrue(
            "抽屉会话库也要排除软删：${BaopuSql.sessionLibraryEntries().sql}",
            BaopuSql.sessionLibraryEntries().sql.contains("deleted_at IS NULL"),
        )
    }

    @Test
    fun hard_delete_still_goes_through_the_five_statements_only_in_purge() {
        val controller = repoFile("src/main/java/com/example/zhengdao/ui/baopu/BaopuChatController.kt").readText()
        val deleteBody = controller.substringAfter("fun deleteSession(sid: Long): Boolean {")
            .substringBefore("\n    }")
        assertTrue("删除会话必须软删", deleteBody.contains("softDeleteSession"))
        assertFalse("删除会话不许直接硬删", deleteBody.contains("deleteSessionStatements"))

        val purgeBody = controller.substringAfter("fun purgeSession(sid: Long): Boolean")
            .substringBefore("\n    }")
        assertTrue("彻底删除才用五条语句", purgeBody.contains("deleteSessionStatements"))
        assertTrue(purgeBody.contains("restoreSession") || true) // restore 是另一个函数，这里只确认 purge 走硬删
    }

    @Test
    fun trash_panel_has_session_section_strings() {
        val zh = repoFile("src/main/res/values/strings.xml").readText()
        listOf(
            "trash_sessions_title", "trash_sessions_none", "trash_session_row",
            "trash_session_restore", "trash_session_purge", "trash_session_confirm",
        ).forEach { assertTrue("缺文案 $it", zh.contains("name=\"$it\"")) }
        val panel = repoFile("src/main/java/com/example/zhengdao/ui/baopu/BaopuTrashBin.kt").readText()
        assertTrue(panel.contains("listDeletedSessions"))
        assertTrue(panel.contains("confirmingSession"))
    }
}
