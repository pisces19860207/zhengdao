// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 第四十五批（2026-10-19）：card_build —— 母档 → 三套格式产物；不覆盖、单字关键词拒收。
package com.example.zhengdao.baopu.card

import org.junit.Assert.assertEquals
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

    // ── web ──────────────────────────────────────────────────────────────

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
        assertTrue("常规模式默认 value_type=0", wb.contains("\"value_type\": 0"))
        assertTrue("value_configs 默认空数组", wb.contains("\"value_configs\": []"))
        assertTrue("要能认脸成网络平台世界书", CardFormat.recognize(wb.toByteArray()).format == FormatKind.WEB_BOOK)
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
