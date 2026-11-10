// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百九十六批开工令点名的判据⑤：**第⑤步有真实调用点（源级门禁）**。
//
// 为什么必须打在源码上：239 交付的影子是"全绿但零调用"——判定层自己测得再干净，
// `ShadowRehearsal.compare` 在 `app/src/main` 里一处调用都没有，249 的第⑤步就永远停在
// `NO_DATA`。行为用例管"给定事实判成什么"，管不了"有没有人把事实拿出来"。所以这里钉五件事：
//   1. 判据层在 main 里**有且只有一个**调用点，而且它在适配器（多处调用＝多套判定口径）；
//   2. 第⑤步真去问适配器（`ShadowAdapter.rehearse` 一处 ＋ `shadowSignalFor(request.shadowInput)`）；
//   3. 构造层把预演输入交上来，装配层把沙盒装上（`ShadowAdapter.install(` 全仓唯一）；
//   4. 碰盘的那一份（建副本／复制／删副本）不长在勘验层——那是 [InspectReadOnlyGateTest]
//      那条"纠察只查不改"的门禁的**另一半**：它钉"没有写口"，这条钉"跑的人在对岸"；
//   5. 免卡判据的字面只认 `CONSISTENT`，第⑤步没有给结构故障留"仍然执行"的出口（§2）。
//
// 第五百五十七批（⑤去脆化）：整句字面／含局部名的计数 → 结构断言，保护意图不变——
// · shadowAllows 的函数体必须整份就是「X == CONSISTENT」（参数名自由；加 || 放宽必红）；
// · gate 里不许有 shadowAllows 之外的第二处 == CONSISTENT（containment，不数词频）；
// · 第⑤步区域用「Rehearsed 过滤 → 第一处 RECORD 读数」定位，不钉 val signal/steps 这些局部名；
// · 稳定 API 调用点唯一性（compare/rehearse/install/shortCircuit）保留计数。
package com.example.zhengdao.baopu.inspect

