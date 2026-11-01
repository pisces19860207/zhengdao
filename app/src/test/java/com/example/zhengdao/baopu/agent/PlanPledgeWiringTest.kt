// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：派工单-2026-11-01-第三百四十七批-军令状接线 §一（三个调用点）＋ §二（硬规矩）
// ＋ §三（判据 ①–⑥ ＋ 负例）。
//
// 与 `PlanPledgeTest`/`PlanPledgeBoardTest` 的分工：那边打的是**判定层与账**（312 交付，
// 纯函数 JVM 直测）；本文件打的是**接线**——判据写得再对，没人调就等于没写（NOT_WIRED）：
// - A 组：update_plan 的 schema 三键（判据①）＋ 非法组合工具层拒；
// - B 组：开工卡（判据②：长程弹一次、短任务不弹；不许自动立状——冻结只认落库后）；
// - C 组：closeout 与收口（判据③④：漏项判未完成进未办成单、closeout 有账行）；
// - D 组：resign（判据⑤：缺原因拒；重签人批后按新版结项——343 衔接）；
// - E 组：源级门禁（判据⑥：settle( 至少一个非测试调用点；不许自动立状的机器检查）；
// - F 组：跨会话 seed（312 §4.5——账是权威，只读不编）。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.PlanRules
import com.example.zhengdao.baopu.PlanRules.PlanItem
import com.example.zhengdao.baopu.PlanStore
import com.example.zhengdao.baopu.plan.PlanPledgeBoard
import com.example.zhengdao.baopu.plan.PlanPledgeRules
import com.example.zhengdao.baopu.plan.PlanPledgeRules.EndKind
import com.example.zhengdao.baopu.plan.PlanPledgeRules.PledgeSnapshot
import com.example.zhengdao.baopu.scout.ScoutLimits
import java.io.File
import java.nio.file.Files
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 门禁按仓库相对路径找接线点（测试工作目录＝app 模块；`app/` 前缀兜底另一台跑法）。 */
private const val ENGINE = "src/main/java/com/example/zhengdao/baopu/agent/RunEngine.kt"
private const val CTRL = "src/main/java/com/example/zhengdao/baopu/agent/PlanController.kt"

class PlanPledgeWiringTest {

    private class CStore : PlanStore {
        val inserts = mutableListOf<Pair<Long, String>>()
        override fun insert(sessionId: Long, payload: String) {
            inserts += sessionId to payload
        }

        override fun latest(sessionId: Long): String? = null
    }

    @Before
    fun resetBoard() = PlanPledgeBoard.resetForTest()

    @After
    fun cleanBoard() = PlanPledgeBoard.resetForTest()

    private fun controller(store: CStore = CStore(), crossSession: Boolean = false): PlanController =
        PlanController(sessionId = { 7L }, store = store, log = { }, crossSession = { crossSession })

    // ── 参数形状 ──────────────────────────────────────────────────────────────

    private fun planArgs(vararg items: Pair<String, String>): JSONObject = JSONObject().put(
        "items",
        JSONArray().also { arr ->
            items.forEach { (text, status) ->
                arr.put(JSONObject().put("text", text).put("status", status))
            }
        },
    )

    private fun pledgeOf(vararg accepts: String): JSONObject =
        JSONObject().put("acceptance", JSONArray(accepts.toList()))

    /** closeout 的一条：end=done 时 [evidence] 必填；end=abandoned 时 [reason] 必填。 */
    private fun closeoutItem(text: String, end: String, extra: JSONObject.() -> Unit = {}): JSONObject =
        JSONObject().put("text", text).put("end", end).also(extra)

    private fun closeoutOf(vararg items: JSONObject): JSONObject =
        JSONObject().put("items", JSONArray(items.toList()))

    private fun longPlan(vararg accepts: String): JSONObject =
        planArgs("甲" to "todo", "乙" to "todo", "丙" to "todo", "丁" to "todo", "戊" to "todo")
            .put("pledge", pledgeOf(*accepts))

