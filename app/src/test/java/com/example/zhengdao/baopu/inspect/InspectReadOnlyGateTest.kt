// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百三十九批 §5 的源码门禁：负例①（纠察有写权限）与③（明细进模型上下文）
// **只有读源码才验得到**——运行时它们没有可观察的下场：写权限一次没用过就是没用过，
// 明细没拼进 prompt 也可能是"今天凑巧"。所以这一类一律从源码层钉，不靠自觉。
//
// 三条扫描口径（都在文件里写死，改动 `baopu/inspect/` 的人第一眼就能看见为什么被拦）：
// 1. 勘验层没有写口：唯一允许落盘的是它自己那三本 JSONL（巡按/影子）的追加；
// 2. 勘验层不碰模型：不许 import `agent` 里除 227 判据（`AnswerGate`）之外的任何东西，
//    也不许出现 prompt／messages／LlmClient 那类拼上下文的字样；
// 3. 每条 SQL 都带上界：一次 `rawQuery` 一段窗口，窗口里必须有 `LIMIT`（负例④「扫全库」）。
//
// 另外两条界外检查：巡按栏那段界面不许弹窗（负例⑥）、三行上限必须是 3（负例②）。
package com.example.zhengdao.baopu.inspect

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InspectReadOnlyGateTest {

    private val srcRoot = File("src/main/java/com/example/zhengdao/baopu/inspect")

    /** 读源码前先剥注释：KDoc 里写「不许 writeText」是说明，不是调用（237 批同规矩）。 */
    private fun code(f: File): String = f.readText(Charsets.UTF_8)
        .replace(Regex("""/\*[\s\S]*?\*/"""), " ")
        .replace(Regex("(?m)//.*$"), " ")

    private fun inspectFiles(): List<File> {
        assertTrue("勘验层目录不存在：$srcRoot", srcRoot.isDirectory)
        return srcRoot.listFiles { f -> f.isFile && f.name.endsWith(".kt") }!!.sortedBy { it.name }
    }

    // ── 负例①：纠察只有读权 ─────────────────────────────────────────────────

    @Test
    fun `负例①_全层没有任何硬写口`() {
        val hardBanned = listOf(
            "writeText", "writeBytes", "PrintWriter", "FileOutputStream", "bufferedWriter",
            "deleteRecursively", "deleteDir", "renameTo", "execSQL", "INSERT INTO", "UPDATE ",
            "DELETE FROM", "ProcessBuilder", "Runtime.getRuntime",
            "SkillStore.save", "SkillStore.archive", "DecisionLedger.record(", "DecisionLedger.install(",
        )
        inspectFiles().forEach { f ->
            val text = code(f)
            hardBanned.forEach { word ->
                assertFalse("${f.name} 里有写口 $word（纠察只查不改，§5①）", text.contains(word))
            }
        }
    }

    @Test
    fun `负例①_唯一允许落盘的是自己那两本账`() {
        // 追加口只许出现在自己的台账文件里，且每个文件最多一处 appendText（一次巡按一行）
        val ledgers = listOf("PatrolLedger.kt", "ShadowLedger.kt")
        inspectFiles().forEach { f ->
            val text = code(f)
            val hits = Regex("""\.appendText\(""").findAll(text).count()
            if (f.name in ledgers) {
                assertEquals("${f.name} 的追加口应当只有一处", 1, hits)
            } else {
                assertEquals("${f.name} 不许写盘", 0, hits)
            }
        }
    }

    @Test
    fun `负例①_建目录只在自己的台账里`() {
        inspectFiles().forEach { f ->
            val hits = Regex("""mkdirs\(|mkdir\(""").findAll(code(f)).count()
            if (f.name == "PatrolLedger.kt" || f.name == "ShadowLedger.kt") {
                assertTrue("${f.name} 要能自建父目录", hits in 1..1)
            } else {
                assertEquals("${f.name} 不许建目录", 0, hits)
            }
        }
    }

    // ── 负例③：明细永不进模型上下文 ─────────────────────────────────────────

    @Test
    fun `负例③_勘验层不碰模型那一侧的任何出口`() {
        val banned = listOf("LlmClient", "prompt", "Prompt", "messages", "Messages", "chat(", "completions")
        inspectFiles().forEach { f ->
            val text = code(f)
            banned.forEach { word ->
                assertFalse("${f.name} 里出现了 $word（勘验的输出不许回到模型侧，§5③）", text.contains(word))
            }
        }
    }

    @Test
    fun `负例③_agent 包只许借227那一条判据`() {
        val allowed = "com.example.zhengdao.baopu.agent.AnswerGate"
        inspectFiles().forEach { f ->
            Regex("""import\s+com\.example\.zhengdao\.baopu\.agent\.[A-Za-z.]+""")
                .findAll(code(f))
                .forEach { m ->
                    assertEquals("${f.name} 借了 agent 的别的东西", allowed, m.value.replace("import ", "").trim())
                }
        }
        // 而且确实借了：判据不自造（I2 复用 227，虚报率那两条也是它的）
        assertTrue(code(File(srcRoot, "NumberGroundingCheck.kt")).contains(allowed))
    }

    @Test
    fun `负例③_给模型的那两句都由聚合层算`() {
        // PatrolReport.modelLine == headline（一句）；ShadowOutcome.modelLine 走 InspectText.line
        val inspector = code(File(srcRoot, "Inspector.kt"))
        assertTrue(inspector.contains("val modelLine: String get() = headline"))
        val shadow = code(File(srcRoot, "ShadowRehearsal.kt"))
        assertTrue(shadow.contains("val modelLine: String"))
        assertTrue(shadow.contains("InspectText.line("))
        // 明细只有一条出路：进台账与巡按栏
        val ledger = code(File(srcRoot, "PatrolLedger.kt"))
        assertTrue(ledger.contains("\"findings\""))
    }

    // ── 负例④：抽检不是全检 ─────────────────────────────────────────────────

    @Test
    fun `负例④_每条SQL都带LIMIT上界`() {
        val f = code(File(srcRoot, "PatrolSources.kt"))
        val marks = Regex("""rawQuery\(""").findAll(f).map { it.range.first }.toList()
        assertTrue("取数层一条查询都没有，是文件挪走了还是没装配？", marks.isNotEmpty())
        marks.forEachIndexed { i, start ->
            val end = if (i + 1 < marks.size) marks[i + 1] else f.length
            val window = f.substring(start, end)
            assertTrue("第 ${i + 1} 条查询没有 LIMIT（扫全库的负例就在这儿犯）", window.contains("LIMIT"))
        }
    }

    @Test
    fun `负例④_抽样规模是写死的常量不是全表`() {
        val vocab = code(File(srcRoot, "InspectVocab.kt"))
        assertTrue(vocab.contains("ANSWER_SAMPLE = 10"))
        assertTrue(vocab.contains("SKILL_SAMPLE = 5"))
        // 抽到的技能正文只读抽中的那几个（取数层先挑后读，不整层读正文）
        val sources = code(File(srcRoot, "PatrolSources.kt"))
        val pickFirst = sources.indexOf("InspectSample.spread(pool")
        val readBody = sources.indexOf("readText(")
        assertTrue("skillPool 的顺序变成先读后挑了", pickFirst in 0 until readBody)
    }

    @Test
    fun `负例④_读账本也有上界`() {
        val budget = code(File(srcRoot, "InspectVocab.kt"))
        assertTrue(budget.contains("READ_LIMIT = 2_000"))
        // 读技能目录先数后读：目录里只 listFiles，正文按抽样结果读
        assertTrue(code(File(srcRoot, "PatrolSources.kt")).contains("SKILL_FILE"))
    }

    // ── 负例②：报告超三行即红（形制上限住在判据层）──────────────────────────

    @Test
    fun `负例②_三行上限与四十字上限都在判据层`() {
        val vocab = code(File(srcRoot, "InspectVocab.kt"))
        assertTrue(vocab.contains("MAX_REPORT_LINES = 3"))
        assertTrue(vocab.contains("MAX_LINE_CHARS = 40"))
        // 钳形在构造期（`InspectText.line`），不是显示期才截
        val all = inspectFiles().map { code(it) }
        assertTrue(all.any { it.contains("InspectText.line(") })
    }

    // ── 负例⑥：不许弹窗（那一栏的界面里没有任何 Dialog）─────────────────────

    @Test
    fun `负例⑥_巡按栏没有弹窗出口`() {
        val screen = File("src/main/java/com/example/zhengdao/ui/baopu/DecisionCenterScreen.kt")
        val text = code(screen)
        val section = text.substring(text.indexOf("private fun PatrolSection("))
        listOf("AlertDialog", "Dialog(", "Toast", "Snackbar").forEach { word ->
            assertFalse("巡按栏里出现了 $word（§2.4 不许弹窗）", section.contains(word))
        }
        // 手动那一颗是真接通的（不是占位文案）
        assertTrue(text.contains("PatrolRunner.run("))
        assertTrue(text.contains("PatrolScheduler.manualGate("))
        // 展开态提到 Tab 层：留在 item 里会随滚出可视区销毁
        assertTrue(text.contains("var patrolExpanded by remember"))
        // 只渲染一句结论：正文三行由判据层钳好，界面不再截一次（两处口径必打架）
        assertTrue(section.contains("latest?.headline"))
    }

    @Test
    fun `界面对勘验是只读的`() {
        // 这一条钉的是"界面不许替纠察动手"：巡按栏那段唯一的写是点按钮起巡按
        val screen = code(File("src/main/java/com/example/zhengdao/ui/baopu/DecisionCenterScreen.kt"))
        val section = screen.substring(screen.indexOf("private fun PatrolSection("))
        listOf("writeText", "appendText", "execSQL", "mkdirs", "SkillStore", "DecisionLedger.record")
            .forEach { word -> assertFalse("巡按栏渲染段里有 $word", section.contains(word)) }
    }

    // ── 词表闭集：线值是账本稳定键，改动要连着老账本读得出来 ──────────────────

    @Test
    fun `词表_六项与三态都是闭集`() {
        assertEquals(6, InspectCheck.entries.size)
        assertEquals(3, InspectVerdict.entries.size)
        InspectCheck.entries.forEach { assertEquals(it, InspectCheck.ofWire(it.wire)) }
        assertNullWire(InspectCheck.ofWire("made_up_check"))
        assertNullWire(InspectVerdict.entries.firstOrNull { it.wire == "passed" })
    }

    private fun assertNullWire(v: Any?) {
        org.junit.Assert.assertNull(v)
    }
}
