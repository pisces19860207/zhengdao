// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百七十六批 P1：接线层（`TaskGrantWire`）在**真盘**上的行为——判据②③与负例 N1／N2／N3
// 都打在这里，源级门禁（`TaskGrantWiringGateTest`）只能证明"线接上了"，证明不了"判得对"。
//
// ## 为什么这一份要真文件
// 249 那套六步是纯函数，它的夹具（`AuthzFixtures`）直接摆对象快照。本批新增的是**构造层**：
// 对象清单要从契约展开、状态要从盘上 stat、三元组要从真快照重算——这三件事在假数据上
// 永远绿，接到真盘上才有「改一个参数用旧授权」这种红。所以本文件全程用临时目录，
// 一个 `File` 都不假装。
//
// ## 三条裁定的落点（2026-10-29 用户逐条）
// · Q3：文件进清单／目录只列一层／列不出来或超 10 个 ⇒ **整份不生成**（照旧逐次问）；
// · Q5：任务身份＝这次批准的那份计划（契约规范化摘要），**不含**时刻与步骤勾选；
// · Q2：撤销只按三元组定位，摘要错一格就撤不掉。
package com.example.zhengdao.baopu.authz

import com.example.zhengdao.baopu.DecisionLedger
import com.example.zhengdao.baopu.RiskClass
import com.example.zhengdao.baopu.RiskDecision
import com.example.zhengdao.baopu.RiskVerdict
import com.example.zhengdao.baopu.agent.ToolCall
import com.example.zhengdao.baopu.inspect.ShadowVerdict
import com.example.zhengdao.baopu.plan.PlanContract
import com.example.zhengdao.baopu.plan.PlanStep
import java.io.File
import java.nio.file.Files
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TaskGrantWireBehaviorTest {

    /** 走注入接口调（`TaskGrantWire` 那个 override 按 Kotlin 规矩不许再带默认值）。 */
    private val wiring: GrantWiring = TaskGrantWire

    private lateinit var ws: File
    private lateinit var ledgerRoot: File
    private val epoch get() = ProcessEpoch.current

    @Before
    fun setup() {
        ws = Files.createTempDirectory("baopu-wire276-ws").toFile()
        ledgerRoot = Files.createTempDirectory("baopu-wire276-ledger").toFile()
        DecisionLedger.install(ledgerRoot)
        TaskGrantRuntime.resetForTest()
        TaskGrantBoard.resetForTest()
    }

    @After
    fun teardown() {
        TaskGrantRuntime.resetForTest()
        TaskGrantBoard.resetForTest()
        DecisionLedger.uninstallForTest()
        listOf(ws, ledgerRoot).forEach { it.deleteRecursively() }
    }

    // ── 夹具 ────────────────────────────────────────────────────────────────

    private fun touch(rel: String, text: String = "初稿"): File =
        File(ws, rel).also { f ->
            f.parentFile?.mkdirs()
            f.writeText(text)
        }

    private fun contract(touches: List<String>, goal: String = "整理稿子"): PlanContract = PlanContract(
        goal = goal,
        steps = listOf(PlanStep(1, "写第一稿", touches.take(1), RiskClass.R2)),
        touches = touches,
        readonly = emptyList(),
        expectedAsks = 0,
        stopIf = emptyList(),
    )

    private fun verdict2(): RiskVerdict = RiskVerdict(risk = RiskClass.R2, decision = RiskDecision.PROCEED)

    private fun call(opId: String, rel: String, content: String = "新稿子"): ToolCall =
        ToolCall(opId, "write_file", JSONObject().put("path", rel).put("content", content))

    private fun decide(
        call: ToolCall,
        targets: List<File>,
        now: Long,
        isWrite: Boolean = true,
        shadow: ShadowSignal = ShadowSignal.None,
        verdict: RiskVerdict? = verdict2(),
    ): WiringResult = wiring.decide(
        call = call,
        targets = targets,
        verdict = verdict,
        root = ws,
        isWrite = isWrite,
        nowMonoMs = now,
        epoch = epoch,
        shadow = shadow,
    )

    /** 真开一单（与生产同一条路：候选 → 208 记批准 → 装快照 → 板认单）。 */
    private fun open(touches: List<String>, goal: String = "整理稿子"): GrantOffer {
        val now = TaskGrantWire.monoMs()
        val offer = TaskGrantWire.buildOffer(contract(touches, goal), ws, 7L, epoch, now, System.currentTimeMillis())
            .first ?: throw AssertionError("这一轮没生成候选单，开不了")
        val r = TaskGrantWire.openGrant(offer, defaultGrantSelection(offer.facts), ws, epoch, now, System.currentTimeMillis())
        assertTrue("开单没成：${r.reason}", r.installed)
        return TaskGrantBoard.currentOffer()!!
    }

    // ── Q3：对象清单的窄口径 ────────────────────────────────────────────────

    @Test
    fun 文件进清单目录只列一层() {
        touch("docs/single.md")
        touch("notes/a.md")
        touch("notes/b.md")
        touch("notes/sub/deep.md") // 第二层：明确不进清单
        val (objects, refusal) = TaskGrantWire.objectsOf(contract(listOf("notes", "docs/single.md")), ws)
        assertNull("这一份该生成：$refusal", refusal)
        assertEquals(
            "目录只列一层（sub/ 里的东西不算对象）",
            listOf("docs/single.md", "notes/a.md", "notes/b.md"),
            objects.map { it.objectId }.sorted(),
        )
        assertTrue("已存在的文件快照必须是 Present（六步第①步拿它比现状）", objects.all { it.state is ObjectState.Present })
    }

    @Test
    fun 光一个文件名会被判成主机面_所以整份不生成() {
        // 242 的 `TouchFace.of`：没有分隔符但带点的写法按**主机名**判（`notes.txt` 那种
        // 才走路径兜底）。本批不改分类器（红线），所以这里钉的是**现状**：
        // 主机面不进对象清单 ⇒ 路径面为空 ⇒ NO_PATH_FACE ⇒ 整份不生成、照旧逐次问。
        // 方向是安全的：漏掉一个对象只会让六步第①步判 object_out 再问一次，不会多授权。
        // 这条写进报告「遗留」：计划里裸写文件名（`改 README.md`）拿不到任务级授权。
        touch("single.md")
        val (objects, refusal) = TaskGrantWire.objectsOf(contract(listOf("single.md")), ws)
        assertEquals(WireRefusal.NO_PATH_FACE, refusal)
        assertTrue("一个对象都没列出来：${objects.map { it.objectId }}", objects.isEmpty())
    }

    @Test
    fun 目录里一个文件都没有也不生成() {
        // EMPTY_OBJECTS 那一格：声明的是目录、目录在盘上、但一层里全是子目录 ⇒ 列不出可点对象。
        File(ws, "onlydirs/sub").mkdirs()
        val (objects, refusal) = TaskGrantWire.objectsOf(contract(listOf("onlydirs")), ws)
        assertEquals(WireRefusal.EMPTY_OBJECTS, refusal)
        assertTrue("拒了就必须交回空清单", objects.isEmpty())
    }

    @Test
    fun 列不出来就不生成整份授权() {
        touch("notes/a.md")
        // 声明了一个盘上没有的路径：既不是文件也不是目录 ⇒ 说不好，不是"这格没东西"。
        val (objects, refusal) = TaskGrantWire.objectsOf(contract(listOf("notes", "没了/")), ws)
        assertEquals(WireRefusal.NOT_LISTABLE, refusal)
        assertTrue("拒了就必须交回空清单", objects.isEmpty())
    }

    @Test
    fun 超过十个对象不生成() {
        val names = (1..11).map { "notes/f$it.md" }
        names.forEach { touch(it) }
        val (objects, refusal) = TaskGrantWire.objectsOf(contract(listOf("notes")), ws)
        assertEquals(WireRefusal.TOO_MANY_OBJECTS, refusal)
        assertEquals("清单本身还是如实算出来的（报告要能说出为什么不开单）", 11, objects.size)
    }

    @Test
    fun 单文件超上限整份不生成() {
        touch("notes/a.md")
        val big = File(ws, "notes/big.md")
        big.writeBytes(ByteArray((GrantLimits.MAX_BYTES_PER_FILE + 1).toInt()))
        val (objects, refusal) = TaskGrantWire.objectsOf(contract(listOf("notes")), ws)
        assertEquals(WireRefusal.FILE_OVER_LIMIT, refusal)
        assertTrue("整份不生成不是跳过那一格", objects.isEmpty())
    }

    @Test
    fun 声明里没有文件路径就不生成() {
        touch("notes/a.md")
        val (objects, refusal) = TaskGrantWire.objectsOf(contract(listOf("example.com")), ws)
        assertEquals("主机名声明盖不住文件对象", WireRefusal.NO_PATH_FACE, refusal)
        assertTrue(objects.isEmpty())
    }

    @Test
    fun buildOffer把拒绝原样带出来() {
        val r = TaskGrantWire.buildOffer(null, ws, 7L, epoch, TaskGrantWire.monoMs(), 0L)
        assertEquals(WireRefusal.NO_CONTRACT, r.second)
        assertNull(r.first)
        touch("notes/a.md")
        val noSession = TaskGrantWire.buildOffer(contract(listOf("notes")), ws, 0L, epoch, TaskGrantWire.monoMs(), 0L)
        assertEquals(WireRefusal.NO_SESSION, noSession.second)
    }

    // ── Q5：任务身份是那份计划，不是进度 ────────────────────────────────────

    @Test
    fun 任务身份只随计划内容变() {
        val a = contract(listOf("notes/a.md"))
        val b = contract(listOf("notes/a.md"))
        assertEquals("同一份计划永远同一个身份", TaskGrantWire.contractDigest(a), TaskGrantWire.contractDigest(b))
        assertTrue(
            "目标变了＝任务变了",
            TaskGrantWire.contractDigest(a) != TaskGrantWire.contractDigest(contract(listOf("notes/b.md"))),
        )
        assertTrue(
            "要办的事变了＝任务变了",
            TaskGrantWire.contractDigest(a) != TaskGrantWire.contractDigest(contract(listOf("notes/a.md"), goal = "换一件事")),
        )
        touch("notes/a.md")
        val offer = TaskGrantWire.buildOffer(a, ws, 7L, epoch, TaskGrantWire.monoMs(), 0L).first!!
        assertEquals("快照的 taskId 就是这个身份", TaskGrantWire.contractDigest(a), offer.grant.taskId)
        assertEquals("契约哈希同一来源", TaskGrantWire.contractDigest(a), offer.grant.contractHash)
    }

    @Test
    fun 预估上限压到R2() {
        val r3 = PlanContract(
            goal = "含高风险步骤",
            steps = listOf(PlanStep(1, "删一堆", listOf("notes"), RiskClass.R3)),
            touches = listOf("notes"),
            readonly = emptyList(),
            expectedAsks = 0,
            stopIf = emptyList(),
        )
        assertEquals("R3 的计划也只做得到 R2 以内的授权", GrantLimits.MAX_RISK_ORDINAL, TaskGrantWire.maxRiskOf(r3))
    }

    // ── 判据②／负例 N2：改一个参数用旧授权 ⇒ 必拒 ────────────────────────────

    @Test
    fun 纯判定三份各自不符都拒() {
        val b = ApprovalBinding("create_text,edit_text", "a".repeat(16), "b".repeat(16))
        assertTrue(ApprovalBindings.executeAllowed(b, b).first)
        assertEquals(ApprovalBindings.CODE_MATCH, ApprovalBindings.executeAllowed(b, b).second)
        assertFalse(ApprovalBindings.executeAllowed(null, b).first)
        assertTrue(ApprovalBindings.executeAllowed(b, b.copy(actionId = "create_text")).second.contains("actionId 不符"))
        assertTrue(ApprovalBindings.executeAllowed(b, b.copy(argsHash = "c".repeat(16))).second.contains("argsHash 不符"))
        assertTrue(ApprovalBindings.executeAllowed(b, b.copy(objectsDigest = "d".repeat(16))).second.contains("对象清单摘要不符"))
        // 撤销闸没有"没有绑定"这一格：没有授权可比＝没这一单。
        val revoked = ApprovalBindings.revokeAllowed(b, b)
        assertTrue(revoked.first)
        assertEquals(ApprovalBindings.CODE_MATCH, revoked.second)
        assertFalse(ApprovalBindings.revokeAllowed(b, b.copy(argsHash = "z".repeat(16))).first)
    }

    @Test
    fun N2_改了参数的旧授权直接视为未授权() {
        touch("notes/a.md")
        open(listOf("notes"))
        val now = TaskGrantWire.monoMs()
        val granted = TaskGrantRuntime.current()!!.grant
        // 「改一个参数」＝限额被改过（六步第①步②步都还成立，正是 L26 要独吞的那一格）。
        val tampered = granted.copy(budget = GrantBudget(maxBytesPerFile = 4096L))
        assertTrue(
            "被改过的快照本身仍要装得进来（否则这条负例测的是校验器不是绑定）",
            TaskGrantRuntime.install(tampered, epoch, now) is InstallResult.Installed,
        )
        val r = decide(call("op-n2", "notes/a.md"), listOf(File(ws, "notes/a.md")), now)
        assertEquals(WiringDisposition.UNAUTHORIZED, r.disposition)
        assertNull("三元组先拦下 ⇒ 六步一次都没进", r.decision)
        assertTrue("要说不清是哪一份不符：${r.note}", r.note.contains("argsHash 不符"))
        val reading = TaskGrantBoard.reading()
        assertNotNull("拦下也是事实，读数必须落板", reading)
        assertEquals(ApprovalBindings.CODE_MISMATCH, reading!!.code)
        assertEquals("没进六步就只有一条读数", 1, reading.steps.size)
        assertNull("拦下的动作不许留下待落账的那一格", TaskGrantBoard.pendingFor("op-n2"))
    }

    @Test
    fun 没有绑定就没有免卡这一说() {
        assertEquals(false, ApprovalBindings.executeAllowed(null, ApprovalBinding("a", "b", "c")).first)
        assertTrue(ApprovalBindings.executeAllowed(null, ApprovalBinding("a", "b", "c")).second.contains("先开单"))
    }

    // ── 负例 N1：对象换出清单 ⇒ object_out（且只问增量） ─────────────────────

    @Test
    fun N1_清单外的对象走只问增量() {
        touch("notes/a.md")
        open(listOf("notes"))
        val now = TaskGrantWire.monoMs()
        val r = decide(call("op-n1", "notes/别的.md"), listOf(File(ws, "notes/别的.md")), now)
        assertEquals(WiringDisposition.UNAUTHORIZED, r.disposition)
        assertEquals(TaskGrantGate.CODE_OBJECT_OUT, r.decision!!.code)
        assertEquals(GateOutcome.ASK_DELTA, r.decision!!.outcome)
        assertEquals("只问这一次要的那一个", listOf("notes/别的.md"), r.decision!!.delta!!.deltaObjects)
        assertTrue("动作本来就在授权里 ⇒ 增量动作是空的（不许重问整单）", r.decision!!.delta!!.deltaActions.isEmpty())
        assertEquals("拦下的读数落在板上", TaskGrantGate.CODE_OBJECT_OUT, TaskGrantBoard.reading()!!.code)
    }

    // ── 判据③／负例 N3：撤销按三元组，错摘要撤不掉 ──────────────────────────

    @Test
    fun N3_撤销必须三元组相符才生效() {
        touch("notes/a.md")
        val offer = open(listOf("notes"))
        val now = TaskGrantWire.monoMs()
        val wrong = offer.binding.copy(objectsDigest = "0".repeat(ApprovalBindings.DIGEST_LEN))
        val bad = TaskGrantWire.revoke(wrong, now)
        assertFalse("摘要不符＝撤不掉", bad.first)
        assertTrue(bad.second.contains("不许按文本匹配撤销"))
        assertNotNull("撤没撤掉都不许把还在生效的授权顺手关掉", TaskGrantRuntime.active(now))

        assertTrue(TaskGrantBoard.revokeBrief().isNotEmpty())

        val badAction = TaskGrantWire.revoke(offer.binding.copy(actionId = "create_text"), now)
        assertFalse("动作集换了也不行", badAction.first)

        val ok = TaskGrantWire.revoke(offer.binding, now)
        assertTrue(ok.first)
        assertNull(TaskGrantRuntime.active(now))
        assertEquals(GrantEndReason.USER_REVOKED, TaskGrantRuntime.current()!!.endReason)
        assertEquals("撤成之后板上不该再留着这一单", "", TaskGrantBoard.openedGrantId())
        assertNull(TaskGrantBoard.bindingOfOpened())
        assertEquals("", TaskGrantBoard.revokeBrief())
    }

    @Test
    fun 没有开着的单时撤销给的是没这一单() {
        val r = TaskGrantWire.revoke(ApprovalBinding("a", "b", "c"), TaskGrantWire.monoMs())
        assertFalse(r.first)
        assertEquals("没有开着的单可撤", r.second)
    }

    @Test
    fun 对象清单摘要与顺序无关() {
        val one = ApprovalBindings.objectsDigestOf(listOf("a.md", "b.md"))
        val two = ApprovalBindings.objectsDigestOf(listOf("b.md", "a.md"))
        assertEquals("卡上顺序不参与判定", one, two)
        assertTrue(one != ApprovalBindings.objectsDigestOf(listOf("a.md", "c.md")))
        assertEquals(ApprovalBindings.DIGEST_LEN, one.length)
        assertEquals("空清单也有确定值（不许拿空串当摘要）", 16, ApprovalBindings.objectsDigestOf(emptyList()).length)
    }

    // ── P1 的口径：第⑤步恒没影子 ⇒ 停下，但一张卡都不省 ──────────────────────

    @Test
    fun P1到得了第五步但停在NO_DATA() {
        touch("notes/a.md")
        open(listOf("notes"))
        val now = TaskGrantWire.monoMs()
        val r = decide(call("op-p1", "notes/a.md"), listOf(File(ws, "notes/a.md")), now)
        assertEquals(WiringDisposition.STOPPED, r.disposition)
        assertEquals("生产这一格恒没影子", TaskGrantGate.CODE_NO_DATA, r.decision!!.code)
        assertEquals(GateOutcome.STOPPED, r.decision!!.outcome)
        assertNull("停下＝不免卡，也不留待落账的那一格", TaskGrantBoard.pendingFor("op-p1"))
        assertFalse(TaskGrantBoard.isAccounted("op-p1"))
        val line = TaskGrantBoard.readingLine()!!
        assertTrue("报告要原样打的那句读数：$line", line.contains("六步 停下（no_data）"))
        assertTrue(line.contains("三元组相符"))
        // 前四步都真的过了（只有第⑤步停在影子）——「未接线」这句话的证据就在这四格里。
        assertEquals(
            listOf("对象=过", "动作=过", "上限=过", "protected/hardDeny=过", "影子三态=未验"),
            r.decision!!.steps.map { it.line().substringBefore("（") },
        )
    }

    @Test
    fun 注入CONSISTENT影子后放行分支是活的() {
        // 负例 N6 的接线层那一半：ALLOW 不是死代码——影子一给，六步就真放行。
        touch("notes/a.md")
        open(listOf("notes"))
        val now = TaskGrantWire.monoMs()
        val r = decide(
            call("op-n6", "notes/a.md"),
            listOf(File(ws, "notes/a.md")),
            now,
            shadow = ShadowSignal.Rehearsed(ShadowVerdict.CONSISTENT),
        )
        assertEquals(WiringDisposition.ALLOW, r.disposition)
        assertEquals(TaskGrantGate.CODE_ALLOW, r.decision!!.code)
        assertEquals(6, r.decision!!.steps.count { it.passed == true })
        val pending = TaskGrantBoard.pendingFor("op-n6")
        assertNotNull("放行必须把请求交给管线（执行成功后拿它落账）", pending)
        assertEquals("notes/a.md", pending!!.objectId)

        assertEquals("新稿子".toByteArray(Charsets.UTF_8).size.toLong(), pending.candidateBytes)
    }

    @Test
    fun 放行的账只落一条并推进额度() {
        touch("notes/a.md")
        open(listOf("notes"))
        val now = TaskGrantWire.monoMs()
        val r = decide(
            call("op-acct", "notes/a.md"),
            listOf(File(ws, "notes/a.md")),
            now,
            shadow = ShadowSignal.Rehearsed(ShadowVerdict.CONSISTENT),
        )
        val request = r.request!!
        File(ws, "notes/a.md").writeText("新稿子")
        assertTrue(TaskGrantWire.recordApplied(request, File(ws, "notes/a.md"), System.currentTimeMillis()))
        val applied = TaskGrantLedger.appliedOps(ledgerRoot)
        assertEquals("一个动作一条账", listOf("op-acct"), applied)
        val state = TaskGrantRuntime.current()!!
        assertEquals("额度推进一次", 1, state.actionsUsed)
        assertEquals(request.candidateBytes, state.bytesWritten)
        // 重跑同一个 opId：账不许变成两条（249 负例⑧在接线层的同一格）。
        assertTrue(TaskGrantWire.recordApplied(request, File(ws, "notes/a.md"), System.currentTimeMillis()))
        assertEquals(listOf("op-acct"), TaskGrantLedger.appliedOps(ledgerRoot))
        assertEquals("第二次 commit 不推进额度", 1, TaskGrantRuntime.current()!!.actionsUsed)
    }

    @Test
    fun 同一对象第二次动作改走单次人批() {
        // 249 的字面逻辑：六步第①步比的是**快照**状态，写完的新一版自然对不上 ⇒ 保守方向。
        // 本批一个字没改它；这条断言把"改过之后会怎样"钉住，谁要放宽必须先把这条改红。
        touch("notes/a.md")
        open(listOf("notes"))
        val now = TaskGrantWire.monoMs()
        val first = decide(
            call("op-1", "notes/a.md"),
            listOf(File(ws, "notes/a.md")),
            now,
            shadow = ShadowSignal.Rehearsed(ShadowVerdict.CONSISTENT),
        )
        File(ws, "notes/a.md").writeText("新稿子")
        TaskGrantWire.recordApplied(first.request!!, File(ws, "notes/a.md"), System.currentTimeMillis())
        val second = decide(
            call("op-2", "notes/a.md"),
            listOf(File(ws, "notes/a.md")),
            TaskGrantWire.monoMs(),
            shadow = ShadowSignal.Rehearsed(ShadowVerdict.CONSISTENT),
        )
        assertEquals(TaskGrantGate.CODE_STATE_CHANGED, second.decision!!.code)
        assertEquals(GateOutcome.ASK_SINGLE, second.decision!!.outcome)
        assertEquals(WiringDisposition.UNAUTHORIZED, second.disposition)
    }

    // ── 拿不到的事实 ⇒ 执行路径逐字不变（NOT_APPLICABLE 不是"放行"） ──────────

    @Test
    fun 三种拿不到都不进判定() {
        touch("notes/a.md")
        open(listOf("notes"))
        val now = TaskGrantWire.monoMs()
        val target = File(ws, "notes/a.md")
        // ① 读工具：授权管的是改盘。
        assertEquals(
            WiringDisposition.NOT_APPLICABLE,
            decide(call("op-r", "notes/a.md"), listOf(target), now, isWrite = false).disposition,
        )
        // ② 目标不是恰好一个文件（move_path 两端）。
        assertEquals(
            WiringDisposition.NOT_APPLICABLE,
            decide(call("op-m", "notes/a.md"), listOf(target, File(ws, "notes/b.md")), now).disposition,
        )
        // ③ 参数里没有可测的写入字节。
        val noContent = ToolCall("op-c", "write_file", JSONObject().put("path", "notes/a.md"))
        assertEquals(WiringDisposition.NOT_APPLICABLE, decide(noContent, listOf(target), now).disposition)
        // ④ 矩阵结论缺失（MCP 那一组本来就不归本地规则管）。
        assertEquals(
            WiringDisposition.NOT_APPLICABLE,
            decide(call("op-x", "notes/a.md"), listOf(target), now, verdict = null).disposition,
        )
        assertNull("四种都不该留下六步读数", TaskGrantBoard.reading())
        assertNull("更不该留下待落账的那一格", TaskGrantBoard.pendingFor("op-r"))
    }

    @Test
    fun 没装授权时是未授权而不是不适用() {
        touch("notes/a.md")
        val r = decide(call("op-none", "notes/a.md"), listOf(File(ws, "notes/a.md")), TaskGrantWire.monoMs())
        assertEquals(WiringDisposition.UNAUTHORIZED, r.disposition)
        assertTrue(r.note.contains("没有生效的任务级授权"))
        assertNull("没进六步就不编读数", TaskGrantBoard.reading())
    }

    @Test
    fun 没有落点时批准不开单() {
        // 批准本身必须先在 208 留下一条真记录（`approvalEventId` 要的是真发生过的那次批准）。
        DecisionLedger.uninstallForTest()
        touch("notes/a.md")
        val now = TaskGrantWire.monoMs()
        val offer = TaskGrantWire.buildOffer(contract(listOf("notes")), ws, 7L, epoch, now, 0L).first!!
        val r = TaskGrantWire.openGrant(offer, defaultGrantSelection(offer.facts), ws, epoch, now, 0L)
        assertFalse("没装配落点 ⇒ 不开单", r.installed)
        assertTrue(r.reason.contains(WireRefusal.LEDGER_NOT_ASSEMBLED.cn))
        assertNull(TaskGrantRuntime.active(now))
        assertNull(TaskGrantBoard.bindingOfOpened())
    }

    @Test
    fun 候选阶段装不进来() {
        // buildOffer 交回的是**没批过**的骨架：approvalEventId／presentedPayloadHash 都空 ⇒
        // validate 拦住，正好挡在"批了才装"那一步。这一格是"候选不等于生效"的结构保证。
        touch("notes/a.md")
        val now = TaskGrantWire.monoMs()
        val offer = TaskGrantWire.buildOffer(contract(listOf("notes")), ws, 7L, epoch, now, 0L).first!!
        assertEquals("", offer.grant.approvalEventId)
        assertTrue(
            "没批过的骨架装不进来",
            TaskGrantRuntime.install(offer.grant, epoch, now) is InstallResult.Refused,
        )
        assertNull(TaskGrantRuntime.active(now))
    }

    @Test
    fun 开单时绑定与快照同源() {
        touch("notes/a.md")
        val offer = open(listOf("notes"))
        val installed = TaskGrantRuntime.current()!!.grant
        // 板上那份＝从装着的那份快照重算 ⇒ 同一个构造（负例"卡上一份、执行时另一份"的正面）。
        assertEquals(TaskGrantWire.bindingOf(installed), offer.binding)
        assertEquals(TaskGrantBoard.bindingOfOpened(), offer.binding)
        assertEquals(installed.grantId, TaskGrantBoard.openedGrantId())
        assertEquals("开单之后板上这一单就是装上的那一份", offer.grant, TaskGrantBoard.currentOffer()!!.grant)
    }

    @Test
    fun 一个都没勾就不开单() {
        touch("notes/a.md")
        val now = TaskGrantWire.monoMs()
        val offer = TaskGrantWire.buildOffer(contract(listOf("notes")), ws, 7L, epoch, now, 0L).first!!
        val r = TaskGrantWire.openGrant(offer, GrantSelection(emptyList(), emptyList()), ws, epoch, now, 0L)
        assertFalse("空选择＝照旧逐次问，不是「授权了但范围是空的」", r.installed)
        assertTrue(r.reason.contains("一个都没勾"))
        assertNull(TaskGrantRuntime.active(now))
    }
}
