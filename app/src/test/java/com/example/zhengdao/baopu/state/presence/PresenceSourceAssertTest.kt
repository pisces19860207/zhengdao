// 独立开发声明：本文件为本项目原创，不含第三方源码的拷贝。
//
// 第八百九十六批 · 源码门禁：只读本单四个生产文件，剥注释后扫 token。
// PresenceGuard.kt 不进扫描清单：它是唯一持有违禁词表之处，进去必然自咬（与 810 同口径）。
// 每道闸都配负例注入自检——不是「文件里含某个词」就过。
package com.example.zhengdao.baopu.state.presence

import com.example.zhengdao.SrcRoots
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PresenceSourceAssertTest {

    private val dir = "app/src/main/java/com/example/zhengdao/baopu/state/presence/"
    private val files = listOf("PresenceGrades.kt", "PresenceCatalog.kt", "PresenceReading.kt", "PresenceChecks.kt")

    private fun source(name: String): String = SrcRoots.find(dir + name).readText()

    private fun code(): String = files.joinToString("\n") { stripComments(source(it)) }

    private val ioTokens = listOf(
        "java.io", "InputStream", "OutputStream", "readText", "File(", "Files.",
        "Bitmap", "ImageDecoder", "Camera", "MediaStore", "ContentResolver", "openFile",
    )

    private val wiringTokens = listOf(
        "RunEngine", "ZhengdaoApp", "ContextAssembler", "RiskMatrix", "insert(",
        "gatePass", "BaopuSql", "StateSummary", "currentTimeMillis", "Random(",
    )

    private val measureTokens = listOf("%", "％", "百分比", "百分之", "平方", "厘" + "米", "毫" + "米")

    /** 这些词只允许出现在 Guard 的词表里；判据文字一个都不许带。 */
    private val languageTokens = listOf(
        "不该" + "帮", "有" + "问题", "不能给" + "女儿", "不能给" + "儿子",
        "性别" + "设限", "必须穿" + "整齐", "都" + "保守", "都不" + "在乎",
    )

    /** 邻单零改动：既不引用，也不提前实现。 */
    private val foreignTokens = listOf(
        "state.garment.", "state.hygiene.", "state.prepuce.", "state.casting.",
        "state.form.", "state.bodyparts.", "state.topography.", "state.causal.",
    )

    private fun violations(text: String): List<String> {
        val hits = mutableListOf<String>()
        ioTokens.filterTo(hits) { it in text }
        wiringTokens.filterTo(hits) { it in text }
        measureTokens.filterTo(hits) { it in text }
        languageTokens.filterTo(hits) { it in text }
        foreignTokens.filterTo(hits) { it in text }
        return hits
    }

    @Test
    fun `零IO零装配零邻单引用`() {
        val text = code()
        assertTrue(violations(text).toString(), violations(text).isEmpty())
        val imports = text.lines().filter { it.trimStart().startsWith("import ") }
        assertTrue("本包不该需要任何跨包 import", imports.isEmpty())
    }

    @Test
    fun `源码内无长度与百分比`() {
        val text = code()
        listOf("c" + "m", "m" + "m").forEach { tok ->
            assertFalse("源码不得写长度单位", hasUnit(text, tok))
        }
        measureTokens.forEach { tok -> assertFalse("源码不得出现 $tok", tok in text) }
    }

    @Test
    fun `DENY只许两处_红条件只许两条`() {
        val denyLines = denyViolations()
        assertTrue(denyLines.toString(), denyLines.isEmpty())
        val checks = stripComments(source("PresenceChecks.kt"))
        assertEquals(2, countIn(checks, "PresenceVerdict.DENY"))
        assertEquals(2, countIn(checks, "RedRule."))
        assertEquals(2, countIn(checks, "s.sexual != SexualSignal.NONE"))
        assertEquals(2, PresenceChecks.RedRule.COUNT)
    }

    @Test
    fun `参考矩阵不参与判定`() {
        val checks = stripComments(source("PresenceChecks.kt"))
        assertFalse("判据不得读参考矩阵", "PresenceCatalog.matrix(" in checks)
        assertFalse("判据不得读参考矩阵展示行", "referenceLine(" in checks)
    }

    @Test
    fun `计数锁_枚举十五数据类五文件四`() {
        val text = code()
        assertEquals(4, files.size)
        assertEquals(15, countIn(text, "enum class"))
        assertEquals(5, countIn(text, "data class"))
        assertEquals(6, Venue.entries.size)
        assertEquals(4, CanBeNude.entries.size)
        assertEquals(3, MinorPresence.entries.size)
        assertEquals(5, AgeTier.entries.size)
    }

    // ── 闸有牙：负例注入自检 ─────────────────────────────────────────

    @Test
    fun `闸自检_注入IO必被咬住`() {
        assertTrue(violations("val bad = File(\"x\").readText()").isNotEmpty())
        assertTrue(violations("Bitmap Camera MediaStore").isNotEmpty())
    }

    @Test
    fun `闸自检_注入装配与邻单必被咬住`() {
        assertTrue(violations("RiskMatrix.insert(...)").isNotEmpty())
        assertTrue(violations("state.garment.GarmentSheet").isNotEmpty())
    }

    @Test
    fun `闸自检_注入长度与百分比必被咬住`() {
        assertTrue(hasUnit("约7" + "c" + "m", "c" + "m"))
        assertTrue(violations("20%").isNotEmpty())
    }

    @Test
    fun `闸自检_注入污名化语言必被咬住`() {
        assertTrue(violations("父亲" + "不能给女儿" + "洗澡").isNotEmpty())
        assertTrue(violations("不该" + "帮孩子洗澡").isNotEmpty())
        assertTrue(violations("某地人都" + "保守").isNotEmpty())
    }

    @Test
    fun `闸自检_注入第三处DENY必被咬住`() {
        val injected = files.associateWith { stripComments(source(it)) }.toMutableMap()
        injected["PresenceChecks.kt"] = injected.getValue("PresenceChecks.kt") +
            "\nval extra = PresenceVerdict.DENY"
        assertTrue(denyViolations(injected).isNotEmpty())
    }

    @Test
    fun `闸自检_注入矩阵调用必被咬住`() {
        val injected = stripComments(source("PresenceChecks.kt")) + "\nval x = PresenceCatalog.matrix()"
        assertFalse("referenceLine(" in injected)
        assertTrue("PresenceCatalog.matrix(" in injected)
    }

    @Test
    fun `闸自检_剥注释后注释里的词不算数`() {
        val onlyInComment = stripComments(
            "// 这里提到 readText 与 " + "c" + "m" + " 与 RiskMatrix 只是说明\nval ok = 1",
        )
        assertTrue(violations(onlyInComment).isEmpty())
    }

    /** 返回 DENY 字面量计数异常的文件：Checks 恰 2 处，其余 0 处。 */
    private fun denyViolations(sources: Map<String, String> = files.associateWith { stripComments(source(it)) }): List<String> =
        files.filter { name ->
            val expected = if (name == "PresenceChecks.kt") 2 else 0
            countIn(sources.getValue(name), "PresenceVerdict.DENY") != expected
        }

    private fun hasUnit(text: String, unit: String): Boolean {
        var i = text.indexOf(unit, ignoreCase = true)
        while (i >= 0) {
            val after = i + unit.length
            val asciiLetter = after < text.length &&
                (text[after] in 'a'..'z' || text[after] in 'A'..'Z')
            if (!asciiLetter) return true
            i = text.indexOf(unit, i + 1, ignoreCase = true)
        }
        return false
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

    /** 行/块注释整段换空格（保留换行）；字符串原样保留。Kotlin 块注释可嵌套。 */
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
}
