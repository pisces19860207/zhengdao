// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第三百七十五批 · 闭环时延与振荡的门禁（判据①②③④逐条钉；本体见 LoopTiming.kt）。
//
// 对应派工单 §完整判据：
//   ① 5 条已知时刻的偏差账＋处置账 ⇒ p50/p90 手算对拍（逐段验）→ `判据①_五条已知时刻_p50p90手算对拍`；
//   ② 某段缺失 ⇒ 不计入、报「缺 N 条」（不许当 0）→ `判据②_缺失段不计入_报缺失条数`；
//   ③ 振荡：同 kind 24h 内 ≥3 次＋冻结→解除→再冻结 ⇒ OSCILLATION（只标不处置）→ 判据③三件；
//   ④ 只读：不写任何账（字节级对拍＋源级零写口，源级带正例防恒真）。
//
// 第四百二十九批（C 域③）补钉：runId join——单号时间线只收同号行
// （跨号不串场、旧处置行出处不明宁缺勿猜、空白单号空时间线、整本账口径不动）。
package com.example.zhengdao.baopu.inspect

import com.example.zhengdao.baopu.CardDisposition
import com.example.zhengdao.baopu.CardDisposition.CardDispositionRow
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoopTimingTest {

    private val root = File("build/tmp/loop375-timing").apply {
        deleteRecursively()
        mkdirs()
    }

    private val base = 1_700_000_000_000L
    private val min = LoopTiming.MS_PER_MINUTE

    // ── 造账手法：偏差账走 363 的写入口；卡处置账直写文件（测试内写，统计器本身零写口） ──

    private fun writeDeviation(entry: DeviationEntry) {
        assertTrue(DeviationLedger.write(root, entry))
    }

    private fun writeDisposal(
        atMs: Long,
        cardId: String,
        subject: String,
        choice: CardDisposition.Choice = CardDisposition.Choice.DISMISS,
        actor: CardDisposition.Actor = CardDisposition.Actor.CLOCK,
        runId: String = "",
    ) {
        val row = CardDispositionRow(
            id = "cd:$atMs:1", atMs = atMs, face = CardDisposition.Face.CONFIRM, cardId = cardId,
            choice = choice, actor = actor, subject = subject, why = "测试造行", runId = runId,
        )
        CardDisposition.fileIn(root).parentFile?.mkdirs()
        CardDisposition.fileIn(root).appendText(row.toJson().toString() + "\n", Charsets.UTF_8)
    }

    // ── 判据①：5 条已知时刻 ⇒ p50/p90 手算对拍（逐段验） ─────────────────────
    //
    // 五条挂起超时，相隔 25h（窗口内永不凑满 3 条 ⇒ 不触发振荡，与判据③分开）：
    //   发现时延 [1,2,3,4,5] 分 ⇒ p50=3（最近邻秩 ⌈2.5⌉=3 第 3 个）、p90=5（⌈4.5⌉=5）
    //   处置时延 [2,4,6,8,10] 分 ⇒ p50=6、p90=10
    //   恢复时延 [3,6,9,12,15] 分 ⇒ p50=9、p90=15
    @Test
    fun `判据①_五条已知时刻_p50p90手算对拍`() {
        repeat(5) { i ->
            val suspendedAt = base + i * 25L * 60L * min
            val at = suspendedAt + (i + 1) * min
            writeDeviation(
                DeviationEntry.suspendTimeout("run-375", "task-1", at, suspendedAt, "问题$i", stillPending = false),
            )
            val dismissedAt = at + 2 * (i + 1) * min
            writeDisposal(dismissedAt, cardId = "call-$i", subject = "问题$i")
            writeDisposal(
                dismissedAt + 3 * (i + 1) * min, cardId = "restored-$suspendedAt", subject = "问题$i",
                choice = CardDisposition.Choice.APPROVE, actor = CardDisposition.Actor.USER,
            )
        }

        val s = LoopTiming.compute(root)
        assertEquals(5, s.total)
        // 逐段对拍（手算：最近邻秩）
        assertEquals(3L, s.discover.p50Min)
        assertEquals(5L, s.discover.p90Min)
        assertEquals(6L, s.handle.p50Min)
        assertEquals(10L, s.handle.p90Min)
        assertEquals(9L, s.recover.p50Min)
        assertEquals(15L, s.recover.p90Min)
        assertEquals(5, s.discover.samples)
        assertEquals(0, s.discover.missing)
        assertEquals(0, s.handle.missing)
        assertEquals(0, s.recover.missing)
        // 五类分桶：挂起超时一桶 5 条同上，其余四类空桶
        val suspend = s.perKind.first { it.kind == DeviationKind.SUSPEND_TIMEOUT }
        assertEquals(5, suspend.total)
        assertEquals(3L, suspend.discover.p50Min)
        assertEquals(15L, suspend.recover.p90Min)
        assertTrue(s.perKind.filter { it.kind != DeviationKind.SUSPEND_TIMEOUT }.all { it.total == 0 })
        // 相隔 25h ⇒ 无振荡（判据③的活，这里必须是空）
        assertTrue(s.oscillations.isEmpty())
        // 读数行逐字（拍板 2 钉死的格式；N＝本轮，X/Y/Z＝整本账 p50，缺＝三段缺失数）
        assertEquals(
            "偏差 5 条｜发现 3 分 · 处置 6 分 · 恢复 9 分（缺 0/0/0 条）",
            LoopTiming.reportLine(root, "run-375"),
        )
    }

    // ── 判据②：某段缺失 ⇒ 不计入、报「缺 N 条」，不许当 0 ─────────────────────

    @Test
    fun `判据②_缺失段不计入_报缺失条数`() {
        // 超步数：没有发生时刻的事实键、也没有处置键 ⇒ 三段全缺
        writeDeviation(DeviationEntry.overSteps("run-m", "t", base, steps = 12, limit = 10, atStep = 11))
        // 超时：elapsed_ms 推得出发生 ⇒ 发现段计入（10 分）；处置/恢复没键 ⇒ 缺
        writeDeviation(
            DeviationEntry.timeout("run-m", "t", base + 10 * min, elapsedMs = 10 * min, limitMs = 5 * min, tool = "search_web"),
        )
        // 挂起超时：有挂起时刻 ⇒ 发现段计入；处置账里没有认它的行 ⇒ 处置/恢复缺
        writeDeviation(
            DeviationEntry.suspendTimeout("run-m", "t", base + 30 * min, suspendedAtMs = base + 20 * min, question = "没人认的卡", stillPending = false),
        )

        val s = LoopTiming.compute(root)
        assertEquals(3, s.total)
        assertEquals(2, s.discover.samples)
        assertEquals(1, s.discover.missing)
        assertEquals(10L, s.discover.p50Min)
        // 处置/恢复整段缺：样本 0、缺失 3、p50 必须是 null 而不是 0（缺失不许当 0）
        assertEquals(0, s.handle.samples)
        assertEquals(3, s.handle.missing)
        assertNull(s.handle.p50Min)
        assertEquals(0, s.recover.samples)
        assertEquals(3, s.recover.missing)
        assertNull(s.recover.p50Min)
        // 读数行里的缺失位：缺 1/3/3 条
        assertTrue(LoopTiming.reportLine(root, "run-m").endsWith("（缺 1/3/3 条）"))
    }

    @Test
    fun `空账_读数行走惰性形状_破折号不冒充零`() {
        val s = LoopTiming.compute(root)
        assertEquals(0, s.total)
        assertEquals(
            "偏差 0 条｜发现 — 分 · 处置 — 分 · 恢复 — 分（缺 0/0/0 条）",
            LoopTiming.reportLine(root, "run-none"),
        )
    }

    // ── 判据③：振荡（≥3 次／24h ＋ 冻结→解除→再冻结 ⇒ OSCILLATION，只标不处置） ──

    @Test
    fun `判据③_同kind三次内处置后复发_打OSCILLATION`() {
        // 挂起(T0)→收卡(T0+5分,解除)→再挂起(T0+61分)→再挂起(T0+121分)：3 条都在 24h 窗内
        writeDeviation(DeviationEntry.suspendTimeout("run-o", "t", base + min, base, "同一个问题", stillPending = false))
        writeDisposal(base + 5 * min, cardId = "call-a", subject = "同一个问题")
        writeDeviation(DeviationEntry.suspendTimeout("run-o", "t", base + 61 * min, base + 60 * min, "同一个问题", stillPending = false))
        writeDeviation(DeviationEntry.suspendTimeout("run-o", "t", base + 121 * min, base + 120 * min, "同一个问题", stillPending = false))

        val s = LoopTiming.compute(root)
        assertEquals(1, s.oscillations.size)
        val mark = s.oscillations.single()
        assertEquals(DeviationKind.SUSPEND_TIMEOUT, mark.kind)
        assertEquals(3, mark.count)
        assertEquals(base + min, mark.fromMs)
        assertEquals(base + 121 * min, mark.toMs)
    }

    @Test
    fun `判据③_负例_没解除不算振荡`() {
        // 3 条同 kind 都在 24h 窗内，但没有任何处置行（没有"解除"）⇒ 不打标
        repeat(3) { i ->
            writeDeviation(
                DeviationEntry.suspendTimeout("run-o2", "t", base + (60L * i + 1) * min, base + 60L * i * min, "问题$i", stillPending = false),
            )
        }
        assertTrue(LoopTiming.compute(root).oscillations.isEmpty())
    }

    @Test
    fun `判据③_负例_窗口超24h与不足3次都不算`() {
        // 三次相隔 25h（窗口内最多 2 条）＋都处置过 ⇒ 不打标
        repeat(3) { i ->
            val suspendedAt = base + i * 25L * 60L * min
            writeDeviation(
                DeviationEntry.suspendTimeout("run-o3", "t", suspendedAt + min, suspendedAt, "问题$i", stillPending = false),
            )
            writeDisposal(suspendedAt + 5 * min, cardId = "call-$i", subject = "问题$i")
        }
        assertTrue(LoopTiming.compute(root).oscillations.isEmpty())
    }

    // ── 判据④：只读——字节级对拍＋源级零写口（带正例防恒真） ───────────────────

    @Test
    fun `判据④_统计只读_账文件字节不变_不建新文件`() {
        writeDeviation(DeviationEntry.suspendTimeout("run-375", "task-1", base + min, base, "问题", stillPending = false))
        writeDisposal(base + 5 * min, cardId = "call-0", subject = "问题")
        writeDisposal(base + 9 * min, cardId = "restored-$base", subject = "问题",
            choice = CardDisposition.Choice.APPROVE, actor = CardDisposition.Actor.USER)
        val ledger = DeviationLedger.fileIn(root)
        val cards = CardDisposition.fileIn(root)
        val before = listOf(ledger.readBytes(), cards.readBytes())
        val filesBefore = root.listFiles()!!.map { it.name }.sorted()

        LoopTiming.compute(root)
        LoopTiming.reportLine(root, "run-375")

        assertEquals(filesBefore, root.listFiles()!!.map { it.name }.sorted())
        // ByteArray 的 equals 是引用相等 ⇒ 逐文件按内容比（assertArrayEquals）
        val after = listOf(ledger.readBytes(), cards.readBytes())
        org.junit.Assert.assertArrayEquals(before[0], after[0])
        org.junit.Assert.assertArrayEquals(before[1], after[1])
    }

    /** 源级门禁：剥注释后零写口；正例钉住它确实读了两本账（防扫错文件恒真）。 */
    @Test
    fun `判据④_源级_统计器零写口`() {
        val f = File("src/main/java/com/example/zhengdao/baopu/inspect/LoopTiming.kt")
            .let { if (it.exists()) it else File("app/src/main/java/com/example/zhengdao/baopu/inspect/LoopTiming.kt") }
        assertTrue("LoopTiming.kt 没找到：${f.absolutePath}", f.isFile)
        val code = f.readText(Charsets.UTF_8)
            .replace(Regex("""/\*[\s\S]*?\*/"""), " ")
            .replace(Regex("(?m)//.*$"), " ")
        listOf("appendText", "writeText", "writeBytes", "mkdirs", "deleteRecursively", "CardDisposition.record(").forEach { word ->
            assertFalse("统计器里有写口 $word（判据④：只读）", code.contains(word))
        }
        // 正例：确确实实在读两本账——没有这两行，上面的黑名单就是扫了个空文件
        assertTrue(code.contains("DeviationLedger.read("))
        assertTrue(code.contains("CardDisposition.fileIn("))
    }

    // ── 第四百二十九批③：runId join——单号时间线（join 能按 runId 取时间线） ──

    @Test
    fun `四百二十九③_runId join_时间线只收同号行_跨号与旧行不串场`() {
        // run-a：挂起超时一条；处置行＋恢复行同号 ⇒ 三段全对上
        val suspendA = base
        writeDeviation(
            DeviationEntry.suspendTimeout("run-a", "task-a", suspendA + min, suspendA, "甲的事", stillPending = false),
        )
        writeDisposal(suspendA + 5 * min, cardId = "call-a", subject = "甲的事", runId = "run-a")
        writeDisposal(
            suspendA + 9 * min, cardId = "restored-$suspendA", subject = "甲的事",
            choice = CardDisposition.Choice.APPROVE, actor = CardDisposition.Actor.USER, runId = "run-a",
        )
        // run-b：另一单、**同 subject** 的处置行——不同号 ⇒ 不许 join 进 run-a 的时间线
        writeDeviation(
            DeviationEntry.suspendTimeout("run-b", "task-b", base + 60 * min, base + 59 * min, "甲的事", stillPending = false),
        )
        writeDisposal(base + 61 * min, cardId = "call-b", subject = "甲的事", runId = "run-b")

        val timeline = LoopTiming.timelineForRun(root, "run-a")
        assertEquals("run-a 的时间线只有 run-a 的一条", 1, timeline.size)
        val row = timeline.single()
        assertEquals("处置行按同号 join 进来（时刻＝同号处置行那一刻）", suspendA + 5 * min, row.handledAtMs)
        assertEquals("恢复行同理", suspendA + 9 * min, row.recoveredAtMs)

        val s = LoopTiming.computeForRun(root, "run-a")
        assertEquals(1, s.total)
        assertEquals(1, s.discover.samples)
        assertEquals(1, s.handle.samples)
        assertEquals(1, s.recover.samples)
        assertEquals("单号口径照样分桶", 1, s.perKind.first { it.kind == DeviationKind.SUSPEND_TIMEOUT }.total)

        assertEquals("run-b 的时间线不含 run-a 的行", 1, LoopTiming.computeForRun(root, "run-b").total)
        assertEquals("没这单 ⇒ 空时间线（不是拿 0 冒充，是压根没有行）", 0, LoopTiming.computeForRun(root, "run-x").total)
        assertTrue("空白单号 ⇒ 空时间线", LoopTiming.timelineForRun(root, "").isEmpty())
        assertEquals("整本账口径不动：两条偏差都在", 2, LoopTiming.compute(root).total)
    }

    @Test
    fun `四百二十九③_旧行没带runId_单号线里诚实缺失_整本账仍对得上`() {
        // 处置行是 415 前的形状（runId 空串）＝出处不明：单号时间线不许猜它属于哪一单
        writeDeviation(
            DeviationEntry.suspendTimeout("run-c", "t", base + min, base, "丙的事", stillPending = false),
        )
        writeDisposal(base + 6 * min, cardId = "call-c", subject = "丙的事")

        val s = LoopTiming.computeForRun(root, "run-c")
        assertEquals(1, s.total)
        assertEquals("处置行出处不明 ⇒ 单号时间线里缺（宁缺勿猜）", 0, s.handle.samples)
        assertEquals(1, s.handle.missing)
        assertNull(s.handle.p50Min)
        assertEquals("同一本账整本口径照旧对得上（老行为一个字没动）", 1, LoopTiming.compute(root).handle.samples)
    }
}
