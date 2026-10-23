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
 * 157「修正已有条目」：amendGuard（provenance 六字段闸＋脱敏闸）JVM 直测。
 *
 * 用户原话：「修正已有条目」是本批要补的——改的必须是**已有条目**上可改的字段
 * （title/summary/artifacts/evidence/okSteps/failedSteps）；provenance 六字段
 * （id/date/sessionId/timeFromMs/timeToMs/createdAt）一个都不许动。
 */
class DiaryAmendTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun entry(
        id: String = "42-20261023",
        date: String = "2026-10-23",
        sessionId: Long = 42L,
        timeFromMs: Long = 1000L,
        timeToMs: Long = 1100L,
        createdAt: Long = 1000L,
        title: String = "工具 3 次 · 产物 1 个",
        summary: String = "工具成功 3 次 · 失败 0 次 · 产物 1 个",
        artifacts: List<String> = listOf("产物/a.md"),
    ) = DiaryEntry(
        id = id, date = date, sessionId = sessionId,
        timeFromMs = timeFromMs, timeToMs = timeToMs,
        title = title, summary = summary, artifacts = artifacts,
        evidence = DiaryEvidence.OBSERVED, okSteps = 3,
        failedSteps = 0, createdAt = createdAt,
    )

    // ── 正例：只改可改字段 ⇒ 放行 ──────────────────────────────────────────

    @Test
    fun `正例_只改可改字段_闸放行`() {
        val existing = entry()
        val patch = entry(
            title = "工具 5 次 · 产物 2 个",
            summary = "工具成功 5 次 · 失败 1 次 · 产物 2 个",
            artifacts = listOf("产物/a.md", "产物/b.md"),
        ).copy(okSteps = 5, failedSteps = 1)
        assertNullReason(DiaryRules.amendGuard(existing, patch))
    }

    private fun assertNullReason(reason: String?) {
        assertEquals("应放行（null），实际被拒：$reason", null, reason)
    }

    // ── 负例：provenance 六字段逐一钉死 ────────────────────────────────────

    @Test
    fun `负例_id不许改`() {
        val r = DiaryRules.amendGuard(entry(), entry(id = "99-20261023"))
        assertTrue("id 改了必须拒：$r", r != null && r.contains("id"))
    }

    @Test
    fun `负例_date不许改`() {
        val r = DiaryRules.amendGuard(entry(), entry(date = "2026-10-24"))
        assertTrue("date 改了必须拒：$r", r != null && r.contains("date"))
    }

    @Test
    fun `负例_sessionId不许改`() {
        val r = DiaryRules.amendGuard(entry(), entry(sessionId = 99L))
        assertTrue("sessionId 改了必须拒：$r", r != null && r.contains("sessionId"))
    }

    @Test
    fun `负例_时间窗不许改`() {
        val r = DiaryRules.amendGuard(entry(), entry(timeFromMs = 2000L))
        assertTrue("timeFromMs 改了必须拒：$r", r != null && r.contains("时间窗"))
        val r2 = DiaryRules.amendGuard(entry(), entry(timeToMs = 9000L))
        assertTrue("timeToMs 改了必须拒：$r2", r2 != null && r2.contains("时间窗"))
    }

    @Test
    fun `负例_createdAt不许改`() {
        val r = DiaryRules.amendGuard(entry(), entry(createdAt = 5000L))
        assertTrue("createdAt 改了必须拒：$r", r != null && r.contains("createdAt"))
    }

    @Test
    fun `负例_脱敏闸_修正也不许带敏感样式`() {
        val r = DiaryRules.amendGuard(
            entry(),
            entry(summary = "api_key=sk-abcdef123456 已写入配置"),
        )
        assertTrue("脱敏闸必须在修正路径也拦：$r", r != null && r.contains("脱敏"))
    }

    // ── 集成：DiaryStore.amend（修正必须改在已有条目上） ─────────────────────

    @Test
    fun `集成_条目不存在_amend拒_不许借修正新插`() {
        val root = tmp.newFolder()
        val s = DiaryStore(MemoryLayout.memoryDir(root))
        val r = s.amend(entry())
        assertFalse("不存在的条目不许 amend：$r", r.ok)
        assertTrue("拒绝理由要说明不存在：${r.reason}", r.reason != null && r.reason.contains("不存在"))
    }

    @Test
    fun `集成_amend同id替换_可改字段生效_provenance原样`() {
        val root = tmp.newFolder()
        val s = DiaryStore(MemoryLayout.memoryDir(root))
        assertTrue(s.upsert(entry()).ok)

        val patch = entry(
            title = "工具 5 次 · 产物 2 个",
            summary = "工具成功 5 次 · 失败 0 次 · 产物 2 个",
            artifacts = listOf("产物/a.md", "产物/b.md"),
        ).copy(okSteps = 5)
        val r = s.amend(patch)

        assertTrue("amend 应成功：${r.reason}", r.ok)
        val day = s.loadDay("2026-10-23")
        assertEquals(1, day.size)
        val got = day.single()
        assertEquals("工具 5 次 · 产物 2 个", got.title)
        assertEquals(5, got.okSteps)
        // provenance 六字段原样（时间窗/createdAt/sessionId/date/id）
        assertEquals(1000L, got.timeFromMs)
        assertEquals(1100L, got.timeToMs)
        assertEquals(1000L, got.createdAt)
        assertEquals(42L, got.sessionId)
    }
}
