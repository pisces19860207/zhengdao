// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百六十八批 A · **技能域夹具**（判据①②④的域内走法，盘上真账）：
//   候选（write_skill 提案）→ 门放行 → 免批落草稿（灰度）→ 用满 3 次转正 → 一键回退；
//   三红线负例各一：假动作打红线 ⇒ 拒（门出 Refuse）**并记账**（既有参谋账留一行）。
//
// 走的都是既有机制：免批写=192 的 SkillAutoWrite、转正=184/207 的 afterUse/promoteBody、
// 回退=207 的 revertBody、灰度=473 的登记表与观察账——本测试一条新判不写，只把链走一遍。
package com.example.zhengdao.baopu.evolution.lowrisk

import com.example.zhengdao.baopu.DecisionActor
import com.example.zhengdao.baopu.DecisionDraft
import com.example.zhengdao.baopu.DecisionKind
import com.example.zhengdao.baopu.DecisionLedger
import com.example.zhengdao.baopu.DecisionRecord
import com.example.zhengdao.baopu.PersonaFiles
import com.example.zhengdao.baopu.SkillAutoOutcome
import com.example.zhengdao.baopu.SkillAutoWrite
import com.example.zhengdao.baopu.SkillPromote
import com.example.zhengdao.baopu.SkillProposal
import com.example.zhengdao.baopu.SkillRules
import com.example.zhengdao.baopu.SkillScope
import com.example.zhengdao.baopu.SkillStore
import com.example.zhengdao.baopu.consequence.AdvisorRules
import com.example.zhengdao.baopu.consequence.CanaryCapability
import com.example.zhengdao.baopu.consequence.ConsequenceEvidence
import com.example.zhengdao.baopu.consequence.ConsequenceWiring
import com.example.zhengdao.testkit.TempSandbox
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SkillEvoFixtureTest {

    private val now = 1_760_000_000_000L
    private val entropy = "3f2504e0-4f89-11d3-9a0c-0305e82c3301"
    private val cap: CanaryCapability = CanaryCapability.REFLECTION_AUTO_EDIT

    private lateinit var sandbox: File
    private lateinit var memory: File
    private lateinit var projectRoot: File
    private lateinit var globalRoot: File

    @Before
    fun setUp() {
        sandbox = TempSandbox.create("zd568a-skill-")
        memory = File(sandbox, "抱朴记忆")
        projectRoot = File(sandbox, "ws").apply { mkdirs() }
        globalRoot = File(sandbox, "global").apply { mkdirs() }
        DecisionLedger.install(memory)
        ConsequenceWiring.install(sandbox, now) { entropy }
    }

    @After
    fun tearDown() {
        ConsequenceWiring.uninstallForTest()
        DecisionLedger.uninstallForTest()
        TempSandbox.deleteTree(sandbox)
    }

    private fun rows(): List<DecisionRecord> = DecisionLedger.read(memory)

    private fun pf() = PersonaFiles(projectRoot = projectRoot, globalRoot = globalRoot)

    private fun proposal(name: String = "平台签到流程", body: String = "1. 打开页面；2. 点签到。") =
        SkillProposal(scope = SkillScope.PROJECT, name = name, body = body, reason = "夹具", draft = true)

    private fun evoCandidate(p: SkillProposal) = EvoCandidate(
        domain = cap.wire,
        target = "skill:project/" + p.name,
        body = p.body,
    )

    /** 生产同款顺序：门 → （拒则记账）→ 免批写 → 观察账（SkillTools 免批写支路；源级门禁钉缝）。 */
    private fun gateThenWrite(p: SkillProposal): Pair<LowRiskVerdict, SkillAutoOutcome?> {
        val c = evoCandidate(p)
        val v = LowRiskEvo.gate(cap, c)
        when (v) {
            is LowRiskVerdict.Refuse -> {
                // 生产（SkillTools）在拒的这一支落记账行；夹具逐字复刻这一步
                LowRiskEvo.recordRefusal(cap, v.redLine, v.note, c, now)
                return v to null
            }
            is LowRiskVerdict.Hold -> return v to null
            is LowRiskVerdict.Allow -> Unit
        }
        val a = SkillAutoWrite.tryAutoWrite(p, projectRoot, globalRoot, pf(), "20261110", nowMs = now)
        LowRiskEvo.observeRun(
            cap,
            ok = a is SkillAutoOutcome.Written,
            atMs = now + 1,
            askedHuman = a is SkillAutoOutcome.NeedsConfirm,
        )
        return v to a
    }

    // ── 判据①：候选→灰度→转正→回退，走完一次（夹具） ────────────────────────

    @Test
    fun `夹具①_候选免批落草稿_用满3次转正_一键回退回草稿`() {
        // 候选进灰度：门放行（缺省灰度档）＋免批落草稿
        val (v, a) = gateThenWrite(proposal())
        assertTrue("缺省灰度档放行：$v", v is LowRiskVerdict.Allow)
        val written = a as SkillAutoOutcome.Written
        assertTrue("免批恒为草稿（灰度中）：${written.file}", SkillStore.statusOf(written.file.readText(Charsets.UTF_8)) == "draft")

        // 观察账真的落了（473 §二②：指标的唯一来源）
        val obs = rows().filter { it.evidence.startsWith("${ConsequenceEvidence.CANARY_RUN}${cap.wire}:") }
        assertEquals("一次免批＝一条观察", 1, obs.size)
        assertEquals("ok", obs.single().after)

        // 灰度期用满 3 次 ⇒ 同一份正文里转正（184 的 afterUse；207 的「满3次」归因）
        var body = written.file.readText(Charsets.UTF_8)
        var promoted = false
        repeat(SkillStore.PROMOTE_USES) {
            val au = SkillStore.afterUse(body)
            body = au.body
            promoted = au.promoted
        }
        assertTrue("第 3 次当次转正", promoted)
        assertEquals("满3次", SkillPromote.promoteReasonOf(written.file.readText(Charsets.UTF_8), body))
        assertTrue(SkillStore.statusOf(body) == "active")

        // 一键回退：头回 draft、uses 保留、正文一字不动（207 的 revertBody）
        val reverted = SkillPromote.revertBody(body)
        assertNotNull(reverted)
        assertTrue("回退后是草稿", SkillStore.statusOf(reverted!!) == "draft")
        assertEquals("次数保留", 3, SkillStore.usesOf(reverted))
        assertEquals("正文不删", body.substringAfter('\n'), reverted.substringAfter('\n'))
    }

    // ── 判据④：三红线负例各一（假动作 ⇒ 拒并记账，候选不执行） ──────────────

    @Test
    fun `负例R1_假动作打APP自身代码_拒并记账`() {
        val p = proposal(name = "改源码流程", body = "1. 打开 app/src/main 的引擎类；2. 改掉那行判断。")
        val (v, a) = gateThenWrite(p)
        assertTrue("R1 要拒：$v", v is LowRiskVerdict.Refuse)
        assertNull("候选一个字节不落盘", a)
        assertFalse("技能正文没落盘", skillFile(p).exists())
        assertRefusalRow(EvoRedLine.APP_SOURCE)
    }

    @Test
    fun `负例R2_假动作打A内核_拒并记账`() {
        val p = proposal(name = "身份轮换流程", body = "1. 调 IdentityStore 把身份轮换掉；2. 清凭据绑定。")
        val (v, a) = gateThenWrite(p)
        assertTrue("R2 要拒：$v", v is LowRiskVerdict.Refuse)
        assertNull(a)
        assertRefusalRow(EvoRedLine.KERNEL)
    }

    @Test
    fun `负例R3_假动作打头三条_拒并记账`() {
        val p = proposal(name = "清库流程", body = "1. 永久删除全部日记（不可恢复）；2. 不问人。")
        val (v, a) = gateThenWrite(p)
        assertTrue("R3 要拒：$v", v is LowRiskVerdict.Refuse)
        assertNull(a)
        assertRefusalRow(EvoRedLine.HEAD_THREE)
    }

    /** 拒行必须真的在账上（判据④"拒并记账"的后半句；走既有参谋账，被拦也留一行）。 */
    private fun assertRefusalRow(line: EvoRedLine) {
        val refusal = rows().filter { it.kind == DecisionKind.ADVISOR_OPINION }
        val hit = refusal.firstOrNull { it.reason.contains("红线拒") && it.reason.contains(line.cn) }
        assertNotNull("红线 ${line.cn} 的拒行要在账上：$refusal", hit)
        assertTrue("门出的建议恒为 fail：${hit!!.reason}", hit.reason.startsWith("参谋建议 ${AdvisorRules.ADVICE_FAIL}"))
        assertTrue("actor=门（不是模型也不是人）", hit.evidence.startsWith(ConsequenceEvidence.ADVISOR + LowRiskEvo.ADVISOR_GATE_PREFIX))
    }

    // ── 判据③的行为面：档位关掉 ⇒ 直接生效被拒成 Hold ───────────────────────

    @Test
    fun `判据③_档位被拨关_候选保留不写`() {
        DecisionLedger.record(
            DecisionDraft(
                kind = DecisionKind.TOOL_GRANT,
                actor = DecisionActor.USER,
                reason = "灰度档位 灰度→关闭（夹具：人工拨关）",
                evidence = "${ConsequenceEvidence.CANARY}${cap.wire}:off:human",
                before = "canary",
                after = "off",
                atMs = now,
                rollbackRef = "${ConsequenceEvidence.CANARY}${cap.wire}:canary",
                reversible = true,
            ),
        )
        val (v, a) = gateThenWrite(proposal())
        assertTrue("关闭档只出 Hold：$v", v is LowRiskVerdict.Hold)
        assertNull("一个字节不写", a)
    }

    /** 项目技能落点（与 SkillStore.plan 同一目录口径；负例断言"一个字节不落盘"用）。 */
    private fun skillFile(p: SkillProposal): File =
        File(File(SkillRules.projectSkillDir(projectRoot), p.name), SkillRules.SKILL_FILE)

    // ── 介入率有数：要问人的那次＝askedHuman 观察行（转全开的"省心"判据） ────

    @Test
    fun `观察账_要问人的一次带asked标`() {
        // 夹具：让免批判据要问人（正文带权限词 ⇒ 不满足免批 ⇒ NeedsConfirm）
        val p = proposal(name = "要确认的流程", body = "1. 需要 root 权限的操作；2. 再做一步。")
        val (v, a) = gateThenWrite(p)
        assertTrue(v is LowRiskVerdict.Allow)
        assertTrue(a is SkillAutoOutcome.NeedsConfirm)
        val obs = rows().filter { it.evidence.startsWith("${ConsequenceEvidence.CANARY_RUN}${cap.wire}:") }
        assertEquals(1, obs.size)
        assertTrue("介入要进账：${obs.single().evidence}", obs.single().evidence.contains(":ask"))
        assertFalse(obs.single().after == "ok")
    }
}
