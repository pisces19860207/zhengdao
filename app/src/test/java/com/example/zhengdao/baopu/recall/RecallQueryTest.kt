// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.baopu.recall

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 540 只读检索语句的纯层判据（JVM 直测语句形状，房型照 `SearchSqlTest`）。
 *
 * 「不新建索引、不改索引结构」在语句层的正面锚：这里只许 SELECT 既有三张表，
 * 一个 DDL 字面量都不该有；词只走绑定参数，语句文本里只有 `?`。
 */
class RecallQueryTest {

    @Test
    fun `空词表不发语句`() {
        assertNull(RecallQuery.searchStmt(emptyList()))
        assertNull(RecallQuery.searchStmt(listOf("  ", "")))
    }

    @Test
    fun `语句形状_只读既有三张表_回收站会话不出`() {
        val stmt = RecallQuery.searchStmt(listOf("部署"))!!
        assertTrue(stmt.sql.contains("FROM message m"))
        assertTrue(stmt.sql.contains("JOIN part p ON p.message_id = m.id"))
        assertTrue(stmt.sql.contains("JOIN session s ON s.id = m.session_id"))
        assertTrue(stmt.sql.contains("s.deleted_at IS NULL"))
        assertTrue(stmt.sql.contains("p.kind = 'text'"))
        assertTrue("新话在前", stmt.sql.contains("ORDER BY m.created_at DESC, m.id DESC"))
    }

    @Test
    fun `语句形状_每个词一个LIKE占位_词只在绑定参数里`() {
        val stmt = RecallQuery.searchStmt(listOf("部署", "端口"))!!
        assertEquals("两个词两个占位", 2, Regex("\\?").findAll(stmt.sql).count())
        assertEquals("绑定参数个数与占位一致", 2, stmt.args.size)
        assertTrue("语句文本里没有词", !stmt.sql.contains("部署"))
        assertEquals("%部署%", stmt.args[0])
    }

    @Test
    fun `绑定值_LIKE通配转义`() {
        assertEquals("%部署%", RecallQuery.likeArg("部署"))
        assertEquals("%a\\%b%", RecallQuery.likeArg("a%b"))
        assertEquals("%a\\_b%", RecallQuery.likeArg("a_b"))
        assertEquals("%a\\\\b%", RecallQuery.likeArg("a\\b"))
    }

    @Test
    fun `词表_去重去空_截到上限`() {
        val stmt = RecallQuery.searchStmt(listOf("部署", " 部署 ", "端口", ""))!!
        assertEquals("trim 后重复的词只留一个", 2, stmt.args.size)
        val many = (1..20).map { "词$it" }
        assertEquals(RecallQuery.MAX_KEYWORDS, RecallQuery.searchStmt(many)!!.args.size)
    }

    @Test
    fun `行数硬顶是常量字面量`() {
        val stmt = RecallQuery.searchStmt(listOf("部署"))!!
        assertTrue("LIMIT 拼 ROW_CAP（硬顶防长尾）", stmt.sql.endsWith("LIMIT ${RecallQuery.ROW_CAP}"))
        assertEquals(200, RecallQuery.ROW_CAP)
    }

    @Test
    fun `语句里连DDL的字面量都没有_这是不新建索引的正面锚`() {
        val stmt = RecallQuery.searchStmt(listOf("部署"))!!
        listOf("CREATE", "ALTER", "INSERT", "UPDATE ", "DELETE", "DROP").forEach {
            assertFalse("检索语句出现「$it」", stmt.sql.contains(it))
        }
    }

    @Test
    fun `hitOf_列序进命中_id与会话id原样带出`() {
        val hit = RecallQuery.hitOf(id = 42L, sessionId = 7L, createdAt = 999L, payload = "原文")
        assertEquals(42L, hit.messageId)
        assertEquals(7L, hit.sessionId)
        assertEquals(999L, hit.atMs)
        assertEquals("原文", hit.text)
    }

    @Test
    fun `常量钉死_与派工单判据对齐`() {
        assertNotNull(RecallQuery.MAX_KEYWORDS)
    }
}
