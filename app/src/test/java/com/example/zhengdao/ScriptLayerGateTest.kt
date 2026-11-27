// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第九百四十一批 · 剧本层与小说层门禁。
// 唯一表在 docs/剧本层与小说层.md 的 SCRIPT-*-BEGIN/END 块；本文件只读它，不抄第二份参数。
// 夹具固定在代码里：删文档一行 ⇒ 对应夹具改判；改夹具 ⇒ 判据仍从文档取值。
// 本单零生产依赖：不 import 929（未派）／940／942 的任何类，只做文档表驱动的判定。
package com.example.zhengdao

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.util.regex.Pattern

class ScriptLayerGateTest {

    private enum class Layer { NOVEL, SCRIPT }

    private data class LayerRow(val layer: String, val category: String, val verdict: String)
    private data class UnshootRow(val id: String, val category: String, val template: String, val verdict: String)
    private data class QuotaRow(
        val id: String, val item: String, val limit: Int, val unit: String,
        val tokens: List<String>, val necessity: List<String>,
    )
    private data class AltRow(val id: String, val method: String, val tokens: List<String>)
    private data class TemplateRow(val id: String, val template: String, val exceptions: List<String>)
    private data class Tables(
        val layers: List<LayerRow>,
        val unshoot: List<UnshootRow>,
        val quotas: List<QuotaRow>,
        val alts: List<AltRow>,
        val templates: List<TemplateRow>,
    )
    private data class Finding(val code: String, val message: String)
    private data class LineCase(val id: String, val layer: Layer, val text: String, val expect: String)
    private data class EpisodeCase(val id: String, val lines: List<String>, val expect: String)

    /** 变异入口：给替身文档的绝对路径；不给就读仓库里的唯一表。 */
    private fun docFile(): File {
        val override = System.getenv("SCRIPT_DOC")
        val candidates = if (override.isNullOrBlank()) {
            listOf(File("../docs/剧本层与小说层.md"), File("docs/剧本层与小说层.md"))
        } else {
            listOf(File(override))
        }
        return candidates.firstOrNull { it.isFile }
            ?: error("剧本层与小说层文档没找到：${candidates.joinToString { it.absolutePath }}")
    }

    private fun section(begin: String, end: String): List<String> {
        val all = docFile().readLines()
        val from = all.indexOfFirst { it.contains(begin) }
        val to = all.indexOfFirst { it.contains(end) }
        check(from >= 0 && to > from) { "文档缺标记：$begin / $end" }
        return all.subList(from + 1, to)
    }

    private fun rows(begin: String, end: String): List<List<String>> =
        section(begin, end)
            .filter { it.trimStart().startsWith("|") }
            .map { line -> line.trim().trim('|').split('|').map { cell -> cell.trim() } }
            .filter { cols -> cols.size >= 2 && cols[0] != "编号" && cols[0].any { it != '-' && it != ':' && it != ' ' } }

    /** `／` 是任选其一；空单元格不产生 token（模板禁令的例外标记不许空）。 */
    private fun tokens(cell: String): List<String> = cell.split('／').map { it.trim() }.filter { it.isNotEmpty() }

    private fun tables(): Tables {
        val layers = rows("SCRIPT-LAYER-BEGIN", "SCRIPT-LAYER-END").map { c ->
            check(c.size == 4) { "两层表必须 4 列：$c" }
            LayerRow(c[1], c[2], c[3])
        }
        val unshoot = rows("SCRIPT-UNSHOOTABLE-BEGIN", "SCRIPT-UNSHOOTABLE-END").map { c ->
            check(c.size == 4) { "不可拍摄表必须 4 列：$c" }
            UnshootRow(c[0], c[1], c[2], c[3])
        }
        val quotas = rows("SCRIPT-QUOTA-BEGIN", "SCRIPT-QUOTA-END").map { c ->
            check(c.size == 6) { "配额表必须 6 列：$c" }
            QuotaRow(c[0], c[1], c[2].toInt(), c[3], tokens(c[4]), tokens(c[5]))
        }
        val alts = rows("SCRIPT-ALT-BEGIN", "SCRIPT-ALT-END").map { c ->
            check(c.size == 3) { "替代表必须 3 列：$c" }
            AltRow(c[0], c[1], tokens(c[2]))
        }
        val templates = rows("SCRIPT-TEMPLATE-BEGIN", "SCRIPT-TEMPLATE-END").map { c ->
            check(c.size == 3) { "模板表必须 3 列：$c" }
            TemplateRow(c[0], c[1], tokens(c[2]))
        }
        return Tables(layers, unshoot, quotas, alts, templates)
    }

