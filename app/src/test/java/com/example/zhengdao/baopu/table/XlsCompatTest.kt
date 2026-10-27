// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百五十二批 · 旧版 Excel（`.xls`）兼容的判据。
//
// 六条负例在这里各有一条钉子：
//   ① 当已支持直接读 ⇒ 乱码：`SheetReader` 认不到 `.xls`（`xls 不被当成已支持格式硬读`）；
//   ② 转换后列错位：`空列不被挤掉`；
//   ③ 日期仍是序列号：`日期格成 yyyy-MM-dd HH:mm 而不是 46319 点几`＋真表那两条；
//   ④ 失败静默：`截断的 xls 给的是能动手的一句话`／`超过 50MB 直接拒`；
//   ⑤ 改了既有通道：`xlsx csv tsv 三条路一个字没动`（登记与行列数照旧）；
//   ⑥ 大文件 OOM：`超过 20 万行先拒再读`（不读就不可能撑爆）。
//
// 真表夹具：`..\_zd-private\样本\f9\` 里那两份（物流.xls / 到货库存.xls）。
// 目录不在就 `Assume` 跳过 —— **宁可少跑，也不造假数据顶替**。
package com.example.zhengdao.baopu.table

import com.example.zhengdao.baopu.agent.SheetCsv
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile

class XlsCompatTest {

    private lateinit var ws: File

    @Before
    fun setUp() {
        ws = tempWorkspace("xls")
        XlsCompat.resetCountForTest()
    }

    @After
    fun tearDown() {
        wipe(ws)
        XlsCompat.resetCountForTest()
    }

    private fun registry() = TableRegistry(TableRegistry.homeUnder(ws), listOf(TableRoot(ws)))

    private fun cacheDir() = File(File(File(ws, "抱朴记忆"), "表格"), "缓存")

    // ── 认格式 ──────────────────────────────────────────────────────────────

    @Test
    fun `xls 不被当成已支持格式硬读`() {
        // `.xls` 只在 XlsCompat 里被认领；`SheetReader` 那条链子的分支里没有它
        assertTrue(XlsCompat.isXls("物流.XLS"))
        assertTrue(XlsCompat.isXls("a.xls"))
        assertFalse("`.xlsx` 是另一条通道，不许被当成旧版", XlsCompat.isXls("a.xlsx"))
        assertFalse(XlsCompat.isXls("a.csv"))
        // 但扫描要认它（不认就等于 F9 导出的表在抱朴里根本不存在）
        assertTrue(TableRegistry.isTableFile("物流.xls"))
        assertTrue(TableRegistry.isTableFile("物流.xlsx"))
        assertFalse(TableRegistry.isTableFile("物流.pdf"))
    }

    // ── 转换的形状 ──────────────────────────────────────────────────────────

    @Test
    fun `转出来的 CSV 行列数与源表一致`() {
        val src = writeXls(
            ws,
            "物流.xls",
            xlsSheet("sheet").apply {
                text(0, 0, "单号"); text(1, 0, "状态"); text(2, 0, "件数")
                text(0, 1, "1073145"); text(1, 1, "已装车"); number(2, 1, 17.0)
                text(0, 2, "1073146"); text(1, 2, "已装车"); number(2, 2, 1067.0)
            },
        )
        val done = XlsCompat.convert(src, cacheDir()).getOrNull()
        assertNotNull("转换失败：${XlsCompat.convert(src, cacheDir()).exceptionOrNull()?.message}", done)
        done!!
        assertEquals(3, done.rows)
        assertEquals(3, done.columns)
        assertEquals(1, XlsCompat.convertCount)
        val lines = done.csv.readText(Charsets.UTF_8).split("\r\n").filter { it.isNotBlank() }
        assertEquals(3, lines.size)
        assertEquals("单号,状态,件数", lines[0])
        assertEquals("1073145,已装车,17", lines[1])
        assertTrue(
            "缓存必须落在 抱朴记忆/表格/缓存/ 下：${done.csv.absolutePath}",
            done.csv.absolutePath.startsWith(cacheDir().absolutePath),
        )
    }

