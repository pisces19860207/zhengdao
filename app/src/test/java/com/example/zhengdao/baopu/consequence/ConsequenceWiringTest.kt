// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百零六批（2026-11-07）· 装配缝上的判据——**只有碰盘才算数**的那几条。
//
// 前四份测试（identity／canary／compensation／depth）测的是纯判据：手搓行进去、断言出来。
// 本文件测的是**另一件事**：判据③④ 的字面要求里有「持久化」「从账本算」「记账」这些词，
// 这些词在纯函数层根本测不到——只在盘上才分得出真做到与做到一半。所以本文件的每一条都：
//   先走 [ConsequenceWiring]（真落盘）⇒ 再 [DecisionLedger.read]（真读回）⇒ 才断言。
//
// 覆盖的判据（派工单 §五 与「追加」节）：
//   ③ 指标从账本算／**达标自动转全开**（不再要人逐个点）／未达标不许转
//   ④ 首启生成并持久化／每条账行带身份／撤销即凭据全失效／撤销后历史账照读
//   追加① 深度落账带「谁定的／依据哪几项／当时读数」
//   追加② 新装首跑＝浅（盘上没有任何深度行时读出来就是浅）
//   追加④ 造一条坏消息立刻降一档
//   追加⑤ 模型给自己发票 ⇒ 拦下**并且留一行账**
// 负例逐条点名：
//   W1 身份表读不动 ⇒ 放行凭据（半份表当有效）
//   W2 模型改身份 ⇒ 被拒却留了账／改了盘
//   W3 机制没装配 ⇒ 动作报成成功
//   W4 档位状态另存一处（SharedPreferences）：被回滚的档位行仍算数
//   W5 赔付四件缺一，盘上却落了一行
package com.example.zhengdao.baopu.consequence

