// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百四十五批 · B2：Tavo 预设的 `basicPrompts` 是**数组**。
//
// 225 把模型字段写成了 `JSONObject?`，解析点又用 `objectOf` 去取 ⇒ 恒 null，
// 这个字段从来没被读到过。判据（派工单 B2）：
// · 字段是 `List<JSONObject>`；数组照收、顺序与内容都在；
// · 读不出来给**空表**（不是 null）；
// · 不是数组／数组里混进非对象 ⇒ 记 unparsed 并保留原文；
// · `card_inspect` 能看到条数；
// · 为空／缺键时不许崩。
package com.example.zhengdao.baopu.card

import com.example.zhengdao.baopu.agent.CardTools
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PresetBasicPromptsTest {

    private val withThree = """
{"kind":"preset","id":"p1","name":"演示预设","updatedAt":0,"active":true,
 "basicPrompts":[
   {"identifier":"a","name":"基础甲","content":"内容甲"},
   {"identifier":"b","name":"基础乙","content":"内容乙"},
   {"identifier":"c","name":"基础丙","content":"内容丙"}],
 "entries":[{"identifier":"1","name":"示例","content":"示例正文","role":"system","enabled":true}]}
"""

    @Test
    fun `正例_数组照收_顺序与内容都在`() {
        val p = parse(withThree)
        val preset = p.preset
        assertNotNull("应当认成预设", preset)
        assertEquals(3, preset!!.basicPrompts.size)
        assertEquals(listOf("基础甲", "基础乙", "基础丙"), preset.basicPrompts.map { it.optString("name") })
        assertEquals("内容丙", preset.basicPrompts[2].optString("content"))
        assertTrue("数组本身不该记 unparsed：${p.unparsed.map { it.reason }}", p.unparsed.isEmpty())
    }

    @Test
    fun `正例_缺键与空数组都给空表而不是 null`() {
        val missing = parse(
            """{"kind":"preset","id":"p2","name":"无提示词","updatedAt":0,"active":true,
               "entries":[{"identifier":"1","name":"示例","content":"x","role":"system","enabled":true}]}""",
        )
        assertEquals(0, missing.preset!!.basicPrompts.size)
        assertTrue("缺键不是解析失败", missing.unparsed.isEmpty())

        val empty = parse(
            """{"kind":"preset","id":"p3","name":"空提示词","updatedAt":0,"basicPrompts":[],
               "entries":[{"identifier":"1","name":"示例","content":"x","role":"system","enabled":true}]}""",
        )
        assertEquals(0, empty.preset!!.basicPrompts.size)
        assertTrue(empty.unparsed.isEmpty())
    }

    @Test
    fun `负例_不是数组时记 unparsed 并保留原文`() {
        val p = parse(
            """{"kind":"preset","id":"p4","name":"怪","updatedAt":0,
               "basicPrompts":{"a":1,"note":"原样留着"},
               "entries":[{"identifier":"1","name":"示例","content":"x","role":"system","enabled":true}]}""",
        )
        assertEquals("读不出来给空表，不是 null", 0, p.preset!!.basicPrompts.size)
        assertEquals(1, p.unparsed.size)
        assertTrue(p.unparsed[0].reason, p.unparsed[0].reason.contains("basicPrompts 不是数组"))
        assertTrue("原文要留着：${p.unparsed[0].rawText}", p.unparsed[0].rawText.contains("原样留着"))
    }

    @Test
    fun `负例_数组里混进非对象只丢那一条_其余照收`() {
        val p = parse(
            """{"kind":"preset","id":"p5","name":"混","updatedAt":0,
               "basicPrompts":[{"name":"甲"},"裸字符串",{"name":"丙"}],
               "entries":[{"identifier":"1","name":"示例","content":"x","role":"system","enabled":true}]}""",
        )
        assertEquals(listOf("甲", "丙"), p.preset!!.basicPrompts.map { it.optString("name") })
        assertEquals(1, p.unparsed.size)
        assertEquals("坏的那条的序号要指对", 1, p.unparsed[0].index)
        assertTrue(p.unparsed[0].rawText.contains("裸字符串"))
    }

    @Test
    fun `正例_card_inspect 报告里能看到基础提示词条数`() {
        val text = CardTools.inspect(withThree.toByteArray(Charsets.UTF_8))
        assertTrue("要有【预设】节：\n$text", text.contains("【预设】"))
        assertTrue("要报条数：\n$text", text.contains("基础提示词 3 条"))
        assertFalse("不该有未解析段：\n$text", text.contains("【未解析】"))
    }

    // ── 门禁：字段类型（"仍返回 null" 的结构面） ─────────────────────────

    @Test
    fun `门禁_字段是列表不是可空对象`() {
        val model = codeOnly(readMain("card/CardModel.kt"))
        assertTrue(
            "basicPrompts 必须是 List<JSONObject>",
            Regex("""val basicPrompts: List<JSONObject>""").containsMatchIn(model),
        )
        assertFalse(
            "225 的 JSONObject? 恒 null，不许回来",
            Regex("""val basicPrompts: JSONObject\?""").containsMatchIn(model),
        )
    }

    @Test
    fun `门禁_解析点不再用 objectOf 取数组字段`() {
        val parser = codeOnly(readMain("card/CardAssetParser.kt"))
        assertFalse(
            "objectOf(raw, \"basicPrompts\") 对数组恒 null",
            Regex("""objectOf\(raw, "basicPrompts"\)""").containsMatchIn(parser),
        )
        assertTrue(Regex("""basicPromptsOf\(raw, bad\)""").containsMatchIn(parser))
    }

    // ── 小工具 ───────────────────────────────────────────────────────────

    private fun parse(json: String): CardAssetParser.Parsed {
        val p = CardAssetParser.parse(json.toByteArray(Charsets.UTF_8))
        assertNotNull("应当能解析：${json.take(40)}", p)
        return p!!
    }

    private fun repoRoot(): File {
        var d: File? = File("").absoluteFile
        while (d != null && !File(d, "app/src/main/java").isDirectory) d = d.parentFile
        return d ?: File("").absoluteFile
    }

    private fun readMain(rel: String): String =
        File(repoRoot(), "app/src/main/java/com/example/zhengdao/baopu/$rel").readText()

    private fun codeOnly(src: String): String =
        src.replace("\r\n", "\n").split("\n").joinToString("\n") { it.substringBefore("//") }
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
}
