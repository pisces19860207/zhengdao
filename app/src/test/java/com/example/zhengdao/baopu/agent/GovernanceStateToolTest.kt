// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百二十七批 · 治理出口（governance_state）的验收用例。
//
// 中心是对拍（527 判据：脚本与出口逐项相等）：「脚本」是本文件里的 [script]——
// 它**不经过**生产取数路（DecisionLedger.read／AskGovLedger.replay／currentCanaryBand…），
// 只拿账本**原文**（jsonl 行 / md 表 / 身份 json）用 org.json 重算一遍六段；
// 「出口」是 GovernanceStateTool.run 的返回。两者逐项断言相等，口径对不上当场红。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.CommitmentArchive
import com.example.zhengdao.baopu.CommitmentLine
import com.example.zhengdao.baopu.CommitmentStage
import com.example.zhengdao.baopu.CommitmentStore
import com.example.zhengdao.baopu.DecisionActor
import com.example.zhengdao.baopu.DecisionDraft
import com.example.zhengdao.baopu.DecisionKind
import com.example.zhengdao.baopu.DecisionLedger
import com.example.zhengdao.baopu.DecisionRecord
import com.example.zhengdao.baopu.DecisionReview
import com.example.zhengdao.baopu.RiskClass
import com.example.zhengdao.baopu.RiskTable
import com.example.zhengdao.baopu.consequence.CanaryBand
import com.example.zhengdao.baopu.consequence.CanaryCapability
import com.example.zhengdao.baopu.consequence.ConsequenceEvidence
import com.example.zhengdao.baopu.consequence.IdentityRow
import com.example.zhengdao.baopu.consequence.IdentityState
import com.example.zhengdao.baopu.consequence.IdentityStore
import com.example.zhengdao.baopu.inspect.ConflictRow
import com.example.zhengdao.baopu.inspect.InspectCheck
import com.example.zhengdao.baopu.inspect.InspectFinding
import com.example.zhengdao.baopu.inspect.InspectVerdict
import com.example.zhengdao.baopu.inspect.LogStep
import com.example.zhengdao.baopu.inspect.MutualLedger
import com.example.zhengdao.baopu.inspect.MutualSide
import com.example.zhengdao.baopu.inspect.PatrolEntry
import com.example.zhengdao.baopu.inspect.PatrolLedger
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GovernanceStateToolTest {

    private lateinit var memoryRoot: File
    private lateinit var filesDir: File
    private lateinit var wsRoot: File

    /** 全程钉死的「现在」：本地今天 10:00。所有账行时刻都由它推（昨日＝−24h）。 */
    private val nowMs: Long = LocalDate.now().atTime(10, 0)
        .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private val yesterdayMs: Long = nowMs - 24L * 3600_000

    /** 巡按明细的两种结论（夹具速写）。 */
    private val ND = InspectVerdict.NO_DATA
    private val CL = InspectVerdict.CLEAR

    /** run_log 的原始事实（假件＝SQL 的替身：按 from 过滤后原样给）。 */
    private val logFixture = listOf(
        LogStep(1, 7, 1, "Thinking", nowMs - 1000),
        LogStep(2, 7, 1, "AwaitConfirm", nowMs - 900),
        LogStep(3, 7, 2, "OK", nowMs - 800),
        LogStep(4, 9, 3, "AwaitConfirm", nowMs - 700),
        // 昨天的一步：跨日不报（判据「跨日只报当日」）。
        LogStep(5, 8, 1, "AwaitConfirm", yesterdayMs),
    )

    @Before
    fun setUp() {
        memoryRoot = makeDir("zd527-memory")
        filesDir = makeDir("zd527-files")
        wsRoot = makeDir("zd527-ws")
        DecisionLedger.resetForTest()
        AskGovernance.resetForTest()
        GovernanceStateTool.env = GovernanceStateTool.Env(
            identityDir = { filesDir },
            logSteps = { from -> logFixture.filter { it.atMs >= from } },
        )
    }

    @After
    fun tearDown() {
        GovernanceStateTool.env = null
        AskGovernance.resetForTest()
        // 账本根也一起还：这本账是进程级单例，漏一根进去会让后面的用例读到假装配
        DecisionLedger.resetForTest()
        memoryRoot.deleteRecursively()
        filesDir.deleteRecursively()
        wsRoot.deleteRecursively()
    }

    // ── 一、对拍：账本原文重算（脚本） vs 工具出口，逐项相等 ───────────────────

    @Test
    fun 对拍_六段逐项相等() {
        DecisionLedger.install(memoryRoot)
        seedLedgers()
        val actual = JSONObject((GovernanceStateTool.run(wsRoot, nowMs) as ToolOutcome.Ok).text)
        val expected = script()
        // 六段都返回（判据①）
        listOf("ratios", "cards", "gray", "conflicts", "identity", "openLoops").forEach {
            assertTrue("缺段 $it", actual.has(it))
        }
        assertEquals("day", expected.optString("day"), actual.optString("day"))

        // 段一 ratios：四率 n/d/rate 逐项
        val ar = actual.getJSONObject("ratios")
        val er = expected.getJSONObject("ratios")
        listOf("violation", "misjudge", "intervention", "skip").forEach { k ->
            assertEquals("ratios.$k.n", er.getJSONObject(k).optInt("n"), ar.getJSONObject(k).optInt("n"))
            assertEquals("ratios.$k.d", er.getJSONObject(k).optInt("d"), ar.getJSONObject(k).optInt("d"))
            assertEquals("ratios.$k.rate", er.getJSONObject(k).optString("rate"), ar.getJSONObject(k).optString("rate"))
        }
        // 段二 cards 三数
        val ac = actual.getJSONObject("cards")
        val ec = expected.getJSONObject("cards")
        assertEquals("cards.pass", ec.optInt("pass"), ac.optInt("pass"))
        assertEquals("cards.card", ec.optInt("card"), ac.optInt("card"))
        assertEquals("cards.compress", ec.optInt("compress"), ac.optInt("compress"))
        // 段三 gray：登记表全量逐能力（两边都按 entries 顺序建 ⇒ 串相等即逐项相等）
        assertEquals("gray", expected.getJSONObject("gray").toString(), actual.getJSONObject("gray").toString())
        // 段四 conflicts
        assertEquals(
            "conflicts.pending_replay",
            expected.getJSONObject("conflicts").optInt("pending_replay"),
            actual.getJSONObject("conflicts").optInt("pending_replay"),
        )
        // 段五 identity
        assertEquals(
            "identity",
            expected.getJSONObject("identity").toString(),
            actual.getJSONObject("identity").toString(),
        )
        // 段六 openLoops：total＋逐条
        val ao = actual.getJSONObject("openLoops")
        val eo = expected.getJSONObject("openLoops")
        assertEquals("openLoops.total", eo.optInt("total"), ao.optInt("total"))
        assertEquals("openLoops.items", eo.optJSONArray("items").toString(), ao.optJSONArray("items").toString())
    }

    // ── 二、三条写死各自的负例 ────────────────────────────────────────────────

    @Test
    fun 跨日只报当日_昨日行不进今日读数() {
        DecisionLedger.install(memoryRoot)
        seedLedgers()
        val out = JSONObject((GovernanceStateTool.run(wsRoot, nowMs) as ToolOutcome.Ok).text)
        // cards：昨天另有一笔 pass＋card（见 seedLedgers），今日三数不含它们
        assertEquals(3, out.getJSONObject("cards").optInt("pass"))
        assertEquals(2, out.getJSONObject("cards").optInt("card"))
        // ratios.violation：昨日那笔 auto_act（未被回滚）不进分母
        assertEquals(2, out.getJSONObject("ratios").getJSONObject("violation").optInt("d"))
        // ratios.skip：昨日巡按的 3 个 no_data 不进
        assertEquals(2, out.getJSONObject("ratios").getJSONObject("skip").optInt("n"))
        // conflicts 是队列深度（状态读数，metrics-registry.md §4）：昨日还挂着的那条**要算**——
        // 这一格与「跨日只报当日」的差别是口径写明的，不许悄悄混掉
        assertEquals(2, out.getJSONObject("conflicts").optInt("pending_replay"))
    }

    @Test
    fun 分母0回no_sample_不编0() {
        DecisionLedger.install(memoryRoot)
        // 空账：根装了、一行没有，run_log 也空 ⇒ 四率全 no_sample，不是 0%
        GovernanceStateTool.env = GovernanceStateTool.Env(identityDir = { filesDir }, logSteps = { emptyList() })
        val out = JSONObject((GovernanceStateTool.run(wsRoot, nowMs) as ToolOutcome.Ok).text)
        val ratios = out.getJSONObject("ratios")
        listOf("violation", "misjudge", "intervention", "skip").forEach { k ->
            val r = ratios.getJSONObject(k)
            assertEquals("$k.d", 0, r.optInt("d"))
            assertEquals("$k.rate", "no_sample", r.optString("rate"))
        }
        assertEquals(0, out.getJSONObject("cards").optInt("pass"))
        assertEquals(0, out.getJSONObject("conflicts").optInt("pending_replay"))
    }

    @Test
    fun 读不到降级unknown_不抛异常() {
        // 账本根**没装**（setUp 只 reset 不 install ⇒ root=null）、env 也摘掉：
        // 六段照样一起回，缺数的段如实 unknown＋原因——不许抛异常，也不许拿 0 冒充读数
        GovernanceStateTool.env = null
        val out = JSONObject((GovernanceStateTool.run(wsRoot, nowMs) as ToolOutcome.Ok).text)
        assertTrue(out.getJSONObject("ratios").getJSONObject("violation").has("unknown"))
        assertTrue(out.getJSONObject("ratios").getJSONObject("intervention").has("unknown"))
        assertTrue(out.getJSONObject("cards").has("unknown"))
        assertTrue(out.getJSONObject("gray").has("unknown"))
        assertTrue(out.getJSONObject("conflicts").has("unknown"))
        assertTrue(out.getJSONObject("identity").has("unknown"))
        // openLoops：工作区可用但档案没写过 ⇒ 真零（不是 unknown）
        assertEquals(0, out.getJSONObject("openLoops").optInt("total"))
    }

    @Test
    fun 率的三态_0样本no_sample_小样本unknown_够样本出百分比() {
        assertEquals("no_sample", GovernanceStateTool.rateOf(0, 0))
        assertEquals("unknown", GovernanceStateTool.rateOf(3, 9))
        assertEquals("20.0%", GovernanceStateTool.rateOf(2, 10))
        assertEquals("12.5%", GovernanceStateTool.rateOf(10, 80))
    }

    // ── 三、矩阵有行与同源（关键事实：弹卡三数别再造）────────────────────────

    @Test
    fun 矩阵有行_只读R0_FREE() {
        val rule = RiskTable.DEFAULT.tools["governance_state"]
        assertNotNull("RiskMatrix 必须有行", rule)
        assertEquals(RiskClass.R0, rule!!.base)
        val spec = GovernanceStateTool.spec({ wsRoot })
        assertEquals("R0", spec.riskTier)
        assertTrue(spec.readOnly)
        assertEquals(ToolPermission.FREE, spec.permission)
    }

    @Test
    fun cards与healthLine同源_严格档pass恒0() {
        DecisionLedger.install(memoryRoot)
        seedCardsOnly()
        val c = AskGovernance.healthCounts(nowMs)
        assertEquals(3, c.pass)
        assertEquals(2, c.cards)
        assertEquals(1, c.compress)
        // healthLine 的话行与 healthCounts 同一份数（507 后这一行的形状有既有用例钉；这里钉数同源）
        val line = AskGovernance.healthLine(nowMs)
        assertTrue(line.contains("今日自动放过 3 次"))
        assertTrue(line.contains("今日问卡 2 张"))
        assertTrue(line.contains("汇总压卡 1 次"))
        // 严格档：pass 恒 0（healthLine 同口径——不是没数）
        AskGovernance.setTier(AskTier.STRICT, atMs = nowMs)
        assertEquals(0, AskGovernance.healthCounts(nowMs).pass)
        val out = JSONObject((GovernanceStateTool.run(wsRoot, nowMs) as ToolOutcome.Ok).text)
        assertEquals(0, out.getJSONObject("cards").optInt("pass"))
    }

    // ── 四、夹具 ─────────────────────────────────────────────────────────────

    /** 各本账都落几行（今日为主，昨日几行专门给「跨日」负例；对拍脚本读原文）。 */
    private fun seedLedgers() {
        // 208：今日两笔 auto_act，其中 a1 被今日的回滚行指到 ⇒ violation n=1/d=2；
        // 昨日第三笔不进当日窗（跨日负例）
        val a1 = DecisionLedger.recordTo(
            memoryRoot,
            DecisionDraft(
                kind = DecisionKind.AUTO_ACT, actor = DecisionActor.AUTO,
                reason = "自动整理", evidence = "ev:auto-1", before = "", after = "",
                atMs = nowMs - 60_000, rollbackRef = "trash/x",
            ),
        )!!
        DecisionLedger.recordTo(
            memoryRoot,
            DecisionDraft(
                kind = DecisionKind.AUTO_ACT, actor = DecisionActor.AUTO,
                reason = "自动改档", evidence = "ev:auto-2", before = "", after = "",
                atMs = nowMs - 50_000, rollbackRef = "trash/y",
            ),
        )!!
        DecisionLedger.recordTo(
            memoryRoot,
            DecisionDraft(
                kind = DecisionKind.AUTO_ACT, actor = DecisionActor.AUTO,
                reason = "昨日的免问动作", evidence = "ev:auto-old", before = "", after = "",
                atMs = yesterdayMs, rollbackRef = "trash/z",
            ),
        )!!
        DecisionLedger.recordTo(
            memoryRoot,
            DecisionDraft(
                kind = DecisionKind.ROLLBACK_OF, actor = DecisionActor.USER,
                reason = "撤销 a1", evidence = "ev:rb", before = "", after = "",
                atMs = nowMs - 10_000, rollbackOf = a1.id,
            ),
        )!!
        // 灰度：wish 档位行（今日，转 FULL）＋ 一笔被回滚的旧档位行（orchestration ⇒ 回缺省 OFF）
        DecisionLedger.recordTo(
            memoryRoot,
            DecisionDraft(
                kind = DecisionKind.TOOL_GRANT, actor = DecisionActor.AUTO,
                reason = "灰度档位 灰度→全开（wish）", evidence = "${ConsequenceEvidence.CANARY}wish:full:达标",
                before = "canary", after = "full", atMs = nowMs - 20_000, rollbackRef = "canary:wish:canary",
            ),
        )
        val oldBand = DecisionLedger.recordTo(
            memoryRoot,
            DecisionDraft(
                kind = DecisionKind.TOOL_GRANT, actor = DecisionActor.AUTO,
                reason = "灰度档位 关闭→灰度（orchestration）",
                evidence = "${ConsequenceEvidence.CANARY}orchestration:canary:试运行",
                before = "off", after = "canary", atMs = yesterdayMs, rollbackRef = "canary:orchestration:off",
            ),
        )!!
        DecisionLedger.recordTo(
            memoryRoot,
            DecisionDraft(
                kind = DecisionKind.ROLLBACK_OF, actor = DecisionActor.USER,
                reason = "撤销编排灰度", evidence = "ev:rb-band", before = "", after = "",
                atMs = nowMs - 5_000, rollbackOf = oldBand.id,
            ),
        )!!
        // 抽检：r1 判错、r2 判错、r3 昨日错今日翻案（取最新=confirm）＋ 昨日一笔错（不进窗）
        fun review(id: String, verdict: String, atMs: Long) = DecisionReview.append(
            memoryRoot,
            DecisionReview.Entry(
                recordId = id, kind = "auto_act", verdict = verdict, actionSummary = "动作 $id",
                matrixVersion = "test", reasonCode = "auto_act", evidence = "ev:$id", atMs = atMs,
            ),
        )
        review("r1", DecisionRecord.VERDICT_WRONG, nowMs - 40_000)
        review("r2", DecisionRecord.VERDICT_WRONG, nowMs - 30_000)
        review("r3", DecisionRecord.VERDICT_WRONG, yesterdayMs)
        review("r3", DecisionRecord.VERDICT_CONFIRMED, nowMs - 25_000)
        review("r4", DecisionRecord.VERDICT_WRONG, yesterdayMs)
        // 巡按：今日两趟（2 个 no_data）＋ 昨日一趟（3 个 no_data，不进）＋ 无明细老行
        PatrolLedger.append(memoryRoot, patrol("p1", nowMs - 60_000, listOf(ND, CL, CL, CL, CL, CL)))
        PatrolLedger.append(memoryRoot, patrol("p2", nowMs - 55_000, listOf(ND, CL, CL, CL, CL, CL)))
        PatrolLedger.append(memoryRoot, patrol("p3", yesterdayMs, listOf(ND, ND, ND, CL, CL, CL)))
        PatrolLedger.append(memoryRoot, patrol("p4", nowMs - 50_000, emptyList()))
        // 打扰治理：今日 3 放 2 卡 1 压 1 申诉；昨日 1 放 1 卡（跨日不进）
        seedCardsOnly(withYesterday = true)
        // 互查：今日 1 条待回放 ＋ 昨日 1 条仍待回放（队列深度含它）＋ 1 条已回放
        MutualLedger.append(memoryRoot, conflict("c1", nowMs - 30_000, pending = true))
        MutualLedger.append(memoryRoot, conflict("c2", yesterdayMs, pending = true))
        MutualLedger.append(memoryRoot, conflict("c3", nowMs - 20_000, pending = false))
        // 身份：一条已轮换的旧行 ＋ 一条在用行
        IdentityStore.save(
            filesDir,
            listOf(
                IdentityRow("zdx-00000000-1111-4222-8333-444444444444", IdentityState.ROTATED_OUT, nowMs - 86_400_000),
                IdentityRow("zdx-aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee", IdentityState.ACTIVE, nowMs - 1000),
            ),
        )
        // 承诺档案：k1 只承诺（5 天前）；k2 改了没改成（3 天前）；k3 三问闭合（不算欠账）
        val f = CommitmentStore.fileOf(wsRoot)
        f.parentFile?.mkdirs()
        f.writeText(
            listOf(
                CommitmentArchive.header(),
                CommitmentArchive.renderLine(CommitmentLine(today().minusDays(5), "k1", CommitmentStage.PROMISE, "欠", "evidence:k1", "少写一遍口径")),
                CommitmentArchive.renderLine(CommitmentLine(today().minusDays(3), "k2", CommitmentStage.PROMISE, "欠", "evidence:k2", "补抽检翻案")),
                CommitmentArchive.renderLine(CommitmentLine(today().minusDays(2), "k2", CommitmentStage.RESULT, "没改成", "evidence:k2b", "前置件没到")),
                CommitmentArchive.renderLine(CommitmentLine(today().minusDays(4), "k3", CommitmentStage.PROMISE, "欠", "evidence:k3", "顺手做")),
                CommitmentArchive.renderLine(CommitmentLine(today().minusDays(1), "k3", CommitmentStage.RESULT, "改成", "evidence:k3b", "已落盘")),
                CommitmentArchive.renderLine(CommitmentLine(today(), "k3", CommitmentStage.OUTCOME, "变好", "evidence:k3c", "对拍见好")),
            ).joinToString("\n"),
            Charsets.UTF_8,
        )
    }

    /** 只落打扰治理账（cards 段的同源用例用）。 */
    private fun seedCardsOnly(withYesterday: Boolean = false) {
        repeat(3) { i ->
            AskGovLedger.append(kind = AskGovLedger.KIND_PASS, who = AskGovLedger.WHO_TIER, basis = "自动放过$i", atMs = nowMs - (i + 1) * 1000)
        }
        AskGovLedger.append(kind = AskGovLedger.KIND_CARD, who = AskGovLedger.WHO_TIER, basis = "问卡1", scene = AskGovLedger.SCENE_OBJECTIVE, atMs = nowMs - 800)
        AskGovLedger.append(kind = AskGovLedger.KIND_CARD, who = AskGovLedger.WHO_TIER, basis = "问卡2", scene = AskGovLedger.SCENE_SUBJECTIVE, atMs = nowMs - 700)
        AskGovLedger.append(kind = AskGovLedger.KIND_COMPRESS, who = AskGovLedger.WHO_LEFT, basis = "压卡", atMs = nowMs - 600)
        AskGovLedger.append(kind = AskGovLedger.KIND_APPEAL, who = "user", basis = "误拦申诉", scene = AskGovLedger.SCENE_OBJECTIVE, atMs = nowMs - 500)
        if (withYesterday) {
            AskGovLedger.append(kind = AskGovLedger.KIND_PASS, who = AskGovLedger.WHO_TIER, basis = "昨日放过", atMs = yesterdayMs)
            AskGovLedger.append(kind = AskGovLedger.KIND_CARD, who = AskGovLedger.WHO_TIER, basis = "昨日问卡", atMs = yesterdayMs)
        }
    }

    private fun patrol(id: String, atMs: Long, verdicts: List<InspectVerdict>): PatrolEntry = PatrolEntry(
        id = id, atMs = atMs, headline = "巡按 $id", lines = listOf(), hits = 0, noData = 0,
        manual = true, elapsedMs = 1L,
        findings = verdicts.mapIndexed { i, v ->
            InspectFinding(check = InspectCheck.AUDIT_GAP, verdict = v, oneLine = "行$i", suggestion = "无")
        },
    )

    private fun conflict(id: String, atMs: Long, pending: Boolean): JSONObject = MutualLedger.conflictJson(
        ConflictRow(
            id = id, atMs = atMs, target = "ev:shared", side = MutualSide.SHADOW,
            check = InspectCheck.AUDIT_GAP, verdict = InspectVerdict.HIT, evidence = listOf("ev:shared"),
            peerSide = MutualSide.GUARD, peerEvidence = listOf("ev:shared"), pendingReplay = pending,
        ),
    )

    private fun makeDir(prefix: String): File =
        File.createTempFile(prefix, "").let { it.delete(); it.mkdirs(); it }

    private fun today(): LocalDate = AskGovernance.dayOf(nowMs)

    // ── 五、脚本（对拍的独立半边：只读账本原文，不走生产取数路）───────────────

    /**
     * 从**账本原文**重算六段（metrics-registry.md 的定义照抄一遍，独立实现）。
     * 与工具的差异只在取数路：这里直接 readLines＋org.json，那里走各账的 replay/read。
     */
    private fun script(): JSONObject {
        val today = Instant.ofEpochMilli(nowMs).atZone(ZoneId.systemDefault()).toLocalDate()
        fun dayOf(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()
        fun linesOf(f: File): List<JSONObject> =
            if (!f.isFile) emptyList()
            else f.readLines(Charsets.UTF_8).mapNotNull { runCatching { JSONObject(it) }.getOrNull() }

        val decision = linesOf(File(memoryRoot, "决策记录.jsonl"))
        val reviews = linesOf(File(memoryRoot, "抽检结论.jsonl"))
        val patrols = linesOf(File(memoryRoot, "巡按记录.jsonl"))
        val gov = linesOf(File(memoryRoot, "打扰治理.jsonl"))
        val mutual = linesOf(File(memoryRoot, "互查记录.jsonl"))

        val out = JSONObject().put("v", 1).put("day", today.toString())

        // ratios（当日窗；回滚行不切窗）
        val rolledBack = decision.filter { it.optString("kind") == "rollback_of" }
            .mapNotNull { if (it.isNull("rollback_of")) null else it.optString("rollback_of") }
            .toSet()
        val auto = decision.filter { it.optString("kind") == "auto_act" && dayOf(it.optLong("at")) == today }
        val violation = auto.count { it.optString("id") in rolledBack } to auto.size

        val latest = LinkedHashMap<String, JSONObject>()
        reviews.forEach { e ->
            val prev = latest[e.optString("record_id")]
            if (prev == null || e.optLong("at") >= prev.optLong("at")) latest[e.optString("record_id")] = e
        }
        val judged = latest.values.filter { dayOf(it.optLong("at")) == today }
            .filter { it.optString("verdict") in setOf("confirmed", "wrong") }
        val misjudge = judged.count { it.optString("verdict") == "wrong" } to judged.size

        val logToday = logFixture.filter { dayOf(it.atMs) == today }
        val steps = logToday.map { it.sessionId to it.step }.toSet()
        val confirms = logToday.filter { it.state == "AwaitConfirm" }.map { it.sessionId to it.step }.toSet()
        val intervention = confirms.size to steps.size

        val patrolToday = patrols.filter {
            dayOf(it.optLong("at")) == today && (it.optJSONArray("findings")?.length() ?: 0) > 0
        }
        val skipN = patrolToday.sumOf { e ->
            val fs = e.optJSONArray("findings")!!
            (0 until fs.length()).count { fs.optJSONObject(it)?.optString("verdict") == "no_data" }
        }
        val skipD = patrolToday.sumOf { e -> e.optJSONArray("findings")!!.length() }
        val skip = skipN to skipD

        fun rateOf(pair: Pair<Int, Int>): String = when {
            pair.second == 0 -> "no_sample"
            pair.second < 10 -> "unknown"
            else -> {
                // 与 Metrics.decimals 同一把尺（两位小数，不去尾 0）
                val v = pair.first * 100.0 / pair.second
                "${Math.round(v * 100) / 100.0}%"
            }
        }
        fun rateJson(pair: Pair<Int, Int>) = JSONObject().put("n", pair.first).put("d", pair.second).put("rate", rateOf(pair))

        out.put(
            "ratios",
            JSONObject()
                .put("violation", rateJson(violation))
                .put("misjudge", rateJson(misjudge))
                .put("intervention", rateJson(intervention))
                .put("skip", rateJson(skip)),
        )

        // cards（当日；严格档 pass 恒 0）
        val govToday = gov.filter { dayOf(it.optLong("at")) == today }
        out.put(
            "cards",
            JSONObject()
                .put("pass", if (AskGovernance.currentTier == AskTier.STRICT) 0 else govToday.count { it.optString("kind") == "pass" })
                .put("card", govToday.count { it.optString("kind") == "card" })
                .put("compress", govToday.count { it.optString("kind") == "compress" }),
        )

        // gray（逐能力当前档；被回滚的档位行不算，没有 ⇒ 缺省档）
        val gray = JSONObject()
        CanaryCapability.entries.forEach { cap ->
            val rows = decision.filter {
                it.optString("kind") == "tool_grant" &&
                    it.optString("evidence").startsWith("canary:${cap.wire}:") &&
                    it.optString("id") !in rolledBack
            }
            val band = rows.maxByOrNull { it.optLong("at") }?.optString("after")
                ?: when (cap.defaultBand) {
                    CanaryBand.OFF -> "off"
                    CanaryBand.CANARY -> "canary"
                    CanaryBand.FULL -> "full"
                }
            val cn = when (band) {
                "full" -> "全开"
                "canary" -> "灰度"
                else -> "关闭"
            }
            gray.put(cap.wire, JSONObject().put("band", band).put("cn", cn))
        }
        out.put("gray", gray)

        // conflicts（队列深度：pending_replay=true 的冲突行，不切窗）
        out.put(
            "conflicts",
            JSONObject().put(
                "pending_replay",
                mutual.count { it.optString("kind") == "conflict" && it.optBoolean("pending_replay", false) },
            ),
        )

        // identity（身份 json 原文）
        val identityFile = File(File(filesDir, "执行体"), "身份.json")
        if (!identityFile.isFile) {
            out.put("identity", JSONObject().put("unknown", "执行体身份账读不到"))
        } else {
            val rows = identityFile.readText(Charsets.UTF_8).let { JSONObject(it).optJSONArray("rows") }!!
            var activeId = ""
            for (i in 0 until rows.length()) {
                if (rows.optJSONObject(i)?.optString("state") == "active") activeId = rows.optJSONObject(i)!!.optString("id")
            }
            if (activeId.isEmpty()) {
                val last = rows.optJSONObject(rows.length() - 1)!!
                out.put("identity", JSONObject().put("id", last.optString("id")).put("state", last.optString("state")))
            } else {
                out.put("identity", JSONObject().put("id", activeId).put("state", "active"))
            }
        }

        // openLoops（承诺档案 md 原文；三问闭合定义照 236）
        val mdFile = CommitmentStore.fileOf(wsRoot)
        if (!mdFile.isFile) {
            out.put("openLoops", JSONObject().put("total", 0).put("items", JSONArray()))
        } else {
            data class Book(val key: String, var promised: Boolean, var result: String?, var outcome: String?, var since: LocalDate, var reason: String, var lastStage: String)
            val books = LinkedHashMap<String, Book>()
            mdFile.readLines(Charsets.UTF_8).forEach { raw ->
                val line = raw.trim()
                if (!line.startsWith("|")) return@forEach
                val f = line.split("|").map { it.trim() }
                if (f.size < 8 || f[1] == "日期") return@forEach
                val date = runCatching { LocalDate.parse(f[1]) }.getOrNull() ?: return@forEach
                val key = f[2]
                val b = books[key] ?: Book(key, promised = false, result = null, outcome = null, since = date, reason = "", lastStage = "")
                b.since = minOf(b.since, date)
                when (f[3]) {
                    "承诺" -> b.promised = true

                    "改了没" -> {
                        b.result = f[4]
                        b.reason = f[6]
                    }

                    "有没有变好" -> b.outcome = f[4]
                }
                b.lastStage = f[3]
                books[key] = b
            }
            val open = books.values.filter { b ->
                b.promised && when (b.result) {
                    null -> true
                    "没改成", "卡住" -> true
                    "放弃" -> b.reason.isBlank()
                    "改成" -> b.outcome == null || b.outcome == "没验"
                    else -> true
                }
            }.sortedBy { it.since }
            val items = JSONArray()
            open.take(10).forEach { b ->
                items.put(
                    JSONObject().put("key", b.key).put("since", b.since.toString()).put("stage", b.lastStage),
                )
            }
            out.put("openLoops", JSONObject().put("total", open.size).put("items", items))
        }
        return out
    }
}
