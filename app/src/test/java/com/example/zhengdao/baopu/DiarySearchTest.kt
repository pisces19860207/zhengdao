// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 157 · 日记层检索（日期范围＋关键词）纯判据直测＋负例。
 *
 * 对应账 §B-待做 149（「记得住、找不到」缺口）与《派工单-2026-10-23-第一百五十七批-日记层检索》。
 * 纪律同 147：判据纯函数化 JVM 直测；自检类判据**必须带负例**——
 * 每条「必须」都有对应的「不许」用例，变异演练全红才收（见 _l157_neg.py）。
 */
class DiarySearchTest {

    private val DAY = 86_400_000L
    private val now = 1_800_000_000_000L // 固定时钟：判据不读真时钟（测试确定性）

    private fun entry(
        id: String,
        date: String = "2026-10-20",
        timeToMs: Long = now - DAY,
        title: String = "工具 1 次 · 产物 1 个",
        summary: String = "工具成功 1 次 · 失败 0 次 · 产物 1 个",
        artifacts: List<String> = listOf("整理/a.md"),
        evidence: DiaryEvidence = DiaryEvidence.OBSERVED,
    ) = DiaryEntry(
        id = id, date = date, sessionId = 1L,
        timeFromMs = timeToMs - 1000, timeToMs = timeToMs,
        title = title, summary = summary, artifacts = artifacts,
        evidence = evidence, okSteps = 1, failedSteps = 0, createdAt = timeToMs,
    )

    // ── J1：日期范围闭区间 ──────────────────────────────────────────

    @Test
    fun `J1_日期范围闭区间_边界当天算命中_单侧缺省不限`() {
        val a = entry("1-20261001", date = "2026-10-01", timeToMs = now - 30 * DAY)
        val b = entry("2-20261015", date = "2026-10-15", timeToMs = now - 20 * DAY)
        val c = entry("3-20261031", date = "2026-10-31", timeToMs = now - 10 * DAY)
        val all = listOf(a, b, c)
        assertEquals(
            listOf("2-20261015"),
            DiaryRecall.search(all, DiaryRecall.DiaryQuery(dateFrom = "2026-10-15", dateTo = "2026-10-15")).map { it.id },
        )
        assertEquals(
            listOf("3-20261031", "2-20261015"),
            DiaryRecall.search(all, DiaryRecall.DiaryQuery(dateFrom = "2026-10-15")).map { it.id },
        )
        assertEquals(
            listOf("2-20261015", "1-20261001"),
            DiaryRecall.search(all, DiaryRecall.DiaryQuery(dateTo = "2026-10-15")).map { it.id },
        )
    }

    @Test
    fun `J1负例_范围外条目不进结果_哪怕关键词命中`() {
        val inside = entry("in-20261015", date = "2026-10-15", summary = "改了 README 的拼写")
        val outside = entry("out-20261001", date = "2026-10-01", summary = "也改了 README 的拼写")
        val r = DiaryRecall.search(
            listOf(outside, inside),
            DiaryRecall.DiaryQuery(dateFrom = "2026-10-10", dateTo = "2026-10-20", keywords = listOf("readme")),
        )
        assertEquals(listOf("in-20261015"), r.map { it.id })
    }

    // ── J2：关键词命中（中西文两形态、大小写不敏感） ─────────────────

    @Test
    fun `J2_关键词命中_拉丁大小写不敏感_产物路径也算`() {
        val e = entry("1-20261020", artifacts = listOf("整理/README.md"))
        assertEquals(1, DiaryRecall.search(listOf(e), DiaryRecall.DiaryQuery(keywords = listOf("readme"))).size)
        assertEquals(1, DiaryRecall.search(listOf(e), DiaryRecall.DiaryQuery(keywords = listOf("readme.md"))).size)
    }

    @Test
    fun `J2_CJK二元组命中标题与摘要`() {
        val e = entry("1-20261020", title = "月度摘要", summary = "本月 3 个会话；主题：写周报")
        val r = DiaryRecall.search(listOf(e), DiaryRecall.DiaryQuery(keywords = listOf("周报")))
        assertEquals(listOf("1-20261020"), r.map { it.id })
    }

