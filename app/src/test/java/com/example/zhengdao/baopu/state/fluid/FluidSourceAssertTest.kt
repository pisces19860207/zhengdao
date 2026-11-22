// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第七百八十批 · 源码门禁：只读本单 5 个生产源码，钉住「零装配、不进闸、不改 658/735、
// 不给点值、不建摆动模型、不重记排出两型」。每道闸配负例注入自检——不是「文件含字面量」恒真。
package com.example.zhengdao.baopu.state.fluid

import com.example.zhengdao.SrcRoots
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FluidSourceAssertTest {

    private val dir = "app/src/main/java/com/example/zhengdao/baopu/state/fluid/"
    private val files = listOf(
        "FluidGrades.kt", "FluidModel.kt", "FluidRules.kt", "FluidChecks.kt", "FluidRenderer.kt",
    ).map { dir + it }

    private fun code(): String = files.joinToString("\n") { stripComments(SrcRoots.find(it).readText()) }

    /** 装配 / 引擎入口：一字不许出现。 */
    private val assemblyTokens = listOf("RunEngine", "Wiring", "Assembler", "ZhengdaoApp", "attach(", "Context")

    /** 闸 / 落库：一字不许出现。 */
    private val gateTokens = listOf("RiskMatrix", "Gate", "BaopuSql", "insertRunLog", "contractLine", "persistContract")

    /** 邻单：658 的 physiology、735 的 prostate、731 的 motion 都不许引。 */
    private val foreignTokens = listOf(
        "state.physiology", "state.prostate", "state.motion", "FluidAmount", "ExpulsionKind",
    )

    /** 735 的排出两型各有一个专名，本单不许借用（借了就是重记那一笔）。 */
    private val foreignWords = listOf("射精", "射出", "流出")

    /** 点值单位：检查器自身用拼接/转义写，所以这里扫不到自己。 */
    private val unitLiterals = listOf("mL", "ml", "cm", "米", "秒", "pH", "成分", "毫升", "厘米")

    /** 摆动归 731：本单连词都不许有。 */
    private val pendulumWords = listOf("pendulum", "摆动", "周期")

    /** 无 IO、无随机、无时钟。 */
    private val impureTokens = listOf("Random", "currentTimeMillis", "nanoTime", "java.io", "readText", "getenv")

    @Test
    fun `边界_零装配`() {
        val text = code()
        assemblyTokens.forEach { tok -> assertFalse("不得装配 $tok", text.contains(tok)) }
    }

    @Test
    fun `边界_零闸零落库`() {
        val text = code()
        gateTokens.forEach { tok -> assertFalse("不得进闸 $tok", text.contains(tok)) }
    }

    @Test
    fun `边界_不改658与735`() {
        val text = code()
        foreignTokens.forEach { tok -> assertFalse("不得引用邻单 $tok", text.contains(tok)) }
        foreignWords.forEach { tok -> assertFalse("不得借用 735 的排出两型专名 $tok", text.contains(tok)) }
        assertEquals("658 量档四档", 4, VolumeGrade.COUNT)
        assertEquals("本单三型，两型那笔归 735", 3, DepartureKind.SINGLE_COUNT)
    }

    @Test
    fun `判据七_源码内无点值单位字面`() {
        val text = code()
        unitLiterals.forEach { tok -> assertFalse("源码不得写单位字面 $tok", text.contains(tok)) }
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
    fun `判据八_六节各有一个渲染出口`() {
        val text = code()
        listOf("四维[", "混合[", "垂坠=", "织物[", "覆盖=", "并存[").forEach { mark ->
            assertTrue("渲染出口缺一节 $mark", text.contains(mark))
        }
    }

    @Test
    fun `码册_不许重复`() {
        val fields = FluidCodes::class.java.declaredFields.filter { it.type == String::class.java }
        fields.forEach { it.isAccessible = true }
        val codes = fields.map { it.get(null) as String }
        assertTrue("码册至少 20 条", codes.size >= 20)
        assertEquals("码册不许重复", codes.size, codes.toSet().size)
    }

    @Test
    fun `口径_量档与658同标度`() {
        val theirs = com.example.zhengdao.baopu.state.physiology.FluidAmount.values().map { it.level to it.label }
        val ours = VolumeGrade.values().map { it.level to it.label }
        assertEquals("量档 0–3 四档、档名逐字相同", theirs, ours)
    }

    // ── 闸有牙：负例注入自检 ─────────────────────────────────────────────

    @Test
    fun `闸自检_注入点值必被咬住`() {
        val poisoned = stripComments("val bad = \"37pH 5毫升 12cm\"")
        assertTrue("注入的点值必须被闸咬住", unitLiterals.any { poisoned.contains(it) })
    }

    @Test
    fun `闸自检_注入摆动词必被咬住`() {
        assertTrue("摆动词要被咬住", pendulumWords.any { stripComments("val p = \"摆动\"").contains(it) })
    }

    @Test
    fun `闸自检_注入他单专名必被咬住`() {
        assertTrue("735 专名要被咬住", foreignWords.any { stripComments("val x = \"射出\"").contains(it) })
    }

    @Test
    fun `闸自检_剥注释后注释里的词不算数`() {
        val only = stripComments("// 这里只提到 12cm 与 摆动 作说明\nval ok = 1")
        assertFalse("注释里的点值不该算违规", unitLiterals.any { only.contains(it) })
        assertFalse("注释里的摆动词不该算违规", pendulumWords.any { only.contains(it) })
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
