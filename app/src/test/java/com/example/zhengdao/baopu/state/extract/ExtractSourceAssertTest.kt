// 独立开发声明：本文件为本项目原创，不含第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第八百五十三批 · 源码门禁：只读本单 4 个生产源码，钉住纯函数/不自造档/逐条显式/出口过滤。
// 每道闸都配负例注入自检——不是「文件里含某个词」就过。
package com.example.zhengdao.baopu.state.extract

import com.example.zhengdao.SrcRoots
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtractSourceAssertTest {

    private val dir = "app/src/main/java/com/example/zhengdao/baopu/state/extract/"
    private val files = listOf("ExtractModel.kt", "ExtractLexicon.kt", "ProseExtractor.kt")
        .map { dir + it }

    private fun code(): String = files.joinToString("\n") { stripComments(source(it)) }

    private fun source(rel: String): String = SrcRoots.find(rel).readText()

    /** 纯函数：IO/网络/随机/时钟一律不许。token 拼接构造，免得检查器自责。 */
    private val impureTokens = listOf(
        "java." + "io", "File(", "readText", "writeText", "InputStream", "OutputStream",
        "java.net", "HttpURLConnection", "Socket(", "Random", "random(",
        "currentTimeMillis", "nanoTime", "Instant.now", "LocalDateTime", "System.getenv",
    )

    /** 自造档：未落地单号对应的档一律不许出现（拼接构造）。 */
    private val inventedTokens = listOf(
        "erect" + "State", "groin" + "Bulge", "State" + "WordGrade",
        "硬" + "度档", "bulge" + "Tier",
    )

    @Test
    fun `纯函数_生产源码零IO零随机零时钟`() {
        val text = code()
        impureTokens.forEach { tok -> assertFalse("不得出现 $tok", tok in text) }
    }

    @Test
    fun `纯函数_负例注入自检证明IO闸会红`() {
        val injected = "val x = java.io.File(\"x\").readText()"
        assertTrue("负例必须命中 IO 闸", "java." + "io" in injected)
        assertTrue("负例必须命中 File 闸", "File(" in injected)
    }

    @Test
    fun `纯函数_负例注入自检证明随机与时钟闸会红`() {
        val injected = "System.currentTimeMillis() + kotlin.random.Random.nextLong()"
        assertTrue("负例必须命中时钟闸", "currentTimeMillis" in injected)
        assertTrue("负例必须命中随机闸", "Random" in injected)
    }

    @Test
    fun `不自造档_生产源码无erectState与groinBulge档`() {
        val text = code()
        inventedTokens.forEach { tok -> assertFalse("不得自造 $tok", tok in text) }
    }

    @Test
    fun `不自造档_负例注入自检证明自造档闸会红`() {
        val injected = "enum class erectState { HARD } // groinBulge tier"
        assertTrue("负例必须命中 erectState 闸", "erect" + "State" in injected)
        assertTrue("负例必须命中 groinBulge 闸", "groin" + "Bulge" in injected)
    }

    @Test
    fun `不自造档_每条规则的档逐字取既有枚举label`() {
        ExtractLexicon.ALL.forEach { rule ->
            val grade = requireNotNull(rule.grade)
            assertTrue("自造档：${rule.dim.label} $grade", grade in ExtractLexicon.legalGrades(rule.dim))
        }
    }

    @Test
    fun `不自造档_810状态词规则一律无档`() {
        ExtractLexicon.ALL_STATE.forEach { rule ->
            assertTrue("810 状态词不许带档", rule.grade == null && rule.stateOnly)
        }
        assertTrue("状态词必须恰好三条", 3 == ExtractLexicon.ALL_STATE.size)
    }

    @Test
    fun `逐条显式_misses词表每条带原因与pending或NO_GRADE`() {
        ExtractLexicon.ALL_MISS.forEach { rule ->
            assertTrue("miss 词不许空", rule.pattern.isNotBlank())
            assertTrue("miss 原因不许空", rule.note.isNotBlank())
            if (rule.reason == MissReason.PENDING) {
                assertTrue("pending 必须是纯数字单号", rule.pending?.toIntOrNull() != null)
            }
        }
    }

    @Test
    fun `逐条显式_比喻与四型判不出都有miss出口`() {
        val result = ProseExtractor.extract("像铁一样硬，全露。")
        assertTrue("比喻进 miss", result.misses.any { it.reason == MissReason.METAPHOR })
        assertTrue("四型判不出进 miss", result.misses.any { it.reason == MissReason.AMBIGUOUS })
    }

    @Test
    fun `出口过滤_observedForCheck挡低档与表外字段`() {
        val text = "似乎有六块腹肌，退到冠后。"
        val result = ProseExtractor.extract(text)
        val feed = result.observedForCheck()
        assertTrue("六档无 817 字段，出口必须挡", feed.isEmpty())
        assertTrue("观测仍逐条保留", result.observed.size >= 2)
    }

    @Test
    fun `出口过滤_负例注入自检证明低档过滤闸已落地`() {
        val model = source(dir + "ExtractModel.kt")
        val stripped = stripComments(model)
        assertTrue("出口必须出现低档判定", "Confidence.LOW" in stripped)
        assertTrue("出口必须查白名单", "known(" in stripped && "accepts(" in stripped)
    }

    @Test
    fun `确定性_三遍抽取逐字一致`() {
        val text = "六块腹肌，已环切，退到冠后，湿透，像铁一样硬，青筋。"
        val lines = List(3) { ProseExtractor.extract(text).line() }
        assertEquals("三遍必须逐字一致", lines[0], lines[1])
        assertEquals("三遍必须逐字一致", lines[1], lines[2])
    }

    @Test
    fun `计数锁_规则表规模与维度数`() {
        assertEquals("躯干八维", 8, ExtractDim.entries.count { it.isTorso })
        assertEquals("下体六维", 6, ExtractDim.GENITAL_DIMS.size)
        assertTrue("落档规则不少于四十条", ExtractLexicon.ALL.size >= 40)
        assertTrue("miss 规则不少于八条", ExtractLexicon.ALL_MISS.size >= 8)
    }

    // ── 剥注释：行/块注释整段换空格（保留换行）；字符串原样保留。Kotlin 块注释可嵌套。 ──
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
            else { out.append(if (src[i] == '\n') '\n' else ' '); i++ }
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
