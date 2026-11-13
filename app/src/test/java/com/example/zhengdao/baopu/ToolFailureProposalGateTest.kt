// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百二十批 · 连环失败提案的门禁（派工单判据①②③④）。
//
// 三条行为判据都用「夹具」跑真 [ProposalRules.generate]，不读源码充数；
// 另配源码门禁钉住接线（读取侧带 err）与「不改失败→记账链路」。
// 每条门禁都带负例样本 —— 门禁自己必须能红，否则只是三行 assertTrue。
package com.example.zhengdao.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ToolFailureProposalGateTest {

    private val now = 1_700_000_000_000L

    /** 一条失败账行；err 就是 run_log.err 已有的错误线索（空＝账上没留）。 */
    private fun failed(id: Long, tool: String, err: String) = ProposalRules.ToolRun(
        id = id,
        tool = tool,
        ok = false,
        atMs = id * 1_000L,
        err = err,
    )

    private fun generate(tools: List<ProposalRules.ToolRun>, pending: Set<String> = emptySet()) =
        ProposalRules.generate(
            ProposalRules.Input(
                sessionId = 1L,
                nowMs = now,
                toolRuns = tools,
                pendingIds = pending,
            ),
        )

    private fun ruleCards(r: ProposalRules.Result) =
        r.candidates.filter { it.kind == ProposalKind.TOOL_RULE }

    // ── 判据①：5 个「路径不存在」⇒ 0 张卡，只有账行 ─────────────────────────

    private val pathAbsent = listOf(
        "源不存在：草稿/a.md",
        "文件不存在：b.md",
        "目录不存在：产物/",
        "目标目录不存在：/ws/产物/",
        "路径里有一层不存在",
    )

    /** 门禁：环境类失败不许弹任何工具约束卡。 */
    private fun noRuleCard(r: ProposalRules.Result): Boolean = ruleCards(r).isEmpty()

    @Test
    fun `判据1_五个路径不存在失败_零张提案卡且账行仍在`() {
        val tools = pathAbsent.mapIndexed { i, err -> failed(i + 1L, "read_file", err) }
        val r = generate(tools)
        assertTrue("环境类失败不许弹约束卡：${r.candidates}", noRuleCard(r))
        assertEquals("账行一条都不能少（只记账、不删账）", 5, tools.size)
        assertEquals("五条都要留「只记账」的跳过说明", 5, r.skipped.size)
        assertTrue(
            "跳过原因要写明分类与「不提约束模型」：${r.skipped}",
            r.skipped.all { it.reason.contains("环境类") && it.reason.contains("只记账，不提约束模型") },
        )
        assertEquals("本轮不产生任何候选", 0, r.candidates.size)
    }

    @Test
    fun `判据1_负例_环境类若弹一张卡_门禁必须红`() {
        val forged = ProposalRules.Result(
            candidates = listOf(
                ProposalCandidate(
                    id = "forged",
                    kind = ProposalKind.TOOL_RULE,
                    target = "read_file",
                    payload = "工具使用约束：别乱重试",
                    createdAt = now,
                ),
            ),
            skipped = emptyList(),
            dropped = 0,
        )
        assertFalse("负例：环境类弹了卡，noRuleCard 必须判 false", noRuleCard(forged))
    }

    @Test
    fun `判据1_环境四类各自都不提约束`() {
        val envTexts = listOf(
            "路径不存在：草稿/a.md",
            "权限不足：原件区只读",
            "该工具组未解锁（默认不可见）",
            "参数解析失败：不是合法 JSON",
        )
        for (text in envTexts) {
            val a = ToolFailureAttribution.classify(text)
            assertEquals("「$text」应归环境类", FailureOwner.ENVIRONMENT, a.owner)
            val r = generate((1L..5L).map { failed(it, "read_file", text) })
            assertTrue("「$text」不许弹卡：${r.candidates}", noRuleCard(r))
        }
    }

    // ── 判据②：3 个「模型参数错」⇒ 恰好 1 张卡，正文含分类与依据 ─────────────

    /** 门禁：卡正文必须写明失败分类与依据。 */
    private fun hasClassAndBasis(payload: String): Boolean =
        payload.contains("失败分类：") && payload.contains("依据：")

    @Test
    fun `判据2_三个模型参数错_恰好一张卡且正文含分类与依据`() {
        val tools = listOf(
            failed(1, "write_file", "缺少必填参数「path」"),
            failed(2, "read_file", "参数「limit」类型不对：期望 整数，实到 字符串"),
            failed(3, "edit_file", "未知参数「limit」（本工具不接受这个参数）"),
        )
        val r = generate(tools)
        val cards = ruleCards(r)
        assertEquals("三个同根因失败只能提一次：${r.candidates}", 1, cards.size)
        val card = cards.single()
        assertTrue("正文必须写失败分类＋依据：${card.payload}", hasClassAndBasis(card.payload))
        assertTrue("分类要落到行为类：${card.payload}", card.payload.contains("行为类"))
        assertTrue("依据要写出命中的线索：${card.payload}", card.payload.contains("线索"))
        assertEquals("证据引用这三条账行", listOf(1L, 2L, 3L), card.evidence.runLogIds)
        assertEquals(ProposalStatus.PENDING, card.status)
    }

    @Test
    fun `判据2_负例_旧正文没有分类与依据_门禁必须红`() {
        val oldPayload = "工具「write_file」在同一会话里重复失败。" +
            "工具使用约束：同一工具连续失败两次就停下，换路径/换参数或问人，不要原样重试。"
        assertFalse("负例：不带分类与依据的旧正文必须被判 false", hasClassAndBasis(oldPayload))
    }

    // ── 判据③：同根因重复失败 ⇒ 不叠加（幂等）───────────────────────────────

    @Test
    fun `判据3_同一根因重复失败_不叠加且id稳定`() {
        val err = "缺少必填参数「path」"
        val three = generate((1L..3L).map { failed(it, "write_file", err) })
        val ten = generate((1L..10L).map { failed(it, "write_file", err) })
        assertEquals("三条只提一次", 1, ruleCards(three).size)
        assertEquals("十条仍只提一次", 1, ruleCards(ten).size)
        assertEquals(
            "次数不进正文 ⇒ id 稳定 ⇒ 跨轮幂等",
            ruleCards(three).single().id,
            ruleCards(ten).single().id,
        )
        assertEquals("证据最多引用 8 条", 8, ruleCards(ten).single().evidence.runLogIds.size)
    }

    @Test
    fun `判据3_同轮多个行为根因_仍然只提一张`() {
        val tools = listOf(
            failed(1, "write_file", "缺少必填参数「path」"),
            failed(2, "write_file", "未知参数「limit」"),
            failed(3, "read_file", "未知工具「read_thing」（已注册：read_file）"),
            failed(4, "read_file", "未知工具「read_thing2」（已注册：read_file）"),
        )
        val r = generate(tools)
        assertEquals("同轮内限一次：两个根因也只弹一张：${r.candidates}", 1, ruleCards(r).size)
        assertTrue(
            "被压掉的根因要留痕不静默：${r.skipped}",
            r.skipped.any { it.reason.contains("同轮已提过") },
        )
    }

    @Test
    fun `判据3_上一轮已PENDING_本轮不再叠加`() {
        val err = "缺少必填参数「path」"
        val first = generate((1L..3L).map { failed(it, "write_file", err) })
        val id = ruleCards(first).single().id
        val again = generate((4L..6L).map { failed(it, "write_file", err) }, pending = setOf(id))
        assertEquals("同 hash 已在 PENDING ⇒ 不重复生成", 0, ruleCards(again).size)
    }

    // ── 混合与兼容：环境类不污染行为类；无线索保持既有口径 ───────────────────

    @Test
    fun `混合_五次环境加三次行为_只出一张行为卡`() {
        val env = pathAbsent.mapIndexed { i, err -> failed(i + 1L, "read_file", err) }
        val behavior = (6L..8L).map { failed(it, "write_file", "缺少必填参数「path」") }
        val r = generate(env + behavior)
        assertEquals("环境类不参与计数：${r.candidates}", 1, ruleCards(r).size)
        assertEquals("行为类证据只引用行为类账行", listOf(6L, 7L, 8L), ruleCards(r).single().evidence.runLogIds)
        assertEquals("环境类五条各自留痕", 5, r.skipped.count { it.reason.contains("只记账") })
    }

    @Test
    fun `兼容_账行没留线索_不硬猜_按既有口径出卡`() {
        val tools = (1L..3L).map { failed(it, "write_file", "") }
        val r = generate(tools)
        assertEquals("无线索仍走既有口径", 1, ruleCards(r).size)
        assertEquals(FailureOwner.UNATTRIBUTED, ToolFailureAttribution.classify("").owner)
        assertTrue("未归因卡正文也要写明分类与依据", hasClassAndBasis(ruleCards(r).single().payload))
        assertTrue("未归因不许冒充行为类", ruleCards(r).single().payload.contains("未归因"))
    }

    // ── 归因纯函数：三态闭集 + 优先级 ──────────────────────────────────────

    @Test
    fun `归因_行为三类各自归位_环境优先不被覆盖`() {
        val behavior = listOf(
            "未知工具「x」（已注册：read_file）",
            "缺少必填参数「path」",
            "重试无效：原样重试仍失败",
        )
        for (text in behavior) {
            assertEquals("「$text」应归行为类", FailureOwner.BEHAVIOR, ToolFailureAttribution.classify(text).owner)
        }
        assertEquals(
            "环境线索与行为线索同时出现 ⇒ 环境优先（宁可不提也不错提）",
            FailureOwner.ENVIRONMENT,
            ToolFailureAttribution.classify("路径不存在，请改用别的写法").owner,
        )
        assertEquals(
            "看不懂的原文不硬猜",
            FailureOwner.UNATTRIBUTED,
            ToolFailureAttribution.classify("某某内部状态异常").owner,
        )
    }

    @Test
    fun `归因_参数类错误共用一个根因码_不拆成多张卡`() {
        val kinds = listOf(
            "缺少必填参数「path」",
            "未知参数「limit」",
            "参数「limit」类型不对：期望 整数",
            "参数「mode」取值不在允许范围（只读、追加）",
        )
        assertEquals(
            "四种参数错都是同一件事，根因必须一样",
            1,
            kinds.map { ToolFailureAttribution.classify(it).rootCause }.distinct().size,
        )
    }

    // ── 判据④：不改「失败→记账」链路 + 读取侧接线（源码门禁 + 负例）──────────

    private fun raw(rel: String): String =
        File(rel).let { if (it.isFile) it else File("app/$rel") }
            .readText(Charsets.UTF_8).replace("\r\n", "\n")

    private fun slice(src: String, start: String, end: String): String {
        val i = src.indexOf(start)
        assertTrue("源码里找不到起点：$start", i >= 0)
        val j = src.indexOf(end, startIndex = i + start.length)
        assertTrue("源码里找不到终点：$end", j > i)
        return src.substring(i, j)
    }

    private val controller = raw("src/main/java/com/example/zhengdao/ui/baopu/BaopuChatController.kt")
    private val engine = raw("src/main/java/com/example/zhengdao/baopu/agent/RunEngine.kt")

    @Test
    fun `接线_读取侧真的把err列带给判据层_负例删掉err会红`() {
        val block = slice(
            controller,
            "SELECT id, tool, ok, created_at, err FROM run_log",
            "val globalRoot",
        )
        assertTrue("SQL 要多读 err 列", block.contains("err FROM run_log"))
        assertTrue("ToolRun 要接住 err", block.contains("err = c.getString(4)"))

        val removed = block
            .replace("SELECT id, tool, ok, created_at, err FROM run_log", "SELECT id, tool, ok, created_at FROM run_log")
            .replace("err = c.getString(4).orEmpty(),", "")
        assertFalse(
            "负例：把 err 接线删掉，门禁必须看见少了一列",
            removed.contains("err FROM run_log") && removed.contains("err = c.getString(4)"),
        )
    }

    @Test
    fun `不改记账链路_run_log工具行仍按原样写入_负例改写成线索会红`() {
        val block = slice(engine, "private fun persistToolExchange(", "if (!ok) log(")
        assertTrue("记账仍写 err 空串（本单不动写入）", block.contains("err = \"\""))
        assertTrue("成败仍按既有 toolExchangeOk 判", block.contains("RunLoopRules.toolExchangeOk(wrapped)"))
        assertTrue("仍是原有的 insertRunLog", block.contains("BaopuSql.insertRunLog("))
        assertFalse(
            "负例：把记账写成「带错误线索」就不再是原链路",
            block.replace("err = \"\"", "err = wrapped").contains("err = \"\""),
        )
    }
}
