// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第四百三十一批的行为门禁：影子按需（执行前基线 → 副本重做 → 执行后对表）四条判据
// ＋②「复核排到下一轮（不在收尾重做）」。
//
// ## 判据与用例的对位
//   · **执行前必采到**：rehearse 走一遍（＝授权闸第⑤步的调用形状），基线行必须落、
//     采集时刻必须落在本次调用窗口内（预演在闸里、真执行在闸后 ⇒ 结构上早于执行）；
//   · **零差异不报**：真盘与预测后态一致 ⇒ `影子按需.jsonl` 一个字节都没有（连"一致"
//     的行都不写），复核队列也不留行；
//   · **有差异记一条**：真盘与预测对不上 ⇒ 恰好一条 DISAGREEMENT 行（差异逐格列在行内）；
//     同一动作第二遍执行（引擎重试同 opId）不再刷第二条；
//   · **影子不写主账**：整个流程过后，账本目录里只许出现影子的四本自有文件
//     （影子执行/影子基线/影子按需/影子复核），其余一律没有；
//   · **②验不了 ⇒ 排到下一轮**：真盘读不动 ⇒ 首验入队、按需账不落行；下一轮（drainDue）
//     恢复了 ⇒ 静默收口（依旧零差异不报）；仍读不动 ⇒ 两试到顶收口，按需账记一条
//     unverifiable（不冒充清白），再 drain 不重试。
//
// 复核的"下一轮"在单测里就是手动调 [ShadowOnDemand.drainDue]——生产里它只被
// RunEngine 开轮点（`resetRunScopedCounters`）调；settle/收尾闸没有这个调用（源码可查）。
package com.example.zhengdao.baopu.inspect

