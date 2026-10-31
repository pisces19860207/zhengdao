// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第三百一十批 · 判据进化的三件（不可进化清单／锚定样本／纪元）——行为层门禁。
//
// 这一套只管「闸会不会拦」。源级牙齿（清单被改写、被配置化、产物出现标量分）在
// `ImmutableCriteriaGateTest` 与 `NoScalarScoreGateTest`，锚定集落盘账在
// `AnchorAdmissionTest` ＋ CI 的 `tools/check-criteria-gates.py`。
//
// 测试里出现的锚定样本**全是 fixture**（screenedBy/signedBy 填的是假名），
// 它们不是御案数据：真正生效的锚定集只有一份，写在 `_zd-private/docs/anchor-set.jsonl`，
// 生效条件是「蓝总管初筛＋用户签字」（见 `AnchorAdmissionTest` 的同名判据）。
package com.example.zhengdao.baopu.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

private const val DAY_MS = 24L * 3_600_000L

/** 2026-10-26 是本单的周一；测试用它当纪元基准，不读系统时钟。 */
private val BASE_MONDAY: LocalDate = LocalDate.of(2026, 10, 26)

private fun weekStart(weeks: Long): Long = BASE_MONDAY.plusWeeks(weeks).toEpochDay() * DAY_MS

private fun midWeek(weeks: Long, plusDays: Long): Long = weekStart(weeks) + plusDays * DAY_MS

class CriteriaEvolutionTest {

    @Before
    fun resetEpoch() {
        EpochRuntime.reset()
    }

    // ── 造数据的四个小工具 ────────────────────────────────────────────

    private fun anchor(
        id: String,
        human: String,
        signed: Boolean = true,
    ): AnchorCase = AnchorCase(
        id = id,
        scene = "场景 $id（fixture，非御案数据）",
        oldVerdict = "不通过：旧判据在这里判错了",
        humanVerdict = human,
        evidence = "_zd-private/docs/缺陷台账.md:fixture",
        screenedBy = if (signed) "蓝总管" else "",
        signedBy = if (signed) "用户" else "",
        signedAt = if (signed) "2026-10-31" else "",
    )

    private fun anchors(vararg sides: String): List<AnchorCase> =
        sides.mapIndexed { i, side -> anchor("A${i + 1}", side) }

    private fun replay(less: Int = 0) = ReplayProof(moreBlocked = 2, lessBlocked = less, moreAsked = 0, ranAtMs = weekStart(0))

    private fun candidate(
        target: String,
        verdicts: Map<String, String>,
        intent: String = "把这类误判补上",
        form: CriteriaOutputForm = CriteriaOutputForm.CHECK_ITEMS,
        proof: ReplayProof? = replay(),
        exempts: Set<String> = emptySet(),
    ) = CriteriaCandidate(
        id = "C-1",
        intent = intent,
        target = target,
        outputForm = form,
        origin = CriteriaOrigin.CANDIDATE_POOL,
        replay = proof,
        verdicts = verdicts,
        exemptActions = exempts,
    )

    /** 三侧齐全的签字锚定集：通过／不通过／通过（同侧判定才看得出谁真在比对）。 */
    private val SIDES = listOf("通过", "不通过", "通过")

    private fun signedAnchors(): List<AnchorCase> = anchors(*SIDES.toTypedArray())

    private fun goodVerdicts(): Map<String, String> =
        (1..SIDES.size).associate { "A$it" to SIDES[it - 1] }

    private val harmlessTarget = "app/src/main/java/com/example/zhengdao/baopu/eval/EvalFloor#floorOf"

    // ── 判据①：命中不可进化清单 ⇒ 红（三条各一例） ─────────────────────

    @Test
    fun `判据一_交付物真落盘被草案触碰_直接拒`() {
        val target = "app/src/main/java/com/example/zhengdao/baopu/verify/ArtifactVerifier.kt#check"
        val hit = ImmutableCriteria.match(target)
        assertNotNull("①的锚点必须还在清单上（清单漂了这条就空转）", hit)
        val admission = CriteriaEvolution.admit(
            candidate(target, goodVerdicts()),
            signedAnchors(),
        )
        assertEquals(AdmissionState.REFUSED, admission.state)
        assertEquals(RefuseCode.IMMUTABLE_HIT, admission.refuseCode)
        assertTrue(admission.checks.first { it.name == "不可进化清单" }.detail.contains("真落盘"))
    }

