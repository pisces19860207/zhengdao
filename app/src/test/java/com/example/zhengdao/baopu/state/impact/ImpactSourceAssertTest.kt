// 独立开发声明：本文件为本项目原创，不含第三方源码的拷贝。
//
// 第七百二十七批 · 源码门禁：剥注释后扫真代码，钉住「分段表无连续公式、五输出齐、
// 敏感区分档、七力矩阵、扭转只写现象、routine 不进矩阵、无伤情分级、
// 覆盖位/皮量进输入」。每道闸配负例注入自检——不是「文件含字面量」恒真。
package com.example.zhengdao.baopu.state.impact

import com.example.zhengdao.SrcRoots
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImpactSourceAssertTest {

    private val dir = "app/src/main/java/com/example/zhengdao/baopu/state/impact/"
    private val files = listOf(
        "ImpactGrades.kt", "ImpactTable.kt", "ImpactMatrix.kt",
        "ImpactRoutine.kt", "ImpactChecks.kt", "ImpactSheet.kt",
    )

    private val sources: List<Pair<String, String>> = files.map { it to SrcRoots.find(dir + it).readText() }

    private fun code(): String = sources.joinToString("\n") { (_, src) -> stripComments(src) }

    private fun codeOf(name: String): String = stripComments(SrcRoots.find(dir + name).readText())

    /** 连续公式 / 积分器 / 数值单位：一字不许进生产源码。 */
    private val formulaTokens = listOf(
        "toDouble", "toFloat", "Math.", "sqrt", "pow(", "sin(", "cos(", "exp(",
        "integrator", "integrate(", "timestep", "time_step", "deltaTime",
        "fun step(", ".step(", "simulate(", "牛顿", "焦耳", "帕斯卡", "厘米", "毫米",
    )

    /** 装配 / 落库 / 随机时钟：本包不碰。 */
    private val impureTokens = listOf(
        "ZhengdaoApp", "StateSummaryWiring", "Wiring", "attach(", "Context",
        "insertRunLog", "contractLine", "BaopuSql", "Random", "currentTimeMillis",
        "nanoTime", "java.io", "readText", "getenv", "File(",
    )

    /** 医学红线：伤情 / 疼痛分级词在源码里只以拼接靶表存在，剥注释后不得成词。 */
    private val medicalTokens = listOf(
        "几级伤", "伤情等级", "疼痛等级", "要不要就医", "需就医", "看医生", "复位手法",
    )

    /** 扭转动词的处置词：不许出现在矩阵生产源码的代码部分。 */
    private val torsionDisposal = listOf("复位", "手法", "手术", "就診", "就医")

    /** 日常动作词：不许出现在矩阵源码（routine 单独成包）。 */
    private val routineWords = listOf("掏", "拉链", "解裤", "坐下", "起身", "排尿")

    @Test
    fun `判据一_三段映射是分段表不是连续公式`() {
        val table = codeOf("ImpactTable.kt")
        listOf("ENERGY", "SPREAD", "DIVIDED", "TIER_OF", "BAND").forEach { tok ->
            assertTrue("三段表缺 $tok", table.contains(tok))
        }
        formulaTokens.forEach { tok -> assertFalse("连续公式/积分器不得出现 $tok", table.contains(tok)) }
        // 表必须逐项可查：数组字面量 + 显式索引，而不是表达式。
        assertTrue("能量表须是数组字面量", table.contains("arrayOf("))
        assertTrue("查表须按档索引", table.contains("DIVIDED[energy][SPREAD[area.level] - 1]"))
    }

    @Test
    fun `判据二_五输出字段齐`() {
        val grades = codeOf("ImpactGrades.kt")
        listOf("val impact: ImpactTier", "val displace: DisplaceTier", "val flatten: FlattenTier",
            "val recovery: RecoveryTier", "val words: List<String>").forEach { field ->
            assertTrue("五输出缺 $field", grades.contains(field))
        }
        val sheet = codeOf("ImpactSheet.kt")
        assertTrue("出口须写冲击档", sheet.contains("冲击="))
        assertTrue("出口须写位移档", sheet.contains("位移="))
        assertTrue("出口须写压扁档", sheet.contains("压扁="))
        assertTrue("出口须写恢复档", sheet.contains("恢复="))
        assertTrue("出口须写后果词", sheet.contains("后果="))
    }

    @Test
    fun `判据二_唯一出口过805闸`() {
        val sheet = codeOf("ImpactSheet.kt")
        assertTrue("须 import BodyPartGuard", sheet.contains("BodyPartGuard"))
        listOf("rejectMeasures", "rejectAbsoluteColor", "rejectDiagnosis").forEach { gate ->
            assertTrue("出口须过 $gate", sheet.contains(gate))
        }
    }

    @Test
    fun `判据三_敏感区与柱身根部在源码中分开且带依据`() {
        val text = code()
        listOf("SENSITIVE_SITES", "ROBUST_SITES", "GLANS", "FRENULUM", "CORONA", "SHAFT", "ROOT").forEach { tok ->
            assertTrue("敏感区分档缺 $tok", text.contains(tok))
        }
        assertTrue("依据须标 ◐", text.contains("◐"))
        val matrix = codeOf("ImpactMatrix.kt")
        assertTrue("分离判据须真实存在", matrix.contains("sensitivitySeparated"))
    }

    @Test
    fun `判据四_七类力齐且矩阵逐格有档`() {
        val grades = codeOf("ImpactGrades.kt")
        listOf("COMPRESSION", "BLUNT", "FRICTION", "TRACTION", "SHEAR", "TORSION", "SUCTION").forEach { tok ->
            assertTrue("七力缺 $tok", grades.contains("ForceKind.$tok") || grades.contains("$tok(\""))
        }
        val matrix = codeOf("ImpactMatrix.kt")
        assertTrue("七力表须齐", matrix.contains("MATRIX_TIER"))
        assertTrue("须有是否允许描写", matrix.contains("allowDepict"))
        assertTrue("不适用须落字", matrix.contains("不适用"))
        assertTrue("负压消退档须落字", matrix.contains("WITHDRAWAL_OF"))
        assertTrue("异档计数判据须在场", matrix.contains("distinctCount"))
    }

    @Test
    fun `判据四_扭转源码不写处置`() {
        val matrix = codeOf("ImpactMatrix.kt")
        torsionDisposal.forEach { tok -> assertFalse("扭转处置词不得进矩阵源码：$tok", matrix.contains(tok)) }
        assertTrue("扭转形态须只列现象", matrix.contains("\"剧痛、失能\""))
    }

    @Test
    fun `判据五_日常动作不进矩阵源码`() {
        val matrix = codeOf("ImpactMatrix.kt")
        routineWords.forEach { tok -> assertFalse("日常动作不得进矩阵：$tok", matrix.contains(tok)) }
        val routine = codeOf("ImpactRoutine.kt")
        assertTrue("豁免包须存在", routine.contains("ImpactRoutine"))
        assertTrue("rare 封顶须在源码中", routine.contains("RARE_EDGE_MAX_TIER"))
        assertFalse("rare 不得封顶 2", routine.contains("= 2"))
    }

    @Test
    fun `判据六_源码无伤情疼痛分级词`() {
        val text = code()
        medicalTokens.forEach { tok -> assertFalse("医学红线词不得成词：$tok", text.contains(tok)) }
    }

    @Test
    fun `判据七_覆盖位804_831与皮量810进输入`() {
        val grades = codeOf("ImpactGrades.kt")
        listOf("AreaLevel", "CoverState", "SkinAmount", "804", "831", "810").forEach { tok ->
            assertTrue("输入缺覆盖位/皮量引用 $tok", grades.contains(tok))
        }
        val checks = codeOf("ImpactChecks.kt")
        assertTrue("未引用须有提示分支", checks.contains("FindingLevel.NOTE"))
        assertTrue("831 退至冠后须被引用", checks.contains("RETRACTED_BEHIND_CORONA"))
        val matrix = codeOf("ImpactMatrix.kt")
        assertTrue("矩阵缓冲吃覆盖位", matrix.contains("coverage: AreaLevel?"))
        assertTrue("矩阵缓冲吃皮量", matrix.contains("skin: SkinAmount?"))
    }

    @Test
    fun `段B_提睾只引用730结论且事件双来源双档齐全`() {
        val grades = codeOf("ImpactGrades.kt")
        listOf(
            "val subzone: SubzoneId?",
            "val consequenceTier: ConsequenceTier",
            "val sources: Set<String>",
        ).forEach { field -> assertTrue("事件契约缺 $field", grades.contains(field)) }
        assertEquals(
            "ImpactInput 与 ImpactEvent 必须各有一份 730 提睾档",
            2,
            Regex("val cremastericTier: CremastericGrade").findAll(grades).count(),
        )
        val text = code()
        listOf(
            "ThermoInput", "Thermo.judge", "ThermoEval", "ThermoSolver", "ThermoClock",
            "ThermalBand", "HumidityBand", "CremasterTrigger",
        ).forEach { token -> assertFalse("727 不得自判或重算提睾：$token", text.contains(token)) }
        val sheetSource = SrcRoots.find(dir + "ImpactSheet.kt").readText()
        assertTrue("同拍受击契约须写明 steps = 1", sheetSource.contains("steps = 1"))
        assertTrue("出口须透传 730 档", codeOf("ImpactSheet.kt").contains("cremastericTier = input.cremastericTier"))
        assertTrue("出口须透传 727 后果档", codeOf("ImpactSheet.kt").contains("consequenceTier = consequence"))
    }

    @Test
    fun `边界_无积分器无装配无IO无随机`() {
        val text = code()
        impureTokens.forEach { tok -> assertFalse("边界被碰：$tok", text.contains(tok)) }
    }

    @Test
    fun `防闸空转_六份主源码都真被读到且剥注释后仍有代码`() {
        assertEquals(6, sources.size)
        for ((name, src) in sources) {
            assertTrue("$name 剥注释后没有代码（闸空转）", stripComments(src).trim().length > 200)
        }
    }

    // ── 闸有牙：负例注入自检 ─────────────────────────────────────────────

    @Test
    fun `闸自检_注入连续公式必被咬住`() {
        val poisoned = stripComments("val x = speed.toDouble() / area")
        assertTrue(formulaTokens.any { poisoned.contains(it) })
    }

    @Test
    fun `闸自检_注入医学红线词必被咬住`() {
        val poisoned = stripComments("val s = \"几级伤\"")
        assertTrue(medicalTokens.any { poisoned.contains(it) })
    }

    @Test
    fun `闸自检_注入日常动作与处置词必被咬住`() {
        val dirty = stripComments("val s = \"拉链夹到，复位\"")
        assertTrue(routineWords.any { dirty.contains(it) })
        assertTrue(torsionDisposal.any { dirty.contains(it) })
    }

    @Test
    fun `闸自检_剥注释后注释里的词不算数`() {
        val only = stripComments("// 这里只提到 几级伤 与 拉链 作说明\nval ok = 1\n/* 复位 */")
        assertFalse(medicalTokens.any { only.contains(it) })
        assertFalse(routineWords.any { only.contains(it) })
        assertFalse(torsionDisposal.any { only.contains(it) })
    }

    @Test
    fun `闸自检_嵌套块注释与字符串分别处理`() {
        val nested = stripComments("/* 外 /* 内 */ 尾 */ val ok = 1")
        assertFalse(nested.contains("内"))
        assertTrue(nested.contains("val ok = 1"))
        val inString = stripComments("val s = \"拉链\"")
        assertTrue("字符串不剥：门禁仍能扫到字面量", inString.contains("拉链"))
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
            else { if (src[i] != '\n') out.append(' ') else out.append('\n'); i++ }
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
