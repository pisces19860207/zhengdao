// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百四十九批 组4：`CardDialectMap` 接卡片格式检查链。
//
// 判据：docs/格式层规格-三套卡与酒馆PNG-2026-10-19.md
//   §15.2／§15.6（F22 每家只跑自己那张判据表；§16.3-3 格式→方言三层并存）
//   §3.3（酒馆字段：核心 39 项＋扩展 4 项）
//
// 夹具：CardFixtures（全部合成，§6.0 红线）
package com.example.zhengdao.baopu.card

import com.example.zhengdao.baopu.agent.CardFixtures
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「方言表接在体检链上」这件事的可测证据，分三类：
 * ① 血统映射（`dialectOf`／`WORLDBOOK_FORMATS`）本身对；
 * ② 守卫函数判得准——别家码报红、本家码与卡内码一条不冤枉（**合法卡输出逐字节不变**）；
 * ③ 源码门禁——`CardCheck.check()` 真的调了守卫、字段表真的派生自对应表。
 *   ③ 就是「接上前失败／接上后正确」那一条：接线前它红，接线后它绿。
 */
class CardDialectChainTest {

    private fun repoFile(rel: String): File =
        File(rel).let { if (it.exists()) it else File("app/$rel") }

    private fun read(rel: String): String = repoFile(rel).readText()

    private fun codeOnly(src: String): String =
        src.split("\n").joinToString("\n") { it.substringBefore("//") }
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")

    private val checkFile = "src/main/java/com/example/zhengdao/baopu/card/CardCheck.kt"

    private fun check(json: String): CheckReport =
        CardCheck.check(CardAssetParser.parse(json.toByteArray(Charsets.UTF_8))!!)

    private fun finding(code: String, level: Level = Level.WARN): Finding =
        Finding(level, code, Stage.FIELD, null, null, "探针", code)

    // ── ① 格式 → 血统 ────────────────────────────────────────────────────

    @Test
    fun `四类世界书都有血统_认脸失败与卡正则预设都没有`() {
        assertEquals(WorldbookDialect.WEB, CardDialectMap.dialectOf(FormatKind.WEB_BOOK))
        assertEquals(WorldbookDialect.TAVO, CardDialectMap.dialectOf(FormatKind.TAVO_BOOK))
        assertEquals(WorldbookDialect.ST_NATIVE, CardDialectMap.dialectOf(FormatKind.ST_WORLD))
        assertEquals(WorldbookDialect.CCV3, CardDialectMap.dialectOf(FormatKind.CCV3_LOREBOOK))
        for (f in listOf(
                FormatKind.TAVO_CHAR, FormatKind.ST_CARD_PNG, FormatKind.TAVO_REGEX,
                FormatKind.TAVO_PRESET, FormatKind.UNKNOWN,
            )
        ) {
            assertNull("$f 不是世界书，不该硬造一个方言", CardDialectMap.dialectOf(f))
            assertFalse("$f 混进了世界书格式列", f in CardDialectMap.WORLDBOOK_FORMATS)
        }
    }

    @Test
    fun `世界书格式列与方言表互为定义_没有漏映射也没有空映射`() {
        // 这一条今天绿 ⇒ dialectOf 里「世界书却没血统」那条分支不可达；
        // 它守的是以后：有人往 FormatKind 加第五种世界书、或往世界书列里加成员却忘了同步表。
        for (f in FormatKind.entries) {
            val mapped = CardDialectMap.dialectOf(f) != null
            assertEquals(
                "$f 的「是不是世界书」与「有没有血统」必须同真同假，否则体检会一声不响地跑零条判据",
                f in CardDialectMap.WORLDBOOK_FORMATS, mapped,
            )
            assertNull(
                "$f 不在世界书列里 ⇒ 守卫不该出 DIALECT_UNMAPPED_FORMAT",
                CardDialectChecks.unmappedDialectFinding(f),
            )
        }
    }

    // ── ② 串味判得准 ─────────────────────────────────────────────────────

