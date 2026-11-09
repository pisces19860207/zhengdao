// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百四十一批 · 待办中心的**行为级判据**（过注册表真跑，不看注释）。
//
// 判据逐条（派工单 §判据 ①–⑥；①"收起/展开"归第二段 UI，本文件不碰）：
//   ② 行只含摘要：`todo_list` 的回执里只有 summary/state/status/verdict，**没有**正文与来源字段；
//   ③ 模型标已做 ⇒ **右相 PASS 才到已完成**：三种真跑各一条——
//      正例（账上有落盘＋文件真在）⇒ PASS ⇒ complete；
//      负例 A（什么都没落盘）⇒ UNVERIFIABLE ⇒ 停在 done；
//      负例 B（账上有行但文件不在）⇒ FAIL ⇒ 停在 done；
//   ④ 用户确认后该条从看板消失、**账本仍有 add 那一行**（append-only，判据④）；
//   ⑤ 未确认不许删（用户点也拒）；模型调 `todo_confirm` ⇒ 注册表里根本没有这个工具
//      ＋ 落盘口对非用户角色一律拒（两道闸各一条断言）；
//   ⑥ 入口净增 0 / detektWall / lint 不在本文件——那三条是静态读数，见交付报告。
//
// ⚠️ FileOpLog 是进程级单例（与 `WorkspaceGrants` 同房型）：每条用例前后都 reset，
// 否则上一条的账行会漏进来把"没有落盘"的负例喂成 PASS（假绿）。
package com.example.zhengdao.baopu.todo

