// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第九百三十五批 · 体相传闻门禁。
// 第九百四十三批 · 表搬 manifest：唯一真相是 blacklist-manifest.json 的 935-shape-myth 类
// （经 ShapeRumorCatalog 读端读出）；本文件不抄第二份话术，夹具固定在这里，
// 所以删掉 manifest 一行 ⇒ 对应夹具由红转绿。总数与小计从 manifest 读，不写条数字面量。
package com.example.zhengdao.baopu.state.bodyparts

import com.example.zhengdao.BlacklistManifestFeed
import com.example.zhengdao.baopu.policy.BlacklistManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

class ShapeRumorGateTest {

    companion object {
        /** 943：表在 manifest，先把 935 行喂进读端再跑（其余测试类同）。 */
        @BeforeClass
        @JvmStatic
        fun feedManifestRows() {
            BlacklistManifestFeed.installShapeMyth()
        }
    }


    private fun red(text: String, voice: Voice = Voice.NARRATOR, plot: Boolean = false): List<ShapeFinding> =
        ShapeJudge.judgeText(text, voice, plot).filter { it.verdict == Verdict.DENY }

    /** 七类反例：每类至少一条，必须全是红。 */
    private val badByClass: Map<RumorClass, String> = mapOf(
        RumorClass.BODY to "个子矮的男人那处一定大。",
        RumorClass.FACE to "鼻子大的男人下面一定大。",
        RumorClass.HAIR to "秃顶的男人那方面不行。",
        RumorClass.POSTURE to "岔腿坐的男人那方面强。",
        RumorClass.FORTUNE to "血型决定他那方面。",
        RumorClass.FLUID to "精液少就是不行。",
        RumorClass.DRESS to "骑电动车的男人没本事。",
    )

    /** 七类合规反例：只写形态与事实，不下结论。 */
    private val goodByClass: Map<RumorClass, String> = mapOf(
        RumorClass.BODY to "他个子不高，那处也不大，两件事没关系。",
        RumorClass.FACE to "他鼻子小，尺寸偏大，两者没关系。",
        RumorClass.HAIR to "他发际线高，能力正常，两件事不挨着。",
        RumorClass.POSTURE to "他说话慢，张力高，这两样各记各的。",
        RumorClass.FORTUNE to "他的血型是 A。",
        RumorClass.FLUID to "精液少，能力正常。",
        RumorClass.DRESS to "他骑电动车上班，做人很正。",
    )

    @Test
    fun `七类齐且条数从唯一表复现`() {
        assertEquals("七类闭集", 7, RumorClass.COUNT)
        assertEquals("七类一个不漏", RumorClass.entries.map { it.tag }, listOf("A", "B", "C", "D", "E", "F", "G"))
        val byClass = ShapeRumorCatalog.countByClass()
        val subs = BlacklistManifestFeed.subtotals(BlacklistManifest.KIND_SHAPE_MYTH)
        val total = BlacklistManifestFeed.total(BlacklistManifest.KIND_SHAPE_MYTH)
        RumorClass.entries.forEach { klass ->
            assertEquals(klass.tag + " 类条数与 manifest 小计一致", subs.getValue(klass.tag), byClass.getValue(klass))
        }
        assertEquals("总数＝各类小计之和", total, subs.values.sum())
        assertEquals("行数与 manifest totals 一致（改表要同步计数，解析层也把守）",
            total, ShapeRumorCatalog.entries.size)
        println("RUMOR-READING total=" + ShapeRumorCatalog.entries.size + " byClass=" + byClass + " from manifest")
    }

    @Test
    fun `每条都是句式模板_编号唯一_目标非空`() {
        val ids = ShapeRumorCatalog.entries.map { it.id }
        assertEquals("编号不许重复", ids.size, ids.toSet().size)
        ShapeRumorCatalog.entries.forEach { rumor ->
            assertTrue(rumor.id + " 必须是句式模板（带 …）", rumor.template.contains('…'))
            assertTrue(rumor.id + " 必须说清断言目标", rumor.targets.isNotEmpty())
            assertTrue(rumor.id + " 编号应带类前缀", rumor.id.startsWith(rumor.klass.tag))
        }
        assertEquals("目标维也应是闭集", 7, RumorTarget.COUNT)
    }

    @Test
    fun `七类反例全红_叙述者层`() {
        val green = ArrayList<String>()
        badByClass.forEach { (klass, text) ->
            val found = red(text)
            println("RUMOR-READING " + klass.tag + " red=" + found.map { it.code })
            if (found.isEmpty()) green += klass.tag + "：" + text
            assertTrue("唯一表的 match 入口应命中 " + klass.tag, ShapeRumorCatalog.match(text).isNotEmpty())
        }
        assertEquals("有类没牙（删表行应在这里转绿）：" + green, emptyList<String>(), green)
    }

