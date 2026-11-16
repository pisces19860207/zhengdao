// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百二十四批 C（阶段三前段）· 判据④：改脚本必须先确认（CONFIRM 工具闸，源码半边在
// GateTest）、改动留痕（修订/<旧版>/＋SkillAudit script_corrected）、**回滚后脚本回到
// 上一版且记录 rollback**。走真 saveConfirmed 整条链（房型照 TeachReplayStoreTest）。
package com.example.zhengdao.baopu

import com.example.zhengdao.baopu.correct.CorrectEdit
import com.example.zhengdao.baopu.correct.TeachCorrect
import com.example.zhengdao.baopu.correct.TeachScriptHistory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TeachScriptHistoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val projectRoot: File by lazy { tmp.newFolder("ws") }
    private val globalRoot: File by lazy { tmp.newFolder("app") }

    private fun files(): PersonaFiles = PersonaFiles(projectRoot = projectRoot, globalRoot = globalRoot)

    /** 真学习器出技能（判据链完整）。 */
    private fun seed(): TeachSkill {
        val draft = TeachLearner.buildDraft(
            TeachLearnerTestAccess.fiveEvents(), "查货", "查当日报文",
            TeachPageFacts("https://search.example.com/start", listOf("关键词"), listOf("搜索", "提交")),
            nowMs = 1_728_000_000_000L,
        )
        val r = TeachReplayStore.saveConfirmed(draft, projectRoot, globalRoot, files(), "seed")
        assertNotNull(r.reason, r.written)
        return draft.skill
    }

    private fun auditText(): String {
        val ledger = File(projectRoot, "技能/_变更.jsonl")
        return if (ledger.isFile) ledger.readText(Charsets.UTF_8) else ""
    }

    private fun typeStepIndex(skill: TeachSkill): Int =
        skill.steps.first { it.action == TeachAction.TYPE }.index

    // ── 判据④前半：纠正要先确认（CONFIRM 闸在工具层）＋改完留痕（备份＋账行） ───────

    @Test
    fun `判据4_纠正落盘_上一版备份到修订_留痕script_corrected`() {
        val original = seed()
        val oldVer = TeachReplayStore.sha256Of(original).take(12)
        // 编辑：给 TYPE 步换一个输入值（「单号要填新运单」那句纠正的落形）
        val typeIdx = typeStepIndex(original)
        val outcome = TeachCorrect.apply(original, CorrectEdit(name = "查货", step = typeIdx, op = "setValue", value = "WB999", reason = "单号要填新运单 WB999"))
        assertTrue(outcome is TeachCorrect.ApplyOutcome.Applied)
        val edited = (outcome as TeachCorrect.ApplyOutcome.Applied).skill

        val msg = TeachScriptHistory.applyCorrection(
            projectRoot = projectRoot, globalRoot = globalRoot, personaFiles = files(),
            name = "查货", edited = edited, reason = "单号要填新运单 WB999", nowMs = 5_000L,
        )
        assertTrue(msg, msg.contains("已确认改脚本"))
        // 修订/<旧版>/ 里是上一版的完整机器态
        val revReplay = File(projectRoot, "技能/查货/修订/$oldVer/回放.json")
        assertTrue("上一版回放.json 已备份", revReplay.isFile)
        assertEquals("备份＝纠正前的原字节", original.toJsonText(), revReplay.readText(Charsets.UTF_8))
        // 现行回放.json＝新版本（内容变了）
        val nowSkill = TeachReplayStore.load(projectRoot, "查货")!!
        val newVer = TeachReplayStore.sha256Of(nowSkill).take(12)
        assertTrue("版本真的变了", newVer != oldVer)
        assertEquals("被纠正的那一步值已改", "WB999", nowSkill.steps.first { it.index == typeIdx }.value)
        // 留痕：script_corrected 一行，beforeSha=旧版
        val audit = auditText()
        assertTrue(audit.contains(TeachScriptHistory.ACTION_CORRECTED))
        assertTrue(audit.contains(oldVer))
        assertTrue(audit.contains("单号要填新运单 WB999"))
    }

    @Test
    fun `纠正被拒_凭证扫描照拒_上一版原样保留`() {
        seed()
        // 构造一个带明文凭证的编辑：setValue 塞 key=（saveConfirmed 的凭证闸会拦）
        val original = TeachReplayStore.load(projectRoot, "查货")!!
        val typeIdx = typeStepIndex(original)
        val outcome = TeachCorrect.apply(original, CorrectEdit(name = "查货", step = typeIdx, op = "setValue", value = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.SflKxwRJSMeKKF2QT4fwpMeJf36POkLyzf", reason = "改值"))
        val edited = (outcome as TeachCorrect.ApplyOutcome.Applied).skill
        val msg = TeachScriptHistory.applyCorrection(
            projectRoot = projectRoot, globalRoot = globalRoot, personaFiles = files(),
            name = "查货", edited = edited, reason = "改值", nowMs = 5_000L,
        )
        assertTrue(msg.contains("没有落盘"))
        assertEquals("上一版一个字节没动", original.toJsonText(), TeachReplayStore.load(projectRoot, "查货")!!.toJsonText())
    }

    // ── 判据④后半：回滚后脚本回到上一版且记录 rollback ──────────────────────────

    @Test
    fun `判据4_回滚_脚本回到上一版_验收样本随版恢复_记录rollback`() {
        seed()
        // 先落一份验收样本（回滚要连它一起恢复——643 的「同版本成功」样本不丢）
        val beforeEdit = TeachReplayStore.load(projectRoot, "查货")!!
        val oldVer = TeachReplayStore.sha256Of(beforeEdit).take(12)
        val samples = listOf(
            AcceptanceSample(opId = "op1", atMs = 1L, ok = true, fingerprintOk = true, driftCount = 0, site = "search.example.com", version = oldVer),
        )
        TeachReplayStore.saveAcceptance(projectRoot, "查货", AcceptanceState(samples = samples))

        val typeIdx = typeStepIndex(beforeEdit)
        val edited = (TeachCorrect.apply(beforeEdit, CorrectEdit(name = "查货", step = typeIdx, op = "setValue", value = "WB999", reason = "改值")) as TeachCorrect.ApplyOutcome.Applied).skill
        TeachScriptHistory.applyCorrection(projectRoot, globalRoot, files(), "查货", edited, "改值", 5_000L)
        // 纠正后：验收被 saveConfirmed 重置（onRelearned）——样本清了
        assertEquals(0, TeachReplayStore.loadAcceptance(projectRoot, "查货")!!.samples.size)

        // 回滚：脚本回到上一版＋样本随版恢复＋留痕 script_rollback
        val msg = TeachScriptHistory.rollbackToPrevious(projectRoot, "查货", 9_000L)
        assertTrue(msg, msg.contains("已回滚"))
        val restored = TeachReplayStore.load(projectRoot, "查货")!!
        assertEquals("脚本回到上一版（逐字节）", beforeEdit.toJsonText(), restored.toJsonText())
        assertTrue("验收.json 随版恢复（旧版样本不丢）", TeachReplayStore.loadAcceptance(projectRoot, "查货")!!.samples.any { it.version == oldVer })
        val audit = auditText()
        assertTrue(audit.contains(TeachScriptHistory.ACTION_ROLLBACK))
        assertTrue(audit.contains("回滚到上一版"))
    }

    @Test
    fun `没有修订_回滚如实说_不猜`() {
        seed()
        val msg = TeachScriptHistory.rollbackToPrevious(projectRoot, "查货", 9_000L)
        assertTrue(msg.contains("没有可回滚的修订"))
        // 技能本身原样
        assertEquals(seed().toJsonText(), TeachReplayStore.load(projectRoot, "查货")!!.toJsonText())
    }

    // ── 编辑闭集与拒收 ────────────────────────────────────────────────────────────

    @Test
    fun `op不在闭集_拒`() {
        val skill = seed()
        val r = TeachCorrect.apply(skill, CorrectEdit(name = "查货", step = 1, op = "freeJs", value = "alert(1)", reason = "r"))
        assertTrue(r is TeachCorrect.ApplyOutcome.Refused)
    }

    @Test
    fun `步号越界_拒_不猜`() {
        val skill = seed()
        val r = TeachCorrect.apply(skill, CorrectEdit(name = "查货", step = 99, op = "deleteStep", value = null, reason = "r"))
        assertTrue(r is TeachCorrect.ApplyOutcome.Refused)
        assertEquals("原技能不动", skill.toJsonText(), TeachReplayStore.load(projectRoot, "查货")!!.toJsonText())
    }

    @Test
    fun `判据3前句_moveStep改顺序后步号重排连续`() {
        val skill = seed()
        val r = TeachCorrect.apply(skill, CorrectEdit(name = "查货", step = 2, op = "moveStep", toIndex = 4, reason = "先导出再筛选"))
        val moved = (r as TeachCorrect.ApplyOutcome.Applied).skill
        assertEquals((1..moved.steps.size).toList(), moved.steps.map { it.index })
        // 顺序真的变了：第 2 位上现在是原来的第 3 步（step2 被挪到第 4 位）
        assertEquals(skill.steps[2].action, moved.steps[1].action)
    }

    @Test
    fun `判据2结构半边_setValue从不启录制器`() {
        // 编辑路径根本不 import TeachRecorder——这里钉行为面：apply 后盘上只有技能目录的变化
        seed()
        val before = File(projectRoot, "技能").walkTopDown().filter { it.isFile }.map { it.path to it.length() }.toList()
        val skill = TeachReplayStore.load(projectRoot, "查货")!!
        val typeIdx = typeStepIndex(skill)
        val edited = (TeachCorrect.apply(skill, CorrectEdit(name = "查货", step = typeIdx, op = "setValue", value = "WB999", reason = "改值")) as TeachCorrect.ApplyOutcome.Applied).skill
        TeachScriptHistory.applyCorrection(projectRoot, globalRoot, files(), "查货", edited, "改值", 5_000L)
        val after = File(projectRoot, "技能").walkTopDown().filter { it.isFile }.map { it.path to it.length() }.toList()
        // 变化只有：修订备份＋回放.json＋验收.json＋SKILL.md＋_变更.jsonl——没有录制类新文件
        val newFiles = after.map { it.first }.toSet() - before.map { it.first }.toSet()
        assertTrue(newFiles.none { it.contains("录制") || it.contains("record") || it.endsWith(".wav") })
    }
}
