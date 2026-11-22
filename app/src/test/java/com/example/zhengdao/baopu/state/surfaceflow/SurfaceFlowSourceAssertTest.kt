// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第八百零六批 · 源码门禁：只读本单 5 个生产源码，钉住「零装配、零 IO、不引邻单、
// 不收体液、不给点值、只对齐 780 口径不依赖其代码」。每道闸配负例注入自检。
package com.example.zhengdao.baopu.state.surfaceflow

import com.example.zhengdao.SrcRoots
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SurfaceFlowSourceAssertTest {

    private val dir = "app/src/main/java/com/example/zhengdao/baopu/state/surfaceflow/"
    private val files = listOf(
        "SurfaceFlowGrades.kt",
        "SurfaceFlowChecks.kt",
        "SurfaceFlowModel.kt",
        "SurfaceFlowRules.kt",
        "SurfaceFlowRenderer.kt",
    ).map { dir + it }

    private fun code(): String = files.joinToString("\n") { stripComments(SrcRoots.find(it).readText()) }

    /** 邻单：一个字都不许依赖（只按口径对齐，不 import）。 */
    private val foreignTokens = listOf(
        "state.fluid", "state.physiology", "state.bodyparts",
        "VolumeGrade", "FluidAmount", "BodyPart", "FluidKind",
    )

    /** 体液专名：分泌与混合归邻单，本包不收。 */
    private val foreignWords = listOf("精液", "唾液", "前液", "semen", "saliva")

    /** 装配 / 闸 / 落库：一字不许出现。 */
    private val wiringTokens = listOf(
        "RunEngine", "Wiring", "Assembler", "ZhengdaoApp", "Context",
        "RiskMatrix", "BaopuSql", "insertRunLog", "contractLine", "persistContract",
    )

    /** 点值字段与单位（检查器自身用拼接写，扫不到自己）。 */
    private val pointTokens = listOf("Double", "Float", "c" + "m", "m" + "L", "H" + "z", "velocity", "frequency")

    /** 摆动归别的单。 */
    private val pendulumWords = listOf("pendulum", "摆动", "周期")

    /** 无 IO、无随机、无时钟。 */
    private val impureTokens = listOf("Random", "currentTimeMillis", "nanoTime", "java.io", "readText", "getenv")

    @Test
    fun `边界_零改既有_不依赖780代码`() {
        val text = code()
        foreignTokens.forEach { tok -> assertFalse("不得依赖邻单 $tok", text.contains(tok)) }
        foreignWords.forEach { tok -> assertFalse("不得收体液专名 $tok", text.contains(tok)) }
    }

    @Test
    fun `边界_零装配零闸零落库`() {
        val text = code()
        wiringTokens.forEach { tok -> assertFalse("不得装配或进闸 $tok", text.contains(tok)) }
    }

    @Test
    fun `判据七_源码内无点值单位字面`() {
        val text = code()
        pointTokens.forEach { tok -> assertFalse("源码不得写点值 $tok", text.contains(tok)) }
    }

    @Test
    fun `边界_不建摆动模型`() {
        val text = code()
        pendulumWords.forEach { tok -> assertFalse("不得出现 $tok", text.contains(tok)) }
    }

    @Test
    fun `判据八_无IO无随机无时钟`() {
        val text = code()
        impureTokens.forEach { tok -> assertFalse("纯函数层不得出现 $tok", text.contains(tok)) }
    }

    @Test
    fun `口径_量档与780逐字对齐但不引用其类型`() {
        // 780 的量档口径：0–3 无/微/中/多。这里硬编码期望，不 import 它的枚举。
        val theirs = listOf(0 to "无", 1 to "微", 2 to "中", 3 to "多")
        val ours = FlowVolume.entries.map { it.level to it.label }
        assertEquals(theirs, ours)
    }

    @Test
    fun `口径_体位与780逐字对齐但不引用其类型`() {
        val theirs = listOf("站立", "坐姿", "仰卧", "俯卧", "侧卧")
        assertEquals(theirs, FlowPosture.entries.map { it.label })
    }

    @Test
    fun `码册_不许重复且覆盖八条判据`() {
        assertEquals(SurfaceCodes.ALL.size, SurfaceCodes.ALL.toSet().size)
        assertTrue("至少 18 条码", SurfaceCodes.ALL.size >= 18)
        assertTrue(SurfaceCodes.ALL.containsAll(
            listOf(
                SurfaceCodes.UNIFORM_RUNOFF, SurfaceCodes.POOL_MISSING_STERNAL, SurfaceCodes.DRIP_NO_LOWEST,
                SurfaceCodes.PREPUCE_NO_BASIS, SurfaceCodes.LARYNX_NO_SPLIT, SurfaceCodes.HAIR_DRY,
                SurfaceCodes.NUMERIC_VALUE,
            )
        ))
    }

    @Test
    fun `渲染出口_四件各一次`() {
        val text = code()
        listOf("paths = renderPaths", "poolAt = renderPools", "dripAt = renderDrips", "posture = value.posture.label")
            .forEach { mark -> assertTrue("渲染出口缺 $mark", text.contains(mark)) }
    }

    // ── 闸有牙：负例注入自检 ─────────────────────────────────────────────

    @Test
    fun `闸自检_注入邻单引用必被咬住`() {
        assertTrue(foreignTokens.any { stripComments("val x: VolumeGrade = ???").contains(it) })
    }

    @Test
    fun `闸自检_注入体液专名必被咬住`() {
        assertTrue(foreignWords.any { stripComments("val s = \"精液\"").contains(it) })
    }

    @Test
    fun `闸自检_注入点值必被咬住`() {
        val poisoned = stripComments("val v = 3" + "cm")
        assertTrue(pointTokens.any { poisoned.contains(it) })
    }

    @Test
    fun `闸自检_注入装配必被咬住`() {
        assertTrue(wiringTokens.any { stripComments("class X : RunEngine()").contains(it) })
    }

    @Test
    fun `闸自检_剥注释后注释里的词不算数`() {
        val only = stripComments("// 提到 VolumeGrade、12cm、摆动 只是说明\nval ok = 1")
        foreignTokens.forEach { assertFalse(only.contains(it)) }
        pointTokens.forEach { assertFalse(only.contains(it)) }
        pendulumWords.forEach { assertFalse(only.contains(it)) }
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
            else { if (src[i] != '\n') out.append(' '); else out.append('\n'); i++ }
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
