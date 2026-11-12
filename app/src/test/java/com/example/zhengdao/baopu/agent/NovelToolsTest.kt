// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百零九批判据测试（派工单 §二）：
// ① 四读各一夹具（真结构文件 ⇒ 结构与字段正确）；② 坏 JSON5/缺必填 ⇒ 报「第 N 行」；
// ③ 围栏三负例（../、绝对路径、symlink 逃逸）全拒并记账；④ 写两件默认不可见，
// 开启后 append 成功且不改已有（对拍）；⑤ R1 必问弹卡，拒绝 0 写入。
// 全部 JVM 直测；临时目录走 testkit/TempSandbox（@After 收口）。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.DecisionLedger
import com.example.zhengdao.baopu.RiskClass
import com.example.zhengdao.baopu.RiskTable
import com.example.zhengdao.testkit.TempSandboxPool
import com.example.zhengdao.testkit.TestAssume
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.Executors

class NovelToolsTest {

    @After
    fun zdCleanSandboxes() = TempSandboxPool.clean()

    private fun tmp(): File = TempSandboxPool.create("novel-609")

    /** 建一个小说项目骨架（book.json ＋ 正文/），返回项目目录。 */
    private fun project(root: File, title: String = "测试书"): File {
        val dir = File(File(root, NOVEL_ROOT_DIR), title)
        File(dir, NOVEL_BODY_DIR).mkdirs()
        File(dir, NOVEL_META_FILE).writeText("""{"format":"zhengdao-novel/1","title":"$title"}""")
        return dir
    }

    private fun asset(dir: File, sub: String, name: String, text: String): File {
        val d = File(dir, sub)
        d.mkdirs()
        return File(d, name).apply { writeText(text) }
    }

    /** 读工具回执：「抬头：\n<JSON 数组>」⇒ 取出数组（结构断言用）。 */
    private fun jsonOf(r: ToolOutcome): JSONArray {
        assertTrue("读必须成功：$r", r is ToolOutcome.Ok)
        return JSONArray((r as ToolOutcome.Ok).text.substringAfter("：\n"))
    }

    private fun errOf(r: ToolOutcome): String {
        assertTrue("必须失败（不许静默空）：$r", r is ToolOutcome.Failed)
        return (r as ToolOutcome.Failed).error
    }

    // ── 判据①：四读各一夹具 ─────────────────────────────────────────────

    @Test
    fun `关系夹具_结构字段正确`() {
        val root = tmp()
        val p = project(root)
        asset(
            p, "关系", "主线.rjson5",
            """
            // 主线关系
            { relations: [
              {from:'张三', to:'李四', kind:'师徒', note:'入门引路人',},
              {from:"李四", to:"王五", kind:"宿敌"},
            ] }
            """.trimIndent(),
        )
        val arr = jsonOf(NovelRelationsTool.read(root, ""))
        assertEquals(2, arr.length())
        val first = arr.getJSONObject(0)
        assertEquals("张三", first.getString("from"))
        assertEquals("李四", first.getString("to"))
        assertEquals("师徒", first.getString("kind"))
        assertEquals("入门引路人", first.getString("note"))
        assertFalse("note 可空 ⇒ 第二条不该有 note", arr.getJSONObject(1).has("note"))
    }

