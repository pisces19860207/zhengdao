// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百三十七批判据④：御案页签出现「冲突」组（2 条夹具）；0 条时不渲染（负例）。
// 组头与空组守卫在 `DecisionCenterScreen.kt`，这里同时做行为断言与源级断言。
package com.example.zhengdao.ui.baopu

import com.example.zhengdao.baopu.inspect.OpinionConflict
import com.example.zhengdao.baopu.inspect.OpinionConflictSide
import com.example.zhengdao.baopu.inspect.Severity
import com.example.zhengdao.baopu.inspect.Source
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoyalConflictRulesTest {

    /** 假文案：带参数的句子把参数串起来（只测结构；真文案由 RoyalCardStringsTest 体检）。 */
    private val s = DecisionStrRes { _, args -> if (args.isEmpty()) "短句" else args.joinToString("／") }

    private fun conflict(gistA: String, gistB: String, event: String = "ev1:event:1") = OpinionConflict(
        eventKey = event,
        a = OpinionConflictSide(gistA, Severity.CRITICAL, Source.PATROL),
        b = OpinionConflictSide(gistB, Severity.MAJOR, Source.AUDIT),
        at = 1_700_000_000_000L,
        evidence = listOf("event:1", "log:1"),
    )

    @Test
    fun `判据4_两条夹具渲染两行`() {
        val rows = RoyalConflictRules.rows(
            listOf(conflict("漏账", "账实不符"), conflict("越界", "未分类", "ev1:event:2")),
            suppressed = emptySet(),
            s = s,
        )
        assertEquals(2, rows.size)
        rows.forEach { row ->
            assertEquals(3, row.card.lines.size)
            assertEquals(
                listOf(RoyalLineKind.WHAT, RoyalLineKind.WHY, RoyalLineKind.IMPACT),
                row.card.lines.map { it.kind },
            )
            assertEquals(3, row.card.options.size)
            assertTrue("卡面必须过既有判据：${RoyalCardGate.check(row.card)}", RoyalCardGate.renderable(row.card))
        }
    }

    @Test
    fun `负例_零条冲突不渲染`() {
        assertTrue(RoyalConflictRules.rows(emptyList(), emptySet(), s).isEmpty())
    }

    @Test
    fun `已裁过的冲突不再上卡`() {
        val c = conflict("漏账", "账实不符")
        assertTrue(RoyalConflictRules.rows(listOf(c), setOf(c.id), s).isEmpty())
    }

    @Test
    fun `源级_组头只在非空时渲染`() {
        val page = listOf(
            File("src/main/java/com/example/zhengdao/ui/baopu/DecisionCenterScreen.kt"),
            File("../app/src/main/java/com/example/zhengdao/ui/baopu/DecisionCenterScreen.kt"),
        ).firstOrNull { it.isFile } ?: throw AssertionError("找不到 DecisionCenterScreen.kt")

        val text = page.readText(Charsets.UTF_8)
        assertTrue("必须有「冲突」组头", text.contains("R.string.royal_conflict_group"))
        assertTrue("空组守卫必须在渲染之前", text.contains("if (conflictRows.isNotEmpty())"))
    }
}
