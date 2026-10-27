// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百三十六批 · A 档自改的**执行壳**（派工单 §2 判据后半句＋§4「自治 ≠ 免检」＋交付项
// 「A 档自改样例（含回滚演示）」）。
//
// 这里的"红"就是"没做成"：负例④＝改完不跑对拍就保留 ⇒ 用例断言它**没保留、退回了原字节**；
// 负例②③＝B/C 档 ⇒ 用例断言**盘上一个字节没动**。
package com.example.zhengdao.baopu

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AutonomySelfEditTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var ws: File
    private lateinit var global: File
    private lateinit var persona: PersonaFiles
    private val logs = mutableListOf<String>()

    private val skillName = "整理台账"
    private val bodyOld = "# 整理台账\n\n## 步骤\n- 读 产物/台账.md\n- 按日期排\n"
    private val bodyNew = "# 整理台账\n\n## 步骤\n- 读 产物/台账.md\n- 按日期排\n\n## 例子\n- 输入「补 10 月」⇒ 只补当月\n"

    private val now = 1_780_000_000_000L

    @Before
    fun setUp() {
        ws = tmp.newFolder("ws")
        global = tmp.newFolder("global")
        persona = PersonaFiles(global, ws)
        logs.clear()
        DecisionLedger.install(global)
        skillFile().parentFile!!.mkdirs()
        skillFile().writeText(bodyOld, Charsets.UTF_8)
        persona.fileOf(PersonaLayer.C_PROJECT).parentFile!!.mkdirs()
        persona.fileOf(PersonaLayer.C_PROJECT).writeText("# 本项目人设\n\n## 说话纪律\n- 不说没做过的话\n", Charsets.UTF_8)
    }

    @After
    fun tearDown() {
        // 谁装谁还：落点是进程级全局，不还原会按执行顺序弄红别的用例（224 批实证过）
        DecisionLedger.uninstallForTest()
    }

    private fun skillFile(): File =
        File(File(SkillRules.projectSkillDir(ws), skillName), SkillRules.SKILL_FILE)

    private fun proposal(
        kind: AutonomyChangeKind,
        current: String = bodyOld,
        next: String = bodyNew,
        target: String = skillName,
    ) = AutonomyProposal(
        kind = kind,
        target = target,
        currentText = current,
        newText = next,
        scopeProject = true,
        rollbackReady = true,
        auditReady = true,
        evalRunnable = true,
    )

    private fun shell(eval: (AutonomyProposal) -> EvalOutcome) = AutonomySelfEdit(
        personaFiles = persona,
        projectRoot = ws,
        globalRoot = global,
        eval = eval,
        currentIndexText = persona.read(PersonaLayer.C_PROJECT).orEmpty(),
        stamp = { "20261027-120000" },
        nowMs = { now },
    )

    // ── 206 结论的假注入（只造判据要看的三个字段）────────────────────────────

    private fun attempt(ok: Boolean) = EvalAttempt(
        ok = ok,
        steps = 3,
        toolCalls = listOf("read_file", "write_file"),
        failurePoint = if (ok) null else "第 3 步｜write_file｜没找到目录",
        elapsedMs = 900,
        tokensIn = 10,
        tokensOut = 20,
        products = listOf("台账.md:abc123"),
    )

    private fun ran(
        verdict: EvalVerdict,
        resultDiff: List<String> = emptyList(),
    ) = EvalOutcome.Ran(
        EvalCompare(
            fixtureId = "eval-test",
            skillName = skillName,
            old = attempt(true),
            new = attempt(verdict != EvalVerdict.REJECT),
            behaviorDiff = emptyList(),
            resultDiff = resultDiff,
            verdict = verdict,
            reason = "测试注入的对拍结论",
        ),
        "对拍表｜$verdict",
    )

    // ── 交付项：A 档自改样例（不弹确认 → 跑对拍 → 留着）────────────────────────

    @Test
    fun `正例_A档补示例_不弹确认且写完留了备份与一条账`() {
        val out = shell { ran(EvalVerdict.ADOPT) }.run(proposal(AutonomyChangeKind.SKILL_EXAMPLE_ADD))
        val kept = out as? SelfEditOutcome.Kept
        assertNotNull("A 档三件齐＋对拍 ADOPT 应当保留，实得 $out", kept)
        assertEquals(bodyNew, skillFile().readText(Charsets.UTF_8))
        assertNotNull("写前必须留备份（§2.5.2 条件②）", kept!!.backup)
        // 208 一条 auto_act（派工单 §4：不新增 kind）
        val rows = DecisionLedger.read(global)
        assertEquals(1, rows.count { it.kind == DecisionKind.AUTO_ACT })
        assertTrue(rows.first { it.kind == DecisionKind.AUTO_ACT }.evidence.contains("autonomy_tier:A"))
        // 卡面上欠的那一句：A 档不摆确认卡（与判据层同一个口径，不在壳里另算一遍）
        assertFalse(AutonomyRules.decide(proposal(AutonomyChangeKind.SKILL_EXAMPLE_ADD)).requiresApproval)
    }

    // ── 交付项：回滚演示（§4 最硬那条：自治 ≠ 免检）───────────────────────────

    @Test
    fun `负例4_对拍判REJECT_自动回滚到原字节`() {
        val out = shell { ran(EvalVerdict.REJECT) }.run(proposal(AutonomyChangeKind.SKILL_EXAMPLE_ADD))
        assertTrue("对拍没过还留着：$out", out is SelfEditOutcome.RolledBack)
        // 盘上退回原样＝这一改等于没发生（"没保留"就是这条负例的红）
        assertEquals(bodyOld, skillFile().readText(Charsets.UTF_8))
        val rows = DecisionLedger.read(global)
        assertEquals(1, rows.count { it.kind == DecisionKind.AUTO_ACT })
        assertEquals("回滚本身也要留一条（否则等于什么都没发生）", 1, rows.count { it.kind == DecisionKind.ROLLBACK_OF })
    }

    @Test
    fun `负例4_对拍没跑成UNRUN_同样不许保留`() {
        // 没有"不回归"的证据＝不能留着：与 233 门槛④「没跑对拍 ⇒ 不许采纳」同一口径
        val out = shell { EvalOutcome.NotRun("未配置模型密钥", "no-key") }
            .run(proposal(AutonomyChangeKind.SKILL_WORDING_EDIT))
        assertTrue("没跑成还留着：$out", out is SelfEditOutcome.RolledBack)
        assertEquals(bodyOld, skillFile().readText(Charsets.UTF_8))
    }

    @Test
    fun `负例4_对拍ADOPT但结果列变差_回滚`() {
        val out = shell { ran(EvalVerdict.ADOPT, listOf("产物少了：台账.md")) }
            .run(proposal(AutonomyChangeKind.SKILL_EXAMPLE_ADD))
        assertTrue("结果列退化被结论字面盖过去了：$out", out is SelfEditOutcome.RolledBack)
        assertEquals(bodyOld, skillFile().readText(Charsets.UTF_8))
    }

    @Test
    fun `负例4_DEFER没回归可以留着`() {
        val out = shell { ran(EvalVerdict.DEFER) }.run(proposal(AutonomyChangeKind.SKILL_EXAMPLE_ADD))
        assertTrue("DEFER＝跑成了没见坏，A 档该留：$out", out is SelfEditOutcome.Kept)
        assertEquals(bodyNew, skillFile().readText(Charsets.UTF_8))
    }

    @Test
    fun `负例4_回滚也失败必须出声`() {
        // 模拟备份在两次之间被外面删掉：回滚退不回去 ⇒ RollbackFailed（不许当成保留）
        val out = shell { p ->
            skillFile().parentFile!!.listFiles { f -> f != null && f.name.contains(SkillStore.BACKUP_MARK) }
                ?.forEach { it.delete() }
            ran(EvalVerdict.REJECT)
        }.run(proposal(AutonomyChangeKind.SKILL_EXAMPLE_ADD))
        val failed = out as? SelfEditOutcome.RollbackFailed
        assertNotNull("回滚失败被静默吞了：$out", failed)
        val line = SelfEditTell.line(failed!!)
        assertTrue("最坏的一态要说清找谁：$line", line.contains("人工"))
    }

    // ── 负例②③：B/C 档走到执行壳，盘上必须一根毛都不动 ───────────────────────

    @Test
    fun `负例2_B档走到壳里_一个字节都没写`() {
        val out = shell { ran(EvalVerdict.ADOPT) }.run(proposal(AutonomyChangeKind.SKILL_NEW))
        val nw = out as? SelfEditOutcome.NotWritten
        assertNotNull("B 档居然写了盘：$out", nw)
        assertTrue(nw!!.verdict.requiresApproval)
        assertEquals(bodyOld, skillFile().readText(Charsets.UTF_8))
        assertTrue("没写盘就不该留 auto_act 账", DecisionLedger.read(global).isEmpty())
    }

    @Test
    fun `负例3_C档走到壳里_写不进去也不摆卡`() {
        listOf(
            AutonomyChangeKind.SKILL_DELETE,
            AutonomyChangeKind.PROTECTED_CLAUSE,
            AutonomyChangeKind.RISK_TIER,
            AutonomyChangeKind.PERMISSION_BIT,
            AutonomyChangeKind.TOOL_WHITELIST,
            AutonomyChangeKind.CONFIRM_POLICY,
        ).forEach { kind ->
            val out = shell { ran(EvalVerdict.ADOPT) }.run(proposal(kind))
            val nw = out as? SelfEditOutcome.NotWritten
            assertNotNull("$kind 竞被自动路径写了：$out", nw)
            assertFalse("$kind 不许摆成确认卡", nw!!.verdict.requiresApproval)
            assertTrue("$kind 要落进 NeverAuto", nw.verdict is AutonomyVerdict.NeverAuto)
        }
        assertEquals(bodyOld, skillFile().readText(Charsets.UTF_8))
        assertTrue(DecisionLedger.read(global).isEmpty())
    }

    @Test
    fun `负例3_自称A实为改契约_壳里被降回B_正文没动`() {
        val cur = "# 整理台账\n<!-- contract appliesWhen url_domain=a.com -->\n- 按日期排\n"
        val next = "# 整理台账\n<!-- contract appliesWhen url_domain=b.com -->\n- 按日期排\n"
        skillFile().writeText(cur, Charsets.UTF_8)
        val out = shell { ran(EvalVerdict.ADOPT) }
            .run(proposal(AutonomyChangeKind.SKILL_EXAMPLE_ADD, cur, next))
        val nw = out as? SelfEditOutcome.NotWritten
        assertNotNull("契约行被当措辞写下去了：$out", nw)
        assertEquals(cur, skillFile().readText(Charsets.UTF_8))
    }

    // ── 两条本层自己加的守门（现有写口兜不住的形状，宁可不做）─────────────────

    @Test
    fun `正例_草稿技能不自改_试用期计数不丢`() {
        val draft = SkillStore.draftHeader(status = "draft", uses = 2) + "\n" + bodyOld
        skillFile().writeText(draft, Charsets.UTF_8)
        val out = shell { ran(EvalVerdict.ADOPT) }
            .run(proposal(AutonomyChangeKind.SKILL_WORDING_EDIT, draft, SkillStore.draftHeader("draft", 2) + "\n" + bodyNew))
        val nw = out as? SelfEditOutcome.NotWritten
        assertNotNull("草稿被自改（uses 会被写口清零）：$out", nw)
        assertTrue(nw!!.line.contains("草稿"))
        assertEquals(draft, skillFile().readText(Charsets.UTF_8))
    }

    @Test
    fun `正例_没有原件就不是自改_是新增`() {
        skillFile().delete()
        val out = shell { ran(EvalVerdict.ADOPT) }
            .run(proposal(AutonomyChangeKind.SKILL_EXAMPLE_ADD, "", bodyNew))
        val nw = out as? SelfEditOutcome.NotWritten
        assertNotNull("没有原件也被自改了：$out", nw)
        assertTrue(nw!!.verdict is AutonomyVerdict.NeedsApproval)
        assertFalse("不该凭空造出一条技能", skillFile().isFile)
    }

    // ── 说话纪律（A 档的第二条道：走人设写口）─────────────────────────────────

    @Test
    fun `正例_追加一条说话纪律_既有条目逐字不动`() {
        val cur = persona.read(PersonaLayer.C_PROJECT)!!
        val next = cur.trimEnd('\n') + "\n- 报数必带出处\n"
        val out = shell { ran(EvalVerdict.ADOPT) }.run(
            proposal(AutonomyChangeKind.PROMPT_DISCIPLINE_APPEND, cur, next, target = "说话纪律"),
        )
        assertTrue("$out", out is SelfEditOutcome.Kept)
        val after = persona.read(PersonaLayer.C_PROJECT)!!
        assertTrue(after.startsWith(cur.trimEnd('\n')))
        assertTrue(after.contains("报数必带出处"))
    }

    @Test
    fun `负例4_纪律改完对拍没过_人设退回上一版`() {
        val cur = persona.read(PersonaLayer.C_PROJECT)!!
        val next = cur.trimEnd('\n') + "\n- 报数必带出处\n"
        val out = shell { ran(EvalVerdict.REJECT) }.run(
            proposal(AutonomyChangeKind.PROMPT_DISCIPLINE_APPEND, cur, next, target = "说话纪律"),
        )
        assertTrue("纪律改动没验成还留着：$out", out is SelfEditOutcome.RolledBack)
        assertEquals(cur, persona.read(PersonaLayer.C_PROJECT))
    }

    @Test
    fun `正例_索引行走人设写口_不另开一条道`() {
        val cur = persona.read(PersonaLayer.C_PROJECT)!!
        val next = cur.trimEnd('\n') + "\n" +
            SkillRules.renderIndexLine(SkillRules.IndexLine(MemoryScope.PROJECT, "查物流", "问单号就查")) + "\n"
        val out = shell { ran(EvalVerdict.ADOPT) }.run(
            proposal(AutonomyChangeKind.SKILL_INDEX_LINE_ADD, cur, next, target = "查物流"),
        )
        assertTrue("$out", out is SelfEditOutcome.Kept)
        val after = persona.read(PersonaLayer.C_PROJECT)!!
        assertTrue(after.contains(SkillRules.INDEX_MARK))
        // 既有正文没被这次"加行"抹掉
        assertTrue(after.contains("不说没做过的话"))
    }
}
