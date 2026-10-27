// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百二十五批：人设／正则／预设三类「只认脸」的字段解析。
//
// 判据（派工单 §1／§2）：
// - 正例：三份不同血统的样例各解析一遍 ⇒ 字段正确；缺字段 ⇒ null／空表；坏条目 ⇒ unparsed 且原文在；
// - 负例（各红）：缺字段补默认值／解析失败抛异常／塞进 Card 主体／改卡文件字节／另开页面。
//
// 夹具全部自造、脱敏（不出现任何站点名），血统用四家的**字段形态**表达。
package com.example.zhengdao.baopu.card

import com.example.zhengdao.baopu.agent.CardFixtures
import com.example.zhengdao.baopu.agent.CardTools
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CardPersonaParseTest {

    // ── 夹具（自造；字段形态按四家血统各自的现场格式）─────────────────────

    /** Tavo 角色卡：人设段在 `data` 里，性格叫 personality，样例叫 mes_example。 */
    private val tavoCard = """
{"kind":"character","id":"demo-2","name":"示例角色","updatedAt":0,"data":{
 "name":"示例角色",
 "description":"一名在雪夜赶路的人。这里是一段会被折叠的长文描述，用来验证「人设长文折叠」这条展示口径，长度要超过八十个字才能触发折叠分支，所以再补一些字凑够长度；再补一句，确保它稳稳超过阈值，折叠分支一定要被走到。",
 "personality":"冷静、克制、话少",
 "scenario":"雪夜，山道旁的一间破庙",
 "mes_example":"<START>路人：借个火？<START>角色：……放这儿吧。",
 "extensions":{}}}
"""

    /** 酒馆 v2 卡（PNG 内嵌 chara）：字段同形，血统记 sillytavern。 */
    private val stV2Card = """
{"spec":"chara_card_v2","data":{
 "name":"旧卡角色",
 "description":"酒馆那侧的描述。",
 "personality":"冷淡",
 "scenario":"雨夜",
 "mes_example":"<START>甲：在吗"}}
"""

    /** CCv3 卡（PNG 内嵌 ccv3）：卡内另带一段正则脚本（酒馆那套字段：placement／disabled 反义）。 */
    private val ccv3Card = """
{"spec":"chara_card_v3","spec_version":"3.0","data":{
 "name":"新卡角色",
 "description":"CCv3 那侧的描述。",
 "personality":"温和",
 "scenario":"清晨",
 "mes_example":"<START>乙：早",
 "extensions":{"regex_scripts":[
   {"scriptName":"记忆块","findRegex":"/<memory>[\\s\\S]*?<\\/memory>/gi","replaceString":"","placement":["ai_output"],"disabled":true},
   {"scriptName":"替换甲","findRegex":"甲","replaceString":"乙","placement":["user_input"]}
 ]}}}
"""

    /** Tavo 正则文件：条目字段 findRegex／replaceString／placements／enabled。 */
    private val tavoRegex = """
{"kind":"regex","id":"demo-3","name":"示例正则","updatedAt":0,"entries":[
 {"identifier":"1","name":"示例","findRegex":"/甲/g","replaceString":"乙","placements":["ai_output"],"enabled":true},
 {"identifier":"2","name":"关掉的","findRegex":"丙","replaceString":"丁","placements":[],"enabled":false},
 "这不是对象"
]}
"""

    /** Tavo 预设文件：条目字段 name／content／enabled。 */
    private val tavoPreset = """
{"kind":"preset","id":"demo-4","name":"示例预设","updatedAt":0,"active":true,"basicPrompts":[],
 "entries":[
  {"identifier":"1","name":"主提示词","content":"你是一个示例。","role":"system","enabled":true},
  {"identifier":"2","name":"关掉的","content":"不用这段。","role":"system","enabled":false},
  {"identifier":"3","name":"没标开关","content":"也许要用。","role":"system"}
 ]}
"""

    /** 半段人设：只有名与场景，其余**一个都没有**（用来钉「缺字段给 null」）。 */
    private val halfCard = """
{"kind":"character","id":"demo-5","name":"半段","updatedAt":0,"data":{
 "name":"半段角色","scenario":"只有场景","extensions":{}}}
"""

    // ── 正例①：Tavo 角色卡 ──────────────────────────────────────────────

    @Test
    fun `Tavo 卡的人设段五个字段都解析出来`() {
        val p = parse(tavoCard)
        assertEquals(FormatKind.TAVO_CHAR, p.verdict.format)
        val persona = p.persona
        assertNotNull("人设段应当解析出来", persona)
        assertEquals("tavo", persona!!.vendor)
        assertEquals("示例角色", persona.name)
        assertTrue(persona.description!!.startsWith("一名在雪夜赶路的人。"))
        assertEquals(listOf("冷静", "克制", "话少"), persona.traits)
        assertEquals("雪夜，山道旁的一间破庙", persona.scenario)
        assertEquals(listOf("路人：借个火？", "角色：……放这儿吧。"), persona.examples)
    }

    // ── 正例②：酒馆 v2 卡（PNG） ────────────────────────────────────────

    @Test
    fun `酒馆卡的人设段按酒馆那一列解析`() {
        val p = pngParse(stV2Card, "chara")
        assertEquals(FormatKind.ST_CARD_PNG, p.verdict.format)
        val persona = p.persona
        assertNotNull("酒馆卡也应当有人设段", persona)
        assertEquals("sillytavern", persona!!.vendor)
        assertEquals("旧卡角色", persona.name)
        assertEquals("酒馆那侧的描述。", persona.description)
        assertEquals(listOf("冷淡"), persona.traits)
        assertEquals("雨夜", persona.scenario)
        assertEquals(listOf("甲：在吗"), persona.examples)
    }

    // ── 正例③：CCv3 卡（PNG）＋卡内嵌正则 ───────────────────────────────

    @Test
    fun `CCv3 卡的人设段与卡内嵌正则都解析出来_反义只在视图层`() {
        val p = pngParse(ccv3Card, "ccv3")
        assertEquals(FormatKind.ST_CARD_PNG, p.verdict.format)
        val persona = p.persona
        assertNotNull("CCv3 卡也应当有人设段", persona)
        assertEquals("ccv3", persona!!.vendor)
        assertEquals("新卡角色", persona.name)
        assertEquals(listOf("温和"), persona.traits)

        val regex = p.regex
        assertNotNull("卡内 extensions.regex_scripts 应当解析成正则段", regex)
        assertEquals(2, regex!!.entries.size)
        val first = regex.entries[0]
        assertEquals("/<memory>[\\s\\S]*?<\\/memory>/gi", first.find)
        assertEquals(listOf("g", "i"), first.flags)          // 标志从 /pattern/flags 尾部取
        assertEquals(listOf("ai_output"), first.scope)       // placement（酒馆单数）
        assertEquals(false, first.enabled)                   // disabled=true 才是关 ⇒ 视图层取反
        // ⚠️ raw 里的 disabled 一个字节不改
        assertEquals(true, first.raw.optBoolean("disabled"))

        val second = regex.entries[1]
        assertEquals(listOf("user_input"), second.scope)
        assertNull("源里没写 disabled ⇒ null，不许补 true", second.enabled)
        assertTrue("没有 /flags 形状 ⇒ 空表，不猜", second.flags.isEmpty())
    }

    // ── 正例④：Tavo 正则文件（含坏条目） ────────────────────────────────

    @Test
    fun `Tavo 正则文件逐条解析_坏条目标 unparsed 且保留原文`() {
        val p = parse(tavoRegex)
        assertEquals(FormatKind.TAVO_REGEX, p.verdict.format)
        val regex = p.regex
        assertNotNull("正则文件应当解析出正则段", regex)
        assertEquals(2, regex!!.entries.size)
        assertEquals("/甲/g", regex.entries[0].find)
        assertEquals("乙", regex.entries[0].replace)
        assertEquals(listOf("g"), regex.entries[0].flags)
        assertEquals(listOf("ai_output"), regex.entries[0].scope)
        assertEquals(true, regex.entries[0].enabled)
        assertEquals(false, regex.entries[1].enabled)

        assertEquals("坏条目不许被悄悄丢掉", 1, p.unparsed.size)
        val bad = p.unparsed[0]
        assertEquals(AssetKind.REGEX, bad.kind)
        assertEquals(2, bad.index)
        assertEquals("这不是对象", bad.rawText)     // 原文在
        assertNull(bad.raw)
    }

    // ── 正例⑤：Tavo 预设文件 ────────────────────────────────────────────

    @Test
    fun `Tavo 预设文件逐条解析_没标开关的给 null`() {
        val p = parse(tavoPreset)
        assertEquals(FormatKind.TAVO_PRESET, p.verdict.format)
        val preset = p.preset
        assertNotNull("预设文件应当解析出预设段", preset)
        assertEquals(3, preset!!.entries.size)
        assertEquals("主提示词", preset.entries[0].name)
        assertEquals("你是一个示例。", preset.entries[0].prompt)   // 源字段叫 content
        assertEquals(true, preset.entries[0].enabled)
        assertEquals(false, preset.entries[1].enabled)
        assertNull(preset.entries[2].enabled)
        assertTrue(p.unparsed.isEmpty())
    }

    // ── 负例①：缺字段补默认值 ───────────────────────────────────────────

    @Test
    fun `负例_缺字段一律给 null 与空表_不补默认值`() {
        val p = parse(halfCard)
        val persona = p.persona
        assertNotNull("有名有场景，应当构造出人设", persona)
        assertEquals("半段角色", persona!!.name)
        assertEquals("只有场景", persona.scenario)
        assertNull("源里没有 description ⇒ null，不是空串也不是假内容", persona.description)
        assertTrue("源里没有 personality ⇒ 空表，不是 listOf(\"\")", persona.traits.isEmpty())
        assertTrue("源里没有 mes_example ⇒ 空表", persona.examples.isEmpty())
    }

    @Test
    fun `负例_人设段全空时不构造空壳 Persona`() {
        val p = parse("""{"kind":"character","id":"x","name":"空","updatedAt":0,"data":{"extensions":{}}}""")
        assertNull("没有人设段 ⇒ 整节不显示，而不是显示一个空人设", p.persona)
    }

    // ── 负例②：解析失败抛异常 ───────────────────────────────────────────

    @Test
    fun `负例_坏条目只标 unparsed 不抛不崩`() {
        // 第一条必须是能认脸的正则条目（认脸靠 entries[0].findRegex），坏条目排在后面
        val p = parse(
            """{"kind":"regex","id":"x","name":"坏","updatedAt":0,"entries":[{"findRegex":"甲"},"裸串",[1,2],null]}""",
        )
        assertEquals(FormatKind.TAVO_REGEX, p.verdict.format)
        assertEquals(3, p.unparsed.size)
        assertEquals("裸串", p.unparsed[0].rawText)
        assertEquals(1, p.unparsed[0].index)
        assertNotNull(p.regex)
        assertEquals(1, p.regex!!.entries.size)
        assertEquals("甲", p.regex!!.entries[0].find)
    }

    @Test
    fun `负例_entries 不是数组时记 unparsed 并保留原文`() {
        // 认脸层只在 entries 是数组时才判成 Tavo 正则／预设（entries 是字典会被判成世界书），
        // 所以这条分支直接对构造点下测：给它一个 entries 是对象的原始节点。
        val raw = org.json.JSONObject(
            """{"kind":"preset","id":"x","name":"怪","updatedAt":0,"entries":{"a":1},"basicPrompts":[]}""",
        )
        val bad = ArrayList<UnparsedAsset>()
        val preset = CardAssetParser.presetOf(raw, "tavo", bad)
        assertEquals(1, bad.size)
        assertEquals(AssetKind.PRESET, bad[0].kind)
        assertTrue(bad[0].rawText.contains("\"a\""))
        assertTrue(preset.entries.isEmpty())
        // 245 B2：basicPrompts 在现场样例（J7）里是**数组**——225 的 JSONObject? 恒 null，
        // 那是缺陷不是口径。现在是数组就照收：空数组 ⇒ 空表（不是 null），未解析条目只有一条。
        assertTrue(preset.basicPrompts.isEmpty())
        assertEquals(1, bad.size)
    }

    @Test
    fun `负例_整份坏 JSON 返回 null 而不是抛异常`() {
        assertNull(CardAssetParser.parse("{这不是 JSON".toByteArray(Charsets.UTF_8)))
        assertNull(CardAssetParser.parse(ByteArray(0)))
    }

    // ── 负例③：把 persona 塞进 Card 主体 ────────────────────────────────

    @Test
    fun `负例_Card 主体的字段一个都没多`() {
        val src = readMain("card/CardModel.kt")
        val head = src.substring(src.indexOf("internal data class Card(")).take(320)
        assertTrue("Card 必须仍有 legacyTopLevel", head.contains("val legacyTopLevel"))
        assertFalse("人设不许塞进 Card（§14.3：统一模型里人设是独立资产）", head.contains("ersona"))
    }

    // ── 负例④：改卡文件字节 ─────────────────────────────────────────────

    @Test
    fun `负例_解析前后字节逐字节相同`() {
        val before = tavoCard.toByteArray(Charsets.UTF_8)
        val copy = before.copyOf()
        CardAssetParser.parse(before)
        assertTrue("解析必须只读：一个字节都不许变", before.contentEquals(copy))
        assertEquals(CardFixtures.sha256(copy), CardFixtures.sha256(before))
    }

    @Test
    fun `负例_解析器源码里没有任何写盘调用`() {
        val src = codeOnly(readMain("card/CardAssetParser.kt"))
        for (forbidden in listOf("writeBytes", "writeText", "FileOutputStream", "File(", "delete(", "renameTo")) {
            assertFalse("只读解析：CardAssetParser 不许出现 $forbidden", src.contains(forbidden))
        }
    }

    // ── 负例⑤：另开页面 ─────────────────────────────────────────────────

    @Test
    fun `负例_没有为三节新开页面`() {
        val ui = File(repoRoot(), "app/src/main/java/com/example/zhengdao/ui")
        val stray = ui.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }
            .filter { it.name.contains("CardDetail") || it.name.contains("PersonaDetail") }
            .toList()
        assertTrue("展示必须落在既有卡详情里，不许新开页面：$stray", stray.isEmpty())
    }

    @Test
    fun `正例_卡详情的既有报告里出现人设节_长文折叠`() {
        val text = CardTools.inspect(tavoCard.toByteArray(Charsets.UTF_8))
        assertTrue("要有【人设】节：\n$text", text.contains("【人设】"))
        assertTrue(text.contains("名：示例角色"))
        assertTrue("长文要折叠", text.contains("长文折叠"))
        assertTrue(text.contains("已折叠"))
        assertTrue(text.contains("标签：冷静、克制、话少"))
        assertFalse("卡里没有正则段 ⇒ 整节不出现", text.contains("【正则】"))
        assertFalse("卡里没有预设段 ⇒ 整节不出现", text.contains("【预设】"))
    }

    @Test
    fun `正例_正则与预设文件在报告里各列条数与前三条`() {
        val regexText = CardTools.inspect(tavoRegex.toByteArray(Charsets.UTF_8))
        assertTrue(regexText.contains("【正则】2 条"))
        assertTrue(regexText.contains("未标 0"))
        assertTrue(regexText.contains("【未解析】1 条"))

        val presetText = CardTools.inspect(tavoPreset.toByteArray(Charsets.UTF_8))
        assertTrue(presetText.contains("【预设】3 条"))
        assertTrue(presetText.contains("未标 1"))
        assertTrue(presetText.contains("主提示词"))
    }

    @Test
    fun `正例_五条以上的正则只列前三条并报剩余`() {
        val many = (1..5).joinToString(",") {
            """{"identifier":"$it","name":"第${it}条","findRegex":"甲$it","replaceString":"乙","placements":[],"enabled":true}"""
        }
        val json = """{"kind":"regex","id":"x","name":"多","updatedAt":0,"entries":[$many]}"""
        val text = CardTools.inspect(json.toByteArray(Charsets.UTF_8))
        assertTrue(text.contains("【正则】5 条"))
        assertTrue(text.contains("还有 2 条未显示"))
    }

    // ── 世界书：一个字都没动（红线） ────────────────────────────────────

    @Test
    fun `红线_世界书的 disable 反义仍在且视图自洽`() {
        val p = parse(CardFixtures.J4)
        val book = p.asset as Worldbook
        assertEquals(WorldbookDialect.ST_NATIVE, book.dialect)
        assertEquals(1, book.entries.size)
        assertEquals(true, book.entries[0].enabled)                 // disable=false ⇒ 开
        assertTrue(CardAssetParser.recheck(book.entries[0]).isEmpty())
        assertEquals(false, book.entries[0].raw.optBoolean("disable")) // raw 未改
        assertNull("世界书没有人设段", p.persona)
        assertNull(p.regex)
    }

    @Test
    fun `红线_四家世界书的血统与条目数照旧`() {
        val tavo = parse(CardFixtures.J3).asset as Worldbook
        assertEquals(WorldbookDialect.TAVO, tavo.dialect)
        assertEquals(1, tavo.entries.size)
        assertNull("Tavo 没有 order", tavo.entries[0].order)

        val web = parse(CardFixtures.J1).asset as WebWork
        assertEquals(WorldbookDialect.WEB, web.worldbook!!.dialect)
        assertEquals(listOf("甲", "乙"), web.worldbook!!.entries[0].keys)

        // CCv3 独立世界书：血统照旧（条目在 `data.entries`，而 `worldbook()` 只读顶层 `entries`
        // ⇒ 当前读出 0 条。这是**既有**行为，不在本批范围（派工单：本批不做世界书），
        // 这里只钉「血统没被改坏、解析不崩」，不替它改口径。报告里单列一条待办。
        val ccv3 = parse(
            """{"spec":"lorebook_v3","data":{"entries":[{"keys":["甲"],"content":"v","enabled":true,"insertion_order":3}]}}""",
        )
        assertEquals(FormatKind.CCV3_LOREBOOK, ccv3.verdict.format)
        assertEquals(WorldbookDialect.CCV3, (ccv3.asset as Worldbook).dialect)
    }

    // ── 方言表 ───────────────────────────────────────────────────────────

    @Test
    fun `资产血统与世界书方言一一对应`() {
        val dialects = WorldbookDialect.entries
        val lineages = AssetLineage.entries
        assertEquals("两家枚举必须一样大（多一个就是硬造血统）", dialects.size, lineages.size)
        for (d in dialects) assertEquals(d.name, AssetLineage.of(d).name)
    }

    @Test
    fun `资产字段表按血统给出不同字段名_查不到就是 null`() {
        assertEquals("personality", CardDialectMap.assetFieldOf(AssetLineage.TAVO, AssetKind.PERSONA, "性格/标签"))
        assertEquals("traits", CardDialectMap.assetFieldOf(AssetLineage.WEB, AssetKind.PERSONA, "性格/标签"))
        assertEquals("mes_example", CardDialectMap.assetFieldOf(AssetLineage.CCV3, AssetKind.PERSONA, "对话样例"))
        assertEquals("placements", CardDialectMap.assetFieldOf(AssetLineage.TAVO, AssetKind.REGEX, "范围"))
        assertEquals("placement", CardDialectMap.assetFieldOf(AssetLineage.ST_NATIVE, AssetKind.REGEX, "范围"))
        assertEquals("content", CardDialectMap.assetFieldOf(AssetLineage.TAVO, AssetKind.PRESET, "提示词"))
        assertNull(
            "CCv3 没有独立预设资产 ⇒ null（不可表达，不硬塞一个名字）",
            CardDialectMap.assetFieldOf(AssetLineage.CCV3, AssetKind.PRESET, "提示词"),
        )
        assertNull(CardDialectMap.assetFieldOf(AssetLineage.TAVO, AssetKind.PERSONA, "不存在的语义"))
        assertNull("认不出的来源不硬塞一家", AssetLineage.ofVendor("unknown-vendor"))
    }

    @Test
    fun `反义只按列生效`() {
        assertTrue(CardDialectMap.assetNegated(AssetLineage.ST_NATIVE, AssetKind.REGEX, "启用"))
        assertTrue(CardDialectMap.assetNegated(AssetLineage.CCV3, AssetKind.REGEX, "启用"))
        assertFalse("Tavo 是正写 enabled", CardDialectMap.assetNegated(AssetLineage.TAVO, AssetKind.REGEX, "启用"))
        assertFalse(CardDialectMap.assetNegated(AssetLineage.WEB, AssetKind.REGEX, "启用"))
    }

    /**
     * 「不编字段」的机械版：资产表里出现的每个字段名都必须在这份**有出处**的白名单里。
     *
     * 白名单的来源逐条写得出来：派工单 §1 给定的人设段与条目字段名、
     * `CardFixtures.J5／J6／J7` 的现场样例、`CardBuild` 写出的卡字段。
     * 谁要往表里加一个新名字，就得先把它加进白名单——加的那一刻就是一次自觉的「编字段」决定。
     */
    @Test
    fun `资产表里的字段名都有出处_不许凭空造`() {
        val documented = setOf(
            // 人设（派工单 §1 ＋ CardBuild.cardJson／J5）
            "name", "description", "traits", "scenario", "examples", "personality", "mes_example",
            // 正则（派工单 §1 ＋ J6 ＋ 酒馆那侧的改名）
            "find", "replace", "flags", "scope", "enabled", "findRegex", "replaceString",
            "placements", "placement", "disabled",
            // 预设（派工单 §1 ＋ J7）
            "prompt", "content",
        )
        val used = CardDialectMap.ASSET_TABLE.flatMap { listOfNotNull(it.web, it.tavo, it.st, it.ccv3) }.toSet()
        val stray = used - documented
        assertTrue("表里出现了没有出处的字段名：$stray", stray.isEmpty())
    }

    @Test
    fun `资产表不许写死站点名`() {
        val src = codeOnly(readMain("card/CardDialectMap.kt")) + codeOnly(readMain("card/CardAssetParser.kt"))
        for (site in listOf("风月", "fengyue", "FengYue", "FYCard")) {
            assertFalse("口径：不锚定任何具体站点，源码里出现了「$site」", src.contains(site))
        }
    }

    @Test
    fun `资产字段不混进世界书的字段表`() {
        for (d in WorldbookDialect.entries) {
            val worldbookFields = CardDialectMap.fieldsOf(d)
            val assetFields = AssetKind.entries
                .flatMap { CardDialectMap.assetFieldsOf(AssetLineage.of(d), it) }
            val proprietary = assetFields.filter {
                it in worldbookFields && it in setOf("personality", "mes_example", "findRegex", "placements")
            }
            assertTrue("$d 的资产字段混进了世界书字段表：$proprietary", proprietary.isEmpty())
        }
    }

    @Test
    fun `inlineFlags 只认正则字面量尾部_不猜`() {
        assertEquals(listOf("g", "i"), CardAssetParser.inlineFlags("/abc/gi"))
        assertTrue(CardAssetParser.inlineFlags("/abc").isEmpty())
        assertTrue(CardAssetParser.inlineFlags("abc").isEmpty())
        assertEquals(listOf("g"), CardAssetParser.inlineFlags("/a\\/b/g"))
        assertTrue("斜杠被转义到结尾 ⇒ 不是字面量", CardAssetParser.inlineFlags("/a\\/").isEmpty())
        assertTrue(CardAssetParser.inlineFlags(null).isEmpty())
        assertTrue("尾巴不是标志字母 ⇒ 不当标志", CardAssetParser.inlineFlags("/a/xyz").isEmpty())
    }

    // ── 小工具 ───────────────────────────────────────────────────────────

    private fun parse(json: String): CardAssetParser.Parsed {
        val p = CardAssetParser.parse(json.toByteArray(Charsets.UTF_8))
        assertNotNull("应当能解析：${json.take(40)}", p)
        return p!!
    }

    private fun pngParse(json: String, keyword: String): CardAssetParser.Parsed {
        val bytes = CardFixtures.withText(CardFixtures.basePng(), keyword, CardFixtures.b64(json))
        val p = CardAssetParser.parse(bytes)
        assertNotNull("PNG 卡要能解析出来", p)
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
        src.split("\n").joinToString("\n") { it.substringBefore("//") }
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
}
