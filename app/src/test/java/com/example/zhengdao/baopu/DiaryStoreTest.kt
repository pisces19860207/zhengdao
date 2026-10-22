// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 依据的公开接口：JUnit 4（TemporaryFolder）。
package com.example.zhengdao.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 147 阶段① · DiaryStore 文件存取的 JVM 直测（临时目录，不碰 Context）。
 *
 * 覆盖：upsert/替换、一日多会话、loadAll 重建地基（判据 19 的文件半边）、
 * deleteById 只删日记（判据 6 的文件半边）、脱敏闸拒写（判据 21）、
 * 工作区隔离（判据 15——两个根互不可见）。
 */
class DiaryStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store(root: File) = DiaryStore(File(root, "记忆/日记"))

    private fun entry(
        id: String = "42-20261022",
        date: String = "2026-10-22",
        sessionId: Long = 42L,
        summary: String = "工具成功 3 次 · 失败 0 次 · 产物 2 个",
        artifacts: List<String> = listOf("整理/a.md"),
        okSteps: Int = 3,
        createdAt: Long = 1000L,
    ) = DiaryEntry(
        id = id, date = date, sessionId = sessionId,
        timeFromMs = createdAt, timeToMs = createdAt + 100,
        title = "工具 $okSteps 次 · 产物 ${artifacts.size} 个",
        summary = summary, artifacts = artifacts,
        evidence = DiaryEvidence.OBSERVED, okSteps = okSteps,
        failedSteps = 0, createdAt = createdAt,
    )

    @Test
    fun `upsert后按日读回_逐字段一致`() {
        val s = store(tmp.newFolder())
        val r = s.upsert(entry())
        assertTrue(r.ok)
        assertEquals(listOf(entry()), s.loadDay("2026-10-22"))
    }

    @Test
    fun `同id替换不重复_同日多会话共存`() {
        val root = tmp.newFolder()
        val s = store(root)
        s.upsert(entry())                                     // 会话 42
        s.upsert(entry(okSteps = 5, createdAt = 2000L))       // 同会话同日第二轮：累计后 upsert（调用方拼好）
        s.upsert(entry(id = "77-20261022", sessionId = 77L, okSteps = 1, createdAt = 3000L))
        val day = s.loadDay("2026-10-22")
        assertEquals(2, day.size)                             // 42 一条（替换非追加）＋ 77 一条
        assertEquals(5, day.first { it.sessionId == 42L }.okSteps)
        // 稳定序：按 createdAt
        assertEquals(77L, day.last().sessionId)
    }

    @Test
    fun `判据19文件半边_loadAll就是重建地基_条数内容一致`() {
        val root = tmp.newFolder()
        val s = store(root)
        val written = listOf(
            entry(createdAt = 1000L),
            entry(id = "42-20261023", date = "2026-10-23", createdAt = 2000L),
            entry(id = "77-20261023", date = "2026-10-23", sessionId = 77L, createdAt = 3000L),
        )
        written.forEach { s.upsert(it) }
        val snap = s.loadAll()
        assertEquals(0, snap.skippedFiles)
        assertEquals(written.sortedBy { it.id }, snap.entries.sortedBy { it.id })
    }

    @Test
    fun `坏文件计数不静默_好文件照读`() {
        val root = tmp.newFolder()
        val s = store(root)
        s.upsert(entry())
        File(File(root, "记忆/日记"), "2026-10-01.md").writeText("随手写的不是 front-matter")
        val snap = s.loadAll()
        assertEquals(1, snap.entries.size)
        assertEquals(1, snap.skippedFiles)
    }

    @Test
    fun `判据6文件半边_deleteById只删那一日_其他日不动`() {
        val root = tmp.newFolder()
        val s = store(root)
        s.upsert(entry(createdAt = 1000L))
        s.upsert(entry(id = "42-20261023", date = "2026-10-23", createdAt = 2000L))
        assertEquals(1, s.deleteById("42-20261022"))
        assertEquals(0, s.loadDay("2026-10-22").size)
        assertEquals(1, s.loadDay("2026-10-23").size)
        assertEquals(0, s.deleteById("42-20261022")) // 再删 = 没找到
        // 条目删光 ⇒ 日文件一并清走（不留空壳）
        assertFalse(File(File(root, "记忆/日记"), "2026-10-22.md").exists())
    }

    @Test
    fun `判据21_脱敏闸命中整条不写_文件不落地`() {
        val root = tmp.newFolder()
        val s = store(root)
        val r = s.upsert(entry(summary = "拿到了 sk-abc12345678901234567 的结果"))
        assertFalse(r.ok)
        assertTrue(r.reason!!.contains("判据 21"))
        assertFalse(File(File(root, "记忆/日记"), "2026-10-22.md").exists())
    }

    @Test
    fun `判据15_工作区隔离_两个根互不可见`() {
        val wsA = tmp.newFolder()
        val wsB = tmp.newFolder()
        store(wsA).upsert(entry())
        store(wsB).upsert(entry(id = "77-20261022", sessionId = 77L))
        assertEquals(1, store(wsA).loadAll().entries.size)
        assertEquals(1, store(wsB).loadAll().entries.size)
        assertTrue(store(wsB).loadAll().entries.none { it.sessionId == 42L })
    }
}
