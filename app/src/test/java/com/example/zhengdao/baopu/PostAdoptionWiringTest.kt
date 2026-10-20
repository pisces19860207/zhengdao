// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 第一百批 §6 F1–F4：**接线**。判据只有一条：生产代码里真的调了，而不是「函数写好了没人用」。
// （85 的 `outcomesFor` 曾长期零调用，就是这么来的。）
package com.example.zhengdao.baopu

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PostAdoptionWiringTest {

    private fun repoFile(rel: String): File = File(rel).let { if (it.isFile) it else File("app/$rel") }

    private val controller = repoFile("src/main/java/com/example/zhengdao/ui/baopu/BaopuChatController.kt")
        .readText(Charsets.UTF_8)
        .replace("\r\n", "\n")

    /**
     * 取一个成员函数的主体：从签名到**第一个 4 空格缩进的右花括号**。
     *
     * 函数体内部的内层括号至少 8 空格缩进，所以不会提前截断。
     */
    private fun bodyOf(signature: String): String {
        val start = controller.indexOf(signature)
        assertTrue("找不到 $signature", start >= 0)
        val rest = controller.substring(start)
        val end = rest.indexOf("\n    }")
        assertTrue("$signature 没有闭合", end > 0)
        return rest.substring(0, end)
    }

    @Test
    fun `F1_adoptLearn写一条采纳事件`() {
        val body = bodyOf("fun adoptLearn(id: String)")
        assertTrue("学习卡采纳要记 LEARN_CARD 事件", body.contains("PostAdoptionChannel.LEARN_CARD"))
        assertTrue("走统一记账口", body.contains("recordPostAdoption("))
        // 基线必须在写盘之前取（顺序反了就把采纳后的样本算进基线）
        val baselineAt = body.indexOf("postAdoptionBaseline(")
        val writeAt = body.indexOf("writeLearnedSkill(")
        assertTrue("基线要在写盘之前取", baselineAt in 0 until writeAt)
    }

    @Test
    fun `F2_confirmSkillProposal写一条采纳事件`() {
        val body = bodyOf("fun confirmSkillProposal(")
        assertTrue("索引提案采纳要记 SKILL_PROPOSAL 事件", body.contains("PostAdoptionChannel.SKILL_PROPOSAL"))
        assertTrue(body.contains("recordPostAdoption("))
    }

    @Test
    fun `F3_收尾会对未结案事件复测`() {
        val body = bodyOf("private fun generateProposalCandidatesOnSettle()")
        assertTrue("收尾钩子里要复测", body.contains("postAdoptionRecheck()"))
    }

    @Test
    fun `F4_回滚只能从卡上的按钮进来`() {
        val rollback = bodyOf("fun rollbackPostAdoption()")
        assertTrue(rollback.contains("PostAdoptionRollback.applyOnUserApproval("))
        assertTrue(rollback.contains("approvedBy = "))
        // 三个按钮各有一个入口
        assertTrue(bodyOf("fun keepPostAdoption()").contains("PostAdoptionAction.DISMISSED"))
        assertTrue(bodyOf("fun laterPostAdoption()").contains("PostAdoptionAction.LATER"))
        // 卡只在收尾里被填上（不许别处偷偷弹）
        val setters = Regex("postAdoptionNotice = ").findAll(controller).count()
        assertTrue("postAdoptionNotice 的写点太多，检查是否被别处偷偷填：$setters", setters <= 6)
    }

    @Test
    fun `F5_状态只给UI读_不给UI写`() {
        assertTrue(
            "postAdoptionNotice 必须是 private set（UI 只能读）",
            controller.contains("var postAdoptionNotice by mutableStateOf<com.example.zhengdao.baopu.PostAdoptionNotice?>(null)\n        private set"),
        )
        assertFalse(
            "UI 层不许直接构造采纳事件（记账只走控制器）",
            repoFile("src/main/java/com/example/zhengdao/ui/baopu/BaopuScreen.kt")
                .readText(Charsets.UTF_8).contains("PostAdoptionEvent("),
        )
    }
}
