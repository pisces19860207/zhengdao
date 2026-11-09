// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第四百八十二批 · 目标模式的判据闸（JVM 直测，零 Android）。
//
// 每条用例对应派工单 §判据 的一条：
// 空判据拒立／可机械验证 ≥1 条／越界停问／超预算停报／军令状只一份（结构）／
// 账本可见（逐字对拍）／三态（PASS 才达成、判不了停待验收）。
package com.example.zhengdao.baopu.plan

import com.example.zhengdao.baopu.PlanRules
import com.example.zhengdao.baopu.evolution.trigger.GoalCloseout
import com.example.zhengdao.baopu.evolution.trigger.TriggerLedgerStore
import com.example.zhengdao.baopu.evolution.trigger.TriggerRow
import com.example.zhengdao.baopu.evolution.trigger.TriggerRowCodec
import com.example.zhengdao.baopu.evolution.trigger.TriggerType
import com.example.zhengdao.baopu.verify.Status
import com.example.zhengdao.ui.baopu.GovernanceCenterRules
import java.io.File
import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GoalModeTest {

    @Before
    fun setUp() {
        GoalBoard.resetForTest()
    }

    /** 板子是进程态：一条用例跑完必须还回去，否则下一条看到的是上一条的目标。 */
    @After
    fun tearDown() {
        GoalBoard.resetForTest()
    }

    // ── 小件 ────────────────────────────────────────────────────────────────

    private fun args(
        sentence: String = "把这批补丁做完并出报告",
        criteria: String = """[{"text":"报告在","kind":"file","path":"产物/报告.md"}]""",
        boundaries: String = "[]",
        budget: String = """{"maxSteps":5}""",
    ): JSONObject = JSONObject(
        """{"goal":{"sentence":"$sentence","criteria":$criteria,"boundaries":$boundaries,"budget":$budget}}""",
    )

    /** 一份立好的目标（样例与 [args] 同源：解析能过 ⇒ 后面判的都是真形状）。 */
    private fun goalOf(
        criteria: String = """[{"text":"报告在","kind":"file","path":"产物/报告.md"}]""",
        boundaries: String = "[]",
        budget: String = """{"maxSteps":5}""",
    ): GoalRules.Goal {
        val parsed = GoalRules.parse(
            args(criteria = criteria, boundaries = boundaries, budget = budget),
            hasPledge = true,
        )
        assertTrue("样例目标本身要立得起来：$parsed", parsed is GoalRules.GoalParse.Ok)
        val draft = (parsed as GoalRules.GoalParse.Ok).draft
        return GoalRules.Goal(
            sentence = draft.sentence,
            criteria = draft.criteria,
            boundaries = draft.boundaries,
            budget = draft.budget,
            state = GoalRules.GoalState.ACTIVE,
            pledgeId = "abc12345",
            total = 5,
            done = 0,
            stepsUsed = 0,
            atMs = 1_000L,
            sessionId = 7L,
        )
    }

    private fun errorOf(parse: GoalRules.GoalParse): String {
        assertTrue("该拒却过了：$parse", parse is GoalRules.GoalParse.Error)
        return (parse as GoalRules.GoalParse.Error).message
    }

    // ── 判据「空判据拒立」 ───────────────────────────────────────────────────

    @Test
    fun `空判据拒立_一条都没有`() {
        val msg = errorOf(GoalRules.parse(args(criteria = "[]"), hasPledge = true))
        assertTrue("拒因要说清是空判据：$msg", msg.contains("空判据"))
    }

    @Test
    fun `判据全是人判的也拒立_可机械验证至少要有一条`() {
        val msg = errorOf(
            GoalRules.parse(args(criteria = """[{"text":"用户点头","kind":"human"}]"""), hasPledge = true),
        )
        assertTrue("拒因要指向「机械验证」这一条：$msg", msg.contains("机械验证"))
    }

    @Test
    fun `文件类判据缺路径也拒立_路径只认工作区内相对路径`() {
        val msg = errorOf(
            GoalRules.parse(args(criteria = """[{"text":"报告在","kind":"file"}]"""), hasPledge = true),
        )
        assertTrue("拒因要说清 kind=file 要 path：$msg", msg.contains("path"))
        val outside = errorOf(
            GoalRules.parse(
                args(criteria = """[{"text":"报告在","kind":"file","path":"/sdcard/x.md"}]"""),
                hasPledge = true,
            ),
        )
        assertTrue("绝对路径不许：$outside", outside.contains("相对路径"))
    }

    // ── 判据「军令状只一份」 ─────────────────────────────────────────────────

    @Test
    fun `没军令状立不起目标`() {
        val msg = errorOf(GoalRules.parse(args(), hasPledge = false))
        assertTrue("拒因要说清先立状：$msg", msg.contains("军令状"))
    }

    @Test
    fun `目标本体没有清单字段_只有状的外键`() {
        val names = GoalRules.Goal::class.java.declaredFields
            .filterNot { it.isSynthetic || it.name.startsWith("\$") }
            .map { it.name }
            .toSet()
        listOf("items", "entries", "steps", "plan", "texts", "planItems").forEach {
            assertFalse("目标里不许有第二份清单（$it）——步骤数走军令状快照", it in names)
        }
        assertTrue("目标必须挂状（外键 pledgeId）：$names", "pledgeId" in names)
    }

    @Test
    fun `不带goal参数时本层沉默_今天的路径不变`() {
        assertEquals(GoalRules.GoalParse.Absent, GoalRules.parse(JSONObject(), hasPledge = true))
        // 没目标时每一件都放行（连预算都没有）
        assertNull("测试间不许留进程态", GoalBoard.active())
    }

    // ── 判据「越界停问」「超预算停报」 ───────────────────────────────────────

    @Test
    fun `越界停问_这一步不执行且原话要它去问人`() {
        val goal = goalOf(boundaries = """[{"text":"不许删文件","denyTools":["delete_path"]}]""")
        val check = GoalRules.stepCheck(goal, "delete_path", emptyList(), 1, 0L)
        assertTrue("越界要停：$check", check is GoalRules.StepCheck.Stop)
        val stop = check as GoalRules.StepCheck.Stop
        assertEquals(GoalRules.StopKind.BOUNDARY, stop.kind)
        assertTrue("停并问：原话要教它问用户——${stop.message}", stop.message.contains("问用户"))
    }

    @Test
    fun `越界也认路径前缀_不做清单点名的目录不许碰`() {
        val goal = goalOf(boundaries = """[{"text":"不许动方案","denyPaths":["方案/"]}]""")
        val check = GoalRules.stepCheck(goal, "write_file", listOf("方案/定稿.md"), 1, 0L)
        assertEquals(GoalRules.StopKind.BOUNDARY, (check as GoalRules.StepCheck.Stop).kind)
        // 边界外照走
        assertEquals(
            GoalRules.StepCheck.Pass,
            GoalRules.stepCheck(goal, "write_file", listOf("产物/报告.md"), 1, 0L),
        )
    }

    @Test
    fun `超预算停报_原话要它把现状报给用户`() {
        val goal = goalOf(budget = """{"maxSteps":3}""")
        val check = GoalRules.stepCheck(goal, "write_file", emptyList(), 4, 0L)
        assertTrue("超预算要停：$check", check is GoalRules.StepCheck.Stop)
        val stop = check as GoalRules.StepCheck.Stop
        assertEquals(GoalRules.StopKind.BUDGET, stop.kind)
        assertTrue("停并报：原话要它报数——${stop.message}", stop.message.contains("报给用户"))
        // 恰好用完（3/3）还不算超：第 4 步才停
        assertEquals(GoalRules.StepCheck.Pass, GoalRules.stepCheck(goal, "write_file", emptyList(), 3, 0L))
    }

    @Test
    fun `超时长也停_分钟预算同样算超`() {
        val goal = goalOf(budget = """{"maxSteps":50,"maxMinutes":10}""")
        val check = GoalRules.stepCheck(goal, "write_file", emptyList(), 2, 11 * 60_000L)
        assertEquals(GoalRules.StopKind.BUDGET, (check as GoalRules.StepCheck.Stop).kind)
    }

    @Test
    fun `停下来之后问人与报进度这两件永远放行`() {
        val goal = goalOf(budget = """{"maxSteps":1}""")
        listOf("ask_user", "update_plan").forEach { tool ->
            assertEquals(
                "预算耗尽了也必须能 $tool（否则「停并问/停并报」变成哑掉）",
                GoalRules.StepCheck.Pass,
                GoalRules.stepCheck(goal, tool, emptyList(), 99, 0L),
            )
        }
    }

    // ── 判据「PASS 才已达成 / 判不了停待验收」 ──────────────────────────────

    @Test
    fun `三态_PASS_才换到已达成`() {
        val goal = goalOf()
        val verdict = GoalRules.judge(goal) { true }
        assertEquals(Status.PASS, verdict.status)
        assertEquals(GoalRules.GoalState.ACHIEVED, GoalRules.stateOf(verdict.status))
        assertTrue("达成的那句要贴出来", GoalRules.settleNotice(goal, verdict).contains("已达成"))
    }

    @Test
    fun `三态_FAIL_判据没兑现时说未达成并指名哪条`() {
        val goal = goalOf()
        val verdict = GoalRules.judge(goal) { false }
        assertEquals(Status.FAIL, verdict.status)
        assertEquals(GoalRules.GoalState.FAILED, GoalRules.stateOf(verdict.status))
        assertTrue("要指名哪条没兑现：${verdict.reasons}", verdict.reasons.first().contains("产物/报告.md"))
    }

    @Test
    fun `三态_UNVERIFIABLE_有人判的判据就停在待验收`() {
        val goal = goalOf(
            criteria = """[{"text":"报告在","kind":"file","path":"产物/报告.md"},""" +
                """{"text":"用户看过说行","kind":"human"}]""",
        )
        val verdict = GoalRules.judge(goal) { true }
        assertEquals("机械那条过了也不许替人判那条下结论", Status.UNVERIFIABLE, verdict.status)
        assertEquals(GoalRules.GoalState.PENDING, GoalRules.stateOf(verdict.status))
        assertTrue(GoalRules.settleNotice(goal, verdict).contains("待验收"))
    }

    @Test
    fun `已达成之后本层不再拦任何一步`() {
        val goal = goalOf(boundaries = """[{"text":"不许删","denyTools":["delete_path"]}]""")
            .copy(state = GoalRules.GoalState.ACHIEVED)
        assertEquals(
            "收了尾就不该再挡人",
            GoalRules.StepCheck.Pass,
            GoalRules.stepCheck(goal, "delete_path", emptyList(), 99, 99_999_999L),
        )
    }

    // ── 判据「账本可见」 ─────────────────────────────────────────────────────

    @Test
    fun `账本那一行逐字_目标_进度_预算`() {
        val goal = goalOf().copy(done = 3, total = 5, stepsUsed = 2)
        assertEquals(
            "目标：把这批补丁做完并出报告 / 进度 3/5 步 / 预算用 40%",
            GoalRules.progressLine(goal),
        )
    }

    @Test
    fun `预算没有分母就说未设_不补成零`() {
        assertEquals("未设", GoalRules.percentText(0, 0))
        assertEquals("100%", GoalRules.percentText(4, 4))
    }

    @Test
    fun `治理中心只抄不算_与出口逐字相等`() {
        val line = "目标：把这批补丁做完并出报告 / 进度 3/5 步 / 预算用 40%"
        val root = JSONObject().put(
            "goal",
            JSONObject().put("line", line).put("stateCn", "进行中"),
        )
        val reading = GovernanceCenterRules.goal(root)
        assertEquals("屏幕上的字必须与出口逐字节相等", line, reading.value)
        assertEquals("进行中", reading.detail)
    }

    @Test
    fun `没有目标时治理中心说读不到并带原样原因`() {
        val root = JSONObject().put("goal", JSONObject().put("unknown", "这次会话还没有立目标"))
        val reading = GovernanceCenterRules.goal(root)
        assertEquals(GovernanceCenterRules.UNKNOWN, reading.value)
        assertEquals("这次会话还没有立目标", reading.detail)
    }

    // ── 落盘：`.goal.json` ──────────────────────────────────────────────────

    @Test
    fun `目标账是点号文件_写后读回逐字一致`() {
        val ws = Files.createTempDirectory("goal-482").toFile()
        try {
            val goal = goalOf().copy(done = 2, stepsUsed = 1)
            assertTrue("写盘要成", GoalStore.write(ws, goal))
            assertEquals("写什么读什么", goal, GoalStore.readGoal(ws))
            assertTrue(
                "点号开头 ⇒ 不计入「产物 N 件」（512 口径）：${GoalStore.FILE_NAME}",
                GoalStore.FILE_NAME.startsWith("."),
            )
            assertEquals(
                "落点必须是 <工作区>/产物/<点号文件>",
                File(File(ws, "产物"), GoalStore.FILE_NAME).absolutePath,
                GoalStore.fileOf(ws).absolutePath,
            )
        } finally {
            ws.deleteRecursively()
        }
    }

    @Test
    fun `坏账读回是没目标_不编一份`() {
        val ws = Files.createTempDirectory("goal-482-bad").toFile()
        try {
            val f = GoalStore.fileOf(ws)
            f.parentFile?.mkdirs()
            f.writeText("""{"v":1,"goal":{"sentence":"x"}}""", Charsets.UTF_8)
            assertEquals(null, GoalStore.readGoal(ws))
            f.writeText("不是 JSON", Charsets.UTF_8)
            assertEquals(null, GoalStore.readGoal(ws))
        } finally {
            ws.deleteRecursively()
        }
    }

    @Test
    fun `编码解往返一致_判据与不做清单都留得住`() {
        val goal = goalOf(
            criteria = """[{"text":"报告在","kind":"file","path":"产物/报告.md"},""" +
                """{"text":"用户看过说行","kind":"human"}]""",
            boundaries = """[{"text":"不许删文件","denyTools":["delete_path"],"denyPaths":["方案/"]}]""",
            budget = """{"maxSteps":8,"maxMinutes":30}""",
        )
        val back = GoalRules.decode(GoalRules.encode(goal))
        assertEquals(goal, back)
        assertNotEquals("id 得是真算出来的", "", goal.id)
    }

    // ── 立起来那一句（模型必须知道头上有个目标） ──────────────────────────────

    @Test
    fun `立目标的回灌要说清判据条数_预算_不做清单`() {
        val ack = GoalRules.frozenAck(goalOf(boundaries = """["不许动 347"]"""))
        assertTrue(ack, ack.contains("目标已立"))
        assertTrue(ack, ack.contains("预算 5 步"))
        assertTrue("不做清单条数要说出来：$ack", ack.contains("不做清单 1 条"))
    }

    @Test
    fun `每步提醒里有主线_进度_预算`() {
        val line = GoalRules.remindLine(goalOf().copy(done = 1, stepsUsed = 1))
        assertTrue(line, line.contains("主线：把这批补丁做完并出报告"))
        assertTrue(line, line.contains("进度 1/5 步"))
        assertTrue(line, line.contains("预算已用 20%"))
    }

    // ── 收口 ＋ 续跑（派工单 §做④「完成」与续跑那一半）──────────────────────

    /** 一份状（5 项）——目标挂在它上面，步骤数从这里来。 */
    private fun pledge(): PlanPledgeRules.PledgeSnapshot {
        val items = (1..5).map { PlanRules.PlanItem("第 $it 步", PlanRules.STATUS_TODO) }
        return PlanPledgeRules.snapshotOf(items, (1..5).map { "第 $it 步的验收方式" }, 1, "把这批补丁做完", 900L, 7L)
    }

    @Test
    fun `立目标挂在状上_步骤数从快照来不是目标自己写`() {
        val draft = (GoalRules.parse(args(budget = """{"maxSteps":6}"""), hasPledge = true)
            as GoalRules.GoalParse.Ok).draft
        val goal = GoalBoard.freeze(draft, pledge(), 1_000L, 7L)!!
        assertEquals("外键必须是状 id", pledge().id, goal.pledgeId)
        assertEquals("步骤总数＝状里的项数（快照给）", 5, goal.total)
        assertEquals("预算步数是目标自己声明的（与步骤数不是一个东西）", 6, goal.budget.maxSteps)
        assertEquals(GoalRules.GoalState.ACTIVE, goal.state)
    }

    @Test
    fun `没状时freeze返null_不编一份目标`() {
        val draft = (GoalRules.parse(args(), hasPledge = true) as GoalRules.GoalParse.Ok).draft
        assertNull("没军令状就立不起来", GoalBoard.freeze(draft, null, 1_000L, 7L))
        assertNull(GoalBoard.active())
    }

    @Test
    fun `收口_判据没兑现就说未达成_并写进账`() {
        val ws = Files.createTempDirectory("goal-482-settle").toFile()
        try {
            GoalBoard.attach(ws)
            val draft = (GoalRules.parse(args(), hasPledge = true) as GoalRules.GoalParse.Ok).draft
            GoalBoard.freeze(draft, pledge(), 1_000L, 7L)
            val outcome = GoalBoard.settle(3, 5, { false }, 2_000L)!!
            assertEquals(Status.FAIL, outcome.verdict.status)
            assertTrue("人话要说未达成：${outcome.notice}", outcome.notice.contains("未达成"))
            assertEquals("账上那份也要是未达成", GoalRules.GoalState.FAILED, GoalStore.readGoal(ws)!!.state)
            assertEquals("收口读数要落账", 3, GoalStore.readGoal(ws)!!.done)
        } finally {
            ws.deleteRecursively()
        }
    }

    @Test
    fun `续跑_进度与预算从盘上接回_不归零`() {
        val ws = Files.createTempDirectory("goal-482-resume").toFile()
        try {
            GoalBoard.attach(ws)
            val draft = (GoalRules.parse(args(), hasPledge = true) as GoalRules.GoalParse.Ok).draft
            GoalBoard.freeze(draft, pledge(), 1_000L, 7L)
            repeat(3) { GoalBoard.tickAndCheck(GoalBoard.active()!!, "write_file", emptyList(), 2_000L) }
            GoalBoard.noteProgress(2)
            assertEquals("预算用量先对得上", "60%", GoalRules.percentText(3, 5))

            // 进程重启：内存清空，只剩盘上那份
            GoalBoard.resetForTest()
            GoalBoard.attach(ws)
            val back = GoalBoard.active()!!
            assertEquals("进度要接回来", 2, back.done)
            assertEquals("已用步数要接回来（不然预算重新计）", 3, back.stepsUsed)
            assertEquals(GoalRules.GoalState.ACTIVE, back.state)
        } finally {
            ws.deleteRecursively()
        }
    }

    // ── 接线缝：并行段不许绕过同一道闸（482 自查抓到的真洞）────────────────────

    @Test
    fun `并行段也过同一道出卡前闸_换个写法绕不过去`() {
        val src = runEngineSource()
        val at = src.indexOf("private fun runParallelStage(")
        assertTrue("runParallelStage 不见了（引擎结构变了要回来重钉）", at >= 0)
        val body = src.substring(at, minOf(src.length, at + 4_000))
        assertTrue(
            "并行段必须调 preExecutionGate：只在 handleSerialCall 里判 ⇒ 模型一次发多个并行读" +
                "就能绕过越界与预算（482 修的就是这个洞）",
            body.contains("preExecutionGate(call)"),
        )
        assertEquals(
            "闸在并行段只许挂一处（两处＝同一个调用数两次预算）",
            1,
            Regex("""preExecutionGate\(""").findAll(body).count(),
        )
    }

    /** 引擎源码（源级门禁照 `GovernanceCenterGateTest` 的找法：三候选路径）。 */
    private fun runEngineSource(): String {
        val suffix = "src/main/java/com/example/zhengdao/baopu/agent/RunEngine.kt"
        val candidates = listOf(suffix, "../app/$suffix", "app/$suffix")
        return candidates.map(::File).firstOrNull(File::isFile)?.readText(Charsets.UTF_8)
            ?: throw AssertionError("找不到 RunEngine.kt（工作目录 ${File(".").absolutePath}）")
    }

    // ── 收口那条链：每条收尾路都要结、且一轮只结一次（482 ④ 的后半）────────────

    @Test
    fun `一轮只结一次_结过再结返null且不重写账`() {
        val ws = Files.createTempDirectory("goal-482-once").toFile()
        try {
            GoalBoard.attach(ws)
            val draft = (GoalRules.parse(args(), hasPledge = true) as GoalRules.GoalParse.Ok).draft
            GoalBoard.freeze(draft, pledge(), 1_000L, 7L)
            val first = GoalBoard.settle(5, 5, { true }, 2_000L)!!
            assertEquals(GoalRules.GoalState.ACHIEVED, first.goal.state)
            val firstAt = GoalStore.readGoal(ws)!!.atMs
            // 正常完成那条路先结过，收尾函数（中止/停止/熔断那条）还会再调一次
            assertNull("已经结过就不许再结（否则同一轮写两次账、贴两遍话）", GoalBoard.settle(5, 5, { true }, 9_999L))
            assertEquals("账上的时刻不许被第二次调改写", firstAt, GoalStore.readGoal(ws)!!.atMs)
        } finally {
            ws.deleteRecursively()
        }
    }

    @Test
    fun `被打断的收尾记已停下_不写成未达成`() {
        val ws = Files.createTempDirectory("goal-482-stop").toFile()
        try {
            GoalBoard.attach(ws)
            val draft = (GoalRules.parse(args(), hasPledge = true) as GoalRules.GoalParse.Ok).draft
            GoalBoard.freeze(draft, pledge(), 1_000L, 7L)
            val outcome = GoalBoard.settle(2, 5, { false }, 2_000L, stopped = true)!!
            assertEquals("中止不是「判了不过」，是「没跑完」", GoalRules.GoalState.STOPPED, outcome.goal.state)
            assertTrue("话要说是停下：${outcome.notice}", outcome.notice.contains("已停下"))
            assertFalse("也不许冒充达成：${outcome.notice}", outcome.notice.contains("已达成"))
            assertEquals(GoalRules.GoalState.STOPPED, GoalStore.readGoal(ws)!!.state)
        } finally {
            ws.deleteRecursively()
        }
    }

    @Test
    fun `没跑完但判据全兑现时_停下也说判据全兑现`() {
        val goal = goalOf()
        val text = GoalRules.stoppedNotice(goal, GoalRules.judge(goal) { true })
        assertTrue(text, text.contains("已停下"))
        assertTrue(text, text.contains("判据全兑现"))
    }

    // ── 467 合入后的顺序回归（用户令：租约先、目标后）──────────────────────────

    @Test
    fun `落库顺序_契约与taskId回填在目标落板之前`() {
        val src = planControllerSource()
        val taskId = src.indexOf("TaskGrantWire.contractDigest")
        val goalLanding = src.indexOf("goalLandingNote(goalParse.draft")
        assertTrue("契约那段或目标落板那一段不见了：$taskId/$goalLanding", taskId >= 0 && goalLanding >= 0)
        assertTrue(
            "顺序必须是「租约/契约（taskId 回填）先、目标落板后」——目标挂在状上，" +
                "状没冻结就立不起来；把目标挪到契约之前会让它拿到一份还没入效的状",
            taskId < goalLanding,
        )
    }

    /** `PlanController` 源码（同 [runEngineSource] 的找法）。 */
    private fun planControllerSource(): String {
        val suffix = "src/main/java/com/example/zhengdao/baopu/agent/PlanController.kt"
        val candidates = listOf(suffix, "../app/$suffix", "app/$suffix")
        return candidates.map(::File).firstOrNull(File::isFile)?.readText(Charsets.UTF_8)
            ?: throw AssertionError("找不到 PlanController.kt（工作目录 ${File(".").absolutePath}）")
    }

    // ── 前置②：收口事实按既有形状交出去（只交不收）────────────────────────────

    /** 两条判据：一条文件类（没兑现）＋ 一条人判（待判）——失败行的字段最全。 */
    private fun twoCriteria(): String =
        """[{"text":"报告在","kind":"file","path":"产物/报告.md"},""" +
            """{"text":"用户看过说行","kind":"human"}]"""

    /** 立一份目标并收口一次，返回（事实，账文件）。 */
    private fun settledFact(dirName: String, exists: Boolean = false): Pair<GoalRules.CloseoutFact, File> {
        val ws = Files.createTempDirectory(dirName).toFile()
        GoalBoard.resetForTest()
        GoalBoard.attach(ws)
        val draft = (GoalRules.parse(args(criteria = twoCriteria()), hasPledge = true)
            as GoalRules.GoalParse.Ok).draft
        GoalBoard.freeze(draft, pledge(), 1_000L, 7L)
        val outcome = GoalBoard.settle(2, 5, { exists }, 2_000L)!!
        val fact = GoalRules.closeoutFact(outcome.goal, outcome.verdict, 2_000L)
        return fact to File(ws, "trigger-ledger.jsonl")
    }

    @Test
    fun `假读取器能解析收口那行_按495文档的键名逐格取值`() {
        val (fact, ledger) = settledFact("goal-482-fake")
        try {
            assertTrue(GoalCloseout.append(ledger, fact))
            // 「假读取器」：只认 495 文档写明的键名，不 import 任何生产解析器
            val line = ledger.readLines(Charsets.UTF_8).single { it.isNotBlank() }
            val o = JSONObject(line)
            assertEquals("at 必在", 2_000L, o.getLong("at"))
            assertEquals("type 只用既有 kind", "manual", o.getString("type"))
            assertFalse("这行不是评测触发（不许替 495 花限频额度）", o.getBoolean("triggered"))
            assertTrue("ref 指回目标", o.getString("ref").startsWith("goal:"))
            assertTrue("intent 里要有预算用量", o.getString("intent").contains("预算"))
            assertTrue("抑制原因必须写明", o.getString("suppressReason").isNotBlank())
            assertEquals("逐条结果一条一行", 2, o.getJSONArray("evalFailedCases").length())
            assertFalse("不许给 passRate（给了就会被 495 的聚合吃掉）", o.has("evalPassRate"))
        } finally {
            ledger.parentFile?.deleteRecursively()
        }
    }

    @Test
    fun `既有读取器原样读得回来_字段与事实逐项相等`() {
        val (fact, ledger) = settledFact("goal-482-real")
        try {
            assertTrue(GoalCloseout.append(ledger, fact))
            // ★ 真正的读取器（495 那一侧，本批一个字没改）
            val row = TriggerLedgerStore(ledger).read().single()
            assertEquals(fact.atMs, row.atMs)
            assertEquals("goal:" + fact.goalId, row.ref)
            assertEquals(fact.ran, row.evalRan)
            assertEquals(fact.passed, row.evalPassed)
            assertEquals(fact.total, row.evalTotal)
            assertNull("passRate 必须留空（495 只认真触发行）", row.evalPassRate)
            assertEquals(fact.cases, row.evalFailedCases)
            assertEquals(GoalCloseout.NOT_A_TRIGGER, row.suppressReason)
        } finally {
            ledger.parentFile?.deleteRecursively()
        }
    }

    @Test
    fun `失败的Goal也写行_字段齐全不缩水`() {
        val goal = goalOf(criteria = twoCriteria())
        val verdict = GoalRules.judge(goal) { false }
        assertEquals("样例必须是失败行", Status.FAIL, verdict.status)
        val fact = GoalRules.closeoutFact(goal, verdict, 3_000L)
        assertEquals("未达成", fact.stateCn)
        assertEquals("验了几条＝判据条数", 2, fact.ran)
        assertEquals("过了几条", 0, fact.passed)
        assertEquals(2, fact.total)
        assertEquals("逐条都在", 2, fact.cases.size)
        assertTrue("逐条要标结果：" + fact.cases, fact.cases.any { it.startsWith("未兑现|") })
        assertTrue("人判那条标待判：" + fact.cases, fact.cases.any { it.startsWith("待判|") })
        assertEquals(
            "预算用量照算",
            "40%",
            GoalRules.closeoutFact(goal.copy(stepsUsed = 2), verdict, 3_000L).budgetPct,
        )
        assertTrue(fact.sentence.isNotBlank())
        assertTrue(fact.goalId.isNotBlank())
    }

    @Test
    fun `不新增kind与文件格式_对拍既有闭集与字段集`() {
        // kind：触发类型仍是四个（新增一个就会在这里红）
        assertEquals(
            listOf("run_failed", "user_correction", "skill_rate", "manual"),
            TriggerType.entries.map { it.wire },
        )
        // 字段：TriggerRow 仍是 13 个（524 的守卫同口径，这里只核对没被本批撑大）
        val fields = TriggerRow::class.java.declaredFields
            .filterNot { it.isSynthetic || it.name.startsWith("$") }
        assertEquals("TriggerRow 字段集不许变", 13, fields.size)
        // 编码键：全部落在既有键表里（没有新键）
        val legacy = setOf(
            "at", "type", "triggered", "ref", "intent", "evalRan", "evalPassed", "evalTotal",
            "evalPassRate", "evalFailedCases", "evalError", "route", "suppressReason",
        )
        val sample = goalOf()
        val encoded = TriggerRowCodec.encode(
            GoalCloseout.row(GoalRules.closeoutFact(sample, GoalRules.judge(sample) { true }, 1L)),
        ).keys().asSequence().toSet()
        assertTrue("编码出既有键表以外的键：" + (encoded - legacy), legacy.containsAll(encoded))
    }

    @Test
    fun `模板候选只产值_不带任何启用或灰度闸`() {
        val candidate = GoalRules.templateCandidate(goalOf())
        assertTrue(candidate.name.startsWith("目标模板："))
        assertTrue(candidate.scenario.isNotBlank())
        assertEquals("判据写法逐条抄形状", 1, candidate.criteriaPattern.size)
        assertTrue(candidate.criteriaPattern.single().startsWith("file:"))
        assertEquals(goalOf().id, candidate.sourceGoalId)
    }

    @Test
    fun `源码里没有自动启用或灰度_目标这一侧只产候选`() {
        val rels = listOf(
            "src/main/java/com/example/zhengdao/baopu/plan/GoalMode.kt",
            "src/main/java/com/example/zhengdao/baopu/evolution/trigger/GoalCloseout.kt",
        )
        // 先剥块注释（KDoc 里会正当地提到「灰度」这两个字），再剥行注释——与仓里既有源级门禁用同一法
        val stripped = rels.joinToString("\n") { rel ->
            sourceOf(rel)
                .replace(Regex("""/\*[\s\S]*?\*/"""), " ")
                .split("\n").joinToString("\n") { it.substringBefore("//") }
        }
        listOf("自动启用", "autoEnable", "autoApply", "canary", "灰度开关", "enabledByDefault")
            .forEach { word -> assertFalse("目标侧不许出现「" + word + "」", stripped.contains(word)) }
    }

    /** 源文件（同 [runEngineSource] 的找法）。 */
    private fun sourceOf(rel: String): String {
        val candidates = listOf(rel, "../app/" + rel, "app/" + rel)
        return candidates.map(::File).firstOrNull(File::isFile)?.readText(Charsets.UTF_8)
            ?: throw AssertionError("找不到 " + rel)
    }
}
