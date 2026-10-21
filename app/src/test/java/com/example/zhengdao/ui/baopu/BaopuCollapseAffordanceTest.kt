// 独立开发声明：本文件为本项目从零编写。
package com.example.zhengdao.ui.baopu

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「一轮展开后能收回」的守卫（2026-10-18 真机第二次反馈）。
 *
 * 事故经过（两次）：第一次用户说「工具调用我点了，展开后就收不回去了」——原因是收起态才有
 * 摘要行；我补了展开态上下各一条。第二次用户说「干了几步点开后没法收回」——**两条都在**，
 * 但一轮展开后有十几张工具卡，两条摘要行都滚出屏幕，用户找不到 ⇒ 判据不是「有没有入口」，
 * 而是「**入口不随内容滚走**」。
 *
 * 第一百零六批（2026-10-21，设计 v1.5 §3.12）**四组判据迁移**：
 * ① 展开状态提到列表层（`expandedRoundId` 在 `BaopuMessageList` 里，不在 item 里 `remember`）
 *    —— **不动**；
 * ② 原「屏幕上有一颗常驻浮标（`R.string.baopu_collapse_detail`）」**反转**为
 *    「源码不许再渲染它、不许再留 56dp 让位、中英字符串键也一并删掉」——用户点名移除浮标；
 * ③ **新增**「内联活动面板是收起入口」：`BaopuActivityPanel` 挂在轮尾（最终回答之后），
 *    点整行展开/收起活动流；
 * ④ 「钉住口径不被待过目候选拖住」—— **不动**。
 *
 * 风险与兜底（写进交付报告 §2.5）：展开态顶部/底部摘要行仍可能滚出屏。设计给的兜底是
 * 「把活动面板行在轮展开时钉在视口底部（程序化滚动定位），**不恢复浮标**」；本批先按设计
 * 落地面板与入口，真机若复现「收不回去」再上钉底，不重新引入浮标。
 */
class BaopuCollapseAffordanceTest {

    private fun repoFile(rel: String): File =
        File(rel).let { if (it.isFile) it else File("app/$rel") }

    private fun code(rel: String): String = repoFile(rel).readText()
        .replace("\r\n", "\n")
        .replace(Regex("(?s)/\\*.*?\\*/"), "")
        .replace(Regex("(?m)^[ \\t]*//.*$"), "")

    private fun list() = code("src/main/java/com/example/zhengdao/ui/baopu/BaopuMessageList.kt")

    private fun controller() = code("src/main/java/com/example/zhengdao/ui/baopu/BaopuChatController.kt")

    // ① 不动：展开状态在列表层

    @Test
    fun `展开状态在列表层`() {
        val src = list()
        assertTrue("展开态必须提到列表层（浮标拿不到状态的历史教训）", src.contains("var expandedRoundId by remember"))
        assertTrue(
            "点摘要行要能收回（翻转：同一个键再点一次回 null）",
            src.contains("if (expandedRoundId == round.userMessageId) null"),
        )
        assertFalse(
            "item 里不许再有自己那份 userOpened 状态（两份状态会打架）",
            src.contains("var userOpened by remember"),
        )
    }

    // ② 反转：浮标整块移除（源码 + 字符串 + 让位）

    @Test
    fun `收起详情浮标整块移除`() {
        val src = list()
        assertFalse(
            "浮标必须移除（用户点名）；还在渲染 ⇒ 本判据红",
            src.contains("baopu_collapse_detail"),
        )
        assertFalse(
            "56dp 让位是浮标时代的遗留，一并删掉（「回到最新」回 12dp）",
            src.contains("bottom = 56.dp"),
        )
    }

    @Test
    fun `中英文都不再有浮标文案`() {
        val zh = repoFile("src/main/res/values/strings.xml").readText()
        val en = repoFile("src/main/res/values-en/strings.xml").readText()
        assertFalse("中文不该再有 baopu_collapse_detail", zh.contains("name=\"baopu_collapse_detail\""))
        assertFalse("英文不该再有 baopu_collapse_detail", en.contains("name=\"baopu_collapse_detail\""))
    }

    // ③ 新增：内联活动面板是同一条入口

    @Test
    fun `内联活动面板挂在轮尾_是新的收起入口`() {
        val src = list()
        assertTrue(
            "轮尾必须有活动面板（移除浮标后由它承接「入口不随内容滚走」）",
            src.contains("BaopuActivityPanel("),
        )
        val lambdaIdx = src.indexOf("val activityPanel: @Composable () -> Unit")
        val panelIdx = src.indexOf("BaopuActivityPanel(")
        assertTrue("两个落点都要在（索引失效会让本判据静默通过）", lambdaIdx > 0 && panelIdx > lambdaIdx)
        assertTrue("面板本体挂在轮尾那个 lambda 里", panelIdx > lambdaIdx)
        val forEachIdx = src.indexOf("round.messages.forEach { msg ->")
        val callIdx = src.indexOf("activityPanel()", forEachIdx)
        assertTrue(
            "展开分支在轮内消息（含最终回答）之后才调面板——轮内最后一个信息块",
            forEachIdx > 0 && callIdx > forEachIdx,
        )
        assertTrue(
            "展开态的底摘要行仍保留（收回整轮的口子没丢）",
            src.contains("BaopuRoundSummaryRow(round, steps, onUserToggle)"),
        )
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
