// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：派工单-2026-10-27-第二百四十二批-计划即契约 §4 五条判据 ＋ §5 七条负例。
// 本文件打的是**判据层与账**（`baopu/plan/` 两份文件 ＋ 208）；打在闸与接线上的那一批
// （GuardPipeline / PlanController / RunEngine / 计划卡）在 `PlanContractWiringTest`。
//
// 房型照 `RiskMatrixTest`（219）：判定层零 Android、零 IO，所以七条负例每一条都能在
// JVM 里红——「负例」不是注释，是一条断言：把错的做法写回代码，这里必红。
package com.example.zhengdao.baopu.plan

import com.example.zhengdao.baopu.DecisionLedger
import com.example.zhengdao.baopu.PlanRules
import com.example.zhengdao.baopu.PlanRules.PlanItem
import com.example.zhengdao.baopu.RiskClass
import java.io.File
import java.nio.file.Files
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 工作区根（造数用的绝对路径前缀；契约里一律写相对，动作一律写绝对）。 */
private const val WS = "/ws"

class PlanContractTest {

    // ── 造数 ────────────────────────────────────────────────────────────────

    private fun item(text: String, status: String = PlanRules.STATUS_TODO) = PlanItem(text, status)

    /** 两步计划：第 1 步勾完、第 2 步在做（写动作落在上限 R1 的那一步，与真实运行同形）。 */
    private fun twoSteps() = listOf(item("读原稿", PlanRules.STATUS_DONE), item("写产物", PlanRules.STATUS_DOING))

    private fun contract(
        steps: List<Pair<String, RiskClass>> = listOf("读原稿" to RiskClass.R0, "写产物" to RiskClass.R1),
        touches: List<String> = listOf("资料库/产物"),
        readonly: List<String> = emptyList(),
        expectedAsks: Int = 0,
        stopIf: List<String> = emptyList(),
        mayTouch: List<List<String>> = emptyList(),
    ): PlanContract = PlanContract(
        goal = "把稿子整理成产物",
        steps = steps.mapIndexed { i, (text, risk) -> PlanStep(i + 1, text, mayTouch.getOrNull(i).orEmpty(), risk) },
        touches = touches,
        readonly = readonly,
        expectedAsks = expectedAsks,
        stopIf = stopIf,
    )

    private fun face(p: String) = TouchFace.of(p)!!

    private fun at(rel: String) = "$WS/$rel"

    /** [PlanContractRules.check] 的造数壳：目标一律给绝对路径（第 ② 段改写之后的形状）。 */
    private fun against(
        c: PlanContract?,
        vararg targets: String,
        actual: RiskClass? = RiskClass.R0,
        isWrite: Boolean = true,
        items: List<PlanItem> = twoSteps(),
    ): ContractCheck = PlanContractRules.check(c, targets.map { face(it) }, actual, isWrite, items, WS)

    private fun action(
        id: String = "c1",
        tool: String = "write_file",
        faces: List<TouchFace> = listOf(face(at("资料库/产物/a.md"))),
        risk: RiskClass? = RiskClass.R1,
        isWrite: Boolean = true,
        breach: ContractBreach = ContractBreach.NONE,
    ) = PlanContractRules.ActualAction(id, tool, faces, risk, isWrite, breach)

    /** `update_plan` 的参数：items ＋（contract ＝ null 表示根本没声明）。 */
    private fun planArgs(items: List<Pair<String, String>>, contract: JSONObject? = JSONObject()): JSONObject =
        JSONObject().put(
            "items",
            JSONArray().also { arr ->
                items.forEach { (t, s) -> arr.put(JSONObject().put("text", t).put("status", s)) }
            },
        ).apply { contract?.let { put(PlanContractRules.ARG_CONTRACT, it) } }

    private fun contractJson(
        steps: List<JSONObject> = listOf(
            JSONObject().put("may_touch", JSONArray(listOf("资料库/原稿"))).put("risk", "R0"),
            JSONObject().put("may_touch", JSONArray(listOf("资料库/产物"))).put("risk", "R1"),
        ),
        touches: Any = JSONArray(listOf("资料库/产物")),
        readonly: Any = JSONArray(),
        expectedAsks: Any = 1,
        stopIf: Any = JSONArray(),
    ): JSONObject = JSONObject()
        .put("goal", "把稿子整理成产物")
        .put("steps", JSONArray(steps))
        .put("touches", touches)
        .put("readonly", readonly)
        .put("expected_asks", expectedAsks)
        .put("stop_if", stopIf)

    private val twoItems = listOf("读原稿" to PlanRules.STATUS_TODO, "写产物" to PlanRules.STATUS_TODO)

