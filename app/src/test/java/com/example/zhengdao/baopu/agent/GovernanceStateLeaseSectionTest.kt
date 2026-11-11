// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百五十八批（2026-11-09）· `governance_state` 租约段（lease）＋体检尾句的验收用例。
//
// 三把牙（对应用 metrics-registry.md §8 的口径）：
//   1. **金样例**：旧七段的字节是在**改动前**的代码上抓下来的常量（本文件底部的
//      GOLDEN_* ——558 动工前于 wt-558 pristine 跑 [GovernanceStateTool.run] 所得）。
//      加一段不许动旧段：六段老读数＋旧体检行**逐字节相等**，键序也不许挪。
//   2. **对拍**：lease 段的事件计数与「账本原文重算」逐项相等（527 对拍的同一套路；
//      `live` 一格是内存权威，脚本侧不重算、以驱动状态钉期望值——registry §8 明文）。
//   3. **显示规则**：体检尾句/读数行的显示条件（今天授过或手里有活的）正负例都钉。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.CommitmentArchive
import com.example.zhengdao.baopu.CommitmentLine
import com.example.zhengdao.baopu.CommitmentStage
import com.example.zhengdao.baopu.CommitmentStore
import com.example.zhengdao.baopu.DecisionActor
import com.example.zhengdao.baopu.DecisionDraft
import com.example.zhengdao.baopu.DecisionKind
import com.example.zhengdao.baopu.DecisionLedger
import com.example.zhengdao.baopu.DecisionReview
import com.example.zhengdao.baopu.DecisionRecord
import com.example.zhengdao.baopu.authz.LeaseKinds
import com.example.zhengdao.baopu.authz.LeaseLedger
import com.example.zhengdao.baopu.authz.LeaseMissReason
import com.example.zhengdao.baopu.authz.LeaseOffer
import com.example.zhengdao.baopu.authz.LeaseScope
import com.example.zhengdao.baopu.authz.LeaseStore
import com.example.zhengdao.baopu.authz.LeaseVerdict
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
import com.example.zhengdao.ui.baopu.GovernanceCenterRules
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.time.ZoneId
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GovernanceStateLeaseSectionTest {

    /** 钉死的「现在」：2026-11-09 本地 10:00（金样例与租约账行都以它推时刻）。 */
    private val nowMs: Long = LocalDate.of(2026, 11, 9).atTime(10, 0)
        .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    private val yesterdayMs: Long = nowMs - 24L * 3600_000

    private lateinit var memoryRoot: File
    private lateinit var filesDir: File
    private lateinit var wsRoot: File
    private lateinit var leaseDir: File

    private val ND = InspectVerdict.NO_DATA
    private val CL = InspectVerdict.CLEAR

    private val logFixture = listOf(
        LogStep(1, 7, 1, "Thinking", nowMs - 1000),
        LogStep(2, 7, 1, "AwaitConfirm", nowMs - 900),
        LogStep(3, 7, 2, "OK", nowMs - 800),
        LogStep(4, 9, 3, "AwaitConfirm", nowMs - 700),
        LogStep(5, 8, 1, "AwaitConfirm", yesterdayMs),
    )

    @Before
    fun setUp() {
        memoryRoot = makeDir("zd558-memory")
        filesDir = makeDir("zd558-files")
        wsRoot = makeDir("zd558-ws")
        leaseDir = File(wsRoot, "同目录").apply { mkdirs() }
        DecisionLedger.resetForTest()
        AskGovernance.resetForTest()
        // 571 的改判账也一起还：companion 段读它，漏一行内存账会让金样例的「真零」变假
        CompanionRejudgeLedger.resetForTest()
        // 467 的两本（内存账＋账落点）一起还——落点**装在 workspace 根**（生产：管线
        // rememberRoot 喂的就是它），558 的读数按生产形状装配
        LeaseStore.resetForTest()
        LeaseLedger.installRoot { wsRoot }
        GovernanceStateTool.env = GovernanceStateTool.Env(
            identityDir = { filesDir },
            logSteps = { from -> logFixture.filter { it.atMs >= from } },
        )
    }

    @After
    fun tearDown() {
        GovernanceStateTool.env = null
        AskGovernance.resetForTest()
        DecisionLedger.resetForTest()
        CompanionRejudgeLedger.resetForTest()
        LeaseStore.resetForTest()
        memoryRoot.deleteRecursively()
        filesDir.deleteRecursively()
        wsRoot.deleteRecursively()
    }

    // ── 一、金样例：旧六段＋旧体检行，逐字节不变 ─────────────────────────────

    /**
     * 558 动工前在 pristine 代码上抓下的字节（抓取件已删，常量即金样例）。
     * 加 lease 段后这里**一个字节都不许变**——包括键序（org.json 的迭代序也是字节的一部分）。
     */
    private val goldenRatios =
        "{\"violation\":{\"d\":2,\"rate\":\"unknown\",\"n\":1}," +
            "\"misjudge\":{\"d\":1,\"rate\":\"unknown\",\"n\":1}," +
            "\"skip\":{\"d\":6,\"rate\":\"unknown\",\"n\":1}," +
            "\"intervention\":{\"d\":3,\"rate\":\"unknown\",\"n\":2}}"
    private val goldenCards = "{\"pass\":2,\"compress\":1,\"card\":1}"
    // 第五百六十八批 A：登记表按 473 自己的扩法补了 prompt_tail（缺省灰度）⇒ gray 段
    // 多一行；gray 的 JSON 键序是 org.json HashMap 迭代序（Java8 spread，String 哈希规范
    // 固定 ⇒ 可推算），新键落在 wish 与 event_trigger 之间——金样例按登记表生长更新，
    // 其余字节照旧逐字节钉死。
    // 第五百八十八批：登记表补登记 evolution（517 漏登的读档线值，缺省灰度）⇒ 同款生长。
    // 新键按 String 哈希落进 persona_organize 与 prod_eval 之间（键序独立复算过，不是照抄
    // 本次输出）：去掉 evolution 后与上一版逐字节相同，其余七段的键序与字节一个不动。
    // 第五百六十八批 B 域①：登记表补 judgment_threshold（缺省灰度）⇒ 第 13 个键触发
    // HashMap 16→32 扩容，全表重排（12 键的相对序整个变了——扩容重哈希，不是插序微调）；
    // 新序按 spread&(32-1) 独立复算，与失败现场逐字节吻合后落笔，其余七段一个不动。
    // 第五百六十八批 B 域②：登记表补 companion_rule（缺省灰度）⇒ 容量仍 32（≤24 不扩），
    // 新键按哈希落进 data_repair 与 prod_eval 之间，其余键相对序不动（同一把复算法）。
    // 第五百六十八批 B 域③：登记表补 goal_template（缺省灰度）⇒ 同款，落进
    // reflection_auto_edit 与 persona_organize 之间（15 键仍 ≤24 不扩容，复算吻合）。
    private val goldenGray =
        "{\"prompt_tail\":{\"band\":\"canary\",\"cn\":\"灰度\"}," +
            "\"orchestration\":{\"band\":\"off\",\"cn\":\"关闭\"}," +
            "\"reflection_auto_edit\":{\"band\":\"canary\",\"cn\":\"灰度\"}," +
            "\"goal_template\":{\"band\":\"canary\",\"cn\":\"灰度\"}," +
            "\"persona_organize\":{\"band\":\"off\",\"cn\":\"关闭\"}," +
            "\"evolution\":{\"band\":\"canary\",\"cn\":\"灰度\"}," +
            "\"wish\":{\"band\":\"canary\",\"cn\":\"灰度\"}," +
            "\"event_trigger\":{\"band\":\"off\",\"cn\":\"关闭\"}," +
            "\"memory_import\":{\"band\":\"canary\",\"cn\":\"灰度\"}," +
            "\"nocturnal_patrol\":{\"band\":\"canary\",\"cn\":\"灰度\"}," +
            "\"data_repair\":{\"band\":\"canary\",\"cn\":\"灰度\"}," +
            "\"companion_rule\":{\"band\":\"canary\",\"cn\":\"灰度\"}," +
            "\"prod_eval\":{\"band\":\"canary\",\"cn\":\"灰度\"}," +
            "\"scout\":{\"band\":\"off\",\"cn\":\"关闭\"}," +
            "\"judgment_threshold\":{\"band\":\"canary\",\"cn\":\"灰度\"}}"
    private val goldenConflicts = "{\"pending_replay\":2}"
    private val goldenIdentity =
        "{\"id\":\"zdx-aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee\",\"state\":\"active\"}"
    private val goldenOpenLoops =
        "{\"total\":2,\"items\":[{\"stage\":\"承诺\",\"key\":\"k1\",\"since\":\"2026-11-04\"}," +
            "{\"stage\":\"改了没\",\"key\":\"k2\",\"since\":\"2026-11-06\"}]}"
    private val goldenHealthLine =
        "授权档：标准 · 今日自动放过 2 次 · 今日问卡 1 张 · 汇总压卡 1 次" +
            " · 误拦申诉 1 次（场景=客观 100.0% (1/1) · 场景=主观 无样本）"

    @Test
    fun `金样例_旧六段与旧体检行逐字节不变`() {
        seedAll()
        val out = exit()
        assertEquals("ratios 字节", goldenRatios, out.getJSONObject("ratios").toString())
        assertEquals("cards 字节", goldenCards, out.getJSONObject("cards").toString())
        assertEquals("gray 字节", goldenGray, out.getJSONObject("gray").toString())
        assertEquals("conflicts 字节", goldenConflicts, out.getJSONObject("conflicts").toString())
        assertEquals("identity 字节", goldenIdentity, out.getJSONObject("identity").toString())
        assertEquals("openLoops 字节", goldenOpenLoops, out.getJSONObject("openLoops").toString())
        assertEquals("体检行字节（无租约 ⇒ 尾句不出现）", goldenHealthLine, AskGovernance.healthLine(nowMs))
    }

    @Test
    fun `旧八段键集不变_companion是唯一新增段_v升4`() {
        seedAll()
        val out = exit()
        assertEquals(4, out.getInt("v"))
        assertEquals(
            "出口键集：旧八段一个不少一个不多，companion（571）是唯一新增",
            setOf("v", "day", "ratios", "cards", "gray", "conflicts", "identity", "openLoops", "goal", "lease", "companion"),
            out.keys().asSequence().toSet(),
        )
        // goal 段（482 加的第七段）的代码路径一个字没动：字节照旧是没立目标的那句 unknown。
        assertEquals(
            "goal 字节（482 路径未动）",
            "{\"unknown\":\"这次会话还没有立目标\"}",
            out.getJSONObject("goal").toString(),
        )
        // companion 段（571 加的第九段）：改判账没人写过 ⇒ 真零（{"today":0}，不编 last 冒充读数）。
        // 558 的铁律照旧成立：这一段是**追加**，旧八段的键序与字节一个不动。
        assertEquals(
            "companion 字节（571：没改判过 ⇒ 真零）",
            "{\"today\":0}",
            out.getJSONObject("companion").toString(),
        )
        // 注：键**序**在两处运行时不一样——Android 的 org.json 是插入序（companion 排在最后，
        // 由 run() 里最后一格 .put 保证），JVM 测试件是 HashMap（顺序是散列的假象）。
        // 所以这里钉键集与逐段字节，键序以生产运行时为准。
    }

    // ── 二、lease 段形状与降级 ────────────────────────────────────────────────

    @Test
    fun `lease段_零租约是真零_七格齐_不编unknown`() {
        seedAll()
        val lease = exit().getJSONObject("lease")
        assertEquals(7, lease.length())
        assertFalse("落点在而账没写过 ⇒ 真零，不是读不到", lease.has("unknown"))
        listOf("live", "grantedToday", "hits", "savedCards", "revoked", "downgraded", "expired").forEach {
            assertEquals("$it 是真零", 0, lease.optInt(it))
        }
    }

    @Test
    fun `lease段_账落点未装配整段unknown_其余段照常`() {
        // 生产：首启没跑过任何管线动作 ⇒ LeaseLedger.root()==null（落点靠 rememberRoot 喂）
        LeaseLedger.resetForTest()
        val out = exit()
        val lease = out.getJSONObject("lease")
        assertEquals("租约账落点未装配", lease.optString("unknown"))
        assertEquals("整段就一格原因", 1, lease.length())
        // 其余段照常（527 判据：不抛、别的段不陪葬）
        assertTrue(out.getJSONObject("openLoops").has("total"))
    }

    // ── 三、造 3 次命中 ⇒ hits=3（判据③）＋ 对拍 ─────────────────────────────

    @Test
    fun `造3次命中_hits3_savedCards3_免卡与命中同源`() {
        seedAll()
        grant(cap = 10, at = nowMs - 60_000)
        repeat(3) { i ->
            val v = hit(op = "op-hit-$i", at = nowMs - 1000 * (i + 1))
            assertTrue("第 ${i + 1} 次必须命中", v is LeaseVerdict.Hit)
            LeaseStore.takePending("op-hit-$i")?.let { LeaseStore.noteUsed(it, "files/delete", "ok", nowMs) }
        }
        val lease = exit().getJSONObject("lease")
        assertEquals("hits=3（判据③）", 3, lease.optInt("hits"))
        assertEquals("命中即免卡：同一行账两个视角", 3, lease.optInt("savedCards"))
        assertEquals(1, lease.optInt("grantedToday"))
        assertEquals(1, lease.optInt("live"))
        assertEquals(0, lease.optInt("revoked"))
        assertEquals(0, lease.optInt("downgraded"))
        assertEquals(0, lease.optInt("expired"))
        assertScriptMatches(lease)
    }

    @Test
    fun `lease段_撤销降权过期三格恰好分完revoke行_对拍`() {
        seedAll()
        val a = grant(cap = 10, at = nowMs - 60_000)
        // 越权满步（VIOLATION_STEP=3）⇒ 降一档一行账（a 此刻是该类唯一的活租约）
        repeat(3) { i ->
            LeaseStore.noteRejected(1L, "files/delete", "risk_matrix", "files/delete", "op-vio-$i", nowMs - 50_000 + i)
        }
        val b = grant(cap = 10, at = nowMs - 40_000)
        assertEquals(1, LeaseStore.revoke(1L, b.id, "user", nowMs - 30_000))
        grant(cap = 10, ttlMs = 1_000L, at = nowMs - 5_000) // c：已过窗（惰性撤销，要摸一下才落账）
        assertEquals(1, LeaseStore.revoke(1L, a.id, "user", nowMs - 2_000))
        val last = hit(op = "op-expire", at = nowMs)
        assertTrue("最后一摸应判过期", last is LeaseVerdict.Miss && last.reason == LeaseMissReason.EXPIRED)

        val lease = exit().getJSONObject("lease")
        assertEquals(3, lease.optInt("grantedToday"))
        assertEquals(0, lease.optInt("hits"))
        assertEquals("撤销=人撤两张", 2, lease.optInt("revoked"))
        assertEquals("降权=满步降一档一行", 1, lease.optInt("downgraded"))
        assertEquals("过期=why=expired 一行", 1, lease.optInt("expired"))
        assertEquals(0, lease.optInt("live"))
        assertScriptMatches(lease)
    }

    @Test
    fun `live_跨会话全机读_状态读数不切窗`() {
        grant(session = 1L, at = nowMs - 60_000)
        grant(session = 2L, card = "card-2", at = nowMs - 50_000)
        grant(session = 3L, card = "card-3", at = yesterdayMs, ttlMs = 26L * 3_600_000) // 昨天授的、TTL 还没走完
        var lease = exit().getJSONObject("lease")
        assertEquals("活 3 张（含昨天那张——状态读数不切当日窗）", 3, lease.optInt("live"))
        assertEquals("grantedToday 只数今天", 2, lease.optInt("grantedToday"))
        LeaseStore.revoke(1L, null, "user", nowMs - 10_000)
        lease = exit().getJSONObject("lease")
        assertEquals("撤掉一张 ⇒ 活 2", 2, lease.optInt("live"))
    }

    // ── 四、体检尾句：正例/负例 ───────────────────────────────────────────────

    @Test
    fun `体检行_负例_账落点未装配_整句不出现与旧版逐字节相同`() {
        seedAll()
        LeaseLedger.resetForTest()
        assertEquals(goldenHealthLine, AskGovernance.healthLine(nowMs))
    }

    @Test
    fun `体检行_负例_落点在但零租约_整句不出现与旧版逐字节相同`() {
        seedAll() // 没有任何租约行，内存也没有
        assertEquals(goldenHealthLine, AskGovernance.healthLine(nowMs))
    }

    @Test
    fun `体检行_正例_有活租约_尾句追加`() {
        seedAll()
        grant(cap = 10, at = nowMs - 60_000)
        repeat(3) { i ->
            hit(op = "op-h-$i", at = nowMs - 1000 * (i + 1))
            LeaseStore.takePending("op-h-$i")?.let { LeaseStore.noteUsed(it, "files/delete", "ok", nowMs) }
        }
        assertEquals(
            goldenHealthLine + " · 租约 · 活 1/今日免卡 3",
            AskGovernance.healthLine(nowMs),
        )
    }

    @Test
    fun `体检行_今日授过但已收场_仍显示活0免卡0`() {
        seedAll()
        grant(cap = 10, ttlMs = 1_000L, at = nowMs - 5_000) // 今天授的、已过窗
        val v = hit(op = "op-expire-line", at = nowMs)
        assertTrue(v is LeaseVerdict.Miss && v.reason == LeaseMissReason.EXPIRED)
        assertEquals(
            goldenHealthLine + " · 租约 · 活 0/今日免卡 0",
            AskGovernance.healthLine(nowMs),
        )
    }

    // ── 五、跨层：同一把出口喂读数区判据层（322 对拍的租约版）─────────────────

    @Test
    fun `读数区那行与出口同源_K为0省第三段`() {
        grant(cap = 10, at = nowMs - 60_000)
        repeat(2) { i ->
            hit(op = "op-ui-$i", at = nowMs - 1000 * (i + 1))
            LeaseStore.takePending("op-ui-$i")?.let { LeaseStore.noteUsed(it, "files/delete", "ok", nowMs) }
        }
        val exit = exit()
        val row = GovernanceCenterRules.lease(exit)
        assertEquals("lease", row?.key)
        assertEquals("活 1 · 今日免卡 2", row?.value)
    }

    @Test
    fun `读数区那行_无租约整行不出现_不补零`() {
        seedAll() // 零租约
        assertNull("零租约 ⇒ 整行不出现（读数区不补 0）", GovernanceCenterRules.lease(exit()))
    }

    // ── 夹具与共用 ─────────────────────────────────────────────────────────────

    private fun exit(): JSONObject {
        val out = GovernanceStateTool.run(wsRoot, nowMs)
        return JSONObject((out as com.example.zhengdao.baopu.agent.ToolOutcome.Ok).text)
    }

    private fun grant(
        session: Long = 1L,
        cap: Int = 10,
        ttlMs: Long = 4L * 3_600_000,
        card: String = "card-1",
        at: Long = nowMs,
    ) = LeaseStore.grant(
        LeaseOffer("files/delete", LeaseScope(LeaseScope.Kind.DIR, leaseDir.canonicalFile.path), cap, ttlMs),
        session,
        card,
        at,
    )

    private fun hit(op: String, at: Long, cost: Int = 1): LeaseVerdict =
        LeaseStore.hit(1L, "files/delete", listOf(File(leaseDir, "a.md")), JSONObject(), cost, op, at)

    /** 账本原文重算（对拍的独立半边）：只读 `产物/.leases.jsonl` 的行，不走 LeaseReadings。 */
    private fun assertScriptMatches(lease: JSONObject) {
        val f = LeaseLedger.fileIn(wsRoot)
        val rows: List<JSONObject> = if (!f.isFile) {
            emptyList()
        } else {
            f.readLines(Charsets.UTF_8).mapNotNull { line -> runCatching { JSONObject(line) }.getOrNull() }
        }
        val today = AskGovernance.dayOf(nowMs)
        fun dayOf(row: JSONObject) = AskGovernance.dayOf(row.optLong("at", 0L)) == today
        val grants = rows.count { it.optString("kind") == LeaseKinds.GRANT && dayOf(it) }
        val uses = rows.count { it.optString("kind") == LeaseKinds.USE && dayOf(it) }
        val revokes = rows.filter { it.optString("kind") == LeaseKinds.REVOKE && dayOf(it) }
        assertEquals("脚本 grantsToday", grants, lease.optInt("grantedToday"))
        assertEquals("脚本 hits", uses, lease.optInt("hits"))
        assertEquals("脚本 savedCards（命中即免卡）", uses, lease.optInt("savedCards"))
        assertEquals(
            "脚本 revoked",
            revokes.count { it.optString("why") == "user" || it.optString("why") == "evicted" },
            lease.optInt("revoked"),
        )
        assertEquals(
            "脚本 downgraded",
            rows.count { it.optString("kind") == LeaseKinds.DOWNGRADE && dayOf(it) } +
                revokes.count { it.optString("why") == "downgrade" },
            lease.optInt("downgraded"),
        )
        assertEquals(
            "脚本 expired",
            revokes.count { it.optString("why") == "expired" },
            lease.optInt("expired"),
        )
    }

    /** 金样例夹具（与抓取件同一份：决策/抽检/巡按/打扰治理/互查/身份/承诺，日期钉死）。 */
    private fun seedAll() {
        DecisionLedger.install(memoryRoot)
        val a1 = DecisionLedger.recordTo(
            memoryRoot,
            DecisionDraft(
                kind = DecisionKind.AUTO_ACT, actor = DecisionActor.AUTO,
                reason = "免问动作一", evidence = "ev:a1", before = "", after = "",
                atMs = nowMs - 60_000, rollbackRef = "",
            ),
        )!!
        DecisionLedger.recordTo(
            memoryRoot,
            DecisionDraft(
                kind = DecisionKind.AUTO_ACT, actor = DecisionActor.AUTO,
                reason = "免问动作二", evidence = "ev:a2", before = "", after = "",
                atMs = nowMs - 50_000, rollbackRef = "",
            ),
        )!!
        DecisionLedger.recordTo(
            memoryRoot,
            DecisionDraft(
                kind = DecisionKind.AUTO_ACT, actor = DecisionActor.AUTO,
                reason = "昨日的免问动作", evidence = "ev:auto-old", before = "", after = "",
                atMs = yesterdayMs, rollbackRef = "",
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
        fun review(id: String, verdict: String, atMs: Long) = DecisionReview.append(
            memoryRoot,
            DecisionReview.Entry(
                recordId = id, kind = "auto_act", verdict = verdict, actionSummary = "动作 $id",
                matrixVersion = "test", reasonCode = "auto_act", evidence = "ev:$id", atMs = atMs,
            ),
        )
        review("r1", DecisionRecord.VERDICT_WRONG, nowMs - 40_000)
        review("r4", DecisionRecord.VERDICT_WRONG, yesterdayMs)
        PatrolLedger.append(memoryRoot, patrol("p1", nowMs - 60_000, listOf(ND, CL, CL, CL, CL, CL)))
        PatrolLedger.append(memoryRoot, patrol("p2", yesterdayMs, listOf(ND, ND, CL, CL, CL, CL)))
        AskGovLedger.append(kind = AskGovLedger.KIND_PASS, who = AskGovLedger.WHO_TIER, basis = "自动放过1", atMs = nowMs - 3000)
        AskGovLedger.append(kind = AskGovLedger.KIND_PASS, who = AskGovLedger.WHO_TIER, basis = "自动放过2", atMs = nowMs - 2000)
        AskGovLedger.append(kind = AskGovLedger.KIND_CARD, who = AskGovLedger.WHO_TIER, basis = "问卡1", scene = AskGovLedger.SCENE_OBJECTIVE, atMs = nowMs - 1000)
        AskGovLedger.append(kind = AskGovLedger.KIND_COMPRESS, who = AskGovLedger.WHO_LEFT, basis = "压卡", atMs = nowMs - 900)
        AskGovLedger.append(kind = AskGovLedger.KIND_APPEAL, who = "user", basis = "误拦申诉", scene = AskGovLedger.SCENE_OBJECTIVE, atMs = nowMs - 800)
        MutualLedger.append(memoryRoot, conflict("c1", nowMs - 30_000, pending = true))
        MutualLedger.append(memoryRoot, conflict("c2", yesterdayMs, pending = true))
        IdentityStore.save(
            filesDir,
            listOf(
                IdentityRow("zdx-00000000-1111-4222-8333-444444444444", IdentityState.ROTATED_OUT, nowMs - 86_400_000),
                IdentityRow("zdx-aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee", IdentityState.ACTIVE, nowMs - 1000),
            ),
        )
        val f = CommitmentStore.fileOf(wsRoot)
        f.parentFile?.mkdirs()
        f.writeText(
            listOf(
                CommitmentArchive.header(),
                CommitmentArchive.renderLine(CommitmentLine(LocalDate.of(2026, 11, 4), "k1", CommitmentStage.PROMISE, "欠", "evidence:k1", "少写一遍口径")),
                CommitmentArchive.renderLine(CommitmentLine(LocalDate.of(2026, 11, 6), "k2", CommitmentStage.PROMISE, "欠", "evidence:k2", "补抽检翻案")),
                CommitmentArchive.renderLine(CommitmentLine(LocalDate.of(2026, 11, 7), "k2", CommitmentStage.RESULT, "没改成", "evidence:k2b", "前置件没到")),
                CommitmentArchive.renderLine(CommitmentLine(LocalDate.of(2026, 11, 5), "k3", CommitmentStage.PROMISE, "欠", "evidence:k3", "顺手做")),
                CommitmentArchive.renderLine(CommitmentLine(LocalDate.of(2026, 11, 8), "k3", CommitmentStage.RESULT, "改成", "evidence:k3b", "已落盘")),
                CommitmentArchive.renderLine(CommitmentLine(LocalDate.of(2026, 11, 9), "k3", CommitmentStage.OUTCOME, "变好", "evidence:k3c", "对拍见好")),
            ).joinToString("\n"),
            Charsets.UTF_8,
        )
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
}