    private fun itemsOf(texts: List<String>, status: String = "todo"): List<PlanItem> =
        texts.map { PlanItem(it, status) }

    /** 过整条开工卡链路把一版长计划立成状（测试里的人＝answerPledgeCard）。 */
    private fun pledgeViaCard(c: PlanController, args: JSONObject): PledgeSnapshot {
        val candidate = c.previewPledge(args)
        assertNotNull("长程任务必须给开工卡候选", candidate)
        c.answerPledgeCard(candidate!!, approved = true)
        val out = c.submit(args)
        assertTrue("立状提交要成功：$out", out is ToolOutcome.Ok)
        return PlanPledgeBoard.activeSnapshot()!!
    }

    private fun tmpDir(): File = Files.createTempDirectory("pledge-347").toFile()

    /** jsonl 一行一条：appendText 不带换行，这里补上。 */
    private fun File.appendTextLn(text: String) {
        appendText(text + System.lineSeparator())
    }

    // ── A 组 · schema 三键与非法组合（判据①） ────────────────────────────────

    @Test
    fun `①_schema挂上三键_键名与枚举取值同源`() {
        val decorated = PlanPledgeSchemas.decorate(PlanTool.spec())
        val props = decorated.inputSchema.getJSONObject("properties")
        assertTrue("缺 pledge", props.has(PlanPledgeRules.ARG_PLEDGE))
        assertTrue("缺 closeout", props.has(PlanPledgeRules.ARG_CLOSEOUT))
        assertTrue("缺 resign", props.has(PlanPledgeRules.ARG_RESIGN))
        assertEquals(
            "properties 里原有键不许少（基线形状：properties 只有 items）",
            setOf("items"),
            props.keys().asSequence().toSet() -
                setOf(PlanPledgeRules.ARG_PLEDGE, PlanPledgeRules.ARG_CLOSEOUT, PlanPledgeRules.ARG_RESIGN),
        )
        assertEquals("required 不变（items 仍是唯一顶层必填）", listOf("items"), (0 until decorated.inputSchema.getJSONArray("required").length())
            .map { decorated.inputSchema.getJSONArray("required").optString(it) })
        // 枚举与判定层同一份（两处各写一份必漂移）
        val end = props.getJSONObject("closeout").getJSONObject("properties")
            .getJSONObject("items").getJSONObject("items").getJSONObject("properties").getJSONObject("end")
        assertEquals(EndKind.entries.map { it.wire }, (0 until end.getJSONArray("enum").length()).map { end.getJSONArray("enum").optString(it) })
    }

    @Test
    fun `①_decorated过SchemaCheck_合法三键放行_未装饰的原schema照样拒键`() {
        val args = longPlan("甲凭据", "乙凭据", "丙凭据", "丁凭据", "戊凭据")
            .put("closeout", closeoutOf(closeoutItem("甲", "done") { put("evidence", "x.md") }))
            .put("resign", JSONObject().put("reason", "why"))
        assertNull("三键齐全的参数要过校验", SchemaCheck.validate(PlanPledgeSchemas.decorate(PlanTool.spec()).inputSchema, args))
        // decorate 是承重墙：没挂键之前这些参数在注册表就进不来（additionalProperties=false）
        val refused = SchemaCheck.validate(PlanTool.spec().inputSchema, longPlan("甲凭据", "乙凭据", "丙凭据", "丁凭据", "戊凭据"))
        assertNotNull("未装饰的 schema 必须拒 pledge（否则说明 decorate 没接上）", refused)
        assertTrue(refused!!.contains("pledge"))
    }

