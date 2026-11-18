// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百七十八批 · 行槽／账／状态的撤销语义单测（判据④：一键撤销 ⇒ 建议区与状态
// 回到重放前、无残留；账只增不删——撤销也是一行；记忆层零改动）。
// 临时目录全走 TempSandbox（564：建了必删，删不干净必响——607 水位口径）。
package com.example.zhengdao.baopu.subconscious

import com.example.zhengdao.baopu.safety.DiaryCodec
import com.example.zhengdao.baopu.safety.DiaryKind
import com.example.zhengdao.testkit.TempSandbox
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayStoresTest {

    /** 工作区侧的记忆哨兵（「记忆/」里放一个文件当记忆层的字节哨兵）。 */
    private fun plantMemorySentinel(ws: File) {
        val memory = File(ws, "记忆")
        memory.mkdirs()
        File(memory, "项目记忆.md").writeText("记忆层的哨兵内容——撤销不许碰它\n")
    }

    private fun memorySnapshot(ws: File): List<Pair<String, String>> = File(ws, "记忆")
        .walkTopDown()
        .filter { it.isFile }
        .map { it.relativeTo(ws).path to it.readText() }
        .toList()

    private fun row(id: String, pointer: String = "session:1#seq:1") = ReplayRow(
        id = id,
        roundId = "rT",
        kind = ReplayRowKind.EXPERIENCE,
        l0 = L0Pointer(pointer),
        l0Second = null,
        memoryRef = "m1",
        title = "标题",
        subtitle = "经验条目 · 教训 · L0 $pointer",
        body = "正文",
        atMs = 1_000L,
    )

    // ── 行槽：一轮一 publish，撤销回快照 ─────────────────────────────────────

    @Test
    fun 行槽_publish带快照_撤销回重放前() {
        ReplayRowStore.resetForTest()
        ReplayRowStore.publishForRound(listOf(row("replay:m1")))
        assertEquals(1, ReplayRowStore.latest().size)
        // 第二轮 publish 时快照＝第一轮的行（撤销回的是「最近一轮之前」的那份）。
        ReplayRowStore.publishForRound(listOf(row("replay:m2")))
        assertEquals(1, ReplayRowStore.latest().size)
        val restored = ReplayRowStore.undoPublish()
        assertEquals(listOf("replay:m1"), restored.map { it.id })
        assertEquals(restored, ReplayRowStore.latest())
        ReplayRowStore.resetForTest()
    }

    @Test
    fun 行槽_收起只滤行_不动撤销快照() {
        ReplayRowStore.resetForTest()
        ReplayRowStore.publishForRound(listOf(row("replay:m1"), row("replay:m2")))
        ReplayRowStore.dismiss("replay:m1")
        assertEquals(listOf("replay:m2"), ReplayRowStore.latest().map { it.id })
        // 撤销仍是整轮语义：回到本轮之前（空）。
        assertTrue(ReplayRowStore.undoPublish().isEmpty())
        ReplayRowStore.resetForTest()
    }

    // ── 状态：天闸桶可回滚 ───────────────────────────────────────────────────

    @Test
    fun 状态_没跑过读作NEVER() {
        TempSandbox.use("zd678-state") { ws ->
            assertEquals(ReplayDayGate.NEVER, ReplayDayGate.lastRunDayKey(ws))
        }
    }

    @Test
    fun 状态_真跑后写桶_撤销回prev() {
        TempSandbox.use("zd678-state-undo") { ws ->
            ReplayDayGate.writeAfterRound(ws, dayKey = 20_700, prev = ReplayDayGate.NEVER, nowMs = 5L, roundId = "rT1")
            assertEquals(20_700L, ReplayDayGate.lastRunDayKey(ws))
            assertTrue(ReplayDayGate.restoreToPrev(ws, nowMs = 9L, roundId = "u1"))
            assertEquals(ReplayDayGate.NEVER, ReplayDayGate.lastRunDayKey(ws))
            // 天闸读回「没跑过」＝ 重放前的语义：同样的轮再跑一遍天闸放行。
        }
    }

    @Test
    fun 状态_连环撤销不越换越旧() {
        TempSandbox.use("zd678-state-chain") { ws ->
            ReplayDayGate.writeAfterRound(ws, dayKey = 20_700, prev = 20_699, nowMs = 5L, roundId = "rT1")
            assertTrue(ReplayDayGate.restoreToPrev(ws, 6L, "u1"))
            assertEquals(20_699L, ReplayDayGate.lastRunDayKey(ws))
            // 第二次撤销：prev 已被清成 NEVER ⇒ 桶回到 NEVER，不会把更旧的桶翻出来。
            assertTrue(ReplayDayGate.restoreToPrev(ws, 7L, "u2"))
            assertEquals(ReplayDayGate.NEVER, ReplayDayGate.lastRunDayKey(ws))
        }
    }

    @Test
    fun 状态_坏文件按没跑过处理() {
        TempSandbox.use("zd678-state-bad") { ws ->
            val f = ReplayDayGate.fileOf(ws)
            f.parentFile?.mkdirs()
            f.writeText("不是 JSON 的坏内容", Charsets.UTF_8)
            assertEquals(ReplayDayGate.NEVER, ReplayDayGate.lastRunDayKey(ws))
        }
    }

    // ── 判据④：撤销无残留（账只增一行撤销；记忆层字节不动） ─────────────────

    @Test
    fun 判据4_撤销后记忆层字节不动() {
        TempSandbox.use("zd678-undo-memory") { ws ->
            plantMemorySentinel(ws)
            val before = memorySnapshot(ws)
            ReplayDayGate.writeAfterRound(ws, 20_700, ReplayDayGate.NEVER, 1L, "rT")
            ReplayLedger.append(
                ReplayLedger.fileOf(ws),
                ReplayLedger.floatedEvent(1L, "replay:m1", "session:1#seq:1", "标题"),
            )
            ReplayDayGate.restoreToPrev(ws, 2L, "uT")
            ReplayLedger.append(
                ReplayLedger.fileOf(ws),
                ReplayLedger.undoneEvent(2L, "uT", 1),
            )
            assertEquals(before, memorySnapshot(ws))
        }
    }

    @Test
    fun 判据4_撤销在账面留一行_不擦历史() {
        TempSandbox.use("zd678-undo-ledger") { ws ->
            val ledger = ReplayLedger.fileOf(ws)
            ReplayLedger.append(ledger, ReplayLedger.floatedEvent(1L, "replay:m1", "session:1#seq:1", "标题"))
            ReplayLedger.append(ledger, ReplayLedger.undoneEvent(2L, "uT", 1))
            val rows = ReplayLedger.read(ledger)
            assertEquals(2, rows.size)
            assertTrue(rows[0].why.contains("·浮出·replay:m1"))
            assertTrue(rows[1].why.contains("·撤销·uT"))
            assertEquals(DiaryKind.ACTION, rows[1].kind)
            assertTrue(rows[1].result.contains("记忆层零改动"))
        }
    }

    @Test
    fun 判据4_浮出N条_账N行加撤销一行() {
        TempSandbox.use("zd678-ledger-count") { ws ->
            val ledger = ReplayLedger.fileOf(ws)
            (1..4).forEach { i ->
                ReplayLedger.append(
                    ledger,
                    ReplayLedger.floatedEvent(1L, "replay:m$i", "session:1#seq:$i", "标题$i"),
                )
            }
            assertEquals(4, ReplayLedger.read(ledger).size)
        }
    }

    @Test
    fun 账_读回只认自己的前缀_坏行跳过() {
        TempSandbox.use("zd678-ledger-bad") { ws ->
            val ledger = ReplayLedger.fileOf(ws)
            ledger.parentFile?.mkdirs()
            val good = ReplayLedger.floatedEvent(1L, "replay:m1", "session:1#seq:1", "标题")
            ledger.writeText("坏行不是 JSON\n" + DiaryCodec.encode(good) + "\n", Charsets.UTF_8)
            val rows = ReplayLedger.read(ledger)
            assertEquals(1, rows.size)
            assertFalse(rows.isEmpty())
        }
    }
}
