// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百七十六批 Q2 补（2026-10-29 用户裁定「P1 补，但只补最简版」）：
// 「开工卡上一行『撤销本单』→ 确认 → 调撤销 API → 落账」这条路的**行为**。
//
// ## 这一份证明的是源级门禁证明不了的两件事
// `TaskGrantRevokeRowGateTest` 只能钉"形状"（是一行、只有这一个入口、第一下不碰授权）。
// 她给 Q2 的第二条理由是「它正好验证 Binding 真能定位」——那是一句**行为**承诺：
// 三元组对不对，只有真开一单、真按一次、真读一次 208 才算数。所以本文件全程真临时目录、
// 真 JSONL，一个 `File` 都不假装（与 `TaskGrantWireBehaviorTest` 同一条纪律）。
//
// ## 四类断言（每一条都对应她写在裁定里的一句话）
// · 「确认」那一下之前**一点都没动**（`nextRevokeStage` 的唯一口径）；
// · 交出去的是**重算**的三元组：同一份批准永远同一个值，别的单的三元组撤不掉这一单；
// · 撤成 ⇒ 208 里**恰好一条** `rollback_of`，指向开单那条批准行，批准行本身不删；
// · 撤不成（拦下／指不出目标／账本没装配）⇒ 不许把已经发生的撤销翻回来，但**要落一句话**。
package com.example.zhengdao.baopu.authz

import com.example.zhengdao.baopu.DecisionActor
import com.example.zhengdao.baopu.DecisionKind
import com.example.zhengdao.baopu.DecisionLedger
import com.example.zhengdao.baopu.DecisionRecord
import com.example.zhengdao.baopu.RiskClass
import com.example.zhengdao.baopu.plan.PlanContract
import com.example.zhengdao.baopu.plan.PlanStep
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

class TaskGrantRevokeFlowTest {

    private lateinit var ws: File
    private lateinit var ledgerRoot: File
    private val epoch get() = ProcessEpoch.current

    @Before
    fun setup() {
        ws = Files.createTempDirectory("baopu-q2-ws").toFile()
        ledgerRoot = Files.createTempDirectory("baopu-q2-ledger").toFile()
        DecisionLedger.install(ledgerRoot)
        TaskGrantRuntime.resetForTest()
        TaskGrantBoard.resetForTest()
    }

    @After
    fun teardown() {
        TaskGrantRuntime.resetForTest()
        TaskGrantBoard.resetForTest()
        DecisionLedger.uninstallForTest()
        listOf(ws, ledgerRoot).forEach { it.deleteRecursively() }
    }

    // ── 夹具（与 TaskGrantWireBehaviorTest 同一条生产路径：候选 → 记批准 → 装快照 → 认单） ──

    private fun touch(rel: String, text: String = "初稿"): File =
        File(ws, rel).also { f ->
            f.parentFile?.mkdirs()
            f.writeText(text)
        }

    private fun contract(touches: List<String>, goal: String = "整理稿子"): PlanContract = PlanContract(
        goal = goal,
        steps = listOf(PlanStep(1, "写第一稿", touches.take(1), RiskClass.R2)),
        touches = touches,
        readonly = emptyList(),
        expectedAsks = 0,
        stopIf = emptyList(),
    )

    private fun open(touches: List<String>): GrantOffer {
        val now = TaskGrantWire.monoMs()
        val offer = TaskGrantWire.buildOffer(contract(touches), ws, 7L, epoch, now, System.currentTimeMillis())
            .first ?: throw AssertionError("这一轮没生成候选单，开不了")
        val r = TaskGrantWire.openGrant(offer, defaultGrantSelection(offer.facts), ws, epoch, now, System.currentTimeMillis())
        assertTrue("开单没成：${r.reason}", r.installed)
        return TaskGrantBoard.currentOffer()!!
    }

    /** 208 里那一类行（现读，新→旧）。 */
    private fun rows(kind: DecisionKind): List<DecisionRecord> =
        DecisionLedger.read(ledgerRoot).filter { it.kind == kind }

    // ── ①「确认」之前一点都没动 ────────────────────────────────────────────

    @Test
    fun 第一下只把确认摆出来() {
        assertEquals("IDLE 上那一下只推进到确认", RevokeStage.ARMED, nextRevokeStage(RevokeStage.IDLE))
        assertEquals("确认那一格不往前推（再点才是动手，动手由卡面另走）", RevokeStage.ARMED, nextRevokeStage(RevokeStage.ARMED))
        assertEquals("按到底之后不再有下一次", RevokeStage.DONE, nextRevokeStage(RevokeStage.DONE))
        assertEquals("三格就是这三格（多一格＝多一个没说过的状态）", 3, RevokeStage.entries.size)
    }

