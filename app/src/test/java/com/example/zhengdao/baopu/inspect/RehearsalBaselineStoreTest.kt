// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第三百五十八批 · 御裁 2026-11-04 04:36 的「勘验」接线判据：军师那份"会改 N 个文件"的清单
// 交给勘验当事后核验基线，勘验据此判**少了／多了／对得上**。
//
// 这一份长在 `inspect/` 的测试目录里，跟着被测件一起分层——它验的是勘验那一侧的口径，
// 军师那侧「跑完确实把清单交过来了」由 `RehearsalRunnerTest` 钉（那一条要临时目录，两边不重复）。
//
// 三条纪律各自的负例牙：
// · 没登记的 runId ⇒ 一律「无法核验」，**不许**因为"实际也是空"就报对得上（缺侧不当一致）；
// · 空清单／空 runId ⇒ 登记就拒，免得留下一份会冒充通过的空基线；
// · 少了与多了同时存在 ⇒ 两笔都点名，不合并成一句"有出入"。
package com.example.zhengdao.baopu.inspect

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RehearsalBaselineStoreTest {

    @Before
    fun clear() = RehearsalBaselineStore.resetForTest()

    @After
    fun clearAfter() = RehearsalBaselineStore.resetForTest()

    @Test
    fun `登记一份基线_事后一核对得上`() {
        assertTrue(RehearsalBaselineStore.register("rh-1", listOf("notes/a.md", "notes/b.md")))
        assertEquals(2, RehearsalBaselineStore.baseline("rh-1").size)
        assertEquals(
            "对得上：2 条一条不差",
            RehearsalBaselineStore.verdict("rh-1", listOf("notes/a.md", "notes/b.md")),
        )
    }

    @Test
    fun `少了两条就点名少了_带着实测条数`() {
        RehearsalBaselineStore.register("rh-2", listOf("notes/a.md", "notes/b.md", "notes/c.md"))
        val note = RehearsalBaselineStore.verdict("rh-2", listOf("notes/a.md"))
        assertTrue(note, note.startsWith("少了 2 条"))
        assertTrue(note, note.contains("notes/b.md"))
        assertTrue(note, note.contains("notes/c.md"))
        assertFalse(note, note.contains("有出入"))
    }

    @Test
    fun `多改了一条就点名多了_基线没说的也算错`() {
        RehearsalBaselineStore.register("rh-3", listOf("notes/a.md"))
        val note = RehearsalBaselineStore.verdict("rh-3", listOf("notes/a.md", "notes/偷改.md"))
        assertTrue(note, note.startsWith("多了 1 条"))
        assertTrue(note, note.contains("notes/偷改.md"))
    }

    @Test
    fun `少了和多了同时存在_两笔都点不合并`() {
        RehearsalBaselineStore.register("rh-4", listOf("notes/a.md", "notes/b.md"))
        val note = RehearsalBaselineStore.verdict("rh-4", listOf("notes/a.md", "notes/z.md"))
        val ix少 = note.indexOf("少了 1 条")
        val ix多 = note.indexOf("多了 1 条")
        assertTrue("$note", ix少 >= 0 && ix多 >= 0)
        assertTrue("$note", ix少 != ix多)
    }

    @Test
    fun `负例_没登记的runId不判一致_缺侧不当一致`() {
        val note = RehearsalBaselineStore.verdict("rh-没登记", emptyList())
        assertTrue(note, note.contains("无法核验"))
        assertFalse("一侧压根没数据不许冒充'对得上'", note.contains("对得上"))
    }

    @Test
    fun `负例_空清单不登记_不留一份会冒充通过的空基线`() {
        assertFalse(RehearsalBaselineStore.register("rh-5", emptyList()))
        assertFalse(RehearsalBaselineStore.register("rh-5", listOf("  ", "")))
        assertFalse(RehearsalBaselineStore.has("rh-5"))
        assertEquals(0, RehearsalBaselineStore.count())
    }

    @Test
    fun `负例_空runId不登记_没有归属的基线核不了`() {
        assertFalse(RehearsalBaselineStore.register("   ", listOf("notes/a.md")))
        assertEquals(0, RehearsalBaselineStore.count())
    }

    @Test
    fun `一份预演只交一份基线_同runId重复交拒`() {
        assertTrue(RehearsalBaselineStore.register("rh-6", listOf("notes/a.md")))
        assertFalse(
            "同一趟预演交两份清单，事后核验就说不清该对哪一份",
            RehearsalBaselineStore.register("rh-6", listOf("notes/b.md")),
        )
        assertEquals(listOf("notes/a.md"), RehearsalBaselineStore.baseline("rh-6"))
    }

    @Test
    fun `反斜杠与重复项归一之后才算同一条`() {
        RehearsalBaselineStore.register("rh-7", listOf("notes\\a.md", "notes/a.md", " notes/a.md "))
        assertEquals(1, RehearsalBaselineStore.baseline("rh-7").size)
        assertEquals("对得上：1 条一条不差", RehearsalBaselineStore.verdict("rh-7", listOf("notes/a.md")))
    }

    @Test
    fun `点名最多六条_超出只报数不刷屏`() {
        val many = (1..9).map { "notes/$it.md" }
        RehearsalBaselineStore.register("rh-8", many)
        val note = RehearsalBaselineStore.verdict("rh-8", emptyList())
        assertTrue(note, note.startsWith("少了 9 条"))
        assertTrue(note, note.contains("另 3 条没点名"))
        assertFalse("点名条数有上限，不许把九条全刷进一句勘验话", note.contains("notes/7.md"))
    }

    @Test
    fun `核验完摘掉基线_不留给下一趟错认`() {
        RehearsalBaselineStore.register("rh-9", listOf("notes/a.md"))
        assertTrue(RehearsalBaselineStore.forget("rh-9"))
        assertFalse(RehearsalBaselineStore.has("rh-9"))
        assertTrue(RehearsalBaselineStore.verdict("rh-9", listOf("notes/a.md")).contains("无法核验"))
        assertFalse("摘掉之后再摘一次报 false，不冒充成功", RehearsalBaselineStore.forget("rh-9"))
    }

    @Test
    fun `上限那个六写死一处_文案里的数跟着常量走`() {
        assertEquals(6, RehearsalBaselineStore.MAX_NAMES_IN_VERDICT)
    }
}
