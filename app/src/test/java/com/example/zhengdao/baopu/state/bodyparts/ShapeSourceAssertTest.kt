// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第九百三十五批 · 源码门禁：只读本单 5 个生产源码，钉住六条边界与四张计数锁。
// 每道闸都配负例注入自检——不是「文件里含某个词」就过。
//
// 六条边界：
// ① 零 IO（不读影像、不读文件、不碰内容提供者）；
// ② 无长度单位字面（判据⑥ 禁数值）；
// ③ 无诊断词（生育力那类判断不许下）；
// ④ 不引用邻单类型（810/868/911/920/924/747 的包一个都不 import）；
// ⑤ 零装配（不进闸、不落库）；
// ⑥ 来源表里没有形态这一格（「无形态推导边」的静态证明）。
package com.example.zhengdao.baopu.state.bodyparts

import com.example.zhengdao.SrcRoots
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShapeSourceAssertTest {

    private val dir = "app/src/main/java/com/example/zhengdao/baopu/state/bodyparts/"
    private val files = listOf(
        "ShapeCatalog.kt", "ShapeRumorCatalog.kt", "ShapeJudge.kt", "ShapeGuard.kt", "ShapeSheet.kt",
    ).map { dir + it }

    private fun code(): String = files.joinToString("\n") { stripComments(SrcRoots.find(it).readText()) }

    private val ioTokens = listOf(
        "java.io", "InputStream", "OutputStream", "readText", "File(", "Files.",
        "Bitmap", "ImageDecoder", "Camera", "MediaStore", "ContentResolver", "openFile",
    )

    private val lengthTokens = listOf(
        "c" + "m", "m" + "m", "厘" + "米", "毫" + "米", "inc" + "h", "英" + "寸",
    )

    private val diagnosisTokens = listOf(
        "诊" + "断", "异" + "常", "需就" + "医", "病" + "理", "确" + "诊", "治" + "疗",
    )

    private val foreignTokens = listOf(
        "state.anchor.", "state.erect.", "state.form.", "state.physiology.", "state.activity.",
        "state.cast", "state.prepuce.", "AnatomyZone", "HumanBodySpec", "HardnessTier",
    )

    private val wiringTokens = listOf(
        "RunEngine", "ZhengdaoApp", "ContextAssembler", "RiskMatrix", "insert",
        "gatePass", "persistContract", "StateSummary",
    )

    @Test
    fun `边界一_零IO`() {
        val text = code()
        ioTokens.forEach { tok -> assertFalse("不得出现 $tok", text.contains(tok)) }
    }

    @Test
    fun `边界二_源码内无长度单位字面`() {
        val text = code()
        lengthTokens.forEach { tok -> assertFalse("源码不得写长度单位 $tok", hasUnit(text, tok)) }
    }

    @Test
    fun `边界三_源码内无诊断词`() {
        val text = code()
        diagnosisTokens.forEach { tok -> assertFalse("源码不得出现判定词 $tok", text.contains(tok)) }
    }

    @Test
    fun `边界四_不引用邻单类型`() {
        val text = code()
        foreignTokens.forEach { tok -> assertFalse("不得引用邻单 $tok", text.contains(tok)) }
    }

    @Test
    fun `边界五_零装配不落库`() {
        val text = code()
        wiringTokens.forEach { tok -> assertFalse("不得装配或落库 $tok", text.contains(tok)) }
    }

    @Test
    fun `边界六_来源表里没有形态这一格`() {
        val text = code()
        assertFalse("ALLOWED 表不许出现 MORPHOLOGY", allowedBlockHasMorphology(text))
        Judged.entries.forEach { dim ->
            assertFalse(
                dim.label + " 不许由形态支撑",
                ShapeRules.supports(dim, Source.MORPHOLOGY),
            )
        }
        println("SOURCE-READING supports(MORPHOLOGY)=false for " + Judged.entries.size + " dims")
    }

    @Test
    fun `计数锁_四张闭集与传闻条数`() {
        assertEquals("形态闭集", 14, ShapeAspect.entries.size)
        assertEquals(ShapeAspect.COUNT, ShapeAspect.entries.size)
        assertEquals("评价维", 6, Judged.entries.size)
        assertEquals(Judged.COUNT, Judged.entries.size)
        assertEquals("来源十格", 10, Source.entries.size)
        assertEquals(Source.COUNT, Source.entries.size)
        assertEquals("口味三档", 3, PreferenceTier.entries.size)
        assertEquals(PreferenceTier.COUNT, PreferenceTier.entries.size)
        assertEquals("传闻七类", 7, RumorClass.entries.size)
        assertEquals(RumorClass.COUNT, RumorClass.entries.size)
        assertEquals("传闻目标七值", 7, RumorTarget.entries.size)
        assertEquals(RumorTarget.COUNT, RumorTarget.entries.size)
        assertEquals("判定三值", 3, ShapeFinding.COUNT_VERDICTS)
        assertEquals("传闻条数", 27, ShapeRumorCatalog.entries.size)
        println("SOURCE-READING counts 14/6/10/3/7/7/3/27")
    }

    @Test
    fun `来源闭集逐字钉死`() {
        assertEquals("能力四源顺序", listOf("810", "868", "911", "行为"),
            ShapeRules.ABILITY_SOURCES.map { it.code })
        assertEquals("张力三源", listOf("观测", "设定", "实测"), ShapeRules.TENSION_SOURCES.map { it.code })
        assertEquals("吸引力只从口味来", listOf("口味"), ShapeRules.ATTRACTION_SOURCES.map { it.code })
        ShapeRules.HARD_DIMS.forEach { dim ->
            assertTrue(dim.label + " 必须恒不可推", ShapeRules.allowed(dim).isEmpty())
        }
        assertEquals("恒不可推的三维", setOf(Judged.EXPERIENCE, Judged.FERTILITY, Judged.MORAL),
            ShapeRules.HARD_DIMS)
    }

    @Test
    fun `闸自检_注入必被咬住`() {
        val io = stripComments("val bad = File(\"x\").readText()")
        assertTrue("IO 注入必须被咬住", ioTokens.any { io.contains(it) })
        val len = stripComments("val bad = \"约7" + "c" + "m\"")
        assertTrue("长度注入必须被咬住", lengthTokens.any { hasUnit(len, it) })
        val diag = stripComments("val bad = \"" + "异" + "常\"")
        assertTrue("判定词注入必须被咬住", diagnosisTokens.any { diag.contains(it) })
        val foreign = stripComments("import com.example.zhengdao.baopu.state.erect.ErectGrades")
        assertTrue("邻单注入必须被咬住", foreignTokens.any { foreign.contains(it) })
        val wiring = stripComments("val bad = RunEngine.run()")
        assertTrue("装配注入必须被咬住", wiringTokens.any { wiring.contains(it) })
        val shapeEdge = stripComments(
            "private val ALLOWED = mapOf(Judged.ABILITY to setOf(Source.MORPHOLOGY))\ninit {",
        )
        assertTrue("形态入来源表必须被咬住", allowedBlockHasMorphology(shapeEdge))
        println("SOURCE-READING negative injections 6/6 caught")
    }

    @Test
    fun `闸自检_剥注释后注释里的词不算数`() {
        val onlyInComment = stripComments("// 这里提到 readText 与 " + "c" + "m" + " 只是说明\nval ok = 1")
        assertFalse("注释里的 IO 不该算", ioTokens.any { onlyInComment.contains(it) })
        assertFalse("注释里的长度单位不该算", lengthTokens.any { hasUnit(onlyInComment, it) })
    }

    /**
     * ALLOWED 表那一块里有没有 MORPHOLOGY（含 Source.MORPHOLOGY 或裸写）。
     * 窗口＝从 `private val ALLOWED` 到 `init {`，只要有就是「形态进了来源表」。
     */
    private fun allowedBlockHasMorphology(src: String): Boolean {
        val from = src.indexOf("private val ALLOWED")
        if (from < 0) return false
        val to = src.indexOf("init {", from)
        if (to < 0) return false
        return src.substring(from, to).contains("MORPHOLOGY")
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
