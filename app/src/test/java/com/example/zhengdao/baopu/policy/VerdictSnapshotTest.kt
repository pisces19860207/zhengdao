// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百九十八批 · 表快照本体：**不可变 ＋ 原子换指针 ＋ fail closed**。
//
// 派工单第 1) 条的两个词是有形状的：
//   - **不可变**：表没有写口（字段全 final、集合只读），"改了一半"的形状**构造不出来**——
//     跨字段不变式写在构造器里（明细求和、抑制类数、键与行内类名、时刻落在窗口里、
//     阈值不越界、明细有界），对不上就抛，半截表连对象都不是；
//   - **原子换指针**：写侧只有一处 `snapshot = …`（在 `synchronized` 里**先建完整表再一次性引用写**），
//     读侧一次 `@Volatile` 引用读。所以负例④「表更新时读到改了一半」在这里有两条墙：
//     结构上一条（建不出来）、指针上一条（只一次写）。
//
// 还钉四条容易走样的语义（都是 283 定的，本批只许照搬，不许"顺手修好"）：
//   窗口是**尺子**不是过滤器（读时按 `now` 现算，老表会自然出窗）；
//   最近一次**重问清零**；
//   账空 ⇒ 换**空表**（不是留着旧表——判据②「删掉表」靠这一条才成立）；
//   折表被拒 ⇒ **保留上一版**（fail closed，不换半截）。
package com.example.zhengdao.baopu.policy

