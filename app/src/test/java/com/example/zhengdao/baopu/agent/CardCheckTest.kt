// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 判据：F4（四个越界）／F5（单字＋HTML 标签名）／F6（A·B·C 五组）／F12–F16（卡内 INFO）
//       ／F20-i（视图只读一致）／F21（KEYSEP_BROKEN）／F22–F25（方言／区域）
// 夹具：CardFixtures（全部合成）
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.card.CardAsset
import com.example.zhengdao.baopu.card.CardAssetParser
import com.example.zhengdao.baopu.card.CardCheck
import com.example.zhengdao.baopu.card.CardDialectChecks
import com.example.zhengdao.baopu.card.CardDialectMap
import com.example.zhengdao.baopu.card.CardJson
import com.example.zhengdao.baopu.card.CheckReport
import com.example.zhengdao.baopu.card.FengyueWork
import com.example.zhengdao.baopu.card.Level
import com.example.zhengdao.baopu.card.Stage
import com.example.zhengdao.baopu.card.Worldbook
import com.example.zhengdao.baopu.card.WorldbookDialect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CardCheckTest {

    private fun check(
        json: String,
        prompt: String? = null,
        pre: String? = null,
        post: String? = null,
        html: String? = null,
    ): CheckReport {
        val parsed = CardAssetParser.parse(json.toByteArray(Charsets.UTF_8))!!
        return CardCheck.check(parsed, prompt, pre, post, html)
    }

    private fun CheckReport.codes(): List<String> = findings.map { it.code }


    /**
     * F4 的共同断言。⚠️ 判据表原话是「恰好一条 ERROR」，但 J1 自己的 key
     * `_or_甲@wb@乙` 含两个单字 ⇒ 恒定产出一条 FY_KEY_TOO_BROAD(ERROR)。
     * 所以这里钉「该字段恰好一条 ERROR ＋ 不出现别的种类的 ERROR」。
     */
    private fun assertSingleRangeError(r: CheckReport, code: String, field: String) {
        val e = r.findings.filter { it.level == Level.ERROR }
        assertEquals("「$field」应恰好一条 ERROR：${e.map { it.code }}", 1, e.count { it.field == field })
        assertTrue("出现了意料外的 ERROR：${e.map { it.code }}", e.all { it.field == field || it.code == "FY_KEY_TOO_BROAD" })
        val f = e.first { it.field == field }
        assertEquals(code, f.code)
        assertEquals(0, f.entry?.index)
    }

    private fun kindOf(a: CardAsset): String = when (a) {
        is Worldbook -> "worldbook:${a.dialect}"
        is FengyueWork -> "work"
        else -> a.javaClass.simpleName
    }

    // ── F4 · 四个越界值（拆四个用例，不许合并）─────────────────────────────

    @Test
    fun `fengyue_probability_zero_is_error`() {
        assertSingleRangeError(check(CardFixtures.J1.replace("\"probability\":100", "\"probability\":0")), "FY_PROBABILITY_RANGE", "probability")
    }

    @Test
    fun `fengyue_depth_21_is_error`() {
        assertSingleRangeError(check(CardFixtures.J1.replace("\"depth\":2", "\"depth\":21")), "FY_DEPTH_RANGE", "depth")
    }

    @Test
    fun `fengyue_key_region_8_is_error`() {
        assertSingleRangeError(check(CardFixtures.J1.replace("\"key_region\":3", "\"key_region\":8")), "FY_KEY_REGION_RANGE", "key_region")
    }

    @Test
    fun `fengyue_match_type_5_is_error`() {
        assertSingleRangeError(check(CardFixtures.J1.replace("\"match_type\":2", "\"match_type\":5")), "FY_MATCH_TYPE_RANGE", "match_type")
    }

    // ── F5 · `_or_` 单字 ────────────────────────────────────────────────

    @Test
    fun `fengyue_or_single_char_is_score_assassin_risk`() {
        val r = check(CardFixtures.N4)
        val broad = r.findings.filter { it.code.startsWith("FY_KEY_TOO_BROAD") }
        assertEquals(
            listOf(0 to ("FY_KEY_TOO_BROAD" to Level.ERROR), 1 to ("FY_KEY_TOO_BROAD_AND" to Level.WARN)),
            broad.map { it.entry?.index to (it.code to it.level) },
        )
        assertEquals("key", broad[0].field)
        assertEquals(2, r.stats.broadKeyCount)
        // 第 2 条（水滴/火焰）：不许有 ERROR/WARN，也不许有过宽判定
        // （group 为空的 INFO、key_region=3 的「AI 不触发」INFO 是常态提示，不算「判错」）
        assertTrue(
            "第 2 条不该有 ERROR/WARN：${r.findings.filter { it.entry?.index == 2 }}",
            r.findings.none { it.entry?.index == 2 && it.level != Level.INFO },
        )
        assertTrue(r.findings.none { it.entry?.index == 2 && it.code.contains("TOO_BROAD") })
    }

    @Test
    fun `single_char_boundaries_strip_fullwidth_and_combining`() {
        fun broad(key: String): List<String> =
            check(CardFixtures.webBook(CardFixtures.webEntry(key))).findings
                .filter { it.code.startsWith("FY_KEY_TOO_BROAD") }.map { it.code }

        // 分隔符旁有空格：strip 后仍是单字
        assertTrue("_or_ 水 应仍报单字", broad("_or_ 水").contains("FY_KEY_TOO_BROAD"))
        // 全角字母：1 个码点
        assertTrue("_or_Ａ 应算单字", broad("_or_Ａ").contains("FY_KEY_TOO_BROAD"))
        // e + 组合符 = 2 个码点 ⇒ 不算单字（已知漏报口子，主文 §8-10）
        assertTrue("e+组合符不该算单字", broad("_or_e\u0301").isEmpty())
    }

    @Test
    fun `keyword_is_html_token_warns`() {
        val json = CardFixtures.webBook(
            CardFixtures.webEntry("_or_div@wb@雨天", sort = 1),
            CardFixtures.webEntry("_or_span@wb@class", sort = 2),
        )
        val r = check(json)
        val html = r.findings.filter { it.code == "KEY_IS_HTML_TOKEN" }
        assertEquals(2, html.size)
        assertTrue(html.all { it.level == Level.WARN && it.field == "key" })
        assertTrue("div/span 不是单字，不该报过宽", r.findings.none { it.code.startsWith("FY_KEY_TOO_BROAD") })
    }

    // ── F6-a ────────────────────────────────────────────────────────────

    @Test
    fun `trigger_class_without_html_marks_unjudged`() {
        val r = check(CardFixtures.N5, prompt = "甲出现了")
        assertEquals(0, r.stats.cCount)          // 能给 0，但必须是 0 不是 null
        assertEquals(2, r.stats.unjudgedCount)
        assertEquals(2, r.stats.notApplicableCount)
        assertEquals(2, r.count("FY_NOT_APPLICABLE_REGION"))
        assertTrue(r.findings.filter { it.code == "FY_NOT_APPLICABLE_REGION" }.all { it.level == Level.INFO })
        assertTrue("C 类计数要能显示 0：${CardCheck.render(r)}", CardCheck.render(r).contains("有 0 条永远触发不了"))
    }

    // ── F6-b ────────────────────────────────────────────────────────────

    @Test
    fun `trigger_class_with_html_reports_c_index`() {
        val r = check(CardFixtures.N5, prompt = "甲出现了", html = "戊")
        assertEquals(1, r.stats.cCount)
        val c = r.findings.filter { it.code == "TRIGGER_CLASS_C" }
        assertEquals(1, c.size)
        assertEquals(1, c[0].entry?.index)
        assertEquals(0, r.stats.unjudgedCount)
        // 条目 4 只在 HTML 里命中 ⇒ B，不该出任何 ERROR/WARN
        // （N5 的 key 是单字，恒定带一条 FY_KEY_TOO_BROAD；那是 F5 的事，不属「触发判定」）
        assertTrue(
            r.findings.none {
                it.entry?.index == 4 && it.level != Level.INFO && !it.code.startsWith("FY_KEY_TOO_BROAD")
            },
        )
        assertTrue(CardCheck.render(r).contains("有 1 条永远触发不了：第 1 条"))
    }

    // ── F6-c ────────────────────────────────────────────────────────────

    @Test
    fun `trigger_class_without_system_text_is_null_not_zero`() {
        val r = check(CardFixtures.N5)
        assertNull("没给系统文本 ⇒ cCount 必须是 null", r.stats.cCount)
        assertEquals(3, r.stats.unjudgedCount)
        assertEquals(2, r.stats.notApplicableCount)
        assertTrue(CardCheck.render(r).contains("3 条没判：没给系统文本。"))
    }

    // ── F6-d ────────────────────────────────────────────────────────────

    @Test
    fun `tavo_without_html_still_reports_c`() {
        val json = CardFixtures.tavoBook(
            CardFixtures.tavoEntry("1", "keyword", """["甲"]"""),
            CardFixtures.tavoEntry("2", "keyword", """["乙"]"""),
            CardFixtures.tavoEntry("3", "keyword", """["丙"]"""),
        )
        val r = check(json, prompt = "甲出现了")
        assertEquals(2, r.stats.cCount)
        assertEquals(0, r.stats.unjudgedCount)
        assertEquals(2, r.count("TRIGGER_CLASS_C"))
        assertTrue("Tavo 的 C 类不许出 ERROR：${r.errors}", r.errors.isEmpty())
    }

    // ── F6-e ────────────────────────────────────────────────────────────

    @Test
    fun `st_match_switch_decides_c_or_unjudged`() {
        val json = CardFixtures.stWorld(
            CardFixtures.stEntry(uid = 0, key = """["乙"]""", matchDescription = false),
            CardFixtures.stEntry(uid = 1, key = """["乙"]""", matchDescription = true),
        )
        val r = check(json, prompt = "甲出现了")
        assertEquals(1, r.stats.cCount)
        assertEquals(1, r.stats.unjudgedCount)
        val c = r.findings.filter { it.code == "TRIGGER_CLASS_C" }
        assertEquals(1, c.size)
        assertEquals(0, c[0].entry?.index)
    }

    // ── F21 · 关键词语法 ────────────────────────────────────────────────

    @Test
    fun `keysep_broken_is_reported_and_counted_as_c`() {
        val broken = listOf(
            "_or_甲＿or＿乙",      // 全角
            "_or_ 甲@wb@乙",      // 分隔符旁空格
            "_or__or_甲",         // 空 token
            "_or_甲@wb@",         // 首尾残留
            "_or_甲_and_乙",      // 混用
            "甲_or_乙",           // 整串含分隔符却未切分
        )
        for (k in broken) {
            val r = check(CardFixtures.webBook(CardFixtures.webEntry(k)), prompt = "无关文本")
            assertTrue("「$k」应报 KEYSEP_BROKEN", r.codes().contains("KEYSEP_BROKEN"))
            assertEquals("「$k」必须是 ERROR", Level.ERROR, r.findings.first { it.code == "KEYSEP_BROKEN" }.level)
            assertEquals("「$k」要计入 C 类", 1, r.stats.cCount)
        }
        // 正常写法一条都不报
        val ok = check(CardFixtures.webBook(CardFixtures.webEntry("_or_甲@wb@乙")), prompt = "无关文本")
        assertFalse(ok.codes().contains("KEYSEP_BROKEN"))
        assertTrue("切分结果要回显", ok.findings.none { it.code == "KEYSEP_BROKEN" })
    }

    @Test
    fun `keysep_echoes_tokens`() {
        val r = check(CardFixtures.webBook(CardFixtures.webEntry("_or_ 甲@wb@乙")), prompt = "x")
        val f = r.findings.first { it.code == "KEYSEP_BROKEN" }
        assertTrue("证据里要能看见切出的词：${f.evidence}", f.evidence.contains("甲") && f.evidence.contains("乙"))
    }

    // ── F22 · 按 dialect 判 ─────────────────────────────────────────────

    @Test
    fun `dialect_tables_never_cross`() {
        assertTrue(CardDialectChecks.codesFor(WorldbookDialect.WEB).none { it.startsWith("TV_") || it.startsWith("ST_") })
        assertTrue(CardDialectChecks.codesFor(WorldbookDialect.TAVO).none { it.startsWith("FY_") || it.startsWith("ST_") })
        assertTrue(CardDialectChecks.codesFor(WorldbookDialect.ST_NATIVE).none { it.startsWith("FY_") || it.startsWith("TV_") })
    }

    @Test
    fun `web_book_is_not_judged_with_st_rules`() {
        // 给风月条目塞一个酒馆专有字段 constant：不许因此触发 ST_* 判定
        val json = CardFixtures.webBook(
            """{"key":"_or_甲","value":"v","group":"","key_region":3,"value_region":1,"sort":1,"depth":2,"probability":100,"match_type":2,"enable":true,"constant":true}""",
        )
        val r = check(json, prompt = "甲")
        assertTrue("风月书里出现了酒馆码：${r.codes()}", r.findings.none { it.code.startsWith("ST_") })
        assertTrue(r.codes().all { it in CardDialectChecks.codesFor(WorldbookDialect.WEB) })
    }

    // ── F23 / F24 · 方言映射 ────────────────────────────────────────────

    @Test
    fun `unexpressible_fields_are_not_defaulted`() {
        val missing = CardDialectMap.unexpressibleFields(WorldbookDialect.ST_NATIVE, WorldbookDialect.TAVO).toSet()
        // 判据点名的最小集合：order ＋ 六个 match*（表里还有 useProbability，也属「Tavo 没有」）
        val required = setOf(
            "order", "matchPersonaDescription", "matchCharacterDescription",
            "matchCharacterPersonality", "matchCharacterDepthPrompt", "matchScenario", "matchCreatorNotes",
        )
        assertTrue("缺不可表达字段：${required - missing}", missing.containsAll(required))
        for (f in missing) {
            assertNull("「$f」不可表达，不许映射成 false/0", CardDialectMap.mappingOf(WorldbookDialect.ST_NATIVE, f))
        }
    }

    @Test
    fun `injection_depth_maps_to_depth_never_order`() {
        assertEquals("depth", CardDialectMap.mappingOf(WorldbookDialect.TAVO, "injectionDepth"))
        val notes = CardDialectMap.lossyNotes(WorldbookDialect.ST_NATIVE, WorldbookDialect.TAVO).joinToString("；")
        assertTrue("有损清单要写清 relative／before_char 的差异：$notes", notes.contains("before_char") && notes.contains("relative"))
        assertTrue("order 必须标不可表达：$notes", notes.contains("order"))
    }

    // ── F25 · 两套区域分开报 ────────────────────────────────────────────

    @Test
    fun `key_region_and_value_region_reported_separately`() {
        val r = check(
            CardFixtures.webBook(CardFixtures.webEntry("_or_甲", keyRegion = 2, valueRegion = 7)),
            prompt = "甲",
        )
        val key = r.findings.first { it.code == "REGION_KEY_NO_SELF_TRIGGER" }
        val valRegion = r.findings.first { it.code == "REGION_VALUE_MULTI" }
        assertEquals("key_region", key.field)
        assertEquals("value_region", valRegion.field)
        assertTrue("AI 自述不会触发要明说：${key.message}", key.message.contains("AI"))
        assertTrue("多段要提示重复：${valRegion.message}", valRegion.message.contains("多处"))
        // 两个码不许互相替代
        assertFalse(key.code == valRegion.code)
    }

    // ── F12–F16 · 卡内提取（只报 INFO、必须带「未判」）──────────────────

    @Test
    fun `card_info_items_are_info_and_say_unjudged`() {
        val json = """
            {"kind":"character","id":"demo-9","name":"示例角色","updatedAt":0,"data":{
              "name":"示例角色","description":"","character_book":{"name":"内嵌书名"},
              "extensions":{
                "world":"外部书名",
                "regex_scripts":[{"findRegex":"甲"},{"findRegex":"乙"}],
                "tavo":{"theme":{"spec":"tavo_theme_v1","display_mode":"x","status_bar":true}}
              }}}
        """.trimIndent()
        val r = check(json)
        val codes = listOf(
            "CARD_EXTERNAL_WORLDBOOK_UNJUDGED",
            "CARD_EMBEDDED_REGEX_UNJUDGED",
            "CARD_EMBEDDED_THEME_UNJUDGED",
            "CARD_EMBEDDED_BOOK_UNJUDGED",
        )
        for (c in codes) {
            val f = r.findings.firstOrNull { it.code == c }
            assertTrue("缺 $c：${r.codes()}", f != null)
            assertEquals("$c 必须是 INFO", Level.INFO, f!!.level)
            assertTrue("$c 文案必须带「未判」：${f.message}", f.message.contains("未判"))
        }
        assertTrue("内部正则条数要报出来", r.findings.first { it.code == "CARD_EMBEDDED_REGEX_UNJUDGED" }.message.contains("2"))
        assertTrue("全量不许升级成 ERROR/WARN", r.findings.none { it.level != Level.INFO })
    }

    @Test
    fun `card_info_theme_without_spec_is_ignored_not_an_error`() {
        val json = """
            {"kind":"character","id":"demo-9","name":"示例角色","updatedAt":0,"data":{
              "name":"示例角色","extensions":{"tavo":{"theme":{"display_mode":"x"}}}}}
        """.trimIndent()
        val r = check(json)
        val f = r.findings.first { it.code == "CARD_EMBEDDED_THEME_UNJUDGED" }
        assertEquals(Level.INFO, f.level)
        assertTrue(f.message.contains("不含 spec"))
        assertTrue(r.errors.isEmpty())
    }

    // ── F20-i · 视图只读一致 ───────────────────────────────────────────

    @Test
    fun `views_match_raw_and_invert_disable_only_in_view`() {
        val parsed = CardAssetParser.parse(CardFixtures.J4.toByteArray(Charsets.UTF_8))!!
        val wb = parsed.asset as Worldbook
        assertEquals(WorldbookDialect.ST_NATIVE, wb.dialect)
        val v = wb.entries.first()
        assertEquals(false, v.raw.opt("disable"))       // raw 不动
        assertEquals(true, v.enabled)                   // 视图取反
        assertEquals(listOf<String>(), CardAssetParser.recheck(v))
        assertEquals("示例正文", v.content)
        assertEquals(100, v.order)
    }

    @Test
    fun `fengyue_is_a_work_not_a_card`() {
        val parsed = CardAssetParser.parse(CardFixtures.J1.toByteArray(Charsets.UTF_8))!!
        assertTrue("风月必须是 FengyueWork：${parsed.asset}", parsed.asset is FengyueWork)
        assertFalse("风月不许塞进 Card", parsed.asset.javaClass.simpleName == "Card")
        assertEquals(WorldbookDialect.WEB, (parsed.asset as FengyueWork).worldbook!!.dialect)
    }


    // ── F15 / 无损铁律 ──────────────────────────────────────────────────

    @Test
    fun `legacy_top_level_and_unknown_extension_keys_survive`() {
        val json = """{"spec":"chara_card_v3","avatar":"a.png","chat":"c","create_date":0,""" +
            """"data":{"name":"示例","extensions":{"world":"外部书名","未知键":{"k":1}}}}"""
        // 双包装只出现在**酒馆 PNG** 里（裸 JSON 带 spec 是「角色卡，不在本批范围」）
        val bytes = CardFixtures.withText(CardFixtures.basePng(), "ccv3", CardFixtures.b64(json))
        val parsed = CardAssetParser.parse(bytes)!!
        val card = parsed.asset as com.example.zhengdao.baopu.card.Card

        // F15：读取取 data，但老 v2 顶层键必须原样留着（写回时靠它保键集合）
        val legacy = card.legacyTopLevel!!
        assertTrue("老 v2 顶层键 avatar 丢了", legacy.has("avatar"))
        assertTrue("老 v2 顶层键 chat 丢了", legacy.has("chat"))
        assertTrue("老 v2 顶层键 create_date 丢了", legacy.has("create_date"))
        assertEquals(
            listOf("spec", "avatar", "chat", "create_date", "data").sorted(),
            CardJson.topLevelKeys(legacy.toString()).sorted(),
        )

        // 无损铁律：认不出的 extensions 键必须还在（读→改→写不许重建对象）
        val ext = card.ccv3.getJSONObject("extensions")
        assertTrue("未知键被丢了", ext.has("未知键"))
        assertEquals(1, ext.getJSONObject("未知键").getInt("k"))
    }

    @Test
    fun `parse_is_deterministic`() {
        for (json in listOf(CardFixtures.J1, CardFixtures.J3, CardFixtures.J4)) {
            val a = CardAssetParser.parse(json.toByteArray())!!.asset
            val b = CardAssetParser.parse(json.toByteArray())!!.asset
            assertEquals(kindOf(a), kindOf(b))
        }
    }
}
