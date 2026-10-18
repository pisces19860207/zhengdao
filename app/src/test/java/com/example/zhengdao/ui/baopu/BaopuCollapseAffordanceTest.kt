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
 * 四组判据（源码文本级，先剥注释再断言，与 `BrowserBottomBarGuardTest` 同一手法）：
 * ① 展开状态提到列表层（`expandedRoundId` 在 `BaopuMessageList` 里，不在 item 里 `remember`）；
 * ② 屏幕上有一颗常驻浮标（`R.string.baopu_collapse_detail`），点它收回；
 * ③ 浮标不许盖住「⬇ 回到最新」（bottom padding 必须留出让位）；
 * ④ 钉住口径不再被「待过目候选」拖住（`actionCardsPresent` 里不许出现 `pendingMemories`）。
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

    @Test
    fun `展开状态在列表层_且有一颗常驻收起浮标`() {
        val src = list()
        assertTrue("展开态必须提到列表层（否则浮标拿不到状态）", src.contains("var expandedRoundId by remember"))
        assertTrue("浮标文案走 strings.xml（不拼中文）", src.contains("R.string.baopu_collapse_detail"))
        assertTrue("点浮标要能收回", src.contains("expandedRoundId = null"))
        assertFalse(
            "item 里不许再有自己那份 userOpened 状态（两份状态会打架）",
            src.contains("var userOpened by remember"),
        )
    }

    @Test
    fun `浮标让开回到最新`() {
        val src = list()
        // 「⬇ 回到最新」在 BaopuScreen 里是 BottomEnd + 12dp；收起浮标必须往上让。
        assertTrue(
            "收起浮标要带 bottom padding 让开「回到最新」",
            src.contains("padding(end = 12.dp, bottom = 56.dp)"),
        )
    }

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

    @Test
    fun `中英文都有浮标文案`() {
        val zh = repoFile("src/main/res/values/strings.xml").readText()
        val en = repoFile("src/main/res/values-en/strings.xml").readText()
        assertTrue("中文缺 baopu_collapse_detail", zh.contains("name=\"baopu_collapse_detail\""))
        assertTrue("英文缺 baopu_collapse_detail", en.contains("name=\"baopu_collapse_detail\""))
    }
}
