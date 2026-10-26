// 独立开发声明：本文件为本项目原创。
package com.example.zhengdao.baopu.cards

import com.example.zhengdao.baopu.logistics.Aggregate
import com.example.zhengdao.baopu.logistics.ExportedAtSource
import com.example.zhengdao.baopu.logistics.LogisticsBasis
import com.example.zhengdao.baopu.logistics.LogisticsField
import com.example.zhengdao.baopu.logistics.LogisticsIntent
import com.example.zhengdao.baopu.logistics.QueryOutcome
import com.example.zhengdao.baopu.logistics.QuerySpec
import com.example.zhengdao.baopu.logistics.StoredRow
import java.io.File
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第二百一十二批 · 195 → 卡 的适配（派工单 §2 的字段表）。
 *
 * 这个测试同时干两件事：
 * ① 钉住「卡上的字段名 ↔ 模板 JSON 的 key ↔ 适配层写入的 key」三者对得上——
 *    只要有一处拼错，对应那一行就会变成「未填」，而「永远未填」在真机上看起来像表被读坏了；
 * ② 钉住隐私面：客户/司机/电话三行必须带 `sensitiveInLog`，客户两行注定未填（195 没有这两列）。
 */
class ResultCardLogisticsTest {

    private val zone: TimeZone = TimeZone.getTimeZone("Asia/Shanghai")

    private fun registry(): ResultCardRegistry = ResultCardRegistry { path ->
        val rel = path.removePrefix("baopu/cards/")
        val f = File("src/main/assets/baopu/cards/$rel")
            .let { if (it.exists()) it else File("app/src/main/assets/baopu/cards/$rel") }
        if (f.isFile) f.readText() else null
    }

    private fun row(
        waybill: String = "1234567",
        orderNo: String = "88001",
        direction: String = "北京",
        unload: String = "朝阳",
        goods: String = "钢材",
        pieces: Int? = 12,
        piecesRaw: String = "",
        weight: String = "3.2吨",
        volume: String = "4.5方",
        driver: String = "王师傅",
        phone: String = "13800138000",
        plate: String = "冀A12345",
        status: String = "LOADED",
    ): StoredRow = StoredRow(
        day = "2026-10-26",
        waybill = waybill,
        orderNo = orderNo,
        direction = direction,
        unload = unload,
        goods = goods,
        pieces = pieces,
        piecesRaw = piecesRaw,
        weight = weight,
        volume = volume,
        driver = driver,
        phone = phone,
        plate = plate,
        status = status,
        statusRaw = status,
        statusUser = "",
        missing = "",
        exportedAt = 1_760_000_000_000L,
        exportedClock = true,
        exportedSource = ExportedAtSource.CONTENT.name,
        srcFile = "t9.xlsx",
        srcHash = "hash",
        lineNo = 1,
    )

    private fun agg(rows: List<StoredRow>, piecesTotal: Int? = 12): Aggregate = Aggregate(
        rows = rows,
        orders = rows.size,
        vehicles = rows.size,
        basis = LogisticsBasis.ORDER,
        piecesTotal = piecesTotal,
        loaded = rows,
        pending = emptyList(),
        shortShip = emptyList(),
        statusUnknown = emptyList(),
        piecesUnknown = emptyList(),
        absent = emptySet(),
        suspicious = false,
    )

    private fun spec(): QuerySpec = QuerySpec(
        intent = LogisticsIntent.COUNT,
        scopes = listOf("北京"),
        unmatchedTerm = "",
        plate = null,
        waybill = null,
        goods = null,
        ambiguous = false,
    )

    private fun found(rows: List<StoredRow>, piecesTotal: Int? = 12): QueryOutcome =
        QueryOutcome.Found(spec(), agg(rows, piecesTotal))

    private val lookup: (String) -> String = { key -> baopuCardStringsFake(key) }

    /** 假文案表：与 `BaopuCardStrings.kt` 同思路（键回显 + 未填用一句能看出来的人话）。 */
    private fun baopuCardStringsFake(key: String): String = when (key) {
        "baopu_card_unfilled" -> "未填"
        "baopu_card_logistics_peer" -> "%1\$s · %2\$s 件 · %3\$s"
        else -> key
    }

    private fun cardOf(outcome: QueryOutcome): ResultCardSpec? =
        ResultCardLogistics.cardOf(outcome, registry(), lookup, zone)