    /** `…` 是任意填充；其余片段按字面量钉住，不做语义扩展。 */
    private fun matches(template: String, text: String): Boolean {
        val pattern = template.split('…').joinToString(".*") { part -> Pattern.quote(part) }
        return Regex(pattern).containsMatchIn(text)
    }

    private fun judgeLine(text: String, layer: Layer, t: Tables): List<Finding> {
        val out = ArrayList<Finding>()
        if (layer != Layer.SCRIPT) return out
        t.unshoot.forEach { rule ->
            if (matches(rule.template, text)) {
                out += Finding(
                    "UNSHOOTABLE-${rule.id}",
                    "剧本层不可拍摄 ${rule.category} ${rule.id}：${rule.template}",
                )
            }
        }
        t.templates.forEach { rule ->
            if (matches(rule.template, text) && rule.exceptions.none { text.contains(it) }) {
                out += Finding("TEMPLATE-BORROWED", "模板借用 ${rule.id}：${rule.template}（戏中戏须标明）")
            }
        }
        return out
    }

    private fun judgeEpisode(lines: List<String>, t: Tables): List<Finding> {
        val out = ArrayList<Finding>()
        val q1 = t.quotas.single { it.id == "Q1" }
        val q2 = t.quotas.single { it.id == "Q2" }
        val eyeLines = lines.filter { line -> q1.tokens.any { line.contains(it) } }
        val tearLines = lines.filter { line -> q2.tokens.any { line.contains(it) } }

        if (eyeLines.size > q1.limit) {
            out += Finding("Q1-COUNT", "眼部特写 ${eyeLines.size} 次 > ${q1.limit}／${q1.unit}")
        }
        eyeLines.forEach { line ->
            if (q1.necessity.none { line.contains(it) }) {
                out += Finding("Q1-NO-NECESSITY", "眼部特写缺戏剧必要性标记：${q1.necessity.joinToString("／")}")
            }
        }
        if (tearLines.size > q2.limit) {
            out += Finding("Q2-COUNT", "眼泪镜头 ${tearLines.size} 次 > ${q2.limit}／${q2.unit}")
        }
        val alternative = t.alts.firstOrNull { alt -> alt.tokens.any { tok -> lines.any { it.contains(tok) } } }
        if (alternative == null) {
            out += Finding("NO-ALTERNATIVE", "本集无中景背影／手部／环境空镜／沉默时长任一替代")
        }
        return out
    }

    private fun expectMet(found: List<Finding>, expect: String): Boolean {
        if (expect == "GREEN") return found.isEmpty()
        check(expect.startsWith("RED:")) { "未知期望：$expect" }
        val code = expect.removePrefix("RED:")
        return found.any { f -> f.code.contains(code) || f.message.contains("$code：") }
    }

    private fun assertCase(id: String, found: List<Finding>, expect: String) {
        assertTrue("$id 期望 $expect，实际 $found", expectMet(found, expect))
    }

    /** 完整夹具文本固定在代码里，不从文档摘要重建——删行变异才能被看见。 */
    private val lineCases = listOf(
        LineCase("F01", Layer.NOVEL, "她闻到雨后的土腥味，心里一松。", "GREEN"),
        LineCase("F02", Layer.NOVEL, "他嘴角抽了一下，心想这下糟了。", "GREEN"),
        LineCase("F03", Layer.SCRIPT, "他走到窗边，把杯子放在桌上。", "GREEN"),
        LineCase("F04", Layer.SCRIPT, "中景。她背对镜头，手搭在门框上，沉默三秒。", "GREEN"),
        LineCase("F05", Layer.SCRIPT, "她嘴角抽了一下。", "RED:U01"),
        LineCase("F06", Layer.SCRIPT, "她心里一沉。", "RED:U03"),
        LineCase("F07", Layer.SCRIPT, "她眼含泪光，强忍着。", "RED:U05"),
        LineCase("F08", Layer.SCRIPT, "像某演员的经典演法，她哭得肩膀发抖。", "RED:T01"),
        LineCase("F09", Layer.SCRIPT, "戏中戏：标明她像某演员的经典演法那样哭。", "GREEN"),
        LineCase("F10", Layer.SCRIPT, "琼瑶式哭戏。", "RED:T02"),
        LineCase("F11", Layer.SCRIPT, "戏中戏：标明这是琼瑶式哭戏的排演。", "GREEN"),
        LineCase("F12", Layer.SCRIPT, "眉头微不可察地皱了一下。", "RED:U02"),
        LineCase("F13", Layer.SCRIPT, "她心想这事还没完。", "RED:U04"),
        LineCase("F14", Layer.SCRIPT, "他泪流满面。", "RED:U07"),
        LineCase("F15", Layer.SCRIPT, "她露出一个苦涩的笑容。", "RED:U08"),
        LineCase("F16", Layer.SCRIPT, "眼部特写，必要：交代凶器位置。", "GREEN"),
    )

