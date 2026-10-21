// 独立开发声明：本文件为本项目从零编写。
package com.example.zhengdao.ui.baopu

import com.example.zhengdao.baopu.BaopuSql
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 第一百二十一批第一件：`purgeSession` 转正 —— 回收站「彻底删除」接线守卫。
 *
 * 背景：`BaopuChatController.purgeSession`（47 批写下的硬删口子）一直没有生产调用方，
 * 回收站面板自己内联了一份一模一样的 `db.write { deleteSessionStatements }`。
 * 本批把面板改走控制器口子（SQL 形状只许 `deleteSessionStatements` 一处定），
 * 顺手把 47 批起就断着的「onSessionLibraryChanged → TrashDialog.onSessionsChanged」
 * 那条线接上（页面一直传着，抽屉却没用它 ⇒ 恢复/彻底删除后会话库不重读）。
 *
 * 判据（派工单 §1 点名四条，源码/清单文本级，先剥注释再断言；SQL 收敛层走 JVM 直测）：
 * ① 删 A 不影响 B——每条语句只带这一个会话的 id，面板传的也是这一行的 id；
 * ② 产物/计划同清——计划快照（plan_batch 两代）显式删，产物（artifact）靠 DDL 级联不许重复删
 *    （级联本体由 `BaopuDbTest` 的 DDL 反推门禁钉，这里只留本批可读形式）；
 * ③ 失败回滚/保留——六条语句在同一个写事务里同步等结果，失败即整体保留且嘴上要说；
 * ④ 负例「只删消息留产物 ⇒ 红」——合成一段图省事的实现，门禁必须拦得下。
 */
class SessionPurgeWiringTest {

    private fun repoFile(rel: String): File =
        File(rel).let { if (it.isFile) it else File("app/$rel") }

    private fun code(rel: String): String = repoFile(rel).readText()
        .replace("\r\n", "\n")
        .replace(Regex("(?s)/\\*.*?\\*/"), "")
        .replace(Regex("(?m)^[ \\t]*//.*$"), "")

    private fun trashPanel() = code("src/main/java/com/example/zhengdao/ui/baopu/BaopuTrashBin.kt")

    private fun drawer() = code("src/main/java/com/example/zhengdao/ui/baopu/BaopuDrawer.kt")

    private fun screen() = code("src/main/java/com/example/zhengdao/ui/baopu/BaopuScreen.kt")

    private fun controllerPurgeBody(): String =
        code("src/main/java/com/example/zhengdao/ui/baopu/BaopuChatController.kt")
            .substringAfter("fun purgeSession(sid: Long): Boolean")
            .substringBefore("\n    fun ")

    private fun panelPurgeRowBody(): String =
        trashPanel()
            .substringAfter("fun purgeSessionRow(id: Long, title: String) {")
            .substringBefore("\n    }")

    @Test
    fun `删A不影响B_每条语句都按sid收敛_面板传的也是这一行的id`() {
        // SQL 层：六条语句一条不许碰别的会话（与 BaopuDbTest 同根源，这里按派工单留本批可读形式）
        BaopuSql.deleteSessionStatements(7L).forEach { st ->
            assertTrue("每条语句只带这一个会话的 id：${st.sql}", st.args.contentEquals(arrayOf("7")))
            assertTrue(
                "WHERE 必须按 session_id/id 收敛（不许全表删）：${st.sql}",
                st.sql.contains("session_id = ?") || st.sql.contains("id = ?"),
            )
        }
        // 接线层：面板把**这一行**的 id 交出去，不是列表里第一条、也不是什么全局量
        assertTrue("第二击必须传这一行的 id", panelPurgeRowBody().contains("onPurgeSession(id)"))
    }

    @Test
    fun `产物计划同清_计划显式删_产物靠级联不重复删`() {
        val deleted = BaopuSql.deleteSessionStatements(1L).joinToString("\n") { it.sql }
        assertTrue("计划快照（plan_batch 父子两代）必须显式清", deleted.contains("DELETE FROM plan_batch"))
        assertTrue("压缩摘要替身必须显式清（E-158：漏了它就永远删不掉）", deleted.contains("DELETE FROM compression"))
        assertFalse(
            "产物（artifact）靠 DDL 的 ON DELETE CASCADE，不许在语句里重复删",
            deleted.contains("DELETE FROM artifact"),
        )
    }

