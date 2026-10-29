// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百七十一批 · 法典门禁 **L14 勘验不得判罚**（派工单 271「勘验模块不得有"判定/处罚"调用」）。
//
// ## 判据（可执行）
// ① `baopu/inspect/` 的**代码面**（去注释、抹字面量）里不许出现处罚语义的标识符/调用：
//    `punish`／`penalty`／`penalize`／`sanction`／`discipline`／`处罚`／`判罚`／`罚款`／`处分`／`惩戒`；
// ② 勘验的结论枚举只许是**事实认定**（准/不准、有/没有），不许长出"罚"这一档。
//
// 为什么判代码面：注释里写「这里不做处罚」是**对的**（要能说明边界），文案里也不能出现
// "罚"（用户看不懂也不该看到），但真正的违规是**代码里多了一条判罚通道**。
//
// ## 负例（本文件自证会红）
// 假勘验模块里加一个 `punish(...)` ⇒ 扫描器必须命中；结论枚举里加 `PENALTY` ⇒ 也必须红。
package com.example.zhengdao.law

import com.example.zhengdao.baopu.inspect.InspectVerdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class L14InspectNoPenaltyGateTest {

    private val inspectRoot = "${LawIo.MAIN_ROOT}/baopu/inspect"

    internal object Penalty {
        val WORDS: List<String> = listOf(
            "punish", "penalty", "penalize", "sanction", "discipline", "sentence",
            "处罚", "判罚", "罚款", "处分", "惩戒", "追究",
        )
        val APIS: Map<String, Regex> = WORDS.associateWith { w -> Regex(Regex.escape(w), RegexOption.IGNORE_CASE) }
    }

    @Test
    fun `L14_正例_勘验模块代码面没有判罚调用_结论只有事实三态`() {
        LawIo.assertNotBlind(inspectRoot, minFiles = 10, minLines = 1_000)
        val hits = LawIo.scanCodeTree(inspectRoot, Penalty.APIS)
        assertEquals(
            "勘验模块里出现了判罚语义（只查不判）：\n" + hits.joinToString("\n") { "  ${it.where()} [${it.api}] ${it.text}" },
            emptyList<String>(),
            hits.map { it.where() },
        )
        // 结论枚举只有事实三态（准/不准/没数据），没有"罚"
        assertEquals(
            "勘验结论只许是事实认定",
            listOf("HIT", "CLEAR", "NO_DATA"),
            InspectVerdict.entries.map { it.name },
        )
    }

    @Test
    fun `L14_负例_勘验里长出判罚通道必须被抓到`() {
        val bad = """
            package com.example.zhengdao.baopu.inspect

            internal object RogueInspect {
                fun punish(name: String): Int = 3
                val 处罚 = "扣分"
            }
        """.trimIndent()
        val hits = LawIo.scanCodeLine("inspect/RogueInspect.kt", bad, Penalty.APIS)
        assertEquals("假勘验模块的两处判罚必须都被抓到：${hits.map { it.api }}", 2, hits.size)
        assertTrue("命中的判据名要能看出是哪种写法", hits.any { it.api == "punish" } && hits.any { it.api == "处罚" })

        // 注释里写"不做处罚"不算违规（边界说明要能写）
        val commented = """
            internal object Doc {
                // 这里只出事实认定，不做处罚；判罚归人
                fun finding(): String = "ok"
            }
        """.trimIndent()
        assertEquals(
            "注释里的判罚字样不算",
            emptyList<String>(),
            LawIo.scanCodeLine("inspect/Doc.kt", commented, Penalty.APIS).map { it.api },
        )
        assertFalse("空源码不许命中", LawIo.scanCodeLine("inspect/Empty.kt", "", Penalty.APIS).isNotEmpty())
    }
}