    // ── A 段 · 判据 1：卡上那四样东西，缺一立不起契约 ───────────────────────

    @Test
    fun `判据1_四样齐了才立得起契约`() {
        val parsed = PlanContractRules.parse(planArgs(twoItems, contractJson()), twoSteps())
        assertTrue("该立起的契约没立起：$parsed", parsed is PlanContractRules.ContractParse.Ok)
        val c = (parsed as PlanContractRules.ContractParse.Ok).contract
        assertEquals("每步都要有一个上限读数", listOf(RiskClass.R0, RiskClass.R1), c.steps.map { it.risk })
        assertEquals("白名单是上限的主轴", listOf("资料库/产物"), c.touches)
        assertEquals("预估问几次（D 段第三项的预估侧）", 1, c.expectedAsks)
        assertEquals("步骤数与计划条目数一致", 2, c.steps.size)
        assertEquals("每步自己声明的目标也在契约里", listOf("资料库/原稿"), c.steps[0].mayTouch)
    }

    @Test
    fun `负例1_没有白名单就没有上限`() {
        // 靶心：卡面上没有 touches／没有每步风险 ⇒ 不许"静默当成没契约"继续跑
        val noTouches = PlanContractRules.parse(
            planArgs(listOf("读原稿" to PlanRules.STATUS_TODO), contractJson(touches = JSONArray())),
            listOf(item("读原稿")),
        )
        assertTrue("缺 touches 必须是 Error 而不是 Absent：$noTouches", noTouches is PlanContractRules.ContractParse.Error)
        assertTrue((noTouches as PlanContractRules.ContractParse.Error).message.contains("touches"))

        val noRisk = PlanContractRules.parse(
            planArgs(
                listOf("读原稿" to PlanRules.STATUS_TODO),
                contractJson(steps = listOf(JSONObject().put("may_touch", JSONArray()))),
            ),
            listOf(item("读原稿")),
        )
        assertTrue("第 1 步缺 risk ⇒ Error", noRisk is PlanContractRules.ContractParse.Error)
        assertTrue((noRisk as PlanContractRules.ContractParse.Error).message.contains("risk"))

        val absent = PlanContractRules.parse(planArgs(listOf("读原稿" to PlanRules.STATUS_TODO), null), listOf(item("读原稿")))
        assertTrue("根本没写 contract 才是 Absent：问答式的一轮本来就没有上限可设", absent is PlanContractRules.ContractParse.Absent)
        assertEquals("没契约就没有上限可判（恒不违反）", ContractBreach.NONE, against(null, at("任何地方/x.md")).breach)
    }

    @Test
    fun `负例1_少一步就少一格上限_不许放过`() {
        val parsed = PlanContractRules.parse(
            planArgs(twoItems, contractJson(steps = listOf(JSONObject().put("risk", "R0")))),
            twoSteps(),
        )
        assertTrue("steps 比计划少 ⇒ Error", parsed is PlanContractRules.ContractParse.Error)
        assertTrue((parsed as PlanContractRules.ContractParse.Error).message.contains("risk"))
    }

    @Test
    fun `步骤只有一个真源_契约里偷加一步加不进`() {
        // 负例④的一半：模型不许在 contract 里写一条计划里没有的步骤
        val parsed = PlanContractRules.parse(
            planArgs(listOf("读原稿" to PlanRules.STATUS_TODO), contractJson(steps = listOf(JSONObject().put("risk", "R0"), JSONObject().put("risk", "R3")))),
            listOf(item("读原稿")),
        )
        val ok = parsed as PlanContractRules.ContractParse.Ok
        assertEquals("多出来的步骤按位置丢掉，不许进契约", 1, ok.contract.steps.size)
        assertEquals("步骤文字只认计划条目（intent 不是模型给的）", "读原稿", ok.contract.steps[0].intent)
        assertTrue("丢掉这一步要留痕", ok.notices.any { it.contains("丢掉") })
    }

    @Test
    fun `白名单与只读区有上限_超长截断并留痕`() {
        val many = (1..20).map { "资料库/目录$it" }
        val parsed = PlanContractRules.parse(
            planArgs(twoItems, contractJson(touches = JSONArray(many), readonly = JSONArray(many))),
            twoSteps(),
        )
        val ok = parsed as PlanContractRules.ContractParse.Ok
        assertEquals(PlanContractRules.MAX_TOUCHES, ok.contract.touches.size)
        assertEquals(PlanContractRules.MAX_READONLY, ok.contract.readonly.size)
        assertEquals("两处截断各留一句", 2, ok.notices.size)
    }

    // ── 覆盖判定：按组件比，不按字符串前缀 ─────────────────────────────────

