// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 第七十二批（§B 80 结构切片）：BaopuIndexBudget 的 JVM 单测 ＋ 源码门禁。
//
// 盯的是 K2：索引超上限只能「拒绝并提示先删旧的」，不许截断兜底。
package com.example.zhengdao.ui.baopu

import com.example.zhengdao.baopu.MemoryScope
import com.example.zhengdao.baopu.SkillRules
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BaopuIndexBudgetTest {

    private fun controller(): String {
        val f = listOf(
            File("app/src/main/java/com/example/zhengdao/ui/baopu/BaopuChatController.kt"),
            File("src/main/java/com/example/zhengdao/ui/baopu/BaopuChatController.kt"),
        ).firstOrNull { it.isFile } ?: error("找不到 BaopuChatController.kt")
        return f.readText()
    }

    private fun indexText(vararg names: String): String =
        names.joinToString("\n") { "【技能】$it：遇到 $it 时用" }

    @Test
    fun `B1_正好顶到上限不算超`() {
        assertFalse("total+adding == cap 是合法的", BaopuIndexBudget.wouldExceed(19, 1))
        assertTrue("多一行就超", BaopuIndexBudget.wouldExceed(20, 1))
        assertEquals(20, SkillRules.INDEX_CAP)
    }

    @Test
    fun `B2_负数增量只减不增_不会误判`() {
        assertFalse(BaopuIndexBudget.wouldExceed(SkillRules.INDEX_CAP, -1))
        assertFalse(BaopuIndexBudget.wouldExceed(SkillRules.INDEX_CAP, 0))
    }

    @Test
    fun `B3_上限可注入_便于边界用例`() {
        assertTrue(BaopuIndexBudget.wouldExceed(2, 1, cap = 2))
        assertFalse(BaopuIndexBudget.wouldExceed(1, 1, cap = 2))
    }

    @Test
    fun `B4_净增行数按两段文本的索引行差算`() {
        val current = indexText("甲")
        val proposed = indexText("甲", "乙")
        assertEquals(1, BaopuIndexBudget.addedIndexLines(proposed, current, MemoryScope.PROJECT))
        assertEquals("原样 ⇒ 0", 0, BaopuIndexBudget.addedIndexLines(current, current, MemoryScope.PROJECT))
        assertEquals("删一行 ⇒ 负", -1, BaopuIndexBudget.addedIndexLines(current, proposed, MemoryScope.PROJECT))
    }

    @Test
    fun `B5_非索引行不计数`() {
        val current = "【技能】甲：场景\n普通一句话\n"
        val proposed = current + "【技能】乙：场景\n"
        assertEquals(1, BaopuIndexBudget.addedIndexLines(proposed, current, MemoryScope.PROJECT))
    }

    // ── 源码门禁：两处判据同源 ────────────────────────────────────────────

    @Test
    fun `G1_两处上限判据都走同一个纯函数`() {
        val c = controller()
        assertEquals(
            "封卷提炼＋确认提案两处都必须调 wouldExceed",
            2,
            Regex("BaopuIndexBudget\\.wouldExceed\\(").findAll(c).count(),
        )
        assertTrue(
            "控制器里不许再写第二份算术",
            !c.contains("> com.example.zhengdao.baopu.SkillRules.INDEX_CAP"),
        )
        assertTrue(c.contains("BaopuIndexBudget.addedIndexLines(p.proposed, p.current, scope)"))
    }
}
