// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第四百零八批 · 类① 索引重建的**四段用例**：造问题 ⇒ 体检发现 ⇒ 自修 ⇒ 再体检变好。
//
// 造问题用的是最硬的一种"不一致"：索引文件写坏（`{ not json`）。选它不选"清单少一行"，
// 因为 `filesIndexed` 与盘上文件数天然不等（过大/读不出/不认领的会被跳过），
// 拿它当判据必假阳——假阳会触发不必要的重建，那是数据churn，不是修复。
package com.example.zhengdao.baopu.inspect

import com.example.zhengdao.baopu.knowledge.KnowledgeIndexStore
import com.example.zhengdao.baopu.knowledge.KnowledgeLayout
import com.example.zhengdao.baopu.repair.DataDoctor
import com.example.zhengdao.baopu.repair.RepairLedger
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DataRepairIndexTest {

    private val ws = File("build/tmp/repair408-index").apply {
        deleteRecursively()
        mkdirs()
    }

    private val filesDir = File("build/tmp/repair408-index-files").apply {
        deleteRecursively()
        mkdirs()
    }

    private val now = 1_790_000_000_000L

    /** 造问题：把索引写成解不开的东西（真机上对应"索引被写坏"）。 */
    private fun corruptIndex() {
        KnowledgeLayout.ensureDirs(ws)
        KnowledgeLayout.indexFile(ws).writeText("{ not json", Charsets.UTF_8)
    }

    @Test
    fun `索引读坏了_体检发现_自修重建_再体检变好`() {
        corruptIndex()

        // ① 体检发现：说得出环节、带对账线索
        val found = DataDoctor.diagnoseIndex(ws, now)
        assertEquals(RepairKind.INDEX, found.kind)
        assertEquals(RepairVerdict.FIXABLE, found.verdict)
        assertTrue("要说得出环节：${found.reason}", found.reason.contains("解不开"))
        assertTrue("要带对账线索（sha）：${found.reason}", found.reason.contains("sha="))
        assertTrue("依据要写清：${found.evidence}", found.evidence.startsWith("diagnose:"))

        // ② 自修
        val out = DataDoctor.repairIndex(ws, filesDir, now)
        assertTrue("该修成：${out.note}", out.done)
        assertNotNull("索引要读得动了", KnowledgeIndexStore.loadRead(ws).valueOrNull())

        // ③ 再体检变好
        val again = DataDoctor.diagnoseIndex(ws, now)
        assertEquals(RepairVerdict.NOTHING, again.verdict)
        assertTrue("要说「读得动」而不是「修好了」：${again.reason}", again.reason.contains("读得动"))
    }

    @Test
    fun `修完有两行账_intent在前result在后_写清动了什么`() {
        corruptIndex()
        assertTrue(DataDoctor.repairIndex(ws, filesDir, now).done)

        val rows = RepairLedger.read(ws)
        assertEquals("一次修复两行（先记后修）", 2, rows.size)
        assertEquals(RepairLedger.PHASE_RESULT, rows[0].phase)
        assertEquals(RepairLedger.PHASE_INTENT, rows[1].phase)
        assertEquals(RepairKind.INDEX.wire, rows[0].kind)
        assertEquals(RepairLedger.OUTCOME_DONE, rows[0].outcome)
        assertTrue("账行要写清动了什么：${rows[0].target}", rows[0].target.contains("知识库索引.json"))
        assertTrue("账行要写清凭什么：${rows[0].reason}", rows[0].reason.contains("解不开"))
    }

    @Test
    fun `没有索引不是坏了_不修也不记账`() {
        val fresh = File("build/tmp/repair408-index-fresh").apply {
            deleteRecursively()
            mkdirs()
        }
        val plan = DataDoctor.diagnoseIndex(fresh, now)
        assertEquals("从没建过不是缺陷", RepairVerdict.NOTHING, plan.verdict)

        val out = DataDoctor.repairIndex(fresh, filesDir, now)
        assertFalse("无需修就不许动手", out.done)
        assertFalse("无需修也不许留账（留了就像修过）", RepairLedger.fileIn(fresh).exists())
    }

    @Test
    fun `索引可整棵删掉再重建_回滚路径真在`() {
        corruptIndex()
        assertTrue(DataDoctor.repairIndex(ws, filesDir, now).done)

        // 回滚＝删掉索引（它是缓存），再走既有接口重建
        assertTrue(KnowledgeLayout.indexFile(ws).delete())
        val built = KnowledgeIndexStore.build(
            workspace = ws,
            filesDir = filesDir,
            previousIndex = null,
            previousSnapshot = null,
            now = now,
        )
        val index = requireNotNull(built.index) { "删了索引仍该能重建（这是可回滚的前提）" }
        assertTrue(KnowledgeIndexStore.save(ws, index, built.snapshot))
        assertNotNull("重建后仍读得动", KnowledgeIndexStore.loadRead(ws).valueOrNull())
    }
}
