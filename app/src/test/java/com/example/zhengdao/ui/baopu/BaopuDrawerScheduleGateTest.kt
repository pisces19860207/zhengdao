// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百二十九批 §1：定时任务入口归位——抽屉**第一组**「新会话」紧跟其后，设置组保留跳转。
//
// 派工单判据：拉开抽屉 ⇒ 第一组依次是「新会话」「定时任务」；设置组里仍有跳转行；
// 两处点进去是同一个页面。负例：两处进两个页面／定时任务被塞进设置组最底部／
// 抽屉顺序变成 新会话→设置→定时任务。Compose 层在 JVM 直测不了 ⇒ 按源码顺序钉：
// 行的渲染顺序就是书写顺序（同一个 Column 里先写的先画）。
package com.example.zhengdao.ui.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BaopuDrawerScheduleGateTest {

    private fun source(): String {
        val rels = listOf(
            "src/main/java/com/example/zhengdao/ui/baopu/BaopuDrawer.kt",
            "../app/src/main/java/com/example/zhengdao/ui/baopu/BaopuDrawer.kt",
            "app/src/main/java/com/example/zhengdao/ui/baopu/BaopuDrawer.kt",
        )
        val f = rels.map(::File).firstOrNull { it.isFile }
            ?: throw AssertionError("找不到 BaopuDrawer.kt（工作目录 ${File(".").absolutePath}）")
        return f.readText()
    }

    @Test
    fun `第一组顺序_新会话之后紧跟定时任务_然后才是工作区组`() {
        val s = source()
        val atSessionGroup = s.indexOf("R.string.baopu_drawer_group_session")
        val atNewSession = s.indexOf("R.string.baopu_new_session")
        val atScheduleRow = s.indexOf("ScheduleDrawerRow(status = schedStatus")
        val atWorkspaceGroup = s.indexOf("R.string.baopu_drawer_group_workspace")
        listOf(atSessionGroup, atNewSession, atScheduleRow, atWorkspaceGroup)
            .forEach { assertTrue("锚点丢了（文件结构变了要回来重新钉）：$it", it >= 0) }
        assertTrue(
            "抽屉第一组必须依次是「新会话」→「定时任务」（229 归位），然后才轮到工作区组",
            atSessionGroup < atNewSession && atNewSession < atScheduleRow && atScheduleRow < atWorkspaceGroup,
        )
    }

    @Test
    fun `第二百八十六批_抽屉里只有一个定时任务入口_设置组整组已删`() {
        // 229 批曾在「设置」组保留一条跳转行（"老用户找得到"），结果抽屉里出现两个同名条目；
        // 2026-10-30 用户裁定：「只留『新会话』下面那一个」。同日「口味档案」也搬去律令 ⇒
        // 「设置」组清空、整组删除。本判据钉新形态：
        val s = source()
        assertFalse(
            "「设置」组必须已从抽屉删除（组里两行都搬走了）",
            s.contains("R.string.baopu_drawer_group_settings"),
        )
        assertEquals(
            "抽屉里「定时任务」只许剩一处（会话组那条带状态位的）",
            1,
            Regex("R\\.string\\.baopu_drawer_sched\\b").findAll(s).count(),
        )
        assertEquals(
            "onScheduleTasks 只许一处调用（跳转行已删）",
            1,
            Regex("onClick = onScheduleTasks").findAll(s).count(),
        )
        // 口味档案也搬走了：抽屉里不许再有它的入口。
        assertFalse(
            "口味档案已搬去律令「抱朴」组，抽屉里不许再有入口",
            s.contains("R.string.baopu_taste_title"),
        )
        // 「为什么在这里」要写在代码里：它是抱朴自己的工作安排，不是设置项（派工单 §1③）。
        assertTrue(
            "归位理由必须写在注释里（不是设置项）",
            s.contains("不是设置项"),
        )
    }
}