    private val episodeCases = listOf(
        EpisodeCase(
            "E01",
            listOf(
                "近景，眼部特写，必要：交代桌上的信件。",
                "手部镜头，把信推过去。",
                "中景背影，她站在窗前。",
                "眼部特写，因为要看清她是否说谎。",
                "眼泪落下来。",
            ),
            "GREEN",
        ),
        EpisodeCase(
            "E02",
            listOf(
                "眼部特写，必要：看清第一处细节。",
                "眼部特写，必要：看清第二处细节。",
                "眼部特写，因为要看清第三处细节。",
                "中景背影，手部停在半空。",
            ),
            "RED:Q1-COUNT",
        ),
        EpisodeCase(
            "E03",
            listOf("眼泪滑下来。", "眼泪又落下来。", "手部镜头，指尖攥紧衣角。"),
            "RED:Q2-COUNT",
        ),
        EpisodeCase(
            "E04",
            listOf("眼部特写，她盯着门口。", "手部镜头，杯子被推到一边。"),
            "RED:Q1-NO-NECESSITY",
        ),
        EpisodeCase("E05", listOf("眼部特写，必要：看清他的表情。"), "RED:NO-ALTERNATIVE"),
        EpisodeCase("E06", listOf("沉默三秒。"), "GREEN"),
        EpisodeCase(
            "E07",
            listOf(
                "眼部特写，必要：交代凶器位置。",
                "眼部特写，因为要看清签名。",
                "眼泪落下来。",
                "手部镜头，手指按住信封。",
            ),
            "GREEN",
        ),
    )

    @Test
    fun `两层表条数从唯一表复现`() {
        val t = tables()
        assertEquals("两层表 10 行：小说 3 + 剧本 7", 10, t.layers.size)
        assertEquals(3, t.layers.count { it.layer.contains("小说") })
        assertEquals(7, t.layers.count { it.layer.contains("剧本") })
        assertEquals("两层都只写 ALLOW，判定前提由层名给出", setOf("ALLOW"), t.layers.map { it.verdict }.toSet())
        println("SCRIPT-READING layers=${t.layers.size} novel=3 script=7")
    }

    @Test
    fun `三类不可拍摄闭集齐全`() {
        val t = tables()
        assertEquals("闭集恰好三类", setOf("微表情", "内心提示", "表演指令"), t.unshoot.map { it.category }.toSet())
        assertEquals(2, t.unshoot.count { it.category == "微表情" })
        assertEquals(2, t.unshoot.count { it.category == "内心提示" })
        assertEquals(4, t.unshoot.count { it.category == "表演指令" })
        assertEquals(setOf("RED"), t.unshoot.map { it.verdict }.toSet())
        assertTrue("只收句式，不收单词", t.unshoot.all { it.template.length >= 3 })
        println("SCRIPT-READING unshoot=${t.unshoot.size} byCategory=微表情2/内心2/表演4")
    }

    @Test
    fun `配额参数从唯一表复现`() {
        val t = tables()
        assertEquals(2, t.quotas.size)
        val q1 = t.quotas.single { it.id == "Q1" }
        val q2 = t.quotas.single { it.id == "Q2" }
        assertEquals(2, q1.limit)
        assertEquals("集", q1.unit)
        assertEquals(listOf("必要：", "因为"), q1.necessity)
        assertEquals(1, q2.limit)
        assertTrue("眼泪不需要必要性标记", q2.necessity.isEmpty())
        println("SCRIPT-READING quota Q1=${q1.limit}/${q1.unit} Q2=${q2.limit}/${q2.unit}")
    }

