// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 147 阶段② · DiaryRecall 纯判据直测（判据 4/5/12/14/20）。
 *
 * 判据号对应《抱朴情景记忆设计方案-2026-10-22.md》v1.2 §8。
 */
class DiaryRecallTest {

    private val DAY = 86_400_000L
    private val now = 1_800_000_000_000L // 固定时钟：判据不读真时钟（测试确定性）

    private fun entry(
        id: String,
        date: String = "2026-10-20",
        timeToMs: Long = now - DAY,
        artifacts: List<String> = listOf("整理/a.md"),
        evidence: DiaryEvidence = DiaryEvidence.OBSERVED,
        title: String = "工具 1 次 · 产物 1 个",
        summary: String = "工具成功 1 次 · 失败 0 次 · 产物 1 个",
    ) = DiaryEntry(
        id = id, date = date, sessionId = 1L,
        timeFromMs = timeToMs - 1000, timeToMs = timeToMs,
        title = title, summary = summary, artifacts = artifacts,
        evidence = evidence, okSteps = 1, failedSteps = 0, createdAt = timeToMs,
    )

    // ── 判据 4：段序由枚举钉，召回排序只用条目字段（同输入同输出） ──────

    @Test
    fun `判据4_召回排序_摘要优先_其余新的在前_不读时钟`() {
        val old = entry("1-20261001", date = "2026-10-01", timeToMs = now - 30 * DAY)
        val recent = entry("2-20261022", date = "2026-10-22", timeToMs = now)
        val digest = entry(
            DiaryRecall.DIGEST_PREFIX + "2026-09", date = "2026-09",
            timeToMs = now - 40 * DAY, evidence = DiaryEvidence.CITED, title = "月度摘要",
        )
        val r1 = DiaryRecall.recall(listOf(old, recent, digest))
        val r2 = DiaryRecall.recall(listOf(digest, recent, old)) // 换输入序 ⇒ 同输出
        assertEquals(listOf(digest.id, recent.id, old.id), r1.selected.map { it.id })
        assertEquals(r1.selected.map { it.id }, r2.selected.map { it.id })
    }

    // ── 判据 5：预算只挑选不删除 ─────────────────────────────────────

    @Test
    fun `判据5_超预算只是不注入_omitted计数_文件一条不少`() {
        val many = (1..8).map { entry("$it-20261022", timeToMs = now - it * 1000L) }
        val r = DiaryRecall.recall(many)
        assertEquals(DiaryRecall.MAX_ITEMS, r.selected.size)
        assertEquals(many.size - DiaryRecall.MAX_ITEMS, r.omitted)
        // 纯函数没有任何写接口 ⇒ 「文件一条不少」由 DiaryStoreTest 的 loadAll 不变式钉
        assertTrue(DiaryRecall::class.java.declaredMethods.none { it.name in setOf("write", "delete", "upsert") })
    }

    @Test
    fun `判据5_字符预算也生效_超大单条跳过`() {
        val big = entry("big-20261022", summary = "长".repeat(DiaryRecall.MAX_CHARS))
        val small = entry("small-20261022", timeToMs = now)
        val r = DiaryRecall.recall(listOf(big, small))
        assertEquals(listOf("small-20261022"), r.selected.map { it.id })
        assertEquals(1, r.omitted)
    }

    // ── 判据 12：回灌带档标注 ────────────────────────────────────────

    @Test
    fun `判据12_实测不带后缀_摘要与推断必须标出来`() {
        assertFalse(DiaryRecall.renderForContext(entry("1-20261022")).contains("（推断）"))
        assertFalse(DiaryRecall.renderForContext(entry("1-20261022")).contains("（月度摘要）"))
        val inferred = DiaryRecall.renderForContext(
            entry("1-20261022", evidence = DiaryEvidence.INFERRED),
        )
        assertTrue("推断必须标出来：$inferred", inferred.contains("（推断）"))
        val cited = DiaryRecall.renderForContext(
            entry("d-202609", evidence = DiaryEvidence.CITED, title = "月度摘要"),
        )
        assertTrue("摘要必须标出来：$cited", cited.contains("（月度摘要）"))
    }

