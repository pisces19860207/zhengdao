// 独立开发声明：本文件为本项目原创，不含第三方源码的拷贝。
//
// 第八百六十五批 · 源码门禁：只读本单 5 个生产源码，钉住补丁与边界。
// 每道闸都配负例注入自检——不是「文件里含某个词」就过。
//
// 补丁一/七：骨段与关节归 900；补丁二：815 真枚举不自造；
// 补丁三：高体脂分明必红（判据测试另有命名用例）；
// 补丁五/六：区名只许真名/none，子区只认 775 十四 code；补丁八：引用示例进报告。
package com.example.zhengdao.baopu.state.frame

import com.example.zhengdao.SrcRoots
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameSourceAssertTest {

    private val dir = "app/src/main/java/com/example/zhengdao/baopu/state/frame/"
    private val files = listOf(
        "FrameGrades.kt", "FrameCatalog.kt", "FrameReading.kt", "FrameChecks.kt", "FrameGuard.kt",
    ).map { dir + it }

    private fun source(rel: String): String = SrcRoots.find(rel).readText()

    private fun code(): String = files.joinToString("\n") { stripComments(source(it)) }

    private val ioTokens = listOf(
        "java.io", "InputStream", "OutputStream", "readText", "File(",
        "Bitmap", "ImageDecoder", "Camera", "MediaStore", "ContentResolver", "openFile",
    )

    private val lengthTokens = listOf(
        "c" + "m", "m" + "m", "厘" + "米", "毫" + "米", "inc" + "h", "英" + "寸",
    )

    private val measureTokens = listOf(
        "%", "％", "百分比", "平方", "胸" + "围", "腰" + "围", "体脂" + "率", "BMI",
    )

    private val diagnosisTokens = listOf(
        "诊" + "断", "异" + "常", "需就" + "医", "病" + "理", "确" + "诊", "治" + "疗",
    )

    private val valueTokens = listOf(
        "理" + "想", "完" + "美", "标" + "准" + "型", "运" + "动" + "型",
        "肥" + "宅", "男" + "神", "女" + "神",
    )

    private val wiringTokens = listOf(
        "RunEngine", "ZhengdaoApp", "ContextAssembler", "RiskMatrix", "insert",
        "gatePass", "BaopuSql", "StateSummary", "currentTimeMillis", "Random",
    )

    /** 补丁一/七 + 邻单禁区：不建骨段关节、不改 660、不自造 815 枚举、不碰 869 接线。 */
    private val foreignTokens = listOf(
        "state.form.", "HumanBodySpec", "body-proportions",
        "TorsoChecks", "TopoChecks", "state.casting.",
        "Mesh", "Vertex", "Joint", "Segment",
        "骨段", "关节", "enum class BodyFatTier", "enum class SkeletonTier",
    )

    private val allowedImports = listOf(
        "import com.example.zhengdao.baopu.state.bodytype.",
        "import com.example.zhengdao.baopu.state.view.Octant",
        "import com.example.zhengdao.baopu.state.subzone.SubzoneId",
        "import com.example.zhengdao.baopu.state.bodyparts.BodyPartGuard",
    )

    @Test
    fun `边界一_零IO不读影像`() {
        val text = code()
        ioTokens.forEach { tok -> assertFalse("不得出现 $tok", text.contains(tok)) }
    }

    @Test
    fun `判据四_源码无长度单位`() {
        val text = code()
        lengthTokens.forEach { tok -> assertFalse("不得写长度单位 $tok", hasUnit(text, tok)) }
    }

    @Test
    fun `判据四_源码无围度百分比与体脂率`() {
        val text = code()
        measureTokens.forEach { tok -> assertFalse("不得出现 $tok", text.contains(tok)) }
    }

    @Test
    fun `边界三_源码无判定词与评价词`() {
        val text = code()
        diagnosisTokens.forEach { tok -> assertFalse("不得出现判定词 $tok", text.contains(tok)) }
        valueTokens.forEach { tok -> assertFalse("不得出现评价词 $tok", text.contains(tok)) }
    }

    @Test
    fun `零装配_不进闸不落库不外传`() {
        val text = code()
        wiringTokens.forEach { tok -> assertFalse("不得装配或落库 $tok", text.contains(tok)) }
    }

    @Test
    fun `补丁一七_骨段关节归900_本包零命中`() {
        val text = code()
        listOf("Mesh", "Vertex", "Joint", "Segment", "骨段", "关节")
            .forEach { tok -> assertFalse("不得抢 900 的账：$tok", text.contains(tok)) }
    }

    @Test
    fun `补丁二_只读815真枚举不自造`() {
        val text = code()
        assertTrue("应 import 815 的 BodyFatTier", text.contains("state.bodytype.BodyFatTier"))
        assertTrue("应 import 815 的 SkeletonTier", text.contains("state.bodytype.SkeletonTier"))
        assertTrue("可见度应读 bodyFatTier 字段", text.contains("profile.bodyFatTier"))
        assertFalse("不得自造体脂枚举", text.contains("enum class BodyFatTier"))
        assertFalse("不得自造骨架枚举", text.contains("enum class SkeletonTier"))
    }

    @Test
    fun `补丁五六_区字段分列且子区只认775`() {
        val text = code()
        assertTrue("zoneId 单字段", text.contains("val zoneId: String"))
        assertTrue("subzoneId 单字段", text.contains("val subzoneId: String?"))
        assertTrue("子区真源是 775", text.contains("state.subzone.SubzoneId"))
        assertTrue("解析走 ofCode", text.contains("SubzoneId.ofCode"))
    }

    @Test
    fun `只许白名单import_邻单一概不碰`() {
        val imports = code().lines().filter { it.startsWith("import ") }
        assertTrue("应当有 import", imports.isNotEmpty())
        imports.forEach { line ->
            assertTrue("import 越界：$line", allowedImports.any { line.startsWith(it) })
        }
        val text = code()
        foreignTokens.forEach { tok -> assertFalse("禁区 $tok", text.contains(tok)) }
    }

    @Test
    fun `计数锁_七枚举四数据类三对象一容器`() {
        val text = code()
        assertEquals(5, files.size)
        assertEquals(7, countIn(text, "enum class"))
        assertEquals(4, countIn(text, "data class"))
        assertEquals(3, countIn(text, "object Frame"))
        assertEquals(1, countIn(text, "class BoneFrame"))
        assertTrue("十四点都应标 none 区", countIn(text, "FRAME_ZONE_NONE") >= FramePoint.COUNT)
        assertTrue("五生产文件齐", files.all { source(it).isNotBlank() })
    }

    // ── 闸有牙：负例注入自检 ─────────────────────────────────────────

    @Test
    fun `闸自检_注入IO必被咬`() {
        val poisoned = stripComments("val bad = File(\"x\").readText()")
        assertTrue(ioTokens.any { poisoned.contains(it) })
    }

    @Test
    fun `闸自检_注入长度单位必被咬`() {
        val poisoned = stripComments("val bad = \"约7" + "c" + "m\"")
        assertTrue(lengthTokens.any { hasUnit(poisoned, it) })
    }

    @Test
    fun `闸自检_注入百分比与围度必被咬`() {
        assertTrue(measureTokens.any { stripComments("val bad = \"20" + "%\"").contains(it) })
        assertTrue(measureTokens.any { stripComments("val bad = \"" + "胸" + "围 90\"").contains(it) })
    }

    @Test
    fun `闸自检_注入判定词与评价词必被咬`() {
        assertTrue(diagnosisTokens.any { stripComments("val bad = \"" + "异" + "常\"").contains(it) })
        assertTrue(valueTokens.any { stripComments("val bad = \"" + "理" + "想\"").contains(it) })
    }

    @Test
    fun `闸自检_注入装配与骨段关节必被咬`() {
        assertTrue(wiringTokens.any { stripComments("val bad = RiskMatrix").contains(it) })
        assertTrue(foreignTokens.any { stripComments("val bad = Joint").contains(it) })
        assertTrue(foreignTokens.any { stripComments("val bad = \"骨段\"").contains(it) })
    }

    @Test
    fun `闸自检_注入自造体脂枚举必被咬`() {
        val poisoned = stripComments("enum class BodyFatTier { LOW, HIGH }")
        assertTrue(foreignTokens.any { poisoned.contains(it) })
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
}
