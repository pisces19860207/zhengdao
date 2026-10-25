// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百零六批 · 评测器日常化的判据（实现细化 §6 的八条负例逐条红＋正例/UNRUN）。
// 红的定义：坏行为被闸挡住——行为断言（没跑/没写/结论被压）＋源码门禁（那条路字面上不存在）。
package com.example.zhengdao.baopu

import com.example.zhengdao.baopu.eval.CaseResult
import com.example.zhengdao.baopu.eval.EvalCase
import com.example.zhengdao.baopu.eval.EvalConfig
import com.example.zhengdao.baopu.eval.EvalFile
import com.example.zhengdao.baopu.eval.EvalHarness
import com.example.zhengdao.baopu.LlmProtocol
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class EvalRunnerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var ws: File
    private lateinit var global: File

    private fun setUp() {
        ws = tmp.newFolder("ws")
        global = tmp.newFolder("global")
    }

    private val now = 1_760_000_000_000L // 固定钟（2025-10 前后，只当毫秒用）

    private fun writeSkill(name: String, body: String): File {
        val f = File(File(File(ws, "技能"), name), "SKILL.md")
        f.parentFile.mkdirs()
        f.writeText(body, Charsets.UTF_8)
        return f
    }

    private fun writeLedger() {
        val dir = File(ws, "技能")
        dir.mkdirs()
        File(dir, "_变更.jsonl").appendText(
            JSONObject().put("ts", 1L).put("scope", "project").put("name", "A").put("action", "write")
                .put("reason", "r").put("beforeSha", "-").put("afterSha", "abc123def456").put("indexUpdated", true)
                .toString() + "\n",
            Charsets.UTF_8,
        )
    }

    private fun provider(apiKey: String = "k"): com.example.zhengdao.baopu.Provider =
        com.example.zhengdao.baopu.Provider(
            id = "p1", label = "测试通道", protocol = LlmProtocol.OPENAI_COMPAT,
            baseUrl = "https://example.invalid", model = "m-1", apiKey = apiKey,
        )

    /** 假双跑：old/new 两份 CaseResult 由调用方给；顺带数调用次数（负例④⑥⑧的「不许开跑」）。 */
    private fun fakeRuns(
        old: CaseResult,
        new: CaseResult,
    ): Triple<(EvalCase, EvalConfig) -> CaseResult, MutableList<String>, MutableList<String>> {
        val callsSeen = mutableListOf<String>()
        val promptsSeen = mutableListOf<String>()
        val fn: (EvalCase, EvalConfig) -> CaseResult = { c, _ ->
            callsSeen += c.id
            promptsSeen += c.prompt
            if (c.id.endsWith("-old")) old else new
        }
        return Triple(fn, callsSeen, promptsSeen)
    }

    private fun caseResult(
        ok: Boolean,
        steps: Int = 3,
        calls: List<String> = listOf("read_file", "write_file"),
        failures: List<String> = emptyList(),
        error: String? = null,
        dirFiles: Map<String, String> = mapOf("答案.md" to "内容"),
    ): CaseResult {
        val dir = kotlin.io.path.createTempDirectory("zd-eval-test-").toFile()
        dirFiles.forEach { (n, c) -> File(dir, n).writeText(c, Charsets.UTF_8) }
        return CaseResult(
            id = "case", note = "", ok = ok, steps = steps, failures = failures,
            finalText = "", toolCalls = calls, tokensIn = 100, tokensOut = 20,
            error = error, dir = dir,
        )
    }

    private fun fixtureOf(runner: EvalRunner, newBody: String): EvalFixture =
        runner.fixtureOf(
            skillName = "技能A",
            userText = "北京那批装了没",
            skillBodyOld = "旧正文",
            skillBodyNew = newBody,
            modelSummary = "p1/m-1@t=0.2",
            seedFiles = listOf(EvalFile("任务.md", "种子内容")),
        )

    // ── 负例①：fixture 不一致（快照变了）却出结论 ⇒ 红 ───────────────────────

    @Test
    fun `负例1_快照不一致_立刻作废不许开跑`() {
        setUp()
        val f = writeSkill("技能A", "旧正文")
        writeLedger()
        val (fake, calls, _) = fakeRuns(caseResult(true), caseResult(true))
        val runner = EvalRunner(ws, global, log = {}, runCase = fake, clock = { now })
        val fixture = runner.fixtureOf("技能A", "北京那批装了没", "旧正文", "新正文", "p1/m-1@t=0.2")
        // 环境变了：技能正文被改（快照哈希随之变化）
        f.writeText("被人改过的正文", Charsets.UTF_8)
        val outcome = runner.runOnce(fixture, provider(), lastRunMs = 0L, runsToday = 0)
        assertTrue(outcome is EvalOutcome.Invalid)
        assertTrue((outcome as EvalOutcome.Invalid).reason.contains("环境已变"))
        assertEquals("不许开跑", 0, calls.size)
    }

    @Test
    fun `负例1b_任一字段不同_comparable为假`() {
        setUp()
        val base = fixtureOf(EvalRunner(ws, global, log = {}, clock = { now }), "新正文")
        assertFalse(base.copy(userText = "换了个问法").comparableWith(base))
        assertFalse(base.copy(model = "p1/m-2@t=0.2").comparableWith(base))
        assertFalse(base.copy(workspaceSnapshotSha = "deadbeef").comparableWith(base))
        assertTrue(base.copy(skillBodyNew = "别的正文").comparableWith(base)) // 只差改动本体
    }

    // ── 负例②：测试消息留在用户会话 ⇒ 红（形状上门就不存在）────────────────────

    @Test
    fun `负例2_评测不碰真会话与真工作区_源码门禁`() {
        // 门禁剥注释再查（注释里提到这些词是讲道理，不是代码在用——205 同款做法）
        val src = repoFile("src/main/java/com/example/zhengdao/baopu/EvalRunner.kt").readText()
            .replace(Regex("(?s)/\\*.*?\\*/"), "")
            .replace(Regex("(?m)^[ \\t]*//.*$"), "")
        listOf(
            "BaopuDb" to "不碰会话库",
            "createSession" to "不建真会话（测试消息无从产生）",
            "insertSession" to "不写会话表",
            "BaopuRuntime" to "不进生产运行时（隔离由 EvalHarness 临时目录保证）",
        ).forEach { (needle, why) ->
            assertTrue("EvalRunner 不许出现 $needle（$why）", !src.contains(needle))
        }
        // 行为半边：跑完真工作区一个字节不变（种子进的是 EvalHarness 的临时目录）
        setUp()
        writeSkill("技能A", "旧正文")
        writeLedger()
        val digestBefore = treeDigest(ws)
        val (fake, _, _) = fakeRuns(caseResult(true), caseResult(true))
        val runner = EvalRunner(ws, global, log = {}, runCase = fake, clock = { now })
        runner.runOnce(fixtureOf(runner, "新正文"), provider(), 0L, 0)
        assertEquals("真工作区零变化", digestBefore, treeDigest(ws))
    }

    // ── 负例③：只比行为列就判 ADOPT ⇒ 红 ───────────────────────────────────

    @Test
    fun `负例3_行为变了但结果没有变好_DEFER不许ADOPT`() {
        val old = EvalAttempt(true, 6, listOf("a", "b", "c", "d", "e", "f"), null, 1000, 10, 10, listOf("p:1"))
        val new = EvalAttempt(true, 3, listOf("x", "y", "z"), null, 500, 5, 5, listOf("p:1"))
        // 行为差异明显（步数少了/工具换了），结果差异为空
        val behavior = EvalCompareRules.behaviorDiff(old, new)
        assertTrue(behavior.isNotEmpty())
        val verdict = EvalCompareRules.verdictOf(EvalCompareRules.resultDiff(old, new), old, new)
        assertEquals("行为≠变好：结论必须是 DEFER", EvalVerdict.DEFER, verdict.first)
        // 源码门禁：verdictOf 的签名与函数体不收行为差异
        val src = repoFile("src/main/java/com/example/zhengdao/baopu/EvalRunner.kt").readText()
        val sig = src.substringAfter("fun verdictOf(").substringBefore(")")
        assertTrue("verdictOf 签名不收 behaviorDiff", !sig.contains("behavior"))
        val body = src.substringAfter("fun verdictOf(").substringAfter("{").substringBefore("\n    }")
        assertTrue("verdictOf 函数体不读行为差异（负例③的执行点）", !body.contains("behavior") && !body.contains("toolCalls"))
    }

    // ── 负例④：无 key 编读数 ⇒ 红 ──────────────────────────────────────────

    @Test
    fun `负例4_无key出UNRUN_绝不开跑_绝不填数`() {
        setUp()
        writeSkill("技能A", "旧正文")
        val (fake, calls, _) = fakeRuns(caseResult(true), caseResult(true))
        val runner = EvalRunner(ws, global, log = {}, runCase = fake, clock = { now })
        val fixture = fixtureOf(runner, "新正文")

        val noProvider = runner.runOnce(fixture, null, 0L, 0)
        assertTrue(noProvider is EvalOutcome.NotRun)
        assertEquals(0, calls.size)
        val line = (noProvider as EvalOutcome.NotRun).line
        assertTrue("唯一合法形状：UNRUN｜原因｜时间｜fixture id", line.startsWith("UNRUN｜原因：") && line.contains(fixture.id))
        assertFalse("不许出现伪造读数", line.contains("ok=") || line.contains("步数") || line.contains("token"))
        assertTrue("UNRUN 行只允许四段", line.split("｜").size == 4)

        val blankKey = runner.runOnce(fixture, provider(apiKey = ""), 0L, 0)
        assertTrue(blankKey is EvalOutcome.NotRun)
        assertEquals("空 key 也不许跑", 0, calls.size)
        // 免费档（额度有限）同样不跑：RuntimeEval.unusableReason 的既有判据复用
        val free = provider().copy(builtinFree = true)
        assertTrue(blankKey is EvalOutcome.NotRun && runner.runOnce(fixture, free, 0L, 0) is EvalOutcome.NotRun)
        assertEquals("三种不可用加起来也没开跑", 0, calls.size)
    }

    // ── 负例⑤：对拍自动改技能/自动晋升 ⇒ 红（源码门禁）────────────────────────

    @Test
    fun `负例5_评测链路没有改技能与晋升的口_源码门禁`() {
        val src = repoFile("src/main/java/com/example/zhengdao/baopu/EvalRunner.kt").readText()
        listOf(
            "SkillStore.apply(" to "技能唯一写口",
            "promoteBody" to "转正写法",
            "commitSkillDraft" to "32 批写口",
            "applyProposal" to "索引写口",
            "archive(" to "软删",
        ).forEach { (needle, why) ->
            assertTrue("EvalRunner 不许出现 $needle（$why：不许自动改技能/晋升）", !src.contains(needle))
        }
    }

    // ── 负例⑥：24h 内重复跑 ⇒ 红 ───────────────────────────────────────────

    @Test
    fun `负例6_24小时内重复跑被压_不许开跑`() {
        setUp()
        writeSkill("技能A", "旧正文")
        val (fake, calls, _) = fakeRuns(caseResult(true), caseResult(true))
        val runner = EvalRunner(ws, global, log = {}, runCase = fake, clock = { now })
        val fixture = fixtureOf(runner, "新正文")
        val outcome = runner.runOnce(fixture, provider(), lastRunMs = now - EvalGate.DEDUP_WINDOW_MS + 1000, runsToday = 0)
        assertTrue(outcome is EvalOutcome.Invalid)
        assertEquals(0, calls.size)
        // 窗外（≥24h）放行
        assertTrue(
            runner.runOnce(fixture, provider(), lastRunMs = now - EvalGate.DEDUP_WINDOW_MS, runsToday = 0) is EvalOutcome.Ran,
        )
    }

    // ── 负例⑦：失败轮被悄悄丢弃 ⇒ 红 ────────────────────────────────────────

    @Test
    fun `负例7_失败轮照记且进结果列_不许只留成功轮`() {
        val old = caseResult(ok = true)
        val new = caseResult(ok = false, steps = 2, calls = listOf("read_file"), failures = listOf("缺产物文件：答案.md"))
        val attemptOld = EvalAttemptReader.of(old, 800)
        val attemptNew = EvalAttemptReader.of(new, 900)
        assertFalse("失败轮原样保留（ok=false）", attemptNew.ok)
        assertEquals("失败点可查", attemptNew.failurePoint, "第 2 步｜read_file｜缺产物文件：答案.md")
        val fixture = EvalFixture(
            id = "f", userText = "t", skillName = "技能A", skillBodyOld = "o", skillBodyNew = "n",
            workspaceSnapshotSha = "s", preconditions = emptyList(), model = "m",
            budget = EvalBudget(), seedFiles = emptyList(), createdAt = 0L,
        )
        val compare = EvalCompareRules.compare(fixture, attemptOld, attemptNew)
        assertEquals("结果变差 ⇒ REJECT（不许写成更高效）", EvalVerdict.REJECT, compare.verdict)
        assertTrue(compare.resultDiff.any { it.contains("结果变差") })
        assertTrue("两轮都在表里", EvalCompareRules.table(compare).contains("ok=true") && EvalCompareRules.table(compare).contains("ok=false"))
        // 都失败也不许丢轮、不许 ADOPT
        val bothFail = EvalCompareRules.compare(
            fixture,
            EvalAttempt(false, 1, listOf("a"), "boom", 1, 1, 1, emptyList()),
            EvalAttempt(false, 2, listOf("b"), "bam", 2, 2, 2, emptyList()),
        )
        assertEquals(EvalVerdict.DEFER, bothFail.verdict)
    }

    // ── 负例⑧：超每日上限仍继续 ⇒ 红 ────────────────────────────────────────

    @Test
    fun `负例8_超每日上限_明天再说`() {
        setUp()
        writeSkill("技能A", "旧正文")
        val (fake, calls, _) = fakeRuns(caseResult(true), caseResult(true))
        val runner = EvalRunner(ws, global, log = {}, runCase = fake, clock = { now })
        val outcome = runner.runOnce(fixtureOf(runner, "新正文"), provider(), 0L, runsToday = EvalGate.DAILY_QUOTA)
        assertTrue(outcome is EvalOutcome.Invalid)
        assertTrue((outcome as EvalOutcome.Invalid).reason.contains("明天再说"))
        assertEquals(0, calls.size)
        // 额度 19 跑 1 次 = 20：放行（边界不误伤）
        assertTrue(runner.runOnce(fixtureOf(runner, "新正文"), provider(), 0L, runsToday = EvalGate.DAILY_QUOTA - 1) is EvalOutcome.Ran)
    }

    // ── 正例 ────────────────────────────────────────────────────────────────

    @Test
    fun `正例_双跑出完整对比表_固定列齐全_结论入档`() {
        setUp()
        writeSkill("技能A", "旧正文")
        writeLedger()
        // 旧跑失败、新跑通过 ⇒ ADOPT；产物不同 ⇒ 结果差异有据
        val (fake, calls, prompts) = fakeRuns(
            caseResult(ok = false, steps = 4, calls = listOf("read_file", "read_file", "list_dir", "read_file"), failures = listOf("终答里没有「已装」")),
            caseResult(ok = true, steps = 3, calls = listOf("read_file", "write_file"), dirFiles = mapOf("答案.md" to "已装 3 台")),
        )
        val runner = EvalRunner(ws, global, log = {}, runCase = fake, clock = { now })
        val fixture = fixtureOf(runner, "新正文")
        val outcome = runner.runOnce(fixture, provider(), 0L, 0)
        assertTrue(outcome is EvalOutcome.Ran)
        val c = (outcome as EvalOutcome.Ran).compare
        assertEquals(EvalVerdict.ADOPT, c.verdict)
        assertEquals("两次同一句用户原话（一字不差）", listOf(fixture.userText, fixture.userText), prompts)
        assertEquals("旧→新恰好两次", 2, calls.size)
        assertEquals("只差 personaText：旧/新各一次", setOf("${fixture.id}-old", "${fixture.id}-new"), calls.toSet())
        val table = outcome.table
        listOf("步数", "工具", "失败", "ms", "token", "行为差异", "结果差异", "结论：ADOPT").forEach { col ->
            assertTrue("对比表固定列齐全（缺「$col」＝未完成）", table.contains(col))
        }
        // 档案行（结论未变不重复写）
        assertTrue(EvalGate.archiveNeedsAppend(null, fixture.id, "技能A", "ADOPT"))
        val line = EvalGate.archiveLineOf(fixture.id, "技能A", "ADOPT", c.reason, "旧ok=false/新ok=true", java.time.LocalDate.of(2026, 10, 25))
        assertTrue(line.startsWith("| 2026-10-25 | 对拍 | 技能A |"))
        assertFalse("同 fixture 同技能同结论 ⇒ 不再 append", EvalGate.archiveNeedsAppend("| 2026-10-25 | 对拍 | 技能A | ADOPT——x | fixture:${fixture.id} 读数:y | ADOPT |", fixture.id, "技能A", "ADOPT"))
        assertTrue("结论变了 ⇒ 要追加新行", EvalGate.archiveNeedsAppend("| 2026-10-25 | 对拍 | 技能A | DEFER——x | fixture:${fixture.id} 读数:y | DEFER |", fixture.id, "技能A", "ADOPT"))
    }

    @Test
    fun `UNRUN样例_一行四段_档案也记UNRUN`() {
        val line = EvalCompareRules.unrunLine("未配置模型密钥（当前没有启用通道）", now, "eval-2026-10-25-abc123")
        assertEquals("UNRUN｜原因：未配置模型密钥（当前没有启用通道）｜${java.time.Instant.ofEpochMilli(now)}｜eval-2026-10-25-abc123", line)
        val archiveLine = EvalGate.unrunArchiveLine("eval-2026-10-25-abc123", "技能A", "未配置模型密钥", java.time.LocalDate.of(2026, 10, 25))
        assertTrue(archiveLine.contains("| UNRUN |"))
        assertTrue(archiveLine.contains("对拍"))
    }

    @Test
    fun `快照_同环境字节稳定_环境变了哈希变`() {
        setUp()
        writeSkill("技能A", "旧正文")
        writeLedger()
        val a = EvalSnapshot.of(ws, global)
        val b = EvalSnapshot.of(ws, global)
        assertEquals("同环境两次快照逐字相同（fixture 可比的前提）", a, b)
        File(File(File(ws, "技能"), "技能A"), "SKILL.md").writeText("改了", Charsets.UTF_8)
        assertNotEquals("关键文件变了 ⇒ 快照变（负例①的原料）", a, EvalSnapshot.of(ws, global))
        // 关键文件集合：台账也算
        val products = File(ws, "产物")
        products.mkdirs()
        File(products, "台账.md").writeText("- ☐ x（1 字）\n", Charsets.UTF_8)
        assertNotEquals("台账进了关键文件集合", b, EvalSnapshot.of(ws, global))
    }

    @Test
    fun `EvalHarness_复用不改_种子进临时目录不进真工作区`() {
        // 复用证明：真 harness 在没网时的行为＝失败轮（ok=false, error 网络失败）——
        // 它也是「一轮结果」，不许被 EvalAttemptReader 丢弃。
        setUp()
        val result = EvalHarness.runCase(
            EvalCase(id = "net", note = "", prompt = "p", maxSteps = 1, files = listOf(EvalFile("种子.md", "s")), asserts = emptyList()),
            EvalConfig(apiKey = "k", baseUrl = "https://127.0.0.1:1", timeoutSeconds = 1),
        )
        assertFalse("网络失败也是一轮（ok=false 照记）", result.ok)
        val attempt = EvalAttemptReader.of(result, 10)
        assertFalse(attempt.ok)
        assertTrue(attempt.failurePoint != null)
    }

    @Test
    fun `验收补丁2_结论能从对比表独立复算_表含全部原始值`() {
        // 206 验收补丁（aaa081a8）：结论必须能从对比表独立复算——
        // 表列（ok/失败点/工具序列/产物清单）就是 verdictOf 的全部输入；
        // 复算通道＝对同一对 attempt 再跑一次 resultDiff→verdictOf，与 compare 的结论一致。
        val old = EvalAttempt(true, 5, listOf("list_dir", "read_file", "read_file", "read_file", "read_file"), "第 5 步｜read_file｜终答里没有「已排版」", 1000, 3200, 410, listOf("草稿.md:7ccec3cd642b"))
        val new = EvalAttempt(true, 3, listOf("list_dir", "read_file", "write_file"), null, 800, 2600, 350, listOf("终稿.md:d661c3d96d53"))
        val fixture = EvalFixture(
            id = "f", userText = "原话", skillName = "技能A", skillBodyOld = "o", skillBodyNew = "n",
            workspaceSnapshotSha = "s", preconditions = emptyList(), model = "m",
            budget = EvalBudget(), seedFiles = emptyList(), createdAt = 0L,
        )
        val compare = EvalCompareRules.compare(fixture, old, new)
        // 复算 1：从表列重跑同一纯函数 ⇒ 同一结论
        val recomputed = EvalCompareRules.verdictOf(EvalCompareRules.resultDiff(old, new), old, new)
        assertEquals(compare.verdict, recomputed.first)
        // 复算 2：表串里含复算所需的全部原始值（ok/失败点/工具/产物），无黑箱
        val table = EvalCompareRules.table(compare)
        listOf("ok=true", "失败 第 5 步", "草稿.md:7ccec3cd642b", "终稿.md:d661c3d96d53", "list_dir→read_file").forEach {
            assertTrue("对比表缺复算原料「$it」", table.contains(it))
        }
        // 复算 3：行为变好（步数 5→3）但结果变差（新挂）⇒ 必须 REJECT，不许写"更高效"
        val worse = EvalAttempt(false, 2, listOf("a", "b"), "第 2 步｜b｜缺产物", 100, 10, 10, emptyList())
        assertEquals(EvalVerdict.REJECT, EvalCompareRules.verdictOf(EvalCompareRules.resultDiff(old, worse), old, worse).first)
        // 复算 4：结果"无法判定"（两轮都失败、失败点不同）⇒ DEFER
        val bothFail = EvalAttempt(false, 1, listOf("a"), "x", 1, 1, 1, emptyList())
        val bothFail2 = EvalAttempt(false, 2, listOf("b"), "y", 2, 2, 2, emptyList())
        assertEquals(EvalVerdict.DEFER, EvalCompareRules.verdictOf(EvalCompareRules.resultDiff(bothFail, bothFail2), bothFail, bothFail2).first)
    }

    @Test
    fun `验收补丁1_fixture四项原始值可独立核验_不看自我声明`() {
        // 206 验收补丁（aaa081a8）：原话/快照哈希/模型参数/预算由验收方核原始值——
        // 四项都是 EvalFixture 的结构化字段（不是一句话声明），且快照可由 EvalSnapshot.of 独立重算。
        setUp()
        writeSkill("技能A", "旧正文")
        writeLedger()
        val runner = EvalRunner(ws, global, log = {}, clock = { now })
        val fixture = fixtureOf(runner, "新正文")
        assertEquals("北京那批装了没", fixture.userText) // 原话：结构化字段，一字不差可核
        assertEquals("p1/m-1@t=0.2", fixture.model)      // 模型参数：结构化字段
        assertEquals(30, fixture.budget.maxSteps)        // 预算：结构化字段
        assertEquals(180_000L, fixture.budget.timeoutMs)
        assertEquals(EvalSnapshot.of(ws, global), fixture.workspaceSnapshotSha) // 快照：可独立重算比对
    }

    // ── 工具 ────────────────────────────────────────────────────────────────

    private fun repoFile(rel: String): File = File(rel).let { if (it.isFile) it else File("app/$rel") }

    private fun treeDigest(root: File): Map<String, String> {
        val out = mutableMapOf<String, String>()
        root.walkTopDown().filter { it.isFile }.forEach { f ->
            out[f.relativeTo(root).path.replace('\\', '/')] = SkillAudit.shaOf(f)
        }
        return out
    }
}