    @Test
    fun `声明盖住子树_兄弟目录骗不过`() {
        val c = contract()
        assertFalse("子树算覆盖", against(c, at("资料库/产物/子目录/a.md")).violated)
        assertEquals("字符串前缀会被这条骗过，按组件比不会", ContractBreach.OUT_OF_TOUCHES, against(c, at("资料库/产物2/a.md")).breach)
    }

    @Test
    fun `网址按主机名比_精确或子域`() {
        val c = contract(touches = listOf("example.com"))
        assertFalse("同一家的两个链接不该各要一次授权", against(c, "https://a.example.com/page?x=1").violated)
        assertTrue("子域算同一家", PlanContractRules.covers(face("example.com"), face("a.example.com")))
        assertFalse("notexample.com 不是 example.com 的子域", PlanContractRules.covers(face("example.com"), face("notexample.com")))
        assertTrue("端口与 www. 都不影响是同一家", PlanContractRules.covers(face("example.com"), face("https://www.example.com:8443/x")))
    }

    @Test
    fun `每步的may_touch只在它这一步生效`() {
        val c = contract(mayTouch = listOf(listOf("资料库/原稿"), listOf("资料库/产物")))
        val first = listOf(item("读原稿", PlanRules.STATUS_DOING), item("写产物"))
        assertFalse(
            "第 1 步自己声明的目标在它这一步算契约内",
            against(c, at("资料库/原稿/a.md"), items = first).violated,
        )
        assertTrue(
            "第 1 步声明的目标不能给第 2 步用（当前步的允许集只含它自己那一格）",
            against(c, at("资料库/原稿/a.md"), items = twoSteps()).violated,
        )
    }

    // ── C 段：三条硬规则（判据 2 ＋ 负例③） ────────────────────────────────

    @Test
    fun `判据2_契约内且不超预估_按档走不弹偏离卡`() {
        val c = contract()
        assertEquals(ContractBreach.NONE, against(c, at("资料库/产物/a.md"), actual = RiskClass.R1).breach)
        assertEquals("R0 更在预估之内", ContractBreach.NONE, against(c, at("资料库/产物/a.md"), actual = RiskClass.R0).breach)
    }

    @Test
    fun `C②_实际档高于这一步预估_立刻判违约`() {
        val hit = against(contract(), at("资料库/产物/a.md"), actual = RiskClass.R3)
        assertEquals(ContractBreach.RISK_OVER_ESTIMATE, hit.breach)
        assertEquals("卡面要说清预估与实际", RiskClass.R1, hit.ceiling)
        assertEquals(RiskClass.R3, hit.actual)
        assertEquals("当前这一步的步号与原文要带上", 2, hit.stepNo)
        assertEquals("写产物", hit.stepIntent)
    }

    @Test
    fun `读动作不吃风险上限_只吃白名单`() {
        val c = contract()
        assertFalse(
            "读计划内的目标不因档位弹卡（上限说的是「能改成什么」）",
            against(c, at("资料库/产物/a.md"), actual = RiskClass.R3, isWrite = false).violated,
        )
        assertTrue("但读计划外的目标同样是计划外动作", against(c, at("桌面/x.txt"), isWrite = false).violated)
    }

    @Test
    fun `C②_只读区可以读不可以写`() {
        // 只读区总是白名单里更严的一层（「资料库能读能写，但原始那份只读」——最常见的声明形状）
        val c = contract(touches = listOf("资料库"), readonly = listOf("资料库/原始"))
        assertEquals(ContractBreach.WRITE_IN_READONLY, against(c, at("资料库/原始/a.md")).breach)
        assertFalse("读同一个地方不违约（只读区管的是写）", against(c, at("资料库/原始/a.md"), isWrite = false).violated)
    }

    @Test
    fun `只读区独立成立_越宽白名单洗不白红线`() {
        // 最常见的声明形状：整个「资料库」能读能写，但「资料库/原始」只读
        val c = contract(touches = listOf("资料库"), readonly = listOf("资料库/原始"))
        assertEquals(
            "只读区那一层必须独立成立，否则越「允许」的声明反而把红线洗白",
            ContractBreach.WRITE_IN_READONLY,
            against(c, at("资料库/原始/a.md")).breach,
        )
    }

    @Test
    fun `C③_步骤全勾完还要改盘_算契约外`() {
        val allDone = listOf(item("读原稿", PlanRules.STATUS_DONE), item("写产物", PlanRules.STATUS_DONE))
        assertEquals(ContractBreach.BEYOND_STEPS, against(contract(), at("资料库/产物/b.md"), items = allDone).breach)
    }