    @Test
    fun `J2负例_不命中的条目不许进结果`() {
        val hit = entry("hit", summary = "写了周报")
        val miss = entry("miss")
        val r = DiaryRecall.search(listOf(hit, miss), DiaryRecall.DiaryQuery(keywords = listOf("周报")))
        assertEquals(listOf("hit"), r.map { it.id })
        assertFalse(r.any { it.id == "miss" })
    }

    // ── J3：排序——分降 → 新 → id；输入换序同输出 ────────────────────

    @Test
    fun `J3_检索排序_分降_然后新_然后id_输入换序同输出`() {
        val both = entry("both", timeToMs = now - 5 * DAY, summary = "周报＋readme 都在")
        val oneOld = entry("one-old", timeToMs = now - DAY, summary = "只有周报")
        val oneNew = entry("one-new", timeToMs = now, summary = "只有周报")
        val q = DiaryRecall.DiaryQuery(keywords = listOf("周报", "readme"))
        val r1 = DiaryRecall.search(listOf(oneOld, both, oneNew), q)
        val r2 = DiaryRecall.search(listOf(oneNew, both, oneOld), q)
        assertEquals(listOf("both", "one-new", "one-old"), r1.map { it.id })
        assertEquals(r1.map { it.id }, r2.map { it.id })
    }

    // ── J4：召回带查询——摘要恒最前、命中提前、预算不变 ──────────────

    @Test
    fun `J4_召回带查询_摘要仍最前_命中排未命中前`() {
        val digest = entry(
            DiaryRecall.DIGEST_PREFIX + "2026-09", date = "2026-09", timeToMs = now - 40 * DAY,
            title = "月度摘要", summary = "本月 2 个会话", evidence = DiaryEvidence.CITED,
        )
        val oldHit = entry("old-hit", date = "2026-10-01", timeToMs = now - 30 * DAY, summary = "写了周报")
        val freshMiss = entry("fresh-miss", date = "2026-10-22", timeToMs = now)
        val oldMiss = entry("old-miss", date = "2026-10-05", timeToMs = now - 20 * DAY, summary = "工具成功 2 次 · 失败 0 次 · 产物 2 个")
        val r = DiaryRecall.recall(listOf(freshMiss, oldMiss, oldHit, digest), DiaryRecall.DiaryQuery(keywords = listOf("周报")))
        assertEquals(listOf(digest.id, "old-hit", "fresh-miss", "old-miss"), r.selected.map { it.id })
    }

    @Test
    fun `J4_带查询仍守5条与1000字符_超预算只是不注入`() {
        val hits = (1..8).map { entry("hit-$it", timeToMs = now - it * 1000L, summary = "写了周报") }
        val r = DiaryRecall.recall(hits, DiaryRecall.DiaryQuery(keywords = listOf("周报")))
        assertEquals(DiaryRecall.MAX_ITEMS, r.selected.size)
        assertEquals(3, r.omitted)
    }

    // ── J5：回归——空查询/null 与 147 逐字相同 ───────────────────────

    @Test
    fun `J5回归_查询为null或不活跃_挑选与147逐字相同`() {
        val batch = listOf(
            entry("a", date = "2026-10-01", timeToMs = now - 30 * DAY),
            entry("b", date = "2026-10-22", timeToMs = now),
            entry(
                DiaryRecall.DIGEST_PREFIX + "2026-09", date = "2026-09", timeToMs = now - 40 * DAY,
                title = "月度摘要", summary = "本月 2 个会话", evidence = DiaryEvidence.CITED,
            ),
        )
        val base = DiaryRecall.recall(batch)
        assertEquals(base.selected.map { it.id }, DiaryRecall.recall(batch, null).selected.map { it.id })
        assertEquals(base.selected.map { it.id }, DiaryRecall.recall(batch, DiaryRecall.DiaryQuery()).selected.map { it.id })
        assertEquals(base.omitted, DiaryRecall.recall(batch, DiaryRecall.DiaryQuery()).omitted)
    }

