// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百八十五批（2026-11-11）：六条原判据 ＋ 形状修正追加判据 ⑧⑨⑫⑬⑭⑮。
// 判据号按用户两轮派工原样使用；⑩⑪ 未在派工中出现，不擅自补号。
package com.example.zhengdao.baopu.card

import com.example.zhengdao.CodeText
import com.example.zhengdao.SrcRoots
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TavernCompatTest {

    // ── 判据① 往返保真（579 挂账：加映射后仍须全绿） ─────────────────────

    @Test
    fun roundTrip_coreFields_are_identical() {
        val first = TavernCodec.encode(TavernFixtures.bundle())
        val decoded1 = TavernCodec.decode(first.cardJson, first.worldJson)
        val second = TavernCodec.encode(decoded1.bundle)
        val decoded2 = TavernCodec.decode(second.cardJson, second.worldJson)
        assertEquals("核心字段逐行对拍", decoded1.bundle.coreSnapshot(), decoded2.bundle.coreSnapshot())
        assertEquals("再导出的卡文本逐字节相同", first.cardJson, second.cardJson)
        assertEquals("再导出的独立世界书文本逐字节相同", first.worldJson, second.worldJson)
        assertEquals("导出方向不应丢字段", 0, first.losses.losses.size)
        assertEquals("导入方向不应丢字段", 0, decoded1.losses.losses.size)
    }

    /** 用户追加：卡内 character_book（数组+keys）单独往返一条。 */
    @Test
    fun roundTrip_cardBook_only_is_identical() {
        val first = TavernCodec.encode(TavernFixtures.bundle())
        val decoded1 = TavernCodec.decode(first.cardJson, "") // 没有独立文件 ⇒ 从卡内 character_book 读
        val second = TavernCodec.encode(decoded1.bundle)
        val decoded2 = TavernCodec.decode(second.cardJson, "")
        assertEquals("卡内往返：条目数", 3, decoded1.bundle.world.entries.size)
        assertEquals("卡内往返：核心字段对拍", decoded1.bundle.coreSnapshot(), decoded2.bundle.coreSnapshot())
        assertEquals("卡内往返：卡文本逐字节相同", first.cardJson, second.cardJson)
    }

    @Test
    fun roundTrip_snapshot_negative() {
        val a = TavernFixtures.bundle()
        val b = a.copy(card = a.card.copy(scenario = "另一个场景"))
        assertNotEquals("核心字段快照必须能分辨差异", a.coreSnapshot(), b.coreSnapshot())
    }

    /** v2/v3 都吃：v2 是平铺无 data 包裹。 */
    @Test
    fun import_v2_and_v3_both_accepted() {
        val v3 = TavernCodec.encode(TavernFixtures.bundle())
        val d3 = TavernCodec.decode(v3.cardJson, "")
        val d2 = TavernCodec.decode(TavernFixtures.v2Card(), "")
        assertEquals("示例角色", d3.bundle.card.name)
        assertEquals("v2角色", d2.bundle.card.name)
        assertEquals("v2性格", d2.bundle.card.personality)
    }

    /** 外部独立世界书（字典 + key）也进得来，表外字段报会丢。 */
    @Test
    fun import_standalone_world_dict_shape() {
        val card = TavernCodec.encode(TavernFixtures.bundle())
        val decoded = TavernCodec.decode(card.cardJson, TavernFixtures.stStandaloneWorld())
        assertEquals(2, decoded.bundle.world.entries.size)
        assertEquals("原生键0", decoded.bundle.world.entries[0].keys.single().raw)
        assertEquals("原生正文1", decoded.bundle.world.entries[1].content)
        assertTrue(
            "表外字段 comment 必须报会丢",
            decoded.losses.losses.any { it.field == "comment" },
        )
    }

    /** 外部卡内形状（数组 + keys）也进得来。 */
    @Test
    fun import_cardBook_array_shape() {
        val card = TavernCodec.encode(TavernFixtures.bundle())
        val decoded = TavernCodec.decode(card.cardJson, TavernFixtures.cardBookText())
        assertEquals(1, decoded.bundle.world.entries.size)
        assertEquals("卡内键", decoded.bundle.world.entries[0].keys.single().raw)
        assertTrue(TavernFields.validateCardBook(TavernFixtures.cardBookText()).isEmpty())
    }

    // ── 判据② schema 校验 ───────────────────────────────────────────────

    @Test
    fun exported_json_passes_tavern_schema() {
        val encoded = TavernCodec.encode(TavernFixtures.bundle())
        assertTrue("卡 schema", TavernFields.validateCard(encoded.cardJson).isEmpty())
        val book = JSONObject(encoded.cardJson).getJSONObject("data")
            .getJSONObject(TavernFields.CARD_BOOK_KEY).toString()
        assertTrue("卡内书 schema（数组+keys）", TavernFields.validateCardBook(book).isEmpty())
        assertTrue("独立书 schema（字典+key）", TavernFields.validateStandaloneWorld(encoded.worldJson).isEmpty())
    }

    @Test
    fun schema_negative_cases_all_detected() {
        val good = TavernCodec.encode(TavernFixtures.bundle())
        val badCards = listOf(
            "删 spec" to JSONObject(good.cardJson).apply { remove("spec") },
            "tags 改成字符串" to JSONObject(good.cardJson).apply {
                getJSONObject("data").put("tags", "不是数组")
            },
            "description 非空" to JSONObject(good.cardJson).apply {
                getJSONObject("data").put("description", "人设偷跑回来了")
            },
            "缺 14 标准字段之一" to JSONObject(good.cardJson).apply {
                getJSONObject("data").remove("system_prompt")
            },
            "data 不是对象" to JSONObject(good.cardJson).apply { put("data", "x") },
        )
        for ((label, o) in badCards) {
            assertTrue("负例未红：$label", TavernFields.validateCard(o.toString()).isNotEmpty())
        }
        val goodWorld = JSONObject(good.worldJson)
        val badWorld = JSONObject(good.worldJson).apply {
            getJSONObject("entries").getJSONObject("0").put("constant", "不是布尔")
        }
        assertTrue("独立书类型负例未红", TavernFields.validateStandaloneWorld(badWorld.toString()).isNotEmpty())
        val noEntries = JSONObject(good.worldJson).apply { remove("entries") }
        assertTrue("缺 entries 负例未红", TavernFields.validateStandaloneWorld(noEntries.toString()).isNotEmpty())
        assertTrue("外部独立书能过", TavernFields.validateStandaloneWorld(TavernFixtures.stStandaloneWorld()).isEmpty())
        assertTrue("正例对照", TavernFields.validateStandaloneWorld(goodWorld.toString()).isEmpty())
    }

    // ── 判据③ 会丢负例 ─────────────────────────────────────────────────

    @Test
    fun customField_is_reported_as_willBeLost_not_silently_dropped() {
        val encoded = TavernCodec.encode(TavernFixtures.bundleWithCustomField())
        assertEquals(1, encoded.losses.losses.size)
        assertEquals("我们的自定义字段", encoded.losses.losses.single().field)
        assertTrue(encoded.losses.message().contains("会丢"))
        assertTrue(encoded.losses.markdown().contains("我们的自定义字段"))
        assertFalse(encoded.cardJson.contains("这条会丢"))
    }

    @Test
    fun noCustomField_reports_no_loss() {
        val encoded = TavernCodec.encode(TavernFixtures.bundle())
        assertTrue(encoded.losses.isEmpty)
    }

    @Test
    fun customField_on_entry_reported_with_index() {
        val b = TavernFixtures.bundle()
        val e = b.world.entries[0].copy(customFields = mapOf("境界" to "筑基"))
        val encoded = TavernCodec.encode(b.copy(world = b.world.copy(entries = listOf(e))))
        assertTrue(encoded.losses.markdown().contains("境界"))
        assertTrue(encoded.losses.losses.single().side.contains("世界书[0]"))
    }

    // ── 判据④ keys 三种写法（两种形状各验一遍） ───────────────────────────

    @Test
    fun keys_three_forms_in_both_shapes() {
        val b = TavernFixtures.bundle()
        val forms = b.world.entries.map { TavernKeys.formsOf(it.keys) }
        assertTrue("纯文本", forms[0].contains(TavernKeyForm.PLAIN))
        assertTrue("多值", forms[1].contains(TavernKeyForm.MULTI))
        assertTrue("正则键", forms[2].contains(TavernKeyForm.REGEX))

        val encoded = TavernCodec.encode(b)
        val cardBook = JSONObject(encoded.cardJson).getJSONObject("data")
            .getJSONObject(TavernFields.CARD_BOOK_KEY).getJSONArray("entries")
        assertEquals("卡内：字段名 keys", "客栈", cardBook.getJSONObject(0).getJSONArray("keys").getString(0))
        assertEquals("卡内：多值 3 项", 3, cardBook.getJSONObject(1).getJSONArray("keys").length())
        assertEquals("卡内：正则", "/(客栈|驿站)/i", cardBook.getJSONObject(2).getJSONArray("keys").getString(0))

        val standalone = JSONObject(encoded.worldJson).getJSONObject("entries")
        assertEquals("独立书：字段名 key", "客栈", standalone.getJSONObject("0").getJSONArray("key").getString(0))
        assertEquals("独立书：多值 3 项", 3, standalone.getJSONObject("1").getJSONArray("key").length())
        assertEquals("独立书：正则", "/(客栈|驿站)/i", standalone.getJSONObject("2").getJSONArray("key").getString(0))

        val back = TavernCodec.decode(encoded.cardJson, encoded.worldJson).bundle
        assertEquals(b.world.entries.map { it.keys }, back.world.entries.map { it.keys })
        assertEquals(TavernKeyKind.REGEX, back.world.entries[2].keys[0].kind)
    }

    @Test
    fun keys_single_string_source_is_accepted_as_plain() {
        val encoded = TavernCodec.encode(TavernFixtures.bundle())
        val doc = JSONObject(encoded.worldJson)
        doc.getJSONObject("entries").getJSONObject("0").put("key", "纯文本单串")
        val back = TavernCodec.decode(encoded.cardJson, doc.toString())
        assertEquals("纯文本单串", back.bundle.world.entries[0].keys.single().raw)
    }

    // ── 判据⑤ 500 条 ───────────────────────────────────────────────────

    @Test
    fun fiveHundredEntries_export_under_two_seconds_and_bounded_memory() {
        val world = TavernFixtures.bigWorld(500)
        TavernCodec.worldJson(world) // 预热
        val t0 = System.nanoTime()
        val json = TavernCodec.worldJson(world)
        val elapsedMs = (System.nanoTime() - t0) / 1_000_000
        assertTrue("500 条导出耗时 $elapsedMs ms，超 2 秒", elapsedMs <= 2_000)
        assertEquals("条目数数对 500", 500, JSONObject(json).getJSONObject("entries").length())
        assertTrue("产物体积有界：${json.length} 字符", json.length < 2_000_000)
        assertTrue("大世界书过 schema", TavernFields.validateStandaloneWorld(json).isEmpty())
    }

    // ── 判据⑧⑬ 导出默认（独立世界书）是 dict + key ────────────────────────

    @Test
    fun default_world_export_is_dict_with_key() {
        val json = TavernCodec.worldJson(TavernFixtures.bundle().world)
        val entries = JSONObject(json).opt("entries")
        assertTrue("entries 必须是字典（JSONObject），不是数组", entries is JSONObject)
        val dict = entries as JSONObject
        assertEquals(3, dict.length())
        for (k in dict.keys()) {
            assertNotNull("uid 键必须是整数字符串：$k", k.toIntOrNull())
            val e = dict.getJSONObject(k)
            assertTrue("条目必须有 key 且是数组", e.opt("key") is JSONArray)
            assertFalse("条目不许出现卡内专用字段名 keys", e.has("keys"))
        }
        assertTrue("dict+key 形状过 STANDALONE 校验", TavernFields.validateStandaloneWorld(json).isEmpty())
        assertFalse("dict+key 形状绝不该被当 CARD_BOOK 放过", TavernFields.validateCardBook(json).isEmpty())
    }

    // ── 判据⑫ 卡内 character_book 是数组 + keys ──────────────────────────

    @Test
    fun cardBook_entries_is_array_with_keys() {
        val card = JSONObject(TavernCodec.encode(TavernFixtures.bundle()).cardJson)
        val data = card.getJSONObject("data")
        assertEquals("chara_card_v3", card.getString("spec"))
        assertTrue("data.character_book 必须存在", data.has("character_book"))
        val book = data.getJSONObject("character_book")
        val entries = book.opt("entries")
        assertTrue("卡内 entries 必须是数组（JSONArray）", entries is JSONArray)
        val arr = entries as JSONArray
        assertEquals(3, arr.length())
        for (i in 0 until arr.length()) {
            val e = arr.getJSONObject(i)
            assertTrue("卡内条目必须有 keys 且是数组", e.opt("keys") is JSONArray)
            assertFalse("卡内条目不许出现独立文件专用字段名 key", e.has("key"))
        }
        assertTrue("数组+keys 形状过 CARD_BOOK 校验", TavernFields.validateCardBook(book.toString()).isEmpty())
        assertFalse("数组+keys 形状绝不该被当 STANDALONE 放过", TavernFields.validateStandaloneWorld(book.toString()).isEmpty())
        val viaCard = TavernCodec.decode(card.toString(), "")
        assertEquals("卡内形状回读 3 条", 3, viaCard.bundle.world.entries.size)
    }

    // ── 判据⑨ 项目既有 ST 校验器能直接吃这份导出（真跑） ────────────────────

    @Test
    fun our_ST_validator_accepts_export_directly() {
        val json = TavernCodec.worldJson(TavernFixtures.bundle().world)
        val bytes = json.toByteArray(Charsets.UTF_8)
        val verdict = CardFormat.recognize(bytes)
        assertEquals("必须被认成 ST_WORLD", FormatKind.ST_WORLD, verdict.format)
        val parsed = CardAssetParser.parse(bytes)
        assertNotNull("CardAssetParser 必须能解析", parsed)
        val report = CardCheck.check(parsed!!)
        assertTrue(
            "ST 体检零 ERROR（实际：${report.errors.map { it.code + ":" + it.message }}）",
            report.errors.isEmpty(),
        )
        assertEquals("ST_WORLD", report.stats.byFormat)
    }

    /** ⑨ 的负例：数组+keys 形状**不该**被当成独立世界书白放进 ST 链路。 */
    @Test
    fun our_ST_validator_probe_negative() {
        val cardBook = TavernCodec.cardBookJson(TavernFixtures.bundle().world)
        val verdict = CardFormat.recognize(cardBook.toByteArray(Charsets.UTF_8))
        assertNotEquals("数组+keys 形状不该认成 ST_WORLD", FormatKind.ST_WORLD, verdict.format)
    }

    // ── 判据⑭ position 双轨 ─────────────────────────────────────────────

    @Test
    fun position_dualTrack_is_correct() {
        val encoded = TavernCodec.encode(TavernFixtures.bundle())
        val entries = JSONObject(encoded.worldJson).getJSONObject("entries")
        val first = entries.getJSONObject("0")
        assertTrue("顶层 position 只允许 before_char／after_char", first.getString("position") in TavernFields.TOP_POSITIONS)
        assertEquals("真值在 extensions.position", 0, first.getJSONObject("extensions").getInt("position"))
        assertFalse("顶层 position 不许是数字", first.get("position") is Int)

        val atDepth = entries.getJSONObject("2")
        assertEquals("atDepth 的 extensions.position = 4", 4, atDepth.getJSONObject("extensions").getInt("position"))
        assertEquals("atDepth 配 extensions.depth", 2, atDepth.getJSONObject("extensions").getInt("depth"))
        assertEquals("atDepth 配 extensions.role", 0, atDepth.getJSONObject("extensions").getInt("role"))
        assertTrue("双轨过 schema", TavernFields.validateStandaloneWorld(encoded.worldJson).isEmpty())

        val back = TavernCodec.decode(encoded.cardJson, encoded.worldJson).bundle
        assertEquals("双轨往返：position", "before_char", back.world.entries[0].position)
        assertEquals("双轨往返：stPosition", 0, back.world.entries[0].stPosition)
        assertEquals("双轨往返：stDepth", 2, back.world.entries[2].stDepth)
        assertEquals("双轨往返：stRole", 0, back.world.entries[2].stRole)
    }

    @Test
    fun position_dualTrack_negative_cases() {
        val good = TavernCodec.worldJson(TavernFixtures.bundle().world)
        val badTop = JSONObject(good).apply {
            getJSONObject("entries").getJSONObject("0").put("position", 4)
        }
        assertTrue("顶层写数字 4 必须红", TavernFields.validateStandaloneWorld(badTop.toString()).isNotEmpty())
        val badExt = JSONObject(good).apply {
            getJSONObject("entries").getJSONObject("0").getJSONObject("extensions").put("position", 99)
        }
        assertTrue("extensions.position=99 越界必须红", TavernFields.validateStandaloneWorld(badExt.toString()).isNotEmpty())
        val badDepth = JSONObject(good).apply {
            getJSONObject("entries").getJSONObject("2").getJSONObject("extensions").remove("role")
        }
        assertTrue("atDepth 缺 role 必须红", TavernFields.validateStandaloneWorld(badDepth.toString()).isNotEmpty())
    }

    // ── 判据⑮ 正则 findRegex 带 flags ────────────────────────────────────

    @Test
    fun regex_findRegex_has_flags_and_display_preset() {
        val encoded = TavernCodec.encode(TavernFixtures.bundle())
        val scripts = JSONObject(encoded.cardJson).getJSONObject("data")
            .getJSONObject("extensions").getJSONArray("regex_scripts")
        assertEquals(1, scripts.length())
        val s = scripts.getJSONObject(0)
        assertTrue("findRegex 必须是带 flags 的完整形式", TavernRegex.isComplete(s.getString("findRegex")))
        assertEquals("/【(.+?)】/gi", s.getString("findRegex"))
        assertEquals("显示类 markdownOnly", true, s.getBoolean("markdownOnly"))
        assertEquals("显示类 promptOnly", false, s.getBoolean("promptOnly"))
        assertEquals("显示类 placement=[2]", 2, s.getJSONArray("placement").getInt(0))
        val back = TavernCodec.decode(encoded.cardJson, encoded.worldJson).bundle
        assertEquals("正则往返", s.getString("findRegex"), back.card.regexScripts.single().findRegex)
    }

    @Test
    fun bare_regex_is_reported_as_willBeLost_and_not_exported() {
        val encoded = TavernCodec.encode(TavernFixtures.bundleWithBareRegex())
        val scripts = JSONObject(encoded.cardJson).getJSONObject("data")
            .getJSONObject("extensions").getJSONArray("regex_scripts")
        assertEquals("裸 pattern 不许混进导出", 0, scripts.length())
        assertTrue("裸 pattern 必须报会丢", encoded.losses.losses.any { it.field == "findRegex" })
        assertTrue(encoded.losses.message().contains("会丢"))
        assertFalse("裸 pattern 不该出现在 JSON 里", encoded.cardJson.contains("裸pattern"))
        assertFalse(TavernRegex.isComplete("裸pattern"))
        assertTrue(TavernRegex.isComplete("/甲/gi"))
        assertFalse(TavernRegex.isComplete("/甲/"))
    }

    // ── 14 个标准字段（V3 校验宽松，导出自己补全） ─────────────────────────

    @Test
    fun v3_card_has_all_14_standard_fields() {
        val cardText = TavernCodec.encode(TavernFixtures.bundle()).cardJson
        val data = JSONObject(cardText).getJSONObject("data")
        assertEquals(14, TavernFields.V3_STANDARD_FIELDS.size)
        for (f in TavernFields.V3_STANDARD_FIELDS) {
            assertTrue("V3 标准字段 $f 缺失（导出必须自己补全）", data.has(f))
        }
        assertTrue(TavernFields.validateCard(cardText).isEmpty())
    }

    // ── 判据⑥ 纯 Kotlin / 铁律 ──────────────────────────────────────────

    private val PRODUCTION_FILES = listOf(
        "app/src/main/java/com/example/zhengdao/baopu/card/TavernDto.kt",
        "app/src/main/java/com/example/zhengdao/baopu/card/TavernFields.kt",
        "app/src/main/java/com/example/zhengdao/baopu/card/TavernCodec.kt",
        "app/src/main/java/com/example/zhengdao/baopu/card/TavernExport.kt",
    )

    @Test
    fun pureKotlin_no_node_exec_mcp_or_network_calls() {
        val banned = listOf(
            Regex("""\bnode\b""", RegexOption.IGNORE_CASE),
            Regex("""\bProcessBuilder\b"""),
            Regex("""Runtime\s*\.\s*getRuntime"""),
            Regex("""\bexec\s*\("""),
            Regex("""\bMCP\b"""),
            Regex("""\bHttpURLConnection\b|\bOkHttp\b|\bSocket\b|\bURL\s*\("""),
        )
        for (path in PRODUCTION_FILES) {
            val code = CodeText.codeOnly(SrcRoots.find(path).readText())
            for (r in banned) assertFalse("$path 出现禁用调用 ${r.pattern}", r.containsMatchIn(code))
        }
    }

    @Test
    fun pureKotlin_probe_negative_cases() {
        val banned = Regex("""\bProcessBuilder\s*\(|\bRuntime\s*\.\s*getRuntime\s*\(""")
        assertTrue(banned.containsMatchIn("fun x() { ProcessBuilder(\"node\") }"))
        assertFalse(banned.containsMatchIn("val text = \"只提一句不算调用\""))
    }

    @Test
    fun finalJson_is_program_built_no_handwritten_template() {
        for (path in PRODUCTION_FILES) {
            val src = SrcRoots.find(path).readText()
            assertFalse("$path 有手写三引号 JSON 模板", src.contains("\"\"\"{"))
            assertFalse("$path 有手写 spec 字面量", src.contains("\"spec\":"))
        }
        val codec = SrcRoots.find(PRODUCTION_FILES[2]).readText()
        assertTrue(codec.contains(".put("))
        assertTrue(codec.contains(".toString(2)"))
    }

    @Test
    fun ironLaw_cardDescription_always_empty() {
        val b = TavernFixtures.bundle()
        val encoded = TavernCodec.encode(b.copy(card = b.card.copy(description = "不该被写进去的人设")))
        assertEquals("", JSONObject(encoded.cardJson).getJSONObject("data").getString("description"))
        assertFalse(encoded.cardJson.contains("不该被写进去的人设"))
        assertTrue(TavernFields.validateCard(encoded.cardJson).isEmpty())
    }

    @Test
    fun fieldTable_matches_valueTables_exactly() {
        val encoded = TavernCodec.encode(TavernFixtures.bundle())
        val data = JSONObject(encoded.cardJson).getJSONObject("data")
        for (r in TavernFields.CARD_ROWS) {
            assertTrue("卡字段 ${r.tavern} 漏导出", data.has(r.tavern))
            assertTrue("卡字段 ${r.tavern} 类型不对", r.type.accepts(data.opt(r.tavern)))
        }
        val e = JSONObject(encoded.worldJson).getJSONObject("entries").getJSONObject("0")
        for (r in TavernFields.ENTRY_ROWS) {
            if (!r.required) continue
            val name = TavernFields.fieldNameOf(TavernShape.STANDALONE, r)
            assertTrue("条目字段 $name 漏导出", e.has(name))
            assertTrue("条目字段 $name 类型不对", r.type.accepts(e.opt(name)))
        }
    }
}