    @Test
    fun `判据12_回灌行带日期与产物引用`() {
        val line = DiaryRecall.renderForContext(entry("1-20261022"))
        assertTrue(line.contains("【日记 2026-10-20】"))
        assertTrue(line.contains("整理/a.md"))
    }

    // ── 判据 14：月度摘要保留全部产物引用 ────────────────────────────

    @Test
    fun `判据14_摘要产物引用一条不丢_并集去重`() {
        val group = listOf(
            entry("1-20261005", date = "2026-10-05", artifacts = listOf("整理/a.md", "整理/b.md")),
            entry("2-20261012", date = "2026-10-12", artifacts = listOf("整理/b.md", "整理/c.md")),
        )
        val digest = DiaryRecall.composeDigest("2026-10", group, now)
        assertEquals(listOf("整理/a.md", "整理/b.md", "整理/c.md"), digest.artifacts)
        assertEquals(DiaryEvidence.CITED, digest.evidence) // 提炼自文件 ⇒ 带档
        assertEquals("digest-2026-10", digest.id)
        assertTrue(digest.summary.contains("2 个会话"))
        assertTrue(digest.summary.contains("主题："))
        assertEquals(2, digest.okSteps) // 计数合计
    }

    // ── 判据 20：归档清理判据 ────────────────────────────────────────

    @Test
    fun `判据20_覆盖判据_被摘要完整承载才可删_缺一不可`() {
        val carried = entry("1-20260401", date = "2026-04-01", artifacts = listOf("整理/a.md"))
        val orphan = entry("3-20260402", date = "2026-04-02", artifacts = listOf("整理/only-here.md"))
        val digest = DiaryRecall.composeDigest("2026-04", listOf(carried), now)
        assertEquals(
            listOf("1-20260401"),
            DiaryRecall.cleanupCandidates(listOf(carried, orphan), listOf(digest)).map { it.id },
        )
        // 没有同月摘要 ⇒ 一条都不许删
        assertEquals(
            emptyList<String>(),
            DiaryRecall.cleanupCandidates(listOf(carried), emptyList()).map { it.id },
        )
    }

    @Test
    fun `归档候选_超过保留窗口的非摘要条目`() {
        val old = entry("1-20260101", date = "2026-01-01", timeToMs = now - 60 * DAY)
        val fresh = entry("2-20261022", timeToMs = now)
        val digest = entry(DiaryRecall.DIGEST_PREFIX + "2026-01", date = "2026-01", timeToMs = now - 60 * DAY)
        val cands = DiaryRecall.archiveCandidates(listOf(old, fresh, digest), now)
        assertEquals(listOf("1-20260101"), cands.map { it.id }) // 摘要不重复归档
    }

    // ── 死参数陷阱回归门禁（蓝总管 2026-10-22 20:1x 指出，147 合并前必修） ──

    /**
     * RunEngine 的私有 assemble 包装：签名有 `diaryEntries` 参数，函数体就必须转它——
     * 不许绕过参数直调 `diaryRecall()`（那签名就是死的，调用方显式传值会被静默丢掉）。
     *
     * 现行为没变（两个调用点都走默认值 `= diaryRecall()`），但显式传参从此有效；
     * 本门禁钉住这个转发关系，防下一个人「顺手简化」回去。
     */
    @Test
    fun `门禁_包装函数转发diaryEntries参数_不绕过它直调闭包`() {
        val cands = listOf(
            java.io.File("app/src/main/java/com/example/zhengdao/baopu/agent/RunEngine.kt"),
            java.io.File("../app/src/main/java/com/example/zhengdao/baopu/agent/RunEngine.kt"),
        )
        val src = cands.firstOrNull { it.isFile }?.readText()
            ?: throw AssertionError("找不到 RunEngine.kt")
        val code = src.replace(Regex("(?m)^[ \\t]*//.*$"), "") // 剥整行注释（防注释里的反例词误伤）
        assertTrue(
            "包装函数必须转发 diaryEntries 参数（ diaryEntries = diaryEntries ）",
            code.contains("diaryEntries = diaryEntries"),
        )
        assertFalse(
            "不许绕过参数直调闭包（ diaryEntries = diaryRecall() ）——死参数陷阱",
            code.contains("diaryEntries = diaryRecall()"),
        )
    }
}