    @Test
    fun `J5负例_空查询检索必须返回空_不许把全部条目当命中`() {
        val batch = listOf(entry("a"), entry("b"))
        assertTrue(DiaryRecall.search(batch, DiaryRecall.DiaryQuery()).isEmpty())
        assertTrue(DiaryRecall.search(batch, DiaryRecall.DiaryQuery(keywords = emptyList())).isEmpty())
    }

    // ── J6：提取——显式日期、关键词形态、上限、不读时钟 ──────────────

    @Test
    fun `J6_提取_显式日期_单个当天_多个取首尾_相对时间不猜`() {
        val one = DiaryRecall.queryFromInput("2026-10-22 那天我们干了什么")
        assertEquals("2026-10-22", one.dateFrom)
        assertEquals("2026-10-22", one.dateTo)
        val two = DiaryRecall.queryFromInput("从 2026-10-01 到 2026-10-22 之间")
        assertEquals("2026-10-01", two.dateFrom)
        assertEquals("2026-10-22", two.dateTo)
        val rel = DiaryRecall.queryFromInput("上周三下午那次改了什么")
        assertEquals(null, rel.dateFrom)
        assertEquals(null, rel.dateTo)
    }

    @Test
    fun `J6_提取_关键词_拉丁词在前_CJK二元组随后_去重_小写_纯数字词不要`() {
        val q = DiaryRecall.queryFromInput("2026-10-22 找一下 README 的周报")
        assertTrue(q.keywords.contains("readme"))
        assertTrue(q.keywords.contains("周报"))
        assertFalse("纯数字词是噪音，不许进关键词：${q.keywords}", q.keywords.contains("2026"))
        assertTrue(q.keywords.all { it == it.lowercase() })
        assertEquals(q.keywords.size, q.keywords.distinct().size)
    }

    @Test
    fun `J6_提取_上限截断_长句也不许超12`() {
        val long = "把" + "甲乙丙丁戊己庚辛壬癸".repeat(3) + "这些都整理一下还有那些东西"
        val q = DiaryRecall.queryFromInput(long)
        assertEquals(DiaryRecall.MAX_KEYWORDS, q.keywords.size)
        assertTrue(q.keywords.all { it.length == 2 })
    }

    @Test
    fun `J6_提取_不读时钟_同输入同输出_空输入空查询`() {
        val text = "2026-10-22 找一下 README 的周报"
        assertEquals(DiaryRecall.queryFromInput(text), DiaryRecall.queryFromInput(text))
        assertTrue(DiaryRecall.queryFromInput(text).isActive)
        assertFalse(DiaryRecall.queryFromInput("").isActive)
        assertFalse(DiaryRecall.queryFromInput("   ").isActive)
    }

    // ── J7：零写接口（同 147 判据 5 的门禁） ────────────────────────

    @Test
    fun `J7_检索与召回_零写接口_读时挑选不删除`() {
        assertTrue(DiaryRecall::class.java.declaredMethods.none { it.name in setOf("write", "delete", "upsert", "remove") })
    }

    // ── J8：接线门禁（死参数陷阱，同 147 的教训） ────────────────────

    @Test
    fun `J8_接线门禁_组装层必须把查询转进recall_引擎必须转发不现提`() {
        fun read(rel: String): String {
            val cands = listOf(
                java.io.File("app/src/main/java/com/example/zhengdao/$rel"),
                java.io.File("../app/src/main/java/com/example/zhengdao/$rel"),
            )
            return cands.firstOrNull { it.isFile }?.readText()?.replace(Regex("(?m)^[ \\t]*//.*$"), "")
                ?: throw AssertionError("找不到 $rel")
        }
        val assembler = read("baopu/ContextAssembler.kt")
        assertTrue(
            "组装层必须把 diaryQuery 转进 recall（ DiaryRecall.recall(diaryEntries, diaryQuery) ）",
            assembler.contains("DiaryRecall.recall(diaryEntries, diaryQuery)"),
        )
        val engine = read("baopu/agent/RunEngine.kt")
        assertTrue(
            "引擎包装必须转发 diaryQuery 参数（ diaryQuery = diaryQuery ）——死参数陷阱",
            engine.contains("diaryQuery = diaryQuery"),
        )
    }
}
