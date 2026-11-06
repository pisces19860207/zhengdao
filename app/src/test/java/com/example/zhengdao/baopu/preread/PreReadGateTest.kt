// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第四百三十六批 B · 预读回执闸：**判据面直测**（三态 + 两张纸 + 账）。
//
// 派工单 436-B 判据逐条对上（用户 2026-11-06 裁定的三态编号即这里的分组）：
//   态① 无清单 ⇒ 不启用、不拦，只落一行「本任务未要求预读」
//   态② 有清单未要求 ⇒ 提示不拦，收工对照「读了没」落一行
//   态③ 有清单且要求 ⇒ 未交回执拦住（**报错拦住，不是记账放行**）；交了 ⇒ 放行
//   「要求」两条来源：显式 requireReceipt ／ 长程·跨会话自动（复用 274 的 isLongTask）
//   回执三行缺一行 ⇒ 视为未交；本任务内有效（续跑不重交、换任务要重交）
//   收工：改了清单外 ⇒ 落账并提出，不阻断
//   回看：任务单＋回执同一屏
package com.example.zhengdao.baopu.preread

import com.example.zhengdao.baopu.agent.DeviationWiring
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreReadGateTest {

    @After
    fun tearDown() {
        PreReadBoard.resetForTest()
        DeviationWiring.installRun("", "")
    }

    // ── 造数据的小工具 ──────────────────────────────────────────────────────

    private fun item(target: String, kind: ReadFirstKind = ReadFirstKind.FILE_LINE) =
        ReadFirstItem(target = target, kind = kind, why = "改这里得先看")

    private fun order(taskKey: String, require: Boolean = false, items: List<ReadFirstItem> = listOf(item("a/Kt.kt:10"))) =
        ReadFirstOrder(taskKey = taskKey, items = items, requireReceipt = require, title = "测试任务")

    private fun receipt(taskKey: String, reads: List<String> = listOf("a/Kt.kt:10"), why: String = "要先改判定再改卡", files: List<String> = listOf("a/Kt.kt")) =
        PreReadReceipt(taskKey = taskKey, readLines = reads, understanding = why, plannedFiles = files)

    private fun arm(taskKey: String, steps: Int = 6, cross: Boolean = false) {
        DeviationWiring.installRun("run-$taskKey", taskKey)
        assertTrue("长程判定点应认这个任务为长程", PreReadWiring.armByLongTask(steps, cross))
    }

    private fun rows(kind: PreReadEventKind): List<PreReadRow> =
        PreReadLedger.readAll().filter { it.kind == kind }

    // ── 态①：无清单 ⇒ 不启用，只落一行 ──────────────────────────────────────

    @Test
    fun `态1_无清单_写工具不拦_且只落一行未要求预读`() {
        DeviationWiring.installRun("run-1", "t1")
        // 没附任何单子 ⇒ 态①。删一个临时文件这种动作不该先写回执（裁定的原话）。
        assertEquals(PreReadMode.OFF, PreReadWiring.modeNow())
        assertNull("态①不许拦写工具", PreReadWiring.beforeToolCall("delete_path", isWrite = true))
        assertNull(PreReadWiring.beforeToolCall("delete_path", isWrite = true))
        val silent = rows(PreReadEventKind.SILENT).filter { it.taskKey == "t1" }
        assertEquals("态①只落一行（第二笔写不再刷）：$silent", 1, silent.size)
        assertTrue(silent.first().text.contains("本任务未要求预读"))
    }

    @Test
    fun `没有任务键_完全不参与`() {
        // 信箱空的（闲聊轮／未回填）⇒ 不拦、不落、不编键（宁空勿猜）。
        DeviationWiring.installRun("", "")
        assertNull(PreReadWiring.beforeToolCall("write_file", isWrite = true))
        assertEquals(PreReadMode.OFF, PreReadWiring.modeNow())
        assertTrue(PreReadLedger.readAll().isEmpty())
    }

    // ── 态②：有清单、未要求 ⇒ 提示不拦 ───────────────────────────────────────

    @Test
    fun `态2_有清单没要求回执_提示一句但放行`() {
        assertEquals(emptyList<Any>(), PreReadWiring.attachOrder(order("t2")))
        DeviationWiring.installRun("run-2", "t2")
        assertEquals(PreReadMode.NOTIFY, PreReadWiring.modeNow())
        val hint = PreReadWiring.startHint()
        assertNotNull("态②开工要提示一句", hint)
        assertTrue(hint!!.contains("有必读清单"))
        assertNull("态②仍可写", PreReadWiring.beforeToolCall("write_file", isWrite = true))
        assertNull("提示只念一次", PreReadWiring.startHint())
    }

    @Test
    fun `态2_收工时对照读了没_落一行`() {
        assertEquals(emptyList<Any>(), PreReadWiring.attachOrder(order("t2b")))
        DeviationWiring.installRun("run-2b", "t2b")
        // 只读了一句别的，清单那条没读到 ⇒ 收工那行要写"未读到 1 条"
        PreReadWiring.submitReceipt(receipt("t2b", reads = listOf("别的文件.kt"), files = listOf("别的文件.kt")))
        val diff = PreReadWiring.closeout(listOf("别的文件.kt"))
        assertNotNull(diff)
        val line = rows(PreReadEventKind.CLOSEOUT).last()
        assertTrue("收工行要带「未读到」：${line.detail}", line.detail.any { it.contains("未读到") })
    }

    // ── 态③：显式要求 ───────────────────────────────────────────────────────

    @Test
    fun `态3_显式要求回执_未交_写工具报错拦住`() {
        assertEquals(emptyList<Any>(), PreReadWiring.attachOrder(order("t3", require = true)))
        DeviationWiring.installRun("run-3", "t3")
        assertEquals(PreReadMode.ENFORCED, PreReadWiring.modeNow())
        val blocked = PreReadWiring.beforeToolCall("write_file", isWrite = true)
        assertNotNull("判据①：未交回执调写工具必须拦住", blocked)
        // 拦住那句必须是可操作的原话（裁定逐字）——不许只回一句"被闸拦了"
        assertTrue("提示语缺「读了哪些 / 我的理解 / 打算改什么」：$blocked", blocked!!.contains(BLOCK_HEAD))
        assertTrue(blocked.contains("write_file"))
        val block = rows(PreReadEventKind.BLOCK).last()
        assertTrue("拦住必须落账", block.text.contains(BLOCK_HEAD))
    }

    @Test
    fun `态3_交了回执_放行`() {
        assertEquals(emptyList<Any>(), PreReadWiring.attachOrder(order("t4", require = true)))
        DeviationWiring.installRun("run-4", "t4")
        assertNotNull(PreReadWiring.beforeToolCall("write_file", isWrite = true))
        assertEquals(emptyList<Any>(), PreReadWiring.submitReceipt(receipt("t4")))
        assertNull("判据②：交了 ⇒ 放行", PreReadWiring.beforeToolCall("write_file", isWrite = true))
        assertEquals("放行落一行（首笔），之后每笔不再刷", 1, rows(PreReadEventKind.PASS).size)
    }

    @Test
    fun `态3_续跑不重交_换任务要重交`() {
        // 裁定原文：回执一旦提交，本任务内有效（续跑不用重交，换任务要重交）。
        // 键是 taskKey 而不是 runId ⇒ 换 runId（续跑）态还在，换 taskKey 就没了。
        assertEquals(emptyList<Any>(), PreReadWiring.attachOrder(order("t5", require = true)))
        DeviationWiring.installRun("run-5-first", "t5")
        assertEquals(emptyList<Any>(), PreReadWiring.submitReceipt(receipt("t5")))
        DeviationWiring.installRun("run-5-resume", "t5") // 续跑：换了轮，没换任务
        assertNull("续跑不该重交回执", PreReadWiring.beforeToolCall("write_file", isWrite = true))
        DeviationWiring.installRun("run-6", "t6") // 换任务
        assertEquals(emptyList<Any>(), PreReadWiring.attachOrder(order("t6", require = true)))
        assertNotNull("换任务必须重交", PreReadWiring.beforeToolCall("write_file", isWrite = true))
    }

    // ── 态③的第二个来源：自动 ───────────────────────────────────────────────

    @Test
    fun `自动来源_长程五步起算_短任务不算`() {
        // 与 274 的军令状同一判定点（≥5 步／跨会话）：4 步不自动要求，5 步要。
        assertEquals(emptyList<Any>(), PreReadWiring.attachOrder(order("t7", items = listOf(item("a/Kt.kt:1"), item("b/Kt.kt:2")))))
        DeviationWiring.installRun("run-7", "t7")
        assertFalse("4 步不算长程", PreReadWiring.armByLongTask(4, crossSession = false))
        assertEquals(PreReadMode.NOTIFY, PreReadWiring.modeNow())
        assertTrue("5 步算长程", PreReadWiring.armByLongTask(5, crossSession = false))
        assertEquals(PreReadMode.ENFORCED, PreReadWiring.modeNow())
        assertNotNull(PreReadWiring.beforeToolCall("write_file", isWrite = true))
    }

    @Test
    fun `自动来源_跨会话一步也算`() {
        assertEquals(emptyList<Any>(), PreReadWiring.attachOrder(order("t8")))
        DeviationWiring.installRun("run-8", "t8")
        assertTrue(PreReadWiring.armByLongTask(1, crossSession = true))
        assertEquals(PreReadMode.ENFORCED, PreReadWiring.modeNow())
        val arm = rows(PreReadEventKind.ARM).last()
        assertTrue("账上要看得出要求来自自动：${arm.text}", arm.text.contains("自动要求回执"))
    }

    @Test
    fun `显式勾了_短任务照样启用`() {
        // 两条来源是"或"：派单人明示要回执，不因步数少而失效。
        assertEquals(emptyList<Any>(), PreReadWiring.attachOrder(order("t9", require = true)))
        DeviationWiring.installRun("run-9", "t9")
        assertFalse(PreReadWiring.armByLongTask(1, crossSession = false))
        assertEquals(PreReadMode.ENFORCED, PreReadWiring.modeNow())
    }

    // ── 只读工具永不拦（判据⑤）─────────────────────────────────────────────

    @Test
    fun `态3_未交回执_只读工具照走`() {
        assertEquals(emptyList<Any>(), PreReadWiring.attachOrder(order("t10", require = true)))
        DeviationWiring.installRun("run-10", "t10")
        assertNotNull("写工具此刻是拦的", PreReadWiring.beforeToolCall("write_file", isWrite = true))
        assertNull("判据⑤：只读工具永不拦", PreReadWiring.beforeToolCall("read_file", isWrite = false))
        assertNull(PreReadWiring.beforeToolCall("list_dir", isWrite = false))
        // 只读一笔都不该落账（否则拦一条要刷十行读日志）
        assertTrue(rows(PreReadEventKind.BLOCK).all { it.detail.none { d -> d.contains("read_file") } })
    }

    // ── 三行缺一行＝没交（判据②）───────────────────────────────────────────

    @Test
    fun `回执三行缺一行_视为未交`() {
        assertEquals(emptyList<Any>(), PreReadWiring.attachOrder(order("t11", require = true)))
        DeviationWiring.installRun("run-11", "t11")
        assertEquals(listOf(ReceiptGap.BLANK_READS), PreReadWiring.submitReceipt(receipt("t11", reads = emptyList())))
        assertEquals(listOf(ReceiptGap.BLANK_UNDERSTANDING), PreReadWiring.submitReceipt(receipt("t11", why = "")))
        assertEquals(listOf(ReceiptGap.BLANK_FILES), PreReadWiring.submitReceipt(receipt("t11", files = emptyList())))
        assertNotNull("缺行的回执不许解锁", PreReadWiring.beforeToolCall("write_file", isWrite = true))
        assertEquals("没收下的回执不落账", 0, rows(PreReadEventKind.RECEIPT).size)
        assertEquals(emptyList<Any>(), PreReadWiring.submitReceipt(receipt("t11")))
        assertNull(PreReadWiring.beforeToolCall("write_file", isWrite = true))
    }

    @Test
    fun `回执超上限_视为不合规`() {
        val many = (1..13).map { "f$it.kt" }
        assertEquals(listOf(ReceiptGap.TOO_MANY_READS), PreReadWiring.submitReceipt(receipt("t12", reads = many)))
    }

    @Test
    fun `任务单不合规_不收下并说明`() {
        val dup = order("t13", items = listOf(item("a.kt"), item("a.kt")))
        assertEquals(listOf(OrderIssue.DUPLICATE_TARGET), PreReadWiring.attachOrder(dup))
        assertEquals(emptyList<Any>(), PreReadWiring.attachOrder(order("t14", items = emptyList())))
        assertEquals("空清单＝没附单子，态①", PreReadMode.OFF, run { DeviationWiring.installRun("r", "t14"); PreReadWiring.modeNow() })
    }

    // ── 第二个出口：声明不要求回执 ──────────────────────────────────────────

    @Test
    fun `声明不要求回执_退回提示不拦_单子照留`() {
        assertEquals(emptyList<Any>(), PreReadWiring.attachOrder(order("t15", require = true)))
        DeviationWiring.installRun("run-15", "t15")
        assertNotNull(PreReadWiring.beforeToolCall("write_file", isWrite = true))
        assertTrue(PreReadWiring.declareNotRequired("这活只碰一个临时文件"))
        assertEquals(PreReadMode.NOTIFY, PreReadWiring.modeNow())
        assertNull(PreReadWiring.beforeToolCall("write_file", isWrite = true))
        // 声明不是销毁：回看时两张纸都还在
        assertNotNull(PreReadBoard.orderFor("t15"))
        val declared = rows(PreReadEventKind.DECLARED).last()
        assertTrue(declared.text.contains("声明不要求回执"))
        assertTrue(declared.text.contains("临时文件"))
    }

    // ── 收工对照（判据③：记账不阻断）──────────────────────────────────────

    @Test
    fun `改了清单外_落账并提出_不阻断`() {
        assertEquals(emptyList<Any>(), PreReadWiring.attachOrder(order("t16", require = true)))
        DeviationWiring.installRun("run-16", "t16")
        assertEquals(emptyList<Any>(), PreReadWiring.submitReceipt(receipt("t16", files = listOf("a/Kt.kt"))))
        val diff = PreReadWiring.closeout(listOf("a/Kt.kt", "偷偷改的.kt"))
        assertNotNull(diff)
        assertEquals("越界只提出一条", listOf("偷偷改的.kt"), diff!!.outOfScope)
        assertEquals(1, diff.matched.size)
        val line = rows(PreReadEventKind.CLOSEOUT).last()
        assertTrue("越界必须写进账：${line.detail}", line.detail.any { it.contains("清单外改动") && it.contains("偷偷改的") })
        // 不阻断的实证：对照之后照样能写
        assertNull(PreReadWiring.beforeToolCall("write_file", isWrite = true))
    }

    @Test
    fun `说改未改_也落一行`() {
        assertEquals(emptyList<Any>(), PreReadWiring.attachOrder(order("t17", require = true)))
        DeviationWiring.installRun("run-17", "t17")
        assertEquals(emptyList<Any>(), PreReadWiring.submitReceipt(receipt("t17", files = listOf("a/Kt.kt", "没动的.kt"))))
        val diff = PreReadWiring.closeout(listOf("a/Kt.kt"))
        assertEquals(listOf("没动的.kt"), diff!!.notTouched)
        assertTrue(rows(PreReadEventKind.CLOSEOUT).last().detail.any { it.contains("说改未改") })
    }

    @Test
    fun `回执写相对路径_实际写绝对路径_仍算对上`() {
        // 287 起全盘可写：管线把 path 改写成绝对路径，回执里人写的是相对的。
        // 严格全等会判一片假越界 ⇒ 尾对齐（比对口径见 PreReadRules.samePath）。
        assertEquals(emptyList<Any>(), PreReadWiring.attachOrder(order("t18", require = true)))
        DeviationWiring.installRun("run-18", "t18")
        assertEquals(emptyList<Any>(), PreReadWiring.submitReceipt(receipt("t18", files = listOf("app/src/main/java/a/Kt.kt"))))
        val diff = PreReadWiring.closeout(listOf("C:/ws/wt/app/src/main/java/a/Kt.kt"))
        assertTrue("不该判越界：${diff!!.outOfScope}", diff.outOfScope.isEmpty())
        assertEquals(1, diff.matched.size)
    }

    // ── 回看（判据④）────────────────────────────────────────────────────────

    @Test
    fun `回看一张屏_任务单与回执逐条对得上`() {
        val items = listOf(item("GuardPipeline.kt:441"), ReadFirstItem("判据①", ReadFirstKind.CRITERION), ReadFirstItem("E-173", ReadFirstKind.PITFALL))
        assertEquals(emptyList<Any>(), PreReadWiring.attachOrder(order("t19", require = true, items = items)))
        DeviationWiring.installRun("run-19", "t19")
        assertEquals(
            emptyList<Any>(),
            PreReadWiring.submitReceipt(
                PreReadReceipt(
                    taskKey = "t19",
                    readLines = listOf("GuardPipeline.kt:441", "判据①", "E-173"),
                    understanding = "闸要落在②之后③之前，被拦的不进判定",
                    plannedFiles = listOf("GuardPipeline.kt"),
                ),
            ),
        )
        val screen = PreReadWiring.review(listOf("GuardPipeline.kt"))
        // 每条清单都要在回执里找得到对应（判据④"逐条对单"的机器形态）
        for (i in items) assertTrue("回看缺条目：${i.target}\n$screen", screen.any { it.contains(i.target) })
        assertTrue(screen.any { it.contains("闸要落在②之后③之前") })
        val cover = PreReadRules.readCoverage(order("t19", items = items), PreReadBoard.receiptFor("t19"))
        assertTrue("三条都该对到：${cover.uncovered}", cover.uncovered.isEmpty())
        assertEquals(3, cover.covered.size)
    }

    // ── 账的形状（追加、坏行跳过、可回放）──────────────────────────────────

    @Test
    fun `账行带任务键与时刻_可按任务回放`() {
        assertEquals(emptyList<Any>(), PreReadWiring.attachOrder(order("t20", require = true)))
        DeviationWiring.installRun("run-20", "t20")
        PreReadWiring.beforeToolCall("write_file", isWrite = true)
        val forTask = PreReadLedger.rowsFor("t20")
        assertEquals("任务单 + 拦住 = 两行：$forTask", 2, forTask.size)
        assertEquals(listOf(PreReadEventKind.ORDER, PreReadEventKind.BLOCK), forTask.map { it.kind })
        assertTrue(forTask.all { it.taskKey == "t20" && it.atMs > 0L && it.id.isNotBlank() })
    }

    @Test
    fun `坏行不落账`() {
        PreReadLedger.append(PreReadRow(id = "x", atMs = 0L, taskKey = "t", kind = PreReadEventKind.ORDER, text = "时刻缺"))
        PreReadLedger.append(PreReadRow(id = "x", atMs = 1L, taskKey = "", kind = PreReadEventKind.ORDER, text = "任务缺"))
        PreReadLedger.append(PreReadRow(id = "x", atMs = 1L, taskKey = "t", kind = PreReadEventKind.ORDER, text = ""))
        assertTrue("三件不齐的行一条都不许留", PreReadLedger.readAll().isEmpty())
    }
}
