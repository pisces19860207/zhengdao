// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第八百六十批 · 源码门禁：只读本包 6 个生产源码，钉住四条边界与三条分工。
// 每道闸都配负例注入自检——不是「文件里含某个词就过」，注入的坏样本必须被咬住，
// 注释里的词必须不算数（否则一道注释就能把闸哄过去）。
//
// 边界① 不做渲染/成像：无位图、无画布、无着色器、无相机硬件；
// 边界② 禁数值：源码里没有长度单位、没有角度单位、没有焦距，也不用浮点；
// 边界③ 禁反推：没有任何函数把 ScreenCell 当参数收进来（屏幕格只出不进）；
// 边界④ 不碰遮挡几何：不求交、不射线、不算包围盒（遮挡判定归 861）。
// 分工：零装配（不进闸、不落库）；不引用邻单类型（import 白名单只有 abs）。
package com.example.zhengdao.baopu.state.view

import com.example.zhengdao.SrcRoots
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewSourceAssertTest {

    private val dir = "app/src/main/java/com/example/zhengdao/baopu/state/view/"
    private val files = listOf(
        "ViewGrades.kt", "SceneNodeRef.kt", "Frustum.kt",
        "Viewpoint.kt", "Projection.kt", "ViewChecks.kt",
    ).map { dir + it }

    private fun source(rel: String): String = SrcRoots.find(rel).readText()

    /** 剥掉注释后的全部生产码（字符串保留：注入自检要用）。 */
    private fun code(): String = files.joinToString("\n") { stripComments(source(it)) }

    /** 边界①：渲染 / 成像 / 相机硬件一律不许出现。 */
    private val renderTokens = listOf(
        "java.io", "InputStream", "Bitmap", "ImageDecoder", "Camera", "MediaStore",
        "ContentResolver", "Canvas", "Shader", "GLSurface", "setPixels", "drawLine",
    )

    /** 边界②：长度与角度单位（拼接构造，避免检查器自己把字面写进表里）。 */
    private val unitTokens = listOf(
        "c" + "m", "m" + "m", "厘" + "米", "毫" + "米", "inc" + "h", "英" + "寸", "°",
        "焦" + "距", "focal", "FieldOfView", "degrees",
    )

    /** 边界②：浮点与三角（格差是整数序号，一旦出现浮点就是「量」混进来了）。 */
    private val floatTokens = listOf(
        "Double", "Float", "toDouble", "toFloat", "sqrt", "atan2", "sin(", "cos(", "tan(",
    )

    /** 边界④：遮挡几何（遮挡判定归 861，本包只收结论）。 */
    private val occlusionTokens = listOf(
        "intersect", "raycast", "AABB", "Triangle", "BoundingBox", "TriangleMesh", "occlusionTest",
    )

    /** 分工：装配 / 闸 / 落库一字不许出现（本单零装配）。 */
    private val wiringTokens = listOf(
        "RunEngine", "ZhengdaoApp", "ContextAssembler", "RiskMatrix", "StateSummary",
        "insert(", "persist", "gatePass",
    )

    /**
     * 来源契约：区域档不许从房间名猜档（「客厅」≠「厅堂」，有人客厅很大有人很小）。
     *
     * 用具体房间名做字面；不收单字「室／厅」——它们也是档位名「小室／厅堂」的一部分，
     * 收进去会把自家档名判成硬映射（假红）。
     */
    private val roomNameTokens = listOf(
        "客厅", "卧室", "厨房", "书房", "浴室", "卫生间", "阳台", "餐厅", "走廊",
        "roomName", "roomToGrade", "roomToRegion", "roomType",
    )

    /** 边界③：把屏幕格当参数的函数（禁反推的唯一硬形态）。 */
    private val screenAsParam = Regex("fun\\s+\\w+\\s*\\([^)]*ScreenCell")

    @Test
    fun `边界一_不做渲染不成像`() {
        val text = code()
        renderTokens.forEach { tok -> assertFalse("不得出现 $tok", text.contains(tok)) }
    }

    @Test
    fun `边界二_源码无长度角度单位`() {
        val text = code()
        unitTokens.forEach { tok ->
            assertFalse("源码不得写单位 $tok", hasUnit(text, tok))
        }
    }

    @Test
    fun `边界二_源码无浮点无三角`() {
        val text = code()
        floatTokens.forEach { tok -> assertFalse("不得出现 $tok（格差是整数序号）", text.contains(tok)) }
    }

    @Test
    fun `边界三_没有任何函数把屏幕格当参数`() {
        val text = code()
        assertFalse("屏幕格只出不进：${screenAsParam.findAll(text).map { it.value }}", screenAsParam.containsMatchIn(text))
    }

    @Test
    fun `来源契约_区域档不许从房间名猜档`() {
        val text = code()
        roomNameTokens.forEach { tok ->
            assertFalse("不许把房间名当尺度档：$tok（客厅不等于厅堂）", text.contains(tok))
        }
    }

    @Test
    fun `来源契约_取值来源写进生产源码契约`() {
        // 读**带注释**的源码：取值来源是写给人看的契约，必须在 RegionGrade 的 KDoc 里落地。
        val grades = source(dir + "ViewGrades.kt")
        val regionDoc = grades.substringBefore("enum class RegionGrade").substringAfterLast("/**")
        assertTrue("RegionGrade 必须写明取值来源＝调用方/装配单显式传入", regionDoc.contains("显式传入"))
        assertTrue("必须写明来源依据是场景图的可用空间尺度档", regionDoc.contains("场景图"))
        assertTrue("必须写明「客厅」不等于「厅堂」", regionDoc.contains("客厅") && regionDoc.contains("厅堂"))
    }

    @Test
    fun `边界三_视点本体里不出现屏幕格`() {
        val text = stripComments(source(dir + "Viewpoint.kt"))
        assertFalse("视点如果记住屏幕格，就等着有人拿它反推位置", text.contains("ScreenCell"))
    }

    @Test
    fun `边界四_不求交不射线不算包围盒`() {
        val text = code()
        occlusionTokens.forEach { tok -> assertFalse("遮挡几何不许出现在本包：$tok", text.contains(tok)) }
    }

    @Test
    fun `分工_零装配不落库`() {
        val text = code()
        wiringTokens.forEach { tok -> assertFalse("不得装配或落库 $tok", text.contains(tok)) }
    }

    @Test
    fun `分工_import白名单只有abs`() {
        files.forEach { rel ->
            val imports = source(rel).lines().filter { it.trimStart().startsWith("import ") }
            assertTrue(
                "$rel 要么不导入，要么只导 kotlin.math.abs（不引邻单类型）：$imports",
                imports.isEmpty() || imports == listOf("import kotlin.math.abs"),
            )
        }
    }

    @Test
    fun `计数锁_三档八方位四层`() {
        assertEquals("八方位", 8, Octant.entries.size)
        assertEquals("场景图四层", 4, SceneLayer.entries.size)
        assertEquals("FOV 三档", 3, FovTier.entries.size)
        assertEquals("可见三态", 3, Visibility.entries.size)
        assertEquals("距离三档", 3, DistanceTier.entries.size)
        assertEquals("俯仰三档", 3, PitchTier.entries.size)
        assertEquals("区域三档", 3, RegionGrade.entries.size)
        assertEquals("格位横向三档", 3, Across.entries.size)
        assertEquals("格位纵深三档", 3, Depth.entries.size)
        assertEquals("格位竖向三档", 3, Height.entries.size)
        assertEquals("屏幕横列三档", 3, ScreenColumn.entries.size)
        assertEquals("屏幕纵列三档", 3, ScreenRow.entries.size)
    }

    @Test
    fun `计数锁_源码里三个格位轴都只有三档名`() {
        val text = code()
        listOf("左", "中", "右").forEach { assertTrue("横向缺 $it", text.contains("$it\"")) }
        listOf("后", "前").forEach { assertTrue("纵深缺 $it", text.contains("\"$it\"")) }
        listOf("下", "上").forEach { assertTrue("竖向缺 $it", text.contains("\"$it\"")) }
        assertTrue("四层名要在源码里", listOf("场景", "区域", "物件", "部件").all { text.contains("\"$it\"") })
    }

    // ── 闸有牙：负例注入自检 ───────────────────────────────────────────

    @Test
    fun `闸自检_注入渲染词必被咬住`() {
        val poisoned = stripComments("val img = Bitmap.create(1, 1)")
        assertTrue(renderTokens.any { poisoned.contains(it) })
    }

    @Test
    fun `闸自检_注入长度单位必被咬住`() {
        val poisoned = stripComments("val w = \"约 30" + "c" + "m\"")
        assertTrue(unitTokens.any { hasUnit(poisoned, it) })
    }

    @Test
    fun `闸自检_注入浮点必被咬住`() {
        val poisoned = stripComments("val f = 3.5" + "Float")
        assertTrue(floatTokens.any { poisoned.contains(it) })
    }

    @Test
    fun `闸自检_注入反推函数必被咬住`() {
        val poisoned = stripComments("fun whereIs(cell: ScreenCell): Cell = TODO()")
        assertTrue("把屏幕格当参数必须被咬住", screenAsParam.containsMatchIn(poisoned))
    }

    @Test
    fun `闸自检_注入几何求交必被咬住`() {
        val poisoned = stripComments("val hit = a.intersect(b)")
        assertTrue(occlusionTokens.any { poisoned.contains(it) })
    }

    @Test
    fun `闸自检_注入房间名映射必被咬住`() {
        val poisoned = stripComments("val grade = when (roomName) { \"客厅\" -> RegionGrade.HALL else -> SMALL_ROOM }")
        assertTrue("房间名→档位必须被咬住", roomNameTokens.any { poisoned.contains(it) })
    }

    @Test
    fun `闸自检_注入装配必被咬住`() {
        val poisoned = stripComments("RunEngine.start()")
        assertTrue(wiringTokens.any { poisoned.contains(it) })
    }

    @Test
    fun `闸自检_剥注释后注释里的词不算数`() {
        val onlyInComment = stripComments(
            "// 这里提到 Bitmap 与 " + "c" + "m" + " 还有 ScreenCell(cell: ScreenCell) 只是说明\nval ok = 1",
        )
        assertFalse("注释里的渲染词不该算", renderTokens.any { onlyInComment.contains(it) })
        assertFalse("注释里的单位不该算", unitTokens.any { hasUnit(onlyInComment, it) })
        assertFalse("注释里的假反推不该算", screenAsParam.containsMatchIn(onlyInComment))
    }

    @Test
    fun `闸自检_块注释也要剥掉且可嵌套`() {
        val nested = stripComments("/* 外层 /* 内层 Bitmap */ 还在注释里 */\nval ok = 1")
        assertFalse("嵌套块注释里的词不算", nested.contains("Bitmap"))
        assertTrue("注释外的代码要留着", nested.contains("val ok"))
    }

    /** 单位是否作为独立词出现：缩写后面不许紧跟 ASCII 字母（ScreenCell 之类不算 cm）。 */
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