    @Test
    fun `当前步_doing优先_否则第一条没打完`() {
        val c = contract()
        assertEquals(
            "标了 doing ⇒ 认它（这里 doing 是第 2 步，而「第一条没打完」是第 1 步：doing 是模型的明示，更准）",
            2,
            PlanContractRules.activeStep(c, listOf(item("读原稿"), item("写产物", PlanRules.STATUS_DOING)))?.n,
        )
        assertEquals(
            "没标 doing ⇒ 退到第一条没打完的（不许跳过中间没做完的去吃后面那一步的上限）",
            1,
            PlanContractRules.activeStep(c, listOf(item("读原稿"), item("写产物", PlanRules.STATUS_DONE)))?.n,
        )
        assertNull("全打完 ⇒ 没有当前步（再改盘就是契约外）", PlanContractRules.activeStep(c, listOf(item("读原稿", PlanRules.STATUS_DONE))))
        assertNull("没契约 ⇒ 没有步骤", PlanContractRules.activeStep(null, twoSteps()))
    }

    @Test
    fun `负例4_发现契约外步骤只报不改`() {
        val notice = PlanContractRules.newStepNotice("把产物发给编辑")
        assertTrue("要说清计划里没有这一步", notice.contains("【计划里没有这一步】"))
        assertTrue("加步骤必须走 update_plan ＋ 变更确认卡", notice.contains("update_plan"))
        assertFalse("不许出现「加好了」这种既成事实", notice.contains("已加入计划"))
        assertFalse("不许出现命令式的「必须加」", notice.contains("必须加"))
        // 同一口径的两张扩不动的回灌：都要说「这一步没有发生」
        val ro = against(contract(readonly = listOf("资料库/原始")), at("资料库/原始/a.md"), actual = RiskClass.R2)
        assertTrue(ContractDeviationRules.notExtendableText(ro, "write_file").contains("没有发生"))
        val beyond = against(contract(), at("资料库/产物/b.md"), items = listOf(item("读原稿", PlanRules.STATUS_DONE)))
        assertTrue(ContractDeviationRules.notExtendableText(beyond, "write_file").contains("没有发生"))
    }

    // ── D 段：勘验对账（判据 5 ＋ 负例⑤⑥） ────────────────────────────────

    @Test
    fun `判据5_三项都对得上_不编不符`() {
        val report = PlanContractRules.reconcile(contract(expectedAsks = 1), listOf(action()), asks = 1, root = WS)
        assertTrue("读数本身要齐：$report", report.isClean)
        assertEquals(1, report.totalActions)
        assertEquals(1, report.writeActions)
        assertEquals(RiskClass.R1, report.declaredMaxRisk)
        assertEquals(RiskClass.R1, report.actualMaxRisk)
        assertNull("对得上就不多说一句", PlanContractRules.reconBrief(report))
    }

    @Test
    fun `负例5_对账按事实重算_抄跑时结论不算账`() {
        // 跑时没判出违约（breach=NONE），但按整份契约复核它动到了白名单外 ⇒ 照样不符
        val c = contract()
        val smuggled = action(faces = listOf(face(at("桌面/偷偷.md"))), breach = ContractBreach.NONE)
        assertEquals("跑时那一格它是干净的", ContractBreach.NONE, smuggled.breach)
        assertTrue("独立复核必须发现它", PlanContractRules.outsideContract(c, smuggled, WS))
        val report = PlanContractRules.reconcile(c, listOf(smuggled), asks = 0, root = WS)
        assertEquals(listOf(ContractFindingCode.ACT_OUT_OF_CONTRACT), report.findings.map { it.code })
        assertEquals(1, report.outOfContract)
        assertTrue(PlanContractRules.reconBrief(report)!!.startsWith("计划与执行对不上账："))
    }

    @Test
    fun `负例6_expectedAsks写0实际问5_出finding`() {
        val report = PlanContractRules.reconcile(contract(expectedAsks = 0), listOf(action()), asks = 5, root = WS)
        val f = report.findings.single { it.code == ContractFindingCode.ASKS_OVER_ESTIMATE }
        assertTrue(f.detail.contains("预估问 0 次") && f.detail.contains("实际问了 5 次"))
        assertEquals(5, f.count)
    }

    @Test
    fun `说好的5次一次没问_同样是估算骗人`() {
        val report = PlanContractRules.reconcile(contract(expectedAsks = 5), emptyList(), asks = 0, root = WS)
        assertEquals(listOf(ContractFindingCode.ASKS_UNDER_ESTIMATE), report.findings.map { it.code })
    }

    @Test
    fun `问人次数容差_一次以内的出入不报`() {
        // ASK_GAP = 2：预估 0 时问 2 次算正常波动，问 3 次才算不符
        assertTrue(PlanContractRules.reconcile(contract(expectedAsks = 0), emptyList(), asks = 2, root = WS).isClean)
        assertFalse(PlanContractRules.reconcile(contract(expectedAsks = 0), emptyList(), asks = 3, root = WS).isClean)
    }

