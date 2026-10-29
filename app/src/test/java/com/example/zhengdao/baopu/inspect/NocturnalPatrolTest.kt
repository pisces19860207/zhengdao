// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百七十九批 §1 的判据靶：④一键停、⑤双闸配额、⑥充电顺延、⑦零模型调用、⑫对账，
// 外加「条＝被检记录」与「留痕不含正文」两条定义。
package com.example.zhengdao.baopu.inspect

import com.example.zhengdao.baopu.DecisionActor
import com.example.zhengdao.baopu.DecisionGroup
import com.example.zhengdao.baopu.DecisionKind
import com.example.zhengdao.baopu.DecisionRecord
import java.io.File
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NocturnalPatrolTest {

    private val srcRoot = File("src/main/java/com/example/zhengdao/baopu/inspect")
    private val myFiles = listOf("NocturnalPatrol.kt", "GoldenGuard.kt", "PatrolMutual.kt")

    private val day = 24L * 3_600_000L

    private fun round(
        atMs: Long,
        bucket: Long,
        inspected: Int,
        emergency: Boolean = false,
        hits: Int = 0,
        noData: Int = 0,
    ) = NightRound(
        id = NocturnalPatrol.idOf(atMs),
        atMs = atMs,
        bucket = bucket,
        inspected = inspected,
        hits = hits,
        noData = noData,
        emergency = emergency,
    )

    private fun record(id: String, kind: String = PatrolRecord.KIND_DECISION) = PatrolRecord(id, kind)

    private fun row(id: String, atMs: Long = 10L, tool: String = "file_write") = DecisionRecord(
        id = id,
        kind = DecisionKind.AUTO_ACT,
        group = DecisionGroup.GRANT,
        actor = DecisionActor.MODEL,
        reason = "免问直行：$tool",
        evidence = "tool:$tool:a.txt",
        before = "",
        after = "",
        atMs = atMs,
        sessionId = -1L,
        reversible = true,
        rollbackRef = "",
    )

    private fun finding(
        check: InspectCheck = InspectCheck.AUDIT_GAP,
        verdict: InspectVerdict = InspectVerdict.HIT,
        evidence: List<String> = listOf("decision:1"),
        count: Int = 1,
    ) = InspectFinding(
        check = check,
        verdict = verdict,
        oneLine = "一句话",
        suggestion = "一句建议",
        evidence = evidence,
        count = count,
    )

    // ── 判据④：一键停 ───────────────────────────────────────────────────────

    @Test
    fun `判据④_一键停能停掉暗卫`() {
        val gate = NocturnalPatrol.gate(nowMs = 0L, bucket = 1L, rounds = emptyList(), charging = true, stopped = true)
        assertFalse(gate.run)
        assertTrue(gate.why.contains("一键停"))
    }

    @Test
    fun `负例_停不掉灾异通道等于没停`() {
        val gate = NocturnalPatrol.gate(
            nowMs = 0L,
            bucket = 1L,
            rounds = emptyList(),
            charging = true,
            stopped = true,
            emergency = true,
        )
        assertFalse("一键停必须连灾异通道一起停", gate.run)
    }

    // ── 判据⑤：双闸配额（周 30 硬总闸 ＋ 每晚 5 单次闸）─────────────────────

    @Test
    fun `判据⑤_六晚各五条用满三十_第七晚没额度`() {
        val rounds = mutableListOf<NightRound>()
        var now = 0L
        repeat(6) { i ->
            val gate = NocturnalPatrol.gate(now, i.toLong(), rounds, charging = true)
            assertTrue("第 ${i + 1} 晚该跑：${gate.why}", gate.run)
            assertEquals(5, gate.allowance)
            rounds += round(now, i.toLong(), gate.allowance)
            now += day
        }
        val seventh = NocturnalPatrol.gate(now, 6L, rounds, charging = true)
        assertFalse("第 7 晚额度已满，必须拒：${seventh.why}", seventh.run)
        assertEquals(30, rounds.sumOf { it.inspected })
    }

    @Test
    fun `判据⑤_任一轮不超过五条`() {
        val pool = (1..100).map { record("decision:$it") }
        repeat(6) { seed ->
            val planned = NocturnalPatrol.plan(pool, NocturnalLimits.NIGHTLY_MAX, Random(seed.toLong()))
            assertTrue("一轮最多 5 条，实际 ${planned.size}", planned.size <= 5)
        }
        assertEquals(0, NocturnalPatrol.plan(pool, 0, Random(0)).size)
    }

    @Test
    fun `负例_无上限狂跑`() {
        val used = listOf(round(0L, 1L, 5))
        val again = NocturnalPatrol.gate(0L, 1L, used, charging = true)
        assertFalse("同一晚已经 5 条还跑＝无单次闸", again.run)
        // 额度只减不增：放行时也永远 ≤5
        val fresh = NocturnalPatrol.gate(day, 2L, listOf(round(0L, 1L, 3)), charging = true)
        assertTrue(fresh.allowance in 1..NocturnalLimits.NIGHTLY_MAX)
    }

    @Test
    fun `条是被检记录不是Opinion条数`() {
        val round = NocturnalPatrol.roundOf(
            atMs = 10L,
            bucket = 1L,
            inspected = (1..5).map { record("decision:$it") },
            findings = listOf(finding(count = 2), finding(verdict = InspectVerdict.CLEAR)),
        )
        assertEquals("额度按被检记录数扣", 5, round.inspected)
        assertEquals("命中数只是读数，不参与额度", 1, round.hits)
        assertEquals(5, NocturnalPatrol.windowUsed(listOf(round), 10L))
        // 负例的另一半：一条都没命中也照样吃掉 5 条额度——「少报」不该换来「少抽」
        val quiet = NocturnalPatrol.roundOf(10L, 1L, (1..5).map { record("decision:$it") }, emptyList())
        assertEquals(5, NocturnalPatrol.windowUsed(listOf(quiet), 10L))
    }

    // ── 裁定②（2026-10-29）：抽到的与判据看到的是同一批 ─────────────────────

    @Test
    fun `判据⑫_池子建在判据读的同一份输入上`() {
        val ledger = listOf(row("r1", atMs = 10L))
        val acts = listOf(WriteTrace(11L, "file_write", sessionId = 7L, step = 3))
        val pool = NocturnalPatrol.poolOf(ledger, acts)
        assertEquals(listOf("trace:7#3", "decision:r1"), pool.map { it.record.id })
        assertEquals(PatrolRecord.KIND_TRACE, pool[0].record.kind)
        assertEquals(11L, pool[0].atMs)
        assertEquals(PatrolRecord.KIND_DECISION, pool[1].record.kind)
        assertEquals("decision:r1", pool[1].key)
    }

    @Test
    fun `判据⑫_抽中的五条原样投回判据输入`() {
        val ledger = (1..8).map { row("r$it", atMs = it.toLong()) }
        val acts = (1..8).map { WriteTrace(it.toLong(), "file_write", 7L, it) }
        val pool = NocturnalPatrol.poolOf(ledger, acts)
        val picked = NocturnalPatrol.pick(pool, 5, Random(3))
        assertEquals(5, picked.size)
        val scoped = NocturnalPatrol.scopeTo(picked, ledger, acts)
        assertTrue("抽到的每一条都该在判据输入里：${scoped.coverage.missing}", scoped.coverage.reconciled)
        assertEquals(5, scoped.coverage.found)
        // 投回去的是判据那份列表里的**同一批对象**，不是另造的副本
        val scopedKeys = scoped.acts.map { NocturnalPatrol.keyOf(it) } + scoped.rows.map { NocturnalPatrol.keyOf(it) }
        assertEquals(picked.map { it.key }.toSet(), scopedKeys.toSet())
        scoped.acts.forEach { a -> assertTrue("痕迹必须取自判据读的那份列表", acts.any { it === a }) }
        scoped.rows.forEach { r -> assertTrue("账行必须取自判据读的那份账", ledger.any { it === r }) }
    }

    @Test
    fun `负例_池子另抓一份就对不上`() {
        val acts = (1..5).map { WriteTrace(it.toLong(), "file_write", 7L, it) }
        val other = (6..10).map { WriteTrace(it.toLong(), "file_write", 8L, it) }
        val scoped = NocturnalPatrol.scopeTo(NocturnalPatrol.poolOf(emptyList(), acts), emptyList(), other)
        assertFalse("另抓一份还照样「对得上」＝对账是假的", scoped.coverage.reconciled)
        assertEquals(5, scoped.coverage.missing.size)
        assertTrue(scoped.acts.isEmpty())
    }

    @Test
    fun `负例_账本也缩到抽样那份会造出假漏行`() {
        val acts = listOf(WriteTrace(10L, "file_write", 7L, 3))
        val ledger = listOf(row("r1", atMs = 10L), row("r2", atMs = 10L, tool = "dir_delete"))
        val picked = NocturnalPatrol.poolOf(ledger, acts)
            .filter { it.record.id == "trace:7#3" || it.record.id == "decision:r2" }
        val scoped = NocturnalPatrol.scopeTo(picked, ledger, acts)
        assertEquals("对手给全量：清白", InspectVerdict.CLEAR, AuditGapCheck.check(scoped.acts, ledger).verdict)
        assertEquals("对手也缩到抽样那份：假漏行", InspectVerdict.HIT, AuditGapCheck.check(scoped.acts, scoped.rows).verdict)
    }

    // ── 判据⑥：非充电顺延，不叠加 ───────────────────────────────────────────

    @Test
    fun `判据⑥_非充电不跑且顺延不叠加`() {
        val pool = (1..100).map { record("decision:$it") }
        val night1 = NocturnalPatrol.gate(0L, 1L, emptyList(), charging = false)
        assertFalse(night1.run)
        assertTrue(night1.why.contains("顺延"))
        // 头一晚没跑 ⇒ 第二天仍只有 5 条的额度（没叠加成 10）
        val night2 = NocturnalPatrol.gate(day, 2L, emptyList(), charging = true)
        assertEquals(5, night2.allowance)
        assertEquals(5, NocturnalPatrol.plan(pool, night2.allowance, Random(0)).size)
    }

    // ── 判据③：CRITICAL 不占额度 ────────────────────────────────────────────

    @Test
    fun `CRITICAL不占额度`() {
        val emergency = round(0L, 1L, inspected = 5, emergency = true)
        assertEquals("灾异那趟不进周额度", 0, NocturnalPatrol.windowUsed(listOf(emergency), 0L))
        assertEquals(0, NocturnalPatrol.tonightUsed(listOf(emergency), 1L))
        // 额度用尽时灾异通道仍开
        val full = (0..5).map { round(it * day, it.toLong(), 5) }
        val gate = NocturnalPatrol.gate(6 * day, 6L, full, charging = false, emergency = true)
        assertTrue("额度满也不能漏掉灾异：${gate.why}", gate.run)
        // 但账上照实记抽了几条（不靠把数字改成 0 来"不占额度"）
        assertEquals(5, emergency.inspected)
    }

    @Test
    fun `随机起点真的换`() {
        val pool = (1..100).map { record("decision:$it") }
        val starts = (0L..9L).map { seed ->
            NocturnalPatrol.plan(pool, 5, Random(seed)).first().id
        }
        assertTrue("十个种子里起点全是同一条，等于没有随机起点", starts.distinct().size > 1)
        val picked = NocturnalPatrol.plan(pool, 5, Random(7))
        assertEquals("一轮里不许重复", picked.size, picked.distinctBy { it.id }.size)
    }

    // ── 判据②：留痕不含正文 ─────────────────────────────────────────────────

    @Test
    fun `抽检记录不含正文`() {
        val long = "decision:" + "x".repeat(400)
        val round = NocturnalPatrol.roundOf(5L, 1L, listOf(record(long)), listOf(finding(evidence = listOf(long))))
        assertTrue("证据引用要钳长", round.evidence.all { it.length <= NocturnalLimits.MAX_EVIDENCE_CHARS })
        val json = NocturnalLedger.toJson(round)
        listOf("text", "body", "content", "prompt", "answer").forEach { key ->
            assertFalse("台账里出现了正文键 $key", json.has(key))
        }
        val back = NocturnalLedger.parse(json.toString())
        assertNotNull(back)
        assertEquals(round, back)
    }

    @Test
    fun `留痕_一封坏行跳过不装成一条`() {
        assertNull(NocturnalLedger.parse("{\"at\":1}"))
        assertNull(NocturnalLedger.parse("not json"))
    }

    // ── 只出意见不打扰 ──────────────────────────────────────────────────────

    @Test
    fun `意见只到建议级_严重度留给御批`() {
        val opinion = PatrolOpinions.of(finding(count = 3))
        assertNotNull(opinion)
        assertEquals(Severity.MINOR, opinion!!.severity)
        assertEquals(LawRef.NONE, opinion.lawRef)
        assertEquals(Source.PATROL, opinion.source)
        assertTrue("非 CRITICAL 一律只是建议", opinion.suggestionOnly)
        assertTrue("gist 超过 8 字会被 Opinion 拒", opinion.gist.codePointCount(0, opinion.gist.length) <= 8)
    }

    @Test
    fun `负例_没证据的命中不出意见`() {
        assertNull("L14：没有证据的结论不许出", PatrolOpinions.of(finding(evidence = emptyList())))
        assertNull("清白不是意见", PatrolOpinions.of(finding(verdict = InspectVerdict.CLEAR)))
        assertNull("查不了不是意见", PatrolOpinions.of(finding(verdict = InspectVerdict.NO_DATA)))
    }

    @Test
    fun `暗卫结果只有两条出路_都不进界面`() {
        assertEquals(2, PatrolDelivery.entries.size)
        val critical = OpinionAdapter.PATROL.adapt(
            severity = Severity.CRITICAL,
            gist = "影子不符",
            action = ActionCode.PROPOSE_REVISION,
            lawRef = LawRef.L21,
            evidence = listOf("trace:r1#2"),
        )
        assertEquals(PatrolDelivery.EMERGENCY, NocturnalPatrol.deliveryOf(critical))
        assertEquals(PatrolDelivery.QUEUE, NocturnalPatrol.deliveryOf(PatrolOpinions.of(finding())!!))
    }

    // ── 判据⑦：零模型调用（源码门禁）─────────────────────────────────────────

    @Test
    fun `判据⑦_抽检路径零模型调用`() {
        val banned = listOf(
            "LlmClient", "prompt", "Prompt", "messages", "Messages", "chat(", "completions",
            "OpenAi", "Gemini", "HttpURLConnection", "OkHttp", "apiKey", "generateContent",
            "com.example.zhengdao.baopu.agent.",
        )
        myFiles.forEach { name ->
            val text = code(File(srcRoot, name))
            banned.forEach { word ->
                assertFalse("$name 里出现了 $word（暗卫抽检零模型调用是硬前提）", text.contains(word))
            }
        }
    }

    @Test
    fun `负例_暗卫不许改业务状态也不许碰界面`() {
        val banned = listOf(
            "writeText", "writeBytes", "FileOutputStream", "execSQL", "INSERT INTO", "UPDATE ",
            "DELETE FROM", "DecisionLedger.record(", "DecisionLedger.install(", "SkillStore.",
            "import com.example.zhengdao.ui.", "AlertDialog", "Toast", "Snackbar",
        )
        myFiles.forEach { name ->
            val text = code(File(srcRoot, name))
            banned.forEach { word ->
                assertFalse("$name 里出现了 $word（暗卫只查不改、不进界面）", text.contains(word))
            }
        }
    }

    /** 注释里写「不许 writeText」是说明，不是调用（照 239 的同一把尺）。 */
    private fun code(f: File): String = f.readText(Charsets.UTF_8)
        .replace(Regex("""/\*[\s\S]*?\*/"""), " ")
        .replace(Regex("(?m)//.*$"), " ")
}