    @Test
    fun 确认那一格不碰授权() {
        // 摆出确认（stage 从 IDLE 推到 ARMED）之后，授权必须还在生效、板上必须还认这一单。
        touch("notes/a.md")
        val offer = open(listOf("notes"))
        val now = TaskGrantWire.monoMs()
        assertEquals(RevokeStage.ARMED, nextRevokeStage(RevokeStage.IDLE))
        assertNotNull("只是把『再点一次确认撤销』摆出来，不该有任何东西被收走", TaskGrantRuntime.active(now))
        assertEquals(offer.grant.grantId, TaskGrantBoard.openedGrantId())
        assertEquals("确认这一格不许留下回滚账", 0, rows(DecisionKind.ROLLBACK_OF).size)
        assertEquals("也不许动批准行", 1, rows(DecisionKind.TOOL_GRANT).size)
    }

    // ── ② 交出去的是重算的三元组（她说：三元组对不对，一按就知道） ────────────

    @Test
    fun 重算的三元组与开单那份同源() {
        touch("notes/a.md")
        val offer = open(listOf("notes"))
        assertEquals(
            "卡上这份批准按同一个构造重算 ⇒ 与开单时绑上的那份逐格相同（否则 L26 比的是两份口径）",
            offer.binding,
            TaskGrantRevokeFlow.tripleOf(offer),
        )
        assertEquals(
            "重算出来的那份，与**装着的那一份**快照的绑定同源（执行路径比的也是它）",
            TaskGrantWire.bindingOf(TaskGrantRuntime.current()!!.grant),
            TaskGrantRevokeFlow.tripleOf(offer),
        )
    }

    @Test
    fun 按一次就撤成并把这一单从板上收走() {
        touch("notes/a.md")
        val offer = open(listOf("notes"))
        val now = TaskGrantWire.monoMs()
        val logs = mutableListOf<String>()
        val r = TaskGrantRevokeFlow.revokeNow(offer, now, WALL, logs::add)
        assertTrue("三元组相符却没撤成：${r.note}", r.revoked)
        assertEquals("撤成时不带回执那句话（话由卡面从资源里取）", "", r.note)
        assertTrue("撤成必须落账：$logs", r.accounted)
        assertNull(TaskGrantRuntime.active(now))
        assertEquals(GrantEndReason.USER_REVOKED, TaskGrantRuntime.current()!!.endReason)
        assertEquals("", TaskGrantBoard.openedGrantId())
        assertNull(TaskGrantBoard.bindingOfOpened())
        assertEquals("", TaskGrantBoard.revokeBrief())
        assertFalse("撤成后板上不该再挂着候选（卡面条件不再成立）", TaskGrantBoard.hasOffer())
        assertTrue("要有一句可查的 log：$logs", logs.any { it.contains("撤销已落账") })
    }

    @Test
    fun 别的单的三元组撤不掉这一单() {
        touch("notes/a.md")
        touch("docs/b.md")
        val offer = open(listOf("notes"))
        val now = TaskGrantWire.monoMs()
        // 「旧 offer 对新单」：另一份计划开出来的候选单（对象清单不同）拿来撤这一单。
        val stale = TaskGrantWire.buildOffer(contract(listOf("docs")), ws, 7L, epoch, now, WALL).first!!
        assertTrue("另一份批准的三元组不该指向这一单", TaskGrantRevokeFlow.tripleOf(stale) != offer.binding)
        val logs = mutableListOf<String>()
        val r = TaskGrantRevokeFlow.revokeNow(stale, now, WALL, logs::add)
        assertFalse(r.revoked)
        assertTrue("拦下的那句话要能说出差在哪一格：${r.note}", r.note.contains("不许按文本匹配撤销"))
        assertNotNull("撤错了对象＝把还在生效的授权关掉，绝对不行", TaskGrantRuntime.active(now))
        assertEquals(offer.grant.grantId, TaskGrantBoard.openedGrantId())
        assertEquals("拦下不落回滚账", 0, rows(DecisionKind.ROLLBACK_OF).size)
        assertTrue("拦下也是事实，要 log 出来：$logs", logs.any { it.contains("撤销被拦") })
        assertTrue("拦下的那句要留在板上供回读：${TaskGrantBoard.revokeBrief()}",
            TaskGrantBoard.revokeBrief().contains("不许按文本匹配撤销"))
    }

