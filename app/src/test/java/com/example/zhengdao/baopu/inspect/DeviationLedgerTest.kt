// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第三百六十三批 · 偏差账的门禁：四条硬规矩逐条钉（判据①②③④＋不判决）。
//
// 对应派工单 §三：
//   ① 五类各有测试（造一次超步 ⇒ 一条账行）→ `五类各造一次_五条账行原样读回`；
//   ② 记完不影响主流程 → `落账失败不抛_返回false_后续照常`；
//   ③ 只追加（同一 runId 两次偏差 ⇒ 两行）→ `同一runId两次偏差_两行`;
//   ④ 收工报告能读到"偏差 N 条" → `收工读数_同一run只数自己的`；
//   硬规矩 4（不判决）→ `自产文本零判决词`。
package com.example.zhengdao.baopu.inspect

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviationLedgerTest {

    private val root = File("build/tmp/deviation363-ledger").apply {
        deleteRecursively()
        mkdirs()
    }

    // ── 判据①：五类各造一次 ⇒ 五条账行，往返原样 ──────────────────────────

    @Test
    fun `五类各造一次_五条账行原样读回`() {
        assertTrue(DeviationLedger.write(root, DeviationEntry.overSteps("run-a", "task-1", 1_000L, steps = 12, limit = 10, atStep = 11)))
        assertTrue(DeviationLedger.write(root, DeviationEntry.timeout("run-a", "task-1", 2_000L, elapsedMs = 65_000L, limitMs = 60_000L, tool = "search_web")))
        assertTrue(DeviationLedger.write(root, DeviationEntry.interruptOver("run-a", "task-1", 3_000L, count = 4, trigger = "真失败打断")))
        assertTrue(DeviationLedger.write(root, DeviationEntry.bypassConfirm("run-a", "task-1", 4_000L, action = "delete_file:/tmp/a", authority = "免打扰授权 quiet-9", reversible = false)))
        assertTrue(DeviationLedger.write(root, DeviationEntry.suspendTimeout("run-a", "task-1", 5_000L, suspendedAtMs = 900_000L, question = "要动哪个目录", stillPending = true)))

        val back = DeviationLedger.read(root)
        assertEquals(5, back.size)
        // read 是新→旧：后写的排前（与体检账同口径）
        assertEquals(
            listOf(
                DeviationKind.SUSPEND_TIMEOUT, DeviationKind.BYPASS_CONFIRM, DeviationKind.INTERRUPT_OVER,
                DeviationKind.TIMEOUT, DeviationKind.OVER_STEPS,
            ),
            back.map { it.kind },
        )
        assertEquals("12", back[4].facts[DeviationEntry.F_STEPS])
        assertEquals("10", back[4].facts[DeviationEntry.F_LIMIT])
        assertEquals("11", back[4].facts[DeviationEntry.F_AT_STEP])
        assertEquals("65000", back[3].facts[DeviationEntry.F_ELAPSED_MS])
        assertEquals("search_web", back[3].facts[DeviationEntry.F_TOOL])
        assertEquals("4", back[2].facts[DeviationEntry.F_COUNT])
        assertEquals("否", back[1].facts[DeviationEntry.F_REVERSIBLE])
        assertEquals("是", back[0].facts[DeviationEntry.F_PENDING])
        // 出处逐条都在（硬规矩 3）
        assertTrue(back.all { it.runId == "run-a" && it.taskId == "task-1" })
    }

    @Test
    fun `造一次超步_一条账行_id按拼法`() {
        assertTrue(DeviationLedger.write(root, DeviationEntry.overSteps("run-x", "", 7_000L, steps = 17, limit = 16, atStep = 17)))
        val back = DeviationLedger.read(root)
        assertEquals(1, back.size)
        assertEquals("dev:run-x#7000", back[0].id)
        assertEquals(7_000L, back[0].atMs)
    }

    // ── 硬规矩 3：出处构造期拦空 ─────────────────────────────────────────

    @Test(expected = IllegalArgumentException::class)
    fun `runId空_构造期拦住`() {
        DeviationEntry.overSteps(runId = "  ", taskId = "t", atMs = 1L, steps = 2, limit = 1, atStep = 2)
    }

    // ── 判据③：只追加，同一 runId 两次偏差 ⇒ 两行 ─────────────────────────

    @Test
    fun `同一runId两次偏差_两行`() {
        assertTrue(DeviationLedger.write(root, DeviationEntry.overSteps("run-2", "t", 1_000L, steps = 9, limit = 8, atStep = 9)))
        assertTrue(DeviationLedger.write(root, DeviationEntry.timeout("run-2", "t", 2_000L, elapsedMs = 61_000L, limitMs = 60_000L)))
        assertEquals(2, DeviationLedger.countForRun(root, "run-2"))
        // 盘上也是两行：追加不覆盖（第一行还在）
        val raw = DeviationLedger.fileIn(root).readLines(Charsets.UTF_8).filter { it.isNotBlank() }
        assertEquals(2, raw.size)
        // 读侧新→旧，两条都在
        assertEquals(listOf(2_000L, 1_000L), DeviationLedger.read(root).map { it.atMs })
    }

    // ── 判据②：记完不影响主流程（写失败不抛，也不带坏后续） ────────────────

    @Test
    fun `落账失败不抛_返回false_后续照常`() {
        // 根路径是一个**文件**：mkdirs 必败 ⇒ write 返 false，不许抛
        val blocker = File("build/tmp/deviation363-blocker").apply {
            deleteRecursively()
            writeText("not a dir")
        }
        val badRoot = File(blocker, "child")
        assertFalse(DeviationLedger.write(badRoot, DeviationEntry.overSteps("run-3", "", 1L, steps = 2, limit = 1, atStep = 2)))
        // 主流程不带坏：同一个好根接着记，照常落上
        assertTrue(DeviationLedger.write(root, DeviationEntry.overSteps("run-4", "", 2L, steps = 3, limit = 2, atStep = 3)))
        assertEquals(1, DeviationLedger.countForRun(root, "run-4"))
        blocker.deleteRecursively()
    }

    @Test
    fun `读空根与坏行_空表不抛`() {
        val empty = File("build/tmp/deviation363-empty").apply {
            deleteRecursively()
            mkdirs()
        }
        assertTrue(DeviationLedger.read(empty).isEmpty())
        assertEquals(0, DeviationLedger.countForRun(empty, "run-5"))
        // 坏行不当账行
        DeviationLedger.fileIn(empty).writeText("不是json\n{\"kind\":\"自造类\"}\n", Charsets.UTF_8)
        assertTrue(DeviationLedger.read(empty).isEmpty())
        assertNull(DeviationKind.ofWire("自造类"))
    }

    // ── 判据④：收工报告读数「偏差 N 条」 ─────────────────────────────────

    @Test
    fun `收工读数_同一run只数自己的`() {
        repeat(3) { i ->
            assertTrue(DeviationLedger.write(root, DeviationEntry.overSteps("run-6", "t", i * 1_000L, steps = 5, limit = 4, atStep = 5)))
        }
        assertTrue(DeviationLedger.write(root, DeviationEntry.timeout("run-7", "t", 9_000L, elapsedMs = 61_000L, limitMs = 60_000L)))
        assertEquals(3, DeviationLedger.countForRun(root, "run-6"))
        assertEquals(1, DeviationLedger.countForRun(root, "run-7"))
        assertEquals("偏差 3 条", DeviationLedger.reportLine(root, "run-6"))
        assertEquals("偏差 0 条", DeviationLedger.reportLine(root, "run-没有"))
        assertEquals(0, DeviationLedger.countForRun(root, ""))
    }

    // ── 硬规矩 4：不判决（自产文本零判决词） ─────────────────────────────

    @Test
    fun `自产文本零判决词`() {
        val entries = listOf(
            DeviationEntry.overSteps("r", "t", 1L, steps = 12, limit = 10, atStep = 11),
            DeviationEntry.timeout("r", "t", 2L, elapsedMs = 65_000L, limitMs = 60_000L, tool = "search_web"),
            DeviationEntry.interruptOver("r", "t", 3L, count = 4, trigger = "真失败打断"),
            DeviationEntry.bypassConfirm("r", "t", 4L, action = "delete_file:/tmp/a", authority = "免打扰授权", reversible = false),
            DeviationEntry.suspendTimeout("r", "t", 5L, suspendedAtMs = 900_000L, question = "要动哪个目录", stillPending = true),
        )
        val selfProduced = buildList {
            DeviationKind.entries.forEach { add(it.wire); add(it.cn) }
            entries.forEach { add(it.describe()); add(it.toJson().toString()) }
            add(DeviationLedger.reportLine(root, "r"))
            DeviationLedger.FACT_LABELS.values.forEach { add(it) }
        }
        DeviationLedger.JUDGMENT_MARKERS.forEach { marker ->
            assertTrue(
                "自产文本不许出现判决词「$marker」",
                selfProduced.none { it.contains(marker) },
            )
        }
        // 事实句是"读数"不是"评语"：超步那句带着数
        assertEquals("超步数：实到步数 12；上限 10；越过的那一步 11", entries[0].describe())
    }
}
