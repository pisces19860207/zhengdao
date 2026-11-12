// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.baopu.readthrough

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 断点账本与网络闸单测（610 判据④⑥）。
 *
 * 钉住的判据：④ 网络质量差（失败率/连败/重连/RTT）⇒ 降并发或暂停，策略可复算；
 * ⑥ 断点续跑只跑缺的片——书变了（sha 变）或模式变了 ⇒ 账本作废，不拿旧账接新书。
 */
class CheckpointAndNetworkTest {

    private fun slices() = listOf(
        ReadSlice(0, "第一章", 0, 1, 0, 2),
        ReadSlice(0, "第一章", 1, 2, 3, 9),
        ReadSlice(1, "第二章", 0, 1, 10, 12),
        ReadSlice(2, "第三章", 0, 1, 13, 15),
    )

    // ── 断点账本 ────────────────────────────────────────────────────────────

    @Test
    fun `checkpoint_json往返_集合不失真`() {
        val cp = Checkpoint(
            bookSha = "abc123", mode = "DEEP", query = "",
            doneSlices = setOf("c0t0", "c1t0"), doneChapters = setOf(0, 1),
            doneMerges = setOf("b0"), updatedAt = 42L,
        )
        val back = CheckpointStore.fromJson(CheckpointStore.toJson(cp))!!
        assertEquals(cp.bookSha, back.bookSha)
        assertEquals(cp.mode, back.mode)
        assertEquals(cp.doneSlices, back.doneSlices)
        assertEquals(cp.doneChapters, back.doneChapters)
        assertEquals(cp.doneMerges, back.doneMerges)
    }

    @Test
    fun `坏账本_宽松处理_不抛`() {
        assertNull(CheckpointStore.fromJson("不是json{"))
        // "{}" 能解析出空账本，但对任何书/模式都不 matches（调用方据之作废）
        val empty = CheckpointStore.fromJson("{}")!!
        assertTrue(!empty.matches("任何书", "DEEP"))
    }

    @Test
    fun `续跑_只跑缺的片_成功的才算完成`() {
        val all = slices()
        val cp = Checkpoint("S", "DEEP", "", setOf("c0t0", "c1t0"), setOf(0, 1), emptySet(), 0)
        val pending = CheckpointStore.pendingSlices(all, cp, "S", "DEEP")
        assertEquals(listOf("c0t1", "c2t0"), pending.map { it.id })
    }

    @Test
    fun `书变了_账本作废_全部重跑`() {
        val cp = Checkpoint("S1", "DEEP", "", setOf("c0t0"), setOf(0), emptySet(), 0)
        assertEquals(slices(), CheckpointStore.pendingSlices(slices(), cp, "S2", "DEEP"))
        // 模式也变了 ⇒ 同样作废（精读的已完成集合对略读没有意义）
        assertEquals(slices(), CheckpointStore.pendingSlices(slices(), cp, "S1", "SKIM"))
        assertTrue(!cp.matches("S2", "DEEP"))
        assertTrue(!cp.matches("S1", "SKIM"))
        assertTrue(cp.matches("S1", "DEEP"))
    }

    @Test
    fun `补缺章_计划减已有`() {
        assertEquals(listOf(3, 5), CheckpointStore.missingChapters(listOf(1, 3, 5), setOf(1)))
        assertTrue(CheckpointStore.missingChapters(listOf(1), setOf(1)).isEmpty())
    }

    @Test
    fun `bookSha_同一文本同哈希`() {
        assertEquals(CheckpointStore.bookSha("正文"), CheckpointStore.bookSha("正文"))
        assertTrue(CheckpointStore.bookSha("正文A") != CheckpointStore.bookSha("正文B"))
    }

    // ── 网络闸 ──────────────────────────────────────────────────────────────

    @Test
    fun `网络正常_按当前并发继续`() {
        val d = NetworkGuard.decide(NetSample(listOf(200L, 300L), listOf(true, true, true, false), 0, 0), 2)
        assertEquals(NetDecision.Proceed(2), d)
    }

    @Test
    fun `失败率过半_降并发到一半_地板1`() {
        val d = NetworkGuard.decide(NetSample(listOf(200L), listOf(true, false, false, true), 0, 0), 4)
        assertEquals(NetDecision.Proceed(2), d)
        val floor = NetworkGuard.decide(NetSample(listOf(200L), listOf(false, false, true), 0, 0), 1)
        assertEquals("并发已是 1 ⇒ 不再降，维持 1", NetDecision.Proceed(1), floor)
    }

    @Test
    fun `RTT过慢_降并发`() {
        val slow = List(20) { NetworkGuard.RTT_DEGRADE_MS + 500L }
        val d = NetworkGuard.decide(NetSample(slow, List(20) { true }, 0, 0), 2)
        assertEquals(NetDecision.Proceed(1), d)
    }

    @Test
    fun `连败三次_暂停_停在片边界`() {
        val d = NetworkGuard.decide(NetSample(emptyList(), listOf(false, false, false), 3, 0), 2)
        assertTrue(d is NetDecision.Pause)
        assertTrue(d.toString().contains("连续失败"))
    }

    @Test
    fun `重连三次_暂停`() {
        val d = NetworkGuard.decide(NetSample(emptyList(), listOf(true), 0, 3), 2)
        assertTrue(d is NetDecision.Pause)
    }

    @Test
    fun `窗口失败率到八成_暂停`() {
        val d = NetworkGuard.decide(NetSample(emptyList(), listOf(false, false, false, false, true), 0, 0), 2)
        assertTrue(d is NetDecision.Pause)
        // 0.75（<0.8）只降并发不暂停
        val d2 = NetworkGuard.decide(NetSample(emptyList(), listOf(false, false, false, true), 0, 0), 2)
        assertEquals(NetDecision.Proceed(1), d2)
    }

    @Test
    fun `连败未到三_失败率过半_降并发止血`() {
        // 率 0.6、连败 2：不到暂停线 ⇒ 降并发（4→2），继续跑
        val d = NetworkGuard.decide(NetSample(listOf(100L), listOf(true, false, false, true, false), 2, 0), 4)
        assertEquals(NetDecision.Proceed(2), d)
    }
}
