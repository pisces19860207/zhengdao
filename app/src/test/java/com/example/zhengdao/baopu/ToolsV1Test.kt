// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百一十八批 · 工具补齐 v1 的判据（六个工具每个 ≥1 正例＋派工单负例逐条红；
// 红的定义与 205/206 一致：坏行为被闸挡住——行为断言＋源码门禁双保险）。
package com.example.zhengdao.baopu

import com.example.zhengdao.baopu.agent.ClipboardWriteTool
import com.example.zhengdao.baopu.agent.DeviceHost
import com.example.zhengdao.baopu.agent.DeviceTools
import com.example.zhengdao.baopu.agent.NotifyTool
import com.example.zhengdao.baopu.agent.PhoneDialTool
import com.example.zhengdao.baopu.agent.ScheduleTaskTool
import com.example.zhengdao.baopu.agent.SearchWebParse
import com.example.zhengdao.baopu.agent.SearchWebPolicy
import com.example.zhengdao.baopu.agent.SearchWebTool
import com.example.zhengdao.baopu.agent.SheetCsv
import com.example.zhengdao.baopu.agent.SheetLimits
import com.example.zhengdao.baopu.agent.SheetReadFailure
import com.example.zhengdao.baopu.agent.SheetReadTool
import com.example.zhengdao.baopu.agent.SheetReader
import com.example.zhengdao.baopu.agent.SheetWorkbook
import com.example.zhengdao.baopu.agent.ToolGroupTable
import com.example.zhengdao.baopu.agent.ToolOutcome
import com.example.zhengdao.schedule.ScheduleRules
import com.example.zhengdao.schedule.ScheduleStore
import com.example.zhengdao.schedule.ScheduleTask
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ToolsV1Test {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var ws: File
    private val logs = mutableListOf<String>()

    @Before
    fun setUp() {
        ws = tmp.newFolder("ws")
        logs.clear()
    }

    // ── ① sheet_read ────────────────────────────────────────────────────────

    /** 造一个最小 xlsx（真 zip+XML，按 [SheetWorkbook] 认的四块结构手工拼）。 */
    private fun writeXlsx(name: String, shared: List<String>, rowsXml: String, encrypted: Boolean = false): File {
        val f = File(ws, name)
        ZipOutputStream(f.outputStream().buffered()).use { z ->
            fun put(path: String, content: String) {
                z.putNextEntry(ZipEntry(path))
                z.write(content.toByteArray(Charsets.UTF_8))
                z.closeEntry()
            }
            if (encrypted) {
                put("EncryptionInfo", "fake")
                put("EncryptedPackage", "fake")
                return f
            }
            put(
                "[Content_Types].xml",
                "<?xml version=\"1.0\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"/>",
            )
            put(
                "xl/workbook.xml",
                "<?xml version=\"1.0\"?><workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"" +
                    " xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">" +
                    "<sheets><sheet name=\"客户\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>",
            )
            put(
                "xl/_rels/workbook.xml.rels",
                "<?xml version=\"1.0\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                    "<Relationship Id=\"rId1\" Target=\"worksheets/sheet1.xml\"/></Relationships>",
            )
            if (shared.isNotEmpty()) {
                put(
                    "xl/sharedStrings.xml",
                    "<?xml version=\"1.0\"?><sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">" +
                        shared.joinToString("") { "<si><t>$it</t></si>" } + "</sst>",
                )
            }
            put(
                "xl/worksheets/sheet1.xml",
                "<?xml version=\"1.0\"?><worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">" +
                    rowsXml + "</worksheet>",
            )
        }
        return f
    }

    @Test
    fun `sheet1_xlsx结构化_空单元格给null不补0_类型原样`() {
        // 行1：姓名(共享串) / 电话(数字原样) / 备注(空)；行2：只填 A 列
        val f = writeXlsx(
            "客户.xlsx",
            shared = listOf("张三丰", "李小龙"),
            rowsXml = "<row r=\"1\"><c r=\"A1\" t=\"s\"><v>0</v></c><c r=\"B1\"><v>13800138000</v></c><c r=\"C1\"/></row>" +
                "<row r=\"2\"><c r=\"A2\" t=\"s\"><v>1</v></c><c r=\"C2\"><v>2026-10-25</v></c></row>",
        )
        val out = SheetReadTool.run(JSONObject().put("path", "客户.xlsx").put("hasHeader", true), ws)
        assertTrue("正例：能读", out is ToolOutcome.Ok)
        val body = (out as ToolOutcome.Ok).text
        assertTrue(body.contains("客户"))
        val parsed = JSONObject(body)
        val rows = parsed.getJSONArray("rows") // 二维：行 → 单元格；hasHeader ⇒ rows 从第二行起
        assertEquals("表头从首行切出（含数字原样：不猜类型）", "13800138000", parsed.getJSONArray("header").getString(1))
        assertEquals("数字原样（不猜类型）", "13800138000", parsed.getJSONArray("header").getString(1))
        assertEquals("B2 空单元格 ⇒ null（不许补 0）", JSONObject.NULL, rows.getJSONArray(0).get(1))
        assertEquals("日期原样（不猜格式）", "2026-10-25", rows.getJSONArray(0).getString(2))
        assertEquals("表头第一格", "张三丰", parsed.getJSONArray("header").getString(0))
        assertFalse(parsed.getBoolean("truncated"))
    }

    @Test
    fun `sheet2_负例_超限标truncated_20MB拒绝_加密未认领_不支持不硬读`() {
        // 超限 ⇒ truncated 标注（不许静默截断）
        val many = (1..30).joinToString("") { i -> "<row r=\"$i\"><c r=\"A$i\"><v>$i</v></c></row>" }
        val f = writeXlsx("多行.xlsx", shared = emptyList(), rowsXml = many)
        val out = SheetReadTool.run(JSONObject().put("path", "多行.xlsx").put("maxRows", 10), ws)
        assertTrue(out is ToolOutcome.Ok && (out as ToolOutcome.Ok).text.contains("\"truncated\": true"))

        // 20MB ⇒ 拒绝并说明（不许静默）
        val big = File(ws, "大.csv")
        big.writeText("a,b\n")
        assertTrue(
            SheetReadTool.run(JSONObject().put("path", "大.csv"), ws) is ToolOutcome.Ok, // 空表照读
        )
        // TooBig 判据直接钉（20MB 闸在 SheetReader.read 的第一行）
        val tooBig = SheetReadFailure.TooBig(SheetLimits.MAX_FILE_BYTES + 1)
        assertTrue(tooBig.bytes > SheetLimits.MAX_FILE_BYTES)
        assertTrue(tooBig.message.orEmpty().isEmpty()) // 数字由工具层渲染成人话

        // 加密 ⇒ 未认领（不许当空表糊弄）
        val enc = writeXlsx("加密.xlsx", shared = emptyList(), rowsXml = "", encrypted = true)
        val encOut = SheetReadTool.run(JSONObject().put("path", "加密.xlsx"), ws)
        assertTrue(encOut is ToolOutcome.Failed && (encOut as ToolOutcome.Failed).error.contains("加密"))

        // 不支持格式 ⇒ 明说不许当文本硬读
        val bin = File(ws, "图.png")
        bin.writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47))
        val binOut = SheetReadTool.run(JSONObject().put("path", "图.png"), ws)
        assertTrue(binOut is ToolOutcome.Failed && (binOut as ToolOutcome.Failed).error.contains("未认领"))
    }

    @Test
    fun `sheet3_csv正则与原样_RFC4180`() {
        val f = File(ws, "表.csv")
        f.writeText("姓名,电话\n\"张,三\",13800138000\n李四,\n", Charsets.UTF_8)
        val grid = SheetCsv.parse(f.readText(Charsets.UTF_8))
        assertEquals("引号里的逗号是内容", "张,三", grid[1][0])
        assertNull("空字段 ⇒ null（不补 0）", grid[2][1])
    }

    // ── ② search_web ────────────────────────────────────────────────────────

    @Test
    fun `search1_负例_内网拒绝_白名单外拒绝_真实URL解码`() {
        // 内网/本机一律拒绝（负例②：抓内网地址）
        listOf(
            "http://localhost:8080/x", "http://127.0.0.1/a", "http://192.168.1.5/a",
            "http://10.0.0.2/a", "http://router.local/a", "http://172.16.0.9/a",
        ).forEach { url ->
            assertTrue("内网必须拒：$url", SearchWebPolicy.isPrivate(url))
            assertFalse(SearchWebPolicy.allowed(url))
        }
        // 白名单外的公网域 ⇒ 不抓（负例③）
        assertFalse(SearchWebPolicy.allowed("https://random-site.example.com/a"))
        assertTrue(SearchWebPolicy.allowed("https://zh.wikipedia.org/wiki/测试"))
        assertTrue("主域后缀匹配", SearchWebPolicy.allowed("https://en.wikipedia.org/wiki/X"))
        // site 限定的域不在白名单 ⇒ runOnce 直接拒（负例③的行为半边在 spec 执行器）
        val siteOut = SearchWebTool.run(
            JSONObject().put("query", "q").put("site", "random-site.example.com"),
            nowMs = 0L,
        )
        assertTrue(siteOut is ToolOutcome.Failed && (siteOut as ToolOutcome.Failed).error.contains("白名单"))
        // 真实 URL 从 uddg 解码（负例①：不许编 URL）
        assertEquals(
            "https://example.com/真链接",
            SearchWebParse.realUrl("//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2F%E7%9C%9F%E9%93%BE%E6%8E%A5&rut=abc"),
        )
        assertNull("编不出真实地址的链接丢弃", SearchWebParse.realUrl("//duckduckgo.com/l/?yisp=xxx"))
    }

    @Test
    fun `search2_结果解析_日志只记哈希不记原文`() {
        val html = """
            <div><a class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fzh.wikipedia.org%2Fwiki%2Ftest">测试<b>页</b></a></div>
            <a class="result__snippet" href="…">这是摘要 &amp; 一点说明</a>
        """.trimIndent()
        val hits = SearchWebParse.hits(html, 5, 42L)
        assertEquals(1, hits.size)
        assertEquals("标题去标签", "测试页", hits[0].title)
        assertEquals("真实 URL（解码）", "https://zh.wikipedia.org/wiki/test", hits[0].url)
        assertTrue(hits[0].snippet.contains("这是摘要"))
        // 日志抽样：唯一拼法只含哈希（qsha），不含检索原文；工具正文里不许另拼原文进日志
        val line = SearchWebPolicy.queryLogLine("员工的身份证号是多少", null, 3)
        assertTrue(line.startsWith("search_web: qsha="))
        assertTrue("检索原文一个字不进日志", !line.contains("身份证号"))
        val src = repoFile("src/main/java/com/example/zhengdao/baopu/agent/tools/SearchWebTool.kt").readText()
            .replace(Regex("(?s)/\\*.*?\\*/"), "").replace(Regex("(?m)^[ \t]*//.*$"), "")
        assertEquals(
            "RunLog 只经 queryLogLine 一处拼行（旁处不许把原文拼进日志）",
            1,
            Regex("RunLog\\.log\\(").findAll(src).count(),
        )
        val out = SearchWebTool.run(
            JSONObject().put("query", "员工的身份证号是多少").put("maxResults", 3),
            nowMs = 42L,
        )
        assertTrue("没 key/没网 ⇒ 如实说，不编链接", out is ToolOutcome.Failed)
    }

    // ── ③ schedule_task ─────────────────────────────────────────────────────

    @Test
    fun `sched1_自然语言when解析_非法when拒绝不许写库`() {
        val daily = com.example.zhengdao.baopu.agent.ScheduleWhenParser.parse("每天 21:00")!!
        assertEquals(com.example.zhengdao.schedule.ScheduleFreq.DAILY, daily.first)
        assertEquals(21 * 60, daily.second)
        val weekly = com.example.zhengdao.baopu.agent.ScheduleWhenParser.parse("每周三 09:30")!!
        assertEquals(com.example.zhengdao.schedule.ScheduleFreq.WEEKLY, weekly.first)
        assertEquals(3, weekly.third.first)
        assertEquals(9 * 60 + 30, weekly.second)
        val once = com.example.zhengdao.baopu.agent.ScheduleWhenParser.parse("2026-11-01 21:00")!!
        assertEquals(com.example.zhengdao.schedule.ScheduleFreq.ONCE, once.first)
        assertEquals("2026-11-01", once.third.second)
        val bare = com.example.zhengdao.baopu.agent.ScheduleWhenParser.parse("21:00")!!
        assertEquals(com.example.zhengdao.schedule.ScheduleFreq.DAILY, bare.first)
        // 认不出 ⇒ null（负例：cron/含糊话不许猜着写库）
        assertNull(com.example.zhengdao.baopu.agent.ScheduleWhenParser.parse("0 21 * * *"))
        assertNull(com.example.zhengdao.baopu.agent.ScheduleWhenParser.parse("下周二下午大概三点"))
    }

    @Test
    fun `sched2_create必过确认卡且入库_list可见_含敏感动作词必须提示`() {
        val storeFile = File(tmp.newFolder("sched"), "schedule_tasks.json")
        val store = ScheduleStore(storeFile)
        ScheduleTaskTool.storeProvider = { store }
        var rescheduled = false
        ScheduleTaskTool.sink = object : com.example.zhengdao.baopu.agent.ScheduleToolSink {
            override fun reschedule() {
                rescheduled = true
            }
        }
        // permission=CONFIRM（负例：绕过确认卡直接建 ⇒ 由权限层挡，源码门禁再钉一道）
        assertEquals(
            "schedule_task 必须是 CONFIRM（R2：无人值守）",
            com.example.zhengdao.baopu.agent.ToolPermission.CONFIRM,
            ScheduleTaskTool.spec().permission,
        )
        val out = ScheduleTaskTool.run(
            JSONObject().put("action", "create").put("when", "每天 21:00").put("prompt", "跑对拍"),
            nowMs = 1_000L,
        )
        assertTrue(out is ToolOutcome.Ok)
        assertTrue(store.load().tasks.size == 1)
        assertTrue("写完库要重排系统闹钟", rescheduled)
        // 含扣费/外发类词 ⇒ 必须提示（负例：含 R3 动作却不提示）
        val sensitive = ScheduleTaskTool.run(
            JSONObject().put("action", "create").put("when", "21:30").put("prompt", "每天自动扣费发短信"),
            nowMs = 2_000L,
        )
        assertTrue((sensitive as ToolOutcome.Ok).text.contains("⚠️"))
        // 非法 when ⇒ 拒绝且不写库（负例：cron 非法也写库）
        val bad = ScheduleTaskTool.run(
            JSONObject().put("action", "create").put("when", "0 21 * * *").put("prompt", "p"),
            nowMs = 3_000L,
        )
        assertTrue(bad is ToolOutcome.Failed)
        assertEquals(2, store.load().tasks.size)
        // delete 要说话（负例：删任务不通知）
        val id = store.load().tasks.first().id
        val del = ScheduleTaskTool.run(JSONObject().put("action", "delete").put("id", id), nowMs = 4_000L)
        assertTrue((del as ToolOutcome.Ok).text.contains("已删除"))
    }

    // ── ④⑤⑥ device 三件 ─────────────────────────────────────────────────────

    private class RecordingHost(
        var notificationsEnabledResult: Boolean = true,
        var postResult: Boolean = true,
        var dialResult: Boolean = true,
    ) : DeviceHost {
        val copied = mutableListOf<String>()
        val posted = mutableListOf<Triple<String, String, String?>>()
        val dialed = mutableListOf<String>()

        override fun copyToClipboard(text: String): Boolean {
            copied += text
            return true
        }

        override fun notificationsEnabled(): Boolean = notificationsEnabledResult

        override fun postNotification(title: String, body: String, channel: String?): Boolean {
            posted += Triple(title, body, channel)
            return postResult
        }

        override fun openDialer(number: String): Boolean {
            dialed += number
            return dialResult
        }
    }

    @Test
    fun `clip1_正例写入_负例超限拒绝_日志只有长度没有全文_敏感提示不阻断`() {
        val host = RecordingHost()
        DeviceTools.host = host
        val out = ClipboardWriteTool.run(JSONObject().put("text", "这里是要复制的正文"), nowMs = 0L)
        assertTrue(out is ToolOutcome.Ok)
        assertEquals("剪贴板真写进去了", "这里是要复制的正文", host.copied.single())
        assertTrue("返回只给长度+前40字摘要", (out as ToolOutcome.Ok).text.contains("已复制") && out.text.contains("开头："))

        // 超限 ⇒ 拒绝不静默截断（负例②）
        val big = ClipboardWriteTool.run(
            JSONObject().put("text", "x".repeat(DeviceTools.CLIPBOARD_MAX_BYTES + 1)),
            nowMs = 0L,
        )
        assertTrue(big is ToolOutcome.Failed && (big as ToolOutcome.Failed).error.contains("上限"))

        // 敏感 ⇒ 提示但不阻断（负例③）
        val sensitive = ClipboardWriteTool.run(
            JSONObject().put("text", "客户电话 13800138000，注意保密"),
            nowMs = 0L,
        )
        assertTrue(sensitive is ToolOutcome.Ok && (sensitive as ToolOutcome.Ok).text.contains("⚠️"))
        // 日志抽样（源码门禁）：三件工具的 RunLog.log 一律只拼数字/长度，不拼原文变量
        val devSrc = repoFile("src/main/java/com/example/zhengdao/baopu/agent/tools/DeviceTools.kt").readText()
            .replace(Regex("(?s)/\\*.*?\\*/"), "").replace(Regex("(?m)^[ \t]*//.*$"), "")
        listOf("text", "body", "raw", "masked").forEach { varName ->
            assertTrue(
                "日志不许拼原文变量（$varName）",
                Regex("RunLog\\.log\\([^\\n]*\\$\\{" + varName + "\\}").containsMatchIn(devSrc).not(),
            )
        }
        DeviceTools.host = null
    }

    @Test
    fun `notify1_没权限明说_刷屏被压_手机号先打码`() {
        val host = RecordingHost()
        DeviceTools.host = host
        // 没权限 ⇒ 明说去哪开（负例①：不静默失败）
        host.notificationsEnabledResult = false
        val denied = NotifyTool.run(JSONObject().put("title", "t").put("body", "b"), nowMs = 0L)
        assertTrue(denied is ToolOutcome.Failed)
        assertTrue((denied as ToolOutcome.Failed).error.contains("设置"))
        // 刷屏 ⇒ 第 4 条被压（负例②）
        host.notificationsEnabledResult = true
        repeat(3) { i ->
            assertTrue(NotifyTool.run(JSONObject().put("title", "t$i").put("body", "b"), nowMs = i * 1000L) is ToolOutcome.Ok)
        }
        val fourth = NotifyTool.run(JSONObject().put("title", "t4").put("body", "b"), nowMs = 4_000L)
        assertTrue(fourth is ToolOutcome.Failed && (fourth as ToolOutcome.Failed).error.contains("合并"))
        // 手机号先打码（负例③：通知内容含未脱敏手机号）
        val masked = NotifyTool.run(
            JSONObject().put("title", "回执").put("body", "已联系 13800138000 完成"),
            nowMs = 10 * 60_000L,
        )
        assertTrue(masked is ToolOutcome.Ok)
        assertEquals("通知面板里是打码的", "已联系 13****00 完成", host.posted.last().second)
        DeviceTools.host = null
    }

    @Test
    fun `dial1_只开拨号盘_空号拒绝_字母清洗`() {
        val host = RecordingHost()
        DeviceTools.host = host
        // 正例：清洗后填号
        val ok = PhoneDialTool.run(JSONObject().put("number", "tel: 138-0013-8000"), nowMs = 0L)
        assertTrue(ok is ToolOutcome.Ok)
        assertEquals("清洗：只留电话字符", "13800138000", host.dialed.single())
        // 源码门禁：绝不 ACTION_CALL（负例①）
        val src = repoFile("src/main/java/com/example/zhengdao/baopu/BaopuRuntime.kt").readText()
            .replace(Regex("(?m)^[ 	]*//.*$"), "") // 剥行注释（注释里的红线是讲道理，不是代码在用）
        assertTrue("不许出现 ACTION_CALL（剥注释后）", !src.contains("ACTION_CALL"))
        assertTrue("必须 ACTION_DIAL", src.contains("Intent.ACTION_DIAL"))
        // 空号拒绝（负例③）；含字母未清洗 ⇒ 清洗后空 ⇒ 拒绝（负例②）
        val blank = PhoneDialTool.run(JSONObject().put("number", "----"), nowMs = 0L)
        assertTrue(blank is ToolOutcome.Failed && (blank as ToolOutcome.Failed).error.contains("空号"))
        val letters = PhoneDialTool.run(JSONObject().put("number", "abcdef"), nowMs = 0L)
        assertTrue(letters is ToolOutcome.Failed)
        DeviceTools.host = null
    }

    // ── ③ 的连败 3 次自动暂停（167 层）────────────────────────────────────────

    @Test
    fun `sched3_连败3次自动暂停_成功清零_到顶通知`() {
        var task = baseTask()
        // 失败 1、2 次：照常重试（不暂停），连败计数涨
        var paused = false
        repeat(2) {
            val (next, p) = ScheduleRules.afterFailure(task, "detail")
            task = next
            paused = paused || p
        }
        assertFalse("未到 3 次不暂停", paused)
        assertEquals(2, task.failStreak)
        // 第 3 次：自动暂停＋要通知（负例：连败不通知/无限重试）
        val (third, p3) = ScheduleRules.afterFailure(task, "又失败")
        assertTrue("第 3 次连败 ⇒ 自动暂停", p3)
        assertFalse(third.enabled)
        assertEquals(3, third.failStreak)
        // 成功一轮 ⇒ 连败清零（afterHandled）
        val recovered = ScheduleRules.afterHandled(third.copy(enabled = true), 0L, com.example.zhengdao.schedule.ScheduleOutcome.FIRED, "", 1L, ran = true)
        assertEquals("发出去 ⇒ 连败终止", 0, recovered.failStreak)
        // 老文件兼容：没有 failStreak 键的旧任务照读（默认 0）
        val legacy = JSONObject(baseTask().toJson().toString().replace("\"failStreak\":0,", ""))
        assertEquals(0, ScheduleTask.fromJson(legacy)?.failStreak)
    }

    @Test
    fun `registry_六工具进表_组归属与档位对上_覆盖率自检不破`() {
        val expected = mapOf(
            "sheet_read" to "R0", "search_web" to "R0", "schedule_task" to "R2",
            "clipboard_write" to "R1", "notify" to "R1", "phone_dial" to "R1",
        )
        val entries = com.example.zhengdao.baopu.agent.BaopuToolCatalog.ENTRIES
        expected.forEach { (name, tier) ->
            val spec = entries.first { it.name == name }.factory({ File("/tmp") })
            assertEquals("「$name」的档位", tier, spec.riskTier)
        }
        assertEquals("sheet_read→DATA", ToolGroupTable.GROUP_OF["sheet_read"], com.example.zhengdao.baopu.agent.ToolGroup.DATA)
        assertEquals("search_web→WEBSEARCH", ToolGroupTable.GROUP_OF["search_web"], com.example.zhengdao.baopu.agent.ToolGroup.WEBSEARCH)
        assertEquals("schedule_task→SCHEDULE", ToolGroupTable.GROUP_OF["schedule_task"], com.example.zhengdao.baopu.agent.ToolGroup.SCHEDULE)
        assertEquals("clipboard_write→DEVICE", ToolGroupTable.GROUP_OF["clipboard_write"], com.example.zhengdao.baopu.agent.ToolGroup.DEVICE)
        assertEquals("notify→DEVICE", ToolGroupTable.GROUP_OF["notify"], com.example.zhengdao.baopu.agent.ToolGroup.DEVICE)
        assertEquals("phone_dial→DEVICE", ToolGroupTable.GROUP_OF["phone_dial"], com.example.zhengdao.baopu.agent.ToolGroup.DEVICE)
        assertNull("覆盖率自检：孤儿工具直接红（六件都进表 ⇒ null）", ToolGroupTable.checkCoverage())
    }

    // ── 工具 ────────────────────────────────────────────────────────────────

    private fun baseTask(): ScheduleTask = ScheduleTask(
        id = "t1", title = "每天跑", prompt = "p", freq = com.example.zhengdao.schedule.ScheduleFreq.DAILY,
        minuteOfDay = 1260, dayOfWeek = 0, onceDate = "", zone = "Asia/Shanghai", enabled = true,
        nextRunAtMs = 1_000L, firedSlot = "", lastRunAtMs = 0L,
        lastOutcome = com.example.zhengdao.schedule.ScheduleOutcome.NONE, lastDetail = "",
        sessionId = -1L, runCount = 0, attempts = 0, createdAtMs = 0L,
    )

    private fun repoFile(rel: String): File = File(rel).let { if (it.isFile) it else File("app/$rel") }
}