    @Test
    fun `判据一_破坏性动作必人确认被草案触碰_直接拒`() {
        val target = "app/src/main/java/com/example/zhengdao/baopu/authz/TaskGrantSection.kt#StillAsk.DESTRUCTIVE"
        assertNotNull("②的锚点必须还在清单上", ImmutableCriteria.match(target))
        val admission = CriteriaEvolution.admit(
            candidate(target, goodVerdicts()),
            signedAnchors(),
        )
        assertEquals(AdmissionState.REFUSED, admission.state)
        assertEquals(RefuseCode.IMMUTABLE_HIT, admission.refuseCode)
    }

    @Test
    fun `判据一_紧急卡必人批被草案触碰_直接拒`() {
        val target = "app/src/main/java/com/example/zhengdao/baopu/law/EmergencyGate#requiresApproval"
        assertNotNull("③的锚点必须还在清单上", ImmutableCriteria.match(target))
        val admission = CriteriaEvolution.admit(
            candidate(target, goodVerdicts()),
            signedAnchors(),
        )
        assertEquals(AdmissionState.REFUSED, admission.state)
        assertEquals(RefuseCode.IMMUTABLE_HIT, admission.refuseCode)
    }

    @Test
    fun `判据一_唯一例外是止损四件_多一件就不算例外`() {
        val emergency = "app/src/main/java/com/example/zhengdao/baopu/law/EmergencyGate#requiresApproval"
        val fourOnly = setOf("stop_machine", "quarantine", "rollback", "freeze_writes")
        val ok = CriteriaEvolution.admit(
            candidate(emergency, goodVerdicts(), exempts = fourOnly),
            signedAnchors(),
        )
        assertTrue("止损白名单例外必须放行（L30b 御批的那一件都没被误伤）", ok.admitted())

        val fifth = fourOnly + "delete_everything"
        val bad = CriteriaEvolution.admit(
            candidate(emergency, goodVerdicts(), exempts = fifth),
            signedAnchors(),
        )
        assertEquals("白名单是闭集：想再放一件进来＝改这张表＝用户立法", AdmissionState.REFUSED, bad.state)
    }

    // ── 判据②：锚定不一致 ⇒ 退回候选区 ＋ 附清单 ────────────────────────

    @Test
    fun `判据二_任一条不一致_退回并附不一致清单`() {
        val set = signedAnchors()
        val wrong = mapOf("A1" to "通过", "A2" to "通过", "A3" to "通过")
        val admission = CriteriaEvolution.admit(candidate(harmlessTarget, wrong), set)
        assertEquals(AdmissionState.RETURNED, admission.state)
        assertEquals(1, admission.mismatches.size)
        assertEquals("A2", admission.mismatches.first().anchorId)
        assertEquals("不通过", admission.mismatches.first().humanSide)
        assertFalse("退回不是准入", admission.admitted())
    }

    @Test
    fun `判据二_草案没覆盖到的样本也算不一致_无法判定不许当通过`() {
        val set = signedAnchors()
        val partial = mapOf("A1" to "通过", "A3" to "通过")
        val admission = CriteriaEvolution.admit(candidate(harmlessTarget, partial), set)
        assertEquals(AdmissionState.RETURNED, admission.state)
        assertEquals("无法判定", admission.mismatches.first().candidateSide)
    }

    @Test
    fun `判据二_锚定集未签字_一条都不放行`() {
        val unsigned = signedAnchors().map { it.copy(signedBy = "", signedAt = "") }
        assertEquals(AnchorStatus.UNSIGNED, AnchorSet.status(unsigned))
        val admission = CriteriaEvolution.admit(candidate(harmlessTarget, goodVerdicts()), unsigned)
        assertEquals(
            "未生效的锚定集不许产出「退回」——退回会伪造出一轮已核验",
            AdmissionState.REFUSED,
            admission.state,
        )
        assertEquals(RefuseCode.ANCHOR_NOT_IN_FORCE, admission.refuseCode)
    }

