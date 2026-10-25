// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百九十五批 · 每日发货台账（派工单 §4：`<工作区>/产物/物流/YYYYMMDD-发货台账.md`，含数据时间与来源文件）。
//
// 这一档钉三件事：
// 1. **形状**：一列一件事，列名与契约 [LogisticsField.label] 同源（另造一份字符串迟早会漂成
//    「台账写重量、面板写计费重量」两套话）；
// 2. **同一套诚实规矩**：空写「未填」不给 0、件数没读出来不进合计、手勾的状态要标「（手勾）」；
// 3. **不生成的那一天**：库里没单子时不许写一份只有标题的空台账——那会把「今天没导表」
//    伪装成「今天没发货」，这两件事对她完全不同。
package com.example.zhengdao.baopu.logistics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LogisticsLedgerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun rows(): List<StoredRow> = listOf(
        storedRow("10231", orderNo = "PH101", direction = "北京朝阳", unload = "三间房", goods = "钢材", pieces = 12, weight = "3.5", volume = "4.2", driver = "王师傅", phone = "13800000001", plate = "冀A12345", status = LogisticsStatus.LOADED, lineNo = 2),
        storedRow("10232", orderNo = "PH101", direction = "北京朝阳", unload = "四惠", goods = "家具", pieces = 8, driver = "李师傅", phone = "13800000002", plate = "冀B67890", status = LogisticsStatus.PENDING, lineNo = 3),
        // 件数那格写的是「一批」：列在，但这格不是数字 ⇒ 原文要跟着进台账
        storedRow("10233", orderNo = "PH102", direction = "上海青浦", unload = "华新", goods = "钢材", pieces = null, driver = "张师傅", status = LogisticsStatus.UNKNOWN, lineNo = 4)
            .copy(piecesRaw = "一批"),
    )

    private fun render(
        list: List<StoredRow> = rows(),
        absent: Set<LogisticsField> = emptySet(),
        at: ExportedAt = ExportedAt(NOW, ExportedAtSource.FILENAME),
    ): String = LogisticsLedger.render(TODAY_KEY, list, absent, at, listOf("配货单$TODAY_KEY.csv"), TZ)

    private fun body(text: String): List<String> = text.lines()

    // ── 形状 ─────────────────────────────────────────────────────────────────

    @Test
    fun `标题用带横杠的日期，文件名用不带横杠的键`() {
        val head = render().lines().first()
        assertEquals("# 2026-10-25 发货台账", head)
        assertEquals("2026-10-25", LogisticsLedger.dayTitle(TODAY_KEY))
        // 非 8 位的键原样留着（不许截断成别的字）
        assertEquals("今天", LogisticsLedger.dayTitle("今天"))
    }

    @Test
    fun `表头列名与契约同源而且顺序固定`() {
        val table = render().lines().filter { it.startsWith("|") }
        // §1.8（2026-10-25 冻结）点名的四列全部进表：配货单号、车牌、司机姓名、司机手机号。
        // 台账属于「显示」那一面，所以照实写；日志与审计是另一面（见 LogisticsGateTest）。
        assertEquals(
            "| 来源 | 单号 | 配货单号 | 方向 | 卸点 | 货名 | 件数 | 车牌 | 司机 | 手机号 | 状态 |",
            table[0],
        )
        assertEquals("| --- | " + (1..10).joinToString(" | ") { "---" } + " |", table[1])
        // 重量**不进表格**：单位没法从格子里证明，它是「只展示、不参与任何计算」那一维
        assertFalse("重量不进台账：${table[0]}", table[0].contains("重量"))
        // 体积同理（§1.8 只裁了「读进来、看得见」，没裁过任何换算；方/吨的换算归 194）
        assertFalse("体积不进台账：${table[0]}", table[0].contains("体积"))
        // 列名与枚举同源：数一下就知道有没有人偷偷在台账里另造一份话
        assertEquals(10, LogisticsField.entries.size - 2)
    }

    @Test
    fun `来源那一格带文件名与真实行号`() {
        val line = render().lines().first { it.contains("10231") }
        assertTrue("要指到文件：$line", line.contains("配货单$TODAY_KEY.csv 第 2 行"))
    }

    @Test
    fun `数据时间与来源文件各自一行`() {
        val text = render()
        assertTrue(text.contains("- 数据时间：2026-10-25 09:30（取自文件名）"))
        assertTrue(text.contains("- 来源文件：配货单$TODAY_KEY.csv"))
    }

    @Test
    fun `取不到数据时间时台账照样写而不是编一个`() {
        val text = render(at = ExportedAt(null, ExportedAtSource.NONE, hasClock = false))
        assertTrue(text.contains("取不到（来源：取不到）"))
        assertFalse("不许拿墙上时钟顶：$text", text.contains("09:30"))
    }

    @Test
    fun `只有日期时数据时间不写零点`() {
        val text = render(at = ExportedAt(NOW, ExportedAtSource.MTIME, hasClock = false))
        assertTrue(text.contains("- 数据时间：2026-10-25（取自文件时间（表里和文件名都没有时间戳））"))
        assertFalse(text.contains("00:00"))
    }

    @Test
    fun `状态分档计数一行报全`() {
        assertTrue(render().contains("- 共 3 单（已装 1、待发 1、缺件 0、状态未填 1）"))
    }

    @Test
    fun `件数合计交代口径，一格都没数时不写零`() {
        assertTrue(render().contains("- 件数合计：20 件（另有 1 单件数未填，没算进来）"))
        val none = rows().map { it.copy(pieces = null, piecesRaw = "一批") }
        val text = render(none)
        assertTrue("全空时不写 0：$text", text.contains("- 件数合计：没有一件有数（件数这一维没读出来，不写 0）"))
        assertFalse("不许出现「0 件」：$text", text.contains("0 件"))
    }

    @Test
    fun `空字段写未填，件数读不出时把原文带上`() {
        val line = render().lines().first { it.contains("10233") }
        // 件数、车牌、手机号、状态四格都是「未填」；件数那格带原文，对账的人才知道表里写了什么
        assertTrue("空值一律写未填：$line", line.contains("| 未填（原写「一批」） | 未填 | 张师傅 | 未填 | 未填 |"))
    }

    @Test
    fun `冻结口径点名的四列原样进台账，不打码不脱敏`() {
        val line = render().lines().first { it.contains("10231") }
        listOf("PH101", "冀A12345", "王师傅", "13800000001").forEach {
            assertTrue("「$it」要一字不差地看得见：$line", line.contains(it))
        }
        // 打码的几种写法一个都不许出现：她拿这份文件去 T9 核对时要能一字一字对上
        listOf("***", "1380000****", "王**", "（已脱敏）", "…号").forEach {
            assertFalse("台账不许打码「$it」：$line", line.contains(it))
        }
    }

    @Test
    fun `缺手机号列时那一格写未填而不是编一个号`() {
        val text = render(absent = setOf(LogisticsField.PHONE))
        assertTrue("开头要点名缺这一列：$text", text.contains("「手机号」"))
        val line = text.lines().first { it.contains("10231") }
        assertTrue("缺列 ⇒ 这一格未填：$line", line.contains("| 王师傅 | 未填 | 已装 |"))
        assertFalse("库里存着的号不许在缺列的台账里冒出来：$line", line.contains("13800000001"))
    }

    @Test
    fun `缺列在开头点名并说明那是这一维没说`() {
        val text = render(absent = setOf(LogisticsField.PIECES, LogisticsField.PLATE))
        assertTrue("要念出缺哪两列：$text", text.contains("本表没有的列：「件数」、「车牌」"))
        assertTrue("要说清「未填」是什么意思：$text", text.contains("那是「这一维没说」，不是「有人没填」"))
        // 缺列那一维在表里一律未填，不许按 0 或按猜出来的值写
        val row = text.lines().first { it.contains("10231") }
        assertTrue("缺件数与车牌两列仍写未填：$row", row.contains("| 未填 | 未填 | 王师傅 | 13800000001 | 已装 |"))
    }

    @Test
    fun `手勾的状态要标手勾`() {
        val ticked = rows().map { if (it.waybill == "10233") it.copy(statusUser = LogisticsStatus.LOADED.name) else it }
        val line = render(ticked).lines().first { it.contains("10233") }
        assertTrue("人勾的要看得出来：$line", line.contains("已装（手勾）"))
        // 没勾的那行不带这三个字
        assertFalse(render().lines().first { it.contains("10231") }.contains("手勾"))
    }

    @Test
    fun `竖线与换行转义，表格不许被撑破`() {
        val odd = listOf(storedRow("10231", direction = "北京|朝阳", goods = "钢材\n附加", pieces = 1, plate = "冀A12345", status = LogisticsStatus.LOADED))
        val line = render(odd).lines().first { it.contains("10231") }
        assertTrue("竖线要转义：$line", line.contains("北京\\|朝阳"))
        assertTrue("换行要换成可见符号：$line", line.contains("钢材／附加"))
    }

    @Test
    fun `并列车牌用斜杠存回一列`() {
        val two = listOf(storedRow("10231", direction = "北京朝阳", plate = "冀A12345/冀B67890", pieces = 5, status = LogisticsStatus.LOADED))
        val line = render(two).lines().first { it.contains("10231") }
        assertTrue("两台车都要看得见：$line", line.contains("冀A12345/冀B67890"))
    }

    @Test
    fun `末尾要写清只在数据时间之前成立`() {
        assertTrue(render().contains("之后的变化不在这份表里"))
    }

    // ── 落盘 ─────────────────────────────────────────────────────────────────

    @Test
    fun `台账落在 产物 物流 下，文件名是日期键`() {
        val workspace = tmp.root
        val w = LogisticsLedger.write(workspace, TODAY_KEY, render(), 3)
        assertTrue("$w", w is LedgerWrite.Written)
        val file = LogisticsPaths.ledgerFileUnder(workspace, TODAY_KEY)
        assertTrue("文件要真存在：${file.absolutePath}", file.isFile)
        assertEquals("台账已记：产物/物流/$TODAY_KEY-发货台账.md（3 单）", LogisticsLedger.report(w))
    }

    @Test
    fun `重记同一天时覆盖并说出来`() {
        val workspace = tmp.root
        LogisticsLedger.write(workspace, TODAY_KEY, render(), 3)
        val again = LogisticsLedger.write(workspace, TODAY_KEY, render(), 4)
        assertTrue("$again", (again as LedgerWrite.Written).overwritten)
        assertTrue(LogisticsLedger.report(again).contains("覆盖旧的一份"))
        // 覆盖式：一天只有一份，不留 .tmp 垃圾
        val dir = LogisticsPaths.logisticsDirUnder(workspace)
        assertEquals(listOf("$TODAY_KEY-发货台账.md"), dir.list()?.sorted())
    }

    @Test
    fun `库里这一天没单子时不生成空台账`() {
        val workspace = tmp.root
        val store = FakeLogisticsStore()
        val w = LogisticsLedger.writeForDay(workspace, store, TODAY_KEY, TZ)
        assertTrue("$w", w is LedgerWrite.Failed)
        assertTrue("要说清为什么没写：$w", (w as LedgerWrite.Failed).reason.contains("台账不生成"))
        assertFalse(
            "不许留一份只有标题的空文件：这会把「没导表」伪装成「没发货」",
            LogisticsPaths.ledgerFileUnder(workspace, TODAY_KEY).isFile,
        )
    }

    @Test
    fun `现读现写用的行与库里的次序一致`() {
        val workspace = tmp.root
        val store = FakeLogisticsStore().apply {
            put(
                storedRow("10231", direction = "北京朝阳", pieces = 12, plate = "冀A12345", status = LogisticsStatus.LOADED, lineNo = 5),
                storedRow("10232", direction = "北京朝阳", pieces = 8, plate = "冀B67890", status = LogisticsStatus.PENDING, lineNo = 2),
            )
        }
        val w = LogisticsLedger.writeForDay(workspace, store, TODAY_KEY, TZ)
        assertTrue("$w", w is LedgerWrite.Written)
        val text = LogisticsPaths.ledgerFileUnder(workspace, TODAY_KEY).readText()
        // 按来源行号序，不重排：重排一次，「来源行」就和她在 Excel 里数出来的行号对不上
        assertTrue(text.indexOf("10232") < text.indexOf("10231"))
    }

    @Test
    fun `report 的措辞只有一处，不许出现双前缀`() {
        val failed = LedgerWrite.Failed("台账没写成：盘满了")
        assertEquals("台账没写成：盘满了", LogisticsLedger.report(failed))
        assertFalse(LogisticsLedger.report(failed).contains("台账没写成：台账没写成"))
        val written = LedgerWrite.Written("产物/物流/$TODAY_KEY-发货台账.md", 2, overwritten = false)
        assertEquals("台账已记：产物/物流/$TODAY_KEY-发货台账.md（2 单）", LogisticsLedger.report(written))
    }

    @Test
    fun `写盘失败的那一句要能转述给用户`() {
        // 拿一个不能当目录用的路径模拟写不进去（临时文件里的一个普通文件当 workspace）
        val blocker = File(tmp.root, "occupied")
        blocker.writeText("我不是目录")
        val w = LogisticsLedger.write(blocker, TODAY_KEY, "正文", 1)
        assertTrue("写不进去必须报 Failed：$w", w is LedgerWrite.Failed)
        assertTrue("句子要完整：$w", LogisticsLedger.report(w).startsWith("台账没写成："))
    }
}