    @Test
    fun `实际高于预估才报_低于预估是好消息`() {
        val over = PlanContractRules.reconcile(contract(), listOf(action(risk = RiskClass.R3)), asks = 0, root = WS)
        assertEquals(listOf(ContractFindingCode.RISK_OVER_ESTIMATE), over.findings.map { it.code })
        assertEquals(RiskClass.R3, over.actualMaxRisk)
        val under = PlanContractRules.reconcile(contract(), listOf(action(risk = RiskClass.R0)), asks = 0, root = WS)
        assertTrue("动得比说好的轻不算不符（报它就是在骗人）", under.isClean)
    }

    @Test
    fun `没有本地档位的动作不参与最高档比较`() {
        // MCP／批准后只跳第 ③ 段：那一次没有本地读数 ⇒ 如实「没有」，不编一个档出来
        val report = PlanContractRules.reconcile(contract(), listOf(action(risk = null)), asks = 0, root = WS)
        assertNull(report.actualMaxRisk)
        assertTrue("读不出档位不许报成风险不符", report.isClean)
    }

    @Test
    fun `三项不符各报各的_一次档位超预估不许报成两件事`() {
        val report = PlanContractRules.reconcile(contract(), listOf(action(risk = RiskClass.R3)), asks = 0, root = WS)
        assertEquals("目标都在白名单里 ⇒ 只报档位那一条", listOf(ContractFindingCode.RISK_OVER_ESTIMATE), report.findings.map { it.code })
        assertEquals(0, report.outOfContract)
    }

    @Test
    fun `读动作越界照样进账`() {
        val read = action(faces = listOf(face(at("桌面/x.txt"))), risk = RiskClass.R0, isWrite = false)
        assertTrue(PlanContractRules.outsideContract(contract(), read, WS))
        val report = PlanContractRules.reconcile(contract(), listOf(read), asks = 0, root = WS)
        assertEquals(listOf(ContractFindingCode.ACT_OUT_OF_CONTRACT), report.findings.map { it.code })
    }

    @Test
    fun `上次账回灌给下次写计划_预估按实际写`() {
        val report = PlanContractRules.reconcile(
            contract(expectedAsks = 0),
            listOf(action(risk = RiskClass.R3)),
            asks = 4,
            root = WS,
        )
        val hint = PlanContractRules.estimateHint(report)!!
        assertTrue(hint.startsWith("【上次的账】"))
        assertTrue(hint.contains("实际问了 4 次"))
        assertTrue(hint.contains("不要写 0"))
        assertTrue(hint.contains("steps[].risk"))
        assertNull(
            "对得上的时候不编提示；「问少了」也不回灌估算",
            PlanContractRules.estimateHint(PlanContractRules.reconcile(contract(), listOf(action()), asks = 0, root = WS)),
        )
    }

    // ── 编解码：payload 里的 contract 键 ───────────────────────────────────

    @Test
    fun `契约写进payload读回来逐字一致`() {
        val items = twoSteps()
        val c = (PlanContractRules.parse(planArgs(twoItems, contractJson()), items)
            as PlanContractRules.ContractParse.Ok).contract
        val back = PlanRules.contractFromJson(PlanRules.toJson(items, c))!!
        assertEquals("往返必须同一个对象", c, back)
        assertEquals("每步上限还在", listOf(RiskClass.R0, RiskClass.R1), back.steps.map { it.risk })
        assertEquals("步骤文字仍以 items 为真源", listOf("读原稿", "写产物"), back.steps.map { it.intent })
    }

    @Test
    fun `没契约时payload字节与今天逐字相同`() {
        val items = twoSteps()
        val plain = PlanRules.toJson(items)
        assertFalse("不许多挂一个键（存量计划的形状不许变）", plain.contains("contract"))
        assertEquals(items, PlanRules.fromJson(plain))
        assertNull(PlanRules.contractFromJson(plain))
        assertNull("坏 payload 不崩", PlanRules.contractFromJson("not json"))
        assertNull(
            "回读缺必备 ⇒ 当没有（宁缺勿崩，与 fromJson 同口径）",
            PlanRules.contractFromJson("""{"v":1,"items":[{"text":"a","status":"todo"}],"contract":{}}"""),
        )
    }