    @Test
    fun `时间线夹具_结构_父子_区间过滤`() {
        val root = tmp()
        val p = project(root)
        asset(
            p, "时间线", "大事.tjson5",
            """
            { events: [
              {id:'e1', name:'出生', at:'2020-01-01'},
              {id:'e2', name:'入门', at:'2026-03-01', parent:'e1'},
              {id:'e3', name:'大成', at:'2030-06-01', parent:'e2'},
            ] }
            """.trimIndent(),
        )
        val all = jsonOf(NovelTimelineTool.read(root, "", "", ""))
        assertEquals(3, all.length())
        assertEquals("e1", all.getJSONObject(0).getString("id"))
        assertEquals("出生", all.getJSONObject(0).getString("name"))
        assertEquals("2020-01-01", all.getJSONObject(0).getString("at"))
        assertEquals("e1", all.getJSONObject(1).getString("parent"))
        assertEquals("e2", all.getJSONObject(0).getJSONArray("children").getString(0))
        // 区间过滤（闭区间，字符串序）
        val mid = jsonOf(NovelTimelineTool.read(root, "", "2026-01-01", "2029-12-31"))
        assertEquals(1, mid.length())
        assertEquals("入门", mid.getJSONObject(0).getString("name"))
    }

    @Test
    fun `批注夹具_线程含状态与回复`() {
        val root = tmp()
        val p = project(root)
        asset(
            p, "批注", "第一章.ajson5",
            """
            { threads: [
              { id:'t1', target:'第001章', quote:'天亮了', status:'open',
                comments: [ {author:'读者甲', at:'2026-11-01', text:'这里节奏快了'} ] },
              { id:'t2', status:'resolved' },
            ] }
            """.trimIndent(),
        )
        val arr = jsonOf(NovelAnnotationsTool.read(root, ""))
        assertEquals(2, arr.length())
        val t1 = arr.getJSONObject(0)
        assertEquals("t1", t1.getString("id"))
        assertEquals("open", t1.getString("status"))
        assertEquals("第001章", t1.getString("target"))
        assertEquals("这里节奏快了", t1.getJSONArray("comments").getJSONObject(0).getString("text"))
        assertEquals("resolved", arr.getJSONObject(1).getString("status"))
        assertEquals(0, arr.getJSONObject(1).getJSONArray("comments").length())
    }

    @Test
    fun `角色树夹具_md层级_json5_关系推断`() {
        val root = tmp()
        val p = project(root)
        asset(
            p, "角色", "主要.md",
            """
            # 张三
            别名：张小三、三哥
            ## 张小二
            正文描述不读。
            # 李四
            """.trimIndent(),
        )
        asset(
            p, "角色", "配角.json5",
            """{ characters: [ {name:'王五', aliases:['老五'], group:'散修'} ] }""",
        )
        asset(
            p, "关系", "门派.rjson5",
            """
            [ {from:'张三', to:'青云门', kind:'门派'},
              {from:'张小二', to:'张三', kind:'父亲'} ]
            """.trimIndent(),
        )
        val arr = jsonOf(NovelRosterTool.read(root, ""))
        assertEquals(4, arr.length())
        val byName = (0 until arr.length()).associateBy { arr.getJSONObject(it).getString("name") }
        val zhang = arr.getJSONObject(byName.getValue("张三"))
        assertEquals("青云门", zhang.getString("group"))
        assertEquals("张小三", zhang.getJSONArray("aliases").getString(0))
        assertEquals("张小二", zhang.getJSONArray("children").getString(0))
        val xiao = arr.getJSONObject(byName.getValue("张小二"))
        assertEquals("张三", xiao.getString("parent"))
        val wang = arr.getJSONObject(byName.getValue("王五"))
        assertEquals("散修", wang.getString("group"))
        assertEquals("老五", wang.getJSONArray("aliases").getString(0))
    }

    // ── 判据②：坏文件报「第 N 行」，不许静默空 ──────────────────────────

    @Test
    fun `坏JSON5_四读都报行号`() {
        val root = tmp()
        val p = project(root)
        asset(p, "关系", "坏.rjson5", "{\n  relations: [\n    {from:'甲' to:'乙'}\n  ]\n}")
        assertTrue(errOf(NovelRelationsTool.read(root, "")).contains("第 3 行"))
        asset(p, "时间线", "坏.tjson5", "{\n  events: [\n    {name:'x', at:'1'}\n    {name:'y', at:'2'}\n  ]\n}")
        assertTrue(errOf(NovelTimelineTool.read(root, "", "", "")).contains("第 4 行"))
        asset(p, "批注", "坏.ajson5", "{\n  threads: [\n    {id:'t1',}\n    {id:'t2'}\n  ]\n}")
        assertTrue(errOf(NovelAnnotationsTool.read(root, "")).contains("第 4 行"))
        asset(p, "角色", "坏.json5", "{\n  characters: [\n    {name:'甲'}\n    {name:'乙'\n  ]\n}")
        assertTrue(errOf(NovelRosterTool.read(root, "")).contains("第 4 行"))
    }

