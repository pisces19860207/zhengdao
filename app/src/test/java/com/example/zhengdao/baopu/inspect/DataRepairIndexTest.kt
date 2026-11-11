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
import org.junit.After
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

    // ── 447 §一①：不一致的对象是**索引↔清单这一对** ─────────────────────────
    //
    // 408 只认"索引自己读不动"。447 把判据扩到这一对，依据是源码里唯一的那处写口
    // （`KnowledgeIndexStore.save`：索引写成才写快照 ⇒ 这一对天然成对，缺一条就是半截）。
    // 依旧**不比篇数**——最后那条用例就是钉"没把 408 拒掉的假阳捡回来"。

    /** 先修出一份健康的成对文件（走既有接口，不手搓 JSON）。 */
    private fun seedPair() {
        KnowledgeLayout.ensureDirs(ws)
        val built = KnowledgeIndexStore.build(ws, filesDir, null, null, now = now)
        assertTrue("成对落盘：${built.report}", KnowledgeIndexStore.save(ws, requireNotNull(built.index), built.snapshot))
    }

    @Test
    fun `清单读坏了_索引读得动_也算不一致`() {
        seedPair()
        assertEquals("起手该是健康的成对", RepairVerdict.NOTHING, DataDoctor.diagnoseIndex(ws, now).verdict)

        KnowledgeLayout.snapshotFile(ws).writeText("{ not json", Charsets.UTF_8)
        val found = DataDoctor.diagnoseIndex(ws, now)
        assertEquals(RepairVerdict.FIXABLE, found.verdict)
        assertTrue("要说清坏的是清单：${found.reason}", found.reason.startsWith("清单读坏了"))
        assertTrue("两条目标都要进账：${found.targets}",
            found.targets.contains("知识库/索引/知识库索引.json") && found.targets.contains("知识库/索引/快照.json"))

        assertTrue(DataDoctor.repairIndex(ws, filesDir, now).done)
        assertNotNull("清单回来了", KnowledgeIndexStore.loadSnapshotRead(ws).valueOrNull())
        assertEquals(RepairVerdict.NOTHING, DataDoctor.diagnoseIndex(ws, now).verdict)
    }

    @Test
    fun `成对文件只剩一条_说得出只剩哪条`() {
        seedPair()
        assertTrue(KnowledgeLayout.snapshotFile(ws).delete())

        val found = DataDoctor.diagnoseIndex(ws, now)
        assertEquals(RepairVerdict.FIXABLE, found.verdict)
        assertTrue("要说「只剩一条」：${found.reason}", found.reason.contains("成对文件只剩一条"))
        assertTrue("要点名缺的那条：${found.reason}", found.reason.contains("快照.json 不在"))

        assertTrue(DataDoctor.repairIndex(ws, filesDir, now).done)
        assertTrue("两条都该在", KnowledgeLayout.indexFile(ws).isFile && KnowledgeLayout.snapshotFile(ws).isFile)
        assertEquals(RepairVerdict.NOTHING, DataDoctor.diagnoseIndex(ws, now).verdict)
    }

    @Test
    fun `两条都没建过是「从没建过」不是不一致`() {
        val fresh = File("build/tmp/repair447-index-pair-fresh").apply {
            deleteRecursively()
            mkdirs()
        }
        val plan = DataDoctor.diagnoseIndex(fresh, now)
        assertEquals(RepairVerdict.NOTHING, plan.verdict)
        assertTrue("要说的是这一对，不是单独一条：${plan.reason}", plan.reason.contains("索引与清单都还没建过"))
    }

    @Test
    fun `不比篇数_408 拒掉的假阳一个都没捡回来`() {
        // 索引里说 0 篇（空工作区），盘上也确实没有文档 ⇒ 这条一路是"读得动"，不是"对不上"。
        // 判据层压根没有拿 filesIndexed／entries.size 比大小的口：`indexPairPlan` 的
        // 两个 Ok 分支只把篇数写进措辞，不改结论——所以"盘上多一个文件就触发重建"这种假阳
        // 在结构上就长不出来（用例把结论钉死，措辞随它变）。
        seedPair()
        val docs = File(ws, "知识库/整理/新文档.md").apply {
            parentFile?.mkdirs()
            writeText("# 新文档\n内容\n", Charsets.UTF_8)
        }
        val plan = DataDoctor.diagnoseIndex(ws, now)
        assertEquals(
            "刚多了一个文件、索引还没更新 ⇒ 不许判成不一致（那是 408 §二拒过的假阳）",
            RepairVerdict.NOTHING,
            plan.verdict,
        )
        assertTrue(docs.delete())
    }

    // ── 447 §一①「只调既有接口（grep 为证）」的源码门禁 ────────────────────

    @Test
    fun `索引这一路没有新写一套逻辑_只调既有读写口`() {
        val f = File("src/main/java/com/example/zhengdao/baopu/repair/DataDoctor.kt")
        assertTrue("找不到执行层：$f", f.isFile)
        val text = f.readText(Charsets.UTF_8)
            .replace(Regex("""/\*[\s\S]*?\*/"""), " ")
            .replace(Regex("(?m)//.*$"), " ")

        // 既有接口四件套：两条读口＋一条建＋一条成对写口
        listOf(
            "KnowledgeIndexStore.loadRead(",
            "KnowledgeIndexStore.loadSnapshotRead(",
            "KnowledgeIndexStore.build(",
            "KnowledgeIndexStore.save(",
        ).forEach { word ->
            assertTrue("既有索引接口没被调用（$word）⇒ 这一路是自己另写了一套", text.contains(word))
        }
        // 这些一律不许出现在执行层：出现了就是"新写了一套索引"
        listOf(
            "KbIndexer", "KbIndexCodec", "KnowledgeSnapshot.encode", "KnowledgeSnapshot.capture",
            "LibraryPaths.atomicWrite", "writeText",
        ).forEach { word ->
            assertFalse("执行层出现了 $word（索引的逻辑只许住在 knowledge/ 那层）", text.contains(word))
        }
    }

    /** cleanup-b571（2026-11-11）：本文件在 `build/tmp/` 下建的目录跑完必须删——
     *  它们是测试暂存区（564 的水位闸按这些目录计）；此前只建不删，水位只能一路涨。 */
    @After
    fun cleanTmpDirs571() {
        java.io.File("build/tmp/repair408-index").deleteRecursively()
        java.io.File("build/tmp/repair408-index-files").deleteRecursively()
        java.io.File("build/tmp/repair408-index-fresh").deleteRecursively()
        java.io.File("build/tmp/repair447-index-pair-fresh").deleteRecursively()
    }

}