    @Test
    fun `同份契约编码字节稳定`() {
        val items = listOf(item("读原稿"))
        val c = (PlanContractRules.parse(
            planArgs(listOf("读原稿" to PlanRules.STATUS_TODO), contractJson(steps = listOf(JSONObject().put("risk", "R2")))),
            items,
        ) as PlanContractRules.ContractParse.Ok).contract
        assertEquals(PlanContractRules.encode(c).toString(), PlanContractRules.encode(c).toString())
        assertEquals(c, PlanRules.contractFromJson(PlanRules.toJson(items, c)))
    }

    // ── 两张卡的文案与走法（负例②③的最后一道） ────────────────────────────

    @Test
    fun `红线1_批准卡自己说清不给授权`() {
        val req = PlanContractCards.approvalRequest(contract(expectedAsks = 2, stopIf = listOf("要花钱")))
        assertTrue(req.question.contains("要把这份计划当本次运行的上限吗"))
        assertTrue("卡面必须画出每步预估", req.question.contains("——R0"))
        assertTrue("卡面必须画白名单", req.question.contains("允许动到：资料库/产物"))
        assertTrue("预估问几次", req.question.contains("预估要问你：2 次"))
        assertTrue("停线条件", req.question.contains("碰到就停：要花钱"))
        assertTrue("只读区没声明就不摆", !req.question.contains("只读（可以看"))
        assertTrue(
            "只读区声明了必须摆",
            ContractApprovalRules.body(contract(readonly = listOf("资料库/原始"))).contains("只读（可以看、不可以改）：资料库/原始"),
        )
        val approve = req.options.first { it.id == ContractApprovalRules.OPT_APPROVE }
        assertTrue("批准那一栏必须点明这不等于授权改盘", approve.detail.contains("批准计划不等于授权改盘"))
        assertTrue("推荐批准", approve.recommended)
        assertTrue(
            "不批准那一栏不许听起来像「可以乱来」",
            req.options.first { it.id == ContractApprovalRules.OPT_DECLINE }.detail.contains("该问就问"),
        )
    }

    @Test
    fun `沉默不等于批准_只有真点批准才算`() {
        assertTrue(ContractApprovalRules.approved(ContractApprovalRules.OPT_APPROVE))
        listOf<String?>(null, "", "随便", ContractApprovalRules.OPT_DECLINE).forEach {
            assertFalse("没点／超时／点不批准 ⇒ 不批准（$it）", ContractApprovalRules.approved(it))
        }
    }

    @Test
    fun `偏离卡三选一_默认不执行`() {
        val hit = against(contract(), at("桌面/x.txt"))
        val req = PlanContractCards.deviationRequest(hit, "write_file")
        assertTrue(req.question.contains("计划外动作：write_file"))
        assertTrue("要说清实际要做什么 vs 计划里没有", req.question.contains("不在计划的白名单里"))
        assertTrue("要说清当前这一步按计划是干什么的", req.question.contains("当前这一步按计划是做「写产物」"))
        assertEquals(
            listOf(ContractDeviationRules.OPT_CANCEL, ContractDeviationRules.OPT_EXTEND, ContractDeviationRules.OPT_CHANGE),
            req.options.map { it.id },
        )
        assertTrue("取消是默认（fail-closed）", req.options.first().recommended)
        assertTrue("扩计划要说清加的是哪些目标", req.options[1].detail.contains(at("桌面/x.txt")))
        // 没点／超时／点了没认识的 id ⇒ 取消（负例③「超出 touches 继续跑」的最后一道）
        listOf<String?>(null, "", "未知").forEach {
            assertEquals(ContractDeviationAction.CANCEL_ACTION, ContractDeviationRules.actionFor(it))
        }
    }

    @Test
    fun `扩不动的违约不摆扩计划按钮`() {
        val ro = against(contract(readonly = listOf("资料库/原始")), at("资料库/原始/a.md"), actual = RiskClass.R2)
        val beyond = against(contract(), at("资料库/产物/b.md"), items = listOf(item("读原稿", PlanRules.STATUS_DONE)))
        listOf(ro, beyond).forEach {
            assertFalse("这张卡做不到的事不许摆按钮", ContractDeviationRules.extendable(it))
            assertFalse(
                "卡面上不许出现一个骗她点的选项",
                PlanContractCards.deviationRequest(it, "write_file").options.any { o -> o.id == ContractDeviationRules.OPT_EXTEND },
            )
        }
        assertTrue(ContractDeviationRules.extendable(against(contract(), at("桌面/x.txt"))))
        assertTrue(ContractDeviationRules.extendable(against(contract(), at("资料库/产物/a.md"), actual = RiskClass.R3)))
    }