import com.example.zhengdao.baopu.DecisionLedger
import com.example.zhengdao.baopu.RiskClass
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ShadowOnDemandTest {

    private lateinit var ledger: File
    private lateinit var ws: File
    private val logs = mutableListOf<String>()

    @Before
    fun setUp() {
        ledger = Files.createTempDirectory("zd431-ledger-").toFile()
        ws = Files.createTempDirectory("zd431-ws-").toFile()
        DecisionLedger.install(ledger)
    }

    @After
    fun tearDown() {
        DecisionLedger.uninstallForTest()
        ledger.deleteRecursively()
        ws.deleteRecursively()
    }

    // ── 现场：真盘文件 ＋ Fake 沙盒（副本里落"重做后的产物"） ────────────────────

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun plantReal(content: String?) {
        File(ws, "notes").mkdirs()
        val f = File(ws, "notes/a.md")
        if (content == null) f.delete() else f.writeText(content, Charsets.UTF_8)
    }

    /** 副本重做后的落点（预测后态从这份字节读出来）。 */
    private fun plantCopy(content: String): String {
        val copy = File(ws, ".shadow/op-431").apply { mkdirs() }
        File(copy, "notes").mkdirs()
        val f = File(copy, "notes/a.md")
        f.writeText(content, Charsets.UTF_8)
        return f.absolutePath
    }

    private class Fake(private val run: ShadowRun) : ShadowSandbox {
        var enteredAt = 0L
        override fun rehearse(input: ShadowInput): ShadowRun {
            enteredAt = System.currentTimeMillis()
            return run
        }

        override fun discard(copyRoot: String) = Unit
    }

    /** 一份"重做成功"的实测：副本里先落好产物（预测后态从这份字节读出来）。 */
    private fun consistentRun(): ShadowRun {
        val landed = plantCopy("重做后的内容\n")
        return ShadowRun(
            built = true,
            note = "副本 1 个对象",
            copyRoot = File(ws, ".shadow/op-431").absolutePath,
            existedBefore = listOf("notes/a.md"),
            observedPaths = listOf(landed),
            observed = ImpactShape(overwriteTotal = 1, touchedPaths = listOf(landed)),
            rollback = RollbackPoint(
                path = File(ws, ".shadow/op-431/.rollback/a.bak").absolutePath,
                exists = true,
                bytes = 13L,
                originalBytes = 13L,
                sha256 = "aaaaaaaa11111111",
                originalSha256 = "aaaaaaaa11111111",
            ),
            realUntouched = true,
            landed = true,
            trialOk = true,
            trialNote = "已覆盖 a.md",
        )
    }

    private fun input(opId: String = "op-431", reversible: Boolean = false) = ShadowInput(
        opId = opId,
        tool = "write_file",
        workspaceRoot = ws.absolutePath,
        declaredPaths = listOf("notes/a.md"),
        content = "重做后的内容\n",
        overwrite = true,
        risk = RiskClass.R2,
        reversible = reversible,
        sessionId = 9L,
        step = 2,
        wallMs = System.currentTimeMillis(),
        ledgerRoot = ledger.absolutePath,
    )

    /** 走一遍预演（＝授权闸第⑤步的调用形状），交回沙盒进门时刻。 */
    private fun rehearseOnce(opId: String = "op-431", reversible: Boolean = false): Long {
        val fake = Fake(consistentRun())
        val r = ShadowAdapter.rehearse(input(opId, reversible), override = fake, log = logs::add)
        assertEquals("现场要跑成一致，别让预演的岔路搅了按需的场", ShadowVerdict.CONSISTENT, r.outcome.verdict)
        return fake.enteredAt
    }

    private fun onDemandRows(): List<ShadowOnDemandRow> {
        val f = File(ledger, ShadowOnDemand.FILE_NAME)
        if (!f.isFile) return emptyList()
        return f.readLines(Charsets.UTF_8).mapNotNull { line ->
            val o = org.json.JSONObject(line)
            ShadowOnDemandRow(
                id = o.optString("id"),
                atMs = o.optLong("at"),
                opId = o.optString("op"),
                sessionId = o.optLong("session"),
                roundId = o.optInt("round"),
                step = o.optInt("step"),
                tool = o.optString("tool"),
                outcome = o.optString("outcome"),
                baselineId = o.optString("baseline"),
                diffs = emptyList(),
                reason = o.optString("reason"),
                attempts = o.optInt("attempts"),
            )
        }
    }

    private fun baselineRow(opId: String = "op-431"): ShadowBaselineRow =
        ShadowBaseline.latestFor(ledger, opId) ?: error("基线行应该在（执行前必采到）")

    // ── 判据「执行前必采到」 ────────────────────────────────────────────────────

    @Test
    fun `判据1_执行前必采到_基线四格落行_采集时刻在预演进门`() {
        plantReal("执行前的旧内容\n")
        val before = System.currentTimeMillis()
        val entered = rehearseOnce()
        val after = System.currentTimeMillis()

        val row = baselineRow()
        assertEquals("影子基线.jsonl 的行号按 opId 一把", "bl:op-431", row.id)
        val print = row.baseline.single()
        assertEquals("notes/a.md", print.path)
        assertTrue("执行前对象在", print.exists)
        assertEquals("旧内容字节数", "执行前的旧内容\n".toByteArray(Charsets.UTF_8).size.toLong(), print.sizeBytes)
        assertEquals("旧内容摘要", sha256("执行前的旧内容\n"), print.sha256)
        assertTrue("mtime 采到了（取证格，非零即可）", print.mtimeMs > 0L)
        assertTrue(
            "采集时刻必须落在本次预演调用窗口内（比沙盒进门还早＝比副本还早）",
            row.atMs in before..entered,
        )
        assertTrue("采集时刻不越调用终点", row.atMs <= after)
        assertTrue(
            "预测后态也要成行（副本重做的产物）：${row.predicted}",
            row.predicted.single().sha256 == sha256("重做后的内容\n"),
        )
    }

    @Test
    fun `判据1_可逆动作不采_无账本根不采_说不出对象不采`() {
        rehearseOnce(reversible = true)
        assertNull("可逆动作不在 431 范围", ShadowBaseline.latestFor(ledger, "op-431"))

        plantReal("x")
        val noLedger = input().copy(ledgerRoot = "")
        assertNull(ShadowBaseline.capture(noLedger, 1L))
        val noTarget = input().copy(declaredPaths = emptyList())
        assertNull(ShadowBaseline.capture(noTarget, 1L))
        val notApplicable = input().copy(tool = "phone_dial")
        assertNull("无文件副作用的工具不拍文件指纹", ShadowBaseline.capture(notApplicable, 1L))
    }

    @Test
    fun `判据1_真盘读不动_整份不采_不落半份基线`() {
        plantReal(null)
        File(ws, "notes/a.md").mkdirs() // 存在但读不出字节
        ShadowAdapter.rehearse(input(), override = Fake(consistentRun()), log = logs::add)
        assertNull("半份基线比出的是假差异 ⇒ 宁可整份不采", ShadowBaseline.latestFor(ledger, "op-431"))
    }

    // ── 判据「零差异不报」 ──────────────────────────────────────────────────────

    @Test
    fun `判据2_零差异不报_按需账与复核队列一个字节都没有`() {
        plantReal("执行前的旧内容\n")
        rehearseOnce()
        // 真执行：真盘落成与重做预测一致的内容（同一把工具同一份参数，本该一致）
        plantReal("重做后的内容\n")
        ShadowOnDemand.onExecuted("op-431", "write_file", ws.absolutePath, logs::add)

        assertFalse("零差异 ⇒ 按需账连文件都不建", File(ledger, ShadowOnDemand.FILE_NAME).isFile)
        assertFalse("零差异 ⇒ 复核队列也不留行", File(ledger, ShadowRecheck.FILE_NAME).isFile)
        assertTrue("日志要有一句交底", logs.joinToString("").contains("零差异不报"))
    }

    @Test
    fun `判据2_mtime不同不算差异_取证格不进比对`() {
        plantReal("执行前的旧内容\n")
        rehearseOnce()
        // 内容与预测一致、mtime 天然不同（这次写盘晚于副本重做）⇒ 依旧零差异
        plantReal("重做后的内容\n")
        File(ws, "notes/a.md").setLastModified(0L)
        ShadowOnDemand.onExecuted("op-431", "write_file", ws.absolutePath, logs::add)
        assertFalse("mtime 只是取证格：${onDemandRows()}", File(ledger, ShadowOnDemand.FILE_NAME).isFile)
    }

    // ── 判据「有差异记一条」 ────────────────────────────────────────────────────

    @Test
    fun `判据3_有差异记一条DISAGREEMENT_第二遍执行不刷第二条`() {
        plantReal("执行前的旧内容\n")
        rehearseOnce()
        plantReal("真盘被改坏的内容\n") // 与重做预测对不上
        ShadowOnDemand.onExecuted("op-431", "write_file", ws.absolutePath, logs::add)
        ShadowOnDemand.onExecuted("op-431", "write_file", ws.absolutePath, logs::add) // 引擎重试同 opId

        val rows = onDemandRows()
        assertEquals("一个动作一条：${rows.size}", 1, rows.size)
        val row = rows.single()
        assertEquals("od:op-431", row.id)
        assertEquals(ShadowOnDemand.OUTCOME_DISAGREE, row.outcome)
        assertEquals("bl:op-431", row.baselineId)
        assertEquals(9L, row.sessionId)
        assertEquals("op-431", row.opId)
        val raw = File(ledger, ShadowOnDemand.FILE_NAME).readText(Charsets.UTF_8)
        assertTrue("差异逐格列在行内（sha 格）", raw.contains("\"g\":\"${ShadowOnDemand.GRID_SHA}\""))
        assertTrue("日志要说不阻断", logs.joinToString("").contains("不阻断"))
    }

    @Test
    fun `判据3_存在性差也记_新建对象在真盘缺席算差`() {
        plantReal("执行前的旧内容\n")
        rehearseOnce()
        plantReal(null) // 真执行后对象反而没了（write_file 不该发生的事）
        ShadowOnDemand.onExecuted("op-431", "write_file", ws.absolutePath, logs::add)

        val rows = onDemandRows()
        assertEquals(1, rows.size)
        val raw = File(ledger, ShadowOnDemand.FILE_NAME).readText(Charsets.UTF_8)
        assertTrue("存在性格差：$raw", raw.contains("\"g\":\"${ShadowOnDemand.GRID_EXISTS}\""))
    }

    @Test
    fun `判据3_纯比对_三格各认各的_摘要在两侧都有才比`() {
        val p = ShadowFingerprint("a", exists = true, sizeBytes = 3, mtimeMs = 1L, sha256 = "aa")
        val same = ShadowFingerprint("a", exists = true, sizeBytes = 3, mtimeMs = 99L, sha256 = "aa")
        assertTrue("mtime 不进比对", ShadowOnDemand.diffOf(listOf(p), listOf(same)).isEmpty())

        val sizeDiff = ShadowFingerprint("a", exists = true, sizeBytes = 4, mtimeMs = 2L, sha256 = "aa")
        assertEquals(ShadowOnDemand.GRID_SIZE, ShadowOnDemand.diffOf(listOf(p), listOf(sizeDiff)).single().grid)

        val shaDiff = ShadowFingerprint("a", exists = true, sizeBytes = 3, mtimeMs = 2L, sha256 = "bb")
        assertEquals(ShadowOnDemand.GRID_SHA, ShadowOnDemand.diffOf(listOf(p), listOf(shaDiff)).single().grid)

        val gone = ShadowFingerprint("a", exists = false, sizeBytes = 0, mtimeMs = 0L, sha256 = "")
        assertEquals(ShadowOnDemand.GRID_EXISTS, ShadowOnDemand.diffOf(listOf(p), listOf(gone)).single().grid)

        val unreadable = ShadowFingerprint("a", exists = true, sizeBytes = 3, mtimeMs = 2L, sha256 = "")
        assertTrue("读不出摘要的一侧不硬比（不冒充读数）", ShadowOnDemand.diffOf(listOf(p), listOf(unreadable)).isEmpty())
    }

    // ── 判据「影子不写主账」 ────────────────────────────────────────────────────

    @Test
    fun `判据4_影子不写主账_全程只出现影子自有文件`() {
        plantReal("执行前的旧内容\n")
        rehearseOnce()
        plantReal("真盘被改坏的内容\n")
        ShadowOnDemand.onExecuted("op-431", "write_file", ws.absolutePath, logs::add)
        ShadowOnDemand.drainDue(logs::add)

        val files = ledger.listFiles()!!.map { it.name }.toSet()
        val shadowOwned = setOf(
            "影子执行.jsonl", // 预演自己的账（239 起就有）
            ShadowBaseline.FILE_NAME,
            ShadowOnDemand.FILE_NAME,
            ShadowRecheck.FILE_NAME,
        )
        assertTrue(
            "账本目录里只许出现影子的自有文件，主账（决策记录/文件操作账/偏差账/灾异事件）一个都不能有：$files",
            files.all { it in shadowOwned },
        )
    }

    // ── ②：验不了 ⇒ 复核排到下一轮（不在收尾重做） ─────────────────────────────

    @Test
    fun `判据2号_首验读不动_排队不落按需账_下一轮恢复则静默收口`() {
        plantReal("执行前的旧内容\n")
        rehearseOnce()
        // 首验现场读不动：真盘对象被换成了目录（readBytes 必挂）
        plantReal(null)
        File(ws, "notes/a.md").mkdirs()
        ShadowOnDemand.onExecuted("op-431", "write_file", ws.absolutePath, logs::add)

        assertTrue("首验验不了 ⇒ 按需账不落行", onDemandRows().isEmpty())
        val pending = ShadowRecheck.pendingRows(ledger)
        assertEquals("排队等下一轮", 1, pending.size)
        assertEquals(1, pending.single().attempts)
        assertTrue("日志要交底排到下一轮", logs.joinToString("").contains("复核排到下一轮"))

        // 下一轮：现场恢复了（真盘与预测一致）⇒ 复核静默收口，依旧零差异不报
        File(ws, "notes/a.md").delete()
        plantReal("重做后的内容\n")
        ShadowOnDemand.drainDue(logs::add)
        assertTrue("复核收口后队列里没有 pending 了", ShadowRecheck.pendingRows(ledger).isEmpty())
        assertTrue("一致依旧不进按需账：${onDemandRows()}", onDemandRows().isEmpty())
    }

    @Test
    fun `判据2号补_同一毫秒入队与收口_必须取文件最后一行`() {
        // 回归（2026-11-05 Linux 真红）：入队 pending 与收口 concluded 可能落在**同一毫秒**
        // （CI 上 mkdir/delete/plant 都在一毫秒内完成）。台账是追加式的 ⇒ 当前态＝**最后一行**；
        // 旧实现按 `maxByOrNull { atMs }` 取"最新"，并列时返回第一行（入队那行）⇒ pending 永远清不掉。
        val t = 1_700_000_000_000L
        ShadowRecheck.enqueue(ledger, "op-same-ms", "write_file", ws.absolutePath, 1, "现场读不动", t)
        val queued = ShadowRecheck.pendingRows(ledger).single()
        assertEquals(ShadowRecheck.STATE_PENDING, queued.state)
        ShadowRecheck.conclude(ledger, queued, ShadowRecheck.STATE_CONCLUDED, "一致", t) // ← 刻意用同一毫秒
        assertEquals(
            "同一毫秒里收口，读侧必须按追加顺序取最后一行（不是按 at 并列取第一行）",
            ShadowRecheck.STATE_CONCLUDED,
            ShadowRecheck.rowOf(ledger, "op-same-ms")!!.state,
        )
        assertTrue("收口后不许再出现在 pending 里", ShadowRecheck.pendingRows(ledger).isEmpty())
    }

    @Test
    fun `判据2号_下一轮仍读不动_两试到顶收口一条unverifiable_不再重试`() {
        plantReal("执行前的旧内容\n")
        rehearseOnce()
        plantReal(null)
        File(ws, "notes/a.md").mkdirs()
        ShadowOnDemand.onExecuted("op-431", "write_file", ws.absolutePath, logs::add)

        ShadowOnDemand.drainDue(logs::add) // 第一轮复核：仍读不动
        val rows = onDemandRows()
        assertEquals("两试到顶 ⇒ 收口一条", 1, rows.size)
        assertEquals(ShadowOnDemand.OUTCOME_UNVERIFIABLE, rows.single().outcome)
        assertEquals(ShadowOnDemand.REASON_READ_FAILED, rows.single().reason)
        assertEquals(2, rows.single().attempts)
        assertTrue(ShadowRecheck.pendingRows(ledger).isEmpty())

        ShadowOnDemand.drainDue(logs::add) // 再开一轮：队列里没有 pending，不再试
        assertEquals("收口后不许再刷", 1, onDemandRows().size)
        assertTrue(logs.joinToString("").contains("不再重试"))
    }

    // ── 范围外：安静返回，不补造账 ─────────────────────────────────────────────

    @Test
    fun `范围外_没采过基线的动作执行后安静返回_不补造基线`() {
        plantReal("随便什么\n")
        ShadowOnDemand.onExecuted("op-别家", "write_file", ws.absolutePath, logs::add)
        assertFalse(File(ledger, ShadowOnDemand.FILE_NAME).isFile)
        assertFalse(File(ledger, ShadowRecheck.FILE_NAME).isFile)
        assertNull(ShadowBaseline.latestFor(ledger, "op-别家"))
    }

    @Test
    fun `范围外_预演短路的动作基线照落_预测为空_按需复核验不了收口`() {
        plantReal("执行前的旧内容\n")
        // UNSUPPORTED 工具：预演进门就短路，但基线已采（进门第一句）、预测没有
        ShadowAdapter.rehearse(input().copy(tool = "unknown_tool"), override = Fake(consistentRun()), log = logs::add)
        val row = baselineRow()
        assertTrue("基线在（执行前必采到）", row.baseline.isNotEmpty())
        assertFalse("短路 ⇒ 没有预测", row.hasPrediction)

        plantReal("真盘被执行了\n")
        ShadowOnDemand.onExecuted("op-431", "unknown_tool", ws.absolutePath, logs::add)
        assertTrue("没预测就验不了 ⇒ 先排队，不落按需账", onDemandRows().isEmpty())
        ShadowOnDemand.drainDue(logs::add)
        ShadowOnDemand.drainDue(logs::add)
        val rows = onDemandRows()
        assertEquals("两试到顶收口一条：$rows", 1, rows.size)
        assertEquals(ShadowOnDemand.REASON_NO_PREDICTION, rows.single().reason)
    }

    @Test
    fun `范围外_纯预测读取_副本缺对象是合法观察_存在但读不动才整份作废`() {
        plantCopy("重做后的内容\n")
        val ok = ShadowOnDemand.predictedOf(File(ws, ".shadow/op-431").absolutePath, listOf("notes/a.md"))
        assertNotNull(ok)
        val withAbsent = ShadowOnDemand.predictedOf(
            File(ws, ".shadow/op-431").absolutePath,
            listOf("notes/a.md", "notes/没落盘.md"),
        )
        assertEquals("副本里没落盘的对象：exists=false 是读数，不是读挂", 2, withAbsent!!.size)
        assertFalse(withAbsent.last().exists)
        // 存在但读不出字节（目录冒充文件）⇒ 半份预测比出假差异 ⇒ 整份作废
        File(ws, ".shadow/op-431/notes/broken.md").mkdirs()
        assertNull(
            ShadowOnDemand.predictedOf(
                File(ws, ".shadow/op-431").absolutePath,
                listOf("notes/a.md", "notes/broken.md"),
            ),
        )
    }
}