    @Test
    fun `①_非法组合三对各拒_单键与空参放行`() {
        val err = { a: JSONObject -> PlanPledgeSchemas.comboError(a) }
        assertNotNull(err(longPlan("甲").put("closeout", closeoutOf(closeoutItem("甲", "done") { put("evidence", "e") }))))
        assertNotNull(err(longPlan("甲").put("resign", JSONObject().put("reason", "r"))))
        assertNotNull(
            err(
                planArgs("甲" to "todo")
                    .put("closeout", closeoutOf(closeoutItem("甲", "done") { put("evidence", "e") }))
                    .put("resign", JSONObject().put("reason", "r")),
            ),
        )
        assertNull("只带 pledge 合法", err(longPlan("甲", "乙", "丙", "丁", "戊")))
        assertNull("什么都没带合法", err(planArgs("甲" to "todo")))
        // 拒的话要教正确拆法（不许只说「不行」）
        assertTrue(err(longPlan("甲").put("resign", JSONObject().put("reason", "r")))!!.contains("重签"))
    }

    @Test
    fun `①_submit层非法组合拒_不落库`() {
        val store = CStore()
        val c = controller(store)
        val out = c.submit(
            longPlan("甲", "乙", "丙", "丁", "戊")
                .put("closeout", closeoutOf(closeoutItem("甲", "done") { put("evidence", "e") })),
        )
        assertTrue("工具层必须拒：$out", out is ToolOutcome.Failed)
        assertTrue("不许落库", store.inserts.isEmpty())
        assertNull("板上也不许有状", PlanPledgeBoard.activeSnapshot())
    }

    // ── B 组 · 开工卡（判据②） ───────────────────────────────────────────────

    @Test
    fun `②_长程给候选_短任务不给_跨会话算长程`() {
        val c = controller()
        val long = c.previewPledge(longPlan("甲凭", "乙凭", "丙凭", "丁凭", "戊凭"))
        assertNotNull("≥5 步要弹卡", long)
        assertEquals(5, long!!.entries.size)
        assertEquals("验收方式与条目按位对齐", "甲凭", long.entries[0].accept)
        assertNull("3 步短任务不弹", c.previewPledge(planArgs("甲" to "todo", "乙" to "todo", "丙" to "todo").put("pledge", pledgeOf("甲", "乙", "丙"))))
        val resumed = controller(crossSession = true)
        assertNotNull("跨会话（哪怕 1 步）算长程", resumed.previewPledge(planArgs("甲" to "todo").put("pledge", pledgeOf("甲凭"))))
    }

    @Test
    fun `②_开工卡弹一次_批准后落库才冻结_ack如实报`() {
        val store = CStore()
        val c = controller(store)
        val args = longPlan("甲凭", "乙凭", "丙凭", "丁凭", "戊凭")
        val candidate = c.previewPledge(args)!!
        c.answerPledgeCard(candidate, approved = true)
        assertNull("同轮不弹第二次（一次问清）", c.previewPledge(args))
        assertNull("冻结只发生在落库之后（不许自动立状）", PlanPledgeBoard.activeSnapshot())
        val out = c.submit(args) as ToolOutcome.Ok
        val snapshot = PlanPledgeBoard.activeSnapshot()
        assertNotNull("落库成功那一刻才冻结", snapshot)
        assertEquals(1, snapshot!!.version)
        assertEquals(5, snapshot.entries.size)
        assertTrue("ack 要如实说状已立：${out.text}", out.text.contains("军令状已立"))
        assertEquals("落了库才立状", 1, store.inserts.size)
    }

    @Test
    fun `②_拒绝立状_同版不再弹_无状照走不许事后补`() {
        val store = CStore()
        val c = controller(store)
        val args = longPlan("甲凭", "乙凭", "丙凭", "丁凭", "戊凭")
        val candidate = c.previewPledge(args)!!
        c.answerPledgeCard(candidate, approved = false)
        assertNull("拒过的同版不再问", c.previewPledge(args))
        val out = c.submit(args) as ToolOutcome.Ok
        assertNull("军令状不生效", PlanPledgeBoard.activeSnapshot())
        assertTrue("回灌要说明未立状：${out.text}", out.text.contains("未立状"))
        assertTrue("计划照常落库", store.inserts.size == 1)
        // 无状任务的 closeout 按无状处理（不许事后补状掩盖漏项）
        val late = c.submit(
            planArgs("甲" to "done", "乙" to "todo", "丙" to "todo", "丁" to "todo", "戊" to "todo")
                .put("closeout", closeoutOf(closeoutItem("甲", "done") { put("evidence", "e") })),
        ) as ToolOutcome.Ok
        assertTrue(late.text.contains("无状任务"))
        assertNull(PlanPledgeBoard.settle(setOf("甲"), 1L))
    }