    @Test
    fun `空列不被挤掉（负例：转换后列错位）`() {
        // 第 1 列整列为空：jxl 的 getRow() 只给到最后一个非空格，
        // 谁按行长度写谁就把中间空列挤没了 —— 这条钉的就是那个 bug。
        val src = writeXls(
            ws,
            "缺列.xls",
            xlsSheet("sheet").apply {
                text(0, 0, "A"); text(2, 0, "C")
                text(0, 1, "a1"); text(2, 1, "c1")
            },
        )
        val done = XlsCompat.convert(src, cacheDir()).getOrNull()!!
        assertEquals(3, done.columns)
        val lines = done.csv.readText(Charsets.UTF_8).split("\r\n").filter { it.isNotBlank() }
        assertEquals("A,,C", lines[0])
        assertEquals("a1,,c1", lines[1])
        // 回读也必须是 3 列（列错位在这里第二道关）
        val grid = SheetCsv.read(done.csv, range = null, hasHeader = false, maxRows = 100)
        assertEquals(3, grid.rows[0].size)
        assertEquals(3, grid.rows[1].size)
    }

    @Test
    fun `含逗号 引号 换行的单元格按 RFC4180 转义`() {
        val src = writeXls(
            ws,
            "闹腾.xls",
            xlsSheet("sheet").apply {
                text(0, 0, "备注")
                text(0, 1, "重庆, 成都")
                text(0, 2, "他说\"明天到\"")
                text(0, 3, "第一行\n第二行")
            },
        )
        val done = XlsCompat.convert(src, cacheDir()).getOrNull()!!
        val csv = done.csv.readText(Charsets.UTF_8)
        assertTrue(csv.contains("\"重庆, 成都\""))
        assertTrue(csv.contains("\"他说\"\"明天到\"\"\""))
        assertTrue(csv.contains("\"第一行\n第二行\""))
        // 回读：4 条记录（表头「备注」＋ 3 个闹腾的格），每条 1 列，内容原样
        val grid = SheetCsv.read(done.csv, range = null, hasHeader = false, maxRows = 100)
        assertEquals(4, grid.rows.size)
        assertEquals(1, grid.rows[0].size)
        assertEquals("备注", grid.rows[0][0])
        assertEquals("重庆, 成都", grid.rows[1][0])
        assertEquals("他说\"明天到\"", grid.rows[2][0])
        assertEquals("第一行\n第二行", grid.rows[3][0])
    }

    @Test
    fun `escape 只在该加引号时才加引号`() {
        assertEquals("abc", XlsCompat.escape("abc"))
        assertEquals("\"a,b\"", XlsCompat.escape("a,b"))
        assertEquals("\"a\"\"b\"", XlsCompat.escape("a\"b"))
        assertEquals("\"a\nb\"", XlsCompat.escape("a\nb"))
        assertEquals("\"a\rb\"", XlsCompat.escape("a\rb"))
    }

    // ── 日期 ────────────────────────────────────────────────────────────────

    @Test
    fun `日期格成 yyyy-MM-dd HH mm 而不是 46319 点几`() {
        val src = writeXls(
            ws,
            "带日期.xls",
            xlsSheet("sheet").apply {
                text(0, 0, "到货时间")
                date(0, 1, utcMoment("2026-10-24 09:36"))
                date(0, 2, utcMoment("2026-10-27 16:18"))
            },
        )
        val done = XlsCompat.convert(src, cacheDir()).getOrNull()!!
        val csv = done.csv.readText(Charsets.UTF_8)
        assertFalse("序列号漏出来了：$csv", csv.contains("46319"))
        assertFalse("序列号漏出来了：$csv", csv.contains("46319.4"))
        assertTrue(csv.contains("2026-10-24 09:36"))
        assertTrue(csv.contains("2026-10-27 16:18"))
    }

    @Test
    fun `整点的日期只写年月日`() {
        val src = writeXls(
            ws,
            "整点.xls",
            xlsSheet("sheet").apply {
                text(0, 0, "日期")
                date(0, 1, utcMoment("2026-10-25 00:00"))
            },
        )
        val csv = XlsCompat.convert(src, cacheDir()).getOrNull()!!.csv.readText(Charsets.UTF_8)
        assertTrue(csv.contains("2026-10-25"))
        assertFalse("整点还挂着 00:00：$csv", csv.contains("2026-10-25 00:00"))
    }

    // ── 多工作表 ────────────────────────────────────────────────────────────