    @Test
    fun `酒馆体检里混进Tavo的码_守卫报红并点名是谁家的`() {
        val out = CardDialectChecks.foreignCodeFindings(
            WorldbookDialect.ST_NATIVE,
            listOf(finding("TV_KEYWORDS_EMPTY"), finding("ST_KEY_NOT_ARRAY", Level.ERROR)),
        )
        assertEquals("只该逮住那条 TV_ 码：${out.map { it.code }}", 1, out.size)
        val g = out[0]
        assertEquals("DIALECT_FOREIGN_CODE", g.code)
        assertEquals("判据走错门是 ERROR，不是提醒一句", Level.ERROR, g.level)
        assertEquals("TV_KEYWORDS_EMPTY", g.evidence)
        assertTrue("话术要点名串进来的是谁家：${g.message}", g.message.contains("Tavo"))
    }

    @Test
    fun `本方言的码一条都不报_守卫不许自我指控`() {
        for (dialect in WorldbookDialect.entries) {
            val allowed = CardDialectChecks.codesFor(dialect) + CardDialectChecks.GUARD_CODES
            val out = CardDialectChecks.foreignCodeFindings(dialect, allowed.map { finding(it) })
            assertTrue("${dialect.name} 自己的码被报成串味：$out", out.isEmpty())
        }
    }

    @Test
    fun `守卫码不许混进方言判据表`() {
        // 守卫的码一旦进了 codesFor，就等于每家方言都「允许自己报警」——
        // 外来自检从此瞎掉，而且没人会发现。
        for (dialect in WorldbookDialect.entries) {
            assertTrue(
                "${dialect.name} 的判据表里混进了守卫自己的码",
                CardDialectChecks.codesFor(dialect).none { it in CardDialectChecks.GUARD_CODES },
            )
        }
    }

    @Test
    fun `三套合法书各跑一遍_守卫一条都不加`() {
        val cases = listOf(
            Triple("J1(网络)", WorldbookDialect.WEB, CardFixtures.J1),
            Triple("J3(Tavo)", WorldbookDialect.TAVO, CardFixtures.J3),
            Triple("J4(酒馆)", WorldbookDialect.ST_NATIVE, CardFixtures.J4),
        )
        for ((name, dialect, json) in cases) {
            val codes = check(json).findings.map { it.code }
            assertTrue(
                "$name 的体检出了不属于 ${dialect.name} 的码 ⇒ 要么判据串味要么表没写全：$codes",
                codes.all { it in CardDialectChecks.codesFor(dialect) },
            )
            assertTrue(
                "$name 本来就是合法书，守卫不该开口：$codes",
                codes.none { it in CardDialectChecks.GUARD_CODES },
            )
        }
    }

    @Test
    fun `卡内的CARD系列码不算串味_方言为空就不跑这一问`() {
        // checkCardInfo 的 CARD_* 码（7 个）与方言无关，且**不在任何一家 codesFor 里**；
        // 角色卡（dialectOf=null）只问①不问②，否则每张合法角色卡都凭空多出 7 条 ERROR。
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
        val codes = r.findings.map { it.code }
        assertTrue("这条链要真有卡内码可跑（否则本用例只是空转）：$codes", codes.any { it.startsWith("CARD_") })
        assertTrue("角色卡被守卫冤枉：$codes", codes.none { it in CardDialectChecks.GUARD_CODES })
        assertTrue("守卫不许把合法的卡内 INFO 升成 ERROR：${r.errors.map { it.code }}", r.errors.isEmpty())
    }

    // ── 字段表派生：合法字段不许被报成表外 ────────────────────────────────

    /** 接线前手写在 CardCheck 里的那 43 个名字（= 现在必须继续认得的契约）。 */
    private val FROZEN_43: Set<String> = setOf(
        "key", "keysecondary", "comment", "content", "constant", "vectorized", "selective",
        "selectiveLogic", "addMemo", "order", "position", "disable", "ignoreBudget",
        "excludeRecursion", "preventRecursion",
        "matchPersonaDescription", "matchCharacterDescription", "matchCharacterPersonality",
        "matchCharacterDepthPrompt", "matchScenario", "matchCreatorNotes",
        "delayUntilRecursion", "probability", "useProbability", "depth", "group",
        "groupOverride", "groupWeight", "scanDepth", "caseSensitive", "matchWholeWords",
        "useGroupScoring", "automationId", "role", "sticky", "cooldown", "delay", "uid",
        "displayIndex", "extensions", "triggers", "outletName", "characterFilter",
    )

