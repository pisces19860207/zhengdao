// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 第九十九批的**生产调用点与红线门禁**（判据 §4 的源码侧）：
//   ① outcomesFor / EvolutionDecision / dry-run / eval 结果 / MANUAL 入口各有非测试引用
//      （这条就是防「又做成死代码」的钉子——零件齐、没人装正是本单的出发点）；
//   ② L3 红线：常量恒 false、生产无 true、EvolutionPolicy 生产实例化只有决策口一处；
//   ③ 判定链只读不写盘（负例必须能红）；
//   ④ MANUAL 效果信号过 85 的闸门（source=MANUAL 原样进库）。
package com.example.zhengdao.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EvolutionLoopWiringTest {

    private fun raw(rel: String): String {
        val f = File(rel).takeIf { it.isFile } ?: File("app/$rel")
        return f.readText(Charsets.UTF_8).replace("\r\n", "\n")
    }

    private val controller = raw("src/main/java/com/example/zhengdao/ui/baopu/BaopuChatController.kt")
    private val runtime = raw("src/main/java/com/example/zhengdao/baopu/BaopuRuntime.kt")
    private val settings = raw("src/main/java/com/example/zhengdao/ui/baopu/BaopuSettingsScreen.kt")
    private val policy = raw("src/main/java/com/example/zhengdao/baopu/EvolutionPolicy.kt")
    private val decision = raw("src/main/java/com/example/zhengdao/baopu/EvolutionDecision.kt")

    /** main 下引用某 token 的 .kt 文件（排除定义所在文件；定义不算「有人用」）。 */
    private fun mainRefs(token: String, excludeDefSuffix: String): List<String> {
        val root = File("src/main").takeIf { it.isDirectory } ?: File("app/src/main")
        return root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.readText(Charsets.UTF_8).contains(token) }
            .map { it.toRelativeString(root.parentFile).replace('\\', '/') }
            .filterNot { it.endsWith(excludeDefSuffix) }
            .sorted()
            .toList()
    }

    // ── ① 生产调用点（防死代码钉子）─────────────────────────────────────

    @Test
    fun `outcomesFor有非测试引用`() {
        val refs = mainRefs("outcomesFor", "baopu/SkillAudit.kt")
        assertTrue("85 的效果聚合必须有生产调用点：$refs", refs.isNotEmpty())
        assertTrue("调用点必须在收尾判定链上", refs.any { it.endsWith("ui/baopu/BaopuChatController.kt") })
    }

    @Test
    fun `EvolutionDecision有非测试引用`() {
        val refs = mainRefs("EvolutionDecision", "baopu/EvolutionDecision.kt")
        assertTrue("88 的决策口必须有生产调用点：$refs", refs.isNotEmpty())
        assertTrue("判定链必须落 EvolutionDecision", refs.any { it.endsWith("ui/baopu/BaopuChatController.kt") })
    }

    @Test
    fun `dryRun自检有生产调用点_且只在证据桥里定义`() {
        assertTrue("证据桥必须定义 dry-run", File("src/main/java/com/example/zhengdao/baopu/EvolutionInputs.kt").isFile)
        assertTrue("控制器判定前必须跑 dry-run", controller.contains("dryRunSelfCheck("))
    }

    @Test
    fun `eval证据有生产调用点_只认结果文件`() {
        assertTrue(controller.contains("EvalResultFile.latest("))
        val refs = mainRefs("EvalResultFile", "baopu/eval/EvalResult.kt")
        assertTrue("EvalResultFile 必须有生产调用点：$refs", refs.isNotEmpty())
    }

    @Test
    fun `listPending有生产调用点`() {
        val refs = mainRefs("listPending", "baopu/ProposalCandidateStore.kt")
        assertTrue("候选读口必须有生产调用点：$refs", refs.isNotEmpty())
    }

    @Test
    fun `MANUAL入口接线_设置页用一次记两行`() {
        assertTrue("BaopuRuntime 必须提供手动入口", runtime.contains("fun recordSkillUseManual("))
        assertTrue("效果信号来源必须是 MANUAL", runtime.contains("SkillSource.MANUAL"))
        assertTrue("设置页「用一次」必须接上", settings.contains("recordSkillUseManual("))
        // 不新开写盘路径：复用 57 的 use 行与 85 的效果行拼装函数
        assertTrue("use 行仍是 57 那条", runtime.contains("BaopuSql.insertSkillUse(name, null, null, true, now)"))
        assertTrue("效果行仍走 85 的闸门", runtime.contains("SkillEffects.toStmt(signal)"))
    }

    @Test
    fun `状态接缝_候选加判定进可观察状态`() {
        assertTrue(controller.contains("var evolutionSuggestions by mutableStateOf"))
        assertTrue(controller.contains("List<EvolutionSuggestion>"))
        assertTrue(controller.contains("evolutionSuggestions = it"))
    }

    @Test
    fun `审计与决策口接线`() {
        assertTrue("审计落点必须接上", controller.contains("EvolutionAuditLog.under("))
        assertTrue("决策口必须接上", controller.contains("EvolutionDecisionPort("))
        assertTrue("每次判定恰好一条审计（decide 的既有口径）", decision.contains("audit.record("))
    }

    // ── ② L3 红线 ───────────────────────────────────────────────────────

    @Test
    fun `L3开关恒false_生产无true`() {
        assertTrue("常量必须仍是 false", policy.contains("const val L3_AUTO_LOW_RISK = false"))
        val root = File("src/main").takeIf { it.isDirectory } ?: File("app/src/main")
        val trues = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.readText(Charsets.UTF_8).contains("autoLowRiskEnabled = true") }
            .map { it.name }
            .toList()
        assertTrue("生产不许出现 autoLowRiskEnabled = true：$trues", trues.isEmpty())
        assertFalse("也不许写成 L3_AUTO_LOW_RISK = true", policy.contains("L3_AUTO_LOW_RISK = true"))
    }

    @Test
    fun `EvolutionPolicy生产实例化只有决策口一处`() {
        val root = File("src/main").takeIf { it.isDirectory } ?: File("app/src/main")
        val sites = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { Regex("\\bEvolutionPolicy\\(").containsMatchIn(it.readText(Charsets.UTF_8)) }
            // 类声明（EvolutionPolicy.kt 自己）不是实例化；测试注入算另一回事（不扫 test）
            .filterNot { it.name == "EvolutionPolicy.kt" }
            .map { it.path.replace('\\', '/') }
            .toList()
        assertEquals(
            "生产只许 EvolutionDecision.kt 造默认策略（控制器用决策口，不自建）",
            listOf("src/main/java/com/example/zhengdao/baopu/EvolutionDecision.kt"),
            sites,
        )
    }

    // ── ③ 判定链只读不写盘（门禁＋负例）────────────────────────────────

    private fun noDirectWrite(src: String): Boolean = listOf(
        "appendNew(",
        "applyProposal(",
        "SkillStore.apply(",
        "SkillStore.plan(",
        "writeLearnedSkill(",
        ".restore(",
        "PersonaFiles.",
        "Files.move",
        "SQLite",
        "execSQL",
        "CREATE TABLE",
    ).none { src.contains(it) }

    @Test
    fun `判定链只读不写盘_负例必须红`() {
        val judge = controller.substringAfter("private fun judgeProposalCandidates()")
            .substringBefore("候选目标技能在盘上的文件")
        assertTrue("真源码必须过门禁", noDirectWrite(judge))
        assertFalse(
            "负例：判定链写技能必须红",
            noDirectWrite("SkillStore.apply(plan, pf, text)"),
        )
        assertFalse(
            "负例：判定链改候选状态必须红",
            noDirectWrite("store.appendNew(listOf(c))"),
        )
    }

    @Test
    fun `判定链四类证据齐活`() {
        val judge = controller.substringAfter("private fun judgeProposalCandidates()")
            .substringBefore("候选目标技能在盘上的文件")
        assertTrue("效果：outcomesFor", judge.contains("SkillEffects.outcomesFor(db, c.target)"))
        assertTrue("自检：dry-run", judge.contains("dryRunSelfCheck("))
        assertTrue("eval：结果文件", judge.contains("EvalResultFile.latest(appCtx)"))
        assertTrue("回滚：dry-run 内部判（84 rollbackVia）", judge.contains("dryRunSelfCheck("))
        assertTrue("候选：listPending", judge.contains("store.listPending()"))
        assertTrue("组装：inputFor", judge.contains("inputFor(c, evidence)"))
        assertTrue("决策：port.decide", judge.contains("port.decide("))
    }

    @Test
    fun `判定失败不阻塞_单条跳过有日志`() {
        val judge = controller.substringAfter("private fun judgeProposalCandidates()")
            .substringBefore("候选目标技能在盘上的文件")
        assertTrue("单条失败 runCatching 兜住", judge.contains("runCatching {"))
        assertTrue("跳过要留痕", judge.contains("候选判定跳过"))
    }

    // ── ④ MANUAL 效果信号过闸门 ─────────────────────────────────────────

    @Test
    fun `MANUAL信号过闸门_来源原样进库`() {
        val signal = SkillEffects.Signal(
            name = "做卡流程",
            skillVersion = "0a1b2c3d4e5f",
            outcome = SkillOutcome.SUCCESS,
            source = SkillSource.MANUAL,
            reason = SkillReasonCode.NONE,
            sessionId = null,
            step = null,
            attemptOk = true,
            at = 1_700_000_000_000L,
        )
        val stmt = SkillEffects.toStmt(signal)
        assertNotNull("MANUAL 信号必须能拼出语句", stmt)
        assertTrue("source 列必须是 MANUAL", stmt!!.args.contains("MANUAL"))
        assertTrue("无会话 ⇒ 不猜重试/纠正（裸成败）", stmt.args.contains("SUCCESS"))
    }

    @Test
    fun `手动入口_没有会话上下文就不猜`() {
        val manual = runtime.substringAfter("fun recordSkillUseManual(")
            .substringBefore("\n    /**")
        assertTrue("会话号明写 null", manual.contains("sessionId = null"))
        assertTrue("只记裸成败", manual.contains("SkillEffects.classify(null, null, null, true)"))
        assertTrue("写失败只 log", manual.contains("RunLog.log("))
    }
}