    @Test
    fun `判据二_全同侧才进御案`() {
        val set = signedAnchors()
        val admission = CriteriaEvolution.admit(candidate(harmlessTarget, goodVerdicts()), set)
        assertEquals(AdmissionState.ADMIT, admission.state)
        assertEquals("形态＋清单＋回放＋锚定四道全过", 4, admission.checks.size)
        assertTrue(admission.checks.all { it.passed })
    }

    @Test
    fun `判据二_没跑回放就想到锚定_顺序不许倒`() {
        val set = anchors("通过")
        val admission = CriteriaEvolution.admit(
            candidate(harmlessTarget, mapOf("A1" to "通过"), proof = null),
            set,
        )
        assertEquals(AdmissionState.REFUSED, admission.state)
        assertEquals(RefuseCode.REPLAY_MISSING, admission.refuseCode)
    }

    @Test
    fun `判据二_回放少拦一条_直接否决`() {
        val set = anchors("通过")
        val admission = CriteriaEvolution.admit(
            candidate(harmlessTarget, mapOf("A1" to "通过"), proof = replay(less = 1)),
            set,
        )
        assertEquals(RefuseCode.REPLAY_LESS_BLOCKED, admission.refuseCode)
    }

    // ── 判据③：纪内快照固定，不许热改 ─────────────────────────────────

    @Test
    fun `纪元以周一起算_同一周内的任何时刻都是同一纪`() {
        assertEquals(DayOfWeek.MONDAY, BASE_MONDAY.dayOfWeek)
        val start = EpochId.of(weekStart(0))
        val sunday = EpochId.of(midWeek(0, 6))
        assertEquals(start, sunday)
        assertEquals(start.ordinal, sunday.ordinal)
        assertEquals("跨到下一个周一必须换纪", start.ordinal + 1, EpochId.of(weekStart(1)).ordinal)
    }

    @Test
    fun `判据三_纪内换第二张表_热改被拒`() {
        val e0 = EpochId.of(weekStart(0))
        val first = CriteriaSnapshot.of(e0, 1, listOf(CriterionEntry("k1", "判据一", harmlessTarget, e0.ordinal)))
        assertTrue(EpochRuntime.swap(first, midWeek(0, 1)).swapped)

        val second = CriteriaSnapshot.of(e0, 2, listOf(CriterionEntry("k2", "判据二", harmlessTarget, e0.ordinal)))
        val hot = EpochRuntime.swap(second, midWeek(0, 3))
        assertFalse("纪内不许热改", hot.swapped)
        assertTrue(hot.reason.contains("热改"))
        assertEquals("被拒的那次不许把表换掉", 1L, EpochRuntime.currentSnapshot()?.seq)
    }

    @Test
    fun `判据三_边界到了一笔就换`() {
        val e0 = EpochId.of(weekStart(0))
        val e1 = EpochId.of(weekStart(1))
        EpochRuntime.swap(CriteriaSnapshot.of(e0, 1, listOf(CriterionEntry("k1", "判据一", harmlessTarget, 0))), midWeek(0, 2))
        val next = CriteriaSnapshot.of(e1, 2, listOf(CriterionEntry("k2", "判据二", harmlessTarget, 1)))
        assertTrue(EpochRuntime.swap(next, weekStart(1)).swapped)
        assertEquals(e1, EpochRuntime.currentSnapshot()?.epoch)
        assertEquals(e0, EpochRuntime.previousSnapshot()?.epoch)
    }

