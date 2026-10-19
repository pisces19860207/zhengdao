// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 第四十批：编辑上一条要用的三条 SQL（改正文 / 删后面的消息 / 清计划快照）。
// 本仓库单测环境没有 Robolectric（见 BaopuDbTest 的说明），所以这里钉的是
// **语句文本与参数绑定顺序**——真实 SQLite 行为在真机上验。
package com.example.zhengdao.baopu

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class BaopuEditSqlTest {

    @Test
    fun `改用户正文_只动 text part`() {
        val s = BaopuSql.updateUserText(42L, "把「附件」改成「附件三」")

        assertEquals(
            "UPDATE part SET payload = ? WHERE message_id = ? AND kind = 'text'",
            s.sql,
        )
        // 绑定顺序：先正文、后 messageId（写反了会拿 id 当正文，静默改坏）
        assertArrayEquals(arrayOf<String?>("把「附件」改成「附件三」", "42"), s.args)
    }

    @Test
    fun `删后面的消息_按会话加主键边界`() {
        val s = BaopuSql.deleteMessagesAfter(sessionId = 7L, afterId = 42L)

        assertEquals("DELETE FROM message WHERE session_id = ? AND id > ?", s.sql)
        assertArrayEquals(arrayOf<String?>("7", "42"), s.args)
    }

    @Test
    fun `清计划快照_只清这个会话`() {
        val s = BaopuSql.deletePlanBatches(7L)

        assertEquals("DELETE FROM plan_batch WHERE session_id = ?", s.sql)
        assertArrayEquals(arrayOf<String?>("7"), s.args)
    }
}
