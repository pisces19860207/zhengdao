// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 第一百零九批 §3：L3-lite **影子模式**的行为用例。三组：
//   A 影子三值（低风险达标⇒会批／硬证据⇒会拒／证据不足或风险不在范围⇒DEFER）；
//   B 落盘（一行一条、只追加、读回等价、脏行跳过、写不出去吞）；
//   C 指标（三值计数、按风险类别、与人工决定的一致率口径、零样本没有比率）。
// 关键正例：**影子说会批的那条，真结论仍然是「要人批」**——影子改的是账，不是决定。
// 纯 JVM：不碰 Android/DB/UI，不跑真机。
package com.example.zhengdao.baopu

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class EvolutionShadowTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ── 夹具（与 88 的策略用例同一把尺）──────────────────────────────────────

    private fun okCheck() = SelfCheckEvidence(
        passed = true,
        rollbackAvailable = true,
        caseIds = listOf("selfcheck:case-1"),
    )

    private fun badCheck() = SelfCheckEvidence(
        passed = false,
        rollbackAvailable = true,
        caseIds = listOf("selfcheck:case-1"),
        failedRuleIds = listOf("selfcheck:rule-9"),
    )

    private fun noRollbackCheck() = SelfCheckEvidence(
        passed = true,
        rollbackAvailable = false,
        caseIds = listOf("selfcheck:case-2"),
    )

    private fun goodEval() = EvolutionEval(score = 0.9, caseIds = listOf("eval:case-1"))

    private fun lowEval() = EvolutionEval(score = 0.5, caseIds = listOf("eval:case-2"))

    private fun goodEffect() = EffectSnapshot(
        successes = 3,
        retries = 1,
        signalIds = listOf("skill_event:11"),
    )

    private fun weakEffect() = EffectSnapshot(successes = 1, signalIds = listOf("skill_event:12"))

    private fun badEffect() = EffectSnapshot(failures = 3, signalIds = listOf("skill_event:13"))

    private fun input(
        risk: EvolutionRiskCategory = EvolutionRiskCategory.SKILL_BODY_MINOR,
        kind: ProposalKind = ProposalKind.SKILL_BODY,
        selfCheck: SelfCheckEvidence? = okCheck(),
        eval: EvolutionEval? = goodEval(),
        effect: EffectSnapshot = goodEffect(),
        postAdoption: PostAdoptionEffect? = null,
        candidateId: String = "candidate-1",
    ) = EvolutionDecisionInput(
        candidateId = candidateId,
        kind = kind,
        riskCategory = risk,
        evidenceIds = listOf("run_log:3", "skill_event:1"),
        selfCheck = selfCheck,
        eval = eval,
        effect = effect,
        postAdoption = postAdoption,
    )

    /** 真结论：默认策略（开关关）——影子的一切用例都从这里出发。 */
    private fun real(source: EvolutionDecisionInput): EvolutionDecision =
        EvolutionPolicy().judge(source).let { j ->
            EvolutionDecision(
                candidateId = source.candidateId,
                recommendation = j.recommendation,
                confidence = j.confidence,
                riskClass = j.riskClass,
                requiresApproval = j.requiresApproval,
                reasons = j.reasons,
            )
        }

    private fun shadow(
        source: EvolutionDecisionInput = input(),
        at: Long = 1000L,
    ): ShadowJudgement = shadowVerdictOf(source, real(source), at)

    // ── A 影子三值 ──────────────────────────────────────────────────────────

    @Test
    fun `正例_低风险达标_影子说会批_真结论仍要人批`() {
        val source = input()
        val decision = real(source)
        val row = shadow(source)
        assertEquals(ShadowVerdict.WOULD_APPROVE, row.verdict)
        assertEquals(ShadowHold.NONE, row.hold)
        assertTrue("派工单红线：影子说会批，这条**实际**仍要人批", decision.requiresApproval)
        assertTrue("实际仍人批这件事必须原样进账", row.requiresApproval)
        assertEquals(EvolutionRecommendation.ADOPT, row.recommendation)
        assertEquals(EvolutionRisk.LOW, row.riskClass)
        assertEquals(EvolutionRiskCategory.SKILL_BODY_MINOR, row.riskCategory)
    }

    @Test
    fun `影子与假想开闸逐类别等价`() {
        // 测试侧造「若开闸」的那个实例（main 里不许出现，见门禁套件）：
        // 影子说会批 ⟺ 那个实例说「不需要人批」。逐风险类别、逐证据形状对账。
        val auto = EvolutionPolicy(/* 测试侧假想开闸 */ autoLowRiskEnabled = true)
        val shapes = listOf(
            "证据齐" to { r: EvolutionRiskCategory -> input(risk = r) },
            "自检没跑" to { r: EvolutionRiskCategory -> input(risk = r, selfCheck = null) },
            "自检失败" to { r: EvolutionRiskCategory -> input(risk = r, selfCheck = badCheck()) },
            "eval 没跑" to { r: EvolutionRiskCategory -> input(risk = r, eval = null) },
            "eval 未过线" to { r: EvolutionRiskCategory -> input(risk = r, eval = lowEval()) },
            "回滚不可用" to { r: EvolutionRiskCategory -> input(risk = r, selfCheck = noRollbackCheck()) },
            "效果不足" to { r: EvolutionRiskCategory -> input(risk = r, effect = weakEffect()) },
            "效果为负" to { r: EvolutionRiskCategory -> input(risk = r, effect = badEffect()) },
            "零效果样本" to { r: EvolutionRiskCategory -> input(risk = r, effect = EffectSnapshot()) },
        )
        var checked = 0
        for (category in EvolutionRiskCategory.values()) {
            for ((label, build) in shapes) {
                val source = build(category)
                val hypothetical = auto.judge(source)
                val row = shadowVerdictOf(source, real(source), 1L)
                assertEquals(
                    "$label／$category：会批必须等价于假想开闸后免人批",
                    !hypothetical.requiresApproval,
                    row.verdict == ShadowVerdict.WOULD_APPROVE,
                )
                checked++
            }
        }
        assertEquals("七类 × 九形状都过了", 7 * 9, checked)
    }

    @Test
    fun `高风险_证据齐也_影子弃权并写明风险不在范围`() {
        for (category in listOf(
            EvolutionRiskCategory.PERSONA_CORE,
            EvolutionRiskCategory.TOOL_PERMISSION,
            EvolutionRiskCategory.ROOTFS_SYSTEM,
        )) {
            val row = shadow(input(risk = category))
            assertEquals("$category 证据齐但风险高", ShadowVerdict.WOULD_DEFER, row.verdict)
            assertEquals("$category 的弃权档", ShadowHold.RISK_NOT_LOW, row.hold)
            assertEquals("$category 的真结论仍是 ADOPT", EvolutionRecommendation.ADOPT, row.recommendation)
        }
    }

    @Test
    fun `中风险_证据齐也弃权_两类都点名`() {
        for (category in listOf(
            EvolutionRiskCategory.SKILL_BODY_STRUCTURE,
            EvolutionRiskCategory.CROSS_SKILL_IMPACT,
        )) {
            val row = shadow(input(risk = category))
            assertEquals(ShadowVerdict.WOULD_DEFER, row.verdict)
            assertEquals(ShadowHold.RISK_NOT_LOW, row.hold)
        }
    }

    @Test
    fun `硬证据反对_影子说拒`() {
        val cases = listOf(
            "自检失败" to input(selfCheck = badCheck()),
            "eval 未过线" to input(eval = lowEval()),
            "效果为负" to input(effect = badEffect()),
        )
        for ((label, source) in cases) {
            val row = shadow(source)
            assertEquals("$label：影子必须说拒", ShadowVerdict.WOULD_REJECT, row.verdict)
            assertEquals("$label：弃权档", ShadowHold.HARD_NO, row.hold)
            assertEquals("$label：真结论同为 REJECT", EvolutionRecommendation.REJECT, row.recommendation)
        }
    }

    @Test
    fun `证据不足_影子DEFER_并写明缺口`() {
        val cases = listOf(
            "自检没跑" to input(selfCheck = null),
            "eval 没跑" to input(eval = null),
            "回滚不可用" to input(selfCheck = noRollbackCheck()),
            "效果样本不足" to input(effect = weakEffect()),
            "零效果样本" to input(effect = EffectSnapshot()),
        )
        for ((label, source) in cases) {
            val row = shadow(source)
            assertEquals("$label：影子必须弃权", ShadowVerdict.WOULD_DEFER, row.verdict)
            assertEquals("$label：缺口档", ShadowHold.EVIDENCE_GAP, row.hold)
        }
    }

    @Test
    fun `零样本效果_只记DEFER_不猜正负`() {
        // 派工单 §2：零样本／数据不足只记 DEFER。效果全零既不是「没效果」也不是「效果差」。
        val source = input(effect = EffectSnapshot())
        val row = shadow(source)
        assertEquals(ShadowVerdict.WOULD_DEFER, row.verdict)
        assertEquals(EvolutionRecommendation.DEFER, row.recommendation)
        assertTrue(
            "原因链必须写明是效果不足，而不是别的",
            row.reasons.any { it.code == EvolutionReasonCode.EFFECT_INSUFFICIENT },
        )
    }

    @Test
    fun `采纳后被判变差_影子跟着DEFER`() {
        val source = input(
            postAdoption = PostAdoptionEffect(
                eventId = "adopt-1",
                verdict = PostAdoptionVerdict.ROLLBACK,
            ),
        )
        val row = shadow(source)
        assertEquals(ShadowVerdict.WOULD_DEFER, row.verdict)
        assertEquals(ShadowHold.EVIDENCE_GAP, row.hold)
        assertTrue(
            row.reasons.any { it.code == EvolutionReasonCode.POST_ADOPTION_NEGATIVE },
        )
    }

    @Test
    fun `防御档_ADOPT缺硬条件原因_影子弃权不猜`() {
        // 今天的策略下 ADOPT 必然带齐四码（上面那条等价用例就是证明）。
        // 这里手工拼一条「ADOPT＋低风险但原因链不齐」的决策：将来策略改了原因链，
        // 影子宁可弃权也不替它猜「大概够条件」。
        val source = input()
        val patched = EvolutionDecision(
            candidateId = source.candidateId,
            recommendation = EvolutionRecommendation.ADOPT,
            confidence = 0.9,
            riskClass = EvolutionRisk.LOW,
            requiresApproval = true,
            reasons = listOf(
                EvolutionReason(EvolutionReasonCode.CANDIDATE_EVIDENCE, listOf("run_log:3")),
                EvolutionReason(EvolutionReasonCode.SELF_CHECK_PASSED, listOf("selfcheck:case-1")),
                EvolutionReason(EvolutionReasonCode.EVAL_PASSED, listOf("eval:case-1")),
                // 少 EFFECT_POSITIVE 与 ROLLBACK_AVAILABLE
            ),
        )
        val row = shadowVerdictOf(source, patched, 7L)
        assertEquals(ShadowVerdict.WOULD_DEFER, row.verdict)
        assertEquals(ShadowHold.EVIDENCE_GAP, row.hold)
    }

    @Test
    fun `影子照抄真结论_不另算一份`() {
        val source = input()
        val decision = real(source)
        val row = shadowVerdictOf(source, decision, 42L)
        assertEquals(decision.recommendation, row.recommendation)
        assertEquals(decision.confidence, row.confidence, 0.0)
        assertEquals(decision.riskClass, row.riskClass)
        assertEquals(decision.requiresApproval, row.requiresApproval)
        assertEquals("原因链整条搬过来，一个字不改", decision.reasons, row.reasons)
        assertEquals("候选元数据取自输入", source.kind, row.kind)
        assertEquals(source.evidenceIds, row.evidenceIds)
        assertEquals(42L, row.atMs)
    }

    @Test
    fun `判据必须落在同一条候选上`() {
        val source = input(candidateId = "a")
        val other = real(input(candidateId = "b"))
        assertThrows(IllegalArgumentException::class.java) {
            shadowVerdictOf(source, other, 1L)
        }
    }

    @Test
    fun `不变式_只有会批可以没有保留原因`() {
        val base = shadow()
        assertThrows(IllegalArgumentException::class.java) {
            base.copy(verdict = ShadowVerdict.WOULD_REJECT, hold = ShadowHold.NONE)
        }
        assertThrows(IllegalArgumentException::class.java) {
            base.copy(verdict = ShadowVerdict.WOULD_APPROVE, hold = ShadowHold.HARD_NO)
        }
        assertThrows(IllegalArgumentException::class.java) {
            base.copy(candidateId = "  ")
        }
        assertThrows(IllegalArgumentException::class.java) {
            base.copy(confidence = 1.5)
        }
        assertThrows(IllegalArgumentException::class.java) {
            base.copy(reasons = emptyList())
        }
    }

    // ── B 落盘 ──────────────────────────────────────────────────────────────

    @Test
    fun `影子行_顶层键固定`() {
        val row = shadow()
        assertEquals(
            setOf(
                "at_ms",
                "candidate_id",
                "kind",
                "risk_category",
                "risk_class",
                "verdict",
                "hold",
                "recommendation",
                "confidence",
                "requires_approval",
                "evidence_ids",
                "reasons",
                "l3_auto_low_risk",
            ),
            JSONObject(row.toJson().toString()).keys().asSequence().toSet(),
        )
    }

    @Test
    fun `影子行_只有id与数值_没有正文`() {
        val source = input()
        val json = shadow(source).toJson().toString()
        for (key in listOf("\"payload\"", "\"body\"", "\"content\"", "\"detail\"", "\"text\"")) {
            assertFalse("影子行不许出现正文键：$key", json.contains(key))
        }
        assertFalse("候选正文一个字都不进来", json.contains("候选正文"))
        val row = JSONObject(json)
        assertEquals(
            listOf("run_log:3", "skill_event:1"),
            (0 until row.getJSONArray("evidence_ids").length()).map { row.getJSONArray("evidence_ids").getString(it) },
        )
        assertTrue("理由只带证据 id", row.getJSONArray("reasons").getJSONObject(0).has("evidence_ids"))
    }

    @Test
    fun `影子行记下当时的开关与真结论`() {
        val row = shadow().toJson()
        assertFalse("开关那一刻是 false（红线）", row.getBoolean("l3_auto_low_risk"))
        assertTrue("会批那格的**真**结论仍是人批", row.getBoolean("requires_approval"))
        assertEquals("WOULD_APPROVE", row.getString("verdict"))
    }

    @Test
    fun `账本一行一条_只追加不覆盖`() {
        val log = EvolutionShadowLog(File(tmp.root, "进化影子.jsonl"))
        log.record(shadow(input(candidateId = "c1"), at = 1L))
        log.record(shadow(input(candidateId = "c2"), at = 2L))
        val lines = log.file().readLines(Charsets.UTF_8).filter { it.isNotBlank() }
        assertEquals(2, lines.size)
        val first = lines[0]
        log.record(shadow(input(candidateId = "c3"), at = 3L))
        val after = log.file().readLines(Charsets.UTF_8).filter { it.isNotBlank() }
        assertEquals(3, after.size)
        assertEquals("老行一个字节都不许动", first, after[0])
        assertEquals("c1", JSONObject(after[0]).getString("candidate_id"))
    }

    @Test
    fun `账本读回与写进去等价`() {
        val log = EvolutionShadowLog(File(tmp.root, "进化影子.jsonl"))
        val rows = listOf(
            shadow(input(candidateId = "c1"), at = 1L),
            shadow(input(risk = EvolutionRiskCategory.PERSONA_CORE, candidateId = "c2"), at = 2L),
            shadow(input(eval = null, candidateId = "c3"), at = 3L),
            shadow(input(effect = badEffect(), candidateId = "c4"), at = 4L),
        )
        rows.forEach { log.record(it) }
        assertEquals(rows, log.load())
    }

    @Test
    fun `账本不存在算空账_不算读不出来`() {
        val log = EvolutionShadowLog(File(tmp.root, "还没有这份账.jsonl"))
        val loaded = log.load()
        assertNotNull("文件不存在 ⇒ 空账（不是 null）", loaded)
        assertTrue(loaded!!.isEmpty())
    }

    @Test
    fun `脏行跳过_好行照读`() {
        val file = File(tmp.root, "进化影子.jsonl")
        val log = EvolutionShadowLog(file)
        log.record(shadow(input(candidateId = "good-1"), at = 1L))
        file.appendText("这不是 JSON\n", Charsets.UTF_8)
        file.appendText("""{"candidate_id":"缺枚举的一行"}""" + "\n", Charsets.UTF_8)
        log.record(shadow(input(candidateId = "good-2"), at = 2L))
        val loaded = log.load()
        assertEquals(listOf("good-1", "good-2"), loaded?.map { it.candidateId })
    }

    @Test
    fun `写失败向上抛_不静默丢账`() {
        val blocker = File(tmp.root, "blocker").apply { writeText("我不是目录") }
        val log = EvolutionShadowLog(File(blocker, "进化影子.jsonl"))
        assertThrows(IOException::class.java) {
            log.record(shadow())
        }
    }

    @Test
    fun `同一候选取最后一行`() {
        val rows = listOf(
            shadow(input(candidateId = "c1", effect = badEffect()), at = 5L),
            shadow(input(candidateId = "c2"), at = 2L),
            shadow(input(candidateId = "c1"), at = 9L),
        )
        val dedup = latestByCandidate(rows)
        assertEquals(2, dedup.size)
        val c1 = dedup.first { it.candidateId == "c1" }
        assertEquals("乱序进来也必须取最晚那行", 9L, c1.atMs)
        assertEquals(ShadowVerdict.WOULD_APPROVE, c1.verdict)
    }

    // ── C 指标 ──────────────────────────────────────────────────────────────

    private fun mixedRows(): List<ShadowJudgement> = listOf(
        shadow(input(candidateId = "a"), at = 1L),
        shadow(input(risk = EvolutionRiskCategory.PROMPT_TWEAK, candidateId = "b"), at = 2L),
        shadow(input(risk = EvolutionRiskCategory.PERSONA_CORE, candidateId = "c"), at = 3L),
        shadow(input(selfCheck = badCheck(), candidateId = "d"), at = 4L),
        shadow(input(eval = null, candidateId = "e"), at = 5L),
        shadow(
            input(
                candidateId = "f",
                kind = ProposalKind.TOOL_RULE,
                risk = EvolutionRiskCategory.TOOL_PERMISSION,
            ),
            at = 6L,
        ),
    )

    @Test
    fun `计数_三值与按类别与按kind`() {
        val counts = tallyShadow(latestByCandidate(mixedRows()))
        assertEquals(6, counts.rows)
        assertEquals(2, counts.wouldApprove)
        assertEquals(1, counts.wouldReject)
        assertEquals(3, counts.wouldDefer)
        assertEquals(
            mapOf(
                EvolutionRiskCategory.SKILL_BODY_MINOR to 3,
                EvolutionRiskCategory.PROMPT_TWEAK to 1,
                EvolutionRiskCategory.PERSONA_CORE to 1,
                EvolutionRiskCategory.TOOL_PERMISSION to 1,
            ),
            counts.byCategory,
        )
        assertEquals(
            "「会批准」那部分单独按风险类别分——开闸前真正要看的是这张表",
            mapOf(
                EvolutionRiskCategory.SKILL_BODY_MINOR to 1,
                EvolutionRiskCategory.PROMPT_TWEAK to 1,
            ),
            counts.approveByCategory,
        )
        assertEquals(
            mapOf(
                ProposalKind.SKILL_BODY to 5,
                ProposalKind.TOOL_RULE to 1,
            ),
            counts.byKind,
        )
    }

    @Test
    fun `一致率_四格与比率`() {
        val rows = latestByCandidate(mixedRows())
        // a：影子会批・人采纳 ⇒ 一致
        // b：影子会批・人丢弃 ⇒ 影子过松那格
        // d：影子说拒・人也丢 ⇒ 一致
        // c：影子弃权・人已采纳 ⇒ 不进分母
        val human = mapOf("a" to true, "b" to false, "d" to false, "c" to true)
        val agreement = agreementShadow(rows, human)
        assertEquals(3, agreement.paired)
        assertEquals(2, agreement.agreed)
        assertEquals(1, agreement.approveAdopt)
        assertEquals(1, agreement.approveReject)
        assertEquals(0, agreement.rejectAdopt)
        assertEquals(1, agreement.rejectReject)
        assertEquals(1, agreement.abstainedHumanDecided)
        assertEquals(2, agreement.waitingHuman)
        assertNotNull(agreement.rate)
        assertEquals(2.0 / 3.0, agreement.rate!!, 1e-9)
    }

    @Test
    fun `零配对_比率是null而不是0`() {
        val agreement = agreementShadow(latestByCandidate(mixedRows()), emptyMap())
        assertEquals(0, agreement.paired)
        assertNull("派工单 §2：零样本不猜——没有配对就没有比率", agreement.rate)
        assertEquals(6, agreement.waitingHuman)
        assertFalse("影子有结论但人还没决，一条都不许算进分母", agreement.paired > 0)
    }

    @Test
    fun `DEFER不进分母_弃权单独一格`() {
        val rows = latestByCandidate(
            listOf(
                shadow(input(risk = EvolutionRiskCategory.ROOTFS_SYSTEM, candidateId = "x"), at = 1L),
                shadow(input(eval = null, candidateId = "y"), at = 2L),
            ),
        )
        val agreement = agreementShadow(rows, mapOf("x" to true, "y" to false))
        assertEquals("两条都是弃权 ⇒ 分母为零", 0, agreement.paired)
        assertNull(agreement.rate)
        assertEquals(2, agreement.abstainedHumanDecided)
        assertEquals(0, agreement.waitingHuman)
    }

    @Test
    fun `一致率的同意定义_批与采纳同向_拒与丢弃同向`() {
        val approve = shadow(input(candidateId = "p"), at = 1L)
        val reject = shadow(input(selfCheck = badCheck(), candidateId = "q"), at = 2L)
        val rows = listOf(approve, reject)
        assertEquals(ShadowVerdict.WOULD_APPROVE, approve.verdict)
        assertEquals(ShadowVerdict.WOULD_REJECT, reject.verdict)
        val bothAgree = agreementShadow(rows, mapOf("p" to true, "q" to false))
        assertEquals(2, bothAgree.agreed)
        assertEquals(1.0, bothAgree.rate!!, 1e-9)
        val bothWrong = agreementShadow(rows, mapOf("p" to false, "q" to true))
        assertEquals(0, bothWrong.agreed)
        assertEquals(0.0, bothWrong.rate!!, 1e-9)
        assertEquals(1, bothWrong.approveReject)
        assertEquals(1, bothWrong.rejectAdopt)
    }

    @Test
    fun `汇总行_零样本打横杠不打0`() {
        val rows = latestByCandidate(mixedRows())
        val line = shadowSummaryLine(tallyShadow(rows), agreementShadow(rows, emptyMap()))
        assertTrue("没配对就写 —，绝不写 0.00 冒充有数", line.contains("一致率 —"))
        assertTrue("开关状态必须跟着一起出，日后能解释这行是谁记的", line.contains("开关仍 false"))
        assertTrue(line.contains("会批 2"))
        assertTrue(line.contains("会拒 1"))
        assertTrue(line.contains("会搁置 3"))
        assertTrue("按风险类别给出会批分布", line.contains("SKILL_BODY_MINOR=1"))
        assertFalse("横杠那格不许同时出现 0.00", line.contains("0.00"))
    }

    @Test
    fun `汇总行_有配对时打出两位比率`() {
        val rows = latestByCandidate(mixedRows())
        val line = shadowSummaryLine(
            tallyShadow(rows),
            agreementShadow(rows, mapOf("a" to true, "b" to false, "d" to false)),
        )
        assertTrue("汇总行要成对给出分母与比率：$line", line.contains("人工配对 3 一致 2 一致率 0.67"))
    }

    // ── D 人工留痕读数 ──────────────────────────────────────────────────────

    @Test
    fun `人工留痕读数_一条SELECT_按时间排`() {
        val stmt = EvolutionShadowHuman.decisionsStmt()
        assertEquals(
            "SELECT hash, ok FROM skill_event WHERE kind = 'decision' AND hash IS NOT NULL ORDER BY at",
            stmt.sql,
        )
        assertTrue("这条读数不带参数", stmt.args.isEmpty())
        for (word in listOf("INSERT", "UPDATE", "DELETE", "CREATE", "ALTER", "DROP")) {
            assertFalse("影子只读，不许出现 $word", stmt.sql.contains(word))
        }
    }

    @Test
    fun `同hash后写覆盖先写_人改主意以最后一次为准`() {
        val map = EvolutionShadowHuman.byHash(
            listOf(
                "h1" to true,
                "h2" to false,
                "h1" to false,
                "  " to true,
            ),
        )
        assertEquals(2, map.size)
        assertEquals(false, map["h1"])
        assertEquals(false, map["h2"])
        assertFalse("空白 hash 不进 map", map.containsKey(""))
    }

    // ── E 零副作用 ──────────────────────────────────────────────────────────

    private fun candidate(id: String) = ProposalCandidate(
        id = id,
        kind = ProposalKind.SKILL_BODY,
        target = "技能A",
        payload = "候选正文-$id",
        evidence = ProposalEvidence(runLogIds = listOf(3L), skillEventIds = listOf(1L), atMs = 9L),
        createdAt = 1L,
    )

    @Test
    fun `影子整条链跑完_候选档一个字节不动_目录不冒新文件`() {
        val storeRoot = tmp.newFolder("store")
        val shadowRoot = tmp.newFolder("shadow")
        val store = ProposalCandidateStore(storeRoot, log = {})
        assertEquals(2, store.appendNew(listOf(candidate("c1"), candidate("c2")), stamp = 5L))
        val before = store.file().readBytes()
        val listing = storeRoot.walkTopDown().filter { it.isFile }.map { it.name }.toSet()

        val sink = RecordingAudit()
        val port = EvolutionDecisionPort(policy = EvolutionPolicy(), audit = sink, clock = { 1L })
        val shadowLog = EvolutionShadowLog(File(shadowRoot, EvolutionShadowLog.FILE_NAME))
        val rows = ArrayList<ShadowJudgement>()
        for (c in store.listPending()) {
            val source = toDecisionInput(
                c,
                EvolutionAssessment(
                    riskCategory = EvolutionRiskCategory.SKILL_BODY_MINOR,
                    selfCheck = okCheck(),
                    eval = goodEval(),
                    effect = goodEffect(),
                ),
            )
            val decision = port.decide(source, operatorId = "settle")
            val row = shadowVerdictOf(source, decision, 1L)
            rows += row
            shadowLog.record(row)
        }
        val dedup = latestByCandidate(shadowLog.load()!!)
        val counts = tallyShadow(dedup)
        val agreement = agreementShadow(dedup, mapOf("c1" to true))

        assertEquals(2, rows.count { it.verdict == ShadowVerdict.WOULD_APPROVE })
        assertEquals(2, counts.wouldApprove)
        assertEquals(2, dedup.size)
        assertEquals("每条真决策恰好一条审计（影子不加第二条）", 2, sink.entries.size)
        assertTrue("影子说的「会批」不许变成「已批」", sink.entries.all { it.decision.requiresApproval })
        assertArrayEquals("候选档不许被影子动一个字节", before, store.file().readBytes())
        assertEquals("候选目录里不许冒出影子文件", listing, storeRoot.walkTopDown().filter { it.isFile }.map { it.name }.toSet())
        assertEquals("影子账只落在它自己的目录", listOf(EvolutionShadowLog.FILE_NAME), shadowRoot.walkTopDown().filter { it.isFile }.map { it.name }.toList())
        assertEquals(1, agreement.paired)
        assertEquals(1.0, agreement.rate!!, 1e-9)
    }

    @Test
    fun `账本文件名与落点`() {
        val root = tmp.newFolder("root")
        val log = EvolutionShadowLog.under(root)
        assertEquals(EvolutionShadowLog.FILE_NAME, "进化影子.jsonl")
        assertEquals(File(root, EvolutionShadowLog.FILE_NAME), log.file())
    }

    private class RecordingAudit : EvolutionAuditSink {
        val entries = mutableListOf<EvolutionAuditEntry>()
        override fun record(entry: EvolutionAuditEntry) {
            entries += entry
        }
    }
}
