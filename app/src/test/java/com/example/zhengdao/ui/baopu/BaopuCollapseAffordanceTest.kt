// 独立开发声明：本文件为本项目从零编写。
package com.example.zhengdao.ui.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「一轮展开后能收回 / 明细点开不推正文」的守卫（2026-10-18 真机两次反馈的最终落点）。
 *
 * 历史：
 * - 106 批把常驻「收起详情」浮标换成轮尾活动面板，入口不再随内容滚走；
 * - 139 批**再收口**：步骤卡/工具卡撤出正文，轮尾只留一行过程条（记录条容器），
 *   点开的是 `Popup` 浮层明细——**明细不进布局流，正文长度不变**，因此不存在
 *   「展开就收不回去」的整轮高度问题；用户能展开的也不再是正文。
 *
 * 四组判据：
 * ① 正文的收/展只看运行态与「要人动手」卡（`expandedRoundId` / `userExpandedIds` 全删）；
 * ② 浮标整块移除（源码、56dp 让位、中英字符串键）；
 * ③ 内联活动面板已被过程条取代（不再有 `BaopuActivityPanel`，也不许只是改个名）；
 * ④ 钉住口径不被待过目候选拖住（不动）。
 */
class BaopuCollapseAffordanceTest {

    private fun repoFile(rel: String): File =
        File(rel).let { if (it.isFile) it else File("app/$rel") }

    private fun code(rel: String): String = repoFile(rel).readText()
        .replace("\r\n", "\n")
        .replace(Regex("(?s)/\\*.*?\\*/"), "")
        .replace(Regex("(?m)^[ \\t]*//.*$"), "")

    private fun list() = code("src/main/java/com/example/zhengdao/ui/baopu/BaopuMessageList.kt")

    private fun bar() = code("src/main/java/com/example/zhengdao/ui/baopu/BaopuProcessBar.kt")

    private fun controller() = code("src/main/java/com/example/zhengdao/ui/baopu/BaopuChatController.kt")

    // ① 不动：正文收/展只看运行态与钉住卡

    @Test
    fun `正文收起不再有用户展开态`() {
        val src = list()
        assertFalse(
            "139 起用户展开的是浮层明细，不是正文——expandedRoundId 必须删干净",
            src.contains("expandedRoundId"),
        )
        assertFalse("也不许换个名字把整轮展开状态搬回 item", src.contains("var userOpened by remember"))
        assertFalse(
            "roundCollapsed 不许再收用户展开集合（那会把正文拉长）",
            src.contains("userExpandedIds"),
        )
        assertTrue(
            "收工后当前轮自动收起（一轮结束收成一行）",
            src.contains("else -> true"),
        )
    }

    // ② 反转：浮标整块移除

    @Test
    fun `收起详情浮标整块移除`() {
        val src = list()
        assertFalse("浮标必须移除（用户点名）", src.contains("baopu_collapse_detail"))
        assertFalse("56dp 让位是浮标时代的遗留，一并删掉", src.contains("bottom = 56.dp"))
    }

    @Test
    fun `中英文都不再有浮标文案`() {
        val zh = repoFile("src/main/res/values/strings.xml").readText()
        val en = repoFile("src/main/res/values-en/strings.xml").readText()
        assertFalse("中文不该再有 baopu_collapse_detail", zh.contains("name=\"baopu_collapse_detail\""))
        assertFalse("英文不该再有 baopu_collapse_detail", en.contains("name=\"baopu_collapse_detail\""))
    }

    // ③ 收口：过程条是唯一入口，点在整行上，明细走浮层

    @Test
    fun `过程条挂在轮尾_是唯一收起入口`() {
        val src = list()
        assertTrue("轮尾必须有过程条", src.contains("BaopuProcessBar("))
        assertFalse("旧活动面板必须删掉（不是改名保留）", src.contains("BaopuActivityPanel"))
        assertFalse("旧摘要行必须删掉", src.contains("BaopuRoundSummaryRow"))

        val forEachIdx = src.indexOf("round.messages.forEach { msg ->")
        val barIdx = src.indexOf("        BaopuProcessBar(")
        assertTrue(
            "过程条在轮内消息（含最终回答）之后——轮尾唯一信息块",
            forEachIdx > 0 && barIdx > forEachIdx,
        )
        assertEquals(
            "一轮只挂一条过程条（不许收起/展开两支各挂一条）",
            1,
            Regex("(?m)^        BaopuProcessBar\\(").findAll(src).count(),
        )
    }

    @Test
    fun `过程条整行可点_明细浮层可收`() {
        val src = bar()
        assertTrue("整行点击交给记录条容器", src.contains("BaopuRecordRow("))
        assertTrue("同一入口翻转展开态", src.contains("onClick = { open = !open }"))
        assertTrue("明细必须是 Popup（不进布局流）", src.contains("Popup("))
        assertTrue("点外面收回", src.contains("dismissOnClickOutside = true"))
        assertTrue("返回键收回", src.contains("dismissOnBackPress = true"))
        assertFalse("不许退回行内展开", src.contains("AnimatedVisibility"))
    }

    // ④ 不动：钉住口径

    @Test
    fun `钉住只看要人动手的两张卡_不被候选拖住`() {
        val src = controller()
        val idx = src.indexOf("val actionCardsPresent: Boolean")
        assertTrue("actionCardsPresent 还在（不然没人钉卡）", idx >= 0)
        val body = src.substring(idx, minOf(src.length, idx + 400))
        assertFalse(
            "钉住口径不许包含 pendingMemories —— 一条没点的候选会让最近一轮永远收不起来",
            body.contains("pendingMemories"),
        )
        assertTrue("确认卡必须继续钉住", body.contains("confirmCard != null"))
        assertTrue("问人卡必须继续钉住", body.contains("askCard != null"))
    }
}
