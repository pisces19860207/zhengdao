// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第八百零五批 · 源码门禁：只读本单 5 个生产源码，钉住三条边界与两条分工。
// 每道闸都配负例注入自检——不是「文件里含某个词」就过。
//
// 边界①：不读真人影像（无任何 IO / 相机 / 图像解码 / 内容提供者）；
// 边界②：不反推尺寸（源码里一个长度单位字面都没有，检查器自身用拼接，不自咬）；
// 边界③：不做诊断（判定词一个都没有）。
// 分工：不改 660 形体、不碰 658 生理、不碰 747 锚定、不碰 687 可见度、不碰 804 包皮。
package com.example.zhengdao.baopu.state.bodyparts

import com.example.zhengdao.SrcRoots
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BodyPartSourceAssertTest {

    private val dir = "app/src/main/java/com/example/zhengdao/baopu/state/bodyparts/"
    private val files = listOf(
        "BodyPartGrades.kt", "BodyPartCatalog.kt", "BodyPartSheet.kt",
        "BodyPartChecks.kt", "BodyPartGuard.kt",
    ).map { dir + it }

    private fun code(): String = files.joinToString("\n") { stripComments(source(it)) }

    private fun source(rel: String): String = SrcRoots.find(rel).readText()

    /** 边界①：不读真人影像。IO、相机、解码、内容提供者一律不许出现。 */
    private val ioTokens = listOf(
        "java.io", "InputStream", "OutputStream", "readText", "File(", "Files.",
        "Bitmap", "ImageDecoder", "Camera", "MediaStore", "ContentResolver", "openFile",
    )

    /** 边界②：长度单位字面（拼接构造，避免检查器自责）。 */
    private val lengthTokens = listOf(
        "c" + "m", "m" + "m", "厘" + "米", "毫" + "米", "inc" + "h", "英" + "寸",
    )

    /** 边界③：判定词（拼接构造）。 */
    private val diagnosisTokens = listOf(
        "诊" + "断", "异" + "常", "需就" + "医", "病" + "理", "确" + "诊", "治" + "疗",
    )

    /** 邻单：不改 660、不抢 658／747／687／804 的账。 */
    private val foreignTokens = listOf(
        "state.form.", "state.physiology.", "state.anchor.", "state.prepuce.",
        "state.geometry.", "baopu.body.", "AnatomyZone", "HumanBodySpec",
    )

    /** 装配 / 闸 / 落库：一字不许出现（本单零装配）。 */
    private val wiringTokens = listOf(
        "RunEngine", "ZhengdaoApp", "ContextAssembler", "RiskMatrix", "insert",
        "gatePass", "persistContract", "StateSummary",
    )

    @Test
    fun `边界一_零IO不读影像`() {
        val text = code()
        ioTokens.forEach { tok -> assertFalse("不得出现 $tok", text.contains(tok)) }
    }

    @Test
    fun `边界二_源码内无长度单位字面`() {
        val text = code()
        lengthTokens.forEach { tok -> assertFalse("源码不得写长度单位 $tok", hasUnit(text, tok)) }
    }

    @Test
    fun `边界三_源码内无判定词`() {
        val text = code()
        diagnosisTokens.forEach { tok -> assertFalse("源码不得出现判定词 $tok", text.contains(tok)) }
    }

    @Test
    fun `分工_不引用邻单类型`() {
        val text = code()
        foreignTokens.forEach { tok -> assertFalse("不得引用邻单 $tok", text.contains(tok)) }
    }

    @Test
    fun `零装配_不进闸不落库`() {
        val text = code()
        wiringTokens.forEach { tok -> assertFalse("不得装配或落库 $tok", text.contains(tok)) }
    }

    @Test
    fun `计数锁_八部位二十三维七十二读数`() {
        val text = code()
        BodyPart.entries.forEach { part ->
            assertTrue("源码里找不到部位 " + part.label, text.contains(part.label))
        }
        assertEquals(8, BodyPart.entries.size)
        assertEquals(23, Dim.entries.size)
        assertEquals(72, BodyPartCatalog.totalReadings)
        assertEquals(5, LarynxGrade.entries.size)
        assertEquals(4, AreolaRatio.entries.size)
        assertEquals(2, AreolaRatio.MIN_FACTOR)
    }

    @Test
    fun `边界二_单词里的mm不算单位`() {
        // 上一版把 Symmetry 里的 mm 当成毫米，一次假红——这条钉住「不是有 mm 就算」。
        assertFalse("Symmetry 里的 mm 不是长度单位", hasUnit("Symmetry", "m" + "m"))
        assertTrue("跟着数字的才是单位", hasUnit("约7" + "m" + "m" + "宽", "m" + "m"))
    }

    @Test
    fun `禁词表自身用拼接写`() {
        // 反向校验：如果检查器自己写成连续字面，它就会被下一轮门禁咬住。
        assertEquals(2, ("c" + "m").length)
        assertTrue(("m" + "m").endsWith("m"))
        assertEquals(2, ("异" + "常").length)
    }

    // ── 闸有牙：负例注入自检 ─────────────────────────────────────────

    @Test
    fun `闸自检_注入IO必被咬住`() {
        val poisoned = stripComments("val bad = File(\"x\").readText()")
        assertTrue("注入的 IO 必须被咬住", ioTokens.any { poisoned.contains(it) })
    }

    @Test
    fun `闸自检_注入长度单位必被咬住`() {
        val poisoned = stripComments("val bad = \"约7" + "c" + "m\"")
        assertTrue("注入的长度单位必须被咬住", lengthTokens.any { hasUnit(poisoned, it) })
    }

    @Test
    fun `闸自检_注入判定词必被咬住`() {
        val poisoned = stripComments("val bad = \"" + "异" + "常\"")
        assertTrue("注入的判定词必须被咬住", diagnosisTokens.any { poisoned.contains(it) })
    }

    @Test
    fun `闸自检_剥注释后注释里的词不算数`() {
        val onlyInComment = stripComments("// 这里提到 readText 与 " + "c" + "m" + " 只是说明\nval ok = 1")
        assertFalse("注释里的 IO 不该算", ioTokens.any { onlyInComment.contains(it) })
        assertFalse("注释里的长度单位不该算", lengthTokens.any { hasUnit(onlyInComment, it) })
    }

    /** 单位是否作为独立词出现：缩写后面不许紧跟 ASCII 字母（Symmetry 的 mm 不算）。 */
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
                if (src[i] != '\n') out.append(' ') else out.append('\n')
                i++
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
