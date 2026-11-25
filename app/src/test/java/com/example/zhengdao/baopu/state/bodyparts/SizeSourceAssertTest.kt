// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第九百二十批 · 源码门禁：只读本单 6 个生产源码，钉住「四向全禁」与五条口径。
// 每道闸都配负例注入自检——不是「文件里含某个词」就过。
//
// 判据②（四向全禁）在这里落成三件硬约束：
// ① 装配点 SizeGate.kt 里 `context.` 只许出现一次（成年闸），出现第二次就红；
// ② 装配点不许出现 stature／mass／skeleton／ageYears 任何一个字段名；
// ③ 全包不许出现因果连接词与全角等号（「胖＝小」这类句子连字面都写不进来）。
package com.example.zhengdao.baopu.state.bodyparts

import com.example.zhengdao.SrcRoots
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SizeSourceAssertTest {

    private val dir = "app/src/main/java/com/example/zhengdao/baopu/state/bodyparts/"
    private val files = listOf(
        "SizeGrades.kt", "SizeProfile.kt", "SizeContext.kt",
        "SizeGate.kt", "SizeRules.kt", "SizeGuard.kt",
    ).map { dir + it }

    private val gateFile = dir + "SizeGate.kt"

    private fun source(rel: String): String = SrcRoots.find(rel).readText()

    private fun code(): String = files.joinToString("\n") { stripComments(source(it)) }

    private fun gateCode(): String = stripComments(source(gateFile))

    /** 边界①：读影像、开文件、解码图片一律不许。 */
    private val ioTokens = listOf(
        "java.io", "InputStream", "OutputStream", "readText", "File(", "Files.",
        "Bitmap", "ImageDecoder", "Camera", "MediaStore", "ContentResolver", "openFile",
    )

    /** 边界②（805 沿用）：长度单位（拼接构造，免得检查器自责）。 */
    private val lengthTokens = listOf(
        "c" + "m", "m" + "m", "厘" + "米", "毫" + "米", "inc" + "h", "英" + "寸",
    )

    /** 判据⑦：围度、百分比、体脂率一律不许——本单只给档。 */
    private val measureTokens = listOf(
        "%", "％", "百分比", "平方", "围" + "度", "体脂" + "率", "b" + "mi",
    )

    /** 边界③：判定词（拼接构造）。 */
    private val diagnosisTokens = listOf(
        "诊" + "断", "异" + "常", "需就" + "医", "病" + "理", "确" + "诊", "治" + "疗",
    )

    /** 零装配／零落库：本包不产工具、不进闸、不写库、不取时钟与随机数。 */
    private val wiringTokens = listOf(
        "RunEngine", "ZhengdaoApp", "ContextAssembler", "RiskMatrix", "insert",
        "gatePass", "BaopuSql", "StateSummary", "currentTimeMillis", "Random",
    )

    /** 邻单：不改 660／775／731／866，也不把 805 的部位单塞进来。 */
    private val foreignTokens = listOf(
        "state.form.", "state.subzone.", "state.thermo.", "state.zone.",
        "state.topography.", "state.prepuce.", "state.geometry.", "state.anchor.",
        "AnatomyZone", "HumanBodySpec", "BodyPartCatalog", "BodyParts(",
    )

    /** 判据②的字段名：装配点里一个都不许出现。 */
    private val identityTokens = listOf("stature", "mass", "skeleton", "ageYears", "weight")

    /** 判据④：因果连接词与全角等号，全包字面都不许有。 */
    private val causationLiterals = listOf("所" + "以", "因" + "此", "导" + "致", "\uFF1D")

    @Test
    fun `边界一_零IO不读影像`() {
        val text = code()
        ioTokens.forEach { tok -> assertFalse("不得出现 $tok", text.contains(tok)) }
    }

    @Test
    fun `边界二_源码内无长度单位`() {
        val text = code()
        lengthTokens.forEach { tok -> assertFalse("源码不得写长度单位 $tok", hasUnit(text, tok)) }
    }

    @Test
    fun `判据七_源码内无围度与百分比`() {
        val text = code().lowercase()
        measureTokens.forEach { tok -> assertFalse("源码不得出现 $tok", text.contains(tok.lowercase())) }
    }

    @Test
    fun `边界三_源码内无判定词`() {
        val text = code()
        diagnosisTokens.forEach { tok -> assertFalse("源码不得出现判定词 $tok", text.contains(tok)) }
    }

    @Test
    fun `判据四_源码内无因果句字面`() {
        val text = code()
        causationLiterals.forEach { tok -> assertFalse("源码不得写因果连接词 $tok", text.contains(tok)) }
    }

    @Test
    fun `零装配_不进闸不落库不碰邻单`() {
        val text = code()
        wiringTokens.forEach { tok -> assertFalse("不得装配或落库 $tok", text.contains(tok)) }
        foreignTokens.forEach { tok -> assertFalse("不得引用邻单 $tok", text.contains(tok)) }
    }

    @Test
    fun `判据二_装配点只读成年闸一处`() {
        val gate = gateCode()
        assertEquals("SizeGate 里 context. 只许出现一次（成年闸）", 1, countIn(gate, "context."))
        identityTokens.forEach { tok ->
            assertFalse("装配点不得读体格字段 $tok", gate.contains(tok))
        }
        assertTrue("成年闸必须真的读一次", gate.contains("context.adult"))
    }

    @Test
    fun `判据六_颜色两层是两个字段`() {
        val text = code()
        assertTrue("色变档字段", text.contains("val colorShift:"))
        assertTrue("色档字段", text.contains("val colorTier:"))
        assertFalse("不许出现合体的单字段颜色", hasMergedColorField(text))
        assertFalse("不许新造第二个色档枚举", text.contains("enum class StaticTone"))
    }

    @Test
    fun `判据五_源码里没有未成年段`() {
        val text = code()
        assertFalse("本包不写未成年段", text.contains("未成年"))
        assertTrue("成年闸常量在上下文里", code().contains("ADULT_MIN"))
    }

    @Test
    fun `计数锁_七枚举六数据类三出口`() {
        val text = code()
        assertEquals(6, files.size)
        assertEquals(7, countIn(text, "enum class"))
        assertEquals(6, countIn(text, "data class"))
        assertEquals(3, countIn(text, "object Size"))
        assertEquals(4, BuriedLook.entries.size)
        assertEquals(4, LengthTier.entries.size)
        assertEquals(4, GlansTier.entries.size)
        assertEquals(3, DevelopmentTiming.entries.size)
        assertEquals(3, SizeSource.entries.size)
        assertEquals(48, IndependenceMatrix.CELLS)
        assertEquals(18, BodyContext.ADULT_MIN)
    }

    // ── 闸有牙：负例注入自检 ─────────────────────────────────────────

    @Test
    fun `闸自检_注入身高推导必被咬住`() {
        val poisoned = stripComments(
            "fun resolve(p: SizeProfile, context: BodyContext): SizedLayer =\n" +
                "    if (context.adult && context.stature > 180) SizedLayer.Present(p) " +
                "else SizedLayer.Absent(\"x\")",
        )
        assertEquals("成年闸之外又读了一次 context.", 2, countIn(poisoned, "context."))
        assertTrue("体格字段必须被咬住", identityTokens.any { poisoned.contains(it) })
    }

    @Test
    fun `闸自检_注入体重年龄骨架同一把尺`() {
        listOf("mass", "skeleton", "ageYears").forEach { field ->
            val poisoned = stripComments("if (context.$field > 0) { }")
            assertTrue("$field 必须被咬住", identityTokens.any { poisoned.contains(it) })
            assertEquals("一次读 context. 才对", 1, countIn(poisoned, "context."))
        }
    }

    @Test
    fun `闸自检_注入因果句必被咬住`() {
        val poisoned = stripComments("val bad = \"" + "胖" + "所" + "以" + "小\"")
        assertTrue("因果连接词必须被咬住", causationLiterals.any { poisoned.contains(it) })
    }

    @Test
    fun `闸自检_注入合体颜色字段必被咬住`() {
        val poisoned = stripComments("data class Bad(val color: Int)")
        assertTrue("合体字段必须被咬住", hasMergedColorField(poisoned))
    }

    @Test
    fun `闸自检_剥注释后注释里的词不算数`() {
        val onlyInComment = stripComments(
            "// 注释里提到 stature 与 " + "所" + "以" + " 与 未成年，都不算\nval ok = 1",
        )
        assertFalse(identityTokens.any { onlyInComment.contains(it) })
        assertFalse(causationLiterals.any { onlyInComment.contains(it) })
        assertFalse(onlyInComment.contains("未成年"))
    }

    @Test
    fun `闸自检_单词里的mm不算单位`() {
        assertFalse("标识符里的 mm 不是长度单位", hasUnit("Symmetry", "m" + "m"))
        assertTrue("跟着数字的才是单位", hasUnit("约7" + "m" + "m" + "宽", "m" + "m"))
    }

    /** 单位是否作为独立词出现：缩写后面不许紧跟 ASCII 字母。 */
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

    /** 判据⑥的检法：有合体单字段（val color:）且没有分层的两字段。 */
    private fun hasMergedColorField(src: String): Boolean =
        src.contains("val color:") && !src.contains("val colorShift:")

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
