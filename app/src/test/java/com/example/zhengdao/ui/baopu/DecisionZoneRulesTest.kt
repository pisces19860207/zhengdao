// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百二十八批 · 决策中心主屏判据层测试（派工单 §二）：
//   ① 待批区能显示 ≥1 条来自 `DecisionLedger` 的真实记录（真临时目录落账再读回）；
//   ② 批/驳/留各写一行账，带 actor 与理由（`rulingDraft` → `recordTo` → 读回逐项断言）；
//   ③ 可回滚条目能触发回滚，回滚后状态与账一致（`DecisionRollbacks.run` 后重读快照）；
//   ⑤ 未新增账本字段（`toJson` 键集与 kind 闭集逐字钉死——差一个键都红）。
//
// 纪律：全程零 Android——账走临时目录真读真写，文案走假解析器。
package com.example.zhengdao.ui.baopu

import com.example.zhengdao.baopu.DecisionActor
import com.example.zhengdao.baopu.DecisionDraft
import com.example.zhengdao.baopu.DecisionKind
import com.example.zhengdao.baopu.DecisionLedger
import com.example.zhengdao.baopu.DecisionRecord
import com.example.zhengdao.baopu.DecisionReview
import com.example.zhengdao.baopu.DecisionRollbackHandler
import com.example.zhengdao.baopu.DecisionRollbacks
import com.example.zhengdao.baopu.EffectSnapshot
import com.example.zhengdao.baopu.PostAdoptionChannel
import com.example.zhengdao.baopu.PostAdoptionNotice
import com.example.zhengdao.baopu.PostAdoptionReason
import com.example.zhengdao.baopu.PostAdoptionVerdict
import com.example.zhengdao.baopu.ProposalKind
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DecisionZoneRulesTest {

    private lateinit var root: File
    private val now = 1_760_000_000_000L

    /** 假文案解析器：`r<资源id>`＋参数原样带上——断言认得出「哪句是哪句」就够。 */
    private val s = DecisionStrRes { id, args -> "r$id" + if (args.isEmpty()) "" else "|" + args.joinToString(",") }

    @Before
    fun setUp() {
        root = Files.createTempDirectory("dz528").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    // ── 夹具 ────────────────────────────────────────────────────────────────

    private fun royalFixture(atMs: Long = now - 5_000L) = RoyalRow(
        id = "op1",
        card = RoyalCard(
            key = "op1",
            lines = listOf(
                RoyalLine(RoyalLineKind.WHAT, "给文件组开一次权限", RoyalTruth.TRUE),
                RoyalLine(RoyalLineKind.WHY, "连续三次卡在缺权限", RoyalTruth.TRUE),
                RoyalLine(RoyalLineKind.IMPACT, "批了就放行这一组", RoyalTruth.UNVERIFIED),
            ),
            options = listOf(
                RoyalOption("approve", "批", "按这条办", isDefault = true),
                RoyalOption("reject", "驳", "这一步不做"),
                RoyalOption("hold", "留", "留到明天再看"),
            ),
        ),
        atMs = atMs,
        gist = "文件组权限",
    )

    private fun authFixture() = DecisionCardSpec(
        variant = DecisionVariant.AUTHORIZATION,
        key = "auth:files",
        title = "允许文件工具组",
        subtitle = "读你手机上的文件",
        buttons = listOf(
            DecisionButton("cancel", "取消", DecisionButtonRole.NEGATIVE),
            DecisionButton("session", "本次会话", DecisionButtonRole.PRIMARY),
        ),
    )

    private fun suggestionFixture(createdAt: Long = now - 3_000L) = EvolutionSuggestion(
        candidateId = "cand1",
        kind = ProposalKind.SKILL_BODY,
        target = "技能A",
        payload = "正文",
        createdAt = createdAt,
        recommendation = com.example.zhengdao.baopu.EvolutionRecommendation.ADOPT,
        confidence = 0.9,
        riskClass = com.example.zhengdao.baopu.EvolutionRisk.LOW,
        requiresApproval = true,
        reasons = emptyList(),
    )

    private fun effectFixture(appliedAtMs: Long = now - 4_000L) = PostAdoptionNotice(
        eventId = "evt1",
        channel = PostAdoptionChannel.LEARN_CARD,
        skillName = "技能A",
        verdict = PostAdoptionVerdict.ROLLBACK,
        reason = PostAdoptionReason.SAMPLES_INSUFFICIENT,
        before = EffectSnapshot(),
        delta = EffectSnapshot(),
        backupIds = listOf("b1"),
        appliedAtMs = appliedAtMs,
    )

    private fun ledgerDraft(kind: DecisionKind, reason: String, atMs: Long, reversible: Boolean? = null) =
        DecisionDraft(
            kind = kind,
            actor = DecisionActor.AUTO,
            reason = reason,
            evidence = "fixture:$reason",
            before = "前",
            after = "后",
            atMs = atMs,
            rollbackRef = if (kind.reversible) "backup-1" else "",
            reversible = reversible,
        )

    private fun snapshotWith(
        records: List<DecisionRecord>,
        royal: List<RoyalRow> = emptyList(),
        authSpec: DecisionCardSpec? = null,
        suggestions: List<EvolutionSuggestion> = emptyList(),
        effects: List<PostAdoptionNotice> = emptyList(),
    ) = DecisionZoneRules.snapshot(
        s = s,
        nowMs = now,
        royal = royal,
        authSpec = authSpec,
        personaSpec = null,
        suggestions = suggestions,
        effects = effects,
        records = records,
        judgedIds = emptySet(),
    )

    // ── 判据①：待批区显示来自 DecisionLedger 的真实记录 ──────────────────────

    @Test
    fun `判据1_待批区显示一条真账_抽检命中的记录落在待批第一区`() {
        val written = DecisionLedger.recordTo(root, ledgerDraft(DecisionKind.DIARY_PURGE, "真删了一批日记", now - 60_000L))
        assertNotNull("夹具账没落上：${DecisionLedger.lastError}", written)
        val records = DecisionLedger.read(root)
        val snap = snapshotWith(records)
        val review = snap.pending.singleOrNull { it.key.startsWith(DecisionZoneRules.REVIEW_PREFIX) }
        assertNotNull("待批区必须出现这条 DecisionLedger 记录（DIARY_PURGE 必抽）", review)
        assertEquals("判据①要的是账上的原句", "真删了一批日记", review!!.lines.first())
    }

    @Test
    fun `判据1补_抽检行的三行与本体`() {
        val written = DecisionLedger.recordTo(root, ledgerDraft(DecisionKind.DIARY_PURGE, "真删了一批日记", now - 60_000L))!!
        val row = snapshotWith(DecisionLedger.read(root)).pending.single { it.source == DecisionZoneSource.REVIEW }
        assertEquals("review:${written.id}", row.key)
        assertEquals("真删了一批日记", row.lines[0])
        assertEquals("前→后", row.lines[1])
        assertTrue("第三行（不答会怎样）必须来自文案解析器注入", row.lines[2].startsWith("r"))
        assertEquals(written, row.record)
        assertTrue(row.canApprove)
        assertTrue(row.canReject)
        assertFalse("留没有既有口：抽检结论是闭集，不新造第三值", row.canHold)
    }

    @Test
    fun `判据1负例_不抽的kind不进待批`() {
        DecisionLedger.recordTo(root, ledgerDraft(DecisionKind.SKILL_PROMOTE, "转正了一个技能", now - 60_000L))
        val snap = snapshotWith(DecisionLedger.read(root))
        assertTrue("NONE 抽检档不许冒进待批", snap.pending.none { it.source == DecisionZoneSource.REVIEW })
    }

    // ── 判据②：批/驳/留各写一行账，带 actor 与理由 ────────────────────────────

    @Test
    fun `判据2_三动作各落一行_带actor与理由_且不可回滚`() {
        val actions = listOf(
            DecisionZoneAction.APPROVE to "已批",
            DecisionZoneAction.REJECT to "已驳",
            DecisionZoneAction.HOLD to "已留",
        )
        actions.forEach { (action, cn) ->
            val draft = DecisionZoneRules.rulingDraft("royal:op1", action, "给文件组开一次权限", now, 7L)
            assertNotNull("$cn 的裁定稿不该为空", draft)
            val row = DecisionLedger.recordTo(root, draft!!)
            assertNotNull("$cn 没落账：${DecisionLedger.lastError}", row)
        }
        val rows = DecisionLedger.read(root).filter { it.evidence.startsWith(DecisionZoneRules.RULING_PREFIX) }
        assertEquals(3, rows.size)
        assertEquals(setOf("已批｜给文件组开一次权限", "已驳｜给文件组开一次权限", "已留｜给文件组开一次权限"), rows.map { it.reason }.toSet())
        rows.forEach {
            assertEquals(DecisionActor.USER, it.actor)
            assertEquals("待批", it.before)
            assertFalse("裁定行只降档：不吃通用撤销钮", it.reversible)
            assertEquals(7L, it.sessionId)
        }
        assertEquals(DecisionKind.TOOL_GRANT, rows.first().kind)
    }

    @Test
    fun `判据2补_说不出办的是哪件事的裁定不落账`() {
        assertNull(DecisionZoneRules.rulingDraft("k", DecisionZoneAction.APPROVE, "  ", now, -1L))
    }

    // ── 判据③：可回滚条目触发回滚，回滚后状态与账一致 ─────────────────────────

    @Test
    fun `判据3_回滚后该区变空_且账上多一条rollback_of`() {
        val target = DecisionLedger.recordTo(root, ledgerDraft(DecisionKind.SKILL_PROMOTE, "转正了一个技能", now - 120_000L))!!
        val before = snapshotWith(DecisionLedger.read(root))
        assertEquals("可回滚区必须认得出这条", listOf(target.id), before.rollbackable.map { it.record.id })

        var handled = 0
        val ok = DecisionRollbacks.run(
            target = target,
            root = root,
            handler = DecisionRollbackHandler { handled++; true },
            atMs = now,
        )
        assertTrue(ok)
        assertEquals(1, handled)

        val after = snapshotWith(DecisionLedger.read(root))
        assertTrue("回滚成功后不许再留在可回滚区（读侧降级）", after.rollbackable.isEmpty())
        val rb = DecisionLedger.read(root).single { it.kind == DecisionKind.ROLLBACK_OF }
        assertEquals(target.id, rb.rollbackOf)
        assertEquals("zone-无关：回滚行由既有通道记账", target.id, rb.evidence)
    }

    @Test
    fun `判据3负例_回滚失败不留痕_可回滚区原样`() {
        val target = DecisionLedger.recordTo(root, ledgerDraft(DecisionKind.SKILL_PROMOTE, "转正了一个技能", now - 120_000L))!!
        val ok = DecisionRollbacks.run(target, root, DecisionRollbackHandler { false }, now)
        assertFalse(ok)
        assertEquals(1, DecisionLedger.read(root).size)
        assertEquals(listOf(target.id), snapshotWith(DecisionLedger.read(root)).rollbackable.map { it.record.id })
    }

    // ── 三区形状：倒序、三行、留的档位、已办取 N ──────────────────────────────

    @Test
    fun `待批按时间倒序_引擎卡没有时刻排最后`() {
        val snap = snapshotWith(
            emptyList(),
            royal = listOf(royalFixture(now - 5_000L)),
            authSpec = authFixture(),
            suggestions = listOf(suggestionFixture(now - 3_000L)),
            effects = listOf(effectFixture(now - 4_000L)),
        )
        assertEquals(
            // 新→旧：建议(-3s) > 效果(-4s) > 御案(-5s) > 引擎卡（无时刻 ⇒ 最后）
            listOf("suggestion:cand1", "effect:evt1", "royal:op1", "auth:files"),
            snap.pending.map { it.key },
        )
    }

    @Test
    fun `每条三行_留只给有既有搁置档的条目`() {
        val snap = snapshotWith(
            emptyList(),
            royal = listOf(royalFixture()),
            authSpec = authFixture(),
            suggestions = listOf(suggestionFixture()),
            effects = listOf(effectFixture()),
        )
        snap.pending.forEach { assertEquals("${it.key} 必须三行", 3, it.lines.size) }
        val byKey = snap.pending.associateBy { it.key }
        assertTrue(byKey.getValue("suggestion:cand1").canHold)
        assertTrue(byKey.getValue("effect:evt1").canHold)
        assertFalse("引擎单槽没有既有搁置口", byKey.getValue("auth:files").canHold)
        assertTrue("御案行带卡（渲染复用 BaopuRoyalCard）", byKey.getValue("royal:op1").royal != null)
    }

    @Test
    fun `已办取最近N条_新到旧`() {
        repeat(25) { i ->
            DecisionLedger.recordTo(root, ledgerDraft(DecisionKind.DIARY_MAINTAIN, "维护第$i", now - i * 1_000L))
        }
        val done = snapshotWith(DecisionLedger.read(root, limit = 200)).done
        assertEquals(DecisionZoneRules.DONE_LIMIT, done.size)
        assertEquals("维护第0", done.first().record.reason)
    }

    @Test
    fun `引擎选项认领_批认确认位_驳认否定位`() {
        val spec = authFixture()
        assertEquals("session", DecisionZoneRules.engineOptionId(spec, approve = true))
        assertEquals("cancel", DecisionZoneRules.engineOptionId(spec, approve = false))
        val disabled = spec.copy(
            buttons = listOf(
                DecisionButton("cancel", "取消", DecisionButtonRole.NEGATIVE),
                DecisionButton("session", "本次会话", DecisionButtonRole.PRIMARY, enabled = false),
            ),
        )
        assertNull("置灰的确认位不许被认领", DecisionZoneRules.engineOptionId(disabled, approve = true))
    }

    // ── 判据⑤：结构断言——账本字段与 kind 闭集一个都没新增 ────────────────────

    @Test
    fun `判据5_账行键集与kind闭集逐字钉死`() {
        val written = DecisionLedger.build(
            DecisionDraft(
                kind = DecisionKind.AUTO_ACT, actor = DecisionActor.AUTO, reason = "r",
                evidence = "e", before = "b", after = "a", atMs = 1L,
            ),
        )!!
        assertEquals(
            setOf(
                "v", "id", "kind", "group_if_known", "actor", "reason", "evidence",
                "before", "after", "at", "session", "reversible", "rollback_ref", "rollback_of", "executor",
            ),
            written.toJson().keys().asSequence().toSet(),
        )
        assertEquals(
            listOf(
                "skill_promote", "skill_auto_write", "skill_retire", "skill_draft_retire", "skill_demoted",
                "read_scope", "artifact_delete", "diary_maintain", "diary_purge", "eval_verdict",
                "auto_act", "rollback_of", "tool_grant", "site_trust", "unclassified_tool",
                "plan_contract_finding", "ledger_policy", "compensation", "identity_state", "advisor_opinion",
            ),
            DecisionKind.entries.map { it.wire },
        )
        assertEquals(setOf(DecisionRecord.VERDICT_CONFIRMED, DecisionRecord.VERDICT_WRONG), DecisionReview.VERDICTS)
    }
}
