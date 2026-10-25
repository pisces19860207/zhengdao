// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百九十五批 · A 档 `logistics_query`（只读 agent 工具）＋判据里的「未解锁」两句。
//
// 2026-10-25 裁定的形状逐条钉在这儿：参数是 `{direction?, plate?, waybill?, goods?, ask}` 五个、
// `ask` 只三档、解锁话术**写死在 description 里**、没表时回 `found:false ＋ NO_TABLE ＋ hint`
// 而不是报错（`Failed` 会被引擎当成「工具坏了」，模型会重试）。
//
// 「未解锁」那两句判据就在本档：
//   - 装配没给库（工具还没解锁/没接上）⇒ 一句能转述的话，不抛异常、不返回半截 JSON；
//   - 解锁了但今天没表 ⇒ `found:false` ＋去路，模型转述给用户而不是重试。
//
// §1.8（2026-10-25 冻结）在本档钉两条：
//   - 默认口径＝`ORDER`（按配货单去重），所以**生产入口传的必须是 ORDER**，且 `basis` 缺省时 JSON 里
//     回的是 "ORDER"——这一条靠读源码断言，因为装配层传什么，模型猜不出来；
//   - 工具面出去的东西（JSON 与 answer）不带司机姓名与手机号：那一面是给模型读的，等于出了本机。
package com.example.zhengdao.baopu.logistics

