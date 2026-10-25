// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百批 §2/§4 执行期取值单测：一句话 → 参数值；取不到就**只反问一行**。
// 负例 2（取错值）/ 3（默认值静默生效）/ 5（相对日期不换算）的靶子全在这。
package com.example.zhengdao.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class TeachParamsParseTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    /** 2026-10-24 10:00（Asia/Shanghai）——「昨天」必须换算成 2026-10-23。 */
    private val nowMs: Long = LocalDate.of(2026, 10, 24)
        .atTime(10, 0).atZone(zone).toInstant().toEpochMilli()

    // ── 直接造参数表（判据单测不必每次都从示教跑一遍）──────────────────────────

    private fun dirParam(default: String? = null) = TeachParam(
        name = "dir", label = "方向", source = TeachParamSource.SELECT, shape = TeachParamShape.ENUM,
        slots = listOf(TeachSlot(1, TeachSlotField.VALUE)),
        default = default,
        options = listOf(TeachOption("北京", "北京"), TeachOption("天津", "天津"), TeachOption("唐山", "唐山")),
    )

    private fun plateParam() = TeachParam(
        name = "plate", label = "车牌", source = TeachParamSource.INPUT, shape = TeachParamShape.CODE,
        slots = listOf(TeachSlot(2, TeachSlotField.VALUE)),
    )

    private fun dayParam() = TeachParam(
        name = "day", label = "日期", source = TeachParamSource.INPUT, shape = TeachParamShape.DATE,
        slots = listOf(TeachSlot(3, TeachSlotField.VALUE)),
    )

    private fun countParam() = TeachParam(
        name = "count", label = "件数", source = TeachParamSource.INPUT, shape = TeachParamShape.NUMBER,
        slots = listOf(TeachSlot(4, TeachSlotField.VALUE)),
    )

    private fun ok(parsed: TeachParamParse): TeachParamParse.Ok {
        assertTrue("本该取到值，实际反问/失败：${(parsed as? TeachParamParse.Ask)?.line}", parsed is TeachParamParse.Ok)
        return parsed as TeachParamParse.Ok
    }

    private fun ask(parsed: TeachParamParse): TeachParamParse.Ask {
        assertTrue("本该反问，实际取值成功：${(parsed as? TeachParamParse.Ok)?.values}", parsed is TeachParamParse.Ask)
        return parsed as TeachParamParse.Ask
    }

    // ── 正例：三种来源各取一次 ────────────────────────────────────────────────

    @Test
    fun `取参数_说天津就取到天津（选项词表认）`() {
        val r = ok(TeachParams.parse(listOf(dirParam()), "查天津的运单", nowMs, zone))
        assertEquals("天津", r.values["dir"])
        assertEquals("天津", r.display.single().value)
    }

    @Test
    fun `取参数_车牌按编码形状认`() {
        val r = ok(TeachParams.parse(listOf(plateParam()), "查一下冀A12345", nowMs, zone))
        assertEquals("冀A12345", r.values["plate"])
    }

    @Test
    fun `取参数_三个参数一句话全取到`() {
        val r = ok(TeachParams.parse(listOf(dirParam(), plateParam(), dayParam()), "查天津，车牌冀A12345，昨天的", nowMs, zone))
        assertEquals("天津", r.values["dir"])
        assertEquals("冀A12345", r.values["plate"])
        assertEquals("2026-10-23", r.values["day"])
    }

    // ── 负例 5：相对日期必须当场换算，并在回答里显示换成了哪天 ─────────────────

    @Test
    fun `负例5_昨天必须换算成具体日期_回答里显示换成哪天`() {
        val r = ok(TeachParams.parse(listOf(dayParam()), "查昨天的", nowMs, zone))
        assertEquals("2026-10-23", r.values["day"])
        assertFalse("不许把「昨天」原样当值", r.values["day"] == "昨天")
        val d = r.display.single()
        assertEquals("昨天", d.spoken)
        assertTrue("要显示换成哪天：${d.note}", d.note!!.contains("2026-10-23"))
        assertTrue("回答里那句话要带换算：${d.answerLine()}", d.answerLine().contains("2026-10-23"))
    }

    @Test
    fun `负例5_其余相对日期与绝对日期都换算`() {
        assertEquals("2026-10-21", ok(TeachParams.parse(listOf(dayParam()), "大前天", nowMs, zone)).values["day"])
        assertEquals("2026-10-23", ok(TeachParams.parse(listOf(dayParam()), "昨日", nowMs, zone)).values["day"])
        assertEquals("2026-10-26", ok(TeachParams.parse(listOf(dayParam()), "后天", nowMs, zone)).values["day"])
        assertEquals("2026-10-21", ok(TeachParams.parse(listOf(dayParam()), "3天前", nowMs, zone)).values["day"])
        assertEquals("2026-10-20", ok(TeachParams.parse(listOf(dayParam()), "2026-10-20", nowMs, zone)).values["day"])
        assertEquals("2026-10-22", ok(TeachParams.parse(listOf(dayParam()), "10月22日", nowMs, zone)).values["day"])
    }

    // ── 负例 3：默认值绝不静默生效 ────────────────────────────────────────────

    @Test
    fun `负例3_用户没说就用默认值_必须红_只反问一行`() {
        val r = ask(TeachParams.parse(listOf(dirParam(default = "唐山")), "查一下今天的", nowMs, zone))
        assertFalse("反问里不许把默认值当结果给", r.missing.isEmpty())
        assertEquals(listOf("dir"), r.missing)
        assertTrue("反问要提一句默认值（提议，不是生效）：${r.line}", r.line.contains("默认 唐山"))
        assertTrue("反问要带选项：${r.line}", r.line.contains("北京") && r.line.contains("天津"))
        assertEquals("默认值只作为提议出现", "唐山", r.offered["dir"])
    }

    @Test
    fun `负例3_就算有默认值_取值结果里也永远不会出现它`() {
        val params = listOf(dirParam(default = "唐山"), plateParam())
        val r = ask(TeachParams.parse(params, "车牌冀A12345", nowMs, zone))
        assertEquals(listOf("dir"), r.missing)
        // 只缺 dir；有默认值也不许填
        assertFalse(r.line.isBlank())
    }

    // ── 负例 2：取错值 ────────────────────────────────────────────────────────

    @Test
    fun `负例2_数字不许顶到方向上`() {
        // 只有方向（枚举）时，句子里的 12 不是方向
        val r = ask(TeachParams.parse(listOf(dirParam()), "查 12 的", nowMs, zone))
        assertEquals(listOf("dir"), r.missing)
    }

    @Test
    fun `负例2_数字只进数字参数_方向仍由词表认`() {
        val r = ok(TeachParams.parse(listOf(dirParam(), countParam()), "查天津的 12 件", nowMs, zone))
        assertEquals("天津", r.values["dir"])
        assertEquals("12", r.values["count"])
    }

    @Test
    fun `负例2_两个数字不知给谁_反问不猜`() {
        val r = ask(TeachParams.parse(listOf(countParam()), "12 和 34 都算", nowMs, zone))
        assertEquals(listOf("count"), r.missing)
        assertTrue("要说清是两个候选：${r.line}", r.line.contains("12") || r.line.contains("候选"))
    }

    // ── 反问的形态：只有一行 ──────────────────────────────────────────────────

    @Test
    fun `反问只有一行_不许多行追问`() {
        val r = ask(TeachParams.parse(listOf(dirParam(), plateParam(), dayParam()), "查", nowMs, zone))
        assertFalse("反问不许有换行：${r.line}", r.line.contains("\n"))
        assertFalse("反问不许有回车", r.line.contains("\r"))
        assertEquals(3, r.missing.size)
        assertTrue("要把缺的都说出来：${r.line}", r.line.contains("方向") && r.line.contains("车牌") && r.line.contains("日期"))
    }

    @Test
    fun `反问_单选缺项时按派工单原型给选项`() {
        val line = TeachParams.askLine(listOf(dirParam()))
        assertTrue("原型是「查哪个方向？（可选：北京/天津…）」：$line", line.contains("方向") && line.contains("可选：北京/天津"))
        assertFalse(line.contains("\n"))
    }

    // ── 其它口径 ──────────────────────────────────────────────────────────────

    @Test
    fun `显式赋值优先_名字或标签都能写`() {
        assertEquals("唐山", ok(TeachParams.parse(listOf(dirParam()), "dir=唐山", nowMs, zone)).values["dir"])
        assertEquals("唐山", ok(TeachParams.parse(listOf(dirParam()), "方向：唐山", nowMs, zone)).values["dir"])
    }

    @Test
    fun `枚举按选项文案认_填回去的是页面要的value`() {
        val p = dirParam().copy(
            options = listOf(TeachOption("01", "未发"), TeachOption("02", "已发")),
        )
        val r = ok(TeachParams.parse(listOf(p), "查未发的", nowMs, zone))
        assertEquals("说「未发」要填回页面的 value", "01", r.values["dir"])
    }

    @Test
    fun `没有词表的自由文本_不猜_反问`() {
        val p = TeachParam(
            name = "keyword", label = "关键词", source = TeachParamSource.INPUT,
            shape = TeachParamShape.TEXT, slots = listOf(TeachSlot(1, TeachSlotField.VALUE)),
        )
        val r = ask(TeachParams.parse(listOf(p), "查天津", nowMs, zone))
        assertEquals(listOf("keyword"), r.missing)
        // 加了引号就是用户说清楚了，取
        assertEquals("天津", ok(TeachParams.parse(listOf(p), "查「天津」", nowMs, zone)).values["keyword"])
    }

    @Test
    fun `歧义_两个日期参数却只给了一个日期_反问`() {
        val a = dayParam().copy(name = "day_from", label = "开始日期")
        val b = dayParam().copy(name = "day_to", label = "结束日期")
        val r = ask(TeachParams.parse(listOf(a, b), "查昨天的", nowMs, zone))
        assertEquals(2, r.missing.size)
    }

    @Test
    fun `plan_取不到参数只反问_不产生任何可执行计划`() {
        val withParams = TeachParamFixtures.parameterized()
        val pl = TeachParams.plan(withParams, "查", nowMs, zone)
        assertTrue("该反问：$pl", pl is TeachParams.TeachRunPlan.Ask)
        val line = (pl as TeachParams.TeachRunPlan.Ask).line
        assertFalse(line.contains("\n"))
    }

    @Test
    fun `plan_取到参数就给出填好的技能_起点没变就不用导航`() {
        val withParams = TeachParamFixtures.parameterized()
        val pl = TeachParams.plan(withParams, "查天津，车牌冀A12345，昨天", nowMs, zone)
        assertTrue("该可以跑：$pl", pl is TeachParams.TeachRunPlan.Ready)
        val ready = pl as TeachParams.TeachRunPlan.Ready
        assertNull("起点 URL 没参数 ⇒ 不导航", ready.navigateTo)
        assertTrue("占位要填满", TeachParams.unresolvedPlaceholders(ready.skill, ready.values).isEmpty())
        assertFalse("填好的技能里不该再有占位", ready.skill.toJsonText().contains("\${"))
        assertTrue(ready.skill.toJsonText().contains("天津"))
    }
}