    @Test
    fun `②_长程缺pledge_拒绝并教补法_不落库`() {
        val store = CStore()
        val c = controller(store)
        val out = c.submit(planArgs("甲" to "todo", "乙" to "todo", "丙" to "todo", "丁" to "todo", "戊" to "todo"))
        assertTrue("长程缺 pledge 是 Error 不是 Skip：$out", out is ToolOutcome.Failed)
        assertTrue((out as ToolOutcome.Failed).error.contains("acceptance"))
        assertTrue(store.inserts.isEmpty())
    }

    @Test
    fun `②_短任务带pledge_说明性忽略_计划照落`() {
        val store = CStore()
        val c = controller(store)
        val out = c.submit(planArgs("甲" to "todo", "乙" to "todo", "丙" to "todo").put("pledge", pledgeOf("甲", "乙", "丙")))
        assertTrue(out is ToolOutcome.Ok)
        assertTrue((out as ToolOutcome.Ok).text.contains("短任务不立状"))
        assertEquals(1, store.inserts.size)
    }

    // ── C 组 · closeout 与收口（判据③④） ────────────────────────────────────

    @Test
    fun `③_漏项收口判未完成_缺口进未办成单`() {
        val dir = tmpDir()
        PlanPledgeBoard.attach(listOf(dir))
        val c = controller()
        pledgeViaCard(c, longPlan("甲凭", "乙凭", "丙凭", "丁凭", "戊凭"))
        // 只申报甲 done 带证据，其余四项没申报
        val out = c.submit(
            planArgs("甲" to "done", "乙" to "todo", "丙" to "todo", "丁" to "todo", "戊" to "todo")
                .put("closeout", closeoutOf(closeoutItem("甲", "done") { put("evidence", "产物/a.md") })),
        )
        assertTrue(out is ToolOutcome.Ok)
        val verdict = PlanPledgeBoard.settle(setOf("甲"), 123L)!!
        assertFalse("漏了四项 ⇒ 任务判未完成", verdict.complete)
        assertEquals(listOf("乙", "丙", "丁", "戊"), verdict.gaps.map { it.text })
        // 账行：settle 行 complete=false（不许静默结项）
        val rows = File(dir, PlanPledgeBoard.LEDGER_FILE).readLines().filter { it.isNotBlank() }
        val settle = rows.map { JSONObject(it) }.last { it.optString("event") == PlanPledgeRules.EVENT_SETTLE }
        assertFalse(settle.optBoolean("complete"))
        // 未办成单：缺口逐行进单（负例⑤的落点）
        val unfinished = File(dir, ScoutLimits.UNFINISHED_FILE).readLines().filter { it.isNotBlank() }
        assertEquals("缺口一项一行", 4, unfinished.size)
        assertTrue(unfinished.any { it.contains("乙") && it.contains("pledge:${verdict.snapshotId}") })
    }

    @Test
    fun `③_DONE缺证据_收口按缺证据记缺口_不算结局`() {
        val c = controller()
        pledgeViaCard(c, longPlan("甲凭", "乙凭", "丙凭", "丁凭", "戊凭"))
        c.submit(
            planArgs("甲" to "done", "乙" to "todo", "丙" to "todo", "丁" to "todo", "戊" to "todo")
                .put("closeout", closeoutOf(closeoutItem("甲", "done"))),
        )
        val verdict = PlanPledgeBoard.settle(emptySet(), 1L)!!
        assertFalse(verdict.complete)
        assertEquals("缺证据", verdict.gaps.single { it.text == "甲" }.missing)
    }

