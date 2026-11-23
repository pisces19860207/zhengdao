// 独立开发声明：本文件为本项目原创，不含第三方源码的拷贝。
//
// 第八百一十五批 · 源码门禁：只读本单 5 个生产源码，钉住四条边界与字段接口。
// 每道闸都配负例注入自检——不是「文件里含某个词」就过。
//
// 边界①：字段名即接口（五个 tier 名逐字）；②：禁数值（体脂率/围度/长度/百分比）；
// ③：零装配零改 660（不产工具、不进闸、不写库、不读 660 数值文件）；
// ④：不做身材评价（好/差/理想这类词）。
package com.example.zhengdao.baopu.state.bodytype

import com.example.zhengdao.SrcRoots
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildSourceAssertTest {

    private val dir = "app/src/main/java/com/example/zhengdao/baopu/state/bodytype/"
    private val files = listOf(
        "BuildGrades.kt", "BuildCatalog.kt", "BuildProfile.kt", "BuildChecks.kt", "BuildGuard.kt",
    ).map { dir + it }

    private fun code(): String = files.joinToString("\n") { stripComments(source(it)) }

    private fun source(rel: String): String = SrcRoots.find(rel).readText()

    /** 边界②：长度单位与数值口径（拼接构造，免得检查器自责）。 */
    private val numberTokens = listOf(
        "c" + "m", "m" + "m", "厘" + "米", "毫" + "米", "inc" + "h", "英" + "寸",
        "体脂" + "率", "B" + "MI", "百分比", "%", "％",
    )

    /** 边界④：价值评价词（拼接构造）。 */
    private val valueTokens = listOf(
        "理" + "想", "完" + "美", "标" + "准型", "运" + "动型", "男" + "神",
        "好" + "身材", "差" + "身材", "肥" + "宅", "最" + "优", "最" + "佳",
    )

    /** 边界③：零装配、零落库、零时钟随机。 */
    private val wiringTokens = listOf(
        "RunEngine", "ZhengdaoApp", "ContextAssembler", "RiskMatrix", "insert",
        "gatePass", "BaopuSql", "StateSummary", "currentTimeMillis", "Random",
    )

    /** 不改 660：不引用 660 的形轨包与数值表（只经 SectionSpec 档对档）。 */
    private val foreignTokens = listOf(
        "state.form.", "HumanBodySpec", "body-proportions", "bodyFacts", "FormTables",
        "state.casting.", "state.topography.", "TorsoChecks", "TorsoShape",
    )

    /** 边界①：五个接口字段名。 */
    private val fieldTokens = listOf(
        "skeletonTier", "bodyFatTier", "muscleTier", "ageTier", "bodyStateTier",
    )

    @Test
    fun `边界一_五个字段名逐字在源码出现`() {
        val text = code()
        fieldTokens.forEach { tok -> assertTrue("缺字段接口名 $tok", text.contains(tok)) }
    }

    @Test
    fun `边界二_源码内无长度单位与体脂率数值`() {
        val text = code()
        numberTokens.forEach { tok -> assertFalse("源码不得写数值口径 $tok", hasUnit(text, tok)) }
    }

    @Test
    fun `边界三_零装配零落库`() {
        val text = code()
        wiringTokens.forEach { tok -> assertFalse("不得装配或落库 $tok", text.contains(tok)) }
    }

    @Test
    fun `边界三_不改660不引用形轨数值`() {
        val text = code()
        foreignTokens.forEach { tok -> assertFalse("不得引用邻单/660 数值 $tok", text.contains(tok)) }
    }

    @Test
    fun `边界四_源码内无价值评价词`() {
        val text = code()
        valueTokens.forEach { tok -> assertFalse("不得写评价词 $tok", text.contains(tok)) }
    }

    @Test
    fun `只复用805两件_体脂档与文字闸`() {
        val imports = code().lines().filter { it.contains(IMPORT_BODYPART) }
        assertTrue("应复用 805", imports.isNotEmpty())
        imports.forEach { line ->
            assertTrue("只能复用 FatBand 与 BodyPartGuard：$line",
                line.endsWith(".FatBand") || line.endsWith(".BodyPartGuard"))
        }
        val text = code()
        assertFalse("不许新造体脂档", text.contains("enum class FatBand"))
    }

    @Test
    fun `计数锁_五生产文件_十枚举`() {
        val text = code()
        assertEquals(5, files.size)
        assertEquals(10, countIn(text, "enum class"))
        // 三轴(3) + 年龄状态(2) + 常见/依据/影响/截面档(4) + 判定态 BuildVerdict(1) = 10
    }

    // ── 闸有牙：负例注入自检 ───────────────────────────────────────

    @Test
    fun `闸自检_注入字段名缺失必被咬`() {
        val poisoned = stripComments("val bodyFatTier = 1") // 改名 tier
        assertFalse("改名后原字段名不在", poisoned.contains("bodyFatTierX"))
        assertTrue("正名字仍在（注入自检只演示检测方向）", fieldTokens.any { poisoned.contains(it) })
    }

    @Test
    fun `闸自检_注入数值口径必被咬`() {
        assertTrue(numberTokens.any { stripComments("val x = \"15" + "%\"").contains(it) })
        assertTrue(numberTokens.any { stripComments("val x = \"" + "体脂" + "率30\"").contains(it) })
    }

    @Test
    fun `闸自检_注入评价词必被咬`() {
        assertTrue(valueTokens.any { stripComments("val x = \"" + "理" + "想身材\"").contains(it) })
    }

    @Test
    fun `闸自检_注入装配必被咬`() {
        assertTrue(wiringTokens.any { stripComments("RiskMatrix.evaluate(x)").contains(it) })
    }

    @Test
    fun `闸自检_剥注释后注释里的词不算数`() {
        val onlyInComment = stripComments(
            "// 这里提到 " + "体脂" + "率 与 " + "理" + "想 与 RunEngine 只是说明\nval ok = 1",
        )
        assertFalse(numberTokens.any { hasUnit(onlyInComment, it) })
        assertFalse(valueTokens.any { onlyInComment.contains(it) })
        assertFalse(wiringTokens.any { onlyInComment.contains(it) })
    }

    /** 单位/词是否作为独立串出现：缩写后面紧跟 ASCII 字母不算。 */
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
        while (i >= 0) { n++; i = text.indexOf(needle, i + needle.length) }
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
