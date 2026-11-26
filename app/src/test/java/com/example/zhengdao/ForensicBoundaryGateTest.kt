// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第九百三十七批 · 观察与鉴定边界门禁。
// 唯一表在 docs/观察与鉴定边界.md 的 FORENSIC-*-BEGIN/END 块；本文件只读它、不抄第二份模板。
// 夹具固定在代码里，所以删掉表里一行 ⇒ 对应夹具由红转绿。
package com.example.zhengdao

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.util.regex.Pattern

class ForensicBoundaryGateTest {

    private enum class Voice { NARRATOR, CHARACTER }

    private data class AllowRow(val id: String, val category: String, val sample: String, val boundary: String)
    private data class ForbidRow(val id: String, val category: String, val template: String, val verdict: String)
    private data class TokenRow(val id: String, val category: String, val token: String)
    private data class BanRow(val id: String, val direction: String, val template: String)
    private data class Finding(val code: String, val message: String)
    private data class RedCase(val id: String, val text: String, val ruleId: String)
    private data class Tables(
        val allow: List<AllowRow>,
        val forbid: List<ForbidRow>,
        val role: List<TokenRow>,
        val med: List<BanRow>,
        val law: List<BanRow>,
    )

    /** 变异入口：给替身文档的绝对路径；不给就读仓库里的唯一表。 */
    private fun docFile(): File {
        val override = System.getenv("FORENSIC_DOC")
        val candidates = if (override.isNullOrBlank()) {
            listOf(File("../docs/观察与鉴定边界.md"), File("docs/观察与鉴定边界.md"))
        } else {
            listOf(File(override))
        }
        return candidates.firstOrNull { it.isFile }
            ?: error("观察与鉴定边界文档没找到：${candidates.joinToString { it.absolutePath }}")
    }

    private fun section(begin: String, end: String): List<String> {
        val all = docFile().readLines()
        val from = all.indexOfFirst { it.contains(begin) }
        val to = all.indexOfFirst { it.contains(end) }
        check(from >= 0 && to > from) { "文档缺标记：$begin / $end" }
        return all.subList(from + 1, to)
    }

    private fun rows(begin: String, end: String): List<List<String>> =
        section(begin, end)
            .filter { it.trimStart().startsWith("|") }
            .map { line -> line.trim().trim('|').split('|').map { cell -> cell.trim() } }
            .filter { cols -> cols.size >= 2 && cols[0] != "编号" && cols[0].any { it != '-' && it != ':' && it != ' ' } }

    private fun tables(): Tables {
        val allow = rows("FORENSIC-ALLOW-BEGIN", "FORENSIC-ALLOW-END").map { c ->
            check(c.size == 4) { "能写表必须 4 列：$c" }
            AllowRow(c[0], c[1], c[2], c[3])
        }
        val forbid = rows("FORENSIC-FORBID-BEGIN", "FORENSIC-FORBID-END").map { c ->
            check(c.size == 4) { "禁写表必须 4 列：$c" }
            ForbidRow(c[0], c[1], c[2], c[3])
        }
        val role = rows("FORENSIC-ROLE-BEGIN", "FORENSIC-ROLE-END").map { c ->
            check(c.size == 3) { "角色标记表必须 3 列：$c" }
            TokenRow(c[0], c[1], c[2])
        }
        val med = rows("FORENSIC-MED-BEGIN", "FORENSIC-MED-END").map { c ->
            check(c.size == 3) { "医学处置表必须 3 列：$c" }
            BanRow(c[0], c[1], c[2])
        }
        val law = rows("FORENSIC-LAW-BEGIN", "FORENSIC-LAW-END").map { c ->
            check(c.size == 3) { "法律结论表必须 3 列：$c" }
            BanRow(c[0], c[1], c[2])
        }
        return Tables(allow, forbid, role, med, law)
    }

    /** `…` 是任意填充，`／` 是任选其一；其余片段按字面量钉住，不做语义推断。 */
    private fun matches(template: String, text: String): Boolean = template.split('／').any { alt ->
        val pattern = alt.split('…').joinToString(".*") { part -> Pattern.quote(part) }
        Regex(pattern).containsMatchIn(text)
    }

    private val numberPattern = "(?:[一二三四五六七八九十两百千零]+|\\d+)"