import com.example.zhengdao.CodeText
import com.example.zhengdao.GateBodies
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShadowWiringGateTest {

    private fun repoFile(rel: String): File = if (File(rel).exists()) File(rel) else File("app/$rel")

    private fun gate(): File = repoFile("src/main/java/com/example/zhengdao/baopu/authz/TaskGrantGate.kt")

    private fun wire(): File = repoFile("src/main/java/com/example/zhengdao/baopu/authz/TaskGrantWire.kt")

    private fun adapter(): File = repoFile("src/main/java/com/example/zhengdao/baopu/inspect/ShadowAdapter.kt")

    private fun runtime(): File = repoFile("src/main/java/com/example/zhengdao/baopu/BaopuRuntime.kt")

    /** `app/src/main` 下全部 Kotlin 源（判据⑤要的是"生产"，测试源不算）。 */
    private fun mainSources(): List<File> {
        val dir = repoFile("src/main/java")
        require(dir.isDirectory) { "main 源目录没找到：${dir.absolutePath}" }
        return dir.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.toList()
    }

    /** 只留代码行：注释里写「唯一调用点是 ShadowRehearsal.compare」不算调用（276 批同规矩）。557 起走 CodeText（连字符串一起剥）。 */
    private fun codeOnly(src: String): String = CodeText.codeOnly(src)

    private fun code(f: File): String = codeOnly(f.readText(Charsets.UTF_8))

    private fun String.occurrences(needle: String): Int =
        if (needle.isEmpty()) 0 else Regex(Regex.escape(needle)).findAll(this).count()

    /** 某个字面在 main 里的调用点（文件名 → 次数，只留非零）。 */
    private fun callsInMain(needle: String): List<Pair<String, Int>> =
        mainSources().map { it.name to code(it).occurrences(needle) }.filter { it.second > 0 }

    // ── 判据⑤：第⑤步有真实调用点 ────────────────────────────────────────────

    @Test
    fun `判据⑤_判据层在main只有一个调用点`() {
        // 零处＝239 那种交付；两处以上＝判定口径漂移。
        assertEquals(
            "ShadowRehearsal.compare 的调用点必须只在适配器里",
            listOf("ShadowAdapter.kt" to 1),
            callsInMain("ShadowRehearsal.compare("),
        )
    }

    @Test
    fun `判据⑤_第⑤步真去问适配器`() {
        val text = code(gate())
        assertEquals("`ShadowAdapter.rehearse(` 在第⑤步只许一处", 1, text.occurrences("ShadowAdapter.rehearse("))
        // 557：不钉实参局部名（request.shadowInput），改问「非定义的调用点恰一处」。
        assertEquals("shadowSignalFor 只许一处调用", 1, Regex("""(?<!fun )shadowSignalFor\s*\(""").findAll(text).count())
        assertTrue(
            "先认调用方交回的现成结论，没有才自己去跑（同一次动作只许跑一遍影子）",
            Regex("""takeIf\s*\{\s*it\s+is\s+ShadowSignal\.Rehearsed\s*\}""").containsMatchIn(text),
        )
        assertTrue("请求里要带着预演输入（可空、默认 null）", Regex("""val\s+shadowInput\s*:\s*ShadowInput\?\s*=\s*null""").containsMatchIn(text))
    }

    @Test
    fun `判据⑤_构造层交输入_装配层装沙盒`() {
        val w = code(wire())
        // 557：不钉整串实参（call, root, objectId, verdict…），改问「非定义的调用点恰一处」。
        assertEquals("shadowInputOf 只许一处调用", 1, Regex("""(?<!fun )shadowInputOf\s*\(""").findAll(w).count())
        // 挂进请求：named-arg 或 positional 都算（不钉 = 号两边）。
        assertTrue("算出来的输入要真的挂进请求", Regex("""[(,]\s*shadowInput\s*[,)=]""").containsMatchIn(w))
        assertTrue("装配层的预演输入必须可空、默认 null", Regex("""shadowInput\s*:\s*ShadowInput\?\s*=\s*null""").containsMatchIn(w))
        assertEquals(
            "沙盒全仓只许装一次，而且装在装配层（装两处＝两份副本口径）",
            listOf("BaopuRuntime.kt" to 1),
            callsInMain("ShadowAdapter.install("),
        )
        assertTrue(code(runtime()).contains("ShadowAdapter.install(FileShadowSandbox())"))
    }

    // ── §2：碰盘的那一份不在勘验层（纠察只查不改的另一半）──────────────────────

    @Test
    fun `§2_建副本的体力活不长在勘验层`() {
        val dir = repoFile("src/main/java/com/example/zhengdao/baopu/inspect")
        assertTrue("勘验层目录没找到：${dir.absolutePath}", dir.isDirectory)
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".kt") }!!.sortedBy { it.name }
        files.forEach { f ->
            val text = code(f)
            listOf("copyTo(", "deleteRecursively(", "walkTopDown(", "FileShadowSandbox").forEach { word ->
                assertFalse("${f.name} 里有 $word（影子要建副本，但那份体力活不能长在勘验层）", text.contains(word))
            }
        }
        // 适配器自己不写盘也不建目录：落账走 ShadowLedger，碰盘走注入进来的沙盒口
        val a = code(adapter())
        listOf("mkdirs(", "writeText(", "appendText(", "copyTo(", "deleteRecursively(").forEach { word ->
            assertFalse("ShadowAdapter.kt 里有 $word", a.contains(word))
        }
        val sandbox = repoFile("src/main/java/com/example/zhengdao/baopu/agent/ShadowSandbox.kt")
        assertTrue("沙盒实现挪出装配层了？${sandbox.absolutePath}", sandbox.isFile)
        assertTrue(code(sandbox).contains(": ShadowSandbox"))
    }

    // ── 负例④：免卡判据的字面（行为那一半在 TaskGrantShadowStepTest）───────────

    @Test
    fun `负例④_免卡判据只认CONSISTENT`() {
        val raw = gate().readText(Charsets.UTF_8)
        // 557：整句字面 → 函数体形状。参数名/写法可变，但「只认 CONSISTENT」这一条不许放宽。
        val body = GateBodies.functionBody(raw, "fun shadowAllows(")
        assertTrue("shadowAllows 不在了？", body != null)
        val bodyCode = CodeText.codeOnly(body!!).trim()
        assertTrue(
            "shadowAllows 一旦变成「UNVERIFIABLE 也放行」，249 §7 的三态就废了",
            Regex("""(?:=\s*|return\s+)\w+\s*==\s*ShadowVerdict\.CONSISTENT\s*\}?\s*$""").containsMatchIn(bodyCode),
        )
        // 第二处放行判据＝shadowAllows 之外还出现 == CONSISTENT（containment，不数词频）。
        val rest = CodeText.codeOnly(raw).replace(CodeText.codeOnly(body!!), "")
        assertFalse("gate 里出现了 shadowAllows 之外的 == CONSISTENT（＝第二个放行口）", Regex("""==\s*ShadowVerdict\.CONSISTENT""").containsMatchIn(rest))
        // 适配器也不许自己造放行：只许在"清不清副本"那一格用 !=。
        val a = code(adapter())
        assertFalse("适配器里出现了 == CONSISTENT（自己造放行）", Regex("""==\s*ShadowVerdict\.CONSISTENT""").containsMatchIn(a))
        assertTrue("清副本的判据必须是 != CONSISTENT（违规的副本不许清）", Regex("""!=\s*ShadowVerdict\.CONSISTENT""").containsMatchIn(a))
    }

    // ── §2：结构故障取消自动资格，没有「仍然执行」那颗按钮 ─────────────────────

    @Test
    fun `§2_第⑤步不给结构故障留绕过去的口子`() {
        val text = code(gate())
        // 557：区域定位不钉局部名（原 substringAfter "val signal = …" / substringBefore "steps += …"），
        // 起点＝Rehearsed 过滤那一处，终点＝第一处 RECORD 读数（稳定 API 构造子）。
        val start = Regex("""takeIf\s*\{\s*it\s+is\s+ShadowSignal\.Rehearsed\s*\}""").find(text)
        val end = Regex("""StepReading\s*\(\s*GrantStep\.RECORD""").find(text)
        assertTrue(
            "第⑤步的那一段抓不到了（改动挪走了？）",
            start != null && end != null && start!!.range.first < end!!.range.first,
        )
        val block = text.substring(start!!.range.first, end!!.range.first)
        assertTrue("第⑤步的那一段抓不到了（改动挪走了？）", block.contains("ShadowVerdict"))
        assertTrue("第⑤步只剩停下这一条路", block.contains("GateOutcome.STOPPED"))
        listOf("GateOutcome.ALLOW", "GateOutcome.ASK_SINGLE", "GateOutcome.ASK_DELTA", "GateOutcome.REJECT")
            .forEach { outcome ->
                assertFalse("第⑤步里出现了 $outcome（＝给跑不出结论的动作留了出口）", block.contains(outcome))
            }
        assertTrue(
            "UNVERIFIABLE 记未验、MISMATCH/MISWIRED 记不过（239 的三态口径）",
            Regex("""passed\s*=\s*if\s*\(\s*\w+\s*==\s*ShadowVerdict\.UNVERIFIABLE\s*\)\s*null\s+else\s+false""")
                .containsMatchIn(block),
        )
    }

    // ── §2 的闭集：适配器只能报那五种原因，覆盖表 306 起五件＋两件不适用 ──────────

    @Test
    fun `§2_覆盖表与原因码都是闭集`() {
        assertEquals(
            "第一版 write/edit 两件，306 开工令逐工具补齐到五件",
            listOf("write_file", "edit_file", "move_path", "delete_path", "convert_encoding"),
            SHADOW_TOOLS,
        )
        val a = code(adapter())
        val used = Regex("""ShadowUnverifiable\.([A-Z_]+)""").findAll(a).map { it.groupValues[1] }.toSet()
        assertEquals("适配器报的原因码必须正好是闭集那五条", ShadowUnverifiable.ALL.toSet(), used)
        assertEquals(5, ShadowUnverifiable.ALL.size)
        assertEquals(
            "跑不成结论只许走 shortCircuit 这一条口",
            7,
            a.occurrences("return shortCircuit("),
        )
        // 306 负例点名格：NOT_APPLICABLE 与 UNSUPPORTED 混为一谈 ＝ 无文件副作用的工具被
        // 记成"下一批接谁"的待办，体检永远差两片接不上的假待办。
        assertTrue(
            "适配器要分得开「不适用」与「还没接」",
            a.contains("ShadowUnverifiable.NOT_APPLICABLE") && a.contains("ShadowUnverifiable.UNSUPPORTED"),
        )
    }
}