    @Test
    fun `字段表对得上_单据小结线路车都在`() {
        val card = cardOf(found(listOf(row())))!!
        val keys = card.rows.associateBy { it.key }
        // 小结（195 的聚合数）：她问「北京装没装」时答案就是这几行
        assertEquals("1", keys.getValue("orders").value)
        assertEquals("12", keys.getValue("piecesTotal").value)
        // 单据 / 货 / 线路 / 车
        assertEquals("1234567", keys.getValue("waybill").value)
        assertEquals("88001", keys.getValue("orderNo").value)
        assertEquals("已装", keys.getValue("status").value)
        assertEquals("钢材", keys.getValue("goods").value)
        assertEquals("12", keys.getValue("pieces").value)
        assertEquals("北京", keys.getValue("direction").value)
        assertEquals("朝阳", keys.getValue("unload").value)
        assertEquals("王师傅", keys.getValue("driver").value)
        assertEquals("冀A12345", keys.getValue("plate").value)
        assertEquals(1, card.itemCount)
        // 数据时间取的是 195 的 display（不是本层自己格式化时间）
        assertTrue(
            "数据时间应当是一个日期时间串（走的是 195 的 display）",
            Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}""").matches(keys.getValue("dataTime").value),
        )
    }

    @Test
    fun `客户两行注定未填_因为 195 没有这两列`() {
        val card = cardOf(found(listOf(row())))!!
        val rows = card.rows.associateBy { it.key }
        assertEquals("未填", rows.getValue("customerName").value)
        assertEquals("未填", rows.getValue("customerPhone").value)
        assertFalse(rows.getValue("customerPhone").dialable)
        assertNull(rows.getValue("customerPhone").dial)
        // 隐私标记：客户两行 + 司机 + 司机电话
        assertEquals(
            setOf("customerName", "customerPhone", "driver", "driverPhone"),
            card.rows.filter { it.sensitiveInLog }.map { it.key }.toSet(),
        )
    }

    @Test
    fun `司机电话可拨且显示原样`() {
        val card = cardOf(found(listOf(row(phone = "138 0013 8000"))))!!
        val phone = card.rows.first { it.key == "driverPhone" }
        assertEquals("138 0013 8000", phone.value)
        assertEquals("13800138000", phone.dial)
    }

    @Test
    fun `号码形状不过就不可点_原样显示`() {
        // 「微信同号」不是号码（195 的形状判据），卡上照旧原样显示，只是不可点
        val card = cardOf(found(listOf(row(phone = "微信同号"))))!!
        val phone = card.rows.first { it.key == "driverPhone" }
        assertEquals("微信同号", phone.value)
        assertNull(phone.dial)
    }

    @Test
    fun `件数整维没有就写未填_不补零`() {
        val card = cardOf(found(listOf(row(pieces = null)), piecesTotal = null))!!
        val rows = card.rows.associateBy { it.key }
        assertEquals("未填", rows.getValue("piecesTotal").value)
        assertEquals("未填", rows.getValue("pieces").value)
    }

    @Test
    fun `没命中或没表不弹卡`() {
        assertNull(cardOf(QueryOutcome.NoTable))
        assertNull(cardOf(QueryOutcome.NoMatch(spec())))
        assertNull(cardOf(QueryOutcome.Ambiguous("北京", listOf("北京", "北京朝阳"))))
        assertNull("有 Found 但没有行也不弹卡", cardOf(found(emptyList())))
    }

    @Test
    fun `多票一张卡里分条`() {
        val card = cardOf(found(listOf(row(), row(waybill = "2233445", direction = "天津"), row(waybill = "3344556", plate = ""))))!!
        assertEquals(3, card.itemCount)
        val others = card.groups.first { it.key == "others" }
        assertEquals(2, others.rows.size)
        assertEquals(listOf("2233445", "3344556"), others.rows.map { it.label })
        // 缺车牌的那一票照实说未填，不空着
        assertTrue(others.rows[1].value.contains("未填"))
    }

    @Test
    fun `所有模板行要么有值要么是已知的两行缺列`() {
        val card = cardOf(found(listOf(row())))!!
        val unfilled = card.rows.filter { !it.filled }.map { it.key }.toSet()
        assertEquals("除客户两行外，模板里的每个 key 都必须由适配层填得上", setOf("customerName", "customerPhone"), unfilled)
        // 195 字段契约里确实没有客户列——把这条事实写进测试，将来补列时这里会红，提醒改卡
        assertTrue(
            LogisticsField.entries.none { it.label.contains("客户") },
        )
    }
}
