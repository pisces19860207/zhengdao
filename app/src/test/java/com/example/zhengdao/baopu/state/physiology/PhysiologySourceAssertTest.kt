// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百五十八批 · 源码门禁：只读本单 5 个生产源码，钉住「零装配、不进闸、不改 660、
// 不碰 735/732、不给点值、三液不合并」。每道闸配负例注入自检——不是文件含字面量就过。
package com.example.zhengdao.baopu.state.physiology

import com.example.zhengdao.SrcRoots
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhysiologySourceAssertTest {

    private val dir = "app/src/main/java/com/example/zhengdao/baopu/state/physiology/"
    private val files = listOf(
        "PhysioGrades.kt", "LightPhysiology.kt", "LightPhysiologySolver.kt",
        "PhysiologyChecks.kt", "PhysioRenderer.kt",
    ).map { dir + it }

    private fun code(): String = files.joinToString("\n") { stripComments(source(it)) }
    private fun source(rel: String): String = SrcRoots.find(rel).readText()

    /** 装配 / 引擎入口：一字不许出现。 */
    private val assemblyTokens = listOf(
        "RunEngine", "StateSummaryWiring", "CharacterTrack", "ZhengdaoApp",
        "attach(", "assemble(", "ContextAssembler",
    )

    /** 闸 / 落库 / 判定：一字不许出现。 */
    private val gateTokens = listOf(
        "RiskMatrix", "Gate", "BaopuSql", "insertRunLog", "persistContractLine",
        "contractLine", "TaskModeBoundary", "gatePass", "verdict",
    )

    /** 邻单专属：不改 660、不碰 735 前列腺、不碰 732 肛管、不碰 657 环境出汗。 */
    private val foreignImports = listOf(
        "state.form.BodyState", "state.prostate.", "state.stimulus.",
        "state.environment.", "ProstateChannel", "WetnessSource", "HumiditySolver",
    )

    /** 生产源码里不许出现任何完整物理量单位字面（检查器自身用转义/拼接，不自咬）。 */
    private val unitLiterals = listOf("℃", "°C", "bpm", "毫升", "ml", "cm", "kg")

    /** 生产源码里不许出现合并词 / 他单账词字面（常量表用拼接写，不自咬）。 */
    private val mergeLiterals = listOf("体液", "分泌物", "bodyFluid", "secretion")
    private val foreignLiterals = listOf("前列腺液", "前列腺", "精液", "射精", "排出", "肛管", "直肠")

    @Test
    fun `边界_零装配`() {
        val text = code()
        assemblyTokens.forEach { tok -> assertFalse("不得装配 $tok", text.contains(tok)) }
    }

    @Test
    fun `边界_零闸零判定`() {
        val text = code()
        gateTokens.forEach { tok -> assertFalse("不得进闸 $tok", text.contains(tok)) }
    }

    @Test
    fun `边界_不改660不碰735732`() {
        val text = code()
        foreignImports.forEach { tok -> assertFalse("不得引用邻单 $tok", text.contains(tok)) }
    }

    @Test
    fun `判据四_源码内无点值单位字面`() {
        val text = code()
        unitLiterals.forEach { tok -> assertFalse("源码不得写单位字面 $tok", text.contains(tok)) }
    }

    @Test
    fun `判据二五_源码内无合并词与他单账词字面`() {
        val text = code()
        (mergeLiterals + foreignLiterals).forEach { tok ->
            assertFalse("源码不得出现 $tok", text.contains(tok))
        }
    }

    @Test
    fun `判据二_唯一出口确实拼出三条分道`() {
        val text = code()
        assertTrue("渲染出口须点名汗", text.contains("三液[汗="))
        assertTrue("渲染出口须点名唾液", text.contains("/唾液="))
        assertTrue("渲染出口须点名前液", text.contains("/前液="))
    }

    // ── 闸有牙：负例注入自检（记忆纪律：不许「文件含某字面量」恒真）────────

    @Test
    fun `闸自检_注入点值字面必被咬住`() {
        val poisoned = stripComments("val bad = \"37℃ 80bpm 5毫升\"")
        assertTrue("注入的点值必须被闸咬住", unitLiterals.any { poisoned.contains(it) })
    }

    @Test
    fun `闸自检_注入合并词必被咬住`() {
        val poisoned = stripComments("val bad = \"体液\"")
        assertTrue("注入的合并词必须被闸咬住", mergeLiterals.any { poisoned.contains(it) })
    }

    @Test
    fun `闸自检_注入他单账词必被咬住`() {
        val poisoned = stripComments("val bad = \"前列腺液\"")
        assertTrue("注入的他单账词必须被闸咬住", foreignLiterals.any { poisoned.contains(it) })
    }

    @Test
    fun `闸自检_剥注释后注入的注释词不算数`() {
        val onlyInComment = stripComments("// 这里只提到 bpm 与 体液 作为说明\nval ok = 1")
        assertFalse("注释里的词不该被算作违规", unitLiterals.any { onlyInComment.contains(it) })
        assertFalse("注释里的合并词不该被算作违规", mergeLiterals.any { onlyInComment.contains(it) })
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
