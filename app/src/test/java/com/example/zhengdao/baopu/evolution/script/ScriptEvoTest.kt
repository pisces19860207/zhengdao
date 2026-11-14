// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百四十三批 · 脚本进灰度的**行为验收**（派工单 §二 的判据①–⑤ 逐条落成用例）。
//
// 走的全是既有机制：脚本落库＝255 的 `saveConfirmed`、验收样本＝255 的 `recordRun`、
// 灰度＝568-A 的 `LowRiskEvo.gate`／观察账、转正回滚＝207/208 的 `SkillPromote`＋决策账。
// 本批新增的只有"脚本够不够格"的纯判据与三处装配缝（源级钉缝在 ScriptEvoGateTest）。
package com.example.zhengdao.baopu.evolution.script

import com.example.zhengdao.baopu.DecisionKind
import com.example.zhengdao.baopu.DecisionLedger
import com.example.zhengdao.baopu.PersonaFiles
import com.example.zhengdao.baopu.SkillPromote
import com.example.zhengdao.baopu.SkillRules
import com.example.zhengdao.baopu.SkillStore
import com.example.zhengdao.baopu.TeachAction
import com.example.zhengdao.baopu.TeachElementSnapshot
import com.example.zhengdao.baopu.TeachExpect
import com.example.zhengdao.baopu.TeachExpectKind
import com.example.zhengdao.baopu.TeachFingerprint
import com.example.zhengdao.baopu.TeachFingerprintAnchor
import com.example.zhengdao.baopu.TeachLearner
import com.example.zhengdao.baopu.TeachLocator
import com.example.zhengdao.baopu.TeachLocatorKind
import com.example.zhengdao.baopu.TeachReplayStore
import com.example.zhengdao.baopu.TeachRunReport
import com.example.zhengdao.baopu.TeachSkill
import com.example.zhengdao.baopu.TeachSkillDraft
import com.example.zhengdao.baopu.TeachStep
import com.example.zhengdao.baopu.consequence.ConsequenceWiring
import com.example.zhengdao.testkit.TempSandbox
import java.io.File
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ScriptEvoTest {

    private val now = 1_760_000_000_000L
    private val entropy = "3f2504e0-4f89-11d3-9a0c-0305e82c3301"

    private lateinit var sandbox: File
    private lateinit var memory: File
    private lateinit var projectRoot: File
    private lateinit var globalRoot: File

    @Before
    fun setUp() {
        sandbox = TempSandbox.create("zd643-")
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

    // ── 夹具 ────────────────────────────────────────────────────────────────

    private fun pf() = PersonaFiles(projectRoot = projectRoot, globalRoot = globalRoot)

    private fun teachSkill(name: String, clickText: String = "搜索") = TeachSkill(
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
                    TeachLocator(TeachLocatorKind.ROLE_NAME, "button|$clickText"),
                    TeachLocator(TeachLocatorKind.TESTID, "go"),
                ),
                value = null,
                snapshot = TeachElementSnapshot("button", "button", clickText, clickText, null),
                expect = listOf(TeachExpect(TeachExpectKind.TEXT_APPEARS, "结果")),
            ),
        ),
    )

    /** 盘上一条脚本：走 643 之前的既有写口（入库闸 G1 单站点 ⇒ 草稿）。 */
    private fun putScript(skill: TeachSkill) {
        val saved = TeachReplayStore.saveConfirmed(
            draft = TeachSkillDraft(skill, emptyList(), TeachLearner.summarize(skill, emptyList())),
            projectRoot = projectRoot,
            globalRoot = globalRoot,
            personaFiles = pf(),
            stampText = "s",
        )
        assertNotNull("前置：脚本要能落库（${saved.reason}）", saved.written)
    }

    /** 手改/构造出来的脚本（绕过入库闸）：直接摆 `SKILL.md`＋`回放.json`——③ 的负例靠它。 */
    private fun putRawScript(skill: TeachSkill) {
        val dir = File(SkillRules.projectSkillDir(projectRoot), skill.name).apply { mkdirs() }
        File(dir, SkillRules.SKILL_FILE).writeText(SkillStore.draftHeader("draft", 0) + "\n# ${skill.name}\n", Charsets.UTF_8)
        File(dir, TeachReplayStore.REPLAY_FILE).writeText(skill.toJsonText(), Charsets.UTF_8)
    }

    private fun report(name: String, ok: Boolean) = TeachRunReport(
        skillName = name,
        ok = ok,
        stepsDone = if (ok) 1 else 0,
        failedStep = if (ok) null else 1,
        stopReason = if (ok) null else "第 1 步期望断言不满足",
        durationMs = 10L,
        drifts = emptyList(),
        refusals = emptyList(),
        ask = null,
        evidenceDir = null,
    )

    /** 生产同款顺序（`TeachReplay.recordAcceptance`）：先落验收账，再走"候选/回滚"那一步。 */
    private fun replay(name: String, skill: TeachSkill, ok: Boolean, atMs: Long): String? {
        val rep = report(name, ok)
        TeachReplayStore.recordRun(projectRoot, globalRoot, pf(), skill, rep, atMs)
        return ScriptEvo.onReplaySettled(projectRoot, globalRoot, pf(), skill, rep, atMs)
    }

    private fun bodyOf(name: String): String =
        File(File(SkillRules.projectSkillDir(projectRoot), name), SkillRules.SKILL_FILE).readText(Charsets.UTF_8)

    private fun statusOf(name: String): String = SkillStore.statusOf(bodyOf(name))

    /** `技能/_变更.jsonl` 的行（候选行与转正/回退行都在这本既有账里）。 */
    private fun changeRows(): List<JSONObject> {
        val f = File(SkillRules.projectSkillDir(projectRoot), "_变更.jsonl")
        if (!f.isFile) return emptyList()
        return f.readLines(Charsets.UTF_8).filter { it.isNotBlank() }.mapNotNull {
            runCatching { JSONObject(it) }.getOrNull()
        }
    }

    private fun candidateRows() = changeRows().filter { it.optString("action") == ScriptEvo.ACTION_CANDIDATE }

    // ── ① 同版本成功回放 3 次 ⇒ 生成 1 条候选（带 id／版本／成功率／步数／校验点）────

    @Test
    fun `判据1_同版本成功三次_生成一条候选_五个字段齐`() {
        val skill = teachSkill("查货-flow")
        putScript(skill)
        repeat(3) { i -> replay("查货-flow", skill, ok = true, atMs = now + i * 1_000L) }

        val rows = candidateRows()
        assertEquals("三次成功只许生成一条候选", 1, rows.size)
        val verdict = ScriptEvo.candidacyOf(projectRoot, "查货-flow")
        assertTrue("够格 ⇒ Ready（实际 ${verdict?.note}）", verdict is ScriptCandidacy.Ready)
        val c = (verdict as ScriptCandidacy.Ready).candidate
        assertEquals("脚本 id＝项目技能名", "查货-flow", c.scriptId)
        assertEquals("版本＝脚本规范序列化 sha256 前 12", ScriptEvoRules.versionOf(skill), c.version)
        assertEquals(12, c.version.length)
        assertEquals(3, c.okReplays)
        assertEquals(100, c.successRatePct)
        assertEquals("步数", 1, c.steps)
        assertEquals("校验点数（每步 expect＋终态 expect）", 1, c.checkpoints)
        assertEquals("候选 id 进账（candidateId 那一格）", c.id, rows[0].optString("candidateId"))
        assertTrue("账行里要能看出成功次数：${rows[0].optString("reason")}", rows[0].optString("reason").contains("3/3"))
    }

    @Test
    fun `判据1补_第四次成功不重复落候选行_同版本幂等`() {
        val skill = teachSkill("查货-flow")
        putScript(skill)
        repeat(4) { i -> replay("查货-flow", skill, ok = true, atMs = now + i * 1_000L) }
        assertEquals("同版本只落一行候选账", 1, candidateRows().size)
    }

    // ── ② 只成功 1 次 ⇒ 不生成 ───────────────────────────────────────────────

    @Test
    fun `判据2_只成功一次_不生成候选`() {
        val skill = teachSkill("查货-flow")
        putScript(skill)
        val note = replay("查货-flow", skill, ok = true, atMs = now + 1_000L)
        assertEquals("不够格时不许打扰人", null, note)
        assertEquals("一次成功不许生成候选", 0, candidateRows().size)
        val verdict = ScriptEvo.candidacyOf(projectRoot, "查货-flow")
        assertTrue("应当是 NotYet（实际 ${verdict?.note}）", verdict is ScriptCandidacy.NotYet)
        assertTrue((verdict as ScriptCandidacy.NotYet).note.contains("1/3"))
    }

    // ── ③ 含不可逆动作 ⇒ 不生成且报"脚本不允许转正" ─────────────────────────

    @Test
    fun `判据3_脚本含不可逆动作_不生成候选且报脚本不允许转正`() {
        val bad = teachSkill("删单-flow", clickText = "删除")
        putRawScript(bad)
        repeat(3) { i -> replay("删单-flow", bad, ok = true, atMs = now + i * 1_000L) }

        assertEquals("不可逆脚本再稳也不许生成候选", 0, candidateRows().size)
        val verdict = ScriptEvo.candidacyOf(projectRoot, "删单-flow")
        assertTrue("应当被判 Refused（实际 ${verdict?.note}）", verdict is ScriptCandidacy.Refused)
        assertTrue((verdict as ScriptCandidacy.Refused).note.contains(ScriptEvoRules.NOT_PROMOTABLE))

        val promoted = ScriptEvo.promote(projectRoot, globalRoot, pf(), "删单-flow", now + 10_000L)
        assertFalse("不可逆脚本不许转正", promoted.ok)
        assertTrue("要报「脚本不允许转正」：${promoted.message}", promoted.message.contains(ScriptEvoRules.NOT_PROMOTABLE))
        assertEquals("状态一个字节没动（还是草稿）", "draft", statusOf("删单-flow"))
        assertEquals("rejected 之后不许落转正账", 0, changeRows().count { it.optString("action") == "promote" })
    }

    // ── ④ 转正/回滚各一次，账上有行（复用 207/208 的既有账）──────────────────

    @Test
    fun `判据4_转正一次回滚一次_两本既有账上都有行`() {
        val skill = teachSkill("查货-flow")
        putScript(skill)
        repeat(3) { i -> replay("查货-flow", skill, ok = true, atMs = now + i * 1_000L) }

        val p = ScriptEvo.promote(projectRoot, globalRoot, pf(), "查货-flow", now + 10_000L)
        assertTrue("够格的脚本要转得成：${p.message}", p.ok)
        assertEquals("正文头换成 active", "active", statusOf("查货-flow"))
        assertEquals("_变更.jsonl 里一条 promote", 1, changeRows().count { it.optString("action") == "promote" })
        assertTrue(
            "决策账里一条 skill_promote（208 的既有 kind）",
            DecisionLedger.read(memory).any { it.kind == DecisionKind.SKILL_PROMOTE },
        )

        val r = ScriptEvo.rollback(projectRoot, globalRoot, pf(), "查货-flow", "回放失败（页面结构变了）", now + 20_000L)
        assertTrue("转正之后要回得去：${r.message}", r.ok)
        assertEquals("回滚后回到草稿", "draft", statusOf("查货-flow"))
        assertEquals("_变更.jsonl 里一条 promote_undo", 1, changeRows().count { it.optString("action") == SkillPromote.ACTION_REVERT })
        assertTrue(
            "决策账里一条 ROLLBACK_OF（回滚本身也留痕）",
            DecisionLedger.read(memory).any { it.kind == DecisionKind.ROLLBACK_OF },
        )
    }

    @Test
    fun `判据4补_已转正的脚本跑砸一次_生产链当场回滚并留行`() {
        val skill = teachSkill("查货-flow")
        putScript(skill)
        repeat(3) { i -> replay("查货-flow", skill, ok = true, atMs = now + i * 1_000L) }
        assertTrue(ScriptEvo.promote(projectRoot, globalRoot, pf(), "查货-flow", now + 10_000L).ok)

        val note = replay("查货-flow", skill, ok = false, atMs = now + 20_000L)
        assertNotNull("回滚要有话说（不许静默）", note)
        assertTrue("回滚那句话说清楚是灰度回滚：$note", note!!.contains("灰度回滚"))
        assertEquals("一次失败就回草稿（脚本要比文字技能更严）", "draft", statusOf("查货-flow"))
        assertTrue(changeRows().any { it.optString("action") == SkillPromote.ACTION_REVERT })
    }

    // ── ⑤ 一键收回：回到未转正，脚本仍在 ─────────────────────────────────────

    @Test
    fun `判据5_一键收回回到未转正_回放脚本一个字节没动`() {
        val skill = teachSkill("查货-flow")
        putScript(skill)
        repeat(3) { i -> replay("查货-flow", skill, ok = true, atMs = now + i * 1_000L) }
        assertTrue(ScriptEvo.promote(projectRoot, globalRoot, pf(), "查货-flow", now + 10_000L).ok)

        val sidecar = TeachReplayStore.sidecarFile(projectRoot, "查货-flow")
        val before = sidecar.readText(Charsets.UTF_8)
        val r = ScriptEvo.revoke(projectRoot, globalRoot, pf(), "查货-flow", now + 20_000L)
        assertTrue("收回要成：${r.message}", r.ok)
        assertEquals("回到未转正（草稿）", "draft", statusOf("查货-flow"))
        assertTrue("脚本文件还在", sidecar.isFile)
        assertEquals("脚本一个字节没动", before, sidecar.readText(Charsets.UTF_8))
        assertTrue("收回也留一行（promote_undo）", changeRows().any { it.optString("action") == SkillPromote.ACTION_REVERT })
    }

    // ── 版本身份：改了脚本就是另一版，老成绩不冒充新成绩 ────────────────────

    @Test
    fun `版本负例_改了步骤之后_同一批成功样本不算新版本的成绩`() {
        val v1 = teachSkill("查货-flow")
        putScript(v1)
        repeat(3) { i -> replay("查货-flow", v1, ok = true, atMs = now + i * 1_000L) }
        assertTrue(ScriptEvo.candidacyOf(projectRoot, "查货-flow") is ScriptCandidacy.Ready)

        // 重存一版（步骤文本变了 ⇒ 规范化哈希变）：样本按 255 的口径清空，但即便如此，
        // 判据也只认"版本对得上"的样本——这里直接把新脚本摆在盘上、不重教。
        val v2 = v1.copy(purpose = "查货（改过一版）")
        assertNotEquals(ScriptEvoRules.versionOf(v1), ScriptEvoRules.versionOf(v2))
        putRawScript(v2)
        val verdict = ScriptEvo.candidacyOf(projectRoot, "查货-flow")
        assertTrue("新版本没有自己的成功样本 ⇒ 不许沿用老成绩（实际 ${verdict?.note}）", verdict is ScriptCandidacy.NotYet)
        assertTrue((verdict as ScriptCandidacy.NotYet).note.contains("0/3"))
    }
}
