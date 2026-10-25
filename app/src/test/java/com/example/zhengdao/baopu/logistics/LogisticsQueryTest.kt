// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百九十五批 · 一句话查 + 车数口径（2026-10-25 裁定的六条全在这儿钉）。
//
// 负例④「编造方向」在这一档的形态有三种，逐一钉住：
//   1. 表里没有这个方向 ⇒ 回「这份表里没有『××』的记录」，不许回一个看着像的答案；
//   2. 表里没有车牌列 ⇒ 车数那一维降级（不显示、不计算），不许发明一辆车去凑；
//   3. 一句话里同时出现两个都存在的范围 ⇒ 反问一行，不许挑一个。
// 口径那三条（可配置、默认档、车数≤单数自检）按裁定逐条打，切换口径时**数与脚注同时变**。
// 默认档在 §1.8（2026-10-25 冻结）里定成 **ORDER＝按配货单去重**（早前聊天里的「默认 PLATE」作废）：
// 「每一张配货单对应一辆车」⇒ 同一台车的两张配货单是两趟，一张配货单上的三单是一辆车。
package com.example.zhengdao.baopu.logistics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LogisticsQueryTest {

    /**
     * 三单：北京朝阳两单（**同一张配货单、同一台车**）、上海一单另张单子且没车牌。
     *
     * PH101 名下两单是 §1.8 那句话的形状：一张配货单＝一辆车。于是这份表切三档得到三个数：
     * 按配货单 2 张、按车牌 1 台、按车牌＋方向 1 趟——而单数永远是 3。
     */
    private fun store(): FakeLogisticsStore = FakeLogisticsStore().apply {
        put(
            storedRow("10231", orderNo = "PH101", direction = "北京朝阳", unload = "三间房", goods = "钢材", pieces = 12, plate = "冀A12345", status = LogisticsStatus.LOADED),
            storedRow("10232", orderNo = "PH101", direction = "北京朝阳", unload = "四惠", goods = "家具", pieces = 8, plate = "冀A12345", status = LogisticsStatus.PENDING),
            storedRow("10233", orderNo = "PH102", direction = "上海青浦", unload = "华新", goods = "钢材", pieces = null, plate = "", status = LogisticsStatus.UNKNOWN),
        )
    }

    private fun ask(text: String, basis: LogisticsBasis = LogisticsBasis.ORDER) =
        LogisticsQuery.ask(store(), TODAY_KEY, text, basis)

    // ── 拆句 ─────────────────────────────────────────────────────────────────

    @Test
    fun `方向加问法拆成范围与意图`() {
        val spec = LogisticsQuery.parse("北京朝阳装没装", store().rowsFor(TODAY_KEY))
        assertEquals(LogisticsIntent.LOADED, spec.intent)
        assertEquals(listOf("北京朝阳"), spec.scopes)
        assertFalse(spec.ambiguous)
        assertEquals("", spec.unmatchedTerm)
        assertTrue(spec.hasScope)
    }

    @Test
    fun `问法关键词的优先级是判据`() {
        // 「今天还有哪些没发」里有「没」也带「发」，它是 UNSENT 不是 LOADED
        assertEquals(LogisticsIntent.UNSENT, LogisticsIntent.firstIntent("今天还有哪些没发"))
        assertEquals(LogisticsIntent.UNSENT, LogisticsIntent.firstIntent("北京朝阳还没走"))
        assertEquals(LogisticsIntent.LOADED, LogisticsIntent.firstIntent("北京朝阳装了没"))
        assertEquals(LogisticsIntent.COUNT, LogisticsIntent.firstIntent("北京朝阳一共几件"))
        assertEquals(LogisticsIntent.GOODS, LogisticsIntent.firstIntent("上海青浦装的是什么货"))
        assertEquals(LogisticsIntent.LIST, LogisticsIntent.firstIntent("冀A12345"))
    }

    @Test
    fun `卸点也能当范围而且与方向同权`() {
        val rows = store().rowsFor(TODAY_KEY)
        assertEquals(listOf("三间房"), LogisticsQuery.parse("三间房装没装", rows).scopes)
    }

    @Test
    fun `车牌带省份汉字直接认`() {
        val spec = LogisticsQuery.parse("冀A12345 去哪了", store().rowsFor(TODAY_KEY))
        assertEquals("冀A12345", spec.plate)
    }

    @Test
    fun `不带省份的车牌只在表里唯一命中时才认`() {
        val rows = store().rowsFor(TODAY_KEY)
        // 两台车**后缀一样、省份汉字不一样**（冀A12345 / 京A12345）：用户只说「A12345」时
        // 后缀命中两台，认哪台都是猜 ⇒ 不认。换成 冀B12345 就只剩一台命中，那是「唯一」不是「猜」。
        val two = FakeLogisticsStore().apply {
            put(
                storedRow("10231", direction = "北京朝阳", plate = "冀A12345"),
                storedRow("10232", direction = "北京朝阳", plate = "京A12345"),
            )
        }
        assertNull("后缀撞两台 ⇒ 反问比猜强", LogisticsQuery.parse("A12345 装没装", two.rowsFor(TODAY_KEY)).plate)
        assertEquals("冀A12345", LogisticsQuery.parse("A12345 装没装", rows).plate)
    }

    @Test
    fun `单号精确优先包含命中多单就不认`() {
        val rows = store().rowsFor(TODAY_KEY)
        assertEquals("10231", LogisticsQuery.parse("10231 发了没", rows).waybill)
        val two = FakeLogisticsStore().apply {
            put(
                storedRow("102310", direction = "北京朝阳", status = LogisticsStatus.LOADED),
                storedRow("210231", direction = "北京朝阳", status = LogisticsStatus.LOADED),
            )
        }
        assertNull("包含命中两单 ⇒ 反问比猜强", LogisticsQuery.parse("10231 发了没", two.rowsFor(TODAY_KEY)).waybill)
    }

    @Test
    fun `货名当条件时只认表里真有的`() {
        val rows = store().rowsFor(TODAY_KEY)
        assertEquals("钢材", LogisticsQuery.parse("钢材装了几件", rows).goods)
        assertNull(LogisticsQuery.parse("水果装了几件", rows).goods)
    }

    @Test
    fun `按配货单号问还不支持但要说查不到而不是编一单`() {
        // 已知限制（写进交付报告）：§1.8 给了配货单号这一维用来**数车**，没要求按它查。
        // 这一条钉的是「没做」的那一面必须诚实：不把 PH101 当成单号去命中某一行、
        // 也不把它塞进任何条件后回一个看着像的数——它落 NoMatch，答案就是「这份表里没有」。
        val outcome = ask("PH101 装没装")
        assertTrue("按配货单号问应当是 NoMatch，而不是编一个结果：$outcome", outcome is QueryOutcome.NoMatch)
        val spec = (outcome as QueryOutcome.NoMatch).spec
        assertNull("配货单号不许冒充单号条件", spec.waybill)
        assertNull("也不许冒充车牌", spec.plate)
        assertTrue(LogisticsAnswer.refusal(spec).contains("PH101", ignoreCase = true))
    }

    // ── 负例④ 的三种形态 ──────────────────────────────────────────────────────

    @Test
    fun `表里没有的方向回没有而不是编一个`() {
        val outcome = ask("广州白云装没装")
        assertTrue("$outcome", outcome is QueryOutcome.NoMatch)
        val spec = (outcome as QueryOutcome.NoMatch).spec
        assertEquals("广州白云", spec.unmatchedTerm)
        assertTrue(LogisticsAnswer.refusal(spec).contains("没有"))
    }

    @Test
    fun `范围词命中多个值时反问而不是挑一个`() {
        val many = FakeLogisticsStore().apply {
            put(
                storedRow("10231", direction = "北京朝阳", status = LogisticsStatus.LOADED),
                storedRow("10232", direction = "北京丰台", status = LogisticsStatus.PENDING),
            )
        }
        val outcome = LogisticsQuery.ask(many, TODAY_KEY, "北京装没装", LogisticsBasis.PLATE)
        assertTrue("$outcome", outcome is QueryOutcome.Ambiguous)
        assertEquals(2, (outcome as QueryOutcome.Ambiguous).options.size)
    }

    @Test
    fun `表里没车牌列时车数为零而不是发明一辆`() {
        // 缺列的那一维在库里就没值：聚合不许造出「未知车 1 台」
        val noPlate = FakeLogisticsStore().apply {
            put(
                storedRow("10231", direction = "北京朝阳", status = LogisticsStatus.LOADED, missing = "车牌"),
                storedRow("10232", direction = "北京朝阳", status = LogisticsStatus.PENDING, missing = "车牌"),
            )
        }
        val found = LogisticsQuery.ask(noPlate, TODAY_KEY, "北京朝阳装没装", LogisticsBasis.PLATE) as QueryOutcome.Found
        assertEquals(0, found.agg.vehicles)
        assertEquals(2, found.agg.orders)
        assertTrue("缺列名单要带上去给答案层降级用", LogisticsField.PLATE in found.agg.absent)
    }

    // ── 筛选 ─────────────────────────────────────────────────────────────────

    @Test
    fun `组合条件同时成立才算命中`() {
        val rows = store().rowsFor(TODAY_KEY)
        val both = LogisticsQuery.filter(rows, LogisticsQuery.parse("冀A12345 北京朝阳几件", rows))
        assertEquals(listOf("10231", "10232"), both.map { it.waybill })
        val other = LogisticsQuery.filter(rows, LogisticsQuery.parse("冀A12345 上海青浦几件", rows))
        assertTrue(other.isEmpty())
    }

    @Test
    fun `范围用包含而不只是相等`() {
        val rows = store().rowsFor(TODAY_KEY)
        // 「北京」不是表里任何一个完整值，但「北京朝阳」的单子确实是往北京的货
        val spec = LogisticsQuery.parse("北京装没装", rows)
        assertEquals(listOf("北京朝阳"), spec.scopes)
        assertEquals(2, LogisticsQuery.filter(rows, spec).size)
    }

    // ── 口径三档（裁定 1/2/5/6）───────────────────────────────────────────────

    @Test
    fun `同一份表切三种口径车数与脚注同时变`() {
        val rows = store().rowsFor(TODAY_KEY)
        val plate = LogisticsAggregate.aggregate(rows, LogisticsBasis.PLATE)
        val order = LogisticsAggregate.aggregate(rows, LogisticsBasis.ORDER)
        val trip = LogisticsAggregate.aggregate(rows, LogisticsBasis.TRIP)
        // 冀A12345 名下两张单挂**同一张配货单** ⇒ PLATE 1 台、ORDER 2 张（PH101＋PH102）、TRIP 1 趟
        assertEquals(1, plate.vehicles)
        assertEquals("按配货单数出来的是张数，不是运单数", 2, order.vehicles)
        assertEquals(1, trip.vehicles)
        assertEquals(3, plate.orders)
        assertEquals(3, order.orders)
        // 脚注跟着口径变（同一句「几车」必须说清是怎么数的）
        assertTrue(plate.basis.footnote.contains("车牌"))
        assertTrue("默认档脚注要说按配货单去重：${order.basis.footnote}", order.basis.footnote.contains("配货单"))
        assertFalse("脚注不许再说「按单号数车」：${order.basis.footnote}", order.basis.footnote.contains("单号"))
        assertTrue(trip.basis.footnote.contains("趟"))
        // 点名用的东西也跟着变：默认档点配货单号，另两档点车牌
        assertEquals(listOf("PH101", "PH102"), LogisticsAggregate.namesOf(rows, LogisticsBasis.ORDER))
        assertEquals(listOf("冀A12345"), LogisticsAggregate.namesOf(rows, LogisticsBasis.PLATE))
        assertEquals(listOf("冀A12345·北京朝阳"), LogisticsAggregate.namesOf(rows, LogisticsBasis.TRIP))
    }

    @Test
    fun `默认口径是配货单`() {
        // §1.8 冻结：认不出与没给都回默认档，不猜成另一档
        assertEquals(LogisticsBasis.ORDER, LogisticsBasis.of(null))
        assertEquals(LogisticsBasis.ORDER, LogisticsBasis.of(""))
        assertEquals(LogisticsBasis.ORDER, LogisticsBasis.of("不认识的一档"))
        assertEquals(LogisticsBasis.TRIP, LogisticsBasis.of(" trip "))
        assertEquals(LogisticsBasis.PLATE, LogisticsBasis.of("PLATE"))
        assertEquals("默认档靠的列就是配货单号那一列", LogisticsField.ORDER_NO, LogisticsAggregate.vehicleField(LogisticsBasis.ORDER))
        assertEquals(LogisticsField.PLATE, LogisticsAggregate.vehicleField(LogisticsBasis.PLATE))
        assertEquals(LogisticsField.PLATE, LogisticsAggregate.vehicleField(LogisticsBasis.TRIP))
    }

    @Test
    fun `能不能数车看的是这一档靠的那一列在不在`() {
        // 降级判据不许写死成「缺车牌」：默认档换成 ORDER 以后，缺哪一列得跟着口径变，
        // 否则她会去补一列本来不需要的车牌（或反过来，缺配货单号时答案里凭空冒出车数）。
        val noOrder = LogisticsAggregate.aggregate(
            listOf(storedRow("10231", direction = "北京朝阳", plate = "冀A12345", status = LogisticsStatus.LOADED, missing = "配货单号")),
            LogisticsBasis.ORDER,
        )
        assertFalse("缺配货单号列 ⇒ 默认档数不了车", noOrder.canCountVehicles)
        assertTrue("车牌列在 ⇒ 切到车牌档照样数得了", LogisticsAggregate.aggregate(
            listOf(storedRow("10231", direction = "北京朝阳", plate = "冀A12345", status = LogisticsStatus.LOADED, missing = "配货单号")),
            LogisticsBasis.PLATE,
        ).canCountVehicles)
        val noPlate = LogisticsAggregate.aggregate(
            listOf(storedRow("10231", orderNo = "PH101", direction = "北京朝阳", status = LogisticsStatus.LOADED, missing = "车牌")),
            LogisticsBasis.ORDER,
        )
        assertTrue("缺车牌列不影响默认档", noPlate.canCountVehicles)
        assertEquals(1, noPlate.vehicles)
    }

    @Test
    fun `并列车牌让人数超过单数时自检标可疑`() {
        // 人造 fixture（裁定 5）：一单里并列两台车 ⇒ PLATE 口径数出 2 车、只有 1 单
        val odd = FakeLogisticsStore().apply {
            put(storedRow("10231", orderNo = "PH101", direction = "北京朝阳", plate = "冀A12345/冀B67890", status = LogisticsStatus.LOADED, pieces = 5))
        }
        val found = LogisticsQuery.ask(odd, TODAY_KEY, "北京朝阳装没装", LogisticsBasis.PLATE) as QueryOutcome.Found
        assertTrue("车数>单数必须自己说出来", found.agg.suspicious)
        val line = LogisticsAnswer.summaryLine(found.spec, found.agg)
        assertTrue("答案里要出现「口径可疑」：$line", line.contains("口径可疑（车数>单数）"))
        // 默认（配货单）口径下同一份数据不该标可疑：一张配货单就是一辆车，拆不出第二辆
        val byOrder = LogisticsAggregate.aggregate(odd.rowsFor(TODAY_KEY), LogisticsBasis.ORDER)
        assertFalse(byOrder.suspicious)
        assertEquals(1, byOrder.vehicles)
    }

    @Test
    fun `件数全空时合计是没有而不是零`() {
        val none = FakeLogisticsStore().apply {
            put(
                storedRow("10231", direction = "北京朝阳", pieces = null, status = LogisticsStatus.PENDING),
                storedRow("10232", direction = "北京朝阳", pieces = null, status = LogisticsStatus.LOADED),
            )
        }
        val agg = LogisticsAggregate.aggregate(none.rowsFor(TODAY_KEY), LogisticsBasis.PLATE)
        assertNull(agg.piecesTotal)
        assertEquals(2, agg.piecesUnknown.size)
    }

    @Test
    fun `状态四档各归各处不互相塞`() {
        val mixed = FakeLogisticsStore().apply {
            put(
                storedRow("1", status = LogisticsStatus.LOADED, plate = "冀A1"),
                storedRow("2", status = LogisticsStatus.SENT, plate = "冀A2"),
                storedRow("3", status = LogisticsStatus.PENDING, plate = "冀A3"),
                storedRow("4", status = LogisticsStatus.SHORT, plate = "冀A4"),
                storedRow("5", status = LogisticsStatus.UNKNOWN, plate = "冀A5"),
            )
        }
        val agg = LogisticsAggregate.aggregate(mixed.rowsFor(TODAY_KEY), LogisticsBasis.PLATE)
        // 已发也算装过（发出去的车必然装过）
        assertEquals(listOf("1", "2"), agg.loaded.map { it.waybill })
        assertEquals(listOf("3"), agg.pending.map { it.waybill })
        assertEquals(listOf("4"), agg.shortShip.map { it.waybill })
        assertEquals(listOf("5"), agg.statusUnknown.map { it.waybill })
    }

    @Test
    fun `人勾的状态盖过表里的`() {
        val s = store()
        s.setStatusUser(TODAY_KEY, "10233", LogisticsStatus.LOADED)
        val agg = LogisticsAggregate.aggregate(s.rowsFor(TODAY_KEY), LogisticsBasis.PLATE)
        assertTrue("10233" in agg.loaded.map { it.waybill })
        // 取消勾选回到表里的状态（表里是未填 ⇒ 仍答不出）
        s.setStatusUser(TODAY_KEY, "10233", null)
        val back = LogisticsAggregate.aggregate(s.rowsFor(TODAY_KEY), LogisticsBasis.PLATE)
        assertTrue("10233" in back.statusUnknown.map { it.waybill })
    }

    @Test
    fun `查当天没表时回 notable 而不是空答案`() {
        val outcome = LogisticsQuery.ask(FakeLogisticsStore(), TODAY_KEY, "北京朝阳装没装", LogisticsBasis.PLATE)
        assertEquals(QueryOutcome.NoTable, outcome)
    }

    @Test
    fun `库里只有别天时今天也算没表`() {
        val s = FakeLogisticsStore().apply { put(storedRow("10231", day = "20261024", direction = "北京朝阳")) }
        assertEquals(QueryOutcome.NoTable, LogisticsQuery.ask(s, TODAY_KEY, "北京朝阳装没装", LogisticsBasis.PLATE))
        assertEquals(listOf("20261024"), s.daySummaries().map { it.day })
    }

    // ── 「这车谁的／老张电话」（2026-10-25 新增口径）──────────────────────────

    /** 司机这维另摆一份：两司三单，王师傅占两张（同一份 fixture 的号码是一真一空）。 */
    private fun crew(): FakeLogisticsStore = FakeLogisticsStore().apply {
        put(
            storedRow("10231", direction = "北京朝阳", driver = "王师傅", phone = "138 0013 8000"),
            storedRow("10232", direction = "北京朝阳", driver = "王师傅", phone = "13800008000"),
            storedRow("10233", direction = "上海青浦", driver = "张师傅", phone = ""),
        )
    }

    @Test
    fun `这车谁的落到 WHO 意图而且不许落成表里没有`() {
        val spec = LogisticsQuery.parse("这车谁的", crew().rowsFor(TODAY_KEY))
        assertEquals(LogisticsIntent.WHO, spec.intent)
        assertEquals("「这车」是指代不是名词，不许当 unmatchedTerm", "", spec.unmatchedTerm)
        assertTrue(spec.drivers.isEmpty())
        val outcome = LogisticsQuery.ask(crew(), TODAY_KEY, "这车谁的", LogisticsBasis.ORDER)
        assertTrue("表里有单就该答是谁的：$outcome", outcome is QueryOutcome.Found)
    }

    @Test
    fun `老张电话按姓认到司机而且答的就是他的那几单`() {
        val spec = LogisticsQuery.parse("老张电话", crew().rowsFor(TODAY_KEY))
        assertEquals(LogisticsIntent.WHO, spec.intent)
        assertEquals(listOf("张师傅"), spec.drivers)
        val outcome = LogisticsQuery.ask(crew(), TODAY_KEY, "老张电话", LogisticsBasis.ORDER) as QueryOutcome.Found
        assertEquals(listOf("10233"), outcome.agg.rows.map { it.waybill })
    }

    @Test
    fun `报名字走整名命中`() {
        val spec = LogisticsQuery.parse("王师傅的电话", crew().rowsFor(TODAY_KEY))
        assertEquals(LogisticsIntent.WHO, spec.intent)
        assertEquals(listOf("王师傅"), spec.drivers)
    }

    @Test
    fun `同姓两个司机一起带上而不是挑一个`() {
        val s = FakeLogisticsStore().apply {
            put(
                storedRow("10231", direction = "北京朝阳", driver = "张师傅", phone = "13800000001"),
                storedRow("10232", direction = "上海青浦", driver = "张三", phone = "13800000002"),
            )
        }
        val spec = LogisticsQuery.parse("老张电话", s.rowsFor(TODAY_KEY))
        assertEquals(listOf("张师傅", "张三"), spec.drivers)
    }

    @Test
    fun `货主是谁答表里没有这一列而不是没有记录`() {
        val spec = LogisticsQuery.parse("货主是谁", crew().rowsFor(TODAY_KEY))
        assertEquals(LogisticsIntent.WHO, spec.intent)
        assertEquals("货主", spec.unmatchedColumn)
        val outcome = LogisticsQuery.ask(crew(), TODAY_KEY, "货主是谁", LogisticsBasis.ORDER)
        assertTrue("点名缺列要走拒答：$outcome", outcome is QueryOutcome.NoMatch)
    }

    @Test
    fun `问一个表里没有的人按没有记录拒答`() {
        val spec = LogisticsQuery.parse("李四是谁", crew().rowsFor(TODAY_KEY))
        assertEquals("", spec.unmatchedColumn)
        assertEquals("李四", spec.unmatchedTerm)
    }
}
