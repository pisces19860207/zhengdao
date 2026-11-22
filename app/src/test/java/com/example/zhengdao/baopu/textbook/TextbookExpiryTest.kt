// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 798 判据⑤：90 天未复核自动标待复核；伪造旧日期必须能触发，且状态派生不改条目。
package com.example.zhengdao.baopu.textbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDate

class TextbookExpiryTest {
    private val today: LocalDate = LocalDate.of(2026, 11, 22)

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `刚入库的条目状态正常`() {
        val view = ReviewExpiry.of(Fixtures.entry(asOf = Fixtures.oldDay(1)), today)
        assertEquals(ReviewStatus.正常, view.status)
        assertEquals(1L, view.ageDays)
    }

    @Test
    fun `伪造旧日期触发待复核_89天不触发90天触发`() {
        val before = ReviewExpiry.of(Fixtures.entry(asOf = Fixtures.oldDay(89)), today)
        assertEquals("89 天不该标待复核", ReviewStatus.正常, before.status)

        val at = ReviewExpiry.of(Fixtures.entry(asOf = Fixtures.oldDay(90)), today)
        assertEquals("90 天整应标待复核", ReviewStatus.待复核, at.status)

        val forged = ReviewExpiry.of(Fixtures.entry(asOf = "2019-01-01"), today)
        assertEquals("伪造旧日期必须触发", ReviewStatus.待复核, forged.status)
        assertTrue("应给出天数差", forged.ageDays > 2000L)
    }

    @Test
    fun `待复核是派生状态_不改条目本身`() {
        val lib = tmp.newFolder("lib")
        val store = AppendOnlyStore(lib)
        val old = Fixtures.entry(id = "TB-OLD", asOf = "2019-01-01")
        store.add(old)

        val due = store.due(today)
        assertEquals(1, due.size)
        assertEquals("TB-OLD", due.first().entry.id)
        assertEquals(old.asOf, store.get("TB-OLD")?.asOf)
        assertEquals(1, File(lib, "entries.jsonl").readLines(Charsets.UTF_8).size)

        val report = TextbookLibrary.writeDueReport(lib, today)
        assertTrue("待复核派生文件应落盘", report.isFile)
        assertTrue(report.readText(Charsets.UTF_8).contains("待复核"))
        assertTrue(File(lib, "entries.jsonl").readText(Charsets.UTF_8).contains(old.id))
    }

    @Test
    fun `未来日期不误判为过期`() {
        val view = ReviewExpiry.of(Fixtures.entry(asOf = "2099-01-01"), today)
        assertEquals(ReviewStatus.正常, view.status)
        assertTrue(view.ageDays < 0)
    }
}
