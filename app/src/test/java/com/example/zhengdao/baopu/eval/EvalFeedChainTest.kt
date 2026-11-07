// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第四百九十五批·残留（分支 `fix/eval-feed`）——「评测读数 → 决策口」这条链的**跨层取证**。
//
// 为什么是补取证而不是重写：主链在第五百零七批已经接上（`EvolutionInputs.triggerEvalOf`
// 汇总读数、`EvalEvidence.selectEvalEvidence` 的第三来源、控制器
// `BaopuChatController.selectEvalEvidenceForJudge` 真读账本、判定时 `eval = evalResult`）。
// 但取证是断的：账本层被钉住的是 `encode/parse` 往返（EvalTriggerEngineTest），决策口层
// 被钉住的是**内存里现造的行**（TriggerEvalEvidenceTest）——"盘上的那一行"到"决策口的
// eval 槽"这一跳，此前没有一条用例走过；`RUN_FAILED` 类型的账行在决策口用例里也从没被
// 造过（那边一律用 MANUAL）。
//
// 变异实证（2026-11-07 本机）：把 `TriggerRowCodec.parse` 的 `evalPassRate` 读成 null
// ⇒ `EvalTriggerEngineTest` 1 例红、**决策口用例全绿**。即这条缝当时无人看守。本文件
// 就是把这条缝钉上的钉子：判据① 走"真引擎 → 真账本 → 真读回 → 真决策"，判据② 走
// 账本空（文件都不存在）那条路，判据③ 用反射锁结构（不许长新字段）。
package com.example.zhengdao.baopu.eval