    @Test
    fun `扩计划之后重走完整管线_取消换做法各说一句实话`() {
        val hit = against(contract(), at("桌面/x.txt"))
        assertNull(
            "扩计划不回灌「没做」（动作真的会跑），但引擎必须重走完整管线而不是走批准路径",
            ContractDeviationRules.feedFor(ContractDeviationAction.EXTEND_CONTRACT, hit, "write_file"),
        )
        assertTrue(
            ContractDeviationRules.feedFor(ContractDeviationAction.CANCEL_ACTION, hit, "write_file")!!
                .contains("这一步**没有发生**"),
        )
        assertTrue(
            ContractDeviationRules.feedFor(ContractDeviationAction.CHANGE_WAY, hit, "write_file")!!
                .contains("换一种**在计划白名单与步骤范围内**的做法"),
        )
        assertTrue(
            "扩过仍然超限：不再问第二遍，但也不许静默放行",
            ContractDeviationRules.stillBreachText(hit, "write_file").contains("这次动作**没有发生**"),
        )
    }

    // ── 活动契约板：一轮的事实（含 208） ────────────────────────────────────

    private fun board(
        c: PlanContract? = contract(),
        items: List<PlanItem> = twoSteps(),
        root: String = WS,
        session: Long = 7L,
    ) {
        PlanContractBoard.resetForTest()
        if (c != null) PlanContractBoard.activate(c, items, root, session)
    }

    private fun ledgerDir(): File = Files.createTempDirectory("baopu-242-ledger").toFile()

    @After
    fun tearDown() {
        PlanContractBoard.resetForTest()
        DecisionLedger.uninstallForTest()
    }

    @Test
    fun `没契约时这一层完全惰性`() {
        board(null)
        assertFalse(PlanContractBoard.hasContract())
        PlanContractBoard.noteAction(action())
        PlanContractBoard.noteAsk()
        assertEquals("没契约不记问人账（今天的每一张卡都不该被这层数进去）", 0, PlanContractBoard.askCount())
        assertNull("没契约不编读数", PlanContractBoard.reconcile())
    }

    @Test
    fun `同一次调用重复判定只记一条`() {
        board()
        // 确认卡批准后重走一遍管线／偏离卡扩完计划再判一次 ⇒ 同一个 ToolCall.id
        PlanContractBoard.noteAction(action(id = "same"))
        PlanContractBoard.noteAction(action(id = "same", risk = RiskClass.R0))
        val report = PlanContractBoard.reconcile()!!
        assertEquals("两次判定是一个动作，账上不许变成两次", 1, report.totalActions)
        assertEquals("记的是第一次的读数", RiskClass.R1, report.actualMaxRisk)
    }

    @Test
    fun `问人计数只数要人点的卡`() {
        board()
        repeat(3) { PlanContractBoard.noteAsk() }
        assertEquals(3, PlanContractBoard.askCount())
        val report = PlanContractBoard.reconcile()!!
        assertEquals("问人那一侧读的是板上的账", 3, report.asks)
        assertEquals(listOf(ContractFindingCode.ASKS_OVER_ESTIMATE), report.findings.map { it.code })
    }

    @Test
    fun `负例7_不符必须落208`() {
        val dir = ledgerDir()
        DecisionLedger.install(dir)
        board(contract(expectedAsks = 0))
        PlanContractBoard.noteAction(action(faces = listOf(face(at("桌面/偷偷.md"))), risk = RiskClass.R3))
        repeat(3) { PlanContractBoard.noteAsk() }
        val report = PlanContractBoard.reconcile()!!
        assertEquals("三项不符各一条：$report", 3, report.findings.size)
        val lines = DecisionLedger.fileIn(dir).readLines().filter { it.isNotBlank() }
        assertEquals("每条不符落一行 208", 3, lines.size)
        lines.forEach { line ->
            val row = JSONObject(line)
            assertEquals("plan_contract_finding", row.getString("kind"))
            assertTrue("reason／evidence 缺一条整条不落（208 纪律①）", row.getString("reason").isNotBlank())
            assertTrue(row.getString("evidence").isNotBlank())
            assertTrue(row.getString("before").contains("上限"))
            assertTrue(row.getString("after").contains("实际"))
            assertFalse("已经跑完的动作没有回滚引用可取 ⇒ 如实降级", row.getBoolean("reversible"))
            assertEquals(7, row.getLong("session").toInt())
        }
        assertEquals("三条不符各指自己的证据", 3, lines.map { JSONObject(it).getString("evidence") }.toSet().size)
    }

    @Test
    fun `一轮只结一次账`() {
        val dir = ledgerDir()
        DecisionLedger.install(dir)
        board(contract(expectedAsks = 0))
        PlanContractBoard.noteAction(action(faces = listOf(face(at("桌面/偷偷.md")))))
        val first = PlanContractBoard.reconcile()!!
        assertTrue("settle 会被多条路叫到（正常收尾／熔断／停止）", PlanContractBoard.reconcile() === first)
        assertEquals(
            "同一份账记两遍就是假账（208 只追加，删不掉）",
            1,
            DecisionLedger.fileIn(dir).readLines().filter { it.isNotBlank() }.size,
        )
    }