    @Test
    fun `行夹具逐条咬住`() {
        val t = tables()
        val bad = lineCases.filter { c -> !expectMet(judgeLine(c.text, c.layer, t), c.expect) }
        println("SCRIPT-READING line red=${lineCases.size - bad.size}/${lineCases.size} bad=${bad.joinToString { it.id }}")
        assertEquals("有夹具没咬住：$bad", emptyList<LineCase>(), bad)
    }

    @Test
    fun `同一句小说层绿剧本层红`() {
        val t = tables()
        val sampleFor = { id: String ->
            if (id == "U06") "她强忍着没哭出声。"
            else lineCases.first { it.expect == "RED:$id" }.text
        }
        t.unshoot.forEach { rule ->
            val text = sampleFor(rule.id)
            assertTrue("小说层须绿 ${rule.id}：$text", judgeLine(text, Layer.NOVEL, t).isEmpty())
            assertTrue("剧本层须红 ${rule.id}：$text", judgeLine(text, Layer.SCRIPT, t).any { it.code == "UNSHOOTABLE-${rule.id}" })
        }
        println("SCRIPT-READING layerSplit=${t.unshoot.size}/${t.unshoot.size}")
    }

    @Test
    fun `双命中两条都记不合并`() {
        val t = tables()
        val c = lineCases.single { it.id == "F07" }
        val ids = judgeLine(c.text, Layer.SCRIPT, t)
            .filter { it.code.startsWith("UNSHOOTABLE-") }
            .map { it.code.removePrefix("UNSHOOTABLE-") }
        assertEquals("眼含泪光＋强忍须两条都记", listOf("U05", "U06"), ids)
    }

    @Test
    fun `模板无例外标记才红_空例外负例也红`() {
        val t = tables()
        listOf("F08", "F10").forEach { id ->
            val c = lineCases.single { it.id == id }
            assertTrue("$id 无例外标记必须红", judgeLine(c.text, Layer.SCRIPT, t).any { it.code == "TEMPLATE-BORROWED" })
        }
        listOf("F09", "F11").forEach { id ->
            val c = lineCases.single { it.id == id }
            assertTrue("$id 戏中戏已标明必须绿", judgeLine(c.text, Layer.SCRIPT, t).isEmpty())
        }
        val emptied = t.copy(templates = t.templates.map { it.copy(exceptions = emptyList()) })
        val f09 = lineCases.single { it.id == "F09" }
        assertTrue("例外标记置空后角色学演法也应红（负例自检）", judgeLine(f09.text, Layer.SCRIPT, emptied).any { it.code == "TEMPLATE-BORROWED" })
        println("SCRIPT-READING template red=2 green=2 emptyExceptionNegative=1")
    }

    @Test
    fun `集夹具逐条咬住`() {
        val t = tables()
        val bad = episodeCases.filter { c -> !expectMet(judgeEpisode(c.lines, t), c.expect) }
        println("SCRIPT-READING episode red=${episodeCases.size - bad.size}/${episodeCases.size} bad=${bad.joinToString { it.id }}")
        assertEquals("有集夹具没咬住：$bad", emptyList<EpisodeCase>(), bad)
    }

    @Test
    fun `文档夹具编号与代码夹具一致`() {
        val docLine = rows("SCRIPT-FIXTURE-BEGIN", "SCRIPT-FIXTURE-END").map { it[0] to it[3] }
        val codeLine = lineCases.map { it.id to it.expect }
        assertEquals("行夹具编号或期望与文档不符", codeLine, docLine)
        val docEp = rows("SCRIPT-EPISODE-BEGIN", "SCRIPT-EPISODE-END").map { it[0] to it[2] }
        val codeEp = episodeCases.map { it.id to it.expect }
        assertEquals("集夹具编号或期望与文档不符", codeEp, docEp)
        assertEquals("五条变异", 5, rows("SCRIPT-MUTATION-BEGIN", "SCRIPT-MUTATION-END").size)
        println("SCRIPT-READING docFixture line=${docLine.size} episode=${docEp.size} mutation=5")
    }

