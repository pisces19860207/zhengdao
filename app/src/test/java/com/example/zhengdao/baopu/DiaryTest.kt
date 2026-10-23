// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 147 阶段① · 日记模型/编解码/结构判据的 JVM 直测。
 *
 * 判据号对应《抱朴情景记忆设计方案-2026-10-22.md》v1.2 §8：
 * 判据 1（结构判据——摘要由计数拼装）、2（产物只存引用）、3（结构化无产出不记）、
 * 11（证据档必填且保守）、16（相对路径守卫）、21（脱敏闸）。
 */
class DiaryTest {

    private fun entry(
        id: String = "42-20261022",
        summary: String = "工具成功 3 次 · 失败 0 次 · 产物 2 个",
        artifacts: List<String> = listOf("整理/a.md", "整理/b/c.md"),
        evidence: DiaryEvidence = DiaryEvidence.OBSERVED,
    ) = DiaryEntry(
        id = id, date = "2026-10-22", sessionId = 42L,
        timeFromMs = 1000L, timeToMs = 2000L,
        title = "工具 3 次 · 产物 2 个", summary = summary,
        artifacts = artifacts, evidence = evidence,
        okSteps = 3, failedSteps = 0, createdAt = 2000L,
    )

    // ── 编解码往返 ──────────────────────────────────────────────────

    @Test
    fun `编解码_往返逐字段一致`() {
        val back = DiaryCodec.parse(DiaryCodec.render(entry()))!!
        assertEquals(entry(), back)
    }

    @Test
    fun `编解码_无产物时正文只有摘要行`() {
        val back = DiaryCodec.parse(DiaryCodec.render(entry(artifacts = emptyList())))!!
        assertEquals(0, back.artifacts.size)
        assertEquals(entry(artifacts = emptyList()).summary, back.summary)
    }

    @Test
    fun `判据11_解析缺档按最保守INFERRED_坏档同罪`() {
        val text = DiaryCodec.render(entry(evidence = DiaryEvidence.OBSERVED))
            .replace("evidence: observed\n", "")
        assertEquals(DiaryEvidence.INFERRED, DiaryCodec.parse(text)!!.evidence)
        val bad = DiaryCodec.render(entry()).replace("evidence: observed", "evidence: guessed")
        assertEquals(DiaryEvidence.INFERRED, DiaryCodec.parse(bad)!!.evidence)
    }

    @Test
    fun `编解码_关键字段缺失不装_返回null`() {
        val noId = DiaryCodec.render(entry()).replace("id: 42-20261022\n", "")
        val noSession = DiaryCodec.render(entry()).replace("session_id: 42\n", "")
        val noBody = "---\nid: x\ndate: 2026-10-22\nsession_id: 1\ntime_from: 1\ntime_to: 1\ntitle: t\nevidence: observed\ncreated_at: 1\n---\n\n"
        assertNull(DiaryCodec.parse(noId))
        assertNull(DiaryCodec.parse(noSession))
        assertNull(DiaryCodec.parse(noBody))
        assertNull(DiaryCodec.parse("不是 front-matter 的文本"))
    }

    // ── 判据 1：结构判据（摘要由计数拼装，不是模型自由作文） ─────────

    @Test
    fun `判据1_摘要与标题由计数拼装`() {
        assertEquals("工具成功 3 次 · 失败 1 次 · 产物 2 个", DiaryRules.composeSummary(3, 1, 2))
        assertEquals("工具 3 次 · 产物 2 个", DiaryRules.composeTitle(3, 2))
        // 拼装产物里不含任何结论句式的前缀（结论走学习卡）：
        assertFalse(DiaryRules.composeSummary(1, 0, 0).contains("用户"))
        assertFalse(DiaryRules.composeSummary(1, 0, 0).contains("以后"))
    }

    // ── 判据 3：结构化无产出不记 ────────────────────────────────────

    @Test
    fun `判据3_无成功工具步且无产物_不记`() {
        assertFalse(DiaryRules.hasActivity(0, 0))
        assertTrue(DiaryRules.hasActivity(1, 0))
        assertTrue(DiaryRules.hasActivity(0, 2))
    }

    // ── 判据 16：相对路径守卫 ───────────────────────────────────────

    @Test
    fun `判据16_绝对路径与越界一律拒绝_相对路径归一放行`() {
        assertNull(DiaryRules.relativePath("/storage/emulated/0/x.md"))
        assertNull(DiaryRules.relativePath("C:\\Users\\x.md"))
        assertNull(DiaryRules.relativePath("~/secret.md"))
        assertNull(DiaryRules.relativePath("a/../../etc/passwd"))
        assertNull(DiaryRules.relativePath("  "))
        assertEquals("整理/a.md", DiaryRules.relativePath("整理/a.md"))
        assertEquals("整理/a.md", DiaryRules.relativePath("整理\\a.md")) // 反斜杠归一
    }

    @Test
    fun `判据16_产物清单清洗_去重保序_丢弃计数交给调用方`() {
        val got = DiaryRules.sanitizeArtifacts(
            listOf("整理/a.md", "/abs/x", "整理/a.md", "../escape", "整理/b.md"),
        )
        assertEquals(listOf("整理/a.md", "整理/b.md"), got)
    }

    // ── 判据 21：脱敏闸 ─────────────────────────────────────────────

    @Test
    fun `判据21_敏感样式命中_清洁文本放行`() {
        assertTrue(DiaryRules.containsSensitive("拿到了 sk-abc12345678901234567 的结果", emptyList()))
        assertTrue(DiaryRules.containsSensitive("配置 api_key = xxx 成功", emptyList()))
        assertTrue(DiaryRules.containsSensitive("token: abc 已写入", emptyList()))
        assertTrue(DiaryRules.containsSensitive("password: 123456", emptyList()))
        assertTrue(DiaryRules.containsSensitive("带 Bearer abcdefgh123 的头", emptyList()))
        assertTrue(DiaryRules.containsSensitive("产物", listOf("keys/token=abc123.txt")))
        assertFalse(DiaryRules.containsSensitive("工具成功 3 次 · 失败 0 次 · 产物 2 个", listOf("整理/a.md")))
    }

    // ── id 规则 ─────────────────────────────────────────────────────

    @Test
    fun `条目id_会话加紧凑日期`() {
        assertEquals("42-20261022", DiaryRules.diaryId(42L, "2026-10-22"))
    }
}