    @Test
    fun `④_abandoned缺原因_申报层就拒_收口有账行`() {
        val dir = tmpDir()
        PlanPledgeBoard.attach(listOf(dir))
        val c = controller()
        pledgeViaCard(c, longPlan("甲凭", "乙凭", "丙凭", "丁凭", "戊凭"))
        val out = c.submit(
            planArgs("甲" to "todo", "乙" to "todo", "丙" to "todo", "丁" to "todo", "戊" to "todo")
                .put("closeout", closeoutOf(closeoutItem("甲", "abandoned"))),
        )
        assertTrue("判据⑤：空原因的弃单不收：$out", out is ToolOutcome.Failed)
        // 五项如实申报（两项 done 带证据＋三项弃单带原因）⇒ 收口完成，账上有 settle 行
        val ok = c.submit(
            planArgs("甲" to "done", "乙" to "todo", "丙" to "todo", "丁" to "done", "戊" to "todo")
                .put(
                    "closeout",
                    closeoutOf(
                        closeoutItem("甲", "done") { put("evidence", "产物/a.md") },
                        closeoutItem("乙", "abandoned") { put("reason", "blocked").put("detail", "等上游") },
                        closeoutItem("丙", "abandoned") { put("reason", "needs_human") },
                        closeoutItem("丁", "done") { put("evidence", "产物/b.md") },
                        closeoutItem("戊", "abandoned") { put("reason", "unsupported") },
                    ),
                ),
        )
        assertTrue(ok is ToolOutcome.Ok)
        val verdict = PlanPledgeBoard.settle(setOf("甲", "丁"), 9L)!!
        assertTrue("两项弃单（带原因）＋两项 done（带证据）＋一项 unsupported ＝完成", verdict.complete)
        val rows = File(dir, PlanPledgeBoard.LEDGER_FILE).readLines().filter { it.isNotBlank() }
        val settle = rows.map { JSONObject(it) }.last { it.optString("event") == PlanPledgeRules.EVENT_SETTLE }
        assertTrue(settle.optBoolean("complete"))
        assertEquals(5, settle.optJSONArray("items")!!.length())
        // 弃单也进未办成单（ABANDONED 的行；DONE 的不成行）
        val unfinished = File(dir, ScoutLimits.UNFINISHED_FILE).readLines().filter { it.isNotBlank() }
        assertEquals(3, unfinished.size)
        assertTrue(unfinished.any { it.contains("乙") && it.contains("受阻") })
    }

    @Test
    fun `③_无状任务closeout说明性忽略_不设门`() {
        val store = CStore()
        val c = controller(store)
        val out = c.submit(
            planArgs("甲" to "todo", "乙" to "todo", "丙" to "todo")
                .put("closeout", closeoutOf(closeoutItem("甲", "done") { put("evidence", "e") })),
        )
        assertTrue(out is ToolOutcome.Ok)
        assertTrue((out as ToolOutcome.Ok).text.contains("无状任务"))
        assertNull("无状 ⇒ 没有结项门，不编读数", PlanPledgeBoard.settle(emptySet(), 1L))
    }

    // ── D 组 · resign（判据⑤ ＋ 343 衔接） ───────────────────────────────────

    @Test
    fun `⑤_立状后结构变更没有resign_拒绝_计划保持原版`() {
        val store = CStore()
        val c = controller(store)
        pledgeViaCard(c, longPlan("甲凭", "乙凭", "丙凭", "丁凭", "戊凭"))
        c.authorizeNextChange()
        val out = c.submit(planArgs("甲" to "todo", "乙" to "todo", "丙" to "todo", "丁" to "todo"))
        assertTrue("冻结后删项必须显式重签：$out", out is ToolOutcome.Failed)
        assertTrue((out as ToolOutcome.Failed).error.contains("重签"))
        assertTrue("不许落库", store.inserts.size == 1)
        assertEquals(5, PlanPledgeBoard.activeSnapshot()!!.entries.size)
    }

