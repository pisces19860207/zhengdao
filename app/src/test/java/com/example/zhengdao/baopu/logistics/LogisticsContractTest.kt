// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百九十五批 · 表头契约的判据测试（2026-10-25 改单后的两档口径）。
//
// 这里钉的是派工单 §1 的那句原则：**缺了「识别/范围」就拒读，缺了「描述」就少报一维并明说**。
// 负例②（「表头变了硬读」）在本档按新口径拆成两条：
//   - 别名表里一个都对不上 ⇒ 必须拒读（[表头全对不上时拒读]）；
//   - `车牌号`→`车牌` 这类同义写法 ⇒ 是**命中别名**，不许当成「表头变了」（[同义列名算命中别名而不是表头变了]）。
//   这两条一起才是改单后的完整判据；只留第一条会把正常导出拒掉，只留第二条会放行真·陌生的表。
package com.example.zhengdao.baopu.logistics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LogisticsContractTest {

    private fun reason(header: List<String>): String =
        (LogisticsContract.resolve(header) as HeaderOutcome.Rejected).reason

    private fun ok(header: List<String>): HeaderOutcome.Ok =
        LogisticsContract.resolve(header) as HeaderOutcome.Ok

    @Test
    fun `十二列齐全时全部认领且没有降级`() {
        val r = ok(FULL_HEADER)
        LogisticsField.entries.forEach { f ->
            assertTrue("$f 该被认到", f in r.columnOf.keys)
        }
        assertTrue("十二列齐全不该有降级维度", r.absentSoft.isEmpty())
        assertTrue("十二列齐全不该有没人认领的列", r.unclaimed.isEmpty())
        assertEquals(0, r.columnOf[LogisticsField.WAYBILL])
        assertEquals(1, r.columnOf[LogisticsField.ORDER_NO])
        assertEquals(7, r.columnOf[LogisticsField.VOLUME])
        assertEquals(9, r.columnOf[LogisticsField.PHONE])
        assertEquals(11, r.columnOf[LogisticsField.STATUS])
    }

    @Test
    fun `真实导出的十七列里单号与配货单号各归各列`() {
        // 194 批实测的那张表（40 行运单挂 9 张配货单）。这一条钉的是 §1.8 最容易踩空的地方：
        // 把 `配货单` 当成 `单号` 的别名，「同字段两列并存取最左」就会把主键落到配货单号上，
        // 40 行静默压成 9 行——那不是降级，是丢单子。
        val header = listOf(
            "发货单号", "运单号", "配货单", "客户", "目的地", "卸货地点", "品名", "件数", "件数合计",
            "重量", "总重量", "体积（方）", "总体积", "司机姓名", "司机手机号", "车牌号", "装车状态", "备注",
        )
        val r = ok(header)
        assertEquals(1, r.columnOf[LogisticsField.WAYBILL])
        assertEquals(2, r.columnOf[LogisticsField.ORDER_NO])
        assertEquals(4, r.columnOf[LogisticsField.UNLOAD])
        assertEquals(6, r.columnOf[LogisticsField.GOODS])
        assertEquals(7, r.columnOf[LogisticsField.PIECES])
        assertEquals(9, r.columnOf[LogisticsField.WEIGHT])
        assertEquals(11, r.columnOf[LogisticsField.VOLUME])
        assertEquals(13, r.columnOf[LogisticsField.DRIVER])
        assertEquals(14, r.columnOf[LogisticsField.PHONE])
        assertEquals(15, r.columnOf[LogisticsField.PLATE])
        assertEquals(16, r.columnOf[LogisticsField.STATUS])
        assertTrue("九个可降级列一张不落：${r.absentSoft}", r.absentSoft.isEmpty())
        // 「合计/总」那一档与没人认领的写法全部如实报出去（§1.8.5：配货单上没有客户信息）
        listOf("发货单号", "客户", "卸货地点", "件数合计", "总重量", "总体积", "备注").forEach {
            assertTrue("「$it」该落进 unclaimed：${r.unclaimed}", it in r.unclaimed)
        }
    }

    @Test
    fun `件数合计绝不当成件数`() {
        // 只有一张「合计」列的表：宁可不认件数（报「缺件数列」），也不能把另一粒度的合计当本行件数——
        // 那样 40 行的件数会加出两倍，正是 §1.8 那句「件数合计不翻倍」的反面
        val r = ok(listOf("单号", "方向", "件数合计", "总件数"))
        assertNull(r.columnOf[LogisticsField.PIECES])
        assertTrue("缺列名单要有件数：${r.absentSoft}", LogisticsField.PIECES in r.absentSoft)
        assertTrue(r.unclaimed.contains("件数合计"))
        assertTrue(r.unclaimed.contains("总件数"))
    }

    // ── A 档：硬列 ────────────────────────────────────────────────────────────

    @Test
    fun `缺单号整份拒读并报清缺哪列`() {
        val r = reason(listOf("方向", "卸点", "货名", "件数", "车牌", "状态"))
        assertTrue("要说清缺的是「单号」：$r", r.contains("单号"))
        assertTrue("要把认识的写法念给用户：$r", r.contains("运单号"))
        assertTrue("要明说一行都没记：$r", r.contains("一行都没记"))
    }

    @Test
    fun `方向与卸点两列都没有才拒读只缺一个照读`() {
        // 两列都缺 ⇒ 没有范围，「北京装没装」无从回答 ⇒ 拒读
        val both = reason(listOf("单号", "货名", "件数", "状态"))
        assertTrue("方向与卸点都要点名：$both", both.contains("方向") && both.contains("卸点"))
        // 只缺其中一个 ⇒ 至少有一个范围列就够，照读（且不算降级维：范围档缺哪个都由另一个兜着）
        val onlyDirection = ok(listOf("单号", "方向", "货名"))
        assertTrue(LogisticsField.DIRECTION in onlyDirection.columnOf.keys)
        assertFalse("有方向就没缺范围，不许报「缺卸点」这种假话", LogisticsField.UNLOAD in onlyDirection.absentSoft)
        val onlyUnload = ok(listOf("单号", "卸点", "货名"))
        assertTrue(LogisticsField.UNLOAD in onlyUnload.columnOf.keys)
        assertFalse(LogisticsField.DIRECTION in onlyUnload.absentSoft)
    }

    @Test
    fun `空表头与重复列名都拒读`() {
        assertTrue(reason(emptyList()).contains("表头是空的"))
        assertTrue(reason(listOf("", " ")).contains("表头是空的"))
        val dup = reason(listOf("单号", "方向", "卸点", "单号"))
        assertTrue("重复列名要点名：$dup", dup.contains("重复"))
    }

    // ── B 档：可降级列 ─────────────────────────────────────────────────────────

    @Test
    fun `缺可降级列照读其余并报清缺哪几列`() {
        val r = ok(listOf("单号", "方向", "件数"))
        assertEquals(
            setOf(
                LogisticsField.ORDER_NO,
                LogisticsField.GOODS,
                LogisticsField.WEIGHT,
                LogisticsField.VOLUME,
                LogisticsField.DRIVER,
                LogisticsField.PHONE,
                LogisticsField.PLATE,
                LogisticsField.STATUS,
            ),
            r.absentSoft,
        )
        // 件数在、车牌不在：车数那一维要降级（这条在 LogisticsAnswerTest 里钉输出）
        assertTrue(LogisticsField.PIECES !in r.absentSoft)
        assertTrue(LogisticsField.PLATE in r.absentSoft)
    }

    @Test
    fun `缺件数绝不变成拒读`() {
        // 负例 1b 的反面：改单前「缺列即拒读」会把这份只有单号+方向+状态的表整份扔掉
        val outcome = LogisticsContract.resolve(listOf("单号", "方向", "状态"))
        assertTrue("缺件数这种描述列不许拒读：$outcome", outcome is HeaderOutcome.Ok)
    }

    // ── C 档：别名表 ──────────────────────────────────────────────────────────

    @Test
    fun `同义列名算命中别名而不是表头变了`() {
        // 派工单原负例②举的例子按 2026-10-25 改单后的口径重钉：车牌号 是 车牌 的别名
        val r = ok(
            listOf(
                "运单号", "派车单", "流向", "卸货地点", "品名", "包装件数", "计费重量", "方数",
                "驾驶员", "司机手机号", "车牌号", "装车状态",
            ),
        )
        val expect = listOf(
            LogisticsField.WAYBILL,
            LogisticsField.ORDER_NO,
            LogisticsField.DIRECTION,
            LogisticsField.UNLOAD,
            LogisticsField.GOODS,
            LogisticsField.PIECES,
            LogisticsField.WEIGHT,
            LogisticsField.VOLUME,
            LogisticsField.DRIVER,
            LogisticsField.PHONE,
            LogisticsField.PLATE,
            LogisticsField.STATUS,
        )
        expect.forEachIndexed { index, field ->
            assertEquals("${field.label} 要认到第 $index 列", index, r.columnOf[field])
        }
        assertTrue("同义写法不该被当成降级", r.absentSoft.isEmpty())
        assertTrue("十二列都被认领了就不该有 unclaimed：${r.unclaimed}", r.unclaimed.isEmpty())
    }

    @Test
    fun `手机号与姓名是两列而不是同一列的两种写法`() {
        // §1.8 把「司机姓名」与「司机手机号」当成两维：混进一个字段就会把号码写进姓名那一格
        val r = ok(listOf("单号", "方向", "司机姓名", "司机手机号"))
        assertEquals(2, r.columnOf[LogisticsField.DRIVER])
        assertEquals(3, r.columnOf[LogisticsField.PHONE])
    }

    @Test
    fun `表头全对不上时拒读`() {
        // 真·负例②：一个别名都对不上（流水号/到港/箱子 都不在表里）⇒ 必须拒读，不许硬按位置读
        val r = reason(listOf("流水号", "到港", "箱子", "吨", "师傅"))
        assertTrue("要说「表头变了」：$r", r.contains("表头变了"))
        assertTrue("要报清缺的是识别列「单号」：$r", r.contains("单号"))
    }

    @Test
    fun `别名表一个写法只归一个字段`() {
        // 「已提」这类撞车会让同一句话既算已装又算已发，答案取决于遍历顺序（负例：静默错数）
        val seen = HashMap<String, LogisticsField>()
        LogisticsContract.ALIASES.forEach { (field, names) ->
            names.forEach { name ->
                val key = LogisticsContract.normalize(name)
                val prev = seen.put(key, field)
                assertNull("$key 同时属于 ${prev?.label} 与 ${field.label}：认领给谁都是猜", prev)
            }
        }
    }

    @Test
    fun `每个字段都有至少一个别名`() {
        LogisticsField.entries.forEach { f ->
            assertFalse("${f.label} 没有别名，导出里换个写法就整份拒读", LogisticsContract.aliasesOf(f).isEmpty())
        }
    }

    @Test
    fun `同一字段两列并存时只认最左另一列如实报出来`() {
        val r = ok(listOf("单号", "方向", "车牌", "车牌号"))
        assertEquals("取最左，不猜哪列才是真值", 2, r.columnOf[LogisticsField.PLATE])
        assertTrue("另一列必须报出来，不许静默丢：${r.unclaimed}", r.unclaimed.contains("车牌号"))
    }

    @Test
    fun `不认识但非空的列进unclaimed而不影响读`() {
        val r = ok(listOf("单号", "方向", "备注", "付款方式"))
        assertTrue(r.unclaimed.contains("备注"))
        assertTrue(r.unclaimed.contains("付款方式"))
        assertTrue("认到的列不受影响", r.columnOf.size == 2)
    }

    @Test
    fun `体积认列但绝不参与计算`() {
        // 体积/方数是 §1.8 加的一维：认列（进表、进台账之外的展示），但重泡判断归 194，本批不碰算式
        val r = ok(listOf("单号", "方向", "体积"))
        assertEquals(2, r.columnOf[LogisticsField.VOLUME])
        assertTrue(LogisticsField.VOLUME !in r.absentSoft)
    }

    // ── 归一（只做三件事，多做一步就是猜）────────────────────────────────────

    @Test
    fun `归一处理空白全角括号与大小写`() {
        assertEquals("单号", LogisticsContract.normalize(" 单号 "))
        assertEquals("单号", LogisticsContract.normalize("单　号"))
        assertEquals("件数", LogisticsContract.normalize("（件数）"))
        assertEquals("kg", LogisticsContract.normalize("KG"))
        assertEquals("空串才叫没列名", "", LogisticsContract.normalize("   "))
    }

    @Test
    fun `归一不去合计后缀不拆并列名`() {
        // 「件数合计」是别名表里整条收进来的写法；「合计」这种后缀如果自动剥，
        // 会把「金额合计」也认成金额那一类——那是猜，不是归一
        val r = LogisticsContract.resolve(listOf("单号", "方向", "合计"))
        assertTrue("单独的「合计」不认领，落进 unclaimed：$r", r is HeaderOutcome.Ok && (r as HeaderOutcome.Ok).unclaimed.contains("合计"))
    }

    // ── 「表里没有的字段答表里没有」（2026-10-25 新增口径）────────────────────

    @Test
    fun `配货单上根本没有的维度点名成缺列`() {
        // §1.8.5：配货单上没有客户信息——「货主是谁」问的是这张表压根不给的一维，
        // 与「表里没有老张的记录」是两件事，答案也要是两句不同的话
        listOf("货主", "客户", "发货人", "托运人", "收货人", "运费", "单价", "金额", "回单", "备注").forEach {
            assertEquals(it, it, LogisticsContract.absentColumn(it, emptyList()))
        }
    }

    @Test
    fun `缺列要全表都缺才算缺整列`() {
        // 「手机号」是认得的列：全表都没读到它（表头没这列）才说「表里没有手机号列」；
        // 只要有一行有号，那一格空就只是「未填」，不许升级成缺列
        val allMissing = listOf(
            storedRow("10231", driver = "王师傅", missing = "手机号"),
            storedRow("10232", driver = "张师傅", missing = "手机号"),
        )
        assertEquals("手机号", LogisticsContract.absentColumn("手机号", allMissing))
        val oneHas = listOf(allMissing[0], storedRow("10232", driver = "张师傅", phone = "13900000002"))
        assertEquals("", LogisticsContract.absentColumn("手机号", oneHas))
    }

    @Test
    fun `认得的列全表都在时不许说缺列`() {
        val rows = listOf(storedRow("10231", orderNo = "PH101", driver = "王师傅"))
        assertEquals("", LogisticsContract.absentColumn("配货单号", rows))
        assertEquals("", LogisticsContract.absentColumn("司机", rows))
    }

    @Test
    fun `人名与陌生长词不算缺列`() {
        // 「李四是谁」是没有这个人（unmatchedTerm 那条路），不是没有哪一列
        assertEquals("", LogisticsContract.absentColumn("李四", emptyList()))
        assertEquals("", LogisticsContract.absentColumn("北京朝阳", emptyList()))
        assertEquals("", LogisticsContract.absentColumn("", emptyList()))
    }
}
