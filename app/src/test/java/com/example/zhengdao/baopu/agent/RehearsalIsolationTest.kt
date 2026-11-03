// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第三百五十八批 · 派单【隔离】④「同任务的军师与影子**串行**」的判据测试。
//
// 这一根道是纯内存件（进程内一张表），所以判据全部走 JVM 直测；真机上「跑完两边目录都消失」
// 那一半在 `RehearsalRunnerTest` 里钉（要落盘的那部分才需要临时目录）。
//
// 负例的牙：抢不到道的那一位**不许**把别人正在持有的道还掉——否则军师一进来就把影子的副本
// 判成"已经清完了"，两边同时往同一份声明对象上写。
package com.example.zhengdao.baopu.agent

import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

class RehearsalIsolationTest {

    @Before
    fun clearLane() {
        RehearsalLane.resetForTest()
        RehearsalSite.resetForTest()
    }

    @After
    fun clearLaneAfter() {
        RehearsalLane.resetForTest()
        RehearsalSite.resetForTest()
    }

    @Test
    fun `空道给第一个抢到的_第二个一律排队`() {
        assertTrue(RehearsalLane.tryBegin("task-1", RehearsalLaneOwner.REHEARSAL))
        assertEquals(RehearsalLaneOwner.REHEARSAL, RehearsalLane.current("task-1"))
        assertFalse("同任务的影子不能叠在军师那棵副本树上", RehearsalLane.tryBegin("task-1", RehearsalLaneOwner.SHADOW))
        assertEquals("抢道失败的那一位不许改持道者", RehearsalLaneOwner.REHEARSAL, RehearsalLane.current("task-1"))
    }

    @Test
    fun `同一位重复抢也拒_军师自己也不许叠两层副本`() {
        assertTrue(RehearsalLane.tryBegin("task-2", RehearsalLaneOwner.REHEARSAL))
        assertFalse(RehearsalLane.tryBegin("task-2", RehearsalLaneOwner.REHEARSAL))
    }

    @Test
    fun `不同任务各占各的道_互不挡`() {
        assertTrue(RehearsalLane.tryBegin("task-a", RehearsalLaneOwner.SHADOW))
        assertTrue(RehearsalLane.tryBegin("task-b", RehearsalLaneOwner.REHEARSAL))
        assertEquals(RehearsalLaneOwner.SHADOW, RehearsalLane.current("task-a"))
        assertEquals(RehearsalLaneOwner.REHEARSAL, RehearsalLane.current("task-b"))
    }

    @Test
    fun `负例_非持道者还不掉道_还掉之后才让对方进`() {
        assertTrue(RehearsalLane.tryBegin("task-3", RehearsalLaneOwner.REHEARSAL))
        assertFalse(
            "影子来还军师的道＝把正在推演的那位踢下线",
            RehearsalLane.end("task-3", RehearsalLaneOwner.SHADOW),
        )
        assertEquals(RehearsalLaneOwner.REHEARSAL, RehearsalLane.current("task-3"))
        assertTrue(RehearsalLane.end("task-3", RehearsalLaneOwner.REHEARSAL))
        assertNull(RehearsalLane.current("task-3"))
        assertTrue(RehearsalLane.tryBegin("task-3", RehearsalLaneOwner.SHADOW))
    }

    @Test
    fun `空任务键抢不到道_因为没法还`() {
        assertFalse(RehearsalLane.tryBegin("   ", RehearsalLaneOwner.REHEARSAL))
        assertFalse(RehearsalLane.tryBegin("", RehearsalLaneOwner.SHADOW))
    }

    @Test
    fun `排队那句点名挡道的那一位_不写成糊话系统忙`() {
        RehearsalLane.tryBegin("task-4", RehearsalLaneOwner.SHADOW)
        val note = RehearsalLane.waitNote("task-4")
        assertTrue(note, note.contains("影子"))
        assertFalse(note, note.contains("系统忙"))
    }

    // ── ①②那两条：常量的来路（不让"互不读"退化成两份各写一遍的字符串）──────────
    //
    // 这里只钉「军师判影子用的是**影子自己那个常量**」这一件事：影子哪天改名，
    // `RehearsalSandbox.refuse` 必须跟着变，测试要当场红，而不是留一份过期的字符串副本。

