// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第四百零八批 · **五条铁律的用例**（不是"设计说明"，是能红的断言）。
//
// 一条一个用例，红的方式各不相同：
// - 一轮只修一类 ⇒ 传两类必须整单拒；
// - 不许修判定类 ⇒ 类④ 不在闭集里；判不了就是需人处理（见 Temp 用例）；
// - 先记后修 ⇒ intent 落不上时盘上必须一字未动；
// - 可回滚 ⇒ 上次只落 intent 的那一趟，不许被第二趟盖过去。
package com.example.zhengdao.baopu.inspect

import com.example.zhengdao.baopu.knowledge.KnowledgeLayout
import com.example.zhengdao.baopu.repair.DataDoctor
import com.example.zhengdao.baopu.repair.RepairEntry
import com.example.zhengdao.baopu.repair.RepairLedger
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DataRepairRulesTest {

    private val now = 1_790_000_000_000L

    @Test
    fun `一轮只修一类_传两类整单拒`() {
        assertNull("单类放行", DataRepair.batchGuard(listOf(RepairKind.INDEX)))
        val two = DataRepair.batchGuard(listOf(RepairKind.INDEX, RepairKind.LEDGER))
        assertNotNull("两类必须拒", two)
        assertTrue("要说出为什么：$two", two!!.contains("一轮只修一类"))
        assertNotNull("空单不是「全都不修」", DataRepair.batchGuard(emptyList()))
    }

    @Test
    fun `类四配置漂移是裁定的先不做_闭集里不许占位`() {
        assertEquals("本批只做三类", 3, RepairKind.entries.size)
        assertEquals(listOf("index", "ledger", "temp"), RepairKind.entries.map { it.wire })
        assertNull("类④ 用户 2026-11-03 裁定先不做，不许偷偷留个空位", RepairKind.ofWire("config"))
    }

    @Test
    fun `先记后修_intent落不上就不许动手`() {
        val ws = File("build/tmp/repair408-rules-intent").absoluteFile.apply {
            deleteRecursively()
            mkdirs()
        }
        KnowledgeLayout.ensureDirs(ws)
        KnowledgeLayout.indexFile(ws).writeText("{ not json", Charsets.UTF_8)

        // 把修复账的落点做成"不可能写成"：父级是一个普通文件，mkdirs/append 都失败
        val blocker = File(ws, "不是目录").apply { writeText("x", Charsets.UTF_8) }
        val badLedgerRoot = File(blocker, "子目录")

        val out = DataDoctor.repairIndex(ws, ws, now, ledgerRoot = badLedgerRoot)

        assertFalse("账落不上就不许动手", out.done)
        assertEquals(DataDoctor.INTENT_NOT_WRITTEN, out.note)
        assertEquals(
            "盘上必须一字未动",
            "{ not json",
            KnowledgeLayout.indexFile(ws).readText(Charsets.UTF_8),
        )
    }

    @Test
    fun `上次只落了intent_不许被第二趟盖过去`() {
        val root = File("build/tmp/repair408-rules-half").absoluteFile.apply {
            deleteRecursively()
            mkdirs()
        }
        val ledgerName = "体检记录.jsonl"
        File(root, ledgerName).writeText("{坏行\n", Charsets.UTF_8)

        // 手造"进程被杀"：只落 intent，没有 result
        assertTrue(
            RepairLedger.write(
                root,
                RepairEntry(
                    id = "repair:ledger:1",
                    atMs = 1L,
                    kind = RepairKind.LEDGER.wire,
                    phase = RepairLedger.PHASE_INTENT,
                    target = ledgerName,
                    reason = "手造半截",
                    evidence = "diagnose:1:ledger",
                ),
            ),
        )
        assertNotNull(RepairLedger.unfinishedIntent(root))

        val out = DataDoctor.repairLedger(root, ledgerName, now)

        assertFalse("修到一半不许再修", out.done)
        assertTrue("要说清卡在哪：${out.note}", out.note.contains("没落 result"))
        assertEquals("不许再写一行", 1, RepairLedger.read(root).size)
    }

    @Test
    fun `无需修与需人处理都不写账_只有真动手才写`() {
        val root = File("build/tmp/repair408-rules-quiet").absoluteFile.apply {
            deleteRecursively()
            mkdirs()
        }
        // 无需修：账本不存在
        assertFalse(DataDoctor.repairLedger(root, "没这本账.jsonl", now).done)
        assertFalse(RepairLedger.fileIn(root).exists())

        // 需人处理：没有前缀判据
        assertFalse(DataDoctor.repairTemp(root, emptySet(), now).done)
        assertFalse("「判不了」也不许留账", RepairLedger.fileIn(root).exists())
    }
}
