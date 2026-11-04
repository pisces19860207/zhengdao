// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第四百二十八批 · 进化闭环接线的**源级门禁**（判据④：只加调用、不改判定）。
//
// 钉三件事：
//   ① 生产调用点必须存在（JinwuObserve 喂板后一行 + BaopuChatController Settled 家族两行）；
//   ② seam 入口只开提案不调 apply（不自动改判据）；
//   ③ agent/TakeoverWiring.kt 里不许出现宰相准入符号（宰相在线路径只查表，不参与准入）。
package com.example.zhengdao.baopu.evolution

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EvolutionWiringGateTest {

    private fun strip(src: String): String {
        // 与 check-criteria-gates.py 的去注释同款：注释替空格，行号不变。
        val out = StringBuilder()
        var i = 0
        val n = src.length
        var inStr = false
        var inChar = false
        var inRaw = false
        while (i < n) {
            val c = src[i]
            when {
                inRaw -> {
                    out.append(c)
                    if (i + 2 < n && src.startsWith("\"\"\"", i)) {
                        inRaw = false
                        out.append("\"\"")
                        i += 3
                    } else i++
                }
                inStr -> {
                    out.append(c)
                    if (c == '\\' && i + 1 < n) { out.append(src[i + 1]); i += 2 }
                    else { if (c == '"') inStr = false; i++ }
                }
                inChar -> {
                    out.append(c)
                    if (c == '\\' && i + 1 < n) { out.append(src[i + 1]); i += 2 }
                    else { if (c == '\'') inChar = false; i++ }
                }
                i + 2 < n && src.startsWith("\"\"\"", i) -> { inRaw = true; out.append("\"\"\""); i += 3 }
                c == '"' -> { inStr = true; out.append(c); i++ }
                c == '\'' -> { inChar = true; out.append(c); i++ }
                i + 1 < n && src.startsWith("//", i) -> {
                    while (i < n && src[i] != '\n') { out.append(' '); i++ }
                }
                i + 1 < n && src.startsWith("/*", i) -> {
                    out.append("  "); i += 2
                    while (i < n && !src.startsWith("*/", i)) {
                        out.append(if (src[i] == '\n') '\n' else ' '); i++
                    }
                    if (i < n) { out.append("  "); i += 2 }
                }
                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
    }

    private fun read(rel: String): String = File(rel).readText(Charsets.UTF_8)

    // ── ① 生产调用点（防「又做成死代码」的钉子） ───────────────────────────

    @Test
    fun `判据①_JinwuObserve 喂板后一行 EvolutionWiring_onGapsRecorded`() {
        val src = strip(read("src/main/java/com/example/zhengdao/baopu/agent/JinwuObserve.kt"))
        assertTrue(
            "JinwuObserve.feedBoard 必须在 GapFeedback.record 之后调 EvolutionWiring.onGapsRecorded",
            src.contains("EvolutionWiring.onGapsRecorded("),
        )
    }

    @Test
    fun `判据①_BaopuChatController 收工家族两调用都在`() {
        val src = strip(read("src/main/java/com/example/zhengdao/ui/baopu/BaopuChatController.kt"))
        assertTrue(
            "BaopuChatController 必须调 EvolutionWiring.onRunSettled",
            src.contains("EvolutionWiring.onRunSettled("),
        )
        assertTrue(
            "BaopuChatController 必须调 TakeoverWiring.sweepStalled",
            src.contains("TakeoverWiring.sweepStalled("),
        )
        // NEG-428-N2 实测教训：contains("wireBOnSettle()") 被定义行恒真满足（注释掉调用点仍绿）。
        // 必须钉「调用行」：剥注释后存在一行含 wireBOnSettle() 且不是定义行，才算挂进收工家族。
        val callLine = src.lineSequence()
            .any { it.contains("wireBOnSettle()") && !it.contains("fun wireBOnSettle(") }
        assertTrue(
            "wireBOnSettle 必须有真实调用行挂进 Settled 家族（定义行不算数——NEG-428-N2 实证）",
            callLine,
        )
    }

    // ── ② seam 入口不自动改判据（onGapsRecorded / onRunSettled 体内不许出现 apply(） ─

    @Test
    fun `判据②_onGapsRecorded 体内不调 EvolutionLoop_apply`() {
        val src = strip(read("src/main/java/com/example/zhengdao/baopu/evolution/EvolutionWiring.kt"))
        val body = src.substringAfter("fun onGapsRecorded(").substringBefore("fun onRunSettled(")
        assertFalse(
            "onGapsRecorded 体内不许调 EvolutionLoop.apply（不自动改判据）",
            body.contains("EvolutionLoop.apply("),
        )
    }

    @Test
    fun `判据②_onRunSettled 体内不调 EvolutionLoop_apply`() {
        val src = strip(read("src/main/java/com/example/zhengdao/baopu/evolution/EvolutionWiring.kt"))
        val body = src.substringAfter("fun onRunSettled(").substringBefore("fun runEvolutionChain(")
        assertFalse(
            "onRunSettled 体内不许调 EvolutionLoop.apply（apply 只发生在链里）",
            body.contains("EvolutionLoop.apply("),
        )
    }

    // ── ③ agent/TakeoverWiring.kt 不许出现宰相准入符号 ─────────────────────

    @Test
    fun `判据③_TakeoverWiring 不引宰相准入符号`() {
        val src = strip(read("src/main/java/com/example/zhengdao/baopu/agent/TakeoverWiring.kt"))
        val banned = listOf(
            "CriteriaEvolution", "EpochRegistry", "EpochChangeRow", "EpochTransition",
            "ImmutableCriteria", "AnchorSet", "CriteriaEpoch",
        )
        for (sym in banned) {
            assertFalse("TakeoverWiring 里不许出现 $sym（宰相在线路径只查表，不参与准入）", src.contains(sym))
        }
    }

    // ── 反向（防恒红）：负例必须真红 ────────────────────────────────────────

    @Test
    fun `负例_把 EvolutionLoop_apply 塞进 onGapsRecorded 必须红`() {
        val fake = "fun onGapsRecorded() { EvolutionLoop.apply(trigger, next, ref, now) } fun onRunSettled() {}"
        val body = fake.substringAfter("fun onGapsRecorded(").substringBefore("fun onRunSettled(")
        assertTrue(body.contains("EvolutionLoop.apply("))
    }

    @Test
    fun `负例_TakeoverWiring 里塞入 EpochRegistry 必须红`() {
        val fake = "val x = EpochRegistry.append(e, s, ref, t)"
        assertTrue(fake.contains("EpochRegistry"))
    }

    @Test
    fun `负例_只留定义不留调用 wireBOnSettle 判据必须红`() {
        // NEG-428-N2 实证回放：只有定义行（无调用行）时，调用行判据必须失败——防恒真回潮。
        val fake = strip("class C {\n  private fun wireBOnSettle() {}\n}")
        val callLine = fake.lineSequence()
            .any { it.contains("wireBOnSettle()") && !it.contains("fun wireBOnSettle(") }
        assertFalse("只剩定义、没有调用 ⇒ 判据必须红", callLine)
    }
}