    @Test
    fun `缺必填_也报行号_不猜`() {
        val root = tmp()
        val p = project(root)
        asset(p, "关系", "缺.rjson5", "[\n  {from:'甲', to:'乙', kind:'友'},\n  {from:'丙', to:'丁'}\n]")
        assertTrue(errOf(NovelRelationsTool.read(root, "")).contains("第 3 行"))
        asset(p, "时间线", "缺.tjson5", "[\n  {name:'有始无终'}\n]")
        assertTrue(errOf(NovelTimelineTool.read(root, "", "", "")).contains("第 2 行"))
        asset(p, "批注", "缺.ajson5", "[\n  {status:'open'}\n]")
        assertTrue(errOf(NovelAnnotationsTool.read(root, "")).contains("第 2 行"))
        asset(p, "角色", "缺.json5", "[\n  {group:'散修'}\n]")
        assertTrue(errOf(NovelRosterTool.read(root, "")).contains("第 2 行"))
    }

    // ── 判据③：围栏三负例全拒（＋记账） ─────────────────────────────────

    @Test
    fun `围栏_点点逃逸_拒`() {
        val root = tmp()
        project(root)
        val r = NovelRelationsTool.read(root, "../外面.rjson5")
        assertTrue("「..」必须拒：$r", r is ToolOutcome.Rejected)
        assertTrue((r as ToolOutcome.Rejected).reason.contains(".."))
    }

    @Test
    fun `围栏_绝对路径越界_拒`() {
        val root = tmp()
        project(root)
        val outside = TempSandboxPool.create("novel-609-out")
        val f = File(outside, "x.rjson5").apply { writeText("[]") }
        val r = NovelRelationsTool.read(root, f.absolutePath)
        assertTrue("围栏外绝对路径必须拒：$r", r is ToolOutcome.Rejected)
    }

    @Test
    fun `围栏_绝对路径但在围栏内_放行`() {
        val root = tmp()
        val p = project(root)
        val f = asset(p, "关系", "内.rjson5", "[{from:'甲',to:'乙',kind:'友'}]")
        val r = NovelRelationsTool.read(root, f.absolutePath)
        assertTrue("围栏内绝对路径不算逃逸：$r", r is ToolOutcome.Ok)
    }

    @Test
    fun `围栏_symlink逃逸_拒`() {
        val root = tmp()
        val p = project(root)
        val rel = File(p, "关系").apply { mkdirs() }
        val outside = TempSandboxPool.create("novel-609-out")
        val outsideFile = File(outside, "秘密.rjson5").apply { writeText("[]") }
        // 先真软链（文件级）；本机不支持 ⇒ junction（目录级，Windows 免特权，
        // canonicalFile 一样解析）；两者都不行 ⇒ 标准环境门跳过（E-138 口径）。
        val fileLink = File(rel, "逃逸.rjson5")
        var link = fileLink
        var probePath = "小说/测试书/关系/逃逸.rjson5"
        val sym = runCatching {
            java.nio.file.Files.createSymbolicLink(fileLink.toPath(), outsideFile.toPath())
        }.isSuccess
        if (!sym || !java.nio.file.Files.isSymbolicLink(fileLink.toPath())) {
            runCatching { java.nio.file.Files.deleteIfExists(fileLink.toPath()) }
            val dirLink = File(rel, "逃逸目录")
            val rc = runCatching {
                ProcessBuilder("cmd", "/c", "mklink", "/J", dirLink.absolutePath, outside.absolutePath)
                    .redirectErrorStream(true).start().waitFor()
            }.getOrDefault(-1)
            val junctionOk = rc == 0 && dirLink.isDirectory && dirLink.canonicalFile != dirLink.absoluteFile
            TestAssume.assume(junctionOk, "本机不支持创建符号链接/目录连接（Windows 需特权或沙箱限制，E-138）；本用例未覆盖")
            link = dirLink
            probePath = "小说/测试书/关系/逃逸目录"
        }
        try {
            val r = NovelRelationsTool.read(root, probePath)
            assertTrue("指向外部的链接必须拒：$r", r is ToolOutcome.Rejected)
        } finally {
            // nio 的 delete 不跟随链接——先摘链再让沙盒收口（Kotlin deleteRecursively 会跟进 junction）
            runCatching { java.nio.file.Files.deleteIfExists(link.toPath()) }
        }
    }