    /** 数值闸：只认"数值＋单位"，不认孤立的"一"，否则"洇湿了一片"会被误杀。 */
    private fun numericFinding(text: String): Finding? {
        val patterns = listOf(
            "NUMERIC_TEMPERATURE" to Regex("(体温|尸温|温度)[^。；，！？]{0,12}$numberPattern\\s*(度|℃|°C)"),
            "NUMERIC_TIME" to Regex("(死后|死亡|身亡)[^。；，！？]{0,12}$numberPattern\\s*个?\\s*(小时|分钟|天)"),
            "NUMERIC_RIGOR" to Regex("(关节|僵硬|尸僵|硬了)[^。；，！？]{0,12}$numberPattern\\s*个?\\s*(小时|分钟|天)"),
            "NUMERIC_DOSE" to Regex("(吃|服|注射|用了)[^。；，！？]{0,12}$numberPattern\\s*(片|粒|毫升|克|mg)"),
        )
        val code = patterns.firstOrNull { it.second.containsMatchIn(text) }?.first ?: return null
        return Finding(code, "数值闸 $code：观察与相对时间都不给数")
    }

    private fun judge(text: String, voice: Voice, t: Tables): List<Finding> {
        val out = ArrayList<Finding>()
        val uncertainty = t.role.filter { it.category == "不确定性" }.map { it.token }
        val attribution = t.role.filter { it.category == "归属" }.map { it.token }
        val soft = t.role.filter { it.category == "软判断" }.map { it.token }
        val hedged = uncertainty.any { text.contains(it) }
        val attributed = attribution.any { text.contains(it) }

        t.forbid.forEach { rule ->
            if (matches(rule.template, text)) {
                out += when (voice) {
                    Voice.NARRATOR -> Finding(rule.id, "叙述者鉴定结论 ${rule.id}：${rule.template}")
                    Voice.CHARACTER -> if (hedged && attributed) {
                        Finding("ROLE_JUDGMENT", "角色判断 ${rule.id}：须读成他的判断（可推翻）")
                    } else {
                        Finding("ROLE_UNHEDGED", "角色说鉴定但缺不确定性或归属 ${rule.id}")
                    }
                }
            }
        }
        if (soft.any { text.contains(it) }) {
            out += when (voice) {
                Voice.NARRATOR -> Finding("NARRATOR_JUDGMENT", "叙述者写软判断：须读成他的判断才允许")
                Voice.CHARACTER -> if (hedged && attributed) {
                    Finding("ROLE_JUDGMENT", "角色软判断：须读成他的判断（可推翻）")
                } else {
                    Finding("ROLE_UNHEDGED", "角色软判断但缺不确定性或归属")
                }
            }
        }
        t.med.forEach { rule ->
            if (matches(rule.template, text)) out += Finding(rule.id, "医学处置双向禁 ${rule.id}：${rule.template}")
        }
        t.law.forEach { rule ->
            if (matches(rule.template, text)) out += Finding(rule.id, "法律结论双向禁 ${rule.id}：${rule.template}")
        }
        numericFinding(text)?.let { out += it }
        return out
    }

    private val verdictTokens = listOf(
        "死亡时间", "死因", "凶器", "溺死", "缢死", "他杀", "自杀",
        "尸僵", "尸斑", "尸温", "构成犯罪", "故意伤害",
    )

    private fun hasVerdictToken(text: String): Boolean = verdictTokens.any { text.contains(it) }

    /** 剥掉 Kotlin 行注释与块注释，只看生产码本身（KDoc 里写禁词不算越界）。 */
    private fun stripComments(source: String): String {
        var inBlock = false
        return source.lines().filter { line ->
            val trimmed = line.trimStart()
            when {
                inBlock -> {
                    if (trimmed.contains("*/")) inBlock = false
                    false
                }
                trimmed.startsWith("//") -> false
                trimmed.startsWith("/*") -> {
                    if (!trimmed.contains("*/")) inBlock = true
                    false
                }
                else -> true
            }
        }.joinToString("\n")
    }

    private fun source899(): List<String> {
        val candidates = listOf(
            File("src/main/java/com/example/zhengdao/baopu/state/injury"),
            File("app/src/main/java/com/example/zhengdao/baopu/state/injury"),
        )
        val dir = candidates.firstOrNull { it.isDirectory }
            ?: error("899 受伤谱目录没找到：${candidates.joinToString { it.absolutePath }}")
        return listOf("InjuryGrades.kt", "ImpactLink.kt", "ObservableLine.kt").map { File(dir, it).readText() }
    }

