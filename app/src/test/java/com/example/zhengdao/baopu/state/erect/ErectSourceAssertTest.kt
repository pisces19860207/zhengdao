// 独立开发声明：本文件为本项目原创，不含第三方源码的拷贝。
//
// 第八百一十批 · 源码门禁：只读本单 6 个生产源码，钉住六条边界与计数锁。
// 每道闸都配负例注入自检——不是「文件里含某个词」就过。
//
// 边界：零 IO／零随机／零时钟／零装配；禁数值（倍数/长度/角度值）；
// 不引用邻单类型（868 时间/814 截面/775 子区/867 组合/804/660 的 class）；
// 硬度与包皮位只做字符串引用；占位只能 pending:775／pending:814。
package com.example.zhengdao.baopu.state.erect

import com.example.zhengdao.SrcRoots
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ErectSourceAssertTest {

    private val dir = "app/src/main/java/com/example/zhengdao/baopu/state/erect/"
    // 闸文件 ErectGuard 不在此列：它的字典按定义就含「倍数／度数／°」等字面量，
    // 与 805 的 BodyPartGuard 同构（后者同样不进任何门禁 files）。它的正确性由
    // ErectReadingTest 的负例注入自检覆盖，不靠「文件里没这个词」这种恒真判据。
    private val files = listOf(
        "ErectSource.kt", "ErectGrades.kt",
        "ErectReading.kt", "ErectSheet.kt", "ErectChecks.kt",
    ).map { dir + it }

    private fun code(): String = files.joinToString("\n") { stripComments(source(it)) }

    private fun source(rel: String): String = SrcRoots.find(rel).readText()

    /** 零 IO／零随机／零时钟。 */
    private val ioTokens = listOf(
        "java.io", "InputStream", "OutputStream", "readText", "File(", "Files.",
        "Bitmap", "ImageDecoder", "Camera", "MediaStore", "ContentResolver", "openFile",
    )

    private val clockRandomTokens = listOf(
        "Random", "currentTimeMillis", "nanoTime", "Math.random", "java.util.Date",
    )

    /** 禁数值：倍数、长度单位、角度数值（拼接构造，免得检查器自责）。 */
    private val measureTokens = listOf(
        "倍数", "增长" + "率", "c" + "m", "m" + "m", "厘" + "米", "毫" + "米",
        "inc" + "h", "英" + "寸", "%", "％", "百分比", "°", "度角", "仰" + "角",
    )

    /** 零装配：不产工具、不进闸、不写库。 */
    private val wiringTokens = listOf(
        "RunEngine", "ZhengdaoApp", "ContextAssembler", "RiskMatrix", "insert",
        "gatePass", "BaopuSql", "StateSummary", "GROUP_OF", "ENTRIES",
    )

    /**
     * 不引用邻单的生产类型：868 时间、814 截面、775 子区、867 组合、804/660 的 class。
     * 只做字符串引用（fb.hardness_ehs / state.prepuce.AreaLevel 的 label 表）。
     */
    private val foreignTokens = listOf(
        "state.timeline.", "state.progress.", "state.section.", "state.crosssection.",
        "state.subzone.", "state.combo.", "state.prepuce.", "state.form.",
        "AreaLevel", "GenitalModel", "HardnessGrade", "SubzoneId", "SubzoneGrades",
        "SubzoneTable", "TorsoShape", "CombinationTable", "ErectionCurve",
    )

    /** 868/814/775/867 的账不许提前实现。 */
    private val foreignConceptTokens = listOf(
        "时间" + "曲线", "起" + "多快", "维持" + "时" + "长", "消退" + "两型", "半勃" + "占" + "比",
        "横" + "截面", "截面" + "厚度", "组合" + "合法性",
    )

    private val bodyPartAllow = listOf("BodyPartGuard")

    @Test
    fun `边界一_零IO`() {
        val text = code()
        ioTokens.forEach { tok -> assertFalse("不得出现 $tok", text.contains(tok)) }
    }

    @Test
    fun `边界一_零随机零时钟`() {
        val text = code()
        clockRandomTokens.forEach { tok -> assertFalse("不得出现 $tok", text.contains(tok)) }
    }

    @Test
    fun `禁数值_源码内无倍数长度角度值`() {
        val text = code()
        measureTokens.forEach { tok -> assertFalse("源码不得出现数值口径 $tok", hasUnit(text, tok)) }
    }

    @Test
    fun `零装配_不产工具不进闸不落库`() {
        val text = code()
        wiringTokens.forEach { tok -> assertFalse("不得装配或落库 $tok", text.contains(tok)) }
    }

    @Test
    fun `边界_不引用邻单类型_只做字符串引用`() {
        val text = code()
        foreignTokens.forEach { tok -> assertFalse("不得引用邻单类型 $tok", text.contains(tok)) }
        foreignConceptTokens.forEach { tok -> assertFalse("不得提前实现邻单概念 $tok", text.contains(tok)) }
        // 字符串引用口径必须在册
        assertTrue("硬度走字符串引用", text.contains("fb.hardness_ehs"))
        assertTrue("包皮位走字符串引用", text.contains("804/831 六档"))
    }

    @Test
    fun `只复用805文字闸_别的一概不碰`() {
        val imports = code().lines().filter { it.contains(IMPORT_BODYPART) }
        assertTrue("应当复用 805", imports.isNotEmpty())
        imports.forEach { line ->
            assertTrue("只能 import 805 的 $bodyPartAllow：$line", bodyPartAllow.any { line.endsWith(".$it") })
        }
        val text = code()
        assertTrue("文字闸复用 805", text.contains("BodyPartGuard"))
        assertFalse("不许新造文字闸类", text.contains("object BodyPartGuard"))
    }

    @Test
    fun `占位只允许两个pending单号`() {
        val text = code()
        assertTrue("子区占位在册", text.contains(PENDING_SUBZONE))
        assertTrue("截面占位在册", text.contains(PENDING_SECTION))
        assertEquals(setOf(PENDING_SUBZONE, PENDING_SECTION), ErectGuard.ALLOWED_PENDING)
    }

    @Test
    fun `计数锁_六枚举一接口五行`() {
        val text = code()
        assertEquals(5, files.size)
        // 生产源码里的 enum class：GrowthType/GrowthTier/ErectAngle/HardnessTier/ErectProfile（ErectGrades）＋ErectVerdict（ErectChecks）
        assertEquals(6, countIn(text, "enum class"))
        assertEquals(1, countIn(text, "interface SheetRow"))
        assertEquals(5, countIn(text, ") : SheetRow {"))
        assertEquals(5, SheetRow.ALL_KEYS.size)
        assertEquals(4, GrowthTier.COUNT)
        assertEquals(4, HardnessTier.COUNT)
    }

    // ── 闸有牙：负例注入自检 ───────────────────────────────────

    @Test
    fun `闸自检_注入IO必被咬住`() {
        val poisoned = stripComments("val bad = File(\"x\").readText()")
        assertTrue(ioTokens.any { poisoned.contains(it) })
    }

    @Test
    fun `闸自检_注入随机时钟必被咬住`() {
        assertTrue(clockRandomTokens.any { stripComments("val bad = Random.nextInt()").contains(it) })
        assertTrue(clockRandomTokens.any { stripComments("val bad = System.currentTimeMillis()").contains(it) })
    }

    @Test
    fun `闸自检_注入倍数长度角度值必被咬住`() {
        assertTrue(measureTokens.any { hasUnit(stripComments("val bad = \"2" + "倍数\""), it) })
        assertTrue(measureTokens.any { hasUnit(stripComments("val bad = \"3" + "c" + "m\""), it) })
        assertTrue(measureTokens.any { hasUnit(stripComments("val bad = \"30" + "°\""), it) })
    }

    @Test
    fun `闸自检_注入邻单类型必被咬住`() {
        assertTrue(foreignTokens.any { stripComments("val bad = SubzoneId.apex").contains(it) })
        assertTrue(foreignTokens.any { stripComments("val bad = AreaLevel.FULL_COVER").contains(it) })
    }

    @Test
    fun `闸自检_注入装配必被咬住`() {
        assertTrue(wiringTokens.any { stripComments("val bad = RiskMatrix.of()").contains(it) })
    }

    @Test
    fun `闸自检_剥注释后注释里的词不算数`() {
        val onlyInComment = stripComments(
            "// 这里提到 readText 与 " + "c" + "m" + " 与 " + "横截面 只是说明\nval ok = 1",
        )
        assertFalse(ioTokens.any { onlyInComment.contains(it) })
        assertFalse(measureTokens.any { hasUnit(onlyInComment, it) })
        assertFalse(foreignConceptTokens.any { onlyInComment.contains(it) })
    }

    /** 单位是否作为独立词出现：缩写后紧跟 ASCII 字母不算。 */
    private fun hasUnit(text: String, unit: String): Boolean {
        var i = text.indexOf(unit, ignoreCase = true)
        while (i >= 0) {
            val after = i + unit.length
            val asciiLetter = after < text.length && (text[after] in 'a'..'z' || text[after] in 'A'..'Z')
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
                else -> { out.append(src[i]); i++ }
            }
        }
        return out.toString()
    }

    private fun skipLine(src: String, start: Int, out: StringBuilder): Int {
        var i = start + 2
        out.append("  ")
        while (i < src.length && src[i] != '\n') { out.append(' '); i++ }
        return i
    }

    private fun skipBlock(src: String, start: Int, out: StringBuilder): Int {
        var i = start + 2
        var depth = 1
        out.append("  ")
        while (i < src.length && depth > 0) {
            val two = if (i + 1 < src.length) src.substring(i, i + 2) else ""
            if (two == "/*") { depth++; out.append("  "); i += 2 }
            else if (two == "*/") { depth--; out.append("  "); i += 2 }
            else { out.append(if (src[i] == '\n') '\n' else ' '); i++ }
        }
        return i
    }

    private fun copyString(src: String, start: Int, out: StringBuilder): Int {
        var i = start
        out.append(src[i]); i++
        while (i < src.length) {
            out.append(src[i])
            if (src[i] == '\\' && i + 1 < src.length) { out.append(src[i + 1]); i += 2; continue }
            if (src[i] == '"') { i++; break }
            i++
        }
        return i
    }

    private companion object {
        const val IMPORT_BODYPART = "import com.example.zhengdao.baopu.state.bodyparts."
    }
}
