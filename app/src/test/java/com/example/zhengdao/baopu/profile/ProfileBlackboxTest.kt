// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百八十批 · 画像黑箱存储外壳的贯通测试（判据④端到端＋落点/落盘/重启语义）。
package com.example.zhengdao.baopu.profile

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileBlackboxTest {

    private val day = TwoLayerProfile.DAY_MS
    private val t0 = 1_790_000_000_000L

    private fun tmp(tag: String): File =
        File.createTempFile("zd280-bb-$tag", "").let { it.delete(); it.mkdirs(); it }

    @After
    fun tearDown() {
        UserProfileBlackbox.resetForTest()
    }

    private fun freshInstall(tag: String): File {
        val dir = File(tmp(tag), UserProfileBlackbox.INTERNAL_DIR)
        UserProfileBlackbox.install(dir)
        return dir
    }

    // ── 判据④端到端：超限即停 ──────────────────────────────────────────────

    @Test
    fun `判据④_第51条被拒且没进画像`() {
        freshInstall("cap")
        val kinds = listOf("k1", "k2")
        repeat(50) { i ->
            assertTrue(
                UserProfileBlackbox.feed("k1", 1, true, t0 + i, screenOn = true, foreground = true)
                    is FeedVerdict.Accept,
            )
        }
        // 同一时刻读分布，前后才可比（衰减是惰性的，不同时刻读数天然差一点点）
        val before = UserProfileBlackbox.distribution(t0 + 999, kinds)
        val v = UserProfileBlackbox.feed("k1", 1, true, t0 + 999, true, true)
        assertEquals(FeedDeny.DAY_CAP, (v as FeedVerdict.Deny).reason)
        assertEquals(before["k1"]!!, UserProfileBlackbox.distribution(t0 + 999, kinds)["k1"]!!, 1e-12)
        assertTrue(UserProfileBlackbox.conclusion(t0 + 999).contains("今日已收=50"))
    }

    @Test
    fun `熄屏与后台的采样连画像都不进`() {
        freshInstall("vis")
        UserProfileBlackbox.feed("k1", 5, true, t0, screenOn = false, foreground = true)
        UserProfileBlackbox.feed("k1", 5, true, t0 + 1, screenOn = true, foreground = false)
        val kinds = listOf("k1", "k2")
        // 两条都没收 ⇒ 证据为空 ⇒ 弱先验等分
        val d = UserProfileBlackbox.distribution(t0 + 2, kinds)
        assertEquals(0.5, d["k1"]!!, 1e-9)
        assertEquals(0.5, d["k2"]!!, 1e-9)
    }

    // ── 重算只认充电 ────────────────────────────────────────────────────────

    @Test
    fun `电池不重算_充电才算`() {
        freshInstall("chg")
        repeat(30) { UserProfileBlackbox.feed("k1", 5, true, t0 + it, screenOn = true, foreground = true) }
        assertFalse(UserProfileBlackbox.recompute(t0 + 1, charging = false))
        assertTrue(UserProfileBlackbox.recompute(t0 + 1, charging = true))
    }

    // ── 清短期层（M7 判据） ─────────────────────────────────────────────────

    @Test
    fun `清短期层后一周内回弱先验_聚合保留`() {
        freshInstall("clear")
        repeat(30) { UserProfileBlackbox.feed("k1", 5, true, t0 + it, screenOn = true, foreground = true) }
        repeat(5) { UserProfileBlackbox.feed("k2", 5, true, t0 + it, screenOn = true, foreground = true) }
        val kinds = listOf("k1", "k2", "k3")
        val now = t0 + 100
        assertTrue(UserProfileBlackbox.distribution(now, kinds)["k1"]!! > 0.5)
        UserProfileBlackbox.clearShortTerm(now)
        kinds.forEach {
            assertEquals(1.0 / 3.0, UserProfileBlackbox.distribution(now + 1, kinds)[it]!!, 1e-9)
        }
    }

    // ── 落盘与重启语义 ──────────────────────────────────────────────────────

    @Test
    fun `重启后状态读回_画像与限频都在`() {
        val dir = freshInstall("persist")
        repeat(7) { UserProfileBlackbox.feed("k1", 5, true, t0 + it, screenOn = true, foreground = true) }
        val kinds = listOf("k1", "k2")
        val before = UserProfileBlackbox.distribution(t0 + 1, kinds)
        UserProfileBlackbox.resetForTest()
        UserProfileBlackbox.install(dir) // 模拟进程重启
        assertEquals(
            before["k1"]!!,
            UserProfileBlackbox.distribution(t0 + 1, kinds)["k1"]!!,
            1e-12,
        )
        // 限频计数也在：当日已收 7，第 8 条照收
        assertTrue(UserProfileBlackbox.feed("k1", 1, true, t0 + 8, screenOn = true, foreground = true) is FeedVerdict.Accept)
        assertTrue(UserProfileBlackbox.conclusion(t0 + 9).contains("今日已收=8"))
    }

    @Test
    fun `损坏的状态文件_按空画像起不抛`() {
        val root = tmp("corrupt")
        val dir = File(root, UserProfileBlackbox.INTERNAL_DIR)
        dir.mkdirs()
        File(dir, UserProfileBlackbox.PROFILE_FILE).writeText("{{{ 这不是 JSON", Charsets.UTF_8)
        UserProfileBlackbox.install(dir)
        assertTrue(
            UserProfileBlackbox.feed("k1", 1, true, t0, screenOn = true, foreground = true)
                is FeedVerdict.Accept,
        )
    }

    @Test
    fun `落盘内容纯ASCII_只留哈希不留原文_勘验不外显`() {
        val dir = freshInstall("ascii")
        UserProfileBlackbox.feed("k1", 1, true, t0, screenOn = true, foreground = true, textSha = "a1b2c3d4e5")
        val state = File(dir, UserProfileBlackbox.PROFILE_FILE).readText(Charsets.UTF_8)
        assertTrue(state.none { it.code > 127 }) // 纯 ASCII ⇒ 中文原文没有容身之处
        assertTrue(state.contains("a1b2c3d4e5")) // 留的是哈希（173fy：采样文本只留哈希/长度）
        // 勘验结论里连哈希都不出（只出结论，不出明细）
        assertFalse(UserProfileBlackbox.conclusion(t0 + 1).contains("a1b2c3d4e5"))
        assertFalse(dir.name.contains("画像"))
    }

    @Test
    fun `TTL重调走黑箱_越界夹回`() {
        freshInstall("ttl")
        assertEquals(7, UserProfileBlackbox.retuneTtl(3))
        assertEquals(15, UserProfileBlackbox.retuneTtl(99))
    }
}