    @Test
    fun `多工作表只取第一张 并报余下几张`() {
        val src = writeXls(
            ws,
            "多表.xls",
            xlsSheet("第一张").apply { text(0, 0, "A"); text(0, 1, "a") },
            xlsSheet("第二张").apply { text(0, 0, "B") },
            xlsSheet("第三张").apply { text(0, 0, "C") },
        )
        val done = XlsCompat.convert(src, cacheDir()).getOrNull()!!
        assertEquals("第一张", done.sheet)
        assertEquals(3, done.sheets.size)
        val notice = XlsNotice("多表.xls", done.rows, done.columns, (done.sheets.size - 1).coerceAtLeast(0))
        assertTrue(notice.sentence().contains("另有 2 个工作表未读"))
        // 只有第一张进了 CSV
        val csv = done.csv.readText(Charsets.UTF_8)
        assertTrue(csv.contains("A"))
        assertFalse(csv.contains("第二张"))
    }

    // ── 缓存 ────────────────────────────────────────────────────────────────

    @Test
    fun `mtime 加 size 没变命中缓存不重转 变了就重转`() {
        val src = writeXls(ws, "缓存.xls", xlsSheet("sheet").apply { text(0, 0, "A") })
        val first = XlsCompat.convert(src, cacheDir()).getOrNull()!!
        assertFalse(first.cached)
        assertEquals(1, XlsCompat.convertCount)

        val second = XlsCompat.convert(src, cacheDir()).getOrNull()!!
        assertTrue("源没动 ⇒ 该命中缓存", second.cached)
        assertEquals("源没动 ⇒ 一次都不该再转", 1, XlsCompat.convertCount)

        // 改内容（长度变了）⇒ 必须重转
        writeXls(ws, "缓存.xls", xlsSheet("sheet").apply { text(0, 0, "A"); text(0, 1, "B") })
        val third = XlsCompat.convert(src, cacheDir()).getOrNull()!!
        assertFalse("内容变了 ⇒ 必须重转", third.cached)
        assertEquals(2, XlsCompat.convertCount)
        assertEquals(2, third.rows)

        // 只改 mtime（内容一个字没动）⇒ 也要重转（派工单：mtime **与** size 变则重转）
        src.setLastModified(System.currentTimeMillis() + 10_000)
        val fourth = XlsCompat.convert(src, cacheDir()).getOrNull()!!
        assertFalse("mtime 变了 ⇒ 必须重转", fourth.cached)
        assertEquals(3, XlsCompat.convertCount)
    }

    // ── 失败：不许静默 ──────────────────────────────────────────────────────

    @Test
    fun `截断的 xls 给的是能动手的一句话`() {
        val good = writeXls(
            ws,
            "截断.xls",
            xlsSheet("sheet").apply {
                text(0, 0, "单号")
                repeat(40) { i -> text(0, i + 1, "NO-$i"); text(1, i + 1, "内容-$i") }
            },
        )
        val bytes = good.readBytes()
        val broken = File(ws, "截断.xls")
        broken.writeBytes(bytes.copyOf(bytes.size / 3)) // 拦腰截断：导出中断就是这个样子
        assertTrue(broken.length() < XlsCompat.MAX_FILE_BYTES)

        val out = XlsCompat.convert(broken, cacheDir())
        assertTrue("截断的 xls 必须失败", out.isFailure)
        val e = out.exceptionOrNull()!!
        assertTrue("必须是 XlsFailure（不许裸 Exception）：$e", e is XlsFailure)
        val action = (e as XlsFailure).action
        assertTrue("提示要有「请重新导出」这类能动手的话：$action", action.contains("请重新导出"))
    }

