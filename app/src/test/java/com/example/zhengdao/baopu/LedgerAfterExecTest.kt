// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百九十二批 P1（`fix/ledger-after-exec`）：**账上不许有没做过的事**。
//
// ## 这一批的真 bug（派工单 292-P1）
// 她点「拒绝」的那次删除，账上却写着「14:54 删·删进回收站」——记账发生在管线
// `prepare` 阶段、批准**之前** ⇒ 没做的事也被记成做了。它比一般 bug 坏：审计从根上不可信。
//
// ## 判据（派工单四条，逐条打）
// ① 拒绝一次删除 ⇒ 账上**没有**该动作的成功行；
// ② 执行成功 ⇒ 有且只有一行；
// ③ 同一动作重试不会写两行；
// ④ 超时／取消 同样不落业务账。
// 外加修法第 2、4 条：`prepare` 阶段不记业务账；账上要有一条「口径变更点」（只增不改）。
//
// ## 打的是哪两本账
// 管线一次成功写入落**两本**：第二百零八批的通用账（`DecisionKind.AUTO_ACT`）与
// 第二百八十七批的文件操作账（`FileOpLog`，她看到的那一行「删·删进回收站」就是它）。
// 四条判据对两本**同样**成立——所以每条都同时读两处，缺一处就不算过。
//
// ## 负例（本文件自证会红，字节变异在报告里）
// 1. `prepare` 就记账（把落行挪回出卡之前）；2. 拒绝仍落账；3. 同一动作写两行；
// 4. 改历史账行（去重靠"重写整本账"而不是"跳过第二条"）。
package com.example.zhengdao.baopu