    @Test
    fun `围栏拒绝_记进工具失败审计`() {
        val root = tmp()
        project(root)
        DecisionLedger.install(root)
        try {
            val r = pipeline(root, registry(root)).execute(
                call("novel_relations", JSONObject().put("path", "../外面.rjson5")),
                { _, _ -> null },
            )
            assertTrue("围栏拒 ⇒ Rejected：$r", r is GuardPipeline.Result.Rejected)
            assertTrue(
                "拒并记账：工具失败审计里要有 novel_relations 这一行",
                FailureAuditLog.readAll().any { it.tool == "novel_relations" },
            )
        } finally {
            DecisionLedger.resetForTest()
        }
    }

    @Test
    fun `写_围栏与原件区_拒`() {
        val root = tmp()
        project(root)
        val r1 = NovelTimelineAppendTool.append(
            root,
            JSONObject().put("path", "../x.tjson5").put("name", "x").put("at", "2026-11-12"),
        )
        assertTrue("写的「..」逃逸必须拒：$r1", r1 is ToolOutcome.Rejected)
        val r2 = NovelTimelineAppendTool.append(
            root,
            JSONObject().put("path", "资料库/原始/x.tjson5").put("name", "x").put("at", "2026-11-12"),
        )
        assertTrue("资料库/原始 只读不许写：$r2", r2 is ToolOutcome.Rejected)
        val r3 = NovelAnnotationReplyTool.reply(
            root,
            JSONObject().put("path", "../x.ajson5").put("thread", "t").put("text", "x"),
        )
        assertTrue("回复的「..」逃逸必须拒：$r3", r3 is ToolOutcome.Rejected)
    }

    // ── 判据④⑤：默认不可见；开启后 append 对拍；R1 必问拒绝 0 写入 ──────

    @Test
    fun `写两件_默认不可见_幻觉调用拒_零写入`() {
        val root = tmp()
        val reg = registry(root)
        val names = reg.visibleSpecs(ToolRegistry.NO_SESSION).map { it.name }
        assertTrue("读四件默认可见（判据⑥）", names.containsAll(READS))
        assertFalse("写默认不可见", names.contains("novel_timeline_append"))
        assertFalse("写默认不可见", names.contains("novel_annotation_reply"))
        val r = pipeline(root, reg).execute(
            call(
                "novel_timeline_append",
                JSONObject().put("path", "小说/测试书/时间线/x.tjson5").put("name", "n").put("at", "2026-11-12"),
            ),
            { _, _ -> null },
        )
        assertTrue("默认关 ⇒ 桩拒绝：$r", r is GuardPipeline.Result.Rejected)
        assertTrue((r as GuardPipeline.Result.Rejected).reason.contains("关闭"))
        assertFalse("默认关 ⇒ 0 写入", File(root, "小说/测试书/时间线/x.tjson5").exists())
    }