    @Test
    fun `表齐_能写四类禁写六条且模板全带占位`() {
        val t = tables()
        assertEquals("能写恰好四类", listOf("伤口", "现场", "身体", "时间"), t.allow.map { it.category }.distinct())
        assertEquals("能写表四行", 4, t.allow.size)
        assertEquals("不能写六条", listOf("F01", "F02", "F03", "F04", "F05", "F06"), t.forbid.map { it.id })
        assertTrue("模板必须可匹配整句：带…或／", t.forbid.all { it.template.contains('…') || it.template.contains('／') })
        assertTrue("判定列不许写成绿灯", t.forbid.all { it.verdict.contains("红") })
        assertEquals("角色标记三类", setOf("不确定性", "归属", "软判断"), t.role.map { it.category }.toSet())
        assertEquals("医学处置两个方向", setOf("要做", "不做"), t.med.map { it.direction }.toSet())
        assertEquals("法律结论两个方向", setOf("肯定", "否定"), t.law.map { it.direction }.toSet())
        println("FORENSIC-READING table allow=${t.allow.size} forbid=${t.forbid.size} role=${t.role.size}")
    }

    @Test
    fun `六条鉴定逐条咬住_删一行必有一条由红转绿`() {
        val t = tables()
        val cases = listOf(
            RedCase("F01", "死亡时间约三小时。", "F01"),
            RedCase("F02", "死因是失血过多。", "F02"),
            RedCase("F03", "凶器是锤类。", "F03"),
            RedCase("F04", "系溺死。", "F04"),
            RedCase("F05", "系缢死。", "F05"),
            RedCase("F06", "这是他杀。", "F06"),
            RedCase("F06B", "这是自杀。", "F06"),
        )
        val green = ArrayList<String>()
        cases.forEach { c ->
            if (judge(c.text, Voice.NARRATOR, t).none { it.code == c.ruleId }) green += c.id
        }
        println("FORENSIC-READING forbid red=${cases.size - green.size}/${cases.size} green=${green.joinToString(",")}")
        assertEquals("有禁写项没牙（删行变异应在这里转绿）：$green", emptyList<String>(), green)
    }

    @Test
    fun `两条核心夹具_叙述者红角色WARN`() {
        val t = tables()
        val fixture = rows("FORENSIC-FIXTURE-BEGIN", "FORENSIC-FIXTURE-END")
        assertEquals("核心夹具两条", 2, fixture.size)
        val k01 = fixture.single { it[0] == "K01" }
        val k02 = fixture.single { it[0] == "K02" }
        assertEquals("死亡时间约三小时。", k01[1])
        assertEquals("RED:F01", k01[3])
        assertEquals("法医初步看，不会太久，还要等报告。", k02[1])
        assertEquals("WARN:ROLE_JUDGMENT", k02[3])

        val narrator = judge(k01[1], Voice.NARRATOR, t)
        assertTrue("K01 叙述者必须红：F01", narrator.any { it.code == "F01" })
        val role = judge(k02[1], Voice.CHARACTER, t)
        assertFalse("K02 不许判红：$role", role.any { it.code != "ROLE_JUDGMENT" })
        assertTrue("K02 必须 WARN 且写明是他的判断：$role", role.any { it.code == "ROLE_JUDGMENT" && it.message.contains("他的判断") })
        println("FORENSIC-READING fixture narrator=RED:F01 role=WARN:ROLE_JUDGMENT")
    }

    @Test
    fun `角色口必须带不确定性与归属_缺一即红`() {
        val t = tables()
        assertTrue("无标记角色必须红", judge("死因是失血过多。", Voice.CHARACTER, t).any { it.code == "ROLE_UNHEDGED" })
        assertTrue("缺归属必须红", judge("初步看，死因是失血过多，还要等报告。", Voice.CHARACTER, t).any { it.code == "ROLE_UNHEDGED" })
        assertTrue("缺不确定性必须红", judge("法医说，死因是失血过多。", Voice.CHARACTER, t).any { it.code == "ROLE_UNHEDGED" })
        val ok = judge("法医初步看，死因是失血过多，还要等报告。", Voice.CHARACTER, t)
        assertEquals("齐全只 WARN：$ok", listOf("ROLE_JUDGMENT"), ok.map { it.code }.distinct())
        println("FORENSIC-READING role hedge+attribution=WARN else=RED")
    }

    @Test
    fun `禁数值_体温时间剂量一律不给数`() {
        val t = tables()
        val red = listOf(
            "摸着凉，体温三十八度。",
            "死后三小时才被发现。",
            "关节硬了四个小时。",
            "给他吃两片止痛药。",
        )
        red.forEach { text ->
            assertTrue("数值必须红：$text", judge(text, Voice.NARRATOR, t).any { it.code.startsWith("NUMERIC_") })
        }
        val green = listOf(
            "摸着手背凉，肘关节还能弯一点。",
            "雨下了半宿，天快亮时才停。",
            "伤口比硬币大一点，颜色发暗。",
        )
        green.forEach { text -> assertEquals("无数值不许误杀：$text", emptyList<Finding>(), judge(text, Voice.NARRATOR, t)) }
        println("FORENSIC-READING numeric red=${red.size}/${red.size} green=${green.size}/${green.size}")
    }

