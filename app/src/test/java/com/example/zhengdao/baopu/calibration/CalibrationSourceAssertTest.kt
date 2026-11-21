// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第七百三十八批 · 源码门禁（判据④⑦）：注入不进闸、不改判定；不改 722 格式；不新增检索实现。
// 剥注释后扫业务码，每道闸配"注入自检"负例，防 contains 恒真。
package com.example.zhengdao.baopu.calibration

import com.example.zhengdao.SrcRoots
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationSourceAssertTest {

    private val dir = "app/src/main/java/com/example/zhengdao/baopu/calibration/"
    private val files = listOf(
        "CalibrationTrigger.kt", "CalibrationSource.kt", "CalibrationInject.kt", "CalibrationWiring.kt",
    ).map { dir + it }

    /** 判据④：闸/判定/落库/装配记号一个都不许出现在本单源码业务码里。 */
    private val gateTokens = listOf(
        "RiskMatrix", "GuardPipeline", "contractLine", "persistContractLine",
        "insertRunLog", "BaopuSql", "TaskModeBoundary", "RiskGate", "AskTierRules",
        "PlanContractRules", "persist(",
    )

    /** 判据⑦：不得新增检索实现（KB/召回/会话搜索都是"复用而非新造"，本包一个都不许自己实现）。 */
    private val retrievalTokens = listOf(
        "KbRetriever", "RecallIndexSearch", "SessionSearchStore", "class .*Retriever",
        "倒排", "TFIDF", "bm25", "BM25", "cosine", "embedding", "RAG(",
    )

    private fun codeOfAll(): String = files.joinToString("\n") { stripComments(source(it)) }

    private fun source(rel: String) = SrcRoots.find(rel).readText()

    // ── ④ 不越权 ──────────────────────────────────────────────────────────

    @Test
    fun `判据四_注入源码零闸引用`() {
        val code = codeOfAll()
        gateTokens.forEach { tok ->
            assertFalse("业务码不得出现闸记号 $tok", code.contains(tok))
        }
    }

    @Test
    fun `判据四_注入只经extraNotes不改判定_装配缝不碰gate`() {
        val code = codeOfAll()
        assertFalse("不得调用 RiskMatrix.classify 或等价判定", "classify(" in code)
        assertFalse("不得调用 GuardPipeline.execute", "executeInline" in code)
        // 装配缝只调 provider 与纯注入函数，并把结果交给 extraNotes（源码里体现为 List<String> notes）。
        assertTrue("装配缝须经 CalibrationInject.build", code.contains("CalibrationInject.build"))
    }

    // ── ⑦ 不新增检索实现 / 不改 722 ──────────────────────────────────────

    @Test
    fun `判据七_不新增检索实现`() {
        val code = codeOfAll()
        retrievalTokens.forEach { tok ->
            if (tok.contains(".*")) {
                assertFalse("不得自定义检索类 $tok", Regex(tok).containsMatchIn(code))
            } else {
                assertFalse("不得引用检索实现 $tok", code.contains(tok))
            }
        }
        // 取数只做 JSON 结构解析（复用 719/723 解析器），不写 query→score。
        assertTrue("须复用 719 解析器", code.contains("FactPackWords.parse"))
        assertTrue("须复用 723 解析器", code.contains("VariantTable.parse"))
    }

    @Test
    fun `判据七_三行直接吃722不重造格式`() {
        val code = codeOfAll()
        assertTrue("必须调用 722 接缝 asContextNotes()", code.contains("asContextNotes()"))
        assertFalse("不得在本包重造行头字面量", code.contains("\"[身体]\""))
        assertFalse("不得在本包重造词档行文本", code.contains("zones.joinToString"))
    }

    @Test
    fun `判据七_722三行源文件未被本单改动`() {
        // 722 三个源文件仍在、且接缝定义仍为 asContextNotes()：本单不重造也不修改它。
        val rb = source("app/src/main/java/com/example/zhengdao/baopu/state/readback/ReadbackLines.kt")
        assertTrue("722 接缝仍在", rb.contains("fun asContextNotes(): List<String>"))
        assertTrue("722 token 上限未被抬高", rb.contains("MAX_TOTAL_TOKENS = 200"))
    }

    // ── 闸自检（负例）：证明闸有牙 ────────────────────────────────────────

    @Test
    fun `闸自检_注入风险记号必被咬住_证明闸有牙`() {
        val poisoned = stripComments("val x = RiskMatrix.classify(/* c */ call)")
        assertTrue("注入的样本必须被闸咬住", gateTokens.any { poisoned.contains(it) })
    }

    @Test
    fun `闸自检_注入检索记号必被咬住`() {
        val poisoned = stripComments("class MyRetriever { } // 注释里提到 KbRetriever 不算")
        assertTrue("检索名必须被咬住", Regex("class .*Retriever").containsMatchIn(poisoned))
    }

    // ── helpers ───────────────────────────────────────────────────────────

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