    @Test
    fun 撤第二次给的是没这一单() {
        touch("notes/a.md")
        val offer = open(listOf("notes"))
        val now = TaskGrantWire.monoMs()
        assertTrue(TaskGrantRevokeFlow.revokeNow(offer, now, WALL).revoked)
        val second = TaskGrantRevokeFlow.revokeNow(offer, now, WALL)
        assertFalse("同一张单不撤第二遍", second.revoked)
        assertEquals("没有开着的单可撤", second.note)
        assertFalse(second.accounted)
        assertEquals("第二遍不许补出第二条回滚账", 1, rows(DecisionKind.ROLLBACK_OF).size)
    }

    // ── ③ 撤成 ⇒ 208 里恰好一条 rollback_of，指向开单那条批准 ────────────────

    @Test
    fun 撤成落的回滚账指向批准行() {
        touch("notes/a.md")
        val offer = open(listOf("notes"))
        val approval = rows(DecisionKind.TOOL_GRANT).first()
        assertEquals("快照里的批准行 id 就是那条真记录", approval.id, offer.grant.approvalEventId)

        assertTrue(TaskGrantRevokeFlow.revokeNow(offer, TaskGrantWire.monoMs(), WALL).accounted)

        val rollback = rows(DecisionKind.ROLLBACK_OF)
        assertEquals("一次撤销一条，不多不少", 1, rollback.size)
        val row = rollback.first()
        assertEquals("撤销本单", row.reason)
        assertEquals(approval.id, row.evidence)
        assertEquals(approval.id, row.rollbackOf)
        assertEquals(DecisionActor.USER, row.actor)
        assertEquals(7L, row.sessionId)
        assertEquals(WALL, row.atMs)
        assertFalse("回滚本身不可再回滚", row.reversible)
        assertEquals("批准行不删（撤的是授权，不是那段历史）", 1, rows(DecisionKind.TOOL_GRANT).size)
        val kept = DecisionLedger.read(ledgerRoot).first { it.id == approval.id }
        assertTrue("208 的读侧要把批准行标成已回滚（G3 那一格）", kept.rolledBack)
    }

    // ── ④ 撤成了但账落不上：说出来，不许把撤销翻回来 ─────────────────────────

    @Test
    fun 指不出批准行就不落账但撤销照完成() {
        touch("notes/a.md")
        val offer = open(listOf("notes"))
        val now = TaskGrantWire.monoMs()
        // approvalEventId 不进三元组（它不是"这件事是哪一份"的一格）⇒ 把它抹掉仍是同一件事。
        val blank = offer.copy(grant = offer.grant.copy(approvalEventId = ""))
        assertEquals(TaskGrantRevokeFlow.tripleOf(offer), TaskGrantRevokeFlow.tripleOf(blank))
        val logs = mutableListOf<String>()
        val r = TaskGrantRevokeFlow.revokeNow(blank, now, WALL, logs::add)
        assertTrue("指不出目标不许把已经撤销的授权翻回来", r.revoked)
        assertFalse(r.accounted)
        assertEquals("指不出目标＝不落 rollback_of", 0, rows(DecisionKind.ROLLBACK_OF).size)
        assertTrue("这一格要当场说出来：$logs", logs.any { it.contains("不落 rollback_of") })
        assertNull(TaskGrantRuntime.active(now))
    }

    @Test
    fun 账本没装配时撤销不抛且不假装落账() {
        touch("notes/a.md")
        val offer = open(listOf("notes"))
        DecisionLedger.uninstallForTest()
        val logs = mutableListOf<String>()
        val r = TaskGrantRevokeFlow.revokeNow(offer, TaskGrantWire.monoMs(), WALL, logs::add)
        assertTrue(r.revoked)
        assertFalse("没装配落点＝账没落成，不许报成落过了", r.accounted)
        assertTrue("要说的是决策记录那句：$logs", logs.any { it.contains("决策记录没写成") })
        assertNull(TaskGrantRuntime.active(TaskGrantWire.monoMs()))
    }

    private companion object {
        /** 固定的墙钟毫秒（回滚行的 `at` 要能逐格对上，不用 now 那种读不出的值）。 */
        const val WALL = 1_730_000_000_000L
    }
}