    @Test
    fun `超过 50MB 直接拒 不打开`() {
        val big = File(ws, "巨大.xls")
        RandomAccessFile(big, "rw").use { it.setLength(XlsCompat.MAX_FILE_BYTES + 1024) }
        val out = XlsCompat.convert(big, cacheDir())
        assertTrue(out.isFailure)
        val e = out.exceptionOrNull() as XlsFailure
        assertTrue("要说明超限与上限：${e.action}", e.action.contains("50MB"))
        assertTrue("要告诉用户下一步：${e.action}", e.action.contains("拆"))
        assertEquals("超限就不该真转", 0, XlsCompat.convertCount)
        assertTrue("超限也不该留下缓存", cacheDir().listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `行数超限先拒再读 不写半个文件`() {
        // ⚠️ 造不出 20 万行的 `.xls`：**BIFF 一张表最多 65536 行**（jxl 的
        // `RowsExceededException` 当场证明），所以 `.xls` 这一侧那条 20 万行上限
        // 是**兜底闸门**——派工单要求保留，就保留；判据本身用注入的小上限钉住。
        // 生产调用 `convert(src, cacheDir)` 一个字没变，MAX_ROWS 仍是 20 万。
        val sheet = xlsSheet("sheet")
        repeat(5) { r -> sheet.text(0, r, "第 $r 行") }
        val src = writeXls(ws, "太多行.xls", sheet)

        // 先试注入的小上限（此时还没有缓存，走的才是「打开 → 查行数 → 拒」那条路）；
        // 反过来的顺序会命中缓存直接返回，测不到判据。
        val out = XlsCompat.convert(src, cacheDir(), maxRows = 3)
        assertTrue("超过注入的上限 ⇒ 必须失败", out.isFailure)
        val e = out.exceptionOrNull() as XlsFailure
        assertTrue("要说明行数与上限：${e.action}", e.action.contains("有 5 行"))
        assertTrue("要说明上限：${e.action}", e.action.contains("超过 3 行上限"))
        assertTrue("要告诉用户下一步：${e.action}", e.action.contains("拆"))
        // 拒了就不许真转（先查行数再写盘：写了再拒 = 下游可能读到一个半截的表）
        assertEquals("超限就不该真转", 0, XlsCompat.convertCount)
        // 上限内照常转（这一句钉住「注入的上限没把正常路径也关掉」）
        val ok = XlsCompat.convert(src, cacheDir())
        assertTrue("5 行在上限内 ⇒ 该成功：${ok.exceptionOrNull()?.message}", ok.isSuccess)
        assertEquals(5, ok.getOrNull()!!.rows)
    }

    // ── 登记簿接线 ──────────────────────────────────────────────────────────

    @Test
    fun `xls 进登记簿 且带着已转成 CSV 那句话`() {
        writeXls(
            ws,
            "物流.xls",
            xlsSheet("sheet").apply {
                text(0, 0, "单号"); text(1, 0, "状态")
                text(0, 1, "1073145"); text(1, 1, "已装车")
            },
        )
        val reg = registry()
        val report = reg.refresh()
        assertEquals(1, report.scanned)
        val entry = reg.entries().first()
        assertEquals("物流.xls", entry.rel)
        val notice = entry.xls
        assertNotNull("`.xls` 的登记必须带转换说明（它与 .xlsx 不同级）", notice)
        notice!!
        assertEquals(2, notice.rows)
        assertEquals(2, notice.columns)
        assertTrue(notice.sentence().contains("已把「物流.xls」自动转成 CSV（2 行 × 2 列）"))
        // 真能问出数来（走的是既有 CSV 通道 + 既有模型）
        val model = reg.modelOf("物流.xls")
        assertNotNull(model)
        assertEquals(1, model!!.rows.size)
    }

    @Test
    fun `xlsx csv tsv 三条路一个字没动`() {
        writeScoreXlsx(ws)
        writeCsv(ws, "名单.csv", "姓名,班级\n张三,三班\n李四,三班\n")
        writeCsv(ws, "名单.tsv", "姓名\t班级\n张三\t三班\n")
        val reg = registry()
        val report = reg.refresh()
        assertEquals(3, report.scanned)
        assertEquals(3, report.parsed)
        assertEquals(
            listOf("名单.csv", "名单.tsv", "成绩表.xlsx"),
            reg.entries().map { it.rel }.sorted(),
        )
        // 三条老路都**没有** xls 说明（说明不是无脑给每张表都挂上）
        assertTrue(reg.entries().none { it.xls != null })
        assertEquals(0, XlsCompat.convertCount)
    }

    @Test
    fun `xls 转不动时 提示进 ask 而不是静默`() {
        val good = writeXls(ws, "坏表.xls", xlsSheet("sheet").apply { repeat(40) { i -> text(0, i, "NO-$i") } })
        val bytes = good.readBytes()
        File(ws, "坏表.xls").writeBytes(bytes.copyOf(bytes.size / 3))
        val reg = registry()
        reg.refresh()
        val entry = reg.entries().firstOrNull { it.rel == "坏表.xls" }
        assertNotNull("读不动的 .xls 也要登记（不许悄悄消失）", entry)
        val ask = entry!!.sheets.first().ask
        assertNotNull("失败必须有话：", ask)
        assertTrue("提示要能动手：$ask", ask!!.contains("请重新导出"))
        assertTrue(reg.xlsFailures.containsKey("坏表.xls"))
    }

    @Test
    fun `转换不占用主线程之外的约定：转完了状态要归位`() {
        writeXls(ws, "状态.xls", xlsSheet("sheet").apply { text(0, 0, "A") })
        registry().refresh()
        assertFalse("转完必须把「正在转换」收起来", XlsProgress.active)
        assertEquals("", XlsProgress.name)
    }

    // ── 真表夹具（`..\_zd-private\样本\f9\`） ────────────────────────────────

    @Test
    fun `真表 物流 xls：94 条数据 28 列`() {
        val src = File(REAL_XLS_DIR, "物流.xls")
        Assume.assumeTrue("真表夹具不在，跳过（不造假数据顶替）：${src.absolutePath}", src.isFile)
        val done = XlsCompat.convert(src, cacheDir()).getOrNull()!!
        assertEquals("第一张工作表就是 95 行（94 条数据 + 1 行表头）", 95, done.rows)
        assertEquals(28, done.columns)
        assertEquals("sheet", done.sheet)
        val grid = SheetCsv.read(done.csv, range = null, hasHeader = false, maxRows = 1000)
        assertEquals(95, grid.rows.size)
        assertEquals(28, grid.rows[0].size)
        assertEquals("运单号", grid.rows[0][0])
        assertEquals("1073145", grid.rows[1][0])
        assertEquals("新", grid.rows[1][1])
        assertEquals("CT27-001-17", grid.rows[1][3])
        assertEquals("2026-10-27 08:40", grid.rows[1][4])
        assertEquals("成都", grid.rows[1][5])
    }

    @Test
    fun `真表 到货库存 xls：43 条数据 43 列 84 个日期都不是序列号`() {
        val src = File(REAL_XLS_DIR, "到货库存.xls")
        Assume.assumeTrue("真表夹具不在，跳过（不造假数据顶替）：${src.absolutePath}", src.isFile)
        val done = XlsCompat.convert(src, cacheDir()).getOrNull()!!
        assertEquals("第一张工作表就是 44 行（43 条数据 + 1 行表头）", 44, done.rows)
        assertEquals(43, done.columns)
        val csv = done.csv.readText(Charsets.UTF_8)
        // 日期列（下单时间/到站时间）：源里是 DateCell，序列化号是 46319.4 这一档
        assertFalse("序列号漏出来了", csv.contains("46319"))
        assertFalse("序列号漏出来了", csv.contains("46322.6"))
        assertTrue(csv.contains("2026-10-24 09:36"))
        assertTrue(csv.contains("2026-10-27 16:18"))
        val grid = SheetCsv.read(done.csv, range = null, hasHeader = false, maxRows = 1000)
        assertEquals(44, grid.rows.size)
        assertEquals(43, grid.rows[1].size)
        // 列不错位：第 6/7 列（0 起 5/6）是那两个时间
        assertEquals("BC24-002-6", grid.rows[1][3])
        assertEquals("2026-10-24 09:36", grid.rows[1][5])
        assertEquals("2026-10-27 16:18", grid.rows[1][6])
    }

    @Test
    fun `真表 走登记簿：能问出真实答案`() {
        val src = File(REAL_XLS_DIR, "物流.xls")
        Assume.assumeTrue("真表夹具不在，跳过：${src.absolutePath}", src.isFile)
        val mine = File(ws, "物流.xls")
        src.copyTo(mine, overwrite = true)
        mine.setLastModified(System.currentTimeMillis())
        val reg = registry()
        reg.refresh()
        val notice = reg.entries().first { it.rel == "物流.xls" }.xls!!
        assertEquals(95, notice.rows)
        assertEquals(28, notice.columns)
        val answer = TableQuery.ask(reg, TableAsk(op = TableOp.COUNT, table = "物流.xls"))
        assertEquals("95 行原样进来 ⇒ 94 条数据（去掉表头）", 94.0, answer.numbers.firstOrNull() ?: -1.0, 0.0)
        assertTrue(
            "答案里必须交代「已自动转成 CSV」：${answer.text()}",
            answer.text().contains("已把「物流.xls」自动转成 CSV（95 行 × 28 列）"),
        )
    }
}
