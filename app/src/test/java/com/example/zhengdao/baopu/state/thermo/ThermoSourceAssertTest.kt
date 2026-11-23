// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第七百三十批 · 源码门禁：只读本单 8 个生产源码，钉住「零装配、零落库、不改邻单、
// 禁连续量、禁第二套气象、禁病理温度、有仿真推进路径」。每道闸配负例注入自检。
package com.example.zhengdao.baopu.state.thermo

import com.example.zhengdao.SrcRoots
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThermoSourceAssertTest {

    private val dir = "app/src/main/java/com/example/zhengdao/baopu/state/thermo/"
    private val files = listOf(
        "ThermoGrades.kt", "ThermoModel.kt", "ThermoClock.kt", "ThermoRules.kt",
        "ThermoEval.kt", "ThermoChecks.kt", "ThermoRenderer.kt", "Thermo.kt",
    ).map { dir + it }

    private fun code(): String = files.joinToString("\n") { stripComments(SrcRoots.find(it).readText()) }

    /** 装配 / 引擎入口 / 落库：一字不许出现。 */
    private val wiringTokens = listOf(
        "RunEngine", "Wiring", "Assembler", "ZhengdaoApp", "Context",
        "BaopuSql", "insertRunLog", "persistContract", "contractLine",
    )

    /** 判定闸：本包是纯产出层，不进任何闸。 */
    private val gateTokens = listOf("RiskMatrix", "gatePass", "verdictGate", "TaskModeBoundary")

    /** 邻单专属类型：不改 657/686/726/658，一个都不 import。 */
    private val foreignTokens = listOf(
        "state.environment.", "state.cavity.", "state.stimulus.", "state.physiology.",
        "EnvironmentSolver", "HumiditySolver", "CavityMap", "GravityContact",
        "LightPhysiology", "Adaptation", "Recovery",
    )

    /** 第二套气象计算词：只读 657 档，不自造天气。 */
    private val weatherTokens = listOf(
        "latitudeDeg", "dayOfYear", "solarTime", "SolarSolver", "beaufortOf", "rhPct",
        "tempC", "日照角", "纬度计算",
    )

    /** 连续量字段类型：对外与内部推进都不许浮点。 */
    private val pointTokens = listOf("Double", "Float", "BigDecimal")

    /**
     * 点值单位字面（拼接写，运行时值连续、生产源码里是拼接形式，扫不到自己）。
     * 只收能在生产源码中保持拼接形态的词；单字符单位（如某温度符号）无法拆，不列。
     */
    private val unitTokens = listOf(
        "摄" + "氏度", "c" + "m", "m" + "m", "m" + "L", "H" + "z",
        "厘" + "米", "毫" + "米", "毫" + "升",
    )

    /** 病理性温度词（拼接写）。 */
    private val pathoTokens = listOf("发" + "热", "炎" + "症", "感" + "染", "病" + "变")

    /** 无 IO、无随机、无时钟源。 */
    private val impureTokens = listOf("Random", "currentTimeMillis", "nanoTime", "java.io", "readText", "getenv")

    @Test
    fun `边界_零装配零落库零闸`() {
        val text = code()
        (wiringTokens + gateTokens).forEach { assertFalse("不得装配/落库/进闸:$it", text.contains(it)) }
    }

    @Test
    fun `边界_不改657_686_726_658`() {
        val text = code()
        foreignTokens.forEach { assertFalse("不得依赖或改邻单:$it", text.contains(it)) }
    }

    @Test
    fun `判据_不重算天气只读657档`() {
        val text = code()
        weatherTokens.forEach { assertFalse("不得自造气象:$it", text.contains(it)) }
    }

    @Test
    fun `ERRATA_禁连续量_无浮点无单位字面`() {
        val text = code()
        pointTokens.forEach { assertFalse("不得用浮点:$it", text.contains(it)) }
        // 单位按独立词判：缩写后紧跟 ASCII 字母不算（否则 liftBaseline 这类标识符会误咬）。
        unitTokens.forEach { assertFalse("源码不得写单位字面:$it", containsUnitWord(text, it)) }
    }

    /** 单位作为独立词出现：命中位置后面不许紧跟 ASCII 字母。 */
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

    @Test
    fun `边界_禁病理性温度`() {
        val text = code()
        pathoTokens.forEach { assertFalse("不得写病理词:$it", text.contains(it)) }
    }

    @Test
    fun `边界_无IO无随机无时钟源`() {
        val text = code()
        impureTokens.forEach { assertFalse("纯函数层不得出现:$it", text.contains(it)) }
    }

    @Test
    fun `ERRATA_确有仿真推进路径非纯查表`() {
        val text = code()
        assertTrue("必须有固定dt常量", text.contains("const val DT = 1"))
        assertTrue("必须有步上限常量", text.contains("const val MAX_STEPS = 6"))
        assertTrue("必须有单拍推进函数", text.contains("fun step("))
        assertTrue("必须有多拍推进函数", text.contains("fun advance("))
        assertTrue("必须有量化出口", text.contains("fun toGrade("))
    }

    @Test
    fun `口径_血管档0到2对齐731`() {
        val text = code()
        assertTrue(text.contains("VESSEL_VISIBLE_MAX = 2"))
        assertTrue(text.contains("WET_FILM_MAX = 3"))
        assertTrue(text.contains("COLOR_SHIFT_MAX = 3"))
    }

    // ── 闸有牙：负例注入自检 ─────────────────────────────────────────────

    @Test
    fun `闸自检_注入装配必被咬住`() {
        assertTrue(wiringTokens.any { stripComments("class X : RunEngine()").contains(it) })
    }

    @Test
    fun `闸自检_注入邻单必被咬住`() {
        assertTrue(foreignTokens.any { stripComments("import x.state.environment.Foo").contains(it) })
    }

    @Test
    fun `闸自检_注入浮点必被咬住`() {
        val poisoned = stripComments("val t: Double = 36.5")
        assertTrue(pointTokens.any { poisoned.contains(it) })
    }

    @Test
    fun `闸自检_注入病理必被咬住`() {
        // 负例源文本里写连续词（测试文件不被生产门禁扫，可直接写），证明闸对连续词有牙。
        val poisoned = stripComments("val s = \"发热\"")
        assertTrue(pathoTokens.any { poisoned.contains(it) })
    }

    @Test
    fun `闸自检_注入气象计算必被咬住`() {
        assertTrue(weatherTokens.any { stripComments("val lat = latitudeDeg").contains(it) })
    }

    @Test
    fun `闸自检_剥注释后注释里的词不算数`() {
        val only = stripComments("// 提到 Double、RunEngine、发热、纬度计算只是说明\nval ok = 1")
        (pointTokens + wiringTokens + pathoTokens + weatherTokens).forEach {
            assertFalse("注释词不该算数:$it", only.contains(it))
        }
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
