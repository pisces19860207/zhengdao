// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// W7（2026-10-19）：外部名册 CardRoster —— 名册存在时 F12/F13/F14/F16 从「未判」升级为真比对。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.card.CardAssetParser
import com.example.zhengdao.baopu.card.CardCheck
import com.example.zhengdao.baopu.card.CardRoster
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CardRosterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // 一张 Tavo 卡：extensions 里带 world / regex_scripts / tavo.theme / 内嵌 character_book
    private fun tavoCard(
        world: String? = "书A",
        bookName: String? = "书A",
        themeName: String? = "渊海",
    ): String {
        val ext = ArrayList<String>()
        if (world != null) ext += "\"world\":\"$world\""
        ext += "\"regex_scripts\":[{\"findRegex\":\"甲\",\"replaceString\":\"乙\"}]"
        if (themeName != null) ext += "\"tavo\":{\"theme\":{\"name\":\"$themeName\",\"spec\":\"tavo_theme_v1\"}}"
        // character_book 属于 data 层（不是 extensions）——§3 的 CCv3 结构
        val book = if (bookName != null) ",\"character_book\":{\"name\":\"$bookName\"}" else ""
        return """
            {"kind":"character","id":1,"name":"卡","updatedAt":"2026-10-19T00:00:00",
             "data":{"name":"卡","description":"","first_mes":"你好","extensions":{${ext.joinToString(",")}}$book}}
        """.trimIndent()
    }

    private fun check(cardJson: String, roster: CardRoster?) =
        CardCheck.check(CardAssetParser.parse(cardJson.toByteArray(Charsets.UTF_8))!!, roster = roster)

    private fun codes(r: com.example.zhengdao.baopu.card.CheckReport) = r.findings.map { it.code }

    // ── 解析 ─────────────────────────────────────────────────────────────

    @Test
    fun roster_parses_full_shape() {
        val r = CardRoster.parse(
            """
            {"worldbooks":["书A"],"assets":["角色A"],
             "regexes":[{"name":"记忆块","findRegex":"甲","replaceString":"乙"}],
             "currentTheme":"渊海"}
            """.trimIndent(),
        )!!
        assertTrue(r.hasWorldbook("书A"))
        assertTrue(r.hasAsset("角色A"))
        assertTrue(r.hasRegex("甲", "乙"))
        assertTrue("记忆块" in r.regexNames)
        assertEquals("渊海", r.currentThemeName)
    }

    @Test
    fun roster_current_theme_can_be_object() {
        val r = CardRoster.parse("""{"currentTheme":{"name":"渊海"}}""")!!
        assertEquals("渊海", r.currentThemeName)
    }

    @Test
    fun roster_bad_or_empty_json_is_null_not_crash() {
        assertNull(CardRoster.parse("不是 json"))
        assertNull(CardRoster.parse(""))
        val empty = CardRoster.parse("{}")!!
        assertTrue(empty.worldbooks.isEmpty())
        assertNull(empty.currentThemeName)
    }

    // ── 查找 ─────────────────────────────────────────────────────────────

    @Test
    fun roster_found_in_same_dir_and_nested_dir() {
        val root = tmp.newFolder("ws")
        java.io.File(root, "名册.json").writeText("""{"worldbooks":["书A"]}""")

        val sub = java.io.File(root, "子目录").apply { mkdirs() }
        val card = java.io.File(sub, "卡.json").apply { writeText("{}") }
        assertEquals("书A", (CardRoster.findNear(card)?.worldbooks?.firstOrNull()))

        val deep = java.io.File(sub, "更深的").apply { mkdirs() }
        val card2 = java.io.File(deep, "卡.json").apply { writeText("{}") }
        assertNotNull(CardRoster.findNear(card2))

        java.io.File(deep, "名册.json").delete() // 清理（TemporaryFolder 会删，这里只是显式）
    }

    @Test
    fun roster_found_under_hidden_dir() {
        val root = tmp.newFolder("ws2")
        val hidden = java.io.File(root, ".抱朴").apply { mkdirs() }
        java.io.File(hidden, "名册.json").writeText("""{"worldbooks":["书A"]}""")
        val card = java.io.File(root, "卡.json").apply { writeText("{}") }
        assertNotNull(CardRoster.findNear(card))
    }

    @Test
    fun roster_missing_returns_null() {
        val root = tmp.newFolder("ws3")
        val card = java.io.File(root, "卡.json").apply { writeText("{}") }
        assertNull(CardRoster.findNear(card))
    }

    // ── 体检升级：无册 = 未判；有册 = 真比对 ───────────────────────────────

    @Test
    fun without_roster_stays_unjudged() {
        val c = codes(check(tavoCard(), null))
        assertTrue("无册必须是「未判」：$c", "CARD_EXTERNAL_WORLDBOOK_UNJUDGED" in c)
    }

    @Test
    fun roster_reports_missing_worldbook_as_error() {
        val roster = CardRoster.parse("""{"worldbooks":["别的书"]}""")!!
        val r = check(tavoCard(world = "书A"), roster)
        assertTrue("应报 CARD_WORLD_MISSING：${codes(r)}", "CARD_WORLD_MISSING" in codes(r))
        assertEquals(1, r.findings.count { it.code == "CARD_WORLD_MISSING" })
    }

    @Test
    fun roster_accepts_present_worldbook() {
        val roster = CardRoster.parse("""{"worldbooks":["书A"]}""")!!
        val c = codes(check(tavoCard(world = "书A"), roster))
        assertTrue("应报在册：$c", "CARD_WORLD_OK" in c)
        assertTrue("不应报缺失：$c", "CARD_WORLD_MISSING" !in c)
    }

    @Test
    fun roster_flags_duplicate_regex() {
        val roster = CardRoster.parse(
            """{"worldbooks":["书A"],"regexes":[{"findRegex":"甲","replaceString":"乙"}]}""",
        )!!
        val c = codes(check(tavoCard(), roster))
        assertTrue("应报重复正则：$c", "CARD_REGEX_DUPLICATE" in c)
    }

    @Test
    fun roster_flags_theme_conflict() {
        val roster = CardRoster.parse("""{"worldbooks":["书A"],"currentTheme":"秘境"}""")!!
        val c = codes(check(tavoCard(themeName = "渊海"), roster))
        assertTrue("应报主题不同：$c", "CARD_THEME_CONFLICT" in c)
    }

    @Test
    fun roster_flags_embedded_book_name_clash() {
        val roster = CardRoster.parse("""{"worldbooks":["书A"]}""")!!
        val c = codes(check(tavoCard(bookName = "书A"), roster))
        assertTrue("应报内嵌书同名：$c", "CARD_EMBEDDED_BOOK_NAME_CLASH" in c)
    }
}