    @Test
    fun `对账读数留在板上供回读`() {
        board(contract(expectedAsks = 9))
        assertNull("没结账前没有读数", PlanContractBoard.lastReport())
        val r = PlanContractBoard.reconcile()!!
        assertEquals(r, PlanContractBoard.lastReport())
        assertEquals(listOf(ContractFindingCode.ASKS_UNDER_ESTIMATE), r.findings.map { it.code })
        assertNull("「问少了」不回灌估算提示（只回灌问多了／档高了）", PlanContractBoard.estimateHint())
    }

    @Test
    fun `扩计划只放宽她点的那一格`() {
        val c = contract(steps = listOf("读原稿" to RiskClass.R0, "写产物" to RiskClass.R1, "收尾" to RiskClass.R1))
        board(c, items = listOf(item("读原稿", PlanRules.STATUS_DOING), item("写产物"), item("收尾")))
        // ① 越白名单 ⇒ 只加这一个目标
        val out = PlanContractBoard.checkFor(listOf(face(at("桌面/x.txt"))), RiskClass.R0, isWrite = true)
        assertEquals(ContractBreach.OUT_OF_TOUCHES, out.breach)
        assertTrue(PlanContractBoard.extendFor(out))
        val afterOut = PlanContractBoard.activeContract()!!
        assertEquals("白名单多了她同意的那一项", listOf("资料库/产物", at("桌面/x.txt")), afterOut.touches)
        assertEquals("步骤一格没动（负例④）", 3, afterOut.steps.size)
        assertEquals("计划步骤文字仍只有 items 那一份真源", listOf("读原稿", "写产物", "收尾"), afterOut.steps.map { it.intent })
        assertFalse("放宽之后再动同一个目标就不算越界", PlanContractBoard.checkFor(listOf(face(at("桌面/x.txt"))), RiskClass.R0, isWrite = true).violated)

        // ② 预估偏保守 ⇒ 只抬当前这一步，别的一步不动
        val over = PlanContractBoard.checkFor(listOf(face(at("资料库/产物/a.md"))), RiskClass.R3, isWrite = true)
        assertEquals(ContractBreach.RISK_OVER_ESTIMATE, over.breach)
        assertTrue(PlanContractBoard.extendFor(over))
        val after = PlanContractBoard.activeContract()!!
        assertEquals("抬的是她点的这一步", RiskClass.R3, after.risks[1])
        assertEquals("其余步骤的上限不许被顺带放宽", listOf(RiskClass.R1, RiskClass.R1), listOf(after.risks[2], after.risks[3]))
    }

    @Test
    fun `扩不动的违约返false_契约一个字不改`() {
        val c = contract(readonly = listOf("资料库/原始"))
        board(c)
        val before = PlanContractBoard.activeContract()
        val ro = PlanContractBoard.checkFor(listOf(face(at("资料库/原始/a.md"))), RiskClass.R2, isWrite = true)
        assertEquals(ContractBreach.WRITE_IN_READONLY, ro.breach)
        assertFalse(PlanContractBoard.extendFor(ro))
        assertEquals("扩不动就不许假扩", before, PlanContractBoard.activeContract())
    }

    @Test
    fun `计划更新同步当前步骤_上限跟着走`() {
        board(items = listOf(item("读原稿", PlanRules.STATUS_DOING), item("写产物")))
        assertEquals(1, PlanContractBoard.checkFor(listOf(face(at("桌面/x.txt"))), RiskClass.R0, isWrite = true).stepNo)
        PlanContractBoard.notePlan(twoSteps())
        assertEquals(2, PlanContractBoard.checkFor(listOf(face(at("桌面/x.txt"))), RiskClass.R0, isWrite = true).stepNo)
    }

    @Test
    fun `换轮清空_上限不跨轮`() {
        board()
        PlanContractBoard.noteAction(action())
        PlanContractBoard.noteAsk()
        PlanContractBoard.clear()
        assertFalse(PlanContractBoard.hasContract())
        assertEquals(0, PlanContractBoard.askCount())
        assertNull(PlanContractBoard.activeContract())
        // clear 之后 noteAction／noteAsk 一律短路（没契约就没有账）
        PlanContractBoard.noteAction(action())
        PlanContractBoard.noteAsk()
        assertEquals(0, PlanContractBoard.askCount())
        assertNull("换轮之后不结旧账", PlanContractBoard.reconcile())
    }
}
