// 独立开发声明：本文件为本项目原创，不含第三方源码的拷贝。
//
// 第八百四十八批 · 源码门禁：只读本单 4 个生产源码，钉住四条边界与三条交叉一致。
// 每道闸都配负例注入自检——不是「文件里含某个词」就过。
//
// 边界①：不读真人影像（无 IO／相机／解码）；②：不反推尺寸（无长度／围度／百分比）；
// 边界③：不做诊断、不做评价；边界④：不改 805 既有档（体积维只复用、不新造）。
// 交叉一致：与 805（复用体积档、维度名零重名、部位表读数不变）／与 815（档案名逐字对齐）／
// 与 830（不碰姿态投影，形状只记放松态）。
package com.example.zhengdao.baopu.state.bodyparts

import com.example.zhengdao.SrcRoots
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TorsoSourceAssertTest {

    private val dir = "app/src/main/java/com/example/zhengdao/baopu/state/bodyparts/"
    private val files = listOf(
        "TorsoGrades.kt", "TorsoCatalog.kt", "TorsoShape.kt", "TorsoChecks.kt",
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

    /** 判据⑥：围度与体脂百分比一律不许（本单只给档，不给量）。 */
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

    /** 邻单：不抢 817（未合）／747／660／658／830 的账。 */
    private val foreignTokens = listOf(
        "state.casting.", "state.anchor.", "state.form.", "state.physiology.",
        "state.geometry.", "state.prepuce.", "CastingCard", "CheckState", "AnchorFields",
        "PoseValidity", "HumanBodySpec",
    )

    /** 805 的单与判据层不许碰（只许复用体积档、姿态词与那道闸）。 */
    private val bodyPartLayerTokens = listOf(
        "BodyPartCatalog", "BodyPartChecks", "BodyParts(", "Reading(", "Dim.", "AreolaMatrix",
        "LarynxDriver",
    )

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
    fun `判据六_源码内无围度与百分比`() {
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
    fun `边界四_只复用805体积档_不碰它的单与判据层`() {
        val text = code()
        bodyPartLayerTokens.forEach { tok -> assertFalse("不得引用 805 的 $tok", text.contains(tok)) }
        // 805 的 Finding 一个字也不许出现：出现次数必须与自家 TorsoFinding 完全相等
        assertEquals("不得引用 805 的 Finding 类型", countIn(text, "TorsoFinding"), countIn(text, "Finding"))
        listOf("PecVolume", "AbsBlock", "Girth", "Posture", "BodyPartGuard")
            .forEach { assertTrue("应复用 805 的 $it", text.contains(it)) }
        assertFalse("不许新造体积档", text.contains("enum class PecVolume"))
        assertFalse("不许新造围度档", text.contains("enum class Girth"))
    }

    @Test
    fun `计数锁_八维五项五档案_VS805零重名`() {
        assertEquals(4, files.size)
        assertEquals(8, TorsoDimension.entries.size)
        assertEquals(5, TorsoItem.entries.size)
        assertEquals(5, TorsoProfile.entries.size)
        assertEquals(28, TorsoCatalog.totalGrades)
        assertEquals(12, countIn(code(), "enum class"))
        val overlap = TorsoDimension.entries.map { it.label }
            .intersect(Dim.entries.map { it.label }.toSet())
        assertTrue("维度名与 805 重名：$overlap", overlap.isEmpty())
        val partOverlap = TorsoDimension.entries.map { it.label }
            .intersect(BodyPart.entries.map { it.label }.toSet())
        assertTrue("维度名与 805 部位重名：$partOverlap", partOverlap.isEmpty())
    }

    @Test
    fun `交叉一致_805部位表读数一条没变`() {
        // 848 是纯增量：805 的八部位／二十三维／七十二读数原样
        assertEquals(8, BodyPart.entries.size)
        assertEquals(23, Dim.entries.size)
        assertEquals(72, BodyPartCatalog.totalReadings)
        assertEquals(4, FatBand.COUNT)
    }

    @Test
    fun `交叉一致_830未落地_形状只记放松态`() {
        // 姿态只有一态：不引入投影/光照/角度这类 830 的账
        assertEquals(Posture.RELAXED, TORSO_SHAPE_POSTURE)
        val text = code()
        listOf("投影", "光照", "Pose", "Light").forEach {
            assertFalse("不得提前实现 830 的 $it", text.contains(it))
        }
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
        val p1 = stripComments("val bad = \"20" + "%\"")
        assertTrue(measureTokens.any { p1.contains(it) })
        val p2 = stripComments("val bad = \"" + "胸" + "围 90\"")
        assertTrue(measureTokens.any { p2.contains(it) })
    }

    @Test
    fun `闸自检_注入判定词与评价词必被咬住`() {
        assertTrue(diagnosisTokens.any { stripComments("val bad = \"" + "异" + "常\"").contains(it) })
        assertTrue(valueTokens.any { stripComments("val bad = \"完美\"").contains(it) })
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
}
