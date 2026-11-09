// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百六十五批 · 治理中心「待办」块的判据（Compose 树 JVM 测不了，按本仓既有取舍分两半验）：
//   行为半（纯函数/真落盘）：收起展开状态机、三字段行、右相未 PASS 不进「已完成」、
//   点确认后看板消失而账本历史一条不少；
//   源码半（锚点断言）：折叠块里只有两个可点口（表头 ＋ 确认）、不许出现来源/正文/右相理由。
//
// 判据逐条（派工单 §判据）：
//   ② 收起/展开可切换；一次只展开一块；
//   ③ 行只含摘要＋状态签，不含正文/来源；
//   ④ 右相未 PASS 不许出现「已完成」（负例）；
//   ⑤ 点「确认」⇒ 看板消失、账本那一条的历史行一条不少。
//
// ⚠️ 判据⑤的一处口径差异（如实记）：派工单写「账本行数不变」，而 541 的账本是 append-only、
// 删除要记「何时删/谁删」（541 派工单原文「账本保留一行（谁建/谁做/谁验/何时删）」）⇒
// 点确认后总数 **+1 行 confirm**，该条原有的 add/done/verify 三行**一行不少**。
// 断言的写法照 541 的方向：**只许增不许减**，且原有行逐行仍在。
package com.example.zhengdao.ui.baopu