    @Test
    fun `开启后_append成功_已有字节不动_对拍`() {
        val root = tmp()
        val p = project(root)
        val f = asset(
            p, "时间线", "大事.tjson5",
            "[\n  {\"id\":\"e1\",\"name\":\"出生\",\"at\":\"2020-01-01\"}, // 卷一\n]\n",
        )
        val old = f.readText()
        val prefs = InMemoryToolPrefs()
        val reg = registry(root, prefs)
        prefs.setDisabled("novel_timeline_append", false)
        reg.setGroupApproved(ToolGroup.LIBRARY, true)
        assertTrue("显式开＋组解锁后可见", reg.visibleSpecs(ToolRegistry.NO_SESSION).any { it.name == "novel_timeline_append" })
        val pipe = pipeline(root, reg)
        val args = JSONObject()
            .put("path", "小说/测试书/时间线/大事.tjson5")
            .put("name", "入门").put("at", "2026-03-01").put("id", "e2").put("parent", "e1")
        // 判据⑤正面：R1 必问 ⇒ 弹卡不执行；不批准 ⇒ 0 写入
        val pending = pipe.execute(call("novel_timeline_append", args), { _, _ -> null })
        assertTrue("R1 必问 ⇒ NeedsConfirm：$pending", pending is GuardPipeline.Result.NeedsConfirm)
        assertEquals("未批准 ⇒ 0 写入", old, f.readText())
        // 判据④：批准后 append 成功
        val done = pipe.executeApproved(call("novel_timeline_append", args), { _, _ -> null })
        assertTrue("批准后 append 成功：$done", done is GuardPipeline.Result.Done)
        assertAppended(old, f.readText(), "入门")
    }

    @Test
    fun `append_同名冲突_报行号_零写入`() {
        val root = tmp()
        val p = project(root)
        val f = asset(p, "时间线", "大事.tjson5", "[\n  {name:'出生',at:'2020-01-01'}\n]")
        val old = f.readText()
        val r = NovelTimelineAppendTool.append(
            root,
            JSONObject().put("path", "小说/测试书/时间线/大事.tjson5").put("name", "出生").put("at", "2026-01-01"),
        )
        val err = errOf(r)
        assertTrue("冲突要报行号：$err", err.contains("第 2 行"))
        assertTrue(err.contains("同名"))
        assertEquals("冲突 ⇒ 0 写入", old, f.readText())
    }

    @Test
    fun `append_文件不存在_新建`() {
        val root = tmp()
        project(root)
        val r = NovelTimelineAppendTool.append(
            root,
            JSONObject().put("path", "小说/测试书/时间线/新.tjson5").put("name", "开书").put("at", "2026-11-12"),
        )
        assertTrue("新建并追加：$r", r is ToolOutcome.Ok)
        val f = File(root, "小说/测试书/时间线/新.tjson5")
        assertTrue(f.isFile)
        assertEquals(1, NovelJson5.arr(NovelJson5.parse(f.readText()).node!!)!!.items.size)
    }

