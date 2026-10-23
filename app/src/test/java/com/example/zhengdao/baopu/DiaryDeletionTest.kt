// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：派工单第一百四十七批 checkpoint③（真删）＋《抱朴情景记忆设计方案》§5
// 五边界＋判据 18（四处全消、grep 零命中）。
// 索引行那半边的功能验证本仓做不了（无 Robolectric，SQLiteOpenHelper 起不来）——
// 由 DiaryDbGateTest 钉语句层＋源码接线层；文件侧四处在此功能真测。
package com.example.zhengdao.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DiaryDeletionTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val now = 1_770_000_000_000L // 2026-10-22 附近
    private val day = 86_400_000L

    private fun store(root: File) = DiaryStore(File(root, "记忆/日记"), db = null)

    private fun entry(
        id: String,
        date: String,
        artifacts: List<String> = emptyList(),
        timeToMs: Long = now,
    ) = DiaryEntry(
        id = id,
        date = date,
        sessionId = id.substringBefore('-').toLongOrNull() ?: 1L,
        timeFromMs = timeToMs - 60_000L,
        timeToMs = timeToMs,
        title = DiaryRules.composeTitle(3, artifacts.size),
        summary = DiaryRules.composeSummary(3, 1, artifacts.size),
        artifacts = artifacts,
        evidence = DiaryEvidence.OBSERVED,
        okSteps = 3,
        failedSteps = 1,
        createdAt = timeToMs - 60_000L,
    )

    /** 全部相关文本（日记＋摘要＋归档），供判据 18 的 grep 零命中。 */
    private fun allSources(root: File): List<String> {
        val diaryRoot = File(root, "记忆/日记")
        val out = mutableListOf<String>()
        diaryRoot.listFiles { f -> f.isFile && f.name.endsWith(".md") }?.forEach {
            out += it.readText()
        }
        val ad = File(diaryRoot, "归档")
        if (ad.isDirectory) {
            ad.listFiles { f -> f.isFile && f.name.endsWith(".md") }?.forEach {
                out += it.readText()
            }
        }
        return out
    }

    @Test
    fun `计划_按条删_只挑指定id_产物引用供核对`() {
        val entries = listOf(
            entry("1-20261022", "2026-10-22", listOf("整理/a.md")),
            entry("2-20261022", "2026-10-22", listOf("整理/b.md")),
        )
        val plan = DiaryDeletionRules.plan(DiaryDeletionScope.ENTRY, entries, ids = listOf("1-20261022"))
        assertEquals(listOf("1-20261022"), plan.targetIds)
        assertEquals(listOf("整理/a.md"), plan.artifactRefs) // 只列要删那条的，不连坐
        assertEquals(listOf("2026-10"), plan.affectedDigestMonths)
    }

    @Test
    fun `计划_按日删_当日全部非摘要条目`() {
        val entries = listOf(
            entry("1-20261022", "2026-10-22"),
            entry("2-20261022", "2026-10-22"),
            entry("3-20261021", "2026-10-21"),
            DiaryRecall.composeDigest("2026-10", listOf(entry("9-20260930", "2026-09-30")), now),
        )
        val plan = DiaryDeletionRules.plan(DiaryDeletionScope.DAY, entries, dates = listOf("2026-10-22"))
        assertEquals(listOf("1-20261022", "2-20261022"), plan.targetIds) // 摘要不连坐
    }

    @Test
    fun `边界2_确认卡把删日记不删文件说到字面上`() {
        val plan = DiaryDeletionRules.plan(
            DiaryDeletionScope.ENTRY,
            listOf(entry("1-20261022", "2026-10-22", listOf("整理/a.md"))),
            ids = listOf("1-20261022"),
        )
        val body = DiaryDeletionRules.confirmBody(plan)
        assertTrue("确认卡必须含固定话头", body.contains(DiaryDeletionRules.CONFIRM_WORDS))
        assertTrue("确认卡必须含不进回收站", body.contains("不进回收站"))
        assertTrue("确认卡必须列产物路径（边界⑤）", body.contains("- 整理/a.md"))
        assertTrue("确认卡必须说这些文件不会被删除", body.contains("不会被删除"))
    }

    @Test
    fun `判据18_verifyGone_有残影时必须报假_不许恒真`() {
        // 负例：文本里还提着一个被删 id ⇒ 必须返回 false。
        // （没有这条，「verifyGone 恒真」的变异永远绿——自检判据防恒真，房规。）
        assertFalse(
            DiaryDeletionRules.verifyGone(listOf("别的流水\n---\nid: 1-20261022\n"), listOf("1-20261022")),
        )
        // 正例：四处都没提 ⇒ true
        assertTrue(DiaryDeletionRules.verifyGone(listOf("干净文本", "另一处也干净"), listOf("1-20261022")))
    }

    @Test
    fun `真删_按条删_四处全消_grep零命中`() {
        val root = tmp.newFolder()
        val s = store(root)
        val artifact = File(root, "整理/a.md").apply { parentFile!!.mkdirs(); writeText("产物原文") }
        val userFile = File(root, "用户的重要文件.txt").apply { writeText("别动我") }
        s.upsert(entry("1-20261022", "2026-10-22", listOf("整理/a.md")))
        s.upsert(entry("2-20261022", "2026-10-22", listOf("整理/a.md")))
        s.maintain(now) // 维护一遍（当日还新，不归档）——只是让管线跑过
        val plan = DiaryDeletionRules.plan(
            DiaryDeletionScope.ENTRY, s.loadAll().entries, ids = listOf("1-20261022"),
        )
        val report = s.realDelete(plan)
        assertEquals(1, report.deletedEntries)
        // 判据 18：四处文本零命中
        assertTrue(DiaryDeletionRules.verifyGone(allSources(root), plan.targetIds))
        assertTrue(allSources(root).none { it.contains("1-20261022") })
        // 边界①：产物与用户文件逐字节不动
        assertEquals("产物原文", artifact.readText())
        assertEquals("别动我", userFile.readText())
        // 另一条完好
        assertEquals(listOf("2-20261022"), s.loadAll().entries.map { it.id })
    }

    @Test
    fun `真删_按日删_整日消失_文件也删掉`() {
        val root = tmp.newFolder()
        val s = store(root)
        s.upsert(entry("1-20261022", "2026-10-22"))
        s.upsert(entry("2-20261022", "2026-10-22"))
        val plan = DiaryDeletionRules.plan(DiaryDeletionScope.DAY, s.loadAll().entries, dates = listOf("2026-10-22"))
        val report = s.realDelete(plan)
        assertEquals(2, report.deletedEntries)
        assertEquals(1, report.deletedFiles) // 整日空了 ⇒ 文件删掉
        assertFalse(File(File(root, "记忆/日记"), "2026-10-22.md").exists())
        assertTrue(s.loadAll().entries.isEmpty())
    }

    @Test
    fun `真删_归档里的条目_残影清除_摘要重算丢掉它的痕迹`() {
        val root = tmp.newFolder()
        val s = store(root)
        // 造一条 60 天前的（进归档候选），跑维护 ⇒ 进摘要＋原文挪归档
        s.upsert(entry("1-20260901", "2026-09-01", listOf("整理/old.md"), timeToMs = now - 60 * day))
        s.upsert(entry("2-20260901", "2026-09-01", listOf("整理/old.md"), timeToMs = now - 60 * day))
        s.maintain(now)
        assertTrue("维护后应进归档", s.loadArchive().isNotEmpty())
        val digestBefore = s.loadAll().entries.single { DiaryRecall.isDigest(it.id) }
        assertTrue("摘要应先含老条目的产物引用", digestBefore.artifacts.contains("整理/old.md"))

        val plan = DiaryDeletionRules.plan(
            DiaryDeletionScope.ENTRY, s.loadAll().entries + s.loadArchive(),
            ids = listOf("1-20260901"),
        )
        val report = s.realDelete(plan)
        assertEquals(1, report.rebuiltDigests) // 摘要被重算
        assertEquals(0, report.removedArchiveFiles) // 2 号还在 ⇒ 归档文件重写而非删
        assertTrue("归档里不许再有 1 号", s.loadArchive().none { it.id == "1-20260901" })
        assertTrue("归档里 2 号完好", s.loadArchive().any { it.id == "2-20260901" })
        // 摘要里老条目还在的那条的产物引用仍在（2 号没删）；被删的 1 号痕迹不再单独存在
        val digestAfter = s.loadAll().entries.singleOrNull { DiaryRecall.isDigest(it.id) }
        assertTrue("摘要应仍在（2 号还承载着）", digestAfter != null)
        // 判据 18：被删 id 在四处文本里零命中
        assertTrue(DiaryDeletionRules.verifyGone(allSources(root), plan.targetIds))
    }

    @Test
    fun `真删_归档清空后_月度摘要连同消失`() {
        val root = tmp.newFolder()
        val s = store(root)
        s.upsert(entry("1-20260901", "2026-09-01", listOf("整理/old.md"), timeToMs = now - 60 * day))
        s.maintain(now)
        assertTrue(s.loadAll().entries.any { DiaryRecall.isDigest(it.id) })
        val plan = DiaryDeletionRules.plan(
            DiaryDeletionScope.DAY, s.loadAll().entries + s.loadArchive(), dates = listOf("2026-09-01"),
        )
        val report = s.realDelete(plan)
        assertEquals(1, report.rebuiltDigests)
        assertFalse("归档空了 ⇒ 摘要必须一起消失", File(File(root, "记忆/日记"), "digest-2026-09.md").exists())
        assertTrue("四处文本零命中", DiaryDeletionRules.verifyGone(allSources(root), plan.targetIds))
    }

    @Test
    fun `真删_日文件里的新条目_不碰月度摘要`() {
        val root = tmp.newFolder()
        val s = store(root)
        s.upsert(entry("1-20260901", "2026-09-01", listOf("整理/old.md"), timeToMs = now - 60 * day))
        s.maintain(now) // 9 月进摘要＋归档
        s.upsert(entry("2-20261022", "2026-10-22", listOf("整理/new.md")))
        val digestBefore = s.loadAll().entries.single { DiaryRecall.isDigest(it.id) }
        val plan = DiaryDeletionRules.plan(
            DiaryDeletionScope.ENTRY, s.loadAll().entries, ids = listOf("2-20261022"),
        )
        val report = s.realDelete(plan)
        assertEquals(0, report.rebuiltDigests) // 新条目没进过摘要 ⇒ 不重算
        val digestAfter = s.loadAll().entries.single { DiaryRecall.isDigest(it.id) }
        assertEquals(digestBefore.summary, digestAfter.summary)
        assertEquals(digestBefore.artifacts, digestAfter.artifacts)
    }

    @Test
    fun `边界3_真删不进回收站_没有trash目录`() {
        val root = tmp.newFolder()
        val s = store(root)
        s.upsert(entry("1-20261022", "2026-10-22"))
        s.realDelete(
            DiaryDeletionRules.plan(DiaryDeletionScope.ENTRY, s.loadAll().entries, ids = listOf("1-20261022")),
        )
        val diaryRoot = File(root, "记忆/日记")
        val names = diaryRoot.listFiles()?.map { it.name }.orEmpty()
        assertFalse("日记目录下不许有回收站", names.any { it.contains("trash") || it.contains("回收") })
    }

    @Test
    fun `空计划_不误删任何东西`() {
        val root = tmp.newFolder()
        val s = store(root)
        s.upsert(entry("1-20261022", "2026-10-22"))
        val plan = DiaryDeletionRules.plan(DiaryDeletionScope.ENTRY, s.loadAll().entries, ids = listOf("不存在的id"))
        assertTrue(plan.isEmpty)
        val report = s.realDelete(plan)
        assertEquals(0, report.deletedEntries)
        assertEquals(1, s.loadAll().entries.size)
    }
}
