// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百八十五批（2026-11-11）：六条判据主测试。
// 判据①往返保真 ②schema 校验 ③会丢负例 ④keys 三种写法 ⑤500 条 ⑥纯 Kotlin 源码断言。
package com.example.zhengdao.baopu.card

import com.example.zhengdao.CodeText
import com.example.zhengdao.SrcRoots
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TavernCompatTest {

    // ── 判据① 往返保真：导入 → 导出 → 再导入，核心字段一模一样（对拍） ──────

    @Test
    fun roundTrip_coreFields_are_identical() {
        val origin = TavernFixtures.bundle()
        val first = TavernCodec.encode(origin)
        val decoded1 = TavernCodec.decode(first.cardJson, first.worldJson)
        val second = TavernCodec.encode(decoded1.bundle)
        val decoded2 = TavernCodec.decode(second.cardJson, second.worldJson)

        assertEquals("核心字段逐行对拍", decoded1.bundle.coreSnapshot(), decoded2.bundle.coreSnapshot())
        assertEquals("再导出的卡文本应逐字节相同", first.cardJson, second.cardJson)
        assertEquals("再导出的世界书文本应逐字节相同", first.worldJson, second.worldJson)
        assertEquals("导出方向不应丢字段", 0, first.losses.losses.size)
        assertEquals("导入方向不应丢字段", 0, decoded1.losses.losses.size)
    }

    /** 对拍的负例：改一个字段后快照必须变（防止「比了个恒真的空串」）。 */
    @Test
    fun roundTrip_snapshot_negative() {
        val a = TavernFixtures.bundle()
        val b = a.copy(card = a.card.copy(scenario = "另一个场景"))
        assertNotEquals("核心字段快照必须能分辨差异", a.coreSnapshot(), b.coreSnapshot())
    }

    /** 外部酒馆原生卡（entries 字典 + key 单数）也要能进得来。 */
    @Test
    fun import_silkyTavern_native_dict_shape() {
        val card = TavernCodec.encode(TavernFixtures.bundle())
        val native = TavernFixtures.silkyNativeWorld()
        val decoded = TavernCodec.decode(card.cardJson, native)
        assertEquals(2, decoded.bundle.world.entries.size)
        assertEquals("原生键0", decoded.bundle.world.entries[0].keys.single().raw)
        assertEquals("原生正文1", decoded.bundle.world.entries[1].content)
        assertTrue(
            "表外字段 comment/vectorized 必须报「会丢」，不许静默",
            decoded.losses.losses.any { it.field == "comment" } &&
                decoded.losses.losses.any { it.field == "vectorized" },
        )
    }

    // ── 判据② 按酒馆 schema 校验（字段类型/必填） ─────────────────────────

    @Test
    fun exported_json_passes_tavern_schema() {
        val encoded = TavernCodec.encode(TavernFixtures.bundle())
        val cardViolations = TavernFields.validateCard(encoded.cardJson)
        val worldViolations = TavernFields.validateWorld(encoded.worldJson)
        assertTrue("卡 schema 违规：$cardViolations", cardViolations.isEmpty())
        assertTrue("世界书 schema 违规：$worldViolations", worldViolations.isEmpty())
    }

    /** 负例×6：每种坏法都必须被 schema 抓到（不许「A 干净就 true」的恒真闸）。 */
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
            "缺必填 name" to JSONObject(good.cardJson).apply {
                getJSONObject("data").remove("name")
            },
            "data 不是对象" to JSONObject(good.cardJson).apply { put("data", "x") },
        )
        for ((label, o) in badCards) {
            val v = TavernFields.validateCard(o.toString())
            assertTrue("负例未红：$label", v.isNotEmpty())
        }
        val badWorld = JSONObject(good.worldJson).apply {
            getJSONArray("entries").getJSONObject(0).put("constant", "不是布尔")
        }
        assertTrue("世界书类型负例未红", TavernFields.validateWorld(badWorld.toString()).isNotEmpty())
        val noEntries = JSONObject(good.worldJson).apply { remove("entries") }
        assertTrue("缺 entries 负例未红", TavernFields.validateWorld(noEntries.toString()).isNotEmpty())
    }

    /** 字典形状的酒馆原生卡也能按同一张表校验（key → keys 别名）。 */
    @Test
    fun schema_accepts_native_dict_shape() {
        val violations = TavernFields.validateWorld(TavernFixtures.silkyNativeWorld())
        assertTrue("原生字典形状不该被自家校验器判红：$violations", violations.isEmpty())
    }

    // ── 判据③ 不支持字段必须报「会丢」（负例，不许静默） ───────────────────

    @Test
    fun customField_is_reported_as_willBeLost_not_silently_dropped() {
        val encoded = TavernCodec.encode(TavernFixtures.bundleWithCustomField())
        assertEquals(1, encoded.losses.losses.size)
        assertEquals("我们的自定义字段", encoded.losses.losses.single().field)
        assertTrue("话术必须出现「会丢」", encoded.losses.message().contains("会丢"))
        assertTrue("清单必须点名该字段", encoded.losses.markdown().contains("我们的自定义字段"))
        assertFalse(
            "自定义字段不许偷偷出现在导出 JSON 里",
            encoded.cardJson.contains("这条会丢") || encoded.cardJson.contains("我们的自定义字段"),
        )
    }

    /** 「没有自定义字段 ⇒ 不报」的对照（负例的对照，防止永远报一条）。 */
    @Test
    fun noCustomField_reports_no_loss() {
        val encoded = TavernCodec.encode(TavernFixtures.bundle())
        assertTrue(encoded.losses.isEmpty)
        assertTrue(encoded.losses.message().contains("没有不支持的字段"))
    }

    @Test
    fun customField_on_entry_reported_with_index() {
        val b = TavernFixtures.bundle()
        val e = b.world.entries[0].copy(customFields = mapOf("境界" to "筑基"))
        val encoded = TavernCodec.encode(b.copy(world = b.world.copy(entries = listOf(e))))
        assertTrue(encoded.losses.markdown().contains("境界"))
        assertTrue(encoded.losses.losses.single().side.contains("世界书[0]"))
    }

    // ── 判据④ keys 三种写法 ─────────────────────────────────────────────

    @Test
    fun keys_three_forms_plain_multi_regex_all_export_and_roundTrip() {
        val b = TavernFixtures.bundle()
        val forms = b.world.entries.map { TavernKeys.formsOf(it.keys) }
        assertTrue("纯文本", forms[0].contains(TavernKeyForm.PLAIN))
        assertTrue("多值", forms[1].contains(TavernKeyForm.MULTI))
        assertTrue("正则键", forms[2].contains(TavernKeyForm.REGEX))

        val encoded = TavernCodec.encode(b)
        val doc = JSONObject(encoded.worldJson)
        val entries = doc.getJSONArray("entries")
        assertEquals(3, entries.length())
        assertEquals("客栈", entries.getJSONObject(0).getJSONArray("keys").getString(0))
        assertEquals(3, entries.getJSONObject(1).getJSONArray("keys").length())
        assertEquals("/(客栈|驿站)/i", entries.getJSONObject(2).getJSONArray("keys").getString(0))

        // 再导入：三种写法原样还原（含正则字符串里的转义与 flags）
        val back = TavernCodec.decode(encoded.cardJson, encoded.worldJson).bundle
        assertEquals(b.world.entries.map { it.keys }, back.world.entries.map { it.keys })
        assertEquals(TavernKeyKind.REGEX, back.world.entries[2].keys[0].kind)
    }

    @Test
    fun keys_single_string_source_is_accepted_as_plain() {
        val doc = JSONObject(TavernCodec.encode(TavernFixtures.bundle()).worldJson)
        doc.getJSONArray("entries").getJSONObject(0).put("keys", "纯文本单串")
        val back = TavernCodec.decode(TavernCodec.encode(TavernFixtures.bundle()).cardJson, doc.toString())
        assertEquals("纯文本单串", back.bundle.world.entries[0].keys.single().raw)
    }

    // ── 判据⑤ 500 条 ≤ 2 秒、内存不爆 ───────────────────────────────────

    @Test
    fun fiveHundredEntries_export_under_two_seconds_and_bounded_memory() {
        val world = TavernFixtures.bigWorld(500)
        // 预热一次，避免把 JIT/类加载算进首次耗时
        TavernCodec.worldJson(world)
        val t0 = System.nanoTime()
        val json = TavernCodec.worldJson(world)
        val elapsedMs = (System.nanoTime() - t0) / 1_000_000

        assertTrue("500 条导出耗时 $elapsedMs ms，超 2 秒", elapsedMs <= 2_000)
        assertEquals("条目数必须数对 500 个", 500, JSONObject(json).getJSONArray("entries").length())
        assertTrue("产物体积应有界（不爆内存）：${json.length} 字符", json.length < 2_000_000)
        assertTrue("大世界书也应过 schema", TavernFields.validateWorld(json).isEmpty())
    }

    // ── 判据⑥ 纯 Kotlin：源码断言无 node/exec/MCP/网络；最终 JSON 不手写 ────

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
            Regex("""\bmcp\b"""),
        )
        for (path in PRODUCTION_FILES) {
            val code = CodeText.codeOnly(SrcRoots.find(path).readText())
            for (r in banned) {
                assertFalse("$path 出现禁用调用 ${r.pattern}", r.containsMatchIn(code))
            }
        }
    }

    /** 自检负例：把禁用串喂进探针，探针必须报（否则上面的断言是恒真）。 */
    @Test
    fun pureKotlin_probe_negative_cases() {
        val banned = Regex("""\bProcessBuilder\s*\(|\bRuntime\s*\.\s*getRuntime\s*\(""")
        assertTrue(banned.containsMatchIn("fun x() { ProcessBuilder(\"node\") }"))
        assertTrue(banned.containsMatchIn("Runtime.getRuntime().exec(\"node\")"))
        assertFalse(banned.containsMatchIn("val text = \"只提一句 node 不该算调用\""))
    }

    /** 铁律①：最终 JSON 由程序生成，源码里不许出现手写 JSON 模板字面量。 */
    @Test
    fun finalJson_is_program_built_no_handwritten_template() {
        for (path in PRODUCTION_FILES) {
            val src = SrcRoots.find(path).readText()
            assertFalse("$path 有手写三引号 JSON 模板", src.contains("\"\"\"{"))
            assertFalse("$path 有手写 spec 字面量", src.contains("\"spec\":") || src.contains("\"spec\" :"))
        }
        // 导出里确实有 put() 在拼 JSON（不是把整串写死）
        val codec = SrcRoots.find(PRODUCTION_FILES[2]).readText()
        assertTrue("必须由 JSONObject.put 逐字段生成", codec.contains(".put("))
        assertTrue("必须由 toString(2) 出最终文本", codec.contains(".toString(2)"))
    }

    /** 铁律②：description 恒空，且与 DTO 传什么都无关。 */
    @Test
    fun ironLaw_cardDescription_always_empty() {
        val b = TavernFixtures.bundle()
        val withDescription = b.copy(card = b.card.copy(description = "不该被写进去的人设"))
        val encoded = TavernCodec.encode(withDescription)
        assertEquals("", JSONObject(encoded.cardJson).getJSONObject("data").getString("description"))
        assertFalse(encoded.cardJson.contains("不该被写进去的人设"))
        assertTrue(TavernFields.validateCard(encoded.cardJson).isEmpty())
    }

    /** 表驱动唯一真相：字段表与值表必须逐格对齐（不许漏接线）。 */
    @Test
    fun fieldTable_matches_valueTables_exactly() {
        val b = TavernFixtures.bundle()
        val encoded = TavernCodec.encode(b)
        val data = JSONObject(encoded.cardJson).getJSONObject("data")
        for (r in TavernFields.CARD_ROWS) {
            assertTrue("卡字段 ${r.tavern} 漏导出", data.has(r.tavern))
            assertTrue("卡字段 ${r.tavern} 类型不对", r.type.accepts(data.opt(r.tavern)))
        }
        val e = JSONObject(encoded.worldJson).getJSONArray("entries").getJSONObject(0)
        for (r in TavernFields.ENTRY_ROWS) {
            if (r.required) {
                assertTrue("条目字段 ${r.tavern} 漏导出", e.has(r.tavern))
                assertTrue("条目字段 ${r.tavern} 类型不对", r.type.accepts(e.opt(r.tavern)))
            }
        }
    }
}
