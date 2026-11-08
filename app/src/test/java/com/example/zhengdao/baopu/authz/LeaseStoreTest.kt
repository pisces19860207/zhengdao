// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第四百六十七批 · 租约内存账：会话隔离 / 淘汰顺序 / 计次上限 / 五种失效 / 越权降权。
package com.example.zhengdao.baopu.authz

import java.io.File
import java.nio.file.Files
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LeaseStoreTest {

    private lateinit var root: File
    private lateinit var dir: File
    private lateinit var other: File

    private val ttl = 4L * 3_600_000L
    private val t0 = 1_700_000_000_000L

    @Before
    fun setUp() {
        root = Files.createTempDirectory("baopu467-store").toFile()
        dir = File(root, "产物").apply { mkdirs() }
        other = File(root, "别处").apply { mkdirs() }
        LeaseLedger.installRoot { root }
    }

    @After
    fun tearDown() {
        LeaseStore.resetForTest()
        root.deleteRecursively()
    }

    private fun offer(cap: Int = 10, ttlMs: Long = ttl, scopeDir: File = dir): LeaseOffer =
        LeaseOffer("files/delete", LeaseScope(LeaseScope.Kind.DIR, scopeDir.canonicalFile.path), cap, ttlMs)

    private fun grant(session: Long = 1L, cap: Int = 10, card: String = "card-1", at: Long = t0): LeaseGrant =
        LeaseStore.grant(offer(cap = cap), session, card, at)

    private fun hit(
        session: Long = 1L,
        targets: List<File> = listOf(File(dir, "a.md")),
        cost: Int = 1,
        op: String = "op-1",
        at: Long = t0 + 1000L,
    ): LeaseVerdict = LeaseStore.hit(session, "files/delete", targets, JSONObject(), cost, op, at)

    private fun kinds(id: String): List<String> = LeaseLedger.rowsFor(id).map { it.optString("kind") }

    // ── 判据①：两个会话各一张租约，A 的在 B 不命中 ─────────────────────────

    @Test
    fun `判据①_会话隔离_A的租约在B不命中`() {
        grant(session = 1L)
        grant(session = 2L, card = "card-2")
        val a = hit(session = 1L)
        assertTrue("A 会话自己命中", a is LeaseVerdict.Hit)
        LeaseStore.revoke(1L, null, "user", t0 + 2000L)
        val b = hit(session = 2L, op = "op-2", at = t0 + 3000L)
        assertTrue("B 会话自己的那张还在，照常命中", b is LeaseVerdict.Hit)
        val stranger = hit(session = 99L, op = "op-99")
        assertTrue(stranger is LeaseVerdict.Miss)
        assertEquals(LeaseMissReason.NOT_GRANTED, (stranger as LeaseVerdict.Miss).reason)
    }

    @Test
    fun `会话隔离_别的会话有租约但没有这一类_CATEGORY_MISMATCH`() {
        LeaseStore.grant(
            LeaseOffer("edit/write", LeaseScope(LeaseScope.Kind.WORKSPACE, root.path), 5, ttl),
            7L,
            "card-x",
            t0,
        )
        val v = hit(session = 7L)
        assertEquals(LeaseMissReason.CATEGORY_MISMATCH, (v as LeaseVerdict.Miss).reason)
    }

    // ── 判据②：MAX_LIVE 打满时先淘汰其它会话，本会话最新的还在 ──────────────

    @Test
    fun `判据②_打满先淘汰其它会话_本会话最新的还在`() {
        val mine = (1..LeaseLimits.MAX_LIVE).map { i -> grant(session = 1L, card = "c$i", at = t0 + i) }
        val newest = mine.last()
        grant(session = 2L, card = "other", at = t0 + 999)
        assertTrue("新会话那张必须在", LeaseStore.active(2L).isNotEmpty())
        assertTrue("本会话最新的必须还在", LeaseStore.active(1L).any { it.id == newest.id })
        assertEquals("本会话被挤掉一张", LeaseLimits.MAX_LIVE - 1, LeaseStore.active(1L).size)
        val evicted = LeaseLedger.replay().filter { it.optString("why") == "evicted" }
        assertEquals("淘汰落了一行账", 1, evicted.size)
    }

    @Test
    fun `判据②_别的会话先被淘汰_不先动自己的`() {
        grant(session = 2L, card = "other", at = t0 - 5000L)
        val mine = (1..(LeaseLimits.MAX_LIVE - 1)).map { i -> grant(session = 1L, card = "c$i", at = t0 + i) }
        grant(session = 1L, card = "newest", at = t0 + 999)
        assertTrue("其它会话先走", LeaseStore.active(2L).isEmpty())
        assertTrue("但自己的全在", mine.all { g -> LeaseStore.active(1L).any { it.id == g.id } })
    }

    // ── 判据③：单次最多扣 2（10 个目标只扣 2 次）────────────────────────────

    @Test
    fun `判据③_10个目标只扣2次`() {
        grant(cap = 10)
        val ten = (1..10).map { File(dir, "f$it.md") }
        val v = hit(targets = ten, cost = LeaseRules.costOf(ten))
        assertTrue(v is LeaseVerdict.Hit)
        assertEquals(2, (v as LeaseVerdict.Hit).cost)
        assertEquals(2, LeaseStore.active(1L).first().used)
        assertEquals(8, v.remaining)
    }

    // ── 五种失效：M1 过期 / M2 用尽 / M3 越界 / M4 撤销 / M5 重启 ────────────

    @Test
    fun `M1_过期_恢复弹卡且落 revoke 行`() {
        val g = grant()
        val v = hit(at = t0 + ttl)
        assertEquals(LeaseMissReason.EXPIRED, (v as LeaseVerdict.Miss).reason)
        assertTrue("过期落一行 revoke/expired", kinds(g.id).contains(LeaseKinds.REVOKE))
        assertTrue(LeaseLedger.rowsFor(g.id).any { it.optString("why") == "expired" })
    }

    @Test
    fun `M2_用尽_恢复弹卡但不落 revoke`() {
        val g = grant(cap = 2)
        assertTrue(hit(op = "op-1") is LeaseVerdict.Hit)
        assertTrue(hit(op = "op-2") is LeaseVerdict.Hit)
        val v = hit(op = "op-3")
        assertEquals(LeaseMissReason.EXHAUSTED, (v as LeaseVerdict.Miss).reason)
        assertTrue("用尽不是撤销", kinds(g.id).none { it == LeaseKinds.REVOKE })
    }

    @Test
    fun `M2_余量恰好等于 cost 仍命中_差一次就用尽`() {
        grant(cap = 2)
        assertTrue(hit(cost = 2, op = "op-1") is LeaseVerdict.Hit)
        assertEquals(LeaseMissReason.EXHAUSTED, (hit(cost = 1, op = "op-2") as LeaseVerdict.Miss).reason)
    }

    @Test
    fun `M3_越界_恢复弹卡且不撤销`() {
        val g = grant()
        val v = hit(targets = listOf(File(other, "x.md")))
        assertEquals(LeaseMissReason.OUT_OF_SCOPE, (v as LeaseVerdict.Miss).reason)
        assertTrue("越界不杀整张", LeaseStore.active(1L).any { it.id == g.id })
    }

    @Test
    fun `M4_撤销_立即失效`() {
        val g = grant()
        assertEquals(1, LeaseStore.revoke(1L, null, "user", t0 + 500))
        assertEquals(LeaseMissReason.REVOKED, (hit() as LeaseVerdict.Miss).reason)
        assertTrue(LeaseLedger.rowsFor(g.id).any { it.optString("why") == "user" })
    }

    @Test
    fun `M5_重启不复活_盘上有 grant 行也照样未命中`() {
        val g = grant()
        assertTrue(LeaseLedger.rowsFor(g.id).isNotEmpty())
        // 进程重启：内存清空，但账本落点与文件都还在
        LeaseStore.resetForTest()
        LeaseLedger.installRoot { root }
        assertEquals(LeaseMissReason.NOT_GRANTED, (hit() as LeaseVerdict.Miss).reason)
        assertTrue("账没被抹掉", LeaseLedger.rowsFor(g.id).isNotEmpty())
    }

    // ── 自进化：越权 3 次降一档、再 3 次撤销（定点 D10）─────────────────────

    @Test
    fun `P6_越权三次降一档_再三次撤销`() {
        val g = grant(cap = 10)
        (1..LeaseLimits.VIOLATION_STEP).forEach { i ->
            LeaseStore.noteRejected(1L, "files/delete", "risk_matrix", "delete_path", "v$i", t0 + i)
        }
        assertEquals("cap 减半（10 → 5）", 5, LeaseStore.active(1L).first().cap)
        assertTrue(LeaseLedger.rowsFor(g.id).any { it.optString("kind") == LeaseKinds.DOWNGRADE })
        ((LeaseLimits.VIOLATION_STEP + 1)..(LeaseLimits.VIOLATION_STEP * 2)).forEach { i ->
            LeaseStore.noteRejected(1L, "files/delete", "risk_matrix", "delete_path", "v$i", t0 + i)
        }
        assertTrue("再满一轮就撤", LeaseStore.active(1L).isEmpty())
        assertEquals(LeaseMissReason.REVOKED, (hit() as LeaseVerdict.Miss).reason)
    }

    @Test
    fun `P5_不是三道闸的拒_不算越权`() {
        grant(cap = 10)
        listOf("mcp_unknown", "halt", "group_locked", null).forEach { g ->
            assertNull(LeaseStore.noteRejected(1L, "files/delete", g, "delete_path", "op-$g", t0 + 1))
        }
        assertEquals("一次都不该降", 10, LeaseStore.active(1L).first().cap)
    }

    @Test
    fun `P5_同一笔 opId 只计一次越权`() {
        grant(cap = 10)
        repeat(3) { LeaseStore.noteRejected(1L, "files/delete", "task_grant", "delete_path", "same-op", t0 + 1) }
        assertEquals("去重后只有 1 次", 10, LeaseStore.active(1L).first().cap)
    }

    // ── 账：授予/使用/结果/撤销 各一行，可按 lease id 回放 ─────────────────

    @Test
    fun `账_用一次两行_可按 lease id 与 op 回放`() {
        val g = grant()
        assertTrue(hit(op = "op-A") is LeaseVerdict.Hit)
        val use = LeaseStore.takePending("op-A")
        assertNotNull(use)
        LeaseStore.noteUsed(use!!, "delete_path", "ok", t0 + 2000L)
        val rows = LeaseLedger.rowsFor(g.id)
        assertEquals(
            listOf(LeaseKinds.GRANT, LeaseKinds.USE, LeaseKinds.OUTCOME),
            rows.map { it.optString("kind") },
        )
        assertEquals("ok", LeaseLedger.rowsForOp("op-A").last().optString("result"))
        assertTrue("账落在 产物/.leases.jsonl", LeaseLedger.fileIn(root).isFile)
    }

    @Test
    fun `账_没有 pending 的动作不落 use`() {
        grant()
        LeaseStore.noteUsed(LeaseUse("nope", "files/delete", 1, "op-Z"), "delete_path", "ok", t0)
        assertTrue(LeaseLedger.rowsForOp("op-Z").isEmpty())
    }
}
