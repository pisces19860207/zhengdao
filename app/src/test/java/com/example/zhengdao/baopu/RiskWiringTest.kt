// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：派工单-2026-10-26-第二百二十三批-分类器接线 §2「判据 / 负例」。
//
// 本文件是**接线层的账**：219 的表是判定层（`RiskMatrixTest` 管它自洽），本文件管
// 「闸真的在用它」——所有用例都穿过 `GuardPipeline`，不直接调矩阵。这是本批的核心判据：
// 一张只被自己测试引用的表，等于没接线（这正是本批要修的那个病）。
package com.example.zhengdao.baopu

import com.example.zhengdao.baopu.agent.GuardPipeline
import com.example.zhengdao.baopu.agent.InMemoryToolPrefs
import com.example.zhengdao.baopu.agent.ToolCall
import com.example.zhengdao.baopu.agent.ToolOutcome
import com.example.zhengdao.baopu.agent.ToolPermission
import com.example.zhengdao.baopu.agent.ToolRegistry
import com.example.zhengdao.baopu.agent.ToolRunner
import com.example.zhengdao.baopu.agent.ToolSpec
import com.example.zhengdao.baopu.agent.jsonObjectOf
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class RiskWiringTest {

    private val roots = mutableListOf<File>()

    @After
    fun cleanup() {
        roots.forEach { it.deleteRecursively() }
        RiskGate.resetForTest()
        // 第二百二十四批：装过落点就必须还回去——它是进程级全局，不还的话同 JVM 里
        // 「没装配落点就不落」那条用例会随执行顺序变红（见 DecisionLedger.uninstallForTest）
        DecisionLedger.uninstallForTest()
    }

    // ── 夹具 ─────────────────────────────────────────────────────────────

    private fun tmp(name: String): File =
        Files.createTempDirectory("zd-risk223-$name").toFile().also { roots += it }

    private val ran = AtomicInteger(0)

    /** 假工具（不进 219 的表——档位由每个用例自己给，这正是「表驱动」的用法）。 */
    private fun fake(name: String, perm: ToolPermission = ToolPermission.FREE, vararg keys: String): ToolSpec =
        ToolSpec(
            name = name,
            description = "第二百二十三批接线测试用的假工具",
            inputSchema = jsonObjectOf(
                "type" to "object",
                "properties" to JSONObject().also { props -> keys.forEach { props.put(it, JSONObject()) } },
            ),
            permission = perm,
        ) {
            ran.incrementAndGet()
            ToolOutcome.Ok("ran:${it.optString("path")}")
        }

    /**
     * 真注册表 ＋ 假工具。
     *
     * 为什么还要读真工具：正例「读文件无卡」必须打在**真实**的 `read_file` 上，
     * 否则「只读不弹卡」只是我自己造的一个 R0 行的自证。
     */
    private fun registry(root: File, vararg specs: ToolSpec): ToolRegistry = ToolRegistry(InMemoryToolPrefs()).apply {
        register(
            ToolSpec("read_file", "读", jsonObjectOf("type" to "object", "properties" to JSONObject())) {
                ToolOutcome.Ok("内容")
            },
        )
        register(
            ToolSpec("list_dir", "列", jsonObjectOf("type" to "object", "properties" to JSONObject())) {
                ToolOutcome.Ok("a.md")
            },
        )
        specs.forEach { register(it) }
        check(root.isDirectory)
    }

    /** 在 219 的表上**加一行**（不改原来的表；`matrixVersion +1` 是改表的规矩）。 */
    private fun tableWith(vararg rows: Pair<String, ToolRule>): RiskTable =
        RiskTable.DEFAULT.copy(
            tools = RiskTable.DEFAULT.tools + rows,
            matrixVersion = RiskTable.DEFAULT_VERSION + 1,
        )

    private fun pipeline(
        root: File,
        table: RiskTable = RiskTable.DEFAULT,
        outside: Boolean = false,
        log: (String) -> Unit = {},
        vararg specs: ToolSpec,
    ): GuardPipeline = GuardPipeline(
        registry(root, *specs),
        { root },
        { null },
        ToolRunner(Executors.newSingleThreadExecutor()),
        log,
        { outside },
        table,
    )

    private fun call(name: String, vararg args: Pair<String, Any?>): ToolCall {
        val json = JSONObject()
        args.forEach { (k, v) -> json.put(k, v) }
        return ToolCall("c-$name", name, json)
    }

    private fun run(root: File, c: ToolCall, p: GuardPipeline): GuardPipeline.Result =
        p.execute(c) { _, _ -> null }

    private fun assertNeedsConfirm(r: GuardPipeline.Result): GuardPipeline.Result.NeedsConfirm {
        assertTrue("应当弹卡，实际是 ${r::class.simpleName}", r is GuardPipeline.Result.NeedsConfirm)
        return r as GuardPipeline.Result.NeedsConfirm
    }

    private fun assertDone(r: GuardPipeline.Result) {
        assertTrue("应当直行，实际是 ${r::class.simpleName}", r is GuardPipeline.Result.Done)
    }

    // ── 正例 ─────────────────────────────────────────────────────────────

    @Test
    fun `正例_写证道内_R1_免卡直行`() {
        val root = tmp("inside")
        val table = tableWith("note_write" to ToolRule(RiskClass.R1, writePathKeys = setOf("path")))
        val p = pipeline(
            root,
            table,
            outside = false,
            log = {},
            specs = arrayOf(fake("note_write", ToolPermission.FREE, "path")),
        )
        assertDone(run(root, call("note_write", "path" to "产物/a.md"), p))
        assertEquals("executor 必须真的跑了一次", 1, ran.get())
        // 矩阵判 R1 且没有硬拒理由 ⇒ 卡面上没有"为什么问你"那一行
        val v = RiskGate.judge(
            call("note_write", "path" to File(root, "产物/a.md").absolutePath),
            listOf(root.absolutePath),
            table = table,
        )
        assertEquals(RiskClass.R1, v.risk)
        assertEquals("R1 不该有必问理由", null, RiskTell.cardHint(v))
    }

    @Test
    fun `正例_R1仍按既有权限_要确认就弹卡`() {
        val root = tmp("r1-confirm")
        val table = tableWith("note_write" to ToolRule(RiskClass.R1, writePathKeys = setOf("path")))
        // 同一个动作、同一张表，只是工具自己声明了 CONFIRM ⇒ 仍弹卡（「R1 按现有策略」）
        val p = pipeline(root, table, false, {}, fake("note_write", ToolPermission.CONFIRM, "path"))
        val card = assertNeedsConfirm(run(root, call("note_write", "path" to "产物/a.md"), p))
        assertEquals("R1 没有判据 ⇒ 卡上不摆理由行", null, card.reasonLine)
        assertEquals("弹卡 ⇒ 一个字节都没执行", 0, ran.get())
    }

    @Test
    fun `正例_写工作区外_R2弹卡且原因=工作区外`() {
        val parent = tmp("outside-parent")
        val root = File(parent, "ws").apply { mkdirs() }
        val outsidePath = File(parent, "别处/a.md").absolutePath
        val p = pipeline(
            root,
            tableWith("note_write" to ToolRule(RiskClass.R1, writePathKeys = setOf("path"))),
            outside = true,
            log = {},
            specs = arrayOf(fake("note_write", ToolPermission.FREE, "path")),
        )
        val card = assertNeedsConfirm(run(root, call("note_write", "path" to outsidePath), p))
        assertEquals("目标在工作区外", card.reasonLine)
        assertEquals(0, ran.get())
    }

    @Test
    fun `正例_点结算_R3弹卡且不可自动`() {
        val root = tmp("r3-click")
        val table = tableWith("pay_click" to ToolRule(RiskClass.R2, clickLike = true))
        val c = call("pay_click", "target" to "结算")
        // 判定层先看档：R2 底档 ＋ 高危词 ⇒ R3
        assertEquals(RiskClass.R3, RiskGate.judge(c, table = table).risk)
        val p = pipeline(
            root,
            table,
            outside = false,
            log = {},
            specs = arrayOf(fake("pay_click", ToolPermission.FREE, "target")),
        )
        val card = assertNeedsConfirm(run(root, c, p))
        assertEquals("点击的是提交类按钮", card.reasonLine)
        assertEquals("R3 绝不能被自动放行", 0, ran.get())
    }

    @Test
    fun `正例_读文件_无卡无提示`() {
        val root = tmp("read")
        val p = pipeline(root)
        assertDone(run(root, call("read_file", "path" to "a.md"), p))
        assertEquals(0, ran.get()) // 真工具的 executor 不计数（用假工具的计数当探针）
        // R0 只读：矩阵不给任何判据 ⇒ 卡面那一行不摆
        assertEquals(0, RiskGate.tierOf(call("read_file", "path" to "a.md")))
    }

    @Test
    fun `正例_未分类工具_必问并记一条unclassified_tool`() {
        val root = tmp("ghost")
        val ledgerRoot = tmp("ghost-ledger")
        DecisionLedger.install(ledgerRoot)
        val p = pipeline(root, RiskTable.DEFAULT, false, {}, fake("ghost_tool", ToolPermission.FREE, "path"))
        val card = assertNeedsConfirm(run(root, call("ghost_tool", "path" to "a.md"), p))
        assertEquals("这个工具没有登记风险档", card.reasonLine)
        assertEquals(0, ran.get())
        assertTrue(
            "内存回读里要有工具名与矩阵版本：${RiskGate.unclassifiedSeen()}",
            RiskGate.unclassifiedSeen().contains("ghost_tool@${RiskTable.DEFAULT_VERSION}"),
        )
        val rec = DecisionLedger.read(ledgerRoot).single()
        assertEquals("unclassified_tool", rec.kind.wire)
        assertEquals(DecisionKind.RISK_UNCLASSIFIED, rec.kind)
        assertTrue("证据里要有工具名与矩阵版本：${rec.evidence}", rec.evidence.contains("ghost_tool"))
        assertTrue("证据里要有矩阵版本：${rec.evidence}", rec.evidence.contains("v${RiskTable.DEFAULT_VERSION}"))
        assertFalse("未分类不能算作可回滚", rec.reversible)
    }

    // ── 硬要求：矩阵改一行 ⇒ 判定结果变（打在闸上，不是打在矩阵上）──────

    @Test
    fun `对照_矩阵改一行_拦不拦立刻变`() {
        val root = tmp("flip")
        val c = call("note_write", "path" to "产物/a.md")
        val spec = arrayOf(fake("note_write", ToolPermission.FREE, "path"))

        // ① R1 行 ⇒ 直行
        val asR1 = pipeline(
            root,
            tableWith("note_write" to ToolRule(RiskClass.R1, writePathKeys = setOf("path"))),
            false, {}, *spec,
        )
        val first = run(root, c, asR1)

        // ② 只把这一行的 base 改成 R3（表里一个字段）⇒ 同一个动作变必问
        val asR3 = pipeline(
            root,
            tableWith("note_write" to ToolRule(RiskClass.R3, writePathKeys = setOf("path"))),
            false, {}, *spec,
        )
        val second = run(root, c, asR3)

        // ③ 改回来 ⇒ 又直行（证明变的是"表"，不是"这次调用的偶然"）
        val backToR1 = pipeline(
            root,
            tableWith("note_write" to ToolRule(RiskClass.R1, writePathKeys = setOf("path"))),
            false, {}, *spec,
        )
        val third = run(root, c, backToR1)

        assertTrue("R1 行应直行，实际 ${first::class.simpleName}", first is GuardPipeline.Result.Done)
        assertTrue("改成 R3 行必须弹卡，实际 ${second::class.simpleName}", second is GuardPipeline.Result.NeedsConfirm)
        assertTrue("改回 R1 应又直行，实际 ${third::class.simpleName}", third is GuardPipeline.Result.Done)
        assertNotEquals(
            "闸的结论必须跟着表走（否则 219 就是白做）",
            first::class, second::class,
        )
        // 只有两次直行真的执行了：第 ② 次（R3）一个字节都没动
        assertEquals(2, ran.get())
    }

    // ── 八条负例（每条对应派工单 §2 的一条）─────────────────────────────

    @Test
    fun `负例1_闸不许仍走旧判据_表改一行就必须变`() {
        // 上一支证"变了"；这一支证"不是被别的判据兜住了"：
        // 表把 write_file 判成 R3 时，**即使 permission=FREE** 也必须问——
        // 旧判据（只看 ToolPermission）在这里会直行，必红。
        val root = tmp("old-rule")
        val p = pipeline(
            root,
            tableWith("write_file" to ToolRule(RiskClass.R3, writePathKeys = setOf("path"))),
            false, {}, fake("write_file", ToolPermission.FREE, "path"),
        )
        val card = assertNeedsConfirm(run(root, call("write_file", "path" to "产物/a.md"), p))
        assertEquals("花钱、外发或账号类动作", card.reasonLine)
        assertEquals(0, ran.get())
    }

    @Test
    fun `负例2_第二套四档表必须已经删干净`() {
        // ① 类不存在（编译期符号已删 ⇒ 运行期也取不到）
        val missing = runCatching { Class.forName("com.example.zhengdao.baopu.ImpactRisk") }.isFailure
        assertTrue("ImpactRisk 还在类路径上：第二套四档没删", missing)
        // ② 源码里不许再有它的定义（注释提到名字可以，`object ImpactRisk` 不行）
        val line = File("src/main/java/com/example/zhengdao/baopu/ImpactLine.kt").readText()
        assertFalse("ImpactLine.kt 还定义着 ImpactRisk", line.contains("object ImpactRisk"))
        assertFalse("旧表的只读清单还在", line.contains("READ_ONLY"))
        assertFalse("旧表的破坏清单还在", line.contains("DESTRUCTIVE"))
        // ③ 档位只有一个出口：`RiskGate.tierOf` 转调矩阵，自己不带任何工具名表
        val gate = File("src/main/java/com/example/zhengdao/baopu/RiskGate.kt").readText()
        assertTrue("tierOf 必须转调矩阵", gate.contains("RiskMatrix.classify"))
        listOf("read_file", "delete_path", "browser_", "write_file").forEach { sym ->
            assertFalse("接线层不许自带工具名表（$sym）", gate.contains("\"$sym"))
        }
    }

    @Test
    fun `负例3_未分类不许直行_必须R2必问`() {
        val root = tmp("neg3")
        val c = call("totally_new_tool", "path" to "a.md")
        val verdict = RiskGate.judge(c, listOf(root.absolutePath))
        assertEquals("未分类的档必须是 R2", RiskClass.R2, verdict.risk)
        assertTrue("未分类必须 MUST_ASK", verdict.mustAsk)
        assertEquals("unclassified_tool", verdict.auditTag)
        val p = pipeline(root, RiskTable.DEFAULT, false, {}, fake("totally_new_tool", ToolPermission.FREE, "path"))
        assertNeedsConfirm(run(root, c, p))
        assertEquals(0, ran.get())
    }

    @Test
    fun `负例4_R3不能被自动放行_无卡执行`() {
        val root = tmp("neg4")
        val c = call("generate_image", "path" to "产物/x.png")
        assertEquals("generate_image 的底档必须是 R3", RiskClass.R3, RiskGate.judge(c).risk)
        val p = pipeline(root, RiskTable.DEFAULT, false, {}, fake("generate_image", ToolPermission.FREE, "path"))
        assertNeedsConfirm(run(root, c, p))
        assertEquals("R3 执行了就是负例", 0, ran.get())
    }

    @Test
    fun `负例5_硬拒项不许降级成问`() {
        val root = tmp("neg5")
        val c = call("type_secret", "target" to "input[type=password]")
        val p = pipeline(
            root,
            tableWith("type_secret" to ToolRule(RiskClass.R2, fieldKeys = setOf("target"))),
            false, {}, fake("type_secret", ToolPermission.FREE, "target"),
        )
        val r = run(root, c, p)
        assertTrue("密码框必须**拒绝**，不许降级成问：${r::class.simpleName}", r is GuardPipeline.Result.Rejected)
        val reason = (r as GuardPipeline.Result.Rejected).reason
        assertTrue("拒绝原文要带原因码：$reason", reason.contains("sensitive_field"))
        assertEquals(0, ran.get())
    }

    @Test
    fun `负例6_卡上原因码必有且只有一行`() {
        // ① 每一个非 NONE 的原因码都有人话，且塞得进那一行（30 字、无换行）
        RiskCode.entries.filter { it != RiskCode.NONE }.forEach { code ->
            val text = RiskTell.hint(code)
            assertTrue("$code 没有卡面人话", text != null && text.isNotBlank())
            text!!
            assertFalse("$code 的人话带换行", text.contains('\n') || text.contains('\r'))
            assertTrue("$code 的人话 ${text.length} 字，超一行", text.length <= ImpactLine.MAX_CHARS)
        }
        // ② 真的出卡时有值（R2/R3 两条路都验）
        val root = tmp("neg6")
        val p2 = pipeline(
            root,
            tableWith("del_thing" to ToolRule(RiskClass.R2, deleteLike = true)),
            false, {}, fake("del_thing", ToolPermission.FREE, "path"),
        )
        val card = assertNeedsConfirm(run(root, call("del_thing", "path" to "a.md"), p2))
        val line = card.reasonLine
        assertTrue("R2 出卡必须有原因行", line != null && line.isNotBlank())
        assertFalse("原因行必须单行", line!!.contains('\n'))
        assertTrue("原因行超 30 字：$line", line.length <= ImpactLine.MAX_CHARS)
        // ③ 合成的卡面行同样是单行、≤30 字（220 的位置一个，不是两行）
        val both = RiskTell.cardLine("将覆盖已有产物", "覆盖 3 个文件、涉 12 条已发台账")
        assertTrue("合成后超长：$both", both!!.length <= ImpactLine.MAX_CHARS)
        assertTrue("合成后的原因被丢了：$both", both.contains("将覆盖已有产物"))
        assertFalse(both.contains('\n'))
    }

    @Test
    fun `负例7_219的表内容一个字节都没动`() {
        // 本批只许"调用"219 的表：豁免集合、阈值都得是 219 留下的读数。
        // 版本号与行数是**当前账**的钉（219 原钉 1/52；228 B 片九个浏览器工具按
        // 「改一行表就 +1」的规矩进表 ⇒ 2/61；谁再动表，先对账再改这两行）。
        assertEquals("矩阵版本与表不配套（改表必 +1）", 3, RiskTable.DEFAULT_VERSION)
        assertEquals("表行数变了：先对账再改（63 个注册工具 = 62 行 ＋ bash 豁免）", 62, RiskTable.DEFAULT.tools.size)
        assertEquals("豁免集合变了", setOf("bash"), RiskTable.DEFAULT.exempt)
        assertEquals("批量阈值变了", 20, RiskTable.DEFAULT.maxTargetsPerCall)
        assertEquals("删除阈值变了", 10, RiskTable.DEFAULT.maxDeleteTargetsPerCall)
        // 判定层不许反向依赖接线层（矩阵里出现接线符号 = 表被人搬过去改了）
        val matrix = File("src/main/java/com/example/zhengdao/baopu/RiskMatrix.kt").readText()
        listOf("RiskGate", "RiskTell", "DecisionLedger").forEach { sym ->
            assertFalse("RiskMatrix.kt 里出现了 $sym（判定层被接线层污染）", matrix.contains(sym))
        }
    }

    @Test
    fun `负例8_没有引入新UI`() {
        // 四档芯片/额度/计划模式属 173；本批只借用 220 那一行的位置
        val card = File("src/main/java/com/example/zhengdao/ui/baopu/BaopuConfirmCard.kt").readText()
        // 只认**那一行**的 maxLines：文件里另一个 maxLines=1 是范围按钮的（与本批无关），
        // 断言整份文件"含 maxLines = 1"会被它冒充（门禁自己被门禁咬的教训）。
        val at = card.indexOf("text = impactLine")
        assertTrue("卡面那一行不见了", at >= 0)
        val seg = card.substring(at, minOf(card.length, at + 320))
        assertTrue("卡面那一行必须仍是单行省略：$seg", seg.contains("maxLines = 1"))
        listOf("RiskClass", "RiskVerdict", "RiskCode", "quota", "Quota").forEach { sym ->
            assertFalse("卡面出现了档位/额度符号（$sym）＝新 UI 入场", card.contains(sym))
        }
        val screen = File("src/main/java/com/example/zhengdao/ui/baopu/BaopuScreen.kt").readText()
        assertFalse("主屏出现了档位符号＝新 UI 入场", screen.contains("RiskClass"))
    }

    // ── 卡面那一行（220 的位置，一个位置放两件事）─────────────────────

    @Test
    fun `复用220位置_两件事合成一行`() {
        assertNull("都无 ⇒ 不摆", RiskTell.cardLine(null, null))
        assertEquals("只有理由 ⇒ 就是它", "目标在工作区外", RiskTell.cardLine("目标在工作区外", null))
        assertEquals("只有影响 ⇒ 就是它", "新建 2 个文件", RiskTell.cardLine(null, "新建 2 个文件"))
        val both = RiskTell.cardLine("将覆盖已有产物", "覆盖「报告.md」")
        assertEquals("都有且放得下 ⇒ 一行两件", "将覆盖已有产物｜覆盖「报告.md」", both)
        val long = RiskTell.cardLine("这个工具没有登记风险档", "覆盖 3 个文件、涉 12 条已发台账")
        assertEquals("放不下 ⇒ 保理由（它是这张卡存在的理由）", "这个工具没有登记风险档", long)
        assertTrue(long!!.length <= ImpactLine.MAX_CHARS)
    }

    @Test
    fun `硬拒的拒绝原文带原因码`() {
        val verdict = RiskGate.judge(call("type_secret", "target" to "密码"))
        // 这个工具不在默认表里 ⇒ 走的是未分类（R2 必问），不是硬拒——先证明这一点
        assertTrue(verdict.unclassified)
        val text = RiskTell.rejectText(verdict)
        assertTrue("拒绝原文要带机器键：$text", text.contains("unclassified_tool"))
        assertTrue(text.startsWith("动作被拒（"))
    }
}