    @Test
    fun `负例_倒退着装表被拒_并指回回滚`() {
        val e0 = EpochId.of(weekStart(0))
        val e1 = EpochId.of(weekStart(1))
        EpochRuntime.swap(CriteriaSnapshot.of(e0, 1, listOf(CriterionEntry("k1", "判据一", harmlessTarget, 0))), weekStart(0))
        EpochRuntime.swap(CriteriaSnapshot.of(e1, 2, listOf(CriterionEntry("k2", "判据二", harmlessTarget, 1))), weekStart(1))

        val back = CriteriaSnapshot.of(e0, 3, listOf(CriterionEntry("k1", "判据一", harmlessTarget, 0)))
        val refused = EpochRuntime.swap(back, weekStart(1))
        assertFalse(refused.swapped)
        assertTrue("倒退必须被指去回滚，而不是走进化审批", refused.reason.contains("rollbackToPrevious"))
    }

    @Test
    fun `负例_回滚不是进化_纪中即时可用且不问审批`() {
        val e0 = EpochId.of(weekStart(0))
        val e1 = EpochId.of(weekStart(1))
        EpochRuntime.swap(CriteriaSnapshot.of(e0, 1, listOf(CriterionEntry("k1", "判据一", harmlessTarget, 0))), weekStart(0))
        EpochRuntime.swap(CriteriaSnapshot.of(e1, 2, listOf(CriterionEntry("k2", "判据二", harmlessTarget, 1))), weekStart(1))

        val before = EpochRuntime.rollbackLedger().size
        val rolled = EpochRuntime.rollbackToPrevious()
        assertTrue("严重缺陷的回滚不许等周一", rolled.rolledBack)
        assertEquals(e0, EpochRuntime.currentSnapshot()?.epoch)
        assertEquals(EpochState.ROLLED_BACK, EpochRuntime.ledgerState())
        // Q3（2026-10-31 用户裁定）：回滚零参数、不问审批，**但必须落一行账**。
        val ledger = EpochRuntime.rollbackLedger()
        assertEquals("回滚必须落一行账", before + 1, ledger.size)
        assertTrue("账行要能看出从哪退到哪", ledger.last().contains(e0.label()) && ledger.last().contains(e1.label()))
        // 结构性证据：回滚这条路上没有任何审批参数（有的话就该带 approved 之类的入参）。
        val arity = EpochRuntime::class.java.getDeclaredMethods().filter { it.name.startsWith("rollbackToPrevious") }
        assertEquals(1, arity.size)
        assertEquals("rollbackToPrevious 必须零入参——没有地方塞「审批」", 0, arity.first().parameterCount)
    }

    @Test
    fun `没有上一纪元时回滚不假装成功`() {
        assertFalse(EpochRuntime.rollbackToPrevious().rolledBack)
        EpochRuntime.swap(
            CriteriaSnapshot.of(EpochId.of(weekStart(0)), 1, listOf(CriterionEntry("k1", "判据一", harmlessTarget, 0))),
            weekStart(0),
        )
        assertFalse("只有一张表时无处可回", EpochRuntime.rollbackToPrevious().rolledBack)
    }

    // ── 判据④：纪元账每周一行、三态齐全 ────────────────────────────────

    @Test
    fun `判据四_纪元账一行五列_三态是闭集`() {
        val set = signedAnchors()
        val now = midWeek(0, 1)
        EpochRuntime.observe(CriteriaEvolution.admit(candidate(harmlessTarget, goodVerdicts()), set), now)
        EpochRuntime.observe(CriteriaEvolution.admit(candidate(harmlessTarget, mapOf("A1" to "通过")), set), now)
        EpochRuntime.observe(
            CriteriaEvolution.admit(candidate(harmlessTarget, goodVerdicts(), proof = null), set),
            now,
        )

        val row = EpochRuntime.ledgerRow(now)
        assertEquals(3, row.candidates)
        assertEquals(1, row.adopted)
        assertEquals(2, row.refused)
        assertEquals("五列，不加不减", 5, row.wire().split("|").size)
        assertTrue(row.consistent())
        assertEquals("三态是闭集，没有第四态的位置", 3, EpochState.entries.size)
    }

    @Test
    fun `判据四_每纪元一行_两周一行`() {
        val set = anchors("通过")
        val verdicts = mapOf("A1" to "通过")
        EpochRuntime.observe(CriteriaEvolution.admit(candidate(harmlessTarget, verdicts), set), midWeek(0, 1))
        EpochRuntime.observe(CriteriaEvolution.admit(candidate(harmlessTarget, verdicts), set), midWeek(1, 2))
        val rows = EpochRuntime.ledgerRows()
        assertEquals(2, rows.size)
        assertEquals(emptyList<Long>(), EpochLedger.duplicateEpochs(rows))
    }