    @Test
    fun `对应表的st列正好是那43项的29项_余下14项是表外补充`() {
        val table = CardDialectMap.fieldsOf(WorldbookDialect.ST_NATIVE)
        assertEquals("对应表 st 列改名了，体检表会跟着漂", 29, table.size)
        assertTrue("对应表冒出了 43 项之外的字段名：${table - FROZEN_43}", FROZEN_43.containsAll(table))
        assertEquals(
            "表外补充那 14 项变了——它必须正好补上对应表没给行的字段",
            setOf(
                "comment", "vectorized", "selective", "addMemo", "ignoreBudget",
                "groupOverride", "groupWeight", "automationId", "uid", "displayIndex",
                "extensions", "triggers", "outletName", "characterFilter",
            ),
            FROZEN_43 - table,
        )
    }

    private fun stEntryWithAllKnownFields(): String {
        val book = JSONObject(CardFixtures.J4)
        val entry = book.getJSONObject("entries").getJSONObject("0")
        for (k in FROZEN_43) {
            if (entry.has(k)) continue
            when (k) {
                "extensions" -> entry.put(k, JSONObject())
                "triggers" -> entry.put(k, JSONArray())
                "characterFilter" -> entry.put(k, JSONObject().put("isJs", false))
                "automationId", "outletName" -> entry.put(k, "")
                "ignoreBudget", "useGroupScoring", "matchPersonaDescription",
                "matchCharacterDescription", "matchCharacterPersonality",
                "matchCharacterDepthPrompt", "matchScenario", "matchCreatorNotes" -> entry.put(k, false)
                else -> entry.put(k, 0)
            }
        }
        assertEquals("J4 夹具与 43 项对不上（有表外键或漏键）", 43, entry.length())
        return book.toString()
    }

    @Test
    fun `43个合法字段全齐的条目_一条表外都不报`() {
        val r = check(stEntryWithAllKnownFields())
        assertEquals(
            "派生后的体检表把合法字段认成了表外字段：" +
                r.of("ST_UNKNOWN_FIELD_PRESERVED").map { it.field },
            0, r.count("ST_UNKNOWN_FIELD_PRESERVED"),
        )
    }

    @Test
    fun `真表外字段照样报INFO_派生没把判据跑成空表`() {
        val book = JSONObject(CardFixtures.J4)
        book.getJSONObject("entries").getJSONObject("0").put("zdProbeField", 1)
        val r = check(book.toString())
        val hits = r.of("ST_UNKNOWN_FIELD_PRESERVED")
        assertEquals("表外字段判据没跑起来：${r.findings.map { it.code }}", 1, hits.size)
        assertEquals("zdProbeField", hits[0].field)
        assertEquals(Level.INFO, hits[0].level)
    }

    // ── ③ 源码门禁：接上了没有 ───────────────────────────────────────────

    @Test
    fun `体检出报告前真的问了方言守卫`() {
        val code = codeOnly(read(checkFile))
        val call = code.indexOf("findings += dialectGuards(")
        val report = code.indexOf("return CheckReport(findings, stats, skipped, given)")
        assertTrue("CardCheck.check 没调方言守卫 ⇒ F22 表只有测试在读，生产一声不响", call > 0)
        assertTrue("守卫必须跑在出报告之前", call < report)
        assertTrue("守卫内部没问血统映射", code.contains("CardDialectChecks.unmappedDialectFinding("))
        assertTrue("守卫内部没问外来码", code.contains("CardDialectChecks.foreignCodeFindings("))
    }

    @Test
    fun `酒馆字段表派生自对应表_源码里不再手写第二份字段名`() {
        val code = codeOnly(read(checkFile))
        assertTrue(
            "ST_ENTRY_FIELDS 又变回手写的名字表了（两份事实必然漂移）",
            code.contains("CardDialectMap.fieldsOf(WorldbookDialect.ST_NATIVE) + ST_ENTRY_EXTRA_FIELDS"),
        )
        assertFalse(
            "手写表残留：这六个 match* 只该出现在对应表与表外补充里",
            Regex("setOf\\([^)]*matchPersonaDescription[^)]*matchCreatorNotes[^)]*delayUntilRecursion",
                RegexOption.DOT_MATCHES_ALL).containsMatchIn(code),
        )
    }
}