    @Test
    fun `开启后_reply成功_已有字节不动_对拍`() {
        val root = tmp()
        val p = project(root)
        val f = asset(
            p, "批注", "第一章.ajson5",
            "{threads:[\n  {id:'t1', status:'open', comments:[{author:'甲',text:'快'}],}, // 尾注释\n]}",
        )
        val old = f.readText()
        val prefs = InMemoryToolPrefs()
        val reg = registry(root, prefs)
        prefs.setDisabled("novel_annotation_reply", false)
        reg.setGroupApproved(ToolGroup.LIBRARY, true)
        val args = JSONObject()
            .put("path", "小说/测试书/批注/第一章.ajson5").put("thread", "t1").put("text", "收到，下章放缓")
        val pipe = pipeline(root, reg)
        val pending = pipe.execute(call("novel_annotation_reply", args), { _, _ -> null })
        assertTrue("R1 必问 ⇒ NeedsConfirm：$pending", pending is GuardPipeline.Result.NeedsConfirm)
        assertEquals("未批准 ⇒ 0 写入", old, f.readText())
        val done = pipe.executeApproved(call("novel_annotation_reply", args), { _, _ -> null })
        assertTrue("批准后 reply 成功：$done", done is GuardPipeline.Result.Done)
        val new = f.readText()
        val oldThread = NovelJson5.obj(NovelJson5.arr(NovelJson5.obj(NovelJson5.parse(old).node!!)!!["threads"])!!.items[0])!!
        val newThread = NovelJson5.obj(NovelJson5.arr(NovelJson5.obj(NovelJson5.parse(new).node!!)!!["threads"])!!.items[0])!!
        val oldComments = NovelJson5.arr(oldThread["comments"])!!.items
        val newComments = NovelJson5.arr(newThread["comments"])!!.items
        assertEquals(oldComments.size + 1, newComments.size)
        for (i in oldComments.indices) {
            assertEquals(
                "已有评论逐字节一致",
                old.substring(oldComments[i].start, oldComments[i].end),
                new.substring(newComments[i].start, newComments[i].end),
            )
        }
        val added = NovelJson5.obj(newComments.last())!!
        assertEquals("抱朴", NovelJson5.str(added["author"]))
        assertEquals("收到，下章放缓", NovelJson5.str(added["text"]))
        assertTrue("at 要有时间戳", NovelJson5.str(added["at"])!!.isNotEmpty())
        assertTrue("尾注释保留", new.contains("// 尾注释"))
        assertTrue("status 不动", new.contains("status:'open'"))
    }

    @Test
    fun `reply_线程无comments键_补键不碰已有字节`() {
        val root = tmp()
        val p = project(root)
        val f = asset(
            p, "批注", "第一章.ajson5",
            "{threads:[\n  {id:'t1', status:'open', quote:'天亮了'}, // 尾注释\n]}",
        )
        val old = f.readText()
        val r = NovelAnnotationReplyTool.reply(
            root,
            JSONObject().put("path", "小说/测试书/批注/第一章.ajson5").put("thread", "t1").put("text", "收到"),
        )
        assertTrue("补 comments 键成功：$r", r is ToolOutcome.Ok)
        val new = f.readText()
        val oldThread = NovelJson5.obj(NovelJson5.arr(NovelJson5.obj(NovelJson5.parse(old).node!!)!!["threads"])!!.items[0])!!
        val newThread = NovelJson5.obj(NovelJson5.arr(NovelJson5.obj(NovelJson5.parse(new).node!!)!!["threads"])!!.items[0])!!
        // 已有键值逐字节一致（comments 是新增键，不算「改已有」）
        for (e in oldThread.entries) {
            val n = newThread[e.key]!!
            assertEquals(
                "键「${e.key}」字节不动",
                old.substring(e.value.start, e.value.end),
                new.substring(n.start, n.end),
            )
        }
        val comments = NovelJson5.arr(newThread["comments"])!!
        assertEquals(1, comments.items.size)
        assertEquals("收到", NovelJson5.str(NovelJson5.obj(comments.items[0])!!["text"]))
        assertTrue("尾注释保留", new.contains("// 尾注释"))
    }

    @Test
    fun `reply_comments不是数组_报行号_零写入`() {
        val root = tmp()
        val p = project(root)
        val f = asset(p, "批注", "坏.ajson5", "{threads:[\n  {id:'t1', comments:'不是数组'}\n]}")
        val old = f.readText()
        val r = NovelAnnotationReplyTool.reply(
            root,
            JSONObject().put("path", "小说/测试书/批注/坏.ajson5").put("thread", "t1").put("text", "x"),
        )
        assertTrue(errOf(r).contains("第 2 行"))
        assertEquals(old, f.readText())
    }

