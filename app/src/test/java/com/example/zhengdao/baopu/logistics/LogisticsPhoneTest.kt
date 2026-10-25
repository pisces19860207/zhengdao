// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百九十五批 · 号码形状判据（2026-10-25 新增口径：答案里的号码可点开拨号盘）。
//
// 这一档钉的是「什么才算一个能拨的号」——判据在 [LogisticsPhone]（纯函数），壳只接线：
// - 负例「把单号车牌当号码」：5 位的单号、带汉字的车牌都长不成链接（下限 7 位是主闸）；
// - 负例「点开空号」：这一格没写成号码（空、`未填`、一串备注）就给空表，界面写「未填」且不可点；
// - 负例「不可点」的反面：判过形的号码真的给出去（forRow/forRows 有货）；
// - 「138 0013 8000」这类带分隔符的照常成立，`shown` 原样（§1.8 不改写）、`dial` 剥干净。
package com.example.zhengdao.baopu.logistics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogisticsPhoneTest {

    @Test
    fun `带分隔符的号码照常成立且拨号串剥干净`() {
        val ns = LogisticsPhone.parse("138 0013 8000")
        assertEquals(1, ns.size)
        assertEquals("138 0013 8000", ns[0].shown)
        assertEquals("13800138000", ns[0].dial)
        assertEquals("01012345678", LogisticsPhone.parse("（010）1234-5678")[0].dial)
    }

    @Test
    fun `一格里两个号切开`() {
        val ns = LogisticsPhone.parse("13800000001、13900000002")
        assertEquals(listOf("13800000001", "13900000002"), ns.map { it.dial })
        assertEquals(2, LogisticsPhone.parse("13800000001/13900000002").size)
    }

    @Test
    fun `空格未填备注都长不成链接`() {
        // 负例「点开空号」：这些形状一律空表（界面写「未填」且不可点），不许给个点开是空的号
        listOf("", "   ", "未填", "无", "见调度", "138*转2").forEach {
            assertTrue("「$it」不该算号码", LogisticsPhone.parse(it).isEmpty())
        }
    }

    @Test
    fun `单号与车牌当不成号码`() {
        // 负例「把单号车牌当号码」：5 位单号在下限之下，车牌带汉字不是纯数字
        assertTrue(LogisticsPhone.parse("10231").isEmpty())
        assertTrue(LogisticsPhone.parse("冀A12345").isEmpty())
        // 6 位内线与 16 位粘连同样不认
        assertTrue(LogisticsPhone.parse("123456").isEmpty())
        assertTrue(LogisticsPhone.parse("1380013800013800").isEmpty())
    }

    @Test
    fun `位数下限上限都收在边界上`() {
        assertEquals("1234567", LogisticsPhone.parse("1234567")[0].dial)
        assertEquals(1, LogisticsPhone.parse("13800138000").size)
        // 15 位＝E.164 上限，收；16 位拒
        assertEquals("138001380001234", LogisticsPhone.parse("138001380001234")[0].dial)
        assertTrue(LogisticsPhone.parse("1380013800012345").isEmpty())
    }

    @Test
    fun `国际前缀只认开头一个加号`() {
        assertEquals("+8613800138000", LogisticsPhone.parse("+86 138 0013 8000")[0].dial)
        assertTrue("中间的加号说明这格粘了两串东西", LogisticsPhone.parse("138+00138000").isEmpty())
    }

    @Test
    fun `forRow 只读手机号那一格`() {
        // 结构上钉死「单号车牌不是号码来源」：这三个格就算长得像号码也不进链接
        val row = storedRow("10231", orderNo = "PH101", plate = "13800138000", phone = "")
        assertTrue(LogisticsPhone.forRow(row).isEmpty())
        val row2 = storedRow("13800138000", plate = "冀A12345", phone = "13900000002")
        assertEquals(listOf("13900000002"), LogisticsPhone.forRow(row2).map { it.dial })
    }

    @Test
    fun `forRows 按拨号串去重并且保行序`() {
        val rows = listOf(
            storedRow("10231", driver = "王师傅", phone = "138 0013 8000"),
            storedRow("10232", driver = "王师傅", phone = "13800138000"),
            storedRow("10233", driver = "张师傅", phone = "13900000002"),
        )
        val ns = LogisticsPhone.forRows(rows)
        assertEquals(listOf("13800138000", "13900000002"), ns.map { it.dial })
        assertEquals("显示保留第一次的原样写法", "138 0013 8000", ns[0].shown)
    }

    @Test
    fun `firstOrNull 是空表安全`() {
        assertTrue(LogisticsPhone.firstOrNull("") == null)
        assertEquals("13800138000", LogisticsPhone.firstOrNull("138 0013 8000")?.dial)
    }
}
