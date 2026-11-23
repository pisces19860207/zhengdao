// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第八百八十二批 · 源码门禁：只读本包 17 个生产源码，钉住四条边界。
//
// ① 判据⑥：纯枚举与表——不许 IO、不许随机、不许读时钟、不许落库、不许装配；
// ② 判据⑤：不改邻单——不许出现 881／883 的字段名与类型名，也不许 import 任何别处（本包零跨包依赖）；
// ③ 判据②④⑧＋2026-11-24 补充更正：OccWords 的五张词表在**剥掉注释后的代码文本**里一个都不许命中——
//    唯一豁免件是 OccWords.kt 本身（词表的作用就是把词写出来），而它反过来被钉成「只许有清单，不许有逻辑与表体」；
// ④ 计数锁：17 个文件、59 个枚举、15 个数据类、4 个 object；改一个档位就得同时改这里。
//
// 注释为什么能提「卫生」「清洁」：stripComments 只把注释换成空格，字符串原样保留——
// 边界说明留在注释里，判据词在代码里一个都不许活。
package com.example.zhengdao.baopu.state.occupation

import com.example.zhengdao.SrcRoots
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OccSourceAssertTest {

    private val dir = "app/src/main/java/com/example/zhengdao/baopu/state/occupation/"

    private val files = listOf(
        "OccIndustry.kt", "OccGrades.kt", "OccProfiles.kt", "OccDrive.kt", "OccService.kt",
        "OccFaith.kt", "OccPhase2.kt", "OccupationRow.kt", "OccRows.kt", "OccTableTrade.kt",
        "OccTableService.kt", "OccTableCivilian.kt", "OccTableUniform.kt", "OccTableFaith.kt",
        "OccTable.kt", "OccChecks.kt", "OccWords.kt",
    ).map { dir + it }

    /** 词表豁免件：只有它可以写出禁词本身。 */
    private val wordFile = dir + "OccWords.kt"

    private fun code(): String = files.filter { it != wordFile }.joinToString("\n") { stripped(it) }

    private fun stripped(rel: String): String = stripComments(SrcRoots.find(rel).readText())

    private fun raw(rel: String): String = SrcRoots.find(rel).readText()

    /** 边界①：IO／随机／时钟／落库／装配。本包是纯表，一个都不许有。 */
    private val ioTokens = listOf(
        "java.io", "java.util", "InputStream", "OutputStream", "readText", "File(", "Files.",
        "Random", "Math.rando" + "m", "currentTimeMillis", "nanoTime", "SharedPreferences",
        "Context", "insert", "query(", "RunEngine", "ZhengdaoApp", "RiskMatrix", "gatePass",
    )

    /** 边界②：881／883 的字段与档名——本包只写中文去处，不许抢邻单的 API。 */
    private val neighbourTokens = listOf(
        "hygieneHabit", "washCondition", "smegmaTier", "flyType", "innerLayer", "wornness",
        "openingStyle", "zipBehavior", "fabricStiffness", "TorsoShape", "FatBand", "BodyPartGuard",
        "import com.example.zhengdao.",
    )

    /** 禁 `@Suppress`：绕闸不是清理。 */
    private val suppressTokens = listOf("@Suppre" + "ss", "detekt:", "lint:")

    @Test
    fun `边界一_零IO零随机零时钟`() {
        val text = code()
        ioTokens.forEach { tok -> assertFalse("不得出现 $tok", text.contains(tok)) }
    }

    @Test
    fun `边界二_不引用邻单字段也不跨包依赖`() {
        val text = code()
        neighbourTokens.forEach { tok -> assertFalse("不得引用邻单 $tok", text.contains(tok)) }
    }

    @Test
    fun `边界三_禁词在代码里一个都不许活`() {
        val text = code()
        (OccWords.STEREOTYPE + OccWords.HYGIENE + OccWords.FAITH_WORDS + OccWords.MOCK + OccWords.DISEASE)
            .forEach { word -> assertFalse("代码文本里出现了「$word」", text.contains(word)) }
    }

    @Test
    fun `边界三后半_涉密词表在代码里也不许活`() {
        val text = code()
        OccWords.SECRET.forEach { word -> assertFalse("代码文本里出现了「$word」", text.contains(word)) }
    }

    @Test
    fun `豁免件只能是词表不能有逻辑`() {
        val words = stripped(wordFile)
        assertFalse("词表件里不许有函数", words.contains("fun "))
        assertFalse("词表件里不许造行", words.contains("occRow("))
        assertFalse("词表件里不许有检查器", words.contains("OccFinding("))
        assertFalse("词表件里不许有 if", words.contains("if ("))
        assertEquals(1, countIn(words, "internal object OccWords"))
        assertEquals(8, countIn(words, "    val "))
        // 反向也要成立：豁免件确实带着这些词，否则整条边界三就成了空枪。
        listOf("脏", "卫生", "清洁", "爱干净", "铁血", "番号", "劳损", "歧视").forEach {
            assertTrue("词表件里应当有「$it」", words.contains(it))
        }
    }

    @Test
    fun `禁绕过_不许抑制告警`() {
        val text = code()
        suppressTokens.forEach { tok -> assertFalse("不许绕闸 $tok", text.contains(tok)) }
    }

    @Test
    fun `计数锁_十七文件五十九枚举十五数据类四个对象`() {
        assertEquals(17, files.size)
        val text = code() + stripped(wordFile)
        assertEquals(59, countIn(text, "enum class"))
        assertEquals(15, countIn(text, "data class"))
        listOf("object OccupationGate", "object OccupationTable", "object EraTable", "object OccWords").forEach {
            assertEquals("应当正好一个 $it", 1, countIn(text, it))
        }
    }

    @Test
    fun `表体只在五张分表里`() {
        // 分表里 70 次 occRow(；其余 17 个文件加起来只许有 OccRows.kt 的那一句声明。
        assertEquals(70, countIn(hostsText(), "occRow("))
        assertEquals(1, countIn(outsideText(), "occRow("))
    }

    @Test
    fun `判据号不许写进分表`() {
        // 判据只在 OccChecks 里说话；分表负责写事实，不负责自我表扬「本行满足 C4」。
        assertFalse("分表里不许出现规则号", hostsText().contains("\"C"))
        assertFalse("分表里不许引用检查器", hostsText().contains("OccupationGate"))
    }

    // ── 闸有牙：注入自检 ───────────────────────────────────────

    @Test
    fun `闸自检_注入IO必被咬住`() {
        assertTrue(ioTokens.any { stripComments("val bad = Fi" + "le(\"x\").rea" + "dText()").contains(it) })
        assertTrue(ioTokens.any { stripComments("val bad = kotlin.ran" + "dom.Random()").contains(it) })
        assertTrue(ioTokens.any { stripComments("val bad = System.cur" + "rentTimeMillis()").contains(it) })
    }

    @Test
    fun `闸自检_注入邻单字段名必被咬住`() {
        assertTrue(neighbourTokens.any { stripComments("val bad = state.hyg" + "ieneHabit").contains(it) })
        assertTrue(neighbourTokens.any { stripComments("import com.example." + "zhengdao.baopu.state.form.X").contains(it) })
    }

    @Test
    fun `闸自检_注入禁词必被咬住`() {
        listOf("脏", "爱干净", "铁血", "服从", "素质", "番号", "劳损", "歧视", "洁癖").forEach { word ->
            val poisoned = stripComments("val bad = \"岗位是" + word + "\"")
            assertTrue("$word 必须能被代码文本抓到", poisoned.contains(word))
        }
    }

    @Test
    fun `闸自检_注释里的词不算数`() {
        val onlyInComment = stripComments("// 这一格谈卫生与清洁，只是说明\nval ok = 1")
        assertFalse(OccWords.HYGIENE.any { onlyInComment.contains(it) })
        assertTrue(raw(wordFile).contains("双向"))
    }

    /** 五张分表（表体唯一允许写 occRow 的地方）。 */
    private val hostNames = listOf(
        "OccTableTrade.kt", "OccTableService.kt", "OccTableCivilian.kt", "OccTableUniform.kt", "OccTableFaith.kt",
    )

    private fun hostsText(): String = hostNames.map { dir + it }.joinToString("\n") { stripped(it) }

    private fun outsideText(): String =
        files.filter { it != wordFile && hostNames.none { name -> it.endsWith(name) } }
            .joinToString("\n") { stripped(it) }

    /** 行／块注释整段换空格（保留换行）；字符串原样保留。Kotlin 块注释可嵌套。 */
    private fun stripComments(src: String): String {
        val out = StringBuilder(src.length)
        var i = 0
        while (i < src.length) {
            val two = if (i + 1 < src.length) src.substring(i, i + 2) else ""
            when {
                two == "//" -> i = skipLine(src, i, out)
                two == "/*" -> i = skipBlock(src, i, out)
                src[i] == '"' -> i = copyString(src, i, out)
                else -> {
                    out.append(src[i]); i++
                }
            }
        }
        return out.toString()
    }

    private fun skipLine(src: String, start: Int, out: StringBuilder): Int {
        var i = start + 2
        out.append("  ")
        while (i < src.length && src[i] != '\n') {
            out.append(' '); i++
        }
        return i
    }

    private fun skipBlock(src: String, start: Int, out: StringBuilder): Int {
        var i = start + 2
        var depth = 1
        out.append("  ")
        while (i < src.length && depth > 0) {
            val two = if (i + 1 < src.length) src.substring(i, i + 2) else ""
            if (two == "/*") {
                depth++; out.append("  "); i += 2
            } else if (two == "*/") {
                depth--; out.append("  "); i += 2
            } else {
                out.append(if (src[i] == '\n') '\n' else ' '); i++
            }
        }
        return i
    }

    private fun copyString(src: String, start: Int, out: StringBuilder): Int {
        var i = start
        out.append(src[i]); i++
        while (i < src.length) {
            out.append(src[i])
            if (src[i] == '\\' && i + 1 < src.length) {
                out.append(src[i + 1]); i += 2; continue
            }
            if (src[i] == '"') {
                i++; break
            }
            i++
        }
        return i
    }

    private fun countIn(text: String, needle: String): Int {
        var i = text.indexOf(needle)
        var n = 0
        while (i >= 0) {
            n++
            i = text.indexOf(needle, i + needle.length)
        }
        return n
    }
}