import com.example.zhengdao.baopu.DecisionActor
import com.example.zhengdao.baopu.DecisionDraft
import com.example.zhengdao.baopu.DecisionKind
import com.example.zhengdao.baopu.DecisionLedger
import com.example.zhengdao.baopu.DecisionRecord
import com.example.zhengdao.baopu.DecisionRollbackHandler
import com.example.zhengdao.baopu.DecisionRollbacks
import com.example.zhengdao.baopu.verify.ChancellorVerdict
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class ConsequenceWiringTest {

    private val now = 1_760_000_000_000L
    private val entropyA = "3f2504e0-4f89-11d3-9a0c-0305e82c3301"
    private val entropyB = "11111111-2222-3333-4444-555555555555"
    private val idA = "zdx-$entropyA"
    private val idB = "zdx-$entropyB"

    /** 灰度登记表里缺省＝灰度的那一档（判据③「达标自动转全开」拿它跑最直白）。 */
    private val cap: String = CanaryCapability.REFLECTION_AUTO_EDIT.wire

    private lateinit var files: File
    private lateinit var memory: File

    @Before
    fun setUp() {
        files = File.createTempFile("zd506-wire", "").let { it.delete(); it.mkdirs(); it }
        memory = File(files, "抱朴记忆")
        DecisionLedger.install(memory)
        ConsequenceWiring.install(files, now) { entropyA }
    }

    @After
    fun tearDown() {
        ConsequenceWiring.uninstallForTest()
        DecisionLedger.uninstallForTest()
        DecisionRollbacks.handler = null
        files.deleteRecursively()
    }

    private fun rows(): List<DecisionRecord> = DecisionLedger.read(memory)

    /** 模拟一次重启：身份机制重装（拿**别的**熵），账本落点不动（同一块盘）。 */
    private fun restart(entropy: String = entropyB) {
        ConsequenceWiring.uninstallForTest()
        ConsequenceWiring.install(files, now + 600_000L) { entropy }
    }

    private fun bandRows(): List<DecisionRecord> =
        rows().filter { it.evidence.startsWith("${ConsequenceEvidence.CANARY}$cap") }

    private fun latestBand(): DecisionRecord? = bandRows().maxByOrNull { it.atMs }

    /** 把 [cap] 从灰度跑到全开（10 条干净观察＋连续两轮达标）。 */
    private fun promoteToFull(from: Long = now) {
        repeat(CanaryLadder.MIN_RUNS) { i ->
            ConsequenceWiring.observe(CanaryObservation(cap, ok = true), from + i)
        }
        assertEquals(
            "一轮达标就转全开＝拿运气当依据",
            CanaryBand.CANARY,
            ConsequenceWiring.decideCanaryBand(cap, from + 100, passedRounds = 1),
        )
        assertEquals(
            "两轮达标还不转＝判据③那句「不再要人逐个点」没落地",
            CanaryBand.FULL,
            ConsequenceWiring.decideCanaryBand(cap, from + 101, passedRounds = CanaryLadder.PASS_ROUNDS_FOR_FULL),
        )
    }

    // ── 判据④·首启生成并持久化 ──────────────────────────────────────────────

    @Test
    fun `判据4_首启铸身份_落一行生成账_且那一行自己带身份`() {
        assertEquals(idA, ConsequenceWiring.executorId())
        assertTrue("身份必须落盘，不然重启就没了", ConsequenceDirs.identityFile(files).isFile)
        val issue = rows().first { it.kind == DecisionKind.IDENTITY_STATE }
        assertEquals("${ConsequenceEvidence.IDENTITY}issue:$idA", issue.evidence)
        assertEquals(DecisionActor.AUTO, issue.actor)
        assertEquals("", issue.before)
        assertEquals(idA, issue.after)
        // 盖章口在落账**之前**装好：否则「首启生成」这一行恰好是唯一一条没身份的账。
        assertEquals(idA, issue.executor)
        assertFalse("身份行不许留通用撤销通道（那是第二条解除撤销的路）", issue.reversible)
    }

    @Test
    fun `判据4b_重启读回同一个id_不再铸第二条`() {
        restart(entropyB)
        assertEquals("熵换了也必须读回盘上那一个 id", idA, ConsequenceWiring.executorId())
        assertEquals(
            "「首启生成」不是「每次启动生成」",
            1,
            rows().count { it.evidence.startsWith("${ConsequenceEvidence.IDENTITY}issue") },
        )
    }

    @Test
    fun `判据4c_每条账行都带身份_不靠调用方自觉`() {
        // 这一条走的是**与后果治理无关**的 kind：盖章在 build() 中央，所以别的域的账行也一样带身份。
        val rec = DecisionLedger.record(
            DecisionDraft(
                kind = DecisionKind.SKILL_PROMOTE,
                actor = DecisionActor.AUTO,
                reason = "转正：满3次",
                evidence = "skill:project/506盖章",
                before = "",
                after = "promoted",
                atMs = now + 1,
                rollbackRef = "/ws/技能/506盖章/SKILL.md",
            ),
        )
        assertNotNull(rec)
        assertEquals(idA, rec!!.executor)
        assertEquals(idA, rows().first { it.id == rec.id }.executor)
    }

    @Test
    fun `判据4d_撤销后新行不再盖章_老行的章一行未改`() {
        ConsequenceWiring.observe(CanaryObservation(cap, ok = true), now + 1)
        val oldId = rows().first { it.evidence.startsWith(ConsequenceEvidence.CANARY_RUN) }.id
        val note = ConsequenceWiring.mutateIdentity(IdentityMutation(IdentityRules.ACTOR_USER, IdentityRules.OP_REVOKE), now + 2)
        assertTrue(note, note.contains("没有在用身份"))
        assertEquals("", ConsequenceWiring.executorId())
        val all = rows()
        assertEquals("撤销不许改写历史行的身份章", idA, all.first { it.id == oldId }.executor)
        val revokeRow = all.first { it.evidence.startsWith("${ConsequenceEvidence.IDENTITY}revoke") }
        assertEquals("撤销那一刻已经没有在用身份 ⇒ 这一行的章就该是空", "", revokeRow.executor)
        assertEquals("", revokeRow.after)
        assertTrue("历史身份必须还在表里（撤销≠删除）", IdentityRules.wasKnown(IdentityStore.load(files), idA))
    }

    @Test
    fun `判据4e_撤销即名下凭据全失效_且不是靠删表`() {
        val cred = Credential(CredentialKind.SITE_TRUST, "example.com", idA, now)
        CredentialStore.save(files, listOf(cred))
        assertTrue(CredentialStore.permitted(files, cred, now))
        ConsequenceWiring.mutateIdentity(IdentityMutation(IdentityRules.ACTOR_USER, IdentityRules.OP_REVOKE), now + 2)
        assertFalse("身份撤销后名下凭据必须一条也不放行", CredentialStore.permitted(files, cred, now + 2))
        assertEquals("失效≠删除：凭据行必须还在表上", 1, CredentialStore.load(files).size)
        assertTrue(ConsequenceWiring.revocationNote(now + 2).contains("1 条凭据全失效"))
    }

    @Test
    fun `判据4f_轮换记已轮换_老凭据不跟着换新主人`() {
        val cred = Credential(CredentialKind.MCP_TOKEN, "srv-1", idA, now)
        CredentialStore.save(files, listOf(cred))
        // 517 U3：轮换只认人工——actor 改用 user（506 测的 auto 路已被判据拒）。
        val note = ConsequenceWiring.mutateIdentity(IdentityMutation(IdentityRules.ACTOR_USER, IdentityRules.OP_ROTATE, idB), now + 2)
        assertTrue(note, note.startsWith("已改"))
        assertEquals(idB, ConsequenceWiring.executorId())
        assertFalse("老主人的凭据不许自动过户", CredentialStore.permitted(files, cred, now + 2))
        val table = IdentityStore.load(files)
        assertTrue("旧 id 必须留一行「已轮换」", table.any { it.id == idA && it.state == IdentityState.ROTATED_OUT })
        val rotateRow = rows().first { it.evidence.startsWith("${ConsequenceEvidence.IDENTITY}rotate") }
        assertEquals(idA, rotateRow.before)
        assertEquals(idB, rotateRow.after)
        assertEquals(idB, rotateRow.executor)
    }

    /**
     * 口径 U2（517 拍板，替换 506 的「撤销后重启自动铸新」——那条口径正式废止）。
     *
     * 撤销之后重启：表里有历史（已撤销行）但没有「在用」行 ⇒ **不许**静默长出新身份，
     * 在用 id 就是空串，账行的章照实空着；名下凭据照旧全失效。
     * 恢复的唯一入口是**人工铸新**（`op=mint`，`actor=user`）：
     * 模型与装配层调进来都被 [IdentityRules.refusalOf] 拒（U3），铸完旧 id 仍停在「已撤销」。
     */
    @Test
    fun `口径U2_撤销后重启仍失效_恢复只能人工铸新`() {
        val cred = Credential(CredentialKind.TASK_GRANT, "task-506", idA, now)
        CredentialStore.save(files, listOf(cred))
        ConsequenceWiring.mutateIdentity(IdentityMutation(IdentityRules.ACTOR_USER, IdentityRules.OP_REVOKE), now + 2)
        restart(entropyB)
        // 重启**没有**长出新身份：撤销的分量就在它回不来（U2）
        assertEquals("撤销后重启不许静默再铸（U2）", "", ConsequenceWiring.executorId())
        assertFalse("老身份名下的凭据重启后照样失效", CredentialStore.permitted(files, cred, now + 600_000L))
        assertTrue("老 id 仍在表里（历史账照读）", IdentityRules.wasKnown(IdentityStore.load(files), idA))
        // 铸新的口只对人工开：装配层（auto）调 ⇒ 拒，盘上不落行
        val autoTry = ConsequenceWiring.mutateIdentity(IdentityMutation("auto", IdentityRules.OP_MINT, idB), now + 3)
        assertTrue(autoTry, autoTry.startsWith("被拒："))
        assertTrue(autoTry.contains("只许人工"))
        // 人工铸新 ⇒ 成；旧 id 停在已撤销，新身份开始盖章
        val mint = ConsequenceWiring.mutateIdentity(
            IdentityMutation(IdentityRules.ACTOR_USER, IdentityRules.OP_MINT, idB),
            now + 4,
        )
        assertTrue(mint, mint.startsWith("已改"))
        assertEquals(idB, ConsequenceWiring.executorId())
        val table = IdentityStore.load(files)
        assertTrue(table.any { it.id == idA && it.state == IdentityState.REVOKED })
        assertFalse("新身份不继承老身份名下的凭据", CredentialStore.permitted(files, cred, now + 4))
        // 解除撤销这个动词已经不存在：任何 actor 调都被拒（U2）
        assertTrue(
            ConsequenceWiring.mutateIdentity(IdentityMutation(IdentityRules.ACTOR_USER, IdentityRules.OP_UNREVOKE), now + 5)
                .startsWith("被拒："),
        )
    }

    // ── 判据③·指标从账本算＋达标自动转 ─────────────────────────────────────

    @Test
    fun `判据3_达标自动转全开_全程没有一只手`() {
        assertEquals("没落过档位行的能力读出来就是登记表的缺省档", CanaryBand.CANARY, ConsequenceWiring.currentCanaryBand(cap))
        promoteToFull()
        assertEquals(CanaryBand.FULL, ConsequenceWiring.currentCanaryBand(cap))
        val b = latestBand()!!
        assertEquals(DecisionActor.AUTO, b.actor)
        assertTrue(b.evidence.endsWith(":auto-pass"))
        assertEquals("${ConsequenceEvidence.CANARY}$cap:canary", b.rollbackRef)
        assertFalse("转档这条链路上不许出现任何 actor=user 的档位行", bandRows().any { it.actor == DecisionActor.USER })
    }

    @Test
    fun `判据3b_未达标不许自动转_也不落空行`() {
        repeat(CanaryLadder.MIN_RUNS - 2) { i -> ConsequenceWiring.observe(CanaryObservation(cap, ok = true), now + i) }
        repeat(2) { i -> ConsequenceWiring.observe(CanaryObservation(cap, ok = false), now + 50 + i) }
        val before = bandRows().size
        assertEquals(
            "失败率 ${CanaryLadder.FULL_FAIL_LIMIT_PCT}% 以上还转全开",
            CanaryBand.CANARY,
            ConsequenceWiring.decideCanaryBand(cap, now + 100, passedRounds = 9),
        )
        assertEquals("档没动就不该多一行账", before, bandRows().size)
    }

    @Test
    fun `判据3c_档位读的是账不是开关_重启照读`() {
        promoteToFull()
        restart()
        assertEquals(
            "档位另存一处的话，重启后开关说全开、账上没这条 —— 正是本批点名的「看起来有账」",
            CanaryBand.FULL,
            ConsequenceWiring.currentCanaryBand(cap),
        )
    }

    @Test
    fun `负例W4_被回滚的档位行不再算数`() {
        promoteToFull()
        val bandRow = latestBand()!!
        assertTrue("档位行必须标可回滚，否则下面这条 rollback 走不到", bandRow.reversible)
        val ok = DecisionRollbacks.run(bandRow, memory, DecisionRollbackHandler { true }, now + 300)
        assertTrue(ok)
        assertEquals("回滚之后还读成全开＝回滚只改得动账改不动开关", CanaryBand.CANARY, ConsequenceWiring.currentCanaryBand(cap))
    }

    @Test
    fun `判据3d_一次永久损失自动回灰_阈值是本文件常量`() {
        promoteToFull()
        ConsequenceWiring.observe(CanaryObservation(cap, ok = false, permanentLoss = true), now + 300)
        assertEquals(CanaryBand.CANARY, ConsequenceWiring.decideCanaryBand(cap, now + 301))
        assertTrue(latestBand()!!.evidence.endsWith(":auto-revert"))
    }

    @Test
    fun `判据3e_一键回灰是人按的_但回到灰不是关闭`() {
        promoteToFull()
        assertEquals(CanaryBand.CANARY, ConsequenceWiring.humanRevertCanary(cap, now + 300))
        val b = latestBand()!!
        assertEquals(DecisionActor.USER, b.actor)
        assertTrue(b.evidence.endsWith(":human"))
        assertEquals(CanaryBand.CANARY, ConsequenceWiring.currentCanaryBand(cap))
    }

    // ── 追加判据①②④·深度档 ────────────────────────────────────────────────

    @Test
    fun `追加2_盘上没有深度行时读出来是浅`() {
        assertEquals(EvolutionDepth.SHALLOW, ConsequenceWiring.currentDepth(cap))
        assertEquals(EvolutionDepth.SHALLOW, ConsequenceWiring.currentDepth("orchestration"))
    }

    @Test
    fun `追加1_深度升档落账带谁定的_依据_读数`() {
        val d = ConsequenceWiring.decideDepth(
            capability = cap,
            cost = DepthCostFacts(budgetLeft = true, patient = true, highConfidence = true),
            evidence = DepthEvidenceFacts(verdict = ChancellorVerdict.PASS, evidenceEnough = true),
            nowMs = now + 5,
            basis = listOf("预算有余", "右相PASS"),
            readings = listOf("近14天 ${CanaryLadder.MIN_RUNS} 条", "失败率 0.0%"),
        )
        assertEquals(EvolutionDepth.MEDIUM, d)
        val row = rows().first { it.evidence.startsWith("${ConsequenceEvidence.DEPTH}$cap") }
        assertEquals(DecisionActor.AUTO, row.actor)
        assertEquals(idA, row.executor)
        assertTrue(row.reason.contains("预算有余") && row.reason.contains("右相PASS"))
        assertTrue(row.evidence.contains("失败率"))
        assertEquals("${ConsequenceEvidence.DEPTH}$cap:shallow", row.rollbackRef)
    }

    @Test
    fun `追加4_造一条FAIL立刻降一档_不等周期`() {
        ConsequenceWiring.decideDepth(
            cap,
            DepthCostFacts(budgetLeft = true, patient = true, highConfidence = true),
            DepthEvidenceFacts(verdict = ChancellorVerdict.PASS, evidenceEnough = true),
            now + 5,
            basis = listOf("预算有余", "右相PASS"),
            readings = listOf("近14天 10 条"),
        )
        assertEquals(EvolutionDepth.MEDIUM, ConsequenceWiring.currentDepth(cap))
        val back = ConsequenceWiring.decideDepth(
            cap,
            DepthCostFacts(budgetLeft = true, patient = true, highConfidence = true),
            DepthEvidenceFacts(verdict = ChancellorVerdict.FAIL),
            now + 6,
            basis = listOf("右相FAIL", "当日打扰 0 次"),
            readings = listOf("近14天 10 条／失败 1 条"),
        )
        assertEquals(EvolutionDepth.SHALLOW, back)
        assertEquals(EvolutionDepth.SHALLOW, ConsequenceWiring.currentDepth(cap))
    }

    @Test
    fun `追加3_升深要三条同时_灰度没全开就挡在门外`() {
        // 先上一档到中（灰度还在 CANARY 也能上中档），再试「连续成功＋可回滚已验证」——
        // 缺的正好是第三条「本单灰度已全开」，所以这一步必须停在原档不落账。
        ConsequenceWiring.decideDepth(
            cap,
            DepthCostFacts(budgetLeft = true, patient = true, highConfidence = true),
            DepthEvidenceFacts(verdict = ChancellorVerdict.PASS, evidenceEnough = true),
            now + 5,
            basis = listOf("预算有余", "右相PASS"),
            readings = listOf("近14天 10 条"),
        )
        assertEquals(CanaryBand.CANARY, ConsequenceWiring.currentCanaryBand(cap))
        val d = ConsequenceWiring.decideDepth(
            cap,
            DepthCostFacts(budgetLeft = true, patient = true, highConfidence = true),
            DepthEvidenceFacts(
                verdict = ChancellorVerdict.PASS,
                evidenceEnough = true,
                consecutiveSuccess = DepthGate.CONSECUTIVE_SUCCESS_FOR_DEEP,
                rollbackVerified = true,
            ),
            now + 6,
            basis = listOf("连续成功 3", "可回滚已验证"),
            readings = listOf("连续成功 3"),
        )
        assertEquals("灰度没到全开就不许改结构（深档要三条同时）", EvolutionDepth.MEDIUM, d)
        assertEquals(1, rows().count { it.evidence.startsWith("${ConsequenceEvidence.DEPTH}$cap") })
    }

    // ── 追加判据⑤·参谋被拦也要留一行账 ─────────────────────────────────────

    @Test
    fun `追加5_模型给自己发票_拦下并且盘上查得到这一试`() {
        val rejected = ConsequenceWiring.recordAdvisor(
            AdvisorOpinion(
                advisor = IdentityRules.ACTOR_MODEL,
                advice = AdvisorRules.ADVICE_PASS,
                reason = "我自己改的东西我看着没问题",
                risks = emptyList(),
                aboutOwnOutput = true,
                atMs = now + 3,
            ),
        )
        assertNotNull(rejected)
        val row = rows().first { it.kind == DecisionKind.ADVISOR_OPINION }
        assertEquals("blocked", row.after)
        assertEquals("${ConsequenceEvidence.ADVISOR}model:blocked:${now + 3}", row.evidence)
        assertTrue(row.reason.contains("自己的产出"))
        assertEquals(DecisionActor.MODEL, row.actor)
        // 「试过」与「没试过」在账上必须长得不一样——只拦不记，这条判据就没人验得动。
        assertEquals(idA, row.executor)
    }

    @Test
    fun `追加5b_一条合法参谋意见照记不误`() {
        val rejected = ConsequenceWiring.recordAdvisor(
            AdvisorOpinion("model", AdvisorRules.ADVICE_FAIL, "这一步会动到别人的技能正文", listOf("跨技能"), false, now + 3),
        )
        assertNull(rejected)
        val row = rows().first { it.kind == DecisionKind.ADVISOR_OPINION }
        assertEquals("accepted", row.after)
    }

    // ── 负例 W1／W2／W3／W5·缝上的失败模式 ──────────────────────────────────

    @Test
    fun `负例W1_身份表读不动时一条凭据也不放行`() {
        val cred = Credential(CredentialKind.KEY_POOL, "deepseek", idA, now)
        CredentialStore.save(files, listOf(cred))
        ConsequenceDirs.identityFile(files).writeText("{ \"schema\": \"zd-executor/1\", \"rows\": [", Charsets.UTF_8)
        assertEquals("半条表 ⇒ 整表作废，不猜", emptyList<IdentityRow>(), IdentityStore.load(files))
        assertFalse("读不出不许被洗成「全都还有效」", CredentialStore.permitted(files, cred, now))
        assertEquals("", IdentityStore.activeId(files))
    }

    @Test
    fun `负例W2_模型改身份_盘和账一个字节都不动`() {
        val before = rows().size
        val r = ConsequenceWiring.mutateIdentity(IdentityMutation(IdentityRules.ACTOR_MODEL, IdentityRules.OP_REVOKE), now + 2)
        assertTrue(r, r.startsWith("被拒："))
        assertEquals(idA, ConsequenceWiring.executorId())
        assertEquals("被拒的改动不许留一行账", before, rows().size)
        assertTrue(
            "被拒之后盘上还是在用的那个身份",
            IdentityStore.load(files).any { it.id == idA && it.state == IdentityState.ACTIVE },
        )
    }

    @Test
    fun `负例W3_机制没装配时不许把动作报成成功`() {
        ConsequenceWiring.uninstallForTest()
        val r = ConsequenceWiring.mutateIdentity(IdentityMutation(IdentityRules.ACTOR_USER, IdentityRules.OP_REVOKE), now + 2)
        assertEquals("身份机制没装配，这次改动没做", r)
        assertEquals("身份机制没装配", ConsequenceWiring.revocationNote(now))
    }

    @Test
    fun `负例W5_赔付四件缺一_盘上就不许有一行`() {
        val half = CompensationPlanner.plan(
            DamageFacts(
                opId = "op-506",
                tool = "delete_file",
                whatHappened = "删掉了两个文件",
                why = "路径判错",
                nextStepForUser = "去备份目录按日期找回",
                restoreRoutesTried = true,
                restoredPaths = listOf("/ws/技能/甲/SKILL.md"),
                lostItems = listOf(LossItem("/ws/技能/乙/SKILL.md", 2048L, now - 1000L)),
                trail = ReplayTrail(TrailStage.entries.map { TrailStep(it, now, it.cn) }),
                prevention = null, // ← 缺的第③件
            ),
        )
        assertNotNull(half)
        val before = rows().size
        val defects = ConsequenceWiring.recordCompensation(half!!, now + 9)
        assertTrue(defects.any { it.startsWith("③") })
        assertEquals("缺一件就不落账（缺项＝未完成，不是扣分）", before, rows().size)
    }

    @Test
    fun `判据1_四件齐的赔付落账并带永久损失条数`() {
        val c = CompensationPlanner.plan(
            DamageFacts(
                opId = "op-506b",
                tool = "delete_file",
                whatHappened = "删掉了两个文件",
                why = "路径判错",
                nextStepForUser = "去备份目录按日期找回",
                restoreRoutesTried = true,
                restoredPaths = listOf("/ws/技能/甲/SKILL.md"),
                lostItems = listOf(LossItem("/ws/技能/乙/SKILL.md", 2048L, now - 1000L)),
                trail = ReplayTrail(TrailStage.entries.map { TrailStep(it, now, it.cn) }),
                prevention = Prevention(PreventionTarget.RULES_LEDGER, "T-99", "tools/check-rules-ledger.py"),
            ),
        )
        assertNotNull(c)
        assertEquals(emptyList<String>(), ConsequenceWiring.recordCompensation(c!!, now + 9))
        val row = rows().first { it.kind == DecisionKind.COMPENSATION }
        assertEquals("${ConsequenceEvidence.COMPENSATION}op-506b:partial:rules_ledger:T-99·机检", row.evidence)
        assertTrue(row.reason.contains("永久损失 1 项"))
        assertFalse("赔付行不留回滚通道（还原不是撤销这笔账）", row.reversible)
        assertEquals(idA, row.executor)
    }

    // ── 517 追加：U4 恢复后缺身份＋U1 体检格＋Q1 装配 sweep ─────────────────

    /**
     * U4（517）：恢复后缺身份 ⇒ **不许静默生成**。
     * 盘面＝机器上有恢复进来的记忆树＋一枚恢复标记，身份表不在备份里 ⇒ 缺。
     * 装配时表必须仍是空的（没长出新身份），提示面（体检格）点名"需铸新身份"，
     * 恢复的唯一路是人工铸新（U3：模型/装配层调 ⇒ 拒）。
     */
    @Test
    fun `口径U4_恢复标记后缺身份_不许静默生成_铸新后解除`() {
        ConsequenceWiring.uninstallForTest()
        // 模拟"恢复"：身份树整棵不在（U4 的前提——身份/凭据不进备份包，
        // 恢复是按挂载表放回记忆/技能，执行体树从包里来不了）；
        // setUp 那次首启留下的身份行也一并清掉，盘面回到"有记忆、没身份"。
        ConsequenceDirs.root(files).deleteRecursively()
        memory.mkdirs() // 恢复进来的记忆树
        assertTrue(ConsequenceWiring.markRestored(files, now + 10))
        val id = ConsequenceWiring.install(files, now + 11) { entropyA }
        assertEquals("恢复后缺身份不许静默生成（U4）", "", id)
        assertTrue(ConsequenceWiring.needsIdentityMint())
        assertFalse("身份表不许被静默写出", ConsequenceDirs.identityFile(files).isFile)
        val line = ConsequenceWiring.executorSheetLine(now + 12)
        assertTrue("体检格要提示需铸新身份：$line", line.contains("需铸新身份"))
        // 人工铸新 ⇒ 恢复；标记还在（幂等），但表里有了在用行就不再拦
        val mint = ConsequenceWiring.mutateIdentity(
            IdentityMutation(IdentityRules.ACTOR_USER, IdentityRules.OP_MINT, idB),
            now + 13,
        )
        assertTrue(mint, mint.startsWith("已改"))
        assertEquals(idB, ConsequenceWiring.executorId())
        assertFalse(ConsequenceWiring.needsIdentityMint())
        val again = ConsequenceWiring.install(files, now + 14) { entropyA }
        assertEquals("重启读回的是铸出来的那个 id", idB, again)
    }

    /** U1（517）：体检单「执行体」一格——在用/撤销两种说法，时刻全是绝对时刻（U5）。 */
    @Test
    fun `体检格U1_执行体一行_撤销后给绝对时刻`() {
        val line0 = ConsequenceWiring.executorSheetLine(now)
        assertTrue("在用 ⇒ id 前 8 位：$line0", line0.contains(idA.removePrefix(IdentityFormat.PREFIX).take(8)))
        assertTrue(line0, line0.contains("凭据 0 条"))
        assertTrue(line0, line0.contains("最近撤销 无"))
        // 撤销 ⇒ 这一行明说不可逆＋给出**绝对时刻**（U5：不许"刚刚/明天"）
        ConsequenceWiring.mutateIdentity(IdentityMutation(IdentityRules.ACTOR_USER, IdentityRules.OP_REVOKE), now + 2)
        val line1 = ConsequenceWiring.executorSheetLine(now + 3)
        assertTrue(line1, line1.contains("已撤销"))
        assertTrue(line1, line1.contains("最近撤销 "))
        assertTrue(
            "时刻必须是 ISO 绝对时刻：$line1",
            Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?[+-]\d{2}:\d{2}""").containsMatchIn(line1),
        )
    }

    /** Q1 装配侧：install 时的 sweep 给老凭据打标（判据⑪的装配半边；到期判定在判据层测）。 */
    @Test
    fun `判据Q1_装配sweep给老凭据打迁移标_到期日是绝对时刻`() {
        val legacy = Credential(CredentialKind.SITE_TRUST, "example.com", CredentialBinding.UNBOUND, now)
        CredentialStore.save(files, listOf(legacy))
        ConsequenceWiring.uninstallForTest()
        ConsequenceWiring.install(files, now + 100) { entropyA }
        val stamped = CredentialStore.load(files).first()
        assertTrue("进了迁移期", stamped.isLegacy)
        assertEquals("到期日＝sweep 时刻＋默认 30 天", now + 100 + LegacyCredentials.DEFAULT_TTL_MS, stamped.expiresAtMs)
        assertTrue("账上要有一笔迁移记账", rows().any { it.evidence.startsWith("${ConsequenceEvidence.IDENTITY}legacy:") })
    }
}