import com.example.zhengdao.baopu.agent.DeletePathTool
import com.example.zhengdao.baopu.agent.GuardPipeline
import com.example.zhengdao.baopu.agent.ToolCall
import com.example.zhengdao.baopu.agent.ToolGroup
import com.example.zhengdao.baopu.agent.ToolOutcome
import com.example.zhengdao.baopu.agent.ToolPermission
import com.example.zhengdao.baopu.agent.ToolRegistry
import com.example.zhengdao.baopu.agent.ToolRunner
import com.example.zhengdao.baopu.agent.ToolSpec
import com.example.zhengdao.baopu.agent.jsonArrayOf
import com.example.zhengdao.baopu.agent.jsonObjectOf
import com.example.zhengdao.baopu.authz.ShadowSignal
import com.example.zhengdao.baopu.authz.TaskGrantBoard
import com.example.zhengdao.baopu.authz.WiringDisposition
import com.example.zhengdao.baopu.authz.WiringResult
import com.example.zhengdao.baopu.authz.GrantWiring
import com.example.zhengdao.baopu.plan.ContractSource
import com.example.zhengdao.baopu.plan.PlanContract
import com.example.zhengdao.baopu.plan.PlanContractRules
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executors
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LedgerAfterExecTest {

    private lateinit var ws: File
    private lateinit var ledgerRoot: File
    private val lines = mutableListOf<String>()

    @Before
    fun setup() {
        ws = Files.createTempDirectory("baopu-292-ws").toFile()
        ledgerRoot = Files.createTempDirectory("baopu-292-ledger").toFile()
        DecisionLedger.install(ledgerRoot)
        // 授权板与文件账都是进程级全局：不清就是随**执行顺序**变红（224 批的教训同款）。
        TaskGrantBoard.resetForTest()
        FileOpLog.resetForTest()
        FileOpLog.enterSession(SESSION)
    }

    @After
    fun teardown() {
        TaskGrantBoard.resetForTest()
        FileOpLog.resetForTest()
        DecisionLedger.uninstallForTest()
        listOf(ws, ledgerRoot).forEach { runCatching { it.deleteRecursively() } }
    }

    // ── 夹具 ────────────────────────────────────────────────────────────────

    /** 这一批只问「账什么时候落」，不掺授权链的变量：六步一律判「不适用」。 */
    private object NoGrant : GrantWiring {
        override fun decide(
            call: ToolCall,
            targets: List<File>,
            verdict: RiskVerdict?,
            root: File,
            isWrite: Boolean,
            nowMonoMs: Long,
            epoch: String,
            shadow: ShadowSignal,
            log: (String) -> Unit,
        ): WiringResult = WiringResult(WiringDisposition.NOT_APPLICABLE, note = "本批不掺授权变量")
    }

    /** 板上没契约 ⇒ 242 的动作流水一格都不进（那一段的位置归 242/276，本批不碰）。 */
    private object NoContract : ContractSource {
        override fun activeContract(): PlanContract? = null
        override fun planItems(): List<PlanRules.PlanItem> = emptyList()
        override fun workspaceRoot(): String = ""
        override fun noteAction(action: PlanContractRules.ActualAction) = Unit
    }

    /** `write_file` 按 R1 直行（免卡）——判据②③④要的是"批准后那一段"，不要卡。 */
    private val table: RiskTable = RiskTable.DEFAULT.copy(
        tools = RiskTable.DEFAULT.tools + mapOf(
            "write_file" to ToolRule(RiskClass.R1, writePathKeys = setOf("path")),
        ),
    )

    private fun pipeline(vararg specs: ToolSpec): GuardPipeline = GuardPipeline(
        registry(*specs),
        { ws },
        { null },
        ToolRunner(Executors.newSingleThreadExecutor()),
        { lines += it },
        riskTable = table,
        contracts = NoContract,
        grantWiring = NoGrant,
    )

    /** `delete_path` 归 `files` 组（默认不可见 ⇒ 执行器是「未解锁」拒绝桩），测试里按御批口径开组。 */
    private fun registry(vararg specs: ToolSpec): ToolRegistry =
        ToolRegistry().apply {
            setGroupApproved(ToolGroup.FILES, true)
            specs.forEach { register(it) }
        }

    private val pathSchema: JSONObject = jsonObjectOf(
        "type" to "object",
        "properties" to jsonObjectOf(
            "path" to jsonObjectOf("type" to "string"),
            "content" to jsonObjectOf("type" to "string"),
        ),
        "required" to jsonArrayOf("path"),
        "additionalProperties" to false,
    )

    /** 假写盘工具：真的落文件，只是不经过真 `write_file` 的正文规则（本批不测正文）。 */
    private fun writeSpec(name: String = "write_file", timeoutMs: Long = 5_000L, body: (JSONObject) -> ToolOutcome) =
        ToolSpec(
            name = name,
            description = "账实相符批次用的假写盘工具（真管线、假执行器）",
            inputSchema = pathSchema,
            timeoutMs = timeoutMs,
            readOnly = false,
            executor = body,
        )

    private fun okWrite() = writeSpec { args ->
        args.writeFile("改过的正文")
        ToolOutcome.Ok("写了")
    }

    /** 执行器落盘（真 `write_file` 也是先建目录再写——不然相对路径根本没地方落）。 */
    private fun JSONObject.writeFile(text: String): File =
        File(optString("path")).also { f ->
            f.parentFile?.mkdirs()
            f.writeText(text)
        }

    private fun call(opId: String, name: String, rel: String) =
        ToolCall(opId, name, JSONObject().put("path", rel))

    private fun touch(rel: String, text: String = "初稿"): File =
        File(ws, rel).also { f ->
            f.parentFile?.mkdirs()
            f.writeText(text)
        }

    /** 208 里的**业务账**（通用写账）；口径变更点那一条不算（它是账本自己的事）。 */
    private fun rows() = DecisionLedger.read(ledgerRoot, DecisionLedger.READ_LIMIT)

    private fun actRows() = rows().filter { it.kind == DecisionKind.AUTO_ACT }

    private fun policyRows() = rows().filter { it.kind == DecisionKind.LEDGER_POLICY }

    private fun ledgerBytes(): ByteArray =
        DecisionLedger.fileIn(ledgerRoot).takeIf { it.isFile }?.readBytes() ?: ByteArray(0)

    // ── 判据①：拒绝一次删除 ⇒ 账上没有该动作的成功行 ─────────────────────────

    @Test
    fun 判据一_卡还没批账上就该是空的_出卡之前一格都不记() {
        val f = touch("资料库/待删.md")
        val p = pipeline(DeletePathTool.spec { ws })
        val r = p.execute(call("op-1", "delete_path", f.absolutePath), { _, _ -> null })
        assertTrue("删文件必须先问：$r", r is GuardPipeline.Result.NeedsConfirm)
        assertTrue("文件原样在", f.exists())
        assertEquals("208 不许有这一笔的成功行", 0, actRows().size)
        assertEquals("文件操作账也不许有（她看到的就是这一行）", 0, FileOpLog.count())
        assertEquals("连意向都不该进 208（整本账要能读出真做过什么）", 0, rows().size)
    }

    @Test
    fun 判据一_只跑prepare也不落业务账_账是做过什么不是想过什么() {
        val f = touch("资料库/待删2.md")
        val p = pipeline(DeletePathTool.spec { ws })
        val prepared = p.prepare(call("op-2", "delete_path", f.absolutePath))
        assertTrue("③段就该定论：$prepared", prepared is GuardPipeline.Prepared.Settled)
        assertEquals("prepare 阶段一格业务账都不许落", 0, actRows().size)
        assertEquals("prepare 阶段也不许进文件操作账", 0, FileOpLog.count())
    }

    @Test
    fun 判据一_硬拒那一支同样不落账() {
        // 矩阵硬拒（原件区）⇒ 动作根本不发生；降级成"问"或者顺手记一笔都是骗人。
        val raw = File(ws, "资料库/原始").apply { mkdirs() }
        val target = File(raw, "原件.md").apply { writeText("原件") }
        val p = GuardPipeline(
            registry(DeletePathTool.spec { ws }),
            { ws },
            { raw },
            ToolRunner(Executors.newSingleThreadExecutor()),
            { lines += it },
            riskTable = table,
            contracts = NoContract,
            grantWiring = NoGrant,
        )
        val r = p.execute(call("op-3", "delete_path", target.absolutePath), { _, _ -> null })
        assertTrue("原件区必须硬拒：$r", r is GuardPipeline.Result.Rejected)
        assertTrue("文件原样在", target.exists())
        assertEquals("被拒的动作不许变成账上的一条", 0, actRows().size)
        assertEquals("被拒的动作也不许进文件操作账", 0, FileOpLog.count())
    }

    // ── 判据②：执行成功 ⇒ 有且只有一行 ──────────────────────────────────────

    @Test
    fun 判据二_批准之后真删_两本账各只有一行() {
        val f = touch("资料库/批准删.md")
        val p = pipeline(DeletePathTool.spec { ws })
        val card = p.execute(call("op-4", "delete_path", f.absolutePath), { _, _ -> null })
        assertTrue("删必须先问：$card", card is GuardPipeline.Result.NeedsConfirm)
        assertEquals("批之前仍然是空的（判据①的另一半）", 0, actRows().size)
        val r = p.executeApproved(call("op-4", "delete_path", f.absolutePath), { _, _ -> null })
        assertTrue("批准之后才执行：$r", r is GuardPipeline.Result.Done)
        assertFalse("文件真没了", f.exists())
        assertEquals("208 有且只有一行", 1, actRows().size)
        assertEquals("文件操作账有且只有一行", 1, FileOpLog.count())
        val row = actRows().single()
        assertTrue("这一行要指得回这一次调用（opId 在证据里）：${row.evidence}", row.evidence.contains("op-4"))
        assertTrue("证据房型仍是 tool:<名>:… 别的批次按这一段取工具名：${row.evidence}", row.evidence.startsWith("tool:delete_path:"))
    }

    @Test
    fun 判据二_不同动作各记各的_不许互相顶掉() {
        val p = pipeline(okWrite())
        val ra = p.execute(call("op-a", "write_file", "笔记/一.md"), { _, _ -> null })
        val rb = p.execute(call("op-b", "write_file", "笔记/二.md"), { _, _ -> null })
        assertTrue("两个都该直行跑成：$ra", ra is GuardPipeline.Result.Done)
        assertTrue("两个都该直行跑成：$rb", rb is GuardPipeline.Result.Done)
        assertEquals("两个动作＝两条账（幂等不是「只记第一条」）", 2, actRows().size)
        assertEquals("两行的证据必须互不相同", 2, actRows().map { it.evidence }.distinct().size)
    }

    // ── 判据③：同一动作重试不写两行 ─────────────────────────────────────────

    @Test
    fun 判据三_同一个opId跑两遍_账上仍只有一行() {
        // 引擎的重试是同一次调用（同 opId）再来一遍；超时那条尤其危险：
        // 第一遍可能其实已经写成了盘，第二遍又写一次——账上必须还是一件事。
        val p = pipeline(okWrite())
        val c = call("op-r", "write_file", "笔记/重跑.md")
        val r1 = p.execute(c, { _, _ -> null })
        val r2 = p.execute(c, { _, _ -> null })
        assertTrue("第一遍就该真跑了：$r1", r1 is GuardPipeline.Result.Done)
        assertTrue("第二遍（引擎重试同一条）也真跑了：$r2", r2 is GuardPipeline.Result.Done)
        assertEquals("一动作两账＝抽检分不清真做了两次还是记了两次", 1, actRows().size)
        assertEquals("文件操作账同理", 1, FileOpLog.recent(10).count { it.path.endsWith("重跑.md") })
    }

    @Test
    fun 判据三_批准后重跑同一条也不补第二条() {
        val f = touch("资料库/批准重跑.md")
        val p = pipeline(DeletePathTool.spec { ws })
        val c = call("op-r2", "delete_path", f.absolutePath)
        assertTrue("删必须先问：$c", p.execute(c, { _, _ -> null }) is GuardPipeline.Result.NeedsConfirm)
        val approved = p.executeApproved(c, { _, _ -> null })
        assertTrue("批了才真删：$approved", approved is GuardPipeline.Result.Done)
        // 第二遍：文件已经没了，工具自己会报失败——但账上不许因此多出「第二件成功的事」。
        val again = p.executeApproved(c, { _, _ -> null })
        assertFalse("同一 opId 的第二遍不许再算一次成功：$again", again is GuardPipeline.Result.Done)
        assertEquals("一次批准只对应一行成功账", 1, actRows().size)
    }

    // ── 判据④：超时／取消 同样不落业务账 ────────────────────────────────────

    @Test
    fun 判据四_执行超时不落业务账() {
        val p = pipeline(
            writeSpec(timeoutMs = 120L) { args ->
                Thread.sleep(1_500L)
                args.writeFile("迟到才写完")
                ToolOutcome.Ok("写了")
            },
        )
        val r = p.execute(call("op-t", "write_file", "笔记/慢.md"), { _, _ -> null })
        assertTrue("超时是可重试失败：$r", r is GuardPipeline.Result.Failed)
        assertTrue("失败原文要说超时：$r", (r as GuardPipeline.Result.Failed).error.contains("超时"))
        assertEquals("超时那一刻动作没成 ⇒ 不许有成功行", 0, actRows().size)
        assertEquals("超时也不许进文件操作账", 0, FileOpLog.count())
    }

    @Test
    fun 判据四_被取消不落业务账() {
        // 取消在管线里的形状＝执行器交回一个失败（引擎把这一轮停了）。
        val p = pipeline(writeSpec { ToolOutcome.Failed("用户取消了这一步") })
        val r = p.execute(call("op-c", "write_file", "笔记/取消.md"), { _, _ -> null })
        assertTrue("取消＝可重试失败：$r", r is GuardPipeline.Result.Failed)
        assertEquals("取消的那一步没做成 ⇒ 账上不许有", 0, actRows().size)
        assertEquals("取消也不许进文件操作账", 0, FileOpLog.count())
    }

    @Test
    fun 判据四_执行器自己拒了同样不落账() {
        val p = pipeline(writeSpec { ToolOutcome.Rejected("这一步工具自己拒了") })
        val r = p.execute(call("op-rj", "write_file", "笔记/自拒.md"), { _, _ -> null })
        assertTrue("执行器自拒＝直接拒：$r", r is GuardPipeline.Result.Rejected)
        assertEquals("被拒的动作不落业务账", 0, actRows().size)
        assertEquals("被拒的动作不落文件操作账", 0, FileOpLog.count())
    }

    @Test
    fun 判据四_只读工具本来就不进业务账() {
        val p = pipeline(okWrite().copy(readOnly = true))
        val r = p.execute(call("op-ro", "write_file", "笔记/只读.md"), { _, _ -> null })
        assertTrue("只读直行：$r", r is GuardPipeline.Result.Done)
        assertEquals("读操作没有副作用，记了只会把记录页淹掉", 0, actRows().size)
    }

    // ── 修法④：账上的「口径变更点」（只增不改）──────────────────────────────

    @Test
    fun 口径变更点_账上标一条并且只标一条() {
        val p = pipeline(okWrite())
        val m1 = p.execute(call("op-m1", "write_file", "笔记/一.md"), { _, _ -> null })
        val m2 = p.execute(call("op-m2", "write_file", "笔记/二.md"), { _, _ -> null })
        assertTrue("两笔都得真跑成：$m1", m1 is GuardPipeline.Result.Done)
        assertTrue("两笔都得真跑成：$m2", m2 is GuardPipeline.Result.Done)
        val marks = policyRows()
        assertEquals("变更点必须真的落进账里（口头口径不算）", 1, marks.size)
        val m = marks.single()
        assertTrue("要说清改的是什么：${m.reason}", m.reason.contains("执行成功后"))
        assertTrue("拒绝/取消/超时那一半也要写在同一条上：${m.reason}", m.reason.contains("拒绝"))
        assertFalse("口径变更点不是副作用，不许标可回滚", m.reversible)
        assertEquals(
            "变更点写死成一个日子（改的是口径，不是这台机器的开机日）：${m.atMs}",
            DecisionLedger.POLICY_CHANGE_MS,
            m.atMs,
        )
        assertTrue("要早于此刻（账后头还要接着长）：${m.atMs}", m.atMs <= System.currentTimeMillis())
        assertTrue("旧口径要说清：${m.before}", m.before.contains("prepare"))
        assertTrue("新口径要说清：${m.after}", m.after.contains("执行成功之后"))
    }

    @Test
    fun 只增不改_后一条账不许动前面那些字节() {
        val p = pipeline(okWrite())
        val h1 = p.execute(call("op-h1", "write_file", "笔记/先.md"), { _, _ -> null })
        assertTrue("第一笔得真跑成，不然没历史行可比：$h1", h1 is GuardPipeline.Result.Done)
        val first = ledgerBytes()
        val firstRow = rows().first { it.kind == DecisionKind.AUTO_ACT }
        val h2 = p.execute(call("op-h2", "write_file", "笔记/后.md"), { _, _ -> null })
        assertTrue("第二笔也得真跑成：$h2", h2 is GuardPipeline.Result.Done)
        val after = ledgerBytes()
        assertTrue("账本只许变长（去重靠「不写第二条」，不是靠重写整本账）", after.size > first.size)
        assertTrue(
            "前面的字节必须逐字不动——历史账行是事实，改它＝伪造审计",
            after.copyOfRange(0, first.size).contentEquals(first),
        )
        val stillThere = rows().firstOrNull { it.id == firstRow.id }
        assertEquals("老行的时刻/证据/结论读回来还是原来那份", firstRow, stillThere)
    }

    private companion object {
        const val SESSION = 92L
    }
}
