// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百五十五批 · 技能两段验收的验收测试（派工单六条负例逐条红＋正例）。
//
// 负例（编号＝派工单）：
//   ① 连续 2 败后仍自动选中；
//   ② 指纹变了照跑（带病执行）；
//   ③ 只有 3 次样本就按成功率降级；
//   ④ 把定位漂移直接改写成新选择器；
//   ⑤ 新建第二本账导致与 208 对不上；
//   ⑥ 同一 opId 重复记账把计数放大。
package com.example.zhengdao.baopu

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SkillAcceptanceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var ws: File
    private lateinit var globalRoot: File

    @Before
    fun setUp() {
        ws = tmp.newFolder("ws")
        globalRoot = tmp.newFolder("global")
        DecisionLedger.install(globalRoot)
    }

    @After
    fun tearDown() {
        DecisionLedger.uninstallForTest()
    }

    // ── 夹具 ────────────────────────────────────────────────────────────────

    private fun pf() = PersonaFiles(projectRoot = ws, globalRoot = globalRoot)

    private val CONTRACT = listOf(
        "<!-- contract appliesWhen url_domain=example.com -->",
        "<!-- contract success element_appears=结果 -->",
        "<!-- contract protected path:/admin -->",
    ).joinToString("\n")

    /** 盘上一条**正式**技能（契约三段齐全，可自动选中）。 */
    private fun putActive(name: String): File {
        val body = CONTRACT + "\n# $name\n流程正文\n"
        val plan = SkillStore.plan(
            proposal = SkillProposal(SkillScope.PROJECT, name, body, "验收用例", draft = false),
            projectRoot = ws,
            globalRoot = globalRoot,
            backupStamp = "s",
        )
        val r = SkillStore.apply(plan, pf(), pf().read(PersonaLayer.C_PROJECT).orEmpty())
        assertTrue("前置：正式技能要能写成：$r", r is SkillWriteResult.Written)
        return (r as SkillWriteResult.Written).file
    }

    private fun teachSkill(name: String, expect: Boolean = true) = TeachSkill(
        name = name,
        purpose = "查货",
        createdAtMs = 0L,
        startUrl = "https://example.com/start",
        allowedDomains = listOf("example.com"),
        clickTagWhitelist = listOf("BUTTON"),
        fingerprint = TeachFingerprint(listOf(TeachFingerprintAnchor("button", "搜索"))),
        steps = listOf(
            TeachStep(
                index = 1,
                action = TeachAction.CLICK,
                url = "https://example.com/start",
                locators = listOf(
                    TeachLocator(TeachLocatorKind.ROLE_NAME, "button|搜索"),
                    TeachLocator(TeachLocatorKind.TESTID, "go"),
                ),
                value = null,
                snapshot = TeachElementSnapshot("button", "button", "搜索", "搜索", null),
                expect = if (expect) listOf(TeachExpect(TeachExpectKind.TEXT_APPEARS, "结果")) else emptyList(),
            ),
        ),
    )

    private fun report(
        name: String,
        ok: Boolean,
        reason: String? = null,
        drifts: List<TeachDrift> = emptyList(),
    ) = TeachRunReport(
        skillName = name,
        ok = ok,
        stepsDone = if (ok) 1 else 0,
        failedStep = if (ok) null else 1,
        stopReason = reason,
        durationMs = 10L,
        drifts = drifts,
        refusals = emptyList(),
        ask = null,
        evidenceDir = null,
    )

    private fun sample(op: String, ts: Long, ok: Boolean, drifts: Int = 0) =
        AcceptanceSample(opId = op, atMs = ts, ok = ok, fingerprintOk = true, driftCount = drifts, site = "example.com")

    // ── ① 连续 2 败 ⇒ 降草稿、不再自动选中、208 一条带两个 opId ────────────────

    @Test
    fun `负例1_连续两败降为草稿_不再被自动选中_208记一条带两个opId`() {
        val file = putActive("查货-flow")
        val skill = teachSkill("查货-flow")

        val u1 = TeachReplayStore.recordRun(ws, globalRoot, pf(), skill, report("查货-flow", ok = false, reason = "第 1 步期望断言不满足"), 1_000_000L)
        assertFalse("第一次失败不降级", u1.demoted)
        assertEquals("active", SkillStore.statusOf(file.readText(Charsets.UTF_8)))

        val u2 = TeachReplayStore.recordRun(ws, globalRoot, pf(), skill, report("查货-flow", ok = false, reason = "第 1 步期望断言不满足"), 1_100_000L)
        assertTrue("连续两次失败要降级", u2.demoted)
        assertEquals("draft", SkillStore.statusOf(file.readText(Charsets.UTF_8)))
        assertTrue("索引行要改回草稿标", pf().read(PersonaLayer.C_PROJECT).orEmpty().contains("草稿"))

        val demoted = DecisionLedger.read(globalRoot).filter { it.kind == DecisionKind.SKILL_DEMOTED }
        assertEquals("降级只记一条", 1, demoted.size)
        assertTrue("evidence 要带第一次 opId", demoted[0].evidence.contains(u1.update.opId))
        assertTrue("evidence 要带第二次 opId", demoted[0].evidence.contains(u2.update.opId))
        assertTrue("既有技能账也要有一行", File(ws, "技能/_变更.jsonl").readText(Charsets.UTF_8).contains("skill_demoted"))

        val v = SkillAcceptance.verdict(TeachReplayStore.loadAcceptance(ws, "查货-flow"), "draft", 1_200_000L)
        assertFalse("降级后不许自动选中", v.autoSelectable)
        assertTrue("stale 之外仍可手动用", v.canRunManually)

        // 选中层（233 的 rank/autoPick）：验收不过的候选 ⇒ 契约全满足也进不了 selectable
        val ctx = SkillContext(url = "https://example.com/start")
        val body = file.readText(Charsets.UTF_8)
        assertTrue(
            "对照组：普通候选契约满足时应可选",
            SkillApplicability.rank(listOf(SkillCandidate("x", body, 1)), ctx).selectable.isNotEmpty(),
        )
        val bad = SkillApplicability.rank(
            listOf(SkillCandidate("x", body, 1, acceptanceOk = false, acceptanceNote = "连续 2 败已降级")),
            ctx,
        )
        assertTrue("验收不过的候选不许进 selectable：${bad.selectable}", bad.selectable.isEmpty())
        assertNull("autoPick 不许选它", SkillApplicability.autoPick(bad))

        // 第三次失败不重复记降级
        TeachReplayStore.recordRun(ws, globalRoot, pf(), skill, report("查货-flow", ok = false, reason = "又失败"), 1_300_000L)
        assertEquals("降级只记一次", 1, DecisionLedger.read(globalRoot).count { it.kind == DecisionKind.SKILL_DEMOTED })
    }

    // ── ② 指纹变了 ⇒ fail-closed，不许带病执行 ───────────────────────────────

    @Test
    fun `负例2_指纹对不上判SKILL_STALE_后续回放停手`() {
        putActive("过期-flow")
        val skill = teachSkill("过期-flow")
        val u = TeachReplayStore.recordRun(
            ws, globalRoot, pf(), skill,
            report("过期-flow", ok = false, reason = "页面结构变了，请重新教一遍（关键锚点缺失：搜索）"),
            2_000_000L,
        )
        assertTrue("要当场判 stale", u.update.staleNow)
        val pre = SkillAcceptance.preflight(TeachReplayStore.loadAcceptance(ws, "过期-flow"), "active", 2_000_100L)
        assertTrue("stale 必须拦住回放", pre.isBlocked)
        assertTrue("停手话要以 SKILL_STALE 开头：${pre.blocked}", pre.blocked!!.startsWith("SKILL_STALE"))

        val v = SkillAcceptance.verdict(TeachReplayStore.loadAcceptance(ws, "过期-flow"), "active", 2_000_100L)
        assertFalse("stale 不许自动选中", v.autoSelectable)
        assertFalse("stale 也不许手动带病跑", v.canRunManually)
    }

    @Test
    fun `指纹取不到只是判不了_不当成环境变了`() {
        putActive("加载慢-flow")
        val skill = teachSkill("加载慢-flow")
        val u = TeachReplayStore.recordRun(
            ws, globalRoot, pf(), skill,
            report("加载慢-flow", ok = false, reason = "取不到页面结构指纹（页面可能还在加载）"),
            2_100_000L,
        )
        assertFalse("判不了就不猜 stale", u.update.staleNow)
        assertNull(preflightOf(ws, 2_100_100L))
    }

    private fun preflightOf(root: File, nowMs: Long): String? =
        TeachReplayStore.loadAcceptance(root, "加载慢-flow").let {
            SkillAcceptance.preflight(it, "active", nowMs).blocked
        }

    // ── ③ 只有 3 次样本：unknown，不按成功率下结论 ────────────────────────────

    @Test
    fun `负例3_三次样本不按成功率降级_记unknown`() {
        var state = AcceptanceState()
        state = SkillAcceptance.applyRun(state, sample("a", 10_000L, ok = true), 10_000L).state
        state = SkillAcceptance.applyRun(state, sample("b", 11_000L, ok = false), 11_000L).state
        state = SkillAcceptance.applyRun(state, sample("c", 12_000L, ok = true), 12_000L).state
        val v = SkillAcceptance.verdict(state, "active", 12_500L)
        assertFalse("样本 <10 不许下成功率结论", v.rateKnown)
        assertFalse("不许标待退役", AcceptanceFlag.RETIRING in v.flags)
        assertTrue("要明确说 unknown：${v.sampleLine}", v.sampleLine.contains("unknown"))
        assertEquals("分子分母都要给：${v.sampleLine}", 3, v.windowTotal)
        assertEquals(2, v.windowOk)
    }

    // ── ④ 定位漂移只记不改 ──────────────────────────────────────────────────

    @Test
    fun `负例4_定位漂移只记不改_技能与回放数据一个字节不动`() {
        val file = putActive("漂移-flow")
        val before = file.readBytes()
        val skill = teachSkill("漂移-flow")
        val drifts = listOf(TeachDrift(1, "T1 roleName=button|搜索", "T2 testid=go"))
        repeat(3) { i ->
            TeachReplayStore.recordRun(ws, globalRoot, pf(), skill, report("漂移-flow", ok = true, drifts = drifts), 3_000_000L + i * 1000L)
        }
        val v = SkillAcceptance.verdict(TeachReplayStore.loadAcceptance(ws, "漂移-flow"), "active", 3_100_000L)
        assertNotNull("累计 3 次漂移要提示修选择器", v.driftTip)
        assertTrue(v.driftTip!!.contains("不会自动改"))
        assertArrayEquals("技能正文一个字节都不许改", before, file.readBytes())
        assertFalse("不许凭空写回放数据", File(ws, "技能/漂移-flow/回放.json").exists())
    }

    // ── ⑤ 不新建第二本账 ────────────────────────────────────────────────────

    @Test
    fun `负例5_不新建第二本账_验收状态与208按opId对得上`() {
        putActive("账本-flow")
        val skill = teachSkill("账本-flow")
        TeachReplayStore.recordRun(ws, globalRoot, pf(), skill, report("账本-flow", ok = false, reason = "失败一"), 4_000_000L)
        TeachReplayStore.recordRun(ws, globalRoot, pf(), skill, report("账本-flow", ok = false, reason = "失败二"), 4_100_000L)

        val skillRoot = File(ws, "技能")
        val rootJsonl = skillRoot.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(".jsonl") }.map { it.name }.toSet()
        assertTrue("技能根下只许既有两本账：$rootJsonl", rootJsonl.all { it in setOf("_变更.jsonl", "_回放审计.jsonl") })
        val nameDir = File(skillRoot, "账本-flow")
        assertTrue("技能目录里不许有第二本账", nameDir.listFiles().orEmpty().none { it.name.endsWith(".jsonl") })
        assertTrue("状态是快照不是账本", File(nameDir, "验收.json").isFile)

        val state = TeachReplayStore.loadAcceptance(ws, "账本-flow")
        val dem = DecisionLedger.read(globalRoot).first { it.kind == DecisionKind.SKILL_DEMOTED }
        assertEquals(2, state.demotionOpIds.size)
        state.demotionOpIds.forEach { op ->
            assertTrue("208 要能逐条对上 opId=$op", dem.evidence.contains(op))
        }
    }

    // ── ⑥ 同 opId 重复记账不放大 ────────────────────────────────────────────

    @Test
    fun `负例6_同一opId重复记账_计数不放大`() {
        putActive("重复-flow")
        val skill = teachSkill("重复-flow")
        val rep = report("重复-flow", ok = true)
        val a = TeachReplayStore.recordRun(ws, globalRoot, pf(), skill, rep, 5_000_000L)
        val b = TeachReplayStore.recordRun(ws, globalRoot, pf(), skill, rep, 5_000_000L)
        assertFalse(a.update.duplicate)
        assertTrue("同一次执行第二遍要判重", b.update.duplicate)
        assertEquals("样本数不许放大", 1, TeachReplayStore.loadAcceptance(ws, "重复-flow").samples.size)
        assertEquals(0, DecisionLedger.read(globalRoot).count { it.kind == DecisionKind.SKILL_DEMOTED })
    }

    // ── U4／U5／U6 的其余判据 ───────────────────────────────────────────────

    @Test
    fun `U4_十次样本成功率不足六成标待退役_恰好六成不算`() {
        // 5 成（失败/成功交替，避免触发 U1 的连续失败）
        var low = AcceptanceState()
        repeat(10) { i ->
            low = SkillAcceptance.applyRun(low, sample("l$i", 6_000_000L + i * 1000L, ok = i % 2 == 1), 6_000_000L + i * 1000L).state
        }
        val vLow = SkillAcceptance.verdict(low, "active", 6_020_000L)
        assertTrue("十次里只成 5 次 ⇒ 待退役", AcceptanceFlag.RETIRING in vLow.flags)
        assertTrue("待退役仍可手动用", vLow.canRunManually)
        assertFalse("待退役不许自动选中", vLow.autoSelectable)

        // 恰好 60%（6 成）：不许标待退役（判据是「< 60%」）
        var edge = AcceptanceState()
        val pattern = listOf(true, true, false, true, false, true, false, true, false, true)
        pattern.forEachIndexed { i, ok ->
            edge = SkillAcceptance.applyRun(edge, sample("e$i", 6_100_000L + i * 1000L, ok = ok), 6_100_000L + i * 1000L).state
        }
        val vEdge = SkillAcceptance.verdict(edge, "active", 6_120_000L)
        assertEquals(6, vEdge.windowOk)
        assertTrue(vEdge.rateKnown)
        assertFalse("恰好 60% 不算待退役", AcceptanceFlag.RETIRING in vEdge.flags)
    }

    @Test
    fun `U5_累计修正五次标该重学`() {
        var state = AcceptanceState()
        repeat(SkillAcceptance.RELEARN_CORRECTIONS) { state = SkillAcceptance.noteCorrection(state) }
        val v = SkillAcceptance.verdict(state, "active", 7_000_000L)
        assertTrue("补了 5 次要提示该重学", AcceptanceFlag.RELEARN in v.flags)
        val pre = SkillAcceptance.preflight(state, "active", 7_000_000L)
        assertTrue("提示要有一句人话：${pre.notes}", pre.notes.any { it.contains("重教") })
    }

    // ── G1／G2／G3 入库闸 ───────────────────────────────────────────────────

    @Test
    fun `G1_两个站点各验一次过关_单站点只能落草稿`() {
        val skill = teachSkill("站点-flow")
        val pass = SkillAcceptance.entryGate(
            EntrySubject.Replay(skill),
            Rehearsal(sites = listOf("a.example.com", "b.example.com")),
        )
        assertTrue("两站点 ⇒ Pass：$pass", pass is EntryVerdict.Pass)
        assertFalse((pass as EntryVerdict.Pass).singleSite)

        val one = SkillAcceptance.entryGate(EntrySubject.Replay(skill), Rehearsal(sites = listOf("a.example.com")))
        assertTrue("单站点 ⇒ 草稿：$one", one is EntryVerdict.DraftOnly)
        assertTrue((one as EntryVerdict.DraftOnly).reason.contains("只有一个站点"))
    }

    @Test
    fun `G2_文字技能没契约或success是空话_都拒收`() {
        val noContract = SkillAcceptance.entryGate(EntrySubject.Prose("# 一条技能\n点搜索框输入单号\n"))
        assertTrue("没有契约 ⇒ 拒：$noContract", noContract is EntryVerdict.Reject)
        assertTrue((noContract as EntryVerdict.Reject).reason.contains("G2"))

        val vague = SkillAcceptance.entryGate(
            EntrySubject.Prose(
                "<!-- contract success element_appears=完成 -->\n# 一条技能\n点搜索框\n",
            ),
        )
        assertTrue("success=「完成」是空话 ⇒ 拒：$vague", vague is EntryVerdict.Reject)

        val replayNoExpect = SkillAcceptance.entryGate(EntrySubject.Replay(teachSkill("无断言", expect = false)))
        assertTrue("示教步骤没有 expect ⇒ 拒：$replayNoExpect", replayNoExpect is EntryVerdict.Reject)
    }

    @Test
    fun `G3_碰到protected清单_拒收并说明碰了什么`() {
        val body = listOf(
            "<!-- contract appliesWhen url_domain=example.com -->",
            "<!-- contract success element_appears=结果 -->",
            "<!-- contract protected path:/admin -->",
            "# 清理流程",
            "1. 打开 https://example.com/admin 批量删除",
        ).joinToString("\n")
        val r = SkillAcceptance.entryGate(EntrySubject.Prose(body))
        assertTrue("碰了 protected ⇒ 拒：$r", r is EntryVerdict.Reject)
        val reason = (r as EntryVerdict.Reject).reason
        assertTrue("要说是 G3：$reason", reason.contains("G3"))
        assertTrue("要说明碰了什么：$reason", reason.contains("path:/admin") || reason.contains("/admin"))
    }

    // ── saveConfirmed 过闸（teach_skill_save）────────────────────────────────

    @Test
    fun `保存_示教技能缺expect_G2拒收且一个字节不写`() {
        val r = TeachReplayStore.saveConfirmed(
            draft = TeachSkillDraft(teachSkill("没断言-flow", expect = false), emptyList(), ""),
            projectRoot = ws,
            globalRoot = globalRoot,
            personaFiles = pf(),
            stampText = "20261028-120000",
        )
        assertNull("不许写盘", r.written)
        assertNotNull("要说清为什么", r.reason)
        assertTrue("原因要点到 G2：${r.reason}", r.reason!!.contains("G2"))
        assertFalse("技能目录一个都不许建", File(ws, "技能/没断言-flow").exists())
    }

    @Test
    fun `保存_单站点只能落草稿_并在人读卡里说明`() {
        val r = TeachReplayStore.saveConfirmed(
            draft = TeachSkillDraft(teachSkill("单站-flow"), emptyList(), ""),
            projectRoot = ws,
            globalRoot = globalRoot,
            personaFiles = pf(),
            stampText = "20261028-130000",
        )
        assertNotNull("允许先草稿入库", r.written)
        assertNotNull("要有一句提示", r.notice)
        assertTrue(r.notice!!.contains("只有一个站点"))
        val md = File(ws, "技能/单站-flow/SKILL.md").readText(Charsets.UTF_8)
        assertTrue("草稿头", md.startsWith("<!-- status=draft uses=0 -->"))
        assertTrue("人读卡里要有这句警示", md.contains("只有一个站点"))
    }

    /**
     * 体检表（派工单交付项）：把三条内置种子按入库闸逐条跑一遍。
     *
     * 三条都是纯文字流程、没有契约 ⇒ 逐条 G2 未过。这是**事实记录**，不是「接闸后
     * 应该缺技能」的认可：种子安装暂未接闸，理由见报告（接闸即清零内置技能，待拍板）。
     */
    @Test
    fun `种子体检_三条内置技能都过不了G2_所以种子安装暂未接闸`() {
        val rows = SeedSkills.NAMES.map { name ->
            val f = File("app/src/main/assets/${SeedSkills.assetPathOf(name)}")
                .let { if (it.isFile) it else File("src/main/assets/${SeedSkills.assetPathOf(name)}") }
            assertTrue("种子资产要在盘上：${f.path}", f.isFile)
            val body = f.readText(Charsets.UTF_8)
            val gate = SkillAcceptance.entryGate(EntrySubject.Prose(body))
            assertTrue("$name 应当是 G2 拒收：$gate", gate is EntryVerdict.Reject)
            assertTrue("$name 的拒收原因要点到 G2：${(gate as EntryVerdict.Reject).reason}", gate.reason.contains("G2"))
            name to gate.reason
        }
        assertEquals(3, rows.size)
    }
}
