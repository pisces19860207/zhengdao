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

    private fun store(root: File) = DiaryStore(MemoryLayout.memoryDir(root))

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

    // ── 判据 14/20：维护（浓缩 → 归档 → 清理） ─────────────────────────

    private val DAY = 86_400_000L
    private val now = 1_800_000_000_000L

    private fun aged(id: String, date: String, daysAgo: Long, artifacts: List<String>) = entry(
        id = id, date = date, sessionId = 1L,
        artifacts = artifacts, createdAt = now - daysAgo * DAY,
    ).let { it.copy(timeFromMs = now - daysAgo * DAY, timeToMs = now - daysAgo * DAY) }

    @Test
    fun `维护_无候选时零动作`() {
        val s = store(tmp.newFolder())
        s.upsert(entry(createdAt = now))
        assertEquals(DiaryStore.MaintenanceReport(0, 0, 0), s.maintain(now))
        assertEquals(1, s.loadAll().entries.size)
    }

    @Test
    fun `判据14_超窗条目浓缩成月度摘要_原文转归档_摘要引用一条不丢`() {
        val root = tmp.newFolder()
        val s = store(root)
        s.upsert(aged("1-20260805", "2026-08-05", 60, listOf("整理/a.md", "整理/b.md")))
        val r = s.maintain(now)
        assertEquals(1, r.digested)
        assertEquals(1, r.archivedFiles)
        // 原文进了归档（不再参与 loadAll），摘要落 digest 文件且引用齐全
        assertTrue(File(MemoryLayout.archiveDir(root), "2026-08-05.md").isFile)
        val digests = s.loadAll().entries.filter { DiaryRecall.isDigest(it.id) }
        assertEquals(1, digests.size)
        assertEquals(listOf("整理/a.md", "整理/b.md"), digests[0].artifacts)
        assertEquals(0, s.loadAll().entries.count { !DiaryRecall.isDigest(it.id) })
    }

    @Test
    fun `判据20_归档清理_归档满窗且被承载才删_产物文件逐字节不动`() {
        val root = tmp.newFolder()
        val s = store(root)
        // 产物与「用户文件」在工作区内、日记目录之外——维护谁也不许碰（相对路径合法）
        val artifact = File(root, "整理/a.md").apply { parentFile!!.mkdirs(); writeText("产物原文") }
        val userFile = File(root, "用户的重要文件.txt").apply { writeText("别动我") }
        s.upsert(aged("1-20260401", "2026-04-01", 200, listOf("整理/a.md")))
        s.maintain(now) // 浓缩＋归档（归档文件时间戳＝now，刚住进来）
        val archived = File(MemoryLayout.archiveDir(root), "2026-04-01.md")
        assertTrue("应先转归档", archived.isFile)
        assertEquals(0, s.maintain(now).cleanedFiles) // 没住满 180 天 ⇒ 不删
        // 模拟归档满 200 天（盖文件时间戳）⇒ 再维护才清理
        assertTrue(archived.setLastModified(now - 200 * DAY))
        val r3 = s.maintain(now)
        assertEquals(1, r3.cleanedFiles)
        assertFalse(archived.exists())
        // 摘要仍在，引用仍有效；目录外两个文件逐字节不动
        assertTrue(s.loadAll().entries.any { DiaryRecall.isDigest(it.id) })
        assertEquals("产物原文", artifact.readText())
        assertEquals("别动我", userFile.readText())
    }

    @Test
    fun `维护_未被摘要承载的归档件_住满窗也不删`() {
        val root = tmp.newFolder()
        val s = store(root)
        s.upsert(aged("1-20260401", "2026-04-01", 200, listOf("整理/only-here.md")))
        s.maintain(now)
        val archived = File(MemoryLayout.archiveDir(root), "2026-04-01.md")
        assertTrue(archived.isFile)
        // 把摘要里的引用抹掉（模拟「线索会断」）＋ 盖老时间戳 ⇒ 两条都不满足，留着
        val digestFile = File(File(root, "记忆/日记"), "digest-2026-04.md")
        digestFile.writeText(digestFile.readText().replace("- 整理/only-here.md\n", ""))
        assertTrue(archived.setLastModified(now - 200 * DAY))
        assertEquals(0, s.maintain(now).cleanedFiles)
        assertTrue(archived.isFile)
    }

    @Test
    fun `摘要upsert路由_digest文件_不被当成日文件`() {
        val root = tmp.newFolder()
        val s = store(root)
        val digest = DiaryRecall.composeDigest(
            "2026-10", listOf(entry(createdAt = now)), now,
        )
        s.upsert(digest)
        assertTrue(File(File(root, "记忆/日记"), "digest-2026-10.md").isFile)
        assertFalse(File(File(root, "记忆/日记"), "2026-10.md").isFile)
        assertEquals(listOf(digest.id), s.loadAll().entries.map { it.id })
    }
}