    @Test
    fun `⑤_resign缺原因_拒_不许弃约不写为什么`() {
        val store = CStore()
        val c = controller(store)
        pledgeViaCard(c, longPlan("甲凭", "乙凭", "丙凭", "丁凭", "戊凭"))
        c.authorizeNextChange()
        val out = c.submit(planArgs("甲" to "todo", "乙" to "todo", "丙" to "todo", "丁" to "todo").put("resign", JSONObject()))
        assertTrue(out is ToolOutcome.Failed)
        assertTrue((out as ToolOutcome.Failed).error.contains("reason"))
        assertTrue(store.inserts.size == 1)
    }

    @Test
    fun `⑤_重签人批后新版生效_旧版留账_按新版结项`() {
        val dir = tmpDir()
        PlanPledgeBoard.attach(listOf(dir))
        val c = controller()
        val first = pledgeViaCard(c, longPlan("甲凭", "乙凭", "丙凭", "丁凭", "戊凭"))
        c.authorizeNextChange()
        val out = c.submit(
            planArgs("甲" to "todo", "乙" to "todo", "丙" to "todo")
                .put("resign", JSONObject().put("reason", "丁戊用户说不要了")),
        ) as ToolOutcome.Ok
        assertTrue("重签要过：${out.text}", out.text.contains("军令状重签"))
        val next = PlanPledgeBoard.activeSnapshot()!!
        assertEquals("版本 +1", 2, next.version)
        assertEquals(listOf("甲", "乙", "丙"), next.texts)
        assertEquals("幸存条目沿用旧验收方式", "甲凭", next.entries[0].accept)
        // 判据③：两个版本都在账上（freeze ＋ resign 两行，旧快照原样留着）
        val rows = File(dir, PlanPledgeBoard.LEDGER_FILE).readLines().filter { it.isNotBlank() }
            .map { JSONObject(it) }
        assertEquals("freeze", rows[0].optString("event"))
        assertEquals("resign", rows[1].optString("event"))
        assertEquals(first.id, rows[0].optString("id"))
        assertEquals("计划变更", rows[1].optString("change"))
        // 按新版本快照结项（343 衔接）：三项都申报 ⇒ 完成
        c.submit(
            planArgs("甲" to "done", "乙" to "done", "丙" to "done")
                .put(
                    "closeout",
                    closeoutOf(
                        closeoutItem("甲", "done") { put("evidence", "e1") },
                        closeoutItem("乙", "done") { put("evidence", "e2") },
                        closeoutItem("丙", "done") { put("evidence", "e3") },
                    ),
                ),
        )
        val verdict = PlanPledgeBoard.settle(setOf("甲", "乙", "丙"), 5L)!!
        assertTrue(verdict.complete)
        assertEquals(3, verdict.total)
    }

    // ── E 组 · 源级门禁（判据⑥ ＋ 不许自动立状的机器检查） ───────────────────

    private fun source(rel: String): String {
        val f = File(rel)
        return if (f.exists()) f.readText() else File("app/$rel").readText()
    }

    @Test
    fun `⑥_settle至少一个非测试调用点_在引擎收尾`() {
        val hits = source(ENGINE).split("\n").count { it.contains("PlanPledgeBoard.settle(") }
        assertTrue("收尾没调 settle＝军令状没上线（NOT_WIRED）：$hits", hits >= 1)
    }

    @Test
    fun `⑥_三个调用点的接线齐全_摘掉任何一处门禁当场红`() {
        val engine = source(ENGINE)
        assertTrue("schema 要挂上（decorate 再注册）", engine.contains("PlanPledgeSchemas.decorate(PlanTool.spec())"))
        assertTrue("开工卡要弹（askPledgeCard）", engine.contains("askPledgeCard(call)"))
        assertTrue("开工卡卡面用 pledgeRequest（照 242 房型）", engine.contains("PlanPledgeCards.pledgeRequest("))
        assertTrue("非法组合的引擎侧闸要在出卡之前", engine.contains("pledgeComboGate(call)"))
        assertTrue("跨会话要接回（seedPledge）", engine.contains("seedPledge(sessionId)"))
        val ctrl = source(CTRL)
        assertTrue("工具层要有非法组合判", ctrl.contains("PlanPledgeSchemas.comboError(args)"))
        assertTrue("申报要走判定层的 parseCloseout", ctrl.contains("PlanPledgeRules.parseCloseout(args)"))
    }

