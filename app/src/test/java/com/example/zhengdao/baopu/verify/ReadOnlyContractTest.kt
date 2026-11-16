// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百八十二批的判据测试（G-673-3：只读任务收工被判「缺证据」）。
//
// 四条判据在这里逐条钉死（全部真文件真判据表，没有一分 mock）：
//   ① 纯只读收工 ⇒ PASS；**负例**：把它判成写入/混合同一轮 ⇒ 必须仍 FAIL 且点名 exists(缺)
//      （证明放宽只对着只读那一档开，不是把 exists 这条判据拆了）；
//   ② 写入任务仍要产物与结构证据（不回归）：真落一份空 md / 没有 md 都照旧红；
//   ③ 账里看得见「本任务属于只读」（收工行 + render 两处口径一致）；
//   ④ 不放宽「是否真做过」：一条 ok=1 的工具行都没有 ⇒ 仍 FAIL，点名 tool_rows(缺)。
//
// 另有两条结构面：三档要求表（必须/可选/不适用）与「四类判据表一格未动」
// ——Tool_ROWS 只服务只读口径，四类表仍是 4/3/4/3 共 14 条（332 的口径一个字没改）。
package com.example.zhengdao.baopu.verify

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ReadOnlyContractTest {

    /**
     * 本类建过的沙盒（@After 里逐个删）。
     *
     * 为什么要收尾：`check-test-temp.py` 数的是"测试自建沙盒根"，跑完不删就是往
     * `app/build/tmp` 里一棵一棵攒（564/607 两批为这件事立的闸）。本类只用一次就丢，
     * 留着它没有意义，删掉才算把闸当回事。
     */
    private val sandboxes = mutableListOf<File>()

    private fun tmp(name: String): File =
        Files.createTempDirectory("baopu-682-$name").toFile().also { sandboxes += it }

    @After
    fun tearDown() {
        sandboxes.forEach { runCatching { it.deleteRecursively() } }
        sandboxes.clear()
    }

    private fun orderOf(text: String): RunContractWiring.RunOrder {
        val order = RunContractWiring.startRun("r682@1700000000000", text)
        assertNotNull("起单失败（契约没建成）", order)
        return order!!
    }

    /** 只读读数：三步 list_dir/read_file 都成了，各带一句结果摘要。 */
    private fun readTrace(rows: Int = 3): RoundTrace = RoundTrace(
        readRows = rows,
        summaries = (1..rows).map { "list_dir（{\"path\":\"产物\"}）→ 第 $it 个条目" },
    )

    private fun writeDoc(root: File, rel: String, body: String) {
        val f = File(root, rel)
        f.parentFile.mkdirs()
        f.writeText(body)
    }

    // ── 判据① 纯只读收工 ⇒ PASS（673 那条误判不许再出现）────────────────────

    @Test
    fun `判据1_纯只读收工_有工具行有摘要_判PASS且不再点exists缺证据`() {
        val order = orderOf("帮我列一下产物目录里都有什么")
        val report = RunContractWiring.closeRun(order, tmp("read-pass"), emptyList(), emptyList(), readTrace())
        assertEquals("只读任务收工必须判过（673 的误判就在这一格）", Status.PASS, report.status)
        assertEquals(Reason.OK, report.reason)
        assertTrue("只读不该再把 exists/structure/user_checklist 点成缺证据", report.missing.isEmpty())
        assertTrue("也不该有判不过的条目", report.failed.isEmpty())
        assertEquals(TaskShape.READ_ONLY, report.shape)
        assertEquals(
            "产物三条要摊开标不适用（不是删掉——删掉就是空过）",
            listOf(CheckId.EXISTS, CheckId.STRUCTURE, CheckId.USER_CHECKLIST),
            report.notApplicable,
        )
    }

    @Test
    fun `判据1负例_同一轮混进一次写_就必须回落到产物判据并点名exists缺`() {
        // 673 原病灶的形状：只读步之外真写过（或写类工具跑成了）⇒ 形态不是只读 ⇒
        // 必须回到产物判据，而没有 md 产物 ⇒ FAIL 点名 exists。放宽只对纯只读开。
        val order = orderOf("帮我列一下产物目录，再顺手记一笔")
        val trace = readTrace(rows = 2).copy(writeRows = 1)
        val report = RunContractWiring.closeRun(order, tmp("mixed"), emptyList(), emptyList(), trace)
        assertEquals(TaskShape.MIXED, report.shape)
        assertEquals(Status.FAIL, report.status)
        assertTrue("混合按最高要求：exists 必须进缺证据名单", CheckId.EXISTS in report.missing)
    }

    // ── 判据② 写入任务不回归：产物、结构照旧要 ─────────────────────────────

    @Test
    fun `判据2_写入任务_没产物仍判缺证据_exists必须点名`() {
        val order = orderOf("帮我写一份周报")
        val report = RunContractWiring.closeRun(order, tmp("w-no-doc"), emptyList(), emptyList(), RoundTrace(writeRows = 1))
        assertEquals(TaskShape.WRITE, report.shape)
        assertEquals(Status.FAIL, report.status)
        assertEquals(Reason.MISSING_EVIDENCE, report.reason)
        assertEquals(
            "写入任务三条一条不少（不回归）",
            listOf(CheckId.EXISTS, CheckId.STRUCTURE, CheckId.USER_CHECKLIST),
            report.missing,
        )
    }

    @Test
    fun `判据2_写入任务_产物空文件与结构坏了照旧红`() {
        val root = tmp("w-blank")
        writeDoc(root, "产物/周报.md", "")
        val order = orderOf("帮我写一份周报")
        val blank = RunContractWiring.closeRun(
            order, root, listOf("产物/周报.md"), emptyList(), RoundTrace(writeRows = 1),
        )
        assertEquals(Status.FAIL, blank.status)
        assertTrue(CheckId.EXISTS in blank.failed)

        writeDoc(root, "产物/周报.md", "## 二级开头\n\n跳级。\n")
        val broken = RunContractWiring.closeRun(
            order, root, listOf("产物/周报.md"), emptyList(), RoundTrace(writeRows = 1),
        )
        assertEquals(Status.FAIL, broken.status)
        assertEquals(Reason.VALUE_MISMATCH, broken.reason)
        assertTrue(CheckId.STRUCTURE in broken.failed)
    }

    // ── 判据③ 账里看得见「本任务属于只读」──────────────────────────────────

    @Test
    fun `判据3_收工行写明本任务属于只读_写入行写明写入`() {
        val order = orderOf("帮我列一下产物目录")
        val readLine = RunContractWiring.reportLine(
            RunContractWiring.closeRun(order, tmp("line-ro"), emptyList(), emptyList(), readTrace()),
        )
        assertTrue("收工行没写「本任务属于只读」：$readLine", readLine.contains("本任务属于只读"))
        assertTrue("收工行要以验收口径起头（账里的 grep 键）", readLine.startsWith("抱朴契约：收工验收"))
        assertTrue("只读行要点出放宽口径的依据（工具行＋结果摘要）", readLine.contains("工具行"))

        val writeLine = RunContractWiring.reportLine(
            RunContractWiring.closeRun(order, tmp("line-w"), emptyList(), emptyList(), RoundTrace(writeRows = 1)),
        )
        assertTrue("写入行要写明形态：$writeLine", writeLine.contains("本任务：写入"))
        assertFalse("写入行不许冒充只读：$writeLine", writeLine.contains("本任务属于只读"))
    }

    @Test
    fun `判据3_验收单render也摊开形态与不适用`() {
        val order = orderOf("帮我列一下产物目录")
        val text = RunContractWiring.closeRun(order, tmp("render"), emptyList(), emptyList(), readTrace()).render()
        assertTrue("render 没写形态：$text", text.contains("本任务：只读"))
        assertTrue("render 没标不适用：$text", text.contains("[不适用]"))
        assertTrue("render 没给工具行读数：$text", text.contains("工具行 3 条"))
    }

    // ── 判据④ 不放宽「是否真做过」──────────────────────────────────────────

    @Test
    fun `判据4_一条工具行都没有_不认只读也不给过`() {
        // 形态未定（null）⇒ 走最高要求；同时这不是"只读"，账上也不许写成只读。
        val order = orderOf("帮我列一下产物目录")
        val report = RunContractWiring.closeRun(order, tmp("no-rows"), emptyList(), emptyList(), RoundTrace())
        assertNull(report.shape)
        assertEquals(Status.FAIL, report.status)
        assertTrue("没有工具行 ⇒ exists 照旧缺", CheckId.EXISTS in report.missing)
        assertFalse(
            "没做过的事不许写成只读任务",
            RunContractWiring.reportLine(report).contains("本任务属于只读"),
        )
    }

    @Test
    fun `判据4_有工具行但一条摘要都没有_只读照样不过并点名tool_rows`() {
        val order = orderOf("帮我列一下产物目录")
        val trace = RoundTrace(readRows = 4, summaries = emptyList())
        val report = RunContractWiring.closeRun(order, tmp("no-summary"), emptyList(), emptyList(), trace)
        assertEquals("形状是只读（读类跑成了 4 行）", TaskShape.READ_ONLY, report.shape)
        assertEquals("但证据不成立：没有结果摘要", Status.FAIL, report.status)
        assertEquals(Reason.MISSING_EVIDENCE, report.reason)
        assertEquals(listOf(CheckId.TOOL_ROWS), report.missing)
        assertTrue(
            "账上要点名 tool_rows(缺)：${RunContractWiring.reportLine(report)}",
            RunContractWiring.reportLine(report).contains("tool_rows(缺)"),
        )
    }

    // ── 三档要求表：必须／可选／不适用 ─────────────────────────────────────

    @Test
    fun `三档_只读把产物三条降成不适用_工具行升成必须`() {
        val needs = ShapeRules.needsOf(TaskType.DOC_PRODUCE, TaskShape.READ_ONLY)
        assertEquals(CheckNeed.NOT_APPLICABLE, needs[CheckId.EXISTS])
        assertEquals(CheckNeed.NOT_APPLICABLE, needs[CheckId.STRUCTURE])
        assertEquals(CheckNeed.NOT_APPLICABLE, needs[CheckId.USER_CHECKLIST])
        assertEquals(CheckNeed.REQUIRED, needs[CheckId.TOOL_ROWS])
        assertEquals(
            "只读口径下必须档只有一条（工具行）——不多不少",
            listOf(CheckId.TOOL_ROWS),
            ShapeRules.requiredOf(TaskType.DOC_PRODUCE, TaskShape.READ_ONLY),
        )
    }

    @Test
    fun `三档_写入与混合都按最高要求_工具行只记不判`() {
        listOf(TaskShape.WRITE, TaskShape.MIXED, null).forEach { shape ->
            val needs = ShapeRules.needsOf(TaskType.DOC_PRODUCE, shape)
            assertEquals("$shape：exists 必须", CheckNeed.REQUIRED, needs[CheckId.EXISTS])
            assertEquals("$shape：structure 必须", CheckNeed.REQUIRED, needs[CheckId.STRUCTURE])
            assertEquals("$shape：检查项必须", CheckNeed.REQUIRED, needs[CheckId.USER_CHECKLIST])
            assertEquals("$shape：工具行是可选档（只记不判）", CheckNeed.OPTIONAL, needs[CheckId.TOOL_ROWS])
            assertEquals(
                "$shape：必须档不许被只读口径削掉任何一条",
                listOf(CheckId.EXISTS, CheckId.STRUCTURE, CheckId.USER_CHECKLIST),
                ShapeRules.requiredOf(TaskType.DOC_PRODUCE, shape),
            )
        }
    }

    @Test
    fun `三档_其余三类任务不开只读口子`() {
        listOf(TaskType.FILE_ORGANIZE, TaskType.CODE_CHANGE, TaskType.DATA_TABLE).forEach { type ->
            val needs = ShapeRules.needsOf(type, TaskShape.READ_ONLY)
            assertEquals(
                "$type：本批只给文档类开口子，其余三类一律必须",
                ContractSelector.checksOf(type).toSet(),
                needs.filterValues { it == CheckNeed.REQUIRED }.keys,
            )
        }
    }

    @Test
    fun `三档_四类判据表一格未动_仍是14条且不含工具行`() {
        val byType = ContractSelector.types().map { ContractSelector.checksOf(it) }
        assertEquals(listOf(4, 3, 4, 3), byType.map { it.size })
        assertEquals(14, byType.flatten().toSet().size)
        assertFalse(
            "工具行那一条只服务只读口径，不许混进四类判据表",
            byType.flatten().contains(CheckId.TOOL_ROWS),
        )
    }

    // ── 形态判定：三类的边界 ───────────────────────────────────────────────

    @Test
    fun `形态_读写都有是混合_只有写是写入_只有读是只读_一条都没有是未定`() {
        assertEquals(TaskShape.READ_ONLY, RoundTrace(readRows = 2).shape)
        assertEquals(TaskShape.WRITE, RoundTrace(writeRows = 2).shape)
        assertEquals(TaskShape.MIXED, RoundTrace(readRows = 1, writeRows = 1).shape)
        assertNull(RoundTrace().shape)
        // touched 也算「写过」：老调用点只给产物、不给 trace，形态据此判成写入（不回归）
        assertEquals(TaskShape.WRITE, ShapeRules.shapeOf(RoundTrace(), listOf("产物/周报.md")))
        assertEquals(TaskShape.MIXED, ShapeRules.shapeOf(RoundTrace(readRows = 1), listOf("产物/周报.md")))
        assertTrue(TaskShape.READ_ONLY.relaxed)
        assertFalse(TaskShape.MIXED.relaxed)
        assertFalse(TaskShape.WRITE.relaxed)
    }

    // ── 面板那一行：只读说的是「做成了几件」，不是「落了几个文件」──────────────

    @Test
    fun `面板_只读PASS走只读那句_依据是工具行条数`() {
        val order = orderOf("帮我列一下产物目录")
        val report = RunContractWiring.closeRun(order, tmp("line-narr"), emptyList(), emptyList(), readTrace(6))
        val line = VerifyNarrative.fromContract(report, emptyList(), gateFindings = 0, toolRows = 6)
        assertNotNull(line)
        assertEquals(VerifyPhrase.READ_PASS, line!!.conclusion)
        assertEquals(VerifyPhrase.BASIS_ROWS, line.basis)
        assertEquals(listOf(6), line.numbers)
        assertFalse(
            "只读不许用「该写的都写了」那句（它压根没写东西）",
            line.conclusion == VerifyPhrase.PASS,
        )
    }
}
