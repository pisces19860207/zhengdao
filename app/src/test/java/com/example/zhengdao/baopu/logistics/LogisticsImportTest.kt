// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百九十五批 · 导入链测试（parse → 落库 → 一句结果）。
//
// 钉的四条：
//   负例③「重复导入产生重复行」——同一份文件导两次，库里行数与 writeCount 都不许动；
//   负例①「缺列静默补 0」——缺的件数在库里是 NULL，注记与降级句都要点名；
//   负例⑤「不带数据时间」——时间三档（文件名 → 表里标注 → 文件时间）取不到就整份拒读，
//     绝不用墙上时钟顶（用墙上时钟 = 把昨天的表当成今天的）；
//   「库里没写成」——落库失败必须进 [ImportTally.failed] 且那句结果不许出现「成功」。
package com.example.zhengdao.baopu.logistics

import java.io.File
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LogisticsImportTest {

    private fun plan(text: String, fileName: String = "配货单$TODAY_KEY.csv", mtime: Long = NOW): ImportPlan {
        val outcome = LogisticsImport.parse(fileName, csvBytes(text), mtime, NOW, TZ)
        return when (outcome) {
            is PlanOutcome.Ready -> outcome.plan
            is PlanOutcome.Refused -> error("这份表本该读通：${outcome.reason}")
        }
    }

    private fun refused(bytes: ByteArray, fileName: String, mtime: Long = NOW): String =
        when (val o = LogisticsImport.parse(fileName, bytes, mtime, NOW, TZ)) {
            is PlanOutcome.Refused -> o.reason
            is PlanOutcome.Ready -> error("本该拒读却读通了：${o.plan.rows.size} 行")
        }

    // ── 基本形状 ─────────────────────────────────────────────────────────────

    @Test
    fun `十二列齐全的导出读成三单并归到当天`() {
        val p = plan(sampleCsv())
        assertEquals(TODAY_KEY, p.day)
        assertEquals(3, p.rows.size)
        assertEquals(listOf("10231", "10232", "10233"), p.rows.map { it.waybill })
        assertEquals("配货单$TODAY_KEY.csv", p.fileLabel)
        // §1.8 新三列各归各的字段：配货单号是**装车身份**，绝不能落到 waybill 那一格里
        assertEquals(listOf("PH101", "PH101", "PH102"), p.rows.map { it.orderNo })
        assertEquals(listOf("4.2", "1.5", ""), p.rows.map { it.volume })
        assertEquals(listOf("13800000001", "13800000001", ""), p.rows.map { it.phone })
        // 每行都带着同一份表的数据时间与来源。
        // 文件名 `配货单20261025` 只有**日期**没有钟点 ⇒ 存的就是那天 00:00，且 exportedClock=false；
        // 界面那句走 hasClock 分支写「10-25 导出的」，不许写成「00:00 导出的」（负例⑤的另一半）。
        p.rows.forEach { r ->
            assertEquals(millisOf(25, 0, 0), r.exportedAt)
            assertFalse("只有日期时不许冒充有钟点", r.exportedClock)
            assertEquals(ExportedAtSource.FILENAME.name, r.exportedSource)
            assertEquals("配货单$TODAY_KEY.csv", r.srcFile)
        }
        assertEquals(listOf(2, 3, 4), p.rows.map { it.lineNo })
    }

    @Test
    fun `同一张配货单下的两单是两行，不被压成一行`() {
        // §1.8.1 的直接后果：真实表 40 行运单挂 9 张配货单。
        // 把配货单号当主键（或当单号的别名）就是 31 行**静默消失**，这里用 2→1 复现同一形状。
        val store = FakeLogisticsStore()
        importCsvText(store, sampleCsv())
        val rows = store.rowsFor(TODAY_KEY)
        assertEquals("库里行数按运单算，不按配货单算", 3, rows.size)
        assertEquals("PH101 名下两单都在", listOf("10231", "10232"), rows.filter { it.orderNo == "PH101" }.map { it.waybill })
        // 而「车」这一维按配货单数：三单两张配货单 ⇒ 2 车
        assertEquals(3, LogisticsAggregate.aggregate(rows, LogisticsBasis.ORDER).orders)
        assertEquals(2, LogisticsAggregate.aggregate(rows, LogisticsBasis.ORDER).vehicles)
    }

    @Test
    fun `缺件数那一维在库里是空而不是零`() {
        // 负例①的靶心
        val text = """
            单号,方向,件数
            10231,北京朝阳,
            10232,北京朝阳,8
        """.trimIndent() + "\n"
        val p = plan(text)
        assertNull("空格子必须读成 null，读成 0 就是把「没填」当成「零件」", p.rows[0].pieces)
        assertEquals(8, p.rows[1].pieces)
        // 「这格没填」与「整列没有」是两件事，注记不许混：件数列在，就不进缺列名单
        val absentNote = p.notes.firstOrNull { it.contains("这份表没有") }
        assertNotNull("缺的那几维要明说：${p.notes}", absentNote)
        assertFalse("件数列是在的（只是这格没填），不许混进缺列名单：$absentNote", absentNote!!.contains("件数"))
        assertTrue("缺列名单要点名车牌这一维：$absentNote", absentNote.contains("车牌"))
        val store = FakeLogisticsStore()
        LogisticsImport.runInto(store, p)
        val stored = store.rowsFor(TODAY_KEY)
        assertNull(stored[0].pieces)
        // 件数合计只加有数的：8 而不是 0+8=8 也成立，但 2 行时按 0 补会得到同样的数——
        // 所以钉「库里是 NULL」这一条，而不是钉合计那一条
        assertEquals(8, LogisticsAggregate.piecesTotal(stored))
    }

    @Test
    fun `缺可降级列照读并登记缺哪几列`() {
        val p = plan("单号,方向,状态\n10231,北京朝阳,已装\n")
        // §1.8 把 配货单号/手机号/体积 也纳入可降级档：缺了不拒读，但那一维不显示、不计算、不告警。
        // 配货单号缺得尤其难看——车数那一维只能降级去按车牌数，这条在 AnswerTest 里单独钉。
        assertEquals(
            setOf(
                LogisticsField.ORDER_NO, LogisticsField.GOODS, LogisticsField.PIECES, LogisticsField.WEIGHT,
                LogisticsField.VOLUME, LogisticsField.DRIVER, LogisticsField.PHONE, LogisticsField.PLATE,
            ),
            p.absent,
        )
        assertTrue("每行都带上缺列名单（库里 missing 列）：${p.rows[0].missing}", p.rows[0].missing.contains("车牌"))
        val note = p.notes.firstOrNull { it.contains("这份表没有") }
        assertNotNull("降级必须明说：${p.notes}", note)
        assertTrue(note!!.contains("不参与任何合计"))
    }

    // ── 负例③：重复导入 ───────────────────────────────────────────────────────

    @Test
    fun `同一份文件导两次一行都不多`() {
        val store = FakeLogisticsStore()
        val first = importCsvText(store, sampleCsv())
        val second = importCsvText(store, sampleCsv())
        val t1 = (first as ImportReport.Done).tally
        val t2 = (second as ImportReport.Done).tally
        assertEquals(3, t1.inserted)
        assertEquals(0, t2.inserted)
        assertEquals("第二次整份都该判成没变", 3, t2.unchanged)
        assertEquals(3, store.rowsFor(TODAY_KEY).size)
        assertEquals("第二次不该动过任何一行", 3, store.writeCount)
    }

    @Test
    fun `同一份表换个文件名再导仍不重复`() {
        // 去重的键是（天, 单号），文件哈希只是证据：换名重导是「同一天同一批单子」的常见情形
        val store = FakeLogisticsStore()
        importCsvText(store, sampleCsv(), fileName = "配货单$TODAY_KEY.csv")
        val again = importCsvText(store, sampleCsv(), fileName = "配货单-$TODAY_KEY-早上.csv")
        assertEquals(0, (again as ImportReport.Done).tally.inserted)
        assertEquals(3, store.rowsFor(TODAY_KEY).size)
    }

    @Test
    fun `内容没变但换了文件也只报没变`() {
        val store = FakeLogisticsStore()
        importCsvText(store, sampleCsv(), fileName = "a配货单$TODAY_KEY.csv")
        val r = importCsvText(store, sampleCsv(), fileName = "b配货单$TODAY_KEY.csv")
        val t = (r as ImportReport.Done).tally
        assertEquals(3, t.unchanged)
        assertEquals(0, t.overwritten + t.inserted)
    }

    @Test
    fun `改了一单再导只动那一单`() {
        val store = FakeLogisticsStore()
        importCsvText(store, sampleCsv())
        val changed = sampleCsv().replace("10232,PH101,北京朝阳,四惠,家具,8,,1.5,王师傅,13800000001,冀A12345,待发", "10232,PH101,北京朝阳,四惠,家具,9,,1.5,王师傅,13800000001,冀A12345,已装")
        val r = importCsvText(store, changed)
        val t = (r as ImportReport.Done).tally
        assertEquals(0, t.inserted)
        assertEquals(2, t.unchanged)
        // 同一份文件名与时间 ⇒ 时间相等而值不同，走 tied（按这次导入的记，并单独报出去）
        assertEquals(1, t.tied)
        assertEquals(9, store.rowsFor(TODAY_KEY).first { it.waybill == "10232" }.pieces)
        assertEquals("已装", store.rowsFor(TODAY_KEY).first { it.waybill == "10232" }.status.let { LogisticsStatus.valueOf(it).label })
    }

    @Test
    fun `用户勾过的状态不被新表覆盖`() {
        val store = FakeLogisticsStore()
        importCsvText(store, sampleCsv())
        store.setStatusUser(TODAY_KEY, "10231", LogisticsStatus.PENDING)
        val changed = sampleCsv().replace("10231,PH101,北京朝阳,三间房,钢材,12", "10231,PH101,北京朝阳,三间房,钢材,13")
        importCsvText(store, changed, fileName = "配货单2026-10-25 12:00.csv")
        val row = store.rowsFor(TODAY_KEY).first { it.waybill == "10231" }
        assertEquals("人是最新事实的来源：勾过的待发不能被表里的已装盖掉", LogisticsStatus.PENDING, row.effectiveStatus())
        assertEquals(13, row.pieces)
    }

    @Test
    fun `更旧的表不许覆盖库里更新的那份`() {
        val store = FakeLogisticsStore()
        importCsvText(store, sampleCsv(), fileName = "配货单2026-10-25 18:00.csv")
        val older = importCsvText(
            store,
            sampleCsv().replace("10232,PH101,北京朝阳,四惠,家具,8", "10232,PH101,北京朝阳,四惠,家具,5"),
            fileName = "配货单2026-10-25 06:00.csv",
        )
        val t = (older as ImportReport.Done).tally
        assertEquals(1, t.kept)
        assertEquals("保住库里那份", 8, store.rowsFor(TODAY_KEY).first { it.waybill == "10232" }.pieces)
        val summary = LogisticsImport.summaryOf(older, TZ, NOW)
        assertTrue("没被覆盖必须点名，静默不覆盖等于让人以为重导过了：$summary", summary.contains("库里那份更新"))
    }

    @Test
    fun `库里没写成时不许报成功`() {
        val store = FakeLogisticsStore(failWrites = true)
        val r = importCsvText(store, sampleCsv())
        val t = (r as ImportReport.Done).tally
        assertEquals(3, t.failed)
        val summary = LogisticsImport.summaryOf(r, TZ, NOW)
        assertTrue("要写「没写进库」：$summary", summary.contains("没写进库"))
        assertFalse("一句「成功」都不许出现：$summary", summary.contains("成功"))
    }

    // ── 负例⑤：数据时间 ───────────────────────────────────────────────────────

    @Test
    fun `时间三档的次序是文件名然后表里然后文件时间`() {
        val byName = LogisticsImport.parse("配货单$TODAY_KEY.csv", csvBytes(sampleCsv()), NOW - 86400000, NOW, TZ) as PlanOutcome.Ready
        assertEquals(ExportedAtSource.FILENAME, byName.plan.exportedAt.source)
        assertEquals(TODAY_KEY, byName.plan.day)

        val text = sampleCsv() + "导出时间 2026-10-25 07:40\r\n"
        val byContent = LogisticsImport.parse("无日期的表.csv", csvBytes(text), NOW - 86400000, NOW, TZ) as PlanOutcome.Ready
        assertEquals(ExportedAtSource.CONTENT, byContent.plan.exportedAt.source)
        assertEquals(TODAY_KEY, byContent.plan.day)

        val byMtime = LogisticsImport.parse("无日期的表.csv", csvBytes(sampleCsv()), millisOf(24, 20), NOW, TZ) as PlanOutcome.Ready
        assertEquals(ExportedAtSource.MTIME, byMtime.plan.exportedAt.source)
        assertEquals("20261024", byMtime.plan.day)
    }

    @Test
    fun `三条路都取不到时间时整份拒读而不是按今天记`() {
        // 负例⑤的另一半：这时候按墙上时钟记 = 把不知道哪天的表当成今天的
        val reason = refused(csvBytes(sampleCsv()), "无日期的表.csv", mtime = millisOf(25) - 400L * 86400000L)
        assertTrue(reason, reason.contains("取不到可用时间"))
        assertTrue(reason, reason.contains("一行都没记"))
    }

    @Test
    fun `文件名里没有钟点时不许写出零点几分的假精确`() {
        val p = plan(sampleCsv())
        assertFalse("20261025 只有日期：hasClock 必须是 false", p.exportedAt.hasClock)
        assertFalse("只有日期时库里也不许存成「有钟点」", p.rows[0].exportedClock)
        val line = LogisticsExportedAt.lineOf(p.exportedAt, p.fileLabel, TZ, NOW)
        assertFalse("不许把「只知道哪天」写成 00:00：$line", line.contains("00:00"))
        assertTrue("日期仍要报出来（负例⑤要求带数据时间）：$line", line.contains("10-25"))
        val note = LogisticsImport.summaryOf(ImportReport.Done(p, ImportTally(inserted = 3)), TZ, NOW)
        assertTrue("结果句要带数据时间那一句：$note", note.contains("数据："))
    }

    @Test
    fun `只有日期的表跨天时明说不是今天`() {
        val yesterday = plan(sampleCsv(), fileName = "配货单20261024.csv")
        val line = LogisticsExportedAt.lineOf(yesterday.exportedAt, yesterday.fileLabel, TZ, NOW)
        assertTrue("昨天的表当今天的数用是最坏的一种错：$line", line.contains("不是今天"))
    }

    // ── 拒读的形状 ────────────────────────────────────────────────────────────

    @Test
    fun `空文件与不是表的后缀都在读之前拒掉`() {
        assertTrue(refused(ByteArray(0), "配货单$TODAY_KEY.csv").contains("0 字节"))
        assertTrue(refused(csvBytes(sampleCsv()), "配货单$TODAY_KEY.xls").contains("只认 .csv 与 .xlsx"))
        // 一坨不是 zip 的字节冒充 xlsx：要在读之前按后缀拦下，而不是解压到一半抛出来
        assertTrue(refused(ByteArray(1024 * 1024), "配货单$TODAY_KEY.xlsx").isNotEmpty())
    }

    @Test
    fun `表头变了时报清缺哪列而且一行都不记`() {
        val reason = refused(csvBytes("流水号,到港,箱子\r\n1,A,B\r\n"), "配货单$TODAY_KEY.csv")
        assertTrue(reason, reason.contains("单号"))
        assertTrue(reason, reason.contains("一行都没记"))
    }

    @Test
    fun `真文件读不到时报一句人话而不是抛`() {
        val missing = File(System.getProperty("java.io.tmpdir"), "根本没有这个文件$TODAY_KEY.csv")
        val r = LogisticsImport.importFile(FakeLogisticsStore(), missing, NOW, TZ)
        assertTrue("$r", r is ImportReport.Refused)
        assertTrue((r as ImportReport.Refused).reason.contains("打不开"))
    }

    @Test
    fun `读 inbox 把每一份都记进台账并逐份给一句`() {
        val workspace = File(System.getProperty("java.io.tmpdir"), "zd195-inbox-${System.nanoTime()}")
        val inbox = LogisticsPaths.inboxDirUnder(workspace)
        assertTrue(inbox.mkdirs())
        try {
            File(inbox, "配货单$TODAY_KEY.csv").writeText(sampleCsv(), Charsets.UTF_8)
            File(inbox, "配货单$TODAY_KEY.xlsx").writeBytes(sampleXlsxBytes())
            File(inbox, "说明.txt").writeText("不是表", Charsets.UTF_8)
            val store = FakeLogisticsStore()
            val msg = LogisticsImport.importInbox(store, workspace, NOW, TZ)
            // 3 单来自 CSV，3 单来自 XLSX，同一批单号 ⇒ 幂等，库里仍是 3 行
            assertEquals(3, store.rowsFor(TODAY_KEY).size)
            assertTrue("要逐份给一句：$msg", msg.lines().size >= 3)
            assertTrue("要落台账：$msg", msg.contains("台账已记"))
            val ledger = LogisticsPaths.ledgerFileUnder(workspace, TODAY_KEY)
            assertTrue("台账文件要真在 产物/物流/ 下：${ledger.absolutePath}", ledger.isFile)
        } finally {
            workspace.walkBottomUp().forEach { it.delete() }
        }
    }

    @Test
    fun `inbox 是空的就直说没有可读的表`() {
        val workspace = File(System.getProperty("java.io.tmpdir"), "zd195-empty-${System.nanoTime()}")
        try {
            val msg = LogisticsImport.importInbox(FakeLogisticsStore(), workspace, NOW, TZ)
            assertTrue(msg, msg.contains("没有可读的表"))
        } finally {
            workspace.deleteRecursively()
        }
    }

    // ── 隐私（派工单 §7）──────────────────────────────────────────────────────

    @Test
    fun `库里没有任何一格存用户问过的那句话`() {
        // 查询词不落库：表里就不该有能装它的列
        listOf("query", "text", "ask", "question", "input").forEach { word ->
            assertTrue(
                "列名里出现了「$word」，查询词就落库了：${LogisticsSql.columnNames}",
                LogisticsSql.columnNames.none { it.lowercase().contains(word) },
            )
        }
    }

    @Test
    fun `导入结果那句话里不出现司机姓名与手机号`() {
        // §1.8（2026-10-25 冻结）：库里存、面板与台账原样显示，但**日志与给用户过目的句子**一个都不带。
        // 导入这句会进面板、进台账、也可能被截图转发 ⇒ 只报条数。
        val store = FakeLogisticsStore()
        val summary = LogisticsImport.summaryOf(importCsvText(store, sampleCsv()), TZ, NOW)
        listOf("王师傅", "张师傅", "13800000001", "13800000002").forEach {
            assertFalse("导入结果里不许出现「$it」：$summary", summary.contains(it))
        }
        // 库里照实存着（不打码），这两件事必须同时成立
        val row = store.rowsFor(TODAY_KEY).first { it.waybill == "10231" }
        assertEquals("王师傅", row.driver)
        assertEquals("13800000001", row.phone)
    }

    @Test
    fun `导入结果的句子按分支各成一句`() {
        val store = FakeLogisticsStore()
        val done = importCsvText(store, sampleCsv())
        val summary = LogisticsImport.summaryOf(done, TZ, NOW)
        assertTrue(summary, summary.contains("3 单进了"))
        assertTrue(summary, summary.contains("新增 3"))
        val refusedLine = LogisticsImport.summaryOf(ImportReport.Refused("配货单.csv", "表头变了"), TZ, NOW)
        assertTrue(refusedLine, refusedLine.startsWith("没读入：表头变了"))
        // 三档尾注各只在自己的那一格非 0 时出现（混着报会让人以为数字是同一件事）
        val keptLine = ImportTally(overwritten = 1, kept = 2)
        val tiedLine = ImportTally(overwritten = 1, tied = 3)
        val doneKept = ImportReport.Done(plan(sampleCsv()), keptLine)
        assertTrue(LogisticsImport.summaryOf(doneKept, TZ, NOW).contains("没被这份旧表覆盖"))
        assertTrue(LogisticsImport.summaryOf(ImportReport.Done(plan(sampleCsv()), tiedLine), TZ, NOW).contains("按这次导入的记"))
    }

    @Test
    fun `时区由调用方给而不是各读各的`() {
        // 同一个毫秒在 UTC+8 是 25 日、在 UTC-5 是 24 日：日归属必须由一把区时定
        val utc = TimeZone.getTimeZone("UTC")
        val millis = millisOf(25, 0, 30)
        assertEquals(TODAY_KEY, LogisticsAsk.dayKeyOf(millis, TZ))
        assertEquals("20261024", LogisticsAsk.dayKeyOf(millis, utc))
    }
}
