// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百九十五批 · 未发播报（派工单 §4：v1 **不做定时**，只在「打开抱朴时」提示一句）。
//
// 闸门只有两条，都能脱库断言：
// 1. **按天一次**：今天提示过就不再提示（每次回前台弹一遍＝同一个消息催两遍，第二遍起是噪音，
//    而她真正会做的是「关掉它再继续」，那样连第一遍也丢了）；跨天自然复位，不需要定时任务来清。
// 2. **只报待发档**：状态写「缺件」和状态没填的单子不进「没发」的名单，但各点一句
//    （「另有 2 单标了缺件」是把事实交回去，「应该还没发」是替她下判断）。
//
// `forOpen`/`shownDay`/`markShown` 那三个要 Context，本档不测（本机没有 Robolectric）；
// 「渲染出来才记一笔」这条由调用侧的 `LaunchedEffect` 保证，写在交付报告 §接线 里。
package com.example.zhengdao.baopu.logistics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LogisticsNoticeTest {

    private fun pending(vararg ids: String): List<StoredRow> = ids.map {
        storedRow(
            it,
            direction = "北京朝阳",
            pieces = 12,
            plate = "冀A12345",
            status = LogisticsStatus.PENDING,
            lineNo = ids.indexOf(it) + 2,
        )
    }

    // ── 闸门一：按天一次 ──────────────────────────────────────────────────────

    @Test
    fun `今天提示过就不再提示，跨天自然复位`() {
        assertTrue(LogisticsNotice.shouldShow(null, TODAY_KEY, 1))
        assertFalse("同一个催第二遍就是噪音", LogisticsNotice.shouldShow(TODAY_KEY, TODAY_KEY, 1))
        assertTrue("昨天提示过，今天该提示", LogisticsNotice.shouldShow("20261024", TODAY_KEY, 1))
        assertFalse("没单子就没什么可播", LogisticsNotice.shouldShow(null, TODAY_KEY, 0))
    }

    @Test
    fun `今天的键由手机日历算，不由表算`() {
        assertEquals(TODAY_KEY, LogisticsAsk.dayKeyOf(NOW, TZ))
        assertEquals("20261024", LogisticsAsk.dayKeyOf(millisOf(24), TZ))
        // 差一小时还在同一天 ⇒ 键不变（否则「今天」会被钟点劈成两天）
        assertEquals(TODAY_KEY, LogisticsAsk.dayKeyOf(millisOf(25, 23, 59), TZ))
    }

    // ── 只报待发档 ───────────────────────────────────────────────────────────

    @Test
    fun `已装已发的不进名单，缺件与状态没填各点一句`() {
        val rows = listOf(
            storedRow("10231", direction = "北京朝阳", pieces = 12, plate = "冀A12345", status = LogisticsStatus.LOADED, lineNo = 2),
            storedRow("10232", direction = "北京朝阳", pieces = 8, plate = "冀B67890", status = LogisticsStatus.PENDING, lineNo = 3),
            storedRow("10233", direction = "上海青浦", pieces = 5, plate = "冀C11111", status = LogisticsStatus.SENT, lineNo = 4),
            storedRow("10234", direction = "北京朝阳", pieces = 3, plate = "冀D22222", status = LogisticsStatus.SHORT, lineNo = 5),
            storedRow("10235", direction = "北京朝阳", pieces = 4, plate = "冀E33333", status = LogisticsStatus.UNKNOWN, lineNo = 6),
        )
        val text = LogisticsNotice.textOf(rows, ExportedAt(NOW, ExportedAtSource.FILENAME), TZ) ?: ""
        assertTrue("只列待发那一单：$text", text.contains("今天还有 1 单没发：10232（北京朝阳，8 件）"))
        assertTrue("已发的不许出现在名单里：$text", !text.contains("10233"))
        assertTrue("缺件单列：$text", text.contains("另有 1 单标了缺件"))
        assertTrue("状态没填单列：$text", text.contains("另有 1 单状态未填，没法算进没发"))
        assertTrue("要带数据时间：$text", text.contains("数据 09:30 导出"))
    }

    @Test
    fun `一条都没待发时不播`() {
        val allShipped = listOf(
            storedRow("10231", direction = "北京朝阳", pieces = 12, plate = "冀A12345", status = LogisticsStatus.LOADED),
            storedRow("10233", direction = "上海青浦", pieces = 5, plate = "冀C11111", status = LogisticsStatus.SENT),
        )
        assertNull(LogisticsNotice.textOf(allShipped, ExportedAt(NOW, ExportedAtSource.FILENAME), TZ))
        // 只有「状态没填」也不算有待发（宁可少播，不能把作废的单催一遍）
        val unknownOnly = listOf(storedRow("10235", direction = "北京朝阳", status = LogisticsStatus.UNKNOWN))
        assertNull(LogisticsNotice.textOf(unknownOnly, ExportedAt(NOW, ExportedAtSource.FILENAME), TZ))
    }

    @Test
    fun `人勾的待发进名单，勾成已装的不进`() {
        val rows = listOf(
            // 表里写已装，她手动改成待发 ⇒ 人是最新事实
            storedRow("10231", direction = "北京朝阳", pieces = 12, status = LogisticsStatus.LOADED, statusUser = LogisticsStatus.PENDING.name),
            storedRow("10232", direction = "北京朝阳", pieces = 8, status = LogisticsStatus.PENDING, statusUser = LogisticsStatus.LOADED.name),
        )
        assertEquals(listOf("10231"), LogisticsNotice.pendingRows(rows).map { it.waybill })
        val text = LogisticsNotice.textOf(rows, ExportedAt(NOW, ExportedAtSource.FILENAME), TZ) ?: ""
        assertTrue(text.contains("10231") && !text.contains("10232"))
    }

    // ── 一条点名长什么样 ─────────────────────────────────────────────────────

    @Test
    fun `缺哪个都写未填而不是省略`() {
        val full = storedRow("10231", direction = "北京朝阳", pieces = 12)
        assertEquals("10231（北京朝阳，12 件）", LogisticsNotice.itemOf(full))
        // 只有卸点时用它当范围（契约保证两列至少有一列）
        val unloadOnly = storedRow("10232", unload = "三间房", pieces = 8)
        assertEquals("10232（三间房，8 件）", LogisticsNotice.itemOf(unloadOnly))
        val blank = storedRow("10233", direction = "北京朝阳", pieces = null)
        assertEquals("省略会让两格看起来像两种东西：", "10233（北京朝阳，件数未填）", LogisticsNotice.itemOf(blank))
        val nothing = storedRow("10234")
        assertEquals("10234（未填，件数未填）", LogisticsNotice.itemOf(nothing))
    }

    @Test
    fun `超过三条折名单但条数说全`() {
        val text = LogisticsNotice.textOf(pending("20001", "20002", "20003", "20004", "20005"), ExportedAt(NOW, ExportedAtSource.FILENAME), TZ) ?: ""
        assertTrue("要折叠：$text", text.contains("等 5 单，展开看"))
        assertTrue("数量不许跟着折：$text", text.startsWith("今天还有 5 单没发："))
        assertTrue(text.contains("20001") && text.contains("20003") && !text.contains("20004"))
    }

    @Test
    fun `数据时间取不到时也要说取不到`() {
        val text = LogisticsNotice.textOf(pending("10232"), ExportedAt(null, ExportedAtSource.NONE, hasClock = false), TZ) ?: ""
        assertTrue(text.contains("数据时间取不到"))
        assertFalse("不许拿墙上时钟顶：$text", text.contains("09:30"))
    }

    @Test
    fun `只有日期时不假装有钟点`() {
        val text = LogisticsNotice.textOf(pending("10232"), ExportedAt(NOW, ExportedAtSource.MTIME, hasClock = false), TZ) ?: ""
        assertTrue(text.contains("数据 $TODAY_KEY 那天导出"))
        assertFalse(text.contains("00:00"))
    }

    @Test
    fun `播报上限与答话纪律是同一个数`() {
        // 两处两个上限迟早会漂移，所以直接钉成同一个引用
        assertEquals(LogisticsAnswer.MAX_DETAIL, LogisticsNotice.MAX_DETAIL)
    }

    // ── 隐私：主屏那一行不许泄露单据内容 ──────────────────────────────────────

    @Test
    fun `播报里不出现货名司机车牌`() {
        val rows = listOf(
            storedRow("10232", direction = "北京朝阳", unload = "四惠", goods = "钢材", weight = "3.5", driver = "王师傅", plate = "冀B67890", pieces = 8, status = LogisticsStatus.PENDING),
        )
        val text = LogisticsNotice.textOf(rows, ExportedAt(NOW, ExportedAtSource.FILENAME), TZ) ?: ""
        listOf("钢材", "王师傅", "冀B67890", "3.5").forEach {
            assertFalse("主屏那行不许带「$it」：$text", text.contains(it))
        }
        // 单号要有（那是她去 T9 核对的唯一凭据）
        assertTrue(text.contains("10232"))
    }

    @Test
    fun `播报价只写单不写车，因为口径没地方切`() {
        val text = LogisticsNotice.textOf(pending("10231", "10232"), ExportedAt(NOW, ExportedAtSource.FILENAME), TZ) ?: ""
        assertTrue("要说「单」：$text", text.contains("今天还有 2 单没发"))
        assertFalse("不许在这一行替她选口径：$text", text.contains("车"))
    }
}