    @Test
    fun `军师根与影子根是两个名字_前缀也是两套`() {
        assertEquals("rehearsal", RehearsalSandbox.ROOT_DIR_NAME)
        assertEquals("rh-", RehearsalSandbox.RUN_ID_PREFIX)
        assertEquals(".shadow", FileShadowSandbox.SHADOW_DIR_NAME)
        assertTrue(
            "两个根必须不同名，否则「互不读」等于自己防自己",
            RehearsalSandbox.ROOT_DIR_NAME != FileShadowSandbox.SHADOW_DIR_NAME,
        )
    }

    // ── 场地闸（派单 §一⑥／§四⑤；御裁 2026-11-04 04:29：本批实现、有牙，不移交）────────
    //
    // 与上面那根【隔离】④ 的道是两件事：道管"同一任务上军师与影子不许叠"，
    // 场地闸管"同一份工作区上最多两趟推演在跑"。所以两边各有自己的表，测试也各钉各的。

    @Test
    fun `同场地前两个进得去_第三个排队`() {
        assertTrue(RehearsalSite.tryEnter("C:/work/site-a", "rh-1"))
        assertTrue(RehearsalSite.tryEnter("C:/work/site-a", "rh-2"))
        assertFalse(
            "上限就是派单点名的 2，第三个必须进不来",
            RehearsalSite.tryEnter("C:/work/site-a", "rh-3"),
        )
        assertEquals("抢不进的第三位不许占名额", 2, RehearsalSite.count("C:/work/site-a"))
        assertEquals(2, RehearsalSite.MAX_CONCURRENT)
    }

    @Test
    fun `第三个排队那句点名挡道的那两位_不写成糊话系统忙`() {
        RehearsalSite.tryEnter("C:/work/site-b", "rh-甲")
        RehearsalSite.tryEnter("C:/work/site-b", "rh-乙")
        val note = RehearsalSite.waitNote("C:/work/site-b")
        assertTrue(note, note.contains("rh-甲"))
        assertTrue(note, note.contains("rh-乙"))
        assertTrue(note, note.contains("排队"))
        assertTrue(note, note.contains("2"))
        assertFalse(note, note.contains("系统忙"))
    }

    @Test
    fun `还掉一个名额第三个才进得来_还的是自己的`() {
        RehearsalSite.tryEnter("C:/work/site-c", "rh-1")
        RehearsalSite.tryEnter("C:/work/site-c", "rh-2")
        assertFalse(RehearsalSite.tryEnter("C:/work/site-c", "rh-3"))
        assertFalse(
            "别人的 runId 还不掉别人的名额＝把正在跑的那位的名额吞掉",
            RehearsalSite.leave("C:/work/site-c", "rh-9"),
        )
        assertEquals(2, RehearsalSite.count("C:/work/site-c"))
        assertTrue(RehearsalSite.leave("C:/work/site-c", "rh-1"))
        assertTrue(RehearsalSite.tryEnter("C:/work/site-c", "rh-3"))
    }

    @Test
    fun `同一个runId不许在同场地上占两个名额`() {
        assertTrue(RehearsalSite.tryEnter("C:/work/site-d", "rh-same"))
        assertFalse(
            "自己叠自己＝两份副本树长在同一个 runId 上（副本混用）",
            RehearsalSite.tryEnter("C:/work/site-d", "rh-same"),
        )
        assertEquals(1, RehearsalSite.count("C:/work/site-d"))
    }

    @Test
    fun `不同场地各占各的额度_一份树满了不挡另一份`() {
        assertTrue(RehearsalSite.tryEnter("C:/work/site-e", "rh-1"))
        assertTrue(RehearsalSite.tryEnter("C:/work/site-e", "rh-2"))
        assertTrue(RehearsalSite.tryEnter("D:/other/site-f", "rh-3"))
        assertEquals(2, RehearsalSite.count("C:/work/site-e"))
        assertEquals(1, RehearsalSite.count("D:/other/site-f"))
    }

    @Test
    fun `空场地或空runId抢不进_没人还得起的名额不留`() {
        assertFalse(RehearsalSite.tryEnter("   ", "rh-1"))
        assertFalse(RehearsalSite.tryEnter("C:/work/site-g", "   "))
        assertFalse(RehearsalSite.tryEnter("", ""))
        assertEquals("拒掉的进场不许留下空表占位", 0, RehearsalSite.count("C:/work/site-g"))
        assertFalse("场地上一个人都没有 ⇒ 退场只能报 false", RehearsalSite.leave("C:/work/site-g", "rh-1"))
        assertEquals(0, RehearsalSite.count("C:/work/site-g"))
    }
}
