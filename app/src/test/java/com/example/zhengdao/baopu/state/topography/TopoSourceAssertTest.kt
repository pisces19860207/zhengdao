// 独立开发声明：本文件为本项目原创，不含第三方源码的拷贝。
//
// 第八百六十六批 · 源码门禁：只读本单 5 个生产源码，钉住四条边界与三条交叉一致。
// 每道闸都配负例注入自检——不是「文件里含某个词」就过。
//
// 边界①：不读真人影像（无 IO／相机／解码）；②：不反推尺寸（无长度／围度／百分比）；
// 边界③：不做诊断、不做评价；④：不改 848 与 830（形状归 848、挺胸归 830）。
// 交叉一致：与 805（只复用 FatBand＋BodyPartGuard，部位表读数一条不变）／
// 与 848（维名、档名零重名，且厚度不引用 848 的判定）／与 830（不碰姿态投影）。
package com.example.zhengdao.baopu.state.topography

import com.example.zhengdao.SrcRoots
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TopoSourceAssertTest {

    private val dir = "app/src/main/java/com/example/zhengdao/baopu/state/topography/"
    private val files = listOf(
        "TopoGrades.kt", "TopoCatalog.kt", "TopoReading.kt", "TopoChecks.kt", "TopoGuard.kt",
    ).map { dir + it }

    private fun code(): String = files.joinToString("\n") { stripComments(source(it)) }

    private fun source(rel: String): String = SrcRoots.find(rel).readText()

    /** 边界①：读影像、开文件、解码图片一律不许。 */
    private val ioTokens = listOf(
        "java.io", "InputStream", "OutputStream", "readText", "File(", "Files.",
        "Bitmap", "ImageDecoder", "Camera", "MediaStore", "ContentResolver", "openFile",
    )

    /** 边界②：长度单位（拼接构造，免得检查器自责）。 */
    private val lengthTokens = listOf(
        "c" + "m", "m" + "m", "厘" + "米", "毫" + "米", "inc" + "h", "英" + "寸",
    )

    /** 判据⑥（派工单「禁数值」）：围度与百分比一律不许，本单只给档。 */
    private val measureTokens = listOf(
        "%", "％", "百分比", "平方", "胸" + "围", "腰" + "围", "体脂" + "率", "BMI",
    )

    /** 边界③：判定词（拼接构造）。 */
    private val diagnosisTokens = listOf(
        "诊" + "断", "异" + "常", "需就" + "医", "病" + "理", "确" + "诊", "治" + "疗",
    )

    /** 边界③后半：价值评价词。 */
    private val valueTokens = listOf("完美", "标准型", "运动型", "肥宅", "虚弱")

    /** 零装配／零落库：本包不产工具、不进闸、不写库。 */
    private val wiringTokens = listOf(
        "RunEngine", "ZhengdaoApp", "ContextAssembler", "RiskMatrix", "insert",
        "gatePass", "BaopuSql", "StateSummary", "currentTimeMillis", "Random",
    )

    /** 判据④：不引用 848 的判定与形状类型，也不抢邻单的账。 */
    private val foreignTokens = listOf(
        "TorsoShape", "TorsoChecks", "TorsoCatalog", "TorsoPerson", "TorsoVolume",
        "state.casting.", "state.form.", "state.anchor.", "state.physiology.",
        "state.geometry.", "state.prepuce.",
    )

    /** 830 未落地：姿态投影、光照、挺胸这类账一个都不许提前实现。 */
    private val poseTokens = listOf("挺胸", "投影", "光照", "Pose", "Posture", "收紧", "Light")

    private val bodyPartAllow = listOf("BodyPartGuard", "FatBand")

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
    fun `禁数值_源码内无围度与百分比`() {
        val text = code()
        measureTokens.forEach { tok -> assertFalse("源码不得出现 $tok", text.contains(tok)) }
    }

    @Test
    fun `边界三_源码内无判定词与评价词`() {
        val text = code()
        diagnosisTokens.forEach { tok -> assertFalse("不得出现判定词 $tok", text.contains(tok)) }
        valueTokens.forEach { tok -> assertFalse("不得出现评价词 $tok", text.contains(tok)) }
    }

    @Test
    fun `零装配_不进闸不落库不外传`() {
        val text = code()
        wiringTokens.forEach { tok -> assertFalse("不得装配或落库 $tok", text.contains(tok)) }
        foreignTokens.forEach { tok -> assertFalse("不得引用邻单 $tok", text.contains(tok)) }
    }

    @Test
    fun `只复用805两件_别的一概不碰`() {
        val imports = code().lines().filter { it.contains(IMPORT_BODYPART) }
        assertTrue("应当有对 805 的复用", imports.isNotEmpty())
        imports.forEach { line ->
            assertTrue("只能 import 805 的 $bodyPartAllow：$line", bodyPartAllow.any { line.endsWith(".$it") })
        }
        val text = code()
        assertTrue("体脂档复用 805", text.contains("FatBand"))
        assertTrue("文字闸复用 805", text.contains("BodyPartGuard"))
        assertFalse("不许新造体脂档", text.contains("enum class FatBand"))
    }

    @Test
    fun `830未落地_不碰姿态投影`() {
        val text = code()
        poseTokens.forEach { tok -> assertFalse("不得提前实现 830 的 $tok", text.contains(tok)) }
    }

    @Test
    fun `计数锁_九枚举五数据类_十五维十一对`() {
        val text = code()
        assertEquals(5, files.size)
        assertEquals(9, countIn(text, "enum class"))
        assertEquals(5, countIn(text, "data class"))
        assertEquals(15, TopoCatalog.allDims().size)
        assertEquals(11, TopoCatalog.lateralDims().size)
        assertEquals(55, TopoCatalog.totalChoices)
        assertEquals(3, AsymCause.COUNT)
    }

    // ── 闸有牙：负例注入自检 ─────────────────────────────────────────

    @Test
    fun `闸自检_注入IO必被咬住`() {
        val poisoned = stripComments("val bad = File(\"x\").readText()")
        assertTrue(ioTokens.any { poisoned.contains(it) })
    }

    @Test
    fun `闸自检_注入长度单位必被咬住`() {
        val poisoned = stripComments("val bad = \"约7" + "c" + "m\"")
        assertTrue(lengthTokens.any { hasUnit(poisoned, it) })
    }

    @Test
    fun `闸自检_注入百分比与围度必被咬住`() {
        assertTrue(measureTokens.any { stripComments("val bad = \"20" + "%\"").contains(it) })
        assertTrue(measureTokens.any { stripComments("val bad = \"" + "胸" + "围 90\"").contains(it) })
    }

    @Test
    fun `闸自检_注入判定词与评价词必被咬住`() {
        assertTrue(diagnosisTokens.any { stripComments("val bad = \"" + "异" + "常\"").contains(it) })
        assertTrue(valueTokens.any { stripComments("val bad = \"完美\"").contains(it) })
    }

    @Test
    fun `闸自检_注入848判定调用必被咬住`() {
        val poisoned = stripComments("val bad = TorsoChecks.pair(a, b)")
        assertTrue(foreignTokens.any { poisoned.contains(it) })
    }

    @Test
    fun `闸自检_注入830姿态必被咬住`() {
        val poisoned = stripComments("val bad = \"挺胸时更显\"")
        assertTrue(poseTokens.any { poisoned.contains(it) })
    }

    @Test
    fun `闸自检_剥注释后注释里的词不算数`() {
        val onlyInComment = stripComments(
            "// 这里提到 readText 与 " + "c" + "m" + " 与 " + "胸" + "围 只是说明\nval ok = 1",
        )
        assertFalse(ioTokens.any { onlyInComment.contains(it) })
        assertFalse(lengthTokens.any { hasUnit(onlyInComment, it) })
        assertFalse(measureTokens.any { onlyInComment.contains(it) })
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

    private companion object {
        const val IMPORT_BODYPART = "import com.example.zhengdao.baopu.state.bodyparts."
    }
}