import com.example.zhengdao.baopu.agent.LogisticsQueryTool
import com.example.zhengdao.baopu.agent.ToolGroup
import com.example.zhengdao.baopu.agent.ToolGroupTable
import com.example.zhengdao.baopu.agent.ToolOutcome
import com.example.zhengdao.baopu.agent.ToolPermission
import com.example.zhengdao.baopu.agent.ToolSpec
import java.io.File
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LogisticsToolTest {

    @After
    fun tearDown() {
        LogisticsQueryTool.store = null
    }

    private fun argsJson(vararg pairs: Pair<String, String>): JSONObject {
        val j = JSONObject()
        pairs.forEach { (k, v) -> j.put(k, v) }
        return j
    }

    /**
     * 默认档＝[LogisticsBasis.ORDER]（§1.8 冻结），与生产入口 `run(args)` 传的那一档一致；
     * 下面「生产入口传的口径」那条用源码门禁钉住两者不许漂。
     */
    private fun run(
        args: JSONObject,
        s: LogisticsStore,
        day: String = TODAY_KEY,
        basis: LogisticsBasis = LogisticsBasis.ORDER,
    ): ToolOutcome {
        LogisticsQueryTool.store = s
        return LogisticsQueryTool.run(args, NOW, TZ, day, basis)
    }

    private fun json(outcome: ToolOutcome): JSONObject {
        assertTrue("工具该正常返回而不是报错：$outcome", outcome is ToolOutcome.Ok)
        return JSONObject((outcome as ToolOutcome.Ok).text)
    }

    private fun threeRowStore(): FakeLogisticsStore = FakeLogisticsStore().apply {
        put(
            storedRow("10231", orderNo = "PH101", direction = "北京朝阳", unload = "三间房", goods = "钢材", pieces = 12, weight = "3.5", driver = "王师傅", phone = "13800000001", plate = "冀A12345", status = LogisticsStatus.LOADED, lineNo = 2),
            storedRow("10232", orderNo = "PH102", direction = "北京朝阳", unload = "四惠", goods = "家具", pieces = 8, driver = "李师傅", phone = "13800000002", plate = "冀B67890", status = LogisticsStatus.PENDING, lineNo = 3),
            storedRow("10233", orderNo = "PH103", direction = "上海青浦", unload = "华新", goods = "钢材", pieces = null, plate = "", status = LogisticsStatus.UNKNOWN, lineNo = 4),
        )
    }

    // ── 形状（裁定 1/2/3）─────────────────────────────────────────────────────

    @Test
    fun `描述不超上限，而且解锁话术写死在描述里`() {
        val spec = LogisticsQueryTool.spec()
        assertEquals("logistics_query", spec.name)
        assertTrue(
            "描述 ${spec.description.length} 字，上限 ${ToolSpec.MAX_DESC} 不许为它抬高",
            spec.description.length <= ToolSpec.MAX_DESC,
        )
        assertTrue("解锁句子要逐字出现在描述里：${spec.description}", spec.description.contains(LogisticsQueryTool.UNLOCK_SENTENCE))
        assertTrue(
            "要指名 capabilities(enable=\"logistics\")",
            spec.description.contains("capabilities(enable=\"logistics\")"),
        )
    }

    @Test
    fun `参数就是裁定给的五个，ask 是唯一必填`() {
        val schema = LogisticsQueryTool.spec().inputSchema
        val props = schema.getJSONObject("properties")
        listOf("direction", "plate", "waybill", "goods", "ask").forEach {
            assertTrue("少了参数「$it」", props.has(it))
        }
        assertEquals(5, props.length())
        // 口径不进 schema：切换口径是面板上的事，模型这一侧只有一档，两次运行才是同一个数
        assertFalse("basis 不许进参数：$props", props.has("basis"))
        assertFalse("text/原话 不许进参数（查询词一旦成参数就会进调用记录）", props.has("text"))
        assertEquals("ask", schema.getJSONArray("required").getString(0))
        assertEquals(1, schema.getJSONArray("required").length())
        assertFalse("多给的字段要挡在门外", schema.getBoolean("additionalProperties"))
    }

    @Test
    fun `ask 只有三档`() {
        val enum = LogisticsQueryTool.spec().inputSchema.getJSONObject("properties").getJSONObject("ask").getJSONArray("enum")
        val values = (0 until enum.length()).map { enum.getString(it) }
        assertEquals(listOf("loaded", "count", "goods"), values)
        assertEquals(LogisticsQueryTool.ASKS, values)
        // 未发名单归播报，不塞进工具：同一件事不许有两条路径、两套话术
        assertFalse(values.contains("unsent"))
    }

    @Test
    fun `只读、免费、可并行，而且默认不可见`() {
        val spec = LogisticsQueryTool.spec()
        assertTrue("只读工具标 readOnly 才能进并行读", spec.readOnly)
        assertTrue(spec.parallelSafe)
        assertEquals(ToolPermission.FREE, spec.permission)
        // 裁定 1：**不塞 CORE**（否则「默认可见 12 条」那张硬卡就破了）
        assertEquals(ToolGroup.LOGISTICS, ToolGroupTable.GROUP_OF.getValue(LogisticsQueryTool.NAME))
        assertFalse("LOGISTICS 组不许进默认可见组名单", ToolGroup.LOGISTICS in ToolGroup.DEFAULT_VISIBLE)
        assertFalse("也不许走工具级默认可见后门", LogisticsQueryTool.NAME in ToolGroup.DEFAULT_VISIBLE_TOOLS)
        // 解锁入口按 key 认（capabilities(enable="logistics")）
        assertEquals(ToolGroup.LOGISTICS, ToolGroup.fromKey("logistics"))
        assertTrue(LogisticsQueryTool.NAME in ToolGroupTable.namesOf(ToolGroup.LOGISTICS))
    }

    // ── 判据 C：未解锁两句 ────────────────────────────────────────────────────

    @Test
    fun `没装配时给一句能转述的话而不是抛异常`() {
        LogisticsQueryTool.store = null
        val outcome = LogisticsQueryTool.run(argsJson("ask" to "loaded"), NOW, TZ, TODAY_KEY, LogisticsBasis.ORDER)
        assertTrue("装配没给库 ⇒ 不查：$outcome", outcome is ToolOutcome.Failed)
        val error = (outcome as ToolOutcome.Failed).error
        assertTrue("要说清是没装配：$error", error.contains("装配"))
        assertFalse("不许把堆栈或绝对路径给用户：$error", error.contains("/") || error.contains("\\"))
    }

    @Test
    fun `今天没表时回 found false 加去路而不是报错`() {
        val j = json(run(argsJson("ask" to "loaded"), FakeLogisticsStore()))
        assertFalse(j.getBoolean("found"))
        assertEquals("NO_TABLE", j.getString("reason"))
        assertTrue("去路要照裁定原话：${j.getString("hint")}", j.getString("hint").contains("先导入 T9 导出的表"))
        assertTrue("要说清放哪儿：${j.getString("hint")}", j.getString("hint").contains("产物/物流/inbox/"))
        assertEquals(TODAY_KEY, j.getString("day"))
        assertEquals("loaded", j.getString("ask"))
        assertEquals("没表也要说清用的是哪一档（默认＝按配货单）", "ORDER", j.getString("basis"))
    }

    @Test
    fun `没表时不编数`() {
        val j = json(run(argsJson("ask" to "count"), FakeLogisticsStore()))
        // 一个数都不许出现：没表就是没表，不能报「0 单 0 件」当作今天发完了
        assertFalse("不许有 orders：$j", j.has("orders"))
        assertFalse("不许有 vehicles：$j", j.has("vehicles"))
        assertFalse("不许有 pieces：$j", j.has("pieces"))
        assertFalse("不许有 rows：$j", j.has("rows"))
    }

    // ── 参数不合法 ────────────────────────────────────────────────────────────

    @Test
    fun `ask 缺了或认不出都报错，并且把三档念出来`() {
        val missing = run(argsJson(), threeRowStore())
        assertTrue("$missing", missing is ToolOutcome.Failed)
        assertTrue((missing as ToolOutcome.Failed).error.contains("ask"))

        val wrong = run(argsJson("ask" to "unsent"), threeRowStore())
        assertTrue("$wrong", wrong is ToolOutcome.Failed)
        val error = (wrong as ToolOutcome.Failed).error
        listOf("loaded", "count", "goods").forEach { assertTrue("要把认的三档列全：$error", error.contains(it)) }
        // 认不出的一档不许猜成最像的那个：猜错会把「什么货」答成「装没装」
        assertFalse("不许猜：$error", error.contains("已按 loaded"))
    }

    // ── 「这车谁的／司机电话」在这层被拒（2026-10-25 新增口径）─────────────────

    @Test
    fun `who 一类的 ask 被拒并且指去面板`() {
        // 模型面永远不该拿到姓名与手机号：who 一类的 ask 在参数检查处就被拒，
        // 拒答话术要能照抄给用户（说清去处），并把本工具认的三档再念一遍免得原地重试。
        listOf("who", "driver", "phone", "电话", "司机", "谁的").forEach { ask ->
            val outcome = run(argsJson("ask" to ask), threeRowStore())
            assertTrue("「$ask」该被拒：$outcome", outcome is ToolOutcome.Failed)
            val error = (outcome as ToolOutcome.Failed).error
            assertTrue("要说清去处在面板：$error", error.contains("查询助手"))
            listOf("loaded", "count", "goods").forEach { assertTrue("要念三档防重试：$error", error.contains(it)) }
            // 拒答话术本身也不许夹带任何真名真号
            listOf("王师傅", "李师傅", "13800000001", "13800000002").forEach {
                assertFalse("拒答里不许出现「$it」：$error", error.contains(it))
            }
        }
    }

    @Test
    fun `描述里就把谁的那一问挡在门外`() {
        val desc = LogisticsQueryTool.spec().description
        assertTrue("描述要指去面板：$desc", desc.contains("查询助手"))
        assertTrue("描述要写明隐私理由：$desc", desc.contains("隐私"))
    }

    @Test
    fun `结构化参数没有司机这一维`() {
        // 模型面靠参数形状兜底：就算模型想问司机，参数表里也没有可以塞名字的地方
        val props = LogisticsQueryTool.spec().inputSchema.getJSONObject("properties")
        listOf("driver", "phone", "who").forEach { assertFalse("不许有参数「$it」", props.has(it)) }
        assertEquals("工具侧的 spec 也没有司机条件", emptyList<String>(), LogisticsQueryTool.specOf("loaded", argsJson()).drivers)
    }

    // ── 查到东西时的 JSON ─────────────────────────────────────────────────────

    @Test
    fun `命中时的 JSON 带数、带口径、带数据时间，answer 仍是三行`() {
        val j = json(run(argsJson("ask" to "loaded", "direction" to "北京朝阳"), threeRowStore()))
        assertTrue(j.getBoolean("found"))
        assertEquals(2, j.getInt("orders"))
        assertEquals(2, j.getInt("vehicles"))
        assertEquals(20, j.getInt("pieces"))
        // 默认档＝ORDER（按配货单去重）：这一份 fixture 两单挂两张配货单，所以车数=单数=2
        assertEquals("ORDER", j.getString("basis"))
        val answer = j.getString("answer")
        assertEquals("答案要落在三行内：$answer", 3, LogisticsAnswer.lineCount(answer))
        assertEquals("禁词：$answer", emptyList<String>(), LogisticsAnswer.containsForbidden(answer))
        assertTrue("第 3 行是数据时间：$answer", answer.lines().last().startsWith("数据："))
        assertTrue("数据时间要单独给字段（面板/日志都用它）：${j.getString("dataTime")}", j.getString("dataTime").contains("取自"))
        assertTrue("脚注要说这一档怎么数的：$answer", answer.contains("车＝按配货单去重（张）"))
    }

    @Test
    fun `工具面出去的东西不带司机姓名与手机号`() {
        // §1.8 的原样显示只给**面板与台账**；工具这条链上 JSON 与 answer 都会进模型上下文，
        // 而模型上下文是派工单点名的「不许写姓名与手机号」的那一面。
        val outcome = run(argsJson("ask" to "loaded", "direction" to "北京朝阳"), threeRowStore())
        val text = (outcome as ToolOutcome.Ok).text
        listOf("王师傅", "李师傅", "13800000001", "13800000002", "138", "手机", "司机").forEach {
            assertFalse("工具 JSON 里不许出现「$it」：$text", text.contains(it))
        }
        // 配货单号与车牌照旧给（那是点名的单元，也是 §1.8 允许进显示的字段）
        assertTrue("配货单号要能点名：$text", text.contains("PH101"))
    }

    @Test
    fun `件数没读出来时 JSON 里是 null 而不是零`() {
        val j = json(run(argsJson("ask" to "count", "direction" to "上海青浦"), threeRowStore()))
        assertTrue("pieces 必须是 JSON null：$j", j.isNull("pieces"))
        assertFalse("答案不许写 0 件：${j.getString("answer")}", j.getString("answer").contains("0 件"))
        assertEquals(1, j.getInt("orders"))
    }

    @Test
    fun `四个条件都按结构化参数生效`() {
        val s = threeRowStore()
        listOf(
            argsJson("ask" to "count", "direction" to "北京朝阳") to 2,
            argsJson("ask" to "count", "plate" to "冀A12345") to 1,
            argsJson("ask" to "count", "waybill" to "10231") to 1,
            argsJson("ask" to "count", "goods" to "家具") to 1,
        ).forEach { (args, expect) ->
            val j = json(run(args, s))
            assertEquals("条件 ${args} 命中行数", expect, j.getInt("rows"))
        }
    }

    @Test
    fun `车牌参数也走同一套归一`() {
        val spec = LogisticsQueryTool.specOf("count", argsJson("plate" to " 冀a12345 "))
        assertEquals("冀A12345", spec.plate)
        val spec2 = LogisticsQueryTool.specOf("count", argsJson("waybill" to " '10231' "))
        assertEquals("10231", spec2.waybill)
        // 不填＝不限（不是「空字符串条件」，那会把所有行筛掉）
        val none = LogisticsQueryTool.specOf("count", argsJson("ask" to "count"))
        assertNull(none.plate)
        assertNull(none.waybill)
        assertNull(none.goods)
        assertTrue(none.scopes.isEmpty())
    }

    @Test
    fun `结构化参数不带原话，所以 unmatchedTerm 恒空`() {
        // 这里的值是照着表给的，没有「表里没这个词」这一说；答案层不许再判一遍意图
        val spec = LogisticsQueryTool.specOf("goods", argsJson("goods" to "不存在的货"))
        assertEquals("", spec.unmatchedTerm)
        assertEquals(LogisticsIntent.GOODS, spec.intent)
    }

    @Test
    fun `ask 三档各自落到对应意图`() {
        assertEquals(LogisticsIntent.LOADED, LogisticsQueryTool.specOf("loaded", argsJson()).intent)
        assertEquals(LogisticsIntent.COUNT, LogisticsQueryTool.specOf("count", argsJson()).intent)
        assertEquals(LogisticsIntent.GOODS, LogisticsQueryTool.specOf("goods", argsJson()).intent)
    }

    // ── NO_MATCH 不许混成 NO_TABLE ───────────────────────────────────────────

    @Test
    fun `有表但条件不中时报 NO_MATCH，并且给出表里认得的写法`() {
        val j = json(run(argsJson("ask" to "loaded", "direction" to "广州白云"), threeRowStore()))
        assertFalse(j.getBoolean("found"))
        assertEquals("两个原因是不同的事", "NO_MATCH", j.getString("reason"))
        val hint = j.getString("hint")
        assertTrue("要说表里有多少单：$hint", hint.contains("表里有 3 单"))
        assertTrue("要把认得的写法给出去，别让模型再猜：$hint", hint.contains("北京朝阳"))
        assertEquals("拒答也是两行：${j.getString("answer")}", 2, LogisticsAnswer.lineCount(j.getString("answer")))
    }

    @Test
    fun `NO_MATCH 的 hint 里方向超过三个折一下`() {
        val s = FakeLogisticsStore().apply {
            listOf("北京朝阳", "北京丰台", "上海青浦", "广州白云", "成都双流").forEachIndexed { i, d ->
                put(storedRow("2000$i", direction = d, pieces = 1, plate = "冀A1234$i", status = LogisticsStatus.PENDING, lineNo = i + 2))
            }
        }
        val hint = json(run(argsJson("ask" to "loaded", "direction" to "哈尔滨"), s)).getString("hint")
        assertTrue("折成「等 N 个」：$hint", hint.contains("等 5 个"))
    }

    @Test
    fun `工具与面板出同一句话`() {
        // 出话只有一条路径：两处各调一次就会各读一次库，读数能差一行
        val s = threeRowStore()
        val rows = s.rowsFor(TODAY_KEY)
        val spec = LogisticsQueryTool.specOf("loaded", argsJson("direction" to "北京朝阳"))
        val fromTool = json(run(argsJson("ask" to "loaded", "direction" to "北京朝阳"), s)).getString("answer")
        val fromPanel = LogisticsAsk.answerOf(
            QueryOutcome.Found(spec, LogisticsAggregate.aggregate(LogisticsQuery.filter(rows, spec), LogisticsBasis.ORDER)),
            rows,
            NOW,
            TZ,
        )
        assertEquals(fromPanel, fromTool)
    }

    @Test
    fun `补导别天的表不会污染今天的 JSON`() {
        val s = FakeLogisticsStore().apply {
            put(storedRow("10231", day = "20261024", direction = "北京朝阳", pieces = 12, plate = "冀A12345", status = LogisticsStatus.LOADED))
        }
        val j = json(run(argsJson("ask" to "count"), s))
        assertFalse("今天问就是今天：$j", j.getBoolean("found"))
        assertEquals("NO_TABLE", j.getString("reason"))
        // 切到那一天才查得到
        val hit = json(run(argsJson("ask" to "count"), s, day = "20261024"))
        assertTrue(hit.getBoolean("found"))
        assertEquals("20261024", hit.getString("day"))
    }

    @Test
    fun `生产入口传的口径就是冻结的默认档`() {
        // `run(args)`（真机那条）自己算时间、自己定口径，本档 JVM 里钉不住它的返回值，
        // 所以按仓库既有的源码门禁形状钉**写的是哪一档**：
        // §1.8 冻结的是「模型这一侧只有一档，且那一档＝按配货单去重」。
        // 漂成 PLATE 的话两次运行会给出两个车数，而 schema 里没有 basis，模型无从解释差异。
        val src = code("src/main/java/com/example/zhengdao/baopu/agent/tools/LogisticsQueryTool.kt")
        val body = src.substringAfter("fun run(args: JSONObject): ToolOutcome").substringBefore("/**")
        assertTrue("生产入口要显式传 ORDER：$body", body.contains("LogisticsBasis.ORDER"))
        assertFalse("生产入口不许拿车牌当默认：$body", body.contains("LogisticsBasis.PLATE"))
    }

    // ── 注册面（仓库既有规矩：新工具要在这四处都留一行）────────────────────────

    @Test
    fun `设置页四处映射与两份字符串都齐`() {
        // 房型照 158/156 批（ImageOpsTest G4）：这两张 `when` 是 catalog 覆盖断言网，
        // 漏一行 ⇒ 设置页构建工具卡时 `throw IllegalArgumentException`（不是「少个标签」那么轻）。
        val screen = code("src/main/java/com/example/zhengdao/ui/baopu/BaopuSettingsScreen.kt")
        assertTrue("baopuToolLabel 漏了 logistics_query", screen.contains("\"logistics_query\" -> R.string.baopu_tool_logistics_query"))
        assertTrue("baopuToolDesc 漏了 logistics_query", screen.contains("\"logistics_query\" -> R.string.baopu_tool_logistics_query_desc"))
        val rules = code("src/main/java/com/example/zhengdao/ui/baopu/BaopuSettingsRules.kt")
        assertTrue("baopuGroupTitle 漏了 LOGISTICS 组", rules.contains("ToolGroup.LOGISTICS -> R.string.baopu_tools_group_logistics"))
        assertTrue("baopuGroupLine 漏了 LOGISTICS 组", rules.contains("ToolGroup.LOGISTICS -> R.string.baopu_group_line_logistics"))
        listOf("src/main/res/values/strings_baopu_logistics.xml", "src/main/res/values-en/strings_baopu_logistics.xml").forEach { rel ->
            val text = code(rel)
            listOf(
                "baopu_tool_logistics_query", "baopu_tool_logistics_query_desc",
                "baopu_tools_group_logistics", "baopu_group_line_logistics",
            ).forEach { key ->
                assertTrue("$rel 缺 $key", text.contains("name=\"$key\""))
            }
        }
    }

    private fun repoFile(rel: String): File = File(rel).let { if (it.isFile) it else File("app/$rel") }

    /** 读源码并把注释剥掉再比：否则「漏这一行 ⇒ …baopuToolLabel」这种说明文字会自己冒充命中。 */
    private fun code(rel: String): String = repoFile(rel).readText(Charsets.UTF_8)
        .replace("\r\n", "\n")
        .replace(Regex("(?s)/\\*.*?\\*/"), "")
        .replace(Regex("(?m)^[ \\t]*//.*$"), "")
}
