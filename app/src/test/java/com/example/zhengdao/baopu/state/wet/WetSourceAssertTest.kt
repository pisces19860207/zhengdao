// 第八百九十七批 · 源码门禁：只读本包 5 个生产件。
// 钉住「零装配、零改邻单、零浮点点值、零渲染/流体、拍口径溯源、蒸汽≠遮挡」，逐闸配负例。
package com.example.zhengdao.baopu.state.wet

import com.example.zhengdao.SrcRoots
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WetSourceAssertTest {

    private val dir = "app/src/main/java/com/example/zhengdao/baopu/state/wet/"
    private val files = listOf(
        "WetGrades.kt", "WetClock.kt", "WetSolver.kt", "WetChecks.kt", "WetRule.kt", "WetRenderer.kt",
    ).map { dir + it }

    private fun raw(): String = files.joinToString("\n") { SrcRoots.find(it).readText() }
    private fun code(): String = files.joinToString("\n") { stripComments(SrcRoots.find(it).readText()) }

    private val wiringTokens = listOf(
        "RunEngine", "Wiring", "Assembler", "ZhengdaoApp", "Context",
        "BaopuSql", "insertRunLog", "persistContract", "contractLine",
    )

    /** 邻单只对齐口径名，一个都不 import、不引用。 */
    private val foreignTokens = listOf(
        "state.surfaceflow.", "state.thermo.", "state.odor.", "state.view.",
        "SurfaceFlow", "ThermoClock", "OdorModel", "VisibilityTier",
    )

    /** 渲染与流体仿真都不是本包的事。 */
    private val effectTokens = listOf(
        "Canvas", "Drawable", "Particle", "Navier", "SurfaceView", "MutableList<Float>",
    )

    private val pointTokens = listOf("Double", "Float", "BigDecimal")

    /** 拼接写单位：运行时连续，生产源码保持拼接形态，扫不到自己。 */
    private val unitTokens = listOf(
        "摄" + "氏度", "m" + "L", "毫" + "升", "厘" + "米", "毫" + "米",
        "滴/秒", "米/秒", "H" + "z",
    )

    private val impureTokens = listOf("Random", "currentTimeMillis", "nanoTime", "java.io", "readText", "getenv")

    /** 五件＋拍口径＋出口都是必要条件，少一个就不算交付。 */
    private val requiredTokens = listOf(
        "enum class SkinWet", "enum class DripSource", "enum class TowelState",
        "enum class SteamFog", "enum class WarmLevel", "enum class WaistTier",
        "const val DT = 1", "const val MAX_STEPS = 6", "recoveryBeats",
        "fun judge(", "WetCodes.DRIP_UPWARD", "WetCodes.FOG_BLOCK_CONFUSION",
        "WetCodes.NUMERIC_VALUE", "WetCodes.FIVE_MISSING", "canSlip",
        "STEAM_MEANS", "OCCLUSION_MEANS", "fun line(", "BodyPartGuard",
    )

    @Test
    fun `边界_零装配零落库零渲染零流体`() {
        val text = code()
        (wiringTokens + effectTokens).forEach { assertFalse("不得装配/落库/做特效:$it", text.contains(it)) }
    }

    @Test
    fun `边界_不改806_730_827_861`() {
        val text = code()
        foreignTokens.forEach { assertFalse("不得依赖邻单:$it", text.contains(it)) }
    }

    @Test
    fun `判据六_禁浮点禁单位字面`() {
        val text = code()
        pointTokens.forEach { assertFalse("不得用浮点:$it", text.contains(it)) }
        unitTokens.forEach { assertFalse("源码不得写单位字面:$it", containsUnitWord(text, it)) }
    }

    @Test
    fun `边界_无IO无随机无时钟源`() {
        val text = code()
        impureTokens.forEach { assertFalse("纯函数层不得出现:$it", text.contains(it)) }
    }

    @Test
    fun `口径_拍预算标明计划口径未合并指向889`() {
        val text = raw()
        assertTrue("必须标注计划口径/未合", "计划口径" in text && "未合" in text)
        assertTrue("必须指向 889 溯源", "889" in text)
        assertFalse("不许写成依 726 实现", "依 726 实现" in text)
    }

    @Test
    fun `判据五_蒸汽与遮挡两句分工都在源码里`() {
        val text = code()
        assertTrue("必须声明蒸汽=看不清", text.contains("STEAM_MEANS") && text.contains("看不清"))
        assertTrue("必须声明遮挡=被挡住", text.contains("OCCLUSION_MEANS") && text.contains("被挡住"))
    }

    @Test
    fun `交付_五件加判据加出口一处不少`() {
        val text = code()
        requiredTokens.forEach { assertTrue("交付缺件:$it", text.contains(it)) }
    }

    // ── 闸有牙：负例注入自检 ─────────────────────────────────────────────

    @Test
    fun `闸自检_注入邻单必被咬住`() {
        val poisoned = stripComments("import com.example.zhengdao.baopu.state.thermo.Foo")
        assertTrue(foreignTokens.any { poisoned.contains(it) })
    }

    @Test
    fun `闸自检_注入浮点必被咬住`() {
        val poisoned = stripComments("val t: Double = 36.5")
        assertTrue(pointTokens.any { poisoned.contains(it) })
    }

    @Test
    fun `闸自检_注入连续单位必被咬住`() {
        assertTrue(containsUnitWord(stripComments("val s = \"三十摄氏度\""), "摄" + "氏度"))
        assertFalse(containsUnitWord(stripComments("val s = \"普通叙述\""), "摄" + "氏度"))
    }

    @Test
    fun `闸自检_抽掉遮挡判据码即失去交付资格`() {
        val broken = code().replace("WetCodes.FOG_BLOCK_CONFUSION", "X")
        assertFalse(requiredTokens.all { broken.contains(it) })
    }

    @Test
    fun `闸自检_抽掉最低部位判据即失去交付资格`() {
        val broken = code().replace("WetCodes.DRIP_UPWARD", "X")
        assertFalse(requiredTokens.all { broken.contains(it) })
    }

    @Test
    fun `闸自检_剥注释后注释里的词不算数`() {
        val only = stripComments(
            "// Double、RunEngine、state.thermo.、摄氏度只是注释\n/* 被挡住 */\nval ok = 1"
        )
        (pointTokens + wiringTokens + foreignTokens + unitTokens).forEach {
            assertFalse("注释词不该算数:$it", only.contains(it))
        }
        assertFalse("被挡住" in only)
    }

    /** 单位作为独立词出现：缩写后面紧跟 ASCII 字母不算。 */
    private fun containsUnitWord(text: String, unit: String): Boolean {
        var i = text.indexOf(unit)
        while (i >= 0) {
            val after = i + unit.length
            if (after >= text.length || !text[after].isAsciiLetter()) return true
            i = text.indexOf(unit, i + 1)
        }
        return false
    }

    private fun Char.isAsciiLetter(): Boolean = this in 'a'..'z' || this in 'A'..'Z'

    /** 行/块注释整段换空格（保留换行），字符串原样保留；Kotlin 块注释可嵌套。 */
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