import com.example.zhengdao.baopu.todo.TodoActor
import com.example.zhengdao.baopu.todo.TodoItem
import com.example.zhengdao.baopu.todo.TodoOp
import com.example.zhengdao.baopu.todo.TodoRules
import com.example.zhengdao.baopu.todo.TodoState
import com.example.zhengdao.baopu.todo.TodoStore
import com.example.zhengdao.baopu.todo.TodoWrite
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TodoBoardTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private var seq = 0

    private fun ws(): File = tmp.newFolder("ws${seq++}")

    /** 造一条：立起来 → 标已做 → 右相给某个结论。返回 id。 */
    private fun seed(workspace: File, text: String, verdict: String): String {
        val added = TodoStore.add(workspace, text, "缺口-565", 1_000L)
        val id = (added as TodoWrite.Ok).item.id
        TodoStore.append(workspace, TodoStore.row(TodoOp.DONE, id, TodoActor.MODEL, 2_000L))
        TodoStore.append(
            workspace,
            TodoStore.row(TodoOp.VERIFY, id, TodoActor.RIGHT_MINISTER, 3_000L, verdict = verdict, reason = "理由"),
        )
        return id
    }

    private fun rows(workspace: File): List<TodoRowView> =
        TodoBoardRules.rows(TodoStore.items(workspace))

    // ── ② 收起/展开：一次只展开一块 ─────────────────────────────────────────

    @Test
    fun `判据②_点开_再点收回_两块不会同开`() {
        // 点开
        assertEquals("点开一块 ⇒ 它是展开的", "todo", TodoBoardRules.toggle(null, "todo"))
        // 再点收回
        assertNull("再点同一块 ⇒ 收回", TodoBoardRules.toggle("todo", "todo"))
        // 点另一块 ⇒ 换它（原来那块自动收回）——"一次只展开一块"就在这一条上
        assertEquals("点另一块 ⇒ 换成它", "board", TodoBoardRules.toggle("todo", "board"))
        assertEquals("反过来也一样", "todo", TodoBoardRules.toggle("board", "todo"))
        // 恒不可能出现"两块同时展开"：返回值只有一个键
        assertTrue("返回值只能是单键或 null", TodoBoardRules.toggle("todo", "board")?.contains(',') != true)
    }

    @Test
    fun `判据②_收起态那一行由541的算法给_不重写`() {
        val workspace = ws()
        seed(workspace, "第一件事", TodoRules.VERDICT_PASS)
        seed(workspace, "第二件事", TodoRules.VERDICT_FAIL)
        val counts = TodoRules.counts(TodoStore.items(workspace))
        assertEquals("待办 0 · 在做 1", TodoBoardRules.header(counts))
        assertEquals("本层只是转发 541 的算法", TodoRules.headerLine(counts), TodoBoardRules.header(counts))
    }

    // ── ③ 行只有摘要 + 状态 ──────────────────────────────────────────────────

    @Test
    fun `判据③_行类型里只有三个字段_想多画也拿不到`() {
        val fields = TodoRowView::class.java.declaredFields
            .filterNot { it.isSynthetic || it.name.startsWith('$') }
            .map { it.name }
            .toSet()
        assertEquals("行的字段只许 id / 摘要 / 状态（判据③）", setOf("id", "summary", "state"), fields)
    }

    @Test
    fun `判据③_行的摘要就是那一句话_状态签三值`() {
        val workspace = ws()
        seed(workspace, "待办一句话", TodoRules.VERDICT_PASS)
        val row = rows(workspace).single()
        assertEquals("待办一句话", row.summary)
        assertEquals(TodoState.COMPLETE, row.state)
        assertTrue("摘要不许超过 40 字", TodoRules.charCount(row.summary) <= TodoRules.MAX_SUMMARY_CHARS)
    }

    // ── ④ 右相未 PASS 不许出现「已完成」──────────────────────────────────────

    @Test
    fun `判据④_右相FAIL与UNVERIFIABLE都停在已做_且点不了确认`() {
        val workspace = ws()
        val failId = seed(workspace, "被打回的一件", TodoRules.VERDICT_FAIL)
        val unvId = seed(workspace, "判不了的一件", TodoRules.VERDICT_UNVERIFIABLE)
        val views = rows(workspace).associateBy { it.id }
        listOf(failId, unvId).forEach { id ->
            val view = views[id] ?: throw AssertionError("看板上少了 $id")
            assertEquals("右相没 PASS ⇒ 只能是「已做」，绝不许出现「已完成」", TodoState.MODEL_DONE, view.state)
            assertFalse("没到「已完成」就不许有确认按钮（判据④/⑤）", view.canConfirm)
        }
    }

    @Test
    fun `判据④_右相PASS才是已完成_而且只有它能点确认`() {
        val workspace = ws()
        val passId = seed(workspace, "通过的一件", TodoRules.VERDICT_PASS)
        val view = rows(workspace).single()
        assertEquals(passId, view.id)
        assertEquals(TodoState.COMPLETE, view.state)
        assertTrue("只有「已完成」能点确认", view.canConfirm)
    }

    // ── ⑤ 点确认：看板消失、账本历史一条不少 ────────────────────────────────

    @Test
    fun `判据⑤_点确认之后看板上没有它_账本原有行一条不少`() {
        val workspace = ws()
        val id = seed(workspace, "要销账的一件", TodoRules.VERDICT_PASS)
        val before = TodoStore.rows(workspace)
        assertEquals("先确认它真在账上", id, before.last().id)

        // 界面那一下调的就是这个（actor 固定为 USER）
        val out = TodoStore.confirm(workspace, id, TodoActor.USER, 9_000L)
        assertTrue("用户点确认该成功：$out", out is TodoWrite.Ok)

        // ① 看板上没有它了
        assertTrue("确认后看板上必须没有它", rows(workspace).none { it.id == id })
        // ② 账本原有行逐行还在（一条不少），只多出一行 confirm
        val after = TodoStore.rows(workspace)
        before.forEachIndexed { i, old ->
            assertEquals("账本第 ${i + 1} 行不许被改写或删掉", old, after[i])
        }
        assertTrue("只许增不许减（+1 行 confirm）", after.size >= before.size)
        assertEquals("恰好只多一行", before.size + 1, after.size)
        assertEquals("最后一行是用户那条确认", TodoOp.CONFIRM, after.last().op)
        assertEquals(TodoActor.USER, after.last().who)
    }

    @Test
    fun `判据⑤_非用户角色点确认_看板上它还在`() {
        val workspace = ws()
        val id = seed(workspace, "模型删不掉的一件", TodoRules.VERDICT_PASS)
        val before = TodoStore.rows(workspace).size
        listOf(TodoActor.MODEL, TodoActor.RIGHT_MINISTER).forEach { actor ->
            val out = TodoStore.confirm(workspace, id, actor, 9_000L)
            assertTrue("$actor 不许删：$out", out is TodoWrite.Refused)
        }
        assertEquals("被拒两次 ⇒ 账本一行都不许多", before, TodoStore.rows(workspace).size)
        assertEquals("看板上它还在", 1, rows(workspace).size)
    }

    @Test
    fun `判据⑤_未到已完成点确认也拒_条目留在看板`() {
        val workspace = ws()
        val id = seed(workspace, "还没过关的一件", TodoRules.VERDICT_FAIL)
        val out = TodoStore.confirm(workspace, id, TodoActor.USER, 9_000L)
        assertTrue("未到「已完成」不许删：$out", out is TodoWrite.Refused)
        assertEquals("看板上它还在（已做）", TodoState.MODEL_DONE, rows(workspace).single().state)
    }

    // ── 源码半：折叠块里只有两个可点口，行里没有来源/正文 ────────────────────

    /** 读一份源码（本仓单测的工作目录是 app/，也兼容仓库根跑法）。 */
    private fun sourceOf(rel: String): String {
        val candidates = listOf(
            "src/main/java/com/example/zhengdao/$rel",
            "../app/src/main/java/com/example/zhengdao/$rel",
            "app/src/main/java/com/example/zhengdao/$rel",
        )
        val f = candidates.map(::File).firstOrNull(File::isFile)
            ?: throw AssertionError("找不到 $rel（工作目录 ${File(".").absolutePath}）")
        // 去行注释再扫：注释里写"我们不碰什么"是好事，不该被自己的门禁判红
        return f.readText(Charsets.UTF_8).split("\n").joinToString("\n") { it.substringBefore("//") }
    }

    private fun screenSource(): String = sourceOf("ui/baopu/GovernanceCenterScreen.kt")

    /** 折叠块那一段源码（锚点：TodoSection 起、todoStateLabel 止；行号会漂，函数名不会）。 */
    private fun todoBlock(screen: String): String {
        val atSection = screen.indexOf("private fun TodoSection")
        val atLabel = screen.indexOf("private fun todoStateLabel")
        assertTrue("锚点丢了（待办块结构变了要回来重新钉）：$atSection", atSection >= 0)
        assertTrue("锚点丢了（状态签翻译函数换了名字）：$atLabel", atLabel > atSection)
        return screen.substring(atSection, atLabel)
    }

    @Test
    fun `判据③_待办块不画来源与正文_也不画右相理由`() {
        val block = todoBlock(screenSource())
        listOf("sourceId", "verdictReason", "statusLine", "item.text").forEach { leak ->
            assertFalse("行里不许出现 $leak（判据③：只有摘要＋状态签）", block.contains(leak))
        }
        assertTrue("摘要只从 row.summary 走", block.contains("row.summary"))
    }

    @Test
    fun `判据②⑤_待办块只有两个可点口_表头与确认`() {
        val block = todoBlock(screenSource())
        assertEquals(
            "折叠块里只许两个 onClick（表头切换 ＋ 确认）；多了就是又开了一个口子",
            2,
            Regex("onClick\\s*=").findAll(block).count(),
        )
        assertTrue("表头那一行要能点（收起/展开）", block.contains(".clickable(onClick = onToggle)"))
        assertTrue("确认按钮要回传 id", block.contains("onConfirm(row.id)"))
        assertEquals(
            "「确认」文案只许一处（同一个动作别配两个名字）",
            1,
            Regex("R\\.string\\.gc_todo_confirm\\b").findAll(block).count(),
        )
        // 本块不许出现决策中心那一族动作字（护栏与 539 同口径）
        listOf("批", "驳", "搁置", "回滚").forEach { word ->
            assertFalse("待办块不许出现动作字「$word」", block.contains(word))
        }
    }

    @Test
    fun `判据④_只有已完成那一个分支画确认按钮`() {
        val block = todoBlock(screenSource())
        assertTrue("按钮的闸是 canConfirm", block.contains("if (row.canConfirm)"))
        // canConfirm 的来源只有一条：Completed（TodoBoardRules 里那一行）
        val rules = sourceOf("ui/baopu/TodoBoardRules.kt")
        assertEquals(
            "canConfirm 只许有 state == COMPLETE 这一条判断",
            1,
            Regex("state == TodoState\\.COMPLETE").findAll(rules).count(),
        )
    }
}