    @Test
    fun `V1删不可拍摄行_对应夹具由红转绿`() {
        val t = tables()
        val c = lineCases.single { it.id == "F05" }
        assertTrue("基线必须红在 U01：${judgeLine(c.text, Layer.SCRIPT, t)}", expectMet(judgeLine(c.text, Layer.SCRIPT, t), c.expect))
        val dropped = t.copy(unshoot = t.unshoot.filter { it.id != "U01" })
        assertTrue("删 U01 后必须转绿", expectMet(judgeLine(c.text, Layer.SCRIPT, dropped), "GREEN"))
        println("SCRIPT-READING V1 RED:U01 -> GREEN")
    }

    @Test
    fun `V2V3配额改数字_超限夹具转绿`() {
        val t = tables()
        val e02 = episodeCases.single { it.id == "E02" }
        val e03 = episodeCases.single { it.id == "E03" }
        assertTrue("基线 E02 红在 Q1-COUNT", expectMet(judgeEpisode(e02.lines, t), e02.expect))
        assertTrue("基线 E03 红在 Q2-COUNT", expectMet(judgeEpisode(e03.lines, t), e03.expect))
        val raised = t.copy(quotas = t.quotas.map { q ->
            when (q.id) {
                "Q1" -> q.copy(limit = 3)
                "Q2" -> q.copy(limit = 2)
                else -> q
            }
        })
        assertTrue("Q1 上限 2→3 后 E02 转绿", expectMet(judgeEpisode(e02.lines, raised), "GREEN"))
        assertTrue("Q2 上限 1→2 后 E03 转绿", expectMet(judgeEpisode(e03.lines, raised), "GREEN"))
        println("SCRIPT-READING V2 Q1-COUNT->GREEN V3 Q2-COUNT->GREEN")
    }

    @Test
    fun `V4删替代手法_沉默时长集转红`() {
        val t = tables()
        val e06 = episodeCases.single { it.id == "E06" }
        assertTrue("基线 E06 绿", expectMet(judgeEpisode(e06.lines, t), e06.expect))
        val dropped = t.copy(alts = t.alts.filter { it.id != "A4" })
        assertTrue("删 A4 后 E06 必须红在 NO-ALTERNATIVE", expectMet(judgeEpisode(e06.lines, dropped), "RED:NO-ALTERNATIVE"))
        println("SCRIPT-READING V4 GREEN -> RED:NO-ALTERNATIVE")
    }

    @Test
    fun `V5删模板行_对应夹具由红转绿`() {
        val t = tables()
        val c = lineCases.single { it.id == "F08" }
        assertTrue("基线必须红在 T01", expectMet(judgeLine(c.text, Layer.SCRIPT, t), c.expect))
        val dropped = t.copy(templates = t.templates.filter { it.id != "T01" })
        assertTrue("删 T01 后必须转绿", expectMet(judgeLine(c.text, Layer.SCRIPT, dropped), "GREEN"))
        println("SCRIPT-READING V5 RED:T01 -> GREEN")
    }

    /** 剥掉 Kotlin 注释，只看真正的 import（KDoc 里提到 929 不算越界）。 */
    private fun stripComments(source: String): String {
        var inBlock = false
        return source.lines().filter { line ->
            val trimmed = line.trimStart()
            when {
                inBlock -> {
                    if (trimmed.contains("*/")) inBlock = false
                    false
                }
                trimmed.startsWith("//") -> false
                trimmed.startsWith("/*") -> {
                    if (!trimmed.contains("*/")) inBlock = true
                    false
                }
                else -> true
            }
        }.joinToString("\n")
    }

    @Test
    fun `本单零生产依赖_不引用929等未派单`() {
        val candidates = listOf(
            File("src/test/java/com/example/zhengdao/ScriptLayerGateTest.kt"),
            File("app/src/test/java/com/example/zhengdao/ScriptLayerGateTest.kt"),
        )
        val src = candidates.firstOrNull { it.isFile }?.readText()
            ?: error("找不到本测试源码：${candidates.joinToString { it.absolutePath }}")
        val body = stripComments(src)
        assertFalse("本单不许 import 生产包（929 未派、940/942 在飞）", Regex("""(?m)^import\s+com\.example\.zhengdao\.""").containsMatchIn(body))
        assertTrue("夹具仍从文档唯一表取值", body.contains("SCRIPT-QUOTA-BEGIN"))
        println("SCRIPT-READING productionImports=0")
    }
}
