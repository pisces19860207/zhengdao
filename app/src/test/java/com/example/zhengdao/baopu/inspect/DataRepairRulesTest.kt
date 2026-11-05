// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第四百零八批 · **五条铁律的用例**（不是"设计说明"，是能红的断言）。
//
// 一条一个用例，红的方式各不相同：
// - 一轮只修一类 ⇒ 传两类必须整单拒；
// - 不许修判定类 ⇒ 判不了就是需人处理（见 Temp 用例），类目里永远没有"谁该弹卡"这一行；
// - 先记后修 ⇒ intent 落不上时盘上必须一字未动；
// - 可回滚 ⇒ 上次只落 intent 的那一趟，不许被第二趟盖过去。
//
// 第四百四十七批（2026-11-05 用户批准）把**类④ 配置漂移**做了进来，这条用例跟着改口：
// 它钉的从来不是"3"这个数，而是**闭集**——清单写死到名字，加第五类必须先过这条闸（就是
// 派工单那句"类④之外不加新类别"的落点）；315 批同一条教训：把条目数当判据，加一项时
// 第一件事是改闸，闸一改就分不清"新增"与"掩盖"。所以这里钉的是**名单**，不是长度。
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
    fun `闭集就是这四路的名字_第五类与判定类都不许进来`() {
        assertEquals(
            "类目名单写死到名字（派工单边界：类④之外不加新类别）。名单一变就要人来对，" +
                "不是把数字改大一寸就放行",
            listOf("index", "ledger", "temp", "config"),
            RepairKind.entries.map { it.wire },
        )
        assertEquals("447 §三批准的一路：配置改回判据表", RepairKind.CONFIG, RepairKind.ofWire("config"))
        listOf("judge", "opinion", "policy", "model").forEach { wire ->
            assertNull("判定类（谁该弹卡、政策怎么裁、模型怎么答）永远不在类目里——铁律②：$wire", RepairKind.ofWire(wire))
        }
    }

    @Test
    fun `三态一个字没改_加路不许顺手多造一个态`() {
        // 派工单通用条款：**不改三态**。三态多一格，"修不了"就有地方被写成"没事"。
        assertEquals(
            "三态名单（可自修／无需修／需人处理）——多一格就是替『修不了』找了个藏身处",
            listOf("fixable", "nothing", "need_human"),
            RepairVerdict.entries.map { it.wire },
        )
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