    @Test
    fun `负例_计数越界的账行被账本读侧抓住`() {
        val forged = EpochLedgerRow(epoch = 1, candidates = 2, adopted = 2, refused = 1, state = EpochState.APPLIED)
        assertEquals(listOf(forged), EpochLedger.inconsistentRows(listOf(forged)))
    }

    @Test
    fun `显式无变更也要留行_分不清没改和忘了记就是漏账`() {
        EpochRuntime.markNoChange()
        val row = EpochRuntime.ledgerRow(midWeek(0, 1))
        assertEquals(EpochState.NO_CHANGE, row.state)
        assertEquals(0, row.candidates)
    }

    // ── 判据⑤：产物里没有标量分（结构层） ─────────────────────────────

    @Test
    fun `判据五_草案产物是标量分_直接拒`() {
        val admission = CriteriaEvolution.admit(
            candidate(harmlessTarget, goodVerdicts(), form = CriteriaOutputForm.SCALAR_SCORE),
            signedAnchors(),
        )
        assertEquals(AdmissionState.REFUSED, admission.state)
        assertEquals(RefuseCode.SCALAR_SCORE_FORM, admission.refuseCode)
    }

    @Test
    fun `判据五_准入结果的每个字段都是可读类型_没有Double也没有Float`() {
        listOf(CheckItem::class, AnchorMismatch::class, Admission::class, EpochLedgerRow::class).forEach { type ->
            val offenders = type.java.declaredFields
                .filter { it.type == Double::class.javaPrimitiveType || it.type == Float::class.javaPrimitiveType }
                .map { it.name }
            assertEquals("$type 里不许有浮点字段（分数不可审计）：$offenders", emptyList<String>(), offenders)
        }
    }

    @Test
    fun `负例_把必须人确认进化成可配置_意图里带开洞词也拦`() {
        val admission = CriteriaEvolution.admit(
            candidate(harmlessTarget, goodVerdicts(), intent = "把「必须人确认」改成可配置，让用户自己决定"),
            signedAnchors(),
        )
        assertEquals(AdmissionState.REFUSED, admission.state)
        assertEquals(RefuseCode.RELAXATION_INTENT, admission.refuseCode)
        assertNull(ImmutableCriteria.match(harmlessTarget))
    }

    // ── 快照自构造兜住「绕过清单」 ────────────────────────────────────

    @Test
    fun `负例_硬闸被装进新表_快照根本建不出来`() {
        val e0 = EpochId.of(weekStart(0))
        val tries = listOf(
            "app/src/main/java/com/example/zhengdao/baopu/verify/ArtifactVerifier.kt#check",
            "app/src/main/java/com/example/zhengdao/baopu/authz/TaskGrantSection.kt#stillAsk",
            "app/src/main/java/com/example/zhengdao/baopu/law/EmergencyGate#selfRulingRejection",
        )
        tries.forEach { target ->
            val ex = runCatching {
                CriteriaSnapshot.of(e0, 1, listOf(CriterionEntry("k", "判据", target, 0)))
            }.exceptionOrNull()
            assertNotNull("清单里的锚点装进新表必须建不出快照：$target", ex)
        }
    }

    @Test
    fun `快照序号与判据id的自校验`() {
        val e0 = EpochId.of(weekStart(0))
        assertNotNull(runCatching { CriteriaSnapshot.of(e0, 0, emptyList()) }.exceptionOrNull())
        val dup = runCatching {
            CriteriaSnapshot.of(
                e0,
                1,
                listOf(
                    CriterionEntry("k", "判据甲", harmlessTarget, 0),
                    CriterionEntry("k", "判据乙", harmlessTarget, 0),
                ),
            )
        }.exceptionOrNull()
        assertNotNull("同表里判据 id 重复必须建不出来", dup)
    }
}
