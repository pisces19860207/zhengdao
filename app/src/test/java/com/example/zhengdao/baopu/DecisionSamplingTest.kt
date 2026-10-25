// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百零八批 §2.7：抽检命中、配额、结论与回归样本的判据。
//   ① 真删 100%、正式退役 100%；草稿软退役与日记维护每周 ≤5 条配额（超了排队下周）；
//   ② 判过的离开待抽检组，但**仍占配额**（不然删一条就能无限抽）；
//   ③ 「判错了」⇒ 一行写全动作摘要＋matrixVersion＋reasonCode，219 的测试读得到。
// 负例（各红）：真删没进待抽检／抽检超配额／判错了没生成回归样本／
//              待抽检里混进可逆的常规动作（转正、批量删这种）。
package com.example.zhengdao.baopu

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class DecisionSamplingTest {

    private lateinit var root: File

    /** 2026-10-26（周一）10:00 本地时间附近取一个基准刻。 */
    private val baseMs = java.time.LocalDate.of(2026, 10, 26).atTime(10, 0)
        .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Before
    fun setUp() {
        root = File.createTempFile("zd208-sample", "").let { it.delete(); it.mkdirs(); it }
        DecisionReview.matrixVersion = DecisionReview.UNVERSIONED
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
        DecisionReview.matrixVersion = DecisionReview.UNVERSIONED
    }

    private fun rec(
        id: String,
        kind: DecisionKind,
        atMs: Long,
    ) = DecisionLedger.build(
        DecisionDraft(
            kind = kind,
            actor = DecisionActor.AUTO,
            reason = "$id 的动作",
            evidence = "ev:$id",
            before = "a",
            after = "b",
            atMs = atMs,
            rollbackRef = "/x/$id",
        ),
    )!!.copy(id = id)

    // ── ① 命中档 ──────────────────────────────────────────────────────────

    @Test
    fun `S1_真删与正式退役100%抽_其余不抽`() {
        val records = listOf(
            rec("p1", DecisionKind.DIARY_PURGE, baseMs),
            rec("p2", DecisionKind.DIARY_PURGE, baseMs - 1_000L),
            rec("r1", DecisionKind.SKILL_RETIRE, baseMs - 2_000L),
            rec("r2", DecisionKind.SKILL_RETIRE, baseMs - 3_000L),
            rec("promote", DecisionKind.SKILL_PROMOTE, baseMs - 4_000L),
            rec("del", DecisionKind.ARTIFACT_DELETE, baseMs - 5_000L),
            rec("eval", DecisionKind.EVAL_VERDICT, baseMs - 6_000L),
        )
        val sampled = DecisionSampling.sampledIds(records)
        assertTrue("真删必抽", "p1" in sampled && "p2" in sampled)
        assertTrue("正式退役必抽", "r1" in sampled && "r2" in sampled)
        assertFalse("负例：待抽检里不许混进可逆的常规动作（转正）", "promote" in sampled)
        assertFalse("批量删（可回滚）不抽", "del" in sampled)
        assertFalse("评测结论只留档，不抽", "eval" in sampled)
    }

    @Test
    fun `S2_每周配额5条_同一周第6条排队下周`() {
        val week = baseMs
        val records = (1..7).map { i ->
            rec("d$i", DecisionKind.SKILL_DRAFT_RETIRE, week + i * 1_000L)
        }
        val sampled = DecisionSampling.sampledIds(records)
        assertEquals("一周最多抽 5 条", DecisionSampling.WEEKLY_QUOTA, sampled.size)
        assertTrue("抽的是最早的 5 条（排队下周的是后 2 条）", sampled == setOf("d1", "d2", "d3", "d4", "d5"))
    }

    @Test
    fun `S3_下一周配额重新算`() {
        val records = (1..7).map { i -> rec("w1-$i", DecisionKind.DIARY_MAINTAIN, baseMs + i * 1_000L) } +
            (1..7).map { i -> rec("w2-$i", DecisionKind.DIARY_MAINTAIN, baseMs + 7L * 86_400_000L + i * 1_000L) }
        val sampled = DecisionSampling.sampledIds(records)
        assertEquals("两周各 5 条", DecisionSampling.WEEKLY_QUOTA * 2, sampled.size)
    }

    // ── ② 判过的离开待抽检，但配额不退 ───────────────────────────────────

    @Test
    fun `S4_判过的不再出现在待抽检_但配额仍被占着`() {
        val records = (1..7).map { i ->
            rec("d$i", DecisionKind.SKILL_DRAFT_RETIRE, baseMs + i * 1_000L)
        }
        val first5 = setOf("d1", "d2", "d3", "d4", "d5")
        assertEquals("没判之前 5 条", 5, DecisionSampling.pending(records).size)

        val pendingAfterJudge = DecisionSampling.pending(records, judged = setOf("d1", "d2"))
        assertEquals("判过两条就少两条", 3, pendingAfterJudge.size)
        assertTrue("判过的不许再冒出来", pendingAfterJudge.none { it.id in setOf("d1", "d2") })
        assertTrue("第 6 条不许因为判过而递补", pendingAfterJudge.none { it.id == "d6" })
    }

    // ── ③ 判错了 ⇒ 回归样本 ───────────────────────────────────────────────

    @Test
    fun `S5_负例_判错了必须生成能读到的回归样本_带矩阵版本与原因码`() {
        val target = rec("purge-1", DecisionKind.DIARY_PURGE, baseMs)
        DecisionReview.matrixVersion = "m7"
        val entry = DecisionReview.entryOf(
            target,
            DecisionRecord.VERDICT_WRONG,
            atMs = baseMs + 5_000L,
            matrixVersion = DecisionReview.matrixVersion,
        )
        assertTrue(DecisionReview.append(root, entry))

        val samples = DecisionReview.regressionSamples(DecisionReview.read(root))
        assertEquals(1, samples.size)
        val s = samples.single()
        assertEquals("m7", s.matrixVersion)
        assertEquals("判错的原因码＝kind 线值（稳定键）", "diary_purge", s.reasonCode)
        assertEquals(target.id, s.recordId)
        assertTrue("动作摘要要能读", s.actionSummary.contains(target.reason))
        assertTrue("证据 id 跟着走", s.evidence.isNotBlank())
    }

    @Test
    fun `S6_确认无误不是回归样本_但离开待抽检`() {
        val target = rec("purge-2", DecisionKind.DIARY_PURGE, baseMs)
        assertTrue(
            DecisionReview.append(
                root,
                DecisionReview.entryOf(target, DecisionRecord.VERDICT_CONFIRMED, baseMs + 1_000L),
            ),
        )
        val entries = DecisionReview.read(root)
        assertTrue("没有回归样本", DecisionReview.regressionSamples(entries).isEmpty())
        assertTrue("但算判过了", target.id in DecisionReview.judgedIds(entries))
    }

    @Test
    fun `S7_判了又反悔_按最后一条算_历史行不覆盖`() {
        val target = rec("purge-3", DecisionKind.DIARY_PURGE, baseMs)
        DecisionReview.append(root, DecisionReview.entryOf(target, DecisionRecord.VERDICT_WRONG, baseMs + 1_000L))
        DecisionReview.append(root, DecisionReview.entryOf(target, DecisionRecord.VERDICT_CONFIRMED, baseMs + 2_000L))
        val entries = DecisionReview.read(root)
        assertEquals("两行都在（只追加）", 2, entries.size)
        assertTrue("按最后一条算：不是样本了", DecisionReview.regressionSamples(entries).isEmpty())
    }

    @Test
    fun `S8_坏行与非法结论一律当没写过`() {
        val target = rec("purge-4", DecisionKind.DIARY_PURGE, baseMs)
        DecisionReview.append(root, DecisionReview.entryOf(target, DecisionRecord.VERDICT_WRONG, baseMs))
        File(root, DecisionReview.FILE_NAME).appendText(
            """{"record_id":"x","verdict":"maybe","at":1}""" + "\n" + "{坏行}\n",
            Charsets.UTF_8,
        )
        assertEquals("只剩那条合法的", 1, DecisionReview.read(root).size)
    }

    @Test
    fun `S9_抽检结论文件与决策记录是两份不同用途的账`() {
        DecisionLedger.recordTo(
            root,
            DecisionDraft(
                kind = DecisionKind.DIARY_PURGE,
                actor = DecisionActor.USER,
                reason = "日记真删",
                evidence = "diary:1",
                before = "1 条",
                after = "0 条",
                atMs = baseMs,
            ),
        )
        assertTrue(DecisionLedger.fileIn(root).isFile)
        assertFalse("结论还没写时不许凭空造文件", File(root, DecisionReview.FILE_NAME).isFile)
    }
}