    @Test
    fun `⑥_不许自动立状_冻结只认落库后的那条路`() {
        val ctrl = source(CTRL)
        val cardStart = ctrl.indexOf("fun answerPledgeCard")
        val cardEnd = ctrl.indexOf("fun doneTexts")
        assertTrue(cardStart in 0 until cardEnd)
        val cardBody = ctrl.substring(cardStart, cardEnd)
        assertFalse("开工卡收场路径里不许有 freeze（她没点头之前不许立状）", cardBody.contains("PlanPledgeBoard.freeze("))
        // 冻结只发生在「落库成功之后」的那条路（pledgeAfterLanding）
        val landStart = ctrl.indexOf("private fun pledgeAfterLanding")
        val landEnd = ctrl.indexOf("private fun contractGoalOf")
        assertTrue(landStart in 0 until landEnd)
        assertTrue(ctrl.substring(landStart, landEnd).contains("PlanPledgeBoard.freeze("))
    }

    // ── F 组 · 跨会话 seed（312 §4.5） ────────────────────────────────────────

    @Test
    fun `seed_接回快照与轮数_别会话的行不接`() {
        val dir = tmpDir()
        val ledger = File(dir, PlanPledgeBoard.LEDGER_FILE)
        val snap = PlanPledgeRules.snapshotOf(itemsOf(listOf("甲", "乙", "丙")), listOf("甲凭", "乙凭", "丙凭"), 1, "目标", 1L, 42L)
        ledger.appendTextLn(PlanPledgeRules.encodeSnapshot(snap, PlanPledgeRules.EVENT_FREEZE).toString())
        // settle 行没有 session 字段（形状如此）——轮数跟它前面那版快照的会话走
        val verdict = PlanPledgeRules.settle(snap, emptyList(), emptySet(), emptyMap())
        ledger.appendTextLn(PlanPledgeRules.encodeSettle(verdict, snap, 2L).toString())
        val recovered = PlanPledgeSeed.read(42L, ledger)
        assertNotNull(recovered)
        assertEquals(snap.id, recovered!!.snapshot.id)
        assertEquals("三项全缺 ⇒ 每项第 1 次未过", 1, recovered.failures["甲"])
        assertNull("别会话不接（不编状）", PlanPledgeSeed.read(99L, ledger))
        assertNull("账不存在不接", PlanPledgeSeed.read(42L, null))
    }

    @Test
    fun `seed_重签后的新版接回_幸存项轮数带过来`() {
        val dir = tmpDir()
        val ledger = File(dir, PlanPledgeBoard.LEDGER_FILE)
        val v1 = PlanPledgeRules.snapshotOf(itemsOf(listOf("甲", "乙", "丙")), listOf("a", "b", "c"), 1, "目标", 1L, 7L)
        ledger.appendTextLn(PlanPledgeRules.encodeSnapshot(v1, PlanPledgeRules.EVENT_FREEZE).toString())
        val v1Verdict = PlanPledgeRules.settle(v1, emptyList(), emptySet(), emptyMap())
        ledger.appendTextLn(PlanPledgeRules.encodeSettle(v1Verdict, v1, 2L).toString())
        val v2 = PlanPledgeRules.snapshotOf(itemsOf(listOf("甲", "乙")), listOf("a", "b"), 2, "目标", 3L, 7L)
        ledger.appendText(
            PlanPledgeRules.encodeSnapshot(
                v2,
                PlanPledgeRules.EVENT_RESIGN,
                added = emptyList(),
                removed = listOf("丙"),
                why = "用户说不要丙了",
                channel = PlanPledgeRules.ResignChannel.HUMAN,
            ).toString(),
        )
        val recovered = PlanPledgeSeed.read(7L, ledger)!!
        assertEquals(2, recovered.snapshot.version)
        assertEquals(listOf("甲", "乙"), recovered.snapshot.texts)
        assertEquals("幸存项的轮数不清零", 1, recovered.failures["甲"])
    }
}
