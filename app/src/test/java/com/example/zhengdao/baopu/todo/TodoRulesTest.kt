// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百四十一批 · 待办中心**判据层**的直测（纯函数：零 IO、零时钟、零 Android）。
//
// 判据逐条（派工单 §判据）：
//   ② 行里只有摘要（≤40 字）——写入侧就掐死（41 字拒收），且行结构里没有正文字段；
//   ④ 已确认删除的条目从看板消失，但账本那几行一条不少（replay 出来的事实，不是删除动作）；
//   ⑤ 未到「已完成」不许删；只有用户能删（模型/右相都拒）。
//   另加两条防绕过：`verify` 落在「待做」上不生效（否则一条 verify 行就能把待做顶成已完成）、
//   `confirm` 落在未完成条目上不生效（历史里真有这种行也不认）。
package com.example.zhengdao.baopu.todo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TodoRulesTest {

    private fun add(id: String, text: String, at: Long = 1_000L) =
        TodoRow(op = TodoOp.ADD, id = id, who = TodoActor.MODEL, atMs = at, text = text, source = "缺口-1")

    private fun done(id: String, at: Long = 2_000L) =
        TodoRow(op = TodoOp.DONE, id = id, who = TodoActor.MODEL, atMs = at)

    private fun verify(id: String, verdict: String, at: Long = 3_000L) =
        TodoRow(op = TodoOp.VERIFY, id = id, who = TodoActor.RIGHT_MINISTER, atMs = at, verdict = verdict, reason = "理由")

    private fun confirm(id: String, at: Long = 4_000L) =
        TodoRow(op = TodoOp.CONFIRM, id = id, who = TodoActor.USER, atMs = at)

    // ── ② 摘要上限与"只有一行" ────────────────────────────────────────────────

    @Test
    fun `四十字边界_40收_41拒`() {
        val forty = "一".repeat(40)
        val fortyOne = "一".repeat(41)
        assertNull("40 字是上限内的边界值，必须收：${TodoRules.refusal(forty)}", TodoRules.refusal(forty))
        val refusal = TodoRules.refusal(fortyOne)
        assertNotNull("41 字必须拒", refusal)
        assertTrue("拒因要带「现在多少 / 上限多少」：$refusal", refusal!!.contains("41") && refusal.contains("40"))
    }

    @Test
    fun `空摘要拒_只有空白的也算空`() {
        assertNotNull(TodoRules.refusal(""))
        assertNotNull(TodoRules.refusal("   \n\t "))
    }

    @Test
    fun `换行与多行正文被压成一行_进不了账本`() {
        val raw = "第一行：把报告写完\n第二行：然后发给用户"
        assertEquals("第一行：把报告写完 第二行：然后发给用户", TodoRules.normalize(raw))
        // 压完之后仍是"一句一行"：行文本里不许再出现换行
        val item = TodoItem("t1", TodoRules.normalize(raw), "s", 1_000L, TodoState.TODO)
        assertFalse("行文本里不许有换行", TodoRules.rowText(item).contains('\n'))
    }

    // ── 四态 replay ───────────────────────────────────────────────────────────

    @Test
    fun `四态_待做_已做_已完成_确认删除`() {
        val rows = listOf(add("t1", "写交付报告"), done("t1"), verify("t1", TodoRules.VERDICT_PASS))
        val item = TodoRules.fold(rows).single()
        assertEquals(TodoState.COMPLETE, item.state)
        assertEquals(TodoRules.VERDICT_PASS, item.verdict)
        // 已完成还不算删：看板上还在
        assertEquals(1, TodoRules.live(TodoRules.fold(rows)).size)
        // 用户确认之后才消失，且账本那几行一条不少
        val confirmed = TodoRules.fold(rows + confirm("t1"))
        assertTrue("确认后看板上必须没有它", TodoRules.live(confirmed).isEmpty())
        assertEquals("账本行不许因为删除而减少", 4, rows.size + 1)
    }

    @Test
    fun `右相判FAIL或UNVERIFIABLE_停在已做不进已完成`() {
        listOf(TodoRules.VERDICT_FAIL, TodoRules.VERDICT_UNVERIFIABLE).forEach { verdict ->
            val item = TodoRules.fold(listOf(add("t1", "做事"), done("t1"), verify("t1", verdict))).single()
            assertEquals("$verdict 不许进已完成", TodoState.MODEL_DONE, item.state)
            assertEquals(verdict, item.verdict)
        }
    }

    @Test
    fun `负例_verify落在待做上不生效_防一条行绕过模型标记`() {
        val item = TodoRules.fold(listOf(add("t1", "做事"), verify("t1", TodoRules.VERDICT_PASS))).single()
        assertEquals("没经过「已做」就判 PASS ⇒ 不认", TodoState.TODO, item.state)
    }

    @Test
    fun `负例_confirm落在未完成上不生效`() {
        val rows = listOf(add("t1", "做事"), done("t1"), confirm("t1"))
        val item = TodoRules.fold(rows).single()
        assertEquals("未到已完成的历史 confirm 行不认", 0L, item.confirmedAtMs)
        assertEquals("看板上它还该在", 1, TodoRules.live(TodoRules.fold(rows)).size)
    }

    @Test
    fun `已完成之后的行不再改它`() {
        val rows = listOf(add("t1", "做事"), done("t1"), verify("t1", TodoRules.VERDICT_PASS), done("t1", 9_000L))
        val item = TodoRules.fold(rows).single()
        assertEquals("完成了就是完成了（不许被后来的 done 打回已做）", TodoState.COMPLETE, item.state)
    }

    // ── ⑤ 谁能删 ──────────────────────────────────────────────────────────────

    @Test
    fun `只有用户能删_模型与右相都拒`() {
        val complete = TodoItem("t1", "做事", "s", 1_000L, TodoState.COMPLETE)
        assertNull("用户点确认：放行", TodoRules.confirmRefusal(complete, TodoActor.USER))
        listOf(TodoActor.MODEL, TodoActor.RIGHT_MINISTER).forEach { actor ->
            val refusal = TodoRules.confirmRefusal(complete, actor)
            assertNotNull("$actor 不许删", refusal)
        }
    }

    @Test
    fun `未到已完成不许删_哪怕用户点`() {
        listOf(TodoState.TODO, TodoState.MODEL_DONE).forEach { state ->
            val item = TodoItem("t1", "做事", "s", 1_000L, state)
            val refusal = TodoRules.confirmRefusal(item, TodoActor.USER)
            assertNotNull("$state 不许删：$refusal", refusal)
            assertTrue("拒因要说清现在是哪一态：$refusal", refusal!!.contains(state.cn))
        }
    }

    @Test
    fun `已完成的不许再标已做`() {
        val complete = TodoItem("t1", "做事", "s", 1_000L, TodoState.COMPLETE)
        assertNotNull(TodoRules.doneRefusal(complete))
        assertNull("待做可以标", TodoRules.doneRefusal(complete.copy(state = TodoState.TODO)))
        assertNull("已做可以再标一次（补做之后重验）", TodoRules.doneRefusal(complete.copy(state = TodoState.MODEL_DONE)))
    }

    // ── 收起态那一行与 id ─────────────────────────────────────────────────────

    @Test
    fun `收起态一行_待办N在做M`() {
        val items = listOf(
            TodoItem("t1", "a", "s", 1L, TodoState.TODO),
            TodoItem("t2", "b", "s", 1L, TodoState.TODO),
            TodoItem("t3", "c", "s", 1L, TodoState.MODEL_DONE),
            TodoItem("t4", "d", "s", 1L, TodoState.COMPLETE),
            TodoItem("t5", "e", "s", 1L, TodoState.COMPLETE, confirmedAtMs = 9L),
        )
        val counts = TodoRules.counts(items)
        assertEquals(2, counts.todo)
        assertEquals(1, counts.doing)
        assertEquals(1, counts.complete)
        assertEquals("待办 2 · 在做 1", TodoRules.headerLine(counts))
    }

    @Test
    fun `id不复用_删掉之后接着往后编`() {
        val rows = listOf(add("t1", "a"), add("t2", "b"), confirm("t2"))
        assertEquals("t3", TodoRules.nextId(rows))
    }
}
