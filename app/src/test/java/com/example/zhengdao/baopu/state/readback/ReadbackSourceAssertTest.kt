// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第七百二十二批 · 读回契约**源码门禁**（判据"三行只作文本上下文、不进任何闸"、
// "词档不硬编码"、"零装配"的机器钉法）。只读本单源码文本，零写盘。
// 每道闸配注入自检（负例），防"闸没牙"——不是文件含某字面量就过，而是剥注释后核对。
package com.example.zhengdao.baopu.state.readback

import com.example.zhengdao.SrcRoots
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadbackSourceAssertTest {

    private val dir = "app/src/main/java/com/example/zhengdao/baopu/state/readback/"
    private val mainFiles = listOf("ReadbackLines.kt", "BodyReadback.kt", "InvariantSeal.kt").map { dir + it }

    /** "不进任何闸"：闸/落库/装配的记号一个都不许出现在业务码里。 */
    private val gateTokens = listOf(
        "RunContractWiring", "RiskMatrix", "contractLine", "persistContractLine",
        "insertRunLog", "BaopuSql", "TaskModeBoundary", "Gate", "assemble(",
    )

    /** 零装配：不碰引擎/装配入口。 */
    private val assemblyTokens = listOf(
        "StateSummaryWiring", "CharacterTrack", "attach(", "ZhengdaoApp", "SceneState", "RunEngine",
    )

    /** 13 区正名：一个都不许硬编码进源码（真源只能是 669 词表 AnatomyZone.name）。 */
    private val zoneNames = listOf(
        "根部", "柱身", "阴茎缝", "龟头", "龟头冠", "冠状沟", "系带",
        "尿道口", "包皮", "阴囊", "睾丸", "精索", "耻骨区",
    )

    private fun codeOfAll(): String = mainFiles.joinToString("\n") { stripComments(source(it)) }

    // ── 闸 / 装配 ────────────────────────────────────────────────────────

    @Test
    fun `边界_三行不进任何闸`() {
        val code = codeOfAll()
        gateTokens.forEach { tok ->
            assertFalse("业务码不得出现闸记号 $tok", code.contains(tok))
        }
    }

    @Test
    fun `边界_零装配`() {
        val code = codeOfAll()
        assemblyTokens.forEach { tok -> assertFalse("不得装配 $tok", code.contains(tok)) }
    }

    // ── 词表不硬编码（带注入自检）────────────────────────────────────────

    @Test
    fun `判据五_13区正名不硬编码在源码`() {
        val code = codeOfAll()
        zoneNames.forEach { name ->
            assertFalse("区名 $name 不许写死在渲染器，须读词表", code.contains(name))
        }
    }

    @Test
    fun `闸自检_注入区名必被咬住_证明闸有牙`() {
        val poisoned = stripComments("val x = \"根部\" // 真源来自词表")
        assertTrue("注入的自检样本必须被闸咬住", poisoned.contains("根部"))
    }

    // ── 真值来源 ─────────────────────────────────────────────────────────

    @Test
    fun `判据五_真值字段只从BodyState取_本文件不写身高体脂数值`() {
        val code = codeOfAll()
        // 负例夹具里"176/170/18/25"是测试值，不许出现在生产源码；生产只引用 spec.frame 字段。
        for (lit in listOf("170.0", "176.0", "180.0", "bodyFatPct =", "heightCm =")) {
            assertFalse("生产源码不得写死身体数值/构造 $lit", code.contains(lit))
        }
        assertTrue("须引用 spec 真值", code.contains("spec.heightCm"))
        assertTrue("须引用体脂真值", code.contains("spec.bodyFatPct"))
        assertTrue("须引用 frame 截面真值", code.contains("frame.sections"))
    }

    private fun source(rel: String): String = SrcRoots.find(rel).readText()

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