    @Test
    fun `角色信传闻只WARN_且必须标偏见`() {
        val withMarker = "听说鼻子大的男人下面一定大。"
        val warn = ShapeJudge.judgeText(withMarker, Voice.CHARACTER)
        println("RUMOR-READING character-marker=" + warn.joinToString(" | ") { it.line() })
        assertTrue("角色 + 偏见标记 ⇒ 须核", warn.any { it.code.startsWith("RUMOR-BIAS-") })
        assertFalse("角色 + 偏见标记不许红", warn.any { it.verdict == Verdict.DENY })

        val noMarker = "鼻子大的男人下面一定大。"
        val harsh = ShapeJudge.judgeText(noMarker, Voice.CHARACTER)
        println("RUMOR-READING character-no-marker=" + harsh.joinToString(" | ") { it.line() })
        assertTrue("读不出偏见的角色传闻 ⇒ 红", harsh.any { it.code.startsWith("RUMOR-UNMARKED-") })
    }

    @Test
    fun `剧情验证传闻必红_即使角色带着偏见标记`() {
        val text = "听说鼻子大的男人下面一定大。"
        val found = ShapeJudge.judgeText(text, Voice.CHARACTER, plotValidates = true)
        println("RUMOR-READING plot-validates=" + found.joinToString(" | ") { it.line() })
        assertTrue("剧情验证 ⇒ 红", found.any { it.code == "RUMOR-PLOT-VALIDATES" && it.verdict == Verdict.DENY })
        assertFalse("剧情验证不许被降成须核", found.any { it.verdict == Verdict.NEED_CHECK })
    }

    @Test
    fun `命理类叙述者直接红_不走WARN`() {
        val text = "听说是血型决定的。"
        val found = ShapeJudge.judgeText(text, Voice.NARRATOR)
        println("RUMOR-READING fortune-direct=" + found.joinToString(" | ") { it.line() })
        assertTrue("带偏见标记也不许降级", found.any { it.code == "FORTUNE-DIRECT" && it.verdict == Verdict.DENY })
        assertFalse("命理不许出现须核分支", found.any { it.verdict == Verdict.NEED_CHECK })

        val asCharacter = ShapeJudge.judgeText(text, Voice.CHARACTER)
        println("RUMOR-READING fortune-character=" + asCharacter.joinToString(" | ") { it.line() })
        assertTrue("角色拿命理当依据 ⇒ 须核", asCharacter.any { it.code == "FORTUNE-BIAS" })
    }

    @Test
    fun `纯记录不算推导_命理不误杀`() {
        val text = "他的血型是 A，体检表上写着。"
        val found = ShapeJudge.judgeText(text, Voice.NARRATOR)
        println("RUMOR-READING fortune-record=" + found.size)
        assertTrue("只记录血型不判红：" + found.map { it.line() }, found.isEmpty())
    }

    @Test
    fun `七类合规反例全绿`() {
        val killed = ArrayList<String>()
        goodByClass.forEach { (klass, text) ->
            val found = ShapeJudge.judgeText(text, Voice.NARRATOR)
            if (found.isNotEmpty()) killed += klass.tag + "：" + text + " -> " + found.map { it.code }
        }
        println("RUMOR-READING compliant green=" + (goodByClass.size - killed.size) + "/" + goodByClass.size)
        assertEquals("合规写法被误杀：" + killed, emptyList<String>(), killed)
    }

    @Test
    fun `文档小计与代码表一致`() {
        val doc = listOf(java.io.File("../docs/形态不推性评价.md"), java.io.File("docs/形态不推性评价.md"))
            .firstOrNull { it.isFile }
            ?: error("形态不推性评价文档没找到")
        val line = doc.readLines().firstOrNull { it.startsWith("SHAPE-RUMOR-COUNTS|") }
            ?: error("文档缺机读小计行")
        val cells = line.removePrefix("SHAPE-RUMOR-COUNTS|").trim('|').split("|")
        val parsed = cells.filter { it.contains("=") }.associate { cell ->
            val (tag, n) = cell.split("=")
            tag.trim() to n.trim().toInt()
        }
        val byClass = ShapeRumorCatalog.countByClass()
        RumorClass.entries.forEach { klass ->
            assertEquals("文档 " + klass.tag + " 小计", byClass.getValue(klass), parsed.getValue(klass.tag))
        }
        assertEquals("文档总数", ShapeRumorCatalog.entries.size, parsed.getValue("TOTAL"))
        println("RUMOR-READING doc counts aligned " + parsed)
    }

    @Test
    fun `自检_删一行对应夹具由红转绿`() {
        val target = ShapeRumorCatalog.entries.first { it.id == "A01" }
        val text = badByClass.getValue(RumorClass.BODY)
        assertTrue("基线必须红", red(text).any { it.code == "RUMOR-NARRATOR-A01" })
        val dropped = ShapeRumorCatalog.entries.filterNot { it.id == target.id }
        val after = ShapeJudge.judgeText(text, Voice.NARRATOR, rumors = dropped)
        println("RUMOR-READING mutation delete A01 -> " + after.size + " finding(s)")
        assertFalse("删行后必须转绿", after.any { it.verdict == Verdict.DENY })
    }

    @Test
    fun `清单外新造_命理词加结论也拦得住`() {
        // 表里没有「骨相…那方面」这一条，但命理词 + 结论词同句也要红。
        val text = "看骨相就知道他那方面一定行。"
        val found = red(text)
        println("RUMOR-READING unfiled=" + found.map { it.code })
        assertTrue("清单外的命理推导也要红", found.any { it.code == "FORTUNE-DIRECT" })
    }
}
