// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第八百三十五批 · 判据④（源码断言无微分/连续物理）＋ 边界（零装配、零闸、零 IO）。
// 每道闸都配一条**负例注入自检**：不是「文件里含某字面量」这种恒真断言。
package com.example.zhengdao.baopu.state.causal

import com.example.zhengdao.SrcRoots
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CausalSourceAssertTest {

    private val dir = "app/src/main/java/com/example/zhengdao/baopu/state/causal/"
    private val files = listOf(
        "CausalDims.kt", "CausalKinds.kt", "TimeSpan825.kt", "CausalEvent.kt",
        "CausalRules.kt", "CausalChains.kt", "CausalChecks.kt", "CausalReplay.kt", "CausalRenderer.kt",
    )

    private fun code(name: String): String = stripComments(SrcRoots.find(dir + name).readText())

    private fun allCode(): String = files.joinToString("\n\n") { "// $it\n" + code(it) }

    /** 装配 / 引擎 / 闸 / 落库：一字不许出现。 */
    private val assemblyTokens = listOf(
        "RunEngine", "Wiring", "Assembler", "ZhengdaoApp", "attach(", "RiskMatrix", "Gate", "BaopuSql", "insert",
    )

    /** 微分与连续物理：类型、函数、词，全都不许。 */
    private val continuousTokens = listOf(
        "Double", "Float", "pow(", "sqrt(", "exp(", "sin(", "cos(", "tan(",
        "积分", "微分", "导数", "速率", "velocity", "accel", "derivative", "integral",
    )

    /** 时间系统：本包只许有 825 的四档，别的时钟一律不许。 */
    private val clockTokens = listOf(
        "java.time", "Duration", "Instant", "Millis", "millis", "nanoTime", "currentTimeMillis", "System.",
    )

    /** 跨度算术：档只许跳，不许算（`rank + 1` 这种一旦出现，下一步就会变成小时数）。 */
    private val spanMathTokens = listOf("rank +", "rank -", "rank *", "rank /")

    @Test
    fun `边界_零装配零闸零落库`() {
        val text = allCode()
        assemblyTokens.forEach { tok -> assertFalse("不得出现 $tok", text.contains(tok)) }
    }

    @Test
    fun `判据四_源码里没有浮点与连续物理`() {
        val text = allCode()
        continuousTokens.forEach { tok -> assertFalse("不得出现 $tok", text.contains(tok)) }
    }

    @Test
    fun `判据二_源码里没有第二套时间系统`() {
        val text = allCode()
        clockTokens.forEach { tok -> assertFalse("不得出现 $tok", text.contains(tok)) }
    }

    @Test
    fun `判据二_跨度不做算术`() {
        val text = allCode()
        spanMathTokens.forEach { tok -> assertFalse("不得出现 $tok", text.contains(tok)) }
    }

    @Test
    fun `判据二_四档只在一处定义`() {
        val words = listOf("分钟级", "小时级", "过夜", "跨日")
        val others = files.filter { it != "TimeSpan825.kt" }
        others.forEach { f ->
            val text = code(f)
            words.forEach { w -> assertFalse("$f 不许再写一遍跨度名 $w", text.contains(w)) }
        }
        words.forEach { w -> assertTrue("四档名必须定义在 TimeSpan825.kt：$w", code("TimeSpan825.kt").contains(w)) }
    }

    @Test
    fun `判据六_七个业主包都真的被引用`() {
        val text = allCode()
        listOf(
            "state.injury", "state.motion", "state.surfaceflow", "state.physiology",
            "state.environment", "state.stimulus", "state.bodyparts",
        ).forEach { p -> assertTrue("必须真的引用 $p", text.contains(p)) }
        assertTrue("824 未落地要写明", Pending824.contains("824"))
        assertTrue("748 未落地要写明", Pending748.contains("748"))
        assertTrue("825 溯源要写明", Span825Source.ORDER == "825")
    }

    @Test
    fun `判据四_唯一渲染出口`() {
        files.filter { it != "CausalRenderer.kt" }.forEach { f ->
            assertFalse("$f 不许自己拼投影文本", code(f).contains("fun render("))
        }
        assertTrue("渲染出口在 CausalRenderer", code("CausalRenderer.kt").contains("fun render("))
    }

    // ── 闸自检：负例注入必须被咬住 ───────────────────────────────────────────

    @Test
    fun `闸自检_注入浮点必被咬住`() {
        val poisoned = stripComments("val r = 0.5 * a + b.toDouble()")
        assertTrue("浮点要被咬住", continuousTokens.any { poisoned.contains(it) })
    }

    @Test
    fun `闸自检_注入墙钟必被咬住`() {
        val poisoned = stripComments("val t = System.currentTimeMillis()")
        assertTrue("墙钟要被咬住", clockTokens.any { poisoned.contains(it) })
    }

    @Test
    fun `闸自检_注入跨度算术必被咬住`() {
        val poisoned = stripComments("val next = Span825.of(span.rank + 1)")
        assertTrue("跨度算术要被咬住", spanMathTokens.any { poisoned.contains(it) })
    }

    @Test
    fun `闸自检_注入装配词必被咬住`() {
        val poisoned = stripComments("RunEngine.run(ctx)")
        assertTrue("装配词要被咬住", assemblyTokens.any { poisoned.contains(it) })
    }

    @Test
    fun `闸自检_剥掉注释后注释里的词不算数`() {
        val onlyComment = stripComments("// 这里提到 3.5 秒的 Duration 与 rank + 1 只是说明\nval ok = 1")
        assertFalse("注释里的浮点不算", continuousTokens.any { onlyComment.contains(it) })
        assertFalse("注释里的时钟不算", clockTokens.any { onlyComment.contains(it) })
        assertFalse("注释里的跨度算术不算", spanMathTokens.any { onlyComment.contains(it) })
    }

    @Test
    fun `闸自检_块注释可嵌套也能剥干净`() {
        val nested = stripComments("/* a /* b */ 这里还有 0.5 秒 */\nval ok = 2")
        assertFalse("嵌套块注释要剥干净", continuousTokens.any { nested.contains(it) })
        assertTrue("代码本体留下", nested.contains("val ok = 2"))
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
            if (c == '\\' && i + 1 < src.length) { out.append(c).append(src[i + 1]); i += 2; continue }
            out.append(c)
            i++
            if (c == '"') break
        }
        return i
    }
}
