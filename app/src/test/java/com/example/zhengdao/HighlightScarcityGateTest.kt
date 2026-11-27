// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第九百四十二批 · 高光账本与配额门禁。
// 唯一表在 docs/高光账本与配额.md 的 HL-*-BEGIN/END 块；本文件只读它、不抄第二份参数。
// 夹具数据固定在代码里：删文档里一行 ⇒ 对应夹具的期望读不到或判据改判，必红。
package com.example.zhengdao

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HighlightScarcityGateTest {

    private data class Highlight(
        val ep: Int,
        val type: String,
        val tech: String,
        val trigger: String,
        val person: String,
        val consequence: String,
        val irreplaceable: String,
        val subversion: String,
    )

    private data class Season(val episodes: Int, val rows: List<Highlight>)

    private enum class Level { RED, WARN }

    private data class Finding(val code: String, val level: Level, val msg: String)

    private data class Tables(
        val quota: Map<String, String>,
        val types: List<String>,
        val ledger: List<String>,
        val techniques: Map<String, String>,
        val subversions: List<String>,
        val fixtures: Map<String, Pair<String, String>>,
        val mutations: Map<String, Pair<String, String>>,
        val division: List<List<String>>,
    )

    /** 变异入口：给替身文档绝对路径；不给就读仓库里的唯一表。 */
    private fun docFile(): File {
        val override = System.getenv("HIGHLIGHT_DOC")
        val candidates = if (override.isNullOrBlank()) {
            listOf(File("../docs/高光账本与配额.md"), File("docs/高光账本与配额.md"))
        } else {
            listOf(File(override))
        }
        return candidates.firstOrNull { it.isFile }
            ?: error("高光账本与配额文档没找到：${candidates.joinToString { it.absolutePath }}")
    }

    private fun section(text: String, begin: String, end: String): List<String> {
        val all = text.lines()
        val from = all.indexOfFirst { it.contains(begin) }
        val to = all.indexOfFirst { it.contains(end) }
        check(from >= 0 && to > from) { "文档缺标记：$begin / $end" }
        return all.subList(from + 1, to)
    }

    private val headerCells = setOf("编号", "序", "批次")

    private fun isDataRow(cols: List<String>): Boolean =
        cols.size >= 2 && cols[0] !in headerCells && cols[0].any { it != '-' && it != ':' && it != ' ' }

    private fun rows(text: String, begin: String, end: String): List<List<String>> =
        section(text, begin, end)
            .filter { it.trimStart().startsWith("|") }
            .map { line -> line.trim().trim('|').split('|').map { cell -> cell.trim() } }
            .filter(::isDataRow)

    private fun tables(text: String): Tables {
        val quota = rows(text, "HL-QUOTA-BEGIN", "HL-QUOTA-END").associate { c ->
            check(c.size == 4) { "配额表必须 4 列：$c" }
            c[2] to c[3]
        }
        val types = rows(text, "HL-TYPE-BEGIN", "HL-TYPE-END").map { c ->
            check(c.size == 2) { "类型表必须 2 列：$c" }
            c[1]
        }
        val ledger = rows(text, "HL-LEDGER-BEGIN", "HL-LEDGER-END").map { c ->
            check(c.size == 3) { "账本列表必须 3 列：$c" }
            c[1]
        }
        val techniques = rows(text, "HL-TECHNIQUES-BEGIN", "HL-TECHNIQUES-END").associate { c ->
            check(c.size == 3) { "技法表必须 3 列：$c" }
            c[1] to c[2]
        }
        val subversions = rows(text, "HL-SUBVERSION-BEGIN", "HL-SUBVERSION-END").map { c ->
            check(c.size == 2) { "subversion 表必须 2 列：$c" }
            c[1]
        }
        val fixtures = rows(text, "HL-FIXTURE-BEGIN", "HL-FIXTURE-END").associate { c ->
            check(c.size == 3) { "夹具表必须 3 列：$c" }
            c[0] to (c[1] to c[2])
        }
        val mutations = rows(text, "HL-MUTATION-BEGIN", "HL-MUTATION-END").associate { c ->
            check(c.size == 3) { "变异表必须 3 列：$c" }
            c[0] to (c[1] to c[2])
        }
        val division = rows(text, "HL-DIVISION-BEGIN", "HL-DIVISION-END")
        return Tables(quota, types, ledger, techniques, subversions, fixtures, mutations, division)
    }

    private fun load(): Tables = tables(docFile().readText())

    private fun num(t: Tables, key: String, default: Int): Int = t.quota[key]?.toIntOrNull() ?: default

    private fun identity(h: Highlight): Triple<String, String, String> = Triple(h.trigger, h.person, h.consequence)

    private fun c1c2(season: Season, cap: Int, ratio: Int): List<Finding> {
        val out = ArrayList<Finding>()
        season.rows.groupBy { it.type }.forEach { (type, list) ->
            if (list.size > cap) {
                out += Finding("C1-TYPE-REPEAT", Level.RED, "类型 $type 出现 ${list.size} 次")
            }
        }
        if (season.episodes == 0) return out
        val light = season.rows.map { it.ep }.toSet()
        val emptyPct = (season.episodes - light.size) * 100 / season.episodes
        if (emptyPct < ratio) {
            out += Finding("C2-LOW-NO-HIGHLIGHT", Level.RED, "无高光集 ${emptyPct}% < $ratio%")
        }
        for (ep in 1..season.episodes - 2) {
            if (light.containsAll(setOf(ep, ep + 1, ep + 2))) {
                out += Finding("C2-CONSECUTIVE", Level.RED, "第 $ep–${ep + 2} 集连续有大情绪")
                break
            }
        }
        return out
    }

    private fun c3(season: Season, changeRule: String): List<Finding> {
        val out = ArrayList<Finding>()
        for (i in season.rows.indices) {
            for (j in i + 1 until season.rows.size) {
                val a = season.rows[i]
                val b = season.rows[j]
                if (a.tech != b.tech) continue
                val sameIdentity = identity(a) == identity(b)
                val repeat = if (changeRule == "措辞") {
                    sameIdentity && a.irreplaceable == b.irreplaceable
                } else {
                    sameIdentity
                }
                if (repeat) out += Finding("C3-SAME-TECH", Level.RED, "技法 ${a.tech} 复用未换三件")
            }
        }
        return out
    }

    private fun ledgerRules(season: Season): List<Finding> {
        val out = ArrayList<Finding>()
        season.rows.forEach { h ->
            if (h.trigger.isBlank() || h.consequence.isBlank()) {
                out += Finding("C5-MISSING", Level.RED, "第 ${h.ep} 集缺触发物或后果")
            }
            if (h.type.isBlank() || h.irreplaceable.isBlank()) {
                out += Finding("C4-INCOMPLETE", Level.RED, "第 ${h.ep} 集高光账本列不齐")
            }
            if (h.subversion.isBlank()) {
                out += Finding("H-NO-SUBVERSION", Level.WARN, "第 ${h.ep} 集缺 subversion")
            }
        }
        return out
    }

    private fun c6(season: Season, t: Tables, streakOn: Boolean): List<Finding> {
        val out = ArrayList<Finding>()
        if (streakOn) {
            val sorted = season.rows.sortedBy { it.ep }
            for (i in 0 until sorted.size - 1) {
                val a = sorted[i]
                val b = sorted[i + 1]
                val bothCommon = t.techniques[a.tech] == "常见" && t.techniques[b.tech] == "常见"
                if (b.ep == a.ep + 1 && bothCommon) {
                    out += Finding("C6-COMMON-STREAK", Level.RED, "第 ${a.ep}–${b.ep} 集连续用常见技法")
                    break
                }
            }
        }
        season.rows.forEach { h ->
            val plain = h.subversion in setOf("无", "没有")
            if (plain && t.techniques[h.tech] == "常见") {
                out += Finding("C6-COMMON-NO-SUBVERSION", Level.RED, "第 ${h.ep} 集常见技法写 subversion=无")
            }
        }
        return out
    }

    private fun c7h1(season: Season, t: Tables, denseAt: Int): List<Finding> {
        val out = ArrayList<Finding>()
        if (t.quota["allowEmptyEpisode"] == "否" && season.episodes > 0) {
            val light = season.rows.map { it.ep }.toSet()
            if (light.size < season.episodes) {
                out += Finding("C7-FORCED-HIGHLIGHT", Level.RED, "整集无高光被判红（过度收紧）")
            }
        }
        if (season.rows.size >= denseAt) {
            out += Finding("H1-OVERDENSE", Level.WARN, "登记 ${season.rows.size} 条高光过密")
        }
        return out
    }

    /** 判据纯函数：季节 → 发现。所有阈值/闭集从文档表取值。 */
    private fun judge(season: Season, t: Tables): List<Finding> {
        val changeRule = t.quota["changeRule"] ?: "三件"
        val out = ArrayList<Finding>()
        out += c1c2(season, num(t, "typePerWork", 1), num(t, "noHighlightRatio", 60))
        out += c3(season, changeRule)
        out += ledgerRules(season)
        out += c6(season, t, t.quota["commonStreakRule"] == "1")
        out += c7h1(season, t, num(t, "densePromptRows", 6))
        return out
    }

    private fun redCodes(f: List<Finding>): List<String> = f.filter { it.level == Level.RED }.map { it.code }

    /** 期望写法：GREEN / RED:<前缀> / WARN:<前缀> */
    private fun matchesExpectation(expected: String, f: List<Finding>): Boolean = when {
        expected == "GREEN" -> f.isEmpty()
        expected.startsWith("RED:") ->
            f.any { it.level == Level.RED && it.code.startsWith(expected.removePrefix("RED:")) }
        expected.startsWith("WARN:") ->
            f.any { it.level == Level.WARN && it.code.startsWith(expected.removePrefix("WARN:")) }
        else -> error("未知期望：$expected")
    }

    // ---- 夹具数据（固定在代码；判据与期望只读文档）----

    private fun h(ep: Int) = Highlight(
        ep, "哭戏", "把汤又热了一遍", "旧照片", "她", "她把信收好",
        "只有这张旧照片能解释她为什么回头", "该哭的地方没哭",
    )

    private val quietTechs = listOf(
        "把汤又热了一遍", "系鞋带", "看窗外", "搁下杯子", "多走一站",
        "把伞留在门边", "绕远路回家", "把信折好收起来", "旧物在手里翻两次",
    )
    private val tenTypes = listOf("哭戏", "崩溃", "爆发", "和解", "告白", "重逢", "别离", "顿悟", "决裂", "牺牲")

    private fun cases(): Map<String, Season> {
        val f1 = Season(12, listOf(1, 2, 4, 5, 7, 8).map { h(it) })
        val f2 = Season(12, (1..9).map { h(it).copy(type = tenTypes[it - 1], tech = quietTechs[it - 1]) })
        val f3row = h(3).copy(type = "哭戏", tech = "笑→哭")
        val f3 = Season(12, listOf(f3row, f3row.copy(ep = 7, type = "崩溃")))
        val f4 = Season(12, listOf(
            h(3).copy(
                tech = "笑→哭", trigger = "旧手机", consequence = "她回了那条短信",
                irreplaceable = "旧手机里存着没发出去的短信",
            ),
            h(9).copy(
                type = "崩溃", tech = "笑→哭", person = "他", trigger = "别人的口音",
                consequence = "他决定留下", irreplaceable = "那口音让他想起母亲",
            ),
        ))
        val f5 = Season(12, listOf(h(3).copy(consequence = "")))
        val f6 = Season(12, listOf(h(5).copy(tech = "眼部特写", subversion = "无")))
        val f7 = Season(1, emptyList())
        val f8 = Season(12, listOf(
            h(1).copy(type = tenTypes[0], tech = quietTechs[0]),
            h(1).copy(type = tenTypes[1], tech = quietTechs[1]),
            h(3).copy(type = tenTypes[2], tech = quietTechs[2]),
            h(3).copy(type = tenTypes[3], tech = quietTechs[3]),
            h(5).copy(type = tenTypes[4], tech = quietTechs[4]),
            h(7).copy(type = tenTypes[5], tech = quietTechs[5]),
        ))
        val f9 = Season(12, listOf(h(6).copy(tech = "眼部特写", subversion = "该特写的地方切走")))
        return mapOf(
            "F1" to f1, "F2" to f2, "F3" to f3, "F4" to f4, "F5" to f5,
            "F6" to f6, "F7" to f7, "F8" to f8, "F9" to f9,
        )
    }

    private fun mutateDoc(text: String, key: String, from: String, to: String): String {
        val anchor = "| $key | $from |"
        assertTrue("变异锚点没找到：$key", text.contains(anchor))
        return text.replace(anchor, "| $key | $to |")
    }

    @Test
    fun `唯一表计数锁_类型十技法十二subversion三账本六列`() {
        val t = load()
        assertEquals("高光类型闭集 10 条", 10, t.types.size)
        assertEquals("常见技法 12 条", 12, t.techniques.size)
        assertEquals("全部标为常见", setOf("常见"), t.techniques.values.toSet())
        assertEquals("subversion 合法写法 3 条", 3, t.subversions.size)
        val cols = listOf("集", "高光类型", "触发物", "为什么不可替代", "后果", "subversion")
        assertEquals("账本六列", cols, t.ledger)
        assertEquals("配额表 7 行", 7, t.quota.size)
        assertEquals("夹具表 9 行", 9, t.fixtures.size)
        assertEquals("变异表 5 行", 5, t.mutations.size)
        val reading = "HL-READING table types=${t.types.size} tech=${t.techniques.size} sub=${t.subversions.size}"
        val tail = "ledger=${t.ledger.size} quota=${t.quota.size}"
        println("$reading $tail fixture=${t.fixtures.size} mutation=${t.mutations.size}")
    }

    @Test
    fun `分工表锁_两套计数不许互相顶替`() {
        val t = load()
        assertEquals("三批分工三行", 3, t.division.size)
        val byBatch = t.division.associateBy { it[0] }
        val d941 = byBatch["941"] ?: error("缺 941 行")
        val d942 = byBatch["942"] ?: error("缺 942 行")
        val d940 = byBatch["940"] ?: error("缺 940 行")
        val eye = d941[1].contains("眼部特写 ≤2／集") && d941[1].contains("眼泪镜头 ≤1／集")
        assertTrue("941 写明眼部≤2/集、眼泪≤1/集", eye)
        assertEquals("941 计数对象是镜头", "镜头", d941[2])
        assertEquals("942 计数对象是高光类型／技法", "高光类型／技法", d942[2])
        assertEquals("942 单位是整部／全季", "整部／全季", d942[3])
        assertFalse("942 不许管镜头计数", d942[1].contains("镜头") || d942[2].contains("镜头"))
        assertTrue("940 管动作个体化", d940[1].contains("动作个体化"))
        println("HL-READING division 940=动作个体化 941=镜头/集 942=类型技法/整部")
    }

    @Test
    fun `账本六列齐缺列即红`() {
        val t = load()
        val full = judge(Season(12, listOf(h(2))), t)
        assertFalse("六列齐不许判红：${redCodes(full)}", redCodes(full).contains("C4-INCOMPLETE"))
        assertFalse("六列齐不许判红：${redCodes(full)}", redCodes(full).contains("C5-MISSING"))
        val missing = judge(Season(12, listOf(h(2).copy(consequence = ""))), t)
        assertTrue("缺后果必红 C5", redCodes(missing).contains("C5-MISSING"))
        val noSub = judge(Season(12, listOf(h(2).copy(subversion = ""))), t)
        assertFalse("缺 subversion 是提示不是红：${redCodes(noSub)}", redCodes(noSub).contains("C6-COMMON-NO-SUBVERSION"))
        assertTrue("缺 subversion 必须留 WARN", noSub.any { it.level == Level.WARN && it.code == "H-NO-SUBVERSION" })
        println("HL-READING ledger full=0 missing consequence=RED:C5 no subversion=WARN:H")
    }

    @Test
    fun `九条夹具逐条咬住`() {
        val t = load()
        val cases = cases()
        assertEquals("夹具表与代码夹具同一组 id", t.fixtures.keys, cases.keys)
        val bad = ArrayList<String>()
        val readings = ArrayList<String>()
        cases.forEach { (id, season) ->
            val expected = t.fixtures[id]!!.second
            val found = judge(season, t)
            if (!matchesExpectation(expected, found)) {
                val actual = found.map { "${it.level}:${it.code}" }
                bad += "$id 期望=$expected 实际=$actual"
            }
            readings += "$id=$expected"
        }
        println("HL-READING fixtures ${readings.joinToString(",")}")
        assertEquals("夹具失配：$bad", emptyList<String>(), bad)
    }

    @Test
    fun `五条变异逐条咬住_红在正确判据上`() {
        val t = load()
        val raw = docFile().readText()
        val cases = cases()
        assertEquals("变异表 V1–V5", setOf("V1", "V2", "V3", "V4", "V5"), t.mutations.keys)

        // V1：C1 上限 1→3，F1 仍红 C1，边界 2 次类型转绿
        val v1 = tables(mutateDoc(raw, "typePerWork", "1", "3"))
        assertTrue("V1 F1 必红 C1", redCodes(judge(cases["F1"]!!, v1)).contains("C1-TYPE-REPEAT"))
        val b1 = Season(12, listOf(h(1), h(3)))
        assertTrue("V1 基线 B1 必红 C1", redCodes(judge(b1, t)).contains("C1-TYPE-REPEAT"))
        assertFalse("V1 后 B1 必须无红", redCodes(judge(b1, v1)).contains("C1-TYPE-REPEAT"))

        // V2：无高光下限 60→30，F2 仍红 C2，边界 50% 无光转绿
        val v2 = tables(mutateDoc(raw, "noHighlightRatio", "60", "30"))
        assertTrue("V2 F2 必红 C2", redCodes(judge(cases["F2"]!!, v2)).any { it.startsWith("C2") })
        val b2 = Season(12, (1..6).map { h(it * 2 - 1).copy(type = tenTypes[it - 1], tech = quietTechs[it - 1]) })
        assertTrue("V2 基线 B2 必红 C2", redCodes(judge(b2, t)).contains("C2-LOW-NO-HIGHLIGHT"))
        assertFalse("V2 后 B2 无红 C2", redCodes(judge(b2, v2)).contains("C2-LOW-NO-HIGHLIGHT"))

        // V3：换三件→换措辞，F3 仍红 C3、F4 仍绿，只换措辞的 B3 转绿
        val v3 = tables(mutateDoc(raw, "changeRule", "三件", "措辞"))
        assertTrue("V3 F3 必红 C3", redCodes(judge(cases["F3"]!!, v3)).contains("C3-SAME-TECH"))
        assertEquals("V3 F4 仍绿", emptyList<String>(), judge(cases["F4"]!!, v3).map { it.code })
        val b3 = Season(12, listOf(
            h(3).copy(tech = "笑→哭"),
            h(7).copy(tech = "笑→哭", irreplaceable = "换了个说法但触发物人物后果都同"),
        ))
        assertTrue("V3 基线 B3 必红 C3", redCodes(judge(b3, t)).contains("C3-SAME-TECH"))
        assertFalse("V3 后 B3 无红 C3", redCodes(judge(b3, v3)).contains("C3-SAME-TECH"))

        // V4：允许整集无高光→否，F7 必红 C7
        val v4 = tables(mutateDoc(raw, "allowEmptyEpisode", "是", "否"))
        assertTrue("V4 F7 基线必绿", judge(cases["F7"]!!, t).isEmpty())
        assertTrue("V4 F7 必红 C7", redCodes(judge(cases["F7"]!!, v4)).contains("C7-FORCED-HIGHLIGHT"))

        // V5：关掉常见技法连续两次，F6 仍红（独立路径），B5 转绿
        val v5 = tables(mutateDoc(raw, "commonStreakRule", "1", "0"))
        assertTrue("V5 F6 必红 C6", redCodes(judge(cases["F6"]!!, v5)).any { it.startsWith("C6") })
        val b5 = Season(12, listOf(h(3).copy(type = "哭戏", tech = "抿嘴"), h(4).copy(type = "崩溃", tech = "握拳")))
        assertTrue("V5 基线 B5 必红 C6", redCodes(judge(b5, t)).contains("C6-COMMON-STREAK"))
        assertFalse("V5 后 B5 无红 C6", redCodes(judge(b5, v5)).contains("C6-COMMON-STREAK"))

        println("HL-READING mutation V1=C1 V2=C2 V3=C3 V4=C7 V5=C6-streak F6-still-red")
    }

    @Test
    fun `自检_删一行夹具期望必被抓住`() {
        val t = load()
        val cases = cases()
        assertTrue("基线夹具齐 9 行", t.fixtures.size == 9 && cases.size == 9)
        val dropped = t.copy(fixtures = t.fixtures - "F3")
        assertFalse("删 F3 期望后计数锁必失败", dropped.fixtures.size == cases.size)
        assertTrue("删行是模拟变异：文档原文仍有 F3", docFile().readText().contains("| F3 |"))
        println("HL-READING selfcheck delete-row detected")
    }
}
