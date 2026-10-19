// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：第三十七批 B 段——有些口径**跑不出结果、只能钉源码**（引擎/控制器要 Android 上下文，
// JVM 里构造不出来）。这些断言盯的是「接线长什么样」，不是风格。
package com.example.zhengdao.ui.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「这次学到」卡的接线守卫（第三十七批 B 段）。
 *
 * 六条：
 * 1. 引擎**只在工具真写成功（Done）时记信号**；
 * 2. 引擎的 **Rejected 分支不记信号**——被保护闸拦下的写尝试不算学到，不许进卡；
 * 3. 控制器 `dismissLearn`（不记）**不落盘**；
 * 4. 控制器 `adoptLearn`（采纳）是**唯一**走 SkillStore 的落盘口；
 * 5. 卡片插在**输入栏上方**（`BaopuComposer` 之前）；
 * 6. A 段的四个闸**一个都没被拆**（用户：别改闸的代码）。
 */
class BaopuLearnCardGuardTest {

    private fun repoFile(rel: String): File =
        File(rel).let { if (it.isFile) it else File("app/$rel") }

    private fun raw(rel: String): String = repoFile(rel).readText().replace("\r\n", "\n")

    private val engine = raw("src/main/java/com/example/zhengdao/baopu/agent/RunEngine.kt")
    private val controller = raw("src/main/java/com/example/zhengdao/ui/baopu/BaopuChatController.kt")
    private val screen = raw("src/main/java/com/example/zhengdao/ui/baopu/BaopuScreen.kt")

    /** 取某个函数/分支的源码片段（从锚点起到下一个空行前）。 */
    private fun block(src: String, anchor: String, limit: Int = 40): String {
        val i = src.indexOf(anchor)
        assertTrue("源码里找不到锚点：$anchor", i >= 0)
        return src.substring(i, (i + limit * 60).coerceAtMost(src.length)).lines().take(limit).joinToString("\n")
    }

    @Test
    fun `引擎_Done分支记信号`() {
        val b = block(engine, "is GuardPipeline.Result.Done -> {")
        assertTrue("Done 分支应当记信号：\n$b", b.contains("signalKindFor"))
    }

    @Test
    fun `引擎_Rejected分支不记信号_被闸拦下的不算学到`() {
        val b = block(engine, "is GuardPipeline.Result.Rejected -> {")
        assertTrue("Rejected 不许记信号：\n$b", !b.contains("signalKindFor"))
        assertTrue("Rejected 分支要写明这条口径", b.contains("不记进"))
    }

    @Test
    fun `引擎_Failed分支也不记信号`() {
        val b = block(engine, "is GuardPipeline.Result.Failed -> {")
        assertTrue("Failed 不许记信号：\n$b", !b.contains("signalKindFor"))
    }

    @Test
    fun `引擎_收尾才出卡且只出一次`() {
        assertTrue(engine.contains("emitLearnCardIfDue()"))
        assertTrue("出卡要走 shouldEmit（含已出过就不再出的判据）", engine.contains("SkillRefine.shouldEmit"))
        assertTrue("新一轮要清掉上一轮的卡与信号", engine.contains("learnShown = false"))
    }

    @Test
    fun `控制器_不记只收卡_不落盘`() {
        // 只看 `fun dismissLearn()` 那一行本体（切片别跨到下一个函数的注释里去）
        val line = controller.lines().first { it.contains("fun dismissLearn()") }
        assertTrue("dismissLearn 只能收卡：$line", line.contains("engine.dismissLearn"))
        assertTrue("dismissLearn 不许碰 SkillStore：$line", !line.contains("SkillStore"))
        assertTrue("dismissLearn 不许写技能：$line", !line.contains("writeLearnedSkill"))
    }

    @Test
    fun `控制器_采纳是唯一落盘口`() {
        val adopt = block(controller, "fun adoptLearn(", limit = 12)
        assertTrue("采纳要走写盘：\n$adopt", adopt.contains("writeLearnedSkill"))
        val write = block(controller, "private fun writeLearnedSkill(", limit = 30)
        assertTrue("落盘必须走 SkillStore.plan：\n$write", write.contains("SkillStore.plan"))
        assertTrue("落盘必须走 SkillStore.apply：\n$write", write.contains("SkillStore.apply"))
        // 真落盘调用全仓控制器里只该有一处（plan/apply 各一次）
        assertEquals("SkillStore.apply 只该有一处", 1, countOf(controller, "SkillStore.apply("))
        assertEquals("SkillStore.plan 只该有一处", 1, countOf(controller, "SkillStore.plan("))
    }

    private fun countOf(src: String, token: String): Int = src.split(token).size - 1

    @Test
    fun `卡片插在输入栏上方_Composer之前`() {
        val iCard = screen.indexOf("BaopuLearnCard(")
        val iComposer = screen.indexOf("BaopuComposer(")
        assertTrue("屏幕上要有这张卡", iCard > 0)
        assertTrue("卡必须在输入栏（Composer）之前：$iCard vs $iComposer", iCard < iComposer)
    }

    @Test
    fun `A段的四个闸一个都没被拆`() {
        for (name in listOf("WriteFileTool", "EditFileTool", "MovePathTool", "DeletePathTool")) {
            val src = raw("src/main/java/com/example/zhengdao/baopu/agent/tools/$name.kt")
            assertTrue("$name 的保护闸必须在", src.contains("PersonaGuard.blocked"))
        }
        assertTrue("闸本体还在", repoFile("src/main/java/com/example/zhengdao/baopu/PersonaGuard.kt").isFile)
    }

    @Test
    fun `中英strings对称`() {
        val zh = raw("src/main/res/values/strings.xml")
        val en = raw("src/main/res/values-en/strings.xml")
        val keys = Regex("name=\"(learn_[a-z_]+)\"").findAll(zh).map { it.groupValues[1] }.toList()
        assertTrue("中文要有 learn_* 键", keys.isNotEmpty())
        for (k in keys) {
            assertTrue("英文缺 $k", en.contains("name=\"$k\""))
        }
    }
}
