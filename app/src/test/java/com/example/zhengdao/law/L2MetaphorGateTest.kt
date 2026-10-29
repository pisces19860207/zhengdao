// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百七十一批 · 法典门禁 **L2 判据高于隐喻**（派工单 271 §1 第一行）。
//
// ## 判据（可执行）
// 主源码 `app/src/main/java` 的**代码面**（去注释、抹掉字符串字面量的内容）里，
// 不许出现以**官职／隐喻**命名的标识符或判定常量。
//
// 为什么判"代码面"而不是整份文本：
//   - 注释里写「御批」「巡按」是历史说明，是好事（253 的门禁也留着这类注释）；
//   - 文案里写「御批」是给人看的词，不是判据；
//   - **判据**只可能是标识符/常量——那一层出现官职词，就是「拿隐喻当判据」。
//
// ## 门禁形态
// 源码扫描（只读文本）＋ 反空转下限（扫不到文件/行数 ⇒ 直接失败，不许恒绿）。
//
// ## 负例（本文件自证会红）
// 往真源码的副本里注入一行 `const val 御批 = true` ⇒ 扫描器必须恰好命中 1 处；
// 同一行写在**注释**里、或写在**字符串**里 ⇒ 必须零命中（防误伤，否则门禁会逼人改文案）。
package com.example.zhengdao.law

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class L2MetaphorGateTest {

    /**
     * 官职／隐喻词表（判据本体）。
     *
     * 只收**多字词**：单字（臣/朕/谏）在变量名里出现的概率太低，收进来只会制造假命中，
     * 而假命中会训练人「看到红灯就加白名单」——那门禁就废了。
     */
    internal object Metaphor {
        val WORDS: List<String> = listOf(
            "御批", "御案", "御裁", "御准", "御笔", "朱批", "密折", "上奏", "奏折",
            "暗卫", "中书舍人", "巡按", "纠察", "圣旨", "廷议", "弹劾", "钦差",
            "陛下", "尚书", "侍郎", "知府", "县令", "太监", "宦官",
        )

        val APIS: Map<String, Regex> = WORDS.associateWith { w -> Regex(Regex.escape(w)) }
    }

    @Test
    fun `L2_正例_主源码代码面没有官职隐喻标识符`() {
        val (files, lines) = LawIo.assertNotBlind(LawIo.MAIN_ROOT, minFiles = 200, minLines = 20_000)
        val hits = LawIo.scanCodeTree(LawIo.MAIN_ROOT, Metaphor.APIS)
        assertTrue(
            "扫了 $files 个文件 / $lines 行，命中 ${hits.size} 处：隐喻不许当判据（判定常量必须用领域词）\n" +
                hits.joinToString("\n") { "  ${it.where()} [${it.api}] ${it.text}" },
            hits.isEmpty(),
        )
    }

    @Test
    fun `L2_负例_代码面里写官职判定常量必须被抓到_注释与文案不算`() {
        // ① 注入：真源码副本 + 一行官职常量 ⇒ 恰好 1 处命中
        val injected = LawIo.sources().toMutableList()
        injected += "baopu/FakeMetaphor.kt" to """
            package com.example.zhengdao.baopu

            internal object FakeMetaphor {
                const val 御批 = true
            }
        """.trimIndent()
        val hits = injected.flatMap { (rel, src) -> LawIo.scanCodeLine(rel, src, Metaphor.APIS) }
        assertEquals(
            "注入的官职判定常量必须被抓到（抓不到＝门禁恒绿）：$hits",
            1,
            hits.size,
        )
        assertTrue("命中的必须是注入那一份：${hits.map { it.relPath }}", hits[0].relPath.endsWith("FakeMetaphor.kt"))

        // ② 注释里的隐喻不算（历史说明要能写）
        val commented = """
            internal object History {
                // 这里以前叫「御批」，是官职隐喻，已按 L2 改成 approvalRequired
                /** 暗卫＝勘验器；巡按＝巡检（仅说明） */
                const val APPROVAL_REQUIRED = true
            }
        """.trimIndent()
        assertEquals(
            "注释里的隐喻不许算违规（否则门禁会逼人删历史说明）",
            emptyList<String>(),
            LawIo.scanCodeLine("Fake/History.kt", commented, Metaphor.APIS).map { it.text },
        )

        // ③ 文案里的隐喻不算（字面量内容已被抹掉）
        val inLiteral = """
            internal object Copy {
                const val LABEL = "御批通过" + "暗卫已核"
            }
        """.trimIndent()
        assertEquals(
            "字符串字面量里的隐喻不算（那是文案，不是判据）",
            emptyList<String>(),
            LawIo.scanCodeLine("Fake/Copy.kt", inLiteral, Metaphor.APIS).map { it.text },
        )
    }
}
