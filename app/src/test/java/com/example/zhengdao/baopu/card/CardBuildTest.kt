// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第四十五批（2026-10-19）：card_build —— 母档 → 三套格式产物；不覆盖、单字关键词拒收。
package com.example.zhengdao.baopu.card

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CardBuildTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun spec(
        format: String = CardBuild.FORMAT_WEB,
        keywords: String = "[\"核心需求\",\"驱动力\"]",
        world: String = "[\"规则一\",\"规则二\"]",
    ): String = """
        {"format":"$format","name":"测试作品","persona":"人设文本",
         "world":$world,"keywords":$keywords,"style":"文风","html":"<div>骨架</div>","css":".gc{color:red}"}
    """.trimIndent()

    private fun build(raw: String, out: String? = null): CardBuild.Built =
        CardBuild.build(CardBuild.parseSpec(raw), tmp.root, out)

    // ── web（599a：默认裸数组；开关切整包；预检可配；超限报数不截断）──────

    @Test
    fun web_builds_six_files_and_key_syntax_is_legal() {
        val b = build(spec())
        val names = b.files.map { it.name }.toSet()
        assertEquals(
            setOf("world_book.json", "提示词.txt", "前置词.txt", "后置词.txt", "内置CSS.txt", "详细介绍.html"),
            names,
        )
        val wb = File(b.outDir, "world_book.json").readText()
        assertTrue("key 必须是 _or_…@wb@…", wb.contains("_or_核心需求@wb@驱动力"))
        // 599a 判据⑨：默认外壳＝裸数组；可选字段无则不写
        assertTrue("默认裸数组开头", wb.trimStart().startsWith("["))
        assertTrue("不再有 world_book 外壳", !wb.contains("\"world_book\""))
        assertTrue("可选字段 value_type 无则不写", !wb.contains("value_type"))
        assertTrue("可选字段 value_configs 无则不写", !wb.contains("value_configs"))
        val verdict = CardFormat.recognize(wb.toByteArray())
        assertEquals("要能认脸成网络平台族", FormatKind.WEB_PLATFORM_BOOK, verdict.format)
        assertEquals("BARE_ARRAY", verdict.subtype)
    }

    @Test
    fun web_whole_package_switch_writes_world_book_object() {
        val raw = """
            {"format":"web","name":"测试作品","persona":"人设文本","world":["规则一"],"keywords":["核心需求"],
             "style":"文风","html":"","css":"","whole_package":true,
             "pre_text":"前置文本","post_text":"后置文本","非白名单":"不许写"}
        """.trimIndent()
        val b = build(raw)
        val wb = File(b.outDir, "world_book.json").readText()
        val o = JSONObject(wb)
        assertTrue("整包外壳：world_book 是数组", o.optJSONArray("world_book") != null)
        assertEquals("整包可选字段：有则写", "前置文本", o.optString("pre_text"))
        assertEquals("后置文本", o.optString("post_text"))
        assertFalse("白名单外的顶层键不写", o.has("非白名单"))
        val verdict = CardFormat.recognize(wb.toByteArray())
        assertEquals(FormatKind.WEB_PLATFORM_BOOK, verdict.format)
        assertEquals("PLAIN", verdict.subtype)
    }

    @Test
    fun web_precheck_limits_are_configurable() {
        // 判据⑧：改上限配置 ⇒ 预检阈值随之变（entry_limit=1，两条 ⇒ 报"当前 2 条"）
        val raw = """
            {"format":"web","name":"测试作品","world":["规则一","规则二"],"keywords":["核心需求"],"entry_limit":1}
        """.trimIndent()
        val err = runCatching { build(raw) }.exceptionOrNull()
        assertTrue("应报超限：$err", err is CardBuild.BuildError)
        assertTrue(err!!.message!!.contains("当前 2 条"))
        assertTrue(err.message!!.contains("夜语 1000/20000，风月 2000/40000"))
        // 阈值改回 2 ⇒ 同量通过（同一份代码，只有配置变了）
        val ok = build(
            """{"format":"web","name":"测试作品","world":["规则一","规则二"],"keywords":["核心需求"],"entry_limit":2}""",
        )
        assertTrue(File(ok.outDir, "world_book.json").isFile)
    }

    @Test
    fun web_precheck_default_strictest_reports_and_writes_nothing() {
        // 默认族内最严 1000/20000：两条共 20002 字 ⇒ 报"当前 2 条/20002 字"；整次拒绝、零落盘（不截断）
        val mother = JSONObject()
            .put("format", "web")
            .put("name", "测试作品")
            .put("keywords", org.json.JSONArray(listOf("核心需求")))
            .put("world", org.json.JSONArray(listOf("规".repeat(10001), "则".repeat(10001))))
        val err = runCatching { build(mother.toString()) }.exceptionOrNull()
        assertTrue("应报超限：$err", err is CardBuild.BuildError)
        assertTrue(err!!.message!!.contains("夜语 1000/20000，风月 2000/40000"))
        assertTrue(err.message!!.contains("当前 2 条/20002 字"))
        assertTrue(err.message!!.contains("不截断"))
        assertFalse("超限不许落半个文件", File(tmp.root, "成品/测试作品/world_book.json").exists())
    }

    @Test
    fun web_default_out_dir_is_under_deliverables() {
        val b = build(spec())
        assertTrue(b.outDir.path.replace('\\', '/').endsWith("成品/测试作品"))
    }

    // ── tavo ─────────────────────────────────────────────────────────────

    @Test
    fun tavo_builds_card_and_lorebook_and_is_recognized() {
        val b = build(spec(format = "tavo"))
        val card = File(b.outDir, "card.json")
        val lore = File(b.outDir, "lorebook.json")
        assertTrue(card.isFile && lore.isFile)
        assertTrue("tavo 卡应被认成角色卡", CardFormat.recognize(card.readBytes()).format == FormatKind.TAVO_CHAR)
        assertTrue(lore.readText().contains("\"keywords\""))
    }

    // ── st ───────────────────────────────────────────────────────────────

    @Test
    fun st_builds_card_and_png_with_ccv3_block() {
        val b = build(spec(format = "st"))
        val png = File(b.outDir, "card.png")
        assertTrue(png.isFile)
        val read = CardPng.read(png.readBytes())
        assertTrue("PNG 里应有 ccv3/chara 块", read is PngRead.Ok)
        assertTrue((read as PngRead.Ok).jsonText.contains("chara_card_v3"))
    }

    // ── 拒绝路径 ─────────────────────────────────────────────────────────

    @Test
    fun refuses_when_target_exists_no_partial_overwrite() {
        val first = build(spec())
        val okFile = File(first.outDir, "world_book.json")
        val before = okFile.readText()
        val err = runCatching { build(spec(), "成品/测试作品") }.exceptionOrNull()
        assertTrue("应拒绝：$err", err is CardBuild.BuildError && err.message!!.contains("未覆盖"))
        assertEquals("既有文件一个字节都不许动", before, okFile.readText())
    }

    @Test
    fun refuses_single_char_keyword() {
        val err = runCatching { build(spec(keywords = "[\"火\",\"水\"]")) }.exceptionOrNull()
        assertTrue("单字必须拒：$err", err is CardBuild.BuildError && err.message!!.contains("单字"))
    }

    @Test
    fun refuses_out_dir_escape() {
        val err = runCatching { build(spec(), "../../外面") }.exceptionOrNull()
        assertTrue("越界必须拒：$err", err is CardBuild.BuildError)
    }

    @Test
    fun parses_minimal_spec_and_rejects_bad_format() {
        val s = CardBuild.parseSpec("""{"format":"web","name":"n","keywords":["甲乙"]}""")
        assertEquals("n", s.name)
        assertTrue(s.world.isEmpty())
        val err = runCatching { CardBuild.parseSpec("""{"format":"x","name":"n","keywords":["甲乙"]}""") }
        assertTrue(err.exceptionOrNull() is CardBuild.BuildError)
    }
}