import com.example.zhengdao.baopu.FileOpKind
import com.example.zhengdao.baopu.FileOpLog
import com.example.zhengdao.baopu.agent.BaopuToolCatalog
import com.example.zhengdao.baopu.agent.InMemoryToolPrefs
import com.example.zhengdao.baopu.agent.ToolOutcome
import com.example.zhengdao.baopu.agent.ToolRegistry
import java.io.File
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TodoCenterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private var wsSeq = 0

    private fun ws(): File = tmp.newFolder("workspace${wsSeq++}")

    @Before
    fun resetLedgers() {
        FileOpLog.resetForTest()
    }

    @After
    fun clearLedgers() {
        FileOpLog.resetForTest()
    }

    /** 冷注册表：全新 prefs（没有任何"长期同意"）、没解锁任何组、currentSessionId ＝ NO_SESSION。 */
    private fun coldRegistry(root: File): ToolRegistry {
        val reg = ToolRegistry(InMemoryToolPrefs())
        BaopuToolCatalog.loadInto(reg) { root }
        return reg
    }

    private fun okJson(out: ToolOutcome): JSONObject {
        assertTrue("这一步该成功，拿到的是：$out", out is ToolOutcome.Ok)
        return JSONObject((out as ToolOutcome.Ok).text)
    }

    private fun addTodo(workspace: File, text: String, at: Long): String =
        okJson(TodoAddTool.run(JSONObject().put("text", text).put("sourceId", "缺口-541"), workspace, at))
            .getString("id")

    /** 落一件真产物（工作区相对 `产物/…`）＋账上一行写记录。 */
    private fun seedDelivery(workspace: File, rel: String, at: Long) {
        val f = File(workspace, rel)
        f.parentFile?.mkdirs()
        f.writeText("# 交付\n内容\n", Charsets.UTF_8)
        FileOpLog.record("write_file", FileOpKind.WRITE, rel, "交付", at)
    }

    // ── 免解锁与入口 ──────────────────────────────────────────────────────────

    @Test
    fun `冷启免解锁_三件在tools数组里_名册里没有`() {
        val root = ws()
        val reg = coldRegistry(root)
        val arr = reg.toolsJsonOpenAi()
        val names = (0 until arr.length()).map {
            arr.getJSONObject(it).getJSONObject("function").optString("name")
        }
        listOf("todo_add", "todo_list", "todo_mark_done").forEach {
            assertTrue("冷启 tools 数组里必须有 $it：$names", it in names)
        }
        // 真身（不是 groupLockedStub）：桩会给 Rejected
        val out = reg.find("todo_list")!!.executor(JSONObject())
        assertTrue("todo_list 冷启不许被换成桩（Rejected 就是桩）：$out", out is ToolOutcome.Ok)
        assertTrue("冷启首轮就要立得起来：$out", reg.find("todo_add")!!.executor(JSONObject().put("text", "冷启第一条")) is ToolOutcome.Ok)
        assertTrue("盘上真有了", TodoStore.fileOf(root).isFile)

        // 名册两处都不念名字（按整词匹配；与 BoardUnlockTest 同一把尺）
        val capabilities = reg.handle(JSONObject())
        assertTrue("capabilities 必须回 Ok：$capabilities", capabilities is ToolOutcome.Ok)
        val rosterText = (capabilities as ToolOutcome.Ok).text
        fun mentions(name: String): Boolean =
            Regex("""(?<![a-z0-9_])${Regex.escape(name)}(?![a-z0-9_])""").containsMatchIn(rosterText)
        listOf("todo_add", "todo_list", "todo_mark_done").forEach {
            assertFalse("capabilities 名册里不许出现 $it（542/541 同一口径）\n$rosterText", mentions(it))
        }
        // 负例：同一个匹配函数对确实念了名字的工具必须为真（否则上面三条可能恒真）
        assertTrue("匹配函数本身要能命中：delete_path 在名册里", mentions("delete_path"))
    }

    // ── ③ 标已做 → 右相三态 ───────────────────────────────────────────────────

    @Test
    fun `正例_有落盘且文件在_右相PASS_进已完成`() {
        val workspace = ws()
        val t0 = 1_000_000L
        val id = addTodo(workspace, "把交付报告写完", t0)
        seedDelivery(workspace, "产物/报告.md", t0 + 100)
        val json = okJson(TodoMarkDoneTool.run(JSONObject().put("id", id), workspace, t0 + 200))
        assertEquals("右相该判 PASS：$json", TodoRules.VERDICT_PASS, json.getString("verdict"))
        assertEquals("PASS 才进已完成", TodoState.COMPLETE.wire, json.getString("state"))
        assertTrue(json.getBoolean("complete"))
        assertEquals("账本里得有 verify 行", 3, TodoStore.rows(workspace).size)
    }

    @Test
    fun `负例A_什么都没落盘_右相判UNVERIFIABLE_停在已做`() {
        val workspace = ws()
        val id = addTodo(workspace, "只说了做完但没落盘", 1_000L)
        val json = okJson(TodoMarkDoneTool.run(JSONObject().put("id", id), workspace, 2_000L))
        assertEquals(TodoRules.VERDICT_UNVERIFIABLE, json.getString("verdict"))
        assertEquals("判不了不许进已完成", TodoState.MODEL_DONE.wire, json.getString("state"))
        assertFalse(json.getBoolean("complete"))
        assertTrue("要把缺什么回给模型：$json", json.getString("reason").contains("右相 UNVERIFIABLE"))
        // 看板上它还在「已做」
        assertEquals(TodoState.MODEL_DONE, TodoRules.live(TodoStore.items(workspace)).single().state)
    }

    @Test
    fun `负例B_账上有行但文件不在_右相判FAIL_停在已做`() {
        val workspace = ws()
        val id = addTodo(workspace, "说写了但盘上没有", 1_000L)
        // 账上有写记录，但那个文件根本不存在（模型谎报/被删掉了）
        FileOpLog.record("write_file", FileOpKind.WRITE, "产物/不存在.md", "交付", 1_500L)
        val json = okJson(TodoMarkDoneTool.run(JSONObject().put("id", id), workspace, 2_000L))
        assertEquals("右相该判 FAIL：$json", TodoRules.VERDICT_FAIL, json.getString("verdict"))
        assertEquals(TodoState.MODEL_DONE.wire, json.getString("state"))
    }

    @Test
    fun `已完成后不许再标_已删的也不许`() {
        val workspace = ws()
        val id = addTodo(workspace, "会走完全程的一条", 1_000L)
        seedDelivery(workspace, "产物/a.md", 1_100L)
        okJson(TodoMarkDoneTool.run(JSONObject().put("id", id), workspace, 1_200L))
        val again = TodoMarkDoneTool.run(JSONObject().put("id", id), workspace, 1_300L)
        assertTrue("已完成不许再标：$again", again is ToolOutcome.Failed)
    }

    // ── ④ 删除只从看板移除，账本保留 ──────────────────────────────────────────

    @Test
    fun `用户确认后看板上消失_但账本add那一行还在`() {
        val workspace = ws()
        val id = addTodo(workspace, "做完等确认的一条", 1_000L)
        seedDelivery(workspace, "产物/b.md", 1_100L)
        okJson(TodoMarkDoneTool.run(JSONObject().put("id", id), workspace, 1_200L))
        val before = TodoStore.rows(workspace).size

        val confirmWrite = TodoStore.confirm(workspace, id, TodoActor.USER, 1_300L)
        assertTrue("用户点确认该成功：$confirmWrite", confirmWrite is TodoWrite.Ok)

        // 看板（活着的那些）没有它了
        assertTrue("确认后看板上必须没有它", TodoRules.live(TodoStore.items(workspace)).isEmpty())
        // 账本一条不少，只多了一行 confirm；add 那一行原地不动
        val rows = TodoStore.rows(workspace)
        assertEquals("账本只许多出一行", before + 1, rows.size)
        val addRow = rows.first { it.op == TodoOp.ADD && it.id == id }
        assertEquals("add 那一行（谁建/何时）必须还在", TodoActor.MODEL, addRow.who)
        assertEquals("做完等确认的一条", addRow.text)
        // 谁建/谁做/谁验/何时删 四件事都在账上
        assertNotNull(rows.firstOrNull { it.op == TodoOp.DONE && it.id == id })
        assertNotNull(rows.firstOrNull { it.op == TodoOp.VERIFY && it.id == id })
        assertEquals(TodoActor.USER, rows.last().who)
        assertEquals(TodoOp.CONFIRM, rows.last().op)
        // 空看板时收起态那一行回到 0
        assertEquals("待办 0 · 在做 0", TodoRules.headerLine(TodoStore.counts(workspace)))
    }

    // ── ⑤ 未完成不许删；模型不可自删 ──────────────────────────────────────────

    @Test
    fun `未到已完成_用户点确认也拒`() {
        val workspace = ws()
        val id = addTodo(workspace, "还没做的", 1_000L)
        val before = TodoStore.rows(workspace).size
        val refused = TodoStore.confirm(workspace, id, TodoActor.USER, 2_000L)
        assertTrue("未到已完成不许删：$refused", refused is TodoWrite.Refused)
        assertEquals("拒了就不许落账", before, TodoStore.rows(workspace).size)
        assertEquals("看板上还该在", 1, TodoRules.live(TodoStore.items(workspace)).size)
    }

    @Test
    fun `模型调todo_confirm_注册表里没有这个工具`() {
        val root = ws()
        val reg = coldRegistry(root)
        assertNull("`todo_confirm` 不许被注册成工具（判据⑤的结构保证）", reg.find("todo_confirm"))
        assertFalse("tools 数组里也不许有", "todo_confirm" in reg.names())
        // 就算有人绕过注册表直接喊名字，落盘口也拒（第二道闸）
        val workspace = ws()
        val id = addTodo(workspace, "这条只有用户能删", 1_000L)
        seedDelivery(workspace, "产物/c.md", 1_100L)
        okJson(TodoMarkDoneTool.run(JSONObject().put("id", id), workspace, 1_200L))
        val before = TodoStore.rows(workspace).size
        listOf(TodoActor.MODEL, TodoActor.RIGHT_MINISTER).forEach { actor ->
            val refused = TodoStore.confirm(workspace, id, actor, 1_300L)
            assertTrue("$actor 不许删：$refused", refused is TodoWrite.Refused)
        }
        assertEquals("两次被拒都不许落账", before, TodoStore.rows(workspace).size)
    }

    // ── ② 摘要 ────────────────────────────────────────────────────────────────

    @Test
    fun `41字拒收_账本不动`() {
        val workspace = ws()
        val out = TodoAddTool.run(JSONObject().put("text", "一".repeat(41)), workspace, 1_000L)
        assertTrue("41 字必须拒：$out", out is ToolOutcome.Failed)
        assertFalse("拒了就不许建账本文件", TodoStore.fileOf(workspace).exists())
    }

    @Test
    fun `list回执只有摘要_没有正文与来源字段`() {
        val workspace = ws()
        addTodo(workspace, "一句话摘要", 1_000L)
        val json = okJson(TodoListTool.run(workspace))
        assertEquals("待办 1 · 在做 0", json.getString("header"))
        val item = json.getJSONArray("items").getJSONObject(0)
        assertEquals("一句话摘要", item.getString("summary"))
        listOf("source", "sourceId", "text", "body").forEach {
            assertFalse("行里不许带 $it（判据②：只有摘要）", item.has(it))
        }
        assertTrue("摘要不许超 40 字", item.getString("summary").length <= TodoRules.MAX_SUMMARY_CHARS)
    }

    @Test
    fun `同摘要不重复立_回既有那条`() {
        val workspace = ws()
        val first = okJson(TodoAddTool.run(JSONObject().put("text", "同一件事"), workspace, 1_000L))
        val second = okJson(TodoAddTool.run(JSONObject().put("text", " 同一件事 "), workspace, 2_000L))
        assertEquals("同一件事不该立两条", first.getString("id"), second.getString("id"))
        assertFalse("第二次是复用，不是新建", second.getBoolean("created"))
        assertEquals("账本里只有一条 add 行", 1, TodoStore.rows(workspace).count { it.op == TodoOp.ADD })
    }

    @Test
    fun `账本坏行跳过不崩_前面几行照读`() {
        val workspace = ws()
        addTodo(workspace, "先立一条", 1_000L)
        TodoStore.fileOf(workspace).appendText("{这行是半截 JSON\n", Charsets.UTF_8)
        assertEquals("坏行只丢它自己，前面的行照读", 1, TodoRules.live(TodoStore.items(workspace)).size)
    }
}