    @Test
    fun `医学处置双向禁`() {
        val t = tables()
        val samples = listOf("应该马上急救，先止血。", "给他吃一片药。", "别救了，不用处理伤口。", "别吃药。")
        samples.forEach { text ->
            assertTrue("医学处置双向红：$text", judge(text, Voice.NARRATOR, t).any { it.code.startsWith("M") })
        }
        assertEquals(
            "只写现象不误杀",
            emptyList<Finding>(),
            judge("渗血把袖口洇湿了一片，我没动他。", Voice.NARRATOR, t),
        )
        println("FORENSIC-READING medical red=${samples.size}/${samples.size} bothDirections=2/2")
    }

    @Test
    fun `法律结论双向禁`() {
        val t = tables()
        val samples = listOf(
            "这构成故意伤害罪。", "他有权正当防卫。", "他违法了。",
            "这不构成犯罪。", "他不违法。", "他没有责任。",
        )
        samples.forEach { text ->
            assertTrue("法律结论双向红：$text", judge(text, Voice.NARRATOR, t).any { it.code.startsWith("L") })
        }
        assertEquals(
            "只写行为不误杀",
            emptyList<Finding>(),
            judge("他伸手去拿桌上的杯子，没再说话。", Voice.NARRATOR, t),
        )
        println("FORENSIC-READING legal red=${samples.size}/${samples.size} bothDirections=2/2")
    }

    @Test
    fun `能写四类样例全绿_误杀负例`() {
        val t = tables()
        t.allow.forEach { row ->
            val found = judge(row.sample, Voice.NARRATOR, t)
            assertEquals("能写的样例被误杀：${row.id} $found", emptyList<Finding>(), found)
        }
        println("FORENSIC-READING allow green=${t.allow.size}/${t.allow.size}")
    }

    @Test
    fun `合规负例全绿_角色判断只WARN`() {
        val t = tables()
        val all = rows("FORENSIC-COMPLIANT-BEGIN", "FORENSIC-COMPLIANT-END")
        var green = 0
        var warn = 0
        all.forEach { c ->
            val voice = if (c[2] == "角色") Voice.CHARACTER else Voice.NARRATOR
            val found = judge(c[1], voice, t)
            when (c[3]) {
                "GREEN" -> {
                    green++
                    assertEquals("误杀合规写法：${c[0]} $found", emptyList<Finding>(), found)
                }
                "WARN" -> {
                    warn++
                    assertTrue("角色判断只 WARN：${c[0]} $found", found.any { it.code == "ROLE_JUDGMENT" })
                    assertFalse("WARN 不许判红：${c[0]}", found.any { it.code != "ROLE_JUDGMENT" })
                }
                else -> fail("未知期望 ${c[3]}")
            }
        }
        println("FORENSIC-READING compliant green=$green warn=$warn total=${all.size}")
    }

    @Test
    fun `叙述者不以事实口吻复述角色判断_剧情替判断作证也红`() {
        val t = tables()
        assertTrue(
            "叙述者写软判断必须红",
            judge("法医初步看，不会太久，还要等报告。", Voice.NARRATOR, t).any { it.code == "NARRATOR_JUDGMENT" },
        )
        assertTrue(
            "剧情替判断作证必须红",
            judge("后来证明法医说得对，死者系溺死。", Voice.NARRATOR, t).any { it.code == "F04" },
        )
        println("FORENSIC-READING narratorAppropriation=RED plotValidation=RED")
    }

    @Test
    fun `自检_删一行禁写模板对应夹具必须由红转绿`() {
        val t = tables()
        val target = t.forbid.single { it.id == "F03" }
        assertTrue("基线必须红", judge("凶器是锤类。", Voice.NARRATOR, t).any { it.code == target.id })
        val dropped = t.copy(forbid = t.forbid.filter { it.id != target.id })
        assertFalse("删行后必须转绿", judge("凶器是锤类。", Voice.NARRATOR, dropped).any { it.code == target.id })
        println("FORENSIC-READING mutation selfcheck ${target.id}: RED->GREEN")
    }

    @Test
    fun `与899联动_受伤谱只写现象与分级`() {
        val sources = source899()
        assertEquals("899 三文件", 3, sources.size)
        sources.forEachIndexed { index, source ->
            assertFalse("899 生产码不许有鉴定词（文件 $index）", hasVerdictToken(stripComments(source)))
        }
        assertTrue("判据自身正例", hasVerdictToken("死因是失血过多"))
        assertFalse("判据自身负例", hasVerdictToken("局部颜色与周围不同"))
        println("FORENSIC-READING 899 verdictTokens=0 files=${sources.size}")
    }
}