import com.example.zhengdao.baopu.EvolutionEvidence
import com.example.zhengdao.baopu.EvolutionEval
import com.example.zhengdao.baopu.EvolutionPolicy
import com.example.zhengdao.baopu.EvolutionReasonCode
import com.example.zhengdao.baopu.EvolutionRecommendation
import com.example.zhengdao.baopu.ProposalCandidate
import com.example.zhengdao.baopu.ProposalEvidence
import com.example.zhengdao.baopu.ProposalKind
import com.example.zhengdao.baopu.SelfCheckEvidence
import com.example.zhengdao.baopu.evolution.trigger.EvalTriggerEngine
import com.example.zhengdao.baopu.evolution.trigger.EvalTriggerInputs
import com.example.zhengdao.baopu.evolution.trigger.EvalTriggerOutcome
import com.example.zhengdao.baopu.evolution.trigger.TriggerEvalReading
import com.example.zhengdao.baopu.evolution.trigger.TriggerLedgerStore
import com.example.zhengdao.baopu.evolution.trigger.TriggerRow
import com.example.zhengdao.baopu.evolution.trigger.TriggerRunRow
import com.example.zhengdao.baopu.evolution.trigger.TriggerType
import com.example.zhengdao.baopu.inputFor
import com.example.zhengdao.baopu.triggerEvalOf
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EvalFeedChainTest {

    private val temp: File = File.createTempFile("zd495f", "").let { it.delete(); it.mkdirs(); it }
    private val ledgerFile: File = File(temp, "evolution/trigger-ledger.jsonl")
    private val store = TriggerLedgerStore(ledgerFile)

    /**
     * 与生产同构的引擎（`EvalTriggerWiring.engine` 的 JVM 版）：读/写都指向**真账本**，
     * 只有评测与路由换假件——本文件要验的是"账本↔决策口"那一跳，不是评测跑得对不对。
     */
    private fun engine(now: Long, ran: Int, passed: Int) = EvalTriggerEngine(
        readRows = { store.read() },
        appendRow = { store.append(it) },
        runEval = {
            TriggerEvalReading(
                ran = ran,
                passed = passed,
                total = ran,
                passRate = passed.toDouble() / ran.toDouble(),
                failedCases = if (passed < ran) listOf("evo-token-budget") else emptyList(),
                elapsedMs = 3,
            )
        },
        recordUnfinished = { _, _, _ -> "unfinished:test" },
        recordProposal = { _, _, _ -> "" },
        now = { now },
        log = {},
    )

    /** 一条终态 FAILED 的会话（触发条件①；不靠回滚、不靠纠错、不靠成功率）。 */
    private fun failedInputs() = EvalTriggerInputs(
        nowMs = 0L,
        sessionId = 1L,
        runs = listOf(TriggerRunRow("r-1", 1L, 0L, 100L, listOf("OK", "FAILED"))),
        rollbackAtMs = emptyList(),
        sessionSkillRows = emptyList(),
        windowSkillRows = emptyList(),
        intent = "整理笔记",
    )

    private fun candidate() = ProposalCandidate(
        id = "c-feed",
        kind = ProposalKind.SKILL_BODY,
        target = "做卡流程",
        payload = "# 做卡流程\n\n正文。",
        evidence = ProposalEvidence(atMs = 1L),
        createdAt = 1L,
    )

    private val selfCheckOk =
        SelfCheckEvidence(passed = true, rollbackAvailable = true, caseIds = listOf("selfcheck:structure"))

    /** 决策口的完整一跳：证据包 → 桥 → policy（生产走的是同一对函数）。 */
    private fun judge(eval: EvolutionEval?) =
        EvolutionPolicy().judge(inputFor(candidate(), EvolutionEvidence(selfCheck = selfCheckOk, eval = eval)))

    // ── 判据① 造一条 FAILED 触发 ⇒ 账本有行 ⇒ 决策口 eval 看得到该读数 ──────

    @Test
    fun `判据①_FAILED触发落账_盘上读回后决策口eval看得到该读数`() {
        val outcome = engine(now = 10_000L, ran = 13, passed = 5).onRoundSettled(failedInputs()).single()

        // 落的是①类触发（不是内存里手造的 MANUAL 行）
        val triggered = outcome as EvalTriggerOutcome.Triggered
        assertEquals(TriggerType.RUN_FAILED, triggered.row.type)

        // 账本真有行——从盘上读回，不拿刚才那个内存对象充数
        val rows = store.read()
        assertEquals(1, rows.size)
        assertEquals(TriggerType.RUN_FAILED, rows[0].type)
        assertTrue(rows[0].triggered)
        assertEquals(13, rows[0].evalRan)
        assertEquals(5, rows[0].evalPassed)

        // 盘上读回的读数正是决策口拿到的那份（往返没丢：变异 parse 会在这里红）
        val trigger = triggerEvalOf(rows)!!
        assertEquals(10_000L, trigger.ranAt) // 时间窗右沿＝这一行的时刻
        assertEquals(5.0 / 13.0, trigger.score, 1e-9) // run/passed 求和，不是估算
        assertEquals(EvalEvidenceSource.TRIGGER, trigger.source) // 来源标得住，卡上才认得出路
        assertTrue("失败场景要进证据引用", trigger.caseIds.contains("evo-token-budget"))

        // 生产那一步：第三来源参与选择（随包发版证据在也不许抢本机读数）
        val release = EvolutionEval(0.95, source = EvalEvidenceSource.RELEASE, ranAt = null)
        val eval = selectEvalEvidence(prod = null, release = release, now = 10_000L, trigger = trigger)!!
        assertEquals("本机读数比随包快照新，必须赢", EvalEvidenceSource.TRIGGER, eval.source)
        assertEquals(5.0 / 13.0, eval.score, 1e-9)

        // 决策口：读数未过线 ⇒ REJECT，且原因链点名 EVAL_BELOW_FLOOR
        val judgement = judge(eval)
        assertEquals(EvolutionRecommendation.REJECT, judgement.recommendation)
        val floor = judgement.reasons.first { it.code == EvolutionReasonCode.EVAL_BELOW_FLOOR }
        assertTrue("场景 id 要跟着原因走", floor.evidenceIds.contains("evo-token-budget"))
    }

    @Test
    fun `判据①_同一条链换个读数_结论跟着翻（不是恒红也不是恒绿）`() {
        // 负例靶子：若上面那条只因为"有 eval 就 REJECT"，这条会红。
        val outcome = engine(now = 10_000L, ran = 13, passed = 13).onRoundSettled(failedInputs()).single()
        assertEquals(TriggerType.RUN_FAILED, (outcome as EvalTriggerOutcome.Triggered).row.type)

        val eval = selectEvalEvidence(prod = null, release = null, now = 10_000L, trigger = triggerEvalOf(store.read()))!!
        assertEquals(1.0, eval.score, 1e-9)

        val judgement = judge(eval)
        // 读数这一关放行后，卡的是效果样本不足（本用例没喂效果计数）——关键是**不判 REJECT**
        assertNotEquals(EvolutionRecommendation.REJECT, judgement.recommendation)
        assertEquals(EvolutionRecommendation.DEFER, judgement.recommendation)
        assertFalse(
            "读数全过时不许再报未过线",
            judgement.reasons.any { it.code == EvolutionReasonCode.EVAL_BELOW_FLOOR },
        )
        assertFalse(
            "有读数就不许报『没评』",
            judgement.reasons.any { it.code == EvolutionReasonCode.EVAL_NOT_RUN },
        )
        assertTrue(
            "走到效果那一格 ⇒ eval 那一关真被走过了（不是短路在别处）",
            judgement.reasons.any { it.code == EvolutionReasonCode.EFFECT_INSUFFICIENT },
        )
    }

    // ── 判据② 账本为空 ⇒ eval 为空 ⇒ 结论 UNVERIFIABLE（不得当通过） ────────

    @Test
    fun `判据②_账本文件不存在_eval为空_判DEFER不是通过`() {
        // 真实空态：连文件都没有（不是"内存里传了个空列表"）
        assertFalse(ledgerFile.exists())
        val rows = store.read()
        assertTrue(rows.isEmpty())
        assertNull(triggerEvalOf(rows))

        // 取数层：没有读数 ⇒ 第三来源为空
        val eval = selectEvalEvidence(prod = null, release = null, now = 10_000L, trigger = triggerEvalOf(rows))
        assertNull("没有读数就不许编一份分数", eval)

        // 决策口：UNVERIFIABLE 的落点是 DEFER（不是 ADOPT，也不是硬判 REJECT）
        val judgement = judge(eval)
        assertEquals(EvolutionRecommendation.DEFER, judgement.recommendation)
        assertTrue(
            "原因链要说清是『没评』而不是『评了没过』",
            judgement.reasons.any { it.code == EvolutionReasonCode.EVAL_NOT_RUN },
        )
        assertFalse(judgement.reasons.any { it.code == EvolutionReasonCode.EVAL_BELOW_FLOOR })
    }

    @Test
    fun `判据②附_账本空但有发版证据时_用的是发版证据而非没法验`() {
        // 防误读：判据② 说的是"账本空且没有别的证据"。168 的次序里 release 是兜底，
        // 账本空不等于"这台设备永远没法验"——真那样写，卡片会把发版证据也说成没有。
        val release = EvolutionEval(0.9, source = EvalEvidenceSource.RELEASE, ranAt = null)
        val eval = selectEvalEvidence(prod = null, release = release, now = 10_000L, trigger = triggerEvalOf(store.read()))
        assertEquals(release, eval)
        assertEquals("来源必须是发版证据，不许冒充本机读数", EvalEvidenceSource.RELEASE, eval!!.source)
        assertFalse(judge(eval).reasons.any { it.code == EvolutionReasonCode.EVAL_NOT_RUN })
    }

    // ── 判据③ 不新增字段（结构断言；加字段就红，不靠注释自觉） ─────────────

    @Test
    fun `判据③_证据结构没长新字段`() {
        // `$stable` 是 Kotlin/Compose 编译器插的合成字段，不是业务字段（按名字前缀排掉）
        fun fieldNames(k: Class<*>) = k.declaredFields
            .filterNot { it.isSynthetic || it.name.startsWith("\$") }
            .map { it.name }
            .toSet()

        // eval 就落在既有的那一格里（507 明确不许为触发读数另开字段）
        assertEquals(
            "EvolutionEvidence 只许这四格：触发读数进 eval，不另开字段",
            setOf("effect", "effectSignalIds", "selfCheck", "eval"),
            fieldNames(EvolutionEvidence::class.java),
        )
        // 读数形状与发版/个人生产证据同形：分数＋场景 id＋来源＋时刻
        assertEquals(
            "EvolutionEval 只许这四格：run/passed 之和折算成 score，不另存计数",
            setOf("score", "caseIds", "source", "ranAt"),
            fieldNames(EvolutionEval::class.java),
        )
        // 账本一行仍是 304 的字段面（本批不借机扩账）
        assertEquals(
            "TriggerRow 字段面不许动（304 的账要能读老数据）",
            setOf(
                "atMs", "type", "triggered", "ref", "intent",
                "evalRan", "evalPassed", "evalTotal", "evalPassRate",
                "evalFailedCases", "evalError", "route", "suppressReason",
            ),
            fieldNames(TriggerRow::class.java),
        )
    }
}