import com.example.zhengdao.baopu.agent.ExperienceKind
import com.example.zhengdao.baopu.agent.ExperienceRow
import com.example.zhengdao.baopu.agent.LearnPolicy
import java.io.File
import java.lang.reflect.Modifier
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class VerdictSnapshotTest {

    private val now = 1_700_000_000_000L
    private val dayMs = 24L * 3600 * 1000

    @After
    fun tearDown() = VerdictExperienceRuntime.reset()

    // ── 不可变：写口在类型上就不存在 ───────────────────────────────────────

    @Test
    fun `表与行全部字段final_没有任何写口`() {
        listOf(VerdictExperienceSnapshot::class.java, ClassExperience::class.java, Draft::class.java).forEach { k ->
            k.declaredFields.filterNot { it.isSynthetic || Modifier.isStatic(it.modifiers) }.forEach { f ->
                assertTrue("${k.simpleName}.${f.name} 不是 final（表可变＝负例④）", Modifier.isFinal(f.modifiers))
            }
            k.declaredMethods.filterNot { it.isSynthetic || "$" in it.name }.forEach { m ->
                val n = m.name
                assertFalse("${k.simpleName} 出现了写口 $n", n.startsWith("set") || n.startsWith("add") || n.startsWith("put") || n == "clear")
            }
        }
        // 类表必须是只读 Map（不是 MutableMap）：换内容得换整张表
        assertTrue(
            "classes 字段类型必须是 java.util.Map",
            VerdictExperienceSnapshot::class.java.getDeclaredField("classes").type == java.util.Map::class.java,
        )
    }

    @Test
    fun `半截表构造不出来_七条不变式逐条红`() {
        val row = ClassExperience("A", listOf(now - dayMs, now - 2 * dayMs), approves = 0, rejects = 0, windowStartMs = now - 30 * dayMs, lastReaskAtMs = null)
        val ok = mapOf("A" to row)
        // 正例先立住（防"恒红"）
        val good = snap(ok, closeTotal = 2, suppressedTotal = 0)
        assertEquals(2, good.closeTotal)

        assertBad("明细求和对不上账面") { snap(ok, closeTotal = 1, suppressedTotal = 0) }
        assertBad("抑制类数与明细对不上") { snap(ok, closeTotal = 2, suppressedTotal = 1) }
        assertBad("键与行内类名不一致") {
            snap(mapOf("B" to row), closeTotal = 2, suppressedTotal = 0)
        }
        assertBad("时刻落在建表窗口之外") {
            val r = row.copy(closeTimes = listOf(now + dayMs))
            snap(mapOf("A" to r), closeTotal = 1, suppressedTotal = 0)
        }
        assertBad("明细超过上限（查表代价必须与账厚无关）") {
            val r = row.copy(closeTimes = (1..VerdictExperienceSnapshot.MAX_CLOSE_TIMES + 1).map { now - it })
            snap(mapOf("A" to r), closeTotal = r.closeTimes.size, suppressedTotal = 0)
        }
        assertBad("阈值 0＝表变成开关") { snap(ok, closeTotal = 2, suppressedTotal = 0, threshold = 0) }
        assertBad("阈值越界＝学到变成永远不弹") { snap(ok, closeTotal = 2, suppressedTotal = 0, threshold = 11) }
        assertBad("序号为负") { snap(ok, closeTotal = 2, suppressedTotal = 0, seq = -1L) }
    }

    private fun snap(
        classes: Map<String, ClassExperience>,
        closeTotal: Int,
        suppressedTotal: Int,
        threshold: Int = LearnPolicy.SUPPRESS_AFTER_CLOSES,
        seq: Long = 1L,
    ) = VerdictExperienceSnapshot(
        seq = seq,
        builtAtMs = now,
        threshold = threshold,
        classes = classes,
        rowCount = closeTotal,
        closeTotal = closeTotal,
        suppressedTotal = suppressedTotal,
    )

    private fun assertBad(what: String, block: () -> Unit) {
        val r = runCatching(block)
        if (r.isSuccess) fail("$what —— 竟然建成了：$r")
        assertTrue("$what ⇒ 应抛 IllegalArgumentException，实抛 ${r.exceptionOrNull()}", r.exceptionOrNull() is IllegalArgumentException)
    }

    // ── 原子换指针：写侧一次引用写；拒绝时一个字节都不动 ─────────────────

    @Test
    fun `源级钉_refresh里只有一次指针写_拒绝分支不换表`() {
        val body = codeOf("policy/VerdictExperienceRuntime.kt")
            .substringAfter("fun refresh(")
            .substringBefore("\n    }")
        assertTrue("前提：这段确实在换表", body.contains("snapshot = built"))
        assertEquals("换表只许一次引用写（两次＝中间那一次就是负例④）", 1, body.split("snapshot =").size - 1)
        // 拒绝分支（built == null）在那一次写**之前**，且自己不许写
        val beforeWrite = body.substringBefore("snapshot = built")
        assertTrue("前提：拒绝分支在写之前", beforeWrite.contains("if (built == null)"))
        assertFalse("拒绝换表时动了指针 ⇒ 半截表会生效", beforeWrite.contains("snapshot ="))
    }

    @Test
    fun `换表seq单调递增_读侧永远拿到完整一版`() {
        VerdictExperienceRuntime.reset()
        assertEquals(0L, VerdictExperienceRuntime.current().seq)
        assertEquals(SnapshotStatus.INIT, VerdictExperienceRuntime.state().status)
        VerdictExperienceRuntime.refresh(closeRows("A", 3), now, "test")
        assertEquals(1L, VerdictExperienceRuntime.current().seq)
        assertEquals(SnapshotStatus.LOADED, VerdictExperienceRuntime.state().status)
        VerdictExperienceRuntime.refresh(closeRows("A", 4), now + dayMs, "test")
        assertEquals(2L, VerdictExperienceRuntime.current().seq)
        // 读到的那一版自己必须自洽（不是"先看到新明细再看到旧总数"）
        val s = VerdictExperienceRuntime.current()
        assertEquals(s.classes.values.sumOf { it.closeTimes.size }, s.closeTotal)
        assertEquals(4, s.closeTotal)
    }

    @Test
    fun `账空就换一张空表_而不是留着旧表`() {
        VerdictExperienceRuntime.reset()
        VerdictExperienceRuntime.refresh(closeRows("A", 5), now, "test")
        assertTrue(VerdictExperienceRuntime.current().suppresses("A", now))
        VerdictExperienceRuntime.refresh(emptyList(), now + dayMs, "ledger 已删")
        assertFalse("表删空 ⇒ 这一类不再被压制（判据②的机制）", VerdictExperienceRuntime.current().suppresses("A", now + dayMs))
        assertEquals(0, VerdictExperienceRuntime.current().classes.size)
        assertEquals(SnapshotStatus.LOADED, VerdictExperienceRuntime.state().status)
        assertEquals("换上来的是空表：账面 0 行", 0, VerdictExperienceRuntime.state().rowCount)
    }

    @Test
    fun `清表有审计行_换表与拒绝各一行`() {
        VerdictExperienceRuntime.reset()
        val loaded = VerdictExperienceRuntime.refresh(closeRows("A", 3), now, "signal")
        assertTrue("换表审计：$loaded", loaded.startsWith("[经验表] 已换表 v1（来源=signal）"))
        assertTrue(loaded.contains("账面 3 行"))
        val cleared = VerdictExperienceRuntime.clear("判据②")
        assertTrue("清表审计：$cleared", cleared.contains("[经验表] 已清表"))
        val rejected = VerdictExperienceRuntime.auditLine(
            SnapshotState(SnapshotStatus.REJECTED, 7L, 3, "IllegalStateException: 两把尺子对不上"),
            "reask",
        )
        assertTrue("拒绝审计要点名来源与保留的版本：$rejected", rejected.contains("拒绝换表（来源=reask）"))
        assertTrue(rejected.contains("保留 v7"))
    }

    // ── 语义照搬 283：窗口是尺子／重问清零／阈值上限 ───────────────────────

    @Test
    fun `窗口是尺子不是过滤器_老表读到第29天自然出窗`() {
        val rows = closeRows("A", 3, at = { now - it * dayMs }) // 昨天/前天/大前天各关一次
        val s = VerdictExperienceSnapshot.build(rows, now, seq = 1L)
        assertTrue("建表那一刻：3 次关 ⇒ 压", s.suppresses("A", now))
        assertFalse("读时按 now 现算：第 28 天只剩 2 次 ⇒ 不压", s.suppresses("A", now + 28 * dayMs))
        assertEquals("出窗之后读数也跟着降", 2, s.statsOf("A", now + 28 * dayMs).closes)
        // 同一把尺子：与 283 的 stats 在一个一个时刻上对得上（窗内那部分）
        assertEquals(LearnPolicy.stats(rows, "A", now).closes, s.statsOf("A", now).closes)
    }

    @Test
    fun `最近一次重问清零_与283同一条`() {
        val rows = closeRows("A", 3) + ExperienceRow(
            id = "reask-1",
            atMs = now + 1,
            kind = ExperienceKind.REASK,
            cardClass = "A",
            key = "signal:reask",
            reason = "用户说这类以后还要问",
            evidence = listOf("ref:reask"),
        )
        val s = VerdictExperienceSnapshot.build(rows, now + dayMs, seq = 1L)
        assertEquals("重问之前的关一律不作数", 0, s.statsOf("A", now + dayMs).closes)
        assertFalse(s.suppresses("A", now + dayMs))
        assertEquals(1, s.classes.getValue("A").lastReaskAtMs?.let { 1 } ?: 0)
    }

    @Test
    fun `明细有界64条_阈值判定不受影响`() {
        val rows = closeRows("胖类", 500)
        val s = VerdictExperienceSnapshot.build(rows, now, seq = 1L)
        val kept = s.classes.getValue("胖类").closeTimes
        assertEquals(VerdictExperienceSnapshot.MAX_CLOSE_TIMES, kept.size)
        assertTrue("关 500 次当然该压", s.suppresses("胖类", now))
        // 唯一允许与 283 读数不同的地方：明细有上限 ⇒ 表里的 closes 封顶 64，而账上是 500。
        // 阈值是 3，两边都远大于它；次序读数（approves）与它无关。这一条差写在报告里。
        assertEquals(500, LearnPolicy.stats(rows, "胖类", now).closes)
        assertEquals(VerdictExperienceSnapshot.MAX_CLOSE_TIMES, s.statsOf("胖类", now).closes)
    }

    // ── 草稿层与表的接缝：没这一类 ⇒ MISS，一句理由都不许编 ────────────────

    @Test
    fun `表里没有这一类_不编理由`() {
        val s = VerdictExperienceSnapshot.build(closeRows("A", 3), now, seq = 2L)
        assertNull("表里没有这一类 ⇒ 一句理由都不许编", s.reasonFor("没有这个类", now))
        assertFalse(s.suppresses("没有这个类", now))
        assertTrue("命中理由要点名版本：${s.reasonFor("A", now)}", s.reasonFor("A", now)!!.contains("命中经验表 v2"))
        // 没到阈值也不编：3 次里的前 2 次
        val two = VerdictExperienceSnapshot.build(closeRows("A", 2), now, seq = 3L)
        assertNull("没到阈值也不编理由", two.reasonFor("A", now))
    }

    @Test
    fun `结论行只出计数不出明细`() {
        val s = VerdictExperienceSnapshot.build(closeRows("A", 3) + closeRows("B", 1), now, seq = 4L)
        val line = s.conclusion(now)
        assertFalse("结论不许把类名递出去：$line", line.contains("A") || line.contains("B"))
        assertTrue("结论要有版本/行数/阈值：$line", line.contains("v4") && line.contains("阈值=3"))
    }

    // ── 帮手 ────────────────────────────────────────────────────────────

    private fun mainRoot(): File = File("src/main").takeIf { it.isDirectory } ?: File("app/src/main")

    private fun codeOf(rel: String): String {
        val text = File(mainRoot(), "java/com/example/zhengdao/baopu/$rel").readText(Charsets.UTF_8)
        return text.replace(Regex("""/\*[\s\S]*?\*/"""), " ").replace(Regex("(?m)//.*$"), " ")
    }

    private fun closeRows(cardClass: String, n: Int, at: (Int) -> Long = { now - it }): List<ExperienceRow> =
        (1..n).map { i ->
            ExperienceRow(
                id = "$cardClass#$i",
                atMs = at(i),
                kind = ExperienceKind.SIGNAL,
                cardClass = cardClass,
                key = "signal:dismiss",
                reason = "测试用的关",
                evidence = listOf("ref:$cardClass:$i"),
            )
        }
}