    @Test
    fun `失败回滚或整体保留_单事务加同步等结果`() {
        val purge = controllerPurgeBody()
        assertEquals(
            "六条语句必须待在同一个写事务里（删一半断电等于没删，失败即整体保留）",
            1,
            Regex("db\\.write").findAll(purge).count(),
        )
        assertTrue("同步等结果再返回（Future.get）——失败不许吞在异步里", purge.contains(".get()"))
        assertTrue("异常要变成 false 交回界面，不许炸", purge.contains("runCatching"))
        // 界面层：失败也要说出来（派工单「失败可读、不静默」）
        assertTrue("成功有话说", panelPurgeRowBody().contains("R.string.trash_session_purged"))
        assertTrue("失败也要说出来（不许静默）", panelPurgeRowBody().contains("R.string.trash_session_failed"))
    }

    @Test
    fun `负例_只删消息留产物_门禁必须拦得下`() {
        // 合成一段「图省事只删 message」的彻底删除实现——按判据它必须判红
        val lazy = """
            val ok = runCatching {
                db.write { d ->
                    d.execSQL("DELETE FROM message WHERE session_id = ?", arrayOf(id.toString()))
                }.get()
            }.isSuccess
        """.trimIndent()
        assertFalse("只删 message 不留产物/计划 ⇒ 必须判红", noHandRolledPartialDelete(lazy))
        // 真实两条实现都过：它们一条字面 DELETE 都没有（硬删形状只走 deleteSessionStatements）
        assertTrue("控制器口子", noHandRolledPartialDelete(controllerPurgeBody()))
        assertTrue("面板口子", noHandRolledPartialDelete(panelPurgeRowBody()))
    }

    /** 硬删判据：要么一条字面 DELETE 都没有（走 deleteSessionStatements 口子），要么五张表一张不漏。 */
    private fun noHandRolledPartialDelete(body: String): Boolean {
        val tables = Regex("DELETE FROM (\\w+)").findAll(body).map { it.groupValues[1] }.toSet()
        return tables.isEmpty() ||
            tables.containsAll(setOf("plan_batch", "run_log", "compression", "message", "session"))
    }

    @Test
    fun `面板彻底删除走控制器口子_不自己开库`() {
        val row = panelPurgeRowBody()
        assertTrue("第二击必须走 onPurgeSession 口子", row.contains("onPurgeSession(id)"))
        assertFalse("面板不许再内联 db.write 硬删（SQL 形状只许一处定）", row.contains("db.write"))
        assertFalse("面板不许再内联 deleteSessionStatements", row.contains("deleteSessionStatements"))
    }

    @Test
    fun `两击确认仍在_确认位用完要清掉`() {
        val row = panelPurgeRowBody()
        assertTrue("第一击只记确认位、不动盘（复用现有确认卡）", row.contains("if (confirmingSession != id)"))
        assertTrue("删完必须清掉确认位（不许记住上次的许可）", row.contains("confirmingSession = null"))
    }

    @Test
    fun `删完要让会话库重读_与恢复对称`() {
        assertTrue(
            "彻底删除后也要回调 onSessionsChanged（与 restoreSessionRow 对称）",
            panelPurgeRowBody().contains("onSessionsChanged()"),
        )
    }

    @Test
    fun `抽屉透传口子_并接上断了好久的会话库刷新线`() {
        val d = drawer()
        assertTrue("抽屉要声明 onPurgeSession 形参", d.contains("onPurgeSession: (Long) -> Boolean,"))
        assertTrue("抽屉必须把 onPurgeSession 透传给面板", d.contains("onPurgeSession = onPurgeSession"))
        // 47 批起 onSessionLibraryChanged 只有声明没人接（页面一直传着）⇒ 本批接上
        assertTrue(
            "恢复/彻底删除后重读会话库的线要接到面板上",
            d.contains("onSessionsChanged = onSessionLibraryChanged"),
        )
    }

    @Test
    fun `页面把控制器口子接进抽屉`() {
        assertTrue(
            "BaopuScreen 必须传 controller.purgeSession",
            screen().contains("onPurgeSession = { controller.purgeSession(it) }"),
        )
    }
}
