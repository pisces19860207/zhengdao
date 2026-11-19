// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第七百三十九批 · 三化抑制**源码断言**（派工单 §二⑥：阈值来自配置、代码不写死数字）。
// 口径：剥掉注释后扫代码——注释里说"不写死"不算证据，代码里"没有"才算。
// 只读本包两份源码（SrcRoots 定位），零写盘。每道闸配注入自检，防"闸没牙"。
package com.example.zhengdao.baopu.antislop

import com.example.zhengdao.SrcRoots
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AntiSlopSourceAssertTest {

    private val dir = "app/src/main/java/com/example/zhengdao/baopu/antislop/"
    private val files = listOf("AntiSlopGuard.kt", "AntiSlopBaseline.kt").map { dir + it }

    /** 带小数的阈值字面量（子串扫描即可，不会与字段名相撞）。 */
    private val decimalTokens = listOf("125.0", "140.0", "0.3", "3.0", "1.0")

    /** 整数阈值只用"独立字面量"匹配，避开 maxHedgePer100 / component2 这类标识符与索引。 */
    private val integerTokens = listOf("500", "100", "2")
    private fun integerPattern(t: String) = Regex("(?<![A-Za-z0-9_])$t(?![A-Za-z0-9_])")

    /** 词表样例：命中即从源码剥注释后扫到具体词＝写死。 */
    private val lexiconTokens = listOf("一柱擎天", "18cm", "可能", "治疗", "非医疗判断", "总之")

    /** 引擎口：模型/网络/随机/时钟/写盘一律不许进检测器。 */
    private val engineTokens = listOf(
        "Provider", "Http", "http", "URL", "OkHttp", "Retrofit", "java.net",
        "Llm", "llm", "Prompt", "prompt", "apiKey", "completion",
        "Random", "random", "nanoTime", "currentTimeMillis", "Instant", "LocalDate", "Thread",
        "writeText", "FileWriter", "OutputStream", "PrintWriter", "mkdir", "delete",
    )

    @Test
    fun `判据六_解析器与检测器剥注释后不含写死阈值数字`() {
        val code = files.joinToString("\n") { codeOf(it) }
        for (t in decimalTokens) assertTrue("阈值 $t 只许在配置里，代码不得写死", t !in code)
        for (t in integerTokens) {
            assertTrue("阈值 $t 只许在配置里，代码不得写死", !integerPattern(t).containsMatchIn(code))
        }
    }

    @Test
    fun `判据六_代码不含写死的词表样例`() {
        val code = files.joinToString("\n") { codeOf(it) }
        for (t in lexiconTokens) assertTrue("词表词「$t」只许在配置里，代码不得写死", t !in code)
    }

    @Test
    fun `判据六_代码确实引用了配置字段`() {
        val code = codeOf(dir + "AntiSlopGuard.kt")
        for (ref in listOf("cfg.b.hedges", "cfg.b.adviceBanWords", "cfg.c.blacklist", "cfg.c.fourCharLexicon",
                "cfg.a.inflationMarkers", "cfg.a.defaultBandLowMm", "cfg.window.hedgeChars",
                "cfg.c.maxSummaryRate", "cfg.b.maxHedgePer100")) {
            assertTrue("阈值须经配置读取，缺引用 $ref", ref in code)
        }
    }

    @Test
    fun `判据六_引擎口_无模型网络随机时钟写盘`() {
        val code = files.joinToString("\n") { codeOf(it) }
        for (t in engineTokens) assertTrue("检测器/加载器不得出现 $t", t !in code)
    }

    @Test
    fun `判据六_注入写死阈值必被咬住_闸有牙自检`() {
        val code = codeOf(dir + "AntiSlopGuard.kt")
        val injected = code + "\nval bad = 125.0\n"
        assertTrue("注入 125.0 后闸必须咬中", decimalTokens.any { it in injected })
        val injectedLex = code + "\nval bad = \"一柱擎天\"\n"
        assertTrue("注入词表词后闸必须咬中", lexiconTokens.any { it in injectedLex })
    }

    @Test
    fun `判据六_注入引擎调用必被咬住_闸有牙自检`() {
        val injected = codeOf(dir + "AntiSlopGuard.kt") + "\nval c = okhttp3.OkHttpClient()\n"
        assertTrue("注入网络调用后闸必须咬中", engineTokens.any { it in injected })
    }

    @Test
    fun `防闸空转_两份源码都真被读到且剥注释后仍有代码`() {
        assertEquals(2, files.size)
        for (f in files) {
            val code = codeOf(f)
            assertTrue("$f 剥注释后不得为空", code.length > 200)
            assertTrue("$f 须是本包源码", "package com.example.zhengdao.baopu.antislop" in code)
        }
    }

    @Test
    fun `配置闸_每条阈值记录都带source与asOf`() {
        val raw = listOf(File(AntiSlopBaselines.REL_PATH), File("../${AntiSlopBaselines.REL_PATH}"))
            .first { it.isFile }.readText()
        val cfg = AntiSlopBaselines.parse(raw)
        assertTrue(cfg.source.isNotBlank()); assertTrue(cfg.asOf.isNotBlank())
        assertTrue(cfg.a.source.isNotBlank()); assertTrue(cfg.a.asOf.isNotBlank())
        assertTrue(cfg.b.source.isNotBlank()); assertTrue(cfg.b.asOf.isNotBlank())
        assertTrue(cfg.c.source.isNotBlank()); assertTrue(cfg.c.asOf.isNotBlank())
        assertTrue("默认区间须写明来源", cfg.a.defaultBandLowMm in 0.0..cfg.a.defaultBandHighMm)
    }

    private fun source(rel: String): String = SrcRoots.find(rel).readText()

    private fun codeOf(rel: String): String = stripComments(source(rel))

    /** 行/块注释整段换空格（换行保留）；字符串原样保留——词表藏字符串里也算数。 */
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
            when {
                two == "/*" -> { depth++; out.append("  "); i += 2 }
                two == "*/" -> { depth--; out.append("  "); i += 2 }
                else -> { out.append(if (src[i] == '\n') '\n' else ' '); i++ }
            }
        }
        return i
    }

    private fun copyString(src: String, start: Int, out: StringBuilder): Int {
        var i = start + 1
        out.append('"')
        while (i < src.length) {
            val c = src[i]
            out.append(c)
            if (c == '\\' && i + 1 < src.length) { out.append(src[i + 1]); i += 2 }
            else if (c == '"') return i + 1
            else i++
        }
        return i
    }
}