    @Test
    fun `reply_线程不存在_不猜不新建`() {
        val root = tmp()
        val p = project(root)
        val f = asset(p, "批注", "第一章.ajson5", "{threads:[{id:'t1'}]}")
        val old = f.readText()
        val r = NovelAnnotationReplyTool.reply(
            root,
            JSONObject().put("path", "小说/测试书/批注/第一章.ajson5").put("thread", "t9").put("text", "x"),
        )
        assertTrue(errOf(r).contains("没有批注线程"))
        assertEquals(old, f.readText())
    }

    @Test
    fun `reply_文件不存在_拒`() {
        val root = tmp()
        project(root)
        val r = NovelAnnotationReplyTool.reply(
            root,
            JSONObject().put("path", "小说/测试书/批注/无.ajson5").put("thread", "t1").put("text", "x"),
        )
        assertTrue(errOf(r).contains("不存在"))
    }

    // ── 判据③⑥：分级走既有档位；读 FREE 只读、写 CONFIRM ────────────────

    @Test
    fun `分级_读R0_写R1必问_不新造档`() {
        for (n in READS) {
            assertEquals("$n 读 R0", RiskClass.R0, RiskTable.DEFAULT.tools.getValue(n).base)
        }
        for (n in listOf("novel_timeline_append", "novel_annotation_reply")) {
            val rule = RiskTable.DEFAULT.tools.getValue(n)
            assertEquals("$n 写 R1", RiskClass.R1, rule.base)
            assertTrue("$n 必问", rule.alwaysAsk)
        }
        assertEquals(ToolPermission.CONFIRM, NovelTimelineAppendTool.spec { File(".") }.permission)
        assertEquals(ToolPermission.CONFIRM, NovelAnnotationReplyTool.spec { File(".") }.permission)
        for (s in listOf(
            NovelRosterTool.spec { File(".") }, NovelRelationsTool.spec { File(".") },
            NovelTimelineTool.spec { File(".") }, NovelAnnotationsTool.spec { File(".") },
        )) {
            assertEquals("${s.name} FREE", ToolPermission.FREE, s.permission)
            assertTrue("${s.name} 只读", s.readOnly)
        }
    }

    // ── 共享小件 ────────────────────────────────────────────────────────

    private fun registry(root: File, prefs: InMemoryToolPrefs = InMemoryToolPrefs()): ToolRegistry =
        ToolRegistry(prefs).apply { BaopuToolCatalog.loadInto(this) { root } }

    private fun pipeline(root: File, reg: ToolRegistry): GuardPipeline =
        GuardPipeline(reg, { root }, { null }, ToolRunner(Executors.newSingleThreadExecutor()), {})

    private fun call(name: String, args: JSONObject): ToolCall = ToolCall("c609", name, args)

    /** 对拍：头尾字节原样、旧条目逐字节一致、只多一个新元素。 */
    private fun assertAppended(old: String, new: String, newName: String) {
        val oldItems = NovelJson5.arr(NovelJson5.parse(old).node!!)!!.items
        val newItems = NovelJson5.arr(NovelJson5.parse(new).node!!)!!.items
        assertEquals(oldItems.size + 1, newItems.size)
        assertTrue("头部字节不动", new.startsWith(old.substring(0, oldItems.last().end)))
        assertTrue("尾部字节不动", new.endsWith(old.substring(oldItems.last().end)))
        for (i in oldItems.indices) {
            assertEquals(
                "第 $i 条逐字节一致",
                old.substring(oldItems[i].start, oldItems[i].end),
                new.substring(newItems[i].start, newItems[i].end),
            )
        }
        assertEquals(newName, NovelJson5.str(NovelJson5.obj(newItems.last())!!["name"]))
        assertTrue("注释保留", new.contains("// 卷一"))
    }

    companion object {
        private val READS = listOf("novel_roster", "novel_relations", "novel_timeline", "novel_annotations")
    }
}