// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百三十七批 §4：**八条负例逐条红**＋§1/§2/§2.5/§2.6/§3 的判据。
//
// 三条写法纪律（照仓里既有闸测）：
// 1. 判据用真注册表（`BaopuToolCatalog.loadInto`），不造假件——「候选必须先注册」这条红线
//    只有对着真表才验得出来（`browser_get_text` 归 228，今天不存在就是不存在）；
// 2. 三闸那几条用**合成 ToolSpec**：它们钉的是判据本身（权限档/readOnly/风险档），
//    不该随 219 表回填而漂移；
// 3. 有几条只有读源码才验得到（本文件不许有造工具出口、不许有转正出口、回灌签名里不许有明细类型），
//    所以另有 `源码闸_*` 三条；读源码前先剥整行注释（KDoc 里写「不 bumpUses」是说明，不是调用）。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.DecisionActor
import com.example.zhengdao.baopu.DecisionKind
import com.example.zhengdao.baopu.DecisionLedger
import com.example.zhengdao.baopu.RiskClass
import java.io.File
import kotlin.io.path.createTempDirectory
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolFallbackTest {

    private val sid = 7001L
    private val now = 1_760_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    // ── 夹具 ───────────────────────────────────────────────────────────────

    private fun registry(
        disabledGroups: Set<ToolGroup> = emptySet(),
        unlocked: Set<ToolGroup> = emptySet(),
    ): ToolRegistry {
        val root = createTempDirectory("zd-237-").toFile()
        val reg = ToolRegistry(InMemoryToolPrefs(initialGroups = disabledGroups))
        BaopuToolCatalog.loadInto(reg) { root }
        if (unlocked.isNotEmpty()) reg.unlockStore().unlock(sid, unlocked)
        return reg
    }

    private fun facts(
        tool: String,
        error: String,
        args: JSONObject? = null,
        kind: FailureKind = FailureKind.TOOL_ERROR,
        attempts: List<String> = emptyList(),
    ): FailureFacts {
        val call = if (args == null) null else ToolCall("call-$tool", tool, args)
        return FailureFacts(
            call = call,
            tool = tool,
            error = error,
            kind = kind,
            advice = ToolFailureAdvisor.advise(ToolFailureEvidence(tool, error, args, kind)),
            attempts = attempts,
        )
    }

    /** 合成规格（只喂三闸：权限档／只读否／风险档）。 */
    private fun spec(
        name: String,
        permission: ToolPermission = ToolPermission.FREE,
        readOnly: Boolean = true,
        tier: String = RiskClass.R0.name,
    ): ToolSpec = ToolSpec(
        name = name,
        description = "单测合成规格",
        inputSchema = JSONObject(),
        permission = permission,
        riskTier = tier,
        readOnly = readOnly,
        executor = { ToolOutcome.Ok("stub") },
    )

    private fun row(
        tool: String,
        group: ToolGroup = ToolGroup.BROWSER,
        atMs: Long = now,
        actor: DecisionActor = DecisionActor.MODEL,
        outcome: String = "问",
    ): FailureAuditRow =
        FailureAuditLog.row(
            tool = tool,
            group = group,
            stage = "③",
            risk = RiskBrief("R2", "dangerous_click", false, "单测"),
            actor = actor,
            outcome = outcome,
            evidence = "证据原文：选择器 #buy 不存在",
            atMs = atMs,
        )

    private fun cardOf(
        reg: ToolRegistry,
        f: FailureFacts = facts("browser_click", "CSS 选择器没命中：#buy", JSONObject().put("selector", "#buy")),
        drafts: List<FallbackDraft> = emptyList(),
    ): HelpCardSpec = ToolFallback.helpCard(f, reg, sid, drafts, now)

    /** 源码闸：剥掉整行注释再查（KDoc 里写「不许 promote」是说明，不是出口）。 */
    private fun code(name: String): String =
        File("src/main/java/com/example/zhengdao/baopu/agent", name)
            .readText()
            .lines()
            .filterNot {
                val t = it.trimStart()
                t.startsWith("//") || t.startsWith("/*") || t.startsWith("*")
            }
            .joinToString("\n")

    // ── §4 负例①：同一工具用同样参数无限重试 ───────────────────────────────

    @Test
    fun `负例1_同样参数不算换参数`() {
        val reg = registry()
        val args = JSONObject().put("path", "资料/表.csv")
        val base = facts("read_file", "读不了：没有那个文件", args)
        val same = base.copy(advice = base.advice.copy(suggestedArgs = JSONObject(args.toString())))
        assertNull(
            "建议参数与失败参数逐字相同 ⇒ 不是「换」，是撞同一堵墙",
            ToolFallback.argsRewrite(same, reg.find("read_file")),
        )
        val changed = base.copy(
            advice = base.advice.copy(suggestedArgs = JSONObject().put("path", "资料/表.xlsx")),
        )
        assertEquals(
            "资料/表.xlsx",
            ToolFallback.argsRewrite(changed, reg.find("read_file"))?.optString("path"),
        )
        val hallucinated = base.copy(
            advice = base.advice.copy(suggestedArgs = JSONObject().put("definitely_not_a_param", 1)),
        )
        assertNull(
            "schema 不认的键不许被当成换参数再塞回去",
            ToolFallback.argsRewrite(hallucinated, reg.find("read_file")),
        )
    }

    @Test
    fun `负例1_每一级只给一次`() {
        val reg = registry()
        val f = facts("read_file", "参数校验失败：缺少必填参数「path」", JSONObject())
        val first = ToolFallback.ladder(f, LadderCounters(), reg, sid, emptyList(), now)
        assertTrue("①级先给：$first", first is LadderPlan.RetryArgs)
        assertEquals("派工单 §1：只重试一次（常量不许改成配置）", 1, ToolFallback.MAX_STAGE_ATTEMPTS)
        // ①已用过、②的数据组没解锁 ⇒ 直接③，绝不回头再撞同一条参数
        val second = ToolFallback.ladder(f, LadderCounters().markStage1(), reg, sid, emptyList(), now)
        assertTrue("①用过 ⇒ 只剩③：$second", second is LadderPlan.Help)
    }

    @Test
    fun `负例1_点击类只给一次改写指令且不许改用JS`() {
        val reg = registry()
        val f = facts("browser_click", "CSS 选择器没命中：#buy", JSONObject().put("selector", "#buy"))
        val plan = ToolFallback.ladder(f, LadderCounters(), reg, sid, emptyList(), now)
        assertTrue("点击类的①级是「换定位」指令，不是自动重跑：$plan", plan is LadderPlan.InstructRetry)
        val note = (plan as LadderPlan.InstructRetry).note
        assertTrue(note.contains("只许再试这一次"))
        assertTrue("原话级红线：$note", note.contains("JS 执行点击"))
    }

    @Test
    fun `负例1_form_fill的①是换字段顺序且写明不提交`() {
        val f = facts("form_fill", "字段名对不上：收货人", JSONObject().put("fields", JSONObject().put("收货人", "张三")))
        val note = ToolFeedback.locatorNote(f)
        assertTrue("§1 那一行写的是换字段匹配顺序：$note", note.contains("换字段名的匹配顺序"))
        assertTrue("提交那一下不归模型：$note", note.contains("只填不提交"))
        assertFalse("填表不许拿换定位当答案：$note", note.contains("JS 执行点击"))
        // 点击类走另一条话，两条不许互相污染
        val click = ToolFeedback.locatorNote(
            facts("browser_click", "CSS 选择器没命中：#buy", JSONObject().put("selector", "#buy")),
        )
        assertFalse("点击类不许被套上表单的话：$click", click.contains("换字段名的匹配顺序"))
        assertTrue(click.contains("JS 执行点击"))
    }

    // ── §4 负例②：读失败降级成写工具（越权）───────────────────────────────

    @Test
    fun `负例2_读工具不许降级成写工具`() {
        val read = spec("read_file")
        assertFalse(ToolFallback.equivalentAllowed(read, spec("write_file", ToolPermission.CONFIRM, readOnly = false, tier = RiskClass.R2.name)))
        assertFalse(ToolFallback.equivalentAllowed(read, spec("delete_path", readOnly = false, tier = RiskClass.R3.name)))
        assertFalse(ToolFallback.equivalentAllowed(read, spec("move_path", ToolPermission.CONFIRM, readOnly = false)))
        // 只读→只读、档不升 ⇒ 允许（这一条不能被上面三条误伤）
        assertTrue(ToolFallback.equivalentAllowed(read, spec("sheet_read")))
        // 档升一级也不许（等价＝同档或更低）
        assertFalse(ToolFallback.equivalentAllowed(read, spec("browser_snapshot", tier = RiskClass.R1.name)))
        // 未入档的工具按 R2 fail-closed：它换到已知只读同档件才允许，往 R3 走一律拒
        assertEquals(2, ToolFallback.riskTierOf("不在219表里的工具"))
        assertFalse(ToolFallback.equivalentAllowed(spec("不在219表里的工具", tier = ""), spec("card_build", readOnly = false, tier = RiskClass.R3.name)))
    }

    @Test
    fun `负例2_点击失败不许换成用JS执行点击`() {
        val click = spec("browser_click", ToolPermission.CONFIRM, readOnly = true, tier = RiskClass.R2.name)
        listOf("browser_evaluate", "browser_run_js", "browser_script").forEach { name ->
            assertTrue("$name 必须点名禁", name in ToolFallback.FORBIDDEN_TARGETS)
            assertFalse(ToolFallback.equivalentAllowed(click, spec(name, ToolPermission.CONFIRM, tier = RiskClass.R3.name)))
        }
        // 而且点击/填表根本不在等价表里：它们的出路只有①级换定位（派工单 §1）
        assertFalse("browser_click 不该有等价候选", "browser_click" in ToolFallback.EQUIVALENT.keys)
        assertFalse("form_fill 不该有等价候选", "form_fill" in ToolFallback.EQUIVALENT.keys)
    }

    @Test
    fun `负例2_write不许换成shell写文件`() {
        val write = spec("write_file", ToolPermission.CONFIRM, readOnly = false, tier = RiskClass.R2.name)
        listOf("bash", "read").forEach { name ->
            assertTrue("$name 必须点名禁", name in ToolFallback.FORBIDDEN_TARGETS)
            assertFalse(ToolFallback.equivalentAllowed(write, spec(name, ToolPermission.CONFIRM, readOnly = false, tier = RiskClass.R3.name)))
        }
        // 整张等价表逐行自检：候选里一个写件、一个禁表都不许有
        ToolFallback.EQUIVALENT.forEach { (from, targets) ->
            targets.forEach { to ->
                assertFalse("$from → $to 命中点名禁表", to in ToolFallback.FORBIDDEN_TARGETS)
                assertFalse("$from → $to 是写件（读侧不许降级）", to in ToolFallback.WRITE_TOOLS)
            }
        }
        assertTrue("write_file 没有等价行（不许换成 shell）", "write_file" !in ToolFallback.EQUIVALENT.keys)
    }

    // ── §4 负例③：失败后自己造一个新工具（工具面固定）─────────────────────

    @Test
    fun `负例3_候选必须先注册_没交付的228工具不换`() {
        val reg = registry(unlocked = setOf(ToolGroup.BROWSER))
        assertNull("browser_get_text 归 228，今天没进 main", reg.find("browser_get_text"))
        assertNull(ToolFallback.equivalentSwap("browser_snapshot", reg, sid))
        val scan = ToolFallback.scanEquivalent("browser_snapshot", reg, sid)
        assertTrue("落选原因要点名「还没注册」：${scan.rejected}", scan.rejected.any { it.contains("还没注册") })
    }

    @Test
    fun `负例3_能力组没开就换不成_那是③的①选项`() {
        val reg = registry()
        assertNull("DATA 组默认没解锁 ⇒ 不许偷偷换", ToolFallback.equivalentSwap("read_file", reg, sid))
        val scan = ToolFallback.scanEquivalent("read_file", reg, sid)
        assertTrue("要说清是组没开：${scan.rejected}", scan.rejected.any { it.contains("能力组没开") })
        reg.unlockStore().unlock(sid, setOf(ToolGroup.DATA))
        assertEquals("人点了 capabilities 之后才有合法候选", "sheet_read", ToolFallback.equivalentSwap("read_file", reg, sid)?.name)
    }

    @Test
    fun `负例3_参数搬不动就不换_不许编一个URL`() {
        val reg = registry(unlocked = setOf(ToolGroup.WEBSEARCH))
        val f = facts("search_web", "检索没返回结果", JSONObject().put("query", "快递单号 SF123"))
        assertNull(
            "browser_open 要 url，query 搬不过去 ⇒ null（编一个 URL 就是造事实）",
            ToolFallback.carryArgs(reg.find("search_web")!!, reg.find("browser_open")!!, f.call?.arguments),
        )
        val plan = ToolFallback.ladder(f, LadderCounters().markStage1(), reg, sid, emptyList(), now)
        assertFalse("搬不动 ⇒ 不换工具：$plan", plan is LadderPlan.SwapTool)
    }

    @Test
    fun `源码闸_本文件没有造工具的出口`() {
        val c = code("ToolFallback.kt")
        assertFalse("不许注册工具", c.contains(".register("))
        assertFalse("不许合成新的 ToolSpec", c.contains("ToolSpec("))
        assertFalse("不许自带执行器", c.contains("executor ="))
    }

    // ── §4 负例④：自动展开 capabilities（没人点）─────────────────────────

    @Test
    fun `负例4_开能力只是发一次capabilities调用`() {
        val call = ToolFallback.groupEnableCall("browser", "c-1")
        assertEquals("capabilities", call.name)
        val enable = call.arguments.optJSONArray("enable")
        assertNotNull(enable)
        assertEquals(1, enable!!.length())
        assertEquals("browser", enable.optString(0))
    }

    @Test
    fun `负例4_①选项列工具但不超过5个且写明还要人点`() {
        val card = cardOf(registry())
        val option = card.options.first { it.kind == HelpOptionKind.GROUP }
        assertTrue(option.detail.contains("还要再确认"))
        assertTrue(option.detail.contains("我不会自己开"))
        assertTrue("最多 5 个：${option.tools}", option.tools.size <= ToolFallback.MAX_GROUP_TOOL_LIST)
        assertTrue(option.tools.isNotEmpty())
        assertEquals("group:browser", option.id)
        assertEquals(5, ToolFallback.MAX_GROUP_TOOL_LIST)
    }

    @Test
    fun `负例4_被用户在设置里关掉的组不假装点一下就开`() {
        val card = cardOf(registry(disabledGroups = setOf(ToolGroup.BROWSER)))
        assertFalse("被关的组不许给①选项", card.options.any { it.kind == HelpOptionKind.GROUP })
        assertTrue("标题要照实说被关了：${card.title}", card.title.contains("被你在设置里关掉了"))
    }

    @Test
    fun `源码闸_本文件没有替人点确认的出口`() {
        val c = code("ToolFallback.kt")
        listOf("unlockStore(", ".unlock(", "setGroupDisabled", "setGroupApproved", "approvedGroups =", "disabledGroups =")
            .forEach {
                assertFalse("授权必须走 capabilities→御批卡，源码不许出现 $it", c.contains(it))
            }
    }

    // ── §4 负例⑤：自动把草稿技能转正 ─────────────────────────────────────

    @Test
    fun `负例5_只本次允许草稿_uses不变且账记不可回滚`() {
        val record = ToolFallback.draftAllowedRecord("网页下单", uses = 0, sessionId = sid, nowMs = now)
        assertEquals(DecisionKind.AUTO_ACT, record.kind)
        assertEquals(DecisionActor.USER, record.actor)
        assertEquals("本次允许没有可撤销的持久状态 ⇒ 标可回滚就是骗人", false, record.reversible)
        assertTrue(record.before.contains("uses=0"))
        assertTrue("after 里 uses 必须还是 0：${record.after}", record.after.contains("uses=0"))
        assertFalse(record.after.contains("正式"))
        val note = ToolFallback.draftAllowedNote("网页下单", 0, "先滚到底再点购买")
        assertTrue(note.contains("uses 仍为 0"))
        assertTrue(note.contains("仍是草稿"))
    }

    @Test
    fun `负例5_②选项写明只这一次生效`() {
        val draft = FallbackDraft("网页下单", 0, "在网页上点击购买按钮")
        val card = cardOf(registry(), drafts = listOf(draft))
        val option = card.options.first { it.kind == HelpOptionKind.DRAFT_SKILL }
        assertEquals("draft:网页下单", option.id)
        assertTrue(option.detail.contains("uses=0"))
        assertTrue(option.detail.contains("uses 不变"))
        assertTrue(option.detail.contains("不转正"))
    }

    @Test
    fun `负例5_没有草稿命中就不给②选项`() {
        // 词表来自组 oneLine 与工具名（browser/click/网页操作/点击/输入/滚动/截图），
        // 所以这条草稿的说法要**一个词都不沾**，否则测的是「沾词」而不是「不命中」。
        val card = cardOf(registry(), drafts = listOf(FallbackDraft("照片调黑白", 0, "把相册里的导出物转成灰度")))
        assertFalse(card.options.any { it.kind == HelpOptionKind.DRAFT_SKILL })
    }

    @Test
    fun `源码闸_本文件没有转正与自写技能出口`() {
        val c = code("ToolFallback.kt")
        listOf("bumpUses", "promote", "commitSkill", "draftToFormal", "write_skill(", "SkillRules.write").forEach {
            assertFalse("转正是治理器的事，源码不许出现 $it", c.contains(it))
        }
    }

    // ── §4 负例⑥：求助卡连弹多张／写成一段长文 ───────────────────────────

    @Test
    fun `负例6_卡是二到四个可点选项不是一段长文`() {
        val card = cardOf(
            registry(),
            drafts = listOf(FallbackDraft("网页下单", 0, "在网页上点击购买按钮")),
        )
        assertTrue("至少 ${AskUserTool.MIN_OPTIONS} 个：${card.options.size}", card.options.size >= AskUserTool.MIN_OPTIONS)
        assertTrue("最多 ${AskUserTool.MAX_OPTIONS} 个：${card.options.size}", card.options.size <= AskUserTool.MAX_OPTIONS)
        assertEquals("选项 id 不许重复", card.options.size, card.options.map { it.id }.distinct().size)
        card.options.forEach {
            assertTrue("每个选项都要有可点文本", it.label.isNotBlank() && it.detail.isNotBlank())
        }
        val ask = card.toAskRequest()
        assertEquals("help:browser_click", ask.subject)
        assertEquals(card.options.size, ask.options.size)
        assertEquals(1, ToolFallback.MAX_HELP_CARDS_PER_RUN)
    }

    @Test
    fun `负例6_没有草稿也没有被关组时卡仍是三项`() {
        val card = cardOf(registry())
        val kinds = card.options.map { it.kind }
        assertTrue("①能力组：$kinds", HelpOptionKind.GROUP in kinds)
        assertTrue("③现做技能恒在：$kinds", HelpOptionKind.MAKE_SKILL in kinds)
        assertTrue("④告诉我该怎么做恒在：$kinds", HelpOptionKind.ASK in kinds)
        assertEquals(3, card.options.size)
        assertEquals(ToolFeedback.OPTION_MAKE_SKILL, card.options[1].id)
        assertEquals(ToolFeedback.OPTION_ASK, card.options.last().id)
    }

    @Test
    fun `负例6_一轮最多一张_第二张被挡住`() {
        val reg = registry()
        val f = facts("browser_click", "CSS 选择器没命中：#buy", JSONObject().put("selector", "#buy"))
        val counters = LadderCounters().markStage1().markStage2().markHelp()
        val plan = ToolFallback.ladder(f, counters, reg, sid, emptyList(), now)
        assertTrue("弹过之后只剩如实收尾：$plan", plan is LadderPlan.Stop)
    }

    // ── §4 负例⑦：无证据的「可能原因」 ───────────────────────────────────

    @Test
    fun `负例7_归因不出来就不编推测`() {
        val blind = facts("read_file", "读不了", JSONObject().put("path", "资料/a.txt")).copy(
            advice = ToolRepairAdvice(
                toolName = "read_file",
                type = ToolFailureType.UNKNOWN,
                cause = ToolFailureCause.UNKNOWN,
                suggestion = ToolRepairSuggestion.NONE,
                reason = "",
                action = "",
            ),
        )
        assertNull(ToolFallback.inferredOf(blind))
        val ctx = CardContext(
            risk = RiskBrief("R2", "none", false, "按档处理"),
            inference = null,
            audit = AuditBrief("读文件", 0, ""),
            impact = ImpactBrief("读 资料/a.txt（只读，不改任何东西）", true, "最多白读一次，盘上一个字节都不动"),
        )
        val sections = ctx.cardSections()
        assertEquals(listOf("分类器", "推演", "审计", "后果"), sections.map { it.first })
        assertEquals("推演段不许删，但要照实说没线索", "错误原文里没有可归因的线索，不编原因", sections[1].second.first())
    }

    @Test
    fun `负例7_推测带证据行`() {
        val f = facts("read_file", "参数校验失败：缺少必填参数「path」", JSONObject())
        val inferred = ToolFallback.inferredOf(f)
        assertNotNull(inferred)
        assertTrue(inferred!!.feedbackLine().startsWith("推测："))
        assertTrue(inferred.cardLines().any { it.startsWith("证据：") && it.contains("read_file") })
    }

    @Test
    fun `负例7_后果不许是空话`() {
        val reg = registry()
        listOf(
            facts("read_file", "读不了", JSONObject().put("path", "资料/a.txt")),
            facts("write_file", "写不了", JSONObject().put("path", "资料/a.txt")),
            facts("delete_path", "删不掉", JSONObject().put("path", "资料/a.txt")),
            facts("browser_click", "找不到", JSONObject().put("selector", "#buy")),
        ).forEach { f ->
            val impact = ToolFallback.impactBrief(f, reg.find(f.tool))
            listOf(impact.action, impact.worst).forEach { line ->
                assertFalse("空话词表命中：$line", ToolFeedback.isVague(line))
                assertTrue("后果要具体动作：$line", line.isNotBlank())
            }
        }
        listOf("可能有影响", "或有影响", "视情况", "影响未知", "可能会有一些影响").forEach {
            assertTrue("空话词表要收得住它：$it", ToolFeedback.isVague("这一步$it"))
        }
    }

    // ── §4 负例⑧：用户忽略卡后仍反复弹同一张 ─────────────────────────────

    @Test
    fun `负例8_忽略之后如实收尾`() {
        val line = ToolFeedback.ignoredLine("browser_click")
        assertTrue(line.contains("没做成"))
        assertTrue(line.contains("别再弹"))
        val stopped = ToolFallback.ladder(
            facts("browser_click", "CSS 选择器没命中：#buy", JSONObject().put("selector", "#buy")),
            LadderCounters().markHelp(),
            registry(),
            sid,
            emptyList(),
            now,
        )
        assertTrue(stopped is LadderPlan.Stop)
        assertTrue((stopped as LadderPlan.Stop).line.contains("没做成"))
    }

    // ── §2.5 四件载荷与回灌分界 ───────────────────────────────────────────

    @Test
    fun `227口径_四件按分界回灌`() {
        val reg = registry()
        // 用一条**归得出来**的错误（115 按原文点名缺哪个参数），否则 [ToolFallback.inferredOf]
        // 照负例⑦返回 null，这一用例测的就不是「推演只回灌结论句」。
        val f = facts("browser_click", "缺少必填参数「ref」", JSONObject().put("selector", "#buy"))
        val risk = ToolFallback.riskBrief(f)
        val inferred = ToolFallback.inferredOf(f)
        val impact = ToolFallback.impactBrief(f, reg.find("browser_click"))
        val audit = ToolFallback.auditBrief(f, ToolGroup.BROWSER, listOf(row("browser_click", atMs = now - day)), now)
        val text = ToolFeedback.feedbackForModel(risk, inferred, audit, impact, outcome = "换参数再试一次")
        assertTrue("分类器完整回灌", text.contains(risk.feedbackLine()))
        assertTrue("推演只回灌结论句且带「推测」", inferred != null && text.contains(inferred.feedbackLine()))
        assertTrue("审计要看得见那一句聚合（删掉它本用例就红）", text.contains(audit.feedbackLine()))
        assertTrue("后果一句", text.contains("后果："))
        assertTrue("审计聚合 ≤40：${audit.feedbackLine()}", audit.feedbackLine().length <= ToolFallback.AGG_MAX_CHARS)
        assertTrue("后果回灌 ≤40：${impact.feedbackLine(risk.tier)}", impact.feedbackLine(risk.tier).length <= ToolFeedback.MAX_LINE_CHARS)
    }

    @Test
    fun `227口径_台账明细绝不进上下文`() {
        val reg = registry()
        val f = facts("browser_click", "CSS 选择器没命中：#buy", JSONObject().put("selector", "#buy"))
        val rows = List(8) { row("browser_click", atMs = now - it) }
        val audit = ToolFallback.auditBrief(f, ToolGroup.BROWSER, rows, now)
        val text = ToolFeedback.feedbackForModel(
            risk = ToolFallback.riskBrief(f),
            inference = ToolFallback.inferredOf(f),
            audit = audit,
            impact = ToolFallback.impactBrief(f, reg.find("browser_click")),
            outcome = "停下问人",
        )
        assertFalse("明细的证据原文不许出现", text.contains("证据原文"))
        assertEquals("聚合只剩次数与标签：${audit.feedbackLine()}", 8, audit.count)
        val card = cardOf(reg, f)
        val auditSection = card.context.cardSections().first { it.first == "审计" }.second
        assertEquals("卡上审计段＝一句聚合＋一句指路", 2, auditSection.size)
        assertFalse(auditSection.any { it.contains("证据原文") })
        assertTrue(auditSection[1].contains("决策中心"))
    }

    @Test
    fun `227口径_审计带上次人的裁决`() {
        val f = facts("browser_click", "CSS 选择器没命中：#buy", JSONObject().put("selector", "#buy"))
        // 行的顺序按 [FailureAuditLog.readAll] 的口径给（新→旧），所以「上次」＝第一条人的行。
        val rows = listOf(
            row("browser_click", atMs = now, outcome = "本轮停下"),
            row("browser_click", atMs = now - 2 * day, actor = DecisionActor.USER, outcome = "用户选了「开启「网页操作」能力」"),
            row("browser_click", atMs = now - 5 * day, actor = DecisionActor.USER, outcome = "更早的一条"),
        )
        val audit = ToolFallback.auditBrief(f, ToolGroup.BROWSER, rows, now)
        assertEquals("只取最近一条人的行，且剥掉「用户选了」的外壳", "开启「网页操作」能力", audit.lastVerdict)
        assertEquals("次数算全部三条", 3, audit.count)
        assertTrue("回灌那句要带裁决：${audit.feedbackLine()}", audit.feedbackLine().contains("上次判："))
        assertTrue("带上裁决也不许破 40 字：${audit.feedbackLine()}", audit.feedbackLine().length <= ToolFallback.AGG_MAX_CHARS)
        // 只有模型自己的行 ⇒ 没有「人的裁决」可说（不许拿模型的判定冒充人点过）
        val noHuman = ToolFallback.auditBrief(
            f,
            ToolGroup.BROWSER,
            listOf(row("browser_click", atMs = now), row("browser_click", atMs = now - day)),
            now,
        )
        assertEquals("", noHuman.lastVerdict)
        assertFalse(noHuman.feedbackLine().contains("上次判"))
        // 窗口外的人的裁决不算（§2.5 说的是「近 7 天」）
        val outOfWindow = ToolFallback.auditBrief(
            f,
            ToolGroup.BROWSER,
            listOf(row("browser_click", atMs = now - 30 * day, actor = DecisionActor.USER, outcome = "上月点的")),
            now,
        )
        assertEquals("7 天外的裁决不算", "", outOfWindow.lastVerdict)
        // 四个真选项的名字都要装得进裁决位，否则截出来的是半个工具名（等于没说）
        listOf(
            "开启「browser」能力",
            "本条技能是草稿，允许这次使用",
            "让我先做一个技能再执行",
            "告诉我该怎么做",
        ).forEach {
            assertTrue("选项名超出裁决位 ${ToolFallback.VERDICT_MAX_CHARS} 字：$it", it.length <= ToolFallback.VERDICT_MAX_CHARS)
        }
    }

    @Test
    fun `227口径_分类器结论用219与223的原文`() {
        val risk = ToolFallback.riskBrief(facts("browser_click", "点不到", JSONObject().put("selector", "#buy")))
        assertEquals(RiskClass.R2.name, risk.tier)
        assertTrue("硬拒位由矩阵给", risk.reasonCode.isNotBlank())
        assertTrue(risk.feedbackLine().contains(risk.tier))
    }

    @Test
    fun `227口径_R3与不可逆要红字确认位`() {
        val reg = registry()
        val card = cardOf(reg)
        assertTrue("点击不可逆 ⇒ 红字位", card.context.needsRedConfirm)
        val readCtx = ToolFallback.cardContext(
            facts("read_file", "读不了", JSONObject().put("path", "资料/a.txt")),
            reg,
            reg.find("read_file"),
            ToolGroup.CORE,
            now,
        )
        assertFalse("只读且不花钱 ⇒ 不必红字", readCtx.needsRedConfirm)
        val r3 = CardContext(
            risk = RiskBrief(RiskClass.R3.name, "spend", false, "花钱"),
            inference = null,
            audit = AuditBrief("生图", 0, ""),
            impact = ImpactBrief("生成一张图", true, "烧额度"),
        )
        assertTrue(r3.needsRedConfirm)
    }

    @Test
    fun `227口径_④提问卡最多三问每问带证据`() {
        val card = cardOf(registry())
        val ask = card.options.first { it.kind == HelpOptionKind.ASK }
        assertTrue(ask.questions.size <= ToolFallback.MAX_QUESTIONS)
        assertEquals(3, ToolFallback.MAX_QUESTIONS)
        assertTrue(ask.questions.all { it.isNotBlank() })
        assertTrue("第一问要带当前目标证据：${ask.questions.first()}", ask.questions.first().contains("#buy"))
        val request = card.questionCardRequest()
        assertEquals(ask.questions.size, request.questions.size)
        request.questions.forEach { assertTrue(it.title.isNotBlank()) }
        assertEquals("q1", request.questions.first().id)
    }

    @Test
    fun `227口径_③选项照实说不当场编假技能`() {
        val card = cardOf(registry())
        val make = card.options.first { it.kind == HelpOptionKind.MAKE_SKILL }
        assertTrue(make.detail.contains("草稿"))
        assertTrue(make.detail.contains("不当场编一条假技能"))
    }

    // ── §2.6 审计明细落点（只读账＋分页）─────────────────────────────────

    @Test
    fun `226审计_没装配不落_缺证据不落`() {
        val previous = DecisionLedger.installedRoot()
        DecisionLedger.uninstallForTest()
        try {
            assertFalse("没装配落点就不落（照 208）", FailureAuditLog.append(row("browser_click")))
            assertTrue(FailureAuditLog.readAll().isEmpty())
            val root = createTempDirectory("zd-237-ledger-").toFile()
            DecisionLedger.install(root)
            val noEvidence = row("browser_click").copy(evidence = "  ")
            assertFalse("缺证据不落：宁可无账", FailureAuditLog.append(noEvidence))
            val noTime = row("browser_click").copy(atMs = 0L)
            assertFalse("缺时刻不落", FailureAuditLog.append(noTime))
            assertTrue(FailureAuditLog.readAll().isEmpty())
        } finally {
            previous?.let { DecisionLedger.install(it) } ?: DecisionLedger.uninstallForTest()
        }
    }

    @Test
    fun `226审计_一次最多50行且加载更多`() {
        val previous = DecisionLedger.installedRoot()
        val root = createTempDirectory("zd-237-ledger-").toFile()
        DecisionLedger.install(root)
        try {
            repeat(60) { i ->
                assertTrue("第 $i 行没落上：${FailureAuditLog.lastError}", FailureAuditLog.append(row("browser_click", atMs = now - i)))
            }
            val all = FailureAuditLog.readAll()
            assertEquals(60, all.size)
            assertEquals("新→旧", now, all.first().atMs)
            assertEquals(now - 59, all.last().atMs)
            val (firstPage, hasMore) = FailureAuditLog.page(0)
            assertEquals(FailureAuditLog.PAGE, firstPage.size)
            assertTrue(hasMore)
            val (tail, noMore) = FailureAuditLog.page(FailureAuditLog.PAGE)
            assertEquals(10, tail.size)
            assertFalse(noMore)
            assertEquals(50, FailureAuditLog.PAGE)
            // 坏行跳过，不当成别的类
            val ledgerFile = File(root, FailureAuditLog.FILE_NAME)
            ledgerFile.appendText("这不是 JSON\n", Charsets.UTF_8)
            ledgerFile.appendText("""{"id":"","tool":"","at":0}""" + "\n", Charsets.UTF_8)
            assertEquals(60, FailureAuditLog.readAll().size)
            assertNull(FailureAuditLog.parse("{{{ 不是 JSON"))
        } finally {
            previous?.let { DecisionLedger.install(it) } ?: DecisionLedger.uninstallForTest()
        }
    }

    @Test
    fun `226审计_行键名稳定`() {
        val json = row("browser_click").toJson()
        listOf("v", "id", "at", "tool", "group", "stage", "risk", "reason", "actor", "outcome", "evidence").forEach {
            assertTrue("少键 $it：$json", json.has(it))
        }
        val back = FailureAuditRow.fromJson(json)!!
        assertEquals("browser_click", back.tool)
        assertEquals("browser", back.groupKey)
        assertEquals("model", back.actor)
        assertTrue(back.matches("browser_click", ToolGroup.BROWSER))
        assertTrue("同组也算同类（§3 的『同一能力』）", back.matches("browser_type", ToolGroup.BROWSER))
        assertFalse(back.matches("read_file", ToolGroup.CORE))
    }

    // ── §3 聚合一行 ───────────────────────────────────────────────────────

    @Test
    fun `3聚合_三次才说一句且不超过40字`() {
        val rows = listOf(
            row("browser_click", atMs = now - day),
            row("browser_click", atMs = now - 2 * day),
            row("browser_type", atMs = now - 3 * day),
        )
        val line = ToolFallback.aggregateLine("browser_click", ToolGroup.BROWSER, rows, now)
        assertNotNull(line)
        assertTrue("≤40 字：$line", line!!.length <= ToolFallback.AGG_MAX_CHARS)
        assertTrue(line.contains("浏览器点击"))
        assertTrue("要说出开哪一组：$line", line.contains("要不要开「网页操作」"))
        assertNull("两次不打扰", ToolFallback.aggregateLine("browser_click", ToolGroup.BROWSER, rows.take(2), now))
        assertNull(
            "窗口外不打扰",
            ToolFallback.aggregateLine("browser_click", ToolGroup.BROWSER, listOf(row("browser_click", atMs = now - 30 * day)), now),
        )
        val core = ToolFallback.aggregateLine(
            "read_file",
            ToolGroup.CORE,
            List(3) { row("read_file", ToolGroup.CORE, atMs = now - it) },
            now,
        )
        assertNotNull(core)
        assertTrue("默认可见的组本来就开着，不许问『要不要开』：$core", core!!.contains("要不要换个做法"))
    }

    // ── §1 梯子顺序与常量 ─────────────────────────────────────────────────

    @Test
    fun `1梯子_三级顺序_①→②→③`() {
        val reg = registry(unlocked = setOf(ToolGroup.DATA))
        val f = facts("read_file", "读不了：没有那个文件", JSONObject().put("path", "资料/表.csv"))
        val stage2 = ToolFallback.ladder(f, LadderCounters().markStage1(), reg, sid, emptyList(), now)
        assertTrue("②级换等价工具（只读→只读、同档）：$stage2", stage2 is LadderPlan.SwapTool)
        val swap = stage2 as LadderPlan.SwapTool
        assertEquals("read_file", swap.from)
        assertEquals("sheet_read", swap.to)
        assertEquals("资料/表.csv", swap.args.optString("path"))
        assertTrue("只读免确认 ⇒ 引擎可自己再跑这一次", swap.autoRun)
        assertTrue(swap.why.contains("等价降级"))
        val stage3 = ToolFallback.ladder(f, LadderCounters().markStage1().markStage2(), reg, sid, emptyList(), now)
        assertTrue("①②走完 ⇒ ③求助卡：$stage3", stage3 is LadderPlan.Help)
    }

    @Test
    fun `1梯子_CONFIRM目标不许自动执行`() {
        val reg = registry(unlocked = setOf(ToolGroup.BROWSER))
        // 解锁 BROWSER 才拿得到**真**规格（没解锁时 find 回的是免确认桩，测不出这一条）
        val click = reg.find("browser_click")!!
        assertFalse("风险档 R2 的交互件不许引擎自己再跑", ToolFallback.autoRunnable(click))
        assertFalse(ToolFallback.autoRunnable(spec("要确认的写件", ToolPermission.CONFIRM, readOnly = false, tier = RiskClass.R2.name)))
        assertTrue("只读＋免确认＋≤R1 才算能自动跑", ToolFallback.autoRunnable(reg.find("read_file")!!))
    }

    @Test
    fun `常量_一次一卡五工具三问`() {
        assertEquals(1, ToolFallback.MAX_STAGE_ATTEMPTS)
        assertEquals(1, ToolFallback.MAX_HELP_CARDS_PER_RUN)
        assertEquals(5, ToolFallback.MAX_GROUP_TOOL_LIST)
        assertEquals(3, ToolFallback.MAX_QUESTIONS)
        assertEquals(40, ToolFallback.AGG_MAX_CHARS)
        assertEquals(3, ToolFallback.AGG_MIN_EVENTS)
        assertEquals(7 * day, ToolFallback.AGG_WINDOW_MS)
    }

    @Test
    fun `拒绝原文锚_59批桩文案逐字`() {
        val reg = registry()
        val locked = reg.find("browser_click")!!.executor(JSONObject().put("selector", "#buy"))
        val text = when (locked) {
            is ToolOutcome.Rejected -> locked.reason
            is ToolOutcome.Failed -> locked.error
            else -> ""
        }
        assertTrue("锚要对得上桩文案：$text", ToolFallback.isGroupLockedRejection(text) || text.contains("browser"))
        assertTrue(ToolFallback.isGroupLockedRejection("该工具组未解锁：browser（要先 capabilities 解锁）"))
        assertTrue(ToolFallback.isUserDisabledRejection("该工具已被用户关闭：read_file"))
        assertFalse(ToolFallback.isGroupLockedRejection("读不了：没有那个文件"))
    }
}
