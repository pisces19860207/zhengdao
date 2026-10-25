// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百零八批 §1／§3：决策记录流（`决策记录.jsonl`）的判据与负例。
//   正例：一条记录写下去 ⇒ 形状齐全（kind/actor/reason/evidence/before/after/at/session/
//         reversible/rollbackRef），读回来逐字段相等；
//   负例（各红）：缺 reason／缺证据 id／丢一条坏行让整本账读不出来／
//        把不可回滚的 kind 写成可回滚／回滚失败却留了 `rollback_of`／自造 kind 被当别的类渲染。
package com.example.zhengdao.baopu

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class DecisionLedgerTest {

    private lateinit var root: File

    @Before
    fun setUp() {
        root = File.createTempFile("zd208-ledger", "").let { it.delete(); it.mkdirs(); it }
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun draft(
        kind: DecisionKind = DecisionKind.SKILL_PROMOTE,
        reason: String = "转正：满3次",
        evidence: String = "skill:project/产物清理",
        atMs: Long = 1_760_000_000_000L,
        reversible: Boolean? = null,
        sessionId: Long = 7L,
        rollbackRef: String = "/ws/技能/产物清理/SKILL.md",
        actor: DecisionActor = DecisionActor.AUTO,
        rollbackOf: String? = null,
        groupOverride: DecisionGroup? = null,
    ) = DecisionDraft(
        kind = kind,
        actor = actor,
        reason = reason,
        evidence = evidence,
        before = "draft",
        after = "active",
        atMs = atMs,
        sessionId = sessionId,
        rollbackRef = rollbackRef,
        reversible = reversible,
        rollbackOf = rollbackOf,
        groupOverride = groupOverride,
    )

    // ── 正例：形状齐全、读回来一致 ───────────────────────────────────────

    @Test
    fun `L1_一条记录写下去_形状十个字段全在_读回来逐字段相等`() {
        val rec = DecisionLedger.recordTo(root, draft())!!
        assertEquals(DecisionKind.SKILL_PROMOTE, rec.kind)
        assertEquals(DecisionGroup.SKILL, rec.group)
        assertEquals(DecisionActor.AUTO, rec.actor)
        assertEquals("skill:project/产物清理", rec.evidence)
        assertTrue(rec.reason.contains("满3次"))
        assertEquals(7L, rec.sessionId)
        assertEquals("/ws/技能/产物清理/SKILL.md", rec.rollbackRef)
        assertTrue("转正可回滚", rec.reversible)
        assertNull(rec.rollbackOf)

        val back = DecisionLedger.read(root).single()
        assertEquals(rec, back)
    }

    @Test
    fun `L2_十类都能落_每类都有证据id与reason`() {
        DecisionKind.REQUIRED.forEachIndexed { i, k ->
            val r = DecisionLedger.recordTo(
                root,
                draft(kind = k, reason = "第${i}类", evidence = "ev:$i", atMs = 1_760_000_000_000L + i),
            )
            assertNotNull("${k.wire} 必须能落", r)
        }
        val all = DecisionLedger.read(root)
        assertEquals(DecisionKind.REQUIRED.size, all.size)
        assertTrue("每条都有证据 id", all.all { it.evidence.isNotBlank() })
        assertTrue("每条都有 reason", all.all { it.reason.isNotBlank() })
    }

    // ── 负例②：缺 reason／缺证据 id ⇒ 一个字节都不写 ─────────────────────

    @Test
    fun `L3_负例_缺reason或证据id_整条不落_账本零字节`() {
        assertNull(DecisionLedger.recordTo(root, draft(reason = "  ")))
        assertNotNull(DecisionLedger.lastError)
        assertNull(DecisionLedger.recordTo(root, draft(evidence = "")))
        assertTrue(DecisionLedger.read(root).isEmpty())
        assertFalse("文件都不该建出来", DecisionLedger.fileIn(root).exists())
    }

    // ── 负例③的反面：reversible 只能降级，不能升级 ─────────────────────

    @Test
    fun `L4_reversible只能降级_真删类永远不可回滚_通用动作可降为不可回滚`() {
        val purge = DecisionLedger.recordTo(root, draft(kind = DecisionKind.DIARY_PURGE, reversible = true))!!
        assertFalse("真删不许被改成可回滚", purge.reversible)
        assertEquals("不可回滚就不许留回滚引用", "", purge.rollbackRef)

        val auto = DecisionLedger.recordTo(root, draft(kind = DecisionKind.AUTO_ACT, reversible = false))!!
        assertFalse("通用写盘可降级为不可回滚", auto.reversible)

        val promote = DecisionLedger.recordTo(root, draft(reversible = true))!!
        assertTrue(promote.reversible)
    }

    // ── 回滚：成功才留 rollback_of；失败不留、不标成功 ────────────────────

    @Test
    fun `L5_回滚成功多一条rollback_of_指向被回滚那条_组跟着目标走`() {
        val target = DecisionLedger.recordTo(root, draft())!!
        val called = java.util.concurrent.atomic.AtomicInteger(0)
        val ok = DecisionRollbacks.run(
            target = target,
            root = root,
            handler = DecisionRollbackHandler { called.incrementAndGet(); true },
            atMs = 1_760_000_100_000L,
        )
        assertTrue(ok)
        assertEquals(1, called.get())
        val all = DecisionLedger.read(root)
        val rollback = all.single { it.kind == DecisionKind.ROLLBACK_OF }
        assertEquals(target.id, rollback.rollbackOf)
        assertEquals("回滚行要跟目标同组", DecisionGroup.SKILL, rollback.group)
        assertFalse("回滚行本身不可回滚", rollback.reversible)
    }

    @Test
    fun `L6_负例_回滚失败不留痕也不标成功`() {
        val target = DecisionLedger.recordTo(root, draft())!!
        val ok = DecisionRollbacks.run(
            target = target,
            root = root,
            handler = DecisionRollbackHandler { false },
            atMs = 1_760_000_100_000L,
        )
        assertFalse(ok)
        assertTrue(
            "失败不许留 rollback_of",
            DecisionLedger.read(root).none { it.kind == DecisionKind.ROLLBACK_OF },
        )
    }

    @Test
    fun `L7_不可回滚的条目_回滚对话一个字都不调`() {
        val purge = DecisionLedger.recordTo(root, draft(kind = DecisionKind.DIARY_PURGE))!!
        var called = false
        val ok = DecisionRollbacks.run(
            target = purge,
            root = root,
            handler = DecisionRollbackHandler { called = true; true },
            atMs = 1_760_000_100_000L,
        )
        assertFalse(ok)
        assertFalse("不可回滚就不该进执行口", called)
    }

    // ── 负例⑤：自造 kind 不许被当成别的类 ────────────────────────────────

    @Test
    fun `L8_负例_自造kind读不进来_也不冒充别的类_坏行不炸整本账`() {
        DecisionLedger.recordTo(root, draft())!!
        val f = DecisionLedger.fileIn(root)
        f.appendText(
            """{"v":1,"id":"x1","kind":"delete_everything","actor":"auto","reason":"r","evidence":"e","at":1}""" + "\n" +
                """{"v":1,"id":"x2","kind":"skill_promote","actor":"auto","reason":"","evidence":"e","at":1}""" + "\n" +
                "{ 这不是 JSON }\n",
            Charsets.UTF_8,
        )
        val all = DecisionLedger.read(root)
        assertEquals("三行坏账都被跳过，只剩那一条真的", 1, all.size)
        assertNull(DecisionKind.ofWire("delete_everything"))
    }

    // ── 行格式：键名固定（改了老账本就读不出来） ──────────────────────────

    @Test
    fun `L9_行键名固定_十个字段一个字不差`() {
        val rec = DecisionLedger.recordTo(root, draft())!!
        val o = org.json.JSONObject(DecisionLedger.fileIn(root).readText(Charsets.UTF_8).trim())
        listOf(
            "v", "id", "kind", "group_if_known", "actor", "reason", "evidence",
            "before", "after", "at", "session", "reversible", "rollback_ref", "rollback_of",
        ).forEach { key -> assertTrue("缺键 $key", o.has(key)) }
        assertEquals(DecisionRecord.VERSION, o.getInt("v"))
        assertEquals(rec.id, o.getString("id"))
    }

    @Test
    fun `L10_同毫秒同证据的两条_id也不同_行不会互相覆盖`() {
        val a = DecisionLedger.recordTo(root, draft())!!
        val b = DecisionLedger.recordTo(root, draft())!!
        assertFalse("id 必须唯一（LazyColumn 同键会崩）", a.id == b.id)
        assertEquals(2, DecisionLedger.read(root).size)
    }

    @Test
    fun `L11_没装配落点就不落_留一句话而不是静默`() {
        // 本测试进程从未 install（root=null）⇒ record 返回 null 且 lastError 有话说
        assertNull(DecisionLedger.installedRoot())
        assertNull(DecisionLedger.record(draft()))
        assertNotNull("不许静默丢账", DecisionLedger.lastError)
    }
}
